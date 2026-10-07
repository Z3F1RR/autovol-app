package io.github.z3f1rr.autovol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.z3f1rr.autovol.ui.MainActivity

object Notifications {
    private const val CH_STATUS = "status"
    private const val CH_RESUME = "resume"
    const val ID_STATUS = 1
    private const val ID_RESUME = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.ch_status), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESUME, ctx.getString(R.string.ch_resume), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(ctx: Context, resume: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_RESUME, resume)
        return PendingIntent.getActivity(
            ctx, if (resume) 1 else 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun status(ctx: Context): Notification {
        val st = AutoVol.status.value
        val pausedByUser = AutoVol.prefs.pauseUntilMs > System.currentTimeMillis()
        val text = shortStatus(ctx, st)
        val action = if (pausedByUser) {
            Notification.Action.Builder(null, ctx.getString(R.string.act_resume), ActionReceiver.pending(ctx, ActionReceiver.ACTION_RESUME)).build()
        } else {
            Notification.Action.Builder(null, ctx.getString(R.string.act_pause), ActionReceiver.pending(ctx, ActionReceiver.ACTION_PAUSE_1H)).build()
        }
        val b = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(text.first)
            .setContentText(text.second)
            .setStyle(Notification.BigTextStyle().bigText(text.second))
            .setContentIntent(openApp(ctx, false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    /** Title + body for the ongoing notification. */
    fun shortStatus(ctx: Context, st: Status): Pair<String, String> {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val ring = ctx.getString(
            R.string.notif_ring,
            am.getStreamVolume(android.media.AudioManager.STREAM_RING),
            am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING),
        )
        if (st.timeMs == 0L) return ctx.getString(R.string.notif_starting) to ring
        val reason = Texts.reason(ctx, st)
        val title = when {
            st.outcome == "APPLIED" && st.step != null && st.pct != null ->
                ctx.getString(R.string.notif_step, st.step, st.steps, st.pct, ring)
            st.paused -> ctx.getString(R.string.notif_paused, ring)
            else -> "AutoVol · $ring"
        }
        val body = reason ?: st.lastDb?.let { ctx.getString(R.string.notif_level, String.format(java.util.Locale.ROOT, "%.0f", it)) }
        return title to body.orEmpty()
    }

    fun update(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(ctx))
    }

    fun showResume(ctx: Context) {
        val n = Notification.Builder(ctx, CH_RESUME)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.resume_title))
            .setContentText(ctx.getString(R.string.resume_text))
            .setContentIntent(openApp(ctx, true))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RESUME, n)
    }

    fun cancelResume(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_RESUME)
    }
}
