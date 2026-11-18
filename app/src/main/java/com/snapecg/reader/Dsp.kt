package com.snapecg.reader

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Biquad (Direct Form II transposta), receitas RBJ. */
class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val B0 = b0 / a0; private val B1 = b1 / a0; private val B2 = b2 / a0
    private val A1 = a1 / a0; private val A2 = a2 / a0
    private var z1 = 0.0; private var z2 = 0.0

    fun process(x: Double): Double {
        val y = B0 * x + z1
        z1 = B1 * x - A1 * y + z2
        z2 = B2 * x - A2 * y
        return y
    }

    companion object {
        fun lowpass(fs: Double, f: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        }
        fun highpass(fs: Double, f: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }
        fun notch(fs: Double, f: Double, q: Double = 30.0): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return Biquad(1.0, -2 * c, 1.0, 1 + al, -2 * c, 1 - al)
        }
    }
}

/**
 * Mediana de 5 amostras (remove picos curtos/artefatos) -> passa-alta 0,5 Hz (linha de base)
 * -> notch da rede (60 Hz no Brasil) -> passa-baixa 40 Hz.
 * Os primeiros 2 s sao "aquecimento": saida 0 para nao mostrar o transitorio do filtro.
 */
class EcgFilter(private val fs: Int, mains: Double = 60.0) {
    private val hp = Biquad.highpass(fs.toDouble(), 0.5)
    private val notch = if (mains < fs * 0.45) Biquad.notch(fs.toDouble(), mains) else null
    private val lp = Biquad.lowpass(fs.toDouble(), minOf(40.0, fs * 0.4))
    private val win = DoubleArray(5)
    private var wi = 0
    private var seen = 0L
    private var dc0 = 0.0

    fun process(uv: Double): Double {
        if (seen == 0L) win.fill(uv)
        win[wi] = uv; wi = (wi + 1) % 5
        val x = win.sortedArray()[2]
        if (seen == 0L) dc0 = x
        seen++
        var y = hp.process(x - dc0)
        if (notch != null) y = notch.process(y)
        y = lp.process(y)
        return if (seen < fs * 2) 0.0 else y
    }
}

/**
 * Detector de QRS (estilo Pan-Tompkins): banda 5-15 Hz -> derivada -> quadrado -> janela movel
 * -> limiar adaptativo limitado. Rejeita onda T/ruido que cai logo depois de um batimento.
 * Aprende o nivel do sinal nos primeiros 4 s (2 s de aquecimento do filtro + 2 s).
 */
class QrsDetector(private val fs: Int) {
    private val bpHigh = Biquad.highpass(fs.toDouble(), 5.0)
    private val bpLow = Biquad.lowpass(fs.toDouble(), 15.0)
    private val win = maxOf(3, (0.15 * fs).toInt())
    private val buf = DoubleArray(win)
    private var bi = 0
    private var sum = 0.0
    private var prev = 0.0
    private var m1 = 0.0
    private var m2 = 0.0
    private var spki = 0.0
    private var lastPeak = 0.0
    private var learn = 0
    private val decay = 0.5.pow(1.0 / (2.0 * fs))
    private val refractory = (0.3 * fs).toInt()
    private var since = Int.MAX_VALUE / 2
    private val rr = DoubleArray(5)
    private var rrN = 0
    @Volatile var bpm = 0

    /** Devolve true quando um batimento foi detectado. */
    fun push(x: Double): Boolean {
        val b = bpLow.process(bpHigh.process(x))
        val d = b - prev
        prev = b
        val sq = d * d
        sum += sq - buf[bi]; buf[bi] = sq; bi = (bi + 1) % win
        val m = sum / win
        since++
        var beat = false
        if (learn < 4 * fs) {
            learn++; spki = maxOf(spki, m)
        } else if (since > 1.5 * fs) {
            spki *= decay
        }
        if (learn >= 4 * fs && m1 > 0.35 * spki && m1 >= m2 && m1 > m && spki > 300.0 && since > refractory) {
            val ms = since * 1000.0 / fs
            if (!(ms < 360.0 && m1 < 0.6 * lastPeak)) {      // perto demais e fraco = onda T / ruido
                beat = true
                if (ms in 300.0..2000.0) {
                    rr[rrN % rr.size] = ms; rrN++
                    val k = minOf(rrN, rr.size)
                    val sorted = rr.copyOf(k).sorted()
                    bpm = (60000.0 / sorted[k / 2]).toInt()
                }
                lastPeak = m1
                spki = 0.875 * spki + 0.125 * minOf(m1, 2 * spki)
                since = 0
            }
        }
        m2 = m1; m1 = m
        return beat
    }
}
