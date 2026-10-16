package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Root
import dev.optimizer.android.features.Launchers
import dev.optimizer.android.features.Optimizer
import dev.optimizer.android.features.Play
import dev.optimizer.android.features.StaleApp
import dev.optimizer.android.features.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private sealed class Pending { data class Cache(val pkg: String) : Pending(); data class Data(val pkg: String) : Pending(); data class Ota(val o: dev.optimizer.android.features.OtaPkg) : Pending(); object AutoOta : Pending() }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UpdatesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stale by remember { mutableStateOf<List<StaleApp>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { stale = withContext(Dispatchers.IO) { Updates.staleApps(ctx) } }
    val sys = remember { Updates.sysInfo() }
    val vendorUpd = remember { Updates.vendorUpdaters(ctx) }
    val ota = remember(tick) { Updates.installedOta(ctx) }
    val cacheT = remember { Updates.updaterCacheTargets(ctx) }

    fun run(block: () -> String) { scope.launch { busy = true; msg = withContext(Dispatchers.IO) { block() }; busy = false; tick++; AppState.bumpLog() } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Busy(if (busy) "Working..." else null)
        msg?.let { Section("Result") { Text(it, style = MaterialTheme.typography.bodySmall) } }

        Section("System update helper") {
            KV("Android", sys.android); KV("Security patch", sys.patch + (sys.patchAgeDays?.let { " ($it days old)" } ?: "")); KV("Build", sys.build)
            if ((sys.patchAgeDays ?: 0) > 365) Warn("The security patch is over a year old. Only the vendor / ROM can fix that.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startSafe(ctx, Updates.systemUpdateIntent()) }) { Text("Open system update") }
                vendorUpd.forEach { (l, i) -> OutlinedButton(onClick = { startSafe(ctx, i) }) { Text(l) } }
            }
            Text("Android does not tell other apps whether an OTA is available, so this helper only opens the update screens.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Outdated apps (heuristic)") {
            Text("Android has no API that reveals pending updates to other apps. These user apps look outdated: not updated for over ${Updates.STALE_DAYS} days or targeting an API far below the device's. 'Open in Play' lets Google Play tell you if an update exists.", style = MaterialTheme.typography.bodySmall)
            val s = stale
            if (s == null) Busy("Scanning...") else {
                Text("${s.size} apps flagged", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { Updates.openPlay(ctx, "com.android.vending") }) { Text("Open Play Store") }
                s.take(150).forEach { a ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(a.label, fontWeight = FontWeight.SemiBold)
                        Text("${a.pkg} · v${a.version}\nlast updated ${DateFormat.getDateInstance().format(Date(a.lastUpdate))} · installer: ${a.installer ?: "unknown"}\n${a.reasons.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { Updates.openPlay(ctx, a.pkg) }) { Text("Open in Play") }
                            OutlinedButton(onClick = { startSafe(ctx, Updates.appInfoIntent(a.pkg)) }) { Text("App info") }
                        }
                    }
                }
            }
        }

        Section("Disable automatic OTA (root, reversible)") {
            if (!AppState.unlocked) Warn("Needs root and the baseline backup.")
            val on = remember(tick, AppState.logVersion) { dev.optimizer.android.core.ChangeLog.isActive("ota:auto") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(on, enabled = AppState.unlocked && !busy, onCheckedChange = { if (it) pending = Pending.AutoOta else run { Updates.setAutoOta(false).message } })
                Text("  ota_disable_automatic_update = 1", style = MaterialTheme.typography.bodySmall)
            }
            Text("Safe, AOSP setting; whether your ROM's updater honours it varies. Manual updates keep working.", style = MaterialTheme.typography.bodySmall)
            if (ota.isEmpty()) Text("No known vendor updater package that could be disabled was found. (Xiaomi com.android.updater and ColorOS com.coloros.sau are never offered: disabling them is reported to bootloop some devices.)", style = MaterialTheme.typography.bodySmall)
            ota.forEach { o ->
                val off = remember(tick, AppState.logVersion) { dev.optimizer.android.core.ChangeLog.isActive("ota:pkg:${o.pkg}") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${o.label} (${o.pkg})", fontWeight = FontWeight.SemiBold); Text("HIGH risk. ${o.warning}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Switch(off, enabled = AppState.unlocked && !busy, onCheckedChange = { if (it) pending = Pending.Ota(o) else run { Updates.enableUpdater(o).message } })
                }
            }
        }

        Section("Chinese-ROM update fixes & Play repair") {
            Text("Updater cache clear and Play Store / Play services cache repair delete cache folders only (accounts and data stay). 'Clear data' wipes app data and is irreversible.", style = MaterialTheme.typography.bodySmall)
            if (!Root.isRooted) Info("Without root, use the app-info button and tap Storage → Clear cache.")
            val targets = (cacheT.map { it to it } + Updates.repairTargets.filter { Launchers.Installed.has(ctx, it.first) }.map { it.first to it.second }).distinct()
            targets.forEach { (p, n) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("$n ($p)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { if (Root.isRooted) pending = Pending.Cache(p) else startSafe(ctx, Updates.appInfoIntent(p)) }) { Text(if (Root.isRooted) "Clear cache" else "Open app info") }
                        if (Root.isRooted && p in Updates.repairTargets.map { it.first }) OutlinedButton(enabled = !busy && AppState.baseline, onClick = { pending = Pending.Data(p) }) { Text("Clear data…") }
                    }
                }
            }
        }

        Section("WebView") {
            KV("Current WebView provider", remember { Updates.webViewInfo() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Updates.openPlay(ctx, "com.google.android.webview") }) { Text("Android System WebView in Play") }
                OutlinedButton(onClick = { startSafe(ctx, Updates.webViewSettingsIntent()) }) { Text("WebView implementation (Developer options)") }
            }
        }

        Section("Uncertified devices") {
            Text("If Play shows 'Device is not certified' (common on custom ROMs and some Chinese-market devices), updates may fail or the Store may not list apps. Register the Google Services Framework Android ID at google.com/android/uncertified. The Google Play screen can read the ID (root).", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(Play.UNCERTIFIED_URL)) }) { Text("Open registration page") }
        }
    }

    when (val p = pending) {
        is Pending.AutoOta -> Confirm("Disable automatic OTA?", "Sets Settings.Global ota_disable_automatic_update=1. Reversible from this switch or the change log.", "Apply", onYes = { pending = null; run { Updates.setAutoOta(true).message } }, onNo = { pending = null })
        is Pending.Ota -> Confirm("Disable ${p.o.pkg}?", "HIGH RISK. ${p.o.warning}\n\nThe package is disabled with pm disable-user (not uninstalled) and can be re-enabled here or from the change log. If the device behaves oddly after a reboot, re-enable it via adb: pm enable ${p.o.pkg}.", "Disable", onYes = { pending = null; run { Updates.disableUpdater(ctx, p.o).message } }, onNo = { pending = null })
        is Pending.Cache -> Confirm("Clear cache of ${p.pkg}?", "Force-stops the app and deletes its cache folders (internal, code_cache, external cache). Accounts and settings are kept. Not undoable, rebuilt automatically.", "Clear cache", onYes = { pending = null; run { Updates.clearCache(p.pkg).message } }, onNo = { pending = null })
        is Pending.Data -> Confirm("Clear DATA of ${p.pkg}?", "pm clear wipes ALL app data (settings, local state). For Play services/Store this resets registrations and can cause sync problems or a re-login until it re-registers. Irreversible. Continue only if cache clearing did not help.", "Clear data", onYes = { pending = null; run { Updates.clearData(p.pkg).message } }, onNo = { pending = null })
        null -> {}
    }
}
