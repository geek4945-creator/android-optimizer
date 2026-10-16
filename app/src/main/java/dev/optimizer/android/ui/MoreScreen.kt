package dev.optimizer.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Entry(val key: String, val title: String, val desc: String)

private val entries = listOf(
    Entry("access", "Access & capabilities", "Which access level is active (root / Shizuku / ADB grants / plain), capability matrix, adb commands, wireless-debugging guide, root-lost help"),
    Entry("quickfix", "Quick Fix", "Measure, tick suggested fixes, apply (first-run helper)"),
    Entry("freeze", "Freeze Doctor", "Find out why the device freezes: ranked causes with evidence, selectable fixes, health snapshot, TankPro/MediaTek/Rockchip guidance"),
    Entry("tools", "Quick tools", "Safe Boost (one undo), widgets & live notification, scheduled maintenance, Reset to stock"),
    Entry("cleaner", "Storage cleaner", "Thumbnails, empty folders, large files, duplicates, APKs - preview, checkboxes, restorable trash"),
    Entry("startup", "Startup apps", "Boot receivers: see and (root) disable autostarting apps, undoable"),
    Entry("battery", "Battery & usage", "Battery health, temperature log chart, app-usage ranking"),
    Entry("crashlog", "Crash & ANR log", "Crash buffer, ANRs, system_server/SystemUI problems"),
    Entry("ram", "RAM Optimizer", "Preview-first RAM cleanup, kernel tuning, auto-optimize, history"),
    Entry("rules", "Rules", "Never stop / Keep closed per app, keep-closed guard, export & import"),
    Entry("processes", "Processes", "Running processes, suspicion score, Stop / Ban / Unban, stock snapshot & restore"),
    Entry("network", "Network", "Per-app connections, beaconing detection, tracker list, network block"),
    Entry("diagnostics", "Diagnostics", "Scan for inefficiencies and conflicts; preview and apply fixes"),
    Entry("updates", "Updates", "Outdated apps, system update helper, OTA control, Play/updater repair"),
    Entry("backups", "Backups", "Create, restore and delete backups; baseline"),
    Entry("launchers", "Launchers", "Open-source launchers, default home app"),
    Entry("play", "Google Play", "Detect, verify and install Play Store / services"),
    Entry("help", "Help, FAQ & About", "FAQ, version/build info, link to the GitHub releases page"),
    Entry("settings", "Settings", "Theme: system / light / dark, AMOLED black"),
)

@Composable
fun MoreScreen() {
    var page by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val deep = AppState.morePage
    androidx.compose.runtime.LaunchedEffect(deep) { if (deep != null) { page = deep; AppState.morePage = null } }
    val p = page
    if (p == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            entries.forEach { e ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { page = e.key }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(e.desc, style = MaterialTheme.typography.bodySmall)
                        dev.optimizer.android.features.Features.limits(e.key).forEach { Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = { page = null }) { Text("← More  ·  ${entries.first { it.key == p }.title}") }
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                when (p) {
                    "access" -> AccessScreen()
                    "quickfix" -> QuickFixScreen()
                    "freeze" -> DiagnosticsScreen(freeze = true)
                    "tools" -> ToolsScreen()
                    "cleaner" -> CleanerScreen()
                    "startup" -> StartupScreen()
                    "battery" -> BatteryScreen()
                    "crashlog" -> CrashLogScreen()
                    "help" -> HelpScreen()
                    "ram" -> RamScreen()
                    "rules" -> RulesScreen()
                    "processes" -> ProcessesScreen()
                    "network" -> NetworkScreen()
                    "diagnostics" -> DiagnosticsScreen()
                    "updates" -> UpdatesScreen()
                    "backups" -> BackupScreen()
                    "launchers" -> LaunchersScreen()
                    "play" -> PlayScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}
