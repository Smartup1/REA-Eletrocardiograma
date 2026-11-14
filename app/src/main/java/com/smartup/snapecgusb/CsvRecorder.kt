package com.smartup.snapecgusb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grava t_s,uv,lead_off,pacemaker em Download/SnapEcgUsb/ (MediaStore, sem permissão extra). */
class CsvRecorder(private val ctx: Context, private val fs: Int) {
    private var uri: Uri? = null
    private var w: BufferedWriter? = null
    private var n = 0L
    var name = ""
        private set

    fun start(): Boolean {
        name = "ecg_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SnapEcgUsb")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        val os = ctx.contentResolver.openOutputStream(u) ?: return false
        uri = u
        w = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8), 1 shl 16).also { it.write("t_s,uv,lead_off,pacemaker\n") }
        return true
    }

    fun add(uv: Float, leadOff: Boolean, pacemaker: Boolean) {
        val wr = w ?: return
        val t = n.toDouble() / fs
        wr.write(String.format(Locale.US, "%.4f,%.2f,%d,%d\n", t, uv, if (leadOff) 1 else 0, if (pacemaker) 1 else 0))
        n++
    }

    fun stop(): String? {
        val wr = w ?: return null
        try { wr.flush(); wr.close() } catch (_: Exception) {}
        w = null
        uri?.let {
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(it, v, null, null)
        }
        return "Download/SnapEcgUsb/$name ($n amostras)"
    }
}
