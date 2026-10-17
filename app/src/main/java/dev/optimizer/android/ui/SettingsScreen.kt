package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Store

@Composable
fun SettingsScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Device profile & low-end mode") {
            Text("Low-end mode: slower Monitor refresh and shorter graphs, gentler sampler defaults, heavy dexopt/compile jobs disabled. Automatic = on when the device has 2 GB RAM or less (or Android's low-RAM flag).", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Automatic", 1 to "On", 2 to "Off").forEach { (m, l) ->
                    FilterChip(selected = Store.lowEndMode == m, onClick = { Store.lowEndMode = m; AppState.applyProfile(AppState.profile) }, label = { Text(l) })
                }
            }
            Text("Currently: ${if (AppState.lowEnd) "low-end mode ON" else "normal mode"}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(enabled = !AppState.scanning, onClick = { AppState.rescan(ctx) }) { Text("Re-scan device") }
                androidx.compose.material3.OutlinedButton(onClick = { AppState.showWizard = true }) { Text("Run setup wizard") }
            }
        }
        Section("App updates") {
            val updCtl = dev.optimizer.android.AppUpdates.get(androidx.compose.ui.platform.LocalContext.current)
            dev.optimizer.android.AppUpdateCard(updCtl)
        }
        Section("Theme") {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (m, l) ->
                    FilterChip(selected = AppState.themeMode == m, onClick = { AppState.themeMode = m; Store.themeMode = m }, label = { Text(l) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(AppState.amoled, { AppState.amoled = it; Store.amoled = it })
                Text("  AMOLED black (pure black backgrounds in dark mode)")
            }
            Text("The choice is saved and applied immediately. AMOLED black only has an effect while the dark theme is active.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}
