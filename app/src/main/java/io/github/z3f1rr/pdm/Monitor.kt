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

/** What the router can actually reach right now. */
enum class Net { FULL, WHITELIST, NONE, NO_WIFI }

data class Snapshot(
    val modems: List<ModemState>,
    val net: Net,
    val latencyMs: Int?,
    val time: Long,
    /** When the current [net] state began. */
    val since: Long,
)

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
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * A check that a stub, a captive page or a half-open connection cannot pass:
     * 1. Own server: upload 1 KB of random data, the server must return its SHA-256 plus 2 KB back.
     * 2. Foreign HTTPS (valid certificate required): full internet even if the own server is down.
     * 3. Russian whitelisted HTTPS sites only: whitelist mode.
     */
    fun check(network: Network?, server: String): Pair<Net, Int?> {
        if (network == null) return Net.NO_WIFI to null
        if (server.isNotEmpty()) echo(network, server)?.let { return Net.FULL to it }
        for (url in FULL_URLS) https(network, url, expect204 = true)?.let { return Net.FULL to it }
        for (url in WHITELIST_URLS) https(network, url, expect204 = false)?.let { return Net.WHITELIST to it }
        return Net.NONE to null
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

    private fun open(network: Network, url: String, connect: Int = 3500, read: Int = 5000): HttpURLConnection =
        (network.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = connect
            readTimeout = read
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PdmStatus")
        }

    /** Round trip of real data through the own server, in ms, or null. */
    private fun echo(network: Network, base: String): Int? {
        val body = ByteArray(1024).also { rnd.nextBytes(it) }
        val head = "pdm-ok ${sha256(body)}\n".toByteArray()
        val start = System.nanoTime()
        val c = try { open(network, "$base/echo") } catch (e: Exception) { return null }
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) { c.disconnect(); return null }
            val data = c.inputStream.use { it.readBytes() }
            val ok = data.size >= head.size + 2048 && data.copyOfRange(0, head.size).contentEquals(head)
            if (ok) ms(start) else null
        } catch (e: Exception) {
            c.disconnect()
            null
        }
    }

    /** HEAD over HTTPS: the TLS certificate proves we reached the real site. Time in ms, or null. */
    private fun https(network: Network, url: String, expect204: Boolean): Int? {
        val start = System.nanoTime()
        val c = try { open(network, url, read = 4000) } catch (e: Exception) { return null }
        return try {
            c.requestMethod = if (expect204) "GET" else "HEAD"
            val code = c.responseCode
            try { (if (code < 400) c.inputStream else c.errorStream)?.close() } catch (_: Exception) {}
            // any HTTP answer behind a valid certificate means the site is really reachable
            val ok = if (expect204) code == 204 else code in 100..599
            if (ok) ms(start) else null
        } catch (e: Exception) {
            c.disconnect()
            null
        }
    }

    /** Download for up to 8 s, then upload for up to 6 s, through the own server. */
    fun speedTest(network: Network?, base: String): Speed {
        val now = System.currentTimeMillis()
        if (network == null) return Speed(null, null, now, "Телефон не в Wi-Fi вагона")
        if (base.isEmpty()) return Speed(null, null, now, "Укажите сервер проверки в настройках")
        val down = try { download(network, base) } catch (e: Exception) { null }
        val up = try { upload(network, base) } catch (e: Exception) { null }
        val err = if (down == null && up == null) "Сервер недоступен" else null
        return Speed(down, up, System.currentTimeMillis(), err)
    }

    private fun download(network: Network, base: String): Double? {
        val c = open(network, "$base/down?mb=24", read = 6000)
        val buf = ByteArray(64 * 1024)
        var got = 0L
        val start = System.nanoTime()
        try {
            if (c.responseCode != 200) return null
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    got += n
                    if (System.nanoTime() - start > 8_000_000_000L) break
                }
            }
        } finally {
            c.disconnect()
        }
        val sec = (System.nanoTime() - start) / 1e9
        return if (got > 64 * 1024 && sec > 0) got * 8 / 1e6 / sec else null
    }

    private fun upload(network: Network, base: String): Double? {
        val total = 12 * 1024 * 1024
        val c = open(network, "$base/up", read = 8000)
        val buf = ByteArray(64 * 1024).also { rnd.nextBytes(it) }
        var sent = 0L
        val start = System.nanoTime()
        var complete = false
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(total)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { o ->
                while (sent < total) {
                    o.write(buf)
                    sent += buf.size
                    if (System.nanoTime() - start > 6_000_000_000L) break
                }
                complete = sent >= total
                if (!complete) throw StopUpload()
            }
            c.inputStream.use { it.readBytes() }
        } catch (e: StopUpload) {
            // stopped on time: the bytes written so far are the measurement
        } finally {
            c.disconnect()
        }
        val sec = (System.nanoTime() - start) / 1e9
        return if (sent > 64 * 1024 && sec > 0) sent * 8 / 1e6 / sec else null
    }

    private class StopUpload : Exception()
}
