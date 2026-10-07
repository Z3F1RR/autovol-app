package io.github.z3f1rr.autovol.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * IEC 61672 A-weighting filter (bilinear transform of the analog prototype), normalised to 0 dB at
 * 1 kHz. Removes the low-frequency rumble (building, fridge, handling, mic 1/f noise) that raw
 * UNPROCESSED capture contains and that is barely audible, so the level tracks what people hear.
 */
class AWeighting(rate: Int) {
    private val sections: Array<Biquad>
    private val gain: Double

    init {
        val t = 1.0 / rate
        fun pole(f: Double): Double {
            val w = 2 * PI * f * t / 2
            return (1 - w) / (1 + w)
        }
        val p1 = pole(F1)
        val p2 = pole(F2)
        val p3 = pole(F3)
        val p4 = pole(F4)
        // 4 zeros at DC (z = 1), 2 zeros at Nyquist (z = -1, the analog zeros at infinity).
        sections = arrayOf(
            Biquad(1.0, -2.0, 1.0, -2 * p1, p1 * p1),
            Biquad(1.0, -2.0, 1.0, -(p2 + p3), p2 * p3),
            Biquad(1.0, 2.0, 1.0, -2 * p4, p4 * p4),
        )
        gain = 1.0 / sections.fold(1.0) { acc, s -> acc * s.magnitude(2 * PI * 1000.0 / rate) }
    }

    fun process(x: Double): Double {
        var y = x * gain
        for (s in sections) y = s.process(y)
        return y
    }

    /** Magnitude response at [hz], for tests. */
    fun magnitude(hz: Double, rate: Int): Double =
        gain * sections.fold(1.0) { acc, s -> acc * s.magnitude(2 * PI * hz / rate) }

    /** y = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2), transposed direct form II. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var s1 = 0.0
        private var s2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            return y
        }

        fun magnitude(w: Double): Double {
            // e^{-jw} and e^{-2jw}
            val c1 = cos(w)
            val s1 = -sin(w)
            val c2 = cos(2 * w)
            val s2 = -sin(2 * w)
            val num = hypot(b0 + b1 * c1 + b2 * c2, b1 * s1 + b2 * s2)
            val den = hypot(1 + a1 * c1 + a2 * c2, a1 * s1 + a2 * s2)
            return num / den
        }
    }

    companion object {
        const val F1 = 20.598997
        const val F2 = 107.65265
        const val F3 = 737.86223
        const val F4 = 12194.217
    }
}
