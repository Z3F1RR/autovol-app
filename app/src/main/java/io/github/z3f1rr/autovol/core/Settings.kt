package io.github.z3f1rr.autovol.core

/** All tunables; defaults mirror DEFAULTS in reference/autovol.py. */
data class Settings(
    val enabled: Boolean = true,
    /** Epoch ms until which the user paused (notification action "Пауза 1 ч"). */
    val pauseUntilMs: Long = 0,
    val levels: List<Level> = Levels.parse(Levels.DEFAULT),
    val minVol: Int = 1,
    val fast: Int = 90,
    val slow: Int = 150,
    val idle: Int = 300,
    val elevInt: Int = 45,
    val stableMin: Int = 15,
    val margin: Double = 3.0,
    val confirmSec: Int = 12,
    val overrideMin: Int = 30,
    val recSec: Int = 2,
    /**
     * At or below = mic muted by the system. The script used -85 for unfiltered AAC; an A-weighted quiet
     * room can read lower, and true digital silence is reported as exactly -120 by [LevelAnalyzer].
     */
    val silentDb: Int = -100,
    val lowBat: Int = 15,
    val pauseOnDnd: Boolean = true,
    val pauseOnExtAudio: Boolean = true,
    val pauseOnMedia: Boolean = true,
    val autoCal: Boolean = true,
    val calDays: Int = 7,
    val calMinSamples: Int = 300,
    val calFloorPct: Int = 25,
    val calStart: Int = 8,
    val calMinSpan: Int = 24,
    val calMaxSpan: Int = 40,
    /** Calibration starts blending into the thresholds after this many samples. */
    val calWarmup: Int = 30,
    /** Ringer/notification sensitivity, -3..+3: each notch moves the thresholds by [SENS_DB_PER_NOTCH]. */
    val ringSens: Int = 0,
    /** Also regulate media volume (only while it is neither muted nor set to maximum by the user). */
    val mediaEnabled: Boolean = false,
    /** Media sensitivity, -3..+3 steps relative to the ringer step. */
    val mediaSens: Int = 0,
    /** Learn from manual ringer changes ([Learning]). */
    val learnFromManual: Boolean = true,
) {
    companion object {
        const val SENS_MAX = 3
        const val SENS_DB_PER_NOTCH = 3.0
        const val LEARN_STEP_DB = 1.5
        const val LEARN_MAX_DB = 9.0
    }
}
