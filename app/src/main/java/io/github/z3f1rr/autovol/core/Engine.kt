package io.github.z3f1rr.autovol.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** Mutable engine state between cycles (port of class State). */
class EngineState {
    /** Current step; null = must re-synchronise with the actual volume. */
    var step: Int? = null
    val lastSet: MutableMap<Stream, Int> = mutableMapOf()
    var lastChangeMs: Long = 0
    var overrideUntilMs: Long = 0
    var lastDb: Double? = null
    var cal: CalInfo? = null
    var levels: List<Level> = emptyList()

    /** Media volume we set last; null = not ours (muted/maximum by the user, or never set). Survives pauses. */
    var mediaLastSet: Int? = null

    /** Correction learned from manual ringer changes, dB; positive = louder ([Learning]). */
    var learnedBiasDb: Double = 0.0
    var mediaOverrideUntilMs: Long = 0

    fun resync() {
        step = null
        lastSet.clear()
    }
}

enum class Outcome {
    /** Paused: volume untouched, sync reset (disabled, vibrate, DND, BT, battery, manual override). */
    PAUSED,

    /** Cycle skipped without touching the sync (call, playback, measurement error). */
    SKIPPED,

    /** Measured and volume applied (possibly unchanged). */
    APPLIED,
}

/** Why a cycle did not apply a measurement: localized by the UI ([CycleResult.note] is the log text). */
enum class Reason {
    NONE, DISABLED, USER_PAUSE, RINGER, DND, CALL, CAR, EXT_AUDIO, LOW_BATTERY, VOLUME_ERROR, MANUAL,
    PLAYING, MEASURE_ERROR, MIC_MUTED, RAISE_UNCONFIRMED, MODE_CHANGED, ERROR,
}

/**
 * [note] is the detailed log line (Russian, for the author); [reason] + [arg] are for the UI.
 * [arg] is a time "HH:mm" or a battery level, depending on [reason].
 */
data class CycleResult(
    val waitSec: Int,
    val note: String,
    val outcome: Outcome,
    val reason: Reason = Reason.NONE,
    val arg: String? = null,
)

/**
 * Pure port of cycle() from reference/autovol.py. The decision logic must stay 1:1 with the script;
 * only system access goes through [Platform].
 */
class Engine(
    private val p: Platform,
    val history: History,
    val state: EngineState = EngineState(),
    private val streams: List<Stream> = listOf(Stream.RING, Stream.NOTIFICATION),
    private val biasStore: BiasStore = MemoryBiasStore(),
) {
    init {
        if (state.lastChangeMs == 0L) state.lastChangeMs = p.nowMs()
        state.learnedBiasDb = biasStore.load()
    }

    fun cycle(s: Settings): CycleResult {
        val st = state
        val now = p.nowMs()
        val fast = s.fast
        val slow = s.slow
        val idle = s.idle

        fun pause(note: String, reason: Reason, arg: String? = null, wait: Int = idle): CycleResult {
            st.resync()
            return CycleResult(wait, note, Outcome.PAUSED, reason, arg)
        }

        fun skip(wait: Int, note: String, reason: Reason, arg: String? = null) =
            CycleResult(wait, note, Outcome.SKIPPED, reason, arg)

        if (!s.enabled) return pause("выключено", Reason.DISABLED)
        if (now < s.pauseUntilMs) {
            val left = ((s.pauseUntilMs - now + 999) / 1000).toInt()
            val until = hhmm(s.pauseUntilMs)
            return pause("пауза до $until", Reason.USER_PAUSE, until, minOf(idle, max(1, left)))
        }
        p.ringerNotNormal()?.let { return pause("режим звонка: $it", Reason.RINGER) }
        if (s.pauseOnDnd && p.dndActive()) return pause("режим Не беспокоить", Reason.DND)
        p.callState()?.let { return skip(fast, "пропуск: $it", Reason.CALL) }
        if (p.carMode()) return pause("пауза: режим автомобиля", Reason.CAR)
        if (s.pauseOnExtAudio) p.externalOutput()?.let { return pause("пауза: звук идёт на $it", Reason.EXT_AUDIO) }
        val bat = p.battery()
        if (bat.level != null && bat.level <= s.lowBat && !bat.charging) {
            return pause("пауза: заряд ${bat.level}%", Reason.LOW_BATTERY, bat.level.toString())
        }

        val cur = streams.associateWith { p.volume(it) }
        if (cur.values.any { it == null }) {
            return skip(idle, "ошибка: не удалось прочитать громкость", Reason.VOLUME_ERROR)
        }
        if (now < st.overrideUntilMs) {
            val until = hhmm(st.overrideUntilMs)
            return skip(idle, "ручная громкость, жду до $until", Reason.MANUAL, until)
        }
        if (st.overrideUntilMs != 0L) {
            st.overrideUntilMs = 0
            st.resync()
        }
        if (st.lastSet.isNotEmpty() && streams.any { cur[it]!!.cur != (st.lastSet[it] ?: cur[it]!!.cur) }) {
            if (s.learnFromManual) learnFromManual(cur)
            st.resync()
            st.overrideUntilMs = now + s.overrideMin * 60_000L
            return skip(idle, "громкость изменена вручную — пауза ${s.overrideMin} мин", Reason.MANUAL, hhmm(st.overrideUntilMs))
        }
        if (s.mediaEnabled) checkMediaOverride(s, now)
        val chkPlay = s.pauseOnMedia

        /** One clean measurement: (db, null) or (null, skip reason). */
        fun sample(): Pair<Double?, Pair<String, Reason>?> {
            if (chkPlay) p.playing()?.let { return null to ("пропуск: телефон воспроизводит звук ($it)" to Reason.PLAYING) }
            val db = when (val m = p.measure(s.recSec)) {
                is Measurement.Error -> return null to ("ошибка замера: ${m.message}" to Reason.MEASURE_ERROR)
                is Measurement.Level -> m.db
            }
            if (db <= s.silentDb) {
                return null to ("${f1(db)} дБ — микрофон заглушён системой/занят, пропуск" to Reason.MIC_MUTED)
            }
            if (chkPlay) p.playing()?.let {
                return null to ("${f1(db)} дБ отброшен: во время замера играл звук ($it)" to Reason.PLAYING)
            }
            return db to null
        }

        val (db0, why) = sample()
        if (db0 == null) return skip(fast, why!!.first, why.second)
        var db: Double = db0
        val (calibrated, cal) = Levels.autoLevels(history.values(), s.levels, s)
        val lv = Levels.louderBy(
            calibrated,
            s.ringSens.coerceIn(-Settings.SENS_MAX, Settings.SENS_MAX) * Settings.SENS_DB_PER_NOTCH + st.learnedBiasDb,
        )
        st.cal = cal
        st.levels = lv
        var up = Levels.stepOf(db, lv)
        if (up > (st.step ?: 0)) {
            // Confirmation: a single spike (notification sound, knock, rustle) must not raise the volume.
            p.sleep(s.confirmSec)
            val (db2, why2) = sample()
            if (db2 == null) return skip(fast, "повышение не подтверждено (${why2!!.first})", Reason.RAISE_UNCONFIRMED)
            history.add(db, now, s.calDays)
            if (Levels.stepOf(db2, lv) < up) {
                p.log("всплеск ${f1(db)} дБ не подтвердился (повтор ${f1(db2)} дБ)")
            }
            db = minOf(db, db2)
        }
        history.add(db, now, s.calDays)
        st.lastDb = db
        up = Levels.stepOf(db, lv)
        val n = lv.size - 1
        var extra = ""
        val new: Int
        val curStep = st.step
        if (curStep == null || up > curStep) {
            new = up
        } else {
            val k = curStep
            // "Rubber band": the higher the step, the smaller the margin - volume goes down easier.
            val m = if (k > 0) max(0.5, s.margin * (1 - (k - 1).toDouble() / max(1, n - 1))) else 0.0
            val dn = Levels.stepOf(db + m, lv)
            if (dn < k) {
                // Quick re-check instead of waiting several cycles: quiet confirmed -> go down now.
                p.sleep(s.confirmSec)
                val (db2, why2) = sample()
                if (db2 == null) {
                    new = k
                    extra = " [понижение отложено: ${why2!!.first}]"
                } else {
                    history.add(db2, now, s.calDays)
                    val dn2 = Levels.stepOf(db2 + m, lv)
                    if (dn2 < k) {
                        new = max(dn, dn2)
                    } else {
                        new = k
                        extra = " [затишье не подтвердилось: ${f1(db2)} дБ]"
                    }
                }
            } else {
                new = k
            }
        }
        val pct = lv[new].pct

        // Re-check right before changing: the slider or a call may have changed during measurement.
        if (p.ringerNotNormal() != null || p.callState() != null) {
            st.resync()
            return skip(fast, "режим изменился во время замера — пропуск", Reason.MODE_CHANGED)
        }
        val changed = ArrayList<String>()
        for (str in streams) {
            val v = cur[str]!!
            val t = Levels.targetVol(pct, v.min, v.max, s.minVol)
            if (v.cur != t) {
                p.setVolume(str, t)
                changed += "${streamName(str)}:${v.cur}→$t"
            }
        }
        for (str in streams) {
            val v = cur[str]!!
            st.lastSet[str] = p.volume(str)?.cur ?: Levels.targetVol(pct, v.min, v.max, s.minVol)
        }
        if (s.mediaEnabled) applyMedia(s, now, lv, new, changed)
        if (changed.isNotEmpty()) {
            st.lastChangeMs = now
            if (p.ringerNotNormal() != null) p.log("ВНИМАНИЕ: после изменения громкости сменился режим звонка")
        }
        st.step = new
        val wait = if (new > 0) {
            // At raised volume check more often, to bring it back down sooner.
            Levels.pyRound(fast - (fast - s.elevInt) * new.toDouble() / max(1, n)).toInt()
        } else {
            if (now - st.lastChangeMs < s.stableMin * 60_000L) fast else slow
        }
        val note = "${f1(db)} дБ → ступень $new ($pct%)" +
            calNote(cal) +
            (if (changed.isNotEmpty()) " изменено " + changed.joinToString(" ") else "") +
            extra
        return CycleResult(wait, note, Outcome.APPLIED)
    }

    /** Direction of the user's correction (ringer first, else any stream) → learned bias. */
    private fun learnFromManual(cur: Map<Stream, Volume?>) {
        val changed = streams.firstOrNull { cur[it]!!.cur != (state.lastSet[it] ?: cur[it]!!.cur) } ?: return
        val now = cur[changed]!!.cur
        val was = state.lastSet[changed] ?: return
        if (now == 0) return // to silent/vibrate: a mode change, not a level preference
        val dir = if (now > was) 1 else -1
        val before = state.learnedBiasDb
        state.learnedBiasDb = Learning.next(before, dir)
        if (state.learnedBiasDb != before) biasStore.save(state.learnedBiasDb)
        p.log(
            "ручное изменение ${streamName(changed)} $was→$now: учтено (" +
                (if (dir > 0) "громче" else "тише") + "), поправка ${signed(state.learnedBiasDb)} дБ",
        )
    }

    /** Forget the learned correction. */
    fun resetLearning() {
        state.learnedBiasDb = 0.0
        biasStore.save(0.0)
    }

    /** The user moved the media slider since we set it: leave media alone for OVERRIDE_MIN. */
    private fun checkMediaOverride(s: Settings, now: Long) {
        val set = state.mediaLastSet ?: return
        val v = p.volume(Stream.MEDIA) ?: return
        if (v.cur != set) {
            state.mediaLastSet = null
            state.mediaOverrideUntilMs = now + s.overrideMin * 60_000L
            p.log("громкость мультимедиа изменена вручную — не трогаю ${s.overrideMin} мин")
        }
    }

    /**
     * Media follows the ringer step shifted by [Settings.mediaSens] steps. Muted media (0) and media
     * the user turned all the way up stay as they are.
     */
    private fun applyMedia(s: Settings, now: Long, lv: List<Level>, ringStep: Int, changed: MutableList<String>) {
        val st = state
        if (now < st.mediaOverrideUntilMs) return
        val v = p.volume(Stream.MEDIA) ?: return
        if (v.cur == 0 || (v.cur == v.max && st.mediaLastSet != v.cur)) {
            st.mediaLastSet = null
            return
        }
        val step = (ringStep + s.mediaSens).coerceIn(0, lv.size - 1)
        val t = Levels.targetVol(lv[step].pct, v.min, v.max, 1)
        if (t != v.cur) {
            p.setVolume(Stream.MEDIA, t)
            changed += "${streamName(Stream.MEDIA)}:${v.cur}→$t"
        }
        st.mediaLastSet = p.volume(Stream.MEDIA)?.cur ?: t
    }

    companion object {
        fun calNote(cal: CalInfo?): String = when {
            cal == null -> " [ручные пороги]"
            !cal.complete -> " [калибровка ${(cal.weight * 100).toInt()}%]"
            else -> ""
        }

        fun signed(x: Double): String = (if (x > 0) "+" else "") + f1(x)

        fun f1(x: Double): String = String.format(Locale.ROOT, "%.1f", x)

        fun hhmm(ms: Long): String =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

        fun streamName(s: Stream) = when (s) {
            Stream.RING -> "звонок"
            Stream.NOTIFICATION -> "уведомл."
            Stream.MEDIA -> "медиа"
        }
    }
}
