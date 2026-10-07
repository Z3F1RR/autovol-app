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

    /** su binary forced by tests; null = find it ([CANDIDATES]). */
    @Volatile
    internal var su: String? = null

    @Volatile
    private var resolved: String? = null

    /**
     * Where root managers expose su to apps: KernelSU / KernelSU Next / SukiSU and APatch hook
     * /system/bin/su for allowed apps, Magisk mounts it in /system/bin or /sbin (older) or
     * /debug_ramdisk. "su" first: whatever PATH finds.
     */
    private val CANDIDATES = listOf(
        "su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
    )

    /** Last attempt to re-apply the grant from a cycle; limits su calls. */
    @Volatile
    private var lastReapplyMs = 0L

    fun run(cmd: String, timeoutSec: Long = 15): Result {
        su?.let { return exec(it, cmd, timeoutSec) }
        resolved?.let { return exec(it, cmd, timeoutSec) }
        var last = Result(false, "su не найден")
        for (bin in CANDIDATES) {
            val r = exec(bin, cmd, timeoutSec)
            if (r.output != NOT_FOUND) {
                resolved = bin
                return r
            }
            last = r
        }
        return last.copy(output = "su не найден")
    }

    private const val NOT_FOUND = "\u0000not found"

    private fun exec(bin: String, cmd: String, timeoutSec: Long): Result = try {
        val p = ProcessBuilder(bin, "-c", cmd).redirectErrorStream(true).start()
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
        Result(false, NOT_FOUND)
    }

    /**
     * Background microphone. RECORD_AUDIO is a runtime-permission op: the permission service keeps a
     * per-UID mode ("foreground") that overrides the per-package mode, so the UID mode must be set.
     * The package mode is set too for older Android versions.
     */
    fun micCommands(ctx: Context): List<String> {
        val pkg = ctx.packageName
        return listOf("appops set --uid $pkg RECORD_AUDIO allow", "appops set $pkg RECORD_AUDIO allow")
    }

    /**
     * Commands that turn on full access. Every `pm grant` makes the permission service re-sync the
     * app's ops and reset RECORD_AUDIO back to "foreground", so the appops go last.
     */
    fun grantCommands(ctx: Context): List<String> {
        val pkg = ctx.packageName
        return buildList {
            add("pm grant $pkg android.permission.RECORD_AUDIO")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            add("dumpsys deviceidle whitelist +$pkg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("appops set $pkg SCHEDULE_EXACT_ALARM allow")
            addAll(micCommands(ctx))
        }
    }

    /**
     * Checks su and grants full access. Returns a human-readable outcome for the UI and the log;
     * the grant is remembered so it can be re-applied after reboot.
     */
    fun grantAll(ctx: Context): Result {
        // Magisk asks the user on the first call: give them time to answer.
        val id = run("id", timeoutSec = 60)
        if (!id.ok || "uid=0" !in id.output) {
            val manager = managerName(ctx)
            val hint = if (manager != null) {
                "Root не выдан: разрешите AutoVol в $manager → Суперпользователь и повторите."
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
        return report(ctx, "выдан", failed)
    }

    /**
     * The permission service puts RECORD_AUDIO back to "foreground" after reboot and after any
     * permission change. With root, quietly restore it (at most every 10 minutes). Worker thread only.
     */
    fun reapplyIfNeeded(ctx: Context, force: Boolean = false) {
        if (!AutoVol.prefs.rootGranted || MicAccess.level(ctx) == MicAccess.Level.FULL) return
        val now = System.currentTimeMillis()
        if (!force && now - lastReapplyMs < 10 * 60_000L) return
        lastReapplyMs = now
        val failed = micCommands(ctx).mapNotNull { cmd ->
            val r = run(cmd)
            if (r.ok) null else "$cmd: ${r.output.take(80)}"
        }
        report(ctx, "восстановлен (система сбросила режим)", failed)
    }

    private fun report(ctx: Context, what: String, failed: List<String>): Result {
        val full = MicAccess.level(ctx) == MicAccess.Level.FULL
        val msg = if (full) {
            "Полный доступ $what через root."
        } else {
            val pkg = ctx.packageName
            val uidMode = run("appops get --uid $pkg RECORD_AUDIO").output.take(120)
            val pkgMode = run("appops get $pkg RECORD_AUDIO").output.take(120)
            "Root есть, но доступ: ${MicAccess.rawMode(ctx)} (uid: $uidMode; пакет: $pkgMode)"
        }
        AutoVol.log.add("root: $msg" + if (failed.isNotEmpty()) " Ошибки: ${failed.joinToString("; ")}" else "")
        return Result(full, msg)
    }
}
