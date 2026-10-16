package dev.optimizer.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.BatteryInfo
import dev.optimizer.android.features.CrashLog
import dev.optimizer.android.features.Diagnostics
import dev.optimizer.android.features.FixOpt
import dev.optimizer.android.features.FreezeDoctor
import dev.optimizer.android.features.FreezeReport
import dev.optimizer.android.features.Issue
import dev.optimizer.android.features.LiveService
import dev.optimizer.android.features.MaintSched
import dev.optimizer.android.features.ResetStock
import dev.optimizer.android.features.SafeBoost
import dev.optimizer.android.features.StartupApps
import dev.optimizer.android.features.TempLog
import dev.optimizer.android.features.UsageRank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetConfigUi(onDone: () -> Unit) {
    var iv by remember { mutableIntStateOf(Store.getInt("live_interval_ms", if (dev.optimizer.android.features.Features.lowEnd()) 5000 else 2000)) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Widget update interval", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Widgets are refreshed by a small foreground service only while the screen is on. With the screen off nothing is updated (one temperature sample every 5 minutes). Faster = more CPU and battery; 5 s is recommended on weak tablets. The system limit for Android's own widget updates (updatePeriodMillis) is 30 minutes, so this service is what makes 1-5 s updates possible.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1000 to "1 s", 2000 to "2 s", 5000 to "5 s").forEach { (v, l) -> FilterChip(selected = iv == v, onClick = { iv = v }, label = { Text(l) }) } }
            Text("The interval is shared by all widgets and can be changed later in More › Quick tools. Removing the last widget stops the service.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { Store.putInt("live_interval_ms", iv); onDone() }) { Text("Add widget") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var iv by remember { mutableIntStateOf(Store.getInt("live_interval_ms", 2000)) }
    var notif by remember { mutableStateOf(Store.getBool("live_notif")) }
    var maint by remember { mutableStateOf(MaintSched.prefs()) }
    var confirmBoost by remember { mutableStateOf(false) }
    var resetDlg by remember { mutableStateOf(false) }
    var rsClear by remember { mutableStateOf(false) }
    var rsStop by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pending = remember(tick, AppState.logVersion) { ResetStock.pending() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Safe Boost (one tap, one undo)") {
            Text("Does only low-risk things:", style = MaterialTheme.typography.bodyMedium)
            SafeBoost.plan().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("Impact: a short-lived RAM gain and slightly snappier animations. It will not fix a worn storage chip or a failing battery; use the Freeze Doctor for the cause.", style = MaterialTheme.typography.bodySmall)
            Busy(busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = { confirmBoost = true }) { Text("Safe Boost") }
                OutlinedButton(enabled = busy == null && SafeBoost.active(), onClick = { scope.launch { busy = "Undoing..."; msg = withContext(Dispatchers.IO) { SafeBoost.undo().joinToString("\n") }; busy = null; AppState.bumpLog(); tick++ } }) { Text("Undo Safe Boost") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        Section("Home-screen widgets & live notification") {
            Text("Long-press the home screen › Widgets › Android Optimizer: Small (RAM, CPU), Medium (RAM, CPU, storage, battery, temperature) and Large (live CPU/RAM graphs, per-core CPU if readable, swap, storage, battery, thermal, uptime, network rate, top 5 RAM apps, Optimize RAM / Open / Refresh). All are resizable and follow the system dark/light mode.", style = MaterialTheme.typography.bodySmall)
            Text("Update interval (only while the screen is on):", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1000 to "1 s", 2000 to "2 s", 5000 to "5 s").forEach { (v, l) -> FilterChip(selected = iv == v, onClick = { iv = v; Store.putInt("live_interval_ms", v); LiveService.kick() }, label = { Text(l) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(notif, { notif = it; Store.putBool("live_notif", it); if (it && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS); LiveService.sync(ctx); LiveService.kick() })
                Text("  Persistent notification with RAM / CPU (updates every ≥ 5 s)", style = MaterialTheme.typography.bodySmall)
            }
            Info("Android limits AppWidget updatePeriodMillis to 30 minutes, so 1-5 s updates come from a foreground service that runs only while a widget exists or the notification is on. On Android 12+ the system may refuse to start it from the background after a reboot; open this app once to restart it. CPU values need /proc/stat, which Android 8+ blocks for apps: without root the widgets show 'n/a'. Top-5 apps need root.")
        }
        Section("Scheduled maintenance (idle + charging)") {
            Text("WorkManager runs the selected jobs when the device is idle and charging (Android decides the exact time; the interval is a minimum). Needs root and the baseline; in the background it never prompts for root and is skipped if the manager does not remember the grant. Heavy dexopt is blocked in low-end mode.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(maint.on, { maint = maint.copy(on = it); MaintSched.save(ctx, maint) }); Text("  Enabled") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "daily", 7 to "weekly", 14 to "every 2 weeks").forEach { (d, l) -> FilterChip(selected = maint.days == d, onClick = { maint = maint.copy(days = d); MaintSched.save(ctx, maint) }, label = { Text(l) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(maint.fstrim, { maint = maint.copy(fstrim = it); MaintSched.save(ctx, maint) }); Text("fstrim /data", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(maint.trim, { maint = maint.copy(trim = it); MaintSched.save(ctx, maint) }); Text("Clear app caches (pm trim-caches, Android 7+)", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(maint.dexopt, { maint = maint.copy(dexopt = it); MaintSched.save(ctx, maint) }); Text("Background dexopt job (Android 8+, heavy)", style = MaterialTheme.typography.bodySmall) }
            Text("Last run: ${MaintSched.lastRun()}", style = MaterialTheme.typography.labelSmall)
        }
        Section("Reset to stock") {
            Text("Reverts everything this app changed through root, in reverse order: ${pending.size} logged change(s) are active now. Optionally also clears your rules and stops the app's own services and schedules. It does NOT undo one-shot actions (force-stops, cache clears, fstrim, files moved by the cleaner) and it is not a factory reset.", style = MaterialTheme.typography.bodySmall)
            pending.take(6).forEach { Text("• ${it.title}", style = MaterialTheme.typography.labelSmall) }
            if (pending.size > 6) Text("… and ${pending.size - 6} more", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { resetDlg = true }) { Text("Reset to stock…") }
        }
    }
    if (confirmBoost) Confirm("Run Safe Boost?", SafeBoost.plan().joinToString("\n") { "• $it" } + "\n\nOne undo button reverts the reversible parts.", "Run",
        onYes = { confirmBoost = false; scope.launch { busy = "Boosting..."; msg = withContext(Dispatchers.IO) { SafeBoost.apply(ctx).joinToString("\n") }; busy = null; AppState.bumpLog(); tick++ } }, onNo = { confirmBoost = false })
    if (resetDlg) androidx.compose.material3.AlertDialog(onDismissRequest = { resetDlg = false }, title = { Text("Reset to stock?") },
        text = { Column { Text("Reverts ${pending.size} logged change(s) (needs root for the shell-based ones).", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rsStop, { rsStop = it }); Text("Stop Guard, RAM monitor, live notification, schedules", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rsClear, { rsClear = it }); Text("Also delete all Never-stop / Keep-closed rules", style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { Button(onClick = { resetDlg = false; scope.launch { busy = "Resetting..."; msg = withContext(Dispatchers.IO) { ResetStock.run(ctx, rsClear, rsStop).joinToString("\n") }; busy = null; AppState.bumpLog(); tick++; notif = Store.getBool("live_notif"); maint = MaintSched.prefs() } }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { resetDlg = false }) { Text("Cancel") } })
}

/** First-run Quick Fix: scan, tick the recommended fixes, apply with a command preview. */
@Composable
fun QuickFixScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<FreezeReport?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val sel = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf<List<Pair<Issue, FixOpt>>?>(null) }
    var previewText by remember { mutableStateOf("") }
    fun all(): List<Pair<Issue, FixOpt>> = report?.issues.orEmpty().take(4).flatMap { i -> i.options.filter { it.rootPlan != null || it.local != null }.map { i to it } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Quick Fix") {
            Text("Three steps: 1) measure what slows this device down, 2) tick the suggested fixes (the recommended ones are preselected), 3) apply. You see the exact commands first, and root changes are logged and undoable.", style = MaterialTheme.typography.bodySmall)
            Busy(busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = { scope.launch { busy = "Measuring (about 5-20 s)..."; sel.clear(); val r = withContext(Dispatchers.IO) { FreezeDoctor.scan(ctx, Root.isRooted) }; report = r; r.issues.take(4).forEach { i -> i.options.filter { it.recommended && (it.rootPlan != null || it.local != null) }.forEach { sel.add("${i.id}|${it.id}") } }; busy = null } }) { Text(if (report == null) "1. Measure" else "Measure again") }
                OutlinedButton(enabled = busy == null, onClick = { AppState.openPage("tools") }) { Text("Safe Boost…") }
            }
            if (!Root.isRooted) Info("No root: only what Android allows is measured. Root-only fixes are hidden or disabled.")
        }
        report?.let { r ->
            Section("Top causes (ranked)") {
                if (r.issues.isEmpty()) Text("Nothing suspicious was measured. Run the full Freeze Doctor in More › Freeze Doctor for details.", style = MaterialTheme.typography.bodySmall)
                r.issues.take(4).forEachIndexed { n, i ->
                    Text("#${n + 1} ${i.title}  (${i.sev.label}, score ${i.score})", fontWeight = FontWeight.SemiBold)
                    i.evidence.drop(1).take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    i.options.filter { it.rootPlan != null || it.local != null }.forEach { o ->
                        val k = "${i.id}|${o.id}"
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(k in sel, { if (it) sel.add(k) else sel.remove(k) }); Text(o.label, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Button(enabled = sel.isNotEmpty() && busy == null, onClick = {
                    val chosen = all().filter { "${it.first.id}|${it.second.id}" in sel }
                    scope.launch { busy = "Building preview..."; val p = withContext(Dispatchers.IO) { Diagnostics.preview(chosen) }; previewText = p.lines.joinToString("\n").ifEmpty { "(no shell commands - in-app actions only)" }; busy = null; confirm = chosen }
                }) { Text("3. Review & apply (${sel.size})") }
                TextButton(onClick = { AppState.openPage("freeze") }) { Text("Open the full Freeze Doctor") }
            }
        }
        msg?.let { Section("Result") { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
    confirm?.let { list -> Confirm("Apply ${list.size} fix(es)?", previewText + (if (!AppState.unlocked) "\n\nRoot + baseline are needed for shell fixes; they are skipped otherwise." else ""), "Apply",
        onYes = { confirm = null; scope.launch { busy = "Applying..."; msg = withContext(Dispatchers.IO) { Diagnostics.apply(list).joinToString("\n") }; busy = null; AppState.bumpLog() } }, onNo = { confirm = null }) }
}

@Composable
fun StartupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sys by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<dev.optimizer.android.features.BootApp>?>(null) }
    LaunchedEffect(sys, tick) { apps = withContext(Dispatchers.IO) { StartupApps.list(ctx, sys) } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Startup apps (BOOT_COMPLETED receivers)") {
            Text("Apps that start themselves after boot. With root and the baseline you can disable a boot receiver (`pm disable pkg/receiver`); the app still works when opened. Each change is logged and undoable (also in More › Optimize › change log). Critical packages are protected. Apps can re-enable components when they update.", style = MaterialTheme.typography.bodySmall)
            if (!AppState.unlocked) Warn("Without root + baseline this list is read-only. Alternative: Settings › Apps › the app › Battery/Autostart (vendor-specific).")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(sys, { sys = it }); Text("  Show system apps", style = MaterialTheme.typography.bodySmall) }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        val l = apps
        if (l == null) Busy("Reading receivers...") else {
            Text("${l.size} app(s), ${l.count { a -> a.receivers.any { it.enabled } }} with an active boot receiver", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
            l.forEach { a ->
                Section(a.label) {
                    Text(a.pkg + (a.protectedReason?.let { " · PROTECTED: $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                    a.receivers.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(r.enabled, enabled = a.protectedReason == null && AppState.unlocked, onCheckedChange = { on -> scope.launch { val o = withContext(Dispatchers.IO) { StartupApps.setEnabled(ctx, a.pkg, r.cls, on) }; msg = o.message; AppState.bumpLog(); tick++ } })
                            Text("  " + r.cls.substringAfterLast('.'), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { startSafe(ctx, dev.optimizer.android.features.AppControl.settingsIntent(a.pkg)) }) { Text("App settings") }
                }
            }
        }
    }
}

@Composable
private fun LineChart(series: List<Pair<List<Pair<Long, Float?>>, Color>>, lo: Float, hi: Float, t0: Long, t1: Long) {
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val w = size.width; val h = size.height
        for (g in 0..3) drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, h * g / 3f), Offset(w, h * g / 3f))
        val span = (t1 - t0).coerceAtLeast(1); val rng = (hi - lo).coerceAtLeast(1f)
        series.forEach { (pts, col) ->
            var prev: Offset? = null
            pts.forEach { (t, v) ->
                if (v == null) { prev = null; return@forEach }
                val o = Offset((t - t0).toFloat() / span * w, h - (v - lo) / rng * h)
                prev?.let { drawLine(col, it, o, strokeWidth = 4f) }; prev = o
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var hours by remember { mutableIntStateOf(24) }
    var days by remember { mutableIntStateOf(1) }
    var usage by remember { mutableStateOf<List<dev.optimizer.android.features.UsageRow>?>(null) }
    val rep = remember(tick) { BatteryInfo.read(ctx) }
    val pts = remember(tick, hours) { TempLog.read(System.currentTimeMillis() - hours * 3600_000L) }
    val hasUsage = remember(tick) { dev.optimizer.android.features.Monitor.hasUsageAccess(ctx) }
    LaunchedEffect(days, tick, hasUsage) { usage = if (hasUsage) withContext(Dispatchers.IO) { runCatching { UsageRank.top(ctx, days) }.getOrDefault(emptyList()) } else null }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Battery health") {
            rep.warnings.forEach { Warn(it) }
            rep.rows.forEach { KV(it.first, it.second) }
            Text("Android does not give apps a true 'state of health' percentage; the capacity-vs-design row appears only if the device exposes charge_full/charge_full_design.", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
            OutlinedButton(onClick = { startSafe(ctx, Intent("android.intent.action.POWER_USAGE_SUMMARY").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Android battery usage") }
        }
        Section("Temperature log") {
            Text("Battery temperature (blue) and the hottest readable thermal zone (red), sampled every minute while the live service runs with the screen on, every 5 minutes otherwise (Android may delay samples while the device sleeps), and on every add. Samples exist only for times when the service or this button ran.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(6 to "6 h", 24 to "24 h", 168 to "7 days").forEach { (h, l) -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(l) }) } }
            if (pts.size < 2) Text("Not enough samples yet. Turn on the widgets or the live notification (More › Quick tools), or tap 'Add sample'.", style = MaterialTheme.typography.bodySmall)
            else {
                val all = pts.flatMap { listOfNotNull(it.battC, it.maxZoneC) }; val lo = (all.minOrNull() ?: 20f) - 2; val hi = (all.maxOrNull() ?: 50f) + 2
                LineChart(listOf(pts.map { it.ts to it.battC } to Color(0xFF3F7FD6), pts.map { it.ts to it.maxZoneC } to Color(0xFFD64A3F)), lo, hi, pts.first().ts, pts.last().ts)
                val b = pts.mapNotNull { it.battC }
                if (b.isNotEmpty()) Text("Battery: min %.1f · avg %.1f · max %.1f °C over ${pts.size} samples; axis %.0f-%.0f °C".format(b.min(), b.average(), b.max(), lo, hi), style = MaterialTheme.typography.bodySmall)
                if (b.any { it >= 43f }) Warn("Battery reached ≥ 43 °C: the system throttles the CPU when hot.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { TempLog.add(ctx); tick++ }) { Text("Add sample") }
                OutlinedButton(onClick = { TempLog.clear(); tick++ }) { Text("Clear log") }
            }
        }
        Section("App usage ranking (screen time)") {
            Text("Foreground time per app from Usage Access. Android hides per-app battery and CPU use from apps; use the system battery screen above for energy.", style = MaterialTheme.typography.bodySmall)
            if (!hasUsage) { Warn("Usage Access is not granted."); Button(onClick = { startSafe(ctx, dev.optimizer.android.features.Monitor.usageAccessIntent()) }) { Text("Open Usage Access settings") } }
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "24 h", 7 to "7 days").forEach { (d, l) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) }) } }
                val u = usage
                if (u == null) Busy("Reading...") else if (u.isEmpty()) Text("No usage data for this period.", style = MaterialTheme.typography.bodySmall)
                else u.forEachIndexed { n, r -> val m = r.foregroundMs / 60000; Text("${n + 1}. ${r.label} - ${if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun CrashLogScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Crash & ANR log") {
            Text("Shows what Android recorded about crashes and 'not responding' events. With root: the crash log buffer, /data/anr and the system dropbox summary (system_server and SystemUI problems show up here). Without root only the current error state and this app's own exit reasons (Android 11+) are available.", style = MaterialTheme.typography.bodySmall)
            Busy(busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = { scope.launch { busy = "Reading logs..."; data = withContext(Dispatchers.IO) { CrashLog.read(ctx) }; busy = null } }) { Text(if (data == null) "Load" else "Reload") }
                OutlinedButton(enabled = data != null, onClick = { copyText(ctx, "Crash log", data!!.joinToString("\n\n") { "== ${it.first}\n${it.second}" }) }) { Text("Copy all") }
            }
        }
        data?.forEach { (t, b) -> Section(t) { Text(b.take(6000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) } }
    }
}
