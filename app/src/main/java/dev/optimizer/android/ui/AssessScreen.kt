package dev.optimizer.android.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.optimizer.android.features.AppAssessment
import dev.optimizer.android.features.Assessment
import dev.optimizer.android.features.Section as Sec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun AssessScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hw by remember { mutableStateOf<Sec?>(null) }
    var sw by remember { mutableStateOf<Sec?>(null) }
    var apps by remember { mutableStateOf<List<AppAssessment>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("flagged") } // flagged | user | system | all
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload, AppState.rootState) {
        hw = null; sw = null; apps = null
        withContext(Dispatchers.IO) {
            hw = Assessment.hardware(ctx); sw = Assessment.software(); apps = Assessment.apps(ctx)
        }
    }

    val shown = remember(apps, filter) {
        val a = apps ?: emptyList()
        when (filter) { "flagged" -> a.filter { it.risky }; "user" -> a.filter { !it.system }; "system" -> a.filter { it.system }; else -> a }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Section("Device assessment") {
                Text("Everything below is read from this device. Fields Android hides from normal apps say so.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
                    Button(enabled = hw != null && sw != null && apps != null, onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                val dir = File(ctx.cacheDir, "reports").also { it.mkdirs() }
                                File(dir, "device-report.txt").also { it.writeText(Assessment.report(ctx, hw!!, sw!!, apps!!)) }
                            }
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "Android Optimizer device report")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startSafe(ctx, Intent.createChooser(i, "Export report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Export / share report") }
                }
                if (hw == null) Busy("Reading hardware, software and apps...")
            }
        }
        hw?.let { s -> item { Section(s.title) { s.rows.forEach { (k, v) -> KV(k, v) } } } }
        sw?.let { s -> item { Section(s.title) { s.rows.forEach { (k, v) -> KV(k, v) } } } }
        apps?.let { all ->
            item {
                Section("Application assessment") {
                    val user = all.count { !it.system }
                    KV("User apps", user.toString()); KV("System apps", (all.size - user).toString()); KV("Flagged", all.count { it.risky }.toString())
                    Text("Flag rules: >= ${Assessment.RISKY_PERM_THRESHOLD} granted dangerous permissions, or targetSdk < ${Assessment.OLD_TARGET_SDK}. Size is the APK size on disk.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("flagged" to "Flagged", "user" to "User", "system" to "System", "all" to "All").forEach { (k, l) ->
                            FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l) })
                        }
                    }
                    Text("${shown.size} apps shown", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(shown, key = { it.pkg }) { a ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { expanded = if (expanded == a.pkg) null else a.pkg }) {
                    Text(a.label + if (a.system) "  (system)" else "", fontWeight = FontWeight.SemiBold)
                    Text("${a.pkg}\nv${a.version} · target ${a.targetSdk} · ${Assessment.fmtBytes(a.sizeBytes)} · installed ${DateFormat.getDateInstance().format(Date(a.installTime))}",
                        style = MaterialTheme.typography.bodySmall)
                    if (a.flags.isNotEmpty()) Text("⚠ " + a.flags.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (expanded == a.pkg) Text("Dangerous permissions granted (${a.dangerous.size}): ${a.dangerous.joinToString().ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
