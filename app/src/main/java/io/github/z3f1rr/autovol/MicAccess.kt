package io.github.z3f1rr.autovol

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/** Background microphone access level, see CLAUDE.md "Три режима доступа". */
object MicAccess {
    enum class Level { NONE, BASIC, FULL }

    fun level(ctx: Context): Level {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Level.NONE
        }
        return when (rawModeInt(ctx)) {
            AppOpsManager.MODE_ALLOWED -> Level.FULL
            AppOpsManager.MODE_FOREGROUND, AppOpsManager.MODE_DEFAULT -> Level.BASIC
            else -> Level.NONE
        }
    }

    /**
     * The configured mode, not the effective one: unsafeCheckOpNoThrow() evaluates "foreground" against
     * the current process state (allow while on screen, ignore in background), which made basic mode
     * look like full access whenever the app was open.
     */
    private fun rawModeInt(ctx: Context): Int {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return ops.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), ctx.packageName)
    }

    /** Raw appop mode name, for the log. */
    fun rawMode(ctx: Context): String {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "нет разрешения"
        }
        return when (val mode = rawModeInt(ctx)) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> mode.toString()
        }
    }

    fun adbCommand(ctx: Context) = "adb shell appops set ${ctx.packageName} RECORD_AUDIO allow"
}
