package app.fingernotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fingernotes.core.Bip39
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import java.text.DateFormat
import kotlinx.coroutines.launch
import java.util.Date

@Composable
private fun rememberQrScanner(onResult: (String) -> Unit) = rememberLauncherForActivityResult(ScanContract()) { r ->
    r.contents?.let(onResult)
}

private fun scanOptions(prompt: String) = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(prompt)
    setBeepEnabled(false)
    setOrientationLocked(false)
}

@Composable
fun SyncScreen(vm: AppViewModel) {
    val chain by vm.chain.collectAsState()
    val material by vm.material.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadChain(); loaded = true }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!loaded) return@Column
        if (chain == null) ChainSetup(vm) else {
            ChainCard(vm, chain!!, material?.chainId)
            InternetSyncCard(vm)
            if (material != null) ConnectCard(vm)
            PeersCard(vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Words(m: String) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    m.split(" ").forEachIndexed { i, w ->
        Text("${i + 1}. $w", fontFamily = FontFamily.Monospace, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun ChainSetup(vm: AppViewModel) {
    var mode by remember { mutableStateOf("choose") }
    val words = remember { vm.bip39.generate() }
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scan = rememberQrScanner { input = it }
    fun join(text: String) {
        val n = Bip39.normalize(text)
        if (!vm.bip39.isValid(n)) err = "That is not a valid 24-word sync code (check spelling and order)." else vm.setChain(n)
    }
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (mode) {
            "choose" -> {
                Text("Peer-to-peer sync", style = MaterialTheme.typography.titleLarge)
                Text("Sync Android phones and Chrome browsers directly. A 24-word phrase is the only key — no account, no server.")
                Button({ mode = "create" }) { Text("Start a new sync chain") }
                OutlinedButton({ mode = "join" }) { Text("Join an existing chain") }
            }
            "create" -> {
                Text("Your sync chain code", style = MaterialTheme.typography.titleLarge)
                Text("On the other device choose “Join an existing chain” and scan this QR or type the 24 words. Write them down — they are also your recovery phrase.")
                Words(words)
                QrCode(Bip39.PAIR_PREFIX + words)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ mode = "choose" }) { Text("Back") }
                    Button({ vm.setChain(words) }) { Text("I've saved it — create chain") }
                }
            }
            else -> {
                Text("Join a sync chain", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(input, { input = it; err = null }, label = { Text("24 words") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ mode = "choose" }) { Text("Back") }
                    OutlinedButton({ scan.launch(scanOptions("Scan the pairing QR")) }) { Text("Scan QR") }
                    Button({ join(input) }) { Text("Join") }
                }
            }
        }
    } }
}

@Composable
private fun ChainCard(vm: AppViewModel, chain: String, chainId: String?) {
    var show by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sync chain", style = MaterialTheme.typography.titleLarge)
        Text("Chain ${chainId?.take(8) ?: "…"} · this device: ${settings.deviceName}. Data travels device-to-device over an end-to-end encrypted WebRTC channel.")
        if (show) {
            Text("Anyone with these words can join your chain and read your data.", color = MaterialTheme.colorScheme.error)
            Words(chain)
            QrCode(Bip39.PAIR_PREFIX + chain)
            TextButton({ show = false }) { Text("Hide") }
        } else OutlinedButton({ show = true }) { Text("Show 24 words / pairing QR") }
        TextButton({ vm.setChain(null) }) { Text("Leave chain", color = MaterialTheme.colorScheme.error) }
    } }
}

@Composable
private fun InternetSyncCard(vm: AppViewModel) {
    val st by vm.autoState.collectAsState()
    val settings by vm.settings.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Internet sync (device-to-device)", style = MaterialTheme.typography.titleLarge)
        if (settings.signalingUrl.isBlank()) {
            Text("To sync devices on different networks, deploy the free sync helper (signaling-worker/README.md) and paste its URL into Settings → Relay URL on every device. Notes still travel only device-to-device, end-to-end encrypted.")
            return@Column
        }
        Text("Devices on this chain find each other through your sync helper and connect directly (or via encrypted TURN when a network blocks direct connections). They sync automatically while open; pull down on the notes list to sync now.",
            style = MaterialTheme.typography.bodySmall)
        val last = st.lastSyncAt?.let { " · last sync " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: ""
        Text("Status: ${st.status.name.lowercase()}$last")
        val now = System.currentTimeMillis()
        st.online.filterValues { now - it.seen < 10 * 60_000 }.values.forEach { Text("● ${it.name} (${it.platform}) online", style = MaterialTheme.typography.bodySmall) }
        st.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button({ scope.launch { busy = true; vm.snack(vm.refreshSync()); busy = false } }, enabled = !busy) { Text(if (busy) "Syncing…" else "Sync now") }
        if (st.log.isNotEmpty()) SelectionContainer {
            Text(st.log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp))
        }
    } }
}

@Composable
private fun ConnectCard(vm: AppViewModel) {
    val stage by vm.syncStage.collectAsState()
    val log by vm.syncLog.collectAsState()
    val settings by vm.settings.collectAsState()
    var paste by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val scan = rememberQrScanner { vm.handleCode(it) }

    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Offline QR pairing", style = MaterialTheme.typography.titleLarge)
        when (val s = stage) {
            SyncStage.Idle -> {
                Text("No internet needed: one device shows a code, the other scans it and shows a reply. Works when both are on the same Wi-Fi/LAN.")
                OutlinedButton({ vm.startOffer() }) { Text("Show my code (start)") }
                OutlinedButton({ vm.awaitOffer() }) { Text("Scan their code (join)") }
            }
            is SyncStage.ShowOffer -> {
                Text("Step 1 — scan this with the other device (Sync → Scan their code):")
                QrCode(s.code)
                AssistChip({ clipboard.setText(AnnotatedString(s.code)) }, { Text("Copy code") })
                Text("Step 2 — scan or paste the reply code it shows:")
            }
            is SyncStage.ShowAnswer -> {
                Text("Show this reply code to the device that started:")
                QrCode(s.code)
                AssistChip({ clipboard.setText(AnnotatedString(s.code)) }, { Text("Copy code") })
            }
            SyncStage.AwaitOffer -> Text("Scan or paste the code shown on the other device:")
            SyncStage.Syncing -> Text("Syncing…")
        }
        if (stage is SyncStage.ShowOffer || stage == SyncStage.AwaitOffer) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ scan.launch(scanOptions("Scan the FingerNotes code")) }) { Text("Scan with camera") }
            }
            OutlinedTextField(paste, { paste = it }, label = { Text("…or paste FN1: code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ vm.handleCode(paste); paste = "" }, enabled = paste.startsWith("FN1:")) { Text("Use pasted code") }
        }
        if (stage != SyncStage.Idle) TextButton({ vm.cancelSync() }) { Text("Cancel") }
        if (log.isNotEmpty()) SelectionContainer {
            Text(log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp))
        }
    } }
}

@Composable
private fun PeersCard(vm: AppViewModel) {
    val peers by vm.peers.collectAsState()
    if (peers.isEmpty()) return
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Devices in this chain", style = MaterialTheme.typography.titleMedium)
        peers.forEach { p ->
            Text("${p.name} (${p.platform}) — last sync ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.lastSync))}")
        }
    } }
}
