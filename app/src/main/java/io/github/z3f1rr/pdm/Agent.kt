package io.github.z3f1rr.pdm

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AgentEvent(val time: Long, val text: String)

/**
 * pdm-agent on the router (Entware). While it answers, it is the source of traffic statistics and
 * packages, and the one doing the automation (main channel, healing, device registration): the app
 * stops doing those itself so the two never fight. Without the agent the app works as before.
 */
object Agent {
    const val PORT = 8099
    val events = mutableStateOf<List<AgentEvent>>(emptyList())
    val online = mutableStateOf(false)
    @Volatile private var lastOk = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE)
    fun token(ctx: Context) = prefs(ctx).getString("agent_token", "")!!
    fun setToken(ctx: Context, t: String) = prefs(ctx).edit().putString("agent_token", t.trim()).apply()

    /** True while the agent answered within the last minute. */
    fun active(): Boolean = System.currentTimeMillis() - lastOk < 60_000

    private fun open(ctx: Context, path: String): HttpURLConnection? {
        if (token(ctx).isEmpty()) return null
        val n = Monitor.wifiNetwork(ctx) ?: return null
        val gw = Monitor.gateway(ctx, n) ?: return null
        val url = URL("http://$gw:$PORT$path")
        val through = Monitor.via(ctx, n)
        return (through.openConnection(url) as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 6000
            useCaches = false
            setRequestProperty("X-Token", token(ctx))
        }
    }

    /** Fetches the agent's state and publishes its statistics, packages and events. */
    fun poll(ctx: Context): Boolean {
        val c = open(ctx, "/api/state") ?: return fail()
        val j = try {
            c.use { if (it.responseCode != 200) null else JSONObject(it.inputStream.readBytes().toString(Charsets.UTF_8)) }
        } catch (e: Exception) { null } ?: return fail()
        lastOk = System.currentTimeMillis()
        val stats = parseStats(j.optJSONObject("stats"))
        val quotas = parseQuotas(j.optJSONObject("quotas"))
        val ev = j.optJSONArray("events")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { AgentEvent(it.optLong("t") * 1000, it.optString("text")) }.asReversed()
        }.orEmpty()
        Monitor.post {
            online.value = true
            if (stats != null) Stats.view.value = stats
            Ops.quotas.value = quotas
            events.value = ev
        }
        return true
    }

    private fun fail(): Boolean {
        if (!active()) Monitor.post { online.value = false }
        return false
    }

    fun post(ctx: Context, path: String, body: JSONObject) {
        val c = open(ctx, path) ?: throw IllegalStateException("Агент на роутере недоступен")
        c.use {
            it.requestMethod = "POST"
            it.doOutput = true
            it.setRequestProperty("Content-Type", "application/json")
            it.outputStream.use { o -> o.write(body.toString().toByteArray()) }
            if (it.responseCode != 200) throw IllegalStateException("Агент ответил HTTP ${it.responseCode}")
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect() }

    private fun parseStats(s: JSONObject?): StatsView? {
        s ?: return null
        val days = s.optJSONArray("days") ?: JSONArray()
        val rows = (0 until days.length()).mapNotNull { days.optJSONObject(it) }.map { d ->
            val m = d.optJSONArray("m")
            DayRow(d.optString("date"), listOf(m?.optLong(0) ?: 0L, m?.optLong(1) ?: 0L), emptyMap())
        }
        if (rows.isEmpty()) return null
        fun pair(k: String) = s.optJSONArray(k).let { listOf(it?.optLong(0) ?: 0L, it?.optLong(1) ?: 0L) }
        val per = pair("per_day")
        val dev = s.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until dev.length()).mapNotNull { dev.optJSONObject(it) }.map {
            DeviceUse(it.optString("mac"), it.optString("name"), it.optLong("month"), it.optLong("today"))
        }
        return StatsView(rows, s.optLong("month"), pair("by_modem"), s.optLong("forecast"), per.sum(), per, s.optInt("days_left"), devices)
    }

    private fun parseQuotas(q: JSONObject?): Map<Int, Quota> {
        q ?: return emptyMap()
        return q.keys().asSequence().mapNotNull { k ->
            val o = q.optJSONObject(k) ?: return@mapNotNull null
            val i = k.toIntOrNull() ?: return@mapNotNull null
            i to Quota(
                leftBytes = o.optLong("left"), packGb = o.optInt("pack", 100), day = o.optInt("day"),
                rollover = o.optBoolean("roll"), setAt = (o.optDouble("set", 0.0) * 1000).toLong(), usedSinceSet = o.optLong("used_all"),
            )
        }.toMap()
    }
}
