package app.fingernotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(vm: AppViewModel, activity: MainActivity) {
    val keys = vm.keys
    var err by remember { mutableStateOf<String?>(null) }
    var setUp by remember { mutableStateOf(keys.hasAppPin) }
    val scope = rememberCoroutineScope()
    val bio = vm.settings.value.biometricEnabled && Biometrics.available(activity)

    if (!setUp) {
        PinForm(
            title = "Welcome to FingerNotes",
            subtitle = "Choose a 4–6 digit PIN or a password. Your data is encrypted with SQLCipher and the Android Keystore.",
            confirm = true, button = "Set PIN & start", error = err,
        ) { pin ->
            scope.launch {
                runCatching { withContext(Dispatchers.Default) { keys.setAppPin(pin) } }
                    .onSuccess { setUp = true; vm.onUnlocked() }
                    .onFailure { err = it.message }
            }
        }
        return
    }
    PinForm(
        title = "FingerNotes is locked",
        error = err,
        biometric = if (bio) ({ Biometrics.prompt(activity, "Unlock FingerNotes", onSuccess = vm::onUnlocked) }) else null,
    ) { pin ->
        scope.launch {
            val ok = withContext(Dispatchers.Default) { keys.verifyAppPin(pin) }
            if (ok) vm.onUnlocked() else err = "Wrong PIN"
        }
    }
}

@Composable
fun HiddenVault(vm: AppViewModel, activity: MainActivity, open: Boolean, onOpen: () -> Unit, onEdit: (app.fingernotes.core.Note, Boolean) -> Unit) {
    val keys = vm.keys
    var hasPin by remember { mutableStateOf(keys.hasVaultPin) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    when {
        open -> NotesScreen(vm, NotesFilter.Hidden, onEdit)
        !hasPin -> PinForm(
            "Set up the Hidden vault", "Hidden notes disappear from the grid and search, and need this second PIN.",
            confirm = true, button = "Create vault", error = err,
        ) { pin ->
            scope.launch {
                runCatching { withContext(Dispatchers.Default) { keys.setVaultPin(pin) } }
                    .onSuccess { hasPin = true; onOpen() }.onFailure { err = it.message }
            }
        }
        else -> PinForm(
            "Hidden notes", button = "Open", error = err,
            biometric = if (vm.settings.value.biometricEnabled && Biometrics.available(activity)) ({ Biometrics.prompt(activity, "Open Hidden notes", onSuccess = onOpen) }) else null,
        ) { pin ->
            scope.launch {
                if (withContext(Dispatchers.Default) { keys.verifyVaultPin(pin) }) onOpen() else err = "Wrong PIN"
            }
        }
    }
}
