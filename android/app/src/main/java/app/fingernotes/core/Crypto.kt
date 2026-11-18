package app.fingernotes.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Byte helpers + crypto primitives — identical parameters to extension/src/core/crypto.ts (docs/PROTOCOL.md §1). */
object Bytes {
    private val rng = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun fromB64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    fun fromHex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun deflateRaw(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    fun inflateRaw(data: ByteArray): ByteArray {
        val i = Inflater(true)
        i.setInput(data + byteArrayOf(0)) // dummy byte required by nowrap mode
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
        }
        i.end(); return out.toByteArray()
    }
}

object Crypto {
    const val ENVELOPE_PREFIX = "v1."
    private const val TAG_BITS = 128

    /** PBKDF2-HMAC-SHA256 over the UTF-8 password bytes (explicit, so it matches Web Crypto exactly). */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha256Hex(data: ByteArray) = Bytes.hex(sha256(data))
    fun aesKey(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    /** AES-256-GCM → iv(12) ‖ ciphertext ‖ tag(16). */
    fun encrypt(key: SecretKey, plain: ByteArray, iv: ByteArray = Bytes.random(12)): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    fun seal(key: SecretKey, plain: ByteArray) = ENVELOPE_PREFIX + Bytes.b64url(encrypt(key, plain))
    fun seal(key: SecretKey, text: String) = seal(key, text.toByteArray(Charsets.UTF_8))

    fun open(key: SecretKey, env: String): ByteArray {
        require(env.startsWith(ENVELOPE_PREFIX)) { "Bad envelope" }
        return decrypt(key, Bytes.fromB64url(env.substring(ENVELOPE_PREFIX.length)))
    }
    fun openText(key: SecretKey, env: String) = String(open(key, env), Charsets.UTF_8)
}
