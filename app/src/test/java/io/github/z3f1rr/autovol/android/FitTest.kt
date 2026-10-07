package io.github.z3f1rr.autovol.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.Status
import io.github.z3f1rr.autovol.core.RepeatSettings
import io.github.z3f1rr.autovol.ui.AutoVolTheme
import io.github.z3f1rr.autovol.ui.Live
import io.github.z3f1rr.autovol.ui.MainContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screen must fit a common phone (393x851 dp, smaller than OnePlus 15) without scrolling,
 * even in the busiest state: root hint, media and repeat-call options open. 60 dp are reserved for the
 * status and navigation bars.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h851dp-xxhdpi")
class FitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mainScreenFitsWithoutScrolling() {
        AutoVol.prefs.enabled = true
        AutoVol.setServiceRunning(true)
        AutoVol.prefs.mediaEnabled = true
        AutoVol.prefs.repeat = RepeatSettings(enabled = true)
        AutoVol.publish(
            Status(
                timeMs = 1_760_000_000_000, note = "-61.2 дБ → ступень 1 (25%) [калибровка 40%]", outcome = "APPLIED",
                lastDb = -61.2, step = 1, steps = 4, pct = 25, samples = 120, calWeight = 0.4,
            ),
        )
        val live = Live(ring = 5, ringMax = 16, notif = 5, notifMax = 16, media = 100, mediaMax = 160,
            mic = MicAccess.Level.BASIC, phoneState = true, callLog = true, rootManager = "KernelSU Next")
        compose.setContent { AutoVolTheme { MainContent(live, {}, {}, {}) } }
        compose.onRoot().captureRoboImage(
            System.getProperty("roborazzi.outputDir", "build/outputs/roborazzi") + "/main_fit_393x851.png",
        )
        val density = compose.onRoot().fetchSemanticsNode().layoutInfo.density.density
        // unclipped position of the last row (the repeat-call window slider)
        val node = compose.onNodeWithText("Окно").fetchSemanticsNode()
        val bottomDp = (node.positionInRoot.y + node.size.height) / density + 12 // + card padding
        assertTrue("content ends at $bottomDp dp", bottomDp <= 851 - 60)
    }
}
