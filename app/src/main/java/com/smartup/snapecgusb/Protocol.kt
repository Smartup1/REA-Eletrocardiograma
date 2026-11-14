package com.smartup.snapecgusb

import java.util.Calendar

/**
 * Protocolo do monitor SnapECG (mHealth365), portado de snapecg_client.py.
 * Quadro: 0xFF | L | TIPO | dados... | CHK
 *  - L   = len(dados) + 2 (bytes de L até o último dado)
 *  - CHK = soma (mod 256) dos L bytes a partir de L; se der 0xFF vira 0xFE
 *  - nenhum byte depois do 0xFF vale 0xFF
 */
object Proto {
    const val HEAD = 0xFF

    // tipos device -> app
    const val T_ECG1 = 0
    const val T_ECG3 = 1
    const val T_ECG8 = 2
    const val T_BAT = 3
    const val T_BAT_TEMP = 4
    const val T_ECG1_LONG = 8
    const val T_ECG4 = 12
    const val T_ECG8B = 15

    val ECG_SAMPLES = mapOf(T_ECG1 to 1, T_ECG3 to 3, T_ECG8 to 8, T_ECG1_LONG to 100, T_ECG4 to 4, T_ECG8B to 8)

    // comandos app -> device (a resposta usa o mesmo id)
    const val C_START_STOP = 0x10
    const val C_GET_VERSION = 0x12
    const val C_DEVICE_INFO = 0x13
    const val C_SET_TIME = 0x16
    const val C_FILTER_0_5HZ = 0x1D

    val SAMPLE_RATES = mapOf(0 to 100, 1 to 128, 2 to 200, 3 to 256, 4 to 500, 5 to 512)
    val GAINS = mapOf(0 to 200, 1 to 250, 2 to 400, 3 to 500)
    val LEAD_TYPES = mapOf(
        0 to listOf("ECG"), 1 to listOf("I", "II", "V5"),
        2 to listOf("I", "II", "V1", "V2", "V3", "V4", "V5", "V6"),
        3 to listOf("ECG"), 4 to listOf("I", "II", "V1", "V5"), 5 to listOf("ECG"), 6 to listOf("ECG"),
        7 to listOf("I", "II"), 8 to listOf("I", "II", "V1", "V5"),
        9 to listOf("I", "II", "V1", "V2", "V3", "V4", "V5", "V6"), 10 to listOf("ECG")
    )

    fun checksum(body: ByteArray): Int {
        var c = 0
        for (b in body) c += b.toInt() and 0xFF
        c = c and 0xFF
        return if (c == 0xFF) 0xFE else c
    }

    fun makeCmd(id: Int, payload: ByteArray = byteArrayOf(0)): ByteArray {
        val body = ByteArray(payload.size + 2)
        body[0] = (payload.size + 2).toByte()
        body[1] = id.toByte()
        payload.copyInto(body, 2)
        val out = ByteArray(body.size + 2)
        out[0] = 0xFF.toByte()
        body.copyInto(out, 1)
        out[out.size - 1] = checksum(body).toByte()
        return out
    }

    fun cmdStart() = makeCmd(C_START_STOP, byteArrayOf(1))
    fun cmdStop() = makeCmd(C_START_STOP, byteArrayOf(0))
    fun cmdGetVersion() = makeCmd(C_GET_VERSION)
    fun cmdDeviceInfo() = makeCmd(C_DEVICE_INFO)
    fun cmdFilter05Hz(open: Boolean) = makeCmd(C_FILTER_0_5HZ, byteArrayOf(if (open) 0 else 1))
    fun cmdSetTime(): ByteArray {
        val c = Calendar.getInstance()
        return makeCmd(
            C_SET_TIME, byteArrayOf(
                (c.get(Calendar.YEAR) - 2000).toByte(), (c.get(Calendar.MONTH) + 1).toByte(),
                c.get(Calendar.DAY_OF_MONTH).toByte(), c.get(Calendar.HOUR_OF_DAY).toByte(),
                c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte()
            )
        )
    }

    fun decodeSample(hi: Int, lo: Int): Int = (((hi and 0x1F) shl 7) or (lo and 0x7F)) - 2048
}

class Frame(val type: Int, val data: ByteArray)

/** Remonta quadros a partir de pedaços de bytes (leituras seriais podem cortar/juntar quadros). */
class FrameSplitter {
    private var buf = ByteArray(2048)
    private var len = 0
    var dropped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int, out: (Frame) -> Unit) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(chunk, 0, buf, len, n)
        len += n
        while (true) {
            var i = -1
            for (k in 0 until len) if (buf[k] == 0xFF.toByte()) { i = k; break }
            if (i < 0) { dropped += len; len = 0; return }
            if (i > 0) { dropped += i; drop(i) }
            if (len < 3) return
            val l = buf[1].toInt() and 0xFF
            if (l < 2 || l == 0xFF) { drop(1); dropped++; continue }
            if (len < l + 2) return
            var hasFF = false
            var sum = 0
            for (k in 1..l) {
                val v = buf[k].toInt() and 0xFF
                if (v == 0xFF) hasFF = true
                sum += v
            }
            var c = sum and 0xFF
            if (c == 0xFF) c = 0xFE
            val chk = buf[1 + l].toInt() and 0xFF
            if (!hasFF && c == chk) {
                out(Frame(buf[2].toInt() and 0xFF, buf.copyOfRange(3, 1 + l)))
                drop(l + 2)
            } else {
                drop(1); dropped++ // ressincroniza
            }
        }
    }

    private fun drop(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }
}

class EcgBlock(val samples: IntArray, val leadOff: Boolean, val pacemaker: Boolean)

class DeviceInfo(
    val leadType: Int, val sampleRateHz: Int, val gain: Int,
    val channels: List<String>
) {
    val uvPerCount: Double get() = 3_000_000.0 / 4096.0 / gain
}

fun parseEcg(f: Frame): EcgBlock? {
    val n = Proto.ECG_SAMPLES[f.type] ?: return null
    if (f.data.size != 2 * n) return null
    val d = f.data
    var leadOff = false
    val s = IntArray(n) { k ->
        val hi = d[2 * k].toInt() and 0xFF
        val lo = d[2 * k + 1].toInt() and 0xFF
        if ((hi shr 5) and 1 == 1) leadOff = true
        Proto.decodeSample(hi, lo)
    }
    val pace = ((d[0].toInt() and 0xFF) shr 6) and 1 == 1
    return EcgBlock(s, leadOff, pace)
}

fun parseDeviceInfo(f: Frame): DeviceInfo? {
    if (f.type != Proto.C_DEVICE_INFO || f.data.size != 5) return null
    val lead = f.data[0].toInt() and 0xFF
    val rate = f.data[1].toInt() and 0xFF
    val gain = f.data[2].toInt() and 0xFF
    val r = Proto.SAMPLE_RATES[rate] ?: return null
    val g = Proto.GAINS[gain] ?: return null
    val ch = Proto.LEAD_TYPES[lead] ?: return null
    return DeviceInfo(lead, r, g, ch)
}

/** Retorna a bateria (0..100?) se o quadro for de bateria, senão null. */
fun parseBattery(f: Frame): Int? {
    if ((f.type == Proto.T_BAT && f.data.size == 1) || (f.type == Proto.T_BAT_TEMP && f.data.size == 3))
        return f.data[0].toInt() and 0xFF
    return null
}
