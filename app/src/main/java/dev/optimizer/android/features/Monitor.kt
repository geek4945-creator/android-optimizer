package dev.optimizer.android.features

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import dev.optimizer.android.core.BlockedApp
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import java.io.File

data class CpuSample(val percent: Float?, val source: String)
data class MemSample(val totalKb: Long, val availKb: Long) { val usedKb get() = totalKb - availKb }

data class AppRow(
    val pkg: String, val label: String, val pssKb: Long?, val cpu: Float?, val lastUsed: Long?,
    val system: Boolean, val protectedReason: String?, val blocked: Boolean,
)

data class MonitorSnapshot(
    val cpu: CpuSample, val mem: MemSample, val apps: List<AppRow>, val appsNote: String,
)

object Monitor {
    private var prevTotal = 0L
    private var prevIdle = 0L
    private var cpuMode = 0 // 0=/proc/stat, 1=top, 2=unavailable

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------- CPU ----------------
    private fun cpuFromProcStat(): Float? {
        val line = runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull() ?: return null
        if (!line.startsWith("cpu ")) return null
        val v = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + (v.getOrNull(4) ?: 0)
        val total = v.take(8).sum()
        val dT = total - prevTotal; val dI = idle - prevIdle
        val first = prevTotal == 0L
        prevTotal = total; prevIdle = idle
        if (first || dT <= 0) return -1f // need a second sample
        return ((dT - dI).toFloat() / dT * 100f).coerceIn(0f, 100f)
    }

    private fun parseTopHeader(out: String): Float? {
        // toybox: "800%cpu  23%user   0%nice  12%sys 760%idle ..."
        val l = out.lineSequence().firstOrNull { it.contains("%cpu") && it.contains("%idle") } ?: return null
        val total = Regex("(\\d+)%cpu").find(l)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val idle = Regex("(\\d+)%idle").find(l)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        if (total <= 0) return null
        return ((total - idle) / total * 100f).coerceIn(0f, 100f)
    }

    fun cpu(): CpuSample {
        if (cpuMode == 0) {
            val p = cpuFromProcStat()
            if (p == null) cpuMode = 1 else return if (p < 0) CpuSample(null, "/proc/stat (warming up)") else CpuSample(p, "/proc/stat")
        }
        if (cpuMode == 1) {
            val r = if (Root.isRooted) Sh.root("top -b -n 1 -m 1", 10) else Sh.user("top -b -n 1 -m 1", 10)
            val p = parseTopHeader(r.out)
            return if (p != null) CpuSample(p, if (Root.isRooted) "top (root)" else "top (shell)")
            else { cpuMode = 2; CpuSample(null, "unavailable") }
        }
        // retry occasionally (e.g. after root is granted)
        if (Root.isRooted) cpuMode = 1
        return CpuSample(null, "unavailable: Android restricts /proc/stat and top for apps; grant root for CPU data")
    }

    // ---------------- RAM ----------------
    fun mem(ctx: Context): MemSample {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (mi.totalMem > 0) return MemSample(mi.totalMem / 1024, mi.availMem / 1024)
        val txt = runCatching { File("/proc/meminfo").readText() }.getOrDefault("")
        fun g(k: String) = Regex("$k:\\s+(\\d+)").find(txt)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return MemSample(g("MemTotal"), g("MemAvailable"))
    }

    // ---------------- Per-app ----------------
    private fun pkgOf(proc: String) = proc.substringBefore(':')

    private fun rootPss(): Map<String, Long> {
        // "Total PSS by process:" section of `dumpsys meminfo`
        val r = Sh.root("dumpsys meminfo", 45)
        val m = HashMap<String, Long>()
        var inSec = false
        val rx = Regex("^\\s*([\\d,]+)K:\\s+(\\S+)")
        for (l in r.out.lineSequence()) {
            if (l.startsWith("Total PSS by process")) { inSec = true; continue }
            if (inSec) {
                if (l.isBlank() || l.startsWith("Total PSS by OOM")) break
                val mm = rx.find(l) ?: continue
                val kb = mm.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                val pkg = pkgOf(mm.groupValues[2])
                m[pkg] = (m[pkg] ?: 0L) + kb
            }
        }
        return m
    }

    private fun rootCpu(): Map<String, Float> {
        val r = Sh.root("top -b -n 1 -m 80", 20)
        val m = HashMap<String, Float>()
        var cols: List<String>? = null
        for (l in r.out.lineSequence()) {
            val t = l.trim()
            if (t.startsWith("PID")) { cols = t.replace("S[%CPU]", "S %CPU").split(Regex("\\s+")); continue }
            val c = cols ?: continue
            if (t.isEmpty()) continue
            val parts = t.split(Regex("\\s+"))
            val ci = c.indexOfFirst { it.contains("%CPU") || it == "CPU%" }
            if (ci < 0 || parts.size < c.size) continue
            val name = parts.last()
            val v = parts[ci].replace("%", "").toFloatOrNull() ?: continue
            val pkg = pkgOf(name)
            if (!pkg.contains('.')) continue
            m[pkg] = (m[pkg] ?: 0f) + v
        }
        return m
    }

    private fun recentUsage(ctx: Context, windowMs: Long = 6 * 3600_000L): Map<String, Long> {
        if (!hasUsageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - windowMs, now)
        val e = UsageEvents.Event()
        val last = HashMap<String, Long>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED || e.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                last[e.packageName] = maxOf(last[e.packageName] ?: 0L, e.timeStamp)
            }
        }
        return last
    }

    private fun label(ctx: Context, pkg: String): Pair<String, Boolean> = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai).toString() to ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    }.getOrDefault(pkg to false)

    fun snapshot(ctx: Context, withApps: Boolean): MonitorSnapshot {
        val cpu = cpu(); val mem = mem(ctx)
        if (!withApps) return MonitorSnapshot(cpu, mem, emptyList(), "")
        val blocked = Store.blocked().map { it.pkg }.toSet()
        val rows = mutableListOf<AppRow>()
        val note: String
        if (Root.isRooted) {
            val pss = rootPss(); val cpuMap = rootCpu(); val usage = recentUsage(ctx)
            val pkgs = (pss.keys + cpuMap.keys).filter { it.contains('.') }.toSet()
            for (p in pkgs) {
                val (l, s) = label(ctx, p)
                // skip processes that are not packages
                if (l == p && runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.isFailure) continue
                rows += AppRow(p, l, pss[p], cpuMap[p], usage[p], s, Protected.reasons(ctx, p), p in blocked)
            }
            note = "Root: per-app RAM from `dumpsys meminfo` (PSS), CPU from `top`. CPU is an instantaneous best-effort sample."
        } else {
            val usage = recentUsage(ctx)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pssMap = HashMap<String, Long>()
            runCatching {
                val procs = am.runningAppProcesses ?: emptyList()
                if (procs.isNotEmpty()) {
                    val infos = am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray())
                    procs.forEachIndexed { i, pr ->
                        val kb = infos.getOrNull(i)?.totalPss?.toLong() ?: 0L
                        if (kb > 0) pssMap[pkgOf(pr.processName)] = (pssMap[pkgOf(pr.processName)] ?: 0L) + kb
                    }
                }
            }
            val pkgs = (usage.keys + pssMap.keys).toSet()
            for (p in pkgs) {
                val (l, s) = label(ctx, p)
                rows += AppRow(p, l, pssMap[p], null, usage[p], s, Protected.reasons(ctx, p), p in blocked)
            }
            note = if (!hasUsageAccess(ctx))
                "Limited by Android restrictions: without root, other apps' processes, RAM and CPU are hidden. Grant Usage Access to list recently used apps, or grant root for full data."
            else "Limited by Android restrictions: apps shown are those used in the last 6 h (Usage Access). Per-app RAM/CPU of other apps needs root; '-' means not available."
        }
        return MonitorSnapshot(cpu, mem, rows, note)
    }
}

/** Close / keep-closed / allow actions. */
object AppControl {
    data class Result(val ok: Boolean, val message: String)

    fun close(ctx: Context, pkg: String): Result {
        if (Protected.isProtected(ctx, pkg)) return Result(false, "Protected: ${Protected.reasons(ctx, pkg)}")
        return if (Root.isRooted) {
            val r = Sh.root("am force-stop ${Sh.q(pkg)}")
            Result(r.ok, if (r.ok) "Force-stopped $pkg" else "Failed: ${r.text}")
        } else {
            runCatching {
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            }.fold({ Result(true, "Asked Android to kill background processes of $pkg (cannot stop foreground apps without root)") }, { Result(false, "Failed: ${it.message}") })
        }
    }

    fun settingsIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Root: freeze (pm disable-user, fallback pm suspend). Needs baseline backup. */
    fun keepClosedRoot(ctx: Context, pkg: String, label: String): Result {
        if (Protected.isProtected(ctx, pkg)) return Result(false, "Protected: ${Protected.reasons(ctx, pkg)}")
        if (!Backups.hasBaseline()) return Result(false, "Create the baseline backup first (Root tab / Backups).")
        Sh.root("am force-stop ${Sh.q(pkg)}")
        var method = "disable"
        var r = Sh.root("pm disable-user --user 0 ${Sh.q(pkg)}")
        var undo = "pm enable ${Sh.q(pkg)}"
        if (!r.ok || r.text.contains("Exception", true) || r.text.contains("Unknown", true)) {
            val r2 = Sh.root("pm suspend --user 0 ${Sh.q(pkg)}")
            if (!r2.ok || r2.text.contains("Exception", true)) return Result(false, "Could not freeze: ${r.text} / ${r2.text}")
            method = "suspend"; undo = "pm unsuspend --user 0 ${Sh.q(pkg)}"; r = r2
        }
        Store.addBlocked(BlockedApp(pkg, method, System.currentTimeMillis()))
        ChangeLog.add("block", "block:$pkg", "Keep closed: $label", "$pkg frozen using pm $method", listOf(undo))
        return Result(true, "$label is frozen (pm $method) until you tap Allow / Open.")
    }

    fun allow(ctx: Context, pkg: String): Result {
        val b = Store.blocked().firstOrNull { it.pkg == pkg }
        if (b == null) return Result(false, "Not in blocked list")
        if (b.method == "settings") { Store.removeBlocked(pkg); return Result(true, "Removed from watch list") }
        if (!Root.isRooted) return Result(false, "Root is needed to re-enable this app.")
        val r = if (b.method == "suspend") Sh.root("pm unsuspend --user 0 ${Sh.q(pkg)}") else Sh.root("pm enable ${Sh.q(pkg)}")
        if (!r.ok) return Result(false, "Failed: ${r.text}")
        Store.removeBlocked(pkg)
        ChangeLog.all().filter { it.group == "block:$pkg" && !it.undone }.forEach { ChangeLog.markUndone(it.id) }
        return Result(true, "$pkg re-enabled")
    }

    /** Non-root fallback: remember it (for the optional re-kill service) and open the system page. */
    fun keepClosedNonRoot(pkg: String) {
        Store.addBlocked(BlockedApp(pkg, "settings", System.currentTimeMillis()))
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); return true
    }

    fun isInstalled(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    @Suppress("unused") val pmFlags = PackageManager.MATCH_DISABLED_COMPONENTS
}
