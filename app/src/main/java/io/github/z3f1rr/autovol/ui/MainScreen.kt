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
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.z3f1rr.autovol.Texts
import io.github.z3f1rr.autovol.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
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

/** Stateless body, also rendered by the screenshot tests. Must fit one phone screen without scrolling. */
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Oos.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val viewport = maxHeight
        Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            AdaptiveColumn(
                viewport = viewport,
                gap = 8.dp,
                before = {
                    Header(enabled, running, status, onToggle, onOpenMore)
                    // Root users always see the hint until it is fixed; others can dismiss it.
                    if (!live.autoStart && (live.rootManager != null || !bannerDismissed)) AccessBanner(live, refresh)
                    (update as? Updater.State.Available)?.let { UpdateBanner(it.release, onOpenMore) }
                },
                hero = { extra -> HeroCard(enabled, running, status, live, pauseUntil, onToggle, extra) },
                after = {
                    VolumeCard(live)
                    CallsCard(live, onRequestPhone)
                },
            )
        }
    }
}

/**
 * Fits the screen: measures everything at its compact size and gives the height left on a tall
 * screen to the hero card ([hero] gets that extra height). On short screens nothing grows and the
 * column simply scrolls.
 */
@Composable
private fun AdaptiveColumn(
    viewport: Dp,
    gap: Dp,
    before: @Composable () -> Unit,
    hero: @Composable (extra: Dp) -> Unit,
    after: @Composable () -> Unit,
) {
    SubcomposeLayout { c ->
        val loose = c.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val top = subcompose("before", before).map { it.measure(loose) }
        val bottom = subcompose("after", after).map { it.measure(loose) }
        val probe = subcompose("probe") { hero(0.dp) }.map { it.measure(loose) }
        val gapPx = gap.roundToPx()
        val count = top.size + bottom.size + 1
        val used = top.sumOf { it.height } + bottom.sumOf { it.height } + probe.sumOf { it.height } +
            gapPx * (count - 1) + gapPx // bottom margin
        val extraPx = (viewport.roundToPx() - used).coerceAtLeast(0)
        // The hero gets a fixed height (compact + spare) and spreads its content over it.
        val heroHeight = probe.sumOf { it.height } + extraPx
        val heroPlaced = subcompose("hero") { hero(extraPx.toDp()) }.map {
            it.measure(if (extraPx > 0) c.copy(minHeight = heroHeight, maxHeight = heroHeight) else loose)
        }
        val all = top + heroPlaced + bottom
        val height = all.sumOf { it.height } + gapPx * all.size
        layout(c.maxWidth, height) {
            var y = 0
            all.forEach {
                it.placeRelative(0, y)
                y += it.height + gapPx
            }
        }
    }
}

@Composable
private fun UpdateBanner(rel: Updater.Release, onOpenMore: () -> Unit) {
    CompactCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.update_available, rel.version), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenMore) { Text(stringResource(R.string.btn_update)) }
        }
    }
}

/** Title, status line, the main switch and the "More" button in one row. */
@Composable
private fun Header(enabled: Boolean, running: Boolean, st: Status, onToggle: (Boolean) -> Unit, onOpenMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("AutoVol", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    !enabled -> stringResource(R.string.status_off)
                    !running -> stringResource(R.string.status_stopped)
                    st.timeMs > 0 -> stringResource(R.string.status_running_at, hm(st.timeMs))
                    else -> stringResource(R.string.status_running)
                },
                color = Oos.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
        IconButton(onClick = onOpenMore) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.more_cd))
        }
    }
}

/** Card with tighter padding for the dense main screen. */
@Composable
private fun CompactCard(modifier: Modifier = Modifier, color: Color = Oos.Card, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { content() }
}

/**
 * One-line hint while AutoVol cannot start by itself after reboot. Root controls appear only when a root
 * manager is installed; others get the adb hint and can dismiss it.
 */
@Composable
private fun AccessBanner(live: Live, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var showAdb by remember { mutableStateOf(false) }
    val root = live.rootManager
    CompactCard(color = Oos.WarningBg) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hint_title), style = MaterialTheme.typography.bodyLarge, color = Oos.Warning)
                    Text(
                        when {
                            live.mic == MicAccess.Level.NONE -> stringResource(R.string.hint_no_mic)
                            root != null -> stringResource(R.string.hint_root, root)
                            else -> stringResource(R.string.hint_basic)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    root != null -> FilledTonalButton(onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Root.grantAll(ctx) }
                            busy = false
                            result = r.output
                            refresh()
                        }
                    }) { Text(stringResource(R.string.btn_root)) }
                    else -> {
                        TextButton(onClick = { showAdb = !showAdb }) { Text(stringResource(R.string.btn_adb)) }
                        TextButton(onClick = { AutoVol.prefs.accessHintDismissed.value = true }) { Text(stringResource(R.string.btn_hide)) }
                    }
                }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (showAdb) {
                val cmd = MicAccess.adbCommand(ctx)
                Text(
                    stringResource(R.string.adb_note),
                    color = Oos.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer { Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                FilledTonalButton(onClick = { copyToClipboard(ctx, cmd) }) { Text(stringResource(R.string.btn_copy)) }
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
    extra: Dp = 0.dp,
) {
    // 0 on a compact screen, 1 with 200+ dp to spare: the number grows a little, the chart takes the rest.
    val f = (extra / 200.dp).coerceIn(0f, 1f)
    val dbSize = lerp(36.sp, 56.sp, f)
    val showChart = extra >= 72.dp
    CompactCard(Modifier.fillMaxHeight()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = if (extra > 0.dp && !showChart) Arrangement.SpaceEvenly else Arrangement.spacedBy(6.dp),
        ) {
            if (enabled && !running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.service_stopped), color = Oos.Warning, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { onToggle(true) }) { Text(stringResource(R.string.btn_resume)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    st.lastDb?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—",
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = dbSize, lineHeight = dbSize * 1.1f),
                )
                Text(" " + stringResource(R.string.unit_dba), color = Oos.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (st.step != null) stringResource(R.string.step_of, st.step, st.steps) else stringResource(R.string.step_none),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    st.pct?.let { Text("$it%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            StepBar(st.step, st.steps, lerp(5.dp, 8.dp, f))
            if (showChart) {
                val nowSec = System.currentTimeMillis() / 1000
                // re-read the history whenever a new cycle result arrives
                val samples = remember(st.timeMs) { AutoVol.engine.history.since(nowSec - CHART_HOURS * 3600L) }
                val thresholds = remember(st.timeMs) { AutoVol.engine.state.levels.drop(1).map { it.db } }
                LevelChart(samples, thresholds, nowSec, CHART_HOURS, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
            }
            val reason = Texts.reason(LocalContext.current, st)
            if (reason != null) {
                Text(
                    reason,
                    color = if (st.paused) Oos.Warning else Oos.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                VolumeChip(stringResource(R.string.chip_ring), live.ring, live.ringMax, Modifier.weight(1f))
                VolumeChip(stringResource(R.string.chip_notif), live.notif, live.notifMax, Modifier.weight(1f))
                VolumeChip(stringResource(R.string.chip_media), live.media, live.mediaMax, Modifier.weight(1f))
                if (enabled) {
                    FilledTonalIconButton(onClick = { AutoVolService.instance?.requestCycle(diag = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.check_now))
                    }
                }
            }
            if (pauseUntil > System.currentTimeMillis()) {
                TextButton(onClick = {
                    AutoVol.prefs.pauseUntilMs = 0
                    AutoVolService.instance?.requestCycle()
                }) { Text(stringResource(R.string.btn_unpause)) }
            }
        }
    }
}

private const val CHART_HOURS = 6

@Composable
private fun StepBar(step: Int?, steps: Int, thickness: Dp = 5.dp) {
    val n = steps.coerceAtLeast(1)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0..n) {
            val on = step != null && i <= step
            Box(
                Modifier
                    .weight(1f)
                    .height(thickness)
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
            .clip(RoundedCornerShape(14.dp))
            .background(Oos.CardHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text("$v/$max", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun sensText(v: Int) = if (v == 0) stringResource(R.string.sens_normal) else if (v > 0) "+$v" else "$v"

/** Label, slider and value in a single row. */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    ticks: Boolean,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            colors = if (ticks) SliderDefaults.colors() else noTicks(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            valueText,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(88.dp),
        )
    }
}

/** -3..+3 sensitivity in one row; [onDone] stores the value. */
@Composable
private fun SensitivityRow(label: String, value: Int, onDone: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val max = EngineSettings.SENS_MAX.toFloat()
    SliderRow(label, sensText(v.toInt()), v, -max..max, 2 * EngineSettings.SENS_MAX - 1, ticks = true,
        onChange = { v = Math.round(it).toFloat() }, onDone = { onDone(v.toInt()) })
}

@Composable
private fun VolumeCard(live: Live) {
    val minVol by AutoVol.prefs.minVolFlow.collectAsStateWithLifecycle()
    val ringSens by AutoVol.prefs.ringSensFlow.collectAsStateWithLifecycle()
    val mediaOn by AutoVol.prefs.mediaEnabledFlow.collectAsStateWithLifecycle()
    val mediaSens by AutoVol.prefs.mediaSensFlow.collectAsStateWithLifecycle()
    var ringOpen by rememberSaveable { mutableStateOf(false) }
    val max = live.ringMax.coerceAtLeast(2)
    var value by remember(minVol) { mutableFloatStateOf(minVol.coerceIn(1, max).toFloat()) }
    CompactCard {
        Column {
            SliderRow(stringResource(R.string.quiet_volume), stringResource(R.string.of_max, value.toInt(), max), value, 1f..max.toFloat(), (max - 2).coerceAtLeast(0), ticks = false,
                onChange = { value = it },
                onDone = {
                    AutoVol.prefs.minVol = value.toInt()
                    AutoVolService.instance?.requestCycle()
                })
            // Ringer sensitivity is rarely changed: behind a tap.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ringOpen = !ringOpen }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.sensitivity), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(sensText(ringSens) + if (ringOpen) "  ▴" else "  ▾", color = MaterialTheme.colorScheme.primary)
            }
            if (ringOpen) {
                SensitivityRow(stringResource(R.string.sens_ring), ringSens) {
                    AutoVol.prefs.ringSens = it
                    AutoVolService.instance?.requestCycle()
                }
            }
            HorizontalDivider(color = Oos.Divider, modifier = Modifier.padding(vertical = 6.dp))
            CompactSwitchRow(stringResource(R.string.media_auto), stringResource(R.string.media_auto_sub), mediaOn) {
                AutoVol.prefs.mediaEnabled = it
                AutoVolService.instance?.requestCycle()
            }
            if (mediaOn) {
                SensitivityRow(stringResource(R.string.sens_media), mediaSens) {
                    AutoVol.prefs.mediaSens = it
                    AutoVolService.instance?.requestCycle()
                }
            }
        }
    }
}

@Composable
private fun CompactSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CallsCard(live: Live, onRequestPhone: (sameNumber: Boolean) -> Unit) {
    val s by AutoVol.prefs.repeatFlow.collectAsStateWithLifecycle()
    var window by remember(s.windowMin) { mutableFloatStateOf(s.windowMin.toFloat()) }
    val same = s.mode == RepeatMode.SAME_NUMBER
    CompactCard {
        Column {
            CompactSwitchRow(stringResource(R.string.repeat_title), stringResource(R.string.repeat_sub), s.enabled) {
                AutoVol.prefs.repeat = s.copy(enabled = it)
                if (it) onRequestPhone(same)
            }
            if (s.enabled) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    SegmentedButton(
                        selected = same,
                        onClick = {
                            AutoVol.prefs.repeat = s.copy(mode = RepeatMode.SAME_NUMBER)
                            onRequestPhone(true)
                        },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.repeat_same)) }
                    SegmentedButton(
                        selected = !same,
                        onClick = { AutoVol.prefs.repeat = s.copy(mode = RepeatMode.ANY_NUMBER) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.repeat_any)) }
                }
                SliderRow(stringResource(R.string.repeat_within), stringResource(R.string.minutes, window.toInt()), window, 1f..30f, 28, ticks = false,
                    onChange = { window = Math.round(it).toFloat() },
                    onDone = { AutoVol.prefs.repeat = s.copy(windowMin = window.toInt()) })
                val missing = when {
                    !live.phoneState -> stringResource(R.string.perm_phone_missing)
                    same && !live.callLog -> stringResource(R.string.perm_calllog_missing)
                    else -> null
                }
                if (missing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(missing, color = Oos.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRequestPhone(same) }) { Text(stringResource(R.string.btn_grant)) }
                    }
                }
            }
        }
    }
}
