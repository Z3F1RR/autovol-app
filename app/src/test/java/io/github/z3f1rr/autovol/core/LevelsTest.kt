package io.github.z3f1rr.autovol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelsTest {
    @Test
    fun parseSortsAndFallsBack() {
        assertEquals(listOf(Level(-120.0, 0), Level(-30.0, 100)), Levels.parse("-30:100 -120:0"))
        assertEquals(Levels.parse(Levels.DEFAULT), Levels.parse("garbage"))
        assertEquals(Levels.DEFAULT, Levels.format(Levels.parse(Levels.DEFAULT)))
        assertEquals(150.coerceIn(0, 100), Levels.parse("-10:150")[0].pct)
    }

    @Test
    fun stepOf() {
        val lv = Levels.parse(Levels.DEFAULT)
        assertEquals(0, Levels.stepOf(-50.0, lv))
        assertEquals(1, Levels.stepOf(-46.0, lv))
        assertEquals(3, Levels.stepOf(-30.0, lv))
        assertEquals(4, Levels.stepOf(-5.0, lv))
    }

    @Test
    fun targetVolMatchesPython() {
        // python: [target_vol(p, 0, 15, 1) for p in (0, 25, 50, 75, 100)] == [1, 5, 8, 11, 15]
        // (14 * 0.75 = 10.5 rounds half to even -> 10)
        assertEquals(listOf(1, 5, 8, 11, 15), listOf(0, 25, 50, 75, 100).map { Levels.targetVol(it, 0, 15, 1) })
        // (16-1)*50/100 = 7.5 -> 8; (7-1)*25/100 = 1.5 -> 2
        assertEquals(9, Levels.targetVol(50, 0, 16, 1))
        assertEquals(3, Levels.targetVol(25, 0, 7, 1))
        // (11-1)*25/100 = 2.5 -> 2 (Python rounds half to even)
        assertEquals(3, Levels.targetVol(25, 0, 11, 1))
        // min volume never 0 and never above max
        assertEquals(1, Levels.targetVol(0, 0, 15, 0))
        assertEquals(15, Levels.targetVol(0, 0, 15, 40))
        assertEquals(2, Levels.targetVol(0, 2, 15, 1))
    }

    @Test
    fun autoLevelsNeedsEnoughSamplesAndClampsSpan() {
        val lv = Levels.parse(Levels.DEFAULT)
        val s = Settings()
        assertNull(Levels.autoLevels(List(29) { -60.0 }, lv, s).second)
        // flat history: span clamped up to 24
        val (flat, cal) = Levels.autoLevels(List(300) { -60.0 }, lv, s)
        assertEquals(CalInfo(-60.0, -36.0, 300), cal)
        assertEquals(listOf(-120.0, -52.0, -46.7, -41.3, -36.0), flat.map { it.db })
        assertEquals(lv.map { it.pct }, flat.map { it.pct })
        // very wide history: span clamped down to 40
        val wide = List(150) { -80.0 } + List(150) { -10.0 }
        assertEquals(-40.0, Levels.autoLevels(wide, lv, s).second!!.topDb, 0.0)
        assertNull(Levels.autoLevels(List(300) { -60.0 }, lv, s.copy(autoCal = false)).second)
    }

    @Test
    fun calibrationBlendsInGraduallyAndRunsContinuously() {
        val lv = Levels.parse(Levels.DEFAULT) // -46 -39 -32 -26
        val s = Settings()
        // 150 of 300 samples: halfway between manual and automatic thresholds (-52 -46.7 -41.3 -36)
        val (half, cal) = Levels.autoLevels(List(150) { -60.0 }, lv, s)
        assertEquals(0.5, cal!!.weight, 1e-9)
        assertEquals(false, cal.complete)
        assertEquals(listOf(-120.0, -49.0, -42.8, -36.7, -31.0), half.map { it.db })
        // later history moves the thresholds again: e.g. the room turned out quieter
        val later = Levels.autoLevels(List(300) { -60.0 } + List(300) { -75.0 }, lv, s)
        assertEquals(-75.0, later.second!!.floorDb, 0.0)
        assertEquals(true, later.second!!.complete)
    }

    @Test
    fun sensitivityShiftsThresholds() {
        val lv = Levels.parse(Levels.DEFAULT)
        assertEquals(listOf(-120.0, -52.0, -45.0, -38.0, -32.0), Levels.withSensitivity(lv, 2).map { it.db })
        assertEquals(listOf(-120.0, -37.0, -30.0, -23.0, -17.0), Levels.withSensitivity(lv, -9).map { it.db })
        assertEquals(lv, Levels.withSensitivity(lv, 0))
    }

    @Test
    fun historyPrunesOldSamples() {
        val store = MemoryHistoryStore()
        val h = History(store)
        val day = 86_400_000L
        h.add(-50.0, 0, 7)
        h.add(-51.04, 3 * day, 7)
        h.add(-52.0, 8 * day, 7)
        assertEquals(listOf(-51.0, -52.0), store.saved.map { it.db })
    }
}
