package com.snapecg.reader

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTION_USB = "com.snapecg.reader.USB_PERMISSION"

/** Grava direto em arquivo CSV (nada fica so na memoria). Comentarios do cabecalho comecam com '#'. */
class Recorder(val file: File, private val fs: Int, gain: Int, uvPerCount: Double) {
    private val w = BufferedWriter(FileWriter(file))
    private var closed = false
    var samples = 0L
        private set
    val seconds: Double get() = samples.toDouble() / fs

    init {
        w.write("# SnapECG Reader\n# inicio=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}\n")
        w.write("# taxa_hz=$fs\n# ganho=$gain\n# uv_por_contagem=$uvPerCount\n")
        w.write("# filtro=mediana5 + passa-alta 0.5 Hz + notch 60 Hz + passa-baixa 40 Hz\n")
        w.write("t_s,raw,uv_raw,uv_filtrado,eletrodo_solto,marcapasso,qrs\n")
    }

    @Synchronized
    fun add(raw: Int, uvRaw: Double, uvF: Double, leadOff: Boolean, pace: Boolean, qrs: Boolean) {
        if (closed) return
        w.write(String.format(Locale.US, "%.4f,%d,%.2f,%.2f,%d,%d,%d\n",
            samples.toDouble() / fs, raw, uvRaw, uvF,
            if (leadOff) 1 else 0, if (pace) 1 else 0, if (qrs) 1 else 0))
        samples++
    }

    @Synchronized
    fun close() { if (!closed) { closed = true; w.flush(); w.close() } }
}

class Pipe(val info: DeviceInfo) {
    val filter = EcgFilter(info.rateHz)
    val qrs = QrsDetector(info.rateHz)
    @Volatile var leadOff = false
}

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var ecg: EcgView
    private lateinit var statusTv: TextView
    private lateinit var hrTv: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRec: Button
    private lateinit var btnShare: Button
    private val handler = Handler(Looper.getMainLooper())

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var splitter: FrameSplitter? = null
    @Volatile private var info: DeviceInfo? = null
    @Volatile private var pipe: Pipe? = null
    @Volatile private var recorder: Recorder? = null
    private var lastFile: File? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_USB ->
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openDevice()
                    else status("Permissao USB negada.")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect("Aparelho desconectado.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val f = IntentFilter(ACTION_USB).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, f)
        handler.post(tick)
        handler.post { connect() }
    }

    override fun onDestroy() {
        disconnect("")
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 18, 20))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        hrTv = TextView(this).apply { text = "-- bpm"; textSize = 44f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        statusTv = TextView(this).apply { text = "Conecte o aparelho por cabo OTG."; textSize = 14f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
        ecg = EcgView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, on: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { on() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnConnect = btn("Conectar") { if (port == null) connect() else disconnect("Desconectado.") }
        btnRec = btn("Gravar") { toggleRecording() }
        btnShare = btn("Exportar") { share() }
        btnRec.isEnabled = false; btnShare.isEnabled = false
        row.addView(btnConnect); row.addView(btnRec); row.addView(btnShare)
        root.addView(hrTv)
        root.addView(statusTv)
        root.addView(ecg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        root.addView(row)
        setContentView(root)
    }

    private fun status(s: String) = runOnUiThread { statusTv.text = s }

    /** Atualiza BPM e estado a cada 0,5 s. */
    private val tick = object : Runnable {
        override fun run() {
            val p = pipe
            if (p != null) {
                hrTv.text = if (p.leadOff || p.qrs.bpm <= 0) "-- bpm" else "${p.qrs.bpm} bpm"
                val r = recorder
                statusTv.text = when {
                    p.leadOff -> "Eletrodo solto - encoste os dedos/eletrodos"
                    r != null -> String.format(Locale.US, "GRAVANDO  %.0f s  (%d amostras)", r.seconds, r.samples)
                    else -> "${p.info.rateHz} Hz  |  ganho ${p.info.gain}  |  ao vivo"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- USB
    private fun connect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        if (drivers.isEmpty()) { status("Nenhum aparelho USB encontrado. Use cabo OTG."); return }
        val dev = drivers[0].device
        if (usb.hasPermission(dev)) {
            openDevice()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun openDevice() {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull() ?: return
        val conn = usb.openDevice(driver.device) ?: run { status("Sem permissao para abrir o USB."); return }
        val p = driver.ports[0]
        try {
            p.open(conn)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) { status("Erro ao abrir a porta: ${e.message}"); return }
        port = p
        info = null
        val sp = FrameSplitter { onFrame(it) }
        splitter = sp
        io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = sp.feed(data)
            override fun onRunError(e: Exception) { runOnUiThread { disconnect("Erro de leitura: ${e.message}") } }
        }).also { it.start() }
        btnConnect.text = "Desconectar"
        status("Conectado. Lendo informacoes do aparelho...")
        // mesma sequencia do app oficial: parar -> versao -> info -> hora -> filtro -> iniciar
        send(cmdStop())
        handler.postDelayed({ send(makeCmd(C_GET_VERSION)) }, 300)
        handler.postDelayed({ send(makeCmd(C_DEVICE_INFO)) }, 600)
        handler.postDelayed({ startStream() }, 1500)
    }

    private fun startStream() {
        val inf = info
        if (inf == null) { disconnect("Sem resposta do aparelho (confira o cabo e se ele esta ligado)."); return }
        ecg.configure(inf.rateHz)
        pipe = Pipe(inf)
        send(cmdSetTime())
        handler.postDelayed({ send(makeCmd(C_FILTER_0_5HZ, byteArrayOf(1))) }, 100)
        handler.postDelayed({ send(cmdStart()) }, 200)
        btnRec.isEnabled = true
    }

    private fun send(b: ByteArray) {
        try { port?.write(b, 1000) } catch (_: Exception) { }
    }

    private fun disconnect(msg: String) {
        stopRecording()
        handler.removeCallbacksAndMessages(null)   // cancela handshake pendente
        handler.post(tick)
        if (port != null) send(cmdStop())
        io?.stop(); io = null
        try { port?.close() } catch (_: Exception) { }
        port = null; pipe = null; info = null
        if (this::btnConnect.isInitialized) {
            btnConnect.text = "Conectar"; btnRec.isEnabled = false
            hrTv.text = "-- bpm"
            if (msg.isNotEmpty()) statusTv.text = msg
        }
    }

    // ------------------------------------------------------------- dados (thread de IO)
    private fun onFrame(f: Frame) {
        if (f.type == C_DEVICE_INFO) { parseInfo(f.data)?.let { info = it }; return }
        val n = ECG_SAMPLES[f.type] ?: return
        if (f.data.size == 2 * n) handleSamples(f.data, n)
    }

    private fun handleSamples(d: ByteArray, n: Int) {
        val p = pipe ?: return
        val ch = if (p.info.channels > 1 && n % p.info.channels == 0) p.info.channels else 1
        val pick = if (ch > 1) 1 else 0           // multicanal: mostra o canal 2 (derivacao II) - NAO verificado
        var leadOff = false
        for (k in 0 until n) {
            val hi = d[2 * k].toInt() and 0xFF
            val lo = d[2 * k + 1].toInt() and 0xFF
            if (hi and 0x20 != 0) leadOff = true
            if (k % ch != pick) continue
            val raw = (((hi and 0x1F) shl 7) or (lo and 0x7F)) - 2048
            val uv = raw * p.info.uvPerCount
            val y = p.filter.process(uv)
            val beat = p.qrs.push(y)
            ecg.push(y.toFloat())
            recorder?.add(raw, uv, y, hi and 0x20 != 0, hi and 0x40 != 0, beat)
        }
        p.leadOff = leadOff
        ecg.refresh()
    }

    // ------------------------------------------------------- gravar / exportar
    private fun toggleRecording() {
        if (recorder == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val p = pipe ?: return
        val dir = getExternalFilesDir(null) ?: filesDir
        val name = "ecg_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        recorder = Recorder(File(dir, name), p.info.rateHz, p.info.gain, p.info.uvPerCount)
        btnRec.text = "Parar gravacao"
        btnShare.isEnabled = false
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        r.close()
        lastFile = r.file
        if (this::btnRec.isInitialized) {
            btnRec.text = "Gravar"
            btnShare.isEnabled = true
            statusTv.text = String.format(Locale.US, "Gravado: %.1f s em %s", r.seconds, r.file.name)
        }
    }

    private fun share() {
        val f = lastFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Exportar gravacao"))
    }
}
