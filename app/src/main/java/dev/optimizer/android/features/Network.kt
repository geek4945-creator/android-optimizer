package dev.optimizer.android.features

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.IBinder
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Root
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class Conn(
    val proto: String, val lip: String, val lport: Int, val rip: String, val rport: Int, val state: Int, val uid: Int, val inode: Long,
) {
    val key: String get() = "$proto|$uid|$lport|$rip|$rport|$inode"
}

data class ConnView(
    val conn: Conn, val pkg: String, val label: String, val host: String?, val tracker: String?, val flags: List<String>,
)

data class Beacon(val pkg: String, val remote: String, val port: Int, val count: Int, val medianSec: Double, val jitterPct: Double, val first: Long, val last: Long)

data class TrackerList(val id: String, val label: String, val file: String, val sha256: String, val bytes: Int)

/** Network activity inspection (I). /proc/net/ files are unreadable for normal apps on Android 10+, so reading needs root. */
object Net {
    // ---------------- reading /proc/net ----------------
    private fun hexIp(h: String): String? = runCatching {
        if (h.length == 8) {
            val b = (0 until 4).map { h.substring(it * 2, it * 2 + 2).toInt(16) }.reversed()
            b.joinToString(".")
        } else if (h.length == 32) {
            val bytes = ByteArray(16)
            for (g in 0 until 4) for (k in 0 until 4) bytes[g * 4 + k] = h.substring(g * 8 + (3 - k) * 2, g * 8 + (3 - k) * 2 + 2).toInt(16).toByte()
            val a = InetAddress.getByAddress(bytes)
            // IPv4-mapped (::ffff:a.b.c.d) -> show IPv4
            a.hostAddress?.substringBefore('%')
        } else null
    }.getOrNull()

    private fun parse(proto: String, text: String): List<Conn> {
        val out = ArrayList<Conn>()
        for (l in text.lineSequence()) {
            val t = l.trim().split(Regex("\\s+"))
            if (t.size < 10 || !t[0].endsWith(":")) continue
            val (lipH, lpH) = t[1].split(":").let { it[0] to it.getOrElse(1) { "0" } }
            val (ripH, rpH) = t[2].split(":").let { it[0] to it.getOrElse(1) { "0" } }
            val lip = hexIp(lipH) ?: continue; val rip = hexIp(ripH) ?: continue
            val st = t[3].toIntOrNull(16) ?: continue
            out += Conn(proto, lip, lpH.toInt(16), rip, rpH.toInt(16), st, t[7].toIntOrNull() ?: continue, t[9].toLongOrNull() ?: 0L)
        }
        return out
    }

    data class Read(val conns: List<Conn>, val note: String?)

    fun readConnections(): Read {
        val files = listOf("tcp" to "/proc/net/tcp", "tcp6" to "/proc/net/tcp6", "udp" to "/proc/net/udp", "udp6" to "/proc/net/udp6")
        val all = mutableListOf<Conn>()
        var direct = false
        for ((p, f) in files) runCatching { val txt = File(f).readText(); direct = true; all += parse(p, txt) }
        var note: String? = null
        if (!direct) {
            if (!Root.isRooted) return Read(emptyList(), "Android blocks /proc/net/ files for normal apps (Android 10+). Grant root to read per-app connections.")
            for ((p, f) in files) { val r = Sh.root("cat $f 2>/dev/null"); if (r.out.isNotBlank()) all += parse(p, r.out) }
            if (all.isEmpty()) note = "No readable connections (root shell returned nothing)."
        }
        return Read(all.filter { keep(it) }, note)
    }

    private fun keep(c: Conn): Boolean {
        if (c.proto.startsWith("tcp") && (c.state == 0x0A || c.state == 0x06)) return false // LISTEN, TIME_WAIT
        if (c.rport == 0 || c.rip == "0.0.0.0" || c.rip == "::" || c.rip == "0:0:0:0:0:0:0:0") return false
        if (c.rip.startsWith("127.") || c.rip == "::1" || c.rip == "0:0:0:0:0:0:0:1") return false
        return true
    }

    // ---------------- flags ----------------
    private val commonPorts = setOf(80, 443, 53, 853, 993, 995, 465, 587, 143, 110, 123, 5228, 5229, 5230, 8080, 8443, 4244, 3478)
    private val cleartextPorts = setOf(80, 8080, 8000, 21, 23, 25, 110, 143, 1883)

    private val rdnsPool = Executors.newFixedThreadPool(4)
    private val rdnsCache = ConcurrentHashMap<String, String>()

    /** Reverse DNS (PTR) with a hard timeout; returns "" if none. Note: no visibility into the hostname the app really asked for. */
    fun reverse(ip: String): String? {
        rdnsCache[ip]?.let { return it.ifEmpty { null } }
        val f = rdnsPool.submit(Callable { InetAddress.getByName(ip).canonicalHostName })
        val h = runCatching { f.get(1500, TimeUnit.MILLISECONDS) }.getOrNull()
        if (h == null) { f.cancel(true); return null }
        val v = if (h == ip) "" else h
        rdnsCache[ip] = v
        return v.ifEmpty { null }
    }

    private fun isPrivate(ip: String) = ip.startsWith("10.") || ip.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip) || ip.startsWith("169.254.") || ip.startsWith("fe80") || ip.startsWith("fc") || ip.startsWith("fd")

    fun view(ctx: Context, c: Conn, resolve: Boolean): ConnView {
        val pm = ctx.packageManager
        val pkg = pm.getPackagesForUid(c.uid)?.firstOrNull() ?: if (c.uid == 0) "root" else if (c.uid == 1000) "system" else "uid:${c.uid}"
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val priv = isPrivate(c.rip)
        val host = if (resolve && !priv) reverse(c.rip) else rdnsCache[c.rip]?.ifEmpty { null }
        val tracker = Trackers.match(c.rip, host)
        val fl = mutableListOf<String>()
        if (tracker != null) fl += "tracker list: $tracker"
        if (!priv && c.rport !in commonPorts) fl += "uncommon port ${c.rport}"
        if (!priv && c.proto.startsWith("tcp") && c.rport in cleartextPorts) fl += "likely cleartext (port ${c.rport})"
        if (!priv && resolve && host == null && c.proto.startsWith("tcp")) fl += "no reverse DNS (raw-IP style)"
        return ConnView(c, pkg, label, host, tracker, fl)
    }

    fun cleartextAllowed(ctx: Context, pkg: String): Boolean = runCatching {
        (ctx.packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
    }.getOrDefault(false)

    // ---------------- beacon analysis ----------------
    fun analyzeBeacons(minEvents: Int = 6): List<Beacon> {
        val db = NetDb.get().readableDatabase
        val groups = HashMap<String, MutableList<Long>>()
        db.rawQuery("SELECT pkg,rip,rport,ts_first FROM conns ORDER BY ts_first", null).use { c ->
            while (c.moveToNext()) groups.getOrPut("${c.getString(0)}|${c.getString(1)}|${c.getInt(2)}") { ArrayList() }.add(c.getLong(3))
        }
        val out = mutableListOf<Beacon>()
        for ((k, ts) in groups) {
            if (ts.size < minEvents) continue
            val d = ts.zipWithNext { a, b -> (b - a) / 1000.0 }.filter { it >= 1.0 }
            if (d.size < minEvents - 1) continue
            val mean = d.average(); val sd = sqrt(d.map { (it - mean) * (it - mean) }.average())
            val sorted = d.sorted(); val med = sorted[sorted.size / 2]
            val cv = if (mean > 0) sd / mean else 1.0
            if (cv <= 0.25 && med >= 5 && med <= 4 * 3600) {
                val p = k.split("|")
                out += Beacon(p[0], p[1], p[2].toInt(), ts.size, med, cv * 100, ts.first(), ts.last())
            }
        }
        return out.sortedByDescending { it.count }
    }

    fun analyzeBeaconsFor(pkg: String): Boolean = runCatching { analyzeBeacons().any { it.pkg == pkg } }.getOrDefault(false)

    // ---------------- root network block ----------------
    data class BlockRec(val uid: Int, val pkg: String, val method: String)

    fun blocks(): List<BlockRec> = runCatching {
        val a = JSONArray(Store.netBlocksJson)
        (0 until a.length()).map { val o = a.getJSONObject(it); BlockRec(o.getInt("uid"), o.getString("pkg"), o.getString("method")) }
    }.getOrDefault(emptyList())

    private fun saveBlocks(l: List<BlockRec>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("uid", it.uid).put("pkg", it.pkg).put("method", it.method)) }
        Store.netBlocksJson = a.toString()
    }

    private fun cmdsFor(uid: Int, method: String, add: Boolean): List<String> = when (method) {
        "iptables" -> listOf("iptables -w -${if (add) "I" else "D"} OUTPUT -m owner --uid-owner $uid -j REJECT", "ip6tables -w -${if (add) "I" else "D"} OUTPUT -m owner --uid-owner $uid -j REJECT")
        else -> listOf("cmd netpolicy ${if (add) "add" else "remove"} restrict-background-blacklist $uid")
    }

    fun block(ctx: Context, pkg: String, method: String): Op {
        if (!Optimizer.unlocked()) return Op(false, "Locked: needs root and a baseline backup.")
        Protected.reasons(ctx, pkg)?.let { return Op(false, "Protected: $it") }
        val uid = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return Op(false, "Unknown package")
        if (blocks().any { it.uid == uid && it.method == method }) return Op(false, "Already blocked")
        for (c in cmdsFor(uid, method, true)) {
            val r = Sh.root(c)
            if (!r.ok && !c.startsWith("ip6tables")) { cmdsFor(uid, method, false).forEach { Sh.root(it) }; return Op(false, "`$c` failed: ${r.text}") }
        }
        saveBlocks(blocks() + BlockRec(uid, pkg, method))
        ChangeLog.add("network", "net:$pkg:$method", "Network block ($method): $pkg", "uid $uid. iptables rules do not survive a reboot; use Re-apply.", cmdsFor(uid, method, false))
        return Op(true, "Blocked $pkg via $method")
    }

    fun unblock(pkg: String, method: String): Op {
        val b = blocks().firstOrNull { it.pkg == pkg && it.method == method } ?: return Op(false, "Not blocked")
        cmdsFor(b.uid, method, false).forEach { Sh.root(it) }
        saveBlocks(blocks().filter { it != b })
        ChangeLog.all().filter { it.group == "net:$pkg:$method" && !it.undone }.forEach { ChangeLog.markUndone(it.id) }
        return Op(true, "Unblocked $pkg")
    }

    fun reapplyAll(): String {
        val sb = StringBuilder()
        for (b in blocks()) for (c in cmdsFor(b.uid, b.method, false) + cmdsFor(b.uid, b.method, true)) Sh.root(c).also { if (c.contains("-I") || c.contains("add")) sb.append("${b.pkg}: ${if (it.ok) "ok" else it.text.take(80)}\n") }
        return sb.toString().ifEmpty { "No blocks recorded." }
    }

    fun currentRules(): String = Sh.root("iptables -w -S OUTPUT 2>&1 | grep -i owner; cmd netpolicy list restrict-background-blacklist 2>&1").text.ifEmpty { "(none)" }
}

// =====================================================================================================
object NetDb {
    private var inst: Helper? = null
    class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "net.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE conns(id INTEGER PRIMARY KEY AUTOINCREMENT, ts_first INTEGER, ts_last INTEGER, uid INTEGER, pkg TEXT, proto TEXT, rip TEXT, rport INTEGER, lport INTEGER, inode INTEGER, host TEXT, flags TEXT, k TEXT)")
            db.execSQL("CREATE INDEX i_pkg ON conns(pkg, ts_first)"); db.execSQL("CREATE INDEX i_k ON conns(k)"); db.execSQL("CREATE INDEX i_ts ON conns(ts_last)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }
    @Synchronized fun get(): Helper = inst ?: Helper(Store.appCtx).also { inst = it }

    fun count(): Long = get().readableDatabase.rawQuery("SELECT COUNT(*) FROM conns", null).use { it.moveToFirst(); it.getLong(0) }
    fun clear() { get().writableDatabase.delete("conns", null, null) }
    fun prune(days: Int) {
        val db = get().writableDatabase
        db.delete("conns", "ts_last < ?", arrayOf((System.currentTimeMillis() - days * 86_400_000L).toString()))
        val n = count()
        if (n > 200_000) db.execSQL("DELETE FROM conns WHERE id IN (SELECT id FROM conns ORDER BY ts_first LIMIT ${n - 150_000})")
    }

    fun timeline(pkg: String, sinceMs: Long): List<ConnRow> {
        val l = ArrayList<ConnRow>()
        get().readableDatabase.rawQuery("SELECT ts_first,ts_last,rip,rport,proto,host,flags FROM conns WHERE pkg=? AND ts_first>=? ORDER BY ts_first DESC LIMIT 500", arrayOf(pkg, sinceMs.toString())).use { c ->
            while (c.moveToNext()) l += ConnRow(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6) ?: "")
        }
        return l
    }

    fun packagesWithData(): List<Pair<String, Int>> {
        val l = ArrayList<Pair<String, Int>>()
        get().readableDatabase.rawQuery("SELECT pkg, COUNT(*) c FROM conns GROUP BY pkg ORDER BY c DESC", null).use { c -> while (c.moveToNext()) l += c.getString(0) to c.getInt(1) }
        return l
    }

    /** For the process-suspicion score. */
    fun flagCounts(pkg: String): Pair<Int, Int> { // tracker hits, flagged rows
        get().readableDatabase.rawQuery("SELECT SUM(CASE WHEN flags LIKE '%tracker list%' THEN 1 ELSE 0 END), SUM(CASE WHEN flags!='' THEN 1 ELSE 0 END) FROM conns WHERE pkg=?", arrayOf(pkg)).use {
            return if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else 0 to 0
        }
    }

    data class ConnRow(val first: Long, val last: Long, val rip: String, val rport: Int, val proto: String, val host: String?, val flags: String)
}

/** One sampler tick: stores new connections, extends existing ones, prunes by retention. */
object Sampler {
    private val active = HashMap<String, Long>() // key -> row id

    fun tick(ctx: Context): String? {
        val rd = Net.readConnections()
        if (rd.conns.isEmpty()) return rd.note
        val db = NetDb.get().writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val seen = HashSet<String>()
            for (c in rd.conns) {
                val k = c.key; seen += k
                val id = active[k]
                if (id != null) { db.execSQL("UPDATE conns SET ts_last=? WHERE id=?", arrayOf(now, id)); continue }
                val v = Net.view(ctx, c, resolve = false)
                val cv = ContentValues().apply {
                    put("ts_first", now); put("ts_last", now); put("uid", c.uid); put("pkg", v.pkg); put("proto", c.proto); put("rip", c.rip)
                    put("rport", c.rport); put("lport", c.lport); put("inode", c.inode); put("host", v.host); put("flags", v.flags.joinToString("; ")); put("k", k)
                }
                active[k] = db.insert("conns", null, cv)
            }
            active.keys.retainAll(seen)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return null
    }

    fun reset() { active.clear() }
}

class NetSamplerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile var lastNote: String? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("netsampler", "Network sampler", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "netsampler").setContentTitle("Sampling network connections")
            .setContentText("Stored locally on this device; stop it in the Network screen").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(8, n)
        job?.cancel()
        job = scope.launch {
            var pruneAt = 0L
            if (!Root.isRooted) Root.request()
            while (isActive) {
                lastNote = runCatching { Sampler.tick(applicationContext) }.getOrElse { it.message }
                if (System.currentTimeMillis() > pruneAt) { runCatching { NetDb.prune(Store.netRetentionDays) }; pruneAt = System.currentTimeMillis() + 600_000 }
                delay(Store.netIntervalSec.coerceIn(3, 600) * 1000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); Sampler.reset(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) { Store.netSampling = true; ctx.startForegroundService(Intent(ctx, NetSamplerService::class.java)) }
        fun stop(ctx: Context) { Store.netSampling = false; ctx.stopService(Intent(ctx, NetSamplerService::class.java)) }
    }
}

// =====================================================================================================
/**
 * Tracker blocklist. Source: HaGeZi DNS Blocklists (https://github.com/hagezi/dns-blocklists), licensed GPL-3.0 (repository LICENSE).
 * Vendor "native tracker" lists (domains only). Not bundled: downloaded at runtime on user request from a raw.githubusercontent.com URL pinned
 * to commit 610042ccaa675ae2d51d55dab58b53f78be13128 and checked against the SHA-256 recorded when this app was built, so the content is fixed
 * and verifiable (it will be older than the live list). The lists are data used locally for matching only, not redistributed.
 * Matching is best-effort: the app only sees remote IPs, so domains are resolved to IPs (forward DNS) and remote IPs get a reverse-DNS lookup.
 */
object Trackers {
    const val COMMIT = "610042ccaa675ae2d51d55dab58b53f78be13128"
    const val LICENSE_NOTE = "HaGeZi DNS Blocklists — GPL-3.0 (https://github.com/hagezi/dns-blocklists/blob/main/LICENSE). Downloaded at runtime from a pinned commit, not bundled."
    val lists = listOf(
        TrackerList("xiaomi", "Xiaomi native trackers", "native.xiaomi-onlydomains.txt", "1faedc33691a5c0c5b2cbf78cb721e7c32f6a5dda26aee9ce4cf0914094fe94a", 9342),
        TrackerList("huawei", "Huawei native trackers", "native.huawei-onlydomains.txt", "6c90f7f28abf41f1c173899f83d4951703540a5e4662b4bedc40c451cdfb75a3", 5795),
        TrackerList("oppo-realme", "OPPO/Realme native trackers", "native.oppo-realme-onlydomains.txt", "b6b235428eb57daa108f6c62aed1ca6192e0bc8615bf41aa26018c186751c038", 15218),
        TrackerList("vivo", "vivo native trackers", "native.vivo-onlydomains.txt", "c9567cd5751c8f6798eda9bec1e9b6ccdaec998e2a7b4064cdce8f57e8235ddd", 7657),
        TrackerList("samsung", "Samsung native trackers", "native.samsung-onlydomains.txt", "f3ff6ded350e3580cddd06ae9b2c82ddcdf7bc39187e714c5dd0c83fc8b5f8ff", 6307),
        TrackerList("tiktok", "TikTok trackers", "native.tiktok-onlydomains.txt", "4c99f218ee2f9540786bb937be56563273332312178de91e49f3061fe951a844", 13524),
    )

    fun url(l: TrackerList) = "https://raw.githubusercontent.com/hagezi/dns-blocklists/$COMMIT/wildcard/${l.file}"
    private fun dir() = Store.netDir()
    private fun domFile(l: TrackerList) = File(dir(), "tracker_${l.id}.txt")
    private val domains = HashMap<String, String>() // domain -> list id
    private val ipMap = ConcurrentHashMap<String, String>() // ip -> domain
    @Volatile private var loaded = false

    fun downloaded(l: TrackerList) = domFile(l).exists()

    /** Downloads (HTTPS only) and verifies SHA-256 against the pinned value. Returns an error or null. */
    fun download(l: TrackerList): String? = try {
        val c = URL(url(l)).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (c.responseCode != 200) "HTTP ${c.responseCode}" else {
            val bytes = c.inputStream.use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (sha != l.sha256) "SHA-256 mismatch (expected ${l.sha256.take(12)}…, got ${sha.take(12)}…): refused" else { domFile(l).writeBytes(bytes); loaded = false; null }
        }
    } catch (e: Throwable) { "Download failed: ${e.message}" }

    fun delete(l: TrackerList) { domFile(l).delete(); loaded = false }

    @Synchronized private fun load() {
        if (loaded) return
        domains.clear(); ipMap.clear()
        for (l in lists) if (domFile(l).exists()) domFile(l).forEachLine { s -> val d = s.trim().lowercase(); if (d.isNotEmpty() && !d.startsWith("#")) domains[d] = l.id }
        File(dir(), "ipmap.tsv").takeIf { it.exists() }?.forEachLine { s -> val p = s.split('\t'); if (p.size == 2) ipMap[p[0]] = p[1] }
        loaded = true
    }

    fun domainCount(): Int { load(); return domains.size }
    fun ipCount(): Int { load(); return ipMap.size }

    /** Forward-resolves every listed domain (16 threads) so remote IPs can be matched. Can take a minute; best effort (CDN IPs change). */
    fun resolveAll(progress: (Int, Int) -> Unit): Int {
        load()
        val list = domains.keys.toList()
        val pool = Executors.newFixedThreadPool(16)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val found = ConcurrentHashMap<String, String>()
        val futures = list.map { d -> pool.submit { runCatching { InetAddress.getAllByName(d).forEach { a -> a.hostAddress?.let { found[it] = d } } }; progress(done.incrementAndGet(), list.size) } }
        futures.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
        pool.shutdownNow()
        // drop sinkhole answers
        found.keys.removeAll { it == "0.0.0.0" || it == "::" || it.startsWith("127.") }
        ipMap.putAll(found)
        File(dir(), "ipmap.tsv").writeText(ipMap.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        return found.size
    }

    fun match(ip: String, host: String?): String? {
        load()
        if (domains.isEmpty()) return null
        ipMap[ip]?.let { return "$it (IP match)" }
        if (host != null) {
            var h = host.lowercase().trimEnd('.')
            while (h.contains('.')) { if (domains.containsKey(h)) return "$h (reverse-DNS match)"; h = h.substringAfter('.') }
        }
        return null
    }
}
