package com.snapecg.reader

// Protocolo do SnapECG (obtido por analise estatica do app oficial).
// Quadro: 0xFF | L | TIPO | dados... | CHK   (CHK = soma mod 256 dos L bytes a partir de L; 0xFF vira 0xFE)

const val HEAD = 0xFF
const val C_START_STOP = 0x10
const val C_GET_VERSION = 0x12
const val C_DEVICE_INFO = 0x13
const val C_SET_TIME = 0x16
const val C_FILTER_0_5HZ = 0x1D

// tipo de pacote ECG -> numero de amostras
val ECG_SAMPLES = mapOf(0 to 1, 1 to 3, 2 to 8, 8 to 100, 12 to 4, 15 to 8)
val RATES = intArrayOf(100, 128, 200, 256, 500, 512)
val GAINS = intArrayOf(200, 250, 400, 500)
val LEAD_CHANNELS = intArrayOf(1, 3, 8, 1, 4, 1, 1, 2, 4, 8, 1)

class Frame(val type: Int, val data: ByteArray)

class DeviceInfo(val rateHz: Int, val gain: Int, val leadType: Int) {
    val uvPerCount: Double get() = 3_000_000.0 / 4096.0 / gain
    val channels: Int get() = LEAD_CHANNELS.getOrElse(leadType) { 1 }
}

fun checksum(b: ByteArray, from: Int, len: Int): Int {
    var s = 0
    for (i in from until from + len) s += b[i].toInt() and 0xFF
    s = s and 0xFF
    return if (s == 0xFF) 0xFE else s
}

fun makeCmd(id: Int, payload: ByteArray = byteArrayOf(0)): ByteArray {
    val body = ByteArray(payload.size + 2)
    body[0] = (payload.size + 2).toByte()
    body[1] = id.toByte()
    payload.copyInto(body, 2)
    val out = ByteArray(body.size + 2)
    out[0] = 0xFF.toByte()
    body.copyInto(out, 1)
    out[out.size - 1] = checksum(body, 0, body.size).toByte()
    return out
}

fun cmdStart() = makeCmd(C_START_STOP, byteArrayOf(1))
fun cmdStop() = makeCmd(C_START_STOP, byteArrayOf(0))
fun cmdSetTime(): ByteArray {
    val c = java.util.Calendar.getInstance()
    return makeCmd(
        C_SET_TIME,
        byteArrayOf(
            (c.get(java.util.Calendar.YEAR) - 2000).toByte(),
            (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(),
            c.get(java.util.Calendar.SECOND).toByte()
        )
    )
}

fun parseInfo(d: ByteArray): DeviceInfo? {
    if (d.size != 5) return null
    val lead = d[0].toInt() and 0xFF
    val r = d[1].toInt() and 0xFF
    val g = d[2].toInt() and 0xFF
    if (r !in RATES.indices || g !in GAINS.indices) return null
    return DeviceInfo(RATES[r], GAINS[g], lead)
}

/** Remonta quadros a partir dos bytes da serial (que chegam cortados ou juntos). */
class FrameSplitter(private val onFrame: (Frame) -> Unit) {
    private var buf = ByteArray(8192)
    private var n = 0

    fun feed(chunk: ByteArray) {
        if (n + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + chunk.size))
        chunk.copyInto(buf, n)
        n += chunk.size
        var i = 0
        while (i < n) {
            if ((buf[i].toInt() and 0xFF) != HEAD) { i++; continue }
            if (n - i < 3) break
            val len = buf[i + 1].toInt() and 0xFF
            if (len < 2 || len == 0xFF) { i++; continue }
            if (n - i < len + 2) break
            var ok = true
            for (k in 1..len) if ((buf[i + k].toInt() and 0xFF) == 0xFF) { ok = false; break }
            if (ok && checksum(buf, i + 1, len) == (buf[i + 1 + len].toInt() and 0xFF)) {
                onFrame(Frame(buf[i + 2].toInt() and 0xFF, buf.copyOfRange(i + 3, i + 1 + len)))
                i += len + 2
            } else {
                i++
            }
        }
        if (i > 0) { buf.copyInto(buf, 0, i, n); n -= i }
    }
}
