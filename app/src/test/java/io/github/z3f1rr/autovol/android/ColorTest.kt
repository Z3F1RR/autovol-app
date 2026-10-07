package io.github.z3f1rr.autovol.android

import io.github.z3f1rr.autovol.ui.parseHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorTest {
    @Test
    fun hexCodes() {
        assertEquals(0xFFEB0028.toInt(), parseHex("#EB0028"))
        assertEquals(0xFF3D8BFF.toInt(), parseHex("3D8BFF"))
        assertNull(parseHex("#3D8B"))
        assertNull(parseHex("#GGGGGG"))
    }
}
