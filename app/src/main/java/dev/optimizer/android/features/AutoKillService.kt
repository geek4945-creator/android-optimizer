package dev.optimizer.android.features

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.optimizer.android.core.Compat
import dev.optimizer.android.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Optional non-root helper: every 30 s asks Android to kill background processes of apps on the keep-closed list. */
class AutoKillService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Compat.channel(this, "autokill", "Keep-closed helper", 2)
        val n = Compat.builder(this, "autokill")
            .setContentTitle("Keeping selected apps closed")
            .setContentText("Periodically stops their background processes (cannot block them without root)")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true).build()
        if (!Compat.startForegroundSafe(this, 7, n)) { Store.autoKillEnabled = false; stopSelf(); return START_NOT_STICKY }
        job?.cancel()
        job = scope.launch {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            while (isActive) {
                Store.blocked().filter { it.method == "settings" }.forEach { if (dev.optimizer.android.core.Rules.blockReason(this@AutoKillService, it.pkg, ignoreActive = true) == null) runCatching { am.killBackgroundProcesses(it.pkg) } }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) { Store.autoKillEnabled = true; Compat.startFgService(ctx, Intent(ctx, AutoKillService::class.java)) }
        fun stop(ctx: Context) { Store.autoKillEnabled = false; ctx.stopService(Intent(ctx, AutoKillService::class.java)) }
    }
}
