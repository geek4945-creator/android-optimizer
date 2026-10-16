package dev.optimizer.android.features

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.optimizer.android.MainActivity
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Rules
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class RamStat(val totalKb: Long, val freeKb: Long, val availKb: Long, val cachedKb: Long, val buffersKb: Long, val swapTotalKb: Long, val swapFreeKb: Long) {
    val usedPct: Int get() = if (totalKb <= 0) 0 else (100 - availKb * 100 / totalKb).toInt()
    val availPct: Int get() = if (totalKb <= 0) 0 else (availKb * 100 / totalKb).toInt()
    val swapUsedKb: Long get() = swapTotalKb - swapFreeKb
}

data class RamCandidate(val pkg: String, val label: String, val ramKb: Long?, val system: Boolean)
data class RamCandidates(val items: List<RamCandidate>, val skipped: List<Triple<String, String, String>>, val note: String) // skipped: pkg, label, reason

data class RamOptions(val kill: Boolean = true, val forceStop: Boolean = false, val trim: Boolean = false, val dropCaches: Boolean = false, val compact: Boolean = false)

data class RamResult(val ts: Long, val label: String, val killed: Int, val before: RamStat, val after: RamStat, val actions: List<String>, val auto: Boolean) {
    val freedKb: Long get() = after.availKb - before.availKb
    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("label", label).put("killed", killed).put("auto", auto)
        .put("beforeAvail", before.availKb).put("afterAvail", after.availKb).put("beforeFree", before.freeKb).put("afterFree", after.freeKb)
        .put("beforeCached", before.cachedKb).put("afterCached", after.cachedKb).put("beforeSwapUsed", before.swapUsedKb).put("afterSwapUsed", after.swapUsedKb)
        .put("total", before.totalKb).put("actions", JSONArray(actions))
}

enum class RamPreset(val label: String, val impact: String, val opts: RamOptions) {
    GENTLE("Gentle (recommended)", "Closes only cached background processes (non-root: killBackgroundProcesses, root: `am kill`). No cache dropping. Apps you reopen reload from storage, battery impact is small.", RamOptions(kill = true, forceStop = false)),
    BALANCED("Balanced", "Force-stops the selected apps (root) and asks them to trim memory. They restart cold when opened (slower launch, more battery use).", RamOptions(kill = true, forceStop = true, trim = true)),
    AGGRESSIVE("Aggressive", "Force-stop + trim + compact memory + drop page caches. Drops file caches that Android would have reused, so the next minute can feel slower and use more battery. Use rarely.", RamOptions(kill = true, forceStop = true, trim = true, dropCaches = true, compact = true)),
}

/** RAM Optimizer (P). Android already manages memory; aggressive killing usually costs speed and battery. All kill paths go through [Rules.blockReason]. */
object RamOptimizer {
    fun read(): RamStat {
        val txt = runCatching { File("/proc/meminfo").readText() }.getOrDefault("")
        fun g(k: String) = Regex("(?m)^$k:\\s+(\\d+)").find(txt)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val free = g("MemFree"); val cached = g("Cached"); val buf = g("Buffers")
        val avail = g("MemAvailable").takeIf { it > 0 } ?: (free + cached + buf)
        return RamStat(g("MemTotal"), free, avail, cached, buf, g("SwapTotal"), g("SwapFree"))
    }

    /** Preview list: background apps with RAM (root: PSS from dumpsys meminfo) minus everything that must not be touched (reasons returned for transparency). */
    fun candidates(ctx: Context, includeSystem: Boolean = false): RamCandidates {
        val snap = Monitor.snapshot(ctx, true)
        val items = mutableListOf<RamCandidate>(); val skipped = mutableListOf<Triple<String, String, String>>()
        for (a in snap.apps.sortedByDescending { it.pssKb ?: 0L }) {
            val why = Rules.blockReason(ctx, a.pkg, ignoreActive = false)
            if (why != null) { skipped += Triple(a.pkg, a.label, why); continue }
            if (a.system && !includeSystem) { skipped += Triple(a.pkg, a.label, "System app (hidden unless 'include system apps')"); continue }
            items += RamCandidate(a.pkg, a.label, a.pssKb, a.system)
        }
        return RamCandidates(items, skipped, snap.appsNote)
    }

    fun optionsSupport(): Map<String, Boolean> = mapOf(
        "root" to Root.isRooted,
        "trim" to (Root.isRooted && Build.VERSION.SDK_INT >= 21),
        "drop_caches" to (dev.optimizer.android.core.Priv.rootLike && File("/proc/sys/vm/drop_caches").exists()),
        "compact" to (dev.optimizer.android.core.Priv.rootLike && File("/proc/sys/vm/compact_memory").exists()),
    )

    fun run(ctx: Context, selected: List<RamCandidate>, opts: RamOptions, label: String, auto: Boolean = false): RamResult {
        val before = read(); val actions = mutableListOf<String>(); var killed = 0
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (c in selected) {
            // the gate is re-checked at execution time: rules and foreground state can change between preview and click
            val why = Rules.blockReason(ctx, c.pkg, ignoreActive = false)
            if (why != null) { actions += "skipped ${c.label}: $why"; continue }
            if (opts.trim && Root.isRooted) Sh.root("am send-trim-memory --user 0 ${Sh.q(c.pkg)} RUNNING_CRITICAL", 15).also { if (!it.ok) actions += "trim ${c.pkg}: unsupported (${it.text.take(60)})" }
            if (opts.kill) {
                if (Root.isRooted) {
                    val r = Sh.root(if (opts.forceStop) "am force-stop ${Sh.q(c.pkg)}" else "am kill ${Sh.q(c.pkg)}", 15)
                    if (r.ok) killed++ else actions += "${c.pkg}: ${r.text.take(60)}"
                } else if (runCatching { am.killBackgroundProcesses(c.pkg) }.isSuccess) killed++
            }
        }
        actions += "closed $killed of ${selected.size} selected apps (${if (Root.isRooted) if (opts.forceStop) "am force-stop" else "am kill" else "killBackgroundProcesses"})"
        if (Root.isRooted) {
            if (opts.compact) { val r = Sh.root("[ -e /proc/sys/vm/compact_memory ] && echo 1 > /proc/sys/vm/compact_memory && echo ok", 20); actions += if (r.out.contains("ok")) "compacted memory" else "compact_memory not available" }
            if (opts.dropCaches) { val r = Sh.root("sync; echo 3 > /proc/sys/vm/drop_caches && echo ok", 30); actions += if (r.out.contains("ok")) "dropped page/dentry caches (temporary)" else "drop_caches failed: ${r.text.take(60)}" }
        }
        Thread.sleep(1200) // let the kernel/LMK settle so 'after' reflects the result
        val after = read()
        val res = RamResult(System.currentTimeMillis(), label, killed, before, after, actions, auto)
        addHistory(res)
        if (Root.isRooted && Backups.hasBaseline()) ChangeLog.add("maintenance", "ram:run", "RAM optimize: $label", "closed $killed apps; ${actions.joinToString("; ").take(200)}", emptyList())
        return res
    }

    // ---------------- history ----------------
    @Synchronized private fun addHistory(r: RamResult) {
        val a = runCatching { JSONArray(Store.ramHistoryJson) }.getOrDefault(JSONArray())
        val out = JSONArray(); out.put(r.toJson())
        for (i in 0 until minOf(a.length(), 49)) out.put(a.get(i))
        Store.ramHistoryJson = out.toString()
    }
    fun history(): List<JSONObject> = runCatching { val a = JSONArray(Store.ramHistoryJson); (0 until a.length()).map { a.getJSONObject(it) } }.getOrDefault(emptyList())
    fun clearHistory() { Store.ramHistoryJson = "[]" }

    // ---------------- kernel tunables (root, reversible, sanity-limited) ----------------
    data class Tunables(val swappiness: Int?, val vfs: Int?, val minFree: Long?, val zramBytes: Long?, val zramPresent: Boolean)
    data class TunePreset(val key: String, val label: String, val note: String, val swappiness: Int, val vfs: Int, val minFreeFactor: Double?)

    val presets = listOf(
        TunePreset("balanced", "Balanced", "swappiness 60, vfs_cache_pressure 100: common Android/Linux defaults.", 60, 100, null),
        TunePreset("aggressive", "Aggressive", "swappiness 100 (swap/zRAM earlier), vfs_cache_pressure 150 (reclaim dentry/inode caches sooner), min_free_kbytes +25%. Frees RAM sooner, can cost I/O and CPU.", 100, 150, 1.25),
        TunePreset("gaming", "Gaming", "swappiness 30 (less swapping latency), vfs_cache_pressure 80 (keep file caches), min_free_kbytes unchanged. Fewer stalls, less free RAM for background apps.", 30, 80, null),
    )

    private fun rd(p: String) = Sh.root("cat $p 2>/dev/null").out.trim()
    fun readTunables(): Tunables {
        val z = rd("/sys/block/zram0/disksize").toLongOrNull()
        return Tunables(rd("/proc/sys/vm/swappiness").toIntOrNull(), rd("/proc/sys/vm/vfs_cache_pressure").toIntOrNull(), rd("/proc/sys/vm/min_free_kbytes").toLongOrNull(), z, z != null)
    }

    /** Computes exact apply/undo commands with limits: swappiness 0..100, vfs_cache_pressure 50..200, min_free_kbytes within [50%, 150%] of current and <= 4% of RAM. */
    fun tunePlan(p: TunePreset, includeMinFree: Boolean, cur: Tunables, ramKb: Long): Plan {
        val apply = mutableListOf<String>(); val undo = mutableListOf<String>()
        cur.swappiness?.let { apply += "echo ${p.swappiness.coerceIn(0, 100)} > /proc/sys/vm/swappiness"; undo += "echo $it > /proc/sys/vm/swappiness" }
        cur.vfs?.let { apply += "echo ${p.vfs.coerceIn(50, 200)} > /proc/sys/vm/vfs_cache_pressure"; undo += "echo $it > /proc/sys/vm/vfs_cache_pressure" }
        if (includeMinFree && p.minFreeFactor != null && cur.minFree != null && cur.minFree > 0) {
            val target = (cur.minFree * p.minFreeFactor).toLong().coerceIn(cur.minFree / 2, cur.minFree * 3 / 2).coerceAtMost(ramKb * 4 / 100)
            if (target != cur.minFree) { apply += "echo $target > /proc/sys/vm/min_free_kbytes"; undo += "echo ${cur.minFree} > /proc/sys/vm/min_free_kbytes" }
        }
        return Plan(apply, undo)
    }

    fun applyTune(p: TunePreset, includeMinFree: Boolean): Op {
        if (!Root.isRooted || !Backups.hasBaseline()) return Op(false, "Needs root and the baseline backup.")
        val cur = readTunables(); val ram = read().totalKb
        val plan = tunePlan(p, includeMinFree, cur, ram)
        if (plan.apply.isEmpty()) return Op(false, "No supported tunables found on this kernel.")
        for (c in plan.apply) { val r = Sh.root(c, 15); if (!r.ok) { plan.undo.forEach { Sh.root(it) }; return Op(false, "`$c` failed and everything was rolled back: ${r.text}") } }
        ChangeLog.add("ram", "ram:tune", "RAM tuning: ${p.label}", "${p.note} Runtime values only: they reset at reboot.", plan.undo)
        return Op(true, "Applied ${p.label}: ${plan.apply.joinToString("; ")}")
    }
    fun undoTune(): Op {
        val es = ChangeLog.all().filter { it.group == "ram:tune" && !it.undone && it.undoable }.reversed()
        if (es.isEmpty()) return Op(false, "Nothing to undo")
        val errs = es.mapNotNull { ChangeLog.undo(it) }
        return if (errs.isEmpty()) Op(true, "Tunables restored") else Op(false, errs.joinToString("\n"))
    }

    /** zRAM resize (expert). Only if zram0 exists, swapoff/mkswap/swapon exist and swap is barely used. Size limited to 25%..100% of RAM, at least 256 MB. */
    fun zramPlan(newBytes: Long, cur: Tunables, ram: RamStat): Pair<Plan?, String> {
        val old = cur.zramBytes ?: return null to "No zRAM device (/sys/block/zram0) on this device."
        val maxB = ram.totalKb * 1024; val minB = maxOf(256L * 1024 * 1024, ram.totalKb * 1024 / 4)
        if (newBytes !in minB..maxB) return null to "Size must be between ${minB / 1048576} MB and ${maxB / 1048576} MB."
        if (ram.swapTotalKb > 0 && ram.swapUsedKb * 100 > ram.swapTotalKb * 20) return null to "zRAM is in use (${ram.swapUsedKb / 1024} MB). Resizing now could kill apps; try again after a reboot or when swap is mostly free."
        fun seq(sz: Long) = listOf("swapoff /dev/block/zram0", "echo 1 > /sys/block/zram0/reset", "echo $sz > /sys/block/zram0/disksize", "mkswap /dev/block/zram0", "swapon /dev/block/zram0")
        return Plan(seq(newBytes), seq(old)) to "ok"
    }

    fun applyZram(newBytes: Long): Op {
        if (!Root.isRooted || !Backups.hasBaseline()) return Op(false, "Needs root and the baseline backup.")
        val (plan, why) = zramPlan(newBytes, readTunables(), read())
        if (plan == null) return Op(false, why)
        for (t in listOf("swapoff", "mkswap", "swapon")) if (!Sh.root("command -v $t || which $t").ok) return Op(false, "`$t` is missing on this ROM, zRAM resize not supported.")
        for (c in plan.apply) { val r = Sh.root(c, 30); if (!r.ok) { plan.undo.forEach { Sh.root(it, 30) }; return Op(false, "`$c` failed, previous size restored: ${r.text}") } }
        ChangeLog.add("ram", "ram:zram", "zRAM disksize ${newBytes / 1048576} MB", "Resized zram0; runtime only (reset at reboot).", plan.undo)
        return Op(true, "zRAM is now ${newBytes / 1048576} MB")
    }
    fun undoZram(): Op {
        val es = ChangeLog.all().filter { it.group == "ram:zram" && !it.undone && it.undoable }.reversed()
        if (es.isEmpty()) return Op(false, "Nothing to undo")
        val errs = es.mapNotNull { ChangeLog.undo(it) }
        return if (errs.isEmpty()) Op(true, "zRAM restored") else Op(false, errs.joinToString("\n"))
    }

    // ---------------- auto optimize ----------------
    data class AutoPrefs(val thresholdPct: Int = 12, val thresholdOn: Boolean = false, val screenOff: Boolean = false, val scheduleHours: Int = 0, val preset: String = "GENTLE", val minIntervalMin: Int = 15, val maxPerHour: Int = 3) {
        fun toJson() = JSONObject().put("thresholdPct", thresholdPct).put("thresholdOn", thresholdOn).put("screenOff", screenOff).put("scheduleHours", scheduleHours).put("preset", preset).put("minIntervalMin", minIntervalMin).put("maxPerHour", maxPerHour).toString()
    }
    fun autoPrefs(): AutoPrefs = runCatching {
        val j = JSONObject(Store.ramPrefs)
        AutoPrefs(j.optInt("thresholdPct", 12), j.optBoolean("thresholdOn"), j.optBoolean("screenOff"), j.optInt("scheduleHours"), j.optString("preset", "GENTLE"), j.optInt("minIntervalMin", 15), j.optInt("maxPerHour", 3))
    }.getOrDefault(AutoPrefs())
    fun saveAutoPrefs(p: AutoPrefs) { Store.ramPrefs = p.toJson() }

    private val recentRuns = ArrayDeque<Long>()

    /** Rate-limited automatic run. Returns the result, or null if skipped (limit, nothing to close, screen on for 'screen-off' trigger ...). */
    @Synchronized
    fun autoRun(ctx: Context, reason: String): RamResult? {
        val p = autoPrefs(); val now = System.currentTimeMillis()
        while (recentRuns.isNotEmpty() && now - recentRuns.first() > 3_600_000L) recentRuns.removeFirst()
        if (recentRuns.isNotEmpty() && now - recentRuns.last() < p.minIntervalMin * 60_000L) return null
        if (recentRuns.size >= p.maxPerHour) return null
        val preset = runCatching { RamPreset.valueOf(p.preset) }.getOrDefault(RamPreset.GENTLE)
        val cands = candidates(ctx, includeSystem = false)
        // gentle auto runs only touch apps that really use memory (>= 40 MB with root); non-root has no per-app numbers
        val sel = cands.items.filter { it.ramKb == null || it.ramKb >= 40 * 1024 }
        if (sel.isEmpty()) return null
        recentRuns.addLast(now)
        val res = run(ctx, sel, preset.opts, "auto: $reason", auto = true)
        notifySummary(ctx, res)
        return res
    }

    private fun notifySummary(ctx: Context, r: RamResult) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        Compat.channel(ctx, "ram", "RAM optimizer", 2)
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val mb = r.freedKb / 1024
        runCatching {
            NotificationManagerCompat.from(ctx).notify(12, Compat.builder(ctx, "ram").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("RAM optimized (${r.label.removePrefix("auto: ")})")
                .setContentText("Closed ${r.killed} apps, available RAM ${if (mb >= 0) "+" else ""}$mb MB (${r.before.availKb / 1024} → ${r.after.availKb / 1024} MB)").setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    fun scheduleWorker(ctx: Context, hours: Int) {
        val wm = WorkManager.getInstance(ctx)
        if (hours <= 0) { wm.cancelUniqueWork("ram_sched"); return }
        wm.enqueueUniquePeriodicWork("ram_sched", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<RamWorker>(hours.toLong(), TimeUnit.HOURS).build())
    }
}

class RamWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        // no su prompts from a background worker: without a verified root in this process this is a non-root (killBackgroundProcesses) run
        runCatching { RamOptimizer.autoRun(applicationContext, "schedule") }
        return Result.success()
    }
}

/** Low-power memory watcher: samples /proc/meminfo (cheap), reacts to the threshold and to screen-off; never prompts for root. */
class RamAutoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rcv: BroadcastReceiver? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        Compat.channel(this, "ramsvc", "RAM monitor", 2)
        val n = Compat.builder(this, "ramsvc").setContentTitle("RAM monitor active").setContentText("Low-power sampling; optimizes only when your trigger fires")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
        if (!Compat.startForegroundSafe(this, 10, n)) { Store.ramAutoEnabled = false; stopSelf(); return START_NOT_STICKY }
        if (rcv == null) {
            rcv = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (RamOptimizer.autoPrefs().screenOff) scope.launch { runCatching { RamOptimizer.autoRun(applicationContext, "screen off") } } } }
            ContextCompat.registerReceiver(this, rcv, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        scope.launch {
            if (!Root.isRooted && Store.rootGrantedBefore) Root.request() // silent when the root manager remembers the grant
            while (isActive) {
                val p = RamOptimizer.autoPrefs()
                if (p.thresholdOn && RamOptimizer.read().availPct < p.thresholdPct) runCatching { RamOptimizer.autoRun(applicationContext, "available RAM below ${p.thresholdPct}%") }
                delay(if (Features.lowEnd()) 60_000L else 30_000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); rcv?.let { runCatching { unregisterReceiver(it) } }; super.onDestroy() }

    companion object {
        fun start(ctx: Context) { Store.ramAutoEnabled = true; Compat.startFgService(ctx, Intent(ctx, RamAutoService::class.java)) }
        fun stop(ctx: Context) { Store.ramAutoEnabled = false; ctx.stopService(Intent(ctx, RamAutoService::class.java)) }
    }
}
