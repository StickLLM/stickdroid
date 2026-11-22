package com.ninkinc.stickdroid.core

/**
 * Minimal WebRTC surface Method C needs. The stick (aiortc) is the offerer and
 * creates the ordered DataChannel "sl1"; the phone only answers.
 * Implementations: interop (JVM, webrtc-java) and app (Android, libwebrtc AAR).
 */
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

class RtcClosed(msg: String) : RuntimeException(msg)

interface RtcChannel {
    val label: String
    val isOpen: Boolean
    /** Binary message. Thread-safe. */
    fun send(b: ByteArray)
    /** Next binary message, or null on timeout. Throws [RtcClosed] when the channel is gone. */
    fun recv(timeoutMs: Long): ByteArray?
}

interface RtcPeer {
    /** setRemoteDescription(offer) → createAnswer → setLocalDescription → wait ICE gather (≤ gatherMs). */
    fun answer(offerSdp: String, gatherMs: Long): String
    /** Wait for the stick-created DataChannel to open. */
    fun awaitChannel(timeoutMs: Long): RtcChannel
    /** "direct"/"relay"/"unknown" plus candidate types, for logs. No addresses. */
    fun pathInfo(): String
    /** Connection state as a lowercase word ("connected", "failed", ...). */
    fun state(): String
    fun close()
}

fun interface RtcFactory {
    fun create(iceServers: List<IceServer>): RtcPeer
}
