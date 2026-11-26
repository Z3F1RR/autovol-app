package io.github.z3f1rr.pdm

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One day of traffic: per modem (SIM) and per device, bytes. */
data class DayRow(val date: String, val modems: List<Long>, val devices: Map<String, Long>) {
    val total: Long get() = modems.sum()
}

data class DeviceUse(val mac: String, val name: String, val month: Long, val today: Long)

data class StatsView(
    /** Last 30 days, oldest first, including today. */
    val days: List<DayRow>,
    val monthTotal: Long,
    val monthByModem: List<Long>,
    /** Expected total for the calendar month. */
    val forecast: Long,
    /** Average per day over the last full days (bytes), per modem and total. */
    val perDay: Long,
    val perDayByModem: List<Long>,
    val daysLeft: Int,
    val devices: List<DeviceUse>,
)

/**
 * Traffic history kept on the phone (files/traffic.json): bytes per day per SIM from the modems'
 * counters, and per device from the router's per-device counters. Counters are turned into daily
 * deltas here; a counter that went down (reboot, reset) starts a new baseline.
 */
object Stats {
    val view = mutableStateOf<StatsView?>(null)

    private var root: JSONObject? = null
    private var dirty = false
    private var lastSave = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, "traffic.json")
    private fun dayKey(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    @Synchronized
    private fun root(ctx: Context): JSONObject {
        root?.let { return it }
        val r = try { JSONObject(file(ctx).readText()) } catch (e: Exception) { JSONObject() }
        for (k in listOf("days", "names", "lastD")) if (!r.has(k)) r.put(k, JSONObject())
        if (!r.has("lastM")) r.put("lastM", JSONArray().put(-1L).put(-1L))
        root = r
        return r
    }

    private fun today(r: JSONObject): JSONObject {
        val days = r.getJSONObject("days")
        val k = dayKey()
        return days.optJSONObject(k) ?: JSONObject().put("m", JSONArray().put(0L).put(0L)).put("d", JSONObject()).also { days.put(k, it) }
    }

    /** Glitch guard: more than 2 GB between two polls 10 s apart is a counter jump, not traffic. */
    private fun delta(cur: Long, last: Long): Long {
        if (last < 0) return 0
        val d = if (cur >= last) cur - last else cur
        return if (d in 0..2_000_000_000L) d else 0
    }

    @Synchronized
    fun addModem(ctx: Context, i: Int, counter: Long) {
        if (i !in 0..1) return
        val r = root(ctx)
        val last = r.getJSONArray("lastM")
        val d = delta(counter, last.optLong(i, -1))
        last.put(i, counter)
        if (d > 0) {
            val m = today(r).getJSONArray("m")
            m.put(i, m.optLong(i, 0) + d)
        }
        dirty = true
    }

    @Synchronized
    fun addDevices(ctx: Context, clients: List<Client>) {
        val r = root(ctx)
        val last = r.getJSONObject("lastD")
        val names = r.getJSONObject("names")
        val dev = today(r).getJSONObject("d")
        for (c in clients) {
            val cur = c.down + c.up
            if (cur <= 0) continue
            val d = delta(cur, last.optLong(c.mac, -1))
            last.put(c.mac, cur)
            names.put(c.mac, c.name)
            if (d > 0) dev.put(c.mac, dev.optLong(c.mac, 0) + d)
        }
        dirty = true
    }

    /** Saves at most once a minute (or now with [force]), drops days older than 70, rebuilds the view. */
    @Synchronized
    fun flush(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (!dirty || now - lastSave < 60_000)) return
        val r = root(ctx)
        val days = r.getJSONObject("days")
        val oldest = dayKey(now - 70L * 86_400_000L)
        days.keys().asSequence().toList().filter { it < oldest }.forEach { days.remove(it) }
        try {
            val tmp = File(ctx.filesDir, "traffic.json.tmp")
            tmp.writeText(r.toString())
            tmp.renameTo(file(ctx))
        } catch (_: Exception) {
        }
        dirty = false
        lastSave = now
        val v = build(r, now)
        Monitor.post { view.value = v }
    }

    fun load(ctx: Context) {
        val v = synchronized(this) { build(root(ctx), System.currentTimeMillis()) }
        Monitor.post { view.value = v }
    }

    private fun build(r: JSONObject, now: Long): StatsView {
        val days = r.getJSONObject("days")
        val names = r.getJSONObject("names")
        fun row(k: String): DayRow {
            val o = days.optJSONObject(k) ?: return DayRow(k, listOf(0L, 0L), emptyMap())
            val m = o.optJSONArray("m")
            val d = o.optJSONObject("d") ?: JSONObject()
            return DayRow(k, listOf(m?.optLong(0) ?: 0L, m?.optLong(1) ?: 0L), d.keys().asSequence().associateWith { d.optLong(it) })
        }
        val last30 = (29 downTo 0).map { row(dayKey(now - it * 86_400_000L)) }
        val todayKey = dayKey(now)
        val month = todayKey.substring(0, 7)
        val monthRows = days.keys().asSequence().filter { it.startsWith(month) }.map { row(it) }.toList()
        val monthByModem = listOf(monthRows.sumOf { it.modems[0] }, monthRows.sumOf { it.modems[1] })

        // average over up to 7 full days before today; with no full days yet, today's pace
        val full = last30.dropLast(1).takeLast(7).filter { it.total > 0 }
        val today = last30.last()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayFrac = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440.0
        fun avg(sel: (DayRow) -> Long): Long = when {
            full.isNotEmpty() -> full.sumOf(sel) / full.size
            dayFrac > 0.1 -> (sel(today) / dayFrac).toLong()
            else -> sel(today)
        }
        val perDayByModem = listOf(avg { it.modems[0] }, avg { it.modems[1] })
        val perDay = perDayByModem.sum()
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val daysLeft = daysInMonth - cal.get(Calendar.DAY_OF_MONTH)
        val restOfToday = maxOf(0L, (perDay * (1 - dayFrac)).toLong())
        val forecast = monthByModem.sum() + restOfToday + perDay * daysLeft

        val devMonth = HashMap<String, Long>()
        monthRows.forEach { d -> d.devices.forEach { (mac, b) -> devMonth[mac] = (devMonth[mac] ?: 0) + b } }
        val devices = devMonth.entries.sortedByDescending { it.value }.map { (mac, b) ->
            DeviceUse(mac, names.optString(mac).ifEmpty { mac.uppercase() }, b, today.devices[mac] ?: 0)
        }
        return StatsView(last30, monthByModem.sum(), monthByModem, forecast, perDay, perDayByModem, daysLeft, devices)
    }
}
