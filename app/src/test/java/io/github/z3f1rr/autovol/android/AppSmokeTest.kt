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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.z3f1rr.autovol.AndroidPlatform
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.AutoVolService
import io.github.z3f1rr.autovol.BootReceiver
import io.github.z3f1rr.autovol.CallReceiver
import io.github.z3f1rr.autovol.MicAccess
import io.github.z3f1rr.autovol.Root
import io.github.z3f1rr.autovol.Updater
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
        Root.su = null
    }

    private fun grantMic() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** OP_RECORD_AUDIO = 27 (hidden constant). */
    private fun setMicOp(mode: Int) {
        val ops = app.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(27, Process.myUid(), app.packageName, mode)
    }

    @Test
    fun mainScreenShowsAccessHintWithoutRootControls() {
        compose.onNodeWithText("AutoVol").assertExists()
        compose.onNodeWithText("Нет автозапуска после перезагрузки").assertExists()
        // no root manager installed: nothing about root anywhere
        compose.onNodeWithText("Настроить через root").assertDoesNotExist()
        compose.onNodeWithText("Root", substring = true).assertDoesNotExist()
        // calibration and system moved to "Ещё"
        compose.onNodeWithText("Автокалибровка").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ещё", substring = true).performClick()
        compose.onNodeWithText("Автокалибровка").assertExists()
        compose.onNodeWithText("Root").assertDoesNotExist()
        compose.onNodeWithText("Журнал событий").performScrollTo().performClick()
        compose.onNodeWithText("Поделиться").assertExists()
    }

    @Test
    fun hintCanBeDismissedWithoutRoot() {
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithText("Нет автозапуска после перезагрузки").assertDoesNotExist()
    }

    @Test
    fun githubReleaseIsParsed() {
        val json = """{"tag_name":"v0.2.0","body":"Что нового","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":1},
            {"name":"AutoVol-v0.2.0.apk","browser_download_url":"https://x/a.apk","size":2000000}]}"""
        val r = Updater.parseRelease(json)!!
        assertEquals("0.2.0", r.version)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertNull(Updater.parseRelease("""{"tag_name":"v1","assets":[]}"""))
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
        val log = fakeSu()
        val r = Root.grantAll(app)
        assertTrue(r.output, r.ok)
        val cmds = log.readLines()
        val uidCmd = "appops set --uid ${app.packageName} RECORD_AUDIO allow"
        assertTrue(cmds.toString(), uidCmd in cmds)
        assertTrue(cmds.toString(), "dumpsys deviceidle whitelist +${app.packageName}" in cmds)
        // every pm grant resets the mic op, so the appops must come after all of them
        assertTrue(cmds.toString(), cmds.indexOf(uidCmd) > cmds.indexOfLast { it.startsWith("pm grant") })
        assertTrue(AutoVol.prefs.rootGranted)
        assertTrue(AutoVol.log.text(), AutoVol.log.text().contains("root: работает"))
    }

    /** Fake su: answers "id" as root and records every command. */
    private fun fakeSu(): File {
        val dir = File(app.cacheDir, "fake-su").apply { mkdirs() }
        val log = File(dir, "commands.txt").apply { delete() }
        val su = File(dir, "su")
        su.writeText("#!/bin/sh\n[ \"\$2\" = id ] && echo 'uid=0(root) gid=0(root)'\necho \"\$2\" >> '${log.path}'\n")
        su.setExecutable(true)
        Root.su = su.path
        return log
    }

    @Test
    fun bootWithRootStartsServiceAsRoot() {
        val log = fakeSu()
        grantMic()
        setMicOp(AppOpsManager.MODE_FOREGROUND)
        AutoVol.prefs.enabled = true
        AutoVol.prefs.rootGranted = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val deadline = System.currentTimeMillis() + 10_000
        while (!AutoVol.log.text().contains("запуск через root") && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val cmds = log.readText()
        assertTrue(cmds, cmds.contains("am start-foreground-service -n ${app.packageName}/${app.packageName}.AutoVolService"))
        assertTrue(cmds, cmds.contains("--ez from_ui true"))
        // no "tap to resume" notification for root users
        assertEquals(0, shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test
    fun bootWithRootRevokedFallsBackToNotification() {
        Root.su = "/nonexistent/su"
        grantMic()
        setMicOp(AppOpsManager.MODE_FOREGROUND)
        AutoVol.prefs.enabled = true
        AutoVol.prefs.rootGranted = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val deadline = System.currentTimeMillis() + 10_000
        while (nm.allNotifications.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, nm.allNotifications.size)
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
