package io.github.z3f1rr.autovol.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Oos.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
    )
}

@Composable
fun OosCard(modifier: Modifier = Modifier, color: Color = Oos.Card, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) { content() }
}

@Composable
fun RowDivider() = HorizontalDivider(color = Oos.Divider, modifier = Modifier.padding(vertical = 12.dp))

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Oos.TextPrimary else Oos.TextSecondary)
            if (subtitle != null) Text(subtitle, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

@Composable
fun StatusRow(title: String, ok: Boolean, okText: String, badText: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Oos.Ok else Oos.Warning))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (ok) okText else badText, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok && action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A tappable row that opens another screen. */
@Composable
fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Oos.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = Oos.TextSecondary, style = MaterialTheme.typography.headlineLarge)
    }
}

/** Long integer sliders: hide the tick dots, the value is shown as text. */
@Composable
fun noTicks() = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

fun copyToClipboard(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AutoVol", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

@SuppressLint("BatteryLife")
fun requestBatteryUnrestricted(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: android.content.ActivityNotFoundException) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

fun requestExactAlarms(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    }
}
