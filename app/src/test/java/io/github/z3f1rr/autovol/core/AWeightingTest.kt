package io.github.z3f1rr.autovol.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.log10

class AWeightingTest {
    private val rate = 16000
    private val a = AWeighting(rate)

    private fun db(hz: Double) = 20 * log10(a.magnitude(hz, rate))

    @Test
    fun matchesIecTable() {
        // IEC 61672 nominal A-weighting, dB
        assertEquals(-39.4, db(31.5), 0.5)
        assertEquals(-26.2, db(63.0), 0.3)
        assertEquals(-16.1, db(125.0), 0.3)
        assertEquals(-8.6, db(250.0), 0.2)
        assertEquals(-3.2, db(500.0), 0.2)
        assertEquals(0.0, db(1000.0), 0.01)
        // bilinear warping near Nyquist: small deviation is fine
        assertEquals(1.2, db(2000.0), 0.4)
        assertEquals(1.0, db(4000.0), 1.5)
    }

    @Test
    fun blocksDc() {
        var y = 0.0
        repeat(16000) { y = a.process(1000.0) }
        assertEquals(0.0, y, 1e-3)
    }
}
