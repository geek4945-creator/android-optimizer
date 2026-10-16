package dev.optimizer.android.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Result of a shell command. */
data class ShRes(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
    val text: String get() = (out + (if (err.isNotBlank()) "\n" + err else "")).trim()
}

/** Minimal shell runner (ProcessBuilder). Root commands go through `su`. Never throws. */
object Sh {
    fun run(cmd: String, root: Boolean = false, timeoutSec: Long = 30): ShRes {
        return try {
            val pb = ProcessBuilder(if (root) "su" else "sh")
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
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
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

    val isRooted: Boolean get() = state == RootState.ROOTED

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
    fun request(): RootState {
        val res = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = thread(isDaemon = true) { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
            val done = p.waitFor(60, TimeUnit.SECONDS)
            if (!done) { p.destroyForcibly(); Pair(-1, "timeout waiting for su (prompt not answered?)") }
            else { t.join(1000); Pair(p.exitValue(), out.toString().trim()) }
        } catch (e: Throwable) {
            Pair(-2, e.message ?: "su not found")
        }
        lastDetail = res.second
        state = when {
            res.first == 0 && res.second.contains("uid=0") -> RootState.ROOTED
            res.first == -2 -> RootState.NO_SU
            else -> RootState.DENIED
        }
        return state
    }
}
