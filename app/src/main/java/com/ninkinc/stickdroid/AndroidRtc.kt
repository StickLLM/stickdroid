package com.ninkinc.stickdroid

import android.content.Context
import com.ninkinc.stickdroid.core.IceServer
import com.ninkinc.stickdroid.core.RtcChannel
import com.ninkinc.stickdroid.core.RtcClosed
import com.ninkinc.stickdroid.core.RtcFactory
import com.ninkinc.stickdroid.core.RtcPeer
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * libwebrtc (io.getstream:stream-webrtc-android, BSD; no Google Play services)
 * adapter for Method C. Data channel only: no audio/video tracks, no mic or
 * camera use by WebRTC.
 */
class AndroidRtcFactory(ctx: Context) : RtcFactory {
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
        )
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun create(iceServers: List<IceServer>): RtcPeer = AndroidPeer(factory, iceServers)
}

private class AndroidPeer(factory: PeerConnectionFactory, ice: List<IceServer>) : RtcPeer {
    private val gathered = CountDownLatch(1)
    private val dcFuture = CompletableFuture<DataChannel>()
    @Volatile private var conn: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW
    private val pc: PeerConnection

    init {
        val servers = ice.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }; s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
            }
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(m: MediaStream) {}
            override fun onRemoveStream(m: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) { dcFuture.complete(dc) }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, m: Array<out MediaStream>) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { conn = s }
        }) ?: throw IllegalStateException("createPeerConnection failed")
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) {}
        override fun onSetFailure(e: String) {}
    }

    override fun answer(offerSdp: String, gatherMs: Long): String {
        val setR = CompletableFuture<Unit>()
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() { setR.complete(Unit) }
            override fun onSetFailure(e: String) { setR.completeExceptionally(RuntimeException(e)) }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        setR.get(15, TimeUnit.SECONDS)
        val ans = CompletableFuture<SessionDescription>()
        pc.createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) { ans.complete(d) }
            override fun onCreateFailure(e: String) { ans.completeExceptionally(RuntimeException(e)) }
        }, MediaConstraints())
        val a = ans.get(15, TimeUnit.SECONDS)
        val setL = CompletableFuture<Unit>()
        pc.setLocalDescription(object : Sdp() {
            override fun onSetSuccess() { setL.complete(Unit) }
            override fun onSetFailure(e: String) { setL.completeExceptionally(RuntimeException(e)) }
        }, a)
        setL.get(15, TimeUnit.SECONDS)
        gathered.await(gatherMs, TimeUnit.MILLISECONDS)
        return pc.localDescription.description
    }

    override fun awaitChannel(timeoutMs: Long): RtcChannel {
        val t0 = System.currentTimeMillis()
        val dc = dcFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        val ch = AndroidChannel(dc)
        while (dc.state() != DataChannel.State.OPEN) {
            if (System.currentTimeMillis() - t0 > timeoutMs) throw RtcClosed("open timeout")
            if (dc.state() == DataChannel.State.CLOSED) throw RtcClosed("closed before open")
            Thread.sleep(20)
        }
        return ch
    }

    override fun pathInfo(): String {
        val f = CompletableFuture<String>()
        pc.getStats { rep ->
            val st = rep.statsMap
            val pair = st.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
                ?: st.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" }
            if (pair == null) { f.complete("unknown"); return@getStats }
            val l = st[pair.members["localCandidateId"]?.toString()]
            val r = st[pair.members["remoteCandidateId"]?.toString()]
            val lt = l?.members?.get("candidateType")?.toString()
            val rt = r?.members?.get("candidateType")?.toString()
            val kind = if (lt == "relay" || rt == "relay") "relay" else "direct"
            f.complete("$kind local=$lt remote=$rt proto=${l?.members?.get("protocol")}")
        }
        return try { f.get(5, TimeUnit.SECONDS) } catch (e: Exception) { "unknown" }
    }

    override fun state(): String = conn.name.lowercase()

    override fun close() {
        try { pc.close() } catch (_: Exception) {}
        try { pc.dispose() } catch (_: Exception) {}
    }
}

private class AndroidChannel(private val dc: DataChannel) : RtcChannel {
    private val q = LinkedBlockingQueue<Any>()
    private object Closed

    init {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() { if (dc.state() == DataChannel.State.CLOSED) q.put(Closed) }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val b = ByteArray(buffer.data.remaining()); buffer.data.get(b); q.put(b)
            }
        })
    }

    override val label: String get() = dc.label()
    override val isOpen: Boolean get() = dc.state() == DataChannel.State.OPEN

    @Synchronized override fun send(b: ByteArray) {
        if (!isOpen) throw RtcClosed("not open")
        val bb = ByteBuffer.allocateDirect(b.size); bb.put(b); bb.flip()
        if (!dc.send(DataChannel.Buffer(bb, true))) throw RtcClosed("send failed")
    }

    override fun recv(timeoutMs: Long): ByteArray? {
        val o = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (o === Closed) { q.put(Closed); throw RtcClosed("datachannel closed") }
        return o as ByteArray
    }
}
