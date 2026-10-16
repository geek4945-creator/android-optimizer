package dev.optimizer.android.features

import android.accessibilityservice.AccessibilityService
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Rules
import dev.optimizer.android.core.Store
import org.json.JSONArray
import java.io.FileInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

// ======================================================================= accessibility force stop (opt-in, no root)
/**
 * Taps the system 'Force stop' button of an app's App-info screen. Only acts on packages queued by the user's own button press.
 * UI texts differ per ROM/language: the resource id is tried first, then a list of common translations, so it can fail on unusual ROMs.
 */
class ForceStopService : AccessibilityService() {
    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    private val forceTexts = listOf("force stop", "forcer l'arrêt", "beenden erzwingen", "forzar detención", "forzar parada", "forçar parada", "forza arresto", "принудительно остановить", "强行停止", "强制停止", "強制停止", "강제 종료", "zatrzymaj", "durmaya zorla")
    private val okTexts = listOf("ok", "force stop", "forcer l'arrêt", "beenden", "forzar", "forçar", "forza", "oк", "确定", "确认", "強制停止", "확인", "tamam")

    private fun findClickable(root: AccessibilityNodeInfo, ids: List<String>, texts: List<String>): AccessibilityNodeInfo? {
        for (id in ids) root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { return it }
        for (t in texts) root.findAccessibilityNodeInfosByText(t)?.firstOrNull { it.isClickable || it.parent?.isClickable == true && it.className?.toString()?.contains("Button") != false }?.let { return it }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null) { if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK); c = c.parent }
        return false
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val cur = current ?: run { next(); return }
        val root = rootInActiveWindow ?: return
        if (System.currentTimeMillis() - startedAt > 9000) { result("${cur}: timed out (no Force stop button found)"); advance(); return }
        // confirmation dialog first
        val dlg = findClickable(root, listOf("android:id/button1"), emptyList())
        if (stage == 1 && dlg != null && clickNode(dlg)) { result("$cur: force-stopped via accessibility"); advance(); return }
        val btn = findClickable(root, listOf("com.android.settings:id/force_stop_button", "com.android.settings:id/left_button"), forceTexts)
        if (stage == 0 && btn != null) {
            if (!btn.isEnabled) { result("$cur: already stopped (button disabled)"); advance(); return }
            if (clickNode(btn)) stage = 1
        }
    }

    private fun advance() { performGlobalAction(GLOBAL_ACTION_BACK); current = null; stage = 0; next() }
    private fun next() {
        if (current != null) return
        val p = queue.poll() ?: return
        current = p; stage = 0; startedAt = System.currentTimeMillis()
        runCatching { startActivity(AppControl.settingsIntent(p)) }.onFailure { result("$p: cannot open App info"); current = null }
    }

    companion object {
        @Volatile var inst: ForceStopService? = null
        private val queue = ConcurrentLinkedQueue<String>()
        @Volatile private var current: String? = null
        @Volatile private var stage = 0
        @Volatile private var startedAt = 0L
        val results = ConcurrentLinkedQueue<String>()
        private fun result(s: String) { results.add(s); while (results.size > 30) results.poll() }
        fun enabled(): Boolean = inst != null
        /** Queues packages (protected packages and own app are refused). Returns how many were queued. */
        fun forceStop(ctx: Context, pkgs: List<String>): Int {
            val s = inst ?: return 0
            var n = 0
            for (p in pkgs) { if (Protected.isProtected(ctx, p) || Rules.isNever(p)) { result("$p: protected / Never stop"); continue }; if (p !in queue && p != current) { queue.add(p); n++ } }
            s.next(); return n
        }
    }
}

// ======================================================================= local VPN firewall (opt-in, no root)
/**
 * Routes ONLY the chosen apps into a VPN tunnel that nobody reads from: their packets go nowhere, i.e. the apps are offline.
 * Everything else bypasses the tunnel. Limits: Android allows one VPN at a time (this replaces any other VPN while active);
 * only blocked apps' attempts are visible (destination counters; the packet does not carry the app name); IPv4/IPv6 both routed.
 */
class FirewallVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { teardown(); stopSelf(); return START_NOT_STICKY }
        Store.init(applicationContext)
        teardown()
        val pkgs = blocked()
        if (pkgs.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        try {
            val b = Builder().setSession("Optimizer firewall").addAddress("10.77.0.2", 32).addRoute("0.0.0.0", 0)
            runCatching { b.addAddress("fd00:77::2", 128).addRoute("::", 0) }
            var added = 0
            for (p in pkgs) runCatching { b.addAllowedApplication(p); added++ }
            if (added == 0) { stopSelf(); return START_NOT_STICKY }
            tun = b.establish()
        } catch (t: Throwable) { Store.putStr("vpn_error", t.message ?: "establish failed"); stopSelf(); return START_NOT_STICKY }
        val fd = tun ?: run { Store.putStr("vpn_error", "VPN permission missing or another VPN took over"); stopSelf(); return START_NOT_STICKY }
        running = true; active = true; Store.putStr("vpn_error", "")
        thread = Thread {
            val inp = FileInputStream(fd.fileDescriptor); val buf = ByteArray(2048)
            while (running) {
                val n = runCatching { inp.read(buf) }.getOrDefault(-1)
                if (n <= 0) { if (!running) break; continue } // packets are read and discarded = blackhole
                dropped.incrementAndGet()
                if (n >= 20 && (buf[0].toInt() shr 4) == 4) { val ip = "${buf[16].toInt() and 255}.${buf[17].toInt() and 255}.${buf[18].toInt() and 255}.${buf[19].toInt() and 255}"; synchronized(dests) { dests[ip] = (dests[ip] ?: 0) + 1; if (dests.size > 400) dests.clear() } }
            }
        }.apply { isDaemon = true; start() }
        return START_STICKY
    }

    private fun teardown() { running = false; active = false; runCatching { tun?.close() }; tun = null; thread?.interrupt(); thread = null }
    override fun onDestroy() { teardown(); super.onDestroy() }
    override fun onRevoke() { teardown(); super.onRevoke() }

    companion object {
        const val ACTION_STOP = "dev.optimizer.android.VPN_STOP"
        @Volatile var active = false
        val dropped = AtomicLong()
        val dests = HashMap<String, Int>()
        fun blocked(): List<String> = runCatching { JSONArray(Store.getStr("vpn_blocked", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        fun setBlocked(l: List<String>) = Store.putStr("vpn_blocked", JSONArray(l.distinct()).toString())
        /** Returns an intent the UI must launch for the one-time VPN consent, or null if already granted. */
        fun prepare(ctx: Context): Intent? = VpnService.prepare(ctx)
        fun start(ctx: Context) { ctx.startService(Intent(ctx, FirewallVpnService::class.java)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, FirewallVpnService::class.java).setAction(ACTION_STOP)) }
        fun topDests(n: Int = 8): List<Pair<String, Int>> = synchronized(dests) { dests.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value } }
    }
}

// ======================================================================= per-app network usage (NetworkStatsManager, no root)
data class NetUse(val pkg: String, val label: String, val wifiBytes: Long, val mobileBytes: Long)

object NetUsage {
    fun perApp(ctx: Context, days: Int): List<NetUse> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis(); val start = end - days * 86_400_000L
        fun collect(type: Int): Map<Int, Long> {
            val m = HashMap<Int, Long>()
            runCatching {
                val st = nsm.querySummary(type, null, start, end); val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) { st.getNextBucket(b); m[b.uid] = (m[b.uid] ?: 0L) + b.rxBytes + b.txBytes }
                st.close()
            }
            return m
        }
        val wifi = collect(ConnectivityManager.TYPE_WIFI); val mob = collect(ConnectivityManager.TYPE_MOBILE)
        val pm = ctx.packageManager
        val rows = (wifi.keys + mob.keys).distinct().mapNotNull { uid ->
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull when (uid) { 1000 -> NetUse("android", "Android system", wifi[uid] ?: 0, mob[uid] ?: 0); else -> null }
            NetUse(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg), wifi[uid] ?: 0, mob[uid] ?: 0)
        }
        return rows.sortedByDescending { it.wifiBytes + it.mobileBytes }.take(25)
    }
}
