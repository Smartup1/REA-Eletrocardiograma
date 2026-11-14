package com.smartup.snapecgusb

import kotlin.math.max
import kotlin.math.pow

/**
 * Detector simples de batimentos (estilo Pan-Tompkins simplificado) para estimar BPM.
 * É só uma estimativa para visualização; não serve para diagnóstico.
 */
class BeatDetector(private val fs: Int) {
    private var ema = 0.0
    private var init = false
    private val alpha = 1.0 / (0.6 * fs)
    private var prev1 = 0.0
    private var prev2 = 0.0
    private val win = max(3, (0.12 * fs).toInt())
    private val ring = DoubleArray(win)
    private var ri = 0
    private var rsum = 0.0
    private var env = 0.0
    private val decay = 0.5.pow(1.0 / (2.0 * fs))
    private var n = 0L
    private var lastBeat = -1_000_000L
    private var above = false
    private val rr = ArrayDeque<Double>()

    var bpm = 0
        private set

    fun reset() {
        init = false; env = 0.0; rsum = 0.0; ring.fill(0.0); prev1 = 0.0; prev2 = 0.0
        above = false; lastBeat = -1_000_000L; rr.clear(); bpm = 0
    }

    /** Retorna true quando detecta um batimento. */
    fun push(x: Float): Boolean {
        n++
        if (!init) { ema = x.toDouble(); init = true }
        ema += (x - ema) * alpha
        val hp = x - ema
        val d = hp - prev2
        prev2 = prev1
        prev1 = hp
        val sq = d * d
        rsum += sq - ring[ri]
        ring[ri] = sq
        ri = (ri + 1) % win
        val mw = rsum / win
        env = max(mw, env * decay)
        val thr = 0.4 * env
        var beat = false
        if (!above && env > 400.0 && mw > thr && (n - lastBeat) > (0.25 * fs).toLong()) {
            above = true
            beat = true
            if (lastBeat > 0) {
                val rrSec = (n - lastBeat).toDouble() / fs
                if (rrSec in 0.3..2.0) {
                    rr.addLast(rrSec)
                    if (rr.size > 8) rr.removeFirst()
                    bpm = (60.0 / rr.average()).toInt()
                }
            }
            lastBeat = n
        }
        if (above && mw < thr * 0.5) above = false
        return beat
    }
}
