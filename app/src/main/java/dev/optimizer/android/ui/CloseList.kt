package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Rules

data class CloseRow(val pkg: String, val label: String, val ramKb: Long?, val note: String? = null)

/**
 * The selection list used by every close / stop flow (RAM Optimizer preview, Monitor 'Close apps', Processes 'Stop all').
 * Every row has a checkbox (preselected by the caller), 'Select all / none', a live count and RAM estimate, a lock toggle ('Never stop', persisted
 * and honoured by every kill path) and a 'Keep closed' toggle. The two rules exclude each other. Rows with 'Never stop' cannot be ticked.
 */
@Composable
fun CloseListBody(rows: List<CloseRow>, selection: MutableState<Set<String>>, maxHeight: Int = 320) {
    val ctx = LocalContext.current
    var rv by remember { mutableIntStateOf(0) }
    val never = remember(rv, rows) { Rules.neverSet() }
    val keep = remember(rv, rows) { Rules.all().filter { it.keepClosed }.map { it.pkg }.toSet() }
    val eligible = rows.filter { it.pkg !in never }
    val chosen = selection.value.filter { p -> p !in never && rows.any { it.pkg == p } }
    val ram = rows.filter { it.pkg in chosen }.sumOf { it.ramKb ?: 0L }
    val anyUnknown = rows.any { it.pkg in chosen && it.ramKb == null }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${chosen.size} of ${rows.size} selected · ~${ram / 1024} MB${if (anyUnknown) "+ (some unknown)" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { selection.value = eligible.map { it.pkg }.toSet() }) { Text("All") }
        TextButton(onClick = { selection.value = emptySet() }) { Text("None") }
    }
    LazyColumn(Modifier.heightIn(max = maxHeight.dp)) {
        items(rows, key = { it.pkg }) { r ->
            val isNever = r.pkg in never
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.pkg in chosen, enabled = !isNever, onCheckedChange = { on -> selection.value = if (on) selection.value + r.pkg else selection.value - r.pkg })
                Column(Modifier.weight(1f)) {
                    Text(r.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("${r.pkg} · ${r.ramKb?.let { "${it / 1024} MB" } ?: "RAM n/a"}${r.note?.let { " · $it" } ?: ""}${if (isNever) " · NEVER STOP rule" else if (r.pkg in keep) " · keep-closed rule" else ""}", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = {
                    val err = Rules.setNever(ctx, r.pkg, !isNever)
                    if (err != null) toast(ctx, err) else { if (!isNever) selection.value = selection.value - r.pkg; rv++ }
                }) { Icon(Icons.Filled.Lock, "Never stop this app", tint = if (isNever) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                TextButton(enabled = !isNever, onClick = {
                    val on = r.pkg !in keep
                    val err = Rules.setKeepClosed(ctx, r.pkg, on)
                    if (err != null) toast(ctx, err) else { if (on) toast(ctx, "Keep-closed rule added (mode: kill on start). Manage it in More > Rules."); rv++ }
                }) { Text(if (r.pkg in keep) "Unkeep" else "Keep closed", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
