package io.github.z3f1rr.autovol.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** OxygenOS 16 dark look: pure black background, flat dark-grey cards, system accent colour. */
object Oos {
    val Black = Color(0xFF000000)
    val Card = Color(0xFF151515)
    val CardHigh = Color(0xFF1F1F1F)
    val Divider = Color(0xFF262626)
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF8E8E93)
    val Warning = Color(0xFFFF9F0A)
    val WarningBg = Color(0xFF2A1F0D)
    val Error = Color(0xFFFF453A)
    val Ok = Color(0xFF32D74B)
    private val FallbackAccent = Color(0xFF3D8BFF)

    fun accentFallback() = FallbackAccent
}

@Composable
fun AutoVolTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Always dark: the accent follows the system (OxygenOS / Material You), everything else is black.
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(ctx)
    } else {
        darkColorScheme(primary = Oos.accentFallback(), onPrimary = Color.White)
    }
    val scheme = base.copy(
        background = Oos.Black,
        onBackground = Oos.TextPrimary,
        surface = Oos.Black,
        onSurface = Oos.TextPrimary,
        surfaceVariant = Oos.CardHigh,
        onSurfaceVariant = Oos.TextSecondary,
        surfaceContainerLowest = Oos.Black,
        surfaceContainerLow = Oos.Card,
        surfaceContainer = Oos.Card,
        surfaceContainerHigh = Oos.CardHigh,
        surfaceContainerHighest = Oos.CardHigh,
        outlineVariant = Oos.Divider,
        error = Oos.Error,
    )
    val t = Typography()
    val typography = t.copy(
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light, fontSize = 56.sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Provides the light content colour for text drawn directly on the black background.
        Surface(color = Oos.Black, contentColor = Oos.TextPrimary, content = content)
    }
}
