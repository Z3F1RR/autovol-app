package io.github.z3f1rr.autovol.ui

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3f1rr.autovol.AutoVol

/** OxygenOS-like surfaces for the current light/dark/black mode. */
data class OosColors(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val cardHigh: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val warning: Color,
    val warningBg: Color,
    val ok: Color,
    val error: Color,
) {
    companion object {
        fun dark(black: Boolean) = OosColors(
            dark = true,
            background = if (black) Color(0xFF000000) else Color(0xFF121212),
            card = if (black) Color(0xFF151515) else Color(0xFF1E1E1E),
            cardHigh = if (black) Color(0xFF1F1F1F) else Color(0xFF2A2A2A),
            divider = Color(0xFF2C2C2C),
            textPrimary = Color(0xFFF2F2F2),
            textSecondary = Color(0xFF8E8E93),
            warning = Color(0xFFFF9F0A),
            warningBg = Color(0xFF2A1F0D),
            ok = Color(0xFF32D74B),
            error = Color(0xFFFF453A),
        )

        fun light() = OosColors(
            dark = false,
            background = Color(0xFFF2F2F7),
            card = Color(0xFFFFFFFF),
            cardHigh = Color(0xFFEDEDF0),
            divider = Color(0xFFE2E2E6),
            textPrimary = Color(0xFF111111),
            textSecondary = Color(0xFF6C6C70),
            warning = Color(0xFFB25B00),
            warningBg = Color(0xFFFFF1DC),
            ok = Color(0xFF248A3D),
            error = Color(0xFFD70015),
        )
    }
}

val LocalOosColors = staticCompositionLocalOf { OosColors.dark(true) }

/** Shorthand used by the screens: `Oos.Card`, `Oos.TextSecondary`… */
object Oos {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.background
    val Card: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.card
    val CardHigh: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.cardHigh
    val Divider: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.divider
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.textSecondary
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.warning
    val WarningBg: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.warningBg
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.ok
    val Error: Color @Composable @ReadOnlyComposable get() = LocalOosColors.current.error
}

private val FallbackAccent = Color(0xFF3D8BFF)

/**
 * The accent the user picked in the system settings. OxygenOS (and most OEM skins) apply it to the
 * device-default theme's colorAccent; Material You devices expose it the same way.
 */
fun systemAccent(ctx: Context, dark: Boolean): Color? {
    val theme = if (dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light
    val tv = TypedValue()
    if (!ContextThemeWrapper(ctx, theme).theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) return null
    return when {
        tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> Color(tv.data)
        tv.resourceId != 0 -> try {
            Color(ctx.getColor(tv.resourceId))
        } catch (e: android.content.res.Resources.NotFoundException) {
            null
        }
        else -> null
    }
}

/** Material You primary as the fallback system accent (Android 12+). */
private fun wallpaperAccent(ctx: Context, dark: Boolean): Color =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).primary
    } else {
        FallbackAccent
    }

/** Swatches for a manual accent: OnePlus red, warm, green, blue, violet, pink and neutral. */
val AccentSwatches = listOf(
    0xFFEB0028, 0xFFFF6A00, 0xFFFFB300, 0xFF34C759, 0xFF00BFA5,
    0xFF3D8BFF, 0xFF5E5CE6, 0xFFAF52DE, 0xFFFF2D7A, 0xFFB0B0B0,
).map { it.toInt() }

/** Keeps the accent readable: no near-black accent on black, no near-white accent on white. */
fun readableAccent(accent: Color, dark: Boolean): Color = when {
    dark && accent.luminance() < 0.12f -> lerp(accent, Color.White, 0.45f)
    !dark && accent.luminance() > 0.55f -> lerp(accent, Color.Black, 0.45f)
    else -> accent
}

private fun schemeFromAccent(accent: Color, c: OosColors): ColorScheme {
    val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
    val container = accent.copy(alpha = if (c.dark) 0.28f else 0.16f).compositeOver(c.card)
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = c.textPrimary,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = c.textPrimary,
        tertiary = accent,
        inversePrimary = accent,
        surfaceTint = accent,
    )
}

/** The effective dark flag for the chosen theme mode. */
@Composable
fun isAppInDarkTheme(): Boolean {
    val mode by AutoVol.prefs.themeMode.flow.collectAsStateWithLifecycle()
    return when (mode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun AutoVolTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isAppInDarkTheme()
    val black by AutoVol.prefs.pureBlack.flow.collectAsStateWithLifecycle()
    val accentSource by AutoVol.prefs.accentSource.flow.collectAsStateWithLifecycle()
    val customAccent by AutoVol.prefs.accentColor.flow.collectAsStateWithLifecycle()
    val c = if (dark) OosColors.dark(black) else OosColors.light()
    val accent = if (accentSource == "CUSTOM") Color(customAccent) else systemAccent(ctx, dark) ?: wallpaperAccent(ctx, dark)
    val base = schemeFromAccent(readableAccent(accent, dark), c)
    val scheme = base.copy(
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.background,
        onSurface = c.textPrimary,
        surfaceVariant = c.cardHigh,
        onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.card,
        surfaceContainer = c.card,
        surfaceContainerHigh = c.cardHigh,
        surfaceContainerHighest = c.cardHigh,
        outlineVariant = c.divider,
        error = c.error,
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        CompositionLocalProvider(LocalOosColors provides c) {
            Surface(color = c.background, contentColor = c.textPrimary, content = content)
        }
    }
}

/**
 * One small type scale for the whole app, so rows look the same everywhere: headlineMedium = screen
 * title, bodyLarge = every row title and value, bodySmall = every secondary line, labelMedium = section
 * labels. Tabular figures keep numbers from shifting the layout when they change.
 */
private val AppTypography: Typography = Typography().let { t ->
    fun TextStyle.tab() = copy(fontFeatureSettings = "tnum")
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light).tab(),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light).tab(),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Light).tab(),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold).tab(),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold).tab(),
        headlineSmall = t.headlineSmall.tab(),
        titleLarge = t.titleLarge.tab(),
        titleMedium = t.titleMedium.tab(),
        titleSmall = t.titleSmall.tab(),
        bodyLarge = t.bodyLarge.tab(),
        bodyMedium = t.bodyMedium.tab(),
        bodySmall = t.bodySmall.tab(),
        labelLarge = t.labelLarge.tab(),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp).tab(),
        labelSmall = t.labelSmall.tab(),
    )
}

