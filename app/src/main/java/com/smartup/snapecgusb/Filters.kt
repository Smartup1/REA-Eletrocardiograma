package com.smartup.snapecgusb

import kotlin.math.cos
import kotlin.math.sin

/** Filtro biquad (forma transposta II), coeficientes do "Audio EQ Cookbook" (RBJ). */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() { z1 = 0.0; z2 = 0.0 }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private fun make(type: Int, f: Double, fs: Double, q: Double): Biquad {
            val w0 = 2.0 * Math.PI * f / fs
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            val a1 = -2.0 * c
            val a2 = 1.0 - alpha
            val (b0, b1, b2) = when (type) {
                0 -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)      // passa-baixa
                1 -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)   // passa-alta
                else -> Triple(1.0, -2.0 * c, 1.0)                // notch
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        fun lowPass(f: Double, fs: Double, q: Double = 0.7071) = make(0, f, fs, q)
        fun highPass(f: Double, fs: Double, q: Double = 0.7071) = make(1, f, fs, q)
        fun notch(f: Double, fs: Double, q: Double = 30.0) = make(2, f, fs, q)
    }
}

/**
 * Filtro para visualização do ECG: passa-alta 0,5 Hz (linha de base), passa-baixa 40 Hz
 * e notch de 60 Hz (rede elétrica do Brasil). O notch só entra se a taxa de amostragem
 * comportar (fs >= 133 Hz). O CSV continua gravando o sinal cru.
 */
class EcgFilter(private val fs: Int) {
    private val stages = ArrayList<Biquad>()
    private var primed = false

    init {
        val f = fs.toDouble()
        stages.add(Biquad.highPass(0.5, f))
        if (40.0 < 0.45 * f) stages.add(Biquad.lowPass(40.0, f))
        if (60.0 < 0.45 * f) stages.add(Biquad.notch(60.0, f))
    }

    fun reset() {
        stages.forEach { it.reset() }
        primed = false
    }

    fun process(x: Float): Float {
        if (!primed) {
            // assenta o filtro no primeiro valor para não gerar um transiente grande
            primed = true
            repeat(3 * fs) { var v = x.toDouble(); for (s in stages) v = s.process(v) }
        }
        var v = x.toDouble()
        for (s in stages) v = s.process(v)
        return v.toFloat()
    }
}
