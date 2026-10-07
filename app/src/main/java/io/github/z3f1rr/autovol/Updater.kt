package io.github.z3f1rr.autovol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.z3f1rr.autovol.core.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases (the only network access of the app). The APK is installed with
 * the system installer (user confirms), or silently via root when root is set up.
 */
object Updater {
    const val REPO = "Z3F1RR/autovol-app"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val DAY_MS = 24 * 3600_000L

    data class Release(val version: String, val apkUrl: String, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val current: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data object Installing : State
        data class Failed(val res: Int, val arg: String? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun currentVersion(ctx: Context): String = BuildConfigInfo.versionName(ctx)

    /** Parses the GitHub "latest release" JSON; null when it has no APK asset. */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(
                    version = o.getString("tag_name").removePrefix("v"),
                    apkUrl = a.getString("browser_download_url"),
                    size = a.optLong("size"),
                    notes = o.optString("body").take(1500),
                )
            }
        }
        return null
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "AutoVol-updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Automatic check when the app is opened: at most once a day, silent on errors. */
    suspend fun autoCheck(ctx: Context) {
        val prefs = AutoVol.prefs
        if (!prefs.updateAutoCheck.value || Build.FINGERPRINT == "robolectric") return
        if (System.currentTimeMillis() - prefs.lastUpdateCheckMs < DAY_MS) return
        check(ctx, quiet = true)
    }

    suspend fun check(ctx: Context, quiet: Boolean = false) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Checking
        val result = withContext(Dispatchers.IO) {
            try {
                val c = open(API)
                when (c.responseCode) {
                    200 -> {
                        val rel = parseRelease(c.inputStream.bufferedReader().readText())
                        AutoVol.prefs.lastUpdateCheckMs = System.currentTimeMillis()
                        when {
                            rel == null -> State.Failed(R.string.upd_err_no_apk)
                            Versions.isNewer(rel.version, currentVersion(ctx)) -> State.Available(rel)
                            else -> State.UpToDate(currentVersion(ctx))
                        }
                    }
                    404 -> {
                        AutoVol.prefs.lastUpdateCheckMs = System.currentTimeMillis()
                        State.Failed(R.string.upd_err_no_releases)
                    }
                    else -> State.Failed(R.string.upd_err_http, c.responseCode.toString())
                }
            } catch (e: IOException) {
                State.Failed(R.string.upd_err_network)
            } catch (e: JSONException) {
                State.Failed(R.string.upd_err_parse)
            }
        }
        _state.value = if (quiet && result is State.Failed) State.Idle else result
        if (result is State.Available) AutoVol.log.add("доступна новая версия ${result.release.version}")
    }

    suspend fun downloadAndInstall(ctx: Context, rel: Release) {
        val file = File(ctx.cacheDir, "update.apk")
        try {
            withContext(Dispatchers.IO) {
                val c = open(rel.apkUrl)
                c.setRequestProperty("Accept", "application/octet-stream")
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading(rel, done.toFloat() / total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            _state.value = State.Failed(R.string.upd_err_download)
            return
        }
        _state.value = State.Installing
        AutoVol.log.add("обновление ${rel.version}: установка")
        if (AutoVol.prefs.rootGranted) {
            val r = withContext(Dispatchers.IO) { Root.run("pm install -r '${file.path}'", timeoutSec = 120) }
            if (r.ok && "Success" in r.output) return // the app is replaced and restarted (MY_PACKAGE_REPLACED)
            AutoVol.log.add("установка через root не удалась (${r.output.take(120)}), пробую установщик")
        }
        withContext(Dispatchers.IO) { installWithSession(ctx, file) }
    }

    private fun installWithSession(ctx: Context, apk: File) {
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            _state.value = State.Failed(R.string.upd_err_installer, e.message)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> State.Failed(R.string.upd_err_cancelled)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                State.Failed(R.string.upd_err_signature)
            else -> State.Failed(R.string.upd_err_failed, message ?: status.toString())
        }
        if (status != PackageInstaller.STATUS_SUCCESS) AutoVol.log.add("обновление: ${message ?: status}")
    }
}

/** Results of the PackageInstaller session; asks the user to confirm when the system requires it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
