package io.github.z3f1rr.autovol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class LevelAnalyzerTest {
    private val rate = 16000
    private val skip = rate * 3 / 10

    /** 2 s of signal fed in 100 ms chunks, like Meter does. */
    private fun analyze(sample: (Int) -> Double): Analysis {
        val a = LevelAnalyzer(skip)
        val buf = ShortArray(rate / 10)
        var i = 0
        repeat(20) {
            for (k in buf.indices) buf[k] = sample(i++).roundToInt().coerceIn(-32768, 32767).toShort()
            a.feed(buf, buf.size)
        }
        return a.result()
    }

    private fun sine(i: Int, amp: Double, hz: Double = 1000.0) = amp * 32768 * sin(2 * PI * hz * i / rate)

    @Test
    fun sineLevelIsRms() {
        // amplitude 0.1 FS -> RMS 0.0707 -> -23.0 dBFS
        assertEquals(-23.0, analyze { sine(it, 0.1) }.db, 0.1)
    }

    @Test
    fun dcOffsetDoesNotMaskQuietSound() {
        // Real-device case: offset of 260 LSB alone reads -42 dBFS, the sound under it is at -63 dBFS.
        val a = analyze { 260 + sine(it, 0.001) }
        assertEquals(-63.0, a.db, 0.5)
        assertEquals(-42.0, a.dcDb, 0.2)
        // Without DC removal both music and silence would read about -42 dBFS.
        val quiet = analyze { 260 + sine(it, 0.0002, 300.0) }
        assertTrue("quiet ${quiet.db} vs louder ${a.db}", quiet.db < a.db - 12)
    }

    @Test
    fun lowFrequencySoundIsKept() {
        assertEquals(-23.0, analyze { sine(it, 0.1, 100.0) }.db, 0.5)
    }

    @Test
    fun digitalSilenceIsMinus120() {
        val a = analyze { 0.0 }
        assertEquals(-120.0, a.db, 0.0)
        assertEquals(-120.0, a.dcDb, 0.0)
        assertEquals(0, a.peak)
    }

    @Test
    fun firstMillisecondsAreSkipped() {
        // loud start-up click for 300 ms, then quiet: only the quiet part counts
        val a = analyze { if (it < skip) sine(it, 0.5) else sine(it, 0.001) }
        assertEquals(-63.0, a.db, 0.5)
        assertEquals((2 * rate - skip).toLong(), a.samples)
    }

    @Test
    fun offsetSettlingDuringSkipDoesNotLeak() {
        // AudioRecord start-up: offset jumps from 5000 to 260 LSB exactly when counting starts
        val a = analyze { (if (it < skip) 5000.0 else 260.0) + sine(it, 0.001) }
        assertEquals(-63.0, a.db, 0.5)
    }
}
