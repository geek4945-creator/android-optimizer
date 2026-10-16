package dev.optimizer.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.DeviceProfile
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Short first-run wizard. Every permission is optional, asked only on its own step, with the reason. Nothing is requested before the user
 * presses the button on that step. Steps: 1 device scan + adaptations, 2 Usage Access, 3 notifications, 4 battery list, 5 root, 6 done.
 */
@Composable
fun SetupWizard(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var rootBusy by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val perms = remember(tick, AppState.rootState) { DeviceProfile.livePermissions(ctx) }
    val total = 6

    fun finish() { Store.setupDone = DeviceProfile.WIZARD_VERSION; AppState.showWizard = false; AppState.rescan(ctx); if (!Store.getBool("quickfix_done")) { Store.putBool("quickfix_done", true); AppState.openPage("quickfix") }; onClose() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Setup  ·  step ${step + 1} of $total", style = MaterialTheme.typography.labelLarge)
            when (step) {
                0 -> {
                    Text("Welcome - scanning your device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("The app reads your Android version, CPU ABI, RAM, storage, screen, vendor ROM, Google services, sensors and which permissions are already granted, and adapts itself. Nothing leaves the device.")
                    val p = AppState.profile
                    if (p == null) Busy("Scanning...") else {
                        Section("Result") {
                            KV("Device", "${p.manufacturer} ${p.model}"); KV("Android", "${p.release} (API ${p.sdk}), ${p.abis.firstOrNull()}")
                            KV("RAM / class", "${p.ramMb} MB / ${p.ramClass}"); KV("Screen", "${p.sizeClass}${if (p.isTv) ", TV" else ""}")
                            KV("ROM", p.rom); KV("Google Play", if (p.hasPlayStore && p.hasGms) "present" else "missing or incomplete")
                        }
                        Section("What was adapted") {
                            val a = mutableListOf<String>()
                            if (AppState.lowEnd) a += "Low-end mode ON: slower Monitor refresh, shorter graphs, sampler every ${Features.defaultNetIntervalSec} s, 1-day history, heavy dexopt/compile jobs disabled."
                            else a += "Normal mode: Monitor refresh 2 s, sampler every ${Features.defaultNetIntervalSec} s."
                            Features.optimizeNotes().forEach { a += it }
                            if (p.sdk < 26) a += "Android < 8: legacy shell command variants (ps/top/appops) are used."
                            if (!p.hasTouch || p.isTv) a += "No touchscreen / TV: the layout uses a navigation rail and works with D-pad focus."
                            if (p.hasHms && !p.hasGms) a += "Huawei HMS device: Google Play screen warns that GMS usually can't run."
                            a.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Row { Button(enabled = AppState.profile != null, onClick = { Features.applyDefaultsOnce(); step++ }) { Text("Continue") } }
                }
                1 -> {
                    Text("Usage Access (optional)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Reason: lets the Monitor show which apps were used recently and lets the process view avoid flagging an app that is in active use. Without it those columns are empty. Android asks you to switch it on in a Settings page; this app cannot grant it itself.")
                    StatusLine("Usage Access", perms["Usage Access"] == true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { startSafe(ctx, dev.optimizer.android.features.Monitor.usageAccessIntent()) }) { Text("Open Usage Access settings") }
                        OutlinedButton(onClick = { step++ }) { Text(if (perms["Usage Access"] == true) "Next" else "Skip") }
                    }
                }
                2 -> {
                    Text("Notifications (optional)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Reason: used only for the foreground notification of the optional sampler / keep-closed helper and for the optional diagnostics scan summary.")
                    if (Build.VERSION.SDK_INT >= 33) {
                        StatusLine("Notifications", perms["Notifications"] == true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = perms["Notifications"] != true, onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                            OutlinedButton(onClick = { step++ }) { Text(if (perms["Notifications"] == true) "Next" else "Skip") }
                        }
                    } else {
                        Text("Android ${Features.androidName(Build.VERSION.SDK_INT)} does not need a notification permission (it exists from Android 13). Nothing to do.")
                        Button(onClick = { step++ }) { Text("Next") }
                    }
                }
                3 -> {
                    Text("Battery optimisation (optional)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (!Compat.hasDoze) {
                        Text("Doze does not exist before Android 6. Nothing to do.")
                        Button(onClick = { step++ }) { Text("Next") }
                    } else {
                        Text("Reason: only needed if you use the background sampler or periodic scans, which Doze can pause while the screen is off. This opens the system list where you can pick 'Don't optimize' for this app; some vendor ROMs (Xiaomi, Huawei, OPPO, vivo) also have their own autostart manager.")
                        StatusLine("Exempt from battery optimisation", perms["Battery optimisation exempt"] == true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startSafe(ctx, Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")) }) { Text("Open battery optimisation list") }
                            OutlinedButton(onClick = { step++ }) { Text("Next / skip") }
                        }
                    }
                }
                4 -> if (!dev.optimizer.android.core.Edition.ROOT) {
                    Text("Extra access (optional)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("The Standard edition never uses root. Without anything extra the app monitors and closes background apps. Installing Shizuku, or granting a few permissions once over adb, unlocks force-stop, freezing, settings writes and diagnostics. The Access tab has the exact steps and a feature-by-feature table.")
                    StatusLine("Privileged channel (Shizuku/root)", Root.isRooted)
                    StatusLine("WRITE_SECURE_SETTINGS granted", dev.optimizer.android.core.Priv.writeSecure())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { AppState.openPage("access"); step++ }) { Text("Open Access guide") }
                        OutlinedButton(onClick = { step++ }) { Text("Skip") }
                    }
                } else {
                    Text("Root (optional)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Reason: backups, optimizations, freezing apps, the full process list and network features need root. Everything else works without. The button asks your root manager (Magisk / KernelSU / APatch ...) once. This app cannot root the device; the Root tab has an honest step-by-step helper.")
                    Text(Features.rootHint(), style = MaterialTheme.typography.bodySmall)
                    StatusLine("Root granted", Root.isRooted)
                    Busy(if (rootBusy) "Waiting for the root prompt..." else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !rootBusy && !Root.isRooted, onClick = {
                            scope.launch {
                                rootBusy = true
                                val r = withContext(Dispatchers.IO) { Root.request() }
                                if (r == dev.optimizer.android.core.RootState.ROOTED) Store.rootGrantedBefore = true
                                AppState.refresh(); rootBusy = false; tick++
                            }
                        }) { Text("Request root") }
                        OutlinedButton(onClick = { step++ }) { Text(if (Root.isRooted) "Next" else "Skip") }
                    }
                    if (Root.isRooted && !AppState.baseline) Info("After the wizard, the Root tab offers to create the baseline backup that unlocks the Optimize features.")
                }
                else -> {
                    Text("All set", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("After Finish the Quick Fix page opens once: it measures what slows this device down and offers fixes you can tick. You can re-run this wizard and the device scan any time in More > Settings. The profile is re-scanned automatically when the OS build or security patch changes.")
                    Button(onClick = { finish() }) { Text("Finish") }
                }
            }
            if (step > 0 && step < total - 1) TextButton(onClick = { step-- }) { Text("Back") }
            if (step < total - 1) TextButton(onClick = { finish() }) { Text("Skip the rest") }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text("$label: ${if (ok) "granted ✓" else "not granted"}", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
}
