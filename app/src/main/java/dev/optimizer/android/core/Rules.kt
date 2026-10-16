package dev.optimizer.android.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app persistent rules (Q).
 *  - never:       "Never stop this app" - honoured by every kill path (Monitor Close, Processes Stop/Ban, RAM Optimizer, auto-optimizer,
 *                 auto-kill service, keep-closed guard, bulk actions).
 *  - keepClosed:  "Keep closed (auto re-close)" - the guard service closes the app again when it starts, with [mode] enforcement.
 * The two cannot be active together: setting one while the other is on is refused (the UI explains); "never" wins when imported data conflicts.
 */
data class Rule(val pkg: String, val never: Boolean, val keepClosed: Boolean, val mode: String, val since: Long, val allowUntil: Long, val label: String)

object Rules {
    const val MODE_KILL = "kill"           // close again each time it starts (force-stop with root, killBackgroundProcesses without)
    const val MODE_BGRESTRICT = "bgrestrict" // root: appops RUN_IN_BACKGROUND/RUN_ANY_IN_BACKGROUND ignore + deviceidle / standby bucket (reversible)
    const val MODE_FREEZE = "freeze"       // root: pm disable-user / suspend (existing Keep-closed path)

    private const val KEY = "app_rules"
    private val prefs get() = Store.appCtx.getSharedPreferences("optimizer", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Rule> {
        val a = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { runCatching {
            val o = a.getJSONObject(it)
            Rule(o.getString("pkg"), o.optBoolean("never"), o.optBoolean("keepClosed"), o.optString("mode", MODE_KILL), o.optLong("since"), o.optLong("allowUntil"), o.optString("label"))
        }.getOrNull() }
    }

    @Synchronized
    private fun save(l: List<Rule>) {
        val a = JSONArray()
        l.filter { it.never || it.keepClosed }.forEach { a.put(JSONObject().put("pkg", it.pkg).put("never", it.never).put("keepClosed", it.keepClosed).put("mode", it.mode).put("since", it.since).put("allowUntil", it.allowUntil).put("label", it.label)) }
        prefs.edit().putString(KEY, a.toString()).apply()
        version++
    }
    @Volatile var version = 0

    fun get(pkg: String) = all().firstOrNull { it.pkg == pkg }
    fun isNever(pkg: String) = get(pkg)?.never == true
    fun isKeepClosed(pkg: String) = get(pkg)?.keepClosed == true
    fun neverSet(): Set<String> = all().filter { it.never }.map { it.pkg }.toSet()

    private fun upsert(pkg: String, label: String, f: (Rule) -> Rule) {
        val cur = get(pkg) ?: Rule(pkg, false, false, MODE_KILL, System.currentTimeMillis(), 0L, label)
        save(all().filter { it.pkg != pkg } + f(cur).copy(label = label.ifEmpty { cur.label }))
    }

    /** Returns an error message (shown by the UI) or null. */
    fun setNever(ctx: Context, pkg: String, on: Boolean): String? {
        if (on && isKeepClosed(pkg)) return "This app has a 'Keep closed' rule. Remove that rule first - 'Never stop' and 'Keep closed' cannot both be set."
        upsert(pkg, labelOf(ctx, pkg)) { it.copy(never = on) }
        return null
    }

    fun setKeepClosed(ctx: Context, pkg: String, on: Boolean, mode: String = MODE_KILL): String? {
        if (on) {
            if (isNever(pkg)) return "This app has a 'Never stop' rule, which wins. Remove 'Never stop' first if you really want it kept closed."
            Protected.reasons(ctx, pkg)?.let { return "Protected critical package ($it): it can never be kept closed." }
        }
        upsert(pkg, labelOf(ctx, pkg)) { it.copy(keepClosed = on, mode = mode, allowUntil = 0L) }
        return null
    }

    fun setMode(pkg: String, mode: String) { get(pkg)?.let { r -> save(all().filter { it.pkg != pkg } + r.copy(mode = mode)) } }
    fun allowFor(pkg: String, minutes: Int) { get(pkg)?.let { r -> save(all().filter { it.pkg != pkg } + r.copy(allowUntil = System.currentTimeMillis() + minutes * 60_000L)) } }
    fun remove(pkg: String) = save(all().filter { it.pkg != pkg })

    fun labelOf(ctx: Context, pkg: String): String = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /**
     * The single gate used by every kill path. Returns the reason the app must NOT be stopped, or null if stopping is allowed.
     * Covers: this app, protected critical packages, 'Never stop' rules, the foreground app the user is using, apps running a foreground service /
     * playing media (best effort), unless [ignoreActive] is set (used by the keep-closed guard for apps the user explicitly asked to keep closed).
     */
    fun blockReason(ctx: Context, pkg: String, ignoreActive: Boolean = false): String? {
        if (pkg == ctx.packageName) return "This app"
        Protected.reasons(ctx, pkg)?.let { return "Protected: $it" }
        if (isNever(pkg)) return "Rule: never stop this app"
        if (!ignoreActive) Foreground.activeReason(ctx, pkg)?.let { return it }
        else Foreground.topPackage(ctx)?.let { if (it == pkg) return "In the foreground right now" }
        return null
    }

    // ---------------- export / import (also stored in every backup) ----------------
    fun toJson(): String = JSONObject().put("version", 1).put("rules", JSONArray(all().map { JSONObject().put("pkg", it.pkg).put("never", it.never).put("keepClosed", it.keepClosed).put("mode", it.mode).put("label", it.label) })).toString(2)

    /** Imports rules; conflicts (both flags) are resolved with 'never' winning. Returns (imported, conflictsResolved). */
    fun importJson(s: String, replace: Boolean = false): Pair<Int, Int> {
        val a = JSONObject(s).getJSONArray("rules")
        val base = if (replace) emptyList() else all()
        val map = base.associateBy { it.pkg }.toMutableMap()
        var n = 0; var conflicts = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            var never = o.optBoolean("never"); var keep = o.optBoolean("keepClosed")
            if (never && keep) { keep = false; conflicts++ }
            val pkg = o.getString("pkg")
            val ex = map[pkg]
            if (ex != null) { if (ex.never && keep) { keep = false; conflicts++ }; if (ex.keepClosed && never) { conflicts++ } }
            map[pkg] = Rule(pkg, never || ex?.never == true, if (never || ex?.never == true) false else (keep || ex?.keepClosed == true), o.optString("mode", MODE_KILL), ex?.since ?: System.currentTimeMillis(), 0L, o.optString("label", pkg))
            n++
        }
        save(map.values.toList())
        return n to conflicts
    }
}

/** What the user is doing right now (top app) and which apps must not be touched because they are doing visible work. Cached for a few seconds. */
object Foreground {
    private var cacheAt = 0L
    private var cacheTop: String? = null
    private var cacheActive: Map<String, String> = emptyMap()

    fun topPackage(ctx: Context): String? {
        refresh(ctx); return cacheTop
    }

    /** Reason string if [pkg] is the top app, runs a foreground service, plays media or is in a call; else null. */
    fun activeReason(ctx: Context, pkg: String): String? { refresh(ctx); return if (cacheTop == pkg) "In the foreground right now" else cacheActive[pkg] }

    fun activeSet(ctx: Context): Map<String, String> { refresh(ctx); return cacheActive + (cacheTop?.let { mapOf(it to "In the foreground right now") } ?: emptyMap()) }

    @Synchronized
    private fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - cacheAt < 8_000) return
        cacheAt = now
        var top: String? = null
        val act = HashMap<String, String>()
        if (Root.isRooted) {
            val d = Sh.root("dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity:' | head -3; echo '##svc'; dumpsys activity services 2>/dev/null | grep -E 'ServiceRecord\\{|isForeground=true'; echo '##media'; dumpsys media_session 2>/dev/null | grep -E 'package=|state=PlaybackState \\{state=3'", 25).out
            val parts = d.split("##svc")
            top = Regex("u\\d+ ([\\w.]+)/").find(parts.getOrElse(0) { "" })?.groupValues?.get(1)
            val rest = parts.getOrElse(1) { "" }.split("##media")
            var cur: String? = null
            for (l in rest.getOrElse(0) { "" }.lineSequence()) {
                Regex("ServiceRecord\\{[0-9a-f]+ u\\d+ ([\\w.]+)/").find(l)?.let { cur = it.groupValues[1] }
                if (l.contains("isForeground=true")) cur?.let { act[it] = "Runs a foreground service (media / navigation / call / download)" }
            }
            var pkgLine: String? = null
            for (l in rest.getOrElse(1) { "" }.lineSequence()) {
                Regex("package=([\\w.]+)").find(l)?.let { pkgLine = it.groupValues[1] }
                if (l.contains("state=3")) pkgLine?.let { act[it] = "Playing media" }
            }
        }
        if (top == null) top = runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val ev = usm.queryEvents(now - 15 * 60_000L, now); val e = UsageEvents.Event(); var last: String? = null
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || e.eventType == 1) last = e.packageName; if ((e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND || e.eventType == 2) && e.packageName == last) last = null }
            last
        }.getOrNull()
        // telephony: an app in a call (dialer is protected anyway); keep the default dialer + any app holding audio focus is not detectable without root
        cacheTop = top; cacheActive = act
    }
}
