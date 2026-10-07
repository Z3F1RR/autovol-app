package io.github.z3f1rr.autovol.core

/** Persists the threshold correction learned from the user's manual volume changes (dB). */
interface BiasStore {
    fun load(): Double
    fun save(biasDb: Double)
}

class MemoryBiasStore(var value: Double = 0.0) : BiasStore {
    override fun load() = value
    override fun save(biasDb: Double) {
        value = biasDb
    }
}

/**
 * Learning from manual corrections: every time the user turns the ringer up after AutoVol set it,
 * the thresholds move [Settings.LEARN_STEP_DB] lower (louder next time in the same noise); turning it
 * down moves them higher. The total is clamped to ±[Settings.LEARN_MAX_DB]; opposite corrections
 * cancel out, so occasional one-off changes do not accumulate.
 */
object Learning {
    fun next(biasDb: Double, direction: Int): Double =
        (biasDb + direction.coerceIn(-1, 1) * Settings.LEARN_STEP_DB)
            .coerceIn(-Settings.LEARN_MAX_DB, Settings.LEARN_MAX_DB)
}
