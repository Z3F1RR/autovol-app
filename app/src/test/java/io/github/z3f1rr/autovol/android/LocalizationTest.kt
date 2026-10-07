package io.github.z3f1rr.autovol.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.z3f1rr.autovol.Status
import io.github.z3f1rr.autovol.Texts
import io.github.z3f1rr.autovol.core.Reason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every status reason has a text in both languages; formats take their argument. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LocalizationTest {
    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    private fun all(): List<String> = Reason.entries.mapNotNull { Texts.reason(ctx, Status(reason = it.name, reasonArg = "12:30")) }

    @Test
    @Config(qualifiers = "en")
    fun english() {
        val texts = all()
        assertTrue(texts.size == Reason.entries.size - 1) // NONE has no text
        texts.forEach { assertFalse(it, it.any { c -> c in 'а'..'я' || c in 'А'..'Я' }) }
        assertTrue(Texts.reason(ctx, Status(reason = "LOW_BATTERY", reasonArg = "15"))!!.contains("15%"))
        assertNull(Texts.reason(ctx, Status(reason = "NONE")))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russian() {
        val texts = all()
        texts.forEach { assertTrue(it, it.any { c -> c in 'а'..'я' || c in 'А'..'Я' } || it.isEmpty()) }
        assertNotNull(Texts.reason(ctx, Status(reason = "USER_PAUSE", reasonArg = "12:30"))!!.contains("12:30"))
    }
}
