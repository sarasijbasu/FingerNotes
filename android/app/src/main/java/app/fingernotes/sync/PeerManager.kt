package app.fingernotes.sync

import android.content.Context
import app.fingernotes.core.Signal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel as KChannel

/** Text message channel the sync session runs over (same contract as extension/src/sync/peer.ts). */
interface MessageChannel {
    val incoming: ReceiveChannel<String>
    fun send(text: String)
    fun bufferedAmount(): Long
    fun close()
}

private class RtcChannel(private val dc: DataChannel, private val pc: PeerConnection) : MessageChannel {
    private val queue = KChannel<String>(KChannel.UNLIMITED)
    override val incoming: ReceiveChannel<String> = queue
    val opened = CompletableDeferred<MessageChannel>()

    init {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> opened.complete(this@RtcChannel)
                    DataChannel.State.CLOSED -> queue.close()
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                if (!buffer.binary) queue.trySend(String(bytes, Charsets.UTF_8))
            }
        })
        if (dc.state() == DataChannel.State.OPEN) opened.complete(this)
    }

    override fun send(text: String) {
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }
    override fun bufferedAmount() = dc.bufferedAmount()
    override fun close() {
        queue.close()
        runCatching { dc.close() }
        runCatching { pc.close() }
    }
}

/**
 * Serverless WebRTC peer (Stream WebRTC Android). Non-trickle: ICE gathering completes before
 * the SDP is exported, so the whole handshake fits in one QR code each way.
 */
class Peer(private val factory: PeerConnectionFactory, useStun: Boolean) {
    private val gatheringDone = CompletableDeferred<Unit>()
    private val remoteRtc = CompletableDeferred<MessageChannel>()
    private var failed: ((String) -> Unit)? = null

    private lateinit var pc: PeerConnection

    init {
        pc = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(if (useStun) STUN else emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        },
        object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                if (s == PeerConnection.IceConnectionState.FAILED) failed?.invoke("ICE failed — are both devices on the same network?")
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gatheringDone.complete(Unit)
            }
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {
                dc ?: return
                val ch = RtcChannel(dc, pc)
                ch.opened.invokeOnCompletion { remoteRtc.complete(ch) }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        },
    ) ?: error("Could not create PeerConnection")
    }

    private suspend fun create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = cont.resume(d)
            override fun onCreateFailure(e: String?) = cont.resumeWithException(IllegalStateException(e))
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (offer) pc.createOffer(obs, MediaConstraints()) else pc.createAnswer(obs, MediaConstraints())
    }

    private suspend fun set(local: Boolean, d: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(IllegalStateException(e))
        }
        if (local) pc.setLocalDescription(obs, d) else pc.setRemoteDescription(obs, d)
    }

    private suspend fun localSdp(): String {
        withTimeoutOrNull(5_000) { gatheringDone.await() }
        return Signal.compactSdp(pc.localDescription.description)
    }

    /** Initiator. Returns (offer SDP, channel that completes when the peer connects). */
    suspend fun createOffer(): Pair<String, Deferred<MessageChannel>> {
        val dc = pc.createDataChannel(LABEL, DataChannel.Init().apply { ordered = true })
        val ch = RtcChannel(dc, pc)
        set(true, create(offer = true))
        return localSdp() to ch.opened
    }

    suspend fun acceptAnswer(sdp: String) = set(false, SessionDescription(SessionDescription.Type.ANSWER, sdp))

    /** Responder. Returns (answer SDP, channel that completes when the peer connects). */
    suspend fun answerOffer(offerSdp: String): Pair<String, Deferred<MessageChannel>> {
        set(false, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        set(true, create(offer = false))
        return localSdp() to remoteRtc
    }

    fun onFailure(f: (String) -> Unit) { failed = f }
    fun close() = runCatching { pc.close() }

    companion object {
        const val LABEL = "fingernotes"
        private val STUN = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        )

        @Volatile private var factory: PeerConnectionFactory? = null
        fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
            factory ?: run {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
            }
        }
    }
}
