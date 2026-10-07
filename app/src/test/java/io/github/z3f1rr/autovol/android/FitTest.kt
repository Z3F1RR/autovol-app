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
 * The main screen must fit a common phone (393x851 dp minus 60 dp of system bars = 393x791,
 * smaller than OnePlus 15) without scrolling, even in the busiest state: root hint, media and
 * repeat-call options open. On taller screens the hero card takes the spare height.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h791dp-xxhdpi")
class FitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+ru")
    fun fitsInRussian() = mainScreenFitsWithoutScrolling("ru", "В течение")

    @Test
    @Config(qualifiers = "+en")
    fun fitsInEnglish() = mainScreenFitsWithoutScrolling("en", "Within")

    private fun mainScreenFitsWithoutScrolling(lang: String, lastLabel: String) {
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
            System.getProperty("roborazzi.outputDir", "build/outputs/roborazzi") + "/main_fit_393x791_$lang.png",
        )
        val density = compose.onRoot().fetchSemanticsNode().layoutInfo.density.density
        // unclipped position of the last row (the repeat-call window slider)
        val node = compose.onNodeWithText(lastLabel).fetchSemanticsNode()
        val bottomDp = (node.positionInRoot.y + node.size.height) / density + 12 // + card padding
        assertTrue("content ends at $bottomDp dp", bottomDp <= 791)
    }

    /** Tall phone (≈ OnePlus 15), little content: the hero grows so no big empty band is left. */
    @Test
    @Config(qualifiers = "ru-w448dp-h995dp-xxhdpi")
    fun tallScreenIsFilled() {
        AutoVol.prefs.enabled = true
        AutoVol.setServiceRunning(true)
        AutoVol.prefs.mediaEnabled = false
        AutoVol.prefs.repeat = RepeatSettings(enabled = false)
        // six hours of history for the chart: quiet with a noisy hour in the middle
        val now = System.currentTimeMillis()
        for (i in 0 until 72) {
            val db = -66.0 + 4 * kotlin.math.sin(i / 5.0) + if (i in 30..42) 22.0 else 0.0
            AutoVol.engine.history.add(db, now - (72 - i) * 300_000L, 7)
        }
        AutoVol.engine.state.levels = io.github.z3f1rr.autovol.core.Levels.parse(io.github.z3f1rr.autovol.core.Levels.DEFAULT)
        AutoVol.publish(Status(timeMs = now, outcome = "APPLIED", lastDb = -58.0, step = 1, steps = 4, pct = 25))
        val live = Live(ring = 5, ringMax = 16, notif = 5, notifMax = 16, media = 100, mediaMax = 160,
            mic = MicAccess.Level.FULL, phoneState = true, callLog = true)
        compose.setContent { AutoVolTheme { MainContent(live, {}, {}, {}) } }
        compose.onRoot().captureRoboImage(
            System.getProperty("roborazzi.outputDir", "build/outputs/roborazzi") + "/main_tall_448x995_ru.png",
        )
        val density = compose.onRoot().fetchSemanticsNode().layoutInfo.density.density
        val node = compose.onNodeWithText("Повторный звонок на максимум").fetchSemanticsNode()
        val bottomDp = (node.positionInRoot.y + node.size.height) / density
        // the last card ends close to the bottom (only its padding + the bottom gap remain)
        assertTrue("content ends at $bottomDp dp of 995", bottomDp > 995 - 70 && bottomDp <= 995)
    }
}
