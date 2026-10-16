package dev.optimizer.android.features

import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import dev.optimizer.android.MainActivity
import dev.optimizer.android.R
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Sh
import dev.optimizer.android.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class LiveStats(
    val ts: Long, val cpu: Float?, val cores: List<Float?>, val coreMhz: List<Int?>, val cpuNote: String,
    val ramTotalKb: Long, val ramAvailKb: Long, val swapTotalKb: Long, val swapUsedKb: Long,
    val storageTotal: Long, val storageFree: Long,
    val battPct: Int, val battTempC: Float?, val charging: Boolean, val battMv: Int?, val thermal: String,
    val uptimeMs: Long, val rxBps: Long?, val txBps: Long?, val top: List<Pair<String, Long>>, val topNote: String,
) {
    val ramPct: Int get() = if (ramTotalKb > 0) (100 - ramAvailKb * 100 / ramTotalKb).toInt() else 0
    val storagePct: Int get() = if (storageTotal > 0) (100 - storageFree * 100 / storageTotal).toInt() else 0
}

object LiveSampler {
    val cpuHist = ArrayList<Float>(); val ramHist = ArrayList<Float>()
    const val HIST = 60
    @Volatile var last: LiveStats? = null
    private var prevTotal = HashMap<Int, Long>(); private var prevIdle = HashMap<Int, Long>()
    private var prevRx = -1L; private var prevTx = -1L; private var prevNet = 0L
    private var topCache: List<Pair<String, Long>> = emptyList(); private var topAt = 0L; private var topNote = "Top apps need root (Android hides other apps' memory)."

    private fun readStat(): String? {
        runCatching { File("/proc/stat").readText() }.getOrNull()?.let { if (it.startsWith("cpu")) return it }
        if (dev.optimizer.android.core.Priv.realRoot) return dev.optimizer.android.core.RootShell.exec("cat /proc/stat")
        if (dev.optimizer.android.core.Priv.shell) return Sh.root("cat /proc/stat", 5).out.takeIf { it.startsWith("cpu") }
        return null
    }

    private fun cpuFrom(txt: String): Pair<Float?, List<Float?>> {
        var total: Float? = null; val cores = ArrayList<Float?>()
        for (l in txt.lineSequence()) {
            if (!l.startsWith("cpu")) continue
            val parts = l.trim().split(Regex("\\s+"))
            val id = if (parts[0] == "cpu") -1 else parts[0].removePrefix("cpu").toIntOrNull() ?: continue
            val v = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size < 4) continue
            val idle = v[3] + (v.getOrNull(4) ?: 0); val tot = v.take(8).sum()
            val pt = prevTotal[id]; val pi = prevIdle[id]
            prevTotal[id] = tot; prevIdle[id] = idle
            val pct = if (pt == null || pi == null || tot - pt <= 0) null else ((tot - pt - (idle - pi)).toFloat() / (tot - pt) * 100f).coerceIn(0f, 100f)
            if (id == -1) total = pct else cores += pct
        }
        return total to cores
    }

    private fun battery(ctx: Context): Triple<Int, Float?, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return Triple(if (lvl >= 0) lvl * 100 / sc.coerceAtLeast(1) else -1, t, st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun thermalName(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "n/a (Android 10+)"
        return runCatching {
            when ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
                0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"; else -> "?"
            }
        }.getOrDefault("n/a")
    }

    fun refreshTop(ctx: Context) {
        if (!Root.isRooted) { topCache = emptyList(); topNote = "Top apps need root (Android hides other apps' memory)."; return }
        val r = Sh.root("dumpsys meminfo", 45)
        val m = HashMap<String, Long>(); var inSec = false
        val rx = Regex("^\\s*([\\d,]+)\\s*(?:K|kB):\\s+(\\S+)")
        for (l in r.out.lineSequence()) {
            if (l.startsWith("Total PSS by process")) { inSec = true; continue }
            if (inSec) { if (l.isBlank() || l.startsWith("Total PSS by OOM")) break; val mm = rx.find(l) ?: continue
                val kb = mm.groupValues[1].replace(",", "").toLongOrNull() ?: continue; val pkg = mm.groupValues[2].substringBefore(':'); m[pkg] = (m[pkg] ?: 0L) + kb }
        }
        val pm = ctx.packageManager
        topCache = m.entries.sortedByDescending { it.value }.take(5).map { (k, v) -> (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(k, 0)).toString() }.getOrDefault(k)) to v }
        topAt = System.currentTimeMillis(); topNote = if (topCache.isEmpty()) "No data from dumpsys meminfo." else "PSS via root"
    }

    fun topStale(): Boolean = System.currentTimeMillis() - topAt > (if (Features.lowEnd()) 60_000 else 30_000)

    fun sample(ctx: Context, detail: Boolean): LiveStats {
        val txt = readStat()
        val (cpu, cores) = if (txt != null) cpuFrom(txt) else (null to emptyList())
        val mhz = if (detail) cores.indices.map { i -> runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 }.getOrNull() } else emptyList()
        val ram = RamOptimizer.read()
        val st = runCatching { StatFs(Environment.getDataDirectory().path).let { it.totalBytes to it.availableBytes } }.getOrDefault(0L to 0L)
        val (bp, bt, ch) = battery(ctx)
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
        var rxb: Long? = null; var txb: Long? = null
        if (rx >= 0 && prevRx >= 0 && now > prevNet) { val dt = (now - prevNet).coerceAtLeast(1); rxb = (rx - prevRx) * 1000 / dt; txb = (tx - prevTx) * 1000 / dt }
        prevRx = rx; prevTx = tx; prevNet = now
        synchronized(this) {
            cpuHist.add(cpu ?: 0f); ramHist.add((if (ram.totalKb > 0) 100f - ram.availKb * 100f / ram.totalKb else 0f))
            while (cpuHist.size > HIST) cpuHist.removeAt(0); while (ramHist.size > HIST) ramHist.removeAt(0)
        }
        val s = LiveStats(System.currentTimeMillis(), cpu, cores, mhz,
            if (txt == null) "CPU n/a: Android blocks /proc/stat for apps (needs root)" else if (cpu == null) "CPU warming up" else "",
            ram.totalKb, ram.availKb, ram.swapTotalKb, ram.swapTotalKb - ram.swapFreeKb, st.first, st.second, bp, bt, ch, null, thermalName(ctx),
            now, rxb, txb, topCache, topNote)
        last = s
        return s
    }
}

object LiveFmt {
    fun mb(kb: Long) = if (kb >= 1048576) "%.1f GB".format(kb / 1048576.0) else "${kb / 1024} MB"
    fun bytes(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "${b / 1048576} MB"
    fun rate(b: Long?) = if (b == null) "n/a" else if (b >= 1048576) "%.1f MB/s".format(b / 1048576.0) else "${b / 1024} KB/s"
    fun uptime(ms: Long): String { val m = ms / 60000; val d = m / 1440; val h = m % 1440 / 60; return if (d > 0) "${d}d ${h}h" else "${h}h ${m % 60}m" }
}

object LiveRender {
    private fun night(ctx: Context) = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun graph(ctx: Context, values: List<Float>, color: Int): Bitmap {
        val w = 360; val h = 90
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val grid = Paint().apply { this.color = if (night(ctx)) 0x33FFFFFF else 0x33000000; strokeWidth = 2f }
        c.drawLine(0f, h - 1f, w.toFloat(), h - 1f, grid); c.drawLine(0f, h / 2f, w.toFloat(), h / 2f, grid)
        if (values.size >= 2) {
            val p = Paint().apply { this.color = color; strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true }
            val path = Path(); val step = w / (LiveSampler.HIST - 1f); val off = (LiveSampler.HIST - values.size) * step
            values.forEachIndexed { i, v -> val x = off + i * step; val y = h - 4 - (h - 8) * v.coerceIn(0f, 100f) / 100f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            c.drawPath(path, p)
        }
        return bmp
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 100, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or Compat.immutableFlag())
    private fun action(ctx: Context, a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, WidgetActionReceiver::class.java).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT or Compat.immutableFlag())

    fun small(ctx: Context, s: LiveStats): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_small).apply {
        setTextViewText(R.id.s_ram, "RAM ${s.ramPct}%"); setProgressBar(R.id.s_ram_bar, 100, s.ramPct, false)
        setTextViewText(R.id.s_cpu, "CPU " + (s.cpu?.let { "%.0f%%".format(it) } ?: "n/a")); setProgressBar(R.id.s_cpu_bar, 100, s.cpu?.toInt() ?: 0, false)
        setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    }

    fun medium(ctx: Context, s: LiveStats): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_medium).apply {
        setTextViewText(R.id.m_ram, "RAM ${s.ramPct}% · ${LiveFmt.mb(s.ramAvailKb)} free"); setProgressBar(R.id.m_ram_bar, 100, s.ramPct, false)
        setTextViewText(R.id.m_cpu, "CPU " + (s.cpu?.let { "%.0f%%".format(it) } ?: "n/a")); setProgressBar(R.id.m_cpu_bar, 100, s.cpu?.toInt() ?: 0, false)
        setTextViewText(R.id.m_storage, "Storage ${s.storagePct}% · ${LiveFmt.bytes(s.storageFree)} free"); setProgressBar(R.id.m_storage_bar, 100, s.storagePct, false)
        setTextViewText(R.id.m_batt, "Battery ${s.battPct}%${if (s.charging) " ⚡" else ""} · ${s.battTempC?.let { "%.1f°C".format(it) } ?: "-"}")
        setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    }

    fun large(ctx: Context, s: LiveStats): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_large).apply {
        val acc = if (night(ctx)) 0xFF8AB4F8.toInt() else 0xFF1565C0.toInt(); val acc2 = if (night(ctx)) 0xFFA5D6A7.toInt() else 0xFF2E7D32.toInt()
        setTextViewText(R.id.l_title, "Android Optimizer · live · every ${Store.getInt("live_interval_ms", 2000) / 1000}s")
        setTextViewText(R.id.l_cpu, "CPU " + (s.cpu?.let { "%.0f%%".format(it) } ?: "n/a") + (if (s.cpuNote.isNotEmpty()) "  (${s.cpuNote})" else ""))
        val cores = s.cores.mapIndexed { i, c -> "c$i ${c?.let { "%.0f".format(it) } ?: "-"}%${s.coreMhz.getOrNull(i)?.let { "@${it}" } ?: ""}" }
        setTextViewText(R.id.l_cores, if (cores.isEmpty()) "per-core: not readable on this device" else cores.joinToString("  "))
        synchronized(LiveSampler) { setImageViewBitmap(R.id.l_cpu_graph, graph(ctx, LiveSampler.cpuHist.toList(), acc)); setImageViewBitmap(R.id.l_ram_graph, graph(ctx, LiveSampler.ramHist.toList(), acc2)) }
        setTextViewText(R.id.l_ram, "RAM ${s.ramPct}% used · ${LiveFmt.mb(s.ramAvailKb)} available of ${LiveFmt.mb(s.ramTotalKb)}")
        setTextViewText(R.id.l_swap, "Swap/zRAM " + if (s.swapTotalKb > 0) "${LiveFmt.mb(s.swapUsedKb)} / ${LiveFmt.mb(s.swapTotalKb)}" else "none")
        setTextViewText(R.id.l_storage, "Storage ${s.storagePct}% used · ${LiveFmt.bytes(s.storageFree)} free of ${LiveFmt.bytes(s.storageTotal)}")
        setTextViewText(R.id.l_batt, "Battery ${s.battPct}%${if (s.charging) " charging" else ""} · ${s.battTempC?.let { "%.1f°C".format(it) } ?: "-"}")
        setTextViewText(R.id.l_thermal, "Thermal ${s.thermal} · uptime ${LiveFmt.uptime(s.uptimeMs)}")
        setTextViewText(R.id.l_net, "Network ↓ ${LiveFmt.rate(s.rxBps)}  ↑ ${LiveFmt.rate(s.txBps)}")
        setTextViewText(R.id.l_top, if (s.top.isEmpty()) "Top apps: ${s.topNote}" else "Top RAM: " + s.top.joinToString(" · ") { "${it.first} ${it.second / 1024}M" })
        setOnClickPendingIntent(R.id.btn_opt, action(ctx, WidgetActionReceiver.OPTIMIZE, 1)); setOnClickPendingIntent(R.id.btn_refresh, action(ctx, WidgetActionReceiver.REFRESH, 2))
        setOnClickPendingIntent(R.id.btn_open, openApp(ctx)); setOnClickPendingIntent(R.id.l_title, openApp(ctx))
    }

    fun ids(ctx: Context, cls: Class<*>): IntArray = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))
    fun hasWidgets(ctx: Context) = listOf(WidgetSmall::class.java, WidgetMedium::class.java, WidgetLarge::class.java).any { ids(ctx, it).isNotEmpty() }
    fun hasLarge(ctx: Context) = ids(ctx, WidgetLarge::class.java).isNotEmpty()

    fun renderAll(ctx: Context, s: LiveStats) {
        val m = AppWidgetManager.getInstance(ctx)
        runCatching { ids(ctx, WidgetSmall::class.java).let { if (it.isNotEmpty()) m.updateAppWidget(it, small(ctx, s)) } }
        runCatching { ids(ctx, WidgetMedium::class.java).let { if (it.isNotEmpty()) m.updateAppWidget(it, medium(ctx, s)) } }
        runCatching { ids(ctx, WidgetLarge::class.java).let { if (it.isNotEmpty()) m.updateAppWidget(it, large(ctx, s)) } }
    }
}

abstract class LiveWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(context)
        val s = LiveSampler.last ?: LiveSampler.sample(context, false)
        LiveRender.renderAll(context, s)
        LiveService.sync(context)
    }
    override fun onEnabled(context: Context) { Store.init(context); LiveService.sync(context) }
    override fun onDisabled(context: Context) { Store.init(context); LiveService.sync(context) } // clean removal: service stops when no widget is left (and the notification is off)
    override fun onDeleted(context: Context, ids: IntArray) { Store.init(context); LiveService.sync(context) }
}
class WidgetSmall : LiveWidgetProvider()
class WidgetMedium : LiveWidgetProvider()
class WidgetLarge : LiveWidgetProvider()

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val pending = goAsync()
        Thread {
            try {
                if (!Root.isRooted && Store.rootGrantedBefore) runCatching { Root.request() } // silent when the manager remembers the grant
                if (intent.action == OPTIMIZE) {
                    val c = RamOptimizer.candidates(context, false) // honours protected apps and 'Never stop' rules
                    val r = RamOptimizer.run(context, c.items, RamOptions(true, false, false, false, false), "widget (gentle)")
                    Handler(Looper.getMainLooper()).post { Toast.makeText(context, "Closed ${r.killed} apps · available ${LiveFmt.mb(r.before.availKb)} → ${LiveFmt.mb(r.after.availKb)}", Toast.LENGTH_LONG).show() }
                }
                if (LiveSampler.topStale() && LiveRender.hasLarge(context)) LiveSampler.refreshTop(context)
                LiveRender.renderAll(context, LiveSampler.sample(context, true))
                LiveService.kick()
            } finally { pending.finish() }
        }.start()
    }
    companion object { const val OPTIMIZE = "dev.optimizer.android.WIDGET_OPTIMIZE"; const val REFRESH = "dev.optimizer.android.WIDGET_REFRESH" }
}

/**
 * Lightweight foreground service that drives the widgets (and the optional RAM/CPU notification). Updates only while the screen is on
 * (interval 1/2/5 s from the widget configuration); with the screen off it sleeps (only a 5-minute temperature-log sample).
 * AppWidget `updatePeriodMillis` is limited by the system to 30 minutes, so second-by-second updates are only possible through this service.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rcv: BroadcastReceiver? = null
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        Compat.channel(this, "live", "Live monitor", 1)
        if (!Compat.startForegroundSafe(this, 12, notif("Updating widgets", ""))) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_STICKY
        started = true
        inst = this
        rcv = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { kick() } }
        ContextCompat.registerReceiver(this, rcv, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            if (!Root.isRooted && Store.rootGrantedBefore) Root.request()
            var lastNotif = 0L; var lastTemp = 0L
            while (isActive) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val on = pm.isInteractive
                val now = System.currentTimeMillis()
                if (on) {
                    val large = LiveRender.hasLarge(this@LiveService)
                    if (large && LiveSampler.topStale()) launch(Dispatchers.IO) { runCatching { LiveSampler.refreshTop(applicationContext) } }
                    val s = LiveSampler.sample(this@LiveService, large)
                    LiveRender.renderAll(this@LiveService, s)
                    if (Store.getBool("live_notif") && now - lastNotif >= 5000) { lastNotif = now; updateNotif(s) }
                }
                if (now - lastTemp >= (if (on) 60_000L else 300_000L)) { lastTemp = now; runCatching { TempLog.add(applicationContext) } }
                val wait = if (on) interval() else 300_000L
                withTimeoutOrNull(wait) { kicks.receive() }
            }
        }
        return START_STICKY
    }

    private fun notif(title: String, text: String) = Compat.builder(this, "live").setContentTitle(title).setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setOnlyAlertOnce(true).setPriority(-2)
        .setContentIntent(PendingIntent.getActivity(this, 101, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or Compat.immutableFlag())).build()

    private fun updateNotif(s: LiveStats) {
        runCatching {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            if (nm.areNotificationsEnabled()) nm.notify(12, notif("RAM ${s.ramPct}% · CPU ${s.cpu?.let { "%.0f%%".format(it) } ?: "n/a"}", "${LiveFmt.mb(s.ramAvailKb)} free · battery ${s.battPct}% ${s.battTempC?.let { "%.0f°C".format(it) } ?: ""}"))
        }
    }

    override fun onDestroy() { scope.cancel(); rcv?.let { runCatching { unregisterReceiver(it) } }; inst = null; started = false; super.onDestroy() }

    companion object {
        private val kicks = Channel<Unit>(Channel.CONFLATED)
        @Volatile private var inst: LiveService? = null
        fun kick() { kicks.trySend(Unit) }
        fun interval(): Long = Store.getInt("live_interval_ms", if (Features.lowEnd()) 5000 else 2000).toLong().coerceIn(1000, 10000)
        fun wanted(ctx: Context) = Store.getBool("live_notif") || LiveRender.hasWidgets(ctx)
        /** Starts the service when a widget exists or the notification is enabled, stops it otherwise. Safe to call from anywhere (background-start failures are swallowed). */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            runCatching {
                if (wanted(app)) Compat.startFgService(app, Intent(app, LiveService::class.java))
                else { app.stopService(Intent(app, LiveService::class.java)); runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancel(12) } }
            }
        }
    }
}

/** Widget configuration: update interval (1 / 2 / 5 s). Shown when a widget is added. */
class WidgetConfigActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent {
            dev.optimizer.android.ui.AppTheme { dev.optimizer.android.ui.WidgetConfigUi(onDone = {
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                runCatching { LiveRender.renderAll(this, LiveSampler.last ?: LiveSampler.sample(this, true)) }
                LiveService.sync(this); LiveService.kick(); finish()
            }) }
        }
    }
}
