package io.github.z3f1rr.autovol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatCallsTest {
    private val rc = RepeatCalls(CallsState())
    private var now = 1_700_000_000_000L
    private val same = RepeatSettings(enabled = true, mode = RepeatMode.SAME_NUMBER, windowMin = 15)
    private val any = same.copy(mode = RepeatMode.ANY_NUMBER)
    private val max = 15

    private fun min(m: Int) {
        now += m * 60_000L
    }

    /** Broadcast pair as delivered with READ_CALL_LOG: first without the number, then with it. */
    private fun ring(number: String?, s: RepeatSettings, vol: Int = 5): List<CallAction> =
        listOf(rc.onRinging(now, null, s, vol, max), rc.onRinging(now, number, s, vol, max))

    private fun missedCall(number: String?, s: RepeatSettings) {
        ring(number, s)
        min(1)
        rc.onIdle(now, s)
    }

    private fun boosts(actions: List<CallAction>) = actions.filterIsInstance<CallAction.Boost>()

    @Test
    fun firstCallDoesNothing() {
        assertTrue(boosts(ring("+79123456789", same)).isEmpty())
    }

    @Test
    fun secondCallFromSameNumberRaisesToMaxAndRestores() {
        missedCall("+7 912 345-67-89", same)
        min(3)
        val b = boosts(ring("89123456789", same))
        assertEquals(1, b.size) // only once despite two broadcasts
        assertTrue(b[0].reason, b[0].reason.startsWith("повторный звонок с …89"))
        min(1)
        assertEquals(CallAction.Restore(5), rc.onIdle(now, same))
    }

    @Test
    fun differentNumberDoesNotCountInSameNumberMode() {
        missedCall("+79123456789", same)
        min(2)
        assertTrue(boosts(ring("+79990000000", same)).isEmpty())
    }

    @Test
    fun anyNumberModeCountsDifferentNumbersAndHiddenOnes() {
        missedCall(null, any)
        min(2)
        assertEquals(1, boosts(ring("+79990000000", any)).size)
    }

    @Test
    fun windowExpires() {
        missedCall("+79123456789", same)
        min(16)
        assertTrue(boosts(ring("+79123456789", same)).isEmpty())
    }

    @Test
    fun answeredCallIsNotCountedAsMissed() {
        ring("+79123456789", same)
        rc.onOffhook()
        min(2)
        rc.onIdle(now, same)
        min(1)
        assertTrue(boosts(ring("+79123456789", same)).isEmpty())
    }

    @Test
    fun answeringBoostedCallRestoresImmediately() {
        missedCall("+79123456789", same)
        min(1)
        ring("+79123456789", same)
        assertEquals(CallAction.Restore(5), rc.onOffhook())
        assertEquals(CallAction.None, rc.onIdle(now, same))
    }

    @Test
    fun alreadyAtMaxDoesNothing() {
        missedCall("+79123456789", same)
        min(1)
        assertTrue(boosts(ring("+79123456789", same, vol = 15)).isEmpty())
        assertEquals(CallAction.None, rc.onIdle(now, same))
    }

    @Test
    fun disabledRecordsButNeverBoosts() {
        val off = same.copy(enabled = false)
        missedCall("+79123456789", off)
        min(1)
        assertTrue(boosts(ring("+79123456789", off)).isEmpty())
    }

    @Test
    fun hiddenNumberInSameNumberModeDoesNotBoost() {
        missedCall(null, same)
        min(1)
        assertTrue(boosts(ring(null, same)).isEmpty())
    }

    @Test
    fun outgoingCallIsNotMissed() {
        rc.onOffhook()
        rc.onIdle(now, any)
        min(1)
        assertTrue(boosts(ring("+79123456789", any)).isEmpty())
    }

    @Test
    fun normalizeAndMask() {
        assertEquals("9123456789", RepeatCalls.normalize("+7 (912) 345-67-89"))
        assertEquals("9123456789", RepeatCalls.normalize("89123456789"))
        assertEquals(null, RepeatCalls.normalize(""))
        assertEquals("…89", RepeatCalls.mask("9123456789"))
    }
}
