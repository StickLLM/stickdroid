package com.ninkinc.stickdroid.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/**
 * Minimal DERP v2 client (derp/derp.go), matching sticklink derp.py.
 * Frames: type(1) || len(4 BE) || payload. Login uses NaCl box of ClientInfo JSON.
 */
class DerpError(msg: String) : RuntimeException(msg)

class DerpClient(
    private val url: String,
    private val priv: ByteArray,
    private val pinSha256: String? = null,
    private val sni: String? = null,
    private val onPacket: (src: ByteArray, pkt: ByteArray) -> Unit,
    private val log: (String) -> Unit = {}
) {
    val pub: ByteArray = Crypto.x25519Public(priv)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()
    val connected = AtomicBoolean(false)
    @Volatile var serverKey: ByteArray? = null
        private set

    fun connect(timeoutMs: Int = 10_000) {
        val u = URI(url)
        val tls = u.scheme == "https"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val host = u.host
        val sock: Socket
        if (tls) {
            val ctx = SSLContext.getInstance("TLS")
            val tm = if (pinSha256 != null) pinningTrustManager() else defaultTm()
            ctx.init(null, arrayOf(tm), SecureRandom())
            val ssl = ctx.socketFactory.createSocket() as SSLSocket
            val params = ssl.sslParameters
            val name = sni ?: host
            params.serverNames = listOf(SNIHostName(name))
            if (pinSha256 != null) {
                ssl.soTimeout = timeoutMs
                // hostname check off when pinning the leaf
            } else {
                params.endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.sslParameters = params
            ssl.connect(InetSocketAddress(host, port), timeoutMs)
            ssl.startHandshake()
            if (pinSha256 != null) {
                val der = (ssl.session.peerCertificates[0] as X509Certificate).encoded
                val got = Bytes.hex(MessageDigest.getInstance("SHA-256").digest(der))
                if (!got.equals(normalisePin(pinSha256), ignoreCase = true)) {
                    ssl.close()
                    throw DerpError("relay certificate does not match the pinned SHA-256")
                }
            }
            sock = ssl
        } else {
            sock = Socket()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
        }
        sock.soTimeout = 0
        socket = sock
        val inp = DataInputStream(sock.getInputStream())
        val out = DataOutputStream(sock.getOutputStream())
        input = inp
        output = out
        val path = if (u.path.isNullOrEmpty()) "/derp" else u.path
        val req = "GET $path HTTP/1.1\r\nHost: ${u.authority}\r\nUpgrade: DERP\r\nConnection: Upgrade\r\nUser-Agent: stickdroid\r\n\r\n"
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()
        val head = readHttpHead(inp)
        if (!head.contains(" 101 ")) throw DerpError("upgrade refused: ${head.lineSequence().firstOrNull()}")
        val (t, p) = readFrame(inp)
        if (t != F_SERVER_KEY || p.size < 40 || !p.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw DerpError("bad server key frame")
        }
        serverKey = p.copyOfRange(8, 40)
        val info = """{"version":2,"CanAckPings":true}""".toByteArray(Charsets.UTF_8)
        val boxed = Crypto.naclBox(priv, serverKey!!, info)
        writeFrame(F_CLIENT_INFO, pub + boxed)
        val (t2, p2) = readFrame(inp)
        if (t2 != F_SERVER_INFO) throw DerpError("expected ServerInfo, got 0x${t2.toString(16)}")
        // ServerInfo is a nacl box; decrypt to check admission
        Crypto.naclBoxOpen(priv, serverKey!!, p2)
        writeFrame(F_NOTE_PREFERRED, byteArrayOf(0x01))
        connected.set(true)
        log("derp connected $url as ${Bytes.fp(pub)}")
    }

    fun send(dst: ByteArray, pkt: ByteArray) {
        require(dst.size == 32)
        if (pkt.size > MAX_PACKET) throw DerpError("packet too big")
        writeFrame(F_SEND, dst + pkt)
    }

    /** Blocking read loop. Returns when the connection closes. */
    fun run() {
        val inp = input ?: return
        try {
            while (true) {
                val (t, p) = readFrame(inp)
                when (t) {
                    F_RECV -> if (p.size >= 32) onPacket(p.copyOfRange(0, 32), p.copyOfRange(32, p.size))
                    F_PING -> if (p.size == 8) writeFrame(F_PONG, p)
                    F_HEALTH -> if (p.isNotEmpty()) log("derp health: ${p.decodeToString().take(80)}")
                    F_PEER_GONE, F_PEER_PRESENT, F_KEEPALIVE, F_RESTARTING -> {}
                }
            }
        } catch (_: Exception) {
        } finally {
            connected.set(false)
        }
    }

    fun close() {
        connected.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun writeFrame(t: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val out = output ?: throw DerpError("not connected")
            out.writeByte(t)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x44, 0x45, 0x52, 0x50, 0xf0.toByte(), 0x9f.toByte(), 0x94.toByte(), 0x91.toByte())
        const val F_SERVER_KEY = 0x01
        const val F_CLIENT_INFO = 0x02
        const val F_SERVER_INFO = 0x03
        const val F_SEND = 0x04
        const val F_RECV = 0x05
        const val F_KEEPALIVE = 0x06
        const val F_NOTE_PREFERRED = 0x07
        const val F_PEER_GONE = 0x08
        const val F_PEER_PRESENT = 0x09
        const val F_PING = 0x12
        const val F_PONG = 0x13
        const val F_HEALTH = 0x14
        const val F_RESTARTING = 0x15
        const val MAX_FRAME = 1 shl 20
        const val MAX_PACKET = 64 shl 10

        fun normalisePin(p: String): String {
            val h = p.trim().lowercase()
                .removePrefix("sha256:")
                .removePrefix("sha256 fingerprint=")
                .replace(":", "")
            require(h.length == 64 && h.all { it in "0123456789abcdef" }) { "pin must be a SHA-256 hex fingerprint" }
            return h
        }

        fun nodekeyStr(pub: ByteArray): String = "nodekey:" + Bytes.hex(pub)
        fun parseNodekey(s: String): ByteArray {
            require(s.startsWith("nodekey:") && s.length == 8 + 64) { "bad nodekey" }
            return Bytes.fromHex(s.substring(8))
        }

        private fun readFrame(inp: DataInputStream): Pair<Int, ByteArray> {
            val t = inp.readUnsignedByte()
            val n = inp.readInt()
            if (n < 0 || n > MAX_FRAME) throw DerpError("frame too large")
            val p = ByteArray(n)
            inp.readFully(p)
            return t to p
        }

        private fun readHttpHead(inp: DataInputStream): String {
            val buf = StringBuilder()
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val c = inp.read()
                    if (c < 0) throw DerpError("eof during HTTP upgrade")
                    if (c == '\n'.code) break
                    if (c != '\r'.code) line.append(c.toChar())
                }
                buf.append(line).append("\n")
                if (line.isEmpty()) break
            }
            return buf.toString()
        }

        private fun defaultTm(): X509TrustManager {
            val kmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            kmf.init(null as java.security.KeyStore?)
            return kmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private fun pinningTrustManager(): X509TrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                // Pin is checked on the leaf after handshake.
                if (c.isEmpty()) throw java.security.cert.CertificateException("no cert")
            }
        }
    }
}
