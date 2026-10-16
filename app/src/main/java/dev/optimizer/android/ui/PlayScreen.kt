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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.optimizer.android.core.Store
import dev.optimizer.android.features.Launchers
import dev.optimizer.android.features.Play
import dev.optimizer.android.features.PlayComponent
import dev.optimizer.android.features.PlayDetect
import dev.optimizer.android.features.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var det by remember { mutableStateOf<PlayDetect?>(null) }
    var reload by remember { mutableStateOf(0) }
    val verif = remember { mutableStateMapOf<String, Verification>() }
    val urls = remember { mutableStateMapOf<String, String>() }
    var status by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var priv by remember { mutableStateOf(false) }
    var useRoot by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    var gsf by remember { mutableStateOf<Play.GsfId?>(null) }
    var pending by remember { mutableStateOf<PlayComponent?>(null) }

    LaunchedEffect(reload) { det = withContext(Dispatchers.IO) { Play.detect(ctx) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val comp = pending ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Reading and verifying ${comp.name}..."
            val v = withContext(Dispatchers.IO) {
                val f = File(Store.playDir(), "${comp.pkg}.src")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                Play.verify(ctx, f, comp)
            }
            verif[comp.pkg] = v; busy = null
        }
    }

    fun note(s: String) { status = status + s }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Google Play installer") {
            Warn("Legal / ToS: Google's apps are proprietary. Installing them on a device that did not ship with them, or obtaining them from third-party mirrors, may violate Google's Terms of Service or local law and is entirely your responsibility. This app bundles no APKs; every download is started by you. APKs are only installed if their signing certificate matches a Google certificate fingerprint verified by the developer of this app (see below). Play Store may not work at all on some ROMs (for example Huawei HMS devices) and devices that are not Play-certified show 'device is not certified'.")
        }
        val d = det
        Section("1. Detection") {
            if (d == null) Busy("Detecting...") else {
                KV("Play Store (com.android.vending)", d.vending ?: "not installed")
                KV("Play services (com.google.android.gms)", d.gms ?: "not installed")
                KV("Services Framework (com.google.android.gsf)", d.gsf ?: "not installed")
                KV("Android", "${d.release} (API ${d.sdk})")
                KV("Supported ABIs", d.abis.joinToString())
                KV("Screen density", "${d.dpi} dpi (bucket ${d.dpiBucket})")
                KV("ROM / vendor", "${d.vendor.rom} · ${d.vendor.groups.joinToString { it.label }}")
                KV("Huawei HMS present", if (d.hasHms) "yes" else "no")
                OutlinedButton(onClick = { reload++ }) { Text("Re-detect") }
            }
        }
        if (d != null) {
            Section("2. Compatibility explanation") {
                d.explanation.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                d.warnings.forEach { Warn(it) }
            }
            Section("3. Get the APKs (you choose the source)") {
                Text("APKMirror has no public API and no direct links, so the app opens the right page in your browser. Download the variant described above, then pick the file here (a plain .apk, or an .apkm/.xapk/.apks bundle containing .apk files). You can also paste a direct HTTPS link from a source you trust. In every case the signing certificate is checked before install.", style = MaterialTheme.typography.bodySmall)
                Play.components.sortedBy { it.order }.forEach { c ->
                    val inst = Play.installedCertSha(ctx, c.pkg)
                    val v = verif[c.pkg]
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${c.order}. ${c.name} (${c.pkg})", fontWeight = FontWeight.Bold)
                        if (inst.isNotEmpty()) Text("Installed signer: ${inst.joinToString { Play.pretty(it).take(23) + "…" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Trusted fingerprints: ${c.certs.size} (${c.certSource})", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val page = if (c.pkg == "com.google.android.gsf") Play.gsfReleaseUrl(ctx) else c.listUrl
                            OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(page)) }) { Text("Open APKMirror page") }
                            if (c.pkg == "com.google.android.gsf") OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(c.listUrl)) }) { Text("All GSF releases") }
                            OutlinedButton(enabled = busy == null, onClick = { pending = c; picker.launch(arrayOf("*/*")) }) { Text("Pick downloaded file") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(urls[c.pkg] ?: "", { urls[c.pkg] = it }, Modifier.weight(1f), singleLine = true, label = { Text("or direct https:// link") })
                            Button(enabled = busy == null && (urls[c.pkg] ?: "").startsWith("https://"), onClick = {
                                scope.launch {
                                    busy = "Downloading ${c.name}..."; progress = 0f
                                    val f = File(Store.playDir(), "${c.pkg}.src")
                                    val err = withContext(Dispatchers.IO) { Play.download(urls[c.pkg]!!, f) { done, total -> progress = if (total > 0) done.toFloat() / total else null } }
                                    if (err != null) { verif[c.pkg] = Verification(false, null, null, emptyList(), err, emptyList()); }
                                    else { busy = "Verifying..."; verif[c.pkg] = withContext(Dispatchers.IO) { Play.verify(ctx, f, c) } }
                                    busy = null; progress = null
                                }
                            }) { Text("Download") }
                        }
                        v?.let {
                            Text(
                                (if (it.ok) "✔ VERIFIED " else "✘ NOT VERIFIED ") + "${it.packageName ?: ""} ${it.version ?: ""}\n${it.message}" +
                                    (if (it.fingerprints.isNotEmpty()) "\nSigner SHA-256: ${it.fingerprints.distinct().joinToString { f -> Play.pretty(f) }}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Busy(busy)
                progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            }
            Section("4. Install (order: GSF → Play services → Play Store)") {
                val ready = Play.components.sortedBy { it.order }.filter { verif[it.pkg]?.ok == true }
                Text("Ready to install: ${ready.joinToString { it.name }.ifEmpty { "nothing verified yet" }}", style = MaterialTheme.typography.bodySmall)
                if (!Play.canInstallUnknown(ctx)) {
                    Text("This app is not yet allowed to install apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { startSafe(ctx, Play.unknownSourcesIntent(ctx)) }) { Text("Allow installs from this app") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useRoot, { useRoot = it }, enabled = AppState.rooted); Text("Install with root (`pm install -r`) instead of the system installer", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(priv, { priv = it }, enabled = AppState.unlocked); Text("ADVANCED: also copy to /system/priv-app as privileged system app (root, baseline required, reboot needed, undoable)", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = ready.isNotEmpty() && busy == null && (Play.canInstallUnknown(ctx) || useRoot), onClick = { confirmInstall = true }) { Text("Install verified packages") }
                status.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Section("5. 'Device is not certified'") {
                Text("Uncertified devices must register their Google Services Framework Android ID at google.com/android/uncertified (sign in with the Google account used on the device). Reading the ID needs root (sqlite3 query on gservices.db, with a content-provider fallback).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = AppState.rooted && busy == null, onClick = { scope.launch { busy = "Reading GSF ID..."; gsf = withContext(Dispatchers.IO) { Play.readGsfId() }; busy = null } }) { Text("Read GSF ID") }
                    OutlinedButton(onClick = { startSafe(ctx, Launchers.urlIntent(Play.UNCERTIFIED_URL)) }) { Text("Open registration page") }
                }
                gsf?.let { g ->
                    KV("android_id (decimal)", g.decimal ?: "not found")
                    KV("Hex (what Google asks for)", g.hex ?: "-")
                    if (g.hex == null) Text(g.raw, style = MaterialTheme.typography.bodySmall)
                    g.hex?.let { h -> OutlinedButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GSF ID", h)); toast(ctx, "Copied") }) { Text("Copy hex ID") } }
                    Text("The GSF database only exists after Google Services Framework has run on the device (usually after first sign-in).", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmInstall) Confirm("Install Google packages?",
        "Only packages whose signing certificate matched are installed, in order. ${if (priv) "They will ALSO be copied into /system/priv-app (needs a writable /system, then a reboot). " else ""}This may violate Google's ToS on uncertified devices and is your responsibility. On some ROMs it can cause bootloops or battery drain; make sure the baseline backup exists.",
        "Install",
        onYes = {
            confirmInstall = false
            scope.launch {
                status = emptyList()
                for (c in Play.components.sortedBy { it.order }) {
                    val v = verif[c.pkg]
                    if (v == null || !v.ok) continue
                    busy = "Installing ${c.name}..."
                    val (ok, m) = withContext(Dispatchers.IO) {
                        if (useRoot) Play.installRoot(v.files, c.pkg) else Play.installSession(ctx, v.files, c.pkg)
                    }
                    note("${if (ok) "✔" else "✘"} ${c.name}: ${m.take(300)}")
                    if (!ok) { busy = null; note("Stopped: later packages depend on this one."); return@launch }
                    if (priv) {
                        val (pok, pm) = withContext(Dispatchers.IO) { Play.installPriv(ctx, v.files.first(), c.pkg) }
                        note("${if (pok) "✔" else "✘"} privileged copy ${c.name}: ${pm.take(400)}")
                        AppState.bumpLog()
                    }
                }
                busy = null; reload++
            }
        }, onNo = { confirmInstall = false })
}
