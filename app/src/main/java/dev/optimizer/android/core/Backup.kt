package dev.optimizer.android.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupInfo(
    val id: String, val label: String, val ts: Long, val baseline: Boolean,
    val categories: List<String>, val device: String, val exported: Boolean, val notes: String,
)

enum class RestoreCat(val label: String) {
    SETTINGS("Settings (system / secure / global)"),
    PACKAGES("App enabled / disabled / suspended state"),
    PERMISSIONS("Runtime permission grants"),
    RULES("App rules (Never stop / Keep closed)"),
    VM_TUNABLES("Kernel memory tunables (swappiness, vfs_cache_pressure, min_free_kbytes)"),
}

object Backups {
    const val EXPORT_REL = "Documents/AndroidOptimizer"

    /** Keys that are never written back by a restore because they can lock the user out or break the device. */
    private val denyKeyParts = listOf(
        "provisioned", "setup_complete", "android_id", "adb_", "development_settings", "default_input_method",
        "enabled_input_methods", "airplane", "location_providers", "accessibility", "bluetooth_address",
        "bluetooth_name", "wifi_", "device_name", "install_non_market", "lock_", "user_switcher", "boot_count",
        "device_policy", "paired_", "secure_frp", "adaptive_", "cdma_", "mobile_data", "data_roaming", "usb_mass",
        "package_verifier", "play_protect", "assisted_gps", "tether", "default_", "assistant", "voice_interaction",
        "voice_recognition", "autofill", "credential", "cmas", "notification_listener", "enabled_notification",
        "sms_default", "dialer_default", "nfc_payment", "selected_input", "user_setup", "tv_", "zen_"
    )

    fun hasBaseline(): Boolean = list().any { it.baseline }

    fun list(): List<BackupInfo> {
        val root = Store.backupsDir()
        return (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            runCatching {
                val m = JSONObject(File(d, "manifest.json").readText())
                val cats = m.optJSONArray("categories") ?: JSONArray()
                BackupInfo(
                    m.getString("id"), m.optString("label", m.getString("id")), m.getLong("timestamp"),
                    m.optBoolean("baseline"), (0 until cats.length()).map { cats.getString(it) },
                    m.optString("device"), m.optBoolean("exported"), m.optString("notes")
                )
            }.getOrNull()
        }.sortedByDescending { it.ts }
    }

    private fun deviceString() = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}, ${Build.FINGERPRINT}"

    /**
     * Creates a backup. Requires root. appDataPkgs: user opt-in list of packages whose /data/data is tar'ed into the
     * exported folder (large). progress is called with human-readable steps.
     */
    fun create(
        ctx: Context, label: String, baseline: Boolean, appDataPkgs: List<String> = emptyList(),
        progress: (String) -> Unit = {},
    ): Result<BackupInfo> = runCatching {
        // works without root too: Settings are read through the Settings API and packages through PackageManager / `pm list` (what the app may read)
        val ts = System.currentTimeMillis()
        val id = (if (baseline) "baseline-" else "backup-") + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ts))
        val dir = File(Store.backupsDir(), id).also { it.mkdirs() }
        val cats = mutableListOf<String>()
        val notes = StringBuilder()

        for (ns in listOf("system", "secure", "global")) {
            progress("Dumping settings $ns")
            val r = Sh.root("settings list $ns", 60)
            if (r.ok && r.out.isNotBlank()) { File(dir, "settings_$ns.txt").writeText(r.out); cats += "settings_$ns" }
            else notes.append("settings list $ns failed: ${r.text}\n")
        }
        progress("Animation scales")
        val anim = JSONObject()
        for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            anim.put(k, Sh.root("settings get global $k").out.trim())
        }
        File(dir, "animation.json").writeText(anim.toString(2)); cats += "animation"

        progress("Package states")
        val enabled = Sh.root("pm list packages -e", 60).out
        val disabled = Sh.root("pm list packages -d", 60).out
        val all = Sh.root("pm list packages -u", 60).out
        val states = JSONObject()
        fun names(s: String) = s.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        val en = names(enabled).toSet(); val dis = names(disabled).toSet()
        for (p in names(all)) states.put(p, when { p in dis -> "disabled"; p in en -> "enabled"; else -> "uninstalled" })
        File(dir, "packages_raw.txt").writeText("# pm list packages -d -e -u\nENABLED:\n$enabled\nDISABLED:\n$disabled\nALL(-u):\n$all")

        progress("Parsing dumpsys package (suspended state + runtime permissions) - may take a while")
        val dump = Sh.root("dumpsys package packages", 180)
        val perms = JSONObject()
        if (dump.ok || dump.out.isNotBlank()) {
            var cur: String? = null
            var inRuntime = false
            val pkgHdr = Regex("^\\s*Package \\[([^]]+)]")
            val permLine = Regex("^\\s+([A-Za-z0-9_.]+): granted=(true|false)")
            val suspendedSet = mutableSetOf<String>()
            for (line in dump.out.lineSequence()) {
                pkgHdr.find(line)?.let { cur = it.groupValues[1]; inRuntime = false; return@let }
                val c = cur ?: continue
                val t = line.trim()
                if (t.contains("suspended=true")) suspendedSet += c
                if (t == "runtime permissions:") { inRuntime = true; continue }
                if (inRuntime) {
                    val m = permLine.find(line)
                    if (m != null) {
                        val o = perms.optJSONObject(c) ?: JSONObject().also { perms.put(c, it) }
                        o.put(m.groupValues[1], m.groupValues[2] == "true")
                    } else if (t.isNotEmpty() && !t.startsWith("android.permission") && !t.contains(": granted=")) inRuntime = false
                }
            }
            for (s in suspendedSet) if (states.has(s)) states.put(s, states.getString(s) + "+suspended")
            cats += "permissions"
        } else notes.append("dumpsys package failed: ${dump.text}\n")
        File(dir, "packages.json").writeText(states.toString(2)); cats += "packages"
        File(dir, "permissions.json").writeText(perms.toString(2))

        progress("User app / APK list")
        val apps = Sh.root("pm list packages -f -3 --show-versioncode", 60)
            .let { if (it.ok) it else Sh.root("pm list packages -f -3", 60) }
        File(dir, "apps_user.txt").writeText(apps.out); cats += "apps"

        progress("App rules and memory tunables")
        runCatching { File(dir, "rules.json").writeText(Rules.toJson()); cats += "rules" }
        runCatching {
            val t = dev.optimizer.android.features.RamOptimizer.readTunables()
            File(dir, "vm_tunables.json").writeText(JSONObject().put("swappiness", t.swappiness ?: JSONObject.NULL).put("vfs_cache_pressure", t.vfs ?: JSONObject.NULL)
                .put("min_free_kbytes", t.minFree ?: JSONObject.NULL).put("zram_disksize", t.zramBytes ?: JSONObject.NULL).toString(2)); cats += "vm_tunables"
        }

        progress("Build props")
        File(dir, "getprop.txt").writeText(Sh.root("getprop", 30).out); cats += "getprop"
        val buildProp = Sh.root("cat /system/build.prop 2>/dev/null | head -c 200000")
        if (buildProp.out.isNotBlank()) { File(dir, "build.prop.txt").writeText(buildProp.out); cats += "build.prop" }

        progress("Selected /data/system files (where readable)")
        val sys = JSONArray()
        for (path in listOf(
            "/data/system/packages.list", "/data/system/users/0/package-restrictions.xml",
            "/data/system/users/0/settings_system.xml", "/data/system/users/0/settings_secure.xml",
            "/data/system/users/0/settings_global.xml", "/data/system/users/0/runtime-permissions.xml",
            "/data/system/users/0/appops.xml", "/data/system/appops.xml", "/data/system/deviceidle.xml",
        )) {
            val sz = Sh.root("stat -c %s $path 2>/dev/null").out.trim().toLongOrNull() ?: continue
            if (sz <= 0 || sz > 6_000_000) continue
            val b64 = Sh.root("base64 $path", 60)
            if (!b64.ok || b64.out.isBlank()) continue
            runCatching {
                val bytes = Base64.decode(b64.out, Base64.DEFAULT)
                File(dir, "sys_" + path.removePrefix("/data/system/").replace('/', '_')).writeBytes(bytes)
                sys.put(path)
            }
        }
        if (sys.length() > 0) { File(dir, "system_files.json").writeText(sys.toString()); cats += "system_files" }

        var exported = false
        val manifest = JSONObject()
        fun writeManifest() {
            manifest.put("id", id).put("label", label).put("timestamp", ts).put("baseline", baseline)
                .put("categories", JSONArray(cats)).put("device", deviceString()).put("exported", exported)
                .put("notes", notes.toString().trim())
                .put("app_version", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?")).put("appdata_packages", JSONArray(appDataPkgs))
                .put("device_info", JSONObject()
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("board", Build.BOARD)
                    .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
                    .put("fingerprint", Build.FINGERPRINT).put("security_patch", Compat.securityPatch() ?: "unknown"))
                .put("device_profile", DeviceProfile.current()?.toJson() ?: JSONObject.NULL)
            File(dir, "manifest.json").writeText(manifest.toString(2))
        }
        writeManifest()

        progress("Exporting copy to $EXPORT_REL/$id")
        exported = exportCopy(ctx, dir, id)
        if (!exported) notes.append("Export to Documents failed (backup still saved in app storage).\n")

        if (appDataPkgs.isNotEmpty()) {
            val target = "/sdcard/$EXPORT_REL/$id"
            Sh.root("mkdir -p $target")
            for (p in appDataPkgs) {
                progress("tar app data: $p")
                val src = "/data/data/$p"
                val r = Sh.root("test -d $src && tar -cf $target/appdata-$p.tar -C /data/data $p", 900)
                if (r.ok) { cats += "appdata:$p" } else notes.append("tar $p failed: ${r.text}\n")
            }
            notes.append("App-data tarballs are exported only (not restored automatically).\n")
        }
        writeManifest()
        // refresh exported manifest copy
        if (exported) exportCopy(ctx, dir, id, onlyManifest = true)

        BackupInfo(id, label, ts, baseline, cats, deviceString(), exported, notes.toString().trim())
    }

    private fun exportCopy(ctx: Context, dir: File, id: String, onlyManifest: Boolean = false): Boolean {
        // user-chosen SAF folder first (works on every Android version without storage permission)
        if (SafFolder.uri() != null && !onlyManifest) { if (SafFolder.copyBackup(ctx, dir, id) == null) return true }
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile && (!onlyManifest || it.name == "manifest.json") }
        if (Build.VERSION.SDK_INT >= 29) {
            val ok = runCatching {
                val resolver = ctx.contentResolver
                val coll = MediaStore.Files.getContentUri("external")
                for (f in files) {
                    val rel = "$EXPORT_REL/$id/"
                    // remove previous copy of same name
                    resolver.delete(coll, "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(rel, f.name))
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, if (f.name.endsWith(".txt")) "text/plain" else if (f.name.endsWith(".json")) "application/json" else "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    }
                    val uri = resolver.insert(coll, cv) ?: error("insert failed")
                    resolver.openOutputStream(uri, "wt")!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                }
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        // fallback 1: root copy to /sdcard/Documents
        val tgt = "/sdcard/$EXPORT_REL/$id"
        if (Root.isRooted) {
            Sh.root("mkdir -p $tgt")
            var all = true
            for (f in files) all = Sh.root("cp ${Sh.q(f.absolutePath)} $tgt/ && chmod 666 $tgt/${f.name}", 60).ok && all
            if (all) return true
        }
        // fallback 2 (Android 5-9 without root, or MediaStore failure): app-specific external storage. Needs no storage permission on any
        // supported version; visible to file managers under Android/data/<package>/files/, removed on uninstall.
        return runCatching {
            val d = File(ctx.getExternalFilesDir(null), "$EXPORT_REL/$id").apply { mkdirs() }
            for (f in files) f.copyTo(File(d, f.name), overwrite = true)
            true
        }.getOrDefault(false)
    }

    fun delete(id: String): String? {
        val b = list().firstOrNull { it.id == id } ?: return "Not found"
        if (b.baseline) return "The baseline backup cannot be deleted (the Optimize tab depends on it)."
        File(Store.backupsDir(), id).deleteRecursively()
        if (Root.isRooted) Sh.root("rm -rf /sdcard/$EXPORT_REL/$id", 60)
        runCatching { File(Store.appCtx.getExternalFilesDir(null), "$EXPORT_REL/$id").deleteRecursively() }
        return null
    }

    private fun parseSettings(f: File): Map<String, String> {
        if (!f.exists()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        f.readLines().forEach { l ->
            val i = l.indexOf('=')
            if (i > 0) m[l.substring(0, i)] = l.substring(i + 1)
        }
        return m
    }

    /** Restores selected categories from backup `id`. Returns a human-readable log. */
    fun restore(ctx: Context, id: String, cats: Set<RestoreCat>, progress: (String) -> Unit = {}): String {
        if (!Priv.canChange()) return "Nothing can be restored: this app has no privileged channel (no root, no Shizuku) and no WRITE_SECURE_SETTINGS. Grant it from a PC: adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
        val dir = File(Store.backupsDir(), id)
        if (!dir.exists()) return "Backup not found."
        val log = StringBuilder()
        var changed = 0

        if (RestoreCat.RULES in cats) {
            val f = File(dir, "rules.json")
            if (f.exists()) runCatching { val (n, c) = Rules.importJson(f.readText(), replace = false); log.append("rules: imported $n ($c conflict(s) resolved, 'never stop' wins)\n") }.onFailure { log.append("rules: failed ${it.message}\n") }
            else log.append("rules: nothing saved\n")
        }
        if (RestoreCat.VM_TUNABLES in cats) {
            val f = File(dir, "vm_tunables.json")
            if (f.exists()) runCatching {
                val j = JSONObject(f.readText()); val c = StringBuilder()
                if (!j.isNull("swappiness")) c.append("echo ${j.getInt("swappiness")} > /proc/sys/vm/swappiness\n")
                if (!j.isNull("vfs_cache_pressure")) c.append("echo ${j.getInt("vfs_cache_pressure")} > /proc/sys/vm/vfs_cache_pressure\n")
                if (!j.isNull("min_free_kbytes")) c.append("echo ${j.getLong("min_free_kbytes")} > /proc/sys/vm/min_free_kbytes\n")
                if (c.isNotEmpty()) Sh.root(c.toString(), 30); log.append("vm tunables: restored (zRAM size is not touched by restore)\n")
            }.onFailure { log.append("vm tunables: failed ${it.message}\n") } else log.append("vm tunables: nothing saved\n")
        }
        if (RestoreCat.SETTINGS in cats) {
            for (ns in listOf("system", "secure", "global")) {
                progress("Restoring settings $ns")
                val saved = parseSettings(File(dir, "settings_$ns.txt"))
                if (saved.isEmpty()) { log.append("settings_$ns: nothing saved\n"); continue }
                val current = parseSettings(File.createTempFile("cur", ".txt", ctx.cacheDir).also {
                    it.writeText(Sh.root("settings list $ns", 60).out)
                })
                val cmds = StringBuilder()
                var n = 0
                for ((k, v) in saved) {
                    if (denyKeyParts.any { k.contains(it) }) continue
                    if (current[k] == v) continue
                    if (v == "null") continue
                    cmds.append("settings put $ns ${Sh.q(k)} ${Sh.q(v)}\n"); n++
                    if (n >= 3000) break
                }
                if (n > 0) {
                    val r = Sh.root(cmds.toString(), 300)
                    log.append("settings $ns: applied $n changed keys${if (r.err.isNotBlank()) " (some errors)" else ""}\n")
                    changed += n
                } else log.append("settings $ns: already matches\n")
            }
        }

        if (RestoreCat.PACKAGES in cats) {
            progress("Restoring package states")
            val st = runCatching { JSONObject(File(dir, "packages.json").readText()) }.getOrNull()
            if (st == null) log.append("packages: nothing saved\n") else {
                val cur = Sh.root("pm list packages -d", 60).out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
                val installed = Sh.root("pm list packages", 60).out.lines().map { it.removePrefix("package:").trim() }.toSet()
                var en = 0; var dis = 0
                val cmds = StringBuilder()
                for (p in st.keys()) {
                    if (p !in installed) continue
                    val s = st.getString(p)
                    val wantDisabled = s.startsWith("disabled")
                    val isDisabled = p in cur
                    if (!wantDisabled && isDisabled) { cmds.append("pm enable ${Sh.q(p)}\n"); en++ }
                    else if (wantDisabled && !isDisabled && !Protected.isProtected(ctx, p)) { cmds.append("pm disable-user --user 0 ${Sh.q(p)}\n"); dis++ }
                    if (s.endsWith("+suspended")) cmds.append("pm suspend --user 0 ${Sh.q(p)} 2>/dev/null\n")
                }
                if (cmds.isNotEmpty()) Sh.root(cmds.toString(), 600)
                log.append("packages: re-enabled $en, re-disabled $dis\n")
                changed += en + dis
                Store.blocked().forEach { b -> if (st.optString(b.pkg).startsWith("enabled")) Store.removeBlocked(b.pkg) }
            }
        }

        if (RestoreCat.PERMISSIONS in cats) {
            progress("Restoring runtime permissions")
            val pj = runCatching { JSONObject(File(dir, "permissions.json").readText()) }.getOrNull()
            if (pj == null) log.append("permissions: nothing saved\n") else {
                val cmds = StringBuilder(); var n = 0
                for (p in pj.keys()) {
                    val o = pj.getJSONObject(p)
                    for (perm in o.keys()) {
                        val g = o.getBoolean(perm)
                        if (!Compat.hasRuntimePerms) continue
                        cmds.append(if (g) "pm grant ${Sh.q(p)} $perm 2>/dev/null\n" else "pm revoke ${Sh.q(p)} $perm 2>/dev/null\n"); n++
                    }
                }
                if (n > 0) Sh.root(cmds.toString(), 900)
                log.append("permissions: re-applied $n grant states (errors for fixed/system permissions are ignored)\n")
            }
        }
        ChangeLog.add("restore", "restore:$id", "Restored from $id", "Categories: ${cats.joinToString { it.name }}; $changed changes", emptyList())
        return log.toString().trim().ifEmpty { "Nothing selected." }
    }

    /** Undo everything in the change log (newest first) and then restore the baseline. */
    fun revertEverything(ctx: Context, progress: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        val base = list().filter { it.baseline }.minByOrNull { it.ts } ?: return "No baseline exists."
        for (c in ChangeLog.activeUndoable().reversed()) {
            progress("Undo: ${c.title}")
            val e = ChangeLog.undo(c)
            sb.append(if (e == null) "Undone: ${c.title}\n" else "FAILED: ${c.title}: $e\n")
        }
        progress("Restoring baseline ${base.id}")
        sb.append(restore(ctx, base.id, setOf(RestoreCat.SETTINGS, RestoreCat.PACKAGES), progress))
        return sb.toString().trim()
    }

    val myUid: Int get() = Process.myUid()
}
