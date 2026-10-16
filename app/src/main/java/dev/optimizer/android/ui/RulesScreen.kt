package dev.optimizer.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.FileProvider
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Rules
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.Assessment
import dev.optimizer.android.features.Enforce
import dev.optimizer.android.features.GuardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Rules screen (Q): every 'Never stop' / 'Keep closed' rule, toggles, enforcement level, guard service, export / import. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var guard by remember { mutableStateOf(Store.guardEnabled) }
    var enforce by remember { mutableStateOf<Pair<String, String>?>(null) } // pkg to mode awaiting confirmation
    var busy by remember { mutableStateOf(false) }
    val rules = remember(tick, AppState.logVersion) { Rules.all().sortedBy { it.label.lowercase() } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val txt = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val (n, c) = Rules.importJson(txt)
            msg = "Imported $n rule(s)${if (c > 0) "; $c conflict(s) resolved with 'Never stop' winning" else ""}."; tick++
        }.onFailure { msg = "Import failed: ${it.message}" }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("App rules") {
            Text("Never stop: honoured by every close/stop path (Monitor, Processes, RAM Optimizer, auto-optimizer, auto-kill service, guard). Keep closed: the guard closes the app again when it starts. The two exclude each other; 'Never stop' wins on conflicts. Protected system packages and the app you are using are never touched either way.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker = true }) { Text("Add app...") }
                OutlinedButton(onClick = {
                    val f = File(File(ctx.cacheDir, "reports").also { it.mkdirs() }, "app-rules.json").also { it.writeText(Rules.toJson()) }
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    startSafe(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export rules"))
                }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
            }
            Text("Rules are also written into every backup (restore category 'App rules').", style = MaterialTheme.typography.labelSmall)
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        Section("Keep-closed guard") {
            Text(if (Root.isRooted) "Root: reacts to app starts through the system event log (`logcat -b events am_proc_start`) in one persistent root shell; falls back to polling every ${dev.optimizer.android.features.Features.guardPollMs / 1000} s. Closing uses `am force-stop`." else "No root: checks every ${dev.optimizer.android.features.Features.guardPollMs / 1000} s and calls killBackgroundProcesses. This cannot stop an app that is in the foreground, and Android lets apps restart themselves, so it is best effort. Freeze needs root.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(guard, { guard = it; if (it) GuardService.start(ctx) else GuardService.stop(ctx) })
                Text("  Run guard service (foreground notification)")
            }
            Text("Kill-loop protection: more than ${GuardService.LIMIT} closes within ${GuardService.WINDOW_MS / 60000} min pauses the rule for ${GuardService.COOLDOWN_MS / 60000} min and sends a notification offering Freeze. Notification actions: Allow this time (30 min), Allow always (removes the rule), Keep blocked.", style = MaterialTheme.typography.bodySmall)
        }
        if (rules.isEmpty()) Info("No rules yet. Add one here, or use the lock / 'Keep closed' buttons in any close list.")
        rules.forEach { r ->
            Section(r.label.ifEmpty { r.pkg }) {
                Text(r.pkg, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, tint = if (r.never) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Switch(r.never, { on -> Rules.setNever(ctx, r.pkg, on)?.let { msg = it } ; tick++ })
                    Text("  Never stop this app", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(r.keepClosed, { on ->
                        if (!on) { scope.launch { if (r.mode != Rules.MODE_KILL) withContext(Dispatchers.IO) { Enforce.undo(r.pkg, r.mode) }; Rules.setKeepClosed(ctx, r.pkg, false); AppState.bumpLog(); tick++ } }
                        else { Rules.setKeepClosed(ctx, r.pkg, true)?.let { msg = it }; tick++ }
                    })
                    Text("  Keep closed (auto re-close)", style = MaterialTheme.typography.bodyMedium)
                }
                if (r.keepClosed) {
                    Text("Enforcement:", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Rules.MODE_KILL to "Close on start", Rules.MODE_BGRESTRICT to "Background restrict (root)", Rules.MODE_FREEZE to "Freeze (root)").forEach { (m, l) ->
                            FilterChip(selected = r.mode == m, enabled = m == Rules.MODE_KILL || AppState.unlocked, onClick = {
                                if (m == r.mode) return@FilterChip
                                if (m == Rules.MODE_KILL) scope.launch { withContext(Dispatchers.IO) { if (r.mode != Rules.MODE_KILL) Enforce.undo(r.pkg, r.mode) }; Rules.setMode(r.pkg, m); AppState.bumpLog(); tick++ }
                                else enforce = r.pkg to m
                            }, label = { Text(l) })
                        }
                    }
                    if (!AppState.unlocked) Text("Background restrict and Freeze need root and the baseline backup.", style = MaterialTheme.typography.labelSmall)
                    if (r.allowUntil > System.currentTimeMillis()) Text("Allowed temporarily until ${java.text.DateFormat.getTimeInstance().format(java.util.Date(r.allowUntil))}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = {
                    scope.launch { if (r.keepClosed && r.mode != Rules.MODE_KILL) withContext(Dispatchers.IO) { Enforce.undo(r.pkg, r.mode) }; Rules.remove(r.pkg); AppState.bumpLog(); tick++ }
                }) { Text("Remove rule") }
            }
        }
    }

    if (picker) {
        var apps by remember { mutableStateOf<List<dev.optimizer.android.features.AppAssessment>?>(null) }
        var q by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Assessment.apps(ctx).filter { !it.system } } }
        AlertDialog(onDismissRequest = { picker = false }, title = { Text("Add a rule for...") },
            text = { Column {
                OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true)
                if (apps == null) Busy("Loading apps...") else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(apps!!.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }, key = { it.pkg }) { a ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(a.label, fontWeight = FontWeight.SemiBold); Text(a.pkg, style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { msg = Rules.setNever(ctx, a.pkg, true) ?: "Never stop: ${a.label}"; tick++; picker = false }) { Text("Never stop") }
                                OutlinedButton(onClick = { msg = Rules.setKeepClosed(ctx, a.pkg, true) ?: "Keep closed: ${a.label}"; tick++; picker = false }) { Text("Keep closed") }
                            }
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Close") } })
    }
    enforce?.let { (pkg, mode) ->
        val plan = remember(pkg, mode) { Enforce.plan(pkg, mode) }
        AlertDialog(onDismissRequest = { enforce = null }, title = { Text(if (mode == Rules.MODE_FREEZE) "Freeze $pkg?" else "Background-restrict $pkg?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (mode == Rules.MODE_FREEZE) "Uses the existing Keep-closed freeze (pm disable-user, fallback suspend). The app disappears from the launcher until you unfreeze it here." else "Lighter and reversible: app-ops ignore for RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND, removal from the Doze whitelist, set-inactive and the restricted standby bucket. Exact commands:", style = MaterialTheme.typography.bodySmall)
                Text(plan.apply.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Undo:\n" + plan.undo.joinToString("\n").ifEmpty { "(see change log)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Busy(if (busy) "Applying..." else null)
            } },
            confirmButton = { Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val cur = Rules.get(pkg)
                    val r = withContext(Dispatchers.IO) { if (cur != null && cur.mode != Rules.MODE_KILL && cur.mode != mode) Enforce.undo(pkg, cur.mode); Enforce.apply(ctx, pkg, mode) }
                    if (r.ok) Rules.setMode(pkg, mode)
                    msg = r.message; busy = false; enforce = null; AppState.bumpLog(); tick++
                }
            }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { enforce = null }) { Text("Cancel") } })
    }
}
