package io.github.z3f1rr.autovol

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional root (KernelSU / Magisk / APatch): grants everything AutoVol needs with one tap instead of
 * adb. Never required; every call fails gracefully without root. Must not run on the main thread.
 */
object Root {
    data class Result(val ok: Boolean, val output: String)

    /** Root managers we can name in the hint (declared in <queries> for package visibility). */
    private val MANAGERS = listOf(
        "me.weishu.kernelsu" to "KernelSU",
        "com.rifsxd.ksunext" to "KernelSU Next",
        "com.sukisu.ultra" to "SukiSU",
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.huskydg.magisk" to "Kitsune Magisk",
        "me.bmax.apatch" to "APatch",
    )

    /** Installed root manager name, if any. Cheap, no su call (su may pop up a prompt on Magisk). */
    fun managerName(ctx: Context): String? = MANAGERS.firstOrNull { (pkg, _) ->
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }?.second

    /** su binary; replaced by a fake script in tests. */
    @Volatile
    internal var su = "su"

    fun run(cmd: String, timeoutSec: Long = 15): Result = try {
        val p = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
            } catch (e: IOException) {
                // process killed
            }
        }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            Result(false, "su не ответил за $timeoutSec с (запрос root отклонён или не подтверждён?)")
        } else {
            reader.join(1000)
            Result(p.exitValue() == 0, out.toString().trim())
        }
    } catch (e: IOException) {
        Result(false, "su не найден")
    }

    /** Commands that turn on full access: background mic, permissions, exact alarms, no battery limits. */
    fun grantCommands(ctx: Context): List<String> {
        val pkg = ctx.packageName
        return buildList {
            add("pm grant $pkg android.permission.RECORD_AUDIO")
            add("appops set $pkg RECORD_AUDIO allow")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("appops set $pkg SCHEDULE_EXACT_ALARM allow")
            add("dumpsys deviceidle whitelist +$pkg")
        }
    }

    /**
     * Checks su and grants full access. Returns a human-readable outcome for the UI and the log;
     * the grant is remembered so it can be re-applied after reboot.
     */
    fun grantAll(ctx: Context): Result {
        val id = run("id")
        if (!id.ok || "uid=0" !in id.output) {
            val manager = managerName(ctx)
            val hint = if (manager != null) {
                "Разрешите AutoVol в $manager → Суперпользователь и повторите."
            } else {
                "Root не найден."
            }
            AutoVol.log.add("root: недоступен (${id.output.take(80)})")
            return Result(false, hint)
        }
        val failed = grantCommands(ctx).mapNotNull { cmd ->
            val r = run(cmd)
            if (r.ok) null else "$cmd: ${r.output.take(80)}"
        }
        AutoVol.prefs.rootGranted = true
        val level = MicAccess.level(ctx)
        val msg = if (level == MicAccess.Level.FULL) "Полный доступ выдан через root." else "Root есть, но доступ: ${MicAccess.rawMode(ctx)}"
        AutoVol.log.add("root: $msg" + if (failed.isNotEmpty()) " Ошибки: ${failed.joinToString("; ")}" else "")
        return Result(level == MicAccess.Level.FULL, msg)
    }
}
