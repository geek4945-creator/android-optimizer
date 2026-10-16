package dev.optimizer.android.features

import android.content.Context
import android.os.Build
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh

enum class Risk(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/** A reversible fix. plan() reads the current state and returns the apply commands plus the exact commands that undo them. */
data class Fix(
    val id: String, val title: String, val description: String, val risk: Risk, val minSdk: Int = 21,
    val exclusiveWith: List<String> = emptyList(), val note: String = "",
    val plan: () -> Plan,
)

data class Plan(val apply: List<String>, val undo: List<String>)

data class Op(val ok: Boolean, val message: String)

object Optimizer {
    /** The Optimize tab (and all root changes) are locked until root works and a baseline backup exists. */
    fun unlocked() = dev.optimizer.android.core.Priv.canChange() && Backups.hasBaseline()

    fun getSetting(ns: String, key: String): String? =
        Sh.root("settings get $ns $key").out.trim().let { if (it.isEmpty() || it == "null") null else it }

    fun restoreSetting(ns: String, key: String, old: String?) =
        if (old == null) "settings delete $ns $key" else "settings put $ns $key ${Sh.q(old)}"

    fun animPlan(v: String): Plan {
        val keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val apply = keys.map { "settings put global $it $v" }
        val undo = keys.map { restoreSetting("global", it, getSetting("global", it)) }
        return Plan(apply, undo)
    }

    val fixes: List<Fix> = listOf(
        Fix("anim_half", "Animation scales 0.5x", "Sets window, transition and animator scales to 0.5 so the UI feels faster.", Risk.LOW,
            exclusiveWith = listOf("anim_off"), plan = { animPlan("0.5") }),
        Fix("anim_off", "Animations off", "Sets all three animation scales to 0 (instant transitions). Some apps that rely on animator callbacks can look odd.", Risk.LOW,
            exclusiveWith = listOf("anim_half"), plan = { animPlan("0") }),
        Fix("phantom", "Disable phantom-process killer (Android 12+)", "Android 12+ kills child processes of apps (e.g. terminal tools) beyond a limit. Raises the limit via device_config and turns the monitor off. Values can be reset by the system after a reboot or config sync.", Risk.MEDIUM, minSdk = 31,
            plan = {
                val oldDc = Sh.root("device_config get activity_manager max_phantom_processes").out.trim().let { if (it.isEmpty() || it == "null") null else it }
                val oldMon = getSetting("global", "settings_enable_monitor_phantom_procs")
                Plan(
                    listOf("device_config put activity_manager max_phantom_processes 2147483647", "settings put global settings_enable_monitor_phantom_procs false"),
                    listOf(if (oldDc == null) "device_config delete activity_manager max_phantom_processes" else "device_config put activity_manager max_phantom_processes ${Sh.q(oldDc)}",
                        restoreSetting("global", "settings_enable_monitor_phantom_procs", oldMon))
                )
            }),
        Fix("cached_cap", "Cap cached background processes at 16", "Writes max_cached_processes=16 into Settings.Global activity_manager_constants so fewer idle apps stay in memory (saves RAM, apps may reload more). Replaces any existing activity_manager_constants value; the old value is restored on undo. Effect depends on the ROM - verify.", Risk.MEDIUM, minSdk = 28,
            plan = {
                val old = getSetting("global", "activity_manager_constants")
                Plan(listOf("settings put global activity_manager_constants max_cached_processes=16"), listOf(restoreSetting("global", "activity_manager_constants", old)))
            }),
        Fix("force_gpu", "Force GPU rendering", "Sets the persist.sys.ui.hw property (same as the Developer-options 'Force GPU rendering'). Newer Android versions and some ROMs ignore it. Takes effect after apps restart or on reboot.", Risk.MEDIUM,
            plan = {
                val old = Sh.root("getprop persist.sys.ui.hw").out.trim()
                Plan(listOf("setprop persist.sys.ui.hw true"), listOf("setprop persist.sys.ui.hw ${Sh.q(old)}"))
            }),
        Fix("doze_self", "Exempt this app from Doze / battery optimisation", "Adds this app to the device-idle whitelist so its monitoring keeps working with the screen off.", Risk.LOW, minSdk = 23,
            plan = { val me = dev.optimizer.android.core.Store.appCtx.packageName; Plan(listOf("dumpsys deviceidle whitelist +$me"), listOf("dumpsys deviceidle whitelist -$me")) }),
    )

    fun fixAvailable(f: Fix) = Build.VERSION.SDK_INT >= f.minSdk
    fun maintAvailable(m: Maint) = Build.VERSION.SDK_INT >= m.minSdk
    /** Low-end mode (DeviceProfile) blocks the heavy dexopt jobs: they thrash storage/RAM and heat weak devices. */
    fun maintBlockedReason(m: Maint): String? =
        if (!maintAvailable(m)) "Needs Android ${when (m.minSdk) { 24 -> "7+"; 26 -> "8+"; else -> m.minSdk.toString() }}"
        else if (m.heavy && dev.optimizer.android.ui.AppState.lowEnd) "Disabled in low-end mode (heavy job). Turn low-end mode off in More › Settings to allow it."
        else null

    private fun runAll(cmds: List<String>, timeout: Long = 60): String? {
        for (c in cmds) {
            val r = Sh.root(c, timeout)
            if (!r.ok || r.text.contains("Exception") || r.text.contains("Error:")) return "`$c` -> ${r.text}"
        }
        return null
    }

    fun apply(f: Fix): Op {
        if (!unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        for (ex in f.exclusiveWith) fixes.firstOrNull { it.id == ex }?.let { if (ChangeLog.isActive(it.id)) undoFix(it.id) }
        val plan = f.plan()
        val err = runAll(plan.apply)
        if (err != null) { runAll(plan.undo); return Op(false, "Failed, rolled back: $err") }
        ChangeLog.add("optimize", f.id, f.title, f.description, plan.undo)
        return Op(true, "Applied: ${f.title}")
    }

    fun undoFix(group: String): Op {
        val es = ChangeLog.all().filter { it.group == group && !it.undone && it.undoable }.reversed()
        if (es.isEmpty()) return Op(false, "Nothing to undo")
        val errs = es.mapNotNull { ChangeLog.undo(it) }
        return if (errs.isEmpty()) Op(true, "Reverted") else Op(false, errs.joinToString("\n"))
    }

    // ---- maintenance (not reversible) ----
    data class Maint(val id: String, val title: String, val desc: String, val cmds: List<String>, val timeoutSec: Long, val minSdk: Int = 21, val heavy: Boolean = false)

    val maintenance = listOf(
        Maint("trim", "Clear app caches", "pm trim-caches frees cache space system-wide. Caches are rebuilt by apps; nothing to undo.", listOf("pm trim-caches 999999999999"), 120, minSdk = 24),
        Maint("bgdexopt", "Run background dexopt job", "cmd package bg-dexopt-job optimises apps that need it (can take minutes and use battery).", listOf("cmd package bg-dexopt-job"), 900, minSdk = 26, heavy = true),
        Maint("compile", "Compile all apps (speed-profile)", "cmd package compile -m speed-profile -a. Can take 5-30 min and heat the phone; uses storage.", listOf("cmd package compile -m speed-profile -a"), 1800, minSdk = 24, heavy = true),
        Maint("fstrim", "TRIM storage (fstrim)", "Runs fstrim on /data and /cache where available.", listOf("fstrim -v /data", "fstrim -v /cache"), 300),
    )

    fun runMaintenance(m: Maint): Op {
        if (!unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        val sb = StringBuilder()
        for (c in m.cmds) { val r = Sh.root(c, m.timeoutSec); sb.append("$ $c\n${r.text.take(600)}\n") }
        ChangeLog.add("maintenance", "maint:${m.id}", m.title, "One-shot maintenance command (not reversible)", emptyList())
        return Op(true, sb.toString().trim())
    }

    // ---- doze / background whitelist per app ----
    fun whitelist(ctx: Context, pkg: String): Op {
        if (!unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        val q = Sh.q(pkg)
        if (!Compat.hasDoze) return Op(false, "Doze does not exist before Android 6 - nothing to whitelist.")
        val bgOp = Build.VERSION.SDK_INT >= 26 // RUN_IN_BACKGROUND app-op exists from Android 8
        val prevMode = if (bgOp) Regex("RUN_IN_BACKGROUND:\\s*(\\w+)").find(Sh.root("${Compat.appopsCmd} get $q RUN_IN_BACKGROUND").out.trim())?.groupValues?.get(1) ?: "default" else ""
        val apply = listOf("dumpsys deviceidle whitelist +$pkg") + (if (bgOp) listOf("${Compat.appopsCmd} set $q RUN_IN_BACKGROUND allow") else emptyList())
        val undo = listOf("dumpsys deviceidle whitelist -$pkg") + (if (bgOp) listOf("${Compat.appopsCmd} set $q RUN_IN_BACKGROUND $prevMode") else emptyList())
        val err = runAll(apply)
        if (err != null) return Op(false, err)
        ChangeLog.add("doze", "doze:$pkg", "Doze/background whitelist: $pkg", "deviceidle whitelist" + (if (bgOp) " + appops RUN_IN_BACKGROUND allow (was $prevMode)" else ""), undo)
        return Op(true, "$pkg exempted")
    }

    fun currentWhitelist(): String = Sh.root("dumpsys deviceidle whitelist").text.ifEmpty { "(empty)" }

    // ---- debloat (disable-user only) ----
    fun debloat(ctx: Context, b: Bloat): Op {
        if (!unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        Protected.reasons(ctx, b.pkg)?.let { return Op(false, "${b.pkg} is protected: $it") }
        val r = Sh.root("pm disable-user --user 0 ${Sh.q(b.pkg)}")
        if (!r.ok || r.text.contains("Exception")) return Op(false, r.text)
        ChangeLog.add("debloat", "debloat:${b.pkg}", "Disabled ${b.pkg}", b.what, listOf("pm enable ${Sh.q(b.pkg)}"))
        return Op(true, "Disabled ${b.pkg}")
    }

    // ---- profile ----
    val recommendedIds = listOf("anim_half", "doze_self")

    fun applyRecommended(): List<Op> {
        val out = mutableListOf<Op>()
        for (id in recommendedIds) {
            val f = fixes.first { it.id == id }
            if (!ChangeLog.isActive(id) && fixAvailable(f)) out += apply(f)
        }
        maintenance.first { it.id == "trim" }.let { if (maintAvailable(it)) out += runMaintenance(it) }
        return out
    }

    fun undoAllOptimize(): String {
        val sb = StringBuilder()
        for (c in ChangeLog.activeUndoable().reversed()) {
            val e = ChangeLog.undo(c)
            sb.append(if (e == null) "Undone: ${c.title}\n" else "FAILED: ${c.title}: $e\n")
        }
        return sb.toString().trim().ifEmpty { "Nothing to undo" }
    }
}
