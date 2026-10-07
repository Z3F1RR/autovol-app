package io.github.z3f1rr.autovol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telephony.TelephonyManager
import io.github.z3f1rr.autovol.core.CallAction
import io.github.z3f1rr.autovol.core.RepeatCalls

/**
 * Phone state broadcasts (needs READ_PHONE_STATE; the number comes only with READ_CALL_LOG).
 * Raises the ringer to maximum on a repeat call and restores it afterwards.
 */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        synchronized(lock) { handle(context, intent) }
    }

    private fun handle(context: Context, intent: Intent) {
        val prefs = AutoVol.prefs
        val s = prefs.repeat
        val am = context.getSystemService(AudioManager::class.java)
        val rc = RepeatCalls(prefs.callsState)
        val now = System.currentTimeMillis()
        val action = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                rc.onRinging(
                    now, number, s,
                    am.getStreamVolume(AudioManager.STREAM_RING), am.getStreamMaxVolume(AudioManager.STREAM_RING),
                )
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> rc.onOffhook()
            TelephonyManager.EXTRA_STATE_IDLE -> rc.onIdle(now, s)
            else -> CallAction.None
        }
        when (action) {
            is CallAction.Boost -> {
                if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    // Raising the ringer stream in vibrate/silent would switch the slider mode: respect it.
                    rc.state = rc.state.copy(boostedFrom = null)
                    AutoVol.log.add("${action.reason}, но режим вибро/без звука — громкость не трогаю")
                } else {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                    setRing(am, max)
                    AutoVol.log.add("${action.reason}: громкость звонка ${rc.state.boostedFrom}→$max")
                }
            }
            is CallAction.Restore -> restore(am, action.volume)
            CallAction.None -> Unit
        }
        prefs.callsState = rc.state
    }

    companion object {
        /** Broadcasts arrive on the main thread, the safety net runs on the cycle thread. */
        private val lock = Any()

        private fun setRing(am: AudioManager, v: Int) {
            try {
                am.setStreamVolume(AudioManager.STREAM_RING, v, 0)
            } catch (e: SecurityException) {
                AutoVol.log.add("не удалось изменить громкость звонка: ${e.message}")
            }
        }

        /** Back to [volume] unless the user moved the ringer while it was at maximum. */
        private fun restore(am: AudioManager, volume: Int) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            if (am.getStreamVolume(AudioManager.STREAM_RING) == max) {
                setRing(am, volume)
                AutoVol.log.add("звонок завершён: громкость звонка возвращена $max→$volume")
            } else {
                AutoVol.log.add("звонок завершён: громкость звонка изменена вручную, не возвращаю")
            }
        }

        /**
         * Safety net before each measurement cycle: if an IDLE broadcast was lost, put the ringer back
         * so the engine does not mistake our boost for a manual change.
         */
        fun restoreIfStale(ctx: Context) {
            synchronized(lock) {
                val prefs = AutoVol.prefs
                val st = prefs.callsState
                val from = st.boostedFrom ?: return
                val am = ctx.getSystemService(AudioManager::class.java)
                if (am.mode != AudioManager.MODE_NORMAL) return
                prefs.callsState = st.copy(ringing = false, boostedFrom = null)
                restore(am, from)
            }
        }
    }
}
