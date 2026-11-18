package app.fingernotes.sync

import android.content.Context
import app.fingernotes.core.Bip39
import app.fingernotes.core.SignalMessage
import app.fingernotes.core.SyncMaterial
import app.fingernotes.core.WireJson
import app.fingernotes.data.Repository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.webrtc.PeerConnection
import java.util.concurrent.ConcurrentHashMap

@Serializable data class PeerRecord(val deviceId: String, val name: String, val platform: String, val lastSync: Long)

data class OnlinePeer(val name: String, val platform: String, val seen: Long)

enum class AutoStatus { Off, NoChain, NoRelay, Connecting, Online, Syncing }

data class AutoState(
    val status: AutoStatus = AutoStatus.Off,
    val online: Map<String, OnlinePeer> = emptyMap(),
    val lastResult: String? = null,
    val lastSyncAt: Long? = null,
    val log: List<String> = emptyList(),
)

/**
 * Pure device-to-device sync over the internet — same rules as extension/src/sync/auto.ts
 * (docs/PROTOCOL.md §7.1). Devices meet in the worker room for their chainId; the device with the
 * greater deviceId sends a directed WebRTC offer; data flows over the encrypted DataChannel
 * (TURN-relayed only when a direct path is impossible). Nothing is stored off-device.
 */
@OptIn(FlowPreview::class)
class AutoSync(
    private val context: Context,
    private val repo: Repository,
    private val deviceName: () -> String,
    private val useStun: () -> Boolean,
    private val onPeersChanged: (List<PeerRecord>) -> Unit = {},
) {
    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state

    private var scope: CoroutineScope? = null
    private var relay: RelaySignaling? = null
    private var material: SyncMaterial? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    @Volatile private var busy = false
    @Volatile private var quietUntil = 0L
    private val pending = ConcurrentHashMap<String, Peer>()
    private val waiters = mutableListOf<CompletableDeferred<String>>()

    private fun update(f: (AutoState) -> AutoState) { _state.value = f(_state.value) }
    private fun log(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        update { it.copy(log = (it.log + "$t  $s").takeLast(40)) }
    }
    private fun self() = SelfInfo(repo.deviceId, deviceName())
    private fun signal(type: String, sdp: String? = null, to: String? = null) =
        SignalMessage(type, sdp, repo.deviceId, deviceName(), "android", to)

    fun start(relayUrl: String) {
        stop()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            val chain = repo.getMeta("chain")
            if (chain == null) { update { it.copy(status = AutoStatus.NoChain) }; return@launch }
            if (relayUrl.isBlank()) { update { it.copy(status = AutoStatus.NoRelay) }; return@launch }
            val m = Bip39.deriveSyncMaterial(chain).also { material = it }
            ice = fetchIceServers(relayUrl, m.chainId)
            update { it.copy(status = AutoStatus.Connecting) }
            val r = RelaySignaling(
                relayUrl, m.chainId, m.key,
                onSignal = { sig -> sc.launch { runCatching { onSignal(sig) }.onFailure { e -> log("Error: ${e.message}") } } },
                onStatus = { online -> update { it.copy(status = if (!online) AutoStatus.Connecting else if (busy) AutoStatus.Syncing else AutoStatus.Online) } },
                onOpen = { announce() },
            )
            relay = r
            r.connect()
            sc.launch { while (true) { delay(4 * 60_000L); announce() } }
            // Local edits → announce (debounced); changes written by sync itself are ignored.
            sc.launch {
                merge(repo.notes, repo.expenses, repo.labels, repo.categories).drop(4).debounce(3_000).collect {
                    if (!busy && System.currentTimeMillis() > quietUntil) announce()
                }
            }
        }
    }

    fun stop() {
        relay?.close(); relay = null
        pending.values.forEach { it.close() }; pending.clear()
        scope?.cancel(); scope = null
        busy = false
        update { it.copy(status = AutoStatus.Off, online = emptyMap()) }
    }

    private fun announce(to: String? = null) = relay?.send(signal("announce", to = to))

    private suspend fun onSignal(m: SignalMessage) {
        val me = repo.deviceId
        if (m.deviceId == me || (m.to != null && m.to != me)) return
        val now = System.currentTimeMillis()
        update { st -> st.copy(online = st.online.filterValues { now - it.seen < PRESENCE_TTL } + (m.deviceId to OnlinePeer(m.deviceName, m.platform, now))) }
        when (m.type) {
            "announce" -> if (me > m.deviceId) offer(m.deviceId) else if (m.to == null) announce(m.deviceId)
            "offer" -> {
                if (busy || m.sdp == null) return
                val p = Peer(Peer.factory(context), useStun(), ice)
                val (sdp, ch) = p.answerOffer(m.sdp)
                relay?.send(signal("answer", sdp, m.deviceId))
                session(ch, p, m.deviceId)
            }
            "answer" -> pending.remove(m.deviceId)?.acceptAnswer(m.sdp ?: return)
        }
    }

    private suspend fun offer(peerId: String) {
        if (busy || pending.containsKey(peerId)) return
        val p = Peer(Peer.factory(context), useStun(), ice)
        pending[peerId] = p
        val (sdp, ch) = p.createOffer()
        relay?.send(signal("offer", sdp, peerId))
        scope?.launch {
            delay(OFFER_TIMEOUT)
            if (pending.remove(peerId, p)) p.close()
        }
        session(ch, p, peerId)
    }

    private fun session(channel: Deferred<MessageChannel>, p: Peer, peerId: String) {
        val sc = scope ?: return
        sc.launch {
            val ch = withTimeoutOrNull(45_000) { channel.await() } ?: run { p.close(); return@launch }
            if (busy) { ch.close(); return@launch }
            busy = true
            update { it.copy(status = AutoStatus.Syncing) }
            try {
                val r = withTimeout(5 * 60_000L) { SyncSession.run(repo, material!!.key, self(), ch, ::log) }
                val files = r.mediaIn + r.mediaOut
                val summary = "Synced with ${r.peerName}: ↑${r.sent} ↓${r.received}" + if (files > 0) " · $files files" else ""
                update { it.copy(lastResult = summary, lastSyncAt = System.currentTimeMillis()) }
                val old = repo.getMeta("peers")?.let { WireJson.decodeFromString<List<PeerRecord>>(it) } ?: emptyList()
                val list = listOf(PeerRecord(peerId, r.peerName, r.peerPlatform, System.currentTimeMillis())) + old.filter { it.deviceId != peerId }
                repo.setMeta("peers", WireJson.encodeToString(list))
                onPeersChanged(list)
                synchronized(waiters) { waiters.toList().also { waiters.clear() } }.forEach { it.complete(summary) }
                delay(1_500)
            } catch (e: Exception) {
                log("Sync failed: ${e.message}")
            } finally {
                ch.close()
                busy = false
                quietUntil = System.currentTimeMillis() + 2_500
                update { it.copy(status = if (relay?.connected == true) AutoStatus.Online else AutoStatus.Connecting) }
            }
        }
    }

    /** Pull-to-refresh / "Sync now": ask every online chain device to sync; returns a summary. */
    suspend fun refresh(timeoutMs: Long = 12_000): String {
        when (_state.value.status) {
            AutoStatus.NoChain -> return "Set up a sync chain first (Sync screen)"
            AutoStatus.NoRelay -> return "Add your relay URL in Settings to sync over the internet"
            AutoStatus.Off -> return "Sync is not running"
            else -> {}
        }
        val w = CompletableDeferred<String>()
        synchronized(waiters) { waiters += w }
        announce()
        return withTimeoutOrNull(timeoutMs) { w.await() } ?: run {
            synchronized(waiters) { waiters.remove(w) }
            val now = System.currentTimeMillis()
            when {
                relay?.connected != true -> "Relay unreachable — check your internet connection"
                _state.value.online.values.any { now - it.seen < PRESENCE_TTL } -> "Other devices did not respond in time"
                else -> "No other device on your chain is online"
            }
        }
    }

    companion object {
        private const val PRESENCE_TTL = 10 * 60_000L
        private const val OFFER_TIMEOUT = 30_000L
    }
}
