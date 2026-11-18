package app.fingernotes.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fingernotes.core.BackupContents
import app.fingernotes.data.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Section(title: String, content: @Composable () -> Unit) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
    val s by vm.settings.collectAsState()
    val labels by vm.labels.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Security") {
            Text("Auto-lock after inactivity", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0L to "Immediately", 60_000L to "1 minute", 300_000L to "5 minutes").forEach { (v, l) ->
                    FilterChip(s.lockTimeoutMs == v, { vm.updateSettings { it.copy(lockTimeoutMs = v) } }, { Text(l) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Unlock with fingerprint / face", Modifier.weight(1f))
                Switch(s.biometricEnabled, { on ->
                    if (!on) vm.updateSettings { it.copy(biometricEnabled = false) }
                    else if (!Biometrics.available(activity)) vm.snack("No strong biometric enrolled on this device")
                    else Biometrics.prompt(activity, "Enable biometric unlock", onSuccess = { vm.updateSettings { it.copy(biometricEnabled = true) } })
                })
            }
            PinChanger(vm, "Change app PIN") { vm.keys.setAppPin(it) }
            PinChanger(vm, if (vm.keys.hasVaultPin) "Change Hidden-vault PIN" else "Set Hidden-vault PIN") { vm.keys.setVaultPin(it) }
            OutlinedButton({ vm.lock.lock() }) { Text("Lock now") }
        }
        BackupSection(vm)
        Section("Sync & device") {
            OutlinedTextField(s.deviceName, { v -> vm.updateSettings { it.copy(deviceName = v) } }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.defaultCurrency, { v -> vm.updateSettings { it.copy(defaultCurrency = v.take(4)) } }, label = { Text("Default currency") }, singleLine = true)
            OutlinedTextField(s.signalingUrl, { v -> vm.updateSettings { it.copy(signalingUrl = v.trim()) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Relay URL — sync over the internet (signaling-worker)") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use free public STUN when online", Modifier.weight(1f))
                Switch(s.useStun, { v -> vm.updateSettings { it.copy(useStun = v) } })
            }
        }
        Section("Labels") {
            if (labels.isEmpty()) Text("Create labels from the note editor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            labels.forEach { l ->
                var name by remember(l.id) { mutableStateOf(l.name) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton({ if (name.isNotBlank() && name != l.name) vm.io { saveLabel(name.trim(), l.id) } }) { Text("Save") }
                    TextButton({ vm.io { deleteLabel(l.id) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun PinChanger(vm: AppViewModel, label: String, save: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    if (!open) { OutlinedButton({ open = true }) { Text(label) }; return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(a, { a = it }, label = { Text("New PIN / password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(b, { b = it }, label = { Text("Repeat") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row {
            Button({
                if (a != b) { vm.snack("PINs do not match"); return@Button }
                scope.launch {
                    runCatching { withContext(Dispatchers.Default) { KeyManager.validatePin(a); save(a) } }
                        .onSuccess { vm.snack("Saved"); open = false; a = ""; b = "" }
                        .onFailure { vm.snack(it.message ?: "Error") }
                }
            }) { Text("Save") }
            TextButton({ open = false }) { Text("Cancel") }
        }
    }
}

@Composable
private fun BackupSection(vm: AppViewModel) {
    var useAppPin by remember { mutableStateOf(true) }
    var pw by remember { mutableStateOf("") }
    var restorePw by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<BackupContents?>(null) }
    var overwrite by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var pendingPw by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.createBackup(uri, pendingPw)
        pendingPw = ""
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri; preview = null; err = null }

    Section("Encrypted backup") {
        Text("AES-256 ZIP compatible with the Chrome extension (zip.js). Includes notes, hidden & trashed notes, expenses, labels, tombstones and media.",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(useAppPin, { useAppPin = true }); Text("App Lock PIN")
            RadioButton(!useAppPin, { useAppPin = false }); Text("Custom password")
        }
        OutlinedTextField(pw, { pw = it }, label = { Text(if (useAppPin) "App Lock PIN" else "Backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button({
            scope.launch {
                if (pw.length < 4) { vm.snack("Password too short"); return@launch }
                if (useAppPin && !withContext(Dispatchers.Default) { vm.keys.verifyAppPin(pw) }) { vm.snack("That is not your App Lock PIN"); return@launch }
                pendingPw = pw; pw = ""
                saver.launch("fingernotes-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.zip")
            }
        }) { Text("Create backup") }

        Text("Restore", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton({ opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text(if (restoreUri == null) "Choose .zip file" else "Change file") }
        restoreUri?.let { uri ->
            OutlinedTextField(restorePw, { restorePw = it }, label = { Text("Backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedButton({
                scope.launch {
                    err = null
                    runCatching { vm.readBackup(uri, restorePw) }.onSuccess { preview = it }.onFailure { err = it.message }
                }
            }) { Text("Decrypt & verify") }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        preview?.let { b ->
            Text("✔ Checksums verified. From ${b.metadata.platform}, ${DateFormat.getDateTimeInstance().format(Date(b.metadata.createdAt))} — " +
                b.metadata.counts.entries.joinToString { "${it.value} ${it.key}" })
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!overwrite, { overwrite = false }); Text("Merge by UUID")
                RadioButton(overwrite, { overwrite = true }); Text("Overwrite local")
            }
            Button({ vm.applyBackup(b, overwrite); preview = null; restoreUri = null; restorePw = "" }) { Text("Restore") }
        }
    }
}
