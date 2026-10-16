package dev.optimizer.android.features

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

data class PlayComponent(
    val pkg: String, val name: String, val order: Int, val listUrl: String, val certs: Set<String>, val certSource: String,
)

data class Verification(
    val ok: Boolean, val packageName: String?, val version: String?, val fingerprints: List<String>,
    val message: String, val files: List<File>,
)

data class PlayDetect(
    val vending: String?, val gms: String?, val gsf: String?, val abis: List<String>, val sdk: Int, val release: String,
    val dpi: Int, val dpiBucket: String, val vendor: VendorInfo, val hasHms: Boolean,
    val gsfRelease: String, val explanation: List<String>, val warnings: List<String>,
)

object Play {
    private fun norm(s: String) = s.replace(":", "").uppercase()

    /**
     * Google signing-certificate SHA-256 fingerprints.
     * - com.android.vending: published by Google at https://play.google.com/.well-known/assetlinks.json (fetched and read live).
     * - com.google.android.gms: all six published by Google at https://www.google.com/.well-known/assetlinks.json (fetched live).
     * - com.google.android.gsf: not published in assetlinks. F0FD... is confirmed by a public APK analysis of a GSF build (Pithus report) and
     *   F0FD.../7CE8... by GrapheneOS AppVerifier discussion. Treated as "community-verified" - both are Google's own platform keys.
     * Anything else is REFUSED; the app shows the fingerprint instead.
     */
    private val F0FD = norm("F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83")
    private val C7CE8 = norm("7C:E8:3C:1B:71:F3:D5:72:FE:D0:4C:8D:40:C5:CB:10:FF:75:E6:D8:7D:9D:F6:FB:D5:3F:04:68:C2:90:50:53")

    val components = listOf(
        PlayComponent("com.google.android.gsf", "Google Services Framework", 1,
            "https://www.apkmirror.com/apk/google-inc/google-services-framework/", setOf(F0FD, C7CE8),
            "community-verified (Pithus APK report + GrapheneOS AppVerifier thread)"),
        PlayComponent("com.google.android.gms", "Google Play services", 2,
            "https://www.apkmirror.com/apk/google-inc/google-play-services/",
            setOf(F0FD, norm("19:75:B2:F1:71:77:BC:89:A5:DF:F3:1F:9E:64:A6:CA:E2:81:A5:3D:C1:D1:D5:9B:1D:14:7F:E1:C8:2A:FA:00"), C7CE8,
                norm("D2:2C:C5:00:29:9F:B2:28:73:A0:1A:01:0D:E1:C8:2F:BE:4D:06:11:19:B9:48:14:DD:30:1D:AB:50:CB:76:78"),
                norm("5F:23:91:27:7B:1D:BD:48:90:00:46:7E:4C:2F:A6:AF:80:24:30:08:04:57:DC:E2:F6:18:99:2E:9D:FB:54:02"),
                norm("8F:D4:A0:E7:B8:EB:7C:72:6D:2D:AC:79:44:5B:2C:79:31:F6:2B:7E:B6:92:F5:04:4B:DC:FD:FA:AD:07:0A:36")),
            "Google assetlinks.json for com.google.android.gms (google.com/.well-known)"),
        PlayComponent("com.android.vending", "Google Play Store", 3,
            "https://www.apkmirror.com/apk/google-inc/google-play-store/", setOf(F0FD),
            "Google assetlinks.json for com.android.vending (play.google.com/.well-known)"),
    )

    fun component(pkg: String) = components.first { it.pkg == pkg }

    const val UNCERTIFIED_URL = "https://www.google.com/android/uncertified/"
    const val GSF_REL_URL = "https://www.apkmirror.com/apk/google-inc/google-services-framework/google-services-framework-%s-release/"

    fun installedVersion(ctx: Context, pkg: String): String? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
    }.getOrNull()

    fun installedCertSha(ctx: Context, pkg: String): List<String> = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        sha256s(pi)
    }.getOrDefault(emptyList())

    private fun sha256s(pi: PackageInfo): List<String> {
        val si = pi.signingInfo ?: return emptyList()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray() ?: si.apkContentsSigners
        return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } }
    }

    fun pretty(fp: String) = fp.chunked(2).joinToString(":")

    private fun dpiBucket(dpi: Int): String {
        val b = listOf(120, 160, 213, 240, 320, 480, 640)
        return b.minByOrNull { kotlin.math.abs(it - dpi) }!!.toString() + "dpi"
    }

    fun detect(ctx: Context): PlayDetect {
        val abis = Build.SUPPORTED_ABIS.toList()
        val dpi = ctx.resources.displayMetrics.densityDpi
        val vendor = Vendor.detect()
        val hms = Launchers.Installed.has(ctx, "com.huawei.hwid") || Launchers.Installed.has(ctx, "com.huawei.hms")
        val gsfRel = Build.VERSION.RELEASE.substringBefore('.')
        val ex = mutableListOf<String>()
        val primary = abis.firstOrNull { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") } ?: abis.firstOrNull() ?: "?"
        ex += "Architecture: this device prefers $primary (all: ${abis.joinToString()}). Pick Google Play services builds labelled $primary" +
            (if (primary == "arm64-v8a" && "armeabi-v7a" in abis) " (or a combined arm64-v8a + armeabi-v7a build)" else "") + "; never a different CPU family."
        ex += "Screen density: ${dpi} dpi -> nearest bucket ${dpiBucket(dpi)}. Pick the matching DPI variant if the page offers several; 'nodpi' builds work on every density."
        ex += "Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). Choose the newest release whose 'Min: Android' is at or below your version."
        ex += "Google Services Framework is versioned per Android release: choose the 'Google Services Framework $gsfRel' release (APKMirror: google-services-framework-$gsfRel-release), architecture 'noarch'."
        ex += "Google Play Store is distributed as a universal (noarch / nodpi) APK on APKMirror; install the newest build whose minimum Android version fits."
        ex += "Install order: 1) Google Services Framework, 2) Google Play services, 3) Google Play Store (Play Store depends on the other two)."
        val w = mutableListOf<String>()
        if (VendorGroup.HUAWEI in vendor.groups || hms) w += "Huawei/HMS device: recent Huawei phones ship without Google Mobile Services and many do not work with GMS even after installing these packages. Success is not expected."
        if (VendorGroup.XIAOMI in vendor.groups || VendorGroup.OPPO in vendor.groups || VendorGroup.VIVO in vendor.groups)
            w += "Chinese-market ROM detected (${vendor.rom}): GMS usually needs the privileged install mode and may still be killed by the vendor's battery manager (see Optimize tab)."
        w += "Uncertified devices show 'Device is not certified' in Play Store; register the GSF Android ID (button below)."
        w += "Downloading Google apps from third-party mirrors may conflict with Google's Terms of Service and your ROM's/regional law; use is at your own risk. Nothing is bundled; every download is started by you."
        return PlayDetect(
            installedVersion(ctx, "com.android.vending"), installedVersion(ctx, "com.google.android.gms"), installedVersion(ctx, "com.google.android.gsf"),
            abis, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, dpi, dpiBucket(dpi), vendor, hms, gsfRel, ex, w
        )
    }

    fun gsfReleaseUrl(ctx: Context): String = GSF_REL_URL.format(Build.VERSION.RELEASE.substringBefore('.'))

    // ------------------------------------------------------------------ verification
    /** Verifies a file (APK or zip bundle of APKs): signature validity, package name, signer SHA-256 allow-list. */
    fun verify(ctx: Context, file: File, comp: PlayComponent): Verification {
        val work = File(Store.playDir(), comp.pkg).also { it.deleteRecursively(); it.mkdirs() }
        val apks = mutableListOf<File>()
        val isBundle = runCatching {
            ZipFile(file).use { z -> z.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains('/') } }
        }.getOrDefault(false)
        if (isBundle) {
            runCatching {
                ZipFile(file).use { z ->
                    z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains('/') }.forEach { e ->
                        val out = File(work, File(e.name).name)
                        z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                        apks += out
                    }
                }
            }
        } else {
            val out = File(work, "base.apk"); file.copyTo(out, true); apks += out
        }
        if (apks.isEmpty()) return Verification(false, null, null, emptyList(), "Not a valid APK or bundle.", emptyList())
        val fps = mutableListOf<String>()
        var pkgName: String? = null; var ver: String? = null
        for (a in apks) {
            val pi = try {
                ctx.packageManager.getPackageArchiveInfo(a.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } catch (e: Throwable) { null }
            if (pi == null) return Verification(false, null, null, fps, "${a.name}: cannot parse or its signature is invalid.", apks)
            val s = sha256s(pi)
            if (s.isEmpty()) return Verification(false, pi.packageName, pi.versionName, fps, "${a.name}: no signing certificate found.", apks)
            fps += s
            if (pkgName == null) { pkgName = pi.packageName; ver = pi.versionName }
            if (pi.packageName != pkgName) return Verification(false, pkgName, ver, fps, "Bundle mixes different packages.", apks)
        }
        if (pkgName != comp.pkg) return Verification(false, pkgName, ver, fps, "Wrong package: expected ${comp.pkg}, got $pkgName.", apks)
        val bad = fps.filter { it !in comp.certs }
        return if (bad.isEmpty()) Verification(true, pkgName, ver, fps, "Signing certificate matches a known Google certificate (${comp.certSource}).", apks)
        else Verification(false, pkgName, ver, fps,
            "REFUSED: signing certificate is not in the verified Google list for ${comp.pkg}. Fingerprint(s): ${bad.joinToString { pretty(it) }}. " +
                "Compare it yourself with Google's published value before ever installing manually.", apks)
    }

    // ------------------------------------------------------------------ download (user initiated, https only)
    fun download(url: String, dest: File, progress: (Long, Long) -> Unit): String? {
        return try {
            val u = URL(url)
            if (u.protocol != "https") return "Only HTTPS downloads are allowed."
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "AndroidOptimizer/1.0")
            if (c.responseCode !in 200..299) return "HTTP ${c.responseCode}"
            if (c.url.protocol != "https") return "Redirected to a non-HTTPS URL; refused."
            val total = c.contentLengthLong
            dest.parentFile?.mkdirs()
            c.inputStream.use { i -> dest.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(done, total) }
            } }
            null
        } catch (e: Throwable) { "Download failed: ${e.message}" }
    }

    // ------------------------------------------------------------------ install: PackageInstaller
    fun canInstallUnknown(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()
    fun unknownSourcesIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val ACTION = "dev.optimizer.android.INSTALL_RESULT"

    suspend fun installSession(ctx: Context, files: List<File>, pkg: String): Pair<Boolean, String> {
        val pi = ctx.packageManager.packageInstaller
        val done = CompletableDeferred<Pair<Boolean, String>>()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { c.startActivity(confirm) }.onFailure { done.complete(false to "Could not open the install confirmation: ${it.message}") }
                    }
                    PackageInstaller.STATUS_SUCCESS -> done.complete(true to "Installed $pkg")
                    else -> done.complete(false to "Install failed ($st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                }
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(rcv, filter)
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(pkg)
            val sid = pi.createSession(params)
            pi.openSession(sid).use { s ->
                for (f in files) {
                    f.inputStream().use { i -> s.openWrite(f.name, 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                }
                val intent = Intent(ACTION).setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, sid, intent, flags).intentSender)
            }
            return withTimeoutOrNull(5 * 60_000L) { done.await() } ?: (false to "Timed out waiting for the installer (did you dismiss the confirmation?)")
        } catch (e: Throwable) {
            return false to "Install error: ${e.message}"
        } finally { runCatching { ctx.unregisterReceiver(rcv) } }
    }

    // ------------------------------------------------------------------ install: root pm install
    fun installRoot(files: List<File>, pkg: String): Pair<Boolean, String> {
        val tmp = "/data/local/tmp/aopt-$pkg"
        Sh.root("rm -rf $tmp; mkdir -p $tmp; chmod 755 $tmp")
        val names = files.map { f ->
            val t = "$tmp/${f.name}"
            val r = Sh.root("cp ${Sh.q(f.absolutePath)} ${Sh.q(t)} && chmod 644 ${Sh.q(t)}", 120)
            if (!r.ok) return false to "Copy failed: ${r.text}"
            t
        }
        val cmd = if (names.size == 1) "pm install -r ${Sh.q(names[0])}" else "pm install-multiple -r ${names.joinToString(" ") { Sh.q(it) }}"
        val r = Sh.root(cmd, 300)
        Sh.root("rm -rf $tmp")
        return (r.ok && r.text.contains("Success")) to r.text.ifEmpty { "no output" }
    }

    // ------------------------------------------------------------------ install: privileged system app (advanced, root)
    private fun privDirName(pkg: String) = when (pkg) {
        "com.google.android.gms" -> "GmsCore"; "com.google.android.gsf" -> "GoogleServicesFramework"; else -> "Phonesky"
    }

    /** Generates privapp-permissions XML from the APK's requested permissions that are privileged on this device. */
    private fun privappXml(ctx: Context, apk: File, pkg: String): String {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_PERMISSIONS)
        val privileged = (pi?.requestedPermissions ?: emptyArray()).filter { p ->
            runCatching {
                val info = pm.getPermissionInfo(p, 0)
                (info.protectionLevel and PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0
            }.getOrDefault(false)
        }.distinct().sorted()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<permissions>\n    <privapp-permissions package=\"$pkg\">\n")
            privileged.forEach { append("        <permission name=\"$it\"/>\n") }
            append("    </privapp-permissions>\n</permissions>\n")
        }
    }

    /**
     * Copies a verified APK to /system/priv-app (root only). Undoable (removes the files) and logged. Requires a reboot to take effect.
     * Many devices have a read-only/dynamic /system where remount fails; in that case nothing is changed.
     */
    fun installPriv(ctx: Context, apk: File, pkg: String): Pair<Boolean, String> {
        if (!Root.isRooted) return false to "Root required."
        if (!Backups.hasBaseline()) return false to "Create the baseline backup first."
        val sysApp = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        }.getOrDefault(false)
        if (sysApp) return false to "$pkg is already a system app; update it with the normal install instead."
        val dir = privDirName(pkg)
        val target = "/system/priv-app/$dir"
        val xml = "/system/etc/permissions/privapp-permissions-aopt-$pkg.xml"
        val tmpApk = "/data/local/tmp/aopt-priv-$pkg.apk"
        val tmpXml = "/data/local/tmp/aopt-priv-$pkg.xml"
        File(Store.playDir(), "$pkg.privapp.xml").writeText(privappXml(ctx, apk, pkg))
        val c1 = Sh.root("cp ${Sh.q(apk.absolutePath)} $tmpApk && cp ${Sh.q(File(Store.playDir(), "$pkg.privapp.xml").absolutePath)} $tmpXml && chmod 644 $tmpApk $tmpXml", 120)
        if (!c1.ok) return false to "Staging failed: ${c1.text}"

        val remountRw = "(mount -o rw,remount /system || mount -o rw,remount / || mount -o remount,rw /system) 2>&1"
        val remountRo = "(mount -o ro,remount /system || mount -o ro,remount /) >/dev/null 2>&1"
        val rw = Sh.root(remountRw, 30)
        // verify writable
        val probe = Sh.root("touch /system/priv-app/.aopt_probe && rm /system/priv-app/.aopt_probe && echo OK", 15)
        if (!probe.out.contains("OK")) {
            Sh.root("rm -f $tmpApk $tmpXml")
            return false to "Cannot make /system writable (${rw.text.take(200)}). This device uses a read-only/dynamic system partition; nothing was changed. A systemless Magisk module is the usual alternative (not provided by this app)."
        }
        val cmds = """
            mkdir -p $target &&
            cp $tmpApk $target/$dir.apk &&
            chown root:root $target $target/$dir.apk &&
            chmod 755 $target && chmod 644 $target/$dir.apk &&
            cp $tmpXml $xml && chown root:root $xml && chmod 644 $xml &&
            (chcon u:object_r:system_file:s0 $target $target/$dir.apk $xml 2>/dev/null; true)
        """.trimIndent().replace("\n", " ")
        val r = Sh.root(cmds, 120)
        Sh.root("rm -f $tmpApk $tmpXml")
        if (!r.ok) {
            Sh.root("rm -rf $target $xml; $remountRo")
            return false to "Copy to /system failed and was cleaned up: ${r.text}"
        }
        Sh.root("sync; $remountRo")
        val undo = "($remountRw); rm -rf $target $xml; sync; $remountRo"
        ChangeLog.add("play", "play:priv:$pkg", "Privileged install of ${component(pkg).name}",
            "Copied to $target + $xml. Reboot required. Undo removes the files (reboot again).", listOf(undo))
        return true to "Copied to $target with privapp-permissions XML. REBOOT the device to activate. Undo is available in the change log."
    }

    // ------------------------------------------------------------------ GSF Android ID
    data class GsfId(val decimal: String?, val hex: String?, val raw: String)

    fun readGsfId(): GsfId {
        if (!Root.isRooted) return GsfId(null, null, "Root required to read the GSF database.")
        val q = "select * from main where name='android_id'"
        val db = "/data/data/com.google.android.gsf/databases/gservices.db"
        var r = Sh.root("sqlite3 $db \"$q\"")
        var raw = r.text
        var dec = Regex("android_id\\|(\\d+)").find(r.out)?.groupValues?.get(1)
        if (dec == null) {
            // fallback if the sqlite3 binary is missing: query the GSF content provider as root
            val r2 = Sh.root("content query --uri content://com.google.android.gsf.gservices --where \"name='android_id'\"")
            raw += "\n" + r2.text
            dec = Regex("value=(\\d+)").find(r2.out)?.groupValues?.get(1)
        }
        val hex = (dec?.toLongOrNull() ?: dec?.toULongOrNull()?.toLong())?.let { "%016x".format(it) }
        return GsfId(dec, hex, raw)
    }
}
