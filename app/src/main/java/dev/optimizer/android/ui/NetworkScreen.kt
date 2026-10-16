package dev.optimizer.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.Beacon
import dev.optimizer.android.features.ConnView
import dev.optimizer.android.features.Net
import dev.optimizer.android.features.NetDb
import dev.optimizer.android.features.NetSamplerService
import dev.optimizer.android.features.Trackers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NetworkScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var live by remember { mutableStateOf<List<ConnView>?>(null) }
    var liveNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var beacons by remember { mutableStateOf<List<Beacon>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var sampling by remember { mutableStateOf(Store.netSampling) }
    var interval by remember { mutableIntStateOf(Store.netIntervalSec) }
    var retention by remember { mutableIntStateOf(Store.netRetentionDays) }
    var timelinePkg by remember { mutableStateOf<String?>(null) }
    var blockPkg by remember { mutableStateOf<String?>(null) }
    var blockMethod by remember { mutableStateOf(if (dev.optimizer.android.core.Priv.rootLike) "iptables" else "netpolicy") }
    val dbCount = remember(tick) { runCatching { NetDb.count() }.getOrDefault(0L) }
    val pkgsWithData = remember(tick) { runCatching { NetDb.packagesWithData() }.getOrDefault(emptyList()) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Busy(busy)
        msg?.let { Section("Result") { Text(it, style = MaterialTheme.typography.bodySmall) } }

        NoRootNetworkCards()

        Section("Live connections") {
            Text("Reads /proc/net/tcp, tcp6, udp, udp6. Android 10+ blocks these files for normal apps, so this needs root. Flags: tracker list match, uncommon port, likely cleartext, no reverse DNS. The app cannot see hostnames the app asked for, packet contents or TLS SNI.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = busy == null, onClick = {
                scope.launch {
                    busy = "Reading connections + reverse DNS..."
                    val (l, n) = withContext(Dispatchers.IO) { val r = Net.readConnections(); r.conns.take(120).map { Net.view(ctx, it, true) } to r.note }
                    live = l; liveNote = n; busy = null
                }
            }) { Text("Refresh now") }
            liveNote?.let { Warn(it) }
            live?.let { l ->
                Text("${l.size} connection(s) shown (max 120)", fontWeight = FontWeight.SemiBold)
                l.sortedByDescending { it.flags.size }.forEach { v ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("${v.label} (${v.pkg})", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text("${v.conn.proto} → ${v.conn.rip}:${v.conn.rport}${v.host?.let { " ($it)" } ?: ""}  state ${"%02X".format(v.conn.state)}", style = MaterialTheme.typography.bodySmall)
                        if (v.flags.isNotEmpty()) Text("⚑ " + v.flags.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (Net.cleartextAllowed(ctx, v.pkg)) Text("manifest allows cleartext traffic", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Section("Sampler & history (local database)") {
            Text("Optional foreground service that samples connections (root) and stores new ones in a local SQLite database on this device only, for timelines and beacon detection. Short-lived connections between samples are missed; set the interval below the beat you want to catch.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(sampling, enabled = AppState.rooted, onCheckedChange = { sampling = it; if (it) NetSamplerService.start(ctx) else NetSamplerService.stop(ctx) })
                Text("  Sampler ${if (sampling) "running" else "stopped"}${if (!AppState.rooted) " (needs root)" else ""}")
            }
            Text("Sample every:", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5, 10, 30, 60).forEach { s -> FilterChip(selected = interval == s, onClick = { interval = s; Store.netIntervalSec = s }, label = { Text("${s}s") }) } }
            Text("Retention (older rows are deleted):", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 3, 7, 14).forEach { d -> FilterChip(selected = retention == d, onClick = { retention = d; Store.netRetentionDays = d }, label = { Text("$d d") }) } }
            Text("$dbCount connection records stored.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tick++ }) { Text("Reload") }
                OutlinedButton(onClick = { NetDb.clear(); tick++ }) { Text("Delete history") }
            }
        }

        Section("Beaconing analysis") {
            Text("Looks, per app and remote endpoint, for ≥6 connection starts at near-constant intervals (jitter ≤ 25%, 5 s – 4 h). Regular beats are typical for push/keep-alive and analytics SDKs as well as for malware — it is a lead, not a verdict.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = busy == null, onClick = { scope.launch { busy = "Analysing..."; beacons = withContext(Dispatchers.IO) { Net.analyzeBeacons() }; busy = null } }) { Text("Analyse stored history") }
            beacons?.let { b ->
                if (b.isEmpty()) Text("No regular beacons found in ${dbCount} records.", style = MaterialTheme.typography.bodySmall)
                b.forEach {
                    Text("${it.pkg} → ${it.remote}:${it.port}\n${it.count} connections, every ~%.0f s (jitter %.0f%%), %s – %s".format(it.medianSec, it.jitterPct, DateFormat.getTimeInstance().format(Date(it.first)), DateFormat.getTimeInstance().format(Date(it.last))),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }

        Section("Per-app timeline") {
            if (pkgsWithData.isEmpty()) Text("No history yet. Start the sampler.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pkgsWithData.take(30).forEach { (p, c) -> FilterChip(selected = timelinePkg == p, onClick = { timelinePkg = p }, label = { Text("${p.substringAfterLast('.')} ($c)") }) }
            }
            timelinePkg?.let { p ->
                val rows = remember(p, tick) { NetDb.timeline(p, System.currentTimeMillis() - 24 * 3600_000L) }
                Text("$p — ${rows.size} connection starts in the last 24 h (newest first, max 500)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                val now = System.currentTimeMillis()
                val buckets = IntArray(48) // 30-min buckets over 24 h
                rows.forEach { r -> val idx = ((now - r.first) / 1_800_000L).toInt(); if (idx in 0..47) buckets[47 - idx]++ }
                val mx = (buckets.maxOrNull() ?: 1).coerceAtLeast(1)
                val col = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                    val w = size.width / 48f
                    buckets.forEachIndexed { i, v -> val h = size.height * v / mx; drawRect(col, Offset(i * w + 1f, size.height - h), Size(w - 2f, h)) }
                }
                Text("← 24 h ago … now →  (bars = connection starts per 30 min)", style = MaterialTheme.typography.labelSmall)
                rows.take(60).forEach { r ->
                    Text("${DateFormat.getTimeInstance().format(Date(r.first))}  ${r.proto} ${r.rip}:${r.rport}${r.host?.let { " ($it)" } ?: ""}${if (r.flags.isNotEmpty()) "  ⚑ ${r.flags}" else ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Section("Tracker blocklist") {
            Text(Trackers.LICENSE_NOTE, style = MaterialTheme.typography.bodySmall)
            Text("Pinned to commit ${Trackers.COMMIT.take(12)}…; each file's SHA-256 is verified after download and refused on mismatch. ${Trackers.domainCount()} domains / ${Trackers.ipCount()} resolved IPs loaded.", style = MaterialTheme.typography.bodySmall)
            Trackers.lists.forEach { l ->
                val have = remember(tick) { Trackers.downloaded(l) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(l.label, style = MaterialTheme.typography.bodyMedium); Text("${l.bytes / 1024} KB · sha256 ${l.sha256.take(10)}…", style = MaterialTheme.typography.bodySmall) }
                    if (have) OutlinedButton(onClick = { Trackers.delete(l); tick++ }) { Text("Remove") }
                    else Button(enabled = busy == null, onClick = { scope.launch { busy = "Downloading ${l.label}..."; val e = withContext(Dispatchers.IO) { Trackers.download(l) }; msg = e ?: "Downloaded and verified ${l.label}"; busy = null; tick++ } }) { Text("Download") }
                }
            }
            OutlinedButton(enabled = busy == null && Trackers.domainCount() > 0, onClick = {
                scope.launch { busy = "Resolving tracker domains to IPs..."; val n = withContext(Dispatchers.IO) { Trackers.resolveAll { d, t -> if (d % 50 == 0) busy = "Resolving $d / $t" } }; msg = "Resolved $n tracker IPs (best effort; CDN addresses change)."; busy = null; tick++ }
            }) { Text("Resolve domains → IPs") }
        }

        Section(if (dev.optimizer.android.core.Priv.rootLike) "Network block (root, reversible)" else "Network block (Shizuku/root, reversible; or use the VPN firewall above)") {
            if (!AppState.unlocked) Warn("Needs root and the baseline backup.")
            Text("iptables: `-m owner --uid-owner <uid> -j REJECT` (IPv4+IPv6) blocks all traffic of the app, but rules vanish on reboot (use Re-apply). netpolicy: `cmd netpolicy add restrict-background-blacklist <uid>` only restricts background data and is persistent; availability depends on the Android version. Protected apps (system UI, Play services, launcher, keyboard…) cannot be blocked. Blocks are logged and included in the stock snapshot.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = blockMethod == "iptables", enabled = dev.optimizer.android.core.Priv.rootLike, onClick = { blockMethod = "iptables" }, label = { Text(if (dev.optimizer.android.core.Priv.rootLike) "iptables" else "iptables (needs root)") })
                FilterChip(selected = blockMethod == "netpolicy", onClick = { blockMethod = "netpolicy" }, label = { Text("netpolicy") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (live?.map { it.conn.uid to it.pkg }?.distinct() ?: emptyList()).filter { !it.second.startsWith("uid:") && it.second != "root" && it.second != "system" }.forEach { (_, p) ->
                    FilterChip(selected = blockPkg == p, onClick = { blockPkg = p }, label = { Text(p.substringAfterLast('.')) })
                }
            }
            if (live == null) Text("Refresh live connections to pick an app from them.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = AppState.unlocked && blockPkg != null && busy == null, onClick = {
                scope.launch { busy = "Blocking..."; val r = withContext(Dispatchers.IO) { Net.block(ctx, blockPkg!!, blockMethod) }; msg = r.message; busy = null; AppState.bumpLog(); tick++ }
            }) { Text("Block ${blockPkg ?: "(pick app)"}") }
            val blocks = remember(tick, AppState.logVersion) { Net.blocks() }
            blocks.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.pkg} · ${b.method} · uid ${b.uid}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = AppState.rooted, onClick = { scope.launch { msg = withContext(Dispatchers.IO) { Net.unblock(b.pkg, b.method).message }; AppState.bumpLog(); tick++ } }) { Text("Unblock") }
                }
            }
            if (blocks.isNotEmpty()) OutlinedButton(enabled = AppState.rooted, onClick = { scope.launch { msg = withContext(Dispatchers.IO) { Net.reapplyAll() } } }) { Text("Re-apply after reboot") }
            OutlinedButton(enabled = AppState.rooted, onClick = { scope.launch { busy = "Reading rules..."; msg = withContext(Dispatchers.IO) { Net.currentRules() }; busy = null } }) { Text("Show current rules") }
        }
    }
}
