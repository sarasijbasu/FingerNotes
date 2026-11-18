package app.fingernotes.sync

import app.fingernotes.core.Signal
import app.fingernotes.core.SignalMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/** Optional free relay (Cloudflare Worker in /signaling-worker). It only ever sees FN1: ciphertext. */
class RelaySignaling(
    private val baseUrl: String,
    private val chainId: String,
    private val key: SecretKey,
    private val onSignal: (SignalMessage) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    fun connect(onOpen: () -> Unit) {
        val url = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/room/" + chainId
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onStatus("Relay connected"); onOpen() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { Signal.decode(key, text) }.onSuccess(onSignal)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onStatus("Relay error: ${t.message}")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onStatus("Relay closed")
        })
    }

    fun send(m: SignalMessage) {
        ws?.send(Signal.encode(key, m))
    }

    fun close() {
        ws?.close(1000, null)
        ws = null
    }
}
