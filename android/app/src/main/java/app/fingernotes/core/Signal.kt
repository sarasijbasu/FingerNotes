package app.fingernotes.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.crypto.SecretKey

/** QR / copy-paste / relay signaling payloads (docs/PROTOCOL.md §7). */
@Serializable
data class SignalMessage(
    val type: String, // offer | answer | announce
    val sdp: String? = null,
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    /** Recipient deviceId; null = broadcast to the whole chain room. */
    val to: String? = null,
)

object Signal {
    const val PREFIX = "FN1:"

    fun encode(key: SecretKey, msg: SignalMessage): String =
        PREFIX + Crypto.seal(key, Bytes.deflateRaw(WireJson.encodeToString(msg).toByteArray(Charsets.UTF_8)))

    fun decode(key: SecretKey, text: String): SignalMessage {
        val t = text.trim()
        require(t.startsWith(PREFIX)) { "Not a FingerNotes pairing code" }
        val plain = try {
            Bytes.inflateRaw(Crypto.open(key, t.substring(PREFIX.length)))
        } catch (e: Exception) {
            throw IllegalArgumentException("Code belongs to a different sync chain")
        }
        return WireJson.decodeFromString(plain.decodeToString())
    }

    /** Drop TCP candidates so offers fit in one QR code. */
    fun compactSdp(sdp: String) = sdp.split("\r\n").filterNot { it.startsWith("a=candidate") && it.contains(" tcp ", ignoreCase = true) }.joinToString("\r\n")
}
