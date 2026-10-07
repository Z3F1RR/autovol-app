package io.github.z3f1rr.autovol

import org.json.JSONObject

/** Snapshot of the last cycle for the UI and the notification. */
data class Status(
    val timeMs: Long = 0,
    val note: String = "",
    /** Outcome name: PAUSED / SKIPPED / APPLIED. */
    val outcome: String = "",
    val lastDb: Double? = null,
    val step: Int? = null,
    val steps: Int = 0,
    val pct: Int? = null,
    val nextAtMs: Long = 0,
    val samples: Int = 0,
    val calFloor: Double? = null,
    val calTop: Double? = null,
    /** Share of the thresholds coming from calibration, 0..1. */
    val calWeight: Double = 0.0,
) {
    val paused: Boolean get() = outcome == "PAUSED"

    fun toJson(): String = JSONObject().apply {
        put("timeMs", timeMs)
        put("note", note)
        put("outcome", outcome)
        lastDb?.let { put("lastDb", it) }
        step?.let { put("step", it) }
        put("steps", steps)
        pct?.let { put("pct", it) }
        put("nextAtMs", nextAtMs)
        put("samples", samples)
        calFloor?.let { put("calFloor", it) }
        calTop?.let { put("calTop", it) }
        put("calWeight", calWeight)
    }.toString()

    companion object {
        fun fromJson(s: String?): Status {
            if (s.isNullOrEmpty()) return Status()
            return try {
                val o = JSONObject(s)
                Status(
                    timeMs = o.optLong("timeMs"),
                    note = o.optString("note"),
                    outcome = o.optString("outcome"),
                    lastDb = if (o.has("lastDb")) o.getDouble("lastDb") else null,
                    step = if (o.has("step")) o.getInt("step") else null,
                    steps = o.optInt("steps"),
                    pct = if (o.has("pct")) o.getInt("pct") else null,
                    nextAtMs = o.optLong("nextAtMs"),
                    samples = o.optInt("samples"),
                    calFloor = if (o.has("calFloor")) o.getDouble("calFloor") else null,
                    calTop = if (o.has("calTop")) o.getDouble("calTop") else null,
                    calWeight = o.optDouble("calWeight", 0.0),
                )
            } catch (e: org.json.JSONException) {
                Status()
            }
        }
    }
}
