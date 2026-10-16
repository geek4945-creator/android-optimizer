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
