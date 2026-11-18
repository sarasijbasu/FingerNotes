package app.fingernotes.sync

import app.fingernotes.core.Bytes
import app.fingernotes.core.Crypto
import app.fingernotes.core.Manifest
import app.fingernotes.core.MediaBlob
import app.fingernotes.core.Merge
import app.fingernotes.core.RecordsPayload
import app.fingernotes.core.SyncStore
import app.fingernotes.core.WireJson
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.SecretKey

data class SelfInfo(val deviceId: String, val deviceName: String, val platform: String = "android")

data class SyncResult(
    var peerId: String = "?",
    var peerName: String = "?",
    var peerPlatform: String = "?",
    var sent: Int = 0,
    var received: Int = 0,
    var purged: Int = 0,
    var mediaIn: Int = 0,
    var mediaOut: Int = 0,
)

/** One symmetric sync exchange — wire-identical to extension/src/sync/session.ts (docs/PROTOCOL.md §6). */
object SyncSession {
    const val MEDIA_CHUNK = 32 * 1024

    private fun withType(t: String, body: JsonObject? = null) = buildJsonObject {
        put("t", t)
        body?.forEach { (k, v) -> put(k, v) }
    }

    suspend fun run(store: SyncStore, key: SecretKey, self: SelfInfo, ch: MessageChannel, log: (String) -> Unit = {}): SyncResult {
        val result = SyncResult()
        val parts = HashMap<String, Array<ByteArray?>>()
        var sentDone = false
        var gotDone = false

        suspend fun send(o: JsonObject) {
            while (ch.bufferedAmount() > 1_000_000) delay(20)
            ch.send(Crypto.seal(key, o.toString()))
        }

        send(withType("hello", buildJsonObject {
            put("v", 1); put("deviceId", self.deviceId); put("deviceName", self.deviceName); put("platform", self.platform)
        }))
        send(withType("manifest", WireJson.encodeToJsonElement(Merge.buildManifest(store)).jsonObject))

        while (!(sentDone && gotDone)) {
            val text = ch.incoming.receiveCatching().getOrNull() ?: throw IOException("Connection closed before sync finished")
            val msg = WireJson.parseToJsonElement(Crypto.openText(key, text)).jsonObject
            when (msg["t"]?.jsonPrimitive?.content) {
                "hello" -> {
                    result.peerId = msg["deviceId"]?.jsonPrimitive?.content ?: "?"
                    result.peerName = msg["deviceName"]?.jsonPrimitive?.content ?: "?"
                    result.peerPlatform = msg["platform"]?.jsonPrimitive?.content ?: "?"
                    log("Connected to ${result.peerName} (${result.peerPlatform})")
                }
                "manifest" -> {
                    val peer = WireJson.decodeFromJsonElement(Manifest.serializer(), msg)
                    val diff = Merge.diffForPeer(store, peer)
                    send(withType("records", WireJson.encodeToJsonElement(diff).jsonObject))
                    result.sent = diff.size
                    log("Sent ${diff.size} changes")
                    for (id in Merge.mediaMissingAtPeer(store, peer)) {
                        val blob = store.getMedia(id) ?: continue
                        val total = maxOf(1, (blob.bytes.size + MEDIA_CHUNK - 1) / MEDIA_CHUNK)
                        for (seq in 0 until total) {
                            val chunk = blob.bytes.copyOfRange(seq * MEDIA_CHUNK, minOf(blob.bytes.size, (seq + 1) * MEDIA_CHUNK))
                            send(withType("media", buildJsonObject {
                                put("id", id); put("ownerId", blob.ownerId); put("mime", blob.mime)
                                put("seq", seq); put("total", total); put("data", Bytes.b64url(chunk))
                            }))
                        }
                        result.mediaOut++
                    }
                    send(withType("done"))
                    sentDone = true
                }
                "records" -> {
                    val r = Merge.applyRecords(store, WireJson.decodeFromJsonElement(RecordsPayload.serializer(), msg))
                    result.received += r.applied; result.purged += r.purged
                    log("Applied ${r.applied} changes, ${r.purged} purges")
                }
                "media" -> {
                    val id = msg.str("id"); val total = msg["total"]!!.jsonPrimitive.int; val seq = msg["seq"]!!.jsonPrimitive.int
                    val arr = parts.getOrPut(id) { arrayOfNulls(total) }
                    arr[seq] = Bytes.fromB64url(msg.str("data"))
                    if (arr.all { it != null }) {
                        parts.remove(id)
                        val out = ByteArrayOutputStream(); arr.forEach { out.write(it!!) }
                        val owner = msg.str("ownerId")
                        if (!store.hasTombstone(owner)) store.putMedia(MediaBlob(id, owner, msg.str("mime"), out.toByteArray()))
                        result.mediaIn++
                    }
                }
                "done" -> gotDone = true
            }
        }
        log("Sync complete")
        return result
    }

    private fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content
}
