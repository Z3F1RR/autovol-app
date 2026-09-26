package io.github.z3f1rr.autovol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires on the AlarmManager schedule and runs one cycle in the service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = AutoVolService.instance
        if (svc != null) {
            svc.requestCycle()
        } else if (AutoVol.prefs.enabled) {
            // Process was killed: try to come back (works in full-access mode).
            AutoVolService.startFromBackground(context, "будильник")
        }
    }
}

/** Notification actions: pause for one hour / resume. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_1H -> {
                AutoVol.prefs.pauseUntilMs = System.currentTimeMillis() + 3_600_000L
                AutoVol.log.add("пауза на 1 час (из уведомления)")
            }
            ACTION_RESUME -> {
                AutoVol.prefs.pauseUntilMs = 0
                AutoVol.log.add("пауза снята")
            }
            else -> return
        }
        AutoVolService.instance?.requestCycle()
    }

    companion object {
        const val ACTION_PAUSE_1H = "io.github.z3f1rr.autovol.PAUSE_1H"
        const val ACTION_RESUME = "io.github.z3f1rr.autovol.RESUME"

        fun pending(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, action.hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** After reboot or app update: restart in full-access mode, otherwise ask the user to tap. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AutoVol.prefs.enabled) return
        val why = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "загрузка" else "обновление приложения"
        AutoVolService.startFromBackground(context, why)
    }
}
