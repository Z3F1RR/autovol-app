package io.github.z3f1rr.autovol.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.AutoVolService
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.Root
import io.github.z3f1rr.autovol.Scheduler
import io.github.z3f1rr.autovol.Status
import io.github.z3f1rr.autovol.Updater
import io.github.z3f1rr.autovol.core.RepeatMode
import io.github.z3f1rr.autovol.core.Settings as EngineSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Values read from the system; refreshed every couple of seconds while the screen is visible. */
data class Live(
    val ring: Int = 0,
    val ringMax: Int = 15,
    val notif: Int = 0,
    val notifMax: Int = 15,
    val media: Int = 0,
    val mediaMax: Int = 15,
    val mic: MicAccess.Level = MicAccess.Level.NONE,
    val exactAlarms: Boolean = true,
    val batteryUnrestricted: Boolean = true,
    val phoneState: Boolean = false,
    val callLog: Boolean = false,
    val rootManager: String? = null,
    val rootGranted: Boolean = false,
) {
    /** Starts by itself after reboot: background mic via appops, or service started by root. */
    val autoStart: Boolean get() = mic == MicAccess.Level.FULL || rootGranted
}

fun readLive(ctx: Context): Live {
    val am = ctx.getSystemService(AudioManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    return Live(
        ring = am.getStreamVolume(AudioManager.STREAM_RING),
        ringMax = am.getStreamMaxVolume(AudioManager.STREAM_RING),
        notif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        notifMax = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
        media = am.getStreamVolume(AudioManager.STREAM_MUSIC),
        mediaMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        mic = MicAccess.level(ctx),
        exactAlarms = Scheduler.canExact(ctx),
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
        phoneState = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
        callLog = ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED,
        rootManager = Root.managerName(ctx),
        rootGranted = AutoVol.prefs.rootGranted,
    )
}

private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

@Composable
fun MainScreen(onToggle: (Boolean) -> Unit, onOpenMore: () -> Unit, onRequestPhone: (sameNumber: Boolean) -> Unit) {
    val ctx = LocalContext.current
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
    LaunchedEffect(Unit) { Updater.autoCheck(ctx) }
    MainContent(live, onToggle, onOpenMore, onRequestPhone, refresh = { live = readLive(ctx) })
}

/** Stateless body, also rendered by the screenshot tests. */
@Composable
fun MainContent(
    live: Live,
    onToggle: (Boolean) -> Unit,
    onOpenMore: () -> Unit,
    onRequestPhone: (sameNumber: Boolean) -> Unit,
    refresh: () -> Unit = {},
) {
    val enabled by AutoVol.prefs.enabledFlow.collectAsStateWithLifecycle()
    val running by AutoVol.serviceRunning.collectAsStateWithLifecycle()
    val status by AutoVol.status.collectAsStateWithLifecycle()
    val pauseUntil by AutoVol.prefs.pauseUntilFlow.collectAsStateWithLifecycle()
    val bannerDismissed by AutoVol.prefs.accessHintDismissed.flow.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Oos.Background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(enabled, running, status, onOpenMore)
        // Root users always see the hint until it is fixed; others can dismiss it (nothing to do without a PC).
        if (!live.autoStart && (live.rootManager != null || !bannerDismissed)) AccessBanner(live, refresh)
        (update as? Updater.State.Available)?.let { UpdateBanner(it.release, onOpenMore) }
        HeroCard(enabled, running, status, live, pauseUntil, onToggle)
        SectionLabel("Громкость")
        VolumeSection(live)
        SectionLabel("Звонки")
        RepeatCallSection(live, onRequestPhone)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UpdateBanner(rel: Updater.Release, onOpenMore: () -> Unit) {
    OosCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Доступна версия ${rel.version}", style = MaterialTheme.typography.titleMedium)
                Text("Обновление в разделе «Ещё»", color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onOpenMore) { Text("Открыть") }
        }
    }
}

@Composable
private fun Header(enabled: Boolean, running: Boolean, st: Status, onOpenMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("AutoVol", style = MaterialTheme.typography.headlineLarge)
            Text(
                when {
                    !enabled -> "Выключено"
                    !running -> "Остановлено"
                    st.timeMs > 0 -> "Работает · проверка в ${hm(st.timeMs)}"
                    else -> "Работает"
                },
                color = Oos.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onOpenMore) {
            Icon(Icons.Filled.Settings, contentDescription = "Ещё: калибровка, система, обновления, журнал")
        }
    }
}

/**
 * Top-of-screen check while AutoVol cannot start by itself after reboot. Root controls appear only
 * when a root manager is installed; others get the adb hint and can dismiss it.
 */
@Composable
private fun AccessBanner(live: Live, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var showAdb by remember { mutableStateOf(false) }
    val root = live.rootManager
    OosCard(color = Oos.WarningBg) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Нет автозапуска после перезагрузки", style = MaterialTheme.typography.titleMedium, color = Oos.Warning)
            Text(
                if (live.mic == MicAccess.Level.NONE) {
                    "Нет разрешения на микрофон — включите автогромкость, чтобы его выдать."
                } else {
                    "Сейчас всё работает, но после перезагрузки AutoVol придётся запускать касанием уведомления."
                },
                color = Oos.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (root != null) {
                Text(
                    "Обнаружен $root. Разрешите AutoVol в $root → Суперпользователь, затем нажмите кнопку.",
                    color = Oos.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (root != null) {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Root.grantAll(ctx) }
                            busy = false
                            result = r.output
                            refresh()
                        }
                    }) { Text("Настроить через root") }
                }
                TextButton(onClick = { showAdb = !showAdb }) { Text(if (showAdb) "Скрыть adb" else "Через adb") }
                if (root == null) {
                    TextButton(onClick = { AutoVol.prefs.accessHintDismissed.value = true }) { Text("Понятно") }
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Oos.TextPrimary) }
            if (showAdb) {
                val cmd = MicAccess.adbCommand(ctx)
                Text(
                    "С компьютера (Android 11+ может сбрасывать это после перезагрузки):",
                    color = Oos.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer { Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Oos.TextPrimary) }
                FilledTonalButton(onClick = { copyToClipboard(ctx, cmd) }) { Text("Копировать") }
            }
        }
    }
}

@Composable
private fun HeroCard(
    enabled: Boolean,
    running: Boolean,
    st: Status,
    live: Live,
    pauseUntil: Long,
    onToggle: (Boolean) -> Unit,
) {
    OosCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Автогромкость", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled && !running) {
                FilledTonalButton(onClick = { onToggle(true) }) { Text("Возобновить") }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    st.lastDb?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                )
                Text(" дБА", color = Oos.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(
                        if (st.step != null) "ступень ${st.step} из ${st.steps}" else "ступень —",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    st.pct?.let { Text("$it%", color = MaterialTheme.colorScheme.primary) }
                }
            }
            StepBar(st.step, st.steps)
            if (st.timeMs > 0) {
                Text(
                    st.note,
                    color = if (st.paused) Oos.Warning else Oos.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VolumeChip("Звонок", live.ring, live.ringMax, Modifier.weight(1f))
                VolumeChip("Уведомл.", live.notif, live.notifMax, Modifier.weight(1f))
                VolumeChip("Медиа", live.media, live.mediaMax, Modifier.weight(1f))
            }
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { AutoVolService.instance?.requestCycle(diag = true) }) {
                        Text("Проверить сейчас")
                    }
                    if (pauseUntil > System.currentTimeMillis()) {
                        TextButton(onClick = {
                            AutoVol.prefs.pauseUntilMs = 0
                            AutoVolService.instance?.requestCycle()
                        }) { Text("Снять паузу") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBar(step: Int?, steps: Int) {
    val n = steps.coerceAtLeast(1)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0..n) {
            val on = step != null && i <= step
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Oos.CardHigh),
            )
        }
    }
}

@Composable
private fun VolumeChip(label: String, v: Int, max: Int, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Oos.CardHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text("$v/$max", style = MaterialTheme.typography.titleMedium)
    }
}

/** Long integer sliders: hide the tick dots, the value is shown as text. */
@Composable
private fun SensitivitySlider(title: String, subtitle: String, value: Int, enabled: Boolean = true, onDone: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val max = EngineSettings.SENS_MAX
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val iv = v.toInt()
            Text(
                if (iv == 0) "обычная" else (if (iv > 0) "+$iv" else "$iv"),
                color = if (enabled) MaterialTheme.colorScheme.primary else Oos.TextSecondary,
            )
        }
        Text(subtitle, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = v,
            onValueChange = { v = Math.round(it).toFloat() },
            onValueChangeFinished = { onDone(v.toInt()) },
            valueRange = -max.toFloat()..max.toFloat(),
            steps = 2 * max - 1,
            enabled = enabled,
        )
        Row {
            Text("тише", color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("громче", color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VolumeSection(live: Live) {
    val minVol by AutoVol.prefs.minVolFlow.collectAsStateWithLifecycle()
    val ringSens by AutoVol.prefs.ringSensFlow.collectAsStateWithLifecycle()
    val mediaOn by AutoVol.prefs.mediaEnabledFlow.collectAsStateWithLifecycle()
    val mediaSens by AutoVol.prefs.mediaSensFlow.collectAsStateWithLifecycle()
    val max = live.ringMax.coerceAtLeast(2)
    var value by remember(minVol) { mutableFloatStateOf(minVol.coerceIn(1, max).toFloat()) }
    OosCard {
        Column {
            Row {
                Text("Минимальная громкость", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${value.toInt()} из $max", color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Звонок и уведомления в тишине. Никогда не 0 — иначе включится вибро.",
                color = Oos.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
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
                colors = noTicks(),
            )
            RowDivider()
            SensitivitySlider(
                "Чувствительность: звонок и уведомления",
                "Выше — громкость растёт уже при небольшом шуме (каждое деление ≈ 3 дБ).",
                ringSens,
            ) {
                AutoVol.prefs.ringSens = it
                AutoVolService.instance?.requestCycle()
            }
            RowDivider()
            SwitchRow(
                "Мультимедиа автоматически",
                "Громкость медиа следует за шумом. Если медиа выключено (0) или выкручено на максимум вами — не трогается.",
                mediaOn,
            ) {
                AutoVol.prefs.mediaEnabled = it
                AutoVolService.instance?.requestCycle()
            }
            Spacer(Modifier.height(12.dp))
            SensitivitySlider(
                "Чувствительность: мультимедиа",
                "Сдвиг медиа относительно звонка, в ступенях.",
                mediaSens,
                enabled = mediaOn,
            ) {
                AutoVol.prefs.mediaSens = it
                AutoVolService.instance?.requestCycle()
            }
        }
    }
}

@Composable
private fun RepeatCallSection(live: Live, onRequestPhone: (sameNumber: Boolean) -> Unit) {
    val s by AutoVol.prefs.repeatFlow.collectAsStateWithLifecycle()
    var window by remember(s.windowMin) { mutableFloatStateOf(s.windowMin.toFloat()) }
    val same = s.mode == RepeatMode.SAME_NUMBER
    OosCard {
        Column {
            SwitchRow(
                "Повторный звонок — на максимум",
                "Звонят снова после пропущенного — звонок звучит на полной громкости. В вибро не срабатывает.",
                s.enabled,
            ) {
                AutoVol.prefs.repeat = s.copy(enabled = it)
                if (it) onRequestPhone(same)
            }
            if (s.enabled) {
                RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = same, onClick = {
                        AutoVol.prefs.repeat = s.copy(mode = RepeatMode.SAME_NUMBER)
                        onRequestPhone(true)
                    })
                    Text("С того же номера")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !same, onClick = { AutoVol.prefs.repeat = s.copy(mode = RepeatMode.ANY_NUMBER) })
                    Text("С любого номера")
                }
                RowDivider()
                Row {
                    Text("Учитывать пропущенный", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("до ${window.toInt()} мин", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Повтор срабатывает сразу, без минимальной паузы; старше этого — игнорируется.",
                    color = Oos.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = window,
                    onValueChange = { window = Math.round(it).toFloat() },
                    onValueChangeFinished = { AutoVol.prefs.repeat = s.copy(windowMin = window.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                    colors = noTicks(),
                )
                val missing = when {
                    !live.phoneState -> "Нет разрешения «Телефон» — функция не работает."
                    same && !live.callLog -> "Нет доступа к журналу вызовов — номер не виден, «с того же номера» не сработает."
                    else -> null
                }
                if (missing != null) {
                    Text(missing, color = Oos.Warning, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { onRequestPhone(same) }) { Text("Выдать разрешение") }
                }
            }
        }
    }
}


