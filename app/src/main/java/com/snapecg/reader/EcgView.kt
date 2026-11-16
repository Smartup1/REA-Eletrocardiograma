package com.snapecg.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Grafico de ECG rolante (ultimos 6 s), com escala automatica. */
class EcgView(ctx: Context) : View(ctx) {
    private var data = FloatArray(0)
    private var head = 0
    private var count = 0
    private var fs = 200
    private var scale = 600f
    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val grid = Paint().apply { color = Color.rgb(40, 46, 50); strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 28f }

    @Synchronized
    fun configure(rateHz: Int) {
        fs = rateHz; data = FloatArray(rateHz * 6); head = 0; count = 0; scale = 600f
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
        c.drawColor(Color.BLACK)
        val w = width.toFloat(); val h = height.toFloat()
        for (s in 1..5) c.drawLine(w * s / 6f, 0f, w * s / 6f, h, grid)
        c.drawLine(0f, h / 2, w, h / 2, grid)
        if (count < 2) { c.drawText("aguardando sinal...", 24f, 48f, txt); return }
        var peak = 0f
        for (i in 0 until count) peak = max(peak, abs(data[i]))
        scale += (max(peak * 1.2f, 500f) - scale) * 0.1f
        path.reset()
        val start = if (count < data.size) 0 else head
        for (i in 0 until count) {
            val v = data[(start + i) % data.size]
            val x = w * i / data.size
            val y = h / 2 - v / scale * (h / 2) * 0.95f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
        c.drawText("±${scale.toInt()} µV   6 s", 24f, h - 16f, txt)
    }
}
