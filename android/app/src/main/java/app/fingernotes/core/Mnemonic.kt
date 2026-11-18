package app.fingernotes.core

import java.text.Normalizer
import javax.crypto.SecretKey

/** BIP39 (English, 24 words) + sync-chain key derivation — docs/PROTOCOL.md §2. */
class Bip39(private val words: List<String>) {
    init { require(words.size == 2048) { "BIP39 wordlist must have 2048 words" } }
    private val index = words.withIndex().associate { it.value to it.index }

    fun generate(): String = fromEntropy(Bytes.random(32))

    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size == 32)
        val bits = StringBuilder()
        for (b in entropy) bits.append(Integer.toBinaryString(b.toInt() and 0xff).padStart(8, '0'))
        val cs = Crypto.sha256(entropy)[0].toInt() and 0xff
        bits.append(Integer.toBinaryString(cs).padStart(8, '0'))
        return (0 until 24).joinToString(" ") { words[bits.substring(it * 11, it * 11 + 11).toInt(2)] }
    }

    fun isValid(mnemonic: String): Boolean {
        val w = normalize(mnemonic).split(" ")
        if (w.size != 24 || w.any { it !in index }) return false
        val bits = w.joinToString("") { Integer.toBinaryString(index[it]!!).padStart(11, '0') }
        val entropy = ByteArray(32) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        val cs = bits.substring(256, 264).toInt(2)
        return (Crypto.sha256(entropy)[0].toInt() and 0xff) == cs
    }

    companion object {
        const val PAIR_PREFIX = "FNPAIR:"
        const val SYNC_SALT = "fingernotes/sync/v1"
        const val SYNC_ITERATIONS = 200_000

        fun normalize(input: String): String =
            Normalizer.normalize(input.replace(PAIR_PREFIX, "").trim().lowercase().split(Regex("\\s+")).joinToString(" "), Normalizer.Form.NFKD)

        fun deriveSyncMaterial(mnemonic: String): SyncMaterial {
            val root = Crypto.pbkdf2(normalize(mnemonic).toByteArray(Charsets.UTF_8), SYNC_SALT.toByteArray(Charsets.UTF_8), SYNC_ITERATIONS, 64)
            val raw = root.copyOfRange(0, 32)
            val chainId = Crypto.sha256Hex(root.copyOfRange(32, 64)).substring(0, 32)
            return SyncMaterial(Crypto.aesKey(raw), raw, chainId)
        }
    }
}

class SyncMaterial(val key: SecretKey, val rawKey: ByteArray, val chainId: String)
