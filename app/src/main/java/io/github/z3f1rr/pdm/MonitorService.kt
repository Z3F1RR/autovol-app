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
import java.util.concurrent.Executors

/**
 * Foreground service with two independent loops: one polls the modems (local, cheap), the other
 * checks the internet through the router (can take seconds when packets vanish). Every network call
 * has timeouts and every iteration is wrapped in try/catch, so one failure never stops monitoring.
 */
class MonitorService : Service() {
    private val modemLock = Object()
    private val netLock = Object()
    private val actions = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val history = List(2) { ArrayDeque<Point>() }

    @Volatile private var running = false
    @Volatile private var modems: List<ModemState> = emptyList()

    // Internet state: a change is accepted only after two checks in a row agree.
    @Volatile private var confirmed: Net? = null
    @Volatile private var latency: Int? = null
    @Volatile private var since = 0L
    private var pending: Net? = null
    @Volatile private var recheckSoon = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        Monitor.post { if (Monitor.speed.value == null) Monitor.speed.value = Monitor.loadSpeed(this) }
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
            ACTION_SPEED -> actions.execute { speedTest() }
        }
        startLoops()
        wake()
        return START_STICKY
    }

    private fun startLoops() {
        if (running) return
        try {
            val n = buildStatus(null)
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
        Thread({ loop(modemLock) { pollModems(); Monitor.intervalSec(this) * 1000L } }, "pdm-modems")
            .apply { isDaemon = true; start() }
        Thread({ loop(netLock) { checkNet(); if (recheckSoon) 3000L else Monitor.intervalSec(this) * 1000L } }, "pdm-net")
            .apply { isDaemon = true; start() }
    }

    private fun loop(lock: Object, body: () -> Long) {
        while (running) {
            val wait = try {
                body()
            } catch (t: Throwable) {
                5000L
            }
            synchronized(lock) { if (running) lock.wait(wait) }
        }
    }

    private fun wake() {
        synchronized(modemLock) { modemLock.notifyAll() }
        synchronized(netLock) { netLock.notifyAll() }
    }

    private fun pollModems() {
        val wifi = Monitor.wifiNetwork(this)
        val states = Monitor.config(this).map { m ->
            try {
                HiLink(m.ip, wifi).read()
            } catch (e: Exception) {
                ModemState(ok = false, error = e.javaClass.simpleName)
            }
        }
        modems = states
        val now = System.currentTimeMillis()
        val hist = synchronized(history) {
            states.forEachIndexed { i, s ->
                val h = history[i]
                if (s.ok && s.connected) h.addLast(Point(now, s.rsrp, s.sinr))
                while (h.isNotEmpty() && now - h.first().t > 30 * 60_000) h.removeFirst()
            }
            history.map { it.toList() }
        }
        Monitor.post { Monitor.history.value = hist }
        publish()
    }

    private fun checkNet() {
        val (raw, ms) = Monitor.check(Monitor.wifiNetwork(this), Monitor.server(this))
        val now = System.currentTimeMillis()
        val prev = confirmed
        var changed = false
        when {
            prev == null || raw == Net.NO_WIFI || prev == Net.NO_WIFI -> {
                if (raw != prev) { since = now; changed = prev != null }
                confirmed = raw; pending = null
            }
            raw == prev -> pending = null
            pending == raw -> { confirmed = raw; since = now; pending = null; changed = true }
            else -> pending = raw
        }
        recheckSoon = pending != null
        if (confirmed == raw) latency = ms
        publish()
        val cur = confirmed
        if (changed && cur != null && cur != Net.NO_WIFI && prev != Net.NO_WIFI) {
            getSystemService(NotificationManager::class.java).notify(ID_ALERT, buildAlert(cur))
        }
    }

    @Synchronized
    private fun publish() {
        val net = confirmed ?: return
        val snap = Snapshot(modems, net, latency, System.currentTimeMillis(), since)
        Monitor.post { Monitor.snapshot.value = snap }
        try {
            getSystemService(NotificationManager::class.java).notify(ID_STATUS, buildStatus(snap))
        } catch (_: Exception) {
        }
    }

    private fun speedTest() {
        Monitor.post { Monitor.speedRunning.value = true }
        val s = Monitor.speedTest(Monitor.wifiNetwork(this), Monitor.server(this))
        if (s.error == null) Monitor.saveSpeed(this, s)
        Monitor.post {
            Monitor.speedRunning.value = false
            if (s.error == null) Monitor.speed.value = s else Monitor.message.value = s.error
        }
    }

    private fun modemAction(i: Int, reboot: Boolean) {
        val cfg = Monitor.config(this).getOrNull(i) ?: return
        val msg = try {
            val h = HiLink(cfg.ip, Monitor.wifiNetwork(this))
            if (reboot) {
                h.reboot()
                "${cfg.name} перезагружается, около минуты"
            } else {
                h.reconnect()
                "${cfg.name} переподключён"
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
        Monitor.post { Monitor.running.value = false; Monitor.snapshot.value = null }
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

    private fun buildStatus(s: Snapshot?): Notification {
        val cfg = Monitor.config(this)
        val body = if (s == null) "Проверяю…" else s.modems.mapIndexed { i, m -> line(cfg.getOrNull(i)?.name ?: "?", m) }.joinToString("\n")
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (s == null) "Вагон" else title(s))
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(action("Обновить", Intent(this, MonitorService::class.java), 10))
            .build()
    }

    private fun buildAlert(net: Net): Notification =
        Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(
                when (net) {
                    Net.FULL -> "Интернет вернулся"
                    Net.WHITELIST -> "Только белые списки"
                    else -> "Интернет пропал"
                },
            )
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
        const val ACTION_SPEED = "pdm.SPEED"
        const val EXTRA_INDEX = "index"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2

        fun title(s: Snapshot): String {
            val min = (s.time - s.since) / 60_000
            val dur = if (min > 0) " · $min мин" else ""
            return when (s.net) {
                Net.FULL -> "Интернет есть" + (s.latencyMs?.let { " · $it мс" } ?: "")
                Net.WHITELIST -> "Только белые списки$dur"
                Net.NONE -> "Нет интернета$dur"
                Net.NO_WIFI -> "Телефон не в Wi-Fi вагона"
            }
        }

        fun line(name: String, m: ModemState): String {
            if (!m.ok) return "$name — не отвечает"
            if (!m.connected) return "$name — нет сети"
            val band = if (m.band.isNotEmpty()) " B${m.band}" else ""
            return "$name ${m.net}$band · ${m.rsrp ?: "—"} / ${m.sinr ?: "—"} дБ"
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Статус сети", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Изменение доступа в интернет", NotificationManager.IMPORTANCE_HIGH).apply {
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
