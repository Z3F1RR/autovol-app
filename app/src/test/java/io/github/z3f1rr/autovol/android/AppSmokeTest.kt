package io.github.z3f1rr.autovol.android

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.z3f1rr.autovol.AndroidPlatform
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.AutoVolService
import io.github.z3f1rr.autovol.BootReceiver
import io.github.z3f1rr.autovol.CallReceiver
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.Root
import io.github.z3f1rr.autovol.core.RepeatMode
import io.github.z3f1rr.autovol.core.RepeatSettings
import io.github.z3f1rr.autovol.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Android layer on several Android versions (10, 12, 14, 16): activity, service start paths,
 * boot, appops detection, repeat-call receiver and root helper. Replaces the missing emulator.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 31, 34, 36])
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val am get() = app.getSystemService(AudioManager::class.java)

    @After
    fun tearDown() {
        Root.su = "su"
    }

    private fun grantMic() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** OP_RECORD_AUDIO = 27 (hidden constant). */
    private fun setMicOp(mode: Int) {
        val ops = app.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(27, Process.myUid(), app.packageName, mode)
    }

    @Test
    fun mainScreenShowsAccessHintAtTop() {
        compose.onNodeWithText("AutoVol").assertExists()
        compose.onNodeWithText("Нет полного доступа к микрофону").assertExists()
        compose.onNodeWithText("Выдать через root").assertExists()
    }

    @Test
    fun micAccessUsesRawMode() {
        assertEquals(MicAccess.Level.NONE, MicAccess.level(app))
        grantMic()
        setMicOp(AppOpsManager.MODE_FOREGROUND)
        assertEquals(MicAccess.Level.BASIC, MicAccess.level(app))
        setMicOp(AppOpsManager.MODE_ALLOWED)
        assertEquals(MicAccess.Level.FULL, MicAccess.level(app))
        assertEquals("allow", MicAccess.rawMode(app))
    }

    @Test
    fun serviceStartedFromUiRunsACycle() {
        grantMic()
        AutoVol.prefs.enabled = true
        val intent = Intent(app, AutoVolService::class.java).putExtra(AutoVolService.EXTRA_FROM_UI, true)
        val service = Robolectric.buildService(AutoVolService::class.java, intent).create().startCommand(0, 1).get()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(AutoVol.serviceRunning.value)
        // the cycle runs on a worker thread: wait for its result
        val deadline = System.currentTimeMillis() + 20_000
        while (AutoVol.status.value.timeMs == 0L && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val st = AutoVol.status.value
        assertTrue("no cycle result", st.timeMs > 0)
        assertTrue(st.note, st.note.isNotEmpty())
        assertTrue(AutoVol.log.text(), AutoVol.log.text().contains("сервис запущен"))
        service.onDestroy()
        assertFalse(AutoVol.serviceRunning.value)
    }

    @Test
    fun backgroundStartInBasicModeAsksToResume() {
        grantMic()
        setMicOp(AppOpsManager.MODE_FOREGROUND)
        AutoVol.prefs.enabled = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(1, nm.allNotifications.size)
            assertNull(shadowOf(app).nextStartedService)
        } else {
            // Android 10 has no while-in-use restriction for the microphone: start directly.
            assertNotNull(shadowOf(app).nextStartedService)
        }
    }

    @Test
    fun bootWithFullAccessStartsService() {
        grantMic()
        setMicOp(AppOpsManager.MODE_ALLOWED)
        AutoVol.prefs.enabled = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun bootWhenDisabledDoesNothing() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun phone(state: String, number: String? = null) {
        val i = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE, state)
        @Suppress("DEPRECATION")
        number?.let { i.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, it) }
        CallReceiver().onReceive(app, i)
    }

    @Test
    fun repeatCallRaisesRingerAndRestoresIt() {
        AutoVol.prefs.repeat = RepeatSettings(enabled = true, mode = RepeatMode.SAME_NUMBER, windowMin = 30)
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        am.setStreamVolume(AudioManager.STREAM_RING, 2, 0)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        phone(TelephonyManager.EXTRA_STATE_RINGING, "+79123456789")
        phone(TelephonyManager.EXTRA_STATE_IDLE) // missed
        assertEquals(2, am.getStreamVolume(AudioManager.STREAM_RING))
        phone(TelephonyManager.EXTRA_STATE_RINGING)
        phone(TelephonyManager.EXTRA_STATE_RINGING, "89123456789")
        assertEquals(max, am.getStreamVolume(AudioManager.STREAM_RING))
        phone(TelephonyManager.EXTRA_STATE_OFFHOOK)
        assertEquals(2, am.getStreamVolume(AudioManager.STREAM_RING))
        phone(TelephonyManager.EXTRA_STATE_IDLE)
        assertEquals(2, am.getStreamVolume(AudioManager.STREAM_RING))
    }

    @Test
    fun repeatCallRespectsVibrate() {
        AutoVol.prefs.repeat = RepeatSettings(enabled = true, mode = RepeatMode.ANY_NUMBER, windowMin = 30)
        am.setStreamVolume(AudioManager.STREAM_RING, 2, 0)
        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        phone(TelephonyManager.EXTRA_STATE_RINGING)
        phone(TelephonyManager.EXTRA_STATE_IDLE)
        phone(TelephonyManager.EXTRA_STATE_RINGING)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, am.ringerMode)
        assertTrue(AutoVol.log.text().contains("вибро"))
    }

    @Test
    fun withoutRootGrantFailsGracefully() {
        Root.su = "/nonexistent/su"
        val r = Root.grantAll(app)
        assertFalse(r.ok)
        assertFalse(AutoVol.prefs.rootGranted)
    }

    @Test
    fun withRootAllGrantsAreIssued() {
        val dir = File(app.cacheDir, "fake-su").apply { mkdirs() }
        val log = File(dir, "commands.txt")
        val su = File(dir, "su")
        su.writeText("#!/bin/sh\n[ \"\$2\" = id ] && echo 'uid=0(root) gid=0(root)'\necho \"\$2\" >> '${log.path}'\n")
        su.setExecutable(true)
        Root.su = su.path
        Root.grantAll(app)
        val cmds = log.readText()
        assertTrue(cmds, cmds.contains("appops set ${app.packageName} RECORD_AUDIO allow"))
        assertTrue(cmds, cmds.contains("dumpsys deviceidle whitelist +${app.packageName}"))
        assertTrue(AutoVol.prefs.rootGranted)
    }

    @Test
    fun platformReadsSystemState() {
        val p = AndroidPlatform(app)
        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals("вибро", p.ringerNotNormal())
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertNull(p.ringerNotNormal())
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        assertEquals("VoIP-звонок", p.callState())
        am.mode = AudioManager.MODE_NORMAL
        assertNull(p.callState())
        assertNull(p.externalOutput())
        assertNotNull(p.volume(io.github.z3f1rr.autovol.core.Stream.MEDIA))
        p.battery()
        p.playing()
    }
}
