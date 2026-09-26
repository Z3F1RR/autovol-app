package io.github.z3f1rr.autovol.core

/** Streams AutoVol controls. */
enum class Stream { RING, NOTIFICATION }

/** Current stream volume and its range. */
data class Volume(val cur: Int, val min: Int, val max: Int)

data class Battery(val level: Int?, val charging: Boolean)

/** Measurement outcome: dBFS or an error text. */
sealed interface Measurement {
    data class Level(val db: Double) : Measurement
    data class Error(val message: String) : Measurement
}

/** Everything the engine needs from the system. The Android implementation lives outside core. */
interface Platform {
    fun nowMs(): Long

    /** Blocks for [sec] seconds (the confirmation pause between two measurements). */
    fun sleep(sec: Int)

    /** Null when ringer mode is NORMAL, otherwise its human-readable name ("вибро", "без звука"). */
    fun ringerNotNormal(): String?

    /** Do Not Disturb active (interruption filter != ALL). */
    fun dndActive(): Boolean

    /** Null when AudioManager mode is NORMAL, otherwise a description of the call/VoIP state. */
    fun callState(): String?

    /** Name of a connected external audio output (BT, wired, USB), or null. */
    fun externalOutput(): String?

    fun carMode(): Boolean

    fun battery(): Battery

    fun volume(stream: Stream): Volume?

    fun setVolume(stream: Stream, value: Int)

    /** Non-null description when the phone itself is playing sound. */
    fun playing(): String?

    fun measure(recSec: Int): Measurement

    fun log(msg: String)
}
