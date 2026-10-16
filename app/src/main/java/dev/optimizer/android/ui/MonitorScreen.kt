package dev.optimizer.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.AppControl
import dev.optimizer.android.features.AppRow
import dev.optimizer.android.features.AutoKillService
import dev.optimizer.android.features.Monitor
import dev.optimizer.android.features.MonitorSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MiniChart(values: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width; val h = size.height
        drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, h), Offset(w, h))
        if (values.size < 2) return@Canvas
        val step = w / 59f
        for (i in 1 until values.size) {
            val x0 = (i - 1) * step; val x1 = i * step
            drawLine(color, Offset(x0, h - h * values[i - 1] / 100f), Offset(x1, h - h * values[i] / 100f), strokeWidth = 4f)
        }
    }
}

@Composable
fun MonitorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<MonitorSnapshot?>(null) }
    val cpuHist = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    val ramHist = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    var sort by remember { mutableStateOf("ram") }
    var showSystem by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(Monitor.hasUsageAccess(ctx)) }
    var confirmKeep by remember { mutableStateOf<AppRow?>(null) }
    var blockedVersion by remember { mutableIntStateOf(0) }
    var autoKill by remember { mutableStateOf(Store.autoKillEnabled) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) usage = Monitor.hasUsageAccess(ctx) }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }

    LaunchedEffect(AppState.rootState) {
        while (true) {
            val s = withContext(Dispatchers.IO) { Monitor.snapshot(ctx, true) }
            snap = s
            s.cpu.percent?.let { cpuHist.add(it); if (cpuHist.size > 60) cpuHist.removeAt(0) }
            val m = s.mem; ramHist.add(m.usedKb.toFloat() / m.totalKb.coerceAtLeast(1) * 100f); if (ramHist.size > 60) ramHist.removeAt(0)
            delay(2000)
        }
    }

    val rows = remember(snap, sort, showSystem, blockedVersion) {
        val r = (snap?.apps ?: emptyList()).filter { showSystem || !it.system || it.blocked }
        when (sort) {
            "ram" -> r.sortedByDescending { it.pssKb ?: -1 }
            "cpu" -> r.sortedByDescending { it.cpu ?: -1f }
            "recent" -> r.sortedByDescending { it.lastUsed ?: 0L }
            else -> r.sortedBy { it.label.lowercase() }
        }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Section("Live usage (every 2 s)") {
                val s = snap
                if (s == null) Busy("Sampling...") else {
                    val cpu = s.cpu.percent
                    Text("CPU: " + (cpu?.let { "%.0f%%".format(it) } ?: "n/a") + "   (${s.cpu.source})", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { (cpu ?: 0f) / 100f }, Modifier.fillMaxWidth())
                    MiniChart(cpuHist, MaterialTheme.colorScheme.primary)
                    val m = s.mem
                    Text("RAM: %.0f MB used / %.0f MB total · %.0f MB available".format(m.usedKb / 1024.0, m.totalKb / 1024.0, m.availKb / 1024.0), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { m.usedKb.toFloat() / m.totalKb.coerceAtLeast(1) }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
                    MiniChart(ramHist, MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        if (!usage) item {
            Section("Usage Access") {
                Text("Grant Usage Access so the app can list recently used apps (Android hides other apps' processes from normal apps).", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { startSafe(ctx, Monitor.usageAccessIntent()) }) { Text("Open Usage Access settings") }
            }
        }
        item {
            Section("Running / recent apps") {
                Text(snap?.appsNote ?: "", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("ram" to "RAM", "cpu" to "CPU", "recent" to "Recent", "name" to "Name").forEach { (k, l) ->
                        FilterChip(selected = sort == k, onClick = { sort = k }, label = { Text(l) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(showSystem, { showSystem = it }); Text("  Show system apps", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(rows, key = { it.pkg }) { a ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(a.label + if (a.system) " (system)" else "", fontWeight = FontWeight.SemiBold)
                Text("${a.pkg}\nRAM: ${a.pssKb?.let { "%.0f MB".format(it / 1024.0) } ?: "-"} · CPU: ${a.cpu?.let { "%.1f%%".format(it) } ?: "-"}" +
                    (a.protectedReason?.let { "\nProtected: $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = a.protectedReason == null, onClick = {
                        scope.launch { val r = withContext(Dispatchers.IO) { AppControl.close(ctx, a.pkg) }; toast(ctx, r.message) }
                    }) { Text("Close") }
                    OutlinedButton(enabled = a.protectedReason == null, onClick = { confirmKeep = a }) { Text("Keep closed") }
                }
            }
        }
        item {
            val blocked = remember(blockedVersion, AppState.logVersion) { Store.blocked() }
            Section("Blocked apps (${blocked.size})") {
                if (blocked.isEmpty()) Text("Nothing is blocked.", style = MaterialTheme.typography.bodySmall)
                blocked.forEach { b ->
                    val label = remember(b.pkg) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(b.pkg, 0)).toString() }.getOrDefault(b.pkg) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontWeight = FontWeight.SemiBold)
                            Text("${b.pkg} · ${when (b.method) { "disable" -> "frozen (pm disable-user)"; "suspend" -> "suspended (pm suspend)"; else -> "watch list (non-root)" }}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { AppControl.allow(ctx, b.pkg) }
                                toast(ctx, r.message); blockedVersion++; AppState.bumpLog()
                                if (r.ok && b.method != "settings") AppControl.launch(ctx, b.pkg)
                            }
                        }) { Text("Allow / Open") }
                    }
                }
            }
        }
        item {
            Section("Non-root helper") {
                Text("Without root nothing can be blocked permanently. This optional service re-kills background processes of 'watch list' apps every 30 s.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(autoKill, {
                        autoKill = it
                        if (it) {
                            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                            AutoKillService.start(ctx)
                        } else AutoKillService.stop(ctx)
                    }); Text("  Auto re-kill service", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    confirmKeep?.let { a ->
        val rooted = Root.isRooted
        Confirm(
            "Keep ${a.label} closed?",
            if (rooted) "This force-stops the app and freezes it with `pm disable-user --user 0 ${a.pkg}` until you tap 'Allow / Open'. It disappears from the launcher while frozen. The change is logged and reversible."
            else "Without root this cannot be enforced. The app is added to the watch list (re-killed by the optional helper) and its system settings page opens so you can tap Force stop. True permanent blocking needs root.",
            "Keep closed",
            onYes = {
                confirmKeep = null
                scope.launch {
                    if (rooted) {
                        val r = withContext(Dispatchers.IO) { AppControl.keepClosedRoot(ctx, a.pkg, a.label) }
                        toast(ctx, r.message); AppState.bumpLog()
                    } else {
                        AppControl.keepClosedNonRoot(a.pkg)
                        withContext(Dispatchers.IO) { AppControl.close(ctx, a.pkg) }
                        startSafe(ctx, AppControl.settingsIntent(a.pkg))
                    }
                    blockedVersion++
                }
            }, onNo = { confirmKeep = null })
    }
}

