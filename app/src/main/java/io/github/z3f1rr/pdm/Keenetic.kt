package io.github.z3f1rr.pdm

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** One internet channel of the router: an interface that got its address from one of the modems. */
data class Channel(
    val iface: String,
    /** Index in the modem settings, or -1 if it could not be matched. */
    val modem: Int,
    val connected: Boolean,
    val main: Boolean,
    val priority: Int?,
    val rxBytes: Long,
    val txBytes: Long,
    /** Current rates in Mbit/s, from the difference with the previous poll. */
    val downMbps: Double?,
    val upMbps: Double?,
)

data class Client(
    val mac: String,
    val name: String,
    val ip: String,
    val active: Boolean,
    /** Bytes sent to the device (its download) and received from it, since it appeared. */
    val down: Long,
    val up: Long,
    val downMbps: Double?,
    val upMbps: Double?,
    /** Speed limit in kbit/s, null = not limited. */
    val limitKbps: Int?,
    /** Registered in the router ("known host"): only those can get a personal limit. */
    val registered: Boolean = false,
)

data class RouterState(
    val channels: List<Channel> = emptyList(),
    val clients: List<Client> = emptyList(),
    val time: Long = System.currentTimeMillis(),
    val error: String? = null,
    /** Limit for all unregistered devices, kbit/s. */
    val unknownLimit: Int? = null,
) {
    val downMbps: Double? get() = channels.mapNotNull { it.downMbps }.takeIf { it.isNotEmpty() }?.sum()
    val upMbps: Double? get() = channels.mapNotNull { it.upMbps }.takeIf { it.isNotEmpty() }?.sum()
}

data class LogLine(val time: String, val text: String)

/**
 * Keenetic RCI (the JSON API behind the web interface). Login is challenge-response:
 * sha256(challenge + md5("login:realm:password")); the session lives in a cookie.
 */
class Keenetic(private val gw: String, private val network: Network?, private val login: String, private val pass: String) {

    private class Reply(val code: Int, val body: String, val headers: Map<String?, List<String>>)

    private fun req(method: String, path: String, body: String? = null): Reply {
        val url = URL("http://$gw$path")
        val c = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = 6000
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.requestMethod = method
            synchronized(Companion) { if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            c.headerFields["Set-Cookie"]?.let { remember(it) }
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Reply(code, text, c.headerFields)
        } finally {
            c.disconnect()
        }
    }

    private fun remember(setCookies: List<String>) = synchronized(Companion) {
        val jar = LinkedHashMap<String, String>()
        cookie.split("; ").filter { '=' in it }.forEach { jar[it.substringBefore('=')] = it.substringAfter('=') }
        setCookies.forEach { sc ->
            val kv = sc.substringBefore(';')
            if ('=' in kv) jar[kv.substringBefore('=').trim()] = kv.substringAfter('=')
        }
        cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun hex(algo: String, s: String) =
        MessageDigest.getInstance(algo).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun auth() {
        val r = req("GET", "/auth")
        if (r.code == 200) return
        fun h(name: String) = r.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val realm = h("X-NDM-Realm") ?: throw IllegalStateException("роутер не похож на Keenetic (HTTP ${r.code})")
        val challenge = h("X-NDM-Challenge") ?: throw IllegalStateException("нет X-NDM-Challenge")
        val hash = hex("SHA-256", challenge + hex("MD5", "$login:$realm:$pass"))
        val body = JSONObject().put("login", login).put("password", hash).toString()
        val a = req("POST", "/auth", body)
        if (a.code != 200) throw IllegalStateException("неверный логин или пароль роутера")
    }

    private fun call(method: String, path: String, body: String? = null): String {
        var r = req(method, path, body)
        if (r.code == 401) {
            auth()
            r = req(method, path, body)
        }
        if (r.code != 200) throw IllegalStateException("роутер ответил HTTP ${r.code} на $path")
        return r.body
    }

    fun get(path: String): String = call("GET", path)

    /** Runs CLI commands through RCI "parse"; throws with the router's message if one fails. */
    fun cli(vararg commands: String): String {
        val arr = JSONArray()
        commands.forEach { arr.put(JSONObject().put("parse", it)) }
        val out = call("POST", "/rci/", arr.toString())
        errors(out)?.let { throw IllegalStateException(it) }
        return out
    }

    private fun errors(json: String): String? {
        val found = ArrayList<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    if (v.optString("status") == "error") found += v.optString("message", "ошибка")
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(try { JSONArray(json) } catch (e: Exception) { return null })
        return found.firstOrNull()
    }

    /**
     * Channels, devices and limits. Rates are computed against [prev]; [modems] are matched to router
     * interfaces by subnet (the modem hands out 192.168.8.x, the router's interface gets one of them).
     */
    fun poll(prev: RouterState?, modems: List<ModemConfig>): RouterState {
        val now = System.currentTimeMillis()
        val dt = prev?.let { (now - it.time) / 1000.0 }?.takeIf { it in 1.0..600.0 }
        fun rate(cur: Long, old: Long?) = if (dt != null && old != null && cur >= old) (cur - old) * 8 / 1e6 / dt else null

        val ifs = JSONObject(get("/rci/show/interface"))
        val channels = ArrayList<Channel>()
        for (name in ifs.keys()) {
            val o = ifs.optJSONObject(name) ?: continue
            val addr = o.optString("address")
            val idx = modems.indexOfFirst { m -> addr.isNotEmpty() && subnet(m.ip) == subnet(addr) }
            val usb = o.optString("type").contains("Usb", true) || name.startsWith("Usb")
            if (idx < 0 && !usb) continue
            val iface = o.optString("id", name)
            val st = try {
                JSONObject(get("/rci/show/interface/stat?name=" + URLEncoder.encode(iface, "UTF-8")))
            } catch (e: Exception) { JSONObject() }
            val rx = st.optLong("rxbytes", 0)
            val tx = st.optLong("txbytes", 0)
            val old = prev?.channels?.firstOrNull { it.iface == iface }
            channels += Channel(
                iface = iface,
                modem = idx,
                connected = truthy(o, "connected") || o.optString("state") == "up" && o.optString("link") == "up",
                main = truthy(o, "defaultgw"),
                priority = o.opt("priority")?.toString()?.toIntOrNull(),
                rxBytes = rx, txBytes = tx,
                downMbps = rate(rx, old?.rxBytes), upMbps = rate(tx, old?.txBytes),
            )
        }

        val (limits, unknownLimit) = try { limits() } catch (e: Exception) { emptyMap<String, Int>() to null }

        val hs = JSONObject(get("/rci/show/ip/hotspot")).optJSONArray("host") ?: JSONArray()
        val clients = ArrayList<Client>()
        for (i in 0 until hs.length()) {
            val h = hs.optJSONObject(i) ?: continue
            val mac = h.optString("mac").lowercase()
            if (mac.isEmpty()) continue
            val active = truthy(h, "active")
            val registered = truthy(h, "registered")
            // the router's tx to the device is the device's download
            val down = h.optLong("txbytes", 0)
            val up = h.optLong("rxbytes", 0)
            if (!active && down + up == 0L) continue
            val old = prev?.clients?.firstOrNull { it.mac == mac }
            clients += Client(
                mac = mac,
                name = h.optString("name").ifBlank { h.optString("hostname") }.ifBlank { mac.uppercase() },
                ip = h.optString("ip"),
                active = active,
                down = down, up = up,
                downMbps = if (active) rate(down, old?.down) else null,
                upMbps = if (active) rate(up, old?.up) else null,
                limitKbps = limits[mac] ?: if (!registered) unknownLimit else null,
                registered = registered,
            )
        }
        clients.sortWith(compareByDescending<Client> { it.active }.thenByDescending { it.downMbps ?: 0.0 }.thenByDescending { it.down })
        return RouterState(channels.sortedBy { if (it.modem < 0) 99 else it.modem }, clients, now, unknownLimit = unknownLimit)
    }

    /** Makes [iface] the main channel: priority above every other internet interface. */
    fun makeMain(iface: String, all: List<Channel>) {
        val top = all.mapNotNull { it.priority }.maxOrNull()
            ?: throw IllegalStateException("роутер не сообщает приоритеты подключений")
        cli("interface $iface ip global ${top + 1}", "system configuration save")
    }

    /**
     * Current limits from the running config: per registered device, and the one for all unregistered
     * devices. Lines look like "ip traffic-shape host aa:bb:… rate 5000 [asymmetric 5000]".
     */
    fun limits(): Pair<Map<String, Int>, Int?> {
        val cfg = get("/rci/show/running-config")
        val hosts = Regex("""ip traffic-shape host ([0-9a-fA-F:]{17}) rate (\d+)""").findAll(cfg)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].toInt() }
        val unknown = Regex("""ip traffic-shape unknown-host rate (\d+)""").find(cfg)?.groupValues?.get(1)?.toInt()
        return hosts to unknown
    }

    /**
     * Limit for one device. The router limits only registered devices, so an unregistered one is
     * registered first (it then also stops falling under the "all unregistered" limit).
     */
    fun limit(c: Client, kbps: Int?) {
        val cmds = ArrayList<String>()
        if (!c.registered) cmds += "known host ${hostName(c)} ${c.mac}"
        cmds += if (kbps == null) "no ip traffic-shape host ${c.mac}" else "ip traffic-shape host ${c.mac} rate $kbps"
        run(cmds, ignoreErrorsOf = setOf("no ip traffic-shape host ${c.mac}"))
    }

    /** One limit for everyone: every registered device plus the rule for unregistered ones. */
    fun limitAll(clients: List<Client>, kbps: Int?) {
        val cmds = ArrayList<String>()
        if (kbps == null) {
            cmds += "no ip traffic-shape unknown-host"
            cmds += "no ip traffic-shape host"
            run(cmds, ignoreErrorsOf = cmds.toSet())
        } else {
            cmds += "ip traffic-shape unknown-host rate $kbps"
            clients.filter { it.registered }.forEach { cmds += "ip traffic-shape host ${it.mac} rate $kbps" }
            run(cmds, ignoreErrorsOf = emptySet())
        }
    }

    /** Commands one by one (one failure doesn't stop the rest), then the config is saved. */
    private fun run(cmds: List<String>, ignoreErrorsOf: Set<String>) {
        val errors = ArrayList<String>()
        for (c in cmds) {
            try {
                cli(c)
            } catch (e: IllegalStateException) {
                if (c !in ignoreErrorsOf) errors += e.message ?: c
            }
        }
        try { cli("system configuration save") } catch (_: Exception) {}
        if (errors.isNotEmpty()) throw IllegalStateException(errors.first())
    }

    private fun hostName(c: Client): String {
        val n = c.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24)
        return n.ifEmpty { "pdm-" + c.mac.replace(":", "").takeLast(6) }
    }

    fun reboot() {
        try { cli("system reboot") } catch (e: java.io.IOException) { /* connection drops while rebooting */ }
    }

    /** Recent events about channels and the internet, newest first. */
    fun log(): List<LogLine> {
        val text = get("/rci/show/log?max-lines=400")
        val out = ArrayList<LogLine>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val msg = v.optString("message")
                    if (msg.isNotEmpty()) {
                        if (INTERESTING.containsMatchIn(msg)) out += LogLine(v.optString("timestamp"), msg)
                    } else v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(JSONObject(text))
        return out.asReversed().take(60)
    }

    companion object {
        @Volatile private var cookie = ""
        private val INTERESTING = Regex(
            "(?i)(Usb|connected|disconnected|link (is )?(up|down)|internet|default gateway|ping.?check|reboot|failover|global)",
        )

        fun subnet(ip: String) = ip.substringBeforeLast('.', "")

        private fun truthy(o: JSONObject, key: String): Boolean = when (val v = o.opt(key)) {
            is Boolean -> v
            is String -> v.equals("yes", true) || v.equals("true", true) || v.equals("up", true)
            else -> false
        }
    }
}
