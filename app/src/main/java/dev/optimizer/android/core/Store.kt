package dev.optimizer.android.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persisted blocked app. method = disable | suspend | settings (non-root watch only). */
data class BlockedApp(val pkg: String, val method: String, val since: Long)

object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    lateinit var appCtx: Context
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("optimizer", Context.MODE_PRIVATE)
        dir = appCtx.filesDir
    }

    // ---- root grant memory ----
    var rootGrantedBefore: Boolean
        get() = prefs.getBoolean("root_granted_before", false)
        set(v) { prefs.edit().putBoolean("root_granted_before", v).apply() }

    var autoKillEnabled: Boolean
        get() = prefs.getBoolean("auto_kill", false)
        set(v) { prefs.edit().putBoolean("auto_kill", v).apply() }

    // ---- theme (D) ----
    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(v) { prefs.edit().putInt("theme_mode", v).apply() }
    var amoled: Boolean
        get() = prefs.getBoolean("amoled", false)
        set(v) { prefs.edit().putBoolean("amoled", v).apply() }

    // ---- diagnostics (G) ----
    var diagPeriodicHours: Int
        get() = prefs.getInt("diag_hours", 0)
        set(v) { prefs.edit().putInt("diag_hours", v).apply() }
    var diagLastCount: Int
        get() = prefs.getInt("diag_last", -1)
        set(v) { prefs.edit().putInt("diag_last", v).apply() }

    // ---- network (I) ----
    var netSampling: Boolean
        get() = prefs.getBoolean("net_sampling", false)
        set(v) { prefs.edit().putBoolean("net_sampling", v).apply() }
    var netIntervalSec: Int
        get() = prefs.getInt("net_interval", 10)
        set(v) { prefs.edit().putInt("net_interval", v).apply() }
    var netRetentionDays: Int
        get() = prefs.getInt("net_retention", 3)
        set(v) { prefs.edit().putInt("net_retention", v).apply() }
    /** JSON array of {uid,pkg,method} network blocks created by this app. */
    var netBlocksJson: String
        get() = prefs.getString("net_blocks", "[]") ?: "[]"
        set(v) { prefs.edit().putString("net_blocks", v).apply() }
    var trackerListsJson: String
        get() = prefs.getString("tracker_lists", "{}") ?: "{}"
        set(v) { prefs.edit().putString("tracker_lists", v).apply() }

    // ---- blocked apps ----
    fun blocked(): List<BlockedApp> {
        val arr = runCatching { JSONArray(prefs.getString("blocked", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            BlockedApp(o.getString("pkg"), o.optString("method", "disable"), o.optLong("since"))
        }
    }

    private fun saveBlocked(list: List<BlockedApp>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("method", it.method).put("since", it.since)) }
        prefs.edit().putString("blocked", arr.toString()).apply()
    }

    fun addBlocked(b: BlockedApp) { saveBlocked(blocked().filter { it.pkg != b.pkg } + b) }
    fun removeBlocked(pkg: String) { saveBlocked(blocked().filter { it.pkg != pkg }) }
    fun isBlocked(pkg: String) = blocked().any { it.pkg == pkg }

    // ---- files ----
    fun backupsDir(): File = File(dir, "backups").also { it.mkdirs() }
    fun changeLogFile(): File = File(dir, "changelog.json")
    fun snapshotsDir(): File = File(dir, "snapshots").also { it.mkdirs() }
    fun netDir(): File = File(dir, "net").also { it.mkdirs() }
    fun playDir(): File = File(appCtx.cacheDir, "play").also { it.mkdirs() }
}
