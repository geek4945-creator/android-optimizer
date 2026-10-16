package dev.optimizer.android.ui

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.RamAutoService
import dev.optimizer.android.features.RamCandidates
import dev.optimizer.android.features.RamOptimizer
import dev.optimizer.android.features.RamOptions
import dev.optimizer.android.features.RamPreset
import dev.optimizer.android.features.RamResult
import dev.optimizer.android.features.RamStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun mb(kb: Long) = "${kb / 1024} MB"

@Composable
fun RamBar(s: RamStat) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("RAM used ${s.usedPct}% · available ${mb(s.availKb)} of ${mb(s.totalKb)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { s.usedPct / 100f }, Modifier.fillMaxWidth().height(8.dp))
        Text("free ${mb(s.freeKb)} · cached ${mb(s.cachedKb)} · buffers ${mb(s.buffersKb)} · swap ${mb(s.swapUsedKb)} used of ${mb(s.swapTotalKb)}", style = MaterialTheme.typography.labelSmall)
    }
}

/** Prominent card for the Monitor tab: live RAM + one-tap 'Optimize RAM' (always shows the preview first). */
@Composable
fun RamCard() {
    var stat by remember { mutableStateOf(RamOptimizer.read()) }
    var flow by remember { mutableStateOf(false) }
    LaunchedEffect(flow, AppState.lowEnd) { while (true) { stat = withContext(Dispatchers.IO) { RamOptimizer.read() }; delay(if (AppState.lowEnd) 5000 else 3000) } }
    Section("RAM Optimizer") {
        RamBar(stat)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { flow = true }) { Text("Optimize RAM") }
            Text("Preview first, nothing closes without your tick.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (flow) RamOptimizeFlow(onClose = { flow = false })
}

/** Preview → confirm → result. Used by the Monitor card, the RAM screen and the Monitor 'Close apps' button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RamOptimizeFlow(onClose: () -> Unit, initial: RamPreset = RamPreset.GENTLE, title: String = "Optimize RAM") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSystem by remember { mutableStateOf(false) }
    var cands by remember { mutableStateOf<RamCandidates?>(null) }
    var preset by remember { mutableStateOf(initial) }
    var trim by remember { mutableStateOf(initial.opts.trim) }
    var drop by remember { mutableStateOf(initial.opts.dropCaches) }
    var compact by remember { mutableStateOf(initial.opts.compact) }
    var forceStop by remember { mutableStateOf(initial.opts.forceStop) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RamResult?>(null) }
    var showSkipped by remember { mutableStateOf(false) }
    val sel = remember { mutableStateOf<Set<String>>(emptySet()) }
    val support = remember { RamOptimizer.optionsSupport() }
    LaunchedEffect(includeSystem) {
        cands = null
        val c = withContext(Dispatchers.IO) { RamOptimizer.candidates(ctx, includeSystem) }
        cands = c; sel.value = c.items.map { it.pkg }.toSet() // preselected by default
    }
    AlertDialog(
        onDismissRequest = { if (!running) onClose() }, title = { Text(if (result == null) "$title - preview" else "$title - result") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val r = result
                if (r != null) {
                    Text("Closed ${r.killed} apps. Available RAM ${mb(r.before.availKb)} → ${mb(r.after.availKb)} (${if (r.freedKb >= 0) "+" else ""}${mb(r.freedKb)}).", fontWeight = FontWeight.SemiBold)
                    KV("Free", "${mb(r.before.freeKb)} → ${mb(r.after.freeKb)}"); KV("Cached", "${mb(r.before.cachedKb)} → ${mb(r.after.cachedKb)}"); KV("Swap used", "${mb(r.before.swapUsedKb)} → ${mb(r.after.swapUsedKb)}")
                    r.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Android may restart some of these apps; the number is a snapshot, not a guarantee.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Impact: ${preset.impact}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RamPreset.values().forEach { p -> FilterChip(selected = preset == p, onClick = { preset = p; trim = p.opts.trim; drop = p.opts.dropCaches && support["drop_caches"] == true; compact = p.opts.compact && support["compact"] == true; forceStop = p.opts.forceStop && Root.isRooted }, label = { Text(p.label) }) }
                    }
                    if (!Root.isRooted) Info("No root: apps are closed with killBackgroundProcesses (cannot stop foreground apps; Android may restart them) and RAM per app is not available. Developer options > Running services lets you stop apps manually.")
                    else {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(forceStop, { forceStop = it }); Text("Force-stop (instead of gentle `am kill`)", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(trim, { trim = it }); Text("Ask selected apps to trim memory (`am send-trim-memory`)", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(compact, { compact = it }, enabled = support["compact"] == true); Text("Compact memory${if (support["compact"] != true) " (not on this kernel)" else ""}", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(drop, { drop = it }, enabled = support["drop_caches"] == true); Text("Drop caches (`sync; echo 3 > drop_caches`) - temporary, may cause a short slowdown", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeSystem, { includeSystem = it }); Text("Include (non-protected) system apps", style = MaterialTheme.typography.bodySmall) }
                    val c = cands
                    if (c == null) Busy("Reading running apps...") else {
                        Text(c.note, style = MaterialTheme.typography.labelSmall)
                        if (c.items.isEmpty()) Text("Nothing safe to close right now.", style = MaterialTheme.typography.bodySmall)
                        CloseListBody(c.items.map { CloseRow(it.pkg, it.label, it.ramKb) }, sel, maxHeight = 260)
                        TextButton(onClick = { showSkipped = !showSkipped }) { Text("${c.skipped.size} apps are protected / skipped ${if (showSkipped) "▲" else "▼"}") }
                        if (showSkipped) c.skipped.take(60).forEach { Text("🔒 ${it.second}: ${it.third}", style = MaterialTheme.typography.labelSmall) }
                    }
                    Busy(if (running) "Optimizing..." else null)
                }
            }
        },
        confirmButton = {
            if (result == null) Button(enabled = !running && cands != null && sel.value.isNotEmpty(), onClick = {
                running = true
                val c = cands!!; val chosen = c.items.filter { it.pkg in sel.value }
                scope.launch {
                    result = withContext(Dispatchers.IO) { RamOptimizer.run(ctx, chosen, RamOptions(true, forceStop && Root.isRooted, trim && Root.isRooted, drop && dev.optimizer.android.core.Priv.rootLike, compact && dev.optimizer.android.core.Priv.rootLike), "manual (${preset.label})") }
                    running = false; AppState.bumpLog()
                }
            }) { Text("Close ${sel.value.size} app(s)") } else Button(onClick = onClose) { Text("Done") }
        },
        dismissButton = { if (result == null) TextButton(enabled = !running, onClick = onClose) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RamScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stat by remember { mutableStateOf(RamOptimizer.read()) }
    var flow by remember { mutableStateOf<RamPreset?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var tune by remember { mutableStateOf(RamOptimizer.presets[0]) }
    var minFree by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(RamOptimizer.autoPrefs()) }
    var svc by remember { mutableStateOf(Store.ramAutoEnabled) }
    var cur by remember { mutableStateOf<RamOptimizer.Tunables?>(null) }
    var zramPct by remember { mutableIntStateOf(50) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(tick, AppState.rootState) { stat = withContext(Dispatchers.IO) { RamOptimizer.read() }; cur = if (dev.optimizer.android.core.Priv.rootLike) withContext(Dispatchers.IO) { RamOptimizer.readTunables() } else null }
    fun savePrefs(p: RamOptimizer.AutoPrefs) { auto = p; RamOptimizer.saveAutoPrefs(p) }
    fun run(label: String, f: () -> String) { scope.launch { busy = label; msg = withContext(Dispatchers.IO) { f() }; busy = null; AppState.bumpLog(); tick++ } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("RAM now") {
            RamBar(stat)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { flow = RamPreset.GENTLE }) { Text("Optimize RAM") }
                OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
            }
            Info("Android already manages memory: cached apps are free to reclaim, and 'free RAM' is wasted RAM. Closing apps can make the device slower (cold restarts) and drain more battery. The recommended default is Gentle. Every flow shows a preview, protects critical apps, your 'Never stop' list, the foreground app and apps with a foreground service.")
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Busy(busy)
        }
        Section("Memory actions (root)") {
            if (!dev.optimizer.android.core.Priv.rootLike) Text("Needs real root (uid 0; Shizuku/adb is not enough for /proc/sys). Without it use 'Optimize RAM' (killBackgroundProcesses) or Developer options.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = dev.optimizer.android.core.Priv.rootLike && busy == null, onClick = { confirm = "drop" }) { Text("Drop caches") }
                OutlinedButton(enabled = dev.optimizer.android.core.Priv.rootLike && busy == null && java.io.File("/proc/sys/vm/compact_memory").exists(), onClick = { run("Compacting") { val r = Sh2.compact(); r } }) { Text("Compact memory") }
            }
        }
        Section("Kernel tuning (root, reversible)") {
            val c = cur
            if (!AppState.unlocked) Warn("Needs root and the baseline backup. Values are runtime-only (they reset at reboot) and are also saved in the baseline.")
            if (c == null) Text("Read current values with root.", style = MaterialTheme.typography.bodySmall) else {
                KV("swappiness", c.swappiness?.toString() ?: "n/a"); KV("vfs_cache_pressure", c.vfs?.toString() ?: "n/a"); KV("min_free_kbytes", c.minFree?.toString() ?: "n/a")
                KV("zRAM disksize", c.zramBytes?.let { "${it / 1048576} MB" } ?: "no zram0 device")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RamOptimizer.presets.forEach { p -> FilterChip(selected = tune == p, onClick = { tune = p }, label = { Text(p.label) }) } }
                Text(tune.note, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(minFree, { minFree = it }); Text("Also adjust min_free_kbytes (expert; limited to ±50% and ≤ 4% of RAM)", style = MaterialTheme.typography.bodySmall) }
                val plan = remember(tune, minFree, c, stat.totalKb) { RamOptimizer.tunePlan(tune, minFree, c, stat.totalKb) }
                Text("Commands:\n" + plan.apply.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = AppState.unlocked && busy == null, onClick = { run("Applying") { RamOptimizer.applyTune(tune, minFree).message } }) { Text("Apply ${tune.label}") }
                    OutlinedButton(enabled = dev.optimizer.android.core.Priv.rootLike && busy == null, onClick = { run("Restoring") { RamOptimizer.undoTune().message } }) { Text("Undo") }
                }
                if (c.zramPresent) {
                    Text("zRAM resize (expert): swapoff → reset → disksize → mkswap → swapon. Refused while swap is in use.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(25, 50, 75, 100).forEach { p -> FilterChip(selected = zramPct == p, onClick = { zramPct = p }, label = { Text("$p% of RAM") }) } }
                    val bytes = stat.totalKb * 1024 * zramPct / 100
                    val (zp, why) = remember(zramPct, c, stat) { RamOptimizer.zramPlan(bytes, c, stat) }
                    Text(if (zp == null) "Not possible now: $why" else "Commands:\n" + zp.apply.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = zp != null && AppState.unlocked && busy == null, onClick = { confirm = "zram:$bytes" }) { Text("Resize zRAM") }
                        OutlinedButton(enabled = dev.optimizer.android.core.Priv.rootLike && busy == null, onClick = { run("Restoring zRAM") { RamOptimizer.undoZram().message } }) { Text("Undo") }
                    }
                }
            }
        }
        Section("Auto-optimize") {
            Text("Gentle by default. Triggers run with rate limiting (minimum ${auto.minIntervalMin} min between runs, at most ${auto.maxPerHour} per hour), never touch protected / 'Never stop' / foreground / foreground-service apps, and post a summary notification. Background runs never ask for root; they use root only if this process already verified it.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto.thresholdOn, { savePrefs(auto.copy(thresholdOn = it)) }); Text("  When available RAM drops below ${auto.thresholdPct}%") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(8, 12, 15, 20).forEach { t -> FilterChip(selected = auto.thresholdPct == t, onClick = { savePrefs(auto.copy(thresholdPct = t)) }, label = { Text("$t%") }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto.screenOff, { savePrefs(auto.copy(screenOff = it)) }); Text("  When the screen turns off") }
            Text("Schedule:", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Off", 6 to "6 h", 12 to "12 h", 24 to "24 h").forEach { (h, l) -> FilterChip(selected = auto.scheduleHours == h, onClick = { savePrefs(auto.copy(scheduleHours = h)); RamOptimizer.scheduleWorker(ctx, h) }, label = { Text(l) }) } }
            Text("Strength:", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RamPreset.values().forEach { p -> FilterChip(selected = auto.preset == p.name, onClick = { savePrefs(auto.copy(preset = p.name)) }, label = { Text(p.label) }) } }
            Text("Impact: ${runCatching { RamPreset.valueOf(auto.preset) }.getOrDefault(RamPreset.GENTLE).impact}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(svc, {
                    svc = it
                    if (it) { RamAutoService.start(ctx); if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) } else RamAutoService.stop(ctx)
                })
                Text("  Background monitor service (needed for the threshold and screen-off triggers; samples every ${if (AppState.lowEnd) 60 else 30} s)", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Without root") {
            Text("Android limits what an app can close: killBackgroundProcesses only affects cached/background processes. For manual control open Developer options and look for 'Running services' and 'Background process limit' (names vary by version; the limit is a debugging aid, not a permanent fix).", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { startSafe(ctx, android.content.Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Open Developer options") }
            OutlinedButton(onClick = { startSafe(ctx, android.content.Intent("android.settings.MEMORY_CARD_SETTINGS")) }) { Text("Open memory settings (if the ROM has it)") }
        }
        Section("History") {
            val hist = remember(tick, msg) { RamOptimizer.history() }
            if (hist.isEmpty()) Text("No runs yet.", style = MaterialTheme.typography.bodySmall)
            hist.take(15).forEach { h ->
                val d = (h.optLong("afterAvail") - h.optLong("beforeAvail")) / 1024
                Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.optLong("ts")))} · ${h.optString("label")}\nclosed ${h.optInt("killed")} · available ${h.optLong("beforeAvail") / 1024} → ${h.optLong("afterAvail") / 1024} MB (${if (d >= 0) "+" else ""}$d MB)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
            if (hist.isNotEmpty()) TextButton(onClick = { RamOptimizer.clearHistory(); tick++ }) { Text("Clear history") }
        }
    }
    flow?.let { RamOptimizeFlow(onClose = { flow = null; tick++ }, initial = it) }
    when (val c = confirm) {
        "drop" -> Confirm("Drop caches?", "Runs `sync; echo 3 > /proc/sys/vm/drop_caches`. It frees page/dentry caches only; Android refills them, so the next minute can be slower and use a little more battery. Not undoable, harmless but temporary.", "Drop caches",
            onYes = { confirm = null; run("Dropping caches") { val r = Sh2.drop(); r } }, onNo = { confirm = null })
        else -> if (c != null && c.startsWith("zram:")) Confirm("Resize zRAM?", "Expert operation: swap is turned off, the zram device is reset and recreated. If anything fails the previous size is restored. Runtime-only; reboot resets it. A wrong size can cause lag or app kills.", "Resize",
            onYes = { val b = c.removePrefix("zram:").toLong(); confirm = null; run("Resizing zRAM") { RamOptimizer.applyZram(b).message } }, onNo = { confirm = null })
    }
}

private object Sh2 {
    fun drop(): String = dev.optimizer.android.core.Sh.root("sync; echo 3 > /proc/sys/vm/drop_caches && echo ok", 30).let { if (it.out.contains("ok")) "Dropped caches." else "Failed: ${it.text}" }
    fun compact(): String = dev.optimizer.android.core.Sh.root("echo 1 > /proc/sys/vm/compact_memory && echo ok", 30).let { if (it.out.contains("ok")) "Compacted memory." else "Failed: ${it.text}" }
}
