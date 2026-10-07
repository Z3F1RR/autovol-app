package io.github.z3f1rr.autovol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.z3f1rr.autovol.AutoVol
import io.github.z3f1rr.autovol.AutoVolService

class MainActivity : ComponentActivity() {
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableAfterPermissions() else AutoVol.log.add("разрешение на микрофон не выдано")
    }
    private val notifRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val phoneRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val denied = r.filterValues { !it }.keys.map { it.substringAfterLast('.') }
        if (denied.isNotEmpty()) AutoVol.log.add("повторный звонок: не выданы разрешения ${denied.joinToString()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleResume(intent)
        setContent {
            AutoVolTheme {
                val dark = isAppInDarkTheme()
                LaunchedEffect(dark) {
                    val bars = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                // main → "Ещё" → log
                var screen by rememberSaveable { mutableStateOf("main") }
                BackHandler(enabled = screen != "main") { screen = if (screen == "log") "more" else "main" }
                when (screen) {
                    "log" -> LogScreen(onBack = { screen = "more" })
                    "more" -> MoreScreen(onBack = { screen = "main" }, onOpenLog = { screen = "log" })
                    else -> MainScreen(
                        onToggle = ::toggle,
                        onOpenMore = { screen = "more" },
                        onRequestPhone = ::requestPhonePermissions,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResume(intent)
    }

    private var resumeRequested = false

    private fun handleResume(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            intent.removeExtra(EXTRA_RESUME)
            resumeRequested = true
        }
    }

    /** "Tap to resume" notification after reboot: once visible, a microphone FGS is allowed. */
    override fun onResume() {
        super.onResume()
        if (resumeRequested) {
            resumeRequested = false
            if (AutoVol.prefs.enabled && hasMic()) AutoVolService.startFromUi(this)
        }
    }

    /** Call state for the repeat-call boost; the call log only for "same number" mode. */
    private fun requestPhonePermissions(sameNumber: Boolean) {
        val need = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            if (sameNumber) add(Manifest.permission.READ_CALL_LOG)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) phoneRequest.launch(need.toTypedArray())
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggle(on: Boolean) {
        if (!on) {
            AutoVol.prefs.enabled = false
            AutoVolService.stop(this)
            AutoVol.log.add("выключено пользователем")
            return
        }
        if (!hasMic()) {
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        enableAfterPermissions()
    }

    private fun enableAfterPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AutoVol.prefs.enabled = true
        AutoVol.log.add("включено пользователем")
        AutoVolService.startFromUi(this)
    }

    companion object {
        const val EXTRA_RESUME = "resume"
    }
}
