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
        val a = LevelAnalyzer(skip, rate)
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
        assertEquals(-63.0, a.flatDb, 0.5)
        assertEquals(-42.0, a.dcDb, 0.2)
        // Without DC removal both music and silence would read about -42 dBFS.
        val quiet = analyze { 260 + sine(it, 0.0002, 300.0) }
        assertTrue("quiet ${quiet.db} vs louder ${a.db}", quiet.db < a.db - 12)
    }

    @Test
    fun lowFrequencyRumbleIsWeightedDown() {
        // 50 Hz rumble: flat level stays, A-weighted level drops by ~30 dB
        val a = analyze { sine(it, 0.1, 50.0) }
        assertEquals(-23.0, a.flatDb, 0.5)
        assertEquals(-23.0 - 30.2, a.db, 1.0)
    }

    @Test
    fun quietSoundIsVisibleOverRumble() {
        // Real-device case: loud low rumble (flat ~-45 dB) masked a quiet room vs. music difference
        val quiet = analyze { 250 + sine(it, 0.008, 40.0) + sine(it, 0.0001, 1000.0) }
        val music = analyze { 250 + sine(it, 0.008, 40.0) + sine(it, 0.003, 1000.0) }
        assertTrue("flat levels nearly equal", music.flatDb - quiet.flatDb < 3)
        assertTrue("A-weighted ${quiet.db} vs ${music.db}", music.db - quiet.db > 20)
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
        // loud signal for exactly 300 ms, then quiet: only the quiet part counts (unweighted level)
        val a = analyze { if (it < skip) sine(it, 0.5) else sine(it, 0.001) }
        assertEquals(-63.0, a.flatDb, 0.5)
        assertEquals((2 * rate - skip).toLong(), a.samples)
    }

    @Test
    fun startupTransientDoesNotLeak() {
        // AudioRecord start-up: a loud pop and a settling offset in the first 100 ms
        val startup = rate / 10
        val a = analyze { if (it < startup) 5000.0 + sine(it, 0.5, 300.0) else 260.0 + sine(it, 0.001) }
        assertEquals(-63.0, a.db, 0.5)
        assertEquals(-63.0, a.flatDb, 0.5)
    }
}
