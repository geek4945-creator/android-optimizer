package dev.optimizer.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/** Central place for API-level guards, so every newer API has one tested-by-reading fallback. */
object Compat {
    val sdk: Int get() = Build.VERSION.SDK_INT
    fun immutableFlag(): Int = if (sdk >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
    fun atLeast(api: Int) = Build.VERSION.SDK_INT >= api

    /** Security patch string (yyyy-MM-dd) or null before Android 6, where Build.VERSION.SECURITY_PATCH does not exist. */
    fun securityPatch(): String? = if (sdk >= 23) runCatching { Build.VERSION.SECURITY_PATCH }.getOrNull()?.takeIf { it.isNotBlank() } else null

    /** Age of the security patch in days, null when unknown. SimpleDateFormat, not java.time (needs API 26 / desugaring). */
    fun patchAgeDays(): Long? = runCatching {
        val p = securityPatch() ?: return null
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(p) ?: return null
        (System.currentTimeMillis() - d.time) / 86_400_000L
    }.getOrNull()

    /** Creates the notification channel on Android 8+, no-op before. importance: 2 = LOW, 3 = DEFAULT. */
    fun channel(ctx: Context, id: String, name: String, importance: Int = 2) {
        if (sdk >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    /** NotificationCompat works from API 14; the channel id is ignored before Android 8. */
    fun builder(ctx: Context, channelId: String): NotificationCompat.Builder = NotificationCompat.Builder(ctx, channelId)

    /** startForegroundService exists from API 26; before that a plain startService is the way. */
    fun startFgService(ctx: Context, i: Intent): Boolean = runCatching { ContextCompat.startForegroundService(ctx, i); true }.getOrDefault(false)

    /**
     * startForeground with the right type for the platform: targetSdk 34 on Android 14+ must pass a type that matches the manifest
     * (specialUse here). Before 34 the two-argument call is used. Returns false if Android refused (e.g. started from background on 12+).
     */
    fun startForegroundSafe(s: Service, id: Int, n: android.app.Notification): Boolean = try {
        if (sdk >= 34) s.startForeground(id, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else s.startForeground(id, n)
        true
    } catch (e: Throwable) { false }

    /** Signing certificates of a PackageInfo obtained with [signatureFlags]. API 28+ uses SigningInfo, older uses the deprecated array. */
    @Suppress("DEPRECATION")
    fun signatureFlags(): Int = if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun signers(pi: PackageInfo): List<Signature> {
        if (sdk >= 28) {
            val si = pi.signingInfo ?: return emptyList()
            return (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.toList() ?: emptyList()
        }
        return pi.signatures?.toList() ?: emptyList()
    }

    @Suppress("DEPRECATION")
    fun versionCode(pi: PackageInfo): Long = if (sdk >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /** `cmd appops` only exists from Android 7; Android 6 has the standalone `appops` command. Not available below 6. */
    val appopsCmd: String get() = if (sdk >= 24) "cmd appops" else "appops"
    val hasAppopsCli: Boolean get() = sdk >= 23
    /** Runtime permissions (pm grant / revoke, dumpsys "runtime permissions") exist from Android 6. */
    val hasRuntimePerms: Boolean get() = sdk >= 23
    /** Doze / deviceidle exists from Android 6. */
    val hasDoze: Boolean get() = sdk >= 23
    /** `cmd` binary with services (package, netpolicy, deviceidle...) exists from Android 7. */
    val hasCmd: Boolean get() = sdk >= 24
    val hasNetPolicyCli: Boolean get() = sdk >= 24
    val hasDeviceConfig: Boolean get() = sdk >= 29
    /** Usage stats: UsageStatsManager exists from 21, getSystemService("usagestats") works there too. */
    fun fmt(f: String, vararg a: Any): String = String.format(Locale.US, f, *a)
}
