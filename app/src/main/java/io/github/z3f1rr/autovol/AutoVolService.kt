package io.github.z3f1rr.autovol

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.z3f1rr.autovol.core.CycleResult
import io.github.z3f1rr.autovol.core.Outcome
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the measurement cycle. It holds no permanent wake lock: every cycle
 * is triggered by an AlarmManager alarm and runs under a 60 s partial wake lock.
 */
class AutoVolService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock
    private val queued = AtomicBoolean(false)
    private var foreground = false
    private var lastNote: String? = null
    private var diagLogged = false

    @Volatile
    private var diagRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { r -> Thread(r, "autovol-cycle") }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autovol:cycle")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true
        if (!AutoVol.prefs.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if ((!foreground || fromUi) && !goForeground(fromUi, intent?.getStringExtra(EXTRA_REASON) ?: "перезапуск системой")) {
            Notifications.showResume(this)
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        AutoVol.setServiceRunning(true)
        Notifications.cancelResume(this)
        requestCycle()
        return START_STICKY
    }

    /** Picks the foreground service type allowed in the current situation. */
    private fun goForeground(fromUi: Boolean, reason: String): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AutoVol.log.add("нет разрешения на микрофон — сервис не запущен")
            return false
        }
        val full = MicAccess.level(this) == MicAccess.Level.FULL
        val types = mutableListOf<Int>()
        if (fromUi || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (full) {
            // Boot-safe type; the appop lets us record from the background anyway (проверить).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                types += ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        if (types.isEmpty()) {
            AutoVol.log.add("$reason: базовый режим, нужен запуск из приложения")
            return false
        }
        for (type in types.distinct()) {
            try {
                startForeground(Notifications.ID_STATUS, Notifications.status(this), type)
                foreground = true
                AutoVol.log.add(
                    "сервис запущен ($reason), тип ${typeName(type)}, доступ к микрофону: " +
                        MicAccess.level(this).name.lowercase(),
                )
                return true
            } catch (e: Exception) {
                AutoVol.log.add("startForeground(${typeName(type)}) отклонён: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return false
    }

    /**
     * Runs one cycle soon. Safe to call from any thread; requests arriving mid-cycle are merged.
     * [diag] logs measurement diagnostics after the cycle ("Проверить сейчас"); [lateSec] is how late
     * the alarm was delivered.
     */
    fun requestCycle(diag: Boolean = false, lateSec: Int? = null) {
        if (diag) diagRequested = true
        if (lateSec != null) {
            lastLateSec = lateSec
            maxLateSec = maxOf(maxLateSec, lateSec)
        }
        if (!queued.compareAndSet(false, true)) return
        wakeLock.acquire(WAKE_MS)
        try {
            executor.execute {
                queued.set(false)
                try {
                    runCycle()
                } finally {
                    wakeLock.release()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            queued.set(false)
            wakeLock.release()
        }
    }

    private fun runCycle() {
        CallReceiver.restoreIfStale(this)
        Root.reapplyIfNeeded(this)
        val settings = AutoVol.prefs.settings()
        val engine = AutoVol.engine
        val r = try {
            engine.cycle(settings)
        } catch (e: Exception) {
            AutoVol.log.add("исключение: ${e.stackTraceToString().lines().take(3).joinToString(" | ")}")
            CycleResult(120, "исключение: ${e.javaClass.simpleName} ${e.message}", Outcome.SKIPPED)
        }
        if (r.note != lastNote || " дБ" in r.note) AutoVol.log.add(r.note)
        if (!diagLogged || diagRequested) {
            AutoVol.platform.meter.last?.let {
                diagLogged = true
                diagRequested = false
                AutoVol.log.add("замер: ${AutoVol.platform.meter.diagnostics()}")
            }
        }
        lastNote = r.note
        if (!AutoVol.prefs.enabled || instance !== this) return
        Scheduler.schedule(this, r.waitSec)
        val st = engine.state
        val lv = st.levels.ifEmpty { settings.levels }
        AutoVol.publish(
            Status(
                timeMs = System.currentTimeMillis(),
                note = r.note,
                outcome = r.outcome.name,
                lastDb = st.lastDb,
                step = st.step,
                steps = lv.size - 1,
                pct = st.step?.let { lv.getOrNull(it)?.pct },
                nextAtMs = System.currentTimeMillis() + r.waitSec * 1000L,
                samples = engine.history.size,
                calFloor = st.cal?.floorDb,
                calTop = st.cal?.topDb,
                calWeight = st.cal?.weight ?: 0.0,
            ),
        )
        // The service may have been stopped while measuring: do not resurrect its notification.
        if (instance === this && AutoVol.prefs.enabled) Notifications.update(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        maxLateSec = 0
        Scheduler.cancel(this)
        executor.shutdownNow()
        if (wakeLock.isHeld) wakeLock.release()
        AutoVol.setServiceRunning(false)
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_FROM_UI = "from_ui"
        private const val EXTRA_REASON = "reason"
        private const val WAKE_MS = 60_000L
        const val REASON_BOOT = "загрузка"

        @Volatile
        var instance: AutoVolService? = null
            private set

        /** Alarm delivery delay, seconds: last one and the worst since the service started. */
        @Volatile
        var lastLateSec = 0
            private set

        @Volatile
        var maxLateSec = 0
            private set

        private fun typeName(t: Int) = when (t) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE -> "microphone"
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE -> "specialUse"
            else -> t.toString()
        }

        /** Start from a visible activity: microphone-type FGS is allowed. */
        fun startFromUi(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, AutoVolService::class.java).putExtra(EXTRA_FROM_UI, true).putExtra(EXTRA_REASON, "из приложения"),
            )
        }

        /** Start after boot/update/process death. Falls back to a "tap to resume" notification. */
        fun startFromBackground(ctx: Context, reason: String) {
            if (MicAccess.level(ctx) != MicAccess.Level.FULL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AutoVol.log.add(
                    "$reason: базовый режим (RECORD_AUDIO=${MicAccess.rawMode(ctx)}) — ждём нажатия на уведомление",
                )
                Notifications.showResume(ctx)
                // Right after boot the appop may not be settled yet: check once more in 2 minutes.
                if (reason == REASON_BOOT) Scheduler.schedule(ctx, 120)
                return
            }
            try {
                ctx.startForegroundService(
                    Intent(ctx, AutoVolService::class.java).putExtra(EXTRA_REASON, reason),
                )
            } catch (e: Exception) {
                AutoVol.log.add("$reason: запуск сервиса отклонён: ${e.javaClass.simpleName} ${e.message}")
                Notifications.showResume(ctx)
            }
        }

        fun stop(ctx: Context) {
            Scheduler.cancel(ctx)
            ctx.stopService(Intent(ctx, AutoVolService::class.java))
        }
    }
}
