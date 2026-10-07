package io.github.z3f1rr.autovol

import android.content.Context
import io.github.z3f1rr.autovol.core.BiasStore
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

    @Volatile
    private var platformInstance: AndroidPlatform? = null

    @Volatile
    private var engineInstance: Engine? = null

    val platform: AndroidPlatform
        get() = platformInstance ?: synchronized(this) {
            platformInstance ?: AndroidPlatform(app).also { platformInstance = it }
        }

    val engine: Engine
        get() = engineInstance ?: synchronized(this) {
            engineInstance ?: Engine(
                platform,
                History(FileHistoryStore(app)),
                biasStore = object : BiasStore {
                    override fun load() = prefs.learnedBias.value
                    override fun save(biasDb: Double) {
                        prefs.learnedBias.value = biasDb
                    }
                },
                mediaBiasStore = object : BiasStore {
                    override fun load() = prefs.learnedMediaPct.value
                    override fun save(biasDb: Double) {
                        prefs.learnedMediaPct.value = biasDb
                    }
                },
            ).also { engineInstance = it }
        }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        // Re-initialised per Application instance (matters for Robolectric, harmless in production).
        platformInstance = null
        engineInstance = null
        _serviceRunning.value = false
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
