package dev.optimizer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.DeviceProfile
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.Store
import dev.optimizer.android.ui.AppState
import dev.optimizer.android.ui.AppTheme
import dev.optimizer.android.ui.AssessScreen
import dev.optimizer.android.ui.MonitorScreen
import dev.optimizer.android.ui.MoreScreen
import dev.optimizer.android.ui.OptimizeScreen
import dev.optimizer.android.ui.RootScreen
import dev.optimizer.android.ui.SetupWizard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        AppState.themeMode = Store.themeMode
        AppState.amoled = Store.amoled
        AppState.applyProfile(DeviceProfile.current())
        AppState.refresh()
        if (Store.setupDone < DeviceProfile.WIZARD_VERSION) AppState.showWizard = true
        runCatching { dev.optimizer.android.features.LiveService.sync(this) }
        Thread { runCatching { dev.optimizer.android.core.ShizukuBridge.refresh(applicationContext) } }.start()
        // Device profile: scan on first run; re-scan automatically when the build fingerprint or security patch changed (OS update / ROM flash).
        if (DeviceProfile.needsRescan()) {
            val first = DeviceProfile.current() == null
            AppState.rescan(this, if (first) null else "Your Android build or security patch changed, so the device profile was re-scanned and the adaptations were refreshed.") { dev.optimizer.android.features.Features.applyDefaultsOnce() }
        }
        if (Store.rootGrantedBefore) {
            // Root was granted before: re-verify silently in the background so Optimize unlocks without visiting the Root tab.
            Thread {
                Root.request()
                runOnUiThread { AppState.refresh() }
            }.start()
        }
        setContent { AppTheme { if (AppState.showWizard) SetupWizard(onClose = { }) else MainScreen() } }
    }
}

private data class Dest(val label: String, val icon: ImageVector)

private val dests get() = listOf(
    Dest(if (dev.optimizer.android.core.Edition.ROOT) "Root" else "Access", Icons.Filled.Lock), Dest("Assess", Icons.Filled.Info), Dest("Monitor", Icons.Filled.Home),
    Dest("Optimize", Icons.Filled.Build), Dest("More", Icons.Filled.Menu),
)

/** Bottom bar on phones (< 600 dp wide), navigation rail on wide screens, tablets, TV and phones in landscape. Content is capped to a readable width. */
@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(2) } // open on Monitor
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    androidx.compose.runtime.LaunchedEffect(AppState.morePage) { if (AppState.morePage != null) tab = 4 }
    val content: @Composable (Modifier) -> Unit = { m ->
        Box(m.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxSize().widthIn(max = 960.dp)) {
                when (tab) {
                    0 -> if (dev.optimizer.android.core.Edition.ROOT) RootScreen() else dev.optimizer.android.ui.AccessScreen()
                    1 -> AssessScreen()
                    2 -> MonitorScreen()
                    3 -> OptimizeScreen(goRoot = { tab = 0 })
                    else -> MoreScreen()
                }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                dests.forEachIndexed { i, d ->
                    NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(d.icon, null) }, label = { Text(d.label) }, alwaysShowLabel = true)
                }
            }
            content(Modifier.weight(1f))
        }
    } else {
        Scaffold(bottomBar = {
            NavigationBar {
                dests.forEachIndexed { i, d -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(d.icon, null) }, label = { Text(d.label) }) }
            }
        }) { pad -> content(Modifier.padding(pad)) }
    }
    AppState.profileNotice?.let { msg ->
        AlertDialog(onDismissRequest = { AppState.profileNotice = null }, title = { Text("Device profile updated") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { AppState.profileNotice = null }) { Text("OK") } })
    }
}
