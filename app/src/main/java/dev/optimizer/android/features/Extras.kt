package dev.optimizer.android.features

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Rules
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------ temperature log
data class TempPoint(val ts: Long, val battC: Float?, val maxZoneC: Float?, val pct: Int)

object TempLog {
    private fun file() = File(Store.dataDir(), "templog.csv")

    private fun zoneMax(): Float? {
        var best: Float? = null
        for (i in 0 until 40) {
            val f = File("/sys/class/thermal/thermal_zone$i/temp")
            if (!f.exists()) { if (i > 12) break else continue }
            val v = runCatching { f.readText().trim().toFloat() }.getOrNull() ?: continue
            val c = if (v > 1000) v / 1000f else v
            if (c in 1f..150f && (best == null || c > best)) best = c
        }
        return best
    }

    @Synchronized fun add(ctx: Context) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val f = file()
        f.appendText("${System.currentTimeMillis()},${t ?: ""},${zoneMax() ?: ""},$lvl\n")
        if (f.length() > 200_000) f.writeText(f.readLines().takeLast(2500).joinToString("\n") + "\n")
    }

    @Synchronized fun read(sinceMs: Long = 0): List<TempPoint> = runCatching {
        file().readLines().mapNotNull { l -> val p = l.split(","); if (p.size < 4) null else TempPoint(p[0].toLong(), p[1].toFloatOrNull(), p[2].toFloatOrNull(), p[3].toIntOrNull() ?: -1) }.filter { it.ts >= sinceMs }
    }.getOrDefault(emptyList())

    @Synchronized fun clear() { file().delete() }
}

// ------------------------------------------------------------------ battery
data class BatteryReport(val rows: List<Pair<String, String>>, val warnings: List<String>)

object BatteryInfo {
    private fun rd(p: String): String? = runCatching { File(p).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: if (Root.isRooted) Sh.root("cat $p 2>/dev/null").out.trim().takeIf { it.isNotEmpty() } else null

    fun read(ctx: Context): BatteryReport {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rows = mutableListOf<Pair<String, String>>(); val warn = mutableListOf<String>()
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        rows += "Level" to if (lvl >= 0) "${lvl * 100 / sc.coerceAtLeast(1)}%" else "n/a"
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1)) { 2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over voltage"; 6 -> "Unspecified failure"; 7 -> "Cold"; else -> "Unknown" }
        rows += "Reported health" to health
        if (health in setOf("Overheat", "Dead", "Over voltage", "Unspecified failure")) warn += "Battery reports '$health'. Stop charging/using if the tablet is hot or the battery looks swollen."
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        rows += "Temperature" to (t?.let { "%.1f °C".format(it) } ?: "n/a"); if (t != null && t >= 43f) warn += "Battery is hot (%.1f °C): the system may throttle the CPU, which feels like freezing.".format(t)
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        rows += "Voltage" to if (mv > 0) "$mv mV" else "n/a"
        if (mv in 1..3400 && lvl > 20) warn += "Voltage ($mv mV) is low for ${lvl}% charge: possible worn battery or wrong gauge calibration."
        rows += "Status" to when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) { 2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not charging"; 5 -> "Full"; else -> "Unknown" }
        rows += "Power source" to when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) { 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; else -> "Battery" }
        rows += "Technology" to (i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "n/a")
        if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }?.let { rows += "Charge cycles" to it.toString() }
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (Build.VERSION.SDK_INT >= 21) {
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Int.MIN_VALUE && cur != 0) rows += "Current now" to "${cur / 1000} mA (sign/units vary by device)"
            val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc != Int.MIN_VALUE && cc > 0) rows += "Charge counter" to "${cc / 1000} mAh"
        }
        val full = rd("/sys/class/power_supply/battery/charge_full")?.toLongOrNull(); val design = rd("/sys/class/power_supply/battery/charge_full_design")?.toLongOrNull()
        if (full != null && design != null && design > 0) {
            val pct = full * 100 / design
            rows += "Capacity vs design" to "$pct% ($full / $design µAh)"
            if (pct < 70) warn += "Battery holds only $pct% of its design capacity: expect short runtime and voltage sag under load."
        } else rows += "Capacity vs design" to "not exposed by this device${if (!Root.isRooted) " (root may expose it)" else ""}"
        return BatteryReport(rows, warn)
    }
}

// ------------------------------------------------------------------ app-usage ranking
data class UsageRow(val pkg: String, val label: String, val foregroundMs: Long)

object UsageRank {
    fun top(ctx: Context, days: Int, n: Int = 20): List<UsageRow> {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val st = um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - days * 86_400_000L, now) ?: return emptyList()
        val m = HashMap<String, Long>()
        st.forEach { m[it.packageName] = (m[it.packageName] ?: 0L) + it.totalTimeInForeground }
        val pm = ctx.packageManager
        return m.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(n).map { (k, v) -> UsageRow(k, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(k, 0)).toString() }.getOrDefault(k), v) }
    }
}

// ------------------------------------------------------------------ crash / ANR viewer
object CrashLog {
    fun read(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.processesInErrorState?.let { l -> out += "Processes in error right now" to (if (l.isEmpty()) "none" else l.joinToString("\n") { "${it.processName}: ${it.shortMsg}" }) }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val ex = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            out += "This app's recent exits (Android 11+ API)" to (if (ex.isEmpty()) "none" else ex.joinToString("\n") { "${java.util.Date(it.timestamp)} reason=${it.reason} status=${it.status} ${it.description ?: ""}" })
        }
        if (Root.isRooted) {
            out += "Crash log buffer (logcat -b crash, last 300 lines)" to Sh.root("logcat -b crash -d -v time -t 300 2>&1", 30).out.trim().ifEmpty { "empty" }
            out += "/data/anr" to Sh.root("ls -l /data/anr 2>&1 | tail -n 30", 15).out.trim().ifEmpty { "empty or not readable" }
            out += "Dropbox entries (system crashes/ANRs/tombstones)" to dropboxSummary()
        } else out += "More detail" to "Crash buffers, /data/anr and the system dropbox are only readable with root. Android 11+ also shows ANR reasons in Settings > Apps for each app."
        return out
    }

    fun dropboxSummary(): String {
        val r = Sh.root("dumpsys dropbox 2>&1 | tail -n 120", 30).out
        val rx = Regex("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+(\\S+)")
        val lines = r.lineSequence().mapNotNull { rx.find(it)?.let { m -> m.groupValues[1] to m.groupValues[2] } }.toList()
        if (lines.isEmpty()) return "no entries listed (or dumpsys dropbox is not available)"
        return lines.groupBy { it.second }.entries.sortedByDescending { it.value.size }.joinToString("\n") { "${it.key}: ${it.value.size} (latest ${it.value.last().first})" }
    }
}

// ------------------------------------------------------------------ startup apps (BOOT_COMPLETED receivers)
data class BootReceiver(val cls: String, val enabled: Boolean)
data class BootApp(val pkg: String, val label: String, val system: Boolean, val receivers: List<BootReceiver>, val protectedReason: String?)

object StartupApps {
    @Suppress("DEPRECATION")
    fun list(ctx: Context, includeSystem: Boolean): List<BootApp> {
        val pm = ctx.packageManager
        val acts = listOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")
        val map = LinkedHashMap<String, MutableMap<String, Boolean>>()
        for (a in acts) {
            val rs = runCatching { pm.queryBroadcastReceivers(Intent(a), PackageManager.GET_DISABLED_COMPONENTS) }.getOrDefault(emptyList())
            for (r in rs) {
                val ai = r.activityInfo ?: continue
                val st = pm.getComponentEnabledSetting(ComponentName(ai.packageName, ai.name))
                val en = st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && ai.enabled)
                map.getOrPut(ai.packageName) { LinkedHashMap() }[ai.name] = en
            }
        }
        return map.mapNotNull { (pkg, rc) ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val sys = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (sys && !includeSystem) return@mapNotNull null
            BootApp(pkg, pm.getApplicationLabel(info).toString(), sys, rc.map { BootReceiver(it.key, it.value) }, Protected.reasons(ctx, pkg))
        }.sortedBy { it.label.lowercase() }
    }

    fun setEnabled(ctx: Context, pkg: String, cls: String, enable: Boolean): Op {
        if (!Optimizer.unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        Protected.reasons(ctx, pkg)?.let { return Op(false, "$pkg is protected: $it") }
        val comp = Sh.q("$pkg/$cls")
        val r = Sh.root("pm ${if (enable) "enable" else "disable"} $comp", 30)
        if (!r.ok || r.text.contains("Exception") || r.text.contains("Error")) return Op(false, r.text.ifEmpty { "failed" })
        val group = "startup:$pkg/$cls"
        if (enable) ChangeLog.all().filter { it.group == group && !it.undone }.forEach { ChangeLog.markUndone(it.id) }
        else ChangeLog.add("startup", group, "Startup receiver disabled: $pkg", cls, listOf("pm enable $comp"))
        return Op(true, "${if (enable) "Enabled" else "Disabled"} boot receiver of $pkg")
    }
}

// ------------------------------------------------------------------ Safe Boost (one tap, one undo)
object SafeBoost {
    private const val KEY = "boost_groups"
    fun active(): Boolean = groups().isNotEmpty() && groups().any { ChangeLog.isActive(it) }
    private fun groups(): List<String> = runCatching { JSONArray(Store.getStr(KEY, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())

    /** What it will do, shown before running. */
    fun plan(): List<String> = buildList {
        add("Close cached/background apps gently (protected apps, 'Never stop' rules and the foreground app are skipped)")
        if (Optimizer.unlocked()) { add("Set animation scales to 0.5x (undoable)"); if (Build.VERSION.SDK_INT >= 24) add("Clear app caches with `pm trim-caches` (not undoable; caches are rebuilt)") }
        else add("(Animation scale and cache trim need root + baseline; skipped on this device state)")
    }

    fun apply(ctx: Context): List<String> {
        val res = mutableListOf<String>(); val applied = mutableListOf<String>()
        runCatching {
            val c = RamOptimizer.candidates(ctx, false)
            val r = RamOptimizer.run(ctx, c.items, RamOptions(true, false, false, false, false), "Safe Boost (gentle)")
            res += "RAM: closed ${r.killed} apps, available ${r.before.availKb / 1024} → ${r.after.availKb / 1024} MB"
        }.onFailure { res += "RAM optimize failed: ${it.message}" }
        if (Optimizer.unlocked()) {
            val fix = Optimizer.fixes.first { it.id == "anim_half" }
            if (ChangeLog.isActive(fix.id)) res += "Animations: already 0.5x/managed by a previous change - left alone"
            else { val o = Optimizer.apply(fix); res += "Animations: ${o.message}"; if (o.ok) applied += fix.id }
            val m = Optimizer.maintenance.first { it.id == "trim" }
            if (Optimizer.maintAvailable(m)) res += "Caches: " + Optimizer.runMaintenance(m).message.lineSequence().firstOrNull().orEmpty().take(80)
        } else res += "Root + baseline not available: only the RAM step ran."
        Store.putStr(KEY, JSONArray(applied).toString())
        return res
    }

    fun undo(): List<String> {
        val gs = groups(); if (gs.isEmpty()) return listOf("Nothing to undo (RAM and cache steps are not reversible by nature).")
        val res = gs.map { g -> Optimizer.undoFix(g).message.let { "$g: $it" } }
        Store.putStr(KEY, "[]"); return res
    }
}

// ------------------------------------------------------------------ Reset to stock
object ResetStock {
    fun pending(): List<Change2> = ChangeLog.activeUndoable().map { Change2(it.id, it.title, it.category) }
    data class Change2(val id: String, val title: String, val category: String)

    fun run(ctx: Context, clearRules: Boolean, stopServices: Boolean): List<String> {
        val res = mutableListOf<String>()
        if (!Root.isRooted && ChangeLog.activeUndoable().isNotEmpty()) res += "No root now: ${ChangeLog.activeUndoable().size} logged change(s) cannot be reverted until root is available."
        else ChangeLog.activeUndoable().reversed().forEach { c -> res += (ChangeLog.undo(c)?.let { "✘ ${c.title}: $it" } ?: "✔ reverted: ${c.title}") }
        Rules.all().filter { it.keepClosed && it.mode != Rules.MODE_KILL }.forEach { runCatching { Enforce.undo(it.pkg, it.mode) } }
        if (clearRules) { Rules.all().forEach { Rules.remove(it.pkg) }; res += "Cleared all Never-stop / Keep-closed rules." }
        if (stopServices) {
            runCatching { GuardService.stop(ctx) }; runCatching { RamAutoService.stop(ctx) }
            Store.putBool("live_notif", false); LiveService.sync(ctx)
            runCatching { RamOptimizer.scheduleWorker(ctx, 0) }; runCatching { Diagnostics.schedule(ctx, 0) }; MaintSched.save(ctx, MaintSched.Prefs(on = false))
            SafeBoost.undo()
            res += "Stopped Guard, RAM monitor, live notification and scheduled jobs."
        }
        res += "Not reverted: one-shot actions such as force-stops, cache clears, fstrim, and files moved by the cleaner (restore those from the cleaner's trash). This is not a factory reset."
        return res
    }
}

// ------------------------------------------------------------------ scheduled maintenance (idle + charging)
object MaintSched {
    data class Prefs(val on: Boolean = false, val days: Int = 7, val trim: Boolean = true, val fstrim: Boolean = true, val dexopt: Boolean = false)
    fun prefs(): Prefs = runCatching { JSONObject(Store.getStr("maint_sched", "{}")).let { Prefs(it.optBoolean("on"), it.optInt("days", 7), it.optBoolean("trim", true), it.optBoolean("fstrim", true), it.optBoolean("dexopt", false)) } }.getOrDefault(Prefs())
    fun save(ctx: Context, p: Prefs) {
        Store.putStr("maint_sched", JSONObject().put("on", p.on).put("days", p.days).put("trim", p.trim).put("fstrim", p.fstrim).put("dexopt", p.dexopt).toString())
        val wm = WorkManager.getInstance(ctx)
        if (!p.on) { wm.cancelUniqueWork("maint_sched"); return }
        val cb = Constraints.Builder().setRequiresCharging(true)
        if (Build.VERSION.SDK_INT >= 23) cb.setRequiresDeviceIdle(true)
        wm.enqueueUniquePeriodicWork("maint_sched", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<MaintWorker>(p.days.toLong(), TimeUnit.DAYS).setConstraints(cb.build()).build())
    }
    fun lastRun(): String = Store.getStr("maint_last", "never")
}

class MaintWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (!Root.isRooted && Store.rootGrantedBefore) Root.request() // never prompts in the background; works only if the manager remembers the grant
        val p = MaintSched.prefs()
        val notes = mutableListOf<String>()
        if (!Optimizer.unlocked()) notes += "skipped: needs root + baseline (not available in the background)"
        else Optimizer.maintenance.filter { (it.id == "trim" && p.trim) || (it.id == "fstrim" && p.fstrim) || (it.id == "bgdexopt" && p.dexopt) }.forEach { m ->
            val why = Optimizer.maintBlockedReason(m)
            notes += if (why != null) "${m.id}: skipped ($why)" else "${m.id}: " + (if (Optimizer.runMaintenance(m).ok) "ok" else "failed")
        }
        Store.putStr("maint_last", "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}: ${notes.joinToString("; ").ifEmpty { "nothing selected" }}")
        return Result.success()
    }
}
