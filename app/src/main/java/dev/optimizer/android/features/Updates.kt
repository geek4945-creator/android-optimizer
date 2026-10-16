package dev.optimizer.android.features

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class StaleApp(
    val pkg: String, val label: String, val version: String, val lastUpdate: Long, val ageDays: Long,
    val targetSdk: Int, val installer: String?, val reasons: List<String>,
)

data class OtaPkg(val pkg: String, val label: String, val warning: String)

/** Updates section (E). Android does not expose "an update is available" to other apps; staleness is a heuristic. */
object Updates {
    const val STALE_DAYS = 365L

    fun installerOf(ctx: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
    }.getOrNull()

    /** User-installed apps that look outdated: not updated for > 1 year or targeting an old SDK. Sorted by age. */
    fun staleApps(ctx: Context): List<StaleApp> {
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        return pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || pi.packageName == ctx.packageName) return@mapNotNull null
            val age = (now - pi.lastUpdateTime) / 86_400_000L
            val reasons = mutableListOf<String>()
            if (age > STALE_DAYS) reasons += "not updated for $age days"
            if (ai.targetSdkVersion < Build.VERSION.SDK_INT - 4) reasons += "targets API ${ai.targetSdkVersion} (device API ${Build.VERSION.SDK_INT})"
            if (reasons.isEmpty()) return@mapNotNull null
            StaleApp(pi.packageName, pm.getApplicationLabel(ai).toString(), pi.versionName ?: "?", pi.lastUpdateTime, age,
                ai.targetSdkVersion, installerOf(ctx, pi.packageName), reasons)
        }.sortedByDescending { it.ageDays }
    }

    fun playIntent(pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun playWebIntent(pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openPlay(ctx: Context, pkg: String) {
        val i = playIntent(pkg)
        val target = if (i.resolveActivity(ctx.packageManager) != null) i else playWebIntent(pkg)
        runCatching { ctx.startActivity(target) }
    }

    // ---- system update helper ----
    data class SysInfo(val android: String, val patch: String, val patchAgeDays: Long?, val build: String)

    fun sysInfo(): SysInfo {
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(Build.VERSION.SECURITY_PATCH), LocalDate.now()) }.getOrNull()
        return SysInfo("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Build.VERSION.SECURITY_PATCH, age, Build.DISPLAY)
    }

    private val updaterLaunch = listOf("com.android.updater", "com.huawei.android.hwouc", "com.wssyncmldm", "com.oplus.ota", "com.oppo.ota", "com.zui.ota")

    fun systemUpdateIntent() = Intent("android.settings.SYSTEM_UPDATE_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun vendorUpdaters(ctx: Context): List<Pair<String, Intent>> = updaterLaunch.mapNotNull { p ->
        ctx.packageManager.getLaunchIntentForPackage(p)?.let { (runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)) + " ($p)" to it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    // ---- reversible OTA disable (root) ----
    fun autoOtaDisabled(ctx: Context): Boolean = runCatching { Settings.Global.getInt(ctx.contentResolver, "ota_disable_automatic_update", 0) == 1 }.getOrDefault(false)

    fun setAutoOta(disable: Boolean): Op {
        if (!Optimizer.unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        if (!disable) return Optimizer.undoFix("ota:auto")
        val old = Optimizer.getSetting("global", "ota_disable_automatic_update")
        val r = Sh.root("settings put global ota_disable_automatic_update 1")
        if (!r.ok) return Op(false, r.text)
        ChangeLog.add("update", "ota:auto", "Disable automatic OTA download", "Settings.Global ota_disable_automatic_update=1 (was ${old ?: "unset"}). Whether the vendor updater honours it depends on the ROM.",
            listOf(Optimizer.restoreSetting("global", "ota_disable_automatic_update", old)))
        return Op(true, "Automatic OTA download flag set. Manual updates still work.")
    }

    /**
     * Vendor updater packages that can be disabled. com.android.updater (Xiaomi) and com.coloros.sau are deliberately NOT offered:
     * the Universal Android Debloater list warns that removing them bootloops some devices.
     */
    val otaPackages = listOf(
        OtaPkg("com.huawei.android.hwouc", "Huawei OTA updater", "Expert: you will not receive system updates until re-enabled."),
        OtaPkg("com.wssyncmldm", "Samsung software update", "Expert: works with other Samsung update components; system updates stop."),
        OtaPkg("com.oplus.ota", "OPLUS/ColorOS software update", "Expert: system updates stop."),
        OtaPkg("com.oppo.ota", "OPPO software update", "Expert: system updates stop."),
        OtaPkg("com.zui.ota", "Lenovo ZUI system update", "Expert: system updates stop."),
        OtaPkg("com.adups.fota", "Adups FOTA 'Wireless Update'", "Expert: generic-ROM updater with a history of privacy issues; verify before disabling."),
    )

    fun installedOta(ctx: Context) = otaPackages.filter { AppControl.isInstalled(ctx, it.pkg) }

    fun disableUpdater(ctx: Context, o: OtaPkg): Op {
        if (!Optimizer.unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        Protected.reasons(ctx, o.pkg)?.let { return Op(false, "Protected: $it") }
        val r = Sh.root("pm disable-user --user 0 ${Sh.q(o.pkg)}")
        if (!r.ok || r.text.contains("Exception")) return Op(false, r.text)
        ChangeLog.add("update", "ota:pkg:${o.pkg}", "Disabled updater ${o.pkg}", o.warning, listOf("pm enable ${Sh.q(o.pkg)}"))
        return Op(true, "Disabled ${o.pkg}")
    }

    fun enableUpdater(o: OtaPkg): Op = Optimizer.undoFix("ota:pkg:${o.pkg}")

    // ---- cache repair ----
    val repairTargets = listOf(
        "com.android.vending" to "Google Play Store", "com.google.android.gms" to "Google Play services", "com.google.android.gsf" to "Google Services Framework",
    )

    fun updaterCacheTargets(ctx: Context): List<String> =
        (updaterLaunch + "com.coloros.sau").filter { AppControl.isInstalled(ctx, it) }

    /** Root: force-stop and delete the cache directories only (internal + external). App data and accounts are untouched. */
    fun clearCache(pkg: String): Op {
        if (!Root.isRooted) return Op(false, "Root required (non-root: open the app's Storage page and tap Clear cache).")
        val q = Sh.q(pkg)
        val r = Sh.root("am force-stop $q; rm -rf /data/user/0/$pkg/cache/* /data/user/0/$pkg/code_cache/* /sdcard/Android/data/$pkg/cache/* 2>&1; echo done", 60)
        ChangeLog.add("update", "cache:$pkg", "Cleared cache of $pkg", "Cache dirs only (not undoable, rebuilt automatically)", emptyList())
        return Op(r.out.contains("done"), "Cache cleared for $pkg" + if (r.err.isNotBlank()) " (${r.err.take(120)})" else "")
    }

    /** Root: `pm clear` wipes ALL data of the app (settings, local state). Irreversible. */
    fun clearData(pkg: String): Op {
        if (!Root.isRooted) return Op(false, "Root required.")
        if (!Backups.hasBaseline()) return Op(false, "Create the baseline backup first.")
        val r = Sh.root("pm clear ${Sh.q(pkg)}", 60)
        ChangeLog.add("update", "clear:$pkg", "Cleared DATA of $pkg", "pm clear (not undoable)", emptyList())
        return Op(r.ok && r.text.contains("Success"), r.text.ifEmpty { "no output" })
    }

    fun appInfoIntent(pkg: String) = AppControl.settingsIntent(pkg)

    // ---- WebView ----
    fun webViewInfo(): String = runCatching {
        WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "none"
    }.getOrDefault("unreadable")

    fun webViewSettingsIntent() = Intent(Settings.ACTION_WEBVIEW_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("unused") val pmFlag = PackageManager.GET_META_DATA
}
