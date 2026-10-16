package dev.optimizer.android.ui

import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.features.Processes
import dev.optimizer.android.features.Proc
import dev.optimizer.android.features.SnapCat
import dev.optimizer.android.features.SnapInfo
import dev.optimizer.android.features.Snapshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProcessesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var procs by remember { mutableStateOf<List<Proc>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("score") }
    var onlyApps by remember { mutableStateOf(true) }
    var action by remember { mutableStateOf<String?>(null) }
    val sel = remember { mutableStateListOf<Int>() } // pids
    var expanded by remember { mutableStateOf<Int?>(null) }
    var snapTick by remember { mutableIntStateOf(0) }
    var restoreFor by remember { mutableStateOf<SnapInfo?>(null) }
    var stopAll by remember { mutableStateOf(false) }
    var rv by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        busy = "Reading processes..."
        val (l, n) = withContext(Dispatchers.IO) { Processes.list(ctx) }
        procs = l; note = n; busy = null
    }
    val rows = remember(procs, sort, onlyApps) {
        val r = if (onlyApps) procs.filter { it.pkg != null } else procs
        when (sort) { "ram" -> r.sortedByDescending { it.rssKb }; "cpu" -> r.sortedByDescending { it.cpu ?: -1f }; "name" -> r.sortedBy { it.label.lowercase() }; "start" -> r.sortedByDescending { it.startMs ?: 0L }; else -> r.sortedByDescending { it.score } }
    }
    val snaps = remember(snapTick) { Snapshots.list() }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Section("Processes") {
                Text(Processes.DISCLAIMER, style = MaterialTheme.typography.bodySmall)
                Text(note, style = MaterialTheme.typography.bodySmall)
                Busy(busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = busy == null, onClick = { sel.clear(); reload++ }) { Text("Refresh") }
                    Button(enabled = sel.isNotEmpty() && busy == null, onClick = { action = "stop" }) { Text("Stop (${sel.size})") }
                    OutlinedButton(enabled = busy == null, onClick = { stopAll = true }) { Text("Stop all…") }
                    OutlinedButton(enabled = sel.isNotEmpty() && busy == null, onClick = { action = "ban" }) { Text("Ban") }
                    OutlinedButton(enabled = sel.isNotEmpty() && busy == null, onClick = { action = "unban" }) { Text("Unban") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("score" to "Suspicion", "ram" to "RAM", "cpu" to "CPU", "start" to "Started", "name" to "Name").forEach { (k, l) -> FilterChip(selected = sort == k, onClick = { sort = k }, label = { Text(l) }) }
                    FilterChip(selected = onlyApps, onClick = { onlyApps = !onlyApps }, label = { Text("Apps only") })
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        items(rows, key = { it.pid }) { p ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.pid in sel, enabled = p.critical == null, onCheckedChange = { if (it) sel.add(p.pid) else sel.remove(p.pid) })
                    Column(Modifier.weight(1f)) {
                        Text("${p.label}${if (p.banned) "  [BANNED]" else ""}", fontWeight = FontWeight.SemiBold)
                        Text("pkg ${p.pkg ?: "-"} · pid ${p.pid} · uid ${p.uid}\nRSS ${p.rssKb / 1024} MB · CPU ${p.cpu?.let { "%.1f%% (lifetime avg)".format(it) } ?: "-"} · started ${Processes.fmtStart(p.startMs)}", style = MaterialTheme.typography.bodySmall)
                        Text("Suspicion: ${p.level} (${p.score})" + (p.critical?.let { " · PROTECTED: $it" } ?: ""), style = MaterialTheme.typography.labelMedium,
                            color = if (p.score >= 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.pkg != null && p.critical == null) androidx.compose.material3.IconButton(onClick = {
                        val now = dev.optimizer.android.core.Rules.isNever(p.pkg)
                        dev.optimizer.android.core.Rules.setNever(ctx, p.pkg, !now)?.let { toast(ctx, it) }; if (!now) sel.remove(p.pid); rv++
                    }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Lock, "Never stop", tint = if (rv >= 0 && dev.optimizer.android.core.Rules.isNever(p.pkg)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                    TextButton(onClick = { expanded = if (expanded == p.pid) null else p.pid }) { Text(if (expanded == p.pid) "Hide" else "Why?") }
                }
                if (expanded == p.pid) Text(if (p.reasons.isEmpty()) "No scoring factors apply." else p.reasons.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Section("Stock snapshot & restore") {
                Text("Records processes, package states, runtime permissions, app-ops (user apps), accessibility services, notification listeners, device admins and network blocks so they can be compared with and restored to a known-good state. Needs root. Stored in app storage only.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = AppState.rooted && busy == null, onClick = {
                        scope.launch {
                            busy = "Creating snapshot..."
                            val r = withContext(Dispatchers.IO) { Snapshots.create(ctx, if (snaps.none { it.stock }) "Stock snapshot" else "Snapshot", snaps.none { it.stock }) { s -> busy = s } }
                            msg = r.fold({ "Created ${it.id}: ${it.summary}" }, { "Failed: ${it.message}" }); busy = null; snapTick++
                        }
                    }) { Text(if (snaps.none { it.stock }) "Create stock snapshot" else "New snapshot") }
                }
                snaps.forEach { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text((if (s.stock) "[STOCK] " else "") + s.label, fontWeight = FontWeight.SemiBold)
                        Text("${s.id} · ${DateFormat.getDateTimeInstance().format(Date(s.ts))}\n${s.summary}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = AppState.unlocked && busy == null, onClick = { restoreFor = s }) { Text("Restore…") }
                            OutlinedButton(enabled = busy == null, onClick = { Snapshots.delete(s.id); snapTick++ }) { Text("Delete") }
                        }
                    }
                }
                if (!AppState.baseline) Text("Restore requires the baseline backup.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    action?.let { a ->
        val picked = procs.filter { it.pid in sel }
        Confirm("${a.replaceFirstChar { it.uppercase() }} ${picked.size} process(es)?",
            picked.joinToString("\n") { "${it.label} (${it.pkg})" } + "\n\n" + when (a) {
                "stop" -> "Force-stops the apps (root) or kills their background processes (no root)."
                "ban" -> "Root: force-stop and freeze with pm disable-user until Unban (needs the baseline). No root: only added to the watch list. Protected packages are skipped automatically."
                else -> "Re-enables the apps."
            }, a.replaceFirstChar { it.uppercase() },
            onYes = {
                action = null
                scope.launch {
                    busy = "Working..."
                    val r = withContext(Dispatchers.IO) { picked.joinToString("\n") { p -> when (a) { "stop" -> Processes.stop(ctx, p); "ban" -> Processes.ban(ctx, p); else -> Processes.unban(ctx, p) }.message } }
                    msg = r; busy = null; sel.clear(); AppState.bumpLog(); reload++
                }
            }, onNo = { action = null })
    }
    if (stopAll) {
        val cands = remember(procs) { procs.filter { it.pkg != null && it.critical == null }.groupBy { it.pkg!! }.map { (k, v) -> CloseRow(k, v.first().label, v.sumOf { it.rssKb }) }.sortedByDescending { it.ramKb } }
        val csel = remember { androidx.compose.runtime.mutableStateOf(cands.map { it.pkg }.toSet()) }
        AlertDialog(onDismissRequest = { stopAll = false }, title = { Text("Stop apps") },
            text = { Column { Text("All ticked by default; untick or lock (Never stop) anything you want to keep. Protected apps are not listed.", style = MaterialTheme.typography.bodySmall); CloseListBody(cands, csel, 300) } },
            confirmButton = { Button(enabled = csel.value.isNotEmpty(), onClick = {
                val pk = csel.value; stopAll = false
                scope.launch {
                    busy = "Stopping..."
                    msg = withContext(Dispatchers.IO) { procs.filter { it.pkg in pk }.distinctBy { it.pkg }.joinToString("\n") { Processes.stop(ctx, it).message } }
                    busy = null; sel.clear(); AppState.bumpLog(); reload++
                }
            }) { Text("Stop ${csel.value.size}") } },
            dismissButton = { TextButton(onClick = { stopAll = false }) { Text("Cancel") } })
    }
    restoreFor?.let { s ->
        val cats = remember { mutableStateListOf(SnapCat.PACKAGES, SnapCat.ACCESSIBILITY, SnapCat.NOTIFLISTENERS, SnapCat.NETBLOCKS) }
        AlertDialog(onDismissRequest = { restoreFor = null }, title = { Text("Restore ${s.label}") },
            text = { Column { Text("Choose categories to bring back to the snapshot state.")
                SnapCat.values().forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(c in cats, { if (it) cats.add(c) else cats.remove(c) }); Text(c.label, style = MaterialTheme.typography.bodySmall) } } } },
            confirmButton = { Button(enabled = cats.isNotEmpty(), onClick = {
                val id = s.id; val cs = cats.toSet(); restoreFor = null
                scope.launch { busy = "Restoring..."; msg = withContext(Dispatchers.IO) { Snapshots.restore(ctx, id, cs) { t -> busy = t } }; busy = null; AppState.bumpLog(); reload++ }
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreFor = null }) { Text("Cancel") } })
    }
}
