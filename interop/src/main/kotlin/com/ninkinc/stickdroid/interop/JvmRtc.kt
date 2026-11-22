package com.ninkinc.stickdroid.interop

import com.ninkinc.stickdroid.core.IceServer
import com.ninkinc.stickdroid.core.RtcChannel
import com.ninkinc.stickdroid.core.RtcClosed
import com.ninkinc.stickdroid.core.RtcFactory
import com.ninkinc.stickdroid.core.RtcPeer
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** webrtc-java (libwebrtc) adapter for the JVM harness. Same engine family as Chrome/Android. */
class JvmRtcFactory : RtcFactory {
    private val factory by lazy { PeerConnectionFactory() }
    override fun create(iceServers: List<IceServer>): RtcPeer = JvmPeer(factory, iceServers)
}

private class JvmPeer(factory: PeerConnectionFactory, ice: List<IceServer>) : RtcPeer {
    private val gathered = CountDownLatch(1)
    private val dcFuture = CompletableFuture<RTCDataChannel>()
    @Volatile private var conn: RTCPeerConnectionState = RTCPeerConnectionState.NEW
    private val pc: RTCPeerConnection

    init {
        val cfg = RTCConfiguration()
        cfg.iceServers = ice.map { s -> RTCIceServer().apply { urls = s.urls; username = s.username; password = s.credential } }
        pc = factory.createPeerConnection(cfg, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                if (state == RTCIceGatheringState.COMPLETE) gathered.countDown()
            }
            override fun onConnectionChange(state: RTCPeerConnectionState) { conn = state }
            override fun onDataChannel(dc: RTCDataChannel) { dcFuture.complete(dc) }
        })
    }

    override fun answer(offerSdp: String, gatherMs: Long): String {
        val setR = CompletableFuture<Unit>()
        pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, offerSdp), obs(setR))
        setR.get(15, TimeUnit.SECONDS)
        val ans = CompletableFuture<RTCSessionDescription>()
        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) { ans.complete(d) }
            override fun onFailure(e: String) { ans.completeExceptionally(RuntimeException(e)) }
        })
        val a = ans.get(15, TimeUnit.SECONDS)
        val setL = CompletableFuture<Unit>()
        pc.setLocalDescription(a, obs(setL))
        setL.get(15, TimeUnit.SECONDS)
        gathered.await(gatherMs, TimeUnit.MILLISECONDS) // browser: send after 8 s even if still gathering
        return pc.localDescription.sdp
    }

    private fun obs(f: CompletableFuture<Unit>) = object : SetSessionDescriptionObserver {
        override fun onSuccess() { f.complete(Unit) }
        override fun onFailure(e: String) { f.completeExceptionally(RuntimeException(e)) }
    }

    override fun awaitChannel(timeoutMs: Long): RtcChannel {
        val t0 = System.currentTimeMillis()
        val dc = dcFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        val ch = JvmChannel(dc)
        while (dc.state != RTCDataChannelState.OPEN) {
            if (System.currentTimeMillis() - t0 > timeoutMs) throw RtcClosed("open timeout")
            if (dc.state == RTCDataChannelState.CLOSED) throw RtcClosed("closed before open")
            Thread.sleep(20)
        }
        return ch
    }

    override fun pathInfo(): String {
        val f = CompletableFuture<String>()
        pc.getStats { rep ->
            val stats = rep.stats
            val pair = stats.values.firstOrNull {
                it.type == RTCStatsType.CANDIDATE_PAIR && it.attributes["state"]?.toString() == "succeeded" &&
                    (it.attributes["nominated"] == true)
            } ?: stats.values.firstOrNull { it.type == RTCStatsType.CANDIDATE_PAIR && it.attributes["state"]?.toString() == "succeeded" }
            if (pair == null) { f.complete("unknown"); return@getStats }
            val l = stats[pair.attributes["localCandidateId"]?.toString()]
            val r = stats[pair.attributes["remoteCandidateId"]?.toString()]
            val lt = l?.attributes?.get("candidateType")?.toString()
            val rt = r?.attributes?.get("candidateType")?.toString()
            val proto = l?.attributes?.get("protocol")?.toString()
            val kind = if (lt == "relay" || rt == "relay") "relay" else "direct"
            f.complete("$kind local=$lt remote=$rt proto=$proto")
        }
        return try { f.get(5, TimeUnit.SECONDS) } catch (e: Exception) { "unknown" }
    }

    override fun state(): String = conn.name.lowercase()

    override fun close() { try { pc.close() } catch (_: Exception) {} }
}

private class JvmChannel(private val dc: RTCDataChannel) : RtcChannel {
    private val q = LinkedBlockingQueue<Any>()
    private object Closed

    init {
        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() { if (dc.state == RTCDataChannelState.CLOSED) q.put(Closed) }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                val b = ByteArray(buffer.data.remaining()); buffer.data.get(b); q.put(b)
            }
        })
    }

    override val label: String get() = dc.label
    override val isOpen: Boolean get() = dc.state == RTCDataChannelState.OPEN

    @Synchronized override fun send(b: ByteArray) {
        if (!isOpen) throw RtcClosed("not open")
        val bb = ByteBuffer.allocateDirect(b.size); bb.put(b); bb.flip()
        dc.send(RTCDataChannelBuffer(bb, true))
    }

    override fun recv(timeoutMs: Long): ByteArray? {
        val o = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (o === Closed) { q.put(Closed); throw RtcClosed("datachannel closed") }
        return o as ByteArray
    }
}
