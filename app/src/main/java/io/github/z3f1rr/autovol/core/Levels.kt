package io.github.z3f1rr.autovol.core

import kotlin.math.max
import kotlin.math.min

/** One volume step: noise threshold in dBFS and ringer volume percent. */
data class Level(val db: Double, val pct: Int)

/** Result of auto-calibration: silence floor, top of the scale and history size. */
data class CalInfo(val floorDb: Double, val topDb: Double, val samples: Int)

object Levels {
    const val DEFAULT = "-120:0 -46:25 -39:50 -32:75 -26:100"

    /** Port of parse_levels(): "thr:pct ..." sorted ascending; falls back to defaults on error. */
    fun parse(s: String): List<Level> {
        val lv = try {
            s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { t ->
                val (a, b) = t.split(":").also { require(it.size == 2) }
                Level(a.toDouble(), b.toInt().coerceIn(0, 100))
            }.sortedWith(compareBy<Level> { it.db }.thenBy { it.pct })
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
        return lv.ifEmpty { if (s == DEFAULT) error("bad default levels") else parse(DEFAULT) }
    }

    fun format(lv: List<Level>): String = lv.joinToString(" ") { "${fmtG(it.db)}:${it.pct}" }

    /** Port of step_of(): index of the highest threshold not above db. */
    fun stepOf(db: Double, lv: List<Level>): Int {
        var i = 0
        for ((n, l) in lv.withIndex()) if (db >= l.db) i = n
        return i
    }

    /** Port of target_vol(): 0% = min volume (never 0), 100% = max, linear in between. */
    fun targetVol(pct: Int, lo: Int, hi: Int, minVol: Int): Int {
        val mn = min(hi, maxOf(lo, 1, minVol))
        return min(hi, mn + pyRound((hi - mn) * pct / 100.0).toInt())
    }

    /** Python's pctl(): element at int(n * p / 100), clamped. */
    fun percentile(sorted: List<Double>, p: Int): Double =
        sorted[min(sorted.size - 1, max(0, (sorted.size * p / 100.0).toInt()))]

    /**
     * Port of auto_levels(): thresholds from measurement history. Silence = [floorPct] percentile,
     * top = 97th percentile, top - silence clamped to [minSpan..maxSpan]. Percents stay from [lv].
     */
    fun autoLevels(history: List<Double>, lv: List<Level>, s: Settings): Pair<List<Level>, CalInfo?> {
        if (!s.autoCal || history.size < s.calMinSamples || lv.size < 2) return lv to null
        val xs = history.sorted()
        val floor = percentile(xs, s.calFloorPct)
        val top = percentile(xs, 97)
        val span = min(max(top - floor, s.calMinSpan.toDouble()), s.calMaxSpan.toDouble())
        val start = s.calStart.toDouble()
        val n = lv.size - 1
        val out = ArrayList<Level>(lv.size)
        out += Level(-120.0, lv[0].pct)
        for (k in 1..n) {
            val th = floor + start + (span - start) * (k - 1) / max(1, n - 1)
            out += Level(round1(th), lv[k].pct)
        }
        return out to CalInfo(round1(floor), round1(floor + span), history.size)
    }

    /** Python 3 round(): half to even. */
    fun pyRound(x: Double): Double = Math.rint(x)

    fun round1(x: Double): Double = Math.rint(x * 10) / 10

    private fun fmtG(x: Double): String =
        if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString() else x.toString()
}
