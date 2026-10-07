package io.github.z3f1rr.autovol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val p = FakePlatform()
    private val store = MemoryHistoryStore()
    private val engine = Engine(p, History(store))
    private val s = Settings()

    // Default LEVELS: -120:0 -46:25 -39:50 -32:75 -26:100; volume range 0..15, MIN_VOL 1.
    private fun cycle(settings: Settings = s): CycleResult = engine.cycle(settings).also { p.advanceSec(it.waitSec) }

    private fun assertAllMeasured() = assertTrue("unused measurements left", p.measurements.isEmpty())

    /** Bring the engine to a given step through ordinary cycles. */
    private fun settleAt(db: Double) {
        p.queue(db, db)
        cycle()
        p.measurements.clear()
    }

    @Test
    fun firstQuietMeasurementAppliesMinimumImmediately() {
        p.queue(-60.0)
        val r = cycle()
        assertEquals(Outcome.APPLIED, r.outcome)
        assertEquals(0, engine.state.step)
        assertEquals(1, p.ring())
        assertEquals(1, p.notif())
        assertTrue(r.note, r.note.startsWith("-60.0 дБ → ступень 0 (0%)"))
        assertTrue(r.note.contains("[ручные пороги]"))
        assertEquals(s.fast, r.waitSec) // just changed -> FAST
        assertAllMeasured()
    }

    @Test
    fun singleSpikeDoesNotRaiseVolume() {
        settleAt(-60.0)
        p.queue(-28.0, -58.0) // notification sound, then quiet again
        val r = cycle()
        assertEquals(0, engine.state.step)
        assertEquals(1, p.ring())
        assertEquals(-58.0, engine.state.lastDb!!, 0.0)
        assertTrue(p.logs.any { it.startsWith("всплеск -28.0 дБ не подтвердился") })
        assertEquals(2, p.measureCalls - 1)
        assertTrue(r.note.startsWith("-58.0 дБ → ступень 0"))
    }

    @Test
    fun confirmedNoiseRaisesToLowerOfTwoMeasurements() {
        settleAt(-60.0)
        p.queue(-25.0, -35.0) // step 4, then step 2 -> take min -> step 2 (50%)
        val r = cycle()
        assertEquals(2, engine.state.step)
        assertEquals(1 + Math.rint(14 * 0.5).toInt(), p.ring()) // 8
        assertTrue(r.note.contains("(50%)"))
        // step 2 of 4: FAST - (FAST - ELEV_INT) * 2/4 = 90 - 22.5 = 67.5 -> round half even = 68
        assertEquals(68, r.waitSec)
    }

    @Test
    fun concertThenSilenceDropsStraightToMinimum() {
        p.queue(-20.0, -21.0)
        var r = cycle()
        assertEquals(4, engine.state.step)
        assertEquals(15, p.ring())
        assertEquals(45, r.waitSec) // ELEV_INT at top step
        p.queue(-60.0, -61.0) // concert over, quiet confirmed
        r = cycle()
        assertEquals(0, engine.state.step)
        assertEquals(1, p.ring())
        assertAllMeasured()
    }

    @Test
    fun pauseBetweenSongsKeepsVolume() {
        p.queue(-20.0, -21.0)
        cycle()
        p.queue(-60.0, -24.0) // short pause between songs, music again on re-check
        val r = cycle()
        assertEquals(4, engine.state.step)
        assertEquals(15, p.ring())
        assertTrue(r.note, r.note.endsWith("[затишье не подтвердилось: -24.0 дБ]"))
    }

    @Test
    fun partialDropGoesToHigherOfTwoSteps() {
        p.queue(-20.0, -21.0)
        cycle()
        p.queue(-40.0, -34.0) // step 1 then step 2 (m = 0.5 at top) -> max = 2
        cycle()
        assertEquals(2, engine.state.step)
    }

    @Test
    fun rubberBandMarginOnFirstStep() {
        settleAt(-60.0)
        p.queue(-44.0, -44.0)
        cycle()
        assertEquals(1, engine.state.step)
        // -47.5 + 3 = -44.5 -> still step 1: no re-check, stays
        p.queue(-47.5)
        cycle()
        assertEquals(1, engine.state.step)
        assertAllMeasured()
        // -49.5 + 3 = -46.5 -> step 0: re-check, confirmed -> down
        p.queue(-49.5, -50.0)
        cycle()
        assertEquals(0, engine.state.step)
    }

    @Test
    fun rubberBandMarginShrinksOnTopStep() {
        p.queue(-20.0, -20.0)
        cycle()
        // top step (k=4, n=4): m = max(0.5, 3 * (1 - 3/3)) = 0.5; -26.4 + 0.5 = -25.9 -> stays
        p.queue(-26.4)
        cycle()
        assertEquals(4, engine.state.step)
        p.queue(-26.6, -26.6) // -26.1 -> step 3
        cycle()
        assertEquals(3, engine.state.step)
    }

    @Test
    fun manualChangeIsRespectedForOverrideMinutes() {
        settleAt(-60.0)
        p.userSets(10)
        var r = cycle()
        assertEquals(Outcome.SKIPPED, r.outcome)
        assertTrue(r.note.startsWith("громкость изменена вручную"))
        assertEquals(s.idle, r.waitSec)
        // Still within 30 minutes: untouched, no measurement
        val before = p.measureCalls
        r = cycle()
        assertTrue(r.note.startsWith("ручная громкость, жду до"))
        assertEquals(before, p.measureCalls)
        assertEquals(10, p.ring())
        p.advanceSec(30 * 60)
        p.queue(-60.0)
        r = cycle()
        assertEquals(Outcome.APPLIED, r.outcome)
        assertEquals(1, p.ring())
    }

    @Test
    fun bluetoothPausesAndResyncsWithoutFalseOverride() {
        settleAt(-60.0)
        p.external = "BT A2DP"
        val before = p.measureCalls
        var r = cycle()
        assertEquals(Outcome.PAUSED, r.outcome)
        assertEquals(s.idle, r.waitSec)
        assertEquals("пауза: звук идёт на BT A2DP", r.note)
        assertEquals(before, p.measureCalls)
        assertNull(engine.state.step)
        p.userSets(12) // changed while on BT: must not count as manual
        p.external = null
        p.queue(-60.0)
        r = cycle()
        assertEquals(Outcome.APPLIED, r.outcome)
        assertEquals(1, p.ring())
    }

    @Test
    fun carModePauses() {
        p.car = true
        assertEquals("пауза: режим автомобиля", cycle().note)
    }

    @Test
    fun callSkipsWithoutResync() {
        p.queue(-35.0, -35.0)
        cycle()
        assertEquals(2, engine.state.step)
        p.call = "вызов"
        val r = cycle()
        assertEquals(Outcome.SKIPPED, r.outcome)
        assertEquals(s.fast, r.waitSec)
        assertEquals(2, engine.state.step) // sync kept
        assertTrue(engine.state.lastSet.isNotEmpty())
    }

    @Test
    fun vibrateModePausesWithoutMicrophone() {
        p.ringer = "вибро"
        val r = cycle()
        assertEquals(Outcome.PAUSED, r.outcome)
        assertEquals("режим звонка: вибро", r.note)
        assertEquals(0, p.measureCalls)
        assertTrue(p.setCalls.isEmpty())
    }

    @Test
    fun dndPausesOnlyWhenEnabled() {
        p.dnd = true
        assertEquals("режим Не беспокоить", cycle().note)
        p.queue(-60.0)
        assertEquals(Outcome.APPLIED, cycle(s.copy(pauseOnDnd = false)).outcome)
    }

    @Test
    fun disabledAndUserPause() {
        assertEquals("выключено", cycle(s.copy(enabled = false)).note)
        val r = engine.cycle(s.copy(pauseUntilMs = p.now + 120_000))
        assertEquals(Outcome.PAUSED, r.outcome)
        assertEquals(120, r.waitSec) // wakes up when the pause ends, not after IDLE
    }

    @Test
    fun lowBatteryPausesUnlessCharging() {
        p.battery = Battery(15, false)
        assertEquals("пауза: заряд 15%", cycle().note)
        p.battery = Battery(15, true)
        p.queue(-60.0)
        assertEquals(Outcome.APPLIED, cycle().outcome)
    }

    @Test
    fun mutedMicrophoneIsNotTreatedAsQuietRoom() {
        p.queue(-25.0, -25.0)
        cycle()
        assertEquals(15, p.ring())
        p.queue(-120.0) // system gave us silence
        val r = cycle()
        assertEquals(Outcome.SKIPPED, r.outcome)
        assertEquals(s.fast, r.waitSec)
        assertTrue(r.note, r.note.contains("микрофон заглушён"))
        assertEquals(15, p.ring())
        assertEquals(4, engine.state.step)
        // history holds only the confirmed raise (raw first sample + min of both, as in the script)
        assertEquals(listOf(-25.0, -25.0), store.saved.map { it.db })
    }

    @Test
    fun playbackBeforeMeasurementSkipsIt() {
        p.playingSeq.addLast("медиа")
        val r = cycle()
        assertEquals("пропуск: телефон воспроизводит звук (медиа)", r.note)
        assertEquals(0, p.measureCalls)
    }

    @Test
    fun playbackStartedDuringMeasurementDiscardsIt() {
        settleAt(-60.0)
        p.playingSeq.addAll(listOf(null, "USAGE_NOTIFICATION"))
        p.queue(-20.0)
        val r = cycle()
        assertEquals(Outcome.SKIPPED, r.outcome)
        assertEquals("-20.0 дБ отброшен: во время замера играл звук (USAGE_NOTIFICATION)", r.note)
        assertEquals(1, p.ring())
    }

    @Test
    fun playbackDuringConfirmationBlocksRaise() {
        settleAt(-60.0)
        p.playingSeq.addAll(listOf(null, null, "медиа"))
        p.queue(-20.0)
        val r = cycle()
        assertTrue(r.note, r.note.startsWith("повышение не подтверждено (пропуск: телефон воспроизводит звук"))
        assertEquals(1, p.ring())
    }

    @Test
    fun playbackDuringDownConfirmationPostponesDrop() {
        p.queue(-20.0, -20.0)
        cycle()
        p.playingSeq.addAll(listOf(null, null, "медиа"))
        p.queue(-60.0)
        val r = cycle()
        assertEquals(4, engine.state.step)
        assertTrue(r.note, r.note.endsWith("[понижение отложено: пропуск: телефон воспроизводит звук (медиа)]"))
    }

    @Test
    fun ringerSwitchedDuringMeasurementSkipsSetting() {
        p.afterMeasure = { p.ringer = "вибро" }
        p.queue(-60.0)
        val r = cycle()
        assertEquals("режим изменился во время замера — пропуск", r.note)
        assertTrue(p.setCalls.isEmpty())
        assertNull(engine.state.step)
    }

    @Test
    fun measurementErrorSkips() {
        p.measurements.addLast(Measurement.Error("AudioRecord init"))
        val r = cycle()
        assertEquals("ошибка замера: AudioRecord init", r.note)
        assertEquals(s.fast, r.waitSec)
    }

    @Test
    fun stableQuietSwitchesToSlowInterval() {
        p.queue(-60.0)
        assertEquals(s.fast, cycle().waitSec)
        p.advanceSec(15 * 60)
        p.queue(-60.0)
        val r = cycle()
        assertEquals(s.slow, r.waitSec)
        assertTrue(r.note, !r.note.contains("изменено"))
    }

    @Test
    fun minVolumeIsRespected() {
        p.queue(-60.0)
        cycle(s.copy(minVol = 4))
        assertEquals(4, p.ring())
    }

    @Test
    fun calibrationUsedOnceEnoughSamples() {
        val t0 = p.now / 1000
        // 300 samples: quiet room around -70, some noise up to -40
        store.saved = List(300) { i -> Sample(t0 - 1000 + i, if (i % 10 == 0) -40.0 else -70.0) }
        val eng = Engine(p, History(store))
        p.queue(-60.0, -60.0)
        val r = eng.cycle(s)
        assertTrue(r.note, !r.note.contains("[ручные пороги]"))
        val cal = eng.state.cal!!
        assertEquals(-70.0, cal.floorDb, 0.0)
        assertEquals(-40.0, cal.topDb, 0.0) // span 30 within [24..40]
        // thresholds: floor+8 .. floor+30 evenly over 4 steps
        assertEquals(listOf(-120.0, -62.0, -54.7, -47.3, -40.0), eng.state.levels.map { it.db })
        assertEquals(1, eng.state.step) // -60 is above -62
    }

    @Test
    fun calibrationInProgressIsShownInNote() {
        val t0 = p.now / 1000
        store.saved = List(60) { i -> Sample(t0 - 1000 + i, -70.0) }
        val eng = Engine(p, History(store))
        p.queue(-75.0)
        val r = eng.cycle(s)
        assertTrue(r.note, r.note.contains("[калибровка 20%]"))
    }

    @Test
    fun higherRingSensitivityRaisesEarlier() {
        settleAt(-60.0)
        p.queue(-48.0, -48.0) // below -46 normally, but +1 notch moves step 1 to -49
        cycle(s.copy(ringSens = 1))
        assertEquals(1, engine.state.step)
    }

    @Test
    fun mediaFollowsRingStepWithOffset() {
        val ms = s.copy(mediaEnabled = true)
        p.queue(-35.0, -35.0) // step 2 (50%)
        val r = cycle(ms)
        assertEquals(1 + Math.rint(29 * 0.5).toInt(), p.media()) // 1 + 14 = 15 of 30
        assertTrue(r.note, r.note.contains("медиа:10→15"))
        p.queue(-35.0)
        cycle(ms.copy(mediaSens = 1)) // one step louder: 75%
        assertEquals(1 + Math.rint(29 * 0.75).toInt(), p.media())
    }

    @Test
    fun mediaMutedOrFullByUserIsLeftAlone() {
        val ms = s.copy(mediaEnabled = true)
        p.userSetsMedia(0)
        p.queue(-60.0)
        cycle(ms)
        assertEquals(0, p.media())
        p.userSetsMedia(30) // user turned it all the way up
        p.queue(-60.0)
        cycle(ms)
        assertEquals(30, p.media())
        p.userSetsMedia(20) // back to a normal level: regulated again
        p.queue(-60.0)
        cycle(ms)
        assertEquals(1, p.media())
    }

    @Test
    fun ourOwnMaximumIsStillRegulated() {
        val ms = s.copy(mediaEnabled = true)
        p.queue(-20.0, -20.0) // top step: media to 30 (by us)
        cycle(ms)
        assertEquals(30, p.media())
        p.queue(-60.0, -60.0)
        cycle(ms)
        assertEquals(1, p.media())
    }

    @Test
    fun manualMediaChangeDoesNotPauseRinger() {
        val ms = s.copy(mediaEnabled = true)
        p.queue(-60.0)
        cycle(ms)
        assertEquals(1, p.media())
        p.userSetsMedia(12)
        p.queue(-35.0, -35.0)
        cycle(ms)
        assertEquals(2, engine.state.step) // ringer still regulated
        assertEquals(12, p.media()) // media left alone
        assertTrue(p.logs.any { it.startsWith("громкость мультимедиа изменена вручную") })
        p.advanceSec(30 * 60)
        p.queue(-35.0)
        cycle(ms)
        assertEquals(1 + Math.rint(29 * 0.55).toInt(), p.media()) // 50% + the learned 5%
    }

    /** One cycle of [eng] at a steady [db] (enough measurements for a confirmation). */
    private fun runAt(eng: Engine, settings: Settings, db: Double) {
        p.queue(db, db)
        eng.cycle(settings)
        p.measurements.clear()
    }

    @Test
    fun manualMediaChangesAreLearned() {
        val mediaBias = MemoryBiasStore()
        val eng = Engine(p, History(store), mediaBiasStore = mediaBias)
        val ms = s.copy(mediaEnabled = true)
        runAt(eng, ms, -35.0) // step 2: media 15 of 30
        assertEquals(15, p.media())
        p.userSetsMedia(10) // too loud for the user
        runAt(eng, ms, -35.0)
        assertEquals(10, p.media())
        assertEquals(-Settings.MEDIA_LEARN_STEP_PCT, eng.state.learnedMediaPct, 0.0)
        assertEquals(-Settings.MEDIA_LEARN_STEP_PCT, mediaBias.value, 0.0)
        assertEquals(0.0, eng.state.learnedBiasDb, 0.0) // the ringer is not affected
        assertTrue(p.logs.any { it.contains("мультимедиа изменена вручную 15→10, учтено (тише)") })
        p.advanceSec(31 * 60)
        runAt(eng, ms, -35.0)
        assertEquals(1 + Math.rint(29 * 0.45).toInt(), p.media()) // 50% − 5%
        eng.resetLearning()
        assertEquals(0.0, mediaBias.value, 0.0)
    }

    @Test
    fun mediaMuteOrMaximumIsNotLearnedAndLearningIsClamped() {
        val eng = Engine(p, History(store), mediaBiasStore = MemoryBiasStore(28.0))
        val ms = s.copy(mediaEnabled = true)
        runAt(eng, ms, -60.0)
        p.userSetsMedia(0)
        runAt(eng, ms, -60.0)
        assertEquals(28.0, eng.state.learnedMediaPct, 0.0)
        p.advanceSec(31 * 60)
        p.userSetsMedia(5)
        runAt(eng, ms, -60.0)
        p.userSetsMedia(30)
        runAt(eng, ms, -60.0)
        assertEquals(28.0, eng.state.learnedMediaPct, 0.0)
        p.advanceSec(31 * 60)
        p.userSetsMedia(5)
        runAt(eng, ms, -60.0)
        p.userSetsMedia(p.media() + 3)
        runAt(eng, ms, -60.0)
        assertEquals(Settings.MEDIA_LEARN_MAX_PCT, eng.state.learnedMediaPct, 0.0)
        // disabled: still respected as manual, nothing learned
        val off = ms.copy(learnFromManual = false)
        p.advanceSec(31 * 60)
        runAt(eng, off, -60.0)
        p.userSetsMedia(p.media() - 3)
        runAt(eng, off, -60.0)
        assertEquals(Settings.MEDIA_LEARN_MAX_PCT, eng.state.learnedMediaPct, 0.0)
        assertTrue(p.logs.count { it.startsWith("громкость мультимедиа изменена вручную") } >= 3)
    }

    @Test
    fun mediaDisabledIsNeverTouched() {
        p.queue(-25.0, -25.0)
        cycle()
        assertEquals(10, p.media())
    }

    @Test
    fun manualRaiseIsLearnedAsLouder() {
        val bias = MemoryBiasStore()
        val eng = Engine(p, History(store), biasStore = bias)
        p.queue(-60.0)
        eng.cycle(s)
        assertEquals(1, p.ring())
        p.userSets(6) // too quiet for the user
        val r = eng.cycle(s)
        assertEquals(Outcome.SKIPPED, r.outcome)
        assertEquals(1.5, eng.state.learnedBiasDb, 0.0)
        assertEquals(1.5, bias.value, 0.0)
        assertTrue(p.logs.any { it.contains("учтено (громче)") })
        // after the override the thresholds are 1.5 dB lower: -47 now reaches step 1 (-46 → -47.5)
        p.advanceSec(31 * 60)
        p.queue(-47.0, -47.0)
        eng.cycle(s)
        assertEquals(1, eng.state.step)
        assertEquals(-47.5, eng.state.levels[1].db, 0.0)
    }

    @Test
    fun manualLowerIsLearnedAsQuieterAndCancelsRaise() {
        val bias = MemoryBiasStore(1.5)
        val eng = Engine(p, History(store), biasStore = bias)
        assertEquals(1.5, eng.state.learnedBiasDb, 0.0) // loaded from the store
        p.queue(-35.0, -35.0)
        eng.cycle(s)
        p.userSets(p.ring() - 2)
        eng.cycle(s)
        assertEquals(0.0, eng.state.learnedBiasDb, 0.0)
    }

    @Test
    fun learningIsClampedAndCanBeDisabledOrReset() {
        val bias = MemoryBiasStore(8.5)
        val eng = Engine(p, History(store), biasStore = bias)
        p.queue(-60.0)
        eng.cycle(s)
        p.userSets(9)
        eng.cycle(s)
        assertEquals(Settings.LEARN_MAX_DB, eng.state.learnedBiasDb, 0.0)
        // disabled: a change is still respected as manual, but nothing is learned
        p.advanceSec(31 * 60)
        p.queue(-60.0)
        eng.cycle(s.copy(learnFromManual = false))
        p.userSets(12)
        eng.cycle(s.copy(learnFromManual = false))
        assertEquals(Settings.LEARN_MAX_DB, eng.state.learnedBiasDb, 0.0)
        eng.resetLearning()
        assertEquals(0.0, bias.value, 0.0)
    }

    @Test
    fun switchingToSilentIsNotLearned() {
        val eng = Engine(p, History(store), biasStore = MemoryBiasStore())
        p.queue(-60.0)
        eng.cycle(s)
        p.userSets(0)
        eng.cycle(s)
        assertEquals(0.0, eng.state.learnedBiasDb, 0.0)
    }
}
