package app.fingernotes.ui

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-level lock: locked on cold start, after `lockTimeoutMs` of inactivity in the foreground,
 * and when returning from background after the timeout (0 = lock as soon as the app is hidden).
 */
class AppLock(private val timeoutMs: () -> Long) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked
    private var lastActive = SystemClock.elapsedRealtime()
    private var backgroundedAt = -1L

    fun unlock() { _locked.value = false; touch() }
    fun lock() { _locked.value = true }
    fun touch() { lastActive = SystemClock.elapsedRealtime() }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
        if (timeoutMs() == 0L) lock()
    }

    fun onForeground() {
        if (backgroundedAt >= 0 && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs()) lock()
        backgroundedAt = -1
        touch()
    }

    /** Polled while in foreground. */
    fun checkIdle() {
        val t = timeoutMs()
        if (!_locked.value && t > 0 && SystemClock.elapsedRealtime() - lastActive > t) lock()
    }
}

object Biometrics {
    fun available(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("FingerNotes")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build(),
        )
    }
}
