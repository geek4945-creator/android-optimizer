package dev.optimizer.android.features

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.DeviceProfile
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import dev.optimizer.android.ui.AppState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class FreezeReport(val ts: Long, val rooted: Boolean, val issues: List<Issue>, val metrics: JSONObject, val skipped: List<String>)

/**
 * Freeze Doctor: collects real evidence for why a (low-RAM, eMMC) tablet freezes and ranks the probable causes.
 * Every probe is optional: unreadable sources are listed as "skipped" instead of guessed. Nothing is changed during the scan.
 */
object FreezeDoctor {
    private fun rd(path: String, root: Boolean): String? =
        runCatching { File(path).readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (root) Sh.root("cat $path 2>/dev/null", 10).out.takeIf { it.isNotBlank() } else null

    private fun psi(name: String, root: Boolean): Map<String, Float>? {
        val t = rd("/proc/pressure/$name", root) ?: return null
        val m = HashMap<String, Float>()
        t.lineSequence().forEach { l ->
            val kind = l.substringBefore(' ')
            Regex("(avg10|avg60|avg300)=([\\d.]+)").findAll(l).forEach { m["$kind.${it.groupValues[1]}"] = it.groupValues[2].toFloat() }
        }
        return m.ifEmpty { null }
    }

    private fun vmstat(root: Boolean): Map<String, Long>? {
        val t = rd("/proc/vmstat", root) ?: return null
        return t.lineSequence().mapNotNull { l -> val p = l.split(' '); if (p.size == 2) p[1].toLongOrNull()?.let { p[0] to it } else null }.toMap()
    }

    private data class Disk(val name: String, val ioMs: Long, val sectorsR: Long, val sectorsW: Long, val inFlight: Long)
    private fun diskstats(root: Boolean): Map<String, Disk> {
        val t = rd("/proc/diskstats", root) ?: return emptyMap()
        val m = HashMap<String, Disk>()
        for (l in t.lineSequence()) {
            val p = l.trim().split(Regex("\\s+")); if (p.size < 14) continue
            val n = p[2]
            if (!Regex("^(mmcblk\\d+|sd[a-z]|nvme\\d+n\\d+|vd[a-z])$").matches(n)) continue
            m[n] = Disk(n, p[12].toLongOrNull() ?: 0, p[5].toLongOrNull() ?: 0, p[9].toLongOrNull() ?: 0, p[11].toLongOrNull() ?: 0)
        }
        return m
    }

    private fun sev(score: Int) = when { score >= 70 -> Sev.HIGH; score >= 40 -> Sev.MEDIUM; else -> Sev.LOW }

    fun scan(ctx: Context, root: Boolean = Root.isRooted): FreezeReport {
        val out = mutableListOf<Issue>(); val metrics = JSONObject(); val skipped = mutableListOf<String>()
        fun add(id: String, title: String, score: Int, expl: String, ev: List<String>, opts: List<FixOpt> = emptyList()) {
            if (score >= 15) out += Issue(id, title, sev(score), expl, listOf("Likelihood score: ${score.coerceAtMost(100)}/100") + ev, opts, score.coerceAtMost(100))
        }
        fun note(s: String) { skipped += s }
        val prof = DeviceProfile.current()
        val lowRam = (prof?.ramMb ?: 4096) <= 2200

        // timing-sensitive samples first: t0
        val vm0 = vmstat(root); val d0 = diskstats(root); val t0 = System.nanoTime()

        // ---- memory pressure / PSI ----
        val mi = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }
        val ram = RamOptimizer.read()
        val availPct = if (ram.totalKb > 0) ram.availKb * 100 / ram.totalKb else 100
        val pm = psi("memory", root); val pio = psi("io", root); val pcpu = psi("cpu", root)
        metrics.put("ram_total_mb", ram.totalKb / 1024).put("ram_avail_mb", ram.availKb / 1024).put("avail_pct", availPct).put("low_memory_flag", mi.lowMemory)
        if (pm == null) note("Memory PSI (/proc/pressure/memory) not readable (kernel < 4.20 or blocked)") else metrics.put("psi_mem", JSONObject(pm as Map<*, *>))
        if (pio == null) note("I/O PSI (/proc/pressure/io) not readable") else metrics.put("psi_io", JSONObject(pio as Map<*, *>))
        if (pcpu != null) metrics.put("psi_cpu", JSONObject(pcpu as Map<*, *>))

        // ---- storage (free) ----
        val st = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val freePct = st?.let { it.availableBytes * 100 / it.totalBytes.coerceAtLeast(1) }
        if (st != null) metrics.put("data_free_mb", st.availableBytes / 1048576).put("data_total_mb", st.totalBytes / 1048576).put("data_free_pct", freePct ?: -1)

        // sleep for the delta window while doing other probes
        val other = mutableListOf<() -> Unit>()

        // ---- thermal / cpufreq ----
        val battTemp = prof?.batteryTempC ?: runCatching { ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10f) }.getOrNull()
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrNull() else null
        val zoneMax = TempLog.let { runCatching { var b: Float? = null; for (i in 0 until 40) { val f = File("/sys/class/thermal/thermal_zone$i/temp"); if (!f.exists()) { if (i > 12) break else continue }; val v = runCatching { f.readText().trim().toFloat() }.getOrNull() ?: continue; val c = if (v > 1000) v / 1000f else v; if (c in 1f..150f && (b == null || c > b)) b = c }; b }.getOrNull() }
        val cpuN = (0 until 16).takeWhile { File("/sys/devices/system/cpu/cpu$it").exists() }.size.coerceAtLeast(Runtime.getRuntime().availableProcessors())
        data class Freq(val i: Int, val cur: Long?, val max: Long?, val hw: Long?, val gov: String?)
        val freqs = (0 until cpuN).map { i ->
            fun f(n: String) = rd("/sys/devices/system/cpu/cpu$i/cpufreq/$n", root)?.trim()
            Freq(i, f("scaling_cur_freq")?.toLongOrNull(), f("scaling_max_freq")?.toLongOrNull(), f("cpuinfo_max_freq")?.toLongOrNull(), f("scaling_governor"))
        }
        if (freqs.all { it.cur == null }) note("CPU frequency/governor (/sys/devices/system/cpu/*/cpufreq) not readable")

        // ---- gather root-only text sources (during the I/O sample window) ----
        val dmesg = if (root) Sh.root("dmesg 2>/dev/null | tail -n 4000", 20).out else null
        if (!root) note("dmesg (mmc/UFS/ext4 errors, OOM kills) needs root")
        val drop = if (root) Sh.root("dumpsys dropbox 2>/dev/null", 30).out else null
        if (!root) note("System dropbox (system_server crashes/ANRs) needs root")
        val power = if (root) Sh.root("dumpsys power 2>/dev/null", 20).out else null
        val disk = if (root) Sh.root("dumpsys diskstats 2>/dev/null", 30).out else null
        val topOut = if (root) Sh.root("top -b -n 1 -m 8 2>/dev/null", 20).out else null
        val fstrimAge = if (root) Sh.root("stat -c %Y /data/system/last-fstrim 2>/dev/null", 10).out.trim().toLongOrNull() else null
        val emmc = if (root) Sh.root("for f in /sys/block/mmcblk0/device/life_time /sys/block/mmcblk0/device/pre_eol_info /sys/class/mmc_host/mmc0/mmc0:*/life_time /sys/class/mmc_host/mmc0/mmc0:*/pre_eol_info /sys/class/mmc_host/mmc0/mmc0:*/name /sys/class/mmc_host/mmc0/mmc0:*/manfid; do [ -r \$f ] && echo \"\$f=\$(cat \$f)\"; done", 15).out else rd("/sys/block/mmcblk0/device/life_time", false)?.let { "life_time=$it" }
        val dexopt = if (root && Build.VERSION.SDK_INT >= 24) Sh.root("dumpsys package dexopt 2>/dev/null", 40).out else null
        val services = if (root) Sh.root("dumpsys activity services 2>/dev/null | grep -c 'ServiceRecord{'", 30).out.trim().toIntOrNull() else null

        // t1 sample (>= 2 s after t0)
        val wait = 2000 - (System.nanoTime() - t0) / 1_000_000
        if (wait > 0) Thread.sleep(wait)
        val vm1 = vmstat(root); val d1 = diskstats(root); val dt = (System.nanoTime() - t0) / 1e9

        // ================= findings =================
        // 1. memory pressure + swap thrash
        run {
            var score = 0; val ev = mutableListOf<String>()
            ev += "Available ${ram.availKb / 1024} MB of ${ram.totalKb / 1024} MB ($availPct%), low-memory flag: ${mi.lowMemory}"
            if (availPct < 8) score += 45 else if (availPct < 15) score += 30 else if (availPct < 22) score += 12
            if (mi.lowMemory) score += 20
            pm?.let { ev += "PSI memory: some avg10=${it["some.avg10"]} avg60=${it["some.avg60"]}, full avg10=${it["full.avg10"]}"; val s = it["some.avg10"] ?: 0f; val f = it["full.avg10"] ?: 0f; score += when { f >= 5 -> 35; s >= 25 -> 30; s >= 10 -> 20; s >= 3 -> 8; else -> 0 } }
            val swapPct = if (ram.swapTotalKb > 0) (ram.swapTotalKb - ram.swapFreeKb) * 100 / ram.swapTotalKb else -1
            ev += if (ram.swapTotalKb > 0) "Swap/zRAM ${ (ram.swapTotalKb - ram.swapFreeKb) / 1024} / ${ram.swapTotalKb / 1024} MB ($swapPct%)" else "No swap/zRAM configured"
            if (swapPct >= 80) score += 15 else if (swapPct >= 60) score += 8
            if (vm0 != null && vm1 != null && dt > 0) {
                val sw = ((vm1["pswpin"] ?: 0) - (vm0["pswpin"] ?: 0) + (vm1["pswpout"] ?: 0) - (vm0["pswpout"] ?: 0)) / dt
                val mf = ((vm1["pgmajfault"] ?: 0) - (vm0["pgmajfault"] ?: 0)) / dt
                val sc = ((vm1["pgscan_direct"] ?: 0) - (vm0["pgscan_direct"] ?: 0)) / dt
                ev += "Over %.1f s: swap in+out %.0f pages/s, major faults %.0f/s, direct reclaim scans %.0f pages/s".format(dt, sw, mf, sc)
                metrics.put("swap_pages_per_s", sw).put("major_faults_per_s", mf).put("direct_reclaim_pages_per_s", sc)
                if (sw > 3000) score += 25 else if (sw > 800) score += 12
                if (mf > 400) score += 12
                if (sc > 2000) score += 15
            } else note("/proc/vmstat not readable: swap thrash rate unknown")
            if (lowRam) { ev += "Low-RAM device (${prof?.ramMb} MB): little room for background apps"; score += 8 }
            add("fd_mem", "Memory pressure / swap thrash", score,
                "When available memory is low, Android keeps killing and reloading apps and the kernel spends its time reclaiming or swapping pages; the UI stalls even if the CPU looks idle.", ev,
                listOf(FixOpt("fd_mem_gentle", "Gentle RAM optimize now (protected apps and 'Never stop' rules respected)", true, "Temporary; apps restart when you open them.",
                    local = { val c = RamOptimizer.candidates(ctx, false); RamOptimizer.run(ctx, c.items, RamOptions(true, false, false, false, false), "Freeze Doctor (gentle)") }),
                    FixOpt("fd_mem_compact", "Compact memory (root, closes nothing)", false, "Harmless; short CPU burst.", rootPlan = { Plan(listOf("echo 1 > /proc/sys/vm/compact_memory"), emptyList()) }),
                    FixOpt("fd_mem_startup", "Review startup apps (fewer autostarting apps = more free RAM)", local = { AppState.openPage("startup") })))
        }

        // 2. free storage
        if (freePct != null && st != null) {
            val freeMb = st.availableBytes / 1048576
            val score = when { freePct < 4 || freeMb < 400 -> 92; freePct < 8 || freeMb < 900 -> 70; freePct < 12 -> 45; freePct < 18 -> 22; else -> 0 } + (if (lowRam && freePct < 15) 5 else 0)
            add("fd_storage", "Low free space on /data", score,
                "An almost-full /data forces slow flash garbage-collection (especially on eMMC), makes updates and caches fail, and is one of the most common causes of cheap-tablet freezes.",
                listOf("Free ${freeMb} MB of ${st.totalBytes / 1048576} MB ($freePct%)"),
                listOf(FixOpt("fd_storage_clean", "Open Storage cleaner (preview, nothing deleted without ticks)", true, local = { AppState.openPage("cleaner") }),
                    FixOpt("fd_storage_trim", "Clear all app caches (pm trim-caches)", false, "Not undoable; caches are rebuilt.", rootPlan = { Plan(listOf("pm trim-caches 999999999999"), emptyList()) }),
                    FixOpt("fd_storage_settings", "Open Android storage settings", intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))))
        }

        // 3. eMMC I/O stalls
        run {
            var score = 0; val ev = mutableListOf<String>()
            pio?.let { ev += "PSI io: some avg10=${it["some.avg10"]} avg60=${it["some.avg60"]}, full avg10=${it["full.avg10"]}"; val s = it["some.avg10"] ?: 0f; val f = it["full.avg10"] ?: 0f; score += when { f >= 10 -> 50; s >= 30 -> 40; s >= 12 -> 25; s >= 4 -> 10; else -> 0 } }
            for ((n, a) in d0) {
                val b = d1[n] ?: continue
                if (dt <= 0) continue
                val util = (b.ioMs - a.ioMs) / (dt * 10.0)
                val wMb = (b.sectorsW - a.sectorsW) * 512 / 1048576.0 / dt; val rMb = (b.sectorsR - a.sectorsR) * 512 / 1048576.0 / dt
                ev += "%s: %.0f%% busy, read %.1f MB/s, write %.1f MB/s, %d requests in flight".format(n, util, rMb, wMb, b.inFlight)
                metrics.put("disk_${n}_util_pct", util).put("disk_${n}_write_mbs", wMb).put("disk_${n}_read_mbs", rMb)
                if (util > 85) score += 30 else if (util > 60) score += 18 else if (util > 35) score += 6
            }
            if (d0.isEmpty()) note("/proc/diskstats not readable or no mmc/sd device found")
            if (freePct != null && freePct < 12) { score += 8; ev += "Low free space amplifies I/O stalls on eMMC" }
            add("fd_io", "Storage (eMMC) I/O stalls", score,
                "A busy or worn flash chip makes every app launch and write wait; the system looks frozen while the CPU is idle. Sustained 'busy' at idle usually means a runaway writer (updates, sync, logging, indexing) or a nearly full/worn storage chip.", ev,
                listOf(FixOpt("fd_io_trim", "Run fstrim now (root)", true, "Not undoable; safe.", rootPlan = { Plan(listOf("fstrim -v /data", "fstrim -v /cache"), emptyList()) }),
                    FixOpt("fd_io_crash", "Look for crash loops writing logs: open Crash & ANR log", local = { AppState.openPage("crashlog") })))
        }

        // 4. eMMC health
        emmc?.takeIf { it.isNotBlank() }?.let { txt ->
            metrics.put("emmc_raw", txt.take(600))
            var score = 0; val ev = mutableListOf<String>()
            Regex("pre_eol_info=(0x)?([0-9a-fA-F]+)").find(txt)?.let { m -> val v = m.groupValues[2].toIntOrNull(16) ?: 0; ev += "pre_eol_info = 0x%02x (${when (v) { 1 -> "normal"; 2 -> "WARNING: 80% of reserved blocks used"; 3 -> "URGENT: 90% of reserved blocks used"; else -> "unknown" }})".format(v); score += when (v) { 3 -> 90; 2 -> 70; else -> 0 } }
            Regex("life_time=(0x[0-9a-fA-F]+)\\s+(0x[0-9a-fA-F]+)").find(txt.replace("\n", " "))?.let { m ->
                val a = m.groupValues[1].removePrefix("0x").toInt(16); val b = m.groupValues[2].removePrefix("0x").toInt(16)
                ev += "life_time estimate: type A 0x%02x (%d-%d%% used), type B 0x%02x (%d-%d%% used)".format(a, (a - 1).coerceAtLeast(0) * 10, a * 10, b, (b - 1).coerceAtLeast(0) * 10, b * 10)
                val worst = maxOf(a, b); score += when { worst >= 10 -> 85; worst >= 9 -> 65; worst >= 8 -> 45; worst >= 7 -> 25; else -> 0 }
            }
            ev += txt.lineSequence().filter { it.contains("name=") || it.contains("manfid=") }.map { it.substringAfterLast('/') }.take(2).toList()
            if (score > 0) add("fd_emmc", "Storage chip wear (eMMC health)", score, "eMMC reports how much of its spare/lifetime is consumed. A worn chip becomes very slow and can corrupt data. Back up now; software cannot fix a worn chip.", ev,
                listOf(FixOpt("fd_emmc_backup", "Open Backups (create a safety backup first)", local = { AppState.openPage("backups") })))
        } ?: note("eMMC health registers (life_time / pre_eol_info) not exposed or need root")

        // 5. fstrim age
        if (root) {
            if (fstrimAge == null) note("Last fstrim time (/data/system/last-fstrim) unknown")
            else {
                val days = (System.currentTimeMillis() / 1000 - fstrimAge) / 86400; metrics.put("fstrim_age_days", days)
                add("fd_trim", "Storage not trimmed for $days days", (if (days >= 30) 52 else if (days >= 14) 38 else if (days >= 7) 18 else 0) + (if (lowRam && days >= 14) 8 else 0),
                    "Android normally runs fstrim during idle+charging maintenance. If the tablet is never idle and charging, flash stays untrimmed and writes slow down.",
                    listOf("Last /data/system/last-fstrim: $days days ago"),
                    listOf(FixOpt("fd_trim_now", "Run fstrim now (root)", true, "Not undoable; safe.", rootPlan = { Plan(listOf("fstrim -v /data", "fstrim -v /cache"), emptyList()) }),
                        FixOpt("fd_trim_sched", "Schedule maintenance when idle and charging", local = { AppState.openPage("tools") })))
            }
        }

        // 6. thermal throttling / cpufreq
        run {
            var score = 0; val ev = mutableListOf<String>()
            battTemp?.let { ev += "Battery temperature %.1f °C".format(it); score += when { it >= 47 -> 80; it >= 43 -> 50; it >= 40 -> 20; else -> 0 } }
            zoneMax?.let { ev += "Hottest readable thermal zone %.1f °C".format(it); metrics.put("thermal_zone_max_c", it); score += when { it >= 85 -> 70; it >= 75 -> 45; it >= 65 -> 20; else -> 0 } }
            thermalStatus?.let { ev += "Android thermal status: $it (0 none, 1 light, 2 moderate, 3 severe, 4 critical)"; score += when { it >= 3 -> 60; it == 2 -> 35; it == 1 -> 12; else -> 0 } }
            val capped = freqs.filter { it.max != null && it.hw != null && it.max < it.hw * 0.85 }
            if (capped.isNotEmpty()) { ev += "CPU max frequency capped below hardware max on cores ${capped.joinToString { it.i.toString() }} (e.g. ${capped.first().max!! / 1000} MHz vs ${capped.first().hw!! / 1000} MHz)"; score += 25 }
            val govs = freqs.mapNotNull { it.gov }.distinct()
            if (govs.isNotEmpty()) ev += "Governor: ${govs.joinToString()}"
            if (govs.any { it == "powersave" }) { ev += "Governor 'powersave' pins the CPU at its lowest frequency"; score += 35 }
            val curs = freqs.mapNotNull { f -> if (f.cur != null && f.hw != null) f.cur * 100 / f.hw else null }
            if (curs.isNotEmpty()) { ev += "Current frequency ${curs.joinToString("/") { "$it%" }} of hardware max (single snapshot)"; metrics.put("cpu_freq_pct_of_max", JSONArray(curs)) }
            metrics.put("cpu_governors", govs.joinToString())
            val avail = freqs.firstOrNull { it.gov != null }?.let { rd("/sys/devices/system/cpu/cpu${it.i}/cpufreq/scaling_available_governors", root)?.trim()?.split(" ") } ?: emptyList()
            val alt = listOf("schedutil", "interactive", "ondemand").firstOrNull { it in avail }
            val opts = mutableListOf<FixOpt>()
            if (govs.any { it == "powersave" } && alt != null && root) opts += FixOpt("fd_gov", "Switch governor 'powersave' → '$alt' (root, runtime only)", false, "May run hotter and drain more battery; reboot restores the default.",
                rootPlan = { Plan(freqs.filter { it.gov == "powersave" }.map { "echo $alt > /sys/devices/system/cpu/cpu${it.i}/cpufreq/scaling_governor" }, freqs.filter { it.gov == "powersave" }.map { "echo powersave > /sys/devices/system/cpu/cpu${it.i}/cpufreq/scaling_governor" }) })
            opts += FixOpt("fd_thermal_chart", "Open temperature log chart", local = { AppState.openPage("battery") })
            add("fd_thermal", "Thermal throttling / CPU stuck low", score, "A hot device (or a power-saving governor / frequency cap) makes the SoC run slowly, which looks like freezing under load. Rugged tablets in cases or in sunlight overheat easily.", ev, opts)
        }

        // 7. runaway processes + wakelocks
        if (root && topOut != null) {
            val hdr = topOut.lineSequence().firstOrNull { it.contains("%CPU") || it.contains("CPU%") }
            val rows = mutableListOf<Pair<String, Float>>()
            if (hdr != null) {
                val cols = hdr.trim().split(Regex("\\s+")); val ci = cols.indexOfFirst { it.contains("CPU") && it.contains("%") }.takeIf { it >= 0 } ?: -1
                val after = topOut.lineSequence().dropWhile { it != hdr }.drop(1)
                for (l in after) { val p = l.trim().split(Regex("\\s+")); if (p.size > cols.size - 1 && ci >= 0) { val c = p[ci].removeSuffix("%").toFloatOrNull() ?: continue; rows += (p.last()) to c } }
            }
            val busy = rows.filter { it.second >= 35f }
            val kswapd = rows.firstOrNull { it.first.contains("kswapd") && it.second >= 10 }
            val score = (if (busy.isNotEmpty()) 30 + (busy.maxOf { it.second } / 5).toInt() else 0) + (if (kswapd != null) 20 else 0)
            metrics.put("top_snapshot", JSONArray(rows.take(8).map { "${it.first} ${it.second}%" }))
            add("fd_cpu", "Runaway process eating CPU", score, "One process using a large share of the CPU for a long time starves the UI. This is a single snapshot: run the scan again to see whether it persists.",
                rows.take(5).map { "${it.first}: ${it.second}% CPU" } + listOfNotNull(kswapd?.let { "kswapd is busy (${it.second}%): the kernel is reclaiming memory constantly" }),
                listOf(FixOpt("fd_cpu_monitor", "Open Monitor to close or rule the app", local = { AppState.openPage("rules") })))
        }
        if (root && power != null) {
            val n = Regex("Wake Locks: size=(\\d+)").find(power)?.groupValues?.get(1)?.toIntOrNull()
            val tags = Regex("PARTIAL_WAKE_LOCK\\s+'([^']+)'").findAll(power).map { it.groupValues[1] }.take(6).toList()
            metrics.put("wakelocks", n ?: -1)
            if (n != null) add("fd_wakelock", "Many held wakelocks", if (n >= 12) 45 else if (n >= 7) 30 else 0, "Apps holding wakelocks keep the CPU awake and busy, causing heat and background load.", listOf("$n wakelocks held now") + tags.map { "PARTIAL_WAKE_LOCK '$it'" },
                listOf(FixOpt("fd_wl_rules", "Open Rules (Never stop / Keep closed)", local = { AppState.openPage("rules") })))
        }

        // 8. big caches
        if (root && disk != null) {
            fun arr(k: String) = Regex("$k:\\s*\\[([^\\]]*)\\]").find(disk)?.groupValues?.get(1)?.split(",")?.map { it.trim() } ?: emptyList()
            val names = arr("Package Names").map { it.trim('"') }; val caches = arr("Cache Sizes").mapNotNull { it.toLongOrNull() }
            if (names.isNotEmpty() && names.size == caches.size) {
                val total = caches.sum(); val top = names.zip(caches).sortedByDescending { it.second }.take(5)
                metrics.put("cache_total_mb", total / 1048576)
                val dataTotal = st?.totalBytes ?: 1L
                add("fd_cache", "Large app caches", if (total > 2L shl 30) 45 else if (total > 1L shl 30) 32 else if (total * 100 / dataTotal > 8) 28 else 0,
                    "Large caches eat the scarce free space (see storage). They are rebuilt automatically when needed.", listOf("Total cache ${total / 1048576} MB") + top.map { "${it.first}: ${it.second / 1048576} MB" },
                    listOf(FixOpt("fd_cache_trim", "Clear all app caches (pm trim-caches)", true, "Not undoable; caches are rebuilt.", rootPlan = { Plan(listOf("pm trim-caches 999999999999"), emptyList()) })))
            }
        }

        // 9. autostarters + services
        run {
            val boot = runCatching { StartupApps.list(ctx, false) }.getOrDefault(emptyList()); val enabled = boot.filter { b -> b.receivers.any { it.enabled } }
            metrics.put("boot_receiver_apps", enabled.size)
            var score = if (enabled.size >= 14) 42 else if (enabled.size >= 8) 28 else if (enabled.size >= 5) 15 else 0
            val ev = mutableListOf("${enabled.size} third-party apps start themselves at boot (BOOT_COMPLETED)")
            ev += enabled.take(6).map { it.label }
            if (services != null) { ev += "$services running service records"; metrics.put("service_records", services); score += if (services >= 200) 20 else if (services >= 120 && lowRam) 15 else 0 }
            add("fd_startup", "Too many autostarting apps / background services", score, "Every autostarting app takes RAM after each boot, which hurts most on 1-3 GB devices.", ev,
                listOf(FixOpt("fd_startup_open", "Open Startup manager (disable boot receivers with root, undoable)", true, local = { AppState.openPage("startup") })))
        }

        // 10. battery
        run {
            val b = BatteryInfo.read(ctx)
            if (b.warnings.isNotEmpty()) add("fd_batt", "Battery health warning", if (b.warnings.any { it.contains("Dead") || it.contains("Overheat") }) 72 else 42, "A weak or hot battery makes the SoC throttle or reset under load, causing freezes and random reboots.", b.warnings + b.rows.take(5).map { "${it.first}: ${it.second}" },
                listOf(FixOpt("fd_batt_open", "Open Battery & usage", local = { AppState.openPage("battery") })))
        }

        // 11. WebView
        run {
            val info = Updates.webViewInfo(ctx); val major = Regex("(\\d+)\\.").find(info.substringAfterLast(' '))?.groupValues?.get(1)?.toIntOrNull()
            metrics.put("webview", info)
            if (major != null && major < 110) add("fd_webview", "Very old WebView ($major)", if (major < 80) 38 else 25, "Many apps render with WebView; an old one is slow, buggy and insecure.", listOf("WebView: $info"),
                listOf(FixOpt("fd_webview_open", "Open WebView settings", intent = Updates.webViewSettingsIntent())))
        }

        // 12. stale dexopt
        if (root && dexopt != null) {
            val total = Regex("\\[status=").findAll(dexopt).count(); val bad = Regex("\\[status=(run-from-apk|verify|quicken|extract)\\]").findAll(dexopt).count()
            metrics.put("dexopt_total", total).put("dexopt_unoptimized", bad)
            if (total >= 20) { val ratio = bad * 100 / total
                add("fd_dexopt", "Many apps not compiled ($ratio%)", if (ratio >= 70) 42 else if (ratio >= 45) 28 else 0, "After an OTA or restore, apps run from slower unoptimized code until background dexopt finishes. Dexopt needs charging+idle time and uses storage.",
                    listOf("$bad of $total package ABIs are verify/quicken/run-from-apk"),
                    if (Build.VERSION.SDK_INT >= 26) listOf(FixOpt("fd_dexopt_run", "Run background dexopt job (root; slow, heats the device - do it on charge)", false, "Not undoable; can take minutes.", rootPlan = { Plan(listOf("cmd package bg-dexopt-job"), emptyList()) })) else emptyList()) }
        }

        // 13. crash / ANR loops
        run {
            var score = 0; val ev = mutableListOf<String>()
            if (drop != null) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US); val since = System.currentTimeMillis() - 86_400_000L
                val tags = HashMap<String, Int>()
                Regex("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+(\\S+)").findAll(drop).forEach { m -> val t = runCatching { fmt.parse(m.groupValues[1])!!.time }.getOrDefault(0L); if (t >= since) tags[m.groupValues[2]] = (tags[m.groupValues[2]] ?: 0) + 1 }
                metrics.put("dropbox_24h", JSONObject(tags as Map<*, *>))
                tags.entries.sortedByDescending { it.value }.take(8).forEach { ev += "${it.key}: ${it.value} in the last 24 h" }
                fun c(vararg k: String) = k.sumOf { tags[it] ?: 0 }
                score += c("system_server_crash", "SYSTEM_RESTART") * 40 + c("system_server_anr", "system_server_watchdog") * 35 + minOf(c("system_app_anr") * 12, 45) + minOf(c("system_app_crash") * 10, 40) + minOf(c("data_app_anr") * 5, 25) + minOf(c("SYSTEM_TOMBSTONE") * 4, 30) + minOf(c("data_app_crash") * 2, 15)
            }
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.processesInErrorState?.filter { it.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING }?.let { l -> if (l.isNotEmpty()) { ev += "Not responding right now: " + l.joinToString { it.processName }; score += 35 } }
            if (root) { val fatal = Sh.root("logcat -b crash -d -t 600 2>/dev/null | grep -c 'FATAL EXCEPTION'", 20).out.trim().toIntOrNull(); if (fatal != null) { ev += "$fatal FATAL EXCEPTION entries in the crash buffer"; metrics.put("fatal_exceptions", fatal); score += minOf(fatal * 2, 25) } }
            add("fd_crash", "System / SystemUI crash or ANR loops", score, "system_server or SystemUI crashing, restarting or being unresponsive freezes the whole UI. Repeated app ANRs also show where the time goes.", ev,
                listOf(FixOpt("fd_crash_open", "Open the Crash & ANR log", true, local = { AppState.openPage("crashlog") })))
        }

        // 14. dmesg
        if (dmesg != null) {
            fun cnt(rx: String) = Regex(rx, RegexOption.IGNORE_CASE).findAll(dmesg).count()
            fun ex(rx: String) = dmesg.lineSequence().filter { Regex(rx, RegexOption.IGNORE_CASE).containsMatchIn(it) }.toList().takeLast(3).map { it.take(160) }
            val mmc = cnt("mmc\\d?.*(error|timeout|crc|tuning failed|retry|fail)|mmcblk\\d.*(error|i/o)|ufs.*(error|abort|reset)")
            val fs = cnt("EXT4-fs.*(error|warning)|f2fs.*(error|corrupt)|I/O error|blk_update_request")
            val oom = cnt("Out of memory|Killed process|oom-kill|lowmemorykiller")
            val thr = cnt("thermal.*(throttl|shutdown|critical)|overheat|temperature.*(high|critical)")
            metrics.put("dmesg_mmc_errors", mmc).put("dmesg_fs_errors", fs).put("dmesg_oom", oom).put("dmesg_thermal", thr)
            add("fd_dmesg_io", "Kernel storage errors (mmc/UFS/ext4)", minOf(mmc * 8 + fs * 8, 95) + (if (mmc + fs > 0) 30 else 0),
                "Kernel messages about mmc timeouts/CRC errors or filesystem errors point to failing storage, a bad firmware or a corrupted filesystem. Back up first.",
                listOf("$mmc mmc/UFS error lines, $fs filesystem/I-O error lines in the last 4000 kernel lines") + ex("mmc|ufs|ext4|f2fs|I/O error"),
                listOf(FixOpt("fd_dmesg_backup", "Open Backups", local = { AppState.openPage("backups") })))
            add("fd_dmesg_oom", "Out-of-memory / low-memory kills in kernel log", minOf(oom * 6, 70), "Repeated OOM/lowmemorykiller entries confirm real memory starvation, not just a high 'used' number.", listOf("$oom OOM / kill lines in the kernel log") + ex("Out of memory|Killed process|oom-kill|lowmemorykiller"))
            add("fd_dmesg_thermal", "Kernel thermal throttling events", minOf(thr * 8, 60), "The kernel logged thermal throttling/shutdown messages.", listOf("$thr thermal lines") + ex("thermal.*(throttl|shutdown|critical)|overheat"))
        }

        if (!root) out += Issue("fd_noroot", "Limited scan (no root)", Sev.INFO, "Several of the most informative probes (dmesg, dropbox, eMMC health, top, dumpsys) need root. Results above use only what Android lets a normal app read.", skipped.take(10), emptyList(), 1)
        return FreezeReport(System.currentTimeMillis(), root, out.sortedByDescending { it.score }, metrics, skipped)
    }

    /** TankPro / MediaTek / Rockchip / Allwinner guidance. Facts come from this device's own Build properties. */
    fun guidance(ctx: Context): List<Pair<String, String>> {
        val p = DeviceProfile.current(); val soc = p?.socFamily ?: "unknown"
        val g = mutableListOf<Pair<String, String>>()
        g += "Your device" to "${Build.MANUFACTURER} ${Build.MODEL} (brand ${Build.BRAND}, device ${Build.DEVICE}, board ${Build.BOARD}, hardware ${Build.HARDWARE}) · SoC family guess: $soc · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · RAM ${p?.ramMb ?: "?"} MB"
        g += "What usually freezes rugged / budget tablets" to "Typically 1-4 GB RAM with a slow eMMC chip, an almost full /data, an old vendor build that never gets updates, an overheating enclosure, a worn battery, and too many preinstalled or autostarting apps. The Freeze Doctor above measures these instead of guessing; fix the highest-ranked cause first."
        g += "Storage health" to "eMMC wears out. If the doctor shows pre_eol_info WARNING/URGENT, life_time 80% or more, or kernel mmc/ext4 errors, copy your data off NOW. Software cannot repair worn flash; a factory reset may only delay the problem. Keep at least 15-20% of /data free."
        when (soc) {
            "MediaTek" -> g += "MediaTek notes" to "Many MediaTek budget builds ship with aggressive vendor memory/power managers and no OTA updates. Flashing uses SP Flash Tool with the exact scatter file for your board - a wrong image can brick the tablet. Prefer vendor firmware for your exact model/board; avoid generic images."
            "Rockchip" -> g += "Rockchip notes" to "Rockchip tablets are flashed with rkdeveloptool / RKDevTool using the vendor 'update.img' for the exact board (RK3xxx). Entering loader/maskrom mode differs per model. Keep the original firmware before experimenting."
            "Allwinner" -> g += "Allwinner notes" to "Allwinner devices use PhoenixSuit / PhoenixCard with the exact vendor image. Images are board-specific; flashing the wrong one can brick the device."
            else -> g += "Flashing notes" to "Use only firmware made for your exact model and board. Flashing tools differ per SoC (SP Flash Tool for MediaTek, rkdeveloptool for Rockchip, PhoenixSuit for Allwinner)."
        }
        g += "Firmware lookup keys" to "Search the vendor's support/download page (or ask the seller) with these exact identifiers: model '${Build.MODEL}', device '${Build.DEVICE}', board '${Build.BOARD}', build '${Build.DISPLAY}', fingerprint '${Build.FINGERPRINT}', security patch '${Compat.securityPatch() ?: "n/a"}'. Compare the build number: a newer build for the same model is the only safe update. Do not trust download sites that cannot show the matching board name."
        g += "Factory reset - last resort only" to "Try, in order: Safe Boost, free storage (cleaner), disable unnecessary startup apps, update/replace the worst apps, check heat and battery. If the tablet still freezes: create a backup (Backups tab + copy your files), then Settings › System › Reset options › Erase all data. Reset removes everything including some vendor tweaks; if freezes return within days the storage chip or battery is the likely cause."
        return g
    }

    /** Writes a shareable health snapshot (JSON, no accounts, no IMEI/serial) and returns the file. */
    fun exportSnapshot(ctx: Context, r: FreezeReport): File {
        val o = JSONObject()
        o.put("type", "android-optimizer-health-snapshot").put("created", r.ts).put("rooted", r.rooted)
        o.put("app_version", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull())
        o.put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL).put("device", Build.DEVICE).put("board", Build.BOARD).put("hardware", Build.HARDWARE).put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE).put("display", Build.DISPLAY).put("fingerprint", Build.FINGERPRINT).put("security_patch", Compat.securityPatch() ?: ""))
        runCatching { o.put("device_profile", JSONObject(Store.profileJson)) }
        o.put("metrics", r.metrics)
        o.put("skipped_probes", JSONArray(r.skipped))
        o.put("findings", JSONArray(r.issues.map { JSONObject().put("id", it.id).put("title", it.title).put("severity", it.sev.label).put("score", it.score).put("explanation", it.explanation).put("evidence", JSONArray(it.evidence)) }))
        val f = File(File(ctx.cacheDir, "reports").also { it.mkdirs() }, "health-snapshot-${r.ts}.json")
        f.writeText(o.toString(2)); return f
    }
}
