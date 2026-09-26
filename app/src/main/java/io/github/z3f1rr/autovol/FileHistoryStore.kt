package io.github.z3f1rr.autovol

import android.content.Context
import android.util.AtomicFile
import io.github.z3f1rr.autovol.core.HistoryStore
import io.github.z3f1rr.autovol.core.Sample
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Measurement history in a compact binary file (8 bytes per sample, ~7 days ≈ 50 KB). */
class FileHistoryStore(context: Context) : HistoryStore {
    private val file = AtomicFile(File(context.filesDir, "history.bin"))

    override fun load(): List<Sample> = try {
        DataInputStream(file.openRead().buffered()).use { inp ->
            val n = inp.readInt()
            List(n) { Sample(inp.readInt().toLong() and 0xFFFFFFFFL, inp.readFloat().toDouble()) }
        }
    } catch (e: IOException) {
        emptyList()
    }

    override fun save(samples: List<Sample>) {
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            return
        }
        try {
            val d = DataOutputStream(out.buffered())
            d.writeInt(samples.size)
            for (s in samples) {
                d.writeInt(s.tSec.toInt())
                d.writeFloat(s.db.toFloat())
            }
            d.flush()
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
        }
    }
}
