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

/** Shared state between the service and the screen. */
object Monitor {
    val snapshot = mutableStateOf<Snapshot?>(null)
    val running = mutableStateOf(false)
    val message = mutableStateOf("")
    val history = mutableStateOf<List<List<Point>>>(emptyList())

    private val main = Handler(Looper.getMainLooper())

    fun post(block: () -> Unit) = main.post(block)

    fun config(ctx: Context): List<ModemConfig> {
        val p = ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE)
        return listOf(
            ModemConfig(p.getString("name0", "МегаФон")!!, p.getString("ip0", "192.168.8.1")!!),
            ModemConfig(p.getString("name1", "МТС")!!, p.getString("ip1", "192.168.9.1")!!),
        )
    }

    fun intervalSec(ctx: Context): Int =
        ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE).getInt("interval", 10).coerceIn(5, 120)

    fun save(ctx: Context, modems: List<ModemConfig>, interval: Int) {
        val e = ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE).edit()
        modems.forEachIndexed { i, m ->
            e.putString("name$i", m.name.trim())
            e.putString("ip$i", m.ip.trim())
        }
        e.putInt("interval", interval).apply()
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
     * Real check through the router, over HTTPS so a captive page or a stub answer cannot pass for
     * internet. Foreign hosts answering → full internet. Only Russian whitelisted sites → whitelist mode.
     */
    fun check(network: Network?): Pair<Net, Int?> {
        if (network == null) return Net.NO_WIFI to null
        for (url in FULL_URLS) {
            https(network, url, expect204 = true)?.let { return Net.FULL to it }
        }
        for (url in WHITELIST_URLS) {
            https(network, url, expect204 = false)?.let { return Net.WHITELIST to it }
        }
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

    /** Time of a successful HTTPS answer in ms, or null. Includes DNS through the router. */
    private fun https(network: Network, url: String, expect204: Boolean): Int? {
        val start = System.nanoTime()
        val c = try {
            network.openConnection(URL(url)) as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "PdmStatus")
            val code = c.responseCode
            val ok = if (expect204) code == 204 else code in 200..399
            if (ok) ((System.nanoTime() - start) / 1_000_000).toInt() else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
