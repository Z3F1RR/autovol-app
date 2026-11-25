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
import androidx.activity.compose.setContent
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
                cfg.forEachIndexed { i, c ->
                    ModemCard(
                        c, snap?.modems?.getOrNull(i), hist.getOrNull(i).orEmpty(),
                        onReconnect = { MonitorService.start(ctx, MonitorService.ACTION_RECONNECT, i) },
                        onReboot = { reboot = i },
                    )
                }
            }

            UpsCard(now)

            BatteryHint()

            DiagRow()
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
private fun ModemCard(c: ModemConfig, m: ModemState?, hist: List<Point>, onReconnect: () -> Unit, onReboot: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val live = m != null && m.ok && m.connected
    val q = if (live) m!!.quality else 0
    val tone = when {
        !live -> C.Red
        q >= 3 -> C.Green
        q == 2 -> C.Amber
        else -> C.Red
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface).padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, color = C.Text, fontFamily = Onest, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        m == null -> "Ожидание"
                        !m.ok -> "Не отвечает"
                        !m.connected -> "Нет сети"
                        else -> listOf(m.net, m.band.takeIf { it.isNotEmpty() }?.let { "B$it" }).filterNotNull().joinToString(" · ")
                    },
                    color = if (live) C.Text2 else C.Red, style = Num.copy(fontSize = 14.sp),
                )
            }
            Bars(q, tone)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Действия", tint = C.Text3)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.Raised) {
                    DropdownMenuItem(text = { Text("Переподключить") }, onClick = { menu = false; onReconnect() })
                    DropdownMenuItem(text = { Text("Перезагрузить", color = C.Red) }, onClick = { menu = false; onReboot() })
                }
            }
        }
        if (live) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(end = 12.dp)) {
                Metric(m!!.rsrp?.toString() ?: "—", "RSRP", C.Blue, Modifier.weight(1f))
                Metric(m.sinr?.toString() ?: "—", "SINR", C.Amber, Modifier.weight(1f))
                Metric(fmtSpeed(m.dlMbps), "↓ Мбит/с", null, Modifier.weight(1f))
            }
            if (hist.size > 2) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.padding(end = 12.dp)) { Spark(hist) }
            }
        }
    }
}

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
    var n1 by remember { mutableStateOf(cfg[1].name) }
    var i1 by remember { mutableStateOf(cfg[1].ip) }
    var server by remember { mutableStateOf(Monitor.server(ctx)) }
    var interval by remember { mutableStateOf(Monitor.intervalSec(ctx).toString()) }
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
        OutlinedTextField(
            server, { server = it.trim() }, label = { Text("Сервер проверки") },
            placeholder = { Text("http://IP:порт/ключ") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Адрес из установщика на сервере. Без него — проверка по Google и Яндексу, без замера скорости") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(n0, { n0 = it }, label = { Text("Модем 1") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(i0, { i0 = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(n1, { n1 = it }, label = { Text("Модем 2") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(i1, { i1 = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            interval, { interval = it.filter(Char::isDigit).take(3) },
            label = { Text("Проверка раз в, секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                Monitor.save(ctx, listOf(ModemConfig(n0, i0), ModemConfig(n1, i1)), interval.toIntOrNull() ?: 10, server)
                Monitor.message.value = "Сохранено"
                if (running) MonitorService.start(ctx)
                close()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Text, contentColor = C.Bg),
        ) { Text("Сохранить", fontFamily = Onest, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun FlagRow(title: String, hint: String, key: String, initial: Boolean, running: Boolean) {
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
                if (running) MonitorService.start(ctx)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Bg),
        )
    }
}
