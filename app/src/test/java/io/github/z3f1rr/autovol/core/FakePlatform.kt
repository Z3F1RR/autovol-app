package io.github.z3f1rr.autovol.core

class FakePlatform : Platform {
    var now = 1_700_000_000_000L
    var ringer: String? = null
    var dnd = false
    var call: String? = null
    var external: String? = null
    var car = false
    var battery = Battery(80, false)
    val volumes = mutableMapOf(Stream.RING to Volume(8, 0, 15), Stream.NOTIFICATION to Volume(8, 0, 15))
    val measurements = ArrayDeque<Measurement>()

    /** Values returned by successive playing() calls; empty = nothing plays. */
    val playingSeq = ArrayDeque<String?>()
    var measureCalls = 0
    val setCalls = mutableListOf<Pair<Stream, Int>>()
    val logs = mutableListOf<String>()

    /** Runs right after each measurement (e.g. the slider was moved during recording). */
    var afterMeasure: (() -> Unit)? = null

    fun queue(vararg db: Double) = db.forEach { measurements.addLast(Measurement.Level(it)) }

    fun ring() = volumes[Stream.RING]!!.cur
    fun notif() = volumes[Stream.NOTIFICATION]!!.cur

    /** The user moves the ringer volume slider. */
    fun userSets(v: Int) {
        volumes[Stream.RING] = volumes[Stream.RING]!!.copy(cur = v)
    }

    fun advanceSec(sec: Int) {
        now += sec * 1000L
    }

    override fun nowMs() = now
    override fun sleep(sec: Int) = advanceSec(sec)
    override fun ringerNotNormal() = ringer
    override fun dndActive() = dnd
    override fun callState() = call
    override fun externalOutput() = external
    override fun carMode() = car
    override fun battery() = battery
    override fun volume(stream: Stream) = volumes[stream]
    override fun setVolume(stream: Stream, value: Int) {
        setCalls += stream to value
        volumes[stream] = volumes[stream]!!.copy(cur = value)
    }

    override fun playing(): String? = playingSeq.removeFirstOrNull()

    override fun measure(recSec: Int): Measurement {
        measureCalls++
        advanceSec(recSec)
        val m = measurements.removeFirstOrNull() ?: error("unexpected measurement")
        afterMeasure?.invoke()
        return m
    }

    override fun log(msg: String) {
        logs += msg
    }
}
