package dev.optimizer.android.features

import android.os.Build
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.DeviceProfile
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store

/** What the Device Profile means for the app: availability notes, defaults and low-end behaviour. */
object Features {
    fun androidName(sdk: Int) = when (sdk) { 21 -> "5.0"; 22 -> "5.1"; 23 -> "6"; 24 -> "7"; 25 -> "7.1"; 26 -> "8"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; else -> sdk.toString() }

    /** Is low-end mode in effect? Manual override first, then the profile (RAM <= 2 GB or Android's low-RAM flag), then very old Android on little RAM. */
    fun lowEnd(): Boolean = when (Store.lowEndMode) {
        1 -> true
        2 -> false
        else -> DeviceProfile.current()?.let { it.ramClass == "low" } ?: false
    }

    val monitorIntervalMs: Long get() = if (lowEnd()) 5000L else 2000L
    /** Poll interval of the keep-closed guard when no event source is available. */
    val guardPollMs: Long get() = if (lowEnd()) 20_000L else 8_000L
    val monitorHistory: Int get() = if (lowEnd()) 30 else 60
    val defaultNetIntervalSec: Int get() = if (lowEnd()) 30 else 10
    val defaultRetentionDays: Int get() = if (lowEnd()) 1 else 3

    /** Applies profile-derived defaults once (the user can change them afterwards). */
    fun applyDefaultsOnce() {
        if (Store.profileDefaultsApplied) return
        Store.netIntervalSec = defaultNetIntervalSec
        Store.netRetentionDays = defaultRetentionDays
        Store.profileDefaultsApplied = true
    }

    /** Short per-entry limitation notes shown in More. Empty = fully available. Each states why and what Android version is needed. */
    fun limits(id: String): List<String> {
        val sdk = Build.VERSION.SDK_INT
        val l = mutableListOf<String>()
        when (id) {
            "network" -> {
                if (sdk >= 29) l += "Reading /proc/net needs root on Android 10+."
                if (!Compat.hasNetPolicyCli) l += "netpolicy block needs Android 7+ (iptables only here)."
            }
            "processes" -> {
                if (sdk < 26) l += "Legacy `ps` (Android < 8): no CPU / start time."
                if (!Compat.hasAppopsCli) l += "App-op snapshot needs Android 6+."
                if (!Compat.hasRuntimePerms) l += "Runtime permissions do not exist before Android 6."
            }
            "diagnostics" -> {
                if (!Compat.hasDoze) l += "Battery-optimisation checks need Android 6+."
                if (sdk < 31) l += "Phantom-process check needs Android 12+."
            }
            "updates" -> {
                if (Compat.securityPatch() == null) l += "No security-patch level before Android 6."
                if (sdk < 26) l += "WebView provider cannot be queried before Android 8."
            }
            "play" -> {
                if (sdk < 26) l += "Uses the global 'Unknown sources' switch instead of per-app install permission."
                DeviceProfile.current()?.let { if (it.hasHms && !it.hasGms) l += "Huawei HMS device: GMS usually cannot run at all." }
            }
            "cleaner" -> {
                if (Build.VERSION.SDK_INT >= 30) l += "Android 11+: needs 'All files access' (granted by you in system settings)."
                if (!Root.isRooted) l += "Without root other apps' caches cannot be cleared (only via Android's storage screen)."
            }
            "startup" -> if (!Root.isRooted) l += "Read-only without root + baseline."
            "battery" -> if (Build.VERSION.SDK_INT < 21) l += "Limited battery properties on old Android."
            "ram" -> {
                if (!Root.isRooted) l += "Without root: only background/cached processes can be closed; no RAM per app, no kernel tuning, no drop-caches/compact."
                if (lowEnd()) l += "Low-end mode: background monitor samples every 60 s."
                if (!java.io.File("/proc/sys/vm/compact_memory").exists()) l += "Memory compaction is not exposed by this kernel."
            }
            "rules" -> {
                if (!Root.isRooted) l += "Without root: Keep closed uses killBackgroundProcesses polling (best effort); Freeze / background-restrict need root."
                if (!Compat.hasAppopsCli) l += "Background-restrict (app-ops RUN_IN_BACKGROUND) needs Android 6+."
            }
            "backups" -> {
                if (sdk < 29) l += "Export goes to app storage / root copy (no MediaStore before Android 10)."
                if (!Compat.hasRuntimePerms) l += "Runtime-permission backup needs Android 6+."
            }
        }
        DeviceProfile.current()?.let { p ->
            if (p.isTv && id in setOf("launchers")) l += "Android TV: launcher/default-home settings may not exist."
        }
        return l
    }

    /** Notes for the Optimize tab. */
    fun optimizeNotes(): List<String> {
        val l = mutableListOf<String>()
        if (lowEnd()) l += "Low-end mode: heavy dexopt/compile jobs are disabled; monitor refresh is slower."
        if (!Compat.hasDoze) l += "Doze whitelist needs Android 6+."
        if (!Compat.hasCmd) l += "This Android has no `cmd` service tool (Android < 7): package maintenance commands are unavailable."
        return l
    }

    /** Root guidance line adapted to device. */
    fun rootHint(): String {
        val p = DeviceProfile.current() ?: return ""
        return when {
            Root.isRooted -> "Root is active."
            p.suVisible -> "A su binary is present but access is not granted to this app: open your root manager and allow it."
            else -> "No su found. Use the Root setup helper on the Root tab."
        }
    }
}
