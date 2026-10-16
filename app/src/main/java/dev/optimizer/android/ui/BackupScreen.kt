package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.BackupInfo
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.RestoreCat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var dataPkgs by remember { mutableStateOf("") }
    var restoreTarget by remember { mutableStateOf<BackupInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<BackupInfo?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var askBaseline by remember { mutableStateOf(false) }
    val list = remember(AppState.backupsVersion, busy) { Backups.list() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (!AppState.rooted) Warn("No root/Shizuku: backups contain only what the app can read (Settings values, package list/states, animation scales). Restore writes only what the app is allowed to (needs WRITE_SECURE_SETTINGS / Shizuku).")
        SafCard(onChanged = { AppState.backupsVersion++ }, onMsg = { msg = it })
        Section("Create backup") {
            Text("Stores Settings (system/secure/global), animation scales, package states, runtime permissions, getprop/build.prop, user-app list and selected /data/system files in app storage, and an exported copy in Documents/AndroidOptimizer/<id>/ with a manifest.json.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(dataPkgs, { dataPkgs = it }, label = { Text("Optional: app-data tar, package names comma-separated") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Large. Root `tar` of /data/data/<pkg> to Documents only; not restored automatically.") })
            Busy(busy)
            Button(enabled = busy == null, onClick = {
                if (!AppState.baseline) { askBaseline = true } else scope.launch {
                    val pk = dataPkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val r = withContext(Dispatchers.IO) { Backups.create(ctx, label.ifBlank { "Manual backup" }, false, pk) { s -> busy = s } }
                    busy = null; AppState.backupsVersion++
                    msg = r.fold({ "Created ${it.id}" + (if (it.notes.isNotBlank()) "\n${it.notes}" else "") }, { "Failed: ${it.message}" })
                }
            }) { Text(if (AppState.baseline) "Create new backup" else "Create baseline first") }
        }
        msg?.let { Section("Result") { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall); TextButton(onClick = { msg = null }) { Text("Dismiss") } } }
        Section("Backups (${list.size})") {
            if (list.isEmpty()) Text("No backups yet.")
            list.forEach { b ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text((if (b.baseline) "[BASELINE] " else "") + b.label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    Text("${b.id}\n${DateFormat.getDateTimeInstance().format(Date(b.ts))}\nCategories: ${b.categories.joinToString()}\nExported to Documents: ${if (b.exported) "yes" else "no"}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = dev.optimizer.android.core.Priv.canChange() && busy == null, onClick = { restoreTarget = b }) { Text("Restore") }
                        OutlinedButton(enabled = !b.baseline && busy == null, onClick = { deleteTarget = b }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (askBaseline) BaselineDialog(onClose = { askBaseline = false; AppState.refresh() })
    restoreTarget?.let { b ->
        val sel = remember { mutableStateListOf(RestoreCat.SETTINGS, RestoreCat.PACKAGES) }
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore ${b.label}") },
            text = {
                Column {
                    Text("Select categories. A safety backup of the current state is created first. Secure/global keys that could lock you out (provisioning, ADB, input methods, accessibility, Wi-Fi, ...) are never written back.")
                    RestoreCat.values().forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c in sel, { if (it) sel.add(c) else sel.remove(c) }); Text(c.label)
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = sel.isNotEmpty(), onClick = {
                    val cats = sel.toSet(); val id = b.id; restoreTarget = null
                    scope.launch {
                        busy = "Creating safety backup..."
                        val log = withContext(Dispatchers.IO) {
                            Backups.create(ctx, "Auto before restore of $id", false) { s -> busy = "Safety backup: $s" }
                            Backups.restore(ctx, id, cats) { s -> busy = s }
                        }
                        busy = null; msg = log; AppState.bumpLog(); AppState.backupsVersion++
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { b ->
        Confirm("Delete backup?", "Deletes ${b.id} from app storage and the exported Documents copy.", "Delete",
            onYes = { val e = Backups.delete(b.id); msg = e ?: "Deleted ${b.id}"; deleteTarget = null; AppState.backupsVersion++ }, onNo = { deleteTarget = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafCard(onChanged: () -> Unit, onMsg: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { dev.optimizer.android.core.SafFolder.set(ctx, u); tick++; onChanged() }
    }
    Section("Backup folder (Storage Access Framework)") {
        Text("Choose any folder (internal storage, SD card, USB drive, cloud provider) to receive a copy of every new backup in <folder>/AndroidOptimizer/<id>/. No storage permission is needed and it survives uninstalling the app.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        val set = remember(tick) { dev.optimizer.android.core.SafFolder.uri() != null }
        KV("Folder", remember(tick) { dev.optimizer.android.core.SafFolder.label() })
        if (set && !remember(tick) { dev.optimizer.android.core.SafFolder.usable(ctx) }) Warn("The permission to this folder was lost. Choose it again.")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runCatching { pick.launch(null) }.onFailure { onMsg("No file picker available on this device") } }) { Text(if (set) "Change folder" else "Choose folder") }
            OutlinedButton(enabled = set, onClick = { scope.launch {
                val r = withContext(Dispatchers.IO) { Backups.list().map { b -> b.id to dev.optimizer.android.core.SafFolder.copyBackup(ctx, java.io.File(dev.optimizer.android.core.Store.backupsDir(), b.id), b.id) } }
                onMsg(if (r.all { it.second == null }) "Copied ${r.size} backup(s) to the folder" else "Copy problems: " + r.filter { it.second != null }.joinToString { "${it.first}: ${it.second}" })
            } }) { Text("Copy all backups now") }
            OutlinedButton(enabled = set, onClick = { scope.launch {
                val (n, e) = withContext(Dispatchers.IO) { dev.optimizer.android.core.SafFolder.importMissing(ctx) }
                AppState.backupsVersion++; onMsg(e ?: "Imported $n backup(s) from the folder")
            } }) { Text("Import from folder") }
            OutlinedButton(enabled = set, onClick = { dev.optimizer.android.core.SafFolder.clear(ctx); tick++; onChanged() }) { Text("Forget folder") }
        }
    }
}
