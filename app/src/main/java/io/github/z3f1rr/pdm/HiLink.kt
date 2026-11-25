package io.github.z3f1rr.pdm

import android.net.Network
import java.net.HttpURLConnection
import java.net.URL

data class ModemState(
    val ok: Boolean = false,
    val error: String = "",
    val operator: String = "",
    val net: String = "",
    val connected: Boolean = false,
    val rsrp: Int? = null,
    val sinr: Int? = null,
    val rsrq: Int? = null,
    val rssi: Int? = null,
    val band: String = "",
    val cell: String = "",
    val dlMbps: Double = 0.0,
    val ulMbps: Double = 0.0,
) {
    /** 0..4 bars from RSRP and SINR. */
    val quality: Int
        get() {
            if (!ok || !connected) return 0
            fun lvl(v: Int?, steps: IntArray) = if (v == null) 0 else steps.count { v >= it }
            val r = lvl(rsrp, intArrayOf(-115, -105, -95, -85))
            val s = lvl(sinr, intArrayOf(-3, 2, 8, 15))
            return maxOf(1, Math.round((r + s) / 2.0).toInt())
        }

    val qualityWord: String
        get() = arrayOf("нет связи", "очень слабо", "слабо", "хорошо", "отлично")[quality]
}

/** Huawei HiLink API (E3372h and similar). Every call has short timeouts and never hangs. */
class HiLink(private val ip: String, private val network: Network?) {

    private fun open(path: String): HttpURLConnection {
        val url = URL("http://$ip$path")
        val c = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.useCaches = false
        c.instanceFollowRedirects = false
        return c
    }

    private fun get(path: String, cookie: String?): String {
        val c = open(path)
        try {
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun post(path: String, inner: String): String {
        val (cookie, token) = token()
        val c = open(path)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            if (token != null) c.setRequestProperty("__RequestVerificationToken", token)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request>$inner</request>"
            c.outputStream.use { it.write(body.toByteArray()) }
            val r = c.inputStream.bufferedReader().use { it.readText() }
            check(r, path)
            return r
        } finally {
            c.disconnect()
        }
    }

    private fun token(): Pair<String?, String?> {
        val x = get("/api/webserver/SesTokInfo", null)
        return tag(x, "SesInfo") to tag(x, "TokInfo")
    }

    fun read(): ModemState {
        val (cookie, _) = token()
        fun g(path: String): String = try {
            get(path, cookie).also { check(it, path) }
        } catch (e: Exception) {
            ""
        }
        val sig = g("/api/device/signal")
        val st = g("/api/monitoring/status")
        val plmn = g("/api/net/current-plmn")
        val tr = g("/api/monitoring/traffic-statistics")
        val nt = tag(st, "CurrentNetworkTypeEx") ?: tag(st, "CurrentNetworkType") ?: ""
        return ModemState(
            ok = true,
            operator = tag(plmn, "ShortName") ?: tag(plmn, "FullName") ?: "",
            net = NET[nt] ?: nt.ifEmpty { "?" },
            connected = tag(st, "ConnectionStatus") == "901",
            rsrp = num(tag(sig, "rsrp")),
            sinr = num(tag(sig, "sinr")),
            rsrq = num(tag(sig, "rsrq")),
            rssi = num(tag(sig, "rssi")),
            band = tag(sig, "band") ?: "",
            cell = tag(sig, "cell_id") ?: "",
            dlMbps = (num(tag(tr, "CurrentDownloadRate")) ?: 0) * 8 / 1e6,
            ulMbps = (num(tag(tr, "CurrentUploadRate")) ?: 0) * 8 / 1e6,
        )
    }

    fun reboot() {
        post("/api/device/control", "<Control>1</Control>")
    }

    fun reconnect() {
        post("/api/dialup/mobile-dataswitch", "<dataswitch>0</dataswitch>")
        Thread.sleep(2000)
        post("/api/dialup/mobile-dataswitch", "<dataswitch>1</dataswitch>")
    }

    companion object {
        private val NET = mapOf(
            "0" to "нет сети", "1" to "2G", "2" to "2G", "3" to "2G", "4" to "3G", "5" to "3G", "6" to "3G",
            "7" to "3G", "9" to "3G+", "17" to "3G+", "18" to "3G+", "19" to "4G", "41" to "3G", "44" to "3G+",
            "45" to "3G+", "46" to "3G+", "61" to "3G", "62" to "3G+", "63" to "3G+", "64" to "3G+",
            "65" to "3G+", "101" to "4G", "1011" to "4G+",
        )

        fun tag(xml: String, name: String): String? =
            Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)

        fun num(s: String?): Int? = s?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() }

        private fun check(xml: String, path: String) {
            if (xml.contains("<error>")) {
                throw IllegalStateException("модем ответил ошибкой ${tag(xml, "code") ?: "?"} на $path")
            }
        }
    }
}
