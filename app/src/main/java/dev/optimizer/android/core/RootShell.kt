package dev.optimizer.android.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * ONE persistent `su` shell (rooted edition only, only when root was already granted), shared by the live sampler and the health check.
 * Commands are delimited with a unique end marker; a hung command closes the shell so the next call starts a fresh one.
 * Never started in the Standard edition.
 */
object RootShell {
    private var p: Process? = null
    private var w: OutputStreamWriter? = null
    private var r: BufferedReader? = null
    @Volatile var startedAt = 0L; private set
    @Volatile var restarts = 0; private set
    @Volatile var lastError = ""; private set

    val alive: Boolean get() = p?.let { runCatching { it.exitValue(); false }.getOrDefault(true) } ?: false

    @Synchronized fun exec(cmd: String, timeoutMs: Long = 1500): String? {
        if (!Edition.ROOT) return null
        return runCatching {
            if (!alive) {
                close()
                val pr = ProcessBuilder("su").redirectErrorStream(true).start()
                p = pr; w = OutputStreamWriter(pr.outputStream); r = BufferedReader(InputStreamReader(pr.inputStream)); startedAt = System.currentTimeMillis(); restarts++
            }
            w!!.write("$cmd\necho __OPT_END__\n"); w!!.flush()
            val sb = StringBuilder(); val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (r!!.ready()) { val l = r!!.readLine() ?: break; if (l.contains("__OPT_END__")) return@runCatching sb.toString(); sb.append(l).append('\n') } else Thread.sleep(4)
            }
            lastError = "timeout after ${timeoutMs} ms: ${cmd.take(40)}"; close(); null
        }.getOrElse { lastError = it.message ?: it.javaClass.simpleName; close(); null }
    }

    @Synchronized fun close() { runCatching { p?.destroy() }; p = null; w = null; r = null }
}

/** Root health check: what works, how fast, and what looks wrong. Rooted edition runs it through the persistent shell; Standard is passive only. */
object RootHealth {
    data class Line(val name: String, val ok: Boolean?, val detail: String)

    fun run(ctx: android.content.Context): List<Line> {
        val out = ArrayList<Line>()
        val vis = Root.suBinaryVisible()
        out += Line("su binary visible", vis, if (vis) "found in a standard location" else "none found (a root manager may hide it from apps)")
        val mgrs = runCatching { dev.optimizer.android.features.RootDetect.installedManagers(ctx).map { it.label } }.getOrDefault(emptyList())
        out += Line("Root manager app", mgrs.isNotEmpty(), if (mgrs.isEmpty()) "none detected" else mgrs.joinToString())
        if (!Edition.ROOT) { out += Line("Active checks", null, "Standard edition never runs su; only passive checks above."); return out }
        val t0 = System.currentTimeMillis()
        val id = RootShell.exec("id", 8000)
        val ms = System.currentTimeMillis() - t0
        out += Line("Persistent root shell", id?.contains("uid=0") == true, if (id == null) "no answer: ${RootShell.lastError.ifEmpty { "denied or no su" }}" else "${id.trim().take(60)} · ${ms} ms")
        if (id?.contains("uid=0") != true) return out
        out += Line("Shell alive / restarts", RootShell.alive, "restarts so far: ${RootShell.restarts}")
        val t1 = System.currentTimeMillis(); RootShell.exec("true", 3000); out += Line("Round-trip latency", System.currentTimeMillis() - t1 < 400, "${System.currentTimeMillis() - t1} ms")
        out += Line("SELinux", null, RootShell.exec("getenforce")?.trim().orEmpty().ifEmpty { "unknown" })
        out += Line("Root implementation", null, RootShell.exec("(magisk -v || ksud -V || apd -V) 2>/dev/null | head -1")?.trim().orEmpty().ifEmpty { "unknown (su only)" })
        val rw = RootShell.exec("touch /data/local/tmp/.aopt_probe && echo ok; rm -f /data/local/tmp/.aopt_probe")?.contains("ok") == true
        out += Line("Write /data/local/tmp", rw, if (rw) "ok" else "failed")
        val mnt = RootShell.exec("grep -c ' / ' /proc/self/mountinfo 2>/dev/null")?.trim().orEmpty()
        out += Line("Mount namespace", null, if (mnt.isEmpty()) "unknown" else "root namespace entries: $mnt (isolated if su sees a different /system than apps)")
        out += Line("am / pm reachable", RootShell.exec("pm path android | head -1", 6000)?.contains("package:") == true, "pm path android")
        return out
    }
}
