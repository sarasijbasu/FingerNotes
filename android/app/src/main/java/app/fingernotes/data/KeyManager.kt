package app.fingernotes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.fingernotes.core.Bytes
import app.fingernotes.core.Crypto
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android key hierarchy:
 *   AndroidKeyStore AES-256 (hardware-backed, non-exportable)
 *     └─ wraps a random 256-bit master secret (SharedPreferences, ciphertext only)
 *          ├─ SQLCipher passphrase
 *          └─ media-file AES-GCM key (sha256("media" ‖ secret))
 *   App PIN / Hidden-vault PIN: PBKDF2-HMAC-SHA256 verifiers (gate the UI; biometrics via BiometricPrompt).
 * The master secret needs no user auth so the WorkManager purge can open the DB while the UI is locked.
 */
class KeyManager(context: Context) {
    private val prefs = context.getSharedPreferences("fn_keys", Context.MODE_PRIVATE)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Lazily created, Keystore-wrapped master secret. */
    val masterSecret: ByteArray by lazy {
        val stored = prefs.getString("master", null)
        val key = keystoreKey()
        if (stored != null) {
            val data = Bytes.fromB64url(stored)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
                doFinal(data, 12, data.size - 12)
            }
        } else {
            val secret = Bytes.random(32)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            prefs.edit().putString("master", Bytes.b64url(c.iv + c.doFinal(secret))).apply()
            secret
        }
    }

    val dbPassphrase: ByteArray get() = masterSecret.copyOf()
    val mediaKey: SecretKey by lazy { Crypto.aesKey(Crypto.sha256("media".toByteArray() + masterSecret)) }

    // ---------------- PINs ----------------
    val hasAppPin get() = prefs.contains("pin_hash")
    val hasVaultPin get() = prefs.contains("vault_hash")

    fun setAppPin(pin: String) = setPin("pin", pin)
    fun verifyAppPin(pin: String) = verify("pin", pin)
    fun setVaultPin(pin: String) = setPin("vault", pin)
    fun verifyVaultPin(pin: String) = verify("vault", pin)

    private fun setPin(name: String, pin: String) {
        validatePin(pin)
        val salt = Bytes.random(16)
        prefs.edit()
            .putString("${name}_salt", Bytes.b64url(salt))
            .putString("${name}_hash", Bytes.b64url(Crypto.pbkdf2(pin.toByteArray(), salt, PIN_ITERATIONS, 32)))
            .apply()
    }

    private fun verify(name: String, pin: String): Boolean {
        val salt = prefs.getString("${name}_salt", null) ?: return false
        val hash = prefs.getString("${name}_hash", null) ?: return false
        val got = Crypto.pbkdf2(pin.toByteArray(), Bytes.fromB64url(salt), PIN_ITERATIONS, 32)
        return MessageDigest.isEqual(got, Bytes.fromB64url(hash))
    }

    companion object {
        private const val ALIAS = "fingernotes_master"
        const val PIN_ITERATIONS = 120_000

        fun validatePin(pin: String) {
            require(pin.length >= 4) { "PIN/password must be at least 4 characters" }
            require(!(pin.all { it.isDigit() } && pin.length > 6)) { "Numeric PINs are 4–6 digits (or use a password)" }
        }
    }
}
