package app.fingernotes.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val lockTimeoutMs: Long = 60_000,
    val biometricEnabled: Boolean = false,
    val signalingUrl: String = "",
    val useStun: Boolean = true,
    val deviceName: String = Build.MODEL ?: "Android",
    val defaultCurrency: String = "₹",
    val gridView: Boolean = true,
)

/** Non-secret preferences (secrets live in the SQLCipher DB / Keystore). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("fn_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state

    private fun load() = AppSettings().let { d ->
        AppSettings(
            lockTimeoutMs = p.getLong("lockTimeoutMs", d.lockTimeoutMs),
            biometricEnabled = p.getBoolean("biometricEnabled", d.biometricEnabled),
            signalingUrl = p.getString("signalingUrl", d.signalingUrl) ?: "",
            useStun = p.getBoolean("useStun", d.useStun),
            deviceName = p.getString("deviceName", d.deviceName) ?: d.deviceName,
            defaultCurrency = p.getString("defaultCurrency", d.defaultCurrency) ?: d.defaultCurrency,
            gridView = p.getBoolean("gridView", d.gridView),
        )
    }

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(state.value)
        p.edit()
            .putLong("lockTimeoutMs", s.lockTimeoutMs)
            .putBoolean("biometricEnabled", s.biometricEnabled)
            .putString("signalingUrl", s.signalingUrl)
            .putBoolean("useStun", s.useStun)
            .putString("deviceName", s.deviceName)
            .putString("defaultCurrency", s.defaultCurrency)
            .putBoolean("gridView", s.gridView)
            .apply()
        state.value = s
    }
}
