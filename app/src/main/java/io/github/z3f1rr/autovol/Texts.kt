package io.github.z3f1rr.autovol

import android.content.Context
import io.github.z3f1rr.autovol.core.Reason

/** Localized UI texts that are built outside composables (status line, notification). */
object Texts {
    /** Status line for a cycle that did not apply a measurement; null when there is nothing to say. */
    fun reason(ctx: Context, st: Status): String? {
        val r = runCatching { Reason.valueOf(st.reason) }.getOrNull() ?: return null
        val arg = st.reasonArg.orEmpty()
        return when (r) {
            Reason.NONE -> null
            Reason.DISABLED -> ctx.getString(R.string.reason_DISABLED)
            Reason.USER_PAUSE -> ctx.getString(R.string.reason_USER_PAUSE, arg)
            Reason.RINGER -> ctx.getString(R.string.reason_RINGER)
            Reason.DND -> ctx.getString(R.string.reason_DND)
            Reason.CALL -> ctx.getString(R.string.reason_CALL)
            Reason.CAR -> ctx.getString(R.string.reason_CAR)
            Reason.EXT_AUDIO -> ctx.getString(R.string.reason_EXT_AUDIO)
            Reason.LOW_BATTERY -> ctx.getString(R.string.reason_LOW_BATTERY, arg)
            Reason.VOLUME_ERROR -> ctx.getString(R.string.reason_VOLUME_ERROR)
            Reason.MANUAL -> ctx.getString(R.string.reason_MANUAL, arg)
            Reason.PLAYING -> ctx.getString(R.string.reason_PLAYING)
            Reason.MEASURE_ERROR -> ctx.getString(R.string.reason_MEASURE_ERROR)
            Reason.MIC_MUTED -> ctx.getString(R.string.reason_MIC_MUTED)
            Reason.RAISE_UNCONFIRMED -> ctx.getString(R.string.reason_RAISE_UNCONFIRMED)
            Reason.MODE_CHANGED -> ctx.getString(R.string.reason_MODE_CHANGED)
            Reason.ERROR -> ctx.getString(R.string.reason_ERROR)
        }
    }
}
