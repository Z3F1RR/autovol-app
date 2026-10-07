package io.github.z3f1rr.autovol

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.z3f1rr.autovol.core.CallsState
import io.github.z3f1rr.autovol.core.Levels
import io.github.z3f1rr.autovol.core.MissedCall
import io.github.z3f1rr.autovol.core.RepeatMode
import io.github.z3f1rr.autovol.core.RepeatSettings
import io.github.z3f1rr.autovol.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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

    private val _ringSens = MutableStateFlow(sp.getInt("ring_sens", d.ringSens))
    val ringSensFlow: StateFlow<Int> = _ringSens.asStateFlow()

    var ringSens: Int
        get() = _ringSens.value
        set(v) {
            sp.edit().putInt("ring_sens", v).apply()
            _ringSens.value = v
        }

    private val _mediaEnabled = MutableStateFlow(sp.getBoolean("media_enabled", d.mediaEnabled))
    val mediaEnabledFlow: StateFlow<Boolean> = _mediaEnabled.asStateFlow()

    var mediaEnabled: Boolean
        get() = _mediaEnabled.value
        set(v) {
            sp.edit().putBoolean("media_enabled", v).apply()
            _mediaEnabled.value = v
        }

    private val _mediaSens = MutableStateFlow(sp.getInt("media_sens", d.mediaSens))
    val mediaSensFlow: StateFlow<Int> = _mediaSens.asStateFlow()

    var mediaSens: Int
        get() = _mediaSens.value
        set(v) {
            sp.edit().putInt("media_sens", v).apply()
            _mediaSens.value = v
        }

    var pauseUntilMs: Long
        get() = _pauseUntil.value
        set(v) {
            sp.edit().putLong("pause_until", v).apply()
            _pauseUntil.value = v
        }

    private val _repeat = MutableStateFlow(
        RepeatSettings(
            enabled = sp.getBoolean("repeat_enabled", false),
            mode = if (sp.getString("repeat_mode", null) == RepeatMode.ANY_NUMBER.name) {
                RepeatMode.ANY_NUMBER
            } else {
                RepeatMode.SAME_NUMBER
            },
            windowMin = sp.getInt("repeat_window_min2", RepeatSettings().windowMin),
        ),
    )
    val repeatFlow: StateFlow<RepeatSettings> = _repeat.asStateFlow()

    var repeat: RepeatSettings
        get() = _repeat.value
        set(v) {
            sp.edit()
                .putBoolean("repeat_enabled", v.enabled)
                .putString("repeat_mode", v.mode.name)
                .putInt("repeat_window_min2", v.windowMin)
                .apply()
            _repeat.value = v
        }

    /** su worked once: re-apply the grants after reboot if the system reset them. */
    var rootGranted: Boolean
        get() = sp.getBoolean("root_granted", false)
        set(v) {
            sp.edit().putBoolean("root_granted", v).apply()
        }

    /** Repeat-call state machine; written synchronously, the process may die right after. */
    var callsState: CallsState
        get() = callsFromJson(sp.getString("calls_state", null))
        @SuppressLint("ApplySharedPref")
        set(v) {
            sp.edit().putString("calls_state", callsToJson(v)).commit()
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
        calWarmup = sp.getInt("cal_warmup", d.calWarmup),
        ringSens = ringSens,
        mediaEnabled = mediaEnabled,
        mediaSens = mediaSens,
    )

    fun loadStatus(): Status = Status.fromJson(sp.getString("status", null))

    fun saveStatus(s: Status) {
        sp.edit().putString("status", s.toJson()).apply()
    }

    private companion object {
        const val K_ENABLED = "enabled"

        fun callsToJson(s: CallsState): String = JSONObject().apply {
            put("missed", JSONArray().apply {
                s.missed.forEach { m -> put(JSONObject().put("t", m.tMs).put("n", m.number ?: "")) }
            })
            put("ringing", s.ringing)
            s.ringingNumber?.let { put("number", it) }
            put("answered", s.answered)
            s.boostedFrom?.let { put("boostedFrom", it) }
        }.toString()

        fun callsFromJson(json: String?): CallsState {
            if (json.isNullOrEmpty()) return CallsState()
            return try {
                val o = JSONObject(json)
                val arr = o.optJSONArray("missed") ?: JSONArray()
                CallsState(
                    missed = List(arr.length()) { i ->
                        val m = arr.getJSONObject(i)
                        MissedCall(m.getLong("t"), m.optString("n").ifEmpty { null })
                    },
                    ringing = o.optBoolean("ringing"),
                    ringingNumber = if (o.has("number")) o.getString("number") else null,
                    answered = o.optBoolean("answered"),
                    boostedFrom = if (o.has("boostedFrom")) o.getInt("boostedFrom") else null,
                )
            } catch (e: JSONException) {
                CallsState()
            }
        }
    }
}
