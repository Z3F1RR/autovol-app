package io.github.z3f1rr.autovol.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.BuildConfigInfo
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.core.Levels

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lines by AutoVol.log.flow.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
                actions = { TextButton(onClick = { share(ctx) }) { Text("Поделиться") } },
            )
        },
    ) { pad ->
        SelectionContainer(Modifier.padding(pad)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), reverseLayout = true) {
                items(lines.asReversed()) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun share(ctx: Context) {
    val s = AutoVol.prefs.settings()
    val st = AutoVol.engine.state
    val header = buildString {
        appendLine("AutoVol ${BuildConfigInfo.versionName(ctx)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("микрофон: ${MicAccess.level(ctx).name.lowercase()} · мин. громкость ${s.minVol}")
        appendLine("пороги сейчас: ${Levels.format(st.levels.ifEmpty { s.levels })}")
        appendLine()
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Журнал AutoVol")
        .putExtra(Intent.EXTRA_TEXT, header + AutoVol.log.text())
    ctx.startActivity(Intent.createChooser(send, "Отправить журнал"))
}
