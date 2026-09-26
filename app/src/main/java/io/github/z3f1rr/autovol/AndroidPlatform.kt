package io.github.z3f1rr.autovol

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.BatteryManager
import android.os.SystemClock
import io.github.z3f1rr.autovol.core.Battery
import io.github.z3f1rr.autovol.core.Measurement
import io.github.z3f1rr.autovol.core.Platform
import io.github.z3f1rr.autovol.core.Stream
import io.github.z3f1rr.autovol.core.Volume

class AndroidPlatform(private val ctx: Context) : Platform {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val ui = ctx.getSystemService(UiModeManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val meter = Meter(ctx)

    override fun nowMs() = System.currentTimeMillis()

    override fun sleep(sec: Int) = SystemClock.sleep(sec * 1000L)

    override fun ringerNotNormal(): String? = when (val m = am.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> null
        AudioManager.RINGER_MODE_VIBRATE -> "вибро"
        AudioManager.RINGER_MODE_SILENT -> "без звука"
        else -> m.toString()
    }

    override fun dndActive(): Boolean {
        val f = nm.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    override fun callState(): String? = when (val m = am.mode) {
        AudioManager.MODE_NORMAL -> null
        AudioManager.MODE_RINGTONE -> "входящий вызов"
        AudioManager.MODE_IN_CALL -> "вызов"
        AudioManager.MODE_IN_COMMUNICATION -> "VoIP-звонок"
        AudioManager.MODE_CALL_SCREENING -> "фильтрация вызова"
        else -> "аудиорежим $m"
    }

    override fun externalOutput(): String? {
        val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in EXTERNAL } ?: return null
        val name = dev.productName?.toString()?.trim().orEmpty()
        return deviceTypeName(dev.type) + if (name.isNotEmpty() && name != android.os.Build.MODEL) " ($name)" else ""
    }

    override fun carMode() = ui.currentModeType == Configuration.UI_MODE_TYPE_CAR

    override fun battery() = Battery(
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
        bm.isCharging,
    )

    override fun volume(stream: Stream): Volume? = try {
        val s = stream.id()
        Volume(am.getStreamVolume(s), am.getStreamMinVolume(s), am.getStreamMaxVolume(s))
    } catch (e: RuntimeException) {
        null
    }

    override fun setVolume(stream: Stream, value: Int) {
        try {
            am.setStreamVolume(stream.id(), value, 0)
        } catch (e: SecurityException) {
            log("не удалось изменить громкость: ${e.message}")
        }
    }

    override fun playing(): String? {
        if (am.isMusicActive) return "медиа"
        val cfg = am.activePlaybackConfigurations.firstOrNull { isStarted(it) } ?: return null
        return usageName(cfg.audioAttributes.usage)
    }

    override fun measure(recSec: Int): Measurement = meter.measure(recSec)

    override fun log(msg: String) = AutoVol.log.add(msg)

    private fun Stream.id() = when (this) {
        Stream.RING -> AudioManager.STREAM_RING
        Stream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
    }

    companion object {
        val EXTERNAL = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_DOCK_ANALOG,
        )

        fun deviceTypeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth гарнитура"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_HEARING_AID -> "слуховой аппарат"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "проводные наушники"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-аудио"
            AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "док-станция"
            else -> "линейный выход"
        }

        /**
         * getActivePlaybackConfigurations() also lists paused/idle players; the script only counted
         * "state:started". The state getter is hidden, but toString() carries it.
         */
        fun isStarted(c: AudioPlaybackConfiguration): Boolean {
            val s = c.toString()
            return if ("state:" in s) "state:started" in s else true
        }

        fun usageName(u: Int) = when (u) {
            AudioAttributes.USAGE_MEDIA -> "медиа"
            AudioAttributes.USAGE_GAME -> "игра"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "голосовая связь"
            AudioAttributes.USAGE_ALARM -> "будильник"
            AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "уведомление/звонок"
            AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "ассистент/навигация"
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "звук интерфейса"
            else -> "плеер, usage $u"
        }
    }
}
