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
    val sms = mutableStateOf<Map<Int, List<HiLink.Sms>>>(emptyMap())
    val bands = mutableStateOf<Map<Int, String>>(emptyMap())
    /** Keys of operations in progress: "bal0", "sms1", "bands0", "log", "limit:<mac>"… */
    val busy = mutableStateOf<Set<String>>(emptySet())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pdm", Context.MODE_PRIVATE)

    fun run(ctx: Context, key: String, block: (Context) -> Unit) {
        val app = ctx.applicationContext
        if (key in busy.value) return
        busy.value = busy.value + key
        ex.execute {
            try {
                block(app)
            } catch (e: Throwable) {
                Monitor.post { Monitor.message.value = Monitor.short(e).substringAfter(": ").ifBlank { "Не получилось" } }
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
    fun routeLog(ctx: Context) = prefs(ctx).getBoolean("route_log", false)

    fun saveRouter(ctx: Context, login: String, pass: String, ussd: List<String>) {
        val e = prefs(ctx).edit().putString("r_login", login.trim()).putString("r_pass", pass)
        ussd.forEachIndexed { i, c -> e.putString("ussd$i", c.trim().ifEmpty { "*100#" }) }
        e.apply()
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
        balances.value = (0..1).mapNotNull { i ->
            val t = p.getLong("balt$i", 0)
            if (t == 0L) null else i to Balance(p.getString("bal$i", "")!!, rub(p.getString("bal$i", "")!!), t)
        }.toMap()
    }

    /** USSD balance request; returns the parsed balance. Used by the screen and by the 6-hour auto check. */
    fun balance(ctx: Context, i: Int): Balance {
        val text = modem(ctx, i).ussd(ussd(ctx, i))
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
            Monitor.message.value = "Диапазон ${bandsLabel(mask)} — модем переподключится"
        }
    }

    // ---------------------------------------------------------------- router

    fun loadLog(ctx: Context) {
        val l = needKeenetic(ctx).log()
        Monitor.post { log.value = l }
    }

    fun makeMain(ctx: Context, iface: String) {
        needKeenetic(ctx).makeMain(iface, router.value?.channels.orEmpty())
        Monitor.post { Monitor.message.value = "Основной канал переключён" }
    }

    fun limit(ctx: Context, c: Client, kbps: Int?) {
        val k = needKeenetic(ctx)
        k.limit(c, kbps)
        refreshLimits(k)
        Monitor.post { Monitor.message.value = if (kbps == null) "${c.name}: без ограничения" else "${c.name}: до ${kbps / 1000} Мбит/с" }
    }

    fun limitAll(ctx: Context, kbps: Int?) {
        val k = needKeenetic(ctx)
        k.limitAll(router.value?.clients.orEmpty(), kbps)
        refreshLimits(k)
        Monitor.post { Monitor.message.value = if (kbps == null) "Ограничения сняты" else "Всем до ${kbps / 1000} Мбит/с" }
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
        Monitor.post { Monitor.message.value = "Роутер перезагружается, 2–3 минуты" }
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
        Monitor.post { Monitor.message.value = "Сохранено в Загрузки: $name" }
    }
}
