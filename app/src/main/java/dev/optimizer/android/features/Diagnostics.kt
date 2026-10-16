package dev.optimizer.android.features

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.optimizer.android.MainActivity
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import dev.optimizer.android.core.Compat
import java.util.concurrent.TimeUnit

enum class Sev(val label: String, val rank: Int) { HIGH("High", 0), MEDIUM("Medium", 1), LOW("Low", 2), INFO("Info", 3) }

/**
 * One selectable way to fix an issue. rootPlan builds the exact shell commands (and their undo) at preview time;
 * intent is the non-root fallback that deep-links into system settings; local is an in-app-only change.
 */
data class FixOpt(
    val id: String, val label: String, val recommended: Boolean = false, val note: String = "",
    val rootPlan: (() -> Plan)? = null, val intent: Intent? = null, val local: (() -> Unit)? = null,
)

data class Issue(val id: String, val title: String, val sev: Sev, val explanation: String, val evidence: List<String>, val options: List<FixOpt>, val score: Int = 0)

object Diagnostics {
    private fun intent(action: String) = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun secure(ctx: Context, key: String): String = Settings.Secure.getString(ctx.contentResolver, key) ?: ""

    /** On-demand or periodic scan. Everything is read from this device; checks needing root are skipped when `root` is false. */
    fun scan(ctx: Context, root: Boolean): List<Issue> {
        val out = mutableListOf<Issue>()
        val pm = ctx.packageManager
        fun add(i: Issue) { out += i }

        // 1. storage
        runCatching {
            val st = StatFs(Environment.getDataDirectory().path)
            val pct = st.availableBytes * 100 / st.totalBytes.coerceAtLeast(1)
            if (pct < 10 || st.availableBytes < 2L * 1024 * 1024 * 1024) add(Issue("storage", "Low free storage", if (pct < 5) Sev.HIGH else Sev.MEDIUM,
                "Android slows down, fails app updates and kills caches when internal storage is nearly full.",
                listOf("Free: ${Assessment.fmtBytes(st.availableBytes)} of ${Assessment.fmtBytes(st.totalBytes)} ($pct%)"),
                listOf(FixOpt("storage_trim", "Clear all app caches (pm trim-caches)", true, "Not undoable; caches are rebuilt.",
                    rootPlan = { Plan(listOf("pm trim-caches 999999999999"), emptyList()) }),
                    FixOpt("storage_settings", "Open storage settings", intent = intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)))))
        }
        // 2. RAM / memory pressure
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val pct = mi.availMem * 100 / mi.totalMem.coerceAtLeast(1)
            val rs = RamOptimizer.read()
            val swapPct = if (rs.swapTotalKb > 0) rs.swapUsedKb * 100 / rs.swapTotalKb else 0L
            val pressure = pct < 15 || mi.lowMemory || (rs.swapTotalKb > 0 && swapPct >= 70 && pct < 25)
            if (pressure) add(Issue("ram", "Memory pressure", if (mi.lowMemory || pct < 8) Sev.HIGH else Sev.MEDIUM,
                "Little available memory (or heavy swap use) forces Android to kill apps and reload them, which feels like lag. Note that cached apps count as reclaimable: only a low 'available' figure matters.",
                listOf("Available ${mi.availMem / 1048576} MB of ${mi.totalMem / 1048576} MB ($pct%)", "System low-memory flag: ${mi.lowMemory}",
                    if (rs.swapTotalKb > 0) "Swap/zRAM: ${rs.swapUsedKb / 1024} MB used of ${rs.swapTotalKb / 1024} MB ($swapPct%)" else "No swap/zRAM configured"),
                listOf(FixOpt("ram_gentle", "Gentle RAM optimize now (closes only cached/background apps; skips protected and 'Never stop' apps)", true, "Apps may restart later; effect is temporary.",
                    local = { val c = RamOptimizer.candidates(ctx, false); RamOptimizer.run(ctx, c.items, RamOptions(true, false, false, false, false), "diagnostics (gentle)") }),
                    FixOpt("ram_compact", "Compact memory (root; no apps are closed)", false, "Harmless; short CPU burst.",
                        rootPlan = { Plan(listOf("echo 1 > /proc/sys/vm/compact_memory"), emptyList()) }),
                    FixOpt("ram_cap", "Cap cached background processes at 16", false, "Medium risk; see Optimize tab.",
                        rootPlan = { Optimizer.fixes.first { it.id == "cached_cap" }.plan() }),
                    FixOpt("ram_devopts", "Open Developer options (Running services / background limit)", intent = intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
                    FixOpt("ram_monitor", "Review and close apps manually: Monitor > Close apps…"))))
        }
        // 3. animation scales
        runCatching {
            val w = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
            val t = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
            val a = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            if (w >= 1f || t >= 1f || a >= 1f) add(Issue("anim", "Animations at full length", Sev.LOW,
                "Default 1x animations make the UI feel slower than 0.5x on older hardware.",
                listOf("window=$w transition=$t animator=$a"),
                listOf(FixOpt("anim_half", "Set animation scales to 0.5x", true, rootPlan = { Optimizer.animPlan("0.5") }),
                    FixOpt("anim_dev", "Open Developer options", intent = intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))))
        }
        // 4. security patch age
        runCatching {
            val age = Compat.patchAgeDays() ?: return@runCatching
            if (age > 365) add(Issue("patch", "Outdated security patch", if (age > 730) Sev.HIGH else Sev.MEDIUM,
                "The device has not received security fixes for a long time. Only a vendor/ROM update can fix this.",
                listOf("Patch level ${Compat.securityPatch()} ($age days old)", "Build ${Build.DISPLAY}"),
                listOf(FixOpt("patch_update", "Open system update", intent = intent("android.settings.SYSTEM_UPDATE_SETTINGS")))))
        }
        // 5. SELinux
        val se = Sh.user("getenforce").out.trim()
        if (se.equals("Permissive", true)) add(Issue("selinux", "SELinux is permissive", Sev.HIGH,
            "In permissive mode SELinux logs but does not enforce policy, removing a major app sandbox protection.",
            listOf("getenforce -> $se"), emptyList()))
        // 6. verified boot / bootloader
        val vb = Sh.user("getprop ro.boot.verifiedbootstate").out.trim(); val locked = Sh.user("getprop ro.boot.flash.locked").out.trim()
        if (vb == "orange" || locked == "0") add(Issue("bootloader", "Bootloader is unlocked", Sev.INFO,
            "An unlocked bootloader is normal for rooted devices but disables verified-boot protection.",
            listOf("ro.boot.verifiedbootstate=$vb", "ro.boot.flash.locked=$locked"), emptyList()))
        // 7. USB debugging
        runCatching {
            if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) add(Issue("adb", "USB debugging is enabled", Sev.LOW,
                "ADB access lets anyone who can plug in and has an authorised key control the device.",
                listOf("Settings.Global adb_enabled=1"),
                listOf(FixOpt("adb_off", "Turn USB debugging off (root)", false, "You'll lose adb until re-enabled; undo restores it.",
                    rootPlan = { Plan(listOf("settings put global adb_enabled 0"), listOf("settings put global adb_enabled 1")) }),
                    FixOpt("adb_dev", "Open Developer options", true, intent = intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))))
        }
        // 8. launchers conflict
        val homes = Launchers.homeApps(ctx).filter { it.enabled }
        val defHome = Launchers.defaultHome(ctx)
        if (homes.size >= 2 && defHome == null) add(Issue("launcher", "Several home apps and no default", Sev.MEDIUM,
            "With no default, Android shows a chooser or picks inconsistently when you press Home.",
            homes.map { "${it.label} (${it.pkg})" }, listOf(FixOpt("launcher_default", "Choose default home app", true, intent = Launchers.homeSettingsIntent()))))
        // 9. keyboards
        runCatching {
            val imes = (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList
            if (imes.size >= 3) add(Issue("ime", "Many keyboards enabled", Sev.LOW,
                "Each enabled keyboard can stay loaded and may conflict when switching.",
                imes.map { it.packageName }.distinct(), listOf(FixOpt("ime_settings", "Open keyboard settings", intent = intent(Settings.ACTION_INPUT_METHOD_SETTINGS)))))
        }
        // 10. accessibility services
        run {
            val raw = secure(ctx, "enabled_accessibility_services")
            val svcs = raw.split(":").filter { it.isNotBlank() }
            if (svcs.size >= 3) add(Issue("a11y", "Several accessibility services enabled", Sev.MEDIUM,
                "Accessibility services can read the screen and inject input, cost battery and can conflict with each other.",
                svcs, svcs.map { s -> FixOpt("a11y_off_$s", "Disable $s (root)", false, "Might break the app that needs it; undo restores the list.",
                    rootPlan = {
                        val old = Optimizer.getSetting("secure", "enabled_accessibility_services") ?: ""
                        val new = old.split(":").filter { it.isNotBlank() && it != s }.joinToString(":")
                        Plan(listOf(if (new.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${Sh.q(new)}"),
                            listOf("settings put secure enabled_accessibility_services ${Sh.q(old)}"))
                    }) } + FixOpt("a11y_settings", "Open accessibility settings", true, intent = intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))))
        }
        // 11. task killers / cleaners
        val killers = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { pi ->
            val ai = pi.applicationInfo ?: return@filter false
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && pi.packageName != ctx.packageName &&
                pi.requestedPermissions?.contains("android.permission.KILL_BACKGROUND_PROCESSES") == true
        }
        if (killers.isNotEmpty()) add(Issue("killers", "Apps that can kill other apps", Sev.LOW,
            "Task-killer / cleaner apps fight Android's own memory management and vendor killers, causing restarts and battery drain.",
            killers.map { "${pm.getApplicationLabel(it.applicationInfo!!)} (${it.packageName})" },
            killers.take(6).map { FixOpt("killer_${it.packageName}", "Open settings of ${it.packageName}", intent = AppControl.settingsIntent(it.packageName)) }))
        // 12. battery optimisation for this app
        if (Compat.hasDoze) runCatching {
            val p = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!p.isIgnoringBatteryOptimizations(ctx.packageName)) add(Issue("battery_self", "This app is subject to battery optimisation", Sev.LOW,
                "Doze can stop the monitor, samplers and scheduled scans while the screen is off.",
                listOf("isIgnoringBatteryOptimizations(${ctx.packageName}) = false"),
                listOf(FixOpt("battery_self_fix", "Exempt this app from Doze (root)", true, rootPlan = { Optimizer.fixes.first { it.id == "doze_self" }.plan() }),
                    FixOpt("battery_self_ui", "Open battery optimisation list", intent = intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")))))
        }
        // 13. aggressive vendor killer
        val vendor = Vendor.detect()
        val chinese = vendor.groups.any { it in listOf(VendorGroup.XIAOMI, VendorGroup.HUAWEI, VendorGroup.OPPO, VendorGroup.VIVO, VendorGroup.LENOVO) }
        if (chinese) {
            val screens = Vendor.autostartIntents(ctx, vendor)
            add(Issue("vendor_killer", "Aggressive vendor battery / autostart manager likely active", Sev.MEDIUM,
                "Chinese-market ROMs commonly kill background apps (messengers, alarms, this app) regardless of Android's rules. Exempt important apps in the vendor's own settings.",
                listOf("ROM: ${vendor.rom}", "Detected: ${vendor.groups.joinToString { it.label }}"),
                screens.mapIndexed { i, (l, it) -> FixOpt("vendor_screen_$i", l, i == 0, intent = it) } +
                    (if (Compat.hasDoze) listOf(FixOpt("vendor_batt", "Open Android battery optimisation list", intent = intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))) else emptyList())))
        }
        // 14. Play Store / GMS disabled
        for ((p, n) in listOf("com.android.vending" to "Google Play Store", "com.google.android.gms" to "Google Play services")) {
            runCatching {
                val ai = pm.getApplicationInfo(p, 0)
                if (!ai.enabled) add(Issue("disabled_$p", "$n is disabled", Sev.HIGH,
                    "Apps that depend on $n (push notifications, licensing, sign-in) will malfunction.",
                    listOf("$p enabled=false"),
                    listOf(FixOpt("enable_$p", "Enable $p (root)", true, rootPlan = { Plan(listOf("pm enable $p"), listOf("pm disable-user --user 0 $p")) }),
                        FixOpt("enable_ui_$p", "Open app info", intent = AppControl.settingsIntent(p)))))
            }
        }
        // 15. block-list drift
        Store.blocked().filter { it.method != "settings" }.forEach { b ->
            runCatching {
                val ai = pm.getApplicationInfo(b.pkg, 0)
                if (ai.enabled && b.method == "disable") add(Issue("drift_${b.pkg}", "${b.pkg} is on the blocked list but enabled", Sev.LOW,
                    "Something (an update, a restore, the user) re-enabled it, so it can run again.",
                    listOf("blocked method=${b.method}, pm enabled=true"),
                    listOf(FixOpt("drift_refreeze_${b.pkg}", "Freeze again (root)", true, rootPlan = { if (dev.optimizer.android.core.Rules.blockReason(ctx, b.pkg, true) != null) Plan(emptyList(), emptyList()) else Plan(listOf("pm disable-user --user 0 ${Sh.q(b.pkg)}"), listOf("pm enable ${Sh.q(b.pkg)}")) }),
                        FixOpt("drift_forget_${b.pkg}", "Remove from blocked list", local = { Store.removeBlocked(b.pkg) }))))
            }
        }
        // 16. phantom process killer (root read)
        if (root && Build.VERSION.SDK_INT >= 31) {
            val v = Sh.root("device_config get activity_manager max_phantom_processes").out.trim()
            if (v.isEmpty() || v == "null") add(Issue("phantom", "Phantom-process limit at default", Sev.INFO,
                "Android 12+ kills child processes of apps beyond 32 (terminal tools, automation). Only matters if you run such tools.",
                listOf("device_config max_phantom_processes = (unset, default 32)"),
                listOf(FixOpt("phantom_fix", "Disable phantom-process killer", false, "Medium risk.", rootPlan = { Optimizer.fixes.first { it.id == "phantom" }.plan() }))))
        }
        // 17. old targetSdk
        val old = Assessment.apps(ctx).filter { !it.system && it.targetSdk in 1 until Assessment.OLD_TARGET_SDK }
        if (old.size >= 5) add(Issue("old_target", "${old.size} apps target a very old Android API", Sev.INFO,
            "Apps targeting API < ${Assessment.OLD_TARGET_SDK} run in compatibility mode with fewer privacy protections and can be slow.",
            old.take(8).map { "${it.label}: target ${it.targetSdk}" }, listOf(FixOpt("old_updates", "See the Updates section for update links"))))
        // 18. sideloaded apps
        val known = setOf("com.android.vending", "com.google.android.packageinstaller", "com.android.packageinstaller", "org.fdroid.fdroid", "com.amazon.venezia", "com.sec.android.app.samsungapps",
            "com.huawei.appmarket", "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "com.bbk.appstore", "com.aurora.store", "com.looker.droidify")
        val side = pm.getInstalledPackages(0).filter { pi ->
            val ai = pi.applicationInfo ?: return@filter false
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && pi.packageName != ctx.packageName && (Updates.installerOf(ctx, pi.packageName) ?: "none") !in known
        }
        if (side.size >= 3) add(Issue("sideload", "${side.size} apps not installed by a known app store", Sev.INFO,
            "Sideloaded apps are not vetted by a store and often never get updates.",
            side.take(10).map { "${it.packageName} (installer: ${Updates.installerOf(ctx, it.packageName) ?: "none"})" }, emptyList()))
        // 19. device administrators
        runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admins = dpm.activeAdmins?.map { it.packageName }.orEmpty()
            if (admins.isNotEmpty()) add(Issue("admins", "Device administrator apps active", Sev.INFO,
                "Device admins can lock the device, enforce policies and block uninstall.",
                admins, listOf(FixOpt("admins_ui", "Open security settings", intent = intent(Settings.ACTION_SECURITY_SETTINGS)))))
        }
        return out.sortedBy { it.sev.rank }
    }

    // ---- preview / apply ----
    data class Preview(val lines: List<String>, val intentOnly: List<FixOpt>)

    fun preview(selected: List<Pair<Issue, FixOpt>>): Preview {
        val lines = mutableListOf<String>(); val manual = mutableListOf<FixOpt>()
        for ((i, o) in selected) {
            when {
                o.rootPlan != null -> {
                    val p = runCatching { o.rootPlan.invoke() }.getOrNull()
                    if (p == null) lines += "# ${i.title}: ${o.label} - could not build plan (root needed to read current values)"
                    else { lines += "# ${i.title}: ${o.label}"; lines += p.apply.map { "  $ $it" }; if (p.undo.isNotEmpty()) lines += p.undo.map { "  (undo) $it" } else lines += "  (not undoable)" }
                }
                o.local != null -> lines += "# ${i.title}: ${o.label} (in-app change, no shell command)"
                else -> manual += o
            }
        }
        return Preview(lines, manual)
    }

    fun apply(selected: List<Pair<Issue, FixOpt>>): List<String> {
        val res = mutableListOf<String>()
        for ((i, o) in selected) {
            if (o.local != null) { o.local.invoke(); res += "✔ ${o.label}"; continue }
            val rp = o.rootPlan ?: continue
            if (!Optimizer.unlocked()) { res += "✘ ${o.label}: needs root and a baseline backup (use the settings shortcut instead)"; continue }
            val p = rp()
            var err: String? = null
            for (c in p.apply) { val r = Sh.root(c, 120); if (!r.ok || r.text.contains("Exception")) { err = "`$c` -> ${r.text}"; break } }
            if (err != null) { p.undo.forEach { Sh.root(it) }; res += "✘ ${o.label}: $err (rolled back)"; continue }
            ChangeLog.add("diag", "diag:${o.id}", "${i.title}: ${o.label}", i.evidence.joinToString("; ").take(300), p.undo)
            res += "✔ ${o.label}"
        }
        return res
    }

    fun recommended(issues: List<Issue>): List<Pair<Issue, FixOpt>> =
        issues.flatMap { i -> i.options.filter { it.recommended && (it.rootPlan != null || it.local != null) }.map { i to it } }

    // ---- periodic scan (WorkManager) ----
    fun schedule(ctx: Context, hours: Int) {
        Store.diagPeriodicHours = hours
        val wm = WorkManager.getInstance(ctx)
        if (hours <= 0) { wm.cancelUniqueWork("diag_scan"); return }
        wm.enqueueUniquePeriodicWork("diag_scan", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DiagWorker>(hours.toLong(), TimeUnit.HOURS).build())
    }
}

class DiagWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        // Root is not requested in the background (no surprise su prompts): checks needing root are skipped.
        val issues = runCatching { Diagnostics.scan(applicationContext, root = false) }.getOrNull() ?: return Result.retry()
        val important = issues.filter { it.sev == Sev.HIGH || it.sev == Sev.MEDIUM }
        val prev = Store.diagLastCount
        Store.diagLastCount = important.size
        if (important.isNotEmpty() && important.size != prev) {
            Compat.channel(applicationContext, "diag", "Diagnostics", 3)
            val pi = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            runCatching {
                if (androidx.core.app.NotificationManagerCompat.from(applicationContext).areNotificationsEnabled())
                    androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(11, Compat.builder(applicationContext, "diag")
                        .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Device diagnostics: ${important.size} issue(s)")
                        .setContentText(important.take(3).joinToString { it.title }).setContentIntent(pi).setAutoCancel(true).build())
            }
        }
        return Result.success()
    }
}
