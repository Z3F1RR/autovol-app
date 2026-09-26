package io.github.z3f1rr.autovol.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.AutoVolService
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.Scheduler
import io.github.z3f1rr.autovol.Status
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Values read from the system; refreshed every couple of seconds while the screen is visible. */
private data class Live(
    val ring: Int = 0,
    val ringMax: Int = 0,
    val notif: Int = 0,
    val notifMax: Int = 0,
    val mic: MicAccess.Level = MicAccess.Level.NONE,
    val exactAlarms: Boolean = true,
    val batteryUnrestricted: Boolean = true,
)

private fun readLive(ctx: Context): Live {
    val am = ctx.getSystemService(AudioManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    return Live(
        ring = am.getStreamVolume(AudioManager.STREAM_RING),
        ringMax = am.getStreamMaxVolume(AudioManager.STREAM_RING),
        notif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        notifMax = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
        mic = MicAccess.level(ctx),
        exactAlarms = Scheduler.canExact(ctx),
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
    )
}

private fun hms(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onToggle: (Boolean) -> Unit, onOpenLog: () -> Unit) {
    val ctx = LocalContext.current
    val enabled by AutoVol.prefs.enabledFlow.collectAsStateWithLifecycle()
    val running by AutoVol.serviceRunning.collectAsStateWithLifecycle()
    val status by AutoVol.status.collectAsStateWithLifecycle()
    val pauseUntil by AutoVol.prefs.pauseUntilFlow.collectAsStateWithLifecycle()
    var live by remember { mutableStateOf(readLive(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                live = readLive(ctx)
                delay(2000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoVol") },
                actions = { TextButton(onClick = onOpenLog) { Text("Журнал") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToggleCard(enabled, running, onToggle)
            if (enabled) StatusCard(status, live, pauseUntil)
            MinVolumeCard(live)
            AccessCard(live)
            if (!live.exactAlarms) {
                HintCard(
                    "Точные будильники не разрешены",
                    "Проверки будут идти с опозданием. Разрешите «Будильники и напоминания».",
                    "Разрешить",
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")),
                        )
                    }
                }
            }
            if (!live.batteryUnrestricted) {
                HintCard(
                    "Оптимизация батареи включена",
                    "Система может усыплять сервис. Рекомендуется режим «Без ограничений».",
                    "Отключить",
                ) { requestBatteryUnrestricted(ctx) }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ToggleCard(enabled: Boolean, running: Boolean, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Автогромкость", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        !enabled -> "Выключена"
                        running -> "Работает"
                        else -> "Включена, но сервис остановлен"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (enabled && !running) {
                    FilledTonalButton(onClick = { onToggle(true) }) { Text("Возобновить") }
                }
            }
            Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.scale(1.4f).padding(end = 8.dp))
        }
    }
}

@Composable
private fun StatusCard(st: Status, live: Live, pauseUntil: Long) {
    var confirmReset by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Состояние", style = MaterialTheme.typography.titleMedium)
            Row {
                Text(
                    st.lastDb?.let { String.format(Locale.ROOT, "%.1f дБ", it) } ?: "— дБ",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        if (st.step != null) "ступень ${st.step} из ${st.steps} (${st.pct}%)" else "ступень —",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("звонок ${live.ring}/${live.ringMax} · уведомл. ${live.notif}/${live.notifMax}")
                }
            }
            if (st.timeMs > 0) {
                Text(
                    (if (st.paused) "Пауза: " else "") + st.note,
                    color = if (st.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Последняя проверка ${hms(st.timeMs)}, следующая ≈ ${hms(st.nextAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val s = AutoVol.prefs.settings()
            Text(
                if (st.calFloor != null) {
                    "Калибровка готова: тишина ${st.calFloor} дБ, максимум от ${st.calTop} дБ (${st.samples} замеров)"
                } else {
                    "Калибровка: ${st.samples}/${s.calMinSamples} замеров, пока ручные пороги"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AutoVolService.instance?.requestCycle() }) { Text("Проверить сейчас") }
                if (pauseUntil > System.currentTimeMillis()) {
                    OutlinedButton(onClick = {
                        AutoVol.prefs.pauseUntilMs = 0
                        AutoVolService.instance?.requestCycle()
                    }) { Text("Снять паузу") }
                }
            }
            TextButton(onClick = { confirmReset = true }) { Text("Сбросить калибровку") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить калибровку?") },
            text = { Text("История замеров будет удалена, до накопления новых используются ручные пороги.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    AutoVol.engine.history.clear()
                    AutoVol.log.add("калибровка сброшена")
                    AutoVol.publish(AutoVol.status.value.copy(samples = 0, calFloor = null, calTop = null))
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun MinVolumeCard(live: Live) {
    val minVol by AutoVol.prefs.minVolFlow.collectAsStateWithLifecycle()
    val max = live.ringMax.coerceAtLeast(2)
    var value by remember(minVol) { mutableFloatStateOf(minVol.coerceIn(1, max).toFloat()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Минимальная громкость: ${value.toInt()} из $max", style = MaterialTheme.typography.titleMedium)
            Text(
                "Громкость звонка в тишине. Никогда не 0 — иначе телефон перейдёт в вибро.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = {
                    AutoVol.prefs.minVol = value.toInt()
                    AutoVolService.instance?.requestCycle()
                },
                valueRange = 1f..max.toFloat(),
                steps = (max - 2).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun AccessCard(live: Live) {
    val ctx = LocalContext.current
    var expanded by remember { mutableIntStateOf(0) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Фоновый микрофон", style = MaterialTheme.typography.titleMedium)
            Text(
                when (live.mic) {
                    MicAccess.Level.FULL -> "Полный доступ: автогромкость сама возобновится после перезагрузки."
                    MicAccess.Level.BASIC -> "Базовый режим: после перезагрузки нужно нажать на уведомление. " +
                        "Для полного доступа выполните с компьютера команду adb:"
                    MicAccess.Level.NONE -> "Нет разрешения на микрофон — включите автогромкость, чтобы его выдать."
                },
            )
            if (live.mic == MicAccess.Level.BASIC) {
                val cmd = MicAccess.adbCommand(ctx)
                SelectionContainer {
                    Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = { copy(ctx, cmd) }) { Text("Копировать") }
            }
        }
    }
}

@Composable
private fun HintCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text)
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

@SuppressLint("BatteryLife")
private fun requestBatteryUnrestricted(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        )
    } catch (e: android.content.ActivityNotFoundException) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
