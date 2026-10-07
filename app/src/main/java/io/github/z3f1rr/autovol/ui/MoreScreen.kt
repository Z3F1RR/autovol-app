package io.github.z3f1rr.autovol.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3f1rr.autovol.AutoVol
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Ещё", style = MaterialTheme.typography.headlineLarge)
        }
        SectionLabel("Оформление")
        AppearanceSection()
        SectionLabel("Калибровка")
        CalibrationSection(status)
        SectionLabel("Система")
        SystemSection(live, refresh)
        SectionLabel("Обновления")
        UpdatesSection()
        SectionLabel("Журнал")
        OosCard { NavRow("Журнал событий", "Последние 300 событий, кнопка «Поделиться» для автора", onOpenLog) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppearanceSection() {
    val prefs = AutoVol.prefs
    val mode by prefs.themeMode.flow.collectAsStateWithLifecycle()
    val black by prefs.pureBlack.flow.collectAsStateWithLifecycle()
    val accent by prefs.accentSource.flow.collectAsStateWithLifecycle()
    OosCard {
        Column {
            Text("Тема", style = MaterialTheme.typography.titleMedium)
            RadioRow("Как в системе", mode == "SYSTEM") { prefs.themeMode.value = "SYSTEM" }
            RadioRow("Светлая", mode == "LIGHT") { prefs.themeMode.value = "LIGHT" }
            RadioRow("Тёмная", mode == "DARK") { prefs.themeMode.value = "DARK" }
            RowDivider()
            SwitchRow("Чёрный фон", "В тёмной теме — полностью чёрный (AMOLED), как в OxygenOS", black) {
                prefs.pureBlack.value = it
            }
            RowDivider()
            Text("Цвет акцента", style = MaterialTheme.typography.titleMedium)
            RadioRow("Системный (настройки → Цвета)", accent == "SYSTEM") { prefs.accentSource.value = "SYSTEM" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RadioRow("Из обоев (Material You)", accent == "WALLPAPER") { prefs.accentSource.value = "WALLPAPER" }
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
                Text("Автокалибровка", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (pct >= 100) "готова" else "$pct%", color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { st.calWeight.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                trackColor = Oos.CardHigh,
            )
            Text(
                buildString {
                    append("Идёт постоянно по последним 7 дням, учитываются и ручные проверки. ")
                    append("Замеров: ${st.samples}.")
                    if (st.calFloor != null) append(" Тишина ${st.calFloor} дБА, максимум от ${st.calTop} дБА.")
                },
                color = Oos.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { confirmReset = true }) { Text("Сбросить калибровку") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Oos.CardHigh,
            title = { Text("Сбросить калибровку?") },
            text = { Text("История замеров будет удалена, пороги начнут подстраиваться заново.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    AutoVol.engine.history.clear()
                    AutoVol.log.add("калибровка сброшена")
                    AutoVol.publish(AutoVol.status.value.copy(samples = 0, calFloor = null, calTop = null, calWeight = 0.0))
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
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
                "Автозапуск после перезагрузки",
                live.autoStart,
                if (live.rootGranted) "Через root" else "Полный доступ к микрофону",
                if (live.mic == MicAccess.Level.NONE) "Нет разрешения на микрофон" else "Базовый режим — запуск касанием уведомления",
                null,
            ) {}
            RowDivider()
            StatusRow(
                "Точные будильники",
                live.exactAlarms,
                "Разрешены — проверки идут вовремя",
                "Не разрешены — проверки с опозданием",
                "Разрешить",
            ) { requestExactAlarms(ctx) }
            RowDivider()
            StatusRow(
                "Батарея",
                live.batteryUnrestricted,
                "Без ограничений",
                "Оптимизация включена — система может усыплять сервис",
                "Отключить",
            ) { requestBatteryUnrestricted(ctx) }
            // Root only for those who have it: nothing about root is shown otherwise.
            if (live.rootManager != null || live.rootGranted) {
                RowDivider()
                StatusRow(
                    "Root",
                    live.rootGranted,
                    "Настроен (${live.rootManager ?: "su"}) — запуск после перезагрузки через root",
                    "Найден ${live.rootManager}, не настроен",
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
                    }) { Text(if (live.rootGranted) "Проверить root ещё раз" else "Настроить через root") }
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
                    Text("Версия ${Updater.currentVersion(ctx)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (val s = state) {
                            Updater.State.Idle -> "Обновления — из GitHub Releases"
                            Updater.State.Checking -> "Проверяю…"
                            is Updater.State.UpToDate -> "Установлена последняя версия"
                            is Updater.State.Available -> "Доступна версия ${s.release.version}"
                            is Updater.State.Downloading -> "Загрузка ${(s.progress * 100).toInt()}%"
                            Updater.State.Installing -> "Установка…"
                            is Updater.State.Failed -> s.message
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
                }) { Text("Обновить") }
            }
            if (state !is Updater.State.Available && state !is Updater.State.Downloading) {
                FilledTonalButton(
                    enabled = state !is Updater.State.Checking && state !is Updater.State.Installing,
                    onClick = { scope.launch { Updater.check(ctx) } },
                ) { Text("Проверить обновления") }
            }
            RowDivider()
            SwitchRow("Проверять автоматически", "Раз в сутки при открытии приложения", auto) {
                AutoVol.prefs.updateAutoCheck.value = it
            }
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)))
            }) { Text("Все версии на GitHub") }
        }
    }
}
