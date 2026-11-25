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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/** Status tones: container background and the text/accent colour on it. */
private data class Tone(val bg: Color, val fg: Color)

private val GoodTone = Tone(Color(0xFF123B2A), Color(0xFF6EE7A8))
private val WarnTone = Tone(Color(0xFF3B3012), Color(0xFFF6C453))
private val BadTone = Tone(Color(0xFF451A1A), Color(0xFFFF8A80))
private val RsrpLine = Color(0xFF7CB7FF)
private val SinrLine = Color(0xFFF6C453)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MonitorService.createChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (getSharedPreferences("pdm", MODE_PRIVATE).getBoolean("auto", true)) MonitorService.start(this)
        setContent {
            val ctx = LocalContext.current
            val scheme: ColorScheme =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(ctx)
                else darkColorScheme(primary = Color(0xFF7CB7FF))
            MaterialTheme(colorScheme = scheme) { Screen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
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
        modifier = Modifier.fillMaxSize().background(cs.background),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Вагон", style = MaterialTheme.typography.titleLarge, color = cs.onBackground, modifier = Modifier.weight(1f))
                IconButton(onClick = { settings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = cs.onSurfaceVariant)
                }
            }

            Hero(snap, running, now)

            if (msg.isNotEmpty()) {
                Text(
                    msg, color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(cs.secondaryContainer).padding(14.dp),
                )
            }

            if (running) {
                cfg.forEachIndexed { i, c ->
                    ModemCard(
                        c, snap?.modems?.getOrNull(i), hist.getOrNull(i).orEmpty(),
                        onReconnect = { MonitorService.start(ctx, MonitorService.ACTION_RECONNECT, i) },
                        onReboot = { reboot = i },
                        onWeb = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${c.ip}"))) },
                    )
                }
            }

            BatteryHint()

            val age = snap?.let { ((now - it.time) / 1000).coerceAtLeast(0) }
            Text(
                when {
                    !running -> ""
                    age == null -> "Проверяю…"
                    age < 5 -> "Обновлено только что"
                    else -> "Обновлено $age с назад"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (age != null && age > 60) BadTone.fg else cs.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }

    reboot?.let { i ->
        AlertDialog(
            onDismissRequest = { reboot = null },
            title = { Text("Перезагрузить ${cfg[i].name}?") },
            text = { Text("Канал пропадёт примерно на минуту.") },
            confirmButton = {
                TextButton(onClick = { MonitorService.start(ctx, MonitorService.ACTION_REBOOT, i); reboot = null }) {
                    Text("Перезагрузить")
                }
            },
            dismissButton = { TextButton(onClick = { reboot = null }) { Text("Отмена") } },
        )
    }

    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }) {
            SettingsSheet(running) { settings = false }
        }
    }
}

@Composable
private fun Hero(s: Snapshot?, running: Boolean, now: Long) {
    val cs = MaterialTheme.colorScheme
    val neutral = Tone(cs.surfaceContainerHigh, cs.onSurface)
    val mins = s?.let { (now - it.since) / 60_000 } ?: 0
    val (tone, title, sub) = when {
        !running -> Triple(neutral, "Мониторинг выключен", "Включите в настройках")
        s == null -> Triple(neutral, "Проверяю…", "")
        s.net == Net.NO_WIFI -> Triple(neutral, "Нет Wi-Fi вагона", "Подключитесь к роутеру")
        s.net == Net.FULL -> Triple(GoodTone, "Интернет есть", "${s.latencyMs} мс")
        s.net == Net.WHITELIST -> Triple(WarnTone, "Белые списки", "Работают только разрешённые сайты" + dur(mins))
        else -> Triple(BadTone, "Нет интернета", if (mins > 0) "Уже ${fmtMin(mins)}" else "")
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(tone.bg).padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tone.fg))
            Spacer(Modifier.width(10.dp))
            Text(title, color = tone.fg, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp)
        }
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(sub, color = tone.fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun fmtMin(m: Long) = if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
private fun dur(m: Long) = if (m > 0) " · ${fmtMin(m)}" else ""

@Composable
private fun ModemCard(
    c: ModemConfig, m: ModemState?, hist: List<Point>,
    onReconnect: () -> Unit, onReboot: () -> Unit, onWeb: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val live = m != null && m.ok && m.connected
    val tone = when {
        !live -> BadTone
        m!!.quality >= 3 -> GoodTone
        m.quality == 2 -> WarnTone
        else -> BadTone
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(
                    when {
                        m == null -> "Ожидание"
                        !m.ok -> "Не отвечает"
                        !m.connected -> "Нет сети"
                        else -> listOf(m.net, m.band.takeIf { it.isNotEmpty() }?.let { "B$it" }, m.qualityWord)
                            .filter { !it.isNullOrEmpty() }.joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (live) tone.fg else cs.onSurfaceVariant,
                )
            }
            Bars(if (live) m!!.quality else 0, tone.fg, cs.surfaceContainerHighest)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Действия", tint = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Переподключить") }, onClick = { menu = false; onReconnect() })
                    DropdownMenuItem(text = { Text("Перезагрузить модем") }, onClick = { menu = false; onReboot() })
                    DropdownMenuItem(text = { Text("Открыть веб-интерфейс") }, onClick = { menu = false; onWeb() })
                }
            }
        }
        if (live) {
            Spacer(Modifier.height(16.dp))
            Row {
                Value(m!!.rsrp, "RSRP, дБм", RsrpLine, Modifier.weight(1f))
                Value(m.sinr, "SINR, дБ", SinrLine, Modifier.weight(1f))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("↓ %.1f".format(Locale.US, m.dlMbps), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text("↑ %.1f Мбит/с".format(Locale.US, m.ulMbps), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (hist.size > 2) {
                Spacer(Modifier.height(14.dp))
                Spark(hist, cs.outlineVariant)
            }
        }
    }
}

@Composable
private fun Value(v: Int?, label: String, dot: Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(v?.toString() ?: "—", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
            Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Bars(q: Int, on: Color, off: Color) {
    Canvas(Modifier.size(width = 30.dp, height = 22.dp)) {
        val w = size.width / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            drawRoundRect(
                color = if (i < q) on else off,
                topLeft = Offset(i * w + w * 0.15f, size.height - h),
                size = Size(w * 0.7f, h),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

@Composable
private fun Spark(h: List<Point>, grid: Color) {
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
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
            drawPath(p, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        line({ it.rsrp }, -125f, -65f, RsrpLine)
        line({ it.sinr }, -10f, 30f, SinrLine)
    }
}

@Composable
private fun BatteryHint() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    tick.hashCode()
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Разрешите работу в фоне, иначе Android будет усыплять проверку",
            color = cs.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        FilledTonalButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Разрешить") }
    }
}

@Composable
private fun SettingsSheet(running: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cfg = remember { Monitor.config(ctx) }
    var n0 by remember { mutableStateOf(cfg[0].name) }
    var i0 by remember { mutableStateOf(cfg[0].ip) }
    var n1 by remember { mutableStateOf(cfg[1].name) }
    var i1 by remember { mutableStateOf(cfg[1].ip) }
    var interval by remember { mutableStateOf(Monitor.intervalSec(ctx).toString()) }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мониторинг", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = running, onCheckedChange = { on ->
                ctx.getSharedPreferences("pdm", 0).edit().putBoolean("auto", on).apply()
                if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
            })
        }
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
            label = { Text("Проверка, раз в N секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            Monitor.save(ctx, listOf(ModemConfig(n0, i0), ModemConfig(n1, i1)), interval.toIntOrNull() ?: 10)
            Monitor.message.value = "Сохранено"
            if (running) MonitorService.start(ctx)
            close()
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
    }
}
