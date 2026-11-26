package io.github.z3f1rr.pdm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- design tokens

private object C {
    val Bg = Color(0xFF0A0B0D)
    val Surface = Color(0xFF141619)
    val Raised = Color(0xFF1D2025)
    val Line = Color(0xFF262A30)
    val Text = Color(0xFFF3F4F6)
    val Text2 = Color(0xFF9CA3AF)
    val Text3 = Color(0xFF6B7280)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val Blue = Color(0xFF60A5FA)
    val Violet = Color(0xFFA78BFA)
}

private val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

/** Tabular figures: numbers don't jump when they change. */
private val Num = TextStyle(fontFamily = Onest, fontFeatureSettings = "tnum")

private fun onestTypography(): Typography {
    val t = Typography()
    fun TextStyle.o() = copy(fontFamily = Onest)
    return Typography(
        displayLarge = t.displayLarge.o(), displayMedium = t.displayMedium.o(), displaySmall = t.displaySmall.o(),
        headlineLarge = t.headlineLarge.o(), headlineMedium = t.headlineMedium.o(), headlineSmall = t.headlineSmall.o(),
        titleLarge = t.titleLarge.o(), titleMedium = t.titleMedium.o(), titleSmall = t.titleSmall.o(),
        bodyLarge = t.bodyLarge.o(), bodyMedium = t.bodyMedium.o(), bodySmall = t.bodySmall.o(),
        labelLarge = t.labelLarge.o(), labelMedium = t.labelMedium.o(), labelSmall = t.labelSmall.o(),
    )
}

private val Scheme = darkColorScheme(
    primary = C.Blue, onPrimary = C.Bg,
    background = C.Bg, onBackground = C.Text,
    surface = C.Surface, onSurface = C.Text, onSurfaceVariant = C.Text2,
    surfaceContainerLowest = C.Bg, surfaceContainerLow = C.Surface, surfaceContainer = C.Surface,
    surfaceContainerHigh = C.Raised, surfaceContainerHighest = C.Raised,
    secondaryContainer = C.Raised, onSecondaryContainer = C.Text,
    outline = C.Line, outlineVariant = C.Line, error = C.Red,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        MonitorService.createChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Monitor.speed.value == null) Monitor.speed.value = Monitor.loadSpeed(this)
        Monitor.ups.value = Monitor.loadUps(this)
        Ops.loadBalances(this)
        Ops.loadQuotas(this)
        Stats.load(this)
        if (getSharedPreferences("pdm", MODE_PRIVATE).getBoolean("auto", true)) MonitorService.start(this)
        setContent {
            MaterialTheme(colorScheme = Scheme, typography = onestTypography()) { Screen() }
        }
    }
}

// ---------------------------------------------------------------- screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val snap = Monitor.snapshot.value
    val running = Monitor.running.value
    val msg = Monitor.message.value
    val hist = Monitor.history.value
    val cfg = remember(snap) { Monitor.config(ctx) }
    var reboot by remember { mutableStateOf<Int?>(null) }
    var settings by remember { mutableStateOf(false) }
    var modemSheet by remember { mutableStateOf<Int?>(null) }
    var routerSheet by remember { mutableStateOf(false) }
    var statsSheet by remember { mutableStateOf(false) }
    val stats = Stats.view.value
    val router = Ops.router.value
    val balances = Ops.balances.value
    var refreshing by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(snap?.time) { refreshing = false }
    LaunchedEffect(refreshing) { if (refreshing) { delay(15_000); refreshing = false } }
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { delay(5000); Monitor.message.value = "" } }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; MonitorService.start(ctx) },
        modifier = Modifier.fillMaxSize().background(C.Bg),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Вагон", color = C.Text, fontFamily = Onest, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { settings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = C.Text2)
                }
            }

            Status(snap, running, now)

            if (running && snap != null && snap.label.contains("через VPN")) VpnHint()

            if (msg.isNotEmpty()) {
                Text(
                    msg, color = C.Text, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Raised).padding(14.dp),
                )
            }

            val where = snap?.where
            if (running && where != null && where != Where.TRAIN) {
                LocalCard(snap?.local, where, Monitor.localHistory.value)
            }
            if (running && where == Where.TRAIN) {
                if (router != null) RouterCard(router, cfg) { routerSheet = true }
                if (stats != null) TrafficCard(stats) { statsSheet = true }
                cfg.forEachIndexed { i, c ->
                    ModemCard(c, snap?.modems?.getOrNull(i), hist.getOrNull(i).orEmpty(), balances[i], Ops.quotas.value[i]) { modemSheet = i }
                }
            }

            UpsCard(now)

            BatteryHint()
        }
    }

    reboot?.let { i ->
        AlertDialog(
            onDismissRequest = { reboot = null },
            containerColor = C.Raised,
            title = { Text("Перезагрузить ${cfg[i].name}?") },
            text = { Text("Канал пропадёт примерно на минуту.", color = C.Text2) },
            confirmButton = {
                TextButton(onClick = { MonitorService.start(ctx, MonitorService.ACTION_REBOOT, i); reboot = null }) {
                    Text("Перезагрузить", color = C.Red)
                }
            },
            dismissButton = { TextButton(onClick = { reboot = null }) { Text("Отмена", color = C.Text2) } },
        )
    }

    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = C.Surface) {
            SettingsSheet(running) { settings = false }
        }
    }

    modemSheet?.let { i ->
        ModalBottomSheet(onDismissRequest = { modemSheet = null }, containerColor = C.Surface) {
            ModemSheet(i, cfg[i], snap?.modems?.getOrNull(i)) { modemSheet = null; reboot = i }
        }
    }

    if (statsSheet && stats != null) {
        ModalBottomSheet(onDismissRequest = { statsSheet = false }, containerColor = C.Surface) {
            AnalyticsSheet(stats, cfg)
        }
    }

    if (routerSheet) {
        ModalBottomSheet(onDismissRequest = { routerSheet = false; Ops.log.value = null }, containerColor = C.Surface) {
            RouterSheet(Ops.router.value, cfg)
        }
    }
}

// ---------------------------------------------------------------- status card

@Composable
private fun Status(s: Snapshot?, running: Boolean, now: Long) {
    val ctx = LocalContext.current
    val mins = s?.let { (now - it.since) / 60_000 } ?: 0
    val dur = if (mins > 0) fmtMin(mins) else ""
    fun join(vararg p: String?) = p.filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")
    val (tone, title, sub) = when {
        !running -> Triple(C.Text3, "Мониторинг выключен", "Включите в настройках")
        s == null -> Triple(C.Text3, "Проверяю…", "")
        s.where == null -> Triple(C.Text3, "На паузе", s.label)
        s.net == Net.FULL -> Triple(C.Green, "Интернет есть", join(s.label, s.latencyMs?.let { "$it мс" }))
        s.net == Net.WHITELIST -> Triple(C.Amber, "Белые списки", join(s.label, dur))
        s.net == Net.SLOW -> Triple(C.Amber, "Плохая связь", join(s.label, dur))
        else -> Triple(C.Red, "Нет интернета", join(s.label, if (dur.isNotEmpty()) "уже $dur" else null))
    }
    val age = s?.let { ((now - it.time) / 1000).coerceAtLeast(0) }
    val server = remember(s?.time) { Monitor.server(ctx) }
    val speed = Monitor.speed.value
    val measuring = Monitor.speedRunning.value

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(C.Surface)
            .background(Brush.verticalGradient(listOf(tone.copy(alpha = 0.16f), tone.copy(alpha = 0.02f))))
            .border(1.dp, tone.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(tone, live = running && s?.where != null)
            Spacer(Modifier.weight(1f))
            if (running && age != null) {
                Text(
                    if (age < 5) "сейчас" else "${age} с назад",
                    color = if (age > 60) C.Red else C.Text3, style = Num.copy(fontSize = 13.sp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = tone, fontFamily = Onest, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
            lineHeight = 36.sp, letterSpacing = (-0.5).sp)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, color = C.Text2, style = Num.copy(fontSize = 15.sp))
        }
        if (running && s != null && s.why.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(s.why, color = C.Text3, style = Num.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }

        if (running && server.isNotEmpty() && s?.where != null) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("↓ " + fmtSpeed(speed?.downMbps), color = C.Text, style = Num.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.width(14.dp))
                        Text("↑ " + fmtSpeed(speed?.upMbps), color = C.Text, style = Num.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                    }
                    Text(
                        if (speed == null) "Мбит/с · ещё не измеряли"
                        else "Мбит/с · " + SimpleDateFormat("HH:mm", Locale.US).format(Date(speed.time)),
                        color = C.Text3, style = Num.copy(fontSize = 13.sp),
                    )
                }
                Pill(
                    text = if (measuring) "Замер…" else "Замерить",
                    busy = measuring,
                    onClick = { if (!measuring) MonitorService.start(ctx, MonitorService.ACTION_SPEED) },
                )
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color, live: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).alpha(if (live) a else 1f).clip(CircleShape).background(color))
}

@Composable
private fun Pill(text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.Raised).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = C.Text2, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = C.Text, fontFamily = Onest, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun fmtMin(m: Long) = if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"

private fun fmtSpeed(v: Double?) = when {
    v == null -> "—"
    v < 10 -> "%.1f".format(Locale.US, v)
    else -> "%.0f".format(Locale.US, v)
}

// ---------------------------------------------------------------- modem card

@Composable
private fun ModemCard(c: ModemConfig, m: ModemState?, hist: List<Point>, bal: Balance?, quota: Quota?, onOpen: () -> Unit) {
    val live = m != null && m.ok && m.connected
    val q = if (live) m!!.quality else 0
    val tone = when {
        !live -> C.Red
        q >= 3 -> C.Green
        q == 2 -> C.Amber
        else -> C.Red
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface).clickable(onClick = onOpen).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, color = C.Text, fontFamily = Onest, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if ((m?.unread ?: 0) > 0) {
                        Spacer(Modifier.width(8.dp))
                        Badge("${m!!.unread}")
                    }
                }
                Text(
                    when {
                        m == null -> "Ожидание"
                        !m.ok -> "Не отвечает"
                        !m.connected -> "Нет сети"
                        else -> listOfNotNull(m.net, m.band.takeIf { it.isNotEmpty() }?.let { "B$it" }, quota?.let { gb(it.leftGb) } ?: bal?.rub?.let { rub(it) })
                            .joinToString(" · ")
                    },
                    color = if (live) C.Text2 else C.Red, style = Num.copy(fontSize = 14.sp),
                )
            }
            Bars(q, tone)
        }
        if (live) {
            Spacer(Modifier.height(14.dp))
            Row {
                Metric(m!!.rsrp?.toString() ?: "—", "RSRP", C.Blue, Modifier.weight(1f))
                Metric(m.sinr?.toString() ?: "—", "SINR", C.Amber, Modifier.weight(1f))
                Metric(fmtSpeed(m.dlMbps), "↓ Мбит/с", null, Modifier.weight(1f))
            }
            if (hist.size > 2) {
                Spacer(Modifier.height(14.dp))
                Spark(hist)
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text, color = C.Bg, style = Num.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.Blue).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

private fun gb(v: Double) = if (v >= 10) "%.0f ГБ".format(Locale("ru"), v) else "%.1f ГБ".format(Locale("ru"), v)

private fun rub(v: Double) = "%,.0f ₽".format(Locale("ru"), v).replace('\u00a0', ' ')

@Composable
private fun Metric(value: String, label: String, dot: Color?, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = C.Text, style = Num.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = C.Text3, fontFamily = Onest, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Bars(q: Int, on: Color) {
    Canvas(Modifier.size(width = 26.dp, height = 18.dp)) {
        val w = size.width / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            drawRoundRect(
                color = if (i < q) on else C.Line,
                topLeft = Offset(i * w + w * 0.18f, size.height - h),
                size = Size(w * 0.64f, h),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
    }
}

@Composable
private fun Spark(h: List<Point>, lo: Float = -125f, hi: Float = -65f) {
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val t0 = h.first().t
        val span = (h.last().t - t0).coerceAtLeast(1)
        fun line(sel: (Point) -> Int?, lo: Float, hi: Float, color: Color) {
            val p = Path()
            var started = false
            h.forEach { pt ->
                val v = sel(pt) ?: return@forEach
                val x = (pt.t - t0).toFloat() / span * size.width
                val y = size.height - (v.toFloat().coerceIn(lo, hi) - lo) / (hi - lo) * size.height
                if (started) p.lineTo(x, y) else { p.moveTo(x, y); started = true }
            }
            drawPath(p, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        line({ it.rsrp }, lo, hi, C.Blue)
        line({ it.sinr }, -10f, 30f, C.Amber.copy(alpha = 0.85f))
    }
}

// ---------------------------------------------------------------- phone's own link

@Composable
private fun LocalCard(l: Local?, where: Where, hist: List<Point>) {
    val q = l?.quality ?: 0
    val tone = when {
        q >= 3 -> C.Green
        q == 2 -> C.Amber
        else -> C.Red
    }
    val wifi = where == Where.WIFI
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    l?.name?.takeIf { it.isNotEmpty() } ?: if (wifi) "Wi-Fi" else "Мобильный интернет",
                    color = C.Text, fontFamily = Onest, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(if (wifi) "Wi-Fi телефона" else "SIM телефона", l?.gen?.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    color = C.Text2, style = Num.copy(fontSize = 14.sp),
                )
            }
            Bars(q, tone)
        }
        if (l != null) {
            Spacer(Modifier.height(14.dp))
            Row {
                if (wifi) {
                    Metric(l.dbm?.toString() ?: "—", "Сигнал, дБм", C.Blue, Modifier.weight(1f))
                    Metric(l.linkMbps?.toString() ?: "—", "Линк, Мбит/с", null, Modifier.weight(1f))
                } else if (l.rsrp != null) {
                    Metric(l.rsrp.toString(), "RSRP", C.Blue, Modifier.weight(1f))
                    Metric(l.sinr?.toString() ?: "—", "SINR", C.Amber, Modifier.weight(1f))
                } else {
                    Metric(l.dbm?.toString() ?: "—", "Сигнал, дБм", C.Blue, Modifier.weight(1f))
                }
            }
            if (hist.size > 2) {
                Spacer(Modifier.height(14.dp))
                if (wifi) Spark(hist, -90f, -30f)
                else if (l.rsrp != null) Spark(hist)
                else Spark(hist, -115f, -55f)
            }
        }
    }
}

// ---------------------------------------------------------------- VPN

@Composable
private fun VpnHint() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface)
            .border(1.dp, C.Amber.copy(alpha = 0.25f), RoundedCornerShape(20.dp)).padding(18.dp),
    ) {
        Text("VPN не пускает приложение в Wi-Fi напрямую", color = C.Amber, fontFamily = Onest, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Сейчас проверка идёт через VPN и показывает, работает ли VPN, а не сам канал. Добавьте «ПДМ-статус» " +
                "в исключения VPN-клиента (раздельное туннелирование). Если в настройках Android у VPN включено " +
                "«Блокировать соединения без VPN» — выключите, иначе исключённое приложение останется без сети.",
            color = C.Text2, style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------------------------------------------------------------- diagnostics

@Composable
private fun DiagRow() {
    val ctx = LocalContext.current
    val busy = Monitor.diagRunning.value
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Подробная проверка сетей, роутера и модемов", color = C.Text2,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Pill(if (busy) "Проверяю…" else "Диагностика", busy = busy) {
            if (busy) return@Pill
            Monitor.diagRunning.value = true
            val app = ctx.applicationContext
            Thread {
                val r = try { Monitor.diagnose(app) } catch (e: Throwable) { "Диагностика упала: " + Monitor.short(e) }
                Monitor.post { Monitor.diag.value = r; Monitor.diagRunning.value = false }
            }.apply { isDaemon = true }.start()
        }
    }
    Monitor.diag.value?.let { text ->
        AlertDialog(
            onDismissRequest = { Monitor.diag.value = null },
            containerColor = C.Raised,
            title = { Text("Диагностика") },
            text = {
                Text(text, color = C.Text2, style = Num.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = {
                    val cb = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("ПДМ диагностика", text))
                    Monitor.message.value = "Скопировано"
                    Monitor.diag.value = null
                }) { Text("Копировать") }
            },
            dismissButton = { TextButton(onClick = { Monitor.diag.value = null }) { Text("Закрыть", color = C.Text2) } },
        )
    }
}

// ---------------------------------------------------------------- UPS test (temporary)

@Composable
private fun UpsCard(now: Long) {
    val ctx = LocalContext.current
    val u = Monitor.ups.value
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Тест ИБП", color = C.Text, fontFamily = Onest, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            when {
                u == null -> Pill("Старт", busy = false) { MonitorService.start(ctx, MonitorService.ACTION_UPS_START) }
                u.end == null -> Pill("Стоп", busy = false) { MonitorService.start(ctx, MonitorService.ACTION_UPS_STOP) }
                else -> Pill("Сбросить", busy = false) { confirmReset = true }
            }
        }
        when {
            u == null -> {
                Spacer(Modifier.height(6.dp))
                Text("Нажмите «Старт» и сразу выключите питание ИБП. Телефон держите рядом с роутером — " +
                    "засеку, когда роутер перестанет отвечать.", color = C.Text2, style = MaterialTheme.typography.bodyMedium)
            }
            u.end == null -> {
                Spacer(Modifier.height(10.dp))
                Text(clock(now - u.start), color = C.Text, style = Num.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp))
                val down = u.downSince
                Text(
                    if (down == null) "Роутер отвечает · проверка ${((now - u.lastSeen) / 1000).coerceAtLeast(0)} с назад"
                    else "Роутер молчит ${clock(now - down)} · итог через ${clock((down + MonitorService.UPS_DEAD_MS - now).coerceAtLeast(0))}",
                    color = if (down == null) C.Text2 else C.Amber, style = Num.copy(fontSize = 14.sp),
                )
                if (u.gaps > 0) Text("Роутер перезагружался: ${u.gaps}", color = C.Amber, style = Num.copy(fontSize = 14.sp))
            }
            else -> {
                Spacer(Modifier.height(10.dp))
                Text(MonitorService.fmtDuration(u.end - u.start), color = C.Green,
                    style = Num.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp))
                Text(
                    "Роутер работал от ИБП с " + SimpleDateFormat("HH:mm", Locale.US).format(Date(u.start)) +
                        " до " + SimpleDateFormat("HH:mm", Locale.US).format(Date(u.end)) +
                        if (u.gaps > 0) " · перезагрузок: ${u.gaps}" else "",
                    color = C.Text2, style = Num.copy(fontSize = 14.sp),
                )
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = C.Raised,
            title = { Text("Сбросить результат?") },
            confirmButton = {
                TextButton(onClick = { MonitorService.start(ctx, MonitorService.ACTION_UPS_RESET); confirmReset = false }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена", color = C.Text2) } },
        )
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------- hints and settings

@Composable
private fun BatteryHint() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    tick.hashCode()
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Разрешите работу в фоне, иначе Android усыпит проверку", color = C.Text2,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Pill("Разрешить", busy = false) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
    }
}

@Composable
private fun SettingsSheet(running: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val cfg = remember { Monitor.config(ctx) }
    var n0 by remember { mutableStateOf(cfg[0].name) }
    var i0 by remember { mutableStateOf(cfg[0].ip) }
    var u0 by remember { mutableStateOf(Ops.ussd(ctx, 0)) }
    var n1 by remember { mutableStateOf(cfg[1].name) }
    var i1 by remember { mutableStateOf(cfg[1].ip) }
    var u1 by remember { mutableStateOf(Ops.ussd(ctx, 1)) }
    var login by remember { mutableStateOf(Ops.routerLogin(ctx)) }
    var pass by remember { mutableStateOf(Ops.routerPass(ctx)) }
    var agentKey by remember { mutableStateOf(Agent.token(ctx)) }
    var server by remember { mutableStateOf(Monitor.server(ctx)) }
    var interval by remember { mutableStateOf(Monitor.intervalSec(ctx).toString()) }
    var routeOn by remember { mutableStateOf(Ops.routeLog(ctx)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // the service re-declares itself with the location type, so coordinates keep coming with the screen off
        if (running) MonitorService.start(ctx)
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мониторинг", color = C.Text, fontFamily = Onest, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(
                checked = running,
                onCheckedChange = { on ->
                    ctx.getSharedPreferences("pdm", 0).edit().putBoolean("auto", on).apply()
                    if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Bg),
            )
        }
        FlagRow("Другие Wi-Fi", "Проверять интернет в любой сети, не только в вагоне", "other_wifi", Monitor.otherWifi(ctx), running)
        FlagRow("Мобильный интернет", "Проверять, когда Wi-Fi нет", "mobile", Monitor.mobile(ctx), running)
        FlagRow("Имя Wi-Fi через root", "KernelSU спросит разрешение один раз", "root_ssid", Monitor.rootSsid(ctx), running)
        FlagRow("Трафик каждого устройства", "Регистрировать новые устройства в роутере: иначе он не считает их трафик", "auto_reg", Ops.autoRegister(ctx), running)
        FlagRow("Автовыбор канала", "Основным становится модем с лучшим сигналом, при равном — где больше осталось трафика", "auto_main", Ops.autoMain(ctx), running)
        FlagRow("Автолечение модемов", "Нет сети 1,5 мин — переподключить, 5 мин — перезагрузить", "heal", Ops.heal(ctx), running)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                FlagRow("Журнал сигнала", "Раз в минуту: время, координаты, соты, сигнал", "route_log", routeOn, running) { on ->
                    routeOn = on
                    if (on) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }
        if (routeOn) {
            Row {
                Spacer(Modifier.weight(1f))
                Pill(if ("export" in Ops.busy.value) "Выгружаю…" else "Выгрузить в Загрузки", busy = "export" in Ops.busy.value) {
                    Ops.run(ctx, "export") { Ops.exportRoute(it) }
                }
            }
        }

        SectionTitle("Сервер и интервал")
        OutlinedTextField(
            server, { server = it.trim() }, label = { Text("Сервер проверки") },
            placeholder = { Text("http://IP:порт/ключ") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            interval, { interval = it.filter(Char::isDigit).take(3) },
            label = { Text("Проверка раз в, секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Роутер Keenetic")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(login, { login = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.weight(1f),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        OutlinedTextField(
            agentKey, { agentKey = it.trim() }, label = { Text("Ключ агента на роутере") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (agentKey.isNotEmpty()) Text(if (Agent.online.value) "Агент работает: статистика и автоматика на роутере" else "Агент не отвечает")
            },
        )

        SectionTitle("Модемы")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(n0, { n0 = it }, label = { Text("Модем 1") }, singleLine = true, modifier = Modifier.weight(1.2f))
            OutlinedTextField(i0, { i0 = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.weight(1.2f))
            OutlinedTextField(u0, { u0 = it }, label = { Text("Баланс") }, singleLine = true, modifier = Modifier.weight(0.9f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(n1, { n1 = it }, label = { Text("Модем 2") }, singleLine = true, modifier = Modifier.weight(1.2f))
            OutlinedTextField(i1, { i1 = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.weight(1.2f))
            OutlinedTextField(u1, { u1 = it }, label = { Text("Баланс") }, singleLine = true, modifier = Modifier.weight(0.9f))
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                Monitor.save(ctx, listOf(ModemConfig(n0, i0), ModemConfig(n1, i1)), interval.toIntOrNull() ?: 10, server)
                Ops.saveRouter(ctx, login, pass, listOf(u0, u1))
                Agent.setToken(ctx, agentKey)
                Monitor.message.value = "Сохранено"
                if (running) MonitorService.start(ctx)
                close()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Text, contentColor = C.Bg),
        ) { Text("Сохранить", fontFamily = Onest, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        Spacer(Modifier.height(6.dp))
        DiagRow()
    }
}

@Composable
private fun FlagRow(title: String, hint: String, key: String, initial: Boolean, running: Boolean, onChange: ((Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontFamily = Onest, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(hint, color = C.Text3, fontFamily = Onest, fontSize = 13.sp)
        }
        Switch(
            checked = on,
            onCheckedChange = { v ->
                on = v
                Monitor.setFlag(ctx, key, v)
                Monitor.resetPlace()
                onChange?.invoke(v)
                if (key in listOf("auto_main", "heal", "auto_reg") && Agent.active()) {
                    Ops.run(ctx, "agentset") { Agent.post(it, "/api/settings", org.json.JSONObject().put(key, v)) }
                }
                if (running) MonitorService.start(ctx)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Bg),
        )
    }
}

// ---------------------------------------------------------------- router

private fun plural(n: Int, one: String, few: String, many: String): String {
    val d = n % 10
    val h = n % 100
    return "$n " + when {
        d == 1 && h != 11 -> one
        d in 2..4 && h !in 12..14 -> few
        else -> many
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.1f ГБ".format(Locale("ru"), b / 1e9)
    b >= 1_000_000L -> "${b / 1_000_000} МБ"
    else -> "${b / 1000} КБ"
}

private fun fmtLimit(kbps: Int) = if (kbps % 1000 == 0) "${kbps / 1000} Мбит/с" else "%.1f Мбит/с".format(Locale("ru"), kbps / 1000.0)

private fun chName(ch: Channel, cfg: List<ModemConfig>) = cfg.getOrNull(ch.modem)?.name ?: ch.iface

@Composable
private fun RouterCard(r: RouterState, cfg: List<ModemConfig>, onOpen: () -> Unit) {
    val main = r.channels.firstOrNull { it.main }
    val active = r.clients.count { it.active }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface).clickable(onClick = onOpen).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Роутер", color = C.Text, fontFamily = Onest, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (r.clients.isNotEmpty()) Text(plural(active, "устройство", "устройства", "устройств"), color = C.Text2, style = Num.copy(fontSize = 14.sp))
        }
        if (r.error != null && r.channels.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(r.error, color = C.Red, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("↓ " + fmtSpeed(r.downMbps), color = C.Text, style = Num.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(14.dp))
            Text("↑ " + fmtSpeed(r.upMbps), color = C.Text, style = Num.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(
            listOfNotNull("Мбит/с", main?.let { "через " + chName(it, cfg) }).joinToString(" · "),
            color = C.Text3, style = Num.copy(fontSize = 13.sp),
        )
        if (r.error != null) Text(r.error, color = C.Amber, style = Num.copy(fontSize = 12.sp))
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = C.Text3, fontFamily = Onest, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun RouterSheet(r: RouterState?, cfg: List<ModemConfig>) {
    val ctx = LocalContext.current
    val busy = Ops.busy.value
    var limitFor by remember { mutableStateOf<Client?>(null) }
    var limitAll by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    val log = Ops.log.value
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Text("Роутер", color = C.Text, fontFamily = Onest, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (r == null) return@Column

        SectionTitle("Каналы")
        r.channels.forEach { ch ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(chName(ch, cfg), color = C.Text, fontFamily = Onest, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        listOf(
                            when { ch.main -> "основной"; !ch.connected -> "нет связи"; else -> "резерв" },
                            "↓ ${fmtSpeed(ch.downMbps)} ↑ ${fmtSpeed(ch.upMbps)}",
                            fmtBytes(ch.rxBytes + ch.txBytes),
                        ).joinToString(" · "),
                        color = when { ch.main -> C.Green; !ch.connected -> C.Red; else -> C.Text2 },
                        style = Num.copy(fontSize = 13.sp),
                    )
                }
                if (!ch.main && ch.connected) {
                    val key = "main:${ch.iface}"
                    Pill("Сделать основным", busy = key in busy) { Ops.run(ctx, key) { Ops.makeMain(it, ch.iface) } }
                }
            }
        }

        val online = r.clients.filter { it.active }
        val offline = r.clients.filter { !it.active }
        Row(verticalAlignment = Alignment.Bottom) {
            SectionTitle("Устройства · ${online.size}", Modifier.weight(1f))
            Pill(
                when {
                    "limitall" in busy -> "Применяю…"
                    r.unknownLimit != null -> "Всем ${r.unknownLimit.label}"
                    else -> "Ограничить всех"
                },
                busy = "limitall" in busy,
            ) { limitAll = true }
        }
        online.forEach { cl -> ClientRow(cl) { limitFor = cl } }
        if (offline.isNotEmpty()) {
            Text(
                if (showOffline) "Скрыть не в сети" else "Не в сети · ${offline.size}",
                color = C.Text3, fontFamily = Onest, fontSize = 14.sp,
                modifier = Modifier.clickable { showOffline = !showOffline }.padding(vertical = 10.dp),
            )
            if (showOffline) offline.forEach { cl -> ClientRow(cl) { limitFor = cl } }
        }

        val events = Agent.events.value
        if (events.isNotEmpty()) {
            SectionTitle("События")
            events.take(15).forEach { e ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(e.time)), color = C.Text3, style = Num.copy(fontSize = 12.sp), modifier = Modifier.width(84.dp))
                    Text(e.text, color = C.Text2, style = Num.copy(fontSize = 12.sp, lineHeight = 16.sp))
                }
            }
        }

        SectionTitle("Журнал роутера")
        when {
            log == null -> Pill(if ("log" in busy) "Загружаю…" else "Показать", busy = "log" in busy) { Ops.run(ctx, "log") { Ops.loadLog(it) } }
            log.isEmpty() -> Text("Событий нет", color = C.Text2, style = MaterialTheme.typography.bodyMedium)
            else -> log.forEach { l ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(l.time.takeLast(8), color = C.Text3, style = Num.copy(fontSize = 12.sp), modifier = Modifier.width(64.dp))
                    Text(l.text, color = C.Text2, style = Num.copy(fontSize = 12.sp, lineHeight = 16.sp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { confirmReboot = true }) { Text("Перезагрузить роутер", color = C.Red, fontFamily = Onest) }
    }

    limitFor?.let { cl ->
        LimitDialog(cl.name, listOfNotNull(cl.ip.takeIf { it.isNotEmpty() }, cl.mac.uppercase()).joinToString(" · "), cl.limitKbps,
            onClose = { limitFor = null }) { kbps -> Ops.run(ctx, "limit:${cl.mac}") { Ops.limit(it, cl, kbps) } }
    }
    if (limitAll) {
        LimitDialog("Все устройства", "Новые устройства тоже получат это ограничение", r?.unknownLimit,
            onClose = { limitAll = false }) { kbps -> Ops.run(ctx, "limitall") { Ops.limitAll(it, kbps) } }
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            containerColor = C.Raised,
            title = { Text("Перезагрузить роутер?") },
            text = { Text("Wi-Fi и интернет в вагоне пропадут на 2–3 минуты.", color = C.Text2) },
            confirmButton = {
                TextButton(onClick = { Ops.run(ctx, "reboot") { Ops.rebootRouter(it) }; confirmReboot = false }) {
                    Text("Перезагрузить", color = C.Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("Отмена", color = C.Text2) } },
        )
    }
}

@Composable
private fun LimitDialog(title: String, sub: String, current: Limit?, onClose: () -> Unit, onPick: (Limit?) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.Raised,
        title = { Text(title) },
        text = {
            Column {
                Text(sub, color = C.Text3, style = Num.copy(fontSize = 13.sp))
                Spacer(Modifier.height(12.dp))
                (listOf<Limit?>(null) + Limit.PRESETS).forEach { kbps ->
                    val sel = current == kbps
                    Text(
                        if (kbps == null) "Без ограничения" else "↓ ${Limit.mbit(kbps.down)}   ↑ ${Limit.mbit(kbps.up)} Мбит/с",
                        color = if (sel) C.Blue else C.Text, fontFamily = Onest, fontSize = 16.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(kbps); onClose() }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена", color = C.Text2) } },
    )
}

@Composable
private fun ClientRow(cl: Client, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(cl.name, color = if (cl.active) C.Text else C.Text3, fontFamily = Onest, fontSize = 15.sp, maxLines = 1)
            Text(fmtBytes(cl.down + cl.up), color = C.Text3, style = Num.copy(fontSize = 13.sp))
        }
        cl.limitKbps?.let {
            Text(
                it.label, color = C.Amber, style = Num.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(C.Amber.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (cl.active) {
            val d = cl.downMbps ?: 0.0
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "↓ " + fmtSpeed(cl.downMbps),
                    color = if (d >= 5) C.Amber else C.Text,
                    style = Num.copy(fontSize = 15.sp, fontWeight = if (d >= 5) FontWeight.SemiBold else FontWeight.Normal),
                )
                Text("↑ " + fmtSpeed(cl.upMbps), color = C.Text3, style = Num.copy(fontSize = 13.sp))
            }
        }
    }
}

// ---------------------------------------------------------------- modem sheet

@Composable
private fun ModemSheet(i: Int, c: ModemConfig, m: ModemState?, onReboot: () -> Unit) {
    val ctx = LocalContext.current
    val busy = Ops.busy.value
    val bal = Ops.balances.value[i]
    val band = Ops.bands.value[i]
    val sms = Ops.sms.value[i]
    var confirmBand by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(i) {
        Ops.run(ctx, "bands$i") { Ops.loadBands(it, i) }
        Ops.run(ctx, "sms$i") { Ops.loadSms(it, i) }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Text(c.name, color = C.Text, fontFamily = Onest, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (m != null && m.ok) {
            Text(
                listOfNotNull(m.operator.takeIf { it.isNotEmpty() }, m.net, m.band.takeIf { it.isNotEmpty() }?.let { "B$it" }, m.cell.takeIf { it.isNotEmpty() }?.let { "соты $it" })
                    .joinToString(" · "),
                color = C.Text2, style = Num.copy(fontSize = 14.sp),
            )
        }

        val quota = Ops.quotas.value[i]
        var editQuota by remember { mutableStateOf(false) }
        SectionTitle("Трафик")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    quota?.let { gb(it.leftGb) } ?: "—",
                    color = if (quota != null && quota.leftGb < 5) C.Red else C.Text,
                    style = Num.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                )
                if (quota != null) {
                    val per = quota.perDayGb()
                    val toRenew = quota.daysToRenew()
                    Text(
                        listOfNotNull(
                            per?.let { "%.1f ГБ в день".format(Locale("ru"), it) },
                            toRenew?.let { "+${quota.packGb} ГБ через ${plural(it, "день", "дня", "дней")}" },
                        ).joinToString(" · ").ifEmpty { "осталось" },
                        color = C.Text3, style = Num.copy(fontSize = 13.sp),
                    )
                    if (per != null && per > 0 && toRenew != null && quota.leftGb / per < toRenew) {
                        Text("Пакета хватит примерно на ${plural((quota.leftGb / per).toInt(), "день", "дня", "дней")}", color = C.Amber, style = Num.copy(fontSize = 13.sp))
                    }
                } else {
                    Text("Укажите, сколько осталось по пакету", color = C.Text3, style = Num.copy(fontSize = 13.sp))
                }
            }
            Pill(if (quota == null) "Указать" else "Изменить", busy = false) { editQuota = true }
        }
        if (editQuota) QuotaDialog(i, quota) { editQuota = false }

        if (i !in Ops.ussdOff.value) {
        SectionTitle("Баланс")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    bal?.rub?.let { rub(it) } ?: "—",
                    color = if (bal?.rub != null && bal.rub < 100) C.Red else C.Text,
                    style = Num.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                )
                if (bal != null) Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(bal.time)), color = C.Text3, style = Num.copy(fontSize = 13.sp))
            }
            Pill(if ("bal$i" in busy) "Запрос…" else "Обновить", busy = "bal$i" in busy) { Ops.run(ctx, "bal$i") { Ops.balance(it, i) } }
        }
        if (bal != null && bal.rub == null && bal.text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(bal.text, color = C.Text2, style = MaterialTheme.typography.bodyMedium)
        }
        }

        SectionTitle("Диапазон LTE")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BAND_PRESETS.forEach { (label, mask) ->
                val sel = band != null && bandsLabel(band) == bandsLabel(mask)
                Text(
                    label, color = if (sel) C.Bg else C.Text, fontFamily = Onest, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) C.Text else C.Raised)
                        .clickable(enabled = !sel && "setb$i" !in busy) { confirmBand = label to mask }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (band != null && BAND_PRESETS.none { bandsLabel(it.second) == bandsLabel(band) }) {
            Text("Сейчас: ${bandsLabel(band)}", color = C.Text3, style = Num.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp))
        }

        SectionTitle("СМС")
        when {
            sms == null -> if ("sms$i" in busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.Text2, strokeWidth = 2.dp)
            sms.isEmpty() -> Text("Нет сообщений", color = C.Text2, style = MaterialTheme.typography.bodyMedium)
            else -> sms.take(10).forEach { x ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (x.unread) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(C.Blue))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("${x.phone} · ${x.date.take(16)}", color = C.Text3, style = Num.copy(fontSize = 12.sp))
                    }
                    Text(x.text, color = C.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Переподключить", busy = false) { MonitorService.start(ctx, MonitorService.ACTION_RECONNECT, i) }
            Pill("Перезагрузить", busy = false, onClick = onReboot)
        }
    }

    confirmBand?.let { (label, mask) ->
        AlertDialog(
            onDismissRequest = { confirmBand = null },
            containerColor = C.Raised,
            title = { Text("Диапазон $label?") },
            text = { Text("Модем переподключится, связь пропадёт на полминуты.", color = C.Text2) },
            confirmButton = {
                TextButton(onClick = { Ops.run(ctx, "setb$i") { Ops.setBands(it, i, mask) }; confirmBand = null }) { Text("Включить") }
            },
            dismissButton = { TextButton(onClick = { confirmBand = null }) { Text("Отмена", color = C.Text2) } },
        )
    }
}

@Composable
private fun QuotaDialog(i: Int, q: Quota?, close: () -> Unit) {
    val ctx = LocalContext.current
    var left by remember { mutableStateOf(q?.let { "%.1f".format(Locale.US, it.leftGb).removeSuffix(".0") } ?: "") }
    var pack by remember { mutableStateOf((q?.packGb ?: 100).toString()) }
    var day by remember { mutableStateOf(q?.day?.takeIf { it > 0 }?.toString() ?: "") }
    var roll by remember { mutableStateOf(q?.rollover ?: false) }
    AlertDialog(
        onDismissRequest = close,
        containerColor = C.Raised,
        title = { Text("Пакет трафика") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(left, { left = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } }, label = { Text("Осталось сейчас, ГБ") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pack, { pack = it.filter(Char::isDigit).take(4) }, label = { Text("Пакет, ГБ") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("День оплаты") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Остаток переносится", color = C.Text, fontFamily = Onest, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(roll, { roll = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Bg))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = left.toDoubleOrNull()
                if (l != null) Ops.setQuota(ctx, i, l, pack.toIntOrNull() ?: 100, (day.toIntOrNull() ?: 0).coerceIn(0, 31), roll)
                close()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Отмена", color = C.Text2) } },
    )
}

// ---------------------------------------------------------------- traffic analytics

private val SIM_COLORS = listOf(C.Blue, C.Violet)

private fun monthName(): String = SimpleDateFormat("LLLL", Locale("ru")).format(Date())

@Composable
private fun TrafficCard(v: StatsView, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface).clickable(onClick = onOpen).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Трафик", color = C.Text, fontFamily = Onest, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("сегодня " + fmtBytes(v.days.last().total), color = C.Text2, style = Num.copy(fontSize = 14.sp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(v.monthTotal), color = C.Text, style = Num.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                Text("за ${monthName()} · прогноз ${fmtBytes(v.forecast)}", color = C.Text3, style = Num.copy(fontSize = 13.sp))
            }
            Bars30(v.days.takeLast(14), Modifier.width(96.dp).height(36.dp))
        }
    }
}

/** Daily bars stacked by SIM; today is the last bar. */
@Composable
private fun Bars30(days: List<DayRow>, modifier: Modifier) {
    Canvas(modifier) {
        val max = (days.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1L).toFloat()
        val slot = size.width / days.size
        val w = slot * 0.66f
        days.forEachIndexed { i, d ->
            var y = size.height
            d.modems.forEachIndexed { m, b ->
                val h = b / max * size.height
                if (h > 0f) {
                    drawRoundRect(SIM_COLORS[m], topLeft = Offset(i * slot + (slot - w) / 2, y - h), size = Size(w, h), cornerRadius = CornerRadius(2f, 2f))
                    y -= h
                }
            }
            if (d.total == 0L) {
                drawRoundRect(C.Line, topLeft = Offset(i * slot + (slot - w) / 2, size.height - 2f), size = Size(w, 2f))
            }
        }
    }
}

@Composable
private fun AnalyticsSheet(v: StatsView, cfg: List<ModemConfig>) {
    val quotas = Ops.quotas.value
    var allDevices by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Text("Трафик", color = C.Text, fontFamily = Onest, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(v.monthTotal), color = C.Text, style = Num.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold))
                Text("за ${monthName()}", color = C.Text3, style = Num.copy(fontSize = 13.sp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("≈ " + fmtBytes(v.forecast), color = C.Text2, style = Num.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium))
                Text("к концу месяца", color = C.Text3, style = Num.copy(fontSize = 13.sp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Bars30(v.days, Modifier.fillMaxWidth().height(110.dp))
        Spacer(Modifier.height(6.dp))
        Row {
            Text(v.days.first().date.substring(8) + "." + v.days.first().date.substring(5, 7), color = C.Text3, style = Num.copy(fontSize = 11.sp), modifier = Modifier.weight(1f))
            Text("сегодня", color = C.Text3, style = Num.copy(fontSize = 11.sp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            cfg.forEachIndexed { i, c ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(SIM_COLORS[i]))
                Spacer(Modifier.width(6.dp))
                Text(c.name, color = C.Text2, fontFamily = Onest, fontSize = 13.sp)
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("${fmtBytes(v.perDay)} в день", color = C.Text2, style = Num.copy(fontSize = 13.sp))
        }

        SectionTitle("SIM")
        cfg.forEachIndexed { i, c ->
            val q = quotas[i]
            val per = v.perDayByModem.getOrNull(i) ?: 0L
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(SIM_COLORS[i]))
                    Spacer(Modifier.width(8.dp))
                    Text(c.name, color = C.Text, fontFamily = Onest, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(fmtBytes(v.monthByModem.getOrNull(i) ?: 0L), color = C.Text, style = Num.copy(fontSize = 16.sp))
                }
                val line = buildList {
                    add("${fmtBytes(per)} в день")
                    if (q != null) {
                        add("осталось ${gb(q.leftGb)}")
                        val dr = q.daysToRenew()
                        if (dr != null) {
                            val atRenew = q.leftGb - per / 1e9 * dr
                            add(if (atRenew >= 0) "к оплате останется ${gb(atRenew)}" else "не хватит ${gb(-atRenew)}")
                        } else if (per > 0) {
                            add("хватит на ${plural((q.leftGb / (per / 1e9)).toInt(), "день", "дня", "дней")}")
                        }
                    }
                }
                val short = q != null && q.daysToRenew()?.let { q.leftGb - per / 1e9 * it < 0 } == true
                Text(line.joinToString(" · "), color = if (short) C.Amber else C.Text3, style = Num.copy(fontSize = 13.sp), modifier = Modifier.padding(start = 16.dp))
            }
        }

        if (v.devices.isNotEmpty()) {
            SectionTitle("Устройства за ${monthName()}")
            val total = v.devices.sumOf { it.month }.coerceAtLeast(1)
            val list = if (allDevices) v.devices else v.devices.take(10)
            list.forEach { d ->
                Column(Modifier.padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, color = C.Text, fontFamily = Onest, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(fmtBytes(d.month), color = C.Text, style = Num.copy(fontSize = 15.sp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Line)) {
                            Box(Modifier.fillMaxWidth((d.month.toFloat() / total).coerceIn(0.01f, 1f)).height(4.dp).background(C.Blue))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(if (d.today > 0) "сегодня ${fmtBytes(d.today)}" else "${d.month * 100 / total}%", color = C.Text3, style = Num.copy(fontSize = 12.sp))
                    }
                }
            }
            if (v.devices.size > 10) {
                Text(
                    if (allDevices) "Свернуть" else "Ещё ${v.devices.size - 10}",
                    color = C.Text3, fontFamily = Onest, fontSize = 14.sp,
                    modifier = Modifier.clickable { allDevices = !allDevices }.padding(vertical = 10.dp),
                )
            }
        } else {
            SectionTitle("Устройства")
            Text("Появятся, когда роутер начнёт отдавать трафик устройств", color = C.Text2, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
