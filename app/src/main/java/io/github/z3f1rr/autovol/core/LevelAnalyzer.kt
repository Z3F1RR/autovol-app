package io.github.z3f1rr.autovol.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Level of one recording plus diagnostics. */
data class Analysis(
    /** RMS of the AC part in dBFS: the ambient level. */
    val db: Double,
    /** DC offset of the raw signal in dBFS (diagnostics). */
    val dcDb: Double,
    /** Peak of the raw signal, in samples (0..32768). */
    val peak: Int,
    val samples: Long,
)

/**
 * Streaming RMS meter for 16-bit PCM. The first [skip] samples are ignored (AudioRecord start-up).
 *
 * The mean (DC offset) of the analysed part is removed: raw UNPROCESSED capture can carry a constant
 * offset that alone reads as ~-42 dBFS and hides the real sound. The script measured AAC files,
 * which have no DC component, so removing it keeps the numbers comparable.
 */
class LevelAnalyzer(private val skip: Int) {
    private var seen = 0L
    private var sum = 0.0
    private var sumSq = 0.0
    private var counted = 0L
    private var peak = 0

    fun feed(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            if (seen++ < skip) continue
            val x = buf[i].toDouble()
            sum += x
            sumSq += x * x
            counted++
            peak = max(peak, abs(buf[i].toInt()))
        }
    }

    fun result(): Analysis {
        if (counted == 0L) return Analysis(-120.0, -120.0, 0, 0)
        val mean = sum / counted
        val variance = max(0.0, sumSq / counted - mean * mean)
        // Digital silence (system muted the mic): report -120 like "-inf" in the script.
        val db = if (peak <= SILENT_PEAK) -120.0 else powerDb(variance)
        return Analysis(db, amplitudeDb(abs(mean)), peak, counted)
    }

    companion object {
        /** Peak at or below this many LSB = digital silence. */
        const val SILENT_PEAK = 1

        /** Mean power relative to full scale; zero maps to -120. */
        fun powerDb(meanSquare: Double): Double =
            if (meanSquare <= 0) -120.0 else max(-120.0, 10 * log10(meanSquare / (32768.0 * 32768.0)))

        fun amplitudeDb(a: Double): Double = if (a <= 0) -120.0 else max(-120.0, 20 * log10(a / 32768.0))
    }
}
