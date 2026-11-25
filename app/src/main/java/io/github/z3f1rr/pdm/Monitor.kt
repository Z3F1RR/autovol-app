package io.github.z3f1rr.pdm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import java.net.InetSocketAddress
import java.net.Socket

data class ModemConfig(val name: String, val ip: String)

data class Snapshot(
    val modems: List<ModemState>,
    val internetMs: Int?,
    val onWifi: Boolean,
    val time: Long,
    val downSince: Long?,
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
        ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE).getInt("interval", 10).coerceIn(3, 120)

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

    /** TCP connect to Yandex DNS (it stays reachable under whitelists) through the router. */
    fun internetMs(network: Network?): Int? {
        val start = System.nanoTime()
        return try {
            val s: Socket = network?.socketFactory?.createSocket() ?: Socket()
            s.use { it.connect(InetSocketAddress("77.88.8.8", 53), 3000) }
            ((System.nanoTime() - start) / 1_000_000).toInt()
        } catch (e: Exception) {
            null
        }
    }
}
