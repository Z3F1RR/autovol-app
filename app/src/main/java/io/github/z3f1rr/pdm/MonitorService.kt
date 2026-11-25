package io.github.z3f1rr.pdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service: polls the modems every few seconds on its own thread. Every network call has
 * timeouts and every cycle is wrapped in try/catch, so one failure never stops the monitoring.
 */
class MonitorService : Service() {
    private val lock = Object()
    private var thread: Thread? = null
    private val actions = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val history = List(2) { ArrayDeque<Point>() }

    @Volatile private var running = false
    @Volatile private var wasOnline: Boolean? = null
    @Volatile private var downSince: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_REBOOT, ACTION_RECONNECT -> {
                val i = intent.getIntExtra(EXTRA_INDEX, 0)
                val reboot = intent.action == ACTION_REBOOT
                actions.execute { modemAction(i, reboot) }
            }
        }
        startLoop()
        wake()
        return START_STICKY
    }

    private fun startLoop() {
        if (running) return
        val n = buildStatus(null, "Запуск…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID_STATUS, n)
            }
        } catch (e: Exception) {
            // Android refused a background restart: wait until the app is opened again.
            stopSelf()
            return
        }
        running = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pdm:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pdm:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        Monitor.post { Monitor.running.value = true }
        thread = Thread({ loop() }, "pdm-monitor").apply { isDaemon = true; start() }
    }

    private fun loop() {
        while (running) {
            try {
                cycle()
            } catch (t: Throwable) {
                Monitor.post { Monitor.message.value = "Ошибка опроса: ${t.message}" }
            }
            synchronized(lock) {
                if (running) lock.wait(Monitor.intervalSec(this) * 1000L)
            }
        }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun cycle() {
        val cfg = Monitor.config(this)
        val net = Monitor.wifiNetwork(this)
        val states = cfg.map { m ->
            try {
                HiLink(m.ip, net).read()
            } catch (e: Exception) {
                ModemState(ok = false, error = e.javaClass.simpleName)
            }
        }
        val ms = Monitor.internetMs(net)
        val online = ms != null
        val now = System.currentTimeMillis()
        if (online) downSince = null else if (downSince == null) downSince = now
        val snap = Snapshot(states, ms, net != null, now, downSince)

        states.forEachIndexed { i, s ->
            val h = history[i]
            if (s.ok) h.addLast(Point(now, s.rsrp, s.sinr))
            while (h.isNotEmpty() && now - h.first().t > 30 * 60_000) h.removeFirst()
        }
        val hist = history.map { it.toList() }
        Monitor.post {
            Monitor.snapshot.value = snap
            Monitor.history.value = hist
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(ID_STATUS, buildStatus(snap, null))
        val was = wasOnline
        if (was != null && was != online) {
            nm.notify(ID_ALERT, buildAlert(online, snap))
        }
        wasOnline = online
    }

    private fun modemAction(i: Int, reboot: Boolean) {
        val cfg = Monitor.config(this).getOrNull(i) ?: return
        val msg = try {
            val h = HiLink(cfg.ip, Monitor.wifiNetwork(this))
            if (reboot) {
                h.reboot()
                "${cfg.name}: перезагружается, это около минуты"
            } else {
                h.reconnect()
                "${cfg.name}: передача данных перезапущена"
            }
        } catch (e: Exception) {
            "${cfg.name}: не удалось — ${e.message ?: e.javaClass.simpleName}"
        }
        Monitor.post { Monitor.message.value = msg }
        wake()
    }

    private fun stopAll() {
        running = false
        wake()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        Monitor.post { Monitor.running.value = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        wake()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        actions.shutdownNow()
        Monitor.post { Monitor.running.value = false }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun title(s: Snapshot): String = when {
        !s.onWifi -> "⚪ Телефон не в Wi-Fi роутера"
        s.internetMs != null -> "🟢 Интернет есть · ${s.internetMs} мс"
        else -> "🔴 Интернета нет " + ((System.currentTimeMillis() - (s.downSince ?: s.time)) / 60_000) + " мин"
    }

    private fun buildStatus(s: Snapshot?, text: String?): Notification {
        val cfg = Monitor.config(this)
        val body = text ?: buildString {
            s!!.modems.forEachIndexed { i, m -> appendLine(line(cfg[i].name, m)) }
            append("обновлено " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(s.time)))
        }
        val b = Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (s == null) "ПДМ-статус" else title(s))
            .setContentText(body.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(action("Обновить", Intent(this, MonitorService::class.java), 10))
        cfg.forEachIndexed { i, m ->
            val it = Intent(this, MonitorService::class.java).setAction(ACTION_REBOOT).putExtra(EXTRA_INDEX, i)
            b.addAction(action("↻ ${m.name}", it, 20 + i))
        }
        return b.build()
    }

    private fun buildAlert(online: Boolean, s: Snapshot): Notification =
        Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (online) "Интернет появился" else "Интернет пропал")
            .setContentText(title(s))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000)
            .setContentIntent(openApp())
            .build()

    private fun action(title: String, intent: Intent, code: Int): Notification.Action {
        val pi = PendingIntent.getService(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), title, pi).build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_STOP = "pdm.STOP"
        const val ACTION_REBOOT = "pdm.REBOOT"
        const val ACTION_RECONNECT = "pdm.RECONNECT"
        const val EXTRA_INDEX = "index"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2
        private val BARS = arrayOf("▁▁▁▁", "▂▁▁▁", "▂▄▁▁", "▂▄▆▁", "▂▄▆█")

        fun line(name: String, m: ModemState): String {
            if (!m.ok) return "$name: модем не отвечает"
            val head = "$name ${m.net} ${BARS[m.quality]} ${m.qualityWord}"
            if (!m.connected) return "$head · не подключён"
            val band = if (m.band.isNotEmpty()) " B${m.band}" else ""
            return "$head$band · RSRP ${m.rsrp ?: "—"} SINR ${m.sinr ?: "—"} · ↓%.1f ↑%.1f"
                .format(Locale.US, m.dlMbps, m.ulMbps)
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Статус сети", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Интернет пропал / появился", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                },
            )
        }

        fun start(ctx: Context, action: String? = null, index: Int = 0) {
            val i = Intent(ctx, MonitorService::class.java).setAction(action).putExtra(EXTRA_INDEX, index)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
