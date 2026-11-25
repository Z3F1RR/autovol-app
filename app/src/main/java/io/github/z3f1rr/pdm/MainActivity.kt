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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E1317)
private val CardBg = Color(0xFF171F26)
private val Inner = Color(0xFF0F161C)
private val Txt = Color(0xFFE8EEF3)
private val Mut = Color(0xFF8B9BA9)
private val Ok = Color(0xFF3ECF8E)
private val Mid = Color(0xFFF2B33D)
private val Bad = Color(0xFFFF6363)
private val Accent = Color(0xFF4AA8FF)

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
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
                Screen()
            }
        }
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val snap = Monitor.snapshot.value
    val running = Monitor.running.value
    val msg = Monitor.message.value
    val hist = Monitor.history.value
    val cfg = remember(snap) { Monitor.config(ctx) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // Redraw "N minutes without internet" even between polls.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { delay(6000); Monitor.message.value = "" } }

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ПДМ-статус", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val age = snap?.let { ((System.currentTimeMillis() - it.time) / 1000).toInt() }
            Text(
                when {
                    !running -> "мониторинг выключен"
                    age == null -> "опрос…"
                    else -> "обновлено $age с назад".also { tick.hashCode() }
                },
                color = if (age != null && age > 60) Bad else Mut, fontSize = 13.sp,
            )
        }

        HeroCard(snap, running, tick)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(onClick = { MonitorService.start(ctx) }) { Text("Обновить") }
                OutlinedButton(onClick = {
                    ctx.getSharedPreferences("pdm", 0).edit().putBoolean("auto", false).apply()
                    MonitorService.stop(ctx)
                }) { Text("Остановить") }
            } else {
                Button(onClick = {
                    ctx.getSharedPreferences("pdm", 0).edit().putBoolean("auto", true).apply()
                    MonitorService.start(ctx)
                }) { Text("Запустить мониторинг") }
            }
        }

        if (msg.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF223039), RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text(msg, color = Txt, fontSize = 14.sp)
            }
        }

        cfg.forEachIndexed { i, c ->
            ModemCard(c, snap?.modems?.getOrNull(i), hist.getOrNull(i).orEmpty(),
                onReconnect = { MonitorService.start(ctx, MonitorService.ACTION_RECONNECT, i) },
                onReboot = { confirm = i },
                onWeb = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${c.ip}"))) })
        }

        BatteryHint()

        OutlinedButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Скрыть настройки" else "Настройки") }
        if (showSettings) SettingsCard()

        Text(
            "RSRP: лучше −90 хорошо, −90…−105 средне, хуже −105 слабо. SINR: от 13 отлично, 0…13 средне, ниже 0 плохо. " +
                "Интернет проверяется соединением с DNS Яндекса — он доступен и при белых списках.",
            color = Mut, fontSize = 12.sp,
        )
    }

    confirm?.let { i ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Перезагрузить ${cfg[i].name}?") },
            text = { Text("Канал пропадёт примерно на минуту.") },
            confirmButton = {
                TextButton(onClick = { MonitorService.start(ctx, MonitorService.ACTION_REBOOT, i); confirm = null }) { Text("Перезагрузить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun HeroCard(s: Snapshot?, running: Boolean, tick: Int) {
    val (color, big, small) = when {
        !running -> Triple(Mut, "Мониторинг выключен", "Нажмите «Запустить мониторинг».")
        s == null -> Triple(Mut, "Проверяю…", "Первый опрос занимает несколько секунд.")
        !s.onWifi -> Triple(Mut, "Нет Wi-Fi роутера", "Подключите телефон к Wi-Fi вагона, иначе модемы не видны.")
        s.internetMs != null -> Triple(Ok, "Интернет есть", "Задержка ${s.internetMs} мс")
        else -> {
            val min = (System.currentTimeMillis() - (s.downSince ?: s.time)) / 60_000 + tick * 0
            Triple(Bad, "Интернета нет", "уже $min мин")
        }
    }
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text(big, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(small, color = Mut, fontSize = 14.sp)
    }
}

@Composable
private fun ModemCard(
    c: ModemConfig, m: ModemState?, hist: List<Point>,
    onReconnect: () -> Unit, onReboot: () -> Unit, onWeb: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(c.ip + (m?.operator?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), color = Mut, fontSize = 13.sp)
            }
            val (pc, pt) = when {
                m == null -> Mut to "ожидание"
                !m.ok -> Bad to "не отвечает"
                !m.connected -> Bad to "${m.net} · не подключён"
                else -> qColor(m.quality) to "${m.net}${if (m.band.isNotEmpty()) " B" + m.band else ""} · ${m.qualityWord}"
            }
            Text(pt, color = pc, fontSize = 13.sp)
        }
        if (m != null && m.ok) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Metric("RSRP", m.rsrp, rsrpColor(m.rsrp), Modifier.weight(1f))
                Metric("SINR", m.sinr, sinrColor(m.sinr), Modifier.weight(1f))
                Metric("RSRQ", m.rsrq, rsrqColor(m.rsrq), Modifier.weight(1f))
                Metric("RSSI", m.rssi, Txt, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("↓ %.1f Мбит/с · ↑ %.1f Мбит/с · сота %s".format(Locale.US, m.dlMbps, m.ulMbps, m.cell), color = Mut, fontSize = 13.sp)
            if (hist.size > 1) {
                Spacer(Modifier.height(6.dp))
                Text("RSRP (синий) и SINR (жёлтый) за 30 минут", color = Mut, fontSize = 12.sp)
                Spark(hist)
            }
        } else if (m != null) {
            Text("Нет ответа от ${c.ip}. Проверьте, что модем вставлен и телефон в Wi-Fi роутера.", color = Mut, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onReconnect) { Text("Переподкл.", fontSize = 13.sp) }
            OutlinedButton(onClick = onReboot, colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)) { Text("Перезагр.", fontSize = 13.sp) }
            OutlinedButton(onClick = onWeb) { Text("Веб", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Metric(label: String, v: Int?, color: Color, modifier: Modifier) {
    Column(modifier.background(Inner, RoundedCornerShape(10.dp)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v?.toString() ?: "—", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Mut, fontSize = 11.sp)
    }
}

@Composable
private fun Spark(h: List<Point>) {
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val t0 = h.first().t
        val span = (h.last().t - t0).coerceAtLeast(1)
        fun draw(sel: (Point) -> Int?, lo: Float, hi: Float, color: Color) {
            val p = Path()
            var started = false
            h.forEach { pt ->
                val v = sel(pt) ?: return@forEach
                val x = (pt.t - t0).toFloat() / span * size.width
                val y = size.height - (v.toFloat().coerceIn(lo, hi) - lo) / (hi - lo) * size.height
                if (started) p.lineTo(x, y) else { p.moveTo(x, y); started = true }
            }
            drawPath(p, color, style = Stroke(width = 4f))
        }
        drawLine(Color(0xFF25313B), Offset(0f, size.height), Offset(size.width, size.height))
        draw({ it.rsrp }, -125f, -60f, Accent)
        draw({ it.sinr }, -10f, 30f, Mid)
    }
}

@Composable
private fun BatteryHint() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    Column(Modifier.fillMaxWidth().background(Color(0xFF211D12), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("Чтобы Android не усыплял мониторинг, отключите для приложения оптимизацию батареи.", color = Txt, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
            )
        }) { Text("Отключить оптимизацию") }
    }
}

@Composable
private fun SettingsCard() {
    val ctx = LocalContext.current
    val cfg = remember { Monitor.config(ctx) }
    var n0 by remember { mutableStateOf(cfg[0].name) }
    var i0 by remember { mutableStateOf(cfg[0].ip) }
    var n1 by remember { mutableStateOf(cfg[1].name) }
    var i1 by remember { mutableStateOf(cfg[1].ip) }
    var interval by remember { mutableStateOf(Monitor.intervalSec(ctx).toString()) }
    Column(
        Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(n0, { n0 = it }, label = { Text("Модем 1: название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(i0, { i0 = it }, label = { Text("Модем 1: адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(n1, { n1 = it }, label = { Text("Модем 2: название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(i1, { i1 = it }, label = { Text("Модем 2: адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Опрос, секунд") }, singleLine = true, modifier = Modifier.width(160.dp))
        Button(onClick = {
            Monitor.save(ctx, listOf(ModemConfig(n0, i0), ModemConfig(n1, i1)), interval.toIntOrNull() ?: 10)
            Monitor.message.value = "Настройки сохранены"
            MonitorService.start(ctx)
        }) { Text("Сохранить") }
    }
}

private fun qColor(q: Int) = when (q) { 4, 3 -> Ok; 2 -> Mid; else -> Bad }
private fun rsrpColor(v: Int?) = when { v == null -> Txt; v >= -90 -> Ok; v >= -105 -> Mid; else -> Bad }
private fun sinrColor(v: Int?) = when { v == null -> Txt; v >= 13 -> Ok; v >= 0 -> Mid; else -> Bad }
private fun rsrqColor(v: Int?) = when { v == null -> Txt; v >= -10 -> Ok; v >= -15 -> Mid; else -> Bad }
