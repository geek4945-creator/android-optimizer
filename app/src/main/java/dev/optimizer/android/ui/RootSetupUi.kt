package dev.optimizer.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.RootState
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.RootDetect
import dev.optimizer.android.features.RootReport
import dev.optimizer.android.features.RootVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun copyText(ctx: Context, label: String, text: String) {
    runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)); toast(ctx, "Copied") }
        .onFailure { toast(ctx, "Cannot copy: ${it.message}") }
}

/** (O) Prominent 'Detect root access' card. One su invocation per press. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RootDetectCard(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<RootReport?>(null) }
    Section("Detect root access") {
        Text("Runs every check once (su paths, `which su`, root manager apps, build tags, /system mount, root mounts) and then makes a single `su` call, so your root manager prompts at most once.", style = MaterialTheme.typography.bodySmall)
        Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            scope.launch {
                busy = true
                val r = withContext(Dispatchers.IO) { RootDetect.detect(ctx) }
                report = r; busy = false; AppState.refresh()
                if (r.verdict == RootVerdict.GRANTED) onGranted()
            }
        }) { Text(if (busy) "Detecting... (answer the root prompt if shown)" else "Detect root access") }
        Busy(if (busy) "Waiting for su..." else null)
        report?.let { r ->
            val col = when (r.verdict) { RootVerdict.GRANTED -> MaterialTheme.colorScheme.primary; RootVerdict.NONE -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.error }
            Text(r.verdict.title, color = col, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            r.managerVersion?.let { Text("Root manager: $it", fontWeight = FontWeight.SemiBold) }
            when (r.verdict) {
                RootVerdict.DENIED -> Info("A su binary or manager exists, but this app was not granted access (denied, dismissed or timed out). Open your root manager, find 'Android Optimizer' in its grant list (Magisk: Superuser tab; KernelSU: Superuser; APatch: SuperUser) and allow it, then detect again.")
                RootVerdict.BROKEN -> Info("A su binary was found but running it failed. Typical causes: an incomplete or leftover install, wrong architecture, SELinux blocking it, or the root manager app is installed without the boot image patched. Reinstall the root solution (see the guide below).")
                RootVerdict.NONE -> Info("No su binary or root manager was found. This app cannot root the device; use the guide below.")
                else -> {}
            }
            r.evidence.forEach { e ->
                val mark = when (e.pass) { true -> "✓"; false -> "✗"; null -> "•" }
                Text("$mark ${e.name}: ${e.detail}", style = MaterialTheme.typography.bodySmall, color = if (e.pass == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val mgr = r.managers.firstOrNull { !it.heuristic } ?: r.managers.firstOrNull()
                if (mgr != null) OutlinedButton(onClick = { if (!RootDetect.openManager(ctx, mgr)) toast(ctx, "Cannot open ${mgr.label}") }) { Text("Open ${mgr.label}") }
                OutlinedButton(onClick = { copyText(ctx, "root report", r.text()) }) { Text("Copy report") }
            }
        }
    }
}

/** (L) Root setup helper: honest, device-tailored guide. No exploits, nothing bundled or downloaded by the app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RootSetupGuide(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<RootDetect.BootInfo?>(null) }
    var verify by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val managers = remember { RootDetect.installedManagers(ctx) }
    androidx.compose.runtime.LaunchedEffect(Unit) { info = withContext(Dispatchers.IO) { RootDetect.bootInfo(ctx) } }

    Section("Root setup helper") {
        Text("This app cannot root a phone and contains no exploits or root binaries. It explains the normal, supported-by-the-community route and shows the exact commands for this device. Unlocking the bootloader ERASES ALL DATA, can void the warranty, may disable banking/DRM features, and a wrong flash can leave the phone unbootable. Back up first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        val i = info
        if (i == null) Busy("Reading bootloader / slot properties...") else {
            i.rows.forEach { (k, v) -> KV(k, v) }
            i.vendorNotes.forEach { Info(it) }
            if (Root.isRooted) Info("Root is already working on this device. You do not need the steps below; use 'Verify root'.")
        }
        val detected = managers.firstOrNull { !it.heuristic } ?: managers.firstOrNull()
        if (detected != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Installed: ${detected.label} ${detected.version ?: ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { if (!RootDetect.openManager(ctx, detected)) toast(ctx, "Cannot open ${detected.label}") }) { Text("Open ${detected.label}") }
        } else Text("No root manager app detected (Magisk, KernelSU, APatch, SuperSU).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val st = withContext(Dispatchers.IO) { Root.request() }
                    busy = false; AppState.refresh()
                    verify = when (st) { RootState.ROOTED -> "Root verified: ${Root.lastDetail}"; RootState.DENIED -> "su exists but access was not granted: ${Root.lastDetail}"; else -> "No working su: ${Root.lastDetail}" }
                    if (st == RootState.ROOTED) { Store.rootGrantedBefore = true; onGranted() }
                }
            }) { Text("Verify root") }
        }
        Busy(if (busy) "Waiting for su..." else null)
        verify?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    val i = info
    if (i != null && !Root.isRooted) {
        Section("Step-by-step (tailored to this device)") {
            val steps = mutableListOf<String>()
            steps += "1. Developer options: Settings > About phone > tap Build number 7 times; then Developer options > enable OEM unlocking (if the switch is missing or greyed out, the maker/carrier blocks unlocking) and USB debugging."
            steps += "2. Unlock the bootloader (${i.locked.substringBefore(" (")} now). " + when (i.brandKey) {
                "samsung" -> "Samsung: confirm the unlock in Download mode. Erases data, trips Knox."
                "xiaomi" -> "Xiaomi: official Mi Unlock tool + bound account + waiting period."
                "huawei" -> "Huawei/Honor: no official unlock codes any more."
                "soc-tablet" -> "Generic ${i.socFamily} tablets often have no unlock step; flashing is done in the vendor download mode."
                else -> "`fastboot flashing unlock` (older: `fastboot oem unlock`). Erases data."
            }
            steps += "3. Get the stock ${if (i.bootTarget.startsWith("init_boot")) "init_boot.img" else "boot.img"} for EXACTLY this build (${android.os.Build.DISPLAY}); a mismatched image bootloops. Take it from your maker's firmware package or factory image."
            steps += "4. Install the Magisk app on the phone (official GitHub release below), open Install > Select and Patch a File, choose the stock image. Copy the patched image to the PC (`adb pull`)."
            steps += "5. Flash it: " + (if (i.brandKey == "samsung") "Odin (AP slot)" else if (i.brandKey == "soc-tablet") "your SoC's download tool" else "`fastboot flash ${if (i.bootTarget.startsWith("init_boot")) "init_boot" else "boot"}${i.slotSuffix ?: ""} magisk_patched.img`") + ", then reboot."
            steps += "6. Open the Magisk app (finish setup), grant 'Android Optimizer' in its Superuser list, then press 'Verify root' above and create the baseline backup."
            steps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val cmds = remember(i) { RootDetect.commands(i) }
            Text(cmds, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { copyText(ctx, "root commands", cmds) }) { Text("Copy commands") }
        }
        Section("Verified resources (opened in your browser)") {
            Text("Links were checked reachable on 2026-10-16. Nothing is downloaded by this app; download Magisk yourself from its official page.", style = MaterialTheme.typography.bodySmall)
            val links = listOf(
                "Download Magisk APK (official releases)" to "https://github.com/topjohnwu/Magisk/releases",
                "Magisk installation guide" to "https://topjohnwu.github.io/Magisk/install.html",
                "KernelSU repository" to "https://github.com/tiann/KernelSU", "KernelSU installation guide" to "https://kernelsu.org/guide/installation.html",
                "APatch repository" to "https://github.com/bmax121/APatch", "APatch site" to "https://apatch.dev/",
                "Android platform-tools (adb / fastboot)" to "https://developer.android.com/tools/releases/platform-tools",
                "AOSP: locking / unlocking the bootloader" to "https://source.android.com/docs/core/architecture/bootloader/locking_unlocking",
            ) + (if (i.brandKey == "xiaomi") listOf("Xiaomi Mi Unlock" to "https://en.miui.com/unlock/") else emptyList()) +
                (if (i.socFamily == "Rockchip") listOf("rkdeveloptool" to "https://github.com/rockchip-linux/rkdeveloptool") else emptyList())
            links.forEach { (t, u) -> OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { startSafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u))) }) { Text(t) } }
        }
    }
}

/** (L4) Advanced: install a user-provided su binary. Visible only when a root shell already exists. */
@Composable
fun SuInstallCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<RootDetect.SuFile?>(null) }
    var target by remember { mutableStateOf("/system/xbin/su") }
    var confirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            picked = withContext(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "su-picked.bin")
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }; RootDetect.inspectSuFile(f) }.getOrNull()
            }
            if (picked == null) msg = "Could not read the selected file."
        }
    }
    Section("Advanced: install an su binary (existing root shell required)") {
        Text("Only for devices where you already have a root shell (e.g. `adb root` on a userdebug/eng build, or another root) and /system can be remounted read-write. You must supply the binary yourself; this app never bundles or downloads one. Nothing is overwritten, the change is logged with an undo, and a baseline backup is required first.", style = MaterialTheme.typography.bodySmall)
        if (!AppState.rooted) Warn("Needs an existing root shell: use 'Detect root access' first.")
        else if (!AppState.baseline) Warn("Create the baseline backup first.")
        OutlinedButton(enabled = AppState.rooted, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick su binary file...") }
        picked?.let { p ->
            KV("File", "${p.size} bytes"); KV("SHA-256", p.sha256); KV("ELF architecture", p.elfArch ?: "not ELF")
            p.problems.forEach { Warn(it) }
            Text("Compare the SHA-256 with the checksum published by the source of the binary before installing.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("/system/xbin/su", "/system/bin/su").forEach { t -> FilterChip(selected = target == t, onClick = { target = t }, label = { Text(t) }) }
            }
            Button(enabled = AppState.unlocked && !busy && p.problems.none { it.startsWith("Not an ELF") }, onClick = { confirm = true }) { Text("Install to $target...") }
        }
        Busy(if (busy) "Installing..." else null)
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirm) picked?.let { p ->
        Confirm("Install su to $target?", "SHA-256 ${p.sha256}\n\nThe file is copied to $target with mode 06755 root:root and SELinux context system_file. /system is remounted read-write for this and set back to read-only. On most Android 10+ devices /system cannot be remounted and nothing will change. Undo is in the change log.", "Install",
            onYes = { confirm = false; scope.launch { busy = true; val r = withContext(Dispatchers.IO) { RootDetect.installSu(p, target) }; msg = r.second; busy = false; AppState.bumpLog() } },
            onNo = { confirm = false })
    }
}
