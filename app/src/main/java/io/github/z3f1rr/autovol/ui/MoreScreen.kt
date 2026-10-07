package io.github.z3f1rr.autovol.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
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
            Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineLarge)
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
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
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
            Text(stringResource(R.string.accent), style = MaterialTheme.typography.titleMedium)
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
            }
        }
    }
}

@Composable
private fun CalibrationSection(st: Status) {
    var confirmReset by remember { mutableStateOf(false) }
    OosCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pct = (st.calWeight * 100).toInt()
            Row {
                Text(stringResource(R.string.cal_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                    Text(stringResource(R.string.upd_version, Updater.currentVersion(ctx)), style = MaterialTheme.typography.titleMedium)
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
