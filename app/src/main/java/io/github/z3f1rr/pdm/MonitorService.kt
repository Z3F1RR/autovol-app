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
 * Foreground service with independent loops: signal (modems or the phone's own radio), internet (can
 * take seconds when packets vanish) and, while it runs, the UPS test. It checks the train Wi-Fi, and
 * if enabled other Wi-Fi networks and mobile data. With nothing to check it releases the wake locks
 * and sleeps until Android reports a network change.
 */
class MonitorService : Service() {
    private val modemLock = Object()
    private val netLock = Object()
    private val upsLock = Object()
    private val routerLock = Object()
    private val actions = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val history = List(2) { ArrayDeque<Point>() }
    private val localHist = ArrayDeque<Point>()
    private val ssids = HashMap<Network, String?>()

    /** The link being checked. */
    private data class Ctx(val where: Where, val network: Network, val label: String)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var running = false
    @Volatile private var modems: List<ModemState> = emptyList()
    @Volatile private var current: Ctx? = null
    @Volatile private var local: Local? = null
    @Volatile private var pausedLabel = ""
    @Volatile private var lastPlace = Place.NO_WIFI
    @Volatile private var upsThread: Thread? = null

    // Internet state: a change is accepted only after two checks in a row agree.
    @Volatile private var confirmed: Net? = null
    @Volatile private var latency: Int? = null
    @Volatile private var why = ""
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
        if (running) {
            // e.g. location was just allowed: re-declare the foreground types
            try { promote(Monitor.snapshot.value) } catch (_: Exception) {}
            return
        }
        try {
            promote(null)
        } catch (e: Exception) {
            // Android refused a background restart: wait until the app is opened again.
            stopSelf()
            return
        }
        running = true
        Monitor.post { Monitor.running.value = true }
        registerNetworkCallback()
        Thread({ loop(modemLock) { pollModems() } }, "pdm-modems").apply { isDaemon = true; start() }
        Thread({ loop(netLock) { checkNet() } }, "pdm-net").apply { isDaemon = true; start() }
        Thread({ loop(routerLock) { pollRouter() } }, "pdm-router").apply { isDaemon = true; start() }
        Ops.loadBalances(this)
    }

    private fun promote(s: Snapshot?) {
        run {
            val n = buildStatus(s)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // location type only when allowed: then the route log gets coordinates with the screen off
                val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                try {
                    startForeground(ID_STATUS, n, if (hasLocation()) special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else special)
                } catch (e: SecurityException) {
                    startForeground(ID_STATUS, n, special)
                }
            } else {
                startForeground(ID_STATUS, n)
            }
        }
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
        synchronized(routerLock) { routerLock.notifyAll() }
    }

    /** Wi-Fi and mobile networks appearing or disappearing wake the loops at once. */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Monitor.resetPlace(); wake() }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                // lockdown VPN: the bare link is closed for us, requests have to go through the VPN
                if (Monitor.blocked.put(network, blocked) != blocked) { Monitor.resetPlace(); wake() }
            }
            override fun onLost(network: Network) {
                Monitor.blocked.remove(network)
                Monitor.resetPlace()
                synchronized(ssids) { ssids.remove(network) }
                wake()
            }
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) { Monitor.resetPlace(); wake() }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build(),
                cb,
            )
            callback = cb
        } catch (_: Exception) {
        }
    }

    /**
     * CPU wake lock while something is being checked or the UPS test runs; the low-latency Wi-Fi lock
     * only on the train Wi-Fi and during the UPS test.
     */
    @Synchronized
    private fun updateLocks() {
        val ups = upsRunning()
        val need = running && (current != null || ups)
        val needWifi = running && (current?.where == Where.TRAIN || ups)
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pdm:monitor").apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        @Suppress("DEPRECATION")
        val wf = wifiLock ?: getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pdm:wifi")?.apply { setReferenceCounted(false) }
            ?.also { wifiLock = it }
        try {
            if (need) { if (!wl.isHeld) wl.acquire() } else if (wl.isHeld) wl.release()
            if (wf != null) { if (needWifi) { if (!wf.isHeld) wf.acquire() } else if (wf.isHeld) wf.release() }
        } catch (_: Exception) {
        }
    }

    /**
     * Picks the link to check: the train Wi-Fi first, then another Wi-Fi, then mobile data (each only
     * if enabled). Returns null and publishes the pause when there is nothing to check. Switching to
     * another link starts the state over, without an alert: "internet appeared" on a new network is noise.
     */
    @Synchronized
    private fun resolve(): Ctx? {
        val (place, wifi) = Monitor.place(this)
        lastPlace = place
        val c0 = when {
            place == Place.TRAIN && wifi != null -> Ctx(Where.TRAIN, wifi, "Wi-Fi вагона")
            place == Place.FOREIGN && wifi != null && Monitor.otherWifi(this) ->
                Ctx(Where.WIFI, wifi, ssid(wifi)?.let { "Wi-Fi «$it»" } ?: "Другой Wi-Fi")
            place == Place.NO_WIFI && Monitor.mobile(this) -> Monitor.cellNetwork(this)?.let {
                val op = Radio.cell(this).name
                Ctx(Where.MOBILE, it, if (op.isNotEmpty()) "Мобильный · $op" else "Мобильный интернет")
            }
            else -> null
        }
        // VPN with «Блокировать соединения без VPN»: the bare link is closed for us, everything goes through the VPN
        val c = c0?.let { if (Monitor.viaVpn(this, it.network)) it.copy(label = it.label + " · через VPN") else it }
        val prev = current
        val now = System.currentTimeMillis()
        if (prev?.where != c?.where || prev?.network != c?.network) {
            confirmed = null; pending = null; latency = null; why = ""; recheckSoon = false; since = now
            modems = emptyList(); local = null
            synchronized(history) { history.forEach { it.clear() }; localHist.clear() }
            Monitor.post { Monitor.history.value = emptyList(); Monitor.localHistory.value = emptyList() }
        }
        current = c
        if (prev?.where != c?.where) updateLocks()
        if (c != null) return c
        val paused = if (place == Place.FOREIGN) Net.FOREIGN else Net.NO_WIFI
        pausedLabel = when {
            paused == Net.FOREIGN -> "Другой Wi-Fi · проверка выключена в настройках"
            Monitor.mobile(this) -> "Нет ни Wi-Fi, ни мобильного интернета"
            else -> "Wi-Fi выключен или сети вагона нет рядом"
        }
        if (confirmed != paused) { confirmed = paused; since = now }
        publish()
        return null
    }

    /** SSID through root, once per network. Without root (or if disabled) — null. */
    private fun ssid(n: Network): String? {
        if (!Monitor.rootSsid(this)) return null
        synchronized(ssids) { if (ssids.containsKey(n)) return ssids[n] }
        val v = Root.ssid()
        synchronized(ssids) { ssids[n] = v }
        return v
    }

    @Suppress("UNUSED_PARAMETER")
    private fun interval(c: Ctx): Long = Monitor.intervalSec(this) * 1000L

    private fun pollModems(): Long {
        val c = resolve() ?: return pausedWait()
        val now = System.currentTimeMillis()
        if (c.where != Where.TRAIN) {
            val l = if (c.where == Where.WIFI) Radio.wifi(this, c.network, ssid(c.network)) else Radio.cell(this)
            local = l
            val hist = synchronized(history) {
                // Wi-Fi: dBm goes in the RSRP slot, the chart scales it separately
                localHist.addLast(Point(now, if (c.where == Where.WIFI) l.dbm else l.rsrp ?: l.dbm, l.sinr))
                while (localHist.isNotEmpty() && now - localHist.first().t > 30 * 60_000) localHist.removeFirst()
                localHist.toList()
            }
            Monitor.post { Monitor.localHistory.value = hist }
            publish()
            return interval(c)
        }
        val states = Monitor.config(this).map { m ->
            try {
                HiLink(m.ip, Monitor.via(this, c.network)).read()
            } catch (e: Exception) {
                ModemState(ok = false, error = e.javaClass.simpleName)
            }
        }
        modems = states
        heal(states, now)
        watchSms(states)
        logRoute(states, now)
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
        return interval(c)
    }

    private fun checkNet(): Long {
        val c = resolve() ?: return pausedWait()
        val (raw, ms, reasons) = Monitor.check(Monitor.via(this, c.network), Monitor.server(this))
        // the link changed while we were checking: drop this result
        if (current?.network != c.network) return 1000L
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
        if (confirmed == raw) { latency = ms; why = reasons }
        publish()
        val cur = confirmed
        if (changed && cur != null) {
            getSystemService(NotificationManager::class.java).notify(ID_ALERT, buildAlert(cur, c.label))
        }
        return if (recheckSoon) 3000L else interval(c)
    }

    /** Nothing to check: sleep until Android reports a network change, look again now and then anyway. */
    private fun pausedWait(): Long = if (lastPlace == Place.FOREIGN) 60_000L else 10 * 60_000L

    @Synchronized
    private fun publish() {
        val net = confirmed ?: return
        val c = current
        val snap = Snapshot(
            modems = if (c?.where == Where.TRAIN) modems else emptyList(),
            net = net, latencyMs = latency, time = System.currentTimeMillis(), since = since,
            where = c?.where, label = c?.label ?: pausedLabel, local = if (c != null && c.where != Where.TRAIN) local else null,
            why = if (c != null && net == Net.NONE) why else "",
        )
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
        val s = Monitor.speedTest(current?.network?.let { Monitor.via(this, it) }, Monitor.server(this))
        if (s.error == null) Monitor.saveSpeed(this, s)
        Monitor.post {
            Monitor.speedRunning.value = false
            if (s.error == null) Monitor.speed.value = s else Monitor.message.value = s.error
        }
    }

    private fun modemAction(i: Int, reboot: Boolean) {
        val cfg = Monitor.config(this).getOrNull(i) ?: return
        val msg = try {
            val h = HiLink(cfg.ip, Monitor.wifiNetwork(this)?.let { Monitor.via(this, it) })
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

    // ---------------------------------------------------------------- router (Keenetic)

    private var lastBalanceTry = 0L

    private fun pollRouter(): Long {
        val c = current
        if (c?.where != Where.TRAIN) {
            Monitor.post { Ops.router.value = null }
            return 60_000L
        }
        val k = Ops.keenetic(this)
        if (k != null) {
            val prev = Ops.router.value
            val next = try {
                k.poll(prev, Monitor.config(this), Ops.autoRegister(this))
            } catch (e: Exception) {
                (prev ?: RouterState()).copy(error = Monitor.short(e).substringAfter(": "))
            }
            Monitor.post { Ops.router.value = next }
        }
        autoBalance()
        return Monitor.intervalSec(this) * 1000L
    }

    /** Balance of every SIM every 6 hours; an alert when it drops below 100 ₽. */
    private fun autoBalance() {
        val now = System.currentTimeMillis()
        if (now - lastBalanceTry < 30 * 60_000) return
        lastBalanceTry = now
        modems.forEachIndexed { i, m ->
            if (!m.ok || !m.connected) return@forEachIndexed
            val old = Ops.balances.value[i]
            if (old != null && now - old.time < 6 * 3600_000L) return@forEachIndexed
            val b = try { Ops.balance(this, i) } catch (e: Exception) { return@forEachIndexed }
            val name = Monitor.config(this).getOrNull(i)?.name ?: "SIM"
            if (b.rub != null && b.rub < 100 && (old?.rub == null || old.rub >= 100)) {
                alert(ID_ALERT + 20 + i, "$name: на балансе ${b.rub.toInt()} ₽", b.text)
            }
        }
    }

    // ---------------------------------------------------------------- modem self-healing

    private val badSince = LongArray(2)
    private val healStep = IntArray(2)
    private val healAt = LongArray(2)

    /**
     * A modem that answers but has no mobile connection: after 1.5 min — reconnect, after 5 min — reboot.
     * After a reboot the next one is allowed in 30 min, so a dead SIM doesn't reboot in circles.
     */
    private fun heal(states: List<ModemState>, now: Long) {
        if (!Ops.heal(this)) return
        val cfg = Monitor.config(this)
        for (i in 0 until minOf(2, states.size)) {
            val m = states[i]
            if (!m.ok) continue
            if (m.connected) { badSince[i] = 0; healStep[i] = 0; continue }
            if (badSince[i] == 0L) badSince[i] = now
            val bad = now - badSince[i]
            val name = cfg.getOrNull(i)?.name ?: "Модем"
            when {
                healStep[i] == 0 && bad >= 90_000 -> {
                    healStep[i] = 1; healAt[i] = now
                    actions.execute {
                        val ok = try { Ops.modem(this, i).reconnect(); true } catch (e: Exception) { false }
                        if (ok) alert(ID_ALERT + 10 + i, "$name без сети — переподключил", null)
                    }
                }
                healStep[i] == 1 && bad >= 5 * 60_000 -> {
                    healStep[i] = 2; healAt[i] = now
                    actions.execute {
                        val ok = try { Ops.modem(this, i).reboot(); true } catch (e: Exception) { false }
                        if (ok) alert(ID_ALERT + 10 + i, "$name без сети — перезагрузил", "Если не поможет, проверьте SIM и баланс")
                    }
                }
                healStep[i] == 2 && now - healAt[i] >= 30 * 60_000 -> { healStep[i] = 0; badSince[i] = now }
            }
        }
    }

    // ---------------------------------------------------------------- SMS

    private val lastUnread = IntArray(2)

    private fun watchSms(states: List<ModemState>) {
        for (i in 0 until minOf(2, states.size)) {
            val m = states[i]
            if (!m.ok) continue
            if (m.unread > lastUnread[i]) actions.execute { notifySms(i) }
            lastUnread[i] = m.unread
        }
    }

    private fun notifySms(i: Int) {
        val list = try { Ops.modem(this, i).sms(5) } catch (e: Exception) { return }
        val p = getSharedPreferences("pdm", MODE_PRIVATE)
        val seen = p.getInt("smsn$i", -1)
        val fresh = list.filter { it.unread && it.index > seen }.maxByOrNull { it.index } ?: return
        p.edit().putInt("smsn$i", fresh.index).apply()
        val name = Monitor.config(this).getOrNull(i)?.name ?: "SIM"
        alert(ID_ALERT + 30 + i, "$name · СМС от ${fresh.phone}", fresh.text)
    }

    // ---------------------------------------------------------------- signal log along the route

    private var lastRoute = 0L
    @Volatile private var location: android.location.Location? = null
    private var locListener: android.location.LocationListener? = null

    private fun hasLocation() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Once a minute on the train: time, coordinates (if allowed) and each modem's cell and signal. */
    private fun logRoute(states: List<ModemState>, now: Long) {
        if (!Ops.routeLog(this)) { stopLocation(); return }
        startLocation()
        if (now - lastRoute < 60_000) return
        lastRoute = now
        try { Ops.appendRoute(this, states, Monitor.config(this).map { it.name }, location) } catch (_: Exception) {}
    }

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (locListener != null || !hasLocation()) return
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return
        val l = android.location.LocationListener { location = it }
        try {
            for (prov in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(prov)) {
                    lm.requestLocationUpdates(prov, 30_000L, 50f, l, android.os.Looper.getMainLooper())
                    lm.getLastKnownLocation(prov)?.let { if (location == null) location = it }
                }
            }
            locListener = l
        } catch (_: Exception) {
        }
    }

    private fun stopLocation() {
        val l = locListener ?: return
        try { getSystemService(android.location.LocationManager::class.java)?.removeUpdates(l) } catch (_: Exception) {}
        locListener = null
    }

    private fun alert(id: Int, title: String, text: String?) {
        val b = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        if (!text.isNullOrBlank()) b.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        try { getSystemService(NotificationManager::class.java).notify(id, b.build()) } catch (_: Exception) {}
    }

    private fun releaseAll() {
        stopLocation()
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
        val paused = s != null && s.where == null
        val body = when {
            s == null -> "Проверяю…"
            s.where == null -> s.label
            s.where == Where.TRAIN ->
                (listOf(s.label) + s.modems.mapIndexed { i, m -> line(cfg.getOrNull(i)?.name ?: "?", m) }).joinToString("\n")
            else -> listOfNotNull(s.label, s.local?.let { localLine(it) }).joinToString("\n")
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

    private fun buildAlert(net: Net, label: String): Notification =
        Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(
                when (net) {
                    Net.FULL -> "Интернет вернулся"
                    Net.WHITELIST -> "Только белые списки"
                    else -> "Интернет пропал"
                },
            )
            .setContentText(label)
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

        fun localLine(l: Local): String = when (l.where) {
            Where.WIFI -> listOfNotNull(
                l.dbm?.let { "$it дБм" }, l.gen.takeIf { it.isNotEmpty() }, l.linkMbps?.let { "линк $it Мбит/с" },
            ).joinToString(" · ")
            else -> listOfNotNull(
                l.gen.takeIf { it.isNotEmpty() },
                if (l.rsrp != null) "${l.rsrp} / ${l.sinr ?: "—"} дБ" else l.dbm?.let { "$it дБм" },
            ).joinToString(" · ")
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
