package dev.optimizer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.RootState
import dev.optimizer.android.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RootScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var askBaseline by remember { mutableStateOf(false) }
    val suVisible = remember { Root.suBinaryVisible() }

    // Re-verify silently if root was granted before (no prompt expected as Magisk remembers the grant).
    LaunchedEffect(Unit) {
        if (Root.state == RootState.UNKNOWN && Store.rootGrantedBefore) {
            busy = "Re-checking root..."
            withContext(Dispatchers.IO) { Root.request() }
            AppState.refresh(); busy = null
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        val st = AppState.rootState
        Section("Root status") {
            val (txt, color) = when (st) {
                RootState.ROOTED -> "ROOTED - su access granted" to MaterialTheme.colorScheme.primary
                RootState.DENIED -> "NOT GRANTED - su exists but access was denied or timed out" to MaterialTheme.colorScheme.error
                RootState.NO_SU -> "NOT ROOTED - no su binary found" to MaterialTheme.colorScheme.error
                RootState.UNKNOWN -> "Not checked yet" to MaterialTheme.colorScheme.onSurface
            }
            Text(txt, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            KV("su binary visible", if (suVisible) "yes" else "no (may still be hidden by Magisk/KernelSU)")
            if (Root.lastDetail.isNotBlank()) KV("`su -c id` output", Root.lastDetail)
            Busy(busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = {
                    scope.launch {
                        busy = "Waiting for the su prompt (Magisk / KernelSU)..."
                        val r = withContext(Dispatchers.IO) { Root.request() }
                        if (r == RootState.ROOTED) Store.rootGrantedBefore = true
                        AppState.refresh(); busy = null
                        if (r == RootState.ROOTED && !Backups.hasBaseline()) askBaseline = true
                    }
                }) { Text(if (st == RootState.ROOTED) "Re-check root" else "Request root access") }
            }
        }
        Section("What this app can and cannot do") {
            Text(
                "This app cannot root your device and contains no exploits. Root must already be installed by you using a root manager such as Magisk or KernelSU " +
                    "(this normally requires an unlocked bootloader and a patched boot image for your exact model and firmware). " +
                    "Once su exists, the button above only asks the root manager to grant this app permission.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (st != RootState.ROOTED) Text(
                "Without root: you can use Device Assessment, the Monitor (limited by Android restrictions), 'Close' (background kill) and the " +
                    "Settings shortcut for Force stop. The Optimize, Backup/Restore, freezing apps and privileged Play install features need root.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Section("Safety baseline") {
            Text(if (AppState.baseline) "A baseline backup exists. Optimize features are unlocked while rooted." else
                "No baseline backup yet. Before any change is possible you must create one (settings, package states, permissions, props). The Optimize tab stays locked until then.")
            Button(enabled = AppState.rooted && busy == null && !AppState.baseline, onClick = { askBaseline = true }) { Text("Create baseline backup") }
        }
    }
    if (askBaseline) BaselineDialog(onClose = { askBaseline = false; AppState.refresh() })
}

/** Prompt shown the first time root is granted: creates the 'baseline' backup. */
@Composable
fun BaselineDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Create baseline backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Root was granted. Before this app can change anything, it needs a baseline of your current state: Settings (system/secure/global), animation scales, " +
                    "package enabled/disabled/suspended states, runtime permissions, build props and selected /data/system files. " +
                    "It is stored in app storage and exported to Documents/AndroidOptimizer/. This can take a minute or two.")
                if (running) Busy(step)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (result == null) Button(enabled = !running, onClick = {
                running = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Backups.create(ctx, "Baseline", true) { s -> step = s } }
                    running = false
                    result = r.fold({ "Baseline created: ${it.id}${if (it.exported) " (exported to Documents/AndroidOptimizer)" else " (export to Documents failed; app-storage copy is OK)"}" }, { "Failed: ${it.message}" })
                    AppState.refresh(); AppState.backupsVersion++
                }
            }) { Text("Create baseline") } else Button(onClick = onClose) { Text("Done") }
        },
        dismissButton = { if (result == null) OutlinedButton(enabled = !running, onClick = onClose) { Text("Later") } }
    )
}
