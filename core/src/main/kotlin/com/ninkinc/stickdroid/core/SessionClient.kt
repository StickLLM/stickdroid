package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class ConnectConfig(
    val ws: String,
    val derp: String? = null,
    val derpPin: String? = null,
    val derpSni: String? = null,
    val forceRelay: Boolean = false,
    val blockUdpRx: Boolean = false,
    val region: String = "tor1"
)

data class EchoResult(
    val handshake: Boolean,
    val handshakeMs: Long,
    val firstPath: String?,
    val sent: Int = 0,
    val echoed: Int = 0,
    val byPath: Map<String, Int> = emptyMap(),
    val rttMsMedian: Map<String, Double> = emptyMap(),
    val finalPath: String? = null,
    val events: List<String> = emptyList()
)

/**
 * Join the stick's signalling slot, exchange cand/derp boxes, run IKpsk2 as
 * initiator, then optionally echo or carry companion APP messages.
 * Keeps the signalling WebSocket open after candidates (phone rule).
 */
class SessionClient(
    val state: PairingState,
    val cfg: ConnectConfig,
    val log: (String) -> Unit = {}
) {
    lateinit var ep: Endpoint
        private set
    lateinit var ini: Initiator
        private set
    var sig: SigClient? = null
        private set
    val appRx = LinkedBlockingQueue<ByteArray>()
    val reasm = Fragments.Reassembly()
    private val stop = AtomicBoolean(false)
    private var maintain: Thread? = null
    private val envk: ByteArray = Derive.envKey(state.psk())

    fun connect(joinTimeoutMs: Long = 60_000): Boolean {
        ep = Endpoint(
            derpUrl = cfg.derp, derpPriv = state.derp(), derpPin = cfg.derpPin,
            derpSni = cfg.derpSni, forceRelay = cfg.forceRelay, log = log
        )
        ep.start(connectDerp = false)
        ini = Initiator(ep, state.sPriv(), state.stickStatic(), state.psk(), state.homeBytes(), state.slotBytes(), log = log)
        ini.peer.blockUdpRx = cfg.blockUdpRx
        ini.peer.onData = { typ, body, path ->
            if (typ == Framing.D_APP) {
                val piece = Fragments.parse(body)
                val msg = if (piece != null) reasm.push(piece) else body
                if (msg != null) appRx.offer(msg)
            }
        }
        val cl = SigClient(cfg.ws, log = log)
        sig = cl
        cl.connect(mapOf("home" to state.home))
        var joined: JsonObject? = null
        val tEnd = System.currentTimeMillis() + joinTimeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < tEnd) {
            attempt++
            try {
                val mac = Derive.slotJoinMac(Derive.joinKey(state.psk()), cl.nonce!!, state.homeBytes(), state.slotBytes())
                val j = cl.request(SigJson.obj("t" to "join", "home" to state.home, "slot" to state.slot, "mac" to Bytes.b64u(mac)))
                if (j["peer"]?.jsonPrimitive?.contentOrNull == "online") {
                    joined = j
                    break
                }
                log("stick offline; retrying join in 2 s")
            } catch (e: SigError) {
                log("join failed: ${e.code}")
                return false
            }
            cl.close()
            Thread.sleep(2000)
            val ncl = SigClient(cfg.ws, log = log)
            ncl.connect(mapOf("home" to state.home))
            sig = ncl
        }
        val j = joined ?: run { log("stick never came online"); return false }
        log("joined as ${j["conn"]?.jsonPrimitive?.contentOrNull}, stick online")
        if (cfg.derp != null) {
            try {
                sig!!.request(SigJson.obj("t" to "relay.reg", "nk" to DerpClient.nodekeyStr(ep.derpPub), "rg" to cfg.region))
                log("relay.reg ok (${Bytes.fp(ep.derpPub)})")
            } catch (e: SigError) {
                log("relay.reg failed: ${e.code}")
            }
            ep.startDerp()
            if (!ep.waitDerp(10_000)) log("DERP not connected (relay fallback unavailable)")
        }
        val sid = Bytes.b64u(Crypto.random(8))
        ini.peer.sigSid = sid
        val cands = ep.candidates()
        val candInner = buildJsonObject {
            put("k", "cand"); put("sid", sid); put("seq", 1)
            put("c", buildJsonArray { for (c in cands) add(buildJsonObject { put("a", c["a"]!!); put("p", c["p"]!!) }) })
        }
        sig!!.send(SigJson.obj("t" to "sig", "to" to "stick", "box" to Derive.sealBox(envk, state.homeBytes(), state.slotBytes(), Derive.DIR_C2S, candInner.toString().toByteArray())))
        if (cfg.derp != null) {
            val derpInner = buildJsonObject {
                put("k", "derp"); put("sid", sid)
                put("nk", DerpClient.nodekeyStr(ep.derpPub)); put("rg", cfg.region)
            }
            sig!!.send(SigJson.obj("t" to "sig", "to" to "stick", "box" to Derive.sealBox(envk, state.homeBytes(), state.slotBytes(), Derive.DIR_C2S, derpInner.toString().toByteArray())))
        }
        log("sent ${cands.size} candidates${if (cfg.forceRelay) " (force-relay)" else ""}")
        // Keep WSS open and ingest cand/derp (and later rev) in a background reader.
        thread(isDaemon = true, name = "sl-sig-rx") { sigReader() }
        val deadline = System.currentTimeMillis() + 5_000
        var gotCand = false
        var gotDerp = cfg.derp == null
        while (System.currentTimeMillis() < deadline && !(gotCand && gotDerp)) {
            Thread.sleep(50)
            if (ini.peer.cands.isNotEmpty()) gotCand = true
            if (ini.peer.derpKey != null) gotDerp = true
        }
        val t0 = System.currentTimeMillis()
        val ok = ini.handshake()
        log(if (ok) "handshake ${System.currentTimeMillis() - t0} ms via ${ini.firstPath?.kind}" else "handshake failed")
        if (ok) {
            maintain = thread(isDaemon = true, name = "sl-maintain") { ini.maintainLoop(stop) }
        }
        return ok
    }

    private fun sigReader() {
        val cl = sig ?: return
        while (!stop.get() && !cl.closed) {
            val m = cl.events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val t = m["t"]?.jsonPrimitive?.contentOrNull
            try {
                when (t) {
                    "sig" -> {
                        val inner = Json.parseToJsonElement(
                            Derive.openBox(envk, state.homeBytes(), state.slotBytes(), Derive.DIR_S2C, m["box"]!!.jsonPrimitive.content).toString(Charsets.UTF_8)
                        ).jsonObject
                        when (inner["k"]?.jsonPrimitive?.contentOrNull) {
                            "cand" -> {
                                val list = inner["c"]?.jsonArray?.map { e ->
                                    val o = e.jsonObject
                                    mapOf("a" to o["a"]!!.jsonPrimitive.content, "p" to (o["p"]?.jsonPrimitive?.content ?: "host"))
                                } ?: emptyList()
                                ini.peer.setRemoteCands(list)
                            }
                            "derp" -> {
                                ini.peer.derpKey = DerpClient.parseNodekey(inner["nk"]!!.jsonPrimitive.content)
                                ini.peer.note("stick derp key ${Bytes.fp(ini.peer.derpKey!!)}")
                            }
                        }
                    }
                    "_closed" -> log("signalling closed (${m["code"]})")
                    "err" -> log("signalling err ${m["code"]}")
                }
            } catch (e: Exception) {
                log("sig event $t failed: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }
    }

    fun echo(durationMs: Long = 6_000, intervalMs: Long = 250): EchoResult {
        val sentAt = ConcurrentHashMap<Int, Long>()
        val echoes = ConcurrentHashMap<Int, Pair<String, Double>>()
        val prev = ini.peer.onData
        ini.peer.onData = { typ, body, path ->
            prev?.invoke(typ, body, path)
            if (typ == Framing.D_ECHO_REP && body.size >= 4) {
                val seq = ((body[0].toInt() and 0xff) shl 24) or ((body[1].toInt() and 0xff) shl 16) or
                    ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
                val t0 = sentAt[seq]
                if (t0 != null && !echoes.containsKey(seq)) {
                    echoes[seq] = path.kind to ((System.nanoTime() - t0) / 1e6)
                }
            }
        }
        var seq = 0
        val tEnd = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < tEnd) {
            seq++
            sentAt[seq] = System.nanoTime()
            val body = ByteArray(4 + 32)
            body[0] = (seq ushr 24).toByte(); body[1] = (seq ushr 16).toByte()
            body[2] = (seq ushr 8).toByte(); body[3] = seq.toByte()
            Crypto.random(32).copyInto(body, 4)
            ini.peer.sendData(Framing.D_ECHO_REQ, body)
            Thread.sleep(intervalMs)
        }
        Thread.sleep(1000)
        val by = HashMap<String, MutableList<Double>>()
        for ((_, v) in echoes) by.getOrPut(v.first) { mutableListOf() }.add(v.second)
        val last = echoes.keys.maxOrNull()
        return EchoResult(
            handshake = true,
            handshakeMs = 0,
            firstPath = ini.firstPath?.kind,
            sent = seq,
            echoed = echoes.size,
            byPath = by.mapValues { it.value.size },
            rttMsMedian = by.mapValues { (_, v) -> v.sorted()[v.size / 2] },
            finalPath = last?.let { echoes[it]?.first },
            events = ini.peer.events.filter { "path" in it || "handshake" in it }
        )
    }

    fun sendApp(message: ByteArray) {
        val seq = nextAppSeq.getAndIncrement()
        for (frag in Fragments.split(seq, message)) {
            ini.peer.sendData(Framing.D_APP, frag)
        }
    }

    fun recvApp(timeoutMs: Long): ByteArray? = appRx.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun close() {
        stop.set(true)
        try { sig?.close() } catch (_: Exception) {}
        try { ep.close() } catch (_: Exception) {}
    }

    private val nextAppSeq = AtomicInteger(1)
}
