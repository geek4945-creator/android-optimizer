package dev.optimizer.android.features

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Protected
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Proc(
    val pid: Int, val ppid: Int, val uid: Int, val rssKb: Long, val cpu: Float?, val startMs: Long?, val name: String,
    val pkg: String?, val label: String, val critical: String?, val score: Int, val reasons: List<String>, val banned: Boolean,
) {
    val level: String get() = when { score >= 6 -> "High" ; score >= 3 -> "Medium"; else -> "Low" }
}

/** Process list + explainable heuristic scoring. This is NOT malware detection: it only counts capabilities and behaviours that deserve a look. */
object Processes {
    const val DISCLAIMER = "Suspicion score is a transparent heuristic over real device facts (permissions, autostart, enabled special services, install source, resource use, network flags). " +
        "It is not an antivirus verdict: many legitimate apps score Medium or High. Read the reasons before acting."

    private val sensitivePerms = listOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_SMS", "RECEIVE_SMS", "READ_CONTACTS", "READ_CALL_LOG", "READ_PHONE_STATE")
    private val knownStores = setOf("com.android.vending", "org.fdroid.fdroid", "com.amazon.venezia", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "com.bbk.appstore", "com.aurora.store", "com.looker.droidify", "com.google.android.packageinstaller", "com.android.packageinstaller")

    private fun parseEtime(s: String): Long? {
        // [[dd-]hh:]mm:ss
        return runCatching {
            val d = if (s.contains('-')) s.substringBefore('-').toLong() else 0L
            val t = s.substringAfter('-').split(":").map { it.toLong() }
            val sec = when (t.size) { 3 -> t[0] * 3600 + t[1] * 60 + t[2]; 2 -> t[0] * 60 + t[1]; else -> t[0] }
            d * 86400 + sec
        }.getOrNull()
    }

    private class Facts(val installer: String?, val system: Boolean, val launcher: Boolean, val sensitive: List<String>, val boot: Boolean, val overlay: Boolean, val a11y: Boolean, val notifL: Boolean, val admin: Boolean, val targetSdk: Int, val lastUsed: Long?, val label: String)

    /** toolbox ps prints names like u0_a123 (user 0, app 123 => uid 10123), root, system, shell... */
    private fun legacyUid(user: String): Int {
        Regex("u(\\d+)_a(\\d+)").matchEntire(user)?.let { return it.groupValues[1].toInt() * 100000 + 10000 + it.groupValues[2].toInt() }
        Regex("u(\\d+)_i(\\d+)").matchEntire(user)?.let { return it.groupValues[1].toInt() * 100000 + 99000 + it.groupValues[2].toInt() }
        return when (user) { "root" -> 0; "system" -> 1000; "radio" -> 1001; "shell" -> 2000; "media" -> 1013; "wifi" -> 1010; "bluetooth" -> 1002; "nfc" -> 1027; else -> 1 }
    }

    fun list(ctx: Context): Pair<List<Proc>, String> {
        val rooted = Root.isRooted
        val r = Sh.run("ps -A -o PID,PPID,UID,RSS,PCPU,ETIME,ARGS", rooted, 30)
        val out = ArrayList<Proc>()
        val now = System.currentTimeMillis()
        val pm = ctx.packageManager
        val a11y = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
        val notif = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        val admins = runCatching { (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).activeAdmins?.map { it.packageName }?.toSet() }.getOrNull().orEmpty()
        val usage = runCatching { Monitor.hasUsageAccess(ctx) }.getOrDefault(false)
        val lastUsed = if (usage) runCatching { recentUsageMap(ctx) }.getOrDefault(emptyMap()) else emptyMap()
        val factCache = HashMap<String, Facts?>()
        val beaconPkgs = runCatching { Net.analyzeBeacons().map { it.pkg }.toSet() }.getOrDefault(emptySet())
        val blocked = Store.blocked().map { it.pkg }.toSet()
        var lines = r.out.lines().drop(1).filter { it.isNotBlank() }
        var legacyNote = ""
        if (lines.none { it.trim().split(Regex("\\s+"), 7).size >= 7 && it.trim().split(Regex("\\s+"))[0].toIntOrNull() != null }) {
            // Android 5-7 toolbox `ps` has neither -A nor -o. Format: USER PID PPID VSIZE RSS WCHAN PC [S] NAME (RSS in pages). No CPU / start time.
            val legacy = Sh.run("ps", rooted, 30).out.lines().drop(1).mapNotNull { l ->
                val f = l.trim().split(Regex("\\s+")); if (f.size < 8) return@mapNotNull null
                val pid = f[1].toIntOrNull() ?: return@mapNotNull null
                "$pid ${f[2]} ${legacyUid(f[0])} ${(f[4].toLongOrNull() ?: 0L) * 4} - - ${f.last()}"
            }
            if (legacy.isNotEmpty()) { lines = legacy; legacyNote = " Legacy `ps` (Android < 8): CPU and start time are not available." }
        }
        for (l in lines) {
            val t = l.trim().split(Regex("\\s+"), 7)
            if (t.size < 7) continue
            val pid = t[0].toIntOrNull() ?: continue
            val uid = t[2].toIntOrNull() ?: continue
            val name = t[6].split(" ")[0]
            var pkg: String? = null
            if (uid % 100000 >= 10000 || name.contains('.') && !name.contains('/')) {
                val cand = name.substringBefore(':')
                pkg = if (!cand.contains('/') && runCatching { pm.getApplicationInfo(cand, 0); true }.getOrDefault(false)) cand
                else pm.getPackagesForUid(uid)?.firstOrNull()
            } else if (uid in 1001..9999) pkg = null
            val etime = parseEtime(t[5])
            var facts: Facts? = null
            if (pkg != null) facts = factCache.getOrPut(pkg) {
                runCatching {
                    val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
                    val ai = pi.applicationInfo!!
                    val granted = (pi.requestedPermissions ?: emptyArray()).filterIndexed { i, _ -> (pi.requestedPermissionsFlags?.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
                    val sens = sensitivePerms.filter { p -> granted.any { it == "android.permission.$p" } }
                    val boot = pi.receivers?.any { rc -> pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(pkg), 0).any { it.activityInfo.name == rc.name } } == true
                    val overlay = runCatching {
                        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                        @Suppress("DEPRECATION") ops.checkOpNoThrow("android:system_alert_window", ai.uid, pkg) == AppOpsManager.MODE_ALLOWED
                    }.getOrDefault(false)
                    Facts(Updates.installerOf(ctx, pkg), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, pm.getLaunchIntentForPackage(pkg) != null, sens, boot, overlay,
                        a11y.contains("$pkg/"), notif.contains("$pkg/"), pkg in admins, ai.targetSdkVersion, lastUsed[pkg], pm.getApplicationLabel(ai).toString())
                }.getOrNull()
            }
            val cpu = t[4].replace("%", "").toFloatOrNull()
            val rss = t[3].toLongOrNull() ?: 0L
            val reasons = mutableListOf<String>(); var score = 0
            fun add(p: Int, why: String) { score += p; reasons += "+$p $why" }
            if (facts != null && pkg != null && !facts.system) {
                if (facts.a11y) add(3, "has an enabled accessibility service (can read the screen / inject input)")
                if (facts.admin) add(3, "is an active device administrator")
                if (facts.notifL) add(2, "has notification-listener access")
                if (facts.sensitive.size >= 3) add(2, "granted ${facts.sensitive.size} sensitive permissions: ${facts.sensitive.joinToString()}")
                else if (facts.sensitive.isNotEmpty() && facts.lastUsed.let { it != null && now - it > 7 * 86400_000L }) add(1, "sensitive permissions (${facts.sensitive.joinToString()}) but not used for over 7 days, yet running")
                if (facts.overlay) add(1, "allowed to draw over other apps")
                if (!facts.launcher) add(1, "running without a launcher icon (no visible entry point)")
                if (facts.boot) add(1, "starts automatically at boot")
                if (facts.installer == null || facts.installer !in knownStores) add(1, "not installed by a known app store (installer: ${facts.installer ?: "none"})")
                if (facts.label.contains(Regex("(?i)system|update|service|android|google|security")) && (facts.installer == null || facts.installer !in knownStores) && !pkg.startsWith("com.google.") && !pkg.startsWith("com.android."))
                    add(2, "name suggests a system component ('${facts.label}') but it is a user app from an unknown source")
                if (cpu != null && cpu > 20f) add(2, "high CPU over its lifetime (%.0f%% avg)".format(cpu))
                if (rss > 500_000) add(1, "uses ${rss / 1024} MB RAM")
                if (facts.targetSdk < Assessment.OLD_TARGET_SDK) add(1, "targets old API ${facts.targetSdk}")
                val (trk, flg) = runCatching { NetDb.flagCounts(pkg) }.getOrDefault(0 to 0)
                if (trk > 0) add(3, "contacted hosts on the tracker list ($trk connection records)")
                if (flg - trk > 3) add(1, "$flg network connection records carry flags (uncommon port / cleartext / no reverse DNS)")
                if (pkg in beaconPkgs) add(3, "regular-interval network beaconing observed")
            }
            val crit = when {
                uid in 0..9999 -> "System/native process (uid $uid)"
                pkg != null -> Protected.reasons(ctx, pkg) ?: if (facts?.system == true && !facts.launcher) "System component" else null
                else -> "Native process"
            }
            out += Proc(pid, t[1].toIntOrNull() ?: 0, uid, rss, cpu, etime?.let { now - it * 1000 }, name, pkg, facts?.label ?: name, crit, score, reasons, pkg != null && pkg in blocked)
        }
        val note = if (lines.isEmpty() || (!rooted && out.size < 5))
            "Without root Android shows only this app's own processes (hidepid). Grant root for the full list." else if (rooted) "Root: full process list. RAM = RSS (not PSS). CPU = lifetime average from `ps`, not an instantaneous value." else "Limited list."
        return out.sortedWith(compareByDescending<Proc> { it.score }.thenByDescending { it.rssKb }) to (note + legacyNote)
    }

    private fun recentUsageMap(ctx: Context): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 30L * 86400_000L, now).associate { it.packageName to it.lastTimeUsed }
    }

    fun fmtStart(ms: Long?) = if (ms == null) "-" else SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(ms))

    // ---- actions (critical packages are always refused) ----
    fun stop(ctx: Context, p: Proc): Op {
        p.critical?.let { return Op(false, "${p.name}: protected ($it)") }
        val pkg = p.pkg ?: return Op(false, "${p.name}: not an app process")
        val r = AppControl.close(ctx, pkg)
        return Op(r.ok, r.message)
    }

    fun ban(ctx: Context, p: Proc): Op {
        p.critical?.let { return Op(false, "${p.name}: protected ($it)") }
        val pkg = p.pkg ?: return Op(false, "${p.name}: not an app process")
        if (!Root.isRooted) { AppControl.keepClosedNonRoot(pkg); AppControl.close(ctx, pkg); return Op(true, "$pkg added to watch list (no root: cannot be enforced; use settings → Force stop)") }
        val r = AppControl.keepClosedRoot(ctx, pkg, p.label)
        return Op(r.ok, r.message)
    }

    fun unban(ctx: Context, p: Proc): Op { val pkg = p.pkg ?: return Op(false, "not an app"); val r = AppControl.allow(ctx, pkg); return Op(r.ok, r.message) }
}

// =====================================================================================================
enum class SnapCat(val label: String) {
    PACKAGES("Package enabled/disabled states"), PERMISSIONS("Runtime permissions"), APPOPS("App-ops modes (user apps)"),
    ACCESSIBILITY("Accessibility services"), NOTIFLISTENERS("Notification listeners"), DEVADMINS("Device administrators (remove extras)"), NETBLOCKS("Network blocks made by this app"),
}

data class SnapInfo(val id: String, val label: String, val ts: Long, val stock: Boolean, val summary: String)

/** "Stock snapshot": records processes, package states, permissions, app-ops, accessibility, device-admin, notification-listener settings and network blocks; restorable by category. */
object Snapshots {
    fun list(): List<SnapInfo> = (Store.snapshotsDir().listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
        runCatching { val j = JSONObject(f.readText()); SnapInfo(j.getString("id"), j.getString("label"), j.getLong("ts"), j.optBoolean("stock"), j.optString("summary")) }.getOrNull()
    }.sortedByDescending { it.ts }

    private fun file(id: String) = File(Store.snapshotsDir(), "$id.json")

    fun delete(id: String) { file(id).delete() }

    fun create(ctx: Context, label: String, stock: Boolean, progress: (String) -> Unit = {}): Result<SnapInfo> = runCatching {
        check(Root.isRooted) { "Root is required for a complete snapshot." }
        val ts = System.currentTimeMillis()
        val id = (if (stock) "stock-" else "snap-") + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ts))
        val j = JSONObject().put("id", id).put("label", label).put("ts", ts).put("stock", stock)
        progress("Processes")
        val (procs, _) = Processes.list(ctx)
        j.put("processes", JSONArray().also { a -> procs.forEach { a.put(JSONObject().put("pid", it.pid).put("uid", it.uid).put("name", it.name).put("rssKb", it.rssKb).put("score", it.score)) } })
        progress("Package states")
        val dis = Sh.root("pm list packages -d", 60).out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        val all = Sh.root("pm list packages", 60).out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        val pk = JSONObject(); all.forEach { pk.put(it, if (it in dis) "disabled" else "enabled") }; j.put("packages", pk)
        progress("Runtime permissions (dumpsys package)")
        val dump = Sh.root("dumpsys package packages", 180).out
        val perms = JSONObject(); var cur: String? = null; var inRt = false
        val hdr = Regex("^\\s*Package \\[([^]]+)]"); val pl = Regex("^\\s+([A-Za-z0-9_.]+): granted=(true|false)")
        for (line in dump.lineSequence()) {
            hdr.find(line)?.let { cur = it.groupValues[1]; inRt = false }
            val c = cur ?: continue; val t = line.trim()
            if (t == "runtime permissions:") { inRt = true; continue }
            if (inRt) { val m = pl.find(line); if (m != null) (perms.optJSONObject(c) ?: JSONObject().also { perms.put(c, it) }).put(m.groupValues[1], m.groupValues[2] == "true") else if (t.isNotEmpty() && !t.contains(": granted=")) inRt = false }
        }
        j.put("permissions", perms)
        progress("App-ops (user apps)")
        val pm = ctx.packageManager
        val userPk = pm.getInstalledApplications(0).filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }.map { it.packageName }.take(200)
        val ops = JSONObject()
        if (userPk.isNotEmpty() && Compat.hasAppopsCli) {
            val script = userPk.joinToString("\n") { "echo '##$it'; ${Compat.appopsCmd} get $it 2>/dev/null" }
            val txt = Sh.root(script, 240).out
            var p: String? = null; val rx = Regex("^([A-Z_0-9]+): (allow|ignore|deny|foreground|default)")
            for (line in txt.lineSequence()) {
                if (line.startsWith("##")) { p = line.removePrefix("##").trim(); continue }
                val m = rx.find(line.trim()) ?: continue
                val pp = p ?: continue
                (ops.optJSONObject(pp) ?: JSONObject().also { ops.put(pp, it) }).put(m.groupValues[1], m.groupValues[2])
            }
        }
        j.put("appops", ops)
        progress("Accessibility / notification listeners / device admins")
        j.put("accessibility", JSONObject().put("enabled_accessibility_services", Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "")
            .put("accessibility_enabled", Settings.Secure.getString(ctx.contentResolver, "accessibility_enabled") ?: "0"))
        j.put("notifListeners", Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "")
        val dp = Sh.root("dumpsys device_policy", 30).out
        val admins = Regex("ComponentInfo\\{([^}]+)}").findAll(dp.substringAfter("Enabled Device Admins", "").substringBefore("\n\n")).map { it.groupValues[1] }.toSet()
        j.put("deviceAdmins", JSONArray(admins.toList()))
        j.put("netBlocks", JSONArray(Net.blocks().map { JSONObject().put("uid", it.uid).put("pkg", it.pkg).put("method", it.method) }.toList().let { l -> l.map { it } }))
        val summary = "${procs.size} processes, ${pk.length()} packages (${dis.size} disabled), ${perms.length()} pkgs with permissions, ${ops.length()} pkgs with app-ops, ${admins.size} device admins, ${Net.blocks().size} net blocks"
        j.put("summary", summary)
        file(id).writeText(j.toString())
        SnapInfo(id, label, ts, stock, summary)
    }

    fun restore(ctx: Context, id: String, cats: Set<SnapCat>, progress: (String) -> Unit = {}): String {
        if (!Root.isRooted) return "Root is required."
        val j = runCatching { JSONObject(file(id).readText()) }.getOrNull() ?: return "Snapshot not found."
        val log = StringBuilder()
        if (SnapCat.PACKAGES in cats) {
            progress("Packages")
            val st = j.getJSONObject("packages")
            val dis = Sh.root("pm list packages -d", 60).out.lines().map { it.removePrefix("package:").trim() }.toSet()
            val inst = Sh.root("pm list packages", 60).out.lines().map { it.removePrefix("package:").trim() }.toSet()
            val cmds = StringBuilder(); var n = 0
            for (p in st.keys()) { if (p !in inst) continue; val want = st.getString(p) == "disabled"; val isD = p in dis
                if (!want && isD) { cmds.append("pm enable ${Sh.q(p)}\n"); n++ } else if (want && !isD && !Protected.isProtected(ctx, p)) { cmds.append("pm disable-user --user 0 ${Sh.q(p)}\n"); n++ } }
            if (n > 0) Sh.root(cmds.toString(), 600); log.append("packages: $n changes\n")
            Store.blocked().forEach { if (st.optString(it.pkg) == "enabled") Store.removeBlocked(it.pkg) }
        }
        if (SnapCat.PERMISSIONS in cats && Compat.hasRuntimePerms) {
            progress("Permissions"); val pj = j.getJSONObject("permissions"); val c = StringBuilder(); var n = 0
            for (p in pj.keys()) { val o = pj.getJSONObject(p); for (perm in o.keys()) { c.append(if (o.getBoolean(perm)) "pm grant ${Sh.q(p)} $perm 2>/dev/null\n" else "pm revoke ${Sh.q(p)} $perm 2>/dev/null\n"); n++ } }
            if (n > 0) Sh.root(c.toString(), 900); log.append("permissions: $n re-applied\n")
        }
        if (SnapCat.APPOPS in cats && Compat.hasAppopsCli) {
            progress("App-ops"); val oj = j.getJSONObject("appops"); val c = StringBuilder(); var n = 0
            for (p in oj.keys()) { val o = oj.getJSONObject(p); for (op in o.keys()) { c.append("${Compat.appopsCmd} set ${Sh.q(p)} $op ${o.getString(op)} 2>/dev/null\n"); n++ } }
            if (n > 0) Sh.root(c.toString(), 900); log.append("appops: $n re-applied\n")
        }
        if (SnapCat.ACCESSIBILITY in cats) {
            val a = j.getJSONObject("accessibility"); val s = a.optString("enabled_accessibility_services")
            Sh.root(if (s.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${Sh.q(s)}")
            Sh.root("settings put secure accessibility_enabled ${Sh.q(a.optString("accessibility_enabled", "0"))}"); log.append("accessibility: restored\n")
        }
        if (SnapCat.NOTIFLISTENERS in cats) {
            val s = j.optString("notifListeners")
            Sh.root(if (s.isEmpty()) "settings delete secure enabled_notification_listeners" else "settings put secure enabled_notification_listeners ${Sh.q(s)}"); log.append("notification listeners: restored\n")
        }
        if (SnapCat.DEVADMINS in cats) {
            val keep = (0 until j.getJSONArray("deviceAdmins").length()).map { j.getJSONArray("deviceAdmins").getString(it) }.toSet()
            val now = Regex("ComponentInfo\\{([^}]+)}").findAll(Sh.root("dumpsys device_policy", 30).out.substringAfter("Enabled Device Admins", "").substringBefore("\n\n")).map { it.groupValues[1] }.toSet()
            var n = 0
            for (x in now - keep) { if (Protected.isProtected(ctx, x.substringBefore('/'))) continue; if (Sh.root("dpm remove-active-admin ${Sh.q(x)}").ok) n++ }
            log.append("device admins: removed $n extra (best effort; some admins can't be removed from a shell). Admins that existed at snapshot time but are gone now are NOT re-added.\n")
        }
        if (SnapCat.NETBLOCKS in cats) {
            val keep = (0 until j.getJSONArray("netBlocks").length()).map { j.getJSONArray("netBlocks").getJSONObject(it).let { o -> o.getString("pkg") + "|" + o.getString("method") } }.toSet()
            var n = 0
            for (b in Net.blocks()) if ("${b.pkg}|${b.method}" !in keep) { Net.unblock(b.pkg, b.method); n++ }
            log.append("network blocks: removed $n that were not in the snapshot\n")
        }
        ChangeLog.add("restore", "snap:$id", "Restored snapshot $id", cats.joinToString { it.name }, emptyList())
        return log.toString().trim()
    }
}
