package com.smartup.snapecgusb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Traçado de ECG rolando (últimos 6 s), com grade de 0,2 s / 1 s e autoescala. */
class EcgView(context: Context) : View(context) {
    private var fs = 200
    private var buf = FloatArray(fs * 6)
    private var count = 0L
    private var lo = -500f
    private var hi = 500f

    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 140, 60); strokeWidth = 3f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val minor = Paint().apply { color = Color.rgb(250, 215, 215); strokeWidth = 1f }
    private val major = Paint().apply { color = Color.rgb(240, 160, 160); strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }
    private val path = Path()

    init { setBackgroundColor(Color.rgb(255, 248, 248)) }

    fun setRate(f: Int) {
        if (f != fs && f > 0) { fs = f; buf = FloatArray(fs * 6); count = 0 }
    }

    fun push(v: Float) {
        buf[(count % buf.size).toInt()] = v
        count++
    }

    fun clear() { count = 0; lo = -500f; hi = 500f; invalidate() }

    fun refresh() = invalidate()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = buf.size
        // grade vertical: 0,2 s e 1 s
        val secW = w / 6f
        var k = 0
        while (k <= 30) {
            val x = k * secW / 5f
            c.drawLine(x, 0f, x, h, if (k % 5 == 0) major else minor)
            k++
        }
        for (r in 0..4) c.drawLine(0f, h * r / 4f, w, h * r / 4f, if (r % 2 == 0) major else minor)

        val n = min(count, size.toLong()).toInt()
        if (n < 2) return
        val start = if (count > size) (count % size).toInt() else 0
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until n) {
            val v = buf[(start + i) % size]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val span = max(mx - mn, 600f)
        val mid = (mx + mn) / 2f
        lo += ((mid - span * 0.6f) - lo) * 0.1f
        hi += ((mid + span * 0.6f) - hi) * 0.1f
        val range = max(hi - lo, 1f)

        path.reset()
        for (i in 0 until n) {
            val v = buf[(start + i) % size]
            val x = w - (n - 1 - i) * w / (size - 1)
            val y = h - (v - lo) / range * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, trace)
        c.drawText("%.0f µV".format(range), 12f, 32f, txt)
    }
}
