package dev.optimizer.android.features

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh

/**
 * Curated open-source launchers. Names, licenses and URLs were verified by web lookup (GitHub repo API, F-Droid and
 * IzzyOnDroid pages) when this app was written. Niagara Launcher is deliberately NOT listed: it is closed source.
 */
data class LauncherEntry(
    val name: String, val pkgs: List<String>, val description: String, val license: String,
    val fdroidUrl: String?, val fdroidLabel: String, val githubUrl: String, val extraUrl: String? = null, val extraLabel: String = "", val note: String = "",
)

object Launchers {
    val list = listOf(
        LauncherEntry("Lawnchair", listOf("app.lawnchair", "app.lawnchair.debug"),
            "Customisable launcher based on AOSP Launcher3 with Pixel Launcher features (Material You theming, icon packs, gestures).",
            "Apache-2.0 (current releases; older 'alpha' branch was GPL-3.0)",
            "https://apt.izzysoft.de/fdroid/index/apk/app.lawnchair", "IzzyOnDroid (F-Droid repo)",
            "https://github.com/LawnchairLauncher/lawnchair",
            note = "Not in the main F-Droid repo; available via the IzzyOnDroid repo or GitHub releases."),
        LauncherEntry("Neo Launcher", listOf("com.saggitt.omega", "com.saggitt.omega.neo", "com.saggitt.omega.debug"),
            "Fork of Lawnchair/Omega with extra customisation, themed icons and backup support.",
            "GPL-3.0",
            "https://apt.izzysoft.de/fdroid/index/apk/com.saggitt.omega", "IzzyOnDroid (F-Droid repo)",
            "https://github.com/NeoApplications/Neo-Launcher",
            note = "Distributed via IzzyOnDroid and GitHub releases."),
        LauncherEntry("Kvaesitso", listOf("de.mm20.launcher2.release", "de.mm20.launcher2.nightly", "de.mm20.launcher2.debug"),
            "Search-focused launcher: global search for apps, contacts, files and web, plus widgets and customisable layout.",
            "GPL-3.0-or-later",
            "https://f-droid.org/packages/de.mm20.launcher2.release/", "F-Droid",
            "https://github.com/MM2-0/Kvaesitso",
            note = "F-Droid flags the NonFreeNet anti-feature (optional online services such as weather/search providers)."),
        LauncherEntry("Olauncher", listOf("app.olauncher"),
            "Minimal text-only home screen aimed at less screen time; no ads, no accounts.",
            "GPL-3.0",
            "https://f-droid.org/packages/app.olauncher/", "F-Droid",
            "https://github.com/tanujnotes/Olauncher"),
        LauncherEntry("Fossify Launcher", listOf("org.fossify.home"),
            "Simple, ad-free home launcher from the Fossify project (maintained successor of Simple Mobile Tools launcher).",
            "GPL-3.0",
            "https://f-droid.org/packages/org.fossify.home/", "F-Droid",
            "https://github.com/FossifyOrg/Launcher"),
        LauncherEntry("Trebuchet (LineageOS)", listOf("org.lineageos.trebuchet"),
            "LineageOS's AOSP Launcher3 variant. Normally ships inside LineageOS builds rather than as a standalone download.",
            "Apache-2.0 (AOSP Launcher3 base)",
            null, "",
            "https://github.com/LineageOS/android_packages_apps_Trebuchet",
            extraUrl = "https://android.googlesource.com/platform/packages/apps/Launcher3", extraLabel = "AOSP Launcher3 source",
            note = "Not on F-Droid; only useful if your ROM provides it. AOSP Launcher3 itself is the base of Lawnchair/Neo."),
    )

    fun installedPkg(ctx: Context, e: LauncherEntry): String? = e.pkgs.firstOrNull { Installed.has(ctx, it) }

    fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** Package of the currently preferred home app (what the Home button opens), or null if no preference resolves. */
    fun defaultHome(ctx: Context): String? = runCatching {
        ctx.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" } // "android" = the ResolverActivity chooser, i.e. no default chosen

    data class HomeApp(val pkg: String, val label: String, val system: Boolean, val enabled: Boolean)

    fun homeApps(ctx: Context): List<HomeApp> {
        val pm = ctx.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val ris = pm.queryIntentActivities(homeIntent(), flags)
        return ris.map { it.activityInfo.packageName }.distinct().mapNotNull { p ->
            runCatching {
                val ai = pm.getApplicationInfo(p, 0)
                HomeApp(p, pm.getApplicationLabel(ai).toString(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, ai.enabled)
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    fun homeSettingsIntent() = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun urlIntent(u: String) = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Why disabling `target` is not allowed right now, or null when safe. */
    fun disableBlocker(ctx: Context, target: HomeApp): String? {
        if (!Root.isRooted) return "Root is required."
        if (!Backups.hasBaseline()) return "Create the baseline backup first."
        if (!target.system) return "Only pre-installed (system) launchers can be disabled."
        if (target.pkg == ctx.packageName) return "That is this app."
        val def = defaultHome(ctx) ?: return "No default home app is set. Install another launcher and set it as default first."
        if (def == target.pkg) return "This launcher is the current default. Set a different launcher as default first."
        val others = homeApps(ctx).filter { it.pkg != target.pkg && it.enabled }
        if (others.none { it.pkg == def }) return "The current default launcher is not an enabled home app."
        if (others.isEmpty()) return "No other enabled home app would remain."
        if (Protected.Resources_recentsPackage() == target.pkg)
            return "This launcher provides the system Recents screen (gesture navigation). Disabling it would break navigation, so it is blocked."
        return null
    }

    fun disableStock(ctx: Context, target: HomeApp): Op2 {
        disableBlocker(ctx, target)?.let { return Op2(false, it) }
        val undo = "pm enable ${Sh.q(target.pkg)}"
        val r = Sh.root("pm disable-user --user 0 ${Sh.q(target.pkg)}")
        if (!r.ok || r.text.contains("Exception")) return Op2(false, r.text)
        // safety net: a home app must still resolve; if not, revert immediately
        val after = defaultHome(ctx)
        val ok = after != null && after != target.pkg && homeApps(ctx).any { it.pkg == after && it.enabled }
        if (!ok) {
            Sh.root(undo)
            return Op2(false, "Safety check failed (no usable home app after disabling) - change reverted automatically.")
        }
        ChangeLog.add("launcher", "launcher:${target.pkg}", "Disabled stock launcher ${target.label}", "${target.pkg} disabled; default home is $after", listOf(undo))
        return Op2(true, "Disabled ${target.label}. Default home: $after")
    }

    data class Op2(val ok: Boolean, val message: String)

    object Installed { fun has(ctx: Context, p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }
}
