package io.github.z3f1rr.pdm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

data class ModemConfig(val name: String, val ip: String)

/** What the router can actually reach right now. NO_WIFI and FOREIGN mean checks are paused. */
enum class Net { FULL, WHITELIST, NONE, NO_WIFI, FOREIGN }

/** Where the phone is: on the train router's Wi-Fi, without Wi-Fi, or on some other Wi-Fi. */
enum class Place { TRAIN, NO_WIFI, FOREIGN }

/** Which link is being checked right now. */
enum class Where { TRAIN, WIFI, MOBILE }

/** UPS runtime test, persisted so it survives the app being killed. */
data class Ups(
    val start: Long,
    val lastSeen: Long,
    val end: Long? = null,
    val downSince: Long? = null,
    /** How many times the router vanished and came back (reboots on low voltage). */
    val gaps: Int = 0,
)

data class Snapshot(
    val modems: List<ModemState>,
    val net: Net,
    val latencyMs: Int?,
    val time: Long,
    /** When the current [net] state began. */
    val since: Long,
    /** Link being checked; null while checks are paused. */
    val where: Where? = null,
    /** "Wi-Fi вагона", "Wi-Fi «Home»", "Мобильный · Yota", or why checks are paused. */
    val label: String = "",
    /** The phone's own signal on another Wi-Fi or mobile data. */
    val local: Local? = null,
    /** Why the last check failed, step by step (shown under "no internet"). */
    val why: String = "",
)

/** Result of one probe: time in ms, or the reason it failed. */
class Probe(val ms: Int?, val err: String = "")

/** One point of history: time, RSRP, SINR. */
data class Point(val t: Long, val rsrp: Int?, val sinr: Int?)

data class Speed(val downMbps: Double?, val upMbps: Double?, val time: Long, val error: String? = null)

/** Shared state between the service and the screen. */
object Monitor {
    val snapshot = mutableStateOf<Snapshot?>(null)
    val running = mutableStateOf(false)
    val message = mutableStateOf("")
    val history = mutableStateOf<List<List<Point>>>(emptyList())
    val speed = mutableStateOf<Speed?>(null)
    val speedRunning = mutableStateOf(false)
    val ups = mutableStateOf<Ups?>(null)
    val localHistory = mutableStateOf<List<Point>>(emptyList())
    val diag = mutableStateOf<String?>(null)
    val diagRunning = mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()

    fun post(block: () -> Unit) = main.post(block)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE)

    fun config(ctx: Context): List<ModemConfig> {
        val p = prefs(ctx)
        return listOf(
            ModemConfig(p.getString("name0", "МегаФон")!!, p.getString("ip0", "192.168.8.1")!!),
            ModemConfig(p.getString("name1", "МТС")!!, p.getString("ip1", "192.168.9.1")!!),
        )
    }

    fun intervalSec(ctx: Context): Int = prefs(ctx).getInt("interval", 10).coerceIn(5, 120)

    /** Base URL of the user's check server, e.g. http://1.2.3.4:8093/TOKEN, or "" if not set. */
    fun server(ctx: Context): String = prefs(ctx).getString("server", "")!!

    fun save(ctx: Context, modems: List<ModemConfig>, interval: Int, server: String) {
        val e = prefs(ctx).edit()
        modems.forEachIndexed { i, m ->
            e.putString("name$i", m.name.trim())
            e.putString("ip$i", m.ip.trim())
        }
        e.putInt("interval", interval).putString("server", normalize(server)).apply()
    }

    /** Check other Wi-Fi networks too (not only the train router). */
    fun otherWifi(ctx: Context) = prefs(ctx).getBoolean("other_wifi", true)

    /** Check mobile data when there is no Wi-Fi. */
    fun mobile(ctx: Context) = prefs(ctx).getBoolean("mobile", true)

    /** Read the Wi-Fi name through su (KernelSU / Magisk). */
    fun rootSsid(ctx: Context) = prefs(ctx).getBoolean("root_ssid", false)

    fun setFlag(ctx: Context, key: String, on: Boolean) = prefs(ctx).edit().putBoolean(key, on).apply()

    fun normalize(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u
    }

    fun loadSpeed(ctx: Context): Speed? {
        val p = prefs(ctx)
        val t = p.getLong("speed_t", 0)
        if (t == 0L) return null
        return Speed(
            p.getFloat("speed_d", -1f).takeIf { it >= 0 }?.toDouble(),
            p.getFloat("speed_u", -1f).takeIf { it >= 0 }?.toDouble(),
            t,
        )
    }

    fun saveSpeed(ctx: Context, s: Speed) {
        prefs(ctx).edit().putLong("speed_t", s.time)
            .putFloat("speed_d", (s.downMbps ?: -1.0).toFloat())
            .putFloat("speed_u", (s.upMbps ?: -1.0).toFloat()).apply()
    }

    /**
     * The router's Wi-Fi network. When that Wi-Fi has no internet, Android sends ordinary traffic over
     * mobile data, and the modems' 192.168.x.1 addresses would be unreachable — so we bind to Wi-Fi.
     */
    @Suppress("DEPRECATION")
    fun wifiNetworks(ctx: Context): List<Network> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        // a VPN over Wi-Fi also reports TRANSPORT_WIFI: skip it, we need the real link. Phones with
        // dual Wi-Fi may have two: the one with INTERNET and a gateway goes first.
        return cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            // only a real connection to an access point: tethering apps (VPN Hotspot, Wi-Fi Direct
            // repeater) can add Wi-Fi networks with no signal and no gateway, where the phone itself is the AP
            val rssi = (c.transportInfo as? android.net.wifi.WifiInfo)?.rssi
            val gw = gateway(ctx, n)
            if (gw == null && (rssi == null || rssi !in -120..-1)) return@mapNotNull null
            var score = 0
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 4
            if (gw != null) score += 2
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) score += 1
            n to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** The router's Wi-Fi: the train network if known, otherwise the best Wi-Fi. */
    fun wifiNetwork(ctx: Context): Network? =
        synchronized(this) { placeNet?.takeIf { placeOk } }?.takeIf { it in wifiNetworks(ctx) } ?: wifiNetworks(ctx).firstOrNull()

    /** Mobile data network, if Android keeps one up. Requests bound to it bypass Wi-Fi and the phone's VPN. */
    @Suppress("DEPRECATION")
    fun cellNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun loadUps(ctx: Context): Ups? {
        val p = prefs(ctx)
        val start = p.getLong("ups_start", 0)
        if (start == 0L) return null
        return Ups(
            start = start,
            lastSeen = p.getLong("ups_last", start),
            end = p.getLong("ups_end", 0).takeIf { it > 0 },
            downSince = p.getLong("ups_down", 0).takeIf { it > 0 },
            gaps = p.getInt("ups_gaps", 0),
        )
    }

    fun saveUps(ctx: Context, u: Ups?) {
        val e = prefs(ctx).edit()
        if (u == null) {
            e.remove("ups_start").remove("ups_last").remove("ups_end").remove("ups_down").remove("ups_gaps")
        } else {
            e.putLong("ups_start", u.start).putLong("ups_last", u.lastSeen)
                .putLong("ups_end", u.end ?: 0).putLong("ups_down", u.downSince ?: 0).putInt("ups_gaps", u.gaps)
        }
        e.commit()
    }

    /**
     * Networks Android won't let this app use directly. With a VPN set to «Блокировать соединения без VPN»
     * (lockdown), a request bound to the bare Wi-Fi or mobile network silently goes nowhere: then modems,
     * the router and the internet check all look dead. Filled from NetworkCallback.onBlockedStatusChanged.
     */
    val blocked = java.util.concurrent.ConcurrentHashMap<Network, Boolean>()

    @Suppress("DEPRECATION")
    fun isBlocked(ctx: Context, raw: Network): Boolean {
        if (blocked[raw] == true) return true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val state = try { cm.getNetworkInfo(raw)?.detailedState == android.net.NetworkInfo.DetailedState.BLOCKED } catch (_: Exception) { false }
        return state || bindFails(raw)
    }

    private val bindCache = java.util.concurrent.ConcurrentHashMap<Network, Pair<Boolean, Long>>()

    /**
     * Can this app pin a socket to [raw]? Not when the app is inside a VPN that doesn't allow bypassing
     * (most clients): Android answers EPERM, with or without «Блокировать соединения без VPN», and no
     * callback reports it. Tested on a local UDP socket, no traffic; cached for 20 s.
     */
    fun bindFails(raw: Network): Boolean {
        val now = System.currentTimeMillis()
        bindCache[raw]?.let { (v, t) -> if (now - t < 20_000) return v }
        val v = try {
            java.net.DatagramSocket().use { raw.bindSocket(it) }
            false
        } catch (e: Exception) {
            true
        }
        bindCache[raw] = v to now
        return v
    }

    /** The phone's VPN network, if one is up. */
    @Suppress("DEPRECATION")
    fun vpnNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val vpn = { n: Network? -> n != null && cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        return cm.activeNetwork.takeIf(vpn) ?: cm.allNetworks.firstOrNull(vpn)
    }

    /**
     * What to send requests through for the link [raw]: the link itself, or — when Android blocks it for us
     * (lockdown VPN) — the VPN on top of it. The VPN app sends local addresses (modems, router) straight to the LAN.
     */
    fun via(ctx: Context, raw: Network): Network {
        if (!isBlocked(ctx, raw)) return raw
        // the app may be excluded from the VPN: then the VPN network is closed for it too
        val v = vpnNetwork(ctx) ?: return raw
        return if (bindFails(v)) raw else v
    }

    /** True when requests for [raw] go through the VPN because the bare link is blocked for us. */
    fun viaVpn(ctx: Context, raw: Network): Boolean = via(ctx, raw) != raw

    /** Default gateway of a network (the router), e.g. 192.168.1.1. */
    fun gateway(ctx: Context, network: Network): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }
            ?.gateway?.hostAddress
    }

    /** Any HTTP answer from the router's web interface. */
    fun routerAlive(ctx: Context): Boolean {
        val n = wifiNetwork(ctx) ?: return false
        val gw = gateway(ctx, n) ?: return false
        return try {
            val c = open(via(ctx, n), "http://$gw/auth", connect = 2500, read = 3000)
            try { c.responseCode > 0 } finally { c.disconnect() }
        } catch (e: Exception) {
            false
        }
    }

    /** Keenetic answers GET /auth with 401 and an X-NDM-Realm header. */
    private fun keenetic(n: Network?, gw: String): Probe {
        val start = System.nanoTime()
        return try {
            val c = open(n, "http://$gw/auth", connect = 2500, read = 3000)
            try {
                val code = c.responseCode
                val realm = c.getHeaderField("X-NDM-Realm")
                if (code == 401 && realm != null) Probe(ms(start)) else Probe(null, "HTTP $code, X-NDM-Realm=${realm ?: "нет"}")
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Probe(null, short(e))
        }
    }

    private fun isTrain(ctx: Context, n: Network): Boolean {
        val gw = gateway(ctx, n)
        val through = via(ctx, n)
        if (gw != null && keenetic(through, gw).ms != null) return true
        return config(ctx).any { m -> try { HiLink(m.ip, through).read().ok } catch (e: Exception) { false } }
    }

    private var placeNet: Network? = null
    private var placeOk = false
    private var placeAt = 0L

    /**
     * Is the phone on the train router? Recognised without root or location permission: the gateway
     * answers like a Keenetic web interface (401 with an X-NDM-Realm header), or one of the modems
     * answers. A "yes" is cached per network for two minutes, a "no" for one minute; a Wi-Fi change clears it.
     */
    @Synchronized
    fun place(ctx: Context): Pair<Place, Network?> {
        val nets = wifiNetworks(ctx)
        if (nets.isEmpty()) return Place.NO_WIFI to null
        val now = System.currentTimeMillis()
        val cached = placeNet
        if (cached != null && cached in nets && now - placeAt < (if (placeOk) 120_000 else 60_000)) {
            return if (placeOk) Place.TRAIN to cached else Place.FOREIGN to nets.first()
        }
        val train = nets.firstOrNull { isTrain(ctx, it) }
        // a "no" right after connecting may be wrong (gateway not known yet): onLinkPropertiesChanged resets the cache
        placeNet = train ?: nets.first().takeIf { gateway(ctx, it) != null }
        placeOk = train != null
        placeAt = now
        return if (train != null) Place.TRAIN to train else Place.FOREIGN to nets.first()
    }

    /** Forget the cached answer: called when Wi-Fi connects or disconnects. */
    @Synchronized
    fun resetPlace() {
        placeNet = null
        bindCache.clear()
    }

    /**
     * A check that a stub, a captive page or a half-open connection cannot pass:
     * 1. Own server: upload 1 KB of random data, the server must return its SHA-256 plus 2 KB back.
     * 2. Foreign HTTPS (valid certificate required): full internet even if the own server is down.
     * 3. Russian whitelisted HTTPS sites only: whitelist mode.
     */
    fun check(network: Network?, server: String): Triple<Net, Int?, String> {
        if (network == null) return Triple(Net.NO_WIFI, null, "")
        val errs = ArrayList<String>()
        if (server.isNotEmpty()) {
            val p = echo(network, server)
            if (p.ms != null) return Triple(Net.FULL, p.ms, "")
            errs += "сервер: ${p.err}"
        }
        for (url in FULL_URLS) {
            val p = https(network, url, expect204 = true)
            if (p.ms != null) return Triple(Net.FULL, p.ms, "")
            errs += "${host(url)}: ${p.err}"
        }
        for (url in WHITELIST_URLS) {
            val p = https(network, url, expect204 = false)
            if (p.ms != null) return Triple(Net.WHITELIST, p.ms, errs.joinToString("\n"))
            errs += "${host(url)}: ${p.err}"
        }
        return Triple(Net.NONE, null, errs.joinToString("\n"))
    }

    private fun host(url: String) = URL(url).host.removePrefix("www.")

    fun short(e: Throwable): String {
        val m = (e.message ?: "").replace(Regex("https?://[^ ]+"), "").trim()
        return e.javaClass.simpleName + if (m.isNotEmpty()) ": " + m.take(80) else ""
    }

    private val FULL_URLS = listOf(
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
    )
    private val WHITELIST_URLS = listOf(
        "https://ya.ru/",
        "https://vk.com/",
    )

    private fun ms(start: Long) = ((System.nanoTime() - start) / 1_000_000).toInt()

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** [network] null = the default route, like any other app (through the phone's VPN if one is on). */
    private fun open(network: Network?, url: String, connect: Int = 3500, read: Int = 5000): HttpURLConnection =
        ((network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection).apply {
            connectTimeout = connect
            readTimeout = read
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PdmStatus")
        }

    /** Round trip of real data through the own server, in ms, or null. */
    private fun echo(network: Network?, base: String): Probe {
        val body = ByteArray(1024).also { rnd.nextBytes(it) }
        val head = "pdm-ok ${sha256(body)}\n".toByteArray()
        val start = System.nanoTime()
        val c = try { open(network, "$base/echo") } catch (e: Exception) { return Probe(null, short(e)) }
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code != 200) { c.disconnect(); return Probe(null, "HTTP $code") }
            val data = c.inputStream.use { it.readBytes() }
            val ok = data.size >= head.size + 2048 && data.copyOfRange(0, head.size).contentEquals(head)
            if (ok) Probe(ms(start)) else Probe(null, "ответ не от pdm-probe (${data.size} байт)")
        } catch (e: Exception) {
            c.disconnect()
            Probe(null, short(e))
        }
    }

    /** HEAD over HTTPS: the TLS certificate proves we reached the real site. Time in ms, or null. */
    private fun https(network: Network?, url: String, expect204: Boolean): Probe {
        val start = System.nanoTime()
        val c = try { open(network, url, read = 4000) } catch (e: Exception) { return Probe(null, short(e)) }
        return try {
            c.requestMethod = if (expect204) "GET" else "HEAD"
            val code = c.responseCode
            try { (if (code < 400) c.inputStream else c.errorStream)?.close() } catch (_: Exception) {}
            // any HTTP answer behind a valid certificate means the site is really reachable
            val ok = if (expect204) code == 204 else code in 100..599
            if (ok) Probe(ms(start)) else Probe(null, "HTTP $code")
        } catch (e: Exception) {
            c.disconnect()
            Probe(null, short(e))
        }
    }

    /**
     * Step-by-step report for troubleshooting: every network Android has, the router and modem checks,
     * the internet checks bound to each network and through the default route. The server key is masked.
     */
    @Suppress("DEPRECATION")
    fun diagnose(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
        val server = server(ctx)
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }
        fun p(label: String, r: Probe) = line("  $label → " + if (r.ms != null) "OK ${r.ms} мс" else r.err)
        line("ПДМ-статус ${java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())}, " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        line("Сервер проверки: " + if (server.isEmpty()) "не задан" else server.replace(Regex("/[^/]{8,}$"), "/***"))
        val def = cm.activeNetwork
        line("Сети:")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n)
            val t = listOfNotNull(
                "Wi-Fi".takeIf { c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true },
                "моб".takeIf { c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true },
                "VPN".takeIf { c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true },
            ).joinToString("+")
            val caps = listOfNotNull(
                "INTERNET".takeIf { c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true },
                "VALIDATED".takeIf { c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true },
                "RESTRICTED".takeIf { c != null && !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) },
            ).joinToString(" ")
            val lp = cm.getLinkProperties(n)
            line("  #$n $t $caps · ${lp?.interfaceName ?: "?"} · шлюз ${gateway(ctx, n) ?: "нет"}" +
                (if (isBlocked(ctx, n)) " · ЗАБЛОКИРОВАНА для приложения" else "") + if (n == def) " · по умолчанию" else "")
        }
        val (place, pn) = place(ctx)
        line("Определено: $place" + (pn?.let { " (#$it)" } ?: ""))
        fun internet(n: Network?) {
            if (server.isNotEmpty()) p("сервер", echo(n, server))
            for (u in FULL_URLS) p(host(u), https(n, u, true))
            for (u in WHITELIST_URLS) p(host(u), https(n, u, false))
        }
        for (raw in wifiNetworks(ctx)) {
            val n = via(ctx, raw)
            line("Wi-Fi #$raw" + if (n != raw) " (через VPN #$n, прямой доступ закрыт):" else ":")
            val gw = gateway(ctx, n)
            if (gw != null) p("Keenetic $gw/auth", keenetic(n, gw)) else line("  шлюза нет")
            for (m in config(ctx)) {
                val r = try { if (HiLink(m.ip, n).read().ok) Probe(0) else Probe(null, "не ok") } catch (e: Exception) { Probe(null, short(e)) }
                p("модем ${m.name} ${m.ip}", r)
            }
            internet(n)
        }
        cellNetwork(ctx)?.let { raw ->
            val n = via(ctx, raw)
            line("Мобильная #$raw" + if (n != raw) " (через VPN #$n):" else ":")
            internet(n)
        }
        line("Без привязки к сети (как обычные приложения, через VPN если включён):")
        config(ctx).firstOrNull()?.let { m ->
            p("модем ${m.name} ${m.ip}", try { if (HiLink(m.ip, null).read().ok) Probe(0) else Probe(null, "не ok") } catch (e: Exception) { Probe(null, short(e)) })
        }
        internet(null)
        if (rootSsid(ctx)) line("SSID через root: ${Root.ssid() ?: "не получен"}")
        Ops.keenetic(ctx)?.let { k ->
            line("Роутер:")
            line("  " + try { k.hostSample() } catch (e: Exception) { short(e) })
        }
        return out.toString()
    }

    /**
     * Download: 4 parallel streams for 8 s from the Yandex mirror (Yandex stays reachable under the
     * whitelists, and one TCP stream underrates LTE), the own server if the mirror fails.
     * Upload: 2 parallel streams for 6 s to the own server (there is no whitelisted place to upload to).
     */
    fun speedTest(network: Network?, base: String): Speed {
        val now = System.currentTimeMillis()
        if (network == null) return Speed(null, null, now, "Нет сети для замера")
        var down = mirror(network)?.let { url -> parallel(4, 8) { k, until -> downStream(network, url, k * 300_000_000L, until) } }
        if (down == null && base.isNotEmpty()) down = parallel(4, 8) { _, until -> downStream(network, "$base/down?mb=32", null, until) }
        val up = if (base.isEmpty()) null else parallel(2, 6) { _, until -> upStream(network, base, until) }
        val err = if (down == null && up == null) "Не удалось замерить: нет связи с Яндексом и сервером" else null
        return Speed(down, up, System.currentTimeMillis(), err)
    }

    @Volatile private var mirrorUrl: String? = null

    /** A big file on mirror.yandex.ru: the current Ubuntu desktop ISO, found from the directory listing. */
    private fun mirror(network: Network): String? {
        mirrorUrl?.let { return it }
        for (dir in listOf("https://mirror.yandex.ru/ubuntu-releases/noble/", "https://mirror.yandex.ru/ubuntu-releases/jammy/")) {
            val html = try {
                val c = open(network, dir, read = 6000)
                try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else "" } finally { c.disconnect() }
            } catch (e: Exception) { "" }
            val name = Regex("""href="(ubuntu-[0-9.]+-desktop-amd64\.iso)"""").findAll(html).lastOrNull()?.groupValues?.get(1) ?: continue
            return (dir + name).also { mirrorUrl = it }
        }
        return null
    }

    /** Runs [n] streams until [sec] seconds pass; each returns bytes moved. Result in Mbit/s. */
    private fun parallel(n: Int, sec: Int, stream: (Int, Long) -> Long): Double? {
        val total = java.util.concurrent.atomic.AtomicLong()
        val start = System.nanoTime()
        val until = start + sec * 1_000_000_000L
        val threads = (0 until n).map { k ->
            Thread { try { total.addAndGet(stream(k, until)) } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(sec * 1000L + 8000) }
        val elapsed = (minOf(System.nanoTime(), until + 2_000_000_000L) - start) / 1e9
        val bytes = total.get()
        return if (bytes > 256 * 1024 && elapsed > 0) bytes * 8 / 1e6 / elapsed else null
    }

    private fun downStream(network: Network, url: String, offset: Long?, until: Long): Long {
        val c = open(network, url, read = 6000)
        val buf = ByteArray(64 * 1024)
        var got = 0L
        try {
            if (offset != null) c.setRequestProperty("Range", "bytes=$offset-")
            if (c.responseCode !in 200..299) return 0
            c.inputStream.use { s ->
                while (System.nanoTime() < until) {
                    val n = s.read(buf)
                    if (n < 0) break
                    got += n
                }
            }
        } finally {
            c.disconnect()
        }
        return got
    }

    private fun upStream(network: Network, base: String, until: Long): Long {
        val total = 32 * 1024 * 1024
        val c = open(network, "$base/up", read = 8000)
        val buf = ByteArray(64 * 1024).also { rnd.nextBytes(it) }
        var sent = 0L
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(total)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { o ->
                while (sent < total) {
                    o.write(buf)
                    sent += buf.size
                    if (System.nanoTime() > until) throw StopUpload()
                }
            }
            c.inputStream.use { it.readBytes() }
        } catch (e: StopUpload) {
            // stopped on time: what was written is the measurement
        } catch (e: Exception) {
            if (sent < 256 * 1024) sent = 0
        } finally {
            c.disconnect()
        }
        return sent
    }

    private class StopUpload : Exception()
}
