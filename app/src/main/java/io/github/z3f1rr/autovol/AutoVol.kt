package io.github.z3f1rr.autovol

import android.content.Context
import io.github.z3f1rr.autovol.core.Engine
import io.github.z3f1rr.autovol.core.History
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide singletons: preferences, engine, event log and UI-observable state. */
object AutoVol {
    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var log: EventLog
        private set

    val platform: AndroidPlatform by lazy { AndroidPlatform(app) }

    val engine: Engine by lazy {
        Engine(platform, History(FileHistoryStore(app)))
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        log = EventLog(app)
        _status.value = prefs.loadStatus()
    }

    fun publish(status: Status) {
        _status.value = status
        prefs.saveStatus(status)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }
}
