package app.fingernotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.fingernotes.sync.AutoStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Pull down to sync with every online device on the chain (internet, device-to-device). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(vm: AppViewModel, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                val msg = vm.refreshSync()
                refreshing = false
                vm.snack(msg)
            }
        },
        modifier = modifier,
        content = content,
    )
}

/** One-line auto-sync status shown above the notes list. */
@Composable
fun SyncStatusLine(vm: AppViewModel, modifier: Modifier = Modifier) {
    val st by vm.autoState.collectAsState()
    val now = System.currentTimeMillis()
    val peers = st.online.values.count { now - it.seen < 10 * 60_000 }
    val (text, color) = when (st.status) {
        AutoStatus.Off, AutoStatus.NoChain -> return
        AutoStatus.NoRelay -> "Sync: same-network only (add relay URL in Settings)" to MaterialTheme.colorScheme.outline
        AutoStatus.Connecting -> "Sync: connecting…" to Color(0xFFE0A800)
        AutoStatus.Syncing -> "Syncing…" to MaterialTheme.colorScheme.primary
        AutoStatus.Online -> (if (peers > 0) "Sync: $peers device${if (peers > 1) "s" else ""} online" else "Sync: waiting for your devices") to Color(0xFF2E7D32)
    }
    val last = st.lastSyncAt?.let { " · last ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: ""
    Row(modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(color, CircleShape))
        Text("  $text$last  ·  pull down to sync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
