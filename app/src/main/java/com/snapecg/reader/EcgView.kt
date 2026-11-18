package com.snapecg.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * ECG em escala de papel calibrada: o tamanho fisico na tela segue o padrao clinico
 * (25 mm/s e 10 mm/mV por padrao), usando o DPI real da tela. Grade de 1 mm e 5 mm.
 * Toque = muda o ganho (5/10/20 mm/mV). Toque longo = muda a velocidade (12,5/25/50 mm/s).
 */
class EcgView(ctx: Context) : View(ctx) {
    private var data = FloatArray(0)
    private var head = 0
    private var count = 0
    private var fs = 200
    private val speeds = floatArrayOf(12.5f, 25f, 50f)
    private val gains = floatArrayOf(5f, 10f, 20f)
    private var speedIdx = 1
    private var gainIdx = 1
    private val dm = resources.displayMetrics
    private val pxMmX = (if (dm.xdpi > 100f) dm.xdpi else 160f * dm.density) / 25.4f
    private val pxMmY = (if (dm.ydpi > 100f) dm.ydpi else 160f * dm.density) / 25.4f
    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 1.6f * dm.density
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val minor = Paint().apply { color = Color.rgb(247, 205, 205); strokeWidth = 1f }
    private val major = Paint().apply { color = Color.rgb(232, 150, 150); strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 60, 60); textSize = 12f * dm.density }

    init {
        setOnClickListener { gainIdx = (gainIdx + 1) % gains.size; invalidate() }
        setOnLongClickListener { speedIdx = (speedIdx + 1) % speeds.size; invalidate(); true }
    }

    @Synchronized
    fun configure(rateHz: Int) {
        fs = rateHz; data = FloatArray(rateHz * 15); head = 0; count = 0
        postInvalidate()
    }

    @Synchronized
    fun push(v: Float) {
        if (data.isEmpty()) return
        data[head] = v; head = (head + 1) % data.size
        if (count < data.size) count++
    }

    fun refresh() = postInvalidateOnAnimation()

    @Synchronized
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        val w = width.toFloat(); val h = height.toFloat()
        val cy = h / 2f
        // grade de papel: linhas a cada 1 mm, mais fortes a cada 5 mm; linha de base no centro
        var i = 0
        while (i * pxMmX <= w) { c.drawLine(i * pxMmX, 0f, i * pxMmX, h, if (i % 5 == 0) major else minor); i++ }
        val off = cy % (5f * pxMmY)
        i = 0
        while (off + i * pxMmY <= h) { val y = off + i * pxMmY; c.drawLine(0f, y, w, y, if (i % 5 == 0) major else minor); i++ }

        val label = "${speeds[speedIdx]} mm/s   ${gains[gainIdx]} mm/mV   (toque: ganho | segure: velocidade)"
        c.drawText(label, 12f, h - 12f, txt)
        if (count < 2) { c.drawText("aguardando sinal...", 12f, 12f + txt.textSize, txt); return }

        val pxPerSample = pxMmX * speeds[speedIdx] / fs
        val visible = ((w / pxPerSample).toInt() + 1).coerceIn(2, data.size)
        val n = minOf(count, visible)
        val first = (head - n + data.size) % data.size
        val pxPerUv = gains[gainIdx] * pxMmY / 1000f          // mm/mV * px/mm / (uV/mV)
        path.reset()
        for (k in 0 until n) {
            val v = data[(first + k) % data.size]
            val x = w - (n - 1 - k) * pxPerSample
            val y = cy - v * pxPerUv
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
    }
}
