package dev.optimizer.android.features

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.RootState
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import java.io.File
import java.security.MessageDigest

/** One line of evidence. pass: true = found / ok, false = not found, null = informational or not readable by an app. */
data class Evidence(val name: String, val pass: Boolean?, val detail: String)

enum class RootVerdict(val title: String) {
    GRANTED("Root detected and granted"),
    DENIED("Root found but access denied / not granted to this app"),
    BROKEN("Root binary found but not working"),
    NONE("No root found"),
}

data class ManagerApp(val pkg: String, val label: String, val version: String?, val kind: String, val heuristic: Boolean = false)

data class RootReport(val verdict: RootVerdict, val evidence: List<Evidence>, val managers: List<ManagerApp>, val managerVersion: String?, val suOutput: String, val at: Long) {
    fun text(): String = buildString {
        append("Android Optimizer - root detection report\n")
        append("Time: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))}\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}\n")
        append("Result: ${verdict.title}\n")
        managerVersion?.let { append("Root manager / version: $it\n") }
        append("\nEvidence:\n")
        evidence.forEach { append("[${when (it.pass) { true -> "PASS"; false -> "FAIL"; null -> "INFO" }}] ${it.name}: ${it.detail}\n") }
        if (managers.isNotEmpty()) { append("\nRoot manager apps:\n"); managers.forEach { append("- ${it.label} (${it.pkg}) ${it.version ?: ""} ${if (it.heuristic) "[heuristic: renamed/stub?]" else ""}\n") } }
        if (suOutput.isNotBlank()) append("\nsu output:\n${suOutput.take(2000)}\n")
    }
}

/** Root detection (O) and the helpers for the Root setup guide (L). No exploits, no bundled binaries. */
object RootDetect {
    private val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/vendor/bin/su", "/product/bin/su", "/system/sbin/su", "/su/bin/su", "/system/su")
    private val dirPaths = listOf("/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap", "/data/adb/modules", "/debug_ramdisk", "/sbin/.magisk")
    private val knownManagers = listOf(
        Triple("com.topjohnwu.magisk", "Magisk", "Magisk"), Triple("io.github.vvb2060.magisk", "Magisk (Alpha/Delta)", "Magisk"),
        Triple("io.github.huskydg.magisk", "Magisk Kitsune", "Magisk"), Triple("me.weishu.kernelsu", "KernelSU", "KernelSU"),
        Triple("me.bmax.apatch", "APatch", "APatch"), Triple("com.rifsxd.ksunext", "KernelSU Next", "KernelSU"),
        Triple("eu.chainfire.supersu", "SuperSU", "SuperSU"), Triple("com.koushikdutta.superuser", "Superuser (ClockworkMod)", "Superuser"),
        Triple("com.noshufou.android.su", "Superuser (ChainsDD)", "Superuser"), Triple("com.thirdparty.superuser", "Superuser (3rd party)", "Superuser"),
        Triple("com.yellowes.su", "Superuser (yellowes)", "Superuser"), Triple("me.phh.superuser", "phh Superuser", "Superuser"),
    )

    fun installedManagers(ctx: Context): List<ManagerApp> {
        val pm = ctx.packageManager
        val out = mutableListOf<ManagerApp>()
        for ((pkg, label, kind) in knownManagers) runCatching { out += ManagerApp(pkg, label, pm.getPackageInfo(pkg, 0).versionName, kind) }
        // Renamed / hidden stubs: a user app (not a known manager) whose component class names mention a root manager.
        runCatching {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
            @Suppress("DEPRECATION")
            for (pi in pm.getInstalledPackages(flags)) {
                val ai = pi.applicationInfo ?: continue
                if ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 || out.any { it.pkg == pi.packageName } || pi.packageName == ctx.packageName) continue
                val names = (pi.activities?.map { it.name }.orEmpty() + pi.services?.map { it.name }.orEmpty() + pi.providers?.map { it.name }.orEmpty() + pi.receivers?.map { it.name }.orEmpty()).map { it.lowercase() }
                val hit = listOf("magisk", "kernelsu", "apatch").firstOrNull { k -> names.any { it.contains(k) } }
                if (hit != null) out += ManagerApp(pi.packageName, pm.getApplicationLabel(ai).toString(), pi.versionName, hit.replaceFirstChar { it.uppercase() }, heuristic = true)
            }
        }
        return out
    }

    fun openManager(ctx: Context, m: ManagerApp): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(m.pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Runs all checks plus exactly one su invocation. Blocking: call from a worker thread. */
    fun detect(ctx: Context): RootReport {
        val ev = mutableListOf<Evidence>()
        // 1. su binaries in common paths (apps often cannot see /data/adb or /debug_ramdisk because of SELinux: INFO, not proof of absence)
        val found = suPaths.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        ev += Evidence("su binary in common paths", found.isNotEmpty(), if (found.isEmpty()) "none of ${suPaths.size} paths visible" else found.joinToString())
        for (d in dirPaths) {
            val f = File(d); val ex = runCatching { f.exists() }.getOrDefault(false)
            val readable = runCatching { f.canRead() }.getOrDefault(false)
            ev += Evidence(d, if (ex) true else null, if (ex) "exists" else if (!readable) "not visible to apps (normal; SELinux hides it, absence is not proof)" else "absent")
        }
        // 2. which su + PATH
        val which = Sh.user("which su 2>/dev/null").out.trim()
        val pathHit = (System.getenv("PATH") ?: "").split(":").filter { it.isNotEmpty() && File(it, "su").exists() }
        ev += Evidence("`which su` / PATH", which.isNotEmpty() || pathHit.isNotEmpty(), (which.ifEmpty { null } ?: pathHit.firstOrNull()?.let { "$it/su" }) ?: "su not on PATH")
        // 3. root manager apps
        val managers = installedManagers(ctx)
        ev += Evidence("Root manager apps installed", managers.isNotEmpty(), if (managers.isEmpty()) "none (Magisk, KernelSU, APatch, SuperSU, renamed stubs)" else managers.joinToString { "${it.label}${it.version?.let { v -> " $v" } ?: ""}${if (it.heuristic) " (heuristic)" else ""}" })
        // 4. build tags + debuggable
        val tags = Build.TAGS ?: ""
        ev += Evidence("Build tags", if (tags.contains("test-keys")) true else null, "ro.build.tags=$tags" + if (tags.contains("test-keys")) " (test-keys: custom/dev build, root often easy but not proof)" else " (release-keys)")
        val dbg = Sh.user("getprop ro.debuggable").out.trim(); val sec = Sh.user("getprop ro.secure").out.trim()
        ev += Evidence("ro.debuggable / ro.secure", if (dbg == "1" || sec == "0") true else null, "ro.debuggable=${dbg.ifEmpty { "?" }}, ro.secure=${sec.ifEmpty { "?" }}" + if (dbg == "1" || sec == "0") " (userdebug/eng-style build: `adb root` may work)" else "")
        // 5. writable /system hints
        val mounts = runCatching { File("/proc/mounts").readText() }.getOrDefault("")
        val sysLine = mounts.lineSequence().firstOrNull { l -> l.split(" ").getOrNull(1) == "/system" } ?: mounts.lineSequence().firstOrNull { l -> l.split(" ").getOrNull(1) == "/" }
        val rw = sysLine?.split(" ")?.getOrNull(3)?.split(",")?.contains("rw") == true
        ev += Evidence("/system mounted read-write", if (rw) true else if (sysLine == null) null else false, sysLine?.take(120) ?: "mount table not readable")
        val rootMount = mounts.lineSequence().firstOrNull { l -> Regex("(?i)magisk|ksu|kernelsu|apatch").containsMatchIn(l) }
        ev += Evidence("Root-related mounts in /proc/mounts", if (rootMount != null) true else null, rootMount?.take(120) ?: "none visible to this app")
        // 6. the one su invocation: id + manager versions in a single call (at most one grant prompt)
        val script = "id\n" +
            "echo '##magisk'; magisk -v 2>&1; magisk -V 2>&1\n" +
            "echo '##ksu'; ksud -V 2>&1; /data/adb/ksud -V 2>&1\n" +
            "echo '##apatch'; /data/adb/apd -V 2>&1; apd -V 2>&1\n" +
            "echo '##su'; su -v 2>&1; which su 2>&1\n"
        val pr = Root.probe(script)
        if (pr.state == RootState.ROOTED) Store.rootGrantedBefore = true
        val sections = pr.out.split(Regex("(?m)^##")).drop(1).associate { it.lineSequence().first().trim() to it.substringAfter('\n', "").trim() }
        val idLine = pr.out.lineSequence().firstOrNull { it.contains("uid=") }
        ev += Evidence("`su -c id` (single invocation)", pr.state == RootState.ROOTED, when (pr.state) {
            RootState.ROOTED -> idLine ?: "uid=0"
            RootState.NO_SU -> "could not execute su: ${pr.out.take(120)}"
            else -> "exit ${pr.code}: ${pr.out.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: "(no output)"}"
        })
        var mgrVersion: String? = null
        if (pr.state == RootState.ROOTED) {
            val bad = Regex("(?i)not found|no such file|inaccessible|permission denied|unknown option|usage")
            fun clean(s: String?) = s?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() && !bad.containsMatchIn(it) }?.firstOrNull()
            val mg = clean(sections["magisk"])
            val mgc = sections["magisk"]?.lineSequence()?.map { it.trim() }?.firstOrNull { it.matches(Regex("\\d{4,}")) }
            val ks = clean(sections["ksu"]); val ap = clean(sections["apatch"]); val suv = clean(sections["su"])
            mgrVersion = when {
                mg != null -> "Magisk $mg${mgc?.let { " ($it)" } ?: ""}"
                ks != null -> "KernelSU $ks"
                ap != null -> "APatch $ap"
                suv != null -> "su: $suv"
                else -> managers.firstOrNull()?.let { "${it.label} ${it.version ?: ""}".trim() + " (app version)" }
            }
            ev += Evidence("Root manager / version", mgrVersion != null, mgrVersion ?: "su works but no manager CLI answered")
        }
        // verdict
        val suVisible = found.isNotEmpty() || which.isNotEmpty() || pathHit.isNotEmpty()
        val denyWords = Regex("(?i)denied|not allowed|refused|timeout|prompt|permission")
        val brokenWords = Regex("(?i)not found|inconsistent|exec format|cannot execute|no such")
        val verdict = when {
            pr.state == RootState.ROOTED -> RootVerdict.GRANTED
            pr.state == RootState.NO_SU && !suVisible -> RootVerdict.NONE
            pr.state == RootState.NO_SU -> RootVerdict.BROKEN
            pr.code == -1 || denyWords.containsMatchIn(pr.out) || (managers.isNotEmpty() && pr.out.isBlank()) -> RootVerdict.DENIED
            suVisible || managers.isNotEmpty() -> if (brokenWords.containsMatchIn(pr.out) || (pr.out.isNotBlank() && !pr.out.contains("uid="))) RootVerdict.BROKEN else RootVerdict.DENIED
            else -> RootVerdict.NONE
        }
        return RootReport(verdict, ev, managers, mgrVersion, pr.out, System.currentTimeMillis())
    }

    // -------------------------------------------------------------------- guide data (L)
    data class BootInfo(val rows: List<Pair<String, String>>, val slotSuffix: String?, val abUpdate: Boolean, val bootTarget: String, val locked: String, val oemUnlock: String, val brandKey: String, val vendorNotes: List<String>, val socFamily: String)

    private fun prop(n: String) = Sh.user("getprop $n").out.trim()

    fun bootInfo(ctx: Context): BootInfo {
        val slot = prop("ro.boot.slot_suffix").ifEmpty { null }
        val ab = prop("ro.build.ab_update") == "true" || slot != null
        val firstApi = prop("ro.product.first_api_level").toIntOrNull()
        val kernel = Sh.user("uname -r").out.trim().ifEmpty { System.getProperty("os.version") ?: "unknown" }
        val bootTarget = when {
            firstApi != null && firstApi >= 33 -> "init_boot (device launched with Android 13+; Magisk is patched into init_boot - confirm in your firmware package)"
            Build.VERSION.SDK_INT >= 33 && ab -> "init_boot or boot (if your firmware package contains init_boot.img use that)"
            else -> "boot (ramdisk lives in boot.img; on some non-A/B devices it is recovery)"
        }
        val flashLocked = prop("ro.boot.flash.locked"); val vb = prop("ro.boot.verifiedbootstate"); val vbm = prop("ro.boot.vbmeta.device_state")
        val locked = when {
            flashLocked == "0" || vb == "orange" || vbm == "unlocked" -> "UNLOCKED (flash.locked=${flashLocked.ifEmpty { "-" }}, verifiedbootstate=${vb.ifEmpty { "-" }}, vbmeta=${vbm.ifEmpty { "-" }})"
            flashLocked == "1" || vb == "green" || vbm == "locked" -> "LOCKED (flash.locked=${flashLocked.ifEmpty { "-" }}, verifiedbootstate=${vb.ifEmpty { "-" }}, vbmeta=${vbm.ifEmpty { "-" }})"
            else -> "unknown (properties not exposed by this ROM)"
        }
        val oemProp = listOf("sys.oem_unlock_allowed", "persist.sys.oem_unlock_allowed", "ro.oem_unlock_supported").map { it to prop(it) }
        val oemSetting = runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, "oem_unlock_allowed") }.getOrNull()
        val oem = buildString {
            append(oemSetting?.let { "OEM unlocking toggle: ${if (it == 1) "ON" else "OFF"}" } ?: "OEM unlocking toggle: not readable by apps - check Developer options")
            oemProp.filter { it.second.isNotEmpty() }.forEach { append("; ${it.first}=${it.second}") }
        }
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        val soc = dev.optimizer.android.core.DeviceProfile.socFamily(Build.HARDWARE, Build.BOARD)
        val key = when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> "xiaomi"
            brand.contains("huawei") || brand.contains("honor") -> "huawei"
            brand.contains("samsung") -> "samsung"
            brand.contains("google") -> "pixel"
            brand.contains("oneplus") -> "oneplus"
            brand.contains("motorola") || brand.contains("lenovo") -> "motorola"
            brand.contains("oppo") || brand.contains("realme") -> "oppo"
            brand.contains("sony") -> "sony"
            soc == "MediaTek" || soc == "Rockchip" || soc == "Allwinner" -> "soc-tablet"
            else -> "generic"
        }
        val notes = mutableListOf<String>()
        when (key) {
            "xiaomi" -> notes += "Xiaomi/Redmi/POCO: the bootloader is unlocked only with the official Mi Unlock tool (Windows) using a Xiaomi account bound to the phone in Developer options > Mi Unlock status; Xiaomi enforces a waiting period (commonly 168 hours or more) and may restrict unlocking on newer HyperOS builds or some regions. See https://en.miui.com/unlock/ . Unofficial unlock tools are unsafe."
            "huawei" -> notes += "Huawei/Honor: official bootloader unlock codes were discontinued in 2018, so most devices cannot be unlocked through legitimate channels. Paid third-party code services exist; this app does not recommend or link them."
            "samsung" -> notes += "Samsung: uses Odin/Download mode instead of fastboot. Unlocking (Developer options > OEM unlocking, then confirm in Download mode) wipes data and permanently trips Knox (some Samsung Pay / Secure Folder features stop working). Patch the AP tar's boot/init_boot with Magisk and flash it with Odin. US carrier models are often not unlockable."
            "pixel" -> notes += "Google Pixel: standard fastboot flow (`fastboot flashing unlock`); use the factory image of your exact build to get boot.img / init_boot.img."
            "oneplus" -> notes += "OnePlus: standard fastboot unlock on most models; get the payload.bin of your build and extract boot / init_boot with a payload dumper."
            "motorola" -> notes += "Motorola/Lenovo: unlock key requested from Motorola's website after `fastboot oem get_unlock_data`."
            "oppo" -> notes += "OPPO/Realme: unlocking often needs the vendor's 'In-depth test' / unlock app and an approval period; some regions and carriers have it removed."
            "sony" -> notes += "Sony: unlock code from Sony's developer site; unlocking removes DRM keys (camera quality can degrade)."
            "soc-tablet" -> notes += "$soc tablet/box (${Build.MANUFACTURER} ${Build.MODEL}): many of these have no fastboot unlock at all. Flashing is usually done in the vendor download mode with: " +
                (when (soc) { "MediaTek" -> "SP Flash Tool (scatter file for your exact firmware; read back and patch only the boot partition, keep a full backup)"; "Rockchip" -> "rkdeveloptool or Rockchip's RKDevTool (loader + boot image; https://github.com/rockchip-linux/rkdeveloptool)"; else -> "PhoenixSuit / LiveSuit / PhoenixCard (Allwinner .img firmware)" }) +
                ". Some firmwares ship with su already or allow `adb root` (userdebug) - check before flashing. A wrong flash can hard-brick; keep the stock firmware package."
            else -> notes += "Check your maker's own unlock policy; many carrier-locked and some regional models cannot be unlocked at all."
        }
        if (soc == "MediaTek" && key != "soc-tablet") notes += "MediaTek SoC: boot images are often patched via the vendor's flash tool (SP Flash Tool / mtkclient) when fastboot is restricted."
        val rows = listOf(
            "Android / ABI" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}",
            "Bootloader" to locked, "OEM unlock" to oem,
            "A/B slots" to if (ab) "yes, current slot ${slot ?: "unknown"} (ro.build.ab_update=${prop("ro.build.ab_update").ifEmpty { "-" }}, virtual A/B: ${prop("ro.virtual_ab.enabled").ifEmpty { "-" }})" else "no (non-A/B device)",
            "Patch target" to bootTarget, "First API level" to (firstApi?.toString() ?: "unknown"), "Kernel" to kernel,
            "Build" to Build.FINGERPRINT, "SoC family" to soc, "Vendor group" to key,
        )
        return BootInfo(rows, slot, ab, bootTarget, locked, oem, key, notes, soc)
    }

    /** Commands for the detected device, as plain text for the Copy button. Every destructive step is labelled. */
    fun commands(b: BootInfo): String {
        val part = if (b.bootTarget.startsWith("init_boot")) "init_boot" else "boot"
        val slotPart = if (b.slotSuffix != null) "$part${b.slotSuffix}" else part
        val sb = StringBuilder()
        sb.append("# Android Optimizer - root guide commands for ${Build.MANUFACTURER} ${Build.MODEL} (${Build.FINGERPRINT})\n")
        sb.append("# Read the warnings in the app first. Run on a PC with Android platform-tools: https://developer.android.com/tools/releases/platform-tools\n\n")
        if (b.brandKey == "samsung") {
            sb.append("# Samsung: no fastboot. Unlock via Developer options > OEM unlocking, then confirm the unlock in Download mode (WIPES DATA, trips Knox).\n")
            sb.append("# Take the AP_*.tar.md5 of your exact firmware, extract $part.img.lz4 / boot.img.lz4, patch it in the Magisk app (Install > Select and Patch a File),\n")
            sb.append("# re-pack the patched image into a tar and flash it as AP with Odin in Download mode. See https://topjohnwu.github.io/Magisk/install.html\n")
            return sb.toString()
        }
        if (b.brandKey == "soc-tablet") {
            sb.append("# ${b.socFamily} device: use the vendor download-mode tool for your SoC (see notes in the app). Typical Rockchip example:\n")
            sb.append("rkdeveloptool ld                          # list devices in Loader/Maskrom mode\n")
            sb.append("rkdeveloptool rl 0 <sectors> backup.img   # BACK UP before writing anything (offsets come from your firmware's parameter file)\n")
            sb.append("rkdeveloptool wlx boot magisk_patched.img # write the patched boot partition by name\n")
            sb.append("rkdeveloptool rd                          # reboot\n\n")
        }
        sb.append("# 1. On the phone: Settings > About phone > tap Build number 7 times; Developer options > OEM unlocking ON; USB debugging ON\n")
        sb.append("adb devices                       # confirm the device is listed and authorised\n")
        sb.append("adb reboot bootloader\n")
        sb.append("fastboot getvar current-slot      # detected on this phone: ${b.slotSuffix ?: "(non-A/B)"}\n")
        sb.append("fastboot getvar unlocked\n\n")
        sb.append("# 2. UNLOCK - THIS WIPES ALL DATA AND MAY VOID THE WARRANTY. Skip if the bootloader is already unlocked (${b.locked.substringBefore(' ')}).\n")
        sb.append("fastboot flashing unlock          # older devices: fastboot oem unlock\n")
        sb.append("# (confirm on the phone with the volume/power keys; the phone factory-resets and reboots)\n\n")
        sb.append("# 3. Get the STOCK $part.img of build ${Build.DISPLAY} / ${Build.FINGERPRINT}\n")
        sb.append("#    from your maker's firmware package (payload.bin needs a payload dumper), copy it to the phone, then in the Magisk app:\n")
        sb.append("#    Install > Select and Patch a File > $part.img   -> creates /sdcard/Download/magisk_patched-<random>.img\n")
        sb.append("adb pull /sdcard/Download/magisk_patched-XXXXX.img magisk_patched.img   # replace XXXXX with the real name\n\n")
        sb.append("# 4. Flash the patched image\n")
        sb.append("adb reboot bootloader\n")
        sb.append("fastboot flash $slotPart magisk_patched.img\n")
        if (b.slotSuffix != null) sb.append("# ('$slotPart' targets the current slot ${b.slotSuffix}; use the _a / _b suffix of the other slot if you need it)\n")
        sb.append("# Only if your device's maker/Magisk guide says so (otherwise do NOT run it, a wrong vbmeta can bootloop):\n")
        sb.append("# fastboot flash vbmeta --disable-verity --disable-verification vbmeta.img    (use YOUR stock vbmeta.img)\n")
        sb.append("fastboot reboot\n\n")
        sb.append("# 5. Open the Magisk app (it may ask to finish setup / reboot), then use 'Verify root' in Android Optimizer.\n")
        sb.append("# Recovery if it bootloops: fastboot flash $slotPart <stock $part.img>; fastboot reboot\n")
        return sb.toString()
    }

    // -------------------------------------------------------------------- advanced: install a user-provided su binary (L4)
    data class SuFile(val file: File, val sha256: String, val size: Long, val elfArch: String?, val problems: List<String>)

    fun inspectSuFile(src: File): SuFile {
        val problems = mutableListOf<String>()
        val head = ByteArray(64)
        val n = runCatching { src.inputStream().use { i -> var o = 0; while (o < 64) { val r = i.read(head, o, 64 - o); if (r < 0) break; o += r }; o } }.getOrDefault(0)
        val isElf = n >= 20 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        if (!isElf) problems += "Not an ELF executable (missing 7f 45 4c 46 header)."
        val machine = if (isElf) ((head[19].toInt() and 0xff) shl 8) or (head[18].toInt() and 0xff) else 0
        val arch = if (isElf) when (machine) { 0xB7 -> "arm64-v8a"; 0x28 -> "armeabi-v7a"; 0x3E -> "x86_64"; 0x03 -> "x86"; else -> "unknown (e_machine $machine)" } else null
        if (arch != null && !arch.startsWith("unknown") && Build.SUPPORTED_ABIS.none { it == arch || (arch == "armeabi-v7a" && it.startsWith("armeabi")) }) problems += "ELF architecture $arch is not among this device's ABIs (${Build.SUPPORTED_ABIS.joinToString()})."
        if (src.length() > 20L * 1024 * 1024) problems += "File is larger than 20 MB - unusual for an su binary."
        val md = MessageDigest.getInstance("SHA-256")
        src.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val r = i.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
        return SuFile(src, md.digest().joinToString("") { "%02x".format(it) }, src.length(), arch, problems)
    }

    /**
     * Copies the user's su binary into /system/xbin or /system/bin. Needs an existing root shell + baseline. Refuses to overwrite an existing su.
     * Undo (logged): remount rw, delete the file, remount ro.
     */
    fun installSu(su: SuFile, target: String): Pair<Boolean, String> {
        if (target != "/system/xbin/su" && target != "/system/bin/su") return false to "Invalid target."
        if (!Root.isRooted) return false to "Needs an existing root shell (adb root userdebug/eng build or another root)."
        if (!Backups.hasBaseline()) return false to "Create the baseline backup first."
        if (su.problems.any { it.startsWith("Not an ELF") }) return false to "Refused: ${su.problems.first()}"
        val dir = target.substringBeforeLast('/')
        if (Sh.root("test -e $target && echo EXISTS").out.contains("EXISTS")) return false to "$target already exists - refusing to overwrite it."
        val tmp = "/data/local/tmp/aopt-su.bin"
        val stage = Sh.root("cp ${Sh.q(su.file.absolutePath)} $tmp && chmod 755 $tmp && (sha256sum $tmp 2>/dev/null || toybox sha256sum $tmp 2>/dev/null)", 60)
        if (stage.out.isBlank() && !stage.ok) return false to "Staging failed: ${stage.text}"
        Regex("[0-9a-f]{64}").find(stage.out)?.value?.let { if (it != su.sha256) { Sh.root("rm -f $tmp"); return false to "Checksum changed during staging - aborted." } }
        val remountRw = "(mount -o rw,remount /system || mount -o rw,remount / || mount -o remount,rw /system) 2>&1"
        val remountRo = "(mount -o ro,remount /system || mount -o ro,remount /) >/dev/null 2>&1"
        val rw = Sh.root(remountRw, 30)
        if (!Sh.root("touch $dir/.aopt_probe && rm $dir/.aopt_probe && echo OK", 15).out.contains("OK")) {
            Sh.root("rm -f $tmp")
            return false to "Cannot make /system writable (${rw.text.take(160)}). This device uses a read-only / dynamic system partition (normal on Android 10+); nothing was changed."
        }
        val r = Sh.root("cp $tmp $target && chown 0:0 $target && chmod 06755 $target && (chcon u:object_r:system_file:s0 $target 2>/dev/null; true) && ls -l $target", 60)
        Sh.root("rm -f $tmp")
        if (!r.ok) { Sh.root("rm -f $target; $remountRo"); return false to "Install failed and was cleaned up: ${r.text}" }
        Sh.root("sync; $remountRo")
        ChangeLog.add("root", "su:install", "Installed su binary at $target", "User-provided file, sha256 ${su.sha256}, mode 06755 root:root, context system_file. SELinux policy for su is NOT handled by this app.",
            listOf("($remountRw); rm -f $target; sync; $remountRo"))
        return true to "Installed $target (sha256 ${su.sha256.take(16)}...). ${r.out.trim()}\nThe file only works as su if your kernel/SELinux policy allows it; verify with 'Verify root'."
    }
}
