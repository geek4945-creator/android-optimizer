package dev.optimizer.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.Edition
import dev.optimizer.android.core.Priv
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.ShizukuBridge
import dev.optimizer.android.features.Assessment
import dev.optimizer.android.features.FirewallVpnService
import dev.optimizer.android.features.ForceStopService
import dev.optimizer.android.features.Monitor
import dev.optimizer.android.features.NetUsage
import dev.optimizer.android.features.RootDetect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val RELEASES_URL = "https://github.com/geek4945-creator/android-optimizer/releases"
private const val SHIZUKU_DL = "https://shizuku.rikka.app/download/"
private const val SHIZUKU_GUIDE = "https://shizuku.rikka.app/guide/setup/"
private const val ADB_GUIDE = "https://developer.android.com/tools/adb#wireless-android11-command-line"
private const val MAGISK_INSTALL = "https://topjohnwu.github.io/Magisk/install.html"
private const val MAGISK_FAQ = "https://topjohnwu.github.io/Magisk/faq.html"

private fun open(ctx: android.content.Context, url: String) = startSafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private data class Cap(val name: String, val root: Char, val shizuku: Char, val plain: Char, val how: String, val plainBoost: (() -> Boolean)? = null)

/** W = works, L = limited (needs Shizuku/ADB to unlock more), N = needs real root (unavailable otherwise) */
private fun caps(): List<Cap> = listOf(
    Cap("Device assessment, launchers, update check, Play Store installer (PackageInstaller prompts)", 'W', 'W', 'W', "Nothing needed; installs always ask for your confirmation."),
    Cap("Live monitor: RAM, storage, battery, temperature, uptime, network rate, widgets", 'W', 'W', 'W', "Nothing needed."),
    Cap("CPU % and per-core CPU (widgets, Monitor)", 'W', 'W', 'L', "Android 8+ blocks /proc/stat for apps: use Shizuku/root (shell can read it).") { Build.VERSION.SDK_INT < 26 },
    Cap("Per-app RAM (PSS), top-5 apps, running-process list", 'W', 'W', 'L', "Needs dumpsys: Shizuku, or grant DUMP over adb (see commands below).") { Priv.dump() },
    Cap("Close apps / RAM Optimizer", 'W', 'W', 'L', "Without privileges only killBackgroundProcesses (cached/background processes). Shizuku/root add force-stop and memory-trim; optional Accessibility force-stop below."),
    Cap("Keep closed (auto re-close)", 'W', 'W', 'L', "Without privileges: UsageStats watcher + killBackgroundProcesses (best effort). Root adds event-driven detection."),
    Cap("Freeze / disable apps (pm disable-user, suspend)", 'W', 'W', 'N', "Only possible with a privileged shell (Shizuku or root). Without it use Settings > Apps > Disable."),
    Cap("Background restrict (appops, standby bucket, deviceidle)", 'W', 'W', 'N', "Needs Shizuku/root. Battery-optimisation whitelist requests work without."),
    Cap("Animation scales and other Settings writes", 'W', 'W', 'L', "Grant WRITE_SECURE_SETTINGS once over adb (below) or use Shizuku; otherwise follow the guided Developer-options shortcuts.") { Priv.writeSecure() },
    Cap("Kernel tuning: swappiness, zRAM, drop caches, compact memory", 'W', 'N', 'N', "Needs uid 0 (real root; Shizuku in adb mode is not enough). Unavailable in the Standard edition unless Shizuku runs as root."),
    Cap("Startup (boot receiver) manager", 'W', 'W', 'L', "List works everywhere; disabling receivers needs Shizuku/root."),
    Cap("Storage cleaner (thumbnails, empty folders, large files, duplicates, APKs, restorable trash)", 'W', 'W', 'W', "Needs 'All files access' (you grant it)."),
    Cap("Clear other apps' caches", 'W', 'W', 'L', "Shizuku/root: pm trim-caches. Otherwise only via Android's own Storage settings screen (apps cannot clear each other's caches)."),
    Cap("Per-app network usage (NetworkStatsManager)", 'W', 'W', 'W', "Needs Usage Access; mobile data may be unavailable on some versions."),
    Cap("Per-app network block", 'W', 'W', 'L', "Without privileges: opt-in local VPN firewall (one VPN at a time). iptables needs real root; netpolicy needs Shizuku/root.") { false },
    Cap("Per-app connection list (/proc/net)", 'W', 'L', 'N', "Android 10+ hides it from apps; shell/root may read it."),
    Cap("Backups / restore", 'W', 'L', 'L', "Without privileges: Settings values, package list/states, animation scales. Shizuku adds permissions/suspend state. Root also copies /data/system files and app data. Restore writes only what is allowed."),
    Cap("Freeze Doctor / diagnostics evidence", 'W', 'L', 'L', "Without privileges: memory, storage, battery, thermal, PSI where readable. Shizuku/root add dumpsys, dropbox, logcat crash buffer, dmesg (root), eMMC health (often root).") { Priv.dump() && Priv.readLogs() },
    Cap("Root detection / Root setup helper / su install", 'W', 'N', 'N', "Rooted edition only. The Standard edition never runs su."),
)

private fun pkgCmds(pkg: String) = """
# Run once from a PC with USB debugging (or wireless debugging) connected:
adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant $pkg android.permission.DUMP
adb shell pm grant $pkg android.permission.READ_LOGS
adb shell appops set $pkg GET_USAGE_STATS allow
adb shell appops set $pkg WRITE_SETTINGS allow
adb shell appops set $pkg MANAGE_EXTERNAL_STORAGE allow
adb shell dumpsys deviceidle whitelist +$pkg
# (PACKAGE_USAGE_STATS: some ROMs also accept)  adb shell pm grant $pkg android.permission.PACKAGE_USAGE_STATS
# Undo:
# adb shell pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS   (same for DUMP, READ_LOGS)
""".trim()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { tick++; Thread { runCatching { ShizukuBridge.refresh(ctx.applicationContext) }; AppState.refresh() }.start() } }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    var rootSeen by remember { mutableStateOf<String?>(null) }
    var a11y by remember { mutableStateOf(ForceStopService.enabled()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        a11y = ForceStopService.enabled()
        if (!Edition.ROOT) rootSeen = withContext(Dispatchers.IO) { runCatching { if (Root.suBinaryVisible()) "an su binary is visible on this device" else RootDetect.installedManagers(ctx).firstOrNull()?.let { "root manager app '${it.label}' is installed" } }.getOrNull() }
    }
    val pkg = ctx.packageName
    val level = Priv.level()
    val st = remember(tick, ShizukuBridge.ready) { Triple(ShizukuBridge.running(), ShizukuBridge.granted(), ShizukuBridge.isPackageInstalled(ctx)) }
    val usage = remember(tick) { Monitor.hasUsageAccess(ctx) }
    val canWriteSystem = remember(tick) { Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Access level · ${if (Edition.ROOT) "Rooted" else "Standard"} edition") {
            KV("Edition", "${Edition.name} (${pkg})")
            KV("Active command channel", Priv.levelLabel())
            KV("Order of preference", if (Edition.ROOT) "root (su) > Shizuku > ADB-granted permissions > plain app" else "Shizuku > ADB-granted permissions > plain app (su is never run)")
            Text("In this edition's screens the word 'root' on a button usually means 'a privileged shell is available' (root, or Shizuku). Kernel-level features (zRAM, drop caches, iptables, /system) need real uid 0 and are marked as such.", style = MaterialTheme.typography.bodySmall)
            if (!Edition.ROOT && rootSeen != null) Warn("Root looks available again (${rootSeen}). The Rooted edition can use it; the Standard edition never runs su.")
            if (!Edition.ROOT && rootSeen != null) Button(onClick = { open(ctx, RELEASES_URL) }) { Text("Switch to Rooted edition (releases page)") }
        }
        if (!Edition.ROOT) Section("Passive root check") {
            var h by remember { mutableStateOf<List<dev.optimizer.android.core.RootHealth.Line>?>(null) }
            Button(onClick = { scope.launch { h = withContext(Dispatchers.IO) { dev.optimizer.android.core.RootHealth.run(ctx) } } }) { Text("Run passive check") }
            h?.forEach { Text("• ${it.name}: ${it.detail}", style = MaterialTheme.typography.bodySmall) }
        }
        Section("Shizuku (shell access without root)") {
            Text("Shizuku is an open-source service (RikkaApps) that, once started with adb, lets apps you authorise run commands as the adb shell. This app uses it for force-stop, freeze/disable, appops, settings writes, dumpsys and logcat. It is optional and the app adapts automatically when it appears.", style = MaterialTheme.typography.bodySmall)
            KV("Shizuku app installed", if (st.third) "yes" else "not detected")
            KV("Service running", if (st.first) "yes (v${ShizukuBridge.version})" else "no")
            KV("Authorised for this app", if (st.second) "yes" else "no")
            if (st.second) KV("Runs as", if (ShizukuBridge.uid == 0) "root (uid 0)" else "adb shell (uid ${ShizukuBridge.uid})")
            if (ShizukuBridge.lastError.isNotEmpty()) Text("Last error: ${ShizukuBridge.lastError}", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = st.first && !st.second, onClick = { ShizukuBridge.requestPermission() }) { Text("Authorise this app") }
                OutlinedButton(onClick = { tick++; Thread { ShizukuBridge.refresh(ctx.applicationContext); AppState.refresh() }.start() }) { Text("Re-check") }
                OutlinedButton(onClick = { val i = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"); if (i != null) startSafe(ctx, i) else open(ctx, SHIZUKU_DL) }) { Text(if (st.third) "Open Shizuku" else "Get Shizuku") }
                OutlinedButton(onClick = { open(ctx, SHIZUKU_GUIDE) }) { Text("Shizuku setup guide") }
            }
            Info("Shizuku started over adb stops at every reboot (start it again; Android 11+ can restart it on-device through Wireless debugging). If Shizuku is not installed, this app still works with the limits shown in the matrix.")
        }
        Section("One-time PC commands (ADB)") {
            Text("These documented adb commands grant this app special permissions without root. WRITE_SECURE_SETTINGS lets it change animation scales and some settings; DUMP and READ_LOGS let it read dumpsys and logcat; usage access and storage access can also be granted here instead of by tapping through settings.", style = MaterialTheme.typography.bodySmall)
            Text(pkgCmds(pkg), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Button(onClick = { copyText(ctx, "adb commands", pkgCmds(pkg)) }) { Text("Copy commands") }
            Text("Current grants", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            KV("WRITE_SECURE_SETTINGS", if (Priv.writeSecure()) "granted" else "not granted"); KV("DUMP", if (Priv.dump()) "granted" else "not granted"); KV("READ_LOGS", if (Priv.readLogs()) "granted" else "not granted")
            KV("Usage Access", if (usage) "granted" else "not granted"); KV("Modify system settings", if (canWriteSystem) "granted" else "not granted")
            if (!usage) OutlinedButton(onClick = { startSafe(ctx, Monitor.usageAccessIntent()) }) { Text("Open Usage Access settings") }
        }
        Section("Wireless debugging pairing guide (Android 11+, no cable)") {
            listOf(
                "1. Connect the tablet and your PC to the same Wi-Fi. Settings › About tablet › tap 'Build number' 7 times to enable Developer options.",
                "2. Developer options › turn on 'Wireless debugging', then tap it and choose 'Pair device with pairing code'. Keep that dialog open: it shows an IP:port and a 6-digit code.",
                "3. On the PC (Android platform-tools installed): adb pair <IP>:<pairing port>   and enter the code.",
                "4. Back on the Wireless debugging screen note the main 'IP address & Port' and run: adb connect <IP>:<port>",
                "5. Run the commands above once (adb shell pm grant ...). To use Shizuku instead: open Shizuku › 'Start via Wireless debugging' (Android 11+ can do the pairing on the tablet itself), or on a PC: adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                "Android 10 and older: use a USB cable with 'USB debugging' enabled and the same adb shell commands.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
            OutlinedButton(onClick = { open(ctx, ADB_GUIDE) }) { Text("Official adb / wireless debugging docs") }
            OutlinedButton(onClick = { startSafe(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Developer options") }
        }
        Section("Capability matrix") {
            Text("Works = full. Limited (needs Shizuku/ADB) = partly works now, more with Shizuku or the adb grants. Needs root (unavailable) = impossible without uid 0. Columns: Root · Shizuku · Plain; your current state is marked ▶.", style = MaterialTheme.typography.bodySmall)
            fun w(c: Char) = when (c) { 'W' -> "Works"; 'L' -> "Limited"; else -> "Needs root" }
            caps().forEach { c ->
                val plain = if (c.plain != 'W' && c.plainBoost?.invoke() == true) 'W' else c.plain
                val now = when (level) { Priv.Level.ROOT -> c.root; Priv.Level.SHIZUKU -> if (ShizukuBridge.uid == 0 && c.shizuku == 'N') 'W' else c.shizuku; else -> plain }
                val status = when (now) { 'W' -> "Works"; 'L' -> "Limited (needs Shizuku/ADB)"; else -> "Needs root (unavailable)" }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(c.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("Now: $status", color = when (now) { 'W' -> MaterialTheme.colorScheme.primary; 'L' -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error }, style = MaterialTheme.typography.labelMedium)
                    Text("${if (level == Priv.Level.ROOT) "▶ " else ""}Root: ${w(c.root)} · ${if (level == Priv.Level.SHIZUKU) "▶ " else ""}Shizuku: ${w(c.shizuku)} · ${if (level == Priv.Level.NONE) "▶ " else ""}Plain: ${w(plain)}${if (!Edition.ROOT) "   (Root column: Rooted edition only)" else ""}", style = MaterialTheme.typography.labelSmall)
                    Text(c.how, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Section("Force stop via Accessibility (optional, no root)") {
            Text("An accessibility service that only acts when you press a Force stop button in this app: it opens the target's App info screen and taps the system 'Force stop' and 'OK'. It cannot read or store anything otherwise. Texts differ between ROMs and languages, so it can fail on unusual builds. You can switch it off any time in Settings › Accessibility. (Google Play restricts this use of accessibility; this build is for sideloading.)", style = MaterialTheme.typography.bodySmall)
            KV("Status", if (a11y) "enabled" else "not enabled")
            Button(onClick = { startSafe(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(if (a11y) "Open Accessibility settings" else "Enable in Accessibility settings") }
            ForceStopService.results.toList().takeLast(6).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        Section("Baseline backup (limited without root)") {
            Text(if (AppState.baseline) "A baseline exists. Changes this app is allowed to make are logged and undoable." else "Create a baseline before the app changes anything. Without root it stores what the app can read: Settings values, animation scales, installed-app list and states.", style = MaterialTheme.typography.bodySmall)
            Busy(busy)
            Button(enabled = busy == null && !AppState.baseline, onClick = { scope.launch { busy = "Creating baseline..."; val r = withContext(Dispatchers.IO) { Backups.create(ctx, "Baseline", true) { s -> busy = s } }; msg = r.fold({ "Created ${it.id}" }, { "Failed: ${it.message}" }); busy = null; AppState.bumpLog() } }) { Text("Create baseline backup") }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (!Edition.ROOT) Section("Root lost? Recovery help") {
            Text("Root disappears when an over-the-air (OTA) update or a firmware flash replaces the boot / init_boot image that Magisk or KernelSU patched. Apps then report 'su not found' or 'permission denied'. Typical recoveries (on a device with an unlocked bootloader): before rebooting after an OTA, use Magisk › Install › 'Install to Inactive Slot (After OTA)'; or re-patch the stock boot/init_boot image of your CURRENT build and flash it with fastboot; or restore the stock image if the tablet will not boot. A locked bootloader or a vendor that does not allow unlocking means root cannot come back.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(ctx, MAGISK_INSTALL) }) { Text("Magisk install guide") }
                OutlinedButton(onClick = { open(ctx, MAGISK_FAQ) }) { Text("Magisk FAQ") }
                OutlinedButton(onClick = { open(ctx, RELEASES_URL) }) { Text("Rooted edition (releases)") }
            }
            Info("The Standard edition never executes su. If root returns, install the Rooted edition (it can be installed side by side; settings are not shared).")
        }
    }
}

/** Guided Developer-options shortcuts (no privileges needed) + direct writes if WRITE_SECURE_SETTINGS / Shizuku is available. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidedSettingsCard() {
    val ctx = LocalContext.current
    Section("Guided settings (no root)") {
        Text("Each button opens the right Android screen; set the value yourself. They need no special access.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { startSafe(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Developer options") }
            OutlinedButton(onClick = { startSafe(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Battery optimisation list") }
            OutlinedButton(onClick = { startSafe(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Storage / clear cache") }
            OutlinedButton(onClick = { startSafe(ctx, Intent(Settings.ACTION_DATA_USAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Data usage / Data Saver") }
        }
        Text("In Developer options: 'Window / Transition / Animator duration scale' → 0.5x; 'Background process limit' → 'At most 2-4 processes' (reset to Standard afterwards if apps misbehave); 'Don't keep activities' only for testing; 'Force GPU rendering' optional. Settings › Apps › Special app access › Battery optimisation lets you exempt apps; many vendors also have 'Autostart' or 'Battery' lists.", style = MaterialTheme.typography.bodySmall)
        if (Priv.writeSecure() || Priv.shell) Info("This device lets the app write settings directly: use Optimize › Performance fixes (animation scales).") else Info("Tip: granting WRITE_SECURE_SETTINGS once over adb (More › Access) lets the app set animation scales itself.")
    }
}

/** Opt-in VPN firewall + per-app usage (both editions; the only no-root way to block an app's network). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoRootNetworkCards() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(FirewallVpnService.blocked()) }
    var active by remember { mutableStateOf(FirewallVpnService.active) }
    var days by remember { mutableIntStateOf(1) }
    var use by remember { mutableStateOf<List<dev.optimizer.android.features.NetUse>?>(null) }
    val usage = remember(tick) { Monitor.hasUsageAccess(ctx) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> if (r.resultCode == Activity.RESULT_OK) { FirewallVpnService.start(ctx); tick++ } }
    LaunchedEffect(tick) { kotlinx.coroutines.delay(600); active = FirewallVpnService.active }
    LaunchedEffect(days, usage, tick) { use = if (usage && Build.VERSION.SDK_INT >= 23) withContext(Dispatchers.IO) { runCatching { NetUsage.perApp(ctx, days) }.getOrDefault(emptyList()) } else null }
    Section("Local VPN firewall (no root, opt-in)") {
        Text("Blocks the apps you choose by routing only them into a local VPN that discards their traffic. Limits: Android allows ONE VPN at a time (this replaces any real VPN while active, and shows the key icon); it blocks all traffic of those apps on Wi-Fi and mobile data; it cannot show per-app connection logs (only counters of dropped packets per destination); apps can look 'connected' but time out. System apps that must stay online (Play services, launcher, keyboard) cannot be selected.", style = MaterialTheme.typography.bodySmall)
        KV("Status", if (active) "active" else "stopped"); KV("Blocked apps", blocked.size.toString()); if (active) KV("Packets dropped", FirewallVpnService.dropped.get().toString())
        dev.optimizer.android.core.Store.getStr("vpn_error").takeIf { it.isNotEmpty() }?.let { Warn("Last error: $it") }
        blocked.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { blocked = blocked - p; FirewallVpnService.setBlocked(blocked); if (active) FirewallVpnService.start(ctx); tick++ }) { Text("Remove") } } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker = true }) { Text("Choose apps…") }
            Button(enabled = blocked.isNotEmpty() && !active, onClick = { val i = FirewallVpnService.prepare(ctx); if (i != null) consent.launch(i) else { FirewallVpnService.start(ctx); tick++ } }) { Text("Start firewall") }
            OutlinedButton(enabled = active, onClick = { FirewallVpnService.stop(ctx); tick++ }) { Text("Stop") }
        }
        if (active) FirewallVpnService.topDests().forEach { Text("→ ${it.first}: ${it.second} packets", style = MaterialTheme.typography.labelSmall) }
    }
    Section("Per-app network usage (no root)") {
        if (!usage) { Warn("Usage Access is needed."); Button(onClick = { startSafe(ctx, Monitor.usageAccessIntent()) }) { Text("Open Usage Access settings") } }
        else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "24 h", 7 to "7 days", 30 to "30 days").forEach { (d, l) -> androidx.compose.material3.FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) }) } }
            val u = use
            if (u == null) Busy("Reading...") else if (u.isEmpty()) Text("No data (mobile data stats may be unavailable on tablets without a SIM).", style = MaterialTheme.typography.bodySmall)
            else u.forEach { Text("${it.label}: Wi-Fi ${it.wifiBytes / 1048576} MB · mobile ${it.mobileBytes / 1048576} MB", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (picker) {
        var apps by remember { mutableStateOf<List<dev.optimizer.android.features.AppAssessment>?>(null) }
        var q by remember { mutableStateOf("") }
        val sel = remember { androidx.compose.runtime.mutableStateListOf<String>().also { it.addAll(blocked) } }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Assessment.apps(ctx).filter { !it.system && !dev.optimizer.android.core.Protected.isProtected(ctx, it.pkg) } } }
        androidx.compose.material3.AlertDialog(onDismissRequest = { picker = false }, title = { Text("Block network for…") },
            text = { Column { androidx.compose.material3.OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true)
                if (apps == null) Busy("Loading apps...") else LazyColumn(Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(max = 380.dp)) {
                    items(apps!!.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }.take(150), key = { it.pkg }) { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(a.pkg in sel, { on -> if (on) sel.add(a.pkg) else sel.remove(a.pkg) }); Column { Text(a.label); Text(a.pkg, style = MaterialTheme.typography.labelSmall) } }
                    } } } },
            confirmButton = { Button(onClick = { blocked = sel.toList(); FirewallVpnService.setBlocked(blocked); if (active) FirewallVpnService.start(ctx); picker = false; tick++ }) { Text("Save ${sel.size}") } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Cancel") } })
    }
}
