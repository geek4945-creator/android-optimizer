package dev.optimizer.android.core

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import dev.optimizer.android.features.Monitor
import dev.optimizer.android.features.Play
import dev.optimizer.android.features.Vendor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Snapshot of what this device can do. Produced by [DeviceProfile.scan] on first run, after an OS update (fingerprint or patch changed)
 * and on demand. Stored as JSON in SharedPreferences, shown in the Assess tab, included in backup manifests, and used by [Adapt].
 */
data class DeviceProfile(
    val scannedAt: Long, val fingerprint: String, val securityPatch: String,
    val release: String, val sdk: Int, val abis: List<String>,
    val ramMb: Long, val memoryClassMb: Int, val lowRamFlag: Boolean, val ramClass: String,
    val storageTotalMb: Long, val storageFreeMb: Long,
    val widthDp: Int, val heightDp: Int, val densityDpi: Int, val sizeClass: String, val isTablet: Boolean, val isTv: Boolean, val hasTouch: Boolean,
    val manufacturer: String, val brand: String, val model: String, val hardware: String, val socFamily: String, val rom: String, val vendorGroups: List<String>,
    val suVisible: Boolean, val rootStateName: String, val rootGrantedBefore: Boolean,
    val hasPlayStore: Boolean, val hasGms: Boolean, val hasGsf: Boolean, val hasHms: Boolean,
    val thermalApi: Boolean, val thermalStatus: Int?, val thermalZones: Int, val batteryTempC: Float?, val sensors: Int, val sensorNames: List<String>,
    val selinux: String, val bootloaderUnlockedHint: String,
    val usageAccess: Boolean, val notifications: Boolean, val batteryExempt: Boolean?, val installUnknown: Boolean,
    val features: Map<String, Boolean>,
) {
    val is64: Boolean get() = abis.firstOrNull()?.contains("64") == true

    fun toJson(): JSONObject = JSONObject()
        .put("scannedAt", scannedAt).put("fingerprint", fingerprint).put("securityPatch", securityPatch)
        .put("release", release).put("sdk", sdk).put("abis", JSONArray(abis))
        .put("ramMb", ramMb).put("memoryClassMb", memoryClassMb).put("lowRamFlag", lowRamFlag).put("ramClass", ramClass)
        .put("storageTotalMb", storageTotalMb).put("storageFreeMb", storageFreeMb)
        .put("widthDp", widthDp).put("heightDp", heightDp).put("densityDpi", densityDpi).put("sizeClass", sizeClass)
        .put("isTablet", isTablet).put("isTv", isTv).put("hasTouch", hasTouch)
        .put("manufacturer", manufacturer).put("brand", brand).put("model", model).put("hardware", hardware).put("socFamily", socFamily)
        .put("rom", rom).put("vendorGroups", JSONArray(vendorGroups))
        .put("suVisible", suVisible).put("rootState", rootStateName).put("rootGrantedBefore", rootGrantedBefore)
        .put("hasPlayStore", hasPlayStore).put("hasGms", hasGms).put("hasGsf", hasGsf).put("hasHms", hasHms)
        .put("thermalApi", thermalApi).put("thermalStatus", thermalStatus ?: JSONObject.NULL).put("thermalZones", thermalZones)
        .put("batteryTempC", batteryTempC?.toDouble() ?: JSONObject.NULL).put("sensors", sensors).put("sensorNames", JSONArray(sensorNames))
        .put("selinux", selinux).put("bootloaderUnlockedHint", bootloaderUnlockedHint)
        .put("usageAccess", usageAccess).put("notifications", notifications).put("batteryExempt", batteryExempt ?: JSONObject.NULL).put("installUnknown", installUnknown)
        .put("features", JSONObject().also { o -> features.forEach { (k, v) -> o.put(k, v) } })

    /** Human-readable rows for the Assess tab / text export. */
    fun rows(): List<Pair<String, String>> = listOf(
        "Android" to "$release (API $sdk)", "Security patch" to securityPatch.ifEmpty { "unknown" }, "ABI" to abis.joinToString(", ") + if (is64) " (64-bit)" else " (32-bit)",
        "RAM" to "$ramMb MB, class: $ramClass (app heap class ${memoryClassMb} MB${if (lowRamFlag) ", Android low-RAM device flag set" else ""})",
        "Storage" to String.format(java.util.Locale.US, "%.1f GB free of %.1f GB", storageFreeMb / 1024.0, storageTotalMb / 1024.0),
        "Screen" to "${widthDp}x${heightDp} dp @ ${densityDpi} dpi, $sizeClass${if (isTablet) ", tablet" else ""}${if (isTv) ", TV" else ""}${if (!hasTouch) ", no touchscreen" else ""}",
        "Vendor / ROM" to "$manufacturer $model - $rom (${vendorGroups.joinToString()})", "SoC family" to "$socFamily (hardware: $hardware)",
        "Root" to "su binary visible: ${if (suVisible) "yes" else "no"}; state: $rootStateName",
        "Google Play" to "Store ${yn(hasPlayStore)}, Play services ${yn(hasGms)}, GSF ${yn(hasGsf)}, Huawei HMS ${yn(hasHms)}",
        "Thermal" to (if (thermalApi) "PowerManager thermal status ${thermalStatus ?: "n/a"}" else "no thermal API (needs Android 10)") + ", $thermalZones thermal zones readable, battery ${batteryTempC?.let { "$it °C" } ?: "n/a"}",
        "Sensors" to "$sensors (${sensorNames.take(6).joinToString()})",
        "SELinux" to selinux, "Bootloader hint" to bootloaderUnlockedHint,
        "Usage Access" to yn(usageAccess), "Notifications" to yn(notifications), "Battery optimisation exempt" to (batteryExempt?.let { yn(it) } ?: "n/a (Android < 6)"), "Install unknown apps" to yn(installUnknown),
        "Scanned" to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(scannedAt)),
    )
    private fun yn(b: Boolean) = if (b) "yes" else "no"

    companion object {
        const val WIZARD_VERSION = 1
        @Volatile private var cached: DeviceProfile? = null

        fun fromJson(j: JSONObject): DeviceProfile {
            fun sl(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val f = j.optJSONObject("features")
            return DeviceProfile(
                j.optLong("scannedAt"), j.optString("fingerprint"), j.optString("securityPatch"), j.optString("release"), j.optInt("sdk"), sl("abis"),
                j.optLong("ramMb"), j.optInt("memoryClassMb"), j.optBoolean("lowRamFlag"), j.optString("ramClass"), j.optLong("storageTotalMb"), j.optLong("storageFreeMb"),
                j.optInt("widthDp"), j.optInt("heightDp"), j.optInt("densityDpi"), j.optString("sizeClass"), j.optBoolean("isTablet"), j.optBoolean("isTv"), j.optBoolean("hasTouch", true),
                j.optString("manufacturer"), j.optString("brand"), j.optString("model"), j.optString("hardware"), j.optString("socFamily"), j.optString("rom"), sl("vendorGroups"),
                j.optBoolean("suVisible"), j.optString("rootState"), j.optBoolean("rootGrantedBefore"),
                j.optBoolean("hasPlayStore"), j.optBoolean("hasGms"), j.optBoolean("hasGsf"), j.optBoolean("hasHms"),
                j.optBoolean("thermalApi"), if (j.isNull("thermalStatus")) null else j.optInt("thermalStatus"), j.optInt("thermalZones"),
                if (j.isNull("batteryTempC")) null else j.optDouble("batteryTempC").toFloat(), j.optInt("sensors"), sl("sensorNames"),
                j.optString("selinux"), j.optString("bootloaderUnlockedHint"),
                j.optBoolean("usageAccess"), j.optBoolean("notifications"), if (j.isNull("batteryExempt")) null else j.optBoolean("batteryExempt"), j.optBoolean("installUnknown"),
                f?.keys()?.asSequence()?.associateWith { f.optBoolean(it) } ?: emptyMap(),
            )
        }

        /** Saved profile (memory-cached), or null if no scan has happened yet. */
        fun current(): DeviceProfile? {
            cached?.let { return it }
            val s = runCatching { Store.profileJson }.getOrDefault("")
            if (s.isEmpty()) return null
            return runCatching { fromJson(JSONObject(s)) }.getOrNull()?.also { cached = it }
        }

        /** True when the OS build or patch level differs from the saved profile (OS update / ROM flash), or there is no profile. */
        fun needsRescan(): Boolean {
            val c = current() ?: return true
            return c.fingerprint != Build.FINGERPRINT || c.securityPatch != (Compat.securityPatch() ?: "")
        }

        fun socFamily(hw: String, board: String): String {
            val h = "$hw $board".lowercase()
            return when {
                h.contains("mt") && Regex("mt\\d{3,4}|mediatek").containsMatchIn(h) -> "MediaTek"
                h.contains("rk") && Regex("rk\\d{3,4}|rockchip").containsMatchIn(h) -> "Rockchip"
                h.contains("sun") && Regex("sun\\d+i|allwinner|\\bsun\\d").containsMatchIn(h) || h.contains("allwinner") || h.contains("exdroid") -> "Allwinner"
                h.contains("qcom") || h.contains("msm") || h.contains("sdm") || h.contains("sm8") || h.contains("kona") || h.contains("lahaina") -> "Qualcomm"
                h.contains("exynos") || h.contains("universal") || h.contains("samsungexynos") -> "Samsung Exynos"
                h.contains("kirin") || h.contains("hi3") || h.contains("hi6") -> "HiSilicon Kirin"
                h.contains("unisoc") || h.contains("sc98") || h.contains("sprd") || h.contains("ums") -> "Unisoc / Spreadtrum"
                h.contains("tensor") || h.contains("gs101") || h.contains("zuma") -> "Google Tensor"
                h.contains("intel") || h.contains("x86") -> "Intel x86"
                else -> "unknown"
            }
        }

        /** Runs the scan (a second or two; call off the main thread), saves and caches the result. Does NOT request root. */
        fun scan(ctx: Context): DeviceProfile {
            val app = ctx.applicationContext
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val ramMb = mi.totalMem / (1024 * 1024)
            val lowFlag = am.isLowRamDevice
            val ramClass = when { lowFlag || ramMb <= 2048 -> "low"; ramMb <= 4608 -> "mid"; else -> "high" }
            val sf = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val total = sf?.let { it.blockCountLong * it.blockSizeLong / (1024 * 1024) } ?: 0L
            val free = sf?.let { it.availableBlocksLong * it.blockSizeLong / (1024 * 1024) } ?: 0L
            val cfg = app.resources.configuration
            val dm = app.resources.displayMetrics
            val wDp = cfg.screenWidthDp; val hDp = cfg.screenHeightDp
            val smallest = minOf(wDp, hDp)
            val ui = (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            val pm = app.packageManager
            val isTv = ui == Configuration.UI_MODE_TYPE_TELEVISION || pm.hasSystemFeature("android.software.leanback")
            val sizeClass = when { wDp < 600 -> "compact (phone)"; wDp < 840 -> "medium (large phone / small tablet)"; else -> "expanded (tablet / TV)" }
            val vendor = runCatching { Vendor.detect() }.getOrNull()
            fun installed(p: String) = runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false)
            val sensors = runCatching { (app.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL) }.getOrNull().orEmpty()
            val thermalStatus = if (Build.VERSION.SDK_INT >= 29) runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrNull() else null
            val zones = runCatching { File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.count { File(it, "temp").canRead() } ?: 0 }.getOrDefault(0)
            val batt = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) }.getOrNull()
            val se = Sh.user("getenforce").out.trim().ifEmpty { "unknown (not readable)" }
            val locked = Sh.user("getprop ro.boot.flash.locked").out.trim()
            val vb = Sh.user("getprop ro.boot.verifiedbootstate").out.trim()
            val blHint = when { locked == "0" || vb == "orange" -> "unlocked (flash.locked=$locked, verifiedbootstate=$vb)"; locked == "1" || vb == "green" -> "locked (flash.locked=$locked, verifiedbootstate=$vb)"; else -> "unknown (flash.locked=${locked.ifEmpty { "-" }}, verifiedbootstate=${vb.ifEmpty { "-" }})" }
            val exempt = if (Build.VERSION.SDK_INT >= 23) runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName) }.getOrNull() else null
            val feats = linkedMapOf(
                "cmd_binary" to Compat.hasCmd, "appops_cli" to Compat.hasAppopsCli, "runtime_permissions" to Compat.hasRuntimePerms, "doze" to Compat.hasDoze,
                "netpolicy_cli" to Compat.hasNetPolicyCli, "device_config" to Compat.hasDeviceConfig, "notification_permission" to (Build.VERSION.SDK_INT >= 33),
                "notification_channels" to (Build.VERSION.SDK_INT >= 26), "scoped_storage" to (Build.VERSION.SDK_INT >= 29), "install_source_info" to (Build.VERSION.SDK_INT >= 30),
                "fgs_types" to (Build.VERSION.SDK_INT >= 29), "modern_ps" to (Build.VERSION.SDK_INT >= 26), "phantom_killer" to (Build.VERSION.SDK_INT >= 31),
            )
            val p = DeviceProfile(
                System.currentTimeMillis(), Build.FINGERPRINT, Compat.securityPatch() ?: "", Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                ramMb, am.memoryClass, lowFlag, ramClass, total, free,
                wDp, hDp, dm.densityDpi, sizeClass, smallest >= 600, isTv, pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
                Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.HARDWARE, socFamily(Build.HARDWARE, Build.BOARD), vendor?.rom ?: "unknown", vendor?.groups?.map { it.label } ?: emptyList(),
                Root.suBinaryVisible(), Root.state.name, Store.rootGrantedBefore,
                installed("com.android.vending"), installed("com.google.android.gms"), installed("com.google.android.gsf"), installed("com.huawei.hwid") || installed("com.huawei.appmarket"),
                Build.VERSION.SDK_INT >= 29, thermalStatus, zones, batt?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }, sensors.size, sensors.map { it.name }.distinct().take(12),
                se, blHint,
                runCatching { Monitor.hasUsageAccess(app) }.getOrDefault(false), runCatching { NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrDefault(false),
                exempt, runCatching { Play.canInstallUnknown(app) }.getOrDefault(false), feats,
            )
            Store.profileJson = p.toJson().toString()
            cached = p
            return p
        }

        /** Only the permission fields change often; recompute them without a full scan. */
        fun livePermissions(ctx: Context): Map<String, Boolean?> {
            val app = ctx.applicationContext
            return linkedMapOf(
                "Usage Access" to runCatching { Monitor.hasUsageAccess(app) }.getOrDefault(false),
                "Notifications" to runCatching { NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrDefault(false),
                "Battery optimisation exempt" to (if (Build.VERSION.SDK_INT >= 23) runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName) }.getOrNull() else null),
                "Root granted" to Root.isRooted,
            )
        }
    }
}
