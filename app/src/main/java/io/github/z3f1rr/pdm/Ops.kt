package io.github.z3f1rr.pdm

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class Balance(val text: String, val rub: Double?, val time: Long)

/**
 * Data left on a SIM's package. The user enters "left now" once; after that the modem's own byte
 * counter is subtracted, and on the payment day the package is added (with or without rollover).
 */
data class Quota(
    val leftBytes: Long,
    val packGb: Int,
    /** Day of month the package renews, 0 = unknown. */
    val day: Int,
    val rollover: Boolean,
    /** When the user last entered the numbers: for the usage-per-day estimate. */
    val setAt: Long,
    val usedSinceSet: Long,
) {
    val leftGb: Double get() = leftBytes / 1e9
    val fraction: Double get() = if (packGb > 0) leftBytes / (packGb * 1e9) else 1.0

    /** Days until the next payment day. */
    fun daysToRenew(now: Long = System.currentTimeMillis()): Int? {
        if (day !in 1..31) return null
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val n = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(java.util.Calendar.DAY_OF_MONTH)))
            if (!after(c)) { add(java.util.Calendar.MONTH, 1); set(java.util.Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(java.util.Calendar.DAY_OF_MONTH))) }
        }
        return ((n.timeInMillis - now) / 86_400_000L).toInt()
    }

    /** GB per day so far, once at least 6 hours of data exist. */
    fun perDayGb(now: Long = System.currentTimeMillis()): Double? {
        val days = (now - setAt) / 86_400_000.0
        return if (days >= 0.25) usedSinceSet / 1e9 / days else null
    }
}

/** LTE band presets for the modem sheet: label to hex mask. */
val BAND_PRESETS = listOf(
    "Все" to "7FFFFFFFFFFFFFFF",
    "B20" to "80000",
    "B3" to "4",
    "B7" to "40",
    "B1" to "1",
    "B3+B7" to "44",
    "B3+B20" to "80004",
)

fun bandsLabel(mask: String): String {
    val v = mask.toULongOrNull(16) ?: return mask
    if (v >= 0x7FFFFFFFFFFFFFFFuL || v == 0uL) return "Все"
    return (0 until 64).filter { v and (1uL shl it) != 0uL }.joinToString("+") { "B${it + 1}" }
}

/** Operations started from the screen: one at a time, results go to Monitor state. */
object Ops {
    private val ex = Executors.newSingleThreadExecutor()

    val router = mutableStateOf<RouterState?>(null)
    val log = mutableStateOf<List<LogLine>?>(null)
    val balances = mutableStateOf<Map<Int, Balance>>(emptyMap())
    val quotas = mutableStateOf<Map<Int, Quota>>(emptyMap())
    /** Modems whose firmware has no USSD: the balance block is hidden for them. */
    val ussdOff = mutableStateOf<Set<Int>>(emptySet())
    val sms = mutableStateOf<Map<Int, List<HiLink.Sms>>>(emptyMap())
    val bands = mutableStateOf<Map<Int, String>>(emptyMap())
    /** Keys of operations in progress: "bal0", "sms1", "bands0", "log", "limit:<mac>"… */
    val busy = mutableStateOf<Set<String>>(emptySet())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE)

    /** Toast, not the in-screen message: it shows above the bottom sheets too. */
    fun say(ctx: Context, text: String) = Monitor.post { android.widget.Toast.makeText(ctx.applicationContext, text, android.widget.Toast.LENGTH_LONG).show() }

    /** Register new devices in the router: it counts traffic only for registered ones. */
    fun autoRegister(ctx: Context) = prefs(ctx).getBoolean("auto_reg", true)

    fun run(ctx: Context, key: String, block: (Context) -> Unit) {
        val app = ctx.applicationContext
        if (key in busy.value) return
        busy.value = busy.value + key
        ex.execute {
            try {
                block(app)
            } catch (e: Throwable) {
                say(app, Monitor.short(e).substringAfter(": ").ifBlank { "Не получилось" })
            } finally {
                Monitor.post { busy.value = busy.value - key }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    fun routerLogin(ctx: Context) = prefs(ctx).getString("r_login", "admin")!!
    fun routerPass(ctx: Context) = prefs(ctx).getString("r_pass", "")!!
    fun ussd(ctx: Context, i: Int) = prefs(ctx).getString("ussd$i", "*100#")!!
    fun heal(ctx: Context) = prefs(ctx).getBoolean("heal", true)
    fun autoMain(ctx: Context) = prefs(ctx).getBoolean("auto_main", true)
    fun routeLog(ctx: Context) = prefs(ctx).getBoolean("route_log", false)

    fun saveRouter(ctx: Context, login: String, pass: String, ussd: List<String>) {
        val e = prefs(ctx).edit().putString("r_login", login.trim()).putString("r_pass", pass)
        ussd.forEachIndexed { i, c -> e.putString("ussd$i", c.trim().ifEmpty { "*100#" }) }
        e.apply()
    }

    // ---------------------------------------------------------------- data packages

    fun loadQuotas(ctx: Context) {
        val p = prefs(ctx)
        val m = (0..1).mapNotNull { i ->
            val set = p.getLong("q_set$i", 0)
            if (set == 0L) null else i to Quota(
                leftBytes = p.getLong("q_left$i", 0) - p.getLong("q_used$i", 0),
                packGb = p.getInt("q_pack$i", 100), day = p.getInt("q_day$i", 0),
                rollover = p.getBoolean("q_roll$i", false), setAt = set, usedSinceSet = p.getLong("q_usedall$i", 0),
            )
        }.toMap()
        Monitor.post { quotas.value = m }
    }

    /** The user's numbers: left now (GB), package size, payment day, rollover. Counting restarts from here. */
    fun setQuota(ctx: Context, i: Int, leftGb: Double, packGb: Int, day: Int, roll: Boolean) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putLong("q_left$i", (leftGb * 1e9).toLong()).putLong("q_used$i", 0).putLong("q_usedall$i", 0)
            .putInt("q_pack$i", packGb).putInt("q_day$i", day).putBoolean("q_roll$i", roll)
            .putLong("q_set$i", now).putLong("q_renew$i", now).putLong("q_last$i", -1)
            .apply()
        loadQuotas(ctx)
    }

    /**
     * Called on every modem poll with the modem's lifetime counter. A counter that went down means the
     * modem reset it: then the whole new value is fresh traffic. Applies the monthly renewal too.
     */
    fun countTraffic(ctx: Context, i: Int, counter: Long) {
        val p = prefs(ctx)
        if (p.getLong("q_set$i", 0) == 0L) return
        val last = p.getLong("q_last$i", -1)
        val e = p.edit().putLong("q_last$i", counter)
        if (last >= 0) {
            val delta = if (counter >= last) counter - last else counter
            // more than 2 GB in one poll is not real traffic: a counter glitch, skip it
            if (delta in 1..2_000_000_000L) {
                e.putLong("q_used$i", p.getLong("q_used$i", 0) + delta)
                e.putLong("q_usedall$i", p.getLong("q_usedall$i", 0) + delta)
            }
        }
        val day = p.getInt("q_day$i", 0)
        if (day in 1..31) {
            val renewAt = lastRenewal(day)
            if (p.getLong("q_renew$i", 0) < renewAt) {
                val left = p.getLong("q_left$i", 0) - p.getLong("q_used$i", 0)
                val pack = p.getInt("q_pack$i", 100) * 1_000_000_000L
                val roll = p.getBoolean("q_roll$i", false)
                e.putLong("q_left$i", if (roll) maxOf(0, left) + pack else pack).putLong("q_used$i", 0).putLong("q_renew$i", renewAt)
            }
        }
        e.apply()
    }

    /** Start of the most recent payment day (today if it's the day). */
    private fun lastRenewal(day: Int): Long {
        val c = java.util.Calendar.getInstance()
        fun on(cal: java.util.Calendar) = cal.apply {
            set(java.util.Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(java.util.Calendar.DAY_OF_MONTH)))
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val thisMonth = on(java.util.Calendar.getInstance())
        if (!thisMonth.after(c)) return thisMonth.timeInMillis
        return on(java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -1) }).timeInMillis
    }

    // ---------------------------------------------------------------- clients

    fun keenetic(ctx: Context): Keenetic? {
        val pass = routerPass(ctx)
        if (pass.isEmpty()) return null
        val n = Monitor.wifiNetwork(ctx) ?: return null
        val gw = Monitor.gateway(ctx, n) ?: return null
        return Keenetic(gw, Monitor.via(ctx, n), routerLogin(ctx), pass)
    }

    private fun needKeenetic(ctx: Context) = keenetic(ctx) ?: throw IllegalStateException("Нет связи с роутером")

    fun modem(ctx: Context, i: Int): HiLink {
        val m = Monitor.config(ctx).getOrNull(i) ?: throw IllegalStateException("Нет модема $i")
        return HiLink(m.ip, Monitor.wifiNetwork(ctx)?.let { Monitor.via(ctx, it) })
    }

    // ---------------------------------------------------------------- modem

    fun loadBalances(ctx: Context) {
        val p = prefs(ctx)
        ussdOff.value = (0..1).filter { p.getBoolean("ussd_off$it", false) }.toSet()
        balances.value = (0..1).mapNotNull { i ->
            val t = p.getLong("balt$i", 0)
            if (t == 0L) null else i to Balance(p.getString("bal$i", "")!!, rub(p.getString("bal$i", "")!!), t)
        }.toMap()
    }

    /** USSD balance request; returns the parsed balance. Used by the screen and by the 6-hour auto check. */
    fun balance(ctx: Context, i: Int): Balance {
        val text = try { modem(ctx, i).ussd(ussd(ctx, i)) } catch (e: IllegalStateException) {
            if (e.message?.contains("не поддерживает") == true) {
                prefs(ctx).edit().putBoolean("ussd_off$i", true).apply()
                Monitor.post { ussdOff.value = ussdOff.value + i }
            }
            throw e
        }
        val b = Balance(text, rub(text), System.currentTimeMillis())
        prefs(ctx).edit().putString("bal$i", text).putLong("balt$i", b.time).apply()
        Monitor.post { balances.value = balances.value + (i to b) }
        return b
    }

    /** "Баланс 312,50 р." → 312.5; the number right before р/руб/₽, or after "баланс". */
    fun rub(text: String): Double? {
        val t = text.replace(' ', ' ')
        val m = Regex("""(-?\d[\d ]*(?:[.,]\d{1,2})?)\s*(?:р\b|р\.|руб|₽|rub)""", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("""(?i)баланс\D{0,15}(-?\d[\d ]*(?:[.,]\d{1,2})?)""").find(t)
            ?: return null
        return m.groupValues[1].replace(" ", "").replace(',', '.').toDoubleOrNull()
    }

    fun loadSms(ctx: Context, i: Int) {
        val h = modem(ctx, i)
        val list = h.sms()
        Monitor.post { sms.value = sms.value + (i to list) }
        list.filter { it.unread }.forEach { try { h.markRead(it.index) } catch (_: Exception) {} }
    }

    fun loadBands(ctx: Context, i: Int) {
        val v = modem(ctx, i).lteBands()
        Monitor.post { bands.value = bands.value + (i to v) }
    }

    fun setBands(ctx: Context, i: Int, mask: String) {
        modem(ctx, i).setLteBands(mask)
        Monitor.post {
            bands.value = bands.value + (i to mask)
            say(ctx, "Диапазон ${bandsLabel(mask)} — модем переподключится")
        }
    }

    // ---------------------------------------------------------------- router

    fun loadLog(ctx: Context) {
        val l = needKeenetic(ctx).log()
        Monitor.post { log.value = l }
    }

    fun makeMain(ctx: Context, iface: String) {
        needKeenetic(ctx).makeMain(iface, router.value?.channels.orEmpty())
        say(ctx, "Основной канал переключён")
    }

    fun limit(ctx: Context, c: Client, kbps: Limit?) {
        val k = needKeenetic(ctx)
        k.limit(c, kbps)
        refreshLimits(k)
        say(ctx, if (kbps == null) "${c.name}: без ограничения" else "${c.name}: ${kbps.label}")
    }

    fun limitAll(ctx: Context, kbps: Limit?) {
        val k = needKeenetic(ctx)
        k.limitAll(router.value?.clients.orEmpty(), kbps)
        refreshLimits(k)
        say(ctx, if (kbps == null) "Ограничения сняты" else "Всем: ${kbps.label}")
    }

    /** Re-reads the limits from the router, so the list shows what really got applied. */
    private fun refreshLimits(k: Keenetic) {
        val (hosts, unknown) = try { k.limits() } catch (e: Exception) { return }
        Monitor.post {
            router.value = router.value?.let { r ->
                r.copy(
                    unknownLimit = unknown,
                    clients = r.clients.map { c ->
                        // a device limited just now got registered by the router
                        val reg = c.registered || c.mac in hosts
                        c.copy(registered = reg, limitKbps = hosts[c.mac] ?: if (!reg) unknown else null)
                    },
                )
            }
        }
    }

    fun rebootRouter(ctx: Context) {
        needKeenetic(ctx).reboot()
        say(ctx, "Роутер перезагружается, 2–3 минуты")
    }

    // ---------------------------------------------------------------- signal log along the route

    private fun routeFile(ctx: Context) = File(ctx.filesDir, "route.csv")

    fun appendRoute(ctx: Context, modems: List<ModemState>, names: List<String>, loc: Location?) {
        val f = routeFile(ctx)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        if (!f.exists()) sb.append("time,lat,lon,modem,operator,net,band,cell,rsrp,sinr\n")
        modems.forEachIndexed { i, m ->
            if (!m.ok) return@forEachIndexed
            sb.append(time).append(',')
                .append(loc?.latitude?.let { "%.5f".format(Locale.US, it) } ?: "").append(',')
                .append(loc?.longitude?.let { "%.5f".format(Locale.US, it) } ?: "").append(',')
                .append(names.getOrNull(i) ?: i).append(',')
                .append(m.operator.replace(',', ' ')).append(',')
                .append(if (m.connected) m.net else "нет сети").append(',')
                .append(m.band).append(',').append(m.cell).append(',')
                .append(m.rsrp ?: "").append(',').append(m.sinr ?: "").append('\n')
        }
        f.appendText(sb.toString())
    }

    /** Copies the log to Downloads (no storage permission needed on Android 10+). */
    fun exportRoute(ctx: Context) {
        val f = routeFile(ctx)
        if (!f.exists()) throw IllegalStateException("Журнал пока пуст")
        val name = "PDM-route-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".csv"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("Не удалось создать файл")
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        say(ctx, "Сохранено в Загрузки: $name")
    }
}
