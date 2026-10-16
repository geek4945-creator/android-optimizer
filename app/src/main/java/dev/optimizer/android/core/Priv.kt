package dev.optimizer.android.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.optimizer.android.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Edition {
    /** true = rooted edition (may run su); false = standard edition (never runs su). */
    val ROOT: Boolean = BuildConfig.ROOT_EDITION
    val name: String = BuildConfig.EDITION
}

/** Shizuku user service: runs shell commands in the Shizuku process (adb shell uid 2000, or root if Shizuku was started with root). */
class ShellUserService() : IUserService.Stub() {
    @Suppress("unused") constructor(context: Context) : this()
    override fun destroy() { System.exit(0) }
    override fun exit() { System.exit(0) }
    override fun exec(command: String, timeoutSec: Int): Array<String> {
        return try {
            val p = ProcessBuilder("sh", "-c", command).start()
            val o = StringBuilder(); val e = StringBuilder()
            val t1 = Thread { runCatching { o.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
            val t2 = Thread { runCatching { e.append(p.errorStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
            val end = System.currentTimeMillis() + timeoutSec * 1000L
            var done = false
            while (System.currentTimeMillis() < end) { if (runCatching { p.exitValue(); true }.getOrDefault(false)) { done = true; break }; Thread.sleep(20) }
            if (!done) { p.destroy(); t1.join(300); t2.join(300); return arrayOf("-1", o.toString(), e.toString() + "\n[timeout]") }
            t1.join(1500); t2.join(1500)
            arrayOf(p.exitValue().toString(), o.toString(), e.toString())
        } catch (t: Throwable) { arrayOf("-2", "", "exec failed: ${t.message}") }
    }
}

/** Optional Shizuku integration. All calls are wrapped: if the API misbehaves the app just behaves as if Shizuku were absent. */
object ShizukuBridge {
    /** Compose-observable "authorised and bound" flag. */
    var ready by mutableStateOf(false)
        private set
    @Volatile var installedHint: Boolean = false
    @Volatile private var service: IUserService? = null
    @Volatile var lastError: String = ""
    @Volatile var uid: Int = -1
    @Volatile var version: Int = -1
    private var listenersAdded = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IUserService.Stub.asInterface(binder); ready = true; latch?.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { service = null; ready = false }
    }
    @Volatile private var latch: CountDownLatch? = null

    fun supported() = Build.VERSION.SDK_INT >= 23
    fun running(): Boolean = supported() && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun isPackageInstalled(ctx: Context) = listOf("moe.shizuku.privileged.api").any { runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false) }

    fun requestPermission(code: Int = 4711) { if (running()) runCatching { Shizuku.requestPermission(code) }.onFailure { lastError = it.message ?: "" } }

    /** Call at launch / on resume. Cheap when Shizuku is absent. Binds the user service when authorised. */
    fun refresh(ctx: Context) {
        if (!supported()) { ready = false; return }
        if (!listenersAdded) runCatching {
            Shizuku.addBinderReceivedListener { Thread { refresh(ctx) }.start() }
            Shizuku.addBinderDeadListener { service = null; ready = false }
            Shizuku.addRequestPermissionResultListener { _, _ -> Thread { refresh(ctx) }.start() }
            listenersAdded = true
        }
        if (!granted()) { if (!running()) { service = null; ready = false }; return }
        runCatching { uid = Shizuku.getUid(); version = Shizuku.getVersion() }
        if (service == null) bind(ctx)
        ready = service != null && granted()
    }

    @Synchronized private fun bind(ctx: Context) {
        if (service != null) return
        val l = CountDownLatch(1); latch = l
        runCatching {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellUserService::class.java.name)).daemon(false).processNameSuffix("shell").debuggable(false).version(1)
            Handler(Looper.getMainLooper()).post { runCatching { Shizuku.bindUserService(args, conn) }.onFailure { lastError = it.message ?: "bind failed"; l.countDown() } }
            l.await(6, TimeUnit.SECONDS)
        }.onFailure { lastError = it.message ?: "bind failed" }
    }

    fun exec(cmd: String, timeoutSec: Long): ShRes? {
        if (!granted()) return null
        if (service == null) bind(Store.appCtx)
        val s = service ?: return null
        return try {
            val r = s.exec(cmd, timeoutSec.toInt())
            ShRes(r[0].toIntOrNull() ?: -2, r[1], r[2])
        } catch (t: Throwable) { lastError = t.message ?: "exec failed"; service = null; ready = false; null }
    }
}

/**
 * The single command-runner abstraction used throughout the app (via Sh.root / Sh.run(root = true)):
 *   ROOT     real su (rooted edition only)
 *   SHIZUKU  shell (uid 2000) or root (if Shizuku runs as root) through the Shizuku user service
 *   NONE     plain app: `settings put/get/list` via the Settings API when WRITE_SECURE_SETTINGS was granted over adb,
 *            read-only commands (dumpsys/logcat/pm list/cat /proc ...) as the app's own uid (works for what DUMP/READ_LOGS/
 *            PACKAGE_USAGE_STATS grants allow), everything else is refused with a clear message.
 */
object Priv {
    enum class Level { ROOT, SHIZUKU, NONE }

    fun level(): Level = when {
        Edition.ROOT && Root.state == RootState.ROOTED -> Level.ROOT
        ShizukuBridge.ready -> Level.SHIZUKU
        else -> Level.NONE
    }
    val realRoot: Boolean get() = level() == Level.ROOT
    /** uid-0 capable: real root, or Shizuku started as root. Needed for /proc/sys writes, drop_caches, iptables, /system. */
    val rootLike: Boolean get() = realRoot || (ShizukuBridge.ready && ShizukuBridge.uid == 0)
    val shell: Boolean get() = level() != Level.NONE

    fun hasPerm(perm: String): Boolean = runCatching { Store.appCtx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun writeSecure() = hasPerm("android.permission.WRITE_SECURE_SETTINGS")
    fun dump() = hasPerm("android.permission.DUMP")
    fun readLogs() = hasPerm("android.permission.READ_LOGS")
    /** Can this app change system state at all (shell channel or WRITE_SECURE_SETTINGS)? */
    fun canChange(): Boolean = shell || writeSecure()

    fun levelLabel(): String = when (level()) {
        Level.ROOT -> "root (su)"
        Level.SHIZUKU -> if (ShizukuBridge.uid == 0) "Shizuku (running as root)" else "Shizuku (adb shell, uid ${ShizukuBridge.uid})"
        Level.NONE -> if (writeSecure() || dump() || readLogs()) "ADB-granted permissions only" else "none (plain app)"
    }

    private val readOnly = Regex("^\\s*(dumpsys|logcat|settings get|settings list|pm list|pm path|cmd appops get|appops get|getprop|ls |stat |id\\b|top |ps |dmesg|cat /(proc|sys|system|vendor)|df |du -|uptime|wm size|wm density)")

    fun exec(cmd: String, timeoutSec: Long): ShRes {
        when (level()) {
            Level.ROOT -> return Sh.proc("su", cmd, timeoutSec)
            Level.SHIZUKU -> ShizukuBridge.exec(cmd, timeoutSec)?.let { return it }
            Level.NONE -> {}
        }
        // legacy behaviour in the rooted edition before the first root request: try su (this is what triggers the manager prompt)
        if (Edition.ROOT && Root.state == RootState.UNKNOWN) return Sh.proc("su", cmd, timeoutSec)
        settingsShim(cmd)?.let { return it }
        if (readOnly.containsMatchIn(cmd)) return Sh.proc("sh", cmd, timeoutSec)
        return ShRes(-3, "", "needs root or Shizuku (this edition/state has no privileged channel): ${cmd.lineSequence().firstOrNull().orEmpty().take(80)}")
    }

    private fun unq(s: String): String { val t = s.trim(); return if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) t.substring(1, t.length - 1).replace("'\\''", "'") else t }

    /** `settings get|put|delete|list <ns> ...` through the Settings content provider (needs WRITE_SECURE_SETTINGS for put/delete on global/secure). */
    private fun settingsShim(script: String): ShRes? {
        val lines = script.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it != "exit" }
        if (lines.isEmpty() || !lines.all { it.startsWith("settings ") }) return null
        val cr = Store.appCtx.contentResolver
        val out = StringBuilder(); val err = StringBuilder(); var code = 0
        for (l in lines) {
            val m = Regex("^settings (get|put|delete|list) (global|secure|system)(?: (\\S+))?(?: (.*))?$").find(l) ?: return null
            val (op, ns, key, rest) = m.destructured
            val uri = when (ns) { "global" -> Settings.Global.CONTENT_URI; "secure" -> Settings.Secure.CONTENT_URI; else -> Settings.System.CONTENT_URI }
            try {
                when (op) {
                    "get" -> out.append(when (ns) { "global" -> Settings.Global.getString(cr, key); "secure" -> Settings.Secure.getString(cr, key); else -> Settings.System.getString(cr, key) } ?: "null").append('\n')
                    "list" -> cr.query(uri, arrayOf("name", "value"), null, null, "name")?.use { c -> while (c.moveToNext()) out.append(c.getString(0)).append('=').append(c.getString(1) ?: "null").append('\n') }
                    "put" -> { val v = unq(rest); val ok = when (ns) { "global" -> Settings.Global.putString(cr, key, v); "secure" -> Settings.Secure.putString(cr, key, v); else -> Settings.System.putString(cr, key, v) }; if (!ok) { code = 1; err.append("put $ns $key refused\n") } }
                    "delete" -> { val n = cr.delete(uri, "name=?", arrayOf(key)); if (n < 0) { code = 1; err.append("delete failed\n") } }
                }
            } catch (e: SecurityException) {
                code = 1; err.append("Permission denied for 'settings $op $ns $key': ${if (ns == "system") "needs the 'Modify system settings' special access (Settings > Apps > Special access)" else "grant it once from a PC: adb shell pm grant ${Store.appCtx.packageName} android.permission.WRITE_SECURE_SETTINGS"}\n")
            } catch (e: Throwable) { code = 1; err.append(e.message ?: "failed").append('\n') }
        }
        return ShRes(code, out.toString(), err.toString())
    }
}
