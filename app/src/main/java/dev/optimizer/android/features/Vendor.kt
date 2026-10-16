package dev.optimizer.android.features

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.optimizer.android.core.Sh

data class Bloat(val pkg: String, val what: String, val note: String = "")

enum class VendorGroup(val label: String) {
    XIAOMI("Xiaomi (MIUI / HyperOS)"), HUAWEI("Huawei / Honor (EMUI / HarmonyOS)"), OPPO("OPPO / Realme / OnePlus (ColorOS / Realme UI / OxygenOS)"),
    VIVO("vivo / iQOO (Funtouch / OriginOS)"), LENOVO("Lenovo (ZUI)"), BLACKVIEW("Blackview"),
    TABLET_OEM("Teclast / Chuwi / Alldocube"), GENERIC_SOC("Generic Allwinner / Rockchip / MediaTek ROM"), OTHER("Other / stock-like")
}

data class VendorInfo(val groups: List<VendorGroup>, val rom: String, val evidence: List<String>)

object Vendor {
    private fun prop(n: String) = Sh.user("getprop $n").out.trim()

    fun detect(): VendorInfo {
        val man = Build.MANUFACTURER.lowercase(); val brand = Build.BRAND.lowercase()
        val ev = mutableListOf<String>()
        val groups = linkedSetOf<VendorGroup>()
        var rom = "Android ${Build.VERSION.RELEASE}"
        fun has(s: String) = man.contains(s) || brand.contains(s)

        val miui = prop("ro.miui.ui.version.name"); val hyper = prop("ro.mi.os.version.name")
        if (miui.isNotEmpty() || hyper.isNotEmpty() || has("xiaomi") || has("redmi") || has("poco")) {
            groups += VendorGroup.XIAOMI
            rom = if (hyper.isNotEmpty()) "HyperOS $hyper" else if (miui.isNotEmpty()) "MIUI $miui" else "Xiaomi ROM"
            ev += "ro.miui.ui.version.name='$miui' ro.mi.os.version.name='$hyper' manufacturer=${Build.MANUFACTURER}"
        }
        val emui = prop("ro.build.version.emui")
        val harmony = runCatching {
            Class.forName("com.huawei.system.BuildEx").getMethod("getOsBrand").invoke(null)?.toString()
        }.getOrNull()
        if (emui.isNotEmpty() || has("huawei") || has("honor") || harmony != null) {
            groups += VendorGroup.HUAWEI
            rom = if (harmony.equals("harmony", true)) "HarmonyOS" else if (emui.isNotEmpty()) emui else "Huawei/Honor ROM"
            ev += "ro.build.version.emui='$emui' BuildEx.getOsBrand='$harmony'"
        }
        val opporom = prop("ro.build.version.opporom").ifEmpty { prop("ro.build.version.oplusrom") }
        val oneplus = prop("ro.build.version.oneplusrom")
        val display = Build.DISPLAY
        val realme = has("realme") || display.contains("realme", true)
        if (opporom.isNotEmpty() || oneplus.isNotEmpty() || has("oppo") || has("oneplus") || realme) {
            groups += VendorGroup.OPPO
            rom = when { realme -> "Realme UI"; oneplus.isNotEmpty() || has("oneplus") -> "OxygenOS / ColorOS (OnePlus)"; else -> "ColorOS $opporom" }
            ev += "opporom/oplusrom='$opporom' oneplusrom='$oneplus' display.id='$display'"
        }
        val vivo = prop("ro.vivo.os.version"); val vivoName = prop("ro.vivo.os.name")
        if (vivo.isNotEmpty() || has("vivo") || has("iqoo")) {
            groups += VendorGroup.VIVO; rom = "${vivoName.ifEmpty { "Funtouch/OriginOS" }} $vivo".trim()
            ev += "ro.vivo.os.version='$vivo' name='$vivoName'"
        }
        val zui = prop("ro.zui.version")
        if (zui.isNotEmpty() || has("lenovo")) { groups += VendorGroup.LENOVO; rom = "ZUI $zui".trim(); ev += "ro.zui.version='$zui'" }
        if (has("blackview")) { groups += VendorGroup.BLACKVIEW; ev += "manufacturer/brand Blackview" }
        if (has("teclast") || has("chuwi") || has("alldocube")) { groups += VendorGroup.TABLET_OEM; ev += "manufacturer/brand ${Build.MANUFACTURER}" }
        val plat = (prop("ro.board.platform") + " " + Build.HARDWARE).lowercase()
        if (Regex("(^|\\W)(rk\\d|rockchip)|sun\\d+i|allwinner|\\bmt\\d{4}|mediatek").containsMatchIn(plat)) {
            groups += VendorGroup.GENERIC_SOC; ev += "platform/hardware='${plat.trim()}'"
        }
        if (groups.isEmpty()) groups += VendorGroup.OTHER
        return VendorInfo(groups.toList(), rom, ev)
    }

    /**
     * Debloat candidates. Every package listed here is listed as "Recommended" removal (ads/telemetry/redundant vendor service)
     * in the Universal Android Debloater list (github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)
     * and was cross-checked against it when this app was written. Only packages actually installed on the device are shown.
     * They are DISABLED for user 0 (never uninstalled) and reversible.
     */
    val candidates: Map<VendorGroup, List<Bloat>> = mapOf(
        VendorGroup.XIAOMI to listOf(
            Bloat("com.miui.analytics", "Xiaomi Analytics (telemetry)"),
            Bloat("com.miui.msa.global", "MSA - system ads / user-behaviour analysis"),
            Bloat("com.miui.systemAdSolution", "System ad solution"),
            Bloat("com.xiaomi.mipicks", "GetApps / Mi Picks store (ads, app suggestions)"),
            Bloat("com.miui.bugreport", "Mi Feedback / bug report"),
            Bloat("com.miui.daemon", "MIUI daemon (data collection)", "May be needed for some MIUI features - verify"),
            Bloat("com.miui.miservice", "Services & feedback"),
            Bloat("com.miui.hybrid", "Quick Apps (ads/tracking)"),
            Bloat("com.miui.hybrid.accessory", "Quick Apps accessory"),
            Bloat("com.xiaomi.midrop", "ShareMe / Mi Drop"),
            Bloat("com.mi.globalbrowser", "Mi Browser"),
            Bloat("com.miui.yellowpage", "Yellow Pages (phone number lookup)"),
            Bloat("com.miui.android.fashiongallery", "Wallpaper Carousel (ads)"),
            Bloat("com.facebook.system", "Facebook app installer stub"),
            Bloat("com.facebook.appmanager", "Facebook app manager"),
            Bloat("com.facebook.services", "Facebook services"),
        ),
        VendorGroup.HUAWEI to listOf(
            Bloat("com.huawei.bd", "HwUE user-experience telemetry"),
            Bloat("com.huawei.hiview", "HiView diagnostics (reports data)"),
            Bloat("com.huawei.hicloud", "Huawei Cloud features"),
            Bloat("com.huawei.himovie.overseas", "Huawei Video (tracking)"),
            Bloat("com.huawei.music", "Huawei Music"),
            Bloat("com.huawei.android.tips", "Feature Advisor notifications"),
            Bloat("com.huawei.tips", "Feature Advisor notifications"),
            Bloat("com.huawei.hwdetectrepair", "Smart diagnosis"),
            Bloat("com.huawei.skytone", "SkyTone data service"),
            Bloat("com.huawei.pcassistant", "HiSuite PC assistant service"),
            Bloat("com.huawei.hiai", "HiAI background service", "Used by camera/AI features on some models - verify"),
            Bloat("com.huawei.android.pushagent", "Huawei push agent", "HMS-based push; apps relying on HMS push stop receiving - verify"),
        ),
        VendorGroup.OPPO to listOf(
            Bloat("com.oplus.statistics.rom", "User Experience Program (telemetry)"),
            Bloat("com.nearme.statistics.rom", "User Experience Program (telemetry, older ColorOS)"),
            Bloat("com.opos.ads", "OPPO ads / click tracking"),
            Bloat("com.heytap.mcs", "HeyTap messaging service with trackers", "Push for some OPPO apps - verify"),
            Bloat("com.heytap.cloud", "HeyTap Cloud"),
            Bloat("com.heytap.market", "HeyTap / OPPO app store"),
            Bloat("com.oppo.market", "OPPO App Market"),
            Bloat("com.heytap.browser", "HeyTap Browser"),
            Bloat("com.heytap.themestore", "Theme Store"),
            Bloat("com.heytap.yoli", "HeyTap video (tracking)"),
            Bloat("com.heytap.music", "HeyTap Music"),
            Bloat("com.heytap.habit.analysis", "Habit analysis"),
            Bloat("com.coloros.sceneservice", "Data Services Platform (Breeno)"),
            Bloat("com.coloros.healthcheck", "Health check"),
            Bloat("com.coloros.operationManual", "Help & feedback"),
            Bloat("com.oppo.quicksearchbox", "Quick search box"),
            Bloat("com.nearme.gamecenter", "Game Center"),
            Bloat("com.realme.securitycheck", "Realme security check (ads)"),
            Bloat("com.realme.link", "Realme Link"),
            Bloat("com.oneplus.card", "OnePlus Card widget package"),
            Bloat("com.oneplus.membership", "Red Cable Club"),
        ),
        VendorGroup.VIVO to listOf(
            Bloat("com.vivo.browser", "vivo Browser (trackers)"),
            Bloat("com.vivo.appstore", "V-Appstore"),
            Bloat("com.vivo.easyshare", "EasyShare"),
            Bloat("com.vivo.weather", "vivo Weather (trackers)"),
            Bloat("com.vivo.space", "vivo Space"),
            Bloat("com.vivo.globalsearch", "Global Search"),
            Bloat("com.vivo.magazine", "Lockscreen poster service (ads/tracking)"),
            Bloat("com.vivo.wallet", "vivo Wallet"),
            Bloat("com.vivo.devicereg", "Device registration (debug)"),
            Bloat("com.vivo.pushservice", "Push notification engine", "Needs Google services for pushes on Google ROMs - verify"),
            Bloat("com.vivo.vms", "vms framework (unused)"),
            Bloat("com.vivo.assistant", "vivo assistant"),
            Bloat("com.bbk.theme", "Theme store"),
            Bloat("com.iqoo.engineermode", "Factory test app"),
            Bloat("com.vivo.doubletimezoneclock", "Dual time-zone widget"),
        ),
        VendorGroup.LENOVO to listOf(
            Bloat("com.zui.browser", "Lenovo Browser (trackers)"),
            Bloat("com.lenovo.leos.appstore", "Lenovo Application Center"),
        ),
        VendorGroup.BLACKVIEW to listOf(
            Bloat("com.blackview.userfeedback", "User feedback (requires privacy policy)"),
            Bloat("com.blackview.app.cloudfolder", "Recommended-apps widget (adware)"),
            Bloat("com.blackview.artorial.client", "SmartArt wallpapers/news"),
            Bloat("com.blackview.dokegamecenter", "DK Game Center"),
            Bloat("com.blackview.surfline", "Surfline browser (ads)"),
            Bloat("com.blackview.useguide", "User's manual"),
        ),
    )

    fun candidatesFor(info: VendorInfo): List<Pair<VendorGroup, List<Bloat>>> =
        info.groups.mapNotNull { g -> candidates[g]?.let { g to it } }

    // ---- autostart / battery killer settings screens (best effort; component names vary by ROM version) ----
    data class Screen(val label: String, val component: ComponentName)

    private val screens: Map<VendorGroup, List<Screen>> = mapOf(
        VendorGroup.XIAOMI to listOf(
            Screen("Autostart manager", ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Screen("Battery saver per app", ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
        ),
        VendorGroup.HUAWEI to listOf(
            Screen("Startup manager", ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Screen("Protected apps", ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        ),
        VendorGroup.OPPO to listOf(
            Screen("Startup manager (ColorOS)", ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Screen("Startup manager (older OPPO)", ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Screen("Chain launch (OnePlus)", ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
        ),
        VendorGroup.VIVO to listOf(
            Screen("Background startup manager", ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Screen("Whitelist (iQOO Secure)", ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        ),
        VendorGroup.LENOVO to listOf(
            Screen("Background management", ComponentName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity")),
        ),
    )

    fun autostartIntents(ctx: Context, info: VendorInfo): List<Pair<String, Intent>> {
        val out = mutableListOf<Pair<String, Intent>>()
        for (g in info.groups) for (s in screens[g].orEmpty()) {
            val i = Intent().setComponent(s.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx.packageManager.resolveActivity(i, 0) != null) out += s.label to i
        }
        return out
    }

    val notes: Map<VendorGroup, List<String>> = mapOf(
        VendorGroup.XIAOMI to listOf(
            "MIUI optimization (Developer options) replaces parts of Android's process/permission handling; turning it off can reduce vendor background killing but also breaks some MIUI features. This app does NOT toggle it - decide yourself.",
            "Memory extension (Settings > Additional settings > Memory extension) uses storage as swap on supported models. Availability varies; this app only reports, it does not change it.",
            "Set each important app to 'No restrictions' under App info > Battery saver, enable Autostart, and lock the app in Recents (long-press its card).",
        ),
        VendorGroup.HUAWEI to listOf(
            "Disable 'Manage automatically' in App launch settings and enable Auto-launch, Secondary launch and Run in background for important apps.",
            "Huawei devices without Google services (HMS) may not run the Play Store even after installing the packages.",
        ),
        VendorGroup.OPPO to listOf(
            "Allow 'Auto launch' and 'Allow background activity' per app in App info > Battery usage; lock apps in Recents.",
            "ColorOS 'App freeze' / Athena may kill apps aggressively (com.coloros.athena / com.oplus.athena are NOT offered for disabling because they are system-critical on some builds).",
        ),
        VendorGroup.VIVO to listOf(
            "Allow high background power consumption and Autostart for important apps (i Manager / iQOO Secure).",
        ),
        VendorGroup.LENOVO to listOf("Use the background-management screen below and exempt important apps."),
        VendorGroup.TABLET_OEM to listOf("No verified debloat package list is bundled for these brands. Use the Assessment tab to review apps and the Monitor tab to close/freeze them."),
        VendorGroup.GENERIC_SOC to listOf("Generic Allwinner / Rockchip / MediaTek ROMs have no common package list; use the Assessment tab."),
    )
}
