package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.Change
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.features.Assessment
import dev.optimizer.android.features.Bloat
import dev.optimizer.android.features.Fix
import dev.optimizer.android.features.Optimizer
import dev.optimizer.android.features.Vendor
import dev.optimizer.android.features.VendorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun OptimizeScreen(goRoot: () -> Unit) {
    if (!AppState.unlocked) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Section("Optimize is locked") {
                Text(
                    if (!AppState.rooted && !dev.optimizer.android.core.Priv.writeSecure()) (if (dev.optimizer.android.core.Edition.ROOT) "Optimizing applies changes through a privileged shell: it needs root, Shizuku or the WRITE_SECURE_SETTINGS grant. Open the Root tab (root) or More › Access (Shizuku / adb)." else "Optimizing applies changes through Shizuku or the WRITE_SECURE_SETTINGS adb grant. Open the Access tab to set one up; the guided shortcuts below work without anything.")
                    else "Access is available, but there is no baseline backup yet. Create it first (Access tab / Root tab) so every change can be reverted.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = goRoot) { Text(if (dev.optimizer.android.core.Edition.ROOT) "Go to Root tab" else "Go to Access tab") }
            }
            GuidedSettingsCard()
        }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmFix by remember { mutableStateOf<Fix?>(null) }
    var confirmMaint by remember { mutableStateOf<Optimizer.Maint?>(null) }
    var confirmDebloat by remember { mutableStateOf(false) }
    var confirmProfile by remember { mutableStateOf(false) }
    var confirmRevertAll by remember { mutableStateOf(false) }
    var confirmUndoAll by remember { mutableStateOf(false) }
    var dozePicker by remember { mutableStateOf(false) }
    var vendor by remember { mutableStateOf<VendorInfo?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val ver = AppState.logVersion
    val log = remember(ver) { ChangeLog.all().sortedByDescending { it.ts } }

    androidx.compose.runtime.LaunchedEffect(Unit) { vendor = withContext(Dispatchers.IO) { Vendor.detect() } }

    fun run(label: String, block: () -> String) {
        scope.launch { busy = label; msg = withContext(Dispatchers.IO) { block() }; busy = null; AppState.bumpLog() }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Busy(busy)
        msg?.let { Section("Result") { Text(it, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { msg = null }) { Text("Dismiss") } } }

        Section("Profile") {
            Text("Conservative profile: animation scale 0.5x, exempt this app from Doze, clear caches. Every step is logged and reversible (except cache clearing).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = { confirmProfile = true }) { Text("Apply recommended") }
                OutlinedButton(enabled = busy == null, onClick = { confirmUndoAll = true }) { Text("Undo all") }
            }
        }

        Section("Performance fixes") {
            Optimizer.fixes.forEach { f ->
                val on = remember(ver) { ChangeLog.isActive(f.id) }
                val avail = Optimizer.fixAvailable(f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title, fontWeight = FontWeight.SemiBold)
                        Text("Risk: ${f.risk.label}" + (if (!avail) " · not available: needs Android ${dev.optimizer.android.features.Features.androidName(f.minSdk)}+ (this device: API ${android.os.Build.VERSION.SDK_INT})" else ""), style = MaterialTheme.typography.labelMedium,
                            color = if (f.risk.ordinal > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text(f.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = on, enabled = avail && busy == null, onCheckedChange = {
                        if (it) confirmFix = f else run("Reverting ${f.title}") { Optimizer.undoFix(f.id).message }
                    })
                }
            }
        }

        Section("Maintenance (one-shot, not reversible)") {
            Optimizer.maintenance.forEach { m ->
                Column(Modifier.fillMaxWidth()) {
                    Text(m.title, fontWeight = FontWeight.SemiBold)
                    Text(m.desc, style = MaterialTheme.typography.bodySmall)
                    val blocked = Optimizer.maintBlockedReason(m)
                    blocked?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(enabled = busy == null && blocked == null, onClick = { confirmMaint = m }) { Text("Run") }
                }
            }
        }

        Section("Doze / battery whitelist") {
            if (!dev.optimizer.android.core.Compat.hasDoze) Warn("Doze does not exist before Android 6 - this section does nothing on this device.")
            Text("Exempts chosen apps from Doze (`dumpsys deviceidle whitelist`) and allows RUN_IN_BACKGROUND (`cmd appops`). Previous app-op mode is recorded for undo.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = { dozePicker = true }) { Text("Pick apps") }
                OutlinedButton(enabled = busy == null, onClick = { run("Reading whitelist") { Optimizer.currentWhitelist() } }) { Text("Show whitelist") }
            }
        }

        val v = vendor
        Section("Vendor ROM fixes") {
            if (v == null) Busy("Detecting ROM...") else {
                Text("Detected: ${v.groups.joinToString { it.label }}\nROM: ${v.rom}", fontWeight = FontWeight.SemiBold)
                Text("Evidence: " + v.evidence.joinToString(" | ").ifEmpty { "manufacturer ${android.os.Build.MANUFACTURER}" }, style = MaterialTheme.typography.bodySmall)
                v.groups.forEach { g -> Vendor.notes[g]?.forEach { Info(it) } }
                val screens = remember(v) { Vendor.autostartIntents(ctx, v) }
                if (screens.isNotEmpty()) {
                    Text("Autostart / battery killer settings (opens the vendor's own screen; availability varies by ROM version):", style = MaterialTheme.typography.bodySmall)
                    screens.forEach { (l, i) -> OutlinedButton(onClick = { startSafe(ctx, i) }) { Text(l) } }
                }
                OutlinedButton(onClick = { startSafe(ctx, android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Android battery optimisation list") }
                OutlinedButton(onClick = { startSafe(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://dontkillmyapp.com/")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Guide: dontkillmyapp.com") }
            }
        }

        if (v != null) {
            val cands = remember(v) {
                Vendor.candidatesFor(v).map { (g, l) -> g to l.filter { dev.optimizer.android.features.AppControl.isInstalled(ctx, it.pkg) } }.filter { it.second.isNotEmpty() }
            }
            Section("Optional debloat candidates (disable, never uninstall)") {
                Warn("Disabling vendor packages can remove features (cloud sync, push, themes, game mode). Candidates were cross-checked against the Universal Android Debloater 'Recommended' list but ROM versions differ - read each description. Packages marked 'verify' may be needed on some builds. Critical packages are never offered. Everything is reversible from the change log.")
                if (cands.isEmpty()) Text("None of the known candidate packages are installed on this device (or no verified list exists for this vendor).", style = MaterialTheme.typography.bodySmall)
                cands.forEach { (g, l) ->
                    Text(g.label, fontWeight = FontWeight.SemiBold)
                    l.forEach { b ->
                        val already = remember(ver) { ChangeLog.isActive("debloat:${b.pkg}") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(b.pkg in selected, enabled = !already, onCheckedChange = { if (it) selected.add(b.pkg) else selected.remove(b.pkg) })
                            Column {
                                Text(b.pkg + if (already) "  (disabled by this app)" else "", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Text(b.what + (if (b.note.isNotEmpty()) " — VERIFY: ${b.note}" else ""), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (cands.isNotEmpty()) Button(enabled = selected.isNotEmpty() && busy == null, onClick = { confirmDebloat = true }) { Text("Disable ${selected.size} selected") }
            }
        }

        Section("Change log") {
            Button(enabled = busy == null && log.any { !it.undone && it.undoable }, onClick = { confirmRevertAll = true }) { Text("Revert everything to baseline") }
            if (log.isEmpty()) Text("No changes yet.", style = MaterialTheme.typography.bodySmall)
            log.take(100).forEach { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text((if (c.undone) "↩ " else "") + c.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("${DateFormat.getDateTimeInstance().format(Date(c.ts))} · ${c.category}${if (c.undone) " · undone" else if (!c.undoable) " · not undoable" else ""}\n${c.detail}", style = MaterialTheme.typography.bodySmall)
                    if (!c.undone && c.undoable) OutlinedButton(enabled = busy == null, onClick = {
                        run("Undoing ${c.title}") { ChangeLog.undo(c) ?: "Undone: ${c.title}" }
                    }) { Text("Undo this") }
                }
            }
        }
    }

    confirmFix?.let { f ->
        Confirm("Apply: ${f.title}?", "Risk: ${f.risk.label}.\n\n${f.description}\n\nIt is logged and can be undone with the switch or 'Undo this'.", "Apply",
            onYes = { confirmFix = null; run("Applying ${f.title}") { Optimizer.apply(f).message } }, onNo = { confirmFix = null })
    }
    confirmMaint?.let { m ->
        Confirm("Run: ${m.title}?", m.desc, "Run", onYes = { confirmMaint = null; run("Running ${m.title}...") { Optimizer.runMaintenance(m).message } }, onNo = { confirmMaint = null })
    }
    if (confirmProfile) Confirm("Apply recommended profile?", "Applies animation scale 0.5x, Doze exemption for this app, and clears app caches.", "Apply",
        onYes = { confirmProfile = false; run("Applying profile") { Optimizer.applyRecommended().joinToString("\n") { it.message.take(300) } } }, onNo = { confirmProfile = false })
    if (confirmUndoAll) Confirm("Undo all changes?", "Reverts every active change recorded in the change log (newest first), including frozen apps and disabled packages made by this app.", "Undo all",
        onYes = { confirmUndoAll = false; run("Undoing all") { Optimizer.undoAllOptimize() } }, onNo = { confirmUndoAll = false })
    if (confirmRevertAll) Confirm("Revert everything to baseline?", "Undoes all logged changes, then restores settings and package enabled/disabled states from the baseline backup. A safety backup is not created for this operation; the baseline itself is kept.", "Revert",
        onYes = { confirmRevertAll = false; run("Reverting to baseline") { Backups.revertEverything(ctx) } }, onNo = { confirmRevertAll = false })
    if (confirmDebloat) Confirm("Disable ${selected.size} packages?",
        "These vendor packages will be disabled for user 0 (not uninstalled):\n" + selected.joinToString("\n") +
            "\n\nThis may remove features. You can re-enable each from the change log.", "Disable",
        onYes = {
            confirmDebloat = false
            val all = Vendor.candidates.values.flatten().associateBy { it.pkg }
            val picks = selected.toList(); selected.clear()
            run("Disabling packages") { picks.joinToString("\n") { p -> Optimizer.debloat(ctx, all[p] ?: Bloat(p, "")).message } }
        }, onNo = { confirmDebloat = false })
    if (dozePicker) {
        var apps by remember { mutableStateOf<List<dev.optimizer.android.features.AppAssessment>?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Assessment.apps(ctx).filter { !it.system } } }
        AlertDialog(onDismissRequest = { dozePicker = false }, title = { Text("Exempt an app") },
            text = {
                if (apps == null) Busy("Loading apps...") else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(apps!!, key = { it.pkg }) { a ->
                        val on = ChangeLog.isActive("doze:${a.pkg}")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(a.label); Text(a.pkg, style = MaterialTheme.typography.bodySmall) }
                            if (on) Text("done") else TextButton(onClick = { run("Exempting ${a.label}") { Optimizer.whitelist(ctx, a.pkg).message } }) { Text("Exempt") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dozePicker = false }) { Text("Close") } })
    }
}
