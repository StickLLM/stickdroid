package com.ninkinc.stickdroid.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed class Path {
    data class Udp(val addr: InetSocketAddress) : Path()
    data class Derp(val nodeKey: ByteArray) : Path() {
        override fun equals(other: Any?) = other is Derp && nodeKey.contentEquals(other.nodeKey)
        override fun hashCode() = nodeKey.contentHashCode()
    }
    fun describe(): String = when (this) {
        is Udp -> "direct ${addr.hostString}:${addr.port}"
        is Derp -> "derp ${Bytes.fp(nodeKey)}"
    }
    val kind: String get() = when (this) { is Udp -> "udp"; is Derp -> "derp" }
}

class Endpoint(
    var udpPort: Int = 0,
    val derpUrl: String? = null,
    derpPriv: ByteArray? = null,
    val derpPin: String? = null,
    val derpSni: String? = null,
    var forceRelay: Boolean = false,
    val log: (String) -> Unit = {}
) {
    val derpPriv: ByteArray = derpPriv ?: Crypto.x25519Generate()
    val derpPub: ByteArray = Crypto.x25519Public(this.derpPriv)
    var handler: ((Path, ByteArray) -> Unit)? = null
    private var socket: DatagramSocket? = null
    var derp: DerpClient? = null
        private set
    private var derpThread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start(connectDerp: Boolean = true) {
        val sock = DatagramSocket(udpPort)
        sock.reuseAddress = true
        socket = sock
        udpPort = sock.localPort
        running.set(true)
        thread(isDaemon = true, name = "sl-udp") {
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val data = p.data.copyOfRange(p.offset, p.offset + p.length)
                    if (forceRelay) continue
                    dispatch(Path.Udp(InetSocketAddress(p.address, p.port)), data)
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        if (connectDerp) startDerp()
    }

    fun startDerp() {
        val url = derpUrl ?: return
        if (derpThread != null) return
        derpThread = thread(isDaemon = true, name = "sl-derp") {
            var backoff = 1
            while (running.get()) {
                val c = DerpClient(url, derpPriv, derpPin, derpSni, { src, pkt ->
                    dispatch(Path.Derp(src), pkt)
                }, log)
                try {
                    c.connect()
                    derp = c
                    backoff = 1
                    c.run()
                    log("derp connection closed")
                } catch (e: Exception) {
                    log("derp connect failed: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                } finally {
                    if (derp === c) derp = null
                    c.close()
                }
                if (!running.get()) break
                try { Thread.sleep(backoff * 1000L) } catch (_: InterruptedException) { break }
                backoff = minOf(backoff * 2, 30)
            }
        }
    }

    fun waitDerp(timeoutMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (derp?.connected?.get() == true) return true
            Thread.sleep(100)
        }
        return false
    }

    fun close() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        try { derp?.close() } catch (_: Exception) {}
        derpThread?.interrupt()
    }

    fun send(path: Path, pkt: ByteArray) {
        when (path) {
            is Path.Udp -> {
                if (forceRelay) return
                val sock = socket ?: return
                val p = DatagramPacket(pkt, pkt.size, path.addr)
                try { sock.send(p) } catch (_: Exception) {}
            }
            is Path.Derp -> {
                val d = derp
                if (d != null && d.connected.get()) {
                    try { d.send(path.nodeKey, pkt) } catch (e: Exception) {
                        log("derp send failed: ${e.message?.take(60)}")
                    }
                }
            }
        }
    }

    fun candidates(): List<Map<String, String>> {
        if (forceRelay) return emptyList()
        val out = ArrayList<Map<String, String>>()
        val seen = HashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress.substringBefore('%')
                    val kind = if (addr is java.net.Inet6Address) "v6" else "host"
                    val a = if (':' in ip) "[$ip]:$udpPort" else "$ip:$udpPort"
                    if (seen.add(a)) out.add(mapOf("a" to a, "p" to kind))
                }
            }
        } catch (_: Exception) {}
        return out.take(8)
    }

    private fun dispatch(path: Path, pkt: ByteArray) {
        if (pkt.isEmpty() || pkt.size > Framing.MAX_DGRAM) return
        try { handler?.invoke(path, pkt) } catch (e: Exception) {
            log("drop packet from ${path.kind}: ${e.javaClass.simpleName}")
        }
    }
}

class Peer(val ep: Endpoint, val name: String, val log: (String) -> Unit = {}) {
    val events = CopyOnWriteArrayList<String>()
    var cands: MutableList<InetSocketAddress> = mutableListOf()
    var derpKey: ByteArray? = null
    var session: Noise.Session? = null
    var localIdx: Int = Crypto.random(4).let { ByteArray(4).also { b -> System.arraycopy(it, 0, b, 0, 4) }.let { b ->
        ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
    } }
    var remoteIdx: Int? = null
    var directAddr: InetSocketAddress? = null
    var lastDirectRx: Long = 0
    var lastRxPath: Path? = null
    var onData: ((typ: Int, body: ByteArray, path: Path) -> Unit)? = null
    var sigSid: String? = null
    private var txPath: Path? = null
    var blockUdpRx: Boolean = false

    fun note(ev: String) {
        events.add(ev)
        log("[$name] $ev")
    }

    fun setRemoteCands(list: List<Map<String, String>>) {
        val out = ArrayList<InetSocketAddress>()
        for (x in list.take(8)) {
            val a = x["a"] ?: continue
            try { out.add(parseAddr(a)) } catch (_: Exception) {}
        }
        cands = out
        note("remote candidates: " + (out.joinToString(", ") { "${it.hostString}:${it.port}" }.ifEmpty { "none" }))
    }

    fun bestPath(): Path? {
        val now = System.nanoTime()
        if (directAddr != null && (now - lastDirectRx) < DIRECT_STALE_NS && !ep.forceRelay) {
            return Path.Udp(directAddr!!)
        }
        val dk = derpKey
        if (dk != null) return Path.Derp(dk)
        return lastRxPath
    }

    fun sendData(typ: Int, body: ByteArray = ByteArray(0), path: Path? = null): Boolean {
        val sess = session ?: return false
        val p = path ?: bestPath() ?: return false
        if (path == null && p != txPath) {
            if (txPath != null) note("path -> ${p.describe()}")
            txPath = p
        }
        if (1 + 4 + 8 + 1 + body.size + 16 > Framing.MAX_DGRAM) {
            throw IllegalArgumentException("datagram over ${Framing.MAX_DGRAM} B (Q-N11)")
        }
        val (n, ct) = sess.encrypt(Framing.inner(typ, body))
        ep.send(p, Framing.packData(remoteIdx!!, n, ct))
        return true
    }

    fun rxData(path: Path, n: Long, ct: ByteArray) {
        val sess = session ?: return
        val pt = sess.decrypt(n, ct)
        val prev = bestPath()
        lastRxPath = path
        if (path is Path.Udp) {
            directAddr = path.addr
            lastDirectRx = System.nanoTime()
        } else if (path is Path.Derp) {
            if (derpKey == null) derpKey = path.nodeKey
        }
        val now = bestPath()
        if (prev != now && now != null) note("path -> ${now.describe()}")
        if (pt.isEmpty()) return
        val typ = pt[0].toInt() and 0xff
        val body = pt.copyOfRange(1, pt.size)
        if (typ == Framing.D_PING) sendData(Framing.D_PONG, body, path = path)
        else if (typ == Framing.D_ECHO_REQ) sendData(Framing.D_ECHO_REP, body, path = path)
        onData?.invoke(typ, body, path)
    }

    fun reset() {
        session = null
        remoteIdx = null
        localIdx = java.util.concurrent.ThreadLocalRandom.current().nextInt()
        cands = mutableListOf()
        derpKey = null
        directAddr = null
        lastDirectRx = 0
        lastRxPath = null
        txPath = null
        sigSid = null
    }

    companion object {
        const val DIRECT_STALE_NS: Long = 5_000_000_000L
        fun parseAddr(a: String): InetSocketAddress {
            val s = a.trim()
            val host: String
            val port: Int
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                host = s.substring(1, end)
                port = s.substring(end + 2).toInt()
            } else {
                val i = s.lastIndexOf(':')
                host = s.substring(0, i)
                port = s.substring(i + 1).toInt()
            }
            return InetSocketAddress(InetAddress.getByName(host), port)
        }
    }
}

/** Phone / client initiator (link.py Initiator). */
class Initiator(
    val ep: Endpoint,
    val sPriv: ByteArray,
    val stickPub: ByteArray,
    val psk: ByteArray,
    val home: ByteArray,
    val slot: ByteArray,
    name: String = "stick",
    log: (String) -> Unit = {}
) {
    val peer = Peer(ep, name, log)
    val established = CountDownLatch(1)
    @Volatile var firstPath: Path? = null
    private var hs: Noise.Initiator? = null
    private var msg2: ByteArray? = null
    private var initPkt: ByteArray? = null
    val log = log

    init { ep.handler = ::onPacket }

    fun onPacket(path: Path, pkt: ByteArray) {
        if (peer.blockUdpRx && path is Path.Udp) return
        val t = pkt[0].toInt() and 0xff
        when {
            t == Framing.T_RESP && pkt.size == 1 + 8 + 48 -> {
                val r = Framing.parseResp(pkt) ?: return
                if (r.ridx != peer.localIdx || hs == null) return
                if (msg2 != null) {
                    if (Bytes.ctEq(r.msg2, msg2!!) && path is Path.Udp) {
                        peer.directAddr = path.addr
                        peer.lastDirectRx = System.nanoTime()
                        peer.note("direct path confirmed by duplicate handshake reply: ${path.describe()}")
                    }
                    return
                }
                val (sess, _) = hs!!.readMsg2(r.msg2)
                msg2 = r.msg2
                peer.session = sess
                peer.remoteIdx = r.sidx
                peer.lastRxPath = path
                if (path is Path.Udp) {
                    peer.directAddr = path.addr
                    peer.lastDirectRx = System.nanoTime()
                }
                firstPath = path
                peer.note("handshake ok (Noise IKpsk2) via ${path.describe()}")
                established.countDown()
            }
            t == Framing.T_PUNCH && pkt.size == 9 && path is Path.Udp -> {
                if (!ep.forceRelay && path.addr !in peer.cands) {
                    peer.cands.add(path.addr)
                    peer.note("learned peer-reflexive candidate ${path.addr.hostString}:${path.addr.port}")
                }
                val init = initPkt
                if (hs != null && established.count > 0 && init != null) ep.send(path, init)
            }
            t == Framing.T_DATA && pkt.size >= 1 + 4 + 8 + 16 && peer.session != null -> {
                val d = Framing.parseData(pkt) ?: return
                if (d.ridx == peer.localIdx) peer.rxData(path, d.ctr, d.ct)
            }
        }
    }

    fun handshake(timeoutMs: Long = 15_000): Boolean {
        val prologue = Noise.prologue(home, slot, Crypto.x25519Public(sPriv), stickPub)
        hs = Noise.Initiator(sPriv, stickPub, psk, prologue)
        peer.localIdx = java.util.concurrent.ThreadLocalRandom.current().nextInt()
        val init = Framing.packInit(slot, peer.localIdx, hs!!.writeMsg1())
        initPkt = init
        val t0 = System.currentTimeMillis()
        var derpStarted = false
        while (System.currentTimeMillis() - t0 < timeoutMs && established.count > 0) {
            if (!ep.forceRelay) {
                for (a in peer.cands) ep.send(Path.Udp(a), init)
            }
            val dk = peer.derpKey
            if (System.currentTimeMillis() - t0 >= 1500 && dk != null) {
                if (!derpStarted) {
                    peer.note("no direct reply after 1.5 s; also trying DERP")
                    derpStarted = true
                }
                ep.send(Path.Derp(dk), init)
            }
            established.await(500, TimeUnit.MILLISECONDS)
        }
        return established.count == 0L
    }

    fun maintainLoop(stop: AtomicBoolean) {
        while (!stop.get()) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            if (peer.session == null) continue
            val cur = peer.bestPath()
            if (cur is Path.Derp && !ep.forceRelay && !peer.blockUdpRx) {
                for (a in peer.cands) peer.sendData(Framing.D_PING, Crypto.random(8), path = Path.Udp(a))
            }
            peer.sendData(Framing.D_PING, Crypto.random(8))
        }
    }
}
