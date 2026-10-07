package io.github.z3f1rr.autovol

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Last [MAX] events, like autovol.log in the script. Persisted to a small text file. */
class EventLog(context: Context) {
    private val file = File(context.filesDir, "events.log")
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()

    init {
        try {
            if (file.exists()) file.readLines().takeLast(MAX).forEach { lines.addLast(it) }
        } catch (e: java.io.IOException) {
            // start with an empty log
        }
        _flow.value = lines.toList()
    }

    @Synchronized
    fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + " " + msg.replace('\n', ' '))
        while (lines.size > MAX) lines.removeFirst()
        val snapshot = lines.toList()
        _flow.value = snapshot
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(snapshot.joinToString("\n", postfix = "\n"))
            tmp.renameTo(file)
        } catch (e: java.io.IOException) {
            // logging must never break a cycle
        }
    }

    fun text(): String = _flow.value.joinToString("\n")

    companion object {
        const val MAX = 300
    }
}
