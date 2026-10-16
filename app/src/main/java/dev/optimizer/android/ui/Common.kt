package dev.optimizer.android.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optimizer.android.core.Backups
import dev.optimizer.android.core.ChangeLog
import dev.optimizer.android.core.Root
import dev.optimizer.android.core.RootState

/** Global observable state shared by tabs. */
object AppState {
    var rootState by mutableStateOf(Root.state)
    var baseline by mutableStateOf(false)
    var logVersion by mutableStateOf(0)
    var backupsVersion by mutableStateOf(0)
    var themeMode by mutableStateOf(0)
    var amoled by mutableStateOf(false)

    fun refresh() { rootState = Root.state; baseline = Backups.hasBaseline() }
    fun bumpLog() { logVersion++; refresh() }
    val rooted: Boolean get() = rootState == RootState.ROOTED
    val unlocked: Boolean get() = rooted && baseline
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (AppState.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val base: ColorScheme = if (dark) darkColorScheme(primary = Color(0xFF7FD6A8), secondary = Color(0xFF9FCBB5), tertiary = Color(0xFFE0B26B))
    else lightColorScheme(primary = Color(0xFF1B6B4A), secondary = Color(0xFF4D6357), tertiary = Color(0xFF8A5A00))
    val cs: ColorScheme = if (dark && AppState.amoled) base.copy(
        background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1A1A1A), surfaceContainer = Color.Black,
        surfaceContainerLow = Color.Black, surfaceContainerHigh = Color(0xFF111111), surfaceContainerHighest = Color(0xFF181818),
        onBackground = Color(0xFFE6E6E6), onSurface = Color(0xFFE6E6E6)
    ) else base
    MaterialTheme(colorScheme = cs, content = content)
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

fun startSafe(ctx: Context, i: Intent) {
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { toast(ctx, "Cannot open: ${e.message}") }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Warn(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
fun Info(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Busy(text: String?) {
    if (text != null) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Confirm(title: String, text: String, confirm: String = "Confirm", onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo, title = { Text(title) }, text = { Text(text) },
        confirmButton = { Button(onClick = onYes) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel") } }
    )
}
