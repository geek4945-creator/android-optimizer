package dev.optimizer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.RootState
import dev.optimizer.android.core.Store
import dev.optimizer.android.ui.AppTheme
import dev.optimizer.android.ui.AssessScreen
import dev.optimizer.android.ui.MonitorScreen
import dev.optimizer.android.ui.MoreScreen
import dev.optimizer.android.ui.OptimizeScreen
import dev.optimizer.android.ui.RootScreen
import dev.optimizer.android.ui.AppState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        AppState.themeMode = Store.themeMode
        AppState.amoled = Store.amoled
        AppState.refresh()
        if (Store.rootGrantedBefore) {
            // Root was granted before: re-verify silently in the background so Optimize unlocks without visiting the Root tab.
            Thread {
                Root.request()
                runOnUiThread { AppState.refresh() }
            }.start()
        }
        setContent { AppTheme { MainScreen() } }
    }
}

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(2) } // open on Monitor
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Lock, null) }, label = { Text("Root") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Info, null) }, label = { Text("Assess") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Monitor") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Build, null) }, label = { Text("Optimize") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Filled.Menu, null) }, label = { Text("More") })
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> RootScreen()
                1 -> AssessScreen()
                2 -> MonitorScreen()
                3 -> OptimizeScreen(goRoot = { tab = 0 })
                else -> MoreScreen()
            }
        }
    }
}
