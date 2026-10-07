package io.github.z3f1rr.autovol.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.R
import io.github.z3f1rr.autovol.Status
import io.github.z3f1rr.autovol.core.RepeatSettings
import io.github.z3f1rr.autovol.ui.AutoVolTheme
import io.github.z3f1rr.autovol.ui.Live
import io.github.z3f1rr.autovol.ui.MainContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screen and the launcher icon to build/outputs/roborazzi for visual review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h2300dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun out(name: String) = System.getProperty("roborazzi.outputDir", "build/outputs/roborazzi") + "/$name.png"

    @Test
    fun mainScreenBasicModeWithRoot() {
        AutoVol.prefs.enabled = true
        AutoVol.setServiceRunning(true)
        AutoVol.publish(
            Status(
                timeMs = 1_760_000_000_000, note = "-52.4 дБ → ступень 1 (25%) [калибровка 40%]", outcome = "APPLIED",
                lastDb = -52.4, step = 1, steps = 4, pct = 25, nextAtMs = 1_760_000_090_000, samples = 120, calWeight = 0.4,
                calFloor = -68.2, calTop = -44.2,
            ),
        )
        AutoVol.prefs.repeat = RepeatSettings(enabled = true)
        AutoVol.prefs.mediaEnabled = true
        val live = Live(ring = 5, ringMax = 15, notif = 5, notifMax = 15, media = 9, mediaMax = 30,
            mic = MicAccess.Level.BASIC, exactAlarms = true, batteryUnrestricted = false,
            phoneState = true, callLog = true, rootManager = "KernelSU")
        compose.setContent { AutoVolTheme { MainContent(live, {}, {}, {}) } }
        compose.onRoot().captureRoboImage(out("main_basic_root"))
    }

    @Test
    fun mainScreenFullAccess() {
        AutoVol.prefs.enabled = true
        AutoVol.setServiceRunning(true)
        AutoVol.publish(
            Status(
                timeMs = 1_760_000_000_000, note = "-38.0 дБ → ступень 3 (75%) изменено звонок:5→12", outcome = "APPLIED",
                lastDb = -38.0, step = 3, steps = 4, pct = 75, nextAtMs = 1_760_000_056_000, samples = 412, calWeight = 1.0,
                calFloor = -66.0, calTop = -40.0,
            ),
        )
        val live = Live(ring = 12, ringMax = 15, notif = 12, notifMax = 15, media = 22, mediaMax = 30,
            mic = MicAccess.Level.FULL, exactAlarms = true, batteryUnrestricted = true, phoneState = true, callLog = true)
        compose.setContent { AutoVolTheme { MainContent(live, {}, {}, {}) } }
        compose.onRoot().captureRoboImage(out("main_full"))
    }

    @Test
    fun launcherIcon() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val icon = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 432
        val bmp = Bitmap.createBitmap(size * 3 + 64, size + 32, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(40, 40, 40))
        // squircle (OxygenOS), circle, and the themed monochrome glyph on black
        val shapes = listOf(
            Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.3f, size * 0.3f, Path.Direction.CW) },
            Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) },
        )
        shapes.forEachIndexed { i, shape ->
            c.save()
            c.translate(16f + i * (size + 16f), 16f)
            c.clipPath(shape)
            // layers are 108dp with 18dp of bleed on each side: scale them over the clipped area
            val pad = size / 4
            icon.background.setBounds(-pad, -pad, size + pad, size + pad)
            icon.background.draw(c)
            icon.foreground.setBounds(-pad, -pad, size + pad, size + pad)
            icon.foreground.draw(c)
            c.restore()
        }
        c.save()
        c.translate(16f + 2 * (size + 16f), 16f)
        c.clipPath(shapes[1])
        c.drawColor(Color.BLACK)
        val mono = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_monochrome)!!
        mono.setTint(Color.rgb(170, 200, 255))
        mono.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
        mono.draw(c)
        c.restore()
        bmp.captureRoboImage(out("launcher_icon"))
    }
}
