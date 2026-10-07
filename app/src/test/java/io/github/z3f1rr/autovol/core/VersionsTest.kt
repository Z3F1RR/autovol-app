package io.github.z3f1rr.autovol.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun releaseOrdering() {
        assertTrue(Versions.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Versions.isNewer("v1.0.0", "0.9.9"))
        assertTrue(Versions.isNewer("v0.1.10", "0.1.9"))
        assertFalse(Versions.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("v0.1.0", "0.2.0"))
    }

    @Test
    fun devBuildsAreOlderThanTheirRelease() {
        assertTrue(Versions.isNewer("v0.1.0", "0.1.0-dev.42"))
        assertTrue(Versions.isNewer("v0.1.0", "0.1.0-local"))
        assertFalse(Versions.isNewer("v0.1.0", "0.2.0-dev.1"))
        assertTrue(Versions.isNewer("0.1.0-dev.10", "0.1.0-dev.9"))
    }
}
