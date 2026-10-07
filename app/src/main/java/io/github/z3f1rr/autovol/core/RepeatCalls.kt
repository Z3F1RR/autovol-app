package io.github.z3f1rr.autovol.core

enum class RepeatMode {
    /** A second call from the same number. */
    SAME_NUMBER,

    /** A second call from any number. */
    ANY_NUMBER,
}

data class RepeatSettings(
    val enabled: Boolean = false,
    val mode: RepeatMode = RepeatMode.SAME_NUMBER,
    val windowMin: Int = 15,
)

/** A call that rang and was not answered (missed or rejected). */
data class MissedCall(val tMs: Long, val number: String?)

/** Persistent state between phone-state broadcasts (the process may die in between). */
data class CallsState(
    val missed: List<MissedCall> = emptyList(),
    val ringing: Boolean = false,
    val ringingNumber: String? = null,
    val answered: Boolean = false,
    /** Ringer volume before we raised it; null = not raised. */
    val boostedFrom: Int? = null,
)

/** What to do with the ringer volume after an event. */
sealed interface CallAction {
    data object None : CallAction

    /** Raise the ringer to maximum; [reason] goes to the log. */
    data class Boost(val reason: String) : CallAction

    /** Put the ringer back to [volume] (unless the user changed it meanwhile). */
    data class Restore(val volume: Int) : CallAction
}

/**
 * "Repeat caller" logic: when someone calls again within [RepeatSettings.windowMin] minutes after an
 * unanswered call, the ringer goes to maximum for this call and is restored when it ends.
 * Pure state machine driven by TelephonyManager states; all decisions are unit-tested.
 */
class RepeatCalls(var state: CallsState) {

    /**
     * Phone is ringing. Called twice per call when READ_CALL_LOG is granted (without and with the
     * number); [currentVolume]/[maxVolume] are the ringer volume.
     */
    fun onRinging(nowMs: Long, number: String?, s: RepeatSettings, currentVolume: Int, maxVolume: Int): CallAction {
        val num = normalize(number)
        val firstEvent = !state.ringing
        if (firstEvent) {
            state = state.copy(ringing = true, ringingNumber = num, answered = false, boostedFrom = null)
        } else if (num != null && state.ringingNumber == null) {
            state = state.copy(ringingNumber = num)
        }
        if (!s.enabled || state.boostedFrom != null) return CallAction.None
        prune(nowMs, s)
        val reason = when (s.mode) {
            RepeatMode.ANY_NUMBER -> state.missed.lastOrNull()?.let { "повторный звонок (пропущен ${ago(nowMs, it)})" }
            RepeatMode.SAME_NUMBER -> state.ringingNumber?.let { n ->
                state.missed.lastOrNull { it.number == n }
                    ?.let { "повторный звонок с ${mask(n)} (пропущен ${ago(nowMs, it)})" }
            }
        } ?: return CallAction.None
        if (currentVolume >= maxVolume) return CallAction.None
        state = state.copy(boostedFrom = currentVolume)
        return CallAction.Boost(reason)
    }

    /** Call answered (or an outgoing call started). */
    fun onOffhook(): CallAction {
        val wasRinging = state.ringing
        val from = state.boostedFrom
        state = state.copy(answered = state.answered || wasRinging, boostedFrom = null)
        return if (from != null) CallAction.Restore(from) else CallAction.None
    }

    /** All calls ended. An unanswered ringing call is remembered as missed. */
    fun onIdle(nowMs: Long, s: RepeatSettings): CallAction {
        val from = state.boostedFrom
        var missed = state.missed
        if (state.ringing && !state.answered) missed = missed + MissedCall(nowMs, state.ringingNumber)
        state = CallsState(missed = missed)
        prune(nowMs, s)
        return if (from != null) CallAction.Restore(from) else CallAction.None
    }

    private fun prune(nowMs: Long, s: RepeatSettings) {
        val cut = nowMs - s.windowMin * 60_000L
        val keep = state.missed.filter { it.tMs >= cut }.takeLast(MAX_MISSED)
        if (keep.size != state.missed.size) state = state.copy(missed = keep)
    }

    companion object {
        const val MAX_MISSED = 20

        /** Digits only, last 10: "+7 (912) 345-67-89" and "89123456789" match. */
        fun normalize(number: String?): String? {
            val d = number?.filter { it.isDigit() }.orEmpty()
            return if (d.length < 3) null else d.takeLast(10)
        }

        /** For the shared log: never the full number. */
        fun mask(n: String) = "…" + n.takeLast(2)

        private fun ago(nowMs: Long, c: MissedCall): String {
            val min = (nowMs - c.tMs) / 60_000
            return if (min < 1) "меньше минуты назад" else "$min мин назад"
        }
    }
}
