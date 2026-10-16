package dev.optimizer.android.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/** Packages that must never be frozen / disabled / suspended by this app. */
object Protected {
    private val fixed = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.server.telecom", "com.android.shell", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.bluetooth", "com.android.nfc",
        "com.android.emergency", "com.android.cellbroadcastreceiver", "com.android.mms.service",
        "com.android.stk", "com.android.managedprovisioning", "com.android.location.fused",
        "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.webview", "com.android.webview", "com.google.android.trichrome.library",
        "com.android.inputmethod.latin", "com.google.android.inputmethod.latin",
        "com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.android.dialer",
        "com.google.android.dialer", "com.android.contacts", "com.google.android.contacts",
        "com.android.documentsui", "com.google.android.documentsui", "com.android.wallpapercropper",
        "com.android.keychain", "com.android.certinstaller", "com.android.se", "com.qualcomm.qti.services.systemhelper"
    )
    private val prefixes = listOf("com.android.providers.", "com.google.android.gms.", "com.android.internal.", "com.android.systemui.")

    fun reasons(ctx: Context, pkg: String): String? {
        if (pkg == ctx.packageName) return "This app"
        if (pkg in fixed || prefixes.any { pkg.startsWith(it) }) return "Critical system component"
        val pm = ctx.packageManager
        runCatching {
            val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            if (home?.activityInfo?.packageName == pkg) return "Current home launcher"
        }
        runCatching {
            if ((ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == pkg) return "Default phone app"
        }
        runCatching { if (Telephony.Sms.getDefaultSmsPackage(ctx) == pkg) return "Default SMS app" }
        runCatching {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (imm.enabledInputMethodList.any { it.packageName == pkg }) return "Active keyboard (input method)"
        }
        runCatching {
            val id = Resources_recentsPackage()
            if (id != null && id == pkg) return "Provides the system Recents screen"
        }
        return null
    }

    fun isProtected(ctx: Context, pkg: String) = reasons(ctx, pkg) != null

    /** Package of config_recentsComponentName (the launcher that provides Recents on Android 10+). */
    @Suppress("FunctionName")
    fun Resources_recentsPackage(): String? = runCatching {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_recentsComponentName", "string", "android")
        if (id == 0) null else android.content.ComponentName.unflattenFromString(res.getString(id))?.packageName
    }.getOrNull()

    fun sdkOk() = Build.VERSION.SDK_INT >= 26
}
