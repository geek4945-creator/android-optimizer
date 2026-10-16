package dev.optimizer.android.features

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Stronger, reversible enforcement levels for 'Keep closed' rules (root). Every level records its undo in the change log. */
object Enforce {
    private val ops = listOf("RUN_IN_BACKGROUND" to 26, "RUN_ANY_IN_BACKGROUND" to 28)

    /** Exact commands for the preview, and the undo list built from values read first. */
    fun plan(pkg: String, mode: String): Plan {
        val q = Sh.q(pkg)
        return when (mode) {
            Rules.MODE_BGRESTRICT -> {
                val apply = mutableListOf<String>(); val undo = mutableListOf<String>()
                for ((op, min) in ops) if (Build.VERSION.SDK_INT >= min && Compat.hasAppopsCli) {
                    val prev = Regex("$op:\\s*(\\w+)").find(Sh.root("${Compat.appopsCmd} get $q $op").out)?.groupValues?.get(1) ?: "default"
                    apply += "${Compat.appopsCmd} set $q $op ignore"; undo += "${Compat.appopsCmd} set $q $op $prev"
                }
                if (Compat.hasDoze) {
                    val wl = Sh.root("dumpsys deviceidle whitelist").out
                    if (Regex("(^|\\n)user,${Regex.escape(pkg)},").containsMatchIn(wl)) { apply += "dumpsys deviceidle whitelist -$pkg"; undo += "dumpsys deviceidle whitelist +$pkg" }
                    apply += "am set-inactive $q true"; undo += "am set-inactive $q false"
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    val prev = Sh.root("am get-standby-bucket $q").out.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && !it.contains("rror") && !it.contains("nknown") }
                    apply += "am set-standby-bucket $q restricted"; if (prev != null) undo += "am set-standby-bucket $q ${Sh.q(prev)}"
                }
                Plan(apply, undo)
            }
            Rules.MODE_FREEZE -> Plan(listOf("am force-stop $q", "pm disable-user --user 0 $q"), listOf("pm enable $q"))
            else -> Plan(listOf("am force-stop $q"), emptyList())
        }
    }

    fun apply(ctx: Context, pkg: String, mode: String): Op {
        if (mode == Rules.MODE_KILL) return Op(true, "Kill-on-start needs no system change.")
        if (!Root.isRooted || !Backups.hasBaseline()) return Op(false, "Needs root and the baseline backup.")
        Rules.blockReason(ctx, pkg, true)?.let { return Op(false, "Refused: $it") }
        if (mode == Rules.MODE_FREEZE) {
            val r = AppControl.keepClosedRoot(ctx, pkg, Rules.labelOf(ctx, pkg)); return Op(r.ok, r.message)
        }
        val p = plan(pkg, mode)
        for (c in p.apply) { val r = Sh.root(c, 30); if (!r.ok && !c.contains("set-inactive") && !c.contains("deviceidle")) { for (u in p.undo) Sh.root(u); return Op(false, "`$c` failed: ${r.text}") } }
        ChangeLog.add("rule", "rule:$pkg:$mode", "Background restriction: $pkg", "appops RUN_(ANY_)IN_BACKGROUND ignore, deviceidle, set-inactive, standby bucket restricted", p.undo)
        return Op(true, "Background-restricted $pkg (reversible in the change log).")
    }

    fun undo(pkg: String, mode: String): Op {
        if (mode == Rules.MODE_FREEZE) { val r = AppControl.allow(Store.appCtx, pkg); return Op(r.ok, r.message) }
        val es = ChangeLog.all().filter { it.group == "rule:$pkg:$mode" && !it.undone && it.undoable }
        if (es.isEmpty()) return Op(true, "Nothing to undo")
        val errs = es.mapNotNull { ChangeLog.undo(it) }
        return if (errs.isEmpty()) Op(true, "Restored") else Op(false, errs.joinToString("\n"))
    }
}

/**
 * Keep-closed guard. Re-closes apps that have a 'Keep closed' rule when they start (by the user, another app, a broadcast, job, alarm or boot).
 * Root: event source `logcat -b events` (am_proc_start) in one persistent su shell; falls back to polling `ps`. No root: polling with
 * killBackgroundProcesses (it cannot stop an app that is in the foreground, and Android lets apps restart themselves; this is best effort).
 * Rate limit: more than [LIMIT] closes in [WINDOW_MS] puts the app in a cool-down and posts a prompt suggesting Freeze instead.
 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var proc: Process? = null
    private val history = HashMap<String, ArrayDeque<Long>>()
    private val cooldownUntil = HashMap<String, Long>()
    private val lastNotify = HashMap<String, Long>()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        Compat.channel(this, "guard", "Keep-closed guard", 2)
        Compat.channel(this, "guard_alert", "Blocked app start", 3)
        val n = Compat.builder(this, "guard").setContentTitle("Keep-closed guard running")
            .setContentText(if (Root.isRooted) "Watching app starts (root)" else "Re-closing apps periodically (no root: best effort)")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock).setOngoing(true).build()
        if (!Compat.startForegroundSafe(this, 9, n)) { Store.guardEnabled = false; stopSelf(); return START_NOT_STICKY }
        job?.cancel()
        job = scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        // try the event source first (root only); the reader thread feeds started package names
        var eventMode = false
        if (dev.optimizer.android.core.Priv.realRoot) eventMode = startEventReader()
        while (scope.isActive) {
            val rules = Rules.all().filter { it.keepClosed }
            if (!eventMode) pollOnce(rules)
            delay(if (eventMode) 20_000L else Features.guardPollMs)
            if (eventMode && proc?.let { runCatching { it.exitValue(); true }.getOrDefault(false) } == true) eventMode = false // reader died: fall back to polling
        }
    }

    private fun startEventReader(): Boolean = runCatching {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.apply { write("exec logcat -b events -v brief -T 1 -s am_proc_start\n".toByteArray()); flush() }
        proc = p
        Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { l ->
                    // I/am_proc_start( 1234): [0,5678,10123,com.foo,service,com.foo/.Svc]
                    val m = Regex("am_proc_start.*\\[\\d+,\\d+,\\d+,([^,]+),").find(l) ?: return@forEachLine
                    val pkg = m.groupValues[1].substringBefore(':')
                    if (Rules.isKeepClosed(pkg)) scope.launch { onStart(pkg, if (l.contains(",activity,")) "activity" else "background") }
                }
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(1500)
        runCatching { p.exitValue(); false }.getOrDefault(true) // exited within 1.5 s = logcat not usable
    }.getOrDefault(false)

    private fun pollOnce(rules: List<Rule2>) {
        if (rules.isEmpty()) return
        val running: Set<String> = if (Root.isRooted) {
            val out = Sh.root("ps -A -o NAME 2>/dev/null || ps", 15).out
            out.lineSequence().map { it.trim().substringAfterLast(' ').substringBefore(':') }.toSet()
        } else runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses?.map { it.processName.substringBefore(':') }?.toSet()
        }.getOrNull().orEmpty()
        for (r in rules) if (r.pkg in running || !Root.isRooted) {
            // without root we cannot see other apps' processes, so we simply ask Android to drop their background processes each tick
            if (Root.isRooted) onStart(r.pkg, "running") else closeNonRoot(r)
        }
    }

    private fun closeNonRoot(r: Rule2) {
        if (!allowed(r)) return
        runCatching { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(r.pkg) }
    }

    private fun allowed(r: Rule2): Boolean {
        if (System.currentTimeMillis() < r.allowUntil) return false
        if ((cooldownUntil[r.pkg] ?: 0L) > System.currentTimeMillis()) return false
        return Rules.blockReason(this, r.pkg, ignoreActive = true) == null // never the foreground app, protected packages or 'never stop'
    }

    private fun onStart(pkg: String, how: String) {
        val r = Rules.get(pkg)?.takeIf { it.keepClosed } ?: return
        if (!allowed(r)) return
        // kill-loop protection
        val h = history.getOrPut(pkg) { ArrayDeque() }
        val now = System.currentTimeMillis()
        while (h.isNotEmpty() && now - h.first() > WINDOW_MS) h.removeFirst()
        if (h.size >= LIMIT) {
            cooldownUntil[pkg] = now + COOLDOWN_MS; h.clear()
            notifyStart(pkg, r.label, "$how: restarted more than $LIMIT times in ${WINDOW_MS / 60000} min - paused for ${COOLDOWN_MS / 60000} min. Freeze it instead?", prompt = true)
            return
        }
        h.addLast(now)
        if (Root.isRooted) Sh.root("am force-stop ${Sh.q(pkg)}", 15)
        else runCatching { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) }
        notifyStart(pkg, r.label, "Started ($how) and was closed again.", prompt = false)
    }

    private fun notifyStart(pkg: String, label: String, text: String, prompt: Boolean) {
        val now = System.currentTimeMillis()
        if (!prompt && now - (lastNotify[pkg] ?: 0L) < 5 * 60_000L) return
        lastNotify[pkg] = now
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        fun act(a: String, title: String, id: Int): NotificationCompat.Action {
            val i = Intent(this, GuardReceiver::class.java).setAction(a).putExtra("pkg", pkg)
            val pi = PendingIntent.getBroadcast(this, (pkg.hashCode() and 0xffff) * 4 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            return NotificationCompat.Action(0, title, pi)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val b = Compat.builder(this, "guard_alert").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("$label tried to start").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .addAction(act(GuardReceiver.ALLOW_ONCE, "Allow this time", 0)).addAction(act(GuardReceiver.ALLOW_ALWAYS, "Allow always", 1)).addAction(act(GuardReceiver.KEEP, if (prompt) "Keep blocked" else "Keep blocked", 2))
        if (prompt && Root.isRooted) b.addAction(act(GuardReceiver.FREEZE, "Freeze", 3))
        runCatching { NotificationManagerCompat.from(this).notify(1000 + (pkg.hashCode() and 0xfff), b.build()) }
    }

    override fun onDestroy() { scope.cancel(); runCatching { proc?.destroy() }; super.onDestroy() }

    companion object {
        const val LIMIT = 5; const val WINDOW_MS = 10 * 60_000L; const val COOLDOWN_MS = 30 * 60_000L
        fun start(ctx: Context) { Store.guardEnabled = true; Compat.startFgService(ctx, Intent(ctx, GuardService::class.java)) }
        fun stop(ctx: Context) { Store.guardEnabled = false; ctx.stopService(Intent(ctx, GuardService::class.java)) }
    }
}
private typealias Rule2 = dev.optimizer.android.core.Rule

/** Notification actions of the guard: Allow this time (30 min), Allow always (remove rule), Keep blocked, Freeze. */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Store.init(ctx.applicationContext)
        val pkg = i.getStringExtra("pkg") ?: return
        when (i.action) {
            ALLOW_ONCE -> Rules.allowFor(pkg, 30)
            ALLOW_ALWAYS -> { Rules.get(pkg)?.let { Thread { if (it.mode != Rules.MODE_KILL) Enforce.undo(pkg, it.mode) }.start() }; Rules.remove(pkg) }
            FREEZE -> Thread { Rules.setMode(pkg, Rules.MODE_FREEZE); Enforce.apply(ctx.applicationContext, pkg, Rules.MODE_FREEZE) }.start()
            else -> {}
        }
        runCatching { NotificationManagerCompat.from(ctx).cancel(1000 + (pkg.hashCode() and 0xfff)) }
    }
    companion object {
        const val ALLOW_ONCE = "dev.optimizer.android.GUARD_ALLOW_ONCE"
        const val ALLOW_ALWAYS = "dev.optimizer.android.GUARD_ALLOW_ALWAYS"
        const val KEEP = "dev.optimizer.android.GUARD_KEEP"
        const val FREEZE = "dev.optimizer.android.GUARD_FREEZE"
    }
}
