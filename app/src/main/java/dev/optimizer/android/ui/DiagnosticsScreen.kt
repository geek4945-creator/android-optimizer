package dev.optimizer.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.Diagnostics
import dev.optimizer.android.features.FixOpt
import dev.optimizer.android.features.Issue
import dev.optimizer.android.features.Sev
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var issues by remember { mutableStateOf<List<Issue>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() } // "issueId|optId"
    var preview by remember { mutableStateOf<Pair<List<Pair<Issue, FixOpt>>, Diagnostics.Preview>?>(null) }
    var hours by remember { mutableStateOf(Store.diagPeriodicHours) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun scan() { scope.launch { busy = "Scanning (real device data)..."; selected.clear(); issues = withContext(Dispatchers.IO) { Diagnostics.scan(ctx, Root.isRooted) }; busy = null } }
    fun pick(list: List<Pair<Issue, FixOpt>>) {
        scope.launch { busy = "Building command preview..."; val p = withContext(Dispatchers.IO) { Diagnostics.preview(list) }; busy = null; preview = list to p }
    }
    fun chosen(): List<Pair<Issue, FixOpt>> = issues.orEmpty().flatMap { i -> i.options.filter { "${i.id}|${it.id}" in selected }.map { i to it } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Diagnostics & conflicts") {
            Text("Scans real device data for inefficiencies and conflicts (storage, RAM, animations, patch age, SELinux, accessibility/keyboard/launcher conflicts, task killers, vendor killers, disabled Play components, ...). Each issue shows evidence and selectable fixes; root fixes need the baseline and are logged and undoable; without root use the settings shortcuts.", style = MaterialTheme.typography.bodySmall)
            Busy(busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = { scan() }) { Text("Scan now") }
                OutlinedButton(enabled = busy == null && issues != null, onClick = { pick(Diagnostics.recommended(issues.orEmpty())) }) { Text("Fix all recommended") }
                OutlinedButton(enabled = busy == null && selected.isNotEmpty(), onClick = { pick(chosen()) }) { Text("Fix selected (${selected.size})") }
            }
        }
        Section("Periodic scan (WorkManager)") {
            Text("Runs in the background without root (root-only checks are skipped) and posts a notification when High/Medium issues change. Android may delay work to save battery (minimum interval 15 min).", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 6 to "6 h", 12 to "12 h", 24 to "24 h").forEach { (h, l) ->
                    FilterChip(selected = hours == h, onClick = {
                        hours = h; Diagnostics.schedule(ctx, h)
                        if (h > 0 && Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }, label = { Text(l) })
                }
            }
        }
        msg?.let { Section("Result") { Text(it, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { msg = null }) { Text("Dismiss") } } }
        val list = issues
        if (list != null) {
            Text("${list.size} finding(s)", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
            if (list.isEmpty()) Info("No issues found by the current checks. This does not prove the device is optimal.")
            list.forEach { i ->
                Section("${i.sev.label.uppercase()} · ${i.title}") {
                    Text(i.explanation, style = MaterialTheme.typography.bodyMedium)
                    Text("Evidence:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    i.evidence.take(12).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (i.options.isEmpty()) Text("No automatic fix is offered.", style = MaterialTheme.typography.bodySmall)
                    i.options.forEach { o ->
                        val key = "${i.id}|${o.id}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (o.rootPlan != null || o.local != null) Checkbox(key in selected, { if (it) selected.add(key) else selected.remove(key) })
                            Column(Modifier.weight(1f)) {
                                Text(o.label + if (o.recommended) "  (recommended)" else "", style = MaterialTheme.typography.bodyMedium)
                                if (o.note.isNotEmpty()) Text(o.note, style = MaterialTheme.typography.bodySmall)
                                if (o.rootPlan != null && !AppState.unlocked) Text("needs root + baseline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            o.intent?.let { it2 -> OutlinedButton(onClick = { startSafe(ctx, it2) }) { Text("Open") } }
                        }
                    }
                }
            }
        }
    }

    preview?.let { (list, pv) ->
        AlertDialog(
            onDismissRequest = { preview = null }, title = { Text("Preview: exact commands") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (pv.lines.isEmpty()) Text("No shell commands (nothing selected that runs commands).")
                    Text(pv.lines.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (pv.intentOnly.isNotEmpty()) Text("\nSettings-only options (open them from the list): " + pv.intentOnly.joinToString { it.label }, style = MaterialTheme.typography.bodySmall)
                    if (!AppState.unlocked) Text("\nRoot + baseline are required to execute these; without them nothing will run.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(enabled = pv.lines.isNotEmpty(), onClick = {
                    preview = null
                    scope.launch { busy = "Applying..."; val r = withContext(Dispatchers.IO) { Diagnostics.apply(list) }; msg = r.joinToString("\n"); AppState.bumpLog(); busy = null; scan() }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } }
        )
    }
}
