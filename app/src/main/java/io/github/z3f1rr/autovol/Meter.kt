package io.github.z3f1rr.autovol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.z3f1rr.autovol.core.Analysis
import io.github.z3f1rr.autovol.core.LevelAnalyzer
import io.github.z3f1rr.autovol.core.Measurement
import kotlin.math.max

/**
 * Ambient level measurement: RMS in dBFS over [recSec] seconds of 16 kHz mono PCM16, skipping the
 * first 300 ms (equivalent of `ffmpeg -ss 0.3 ... volumedetect` mean_volume in the script).
 * Signal analysis (DC removal, digital silence) lives in [LevelAnalyzer].
 */
class Meter(private val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)

    val source: Int by lazy {
        if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    /** Diagnostics of the last successful recording. */
    @Volatile
    var last: Analysis? = null
        private set

    val sourceName: String get() = if (source == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC"

    fun diagnostics(): String {
        val a = last ?: return "источник $sourceName, замеров ещё не было"
        return String.format(
            java.util.Locale.ROOT, "источник %s, уровень %.1f дБ, смещение DC %.1f дБ, пик %d",
            sourceName, a.db, a.dcDb, a.peak,
        )
    }

    @SuppressLint("MissingPermission")
    fun measure(recSec: Int): Measurement {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Measurement.Error("нет разрешения на микрофон")
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return Measurement.Error("getMinBufferSize=$minBuf")
        val rec = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE / 2 * 2))
        } catch (e: Exception) {
            return Measurement.Error("AudioRecord: ${e.message}")
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return Measurement.Error("AudioRecord не инициализирован")
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return Measurement.Error("запись не началась (микрофон занят?)")
            }
            val total = RATE * recSec
            val analyzer = LevelAnalyzer(skip = RATE * SKIP_MS / 1000)
            val buf = ShortArray(RATE / 10)
            var got = 0
            var empty = 0
            while (got < total) {
                val n = rec.read(buf, 0, minOf(buf.size, total - got))
                if (n < 0) return Measurement.Error("AudioRecord.read=$n")
                if (n == 0) {
                    if (++empty > 50) return Measurement.Error("AudioRecord не отдаёт данные")
                    continue
                }
                analyzer.feed(buf, n)
                got += n
            }
            val a = analyzer.result()
            last = a
            return Measurement.Level(a.db)
        } catch (e: Exception) {
            return Measurement.Error("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                rec.stop()
            } catch (e: IllegalStateException) {
                // not started
            }
            rec.release()
        }
    }

    companion object {
        const val RATE = 16000
        const val SKIP_MS = 300
    }
}
