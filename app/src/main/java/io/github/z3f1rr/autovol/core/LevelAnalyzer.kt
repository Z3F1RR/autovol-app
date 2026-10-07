package io.github.z3f1rr.autovol.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Level of one recording plus diagnostics. */
data class Analysis(
    /** A-weighted RMS in dBFS: the ambient level used for decisions. */
    val db: Double,
    /** Unweighted RMS without DC in dBFS (diagnostics). */
    val flatDb: Double,
    /** DC offset of the raw signal in dBFS (diagnostics). */
    val dcDb: Double,
    /** Peak of the raw signal, in samples (0..32768). */
    val peak: Int,
    val samples: Long,
)

/**
 * Streaming level meter for 16-bit PCM. The first [skip] samples are ignored (AudioRecord start-up;
 * the filter runs through them so its transient has settled).
 *
 * Raw UNPROCESSED capture has a DC offset and strong low-frequency rumble; together they read as
 * ~-45 dBFS in a silent room and leave only ~10 dB to real noise. The decision level is therefore
 * A-weighted ([AWeighting]); the unweighted level is kept for diagnostics.
 */
class LevelAnalyzer(private val skip: Int, rate: Int = 16000) {
    private val weighting = AWeighting(rate)
    private var seen = 0L
    private var sum = 0.0
    private var sumSq = 0.0
    private var sumSqA = 0.0
    private var counted = 0L
    private var peak = 0

    fun feed(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val x = buf[i].toDouble()
            val a = weighting.process(x)
            if (seen++ < skip) continue
            sum += x
            sumSq += x * x
            sumSqA += a * a
            counted++
            peak = max(peak, abs(buf[i].toInt()))
        }
    }

    fun result(): Analysis {
        if (counted == 0L) return Analysis(-120.0, -120.0, -120.0, 0, 0)
        val mean = sum / counted
        val variance = max(0.0, sumSq / counted - mean * mean)
        // Digital silence (system muted the mic): report -120 like "-inf" in the script.
        val silent = peak <= SILENT_PEAK
        val db = if (silent) -120.0 else powerDb(sumSqA / counted)
        val flat = if (silent) -120.0 else powerDb(variance)
        return Analysis(db, flat, amplitudeDb(abs(mean)), peak, counted)
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
