package dev.optimizer.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer release of this app on GitHub. */
data class AppUpdateInfo(val version: String, val apkUrl: String, val size: Long, val pageUrl: String)

object AppVersions {
    /** "v1.0.10-debug" -> [1, 0, 10]. */
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parse(remote); val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

object AppReleaseParser {
    /** Parses GitHub's releases/latest JSON; null if it has no APK matching [assetRegex] or is a draft/prerelease. */
    fun parse(json: String, assetRegex: Regex): AppUpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (assetRegex.matches(a.optString("name"))) {
                val url = a.optString("browser_download_url")
                if (!url.startsWith("https://")) continue
                return AppUpdateInfo(o.getString("tag_name").removePrefix("v"), url, a.optLong("size", -1), o.optString("html_url"))
            }
        }
        return null
    }
}

/** Checks this app's own GitHub releases, downloads the newer APK and hands it to the system installer (user always confirms). */
class AppUpdater(private val ctx: Context, private val repo: String, private val assetRegex: Regex) {
    private val prefs = ctx.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val dir get() = File(ctx.cacheDir, "updates").apply { mkdirs() }

    @Suppress("DEPRECATION")
    private val pkgInfo get() = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val currentVersion: String get() = pkgInfo.versionName ?: "0"
    private val currentCode: Long get() = if (Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()) = now - prefs.getLong("last_check", 0L) >= DAY_MS
    fun lastCheck(): Long = prefs.getLong("last_check", 0L)

    /** Network check; returns the newer release, null if up to date, throws if the check itself failed. */
    suspend fun check(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "${ctx.packageName}/$currentVersion")
        val info = try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            AppReleaseParser.parse(c.inputStream.bufferedReader().readText(), assetRegex)
        } finally { c.disconnect() }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        info?.takeIf { AppVersions.isNewer(it.version, currentVersion) }
    }

    private fun apkFor(i: AppUpdateInfo) = File(dir, "update-${i.version.replace(Regex("[^\\w.-]"), "_")}.apk")

    fun isDownloaded(i: AppUpdateInfo) = apkFor(i).let { it.exists() && validate(it, i) }

    suspend fun download(i: AppUpdateInfo, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = apkFor(i)
        if (target.exists() && validate(target, i)) { onProgress(100); return@withContext true }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, target.name + ".part")
        val c = URL(i.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "${ctx.packageName}/$currentVersion")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: i.size
            var done = 0L; var last = -1
            c.inputStream.use { input -> part.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            } }
        } finally { c.disconnect() }
        if (!part.renameTo(target)) { part.delete(); return@withContext false }
        if (!validate(target, i)) { target.delete(); return@withContext false }
        true
    }

    /** Must be our package, a higher versionCode than installed, and the expected size when known. */
    private fun validate(f: File, i: AppUpdateInfo): Boolean {
        if (i.size > 0 && f.length() != i.size) return false
        val pi = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) ?: return false
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        return pi.packageName == ctx.packageName && code > currentCode
    }

    fun canInstall() = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun launchInstaller(i: AppUpdateInfo): Boolean {
        val f = apkFor(i); if (!f.exists()) return false
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
    }

    companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val version: String) : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
    data class Downloading(val info: AppUpdateInfo, val pct: Int) : AppUpdateState
    data class Ready(val info: AppUpdateInfo) : AppUpdateState
    data class NeedPermission(val info: AppUpdateInfo) : AppUpdateState
    data class Failed(val msg: String, val info: AppUpdateInfo? = null) : AppUpdateState
}

/** Compose-side state holder: Settings button, once-a-day silent check on open, download + install hand-off. */
class AppUpdateController(val updater: AppUpdater, private val scope: CoroutineScope) {
    var state by mutableStateOf<AppUpdateState>(AppUpdateState.Idle)
        private set

    /** True when a newer version has been found (for badges/banners). */
    val hasUpdate get() = state.let { it is AppUpdateState.Available || it is AppUpdateState.Ready || it is AppUpdateState.NeedPermission || it is AppUpdateState.Downloading || (it is AppUpdateState.Failed && it.info != null) }

    fun autoCheck() { if (updater.shouldAutoCheck()) check(silent = true) }

    fun check(silent: Boolean = false) {
        if (state is AppUpdateState.Checking || state is AppUpdateState.Downloading) return
        if (!silent) state = AppUpdateState.Checking
        scope.launch {
            try {
                val n = updater.check()
                state = if (n != null) AppUpdateState.Available(n) else if (silent) AppUpdateState.Idle else AppUpdateState.UpToDate(updater.currentVersion)
            } catch (e: Exception) { if (!silent) state = AppUpdateState.Failed("Couldn't check for updates (${e.message ?: "network error"}).") else state = AppUpdateState.Idle }
        }
    }

    fun download(i: AppUpdateInfo) {
        state = AppUpdateState.Downloading(i, 0)
        scope.launch {
            val ok = try { updater.download(i) { p -> state = AppUpdateState.Downloading(i, p) } } catch (e: Exception) { false }
            if (ok) install(i) else state = AppUpdateState.Failed("The download didn't finish. Tap to try again.", i)
        }
    }

    fun install(i: AppUpdateInfo) {
        if (!updater.canInstall()) { state = AppUpdateState.NeedPermission(i); updater.openInstallPermissionSettings(); return }
        state = if (updater.launchInstaller(i)) AppUpdateState.Ready(i) else AppUpdateState.Failed("Couldn't open the installer.", i)
    }

    /** Back from the "Install unknown apps" screen: continue if it was granted. */
    fun onResume() { (state as? AppUpdateState.NeedPermission)?.let { if (updater.canInstall()) install(it.info) } }

    fun onTap() {
        when (val s = state) {
            is AppUpdateState.Available -> if (updater.isDownloaded(s.info)) install(s.info) else download(s.info)
            is AppUpdateState.Ready -> install(s.info)
            is AppUpdateState.NeedPermission -> install(s.info)
            is AppUpdateState.Failed -> if (s.info != null) download(s.info) else check()
            is AppUpdateState.Downloading, AppUpdateState.Checking -> {}
            else -> check()
        }
    }
}

@Composable
fun rememberAppUpdateController(updater: AppUpdater): AppUpdateController {
    val scope = rememberCoroutineScope()
    return remember(updater) { AppUpdateController(updater, scope) }
}

/** "App updates" card for the Settings screen. */
@Composable
fun AppUpdateCard(c: AppUpdateController, modifier: Modifier = Modifier) {
    val s = c.state
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Installed version ${c.updater.currentVersion}", fontWeight = FontWeight.Medium)
            Text(when (s) {
                AppUpdateState.Idle -> "Checks the latest GitHub release of this app, once a day and whenever you tap the button. Your data is kept."
                AppUpdateState.Checking -> "Checking GitHub…"
                is AppUpdateState.UpToDate -> "You're up to date."
                is AppUpdateState.Available -> "Update ${s.info.version} is available."
                is AppUpdateState.Downloading -> "Downloading ${s.info.version}… ${s.pct}%"
                is AppUpdateState.Ready -> "Update ${s.info.version} is ready. Confirm in the Android installer (tap again if it didn't open)."
                is AppUpdateState.NeedPermission -> "Turn on \"Allow from this source\" in the settings screen that opened, then come back."
                is AppUpdateState.Failed -> s.msg
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s is AppUpdateState.Downloading) LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { c.onTap() }, enabled = s !is AppUpdateState.Checking && s !is AppUpdateState.Downloading) {
                Text(when (s) {
                    AppUpdateState.Checking -> "Checking…"
                    is AppUpdateState.Available -> "Download & install ${s.info.version}"
                    is AppUpdateState.Downloading -> "Downloading…"
                    is AppUpdateState.Ready -> "Install update ${s.info.version}"
                    is AppUpdateState.NeedPermission -> "Continue"
                    is AppUpdateState.Failed -> if (s.info != null) "Try again" else "Check for updates"
                    else -> "Check for updates"
                })
            }
        }
    }
}

/** Process-wide updater for this edition (rooted / standard each update from their own release asset). */
object AppUpdates {
    private var ctl: AppUpdateController? = null
    fun get(ctx: Context): AppUpdateController = ctl ?: AppUpdateController(
        AppUpdater(ctx.applicationContext, "geek4945-creator/android-optimizer", Regex("^AndroidOptimizer-${BuildConfig.EDITION}-release\\.apk$")),
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ).also { ctl = it }
}
