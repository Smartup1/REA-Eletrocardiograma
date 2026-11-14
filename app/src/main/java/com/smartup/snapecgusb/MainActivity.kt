package com.smartup.snapecgusb

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), UsbEcgConnection.Callback {

    private lateinit var conn: UsbEcgConnection
    private lateinit var ecgView: EcgView
    private lateinit var tvStatus: TextView
    private lateinit var tvBpm: TextView
    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var btnStop: Button
    private lateinit var cbRecord: CheckBox

    private val logLines = ArrayDeque<String>()
    private val seenTypes = HashSet<Int>()
    private var detector: BeatDetector? = null
    private var detectorFs = 0
    private var recorder: CsvRecorder? = null
    private var recordWanted = false
    private var leadOff = false
    private var battery: Int? = null
    private var lastBeatMs = 0L
    private var samples = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        conn = UsbEcgConnection(this, this)
        conn.register()
        log("Pronto. Ligue o ECG no cabo OTG e toque em Conectar.")
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) startSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) startSession()
    }

    override fun onDestroy() {
        conn.unregister()
        conn.disconnect()
        stopRecorder()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        tvStatus = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY); text = "Desconectado" }
        tvBpm = TextView(this).apply {
            textSize = 40f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(190, 30, 45)); text = "-- bpm"
        }
        ecgView = EcgView(this)

        btnConnect = Button(this).apply { text = "Conectar"; setOnClickListener { startSession() } }
        btnStop = Button(this).apply { text = "Parar"; isEnabled = false; setOnClickListener { stopSession() } }
        cbRecord = CheckBox(this).apply { text = "Gravar CSV"; isChecked = true }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnConnect, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btnStop, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(cbRecord, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f))
        }

        tvLog = TextView(this).apply { textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(Color.DKGRAY) }
        scroll = ScrollView(this).apply { addView(tvLog) }
        val disclaimer = TextView(this).apply {
            textSize = 11f; setTextColor(Color.GRAY)
            text = "Uso pessoal/experimental. Não é dispositivo médico validado; não use para decisão clínica."
        }

        root.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(tvBpm, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ecgView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(140)))
        root.addView(disclaimer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun log(msg: String) {
        runOnUiThread {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logLines.addLast("$t $msg")
            while (logLines.size > 60) logLines.removeFirst()
            tvLog.text = logLines.joinToString("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun updateStatus() {
        val di = conn.info
        val parts = ArrayList<String>()
        parts.add(if (conn.isRunning) "Conectado" else "Desconectado")
        if (di != null) parts.add("${di.sampleRateHz} Hz · %.2f µV/cont".format(Locale.US, di.uvPerCount))
        battery?.let { parts.add("bateria $it") }
        if (leadOff) parts.add("ELETRODO SOLTO")
        tvStatus.text = parts.joinToString(" · ")
        tvStatus.setTextColor(if (leadOff) Color.rgb(200, 0, 0) else Color.DKGRAY)
    }

    // ------------------------------------------------------------ sessão
    private fun startSession() {
        if (conn.isRunning) return
        ecgView.clear()
        detector = null
        samples = 0
        leadOff = false
        battery = null
        seenTypes.clear()
        recordWanted = cbRecord.isChecked
        tvBpm.text = "-- bpm"
        conn.connect()
    }

    private fun stopSession() {
        conn.disconnect()
        stopRecorder()
        btnConnect.isEnabled = true
        btnStop.isEnabled = false
        updateStatus()
    }

    private fun stopRecorder() {
        val saved = recorder?.stop()
        recorder = null
        if (saved != null) log("CSV salvo em $saved")
    }

    // ------------------------------------------------- callbacks da conexão
    override fun onLog(msg: String) = log(msg)

    override fun onState(connected: Boolean) {
        runOnUiThread {
            btnConnect.isEnabled = !connected
            btnStop.isEnabled = connected
            if (!connected) {
                stopRecorder()
                log("Descartados: ${conn.droppedBytes} bytes")
            }
            updateStatus()
        }
    }

    override fun onFrame(f: Frame) {
        runOnUiThread { handleFrame(f) }
    }

    private fun handleFrame(f: Frame) {
        val blk = parseEcg(f)
        if (blk != null) {
            val di = conn.info
            val fs = di?.sampleRateHz ?: 200
            val upc = di?.uvPerCount ?: (3_000_000.0 / 4096.0 / 200)
            ecgView.setRate(fs)
            if (detector == null || detectorFs != fs) { detector = BeatDetector(fs); detectorFs = fs }
            if (recordWanted && recorder == null) {
                val r = CsvRecorder(this, fs)
                if (r.start()) { recorder = r; log("Gravando ${r.name}") }
                else { recordWanted = false; log("Falha ao criar o CSV.") }
            }
            val now = System.currentTimeMillis()
            for (s in blk.samples) {
                val uv = (s * upc).toFloat()
                ecgView.push(uv)
                recorder?.add(uv, blk.leadOff, blk.pacemaker)
                if (!blk.leadOff && detector!!.push(uv)) {
                    lastBeatMs = now
                    detector!!.bpm.takeIf { it > 0 }?.let { tvBpm.text = "$it bpm" }
                }
            }
            samples += blk.samples.size
            if (now - lastBeatMs > 4000) tvBpm.text = "-- bpm"
            if (blk.leadOff != leadOff) { leadOff = blk.leadOff; if (leadOff) detector?.reset(); updateStatus() }
            ecgView.refresh()
            return
        }
        val di = parseDeviceInfo(f)
        if (di != null) {
            log("Aparelho: canais=${di.channels} ${di.sampleRateHz} Hz ganho=${di.gain}")
            updateStatus()
            return
        }
        parseBattery(f)?.let { battery = it; updateStatus(); return }
        if (seenTypes.add(f.type)) {
            log("Quadro tipo ${f.type} (${f.data.size} B): " + f.data.take(16).joinToString(" ") { "%02X".format(it) })
        }
    }
}
