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
    val silentDb: Int = -85,
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
)
