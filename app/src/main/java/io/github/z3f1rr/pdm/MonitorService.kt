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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * Foreground service with independent loops: modems (local, cheap), internet (can take seconds when
 * packets vanish) and, while it runs, the UPS test. Off the train Wi-Fi the modem and internet loops
 * pause, release the wake locks and sleep until Android reports a Wi-Fi change.
 */
class MonitorService : Service() {
    private val modemLock = Object()
    private val netLock = Object()
    private val upsLock = Object()
    private val actions = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val history = List(2) { ArrayDeque<Point>() }
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var running = false
    @Volatile private var modems: List<ModemState> = emptyList()
    @Volatile private var onTrain = false
    @Volatile private var lastPlace = Place.NO_WIFI
    @Volatile private var upsThread: Thread? = null

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
        val speed = Monitor.loadSpeed(this)
        val ups = Monitor.loadUps(this)
        Monitor.post {
            if (Monitor.speed.value == null) Monitor.speed.value = speed
            Monitor.ups.value = ups
        }
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
            ACTION_UPS_START -> {
                val now = System.currentTimeMillis()
                val u = Ups(start = now, lastSeen = now)
                Monitor.saveUps(this, u)
                Monitor.post { Monitor.ups.value = u }
            }
            ACTION_UPS_STOP -> finishUps(manual = true)
            ACTION_UPS_RESET -> {
                Monitor.saveUps(this, null)
                Monitor.post { Monitor.ups.value = null }
            }
        }
        startLoops()
        ensureUpsLoop()
        updateLocks()
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
        Monitor.post { Monitor.running.value = true }
        registerWifiCallback()
        Thread({ loop(modemLock) { pollModems() } }, "pdm-modems").apply { isDaemon = true; start() }
        Thread({ loop(netLock) { checkNet() } }, "pdm-net").apply { isDaemon = true; start() }
    }

    /** Runs [body] again after the delay it returns (ms); a Wi-Fi change or "refresh" wakes it early. */
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
        synchronized(upsLock) { upsLock.notifyAll() }
    }

    private fun registerWifiCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Monitor.resetPlace(); wake() }
            override fun onLost(network: Network) { Monitor.resetPlace(); wake() }
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) { Monitor.resetPlace(); wake() }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
            )
            callback = cb
        } catch (_: Exception) {
        }
    }

    /** Wake locks only while there is something to watch: on the train Wi-Fi, or during the UPS test. */
    @Synchronized
    private fun updateLocks() {
        val need = running && (onTrain || upsRunning())
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pdm:monitor").apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        @Suppress("DEPRECATION")
        val wf = wifiLock ?: getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pdm:wifi")?.apply { setReferenceCounted(false) }
            ?.also { wifiLock = it }
        try {
            if (need) {
                if (!wl.isHeld) wl.acquire()
                if (wf != null && !wf.isHeld) wf.acquire()
            } else {
                if (wl.isHeld) wl.release()
                if (wf != null && wf.isHeld) wf.release()
            }
        } catch (_: Exception) {
        }
    }

    /** Checks where we are; off the train it publishes the pause and returns false. */
    private fun atTrain(): Network? {
        val (place, network) = Monitor.place(this)
        lastPlace = place
        val was = onTrain
        onTrain = place == Place.TRAIN
        if (was != onTrain) updateLocks()
        if (onTrain) return network
        val paused = if (place == Place.NO_WIFI) Net.NO_WIFI else Net.FOREIGN
        if (confirmed != paused) {
            confirmed = paused; pending = null; latency = null; since = System.currentTimeMillis()
        }
        modems = emptyList()
        publish()
        return null
    }

    private fun pollModems(): Long {
        val wifi = atTrain() ?: return pausedWait()
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
        return Monitor.intervalSec(this) * 1000L
    }

    private fun checkNet(): Long {
        val wifi = atTrain() ?: return pausedWait()
        val (raw, ms) = Monitor.check(wifi, Monitor.server(this))
        val now = System.currentTimeMillis()
        val prev = confirmed
        val paused = prev == null || prev == Net.NO_WIFI || prev == Net.FOREIGN
        var changed = false
        when {
            paused -> {
                if (raw != prev) since = now
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
        if (changed && cur != null) {
            getSystemService(NotificationManager::class.java).notify(ID_ALERT, buildAlert(cur))
        }
        return if (recheckSoon) 3000L else Monitor.intervalSec(this) * 1000L
    }

    /** No Wi-Fi: sleep until Android reports one. Other Wi-Fi: look again in a minute. */
    private fun pausedWait(): Long = if (lastPlace == Place.NO_WIFI) 10 * 60_000L else 60_000L

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

    // ---------------------------------------------------------------- UPS test

    private fun upsRunning(): Boolean = Monitor.loadUps(this)?.let { it.end == null } == true

    private fun ensureUpsLoop() {
        if (!running || !upsRunning() || upsThread?.isAlive == true) return
        upsThread = Thread({ loop(upsLock) { upsTick() } }, "pdm-ups").apply { isDaemon = true; start() }
    }

    /** Router answering → still on battery; silent for 5 minutes → the UPS is empty. */
    private fun upsTick(): Long {
        val u = Monitor.loadUps(this) ?: return Long.MAX_VALUE / 4
        if (u.end != null) return Long.MAX_VALUE / 4
        val now = System.currentTimeMillis()
        val next = if (Monitor.routerAlive(this)) {
            u.copy(lastSeen = now, downSince = null, gaps = if (u.downSince != null) u.gaps + 1 else u.gaps)
        } else {
            val down = u.downSince ?: now
            if (now - down >= UPS_DEAD_MS) u.copy(end = u.lastSeen, downSince = down) else u.copy(downSince = down)
        }
        Monitor.saveUps(this, next)
        Monitor.post { Monitor.ups.value = next }
        if (next.end != null) {
            updateLocks()
            getSystemService(NotificationManager::class.java).notify(ID_ALERT, buildUpsDone(next))
            return Long.MAX_VALUE / 4
        }
        return 5000L
    }

    private fun finishUps(manual: Boolean) {
        val u = Monitor.loadUps(this) ?: return
        if (u.end != null) return
        val done = u.copy(end = if (manual && u.downSince == null) System.currentTimeMillis() else u.lastSeen)
        Monitor.saveUps(this, done)
        Monitor.post { Monitor.ups.value = done }
        updateLocks()
    }

    // ---------------------------------------------------------------- actions

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

    private fun releaseAll() {
        running = false
        wake()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { callback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        callback = null
    }

    private fun stopAll() {
        releaseAll()
        Monitor.post { Monitor.running.value = false; Monitor.snapshot.value = null }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseAll()
        actions.shutdownNow()
        Monitor.post { Monitor.running.value = false }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun buildStatus(s: Snapshot?): Notification {
        val cfg = Monitor.config(this)
        val paused = s?.net == Net.NO_WIFI || s?.net == Net.FOREIGN
        val body = when {
            s == null -> "Проверяю…"
            s.net == Net.NO_WIFI -> "Wi-Fi выключен или нет сети рядом"
            s.net == Net.FOREIGN -> "Телефон подключён к другой сети"
            else -> s.modems.mapIndexed { i, m -> line(cfg.getOrNull(i)?.name ?: "?", m) }.joinToString("\n")
        }
        val b = Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (s == null) "Вагон" else title(s))
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
        if (!paused) b.addAction(action("Обновить", Intent(this, MonitorService::class.java), 10))
        return b.build()
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

    private fun buildUpsDone(u: Ups): Notification =
        Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ИБП продержал роутер " + fmtDuration((u.end ?: u.lastSeen) - u.start))
            .setAutoCancel(true)
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
        const val ACTION_UPS_START = "pdm.UPS_START"
        const val ACTION_UPS_STOP = "pdm.UPS_STOP"
        const val ACTION_UPS_RESET = "pdm.UPS_RESET"
        const val EXTRA_INDEX = "index"
        const val UPS_DEAD_MS = 5 * 60_000L
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2

        fun fmtDuration(ms: Long): String {
            val m = ms / 60_000
            return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
        }

        fun title(s: Snapshot): String {
            val min = (s.time - s.since) / 60_000
            val dur = if (min > 0) " · $min мин" else ""
            return when (s.net) {
                Net.FULL -> "Интернет есть" + (s.latencyMs?.let { " · $it мс" } ?: "")
                Net.WHITELIST -> "Только белые списки$dur"
                Net.NONE -> "Нет интернета$dur"
                Net.NO_WIFI, Net.FOREIGN -> "Проверки на паузе"
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
