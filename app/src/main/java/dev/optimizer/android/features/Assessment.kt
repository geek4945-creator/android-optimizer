package dev.optimizer.android.features

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import java.io.File
import java.text.DateFormat
import java.util.Date

data class Section(val title: String, val rows: List<Pair<String, String>>)

data class AppAssessment(
    val pkg: String, val label: String, val version: String, val targetSdk: Int, val installTime: Long,
    val sizeBytes: Long, val system: Boolean, val dangerous: List<String>, val flags: List<String>,
) {
    val risky: Boolean get() = flags.isNotEmpty()
}

object Assessment {
    const val RISKY_PERM_THRESHOLD = 6
    const val OLD_TARGET_SDK = 29

    fun fmtBytes(b: Long): String {
        if (b <= 0) return "n/a"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, u[i])
    }

    private fun prop(name: String): String = Sh.user("getprop $name").out.trim()

    private fun readFile(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    private fun freq(cpu: Int, kind: String): String? {
        val p = "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_${kind}_freq"
        val v = readFile(p) ?: if (Root.isRooted) Sh.root("cat $p").out.trim().ifEmpty { null } else null
        return v?.toLongOrNull()?.let { "${it / 1000} MHz" }
    }

    private fun gpuRenderer(): String? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
        val cfgAttr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, n, 0)
        if (n[0] == 0) return null
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val r = GLES20.glGetString(GLES20.GL_RENDERER)
        val v = GLES20.glGetString(GLES20.GL_VENDOR)
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx); EGL14.eglTerminate(dpy)
        if (r == null) null else "$r ($v)"
    }.getOrNull()

    fun hardware(ctx: Context): Section {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Manufacturer" to Build.MANUFACTURER
        rows += "Brand / Model" to "${Build.BRAND} / ${Build.MODEL}"
        rows += "Device / Board" to "${Build.DEVICE} / ${Build.BOARD}"
        val soc = buildString {
            if (Build.VERSION.SDK_INT >= 31) append("${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim())
            if (isBlank() || contains("unknown", true)) {
                val hw = readFile("/proc/cpuinfo")?.lines()?.firstOrNull { it.startsWith("Hardware") }?.substringAfter(":")?.trim()
                clear()
                append(hw ?: prop("ro.board.platform").ifEmpty { Build.HARDWARE })
            }
        }
        rows += "SoC" to soc
        val cores = Runtime.getRuntime().availableProcessors()
        rows += "CPU cores" to cores.toString()
        rows += "ABIs" to Build.SUPPORTED_ABIS.joinToString()
        val freqs = (0 until cores).map { c ->
            val mx = freq(c, "max"); val mn = freq(c, "min")
            if (mx == null && mn == null) null else "cpu$c: ${mn ?: "?"} - ${mx ?: "?"}"
        }
        rows += "CPU freq (min-max)" to (if (freqs.all { it == null }) "unreadable without root (Android restricts /sys)" else freqs.filterNotNull().joinToString("\n"))
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        rows += "Total RAM" to fmtBytes(mi.totalMem)
        val st = StatFs(Environment.getDataDirectory().path)
        rows += "Internal storage" to "${fmtBytes(st.totalBytes)} total, ${fmtBytes(st.availableBytes)} free"
        val dm = ctx.resources.displayMetrics
        rows += "Display" to "${dm.widthPixels}x${dm.heightPixels} px, ${dm.densityDpi} dpi (density ${dm.density})"
        runCatching {
            @Suppress("DEPRECATION")
            val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            rows += "Refresh rate" to String.format("%.0f Hz", d.refreshRate)
        }
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            rows += "Battery level" to "${if (lvl >= 0) lvl * 100 / sc else -1}%"
            rows += "Battery health" to when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                else -> "Unknown"
            }
            rows += "Battery temperature" to "${bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} °C"
            rows += "Battery status" to when (bi.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            rows += "Battery voltage" to "${bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV"
        }
        rows += "GPU renderer" to (gpuRenderer() ?: "not obtainable")
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        rows += "Sensors (${sensors.size})" to sensors.joinToString("\n") { "${it.name} (${it.vendor})" }
        return Section("Hardware", rows)
    }

    fun software(): Section {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        rows += "Security patch" to (dev.optimizer.android.core.Compat.securityPatch() ?: "unknown (Android 5 has no patch level)")
        val kernel = Sh.user("uname -r").out.trim().ifEmpty { System.getProperty("os.version") ?: "unknown" }
        rows += "Kernel" to kernel
        rows += "Build fingerprint" to Build.FINGERPRINT
        rows += "Build display id" to Build.DISPLAY
        rows += "Bootloader" to Build.BOOTLOADER
        val se = Sh.user("getenforce").out.trim().ifEmpty { readFile("/sys/fs/selinux/enforce")?.let { if (it == "1") "Enforcing" else "Permissive" } ?: "unreadable" }
        rows += "SELinux" to se
        val vb = prop("ro.boot.verifiedbootstate").ifEmpty { "unreadable" }
        rows += "Verified boot state" to vb
        val locked = prop("ro.boot.flash.locked").ifEmpty { prop("ro.boot.vbmeta.device_state") }
        rows += "Bootloader lock" to (if (locked.isEmpty()) "unreadable" else if (locked == "1") "locked" else if (locked == "0") "unlocked" else locked)
        rows += "Build type / tags" to "${Build.TYPE} / ${Build.TAGS}"
        return Section("Software", rows)
    }

    fun apps(ctx: Context): List<AppAssessment> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val list: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getInstalledPackages(flags)
        val dangerCache = HashMap<String, Boolean>()
        fun isDangerous(p: String): Boolean = dangerCache.getOrPut(p) {
            runCatching {
                val pi = pm.getPermissionInfo(p, 0)
                (pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
        return list.map { pi ->
            val ai = pi.applicationInfo
            val sys = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val perms = pi.requestedPermissions ?: emptyArray()
            val pf = pi.requestedPermissionsFlags ?: IntArray(0)
            val dangerous = perms.indices.filter { i -> i < pf.size && (pf[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 && isDangerous(perms[i]) }
                .map { perms[it].substringAfterLast('.') }
            val size = ai?.let { a ->
                var s = File(a.sourceDir).length()
                a.splitSourceDirs?.forEach { s += File(it).length() }
                s
            } ?: 0L
            val fl = mutableListOf<String>()
            if (dangerous.size >= RISKY_PERM_THRESHOLD) fl += "${dangerous.size} dangerous permissions granted"
            val t = ai?.targetSdkVersion ?: 0
            if (t in 1 until OLD_TARGET_SDK) fl += "old targetSdk $t (<$OLD_TARGET_SDK)"
            val vname = pi.versionName ?: "?"
            val vcode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            AppAssessment(
                pi.packageName, ai?.let { pm.getApplicationLabel(it).toString() } ?: pi.packageName,
                "$vname ($vcode)", t, pi.firstInstallTime, size, sys, dangerous.sorted(), fl
            )
        }.sortedWith(compareByDescending<AppAssessment> { it.flags.size }.thenByDescending { it.dangerous.size }.thenBy { it.label.lowercase() })
    }

    fun report(ctx: Context, hw: Section, sw: Section, apps: List<AppAssessment>): String {
        val sb = StringBuilder()
        val df = DateFormat.getDateTimeInstance()
        sb.append("Android Optimizer - device assessment\nGenerated: ${df.format(Date())}\nRoot: ${if (Root.isRooted) "yes" else "no / not granted"}\n\n")
        for (s in listOf(hw, sw)) {
            sb.append("== ${s.title} ==\n")
            s.rows.forEach { (k, v) -> sb.append("$k: ${v.replace("\n", "; ")}\n") }
            sb.append("\n")
        }
        dev.optimizer.android.core.DeviceProfile.current()?.let { pr ->
            sb.append("== Device profile ==\n"); pr.rows().forEach { (k, v) -> sb.append("$k: $v\n") }; sb.append("\n")
        }
        val user = apps.count { !it.system }
        sb.append("== Applications ==\nUser apps: $user\nSystem apps: ${apps.size - user}\nFlagged: ${apps.count { it.risky }}\n")
        sb.append("Flag rules: >= $RISKY_PERM_THRESHOLD granted dangerous permissions, or targetSdk < $OLD_TARGET_SDK. Size = APK size on disk.\n\n")
        apps.forEach {
            sb.append("${it.label} [${it.pkg}] v${it.version} target=${it.targetSdk} installed=${df.format(Date(it.installTime))} size=${fmtBytes(it.sizeBytes)} ${if (it.system) "SYSTEM" else "USER"}\n")
            if (it.dangerous.isNotEmpty()) sb.append("   dangerous: ${it.dangerous.joinToString()}\n")
            if (it.flags.isNotEmpty()) sb.append("   FLAGS: ${it.flags.joinToString("; ")}\n")
        }
        return sb.toString()
    }
}
