package dev.optimizer.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Root
import dev.optimizer.android.features.CleanItem
import dev.optimizer.android.features.CleanScan
import dev.optimizer.android.features.Cleaner
import dev.optimizer.android.features.Optimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun mbs(b: Long) = if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "${b / 1048576} MB"

@Composable
fun CleanerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var scan by remember { mutableStateOf<CleanScan?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val sel = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf(false) }
    var emptyDlg by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) { val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }; owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) } }
    val access = remember(tick) { Cleaner.hasAccess(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val trash = remember(tick) { Cleaner.trashList() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Storage cleaner") {
            Text("Finds thumbnail caches, empty folders, large files, duplicate files and APK files on shared storage. Nothing is deleted automatically: thumbnails and empty folders are preselected, your own files (large files, duplicates, APKs) never are. Ticked items are moved to AndroidOptimizer/Trash first and can be restored; only 'Empty trash' deletes permanently. Other apps' private data (Android/) is not touched.", style = MaterialTheme.typography.bodySmall)
            if (!access) {
                Warn(if (Build.VERSION.SDK_INT >= 30) "All-files access is required on Android 11+." else "Storage permission is required.")
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) startSafe(ctx, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else perm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }) { Text("Grant storage access") }
            }
            Busy(busy)
            Button(enabled = access && busy == null, onClick = { scope.launch { busy = "Scanning..."; sel.clear(); val r = withContext(Dispatchers.IO) { Cleaner.scan(ctx) { s -> busy = s } }; scan = r; r.items.filter { it.preselect }.forEach { sel.add(it.path) }; busy = null } }) { Text(if (scan == null) "Scan" else "Scan again") }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        Section("App caches") {
            Text("Android does not let a normal app clear other apps' caches. With root or Shizuku, `pm trim-caches` clears them all (not undoable, caches are rebuilt). Without either, use the system clear-cache dialog (Android 8+, not on every ROM) or Android's storage screen.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = Root.isRooted && Optimizer.unlocked() && busy == null && Build.VERSION.SDK_INT >= 24, onClick = { scope.launch { busy = "Clearing caches..."; msg = withContext(Dispatchers.IO) { Optimizer.runMaintenance(Optimizer.maintenance.first { it.id == "trim" }).message.take(200) }; busy = null; AppState.bumpLog() } }) { Text(if (dev.optimizer.android.core.Priv.rootLike || dev.optimizer.android.core.Priv.shell) "Clear all caches" else "Clear all caches (needs Shizuku/root)") }
                if (Build.VERSION.SDK_INT >= 26) OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent("android.os.storage.action.CLEAR_APP_CACHE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { startSafe(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("System: clear all caches") }
                OutlinedButton(onClick = { startSafe(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Android storage") }
                OutlinedButton(onClick = { msg = "Cleared this app's own cache: ${mbs(Cleaner.clearOwnCache(ctx))}" }) { Text("Clear own cache") }
            }
        }
        scan?.let { sc ->
            Section("Results") {
                Text(sc.note, style = MaterialTheme.typography.bodySmall)
                val chosen = sc.items.filter { it.path in sel }
                Text("${chosen.size} selected · ${mbs(chosen.sumOf { it.size })}", fontWeight = FontWeight.SemiBold)
                Button(enabled = chosen.isNotEmpty() && busy == null, onClick = { confirm = true }) { Text("Move ${chosen.size} item(s) to trash…") }
            }
            listOf("thumbs" to "Thumbnail caches", "empty" to "Empty folders", "large" to "Large files (≥ 100 MB)", "dup" to "Duplicate files", "apk" to "APK files").forEach { (k, title) ->
                val l = sc.items.filter { it.kind == k }
                Section("$title (${l.size}, ${mbs(l.sumOf { it.size })})") {
                    if (l.isEmpty()) Text("none found", style = MaterialTheme.typography.bodySmall) else {
                        Row { TextButton(onClick = { l.forEach { if (it.path !in sel) sel.add(it.path) } }) { Text("Select all") }; TextButton(onClick = { l.forEach { sel.remove(it.path) } }) { Text("None") } }
                        l.forEach { it2 ->
                            Row(Modifier.fillMaxWidth().clickable { if (it2.path in sel) sel.remove(it2.path) else sel.add(it2.path) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(it2.path in sel, { on -> if (on) sel.add(it2.path) else sel.remove(it2.path) })
                                Column(Modifier.weight(1f)) { Text(it2.path.removePrefix(Cleaner.root().path + "/"), style = MaterialTheme.typography.bodySmall); Text("${if (it2.size > 0) mbs(it2.size) + " · " else ""}${it2.note}", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }
        Section("Trash (restorable)") {
            Text("${trash.size} item(s), ${mbs(trash.sumOf { it.size })} in ${Cleaner.trashRoot().path}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = trash.isNotEmpty() && busy == null, onClick = { scope.launch { busy = "Restoring..."; msg = withContext(Dispatchers.IO) { Cleaner.restore(trash) }; busy = null; tick++ } }) { Text("Restore all") }
                OutlinedButton(enabled = trash.isNotEmpty() && busy == null, onClick = { emptyDlg = true }) { Text("Empty trash…") }
            }
            trash.take(15).forEach { Text("${java.io.File(it.orig).name} · ${mbs(it.size)} → ${java.io.File(it.orig).parent?.removePrefix(Cleaner.root().path)}", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (confirm) {
        val chosen = scan?.items?.filter { it.path in sel }.orEmpty()
        Confirm("Move ${chosen.size} item(s) to trash?", "${mbs(chosen.sumOf { it.size })} will be moved to AndroidOptimizer/Trash (restorable). Empty folders are removed. Your files are not deleted until you empty the trash.\n\n" + chosen.take(8).joinToString("\n") { "• " + java.io.File(it.path).name }, "Move",
            onYes = { confirm = false; scope.launch { busy = "Moving..."; msg = withContext(Dispatchers.IO) { Cleaner.trash(chosen) }; busy = null; scan = scan?.let { s -> s.copy(items = s.items.filter { it.path !in sel }) }; sel.clear(); tick++ } }, onNo = { confirm = false })
    }
    if (emptyDlg) Confirm("Empty the trash permanently?", "This cannot be undone.", "Delete permanently", onYes = { emptyDlg = false; msg = Cleaner.emptyTrash(); tick++ }, onNo = { emptyDlg = false })
}

private val FAQ = listOf(
    "Why is my tablet freezing?" to "Use More › Freeze Doctor. It measures memory pressure, swap thrash, free storage, eMMC I/O stalls and wear, heat, battery, crash loops and kernel errors, and ranks the likely causes with evidence. Fix the top-ranked cause first.",
    "Does this app need root?" to "No. Monitor basics, RAM Optimizer (killBackgroundProcesses), widgets, cleaner, battery and usage views work without it. Root adds per-app RAM, CPU on Android 8+, dmesg/dropbox evidence, kernel tuning, freezing, startup-receiver toggles and reversible system changes. The app cannot root your device.",
    "Is 'Optimize RAM' useful?" to "Only briefly. Android already reclaims cached apps; closing apps frees RAM that comes back when they restart, and cold starts cost CPU and battery. Gentle (default) is best; it never touches protected apps, the foreground app or your 'Never stop' list.",
    "What does Safe Boost do and how do I undo it?" to "It closes cached apps gently, sets animations to 0.5x and clears caches when root + baseline are available. The Undo button reverts the animation change; closed apps and cleared caches cannot be 'undone' by nature.",
    "Why do the widgets show CPU n/a?" to "Android 8+ blocks /proc/stat for apps. Grant root once (Root tab) and the widget reads it through a root shell. Top-5 apps also need root.",
    "Why only 30-minute widget updates sometimes?" to "Android limits AppWidget updatePeriodMillis to 30 minutes. 1-5 second updates come from the app's foreground service, which runs while the screen is on. After a reboot Android 12+ may block starting it from the background; open the app once.",
    "Are cleaner deletions permanent?" to "No. Ticked items are moved to AndroidOptimizer/Trash and can be restored. Only 'Empty trash' deletes. Thumbnails and empty folders are the only preselected items; your own files never are.",
    "What is the baseline?" to "A backup of the settings this app may change. Root changes are locked until it exists, and every change is logged with its undo commands (Optimize › Change log). 'Reset to stock' replays those undos.",
    "Will a factory reset help?" to "Only as a last resort, after a backup. If freezes come back within days the usual causes are a worn eMMC chip, a weak battery or heat. See the guidance at the bottom of Freeze Doctor.",
    "Can I flash new firmware?" to "Only firmware made for your exact model and board (MediaTek: SP Flash Tool, Rockchip: rkdeveloptool, Allwinner: PhoenixSuit). A wrong image bricks the device. The Root tab and Freeze Doctor show your identifiers; this app does not flash anything.",
    "Why does Keep closed not always work without root?" to "Without root the guard can only call killBackgroundProcesses; foreground apps cannot be stopped and Android may restart apps. Freeze and background restrict need root.",
    "Where is my data?" to "Everything stays on the device. The only network use is whatever you open yourself (links, Google Play helper downloads you start). Health snapshots are shared only when you tap Share.",
)

@Composable
fun HelpScreen() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<Int?>(null) }
    val pi = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, Compat.signatureFlags()) }.getOrNull() }
    val cert = remember { pi?.let { p -> Compat.signers(p).firstOrNull()?.let { s -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(":") { "%02X".format(it) } } } }
    val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val url = "https://github.com/geek4945-creator/android-optimizer/releases"
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("About") {
            KV("App", "Android Optimizer"); KV("Version", "${pi?.versionName ?: "?"} (code ${pi?.let { Compat.versionCode(it) } ?: "?"})")
            KV("Build", if (debuggable) "debug (debug-signed)" else "release (R8-minified)"); KV("minSdk / targetSdk", "21 / ${ctx.applicationInfo.targetSdkVersion}")
            KV("Package", ctx.packageName); KV("Signing cert SHA-256", cert ?: "n/a")
            KV("This device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Text("It does not root your device and contains no exploits. Not distributed through Google Play.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Updates") {
            Text("Link only: this opens the project's GitHub releases page in your browser. The app never downloads or installs updates by itself. Compare the version shown above with the newest release and verify the signing certificate if you update manually.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { startSafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open releases page") }
            Text(url, style = MaterialTheme.typography.labelSmall)
        }
        Section("Quick links") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AppState.openPage("freeze") }) { Text("Freeze Doctor") }
                OutlinedButton(onClick = { AppState.openPage("quickfix") }) { Text("Quick Fix") }
                OutlinedButton(onClick = { AppState.showWizard = true }) { Text("Setup wizard") }
            }
        }
        Section("FAQ") {
            FAQ.forEachIndexed { i, (q, a) ->
                Text((if (open == i) "▼ " else "▶ ") + q, Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(vertical = 6.dp), fontWeight = FontWeight.SemiBold)
                if (open == i) Text(a, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            }
        }
    }
}
