package io.github.z3f1rr.pdm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.concurrent.TimeUnit

/** The phone's own radio link when it is not on the train Wi-Fi: another Wi-Fi or mobile data. */
data class Local(
    val where: Where,
    /** SSID (if known) or operator name. */
    val name: String = "",
    /** "4G", "5G", "3G", "2G" for mobile; "5 ГГц" for Wi-Fi. */
    val gen: String = "",
    val rsrp: Int? = null,
    val sinr: Int? = null,
    /** dBm: Wi-Fi signal, or 2G/3G signal where there is no RSRP. */
    val dbm: Int? = null,
    val linkMbps: Int? = null,
) {
    /** 0..4 bars, same scale as the modems. */
    val quality: Int
        get() {
            fun lvl(v: Int?, steps: IntArray) = if (v == null) 0 else steps.count { v >= it }
            return when {
                where == Where.WIFI -> if (dbm == null) 0 else maxOf(1, lvl(dbm, intArrayOf(-85, -75, -67, -58)))
                rsrp != null -> {
                    val r = lvl(rsrp, intArrayOf(-115, -105, -95, -85))
                    if (sinr == null) maxOf(1, r) else maxOf(1, Math.round((r + lvl(sinr, intArrayOf(-3, 2, 8, 15))) / 2.0).toInt())
                }
                dbm != null -> maxOf(1, lvl(dbm, intArrayOf(-105, -95, -85, -75)))
                else -> 0
            }
        }
}

object Radio {
    private fun valid(v: Int, lo: Int, hi: Int): Int? = if (v != CellInfo.UNAVAILABLE && v in lo..hi) v else null

    /** Wi-Fi signal and link speed. The SSID is hidden by Android without location access, so it comes separately. */
    @Suppress("DEPRECATION")
    fun wifi(ctx: Context, network: Network, ssid: String?): Local {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val info = cm?.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
            ?: ctx.getSystemService(WifiManager::class.java)?.connectionInfo
        val rssi = info?.rssi?.takeIf { it in -120..-1 }
        val link = info?.linkSpeed?.takeIf { it > 0 }
        val f = info?.frequency ?: 0
        val band = when {
            f > 5900 -> "6 ГГц"
            f > 4900 -> "5 ГГц"
            f > 2000 -> "2,4 ГГц"
            else -> ""
        }
        return Local(Where.WIFI, name = ssid ?: "", gen = band, dbm = rssi, linkMbps = link)
    }

    /** Signal of the SIM that carries mobile data. Needs no permission. */
    fun cell(ctx: Context): Local {
        val base = ctx.getSystemService(TelephonyManager::class.java) ?: return Local(Where.MOBILE)
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        val tm = if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(sub) else base
        val op = (tm.networkOperatorName ?: "").ifBlank { tm.simOperatorName ?: "" }
        val all = try { tm.signalStrength?.cellSignalStrengths.orEmpty() } catch (e: Exception) { emptyList() }
        // 5G NSA reports both LTE and NR: NR is shown as the generation, LTE carries the usable numbers if NR has none
        val nr = all.filterIsInstance<CellSignalStrengthNr>().firstOrNull()
        val lte = all.filterIsInstance<CellSignalStrengthLte>().firstOrNull()
        nr?.let { s ->
            val r = valid(s.ssRsrp, -156, -31)
            val q = valid(s.ssSinr, -23, 40)
            if (r != null) return Local(Where.MOBILE, op, "5G", rsrp = r, sinr = q)
        }
        lte?.let { s ->
            val r = valid(s.rsrp, -140, -43)
            val q = valid(s.rssnr, -20, 30)
            return Local(Where.MOBILE, op, if (nr != null) "5G NSA" else "4G", rsrp = r, sinr = q)
        }
        for (s in all) {
            val gen = when (s) {
                is CellSignalStrengthWcdma, is CellSignalStrengthTdscdma -> "3G"
                is CellSignalStrengthGsm -> "2G"
                is CellSignalStrengthCdma -> "3G"
                else -> continue
            }
            return Local(Where.MOBILE, op, gen, dbm = valid(s.dbm, -140, -20))
        }
        return Local(Where.MOBILE, op)
    }
}

/** Optional root (KernelSU / Magisk) helpers. Every call has a timeout and returns null on any failure. */
object Root {
    /** SSID of the connected Wi-Fi from "cmd wifi status". */
    fun ssid(): String? {
        val out = run("cmd wifi status") ?: return null
        val m = Regex("""connected to "(.+?)"""").find(out) ?: Regex("""SSID: "(.+?)"""").find(out) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    private fun run(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(6, TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else {
            reader.join(1000)
            if (p.exitValue() == 0) synchronized(out) { out.toString() } else null
        }
    } catch (e: Exception) {
        null
    }
}
