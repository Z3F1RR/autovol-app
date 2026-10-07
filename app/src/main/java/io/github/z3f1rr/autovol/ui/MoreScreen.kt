package io.github.z3f1rr.autovol.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.AutoVolService
import io.github.z3f1rr.autovol.R
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.Root
import io.github.z3f1rr.autovol.Status
import io.github.z3f1rr.autovol.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything that is not needed every day: appearance, calibration, system state, updates, log. */
@Composable
fun MoreScreen(onBack: () -> Unit, onOpenLog: () -> Unit) {
    val ctx = LocalContext.current
    var live by remember { mutableStateOf(readLive(ctx)) }
    MoreContent(live, onBack, onOpenLog, refresh = { live = readLive(ctx) })
}

@Composable
fun MoreContent(live: Live, onBack: () -> Unit, onOpenLog: () -> Unit, refresh: () -> Unit = {}) {
    val status by AutoVol.status.collectAsStateWithLifecycle()
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
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back)) }
            Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineMedium)
        }
        SectionLabel(stringResource(R.string.sec_appearance))
        AppearanceSection()
        SectionLabel(stringResource(R.string.sec_calibration))
        CalibrationSection(status)
        SectionLabel(stringResource(R.string.sec_system))
        SystemSection(live, refresh)
        SectionLabel(stringResource(R.string.sec_updates))
        UpdatesSection()
        SectionLabel(stringResource(R.string.sec_log))
        OosCard { NavRow(stringResource(R.string.log_title), stringResource(R.string.log_sub), onOpenLog) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppearanceSection() {
    val prefs = AutoVol.prefs
    val mode by prefs.themeMode.flow.collectAsStateWithLifecycle()
    val black by prefs.pureBlack.flow.collectAsStateWithLifecycle()
    val accent by prefs.accentSource.flow.collectAsStateWithLifecycle()
    val custom by prefs.accentColor.flow.collectAsStateWithLifecycle()
    OosCard {
        Column {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf(
                    "SYSTEM" to stringResource(R.string.theme_system),
                    "LIGHT" to stringResource(R.string.theme_light),
                    "DARK" to stringResource(R.string.theme_dark),
                ).forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = mode == key,
                        onClick = { prefs.themeMode.value = key },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(label) }
                }
            }
            // Black only matters where a dark theme can appear.
            if (mode != "LIGHT") {
                Spacer(Modifier.height(8.dp))
                SwitchRow(stringResource(R.string.black_bg), stringResource(R.string.black_bg_sub), black) { prefs.pureBlack.value = it }
            }
            RowDivider()
            Text(stringResource(R.string.accent), style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SegmentedButton(
                    selected = accent == "SYSTEM",
                    onClick = { prefs.accentSource.value = "SYSTEM" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.accent_system)) }
                SegmentedButton(
                    selected = accent == "CUSTOM",
                    onClick = { prefs.accentSource.value = "CUSTOM" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.accent_custom)) }
            }
            if (accent == "CUSTOM") {
                AccentSwatches.chunked(5).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEach { argb ->
                            val label = stringResource(R.string.accent_swatch, AccentSwatches.indexOf(argb) + 1)
                            val selected = argb == custom
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(if (selected) 3.dp else 0.dp, Oos.TextPrimary, CircleShape)
                                    .clickable(onClickLabel = label) { prefs.accentColor.value = argb }
                                    .semantics { contentDescription = label; this.selected = selected },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ColorEditor(custom) { prefs.accentColor.value = it }
            }
        }
    }
}

/** Any accent colour: hex code or hue / saturation / brightness sliders, kept in sync. */
@Composable
private fun ColorEditor(argb: Int, onChange: (Int) -> Unit) {
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var h by remember(argb) { mutableFloatStateOf(hsv[0]) }
    var s by remember(argb) { mutableFloatStateOf(hsv[1]) }
    var v by remember(argb) { mutableFloatStateOf(hsv[2]) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    var hex by remember(argb) { mutableStateOf(toHex(argb)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(current)))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hex,
                onValueChange = { input ->
                    hex = input.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == '#' }.take(7)
                    parseHex(hex)?.let(onChange)
                },
                label = { Text(stringResource(R.string.accent_hex)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
        }
        val done = { onChange(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }
        ColorSlider(stringResource(R.string.accent_hue), h, 0f..360f, { h = it; hex = toHex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }, done)
        ColorSlider(stringResource(R.string.accent_sat), s, 0f..1f, { s = it; hex = toHex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }, done)
        ColorSlider(stringResource(R.string.accent_val), v, 0f..1f, { v = it; hex = toHex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }, done)
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(112.dp))
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, modifier = Modifier.weight(1f))
    }
}

private fun toHex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

/** "#RRGGBB" or "RRGGBB" → opaque ARGB; null while incomplete. */
internal fun parseHex(s: String): Int? {
    val d = s.removePrefix("#")
    if (d.length != 6) return null
    return d.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}

@Composable
private fun CalibrationSection(st: Status) {
    var confirmReset by remember { mutableStateOf(false) }
    OosCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pct = (st.calWeight * 100).toInt()
            Row {
                Text(stringResource(R.string.cal_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(if (pct >= 100) stringResource(R.string.cal_ready) else "$pct%", color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { st.calWeight.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                trackColor = Oos.CardHigh,
            )
            Text(
                stringResource(R.string.cal_desc, st.samples) +
                    if (st.calFloor != null) " " + stringResource(R.string.cal_levels, st.calFloor.toString(), st.calTop.toString()) else "",
                color = Oos.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.cal_reset)) }
            RowDivider()
            LearningRows()
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Oos.CardHigh,
            title = { Text(stringResource(R.string.cal_reset_q)) },
            text = { Text(stringResource(R.string.cal_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    AutoVol.engine.history.clear()
                    AutoVol.log.add("калибровка сброшена")
                    AutoVol.publish(AutoVol.status.value.copy(samples = 0, calFloor = null, calTop = null, calWeight = 0.0))
                }) { Text(stringResource(R.string.btn_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

/** Learning from manual ringer changes: switch, what was learned, forget. */
@Composable
private fun LearningRows() {
    val prefs = AutoVol.prefs
    val on by prefs.learnFromManual.flow.collectAsStateWithLifecycle()
    val bias by prefs.learnedBias.flow.collectAsStateWithLifecycle()
    SwitchRow(stringResource(R.string.learn_title), stringResource(R.string.learn_sub), on) {
        prefs.learnFromManual.value = it
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (bias == 0.0) {
                stringResource(R.string.learn_none)
            } else {
                stringResource(
                    R.string.learn_value,
                    (if (bias > 0) "+" else "−") + String.format(java.util.Locale.ROOT, "%.1f", kotlin.math.abs(bias)),
                    stringResource(if (bias > 0) R.string.learn_louder else R.string.learn_quieter),
                )
            },
            color = Oos.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (bias != 0.0) {
            TextButton(onClick = {
                AutoVol.engine.resetLearning()
                AutoVol.log.add("поправка по ручным изменениям сброшена")
                AutoVolService.instance?.requestCycle()
            }) { Text(stringResource(R.string.learn_reset)) }
        }
    }
}

@Composable
private fun SystemSection(live: Live, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var rootResult by remember { mutableStateOf<String?>(null) }
    OosCard {
        Column {
            StatusRow(
                stringResource(R.string.sys_autostart),
                live.autoStart,
                stringResource(if (live.rootGranted) R.string.sys_autostart_root else R.string.sys_autostart_full),
                stringResource(if (live.mic == MicAccess.Level.NONE) R.string.sys_autostart_none else R.string.sys_autostart_basic),
                null,
            ) {}
            RowDivider()
            StatusRow(
                stringResource(R.string.sys_alarms),
                live.exactAlarms,
                stringResource(R.string.sys_alarms_ok),
                stringResource(R.string.sys_alarms_bad),
                stringResource(R.string.btn_allow),
            ) { requestExactAlarms(ctx) }
            RowDivider()
            StatusRow(
                stringResource(R.string.sys_battery),
                live.batteryUnrestricted,
                stringResource(R.string.sys_battery_ok),
                stringResource(R.string.sys_battery_bad),
                stringResource(R.string.btn_change),
            ) { requestBatteryUnrestricted(ctx) }
            // Root only for those who have it: nothing about root is shown otherwise.
            if (live.rootManager != null || live.rootGranted) {
                RowDivider()
                StatusRow(
                    stringResource(R.string.sys_root),
                    live.rootGranted,
                    stringResource(R.string.sys_root_ok, live.rootManager ?: "su"),
                    stringResource(R.string.sys_root_bad, live.rootManager ?: "su"),
                    null,
                ) {}
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Root.grantAll(ctx) }
                            busy = false
                            rootResult = r.output
                            refresh()
                        }
                    }) { Text(stringResource(if (live.rootGranted) R.string.btn_root_recheck else R.string.btn_root_setup)) }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
                rootResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }
}

@Composable
private fun UpdatesSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    val auto by AutoVol.prefs.updateAutoCheck.flow.collectAsStateWithLifecycle()
    OosCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.upd_version, Updater.currentVersion(ctx)), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (val s = state) {
                            Updater.State.Idle -> stringResource(R.string.upd_idle)
                            Updater.State.Checking -> stringResource(R.string.upd_checking)
                            is Updater.State.UpToDate -> stringResource(R.string.upd_latest)
                            is Updater.State.Available -> stringResource(R.string.update_available, s.release.version)
                            is Updater.State.Downloading -> stringResource(R.string.upd_downloading, (s.progress * 100).toInt())
                            Updater.State.Installing -> stringResource(R.string.upd_installing)
                            is Updater.State.Failed -> stringResource(s.res, s.arg.orEmpty())
                        },
                        color = if (state is Updater.State.Failed) Oos.Warning else Oos.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state is Updater.State.Checking || state is Updater.State.Installing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            (state as? Updater.State.Downloading)?.let {
                LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth(), trackColor = Oos.CardHigh)
            }
            (state as? Updater.State.Available)?.let { s ->
                if (s.release.notes.isNotBlank()) {
                    Text(s.release.notes.take(500), style = MaterialTheme.typography.bodySmall, color = Oos.TextSecondary)
                }
                Button(onClick = {
                    if (!AutoVol.prefs.rootGranted && !ctx.packageManager.canRequestPackageInstalls()) {
                        // "Install unknown apps" for AutoVol must be allowed once.
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
                        )
                    } else {
                        scope.launch { Updater.downloadAndInstall(ctx, s.release) }
                    }
                }) { Text(stringResource(R.string.btn_update)) }
            }
            if (state !is Updater.State.Available && state !is Updater.State.Downloading) {
                FilledTonalButton(
                    enabled = state !is Updater.State.Checking && state !is Updater.State.Installing,
                    onClick = { scope.launch { Updater.check(ctx) } },
                ) { Text(stringResource(R.string.btn_check_updates)) }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.upd_auto), stringResource(R.string.upd_auto_sub), auto) {
                AutoVol.prefs.updateAutoCheck.value = it
            }
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)))
            }) { Text(stringResource(R.string.upd_all)) }
        }
    }
}
