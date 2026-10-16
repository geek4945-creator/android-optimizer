package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Sh
import dev.optimizer.android.features.AppControl
import dev.optimizer.android.features.Launchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LaunchersScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Launchers.HomeApp?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    val def = remember(tick) { Launchers.defaultHome(ctx) }
    val homes = remember(tick) { Launchers.homeApps(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Current home app") {
            Text(def?.let { p -> homes.firstOrNull { it.pkg == p }?.let { "${it.label} ($p)" } ?: p } ?: "No default home app selected (Android asks every time)", fontWeight = FontWeight.Bold)
            Text("Home apps on this device:", style = MaterialTheme.typography.bodySmall)
            homes.forEach { h ->
                Text("• ${h.label} (${h.pkg})${if (h.system) " · system" else ""}${if (!h.enabled) " · DISABLED" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { startSafe(ctx, Launchers.homeSettingsIntent()) }) { Text("Set default home app") }
        }
        msg?.let { Section("Result") { Text(it, style = MaterialTheme.typography.bodySmall) } }

        Section("Switch off the stock launcher (root)") {
            Text("Only after another launcher is installed and set as default. The stock launcher is disabled with `pm disable-user --user 0`, logged in the change log and reversible. " +
                "Guards: the current default and the launcher that provides Recents can't be disabled, and if no usable home app remains the change is rolled back automatically.", style = MaterialTheme.typography.bodySmall)
            if (!AppState.unlocked) Warn("Needs root and the baseline backup (Root tab).")
            val stocks = homes.filter { it.system }
            if (stocks.isEmpty()) Text("No system launchers found.", style = MaterialTheme.typography.bodySmall)
            stocks.forEach { h ->
                val blocker = remember(tick, AppState.rootState, AppState.baseline) { Launchers.disableBlocker(ctx, h) }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${h.label} (${h.pkg})", fontWeight = FontWeight.SemiBold)
                    if (!h.enabled) {
                        Text("Currently disabled", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = AppState.rooted, onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Sh.root("pm enable ${Sh.q(h.pkg)}") }
                                if (r.ok) ChangeLog.all().filter { it.group == "launcher:${h.pkg}" && !it.undone }.forEach { ChangeLog.markUndone(it.id) }
                                msg = if (r.ok) "Re-enabled ${h.label}" else r.text; tick++; AppState.bumpLog()
                            }
                        }) { Text("Re-enable") }
                    } else {
                        if (blocker != null) Text("Not available: $blocker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Button(enabled = blocker == null, onClick = { confirm = h }) { Text("Disable ${h.label}") }
                    }
                }
            }
        }

        Section("Open-source launchers") {
            Text("Curated list of open-source launchers. Links were checked when this app was written; 'ad-free / tracker-free' reflects the projects' own claims and the F-Droid listings - review each yourself. Niagara Launcher is not listed because it is closed source.", style = MaterialTheme.typography.bodySmall)
        }
        Launchers.list.forEach { e ->
            val inst = remember(tick) { Launchers.installedPkg(ctx, e) }
            Section(e.name) {
                Text(e.description, style = MaterialTheme.typography.bodyMedium)
                KV("License", e.license)
                KV("Installed", if (inst != null) "yes ($inst)${if (inst == def) " · default" else ""}" else "no")
                if (e.note.isNotEmpty()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.fdroidUrl?.let { u -> OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(u)) }) { Text(e.fdroidLabel) } }
                    OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(e.githubUrl)) }) { Text("GitHub") }
                    e.extraUrl?.let { u -> OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(u)) }) { Text(e.extraLabel) } }
                    if (inst != null) Button(onClick = { if (!AppControl.launch(ctx, inst)) toast(ctx, "No launch intent") }) { Text("Launch") }
                    if (inst != null) OutlinedButton(onClick = { startSafe(ctx, Launchers.homeSettingsIntent()) }) { Text("Set default") }
                }
            }
        }
    }

    confirm?.let { h ->
        Confirm("Disable ${h.label}?", "The stock launcher ${h.pkg} will be disabled. Current default home app stays ${def}. If anything looks wrong it is reverted automatically; you can also re-enable it here or from the change log.", "Disable",
            onYes = {
                confirm = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Launchers.disableStock(ctx, h) }
                    msg = r.message; tick++; AppState.bumpLog()
                }
            }, onNo = { confirm = null })
    }
}
