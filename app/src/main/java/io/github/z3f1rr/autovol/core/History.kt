package io.github.z3f1rr.autovol.core

/** One clean measurement: epoch seconds and dBFS rounded to 0.1. */
data class Sample(val tSec: Long, val db: Double)

interface HistoryStore {
    fun load(): List<Sample>
    fun save(samples: List<Sample>)
}

/** Measurement history for auto-calibration (port of State.hist / add_hist). */
class History(private val store: HistoryStore) {
    private var items: MutableList<Sample> = store.load().toMutableList()

    val size: Int @Synchronized get() = items.size

    @Synchronized
    fun values(): List<Double> = items.map { it.db }

    /** Samples not older than [sinceSec] (epoch seconds), oldest first — for the level chart. */
    @Synchronized
    fun since(sinceSec: Long): List<Sample> = items.filter { it.tSec >= sinceSec }

    @Synchronized
    fun add(db: Double, nowMs: Long, calDays: Int) {
        val now = nowMs / 1000
        items.add(Sample(now, Levels.round1(db)))
        val cut = now - calDays * 86400L
        if (items.isNotEmpty() && items[0].tSec < cut) items = items.filterTo(ArrayList()) { it.tSec >= cut }
        store.save(items)
    }

    @Synchronized
    fun clear() {
        items = ArrayList()
        store.save(items)
    }
}

class MemoryHistoryStore(initial: List<Sample> = emptyList()) : HistoryStore {
    var saved: List<Sample> = initial
    override fun load() = saved
    override fun save(samples: List<Sample>) {
        saved = samples.toList()
    }
}
