package com.smartup.snapecgusb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/** Conexão USB serial (FTDI FT231X, 115200 8N1) com o monitor de ECG. */
class UsbEcgConnection(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onLog(msg: String)
        fun onState(connected: Boolean)
        fun onFrame(f: Frame) // chamado na thread de leitura
    }

    companion object {
        private const val ACTION_PERM = "com.smartup.snapecgusb.USB_PERMISSION"
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var readThread: Thread? = null
    private val splitter = FrameSplitter()
    private var rawLogged = 0

    @Volatile
    private var running = false

    @Volatile
    var info: DeviceInfo? = null
        private set

    val isRunning: Boolean get() = running
    val droppedBytes: Long get() = splitter.dropped

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else cb.onLog("Permissão USB negada.")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (running) {
                        cb.onLog("Aparelho desconectado.")
                        disconnect()
                    }
                }
            }
        }
    }

    fun register() {
        val f = IntentFilter(ACTION_PERM)
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, f)
    }

    fun unregister() {
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun prober(): UsbSerialProber {
        val table = UsbSerialProber.getDefaultProbeTable()
        table.addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java)
        return UsbSerialProber(table)
    }

    @Synchronized
    fun connect() {
        if (running) { cb.onLog("Já conectado."); return }
        val drivers = prober().findAllDrivers(usb)
        if (drivers.isEmpty()) {
            val all = usb.deviceList.values
            if (all.isEmpty()) cb.onLog("Nenhum dispositivo USB visto pelo celular. Confira o cabo/adaptador OTG.")
            else all.forEach { cb.onLog("USB visto: VID=%04X PID=%04X (sem driver serial)".format(it.vendorId, it.productId)) }
            return
        }
        val d = drivers[0]
        if (!usb.hasPermission(d.device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERM).setPackage(ctx.packageName), flags)
            cb.onLog("Pedindo permissão USB...")
            usb.requestPermission(d.device, pi)
            return
        }
        openDriver(d)
    }

    private fun openDriver(d: UsbSerialDriver) {
        try {
            val conn = usb.openDevice(d.device)
            if (conn == null) { cb.onLog("Não consegui abrir o dispositivo USB."); return }
            val p = d.ports[0]
            p.open(conn)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
            try { p.purgeHwBuffers(true, true) } catch (_: Exception) {}
            port = p
            info = null
            rawLogged = 0
            running = true
            cb.onLog("USB aberto: %04X:%04X, 115200 8N1".format(d.device.vendorId, d.device.productId))
            cb.onState(true)
            startReader(p)
            startHandshake()
        } catch (e: Exception) {
            cb.onLog("Erro ao abrir USB: ${e.message}")
            try { port?.close() } catch (_: Exception) {}
            port = null
            running = false
        }
    }

    private fun startReader(p: UsbSerialPort) {
        readThread = Thread {
            val buf = ByteArray(4096)
            while (running) {
                try {
                    val n = p.read(buf, 200)
                    if (n > 0) {
                        if (rawLogged < 12) {
                            rawLogged++
                            val hex = buf.take(minOf(n, 24)).joinToString(" ") { "%02X".format(it) }
                            cb.onLog("RX[$n]: $hex")
                        }
                        splitter.feed(buf, n) { f ->
                            if (f.type == Proto.C_DEVICE_INFO) info = parseDeviceInfo(f) ?: info
                            cb.onFrame(f)
                        }
                    }
                } catch (e: Exception) {
                    if (running) cb.onLog("Erro de leitura: ${e.message}")
                    break
                }
            }
            if (running) { running = false }
            cb.onState(false)
        }.also { it.isDaemon = true; it.start() }
    }

    private fun startHandshake() {
        Thread {
            try {
                send(Proto.cmdGetVersion())
                Thread.sleep(150)
                send(Proto.cmdDeviceInfo())
                var t = 0
                while (info == null && t < 30 && running) { Thread.sleep(100); t++ }
                if (info == null) cb.onLog("Sem resposta de device-info; usando 200 Hz / ganho 200.")
                send(Proto.cmdFilter05Hz(false))
                send(Proto.cmdStart())
                cb.onLog("Comando START enviado.")
            } catch (e: Exception) {
                cb.onLog("Erro no handshake: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    private fun send(b: ByteArray) {
        port?.write(b, 1000)
    }

    @Synchronized
    fun disconnect() {
        val p = port ?: return
        try { if (running) send(Proto.cmdStop()) } catch (_: Exception) {}
        running = false
        try { readThread?.join(500) } catch (_: Exception) {}
        try { p.close() } catch (_: Exception) {}
        port = null
    }
}
