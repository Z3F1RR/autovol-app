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
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), ctx.packageName)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> Level.FULL
            AppOpsManager.MODE_FOREGROUND, AppOpsManager.MODE_DEFAULT -> Level.BASIC
            else -> Level.NONE
        }
    }

    fun adbCommand(ctx: Context) = "adb shell appops set ${ctx.packageName} RECORD_AUDIO allow"
}
