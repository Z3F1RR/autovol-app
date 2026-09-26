package io.github.z3f1rr.autovol

import android.content.Context
import android.content.SharedPreferences
import io.github.z3f1rr.autovol.core.Levels
import io.github.z3f1rr.autovol.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SharedPreferences-backed settings. Keys match the script's config names in lower case. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("autovol", Context.MODE_PRIVATE)
    private val d = Settings()

    private val _enabled = MutableStateFlow(sp.getBoolean(K_ENABLED, false))
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _minVol = MutableStateFlow(sp.getInt("min_vol", d.minVol))
    val minVolFlow: StateFlow<Int> = _minVol.asStateFlow()

    private val _pauseUntil = MutableStateFlow(sp.getLong("pause_until", 0))
    val pauseUntilFlow: StateFlow<Long> = _pauseUntil.asStateFlow()

    var enabled: Boolean
        get() = _enabled.value
        set(v) {
            sp.edit().putBoolean(K_ENABLED, v).apply()
            _enabled.value = v
        }

    var minVol: Int
        get() = _minVol.value
        set(v) {
            sp.edit().putInt("min_vol", v).apply()
            _minVol.value = v
        }

    var pauseUntilMs: Long
        get() = _pauseUntil.value
        set(v) {
            sp.edit().putLong("pause_until", v).apply()
            _pauseUntil.value = v
        }

    fun settings(): Settings = Settings(
        enabled = enabled,
        pauseUntilMs = pauseUntilMs,
        levels = Levels.parse(sp.getString("levels", Levels.DEFAULT) ?: Levels.DEFAULT),
        minVol = minVol,
        fast = sp.getInt("fast", d.fast),
        slow = sp.getInt("slow", d.slow),
        idle = sp.getInt("idle", d.idle),
        elevInt = sp.getInt("elev_int", d.elevInt),
        stableMin = sp.getInt("stable_min", d.stableMin),
        margin = sp.getFloat("margin", d.margin.toFloat()).toDouble(),
        confirmSec = sp.getInt("confirm_sec", d.confirmSec),
        overrideMin = sp.getInt("override_min", d.overrideMin),
        recSec = sp.getInt("rec_sec", d.recSec),
        silentDb = sp.getInt("silent_db", d.silentDb),
        lowBat = sp.getInt("low_bat", d.lowBat),
        pauseOnDnd = sp.getBoolean("pause_on_dnd", d.pauseOnDnd),
        pauseOnExtAudio = sp.getBoolean("pause_on_ext_audio", d.pauseOnExtAudio),
        pauseOnMedia = sp.getBoolean("pause_on_media", d.pauseOnMedia),
        autoCal = sp.getBoolean("auto_cal", d.autoCal),
        calDays = sp.getInt("cal_days", d.calDays),
        calMinSamples = sp.getInt("cal_min_samples", d.calMinSamples),
        calFloorPct = sp.getInt("cal_floor_pct", d.calFloorPct),
        calStart = sp.getInt("cal_start", d.calStart),
        calMinSpan = sp.getInt("cal_min_span", d.calMinSpan),
        calMaxSpan = sp.getInt("cal_max_span", d.calMaxSpan),
    )

    fun loadStatus(): Status = Status.fromJson(sp.getString("status", null))

    fun saveStatus(s: Status) {
        sp.edit().putString("status", s.toJson()).apply()
    }

    private companion object {
        const val K_ENABLED = "enabled"
    }
}
