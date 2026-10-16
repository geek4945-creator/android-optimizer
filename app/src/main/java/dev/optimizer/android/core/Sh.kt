package dev.optimizer.android.core

import java.io.File
import kotlin.concurrent.thread

/** Result of a shell command. */
data class ShRes(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
    val text: String get() = (out + (if (err.isNotBlank()) "\n" + err else "")).trim()
}

/** Minimal shell runner (ProcessBuilder). Root commands go through `su`. Never throws. */
/** Process.waitFor(timeout) and destroyForcibly are API 26+; this polls exitValue() so it works on Android 5+. */
internal fun Process.waitForCompat(timeoutSec: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutSec * 1000
    while (true) {
        try { exitValue(); return true } catch (_: IllegalThreadStateException) { }
        if (System.currentTimeMillis() >= end) return false
        try { Thread.sleep(40) } catch (_: InterruptedException) { return false }
    }
}
internal fun Process.killCompat() { runCatching { destroy() } }

object Sh {
    /** root=true means "privileged": routed through [Priv] (su in the rooted edition, Shizuku, or safe non-root fallbacks). */
    fun run(cmd: String, root: Boolean = false, timeoutSec: Long = 30): ShRes =
        if (root) Priv.exec(cmd, timeoutSec) else proc("sh", cmd, timeoutSec)

    internal fun proc(shell: String, cmd: String, timeoutSec: Long): ShRes {
        return try {
            val pb = ProcessBuilder(shell)
            val p = pb.start()
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val t1 = thread(isDaemon = true) { runCatching { p.inputStream.bufferedReader().use { r -> outSb.append(r.readText()) } } }
            val t2 = thread(isDaemon = true) { runCatching { p.errorStream.bufferedReader().use { r -> errSb.append(r.readText()) } } }
            runCatching {
                p.outputStream.bufferedWriter().use { w ->
                    w.write(cmd)
                    w.write("\nexit\n")
                    w.flush()
                }
            }
            val finished = p.waitForCompat(timeoutSec)
            if (!finished) {
                p.killCompat()
                t1.join(500); t2.join(500)
                return ShRes(-1, outSb.toString(), errSb.toString() + "\n[timeout after ${timeoutSec}s]")
            }
            t1.join(2000); t2.join(2000)
            ShRes(p.exitValue(), outSb.toString(), errSb.toString())
        } catch (e: Throwable) {
            ShRes(-2, "", "exec failed: ${e.message}")
        }
    }

    fun root(cmd: String, timeoutSec: Long = 30) = run(cmd, true, timeoutSec)
    fun user(cmd: String, timeoutSec: Long = 15) = run(cmd, false, timeoutSec)

    /** Single-quote a value for safe use inside a shell command. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

enum class RootState { UNKNOWN, ROOTED, DENIED, NO_SU }

/** Root detection. `su -c id` is the only thing that triggers the Magisk / KernelSU prompt. */
object Root {
    @Volatile var state: RootState = RootState.UNKNOWN
        private set
    @Volatile var lastDetail: String = ""
        private set

    /** True when a privileged command channel exists: real root (rooted edition) or a running, authorised Shizuku. Use [Priv.realRoot] for things that need uid 0. */
    val isRooted: Boolean get() = state == RootState.ROOTED || ShizukuBridge.ready

    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
        "/vendor/bin/su", "/system/su", "/product/bin/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
    )

    /** Passive check: looks for an su binary without running it (no prompt). */
    fun suBinaryVisible(): Boolean {
        if (suPaths.any { File(it).exists() }) return true
        val path = System.getenv("PATH") ?: return false
        return path.split(":").any { File(it, "su").exists() }
    }

    /** Runs `su -c id`. This pops the Magisk/KernelSU grant prompt on first use. Blocking. */
    fun request(): RootState = probe("id").state

    data class Probe(val state: RootState, val code: Int, val out: String)

    /**
     * ONE `su -c <script>` invocation (so at most one grant prompt). The script should start with `id`; root is confirmed by `uid=0` in the output.
     * Updates [state] and [lastDetail] exactly like [request].
     */
    fun probe(script: String, timeoutSec: Long = 60): Probe {
        if (!Edition.ROOT) { // standard edition: su is never executed
            state = RootState.NO_SU; lastDetail = "Standard edition never runs su"
            return Probe(state, -3, "The Standard edition never runs su.")
        }
        val res = try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = thread(isDaemon = true) { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
            val done = p.waitForCompat(timeoutSec)
            if (!done) { p.killCompat(); t.join(500); Pair(-1, out.toString().trim().ifEmpty { "timeout waiting for su (prompt not answered?)" }) }
            else { t.join(1500); Pair(p.exitValue(), out.toString().trim()) }
        } catch (e: Throwable) {
            Pair(-2, e.message ?: "su not found")
        }
        if (script == "id" || res.second.contains("uid=0")) lastDetail = res.second.lineSequence().firstOrNull { it.contains("uid=") } ?: res.second.take(200)
        state = when {
            res.first == 0 && res.second.contains("uid=0") -> RootState.ROOTED
            res.first == -2 -> RootState.NO_SU
            else -> RootState.DENIED
        }
        return Probe(state, res.first, res.second)
    }
}
