package app.fingernotes.sync

import app.fingernotes.core.Signal
import app.fingernotes.core.SignalMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.PeerConnection
import java.util.Timer
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import kotlin.concurrent.schedule

fun relayBase(url: String) = url.trim().trimEnd('/')

private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()

/** STUN + short-lived TURN credentials from the sync helper's /turn endpoint (blocking — call off the main thread). */
fun fetchIceServers(baseUrl: String, chainId: String): List<PeerConnection.IceServer> = runCatching {
    val url = relayBase(baseUrl).replaceFirst(Regex("^ws"), "http") + "/turn?room=" + chainId
    http.newCall(Request.Builder().url(url).build()).execute().use { res ->
        if (!res.isSuccessful) return emptyList()
        val root = app.fingernotes.core.WireJson.parseToJsonElement(res.body!!.string()).jsonObject
        (root["iceServers"] as? JsonArray ?: return emptyList()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val urls = when (val u = o["urls"]) {
                is JsonArray -> u.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(u.content)
                else -> return@mapNotNull null
            }
            PeerConnection.IceServer.builder(urls).apply {
                o["username"]?.jsonPrimitive?.content?.let { setUsername(it) }
                o["credential"]?.jsonPrimitive?.content?.let { setPassword(it) }
            }.createIceServer()
        }
    }
}.getOrDefault(emptyList())

/**
 * WebSocket room on the sync helper worker (see /signaling-worker). Only ever carries FN1: ciphertext.
 * Reconnects with exponential backoff until [close].
 */
class RelaySignaling(
    private val baseUrl: String,
    private val chainId: String,
    private val key: SecretKey,
    private val onSignal: (SignalMessage) -> Unit,
    private val onStatus: (online: Boolean) -> Unit,
    private val onOpen: () -> Unit = {},
) {
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile var connected = false
        private set
    private var retry = 0
    private val timer = Timer("relay-reconnect", true)

    fun connect() {
        closed = false
        open()
    }

    private fun open() {
        val url = relayBase(baseUrl).replaceFirst(Regex("^http"), "ws") + "/room/" + chainId
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retry = 0; connected = true
                onStatus(true); onOpen()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { Signal.decode(key, text) }.onSuccess(onSignal)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
        })
    }

    private fun dropped() {
        connected = false
        onStatus(false)
        if (closed) return
        val delay = minOf(30_000L, 1000L shl minOf(retry++, 5))
        timer.schedule(delay) { if (!closed) open() }
    }

    fun send(m: SignalMessage) {
        if (connected) ws?.send(Signal.encode(key, m))
    }

    fun close() {
        closed = true
        connected = false
        ws?.close(1000, null)
        ws = null
        timer.cancel()
    }
}
