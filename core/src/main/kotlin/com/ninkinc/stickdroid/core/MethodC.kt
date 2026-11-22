package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone side of StickLink Method C, Milestone A — a native port of
 * sticklink-server/worker/public/browser/agent_join_client.js (runPairCAgent),
 * talking to overlay/usr/local/lib/sticklink/methodc_peer.py on the stick.
 *
 *  1. WSS  <ws>?mbx=<b64u M>  subprotocol sticklink.1  → {t:welcome, n}
 *  2. {t:pair.join, id:1, mbx, mac=HMAC(jk_qr(P), "sl1/qr-join-mac"||n||M)} → {t:pair.joined, conn, home}
 *  3. env_k-sealed pmsg: → {k:need-offer,v:1}  ← {k:offer,sdp}  → {k:answer,sdp}
 *  4. WebRTC DataChannel "sl1" (stick offers, STUN only, no TURN)
 *  5. Noise_IKpsk2 over the DC, prologue "sl1/qr"||1||home||M||K, psk = boot_psk(P, TH)
 *  6. PT JSON: → ready{static_pub}  → r_i  ← r_r  → confirm_i  ← confirm_r
 *  7. ← agent_ready  → APP-MUX v1 (http_req / ws_open ...)  [AppMux]
 *
 * Milestone A has no durable pairing: the phone's Noise static is fresh per
 * session and the stick keeps no device record, so every session is a fresh scan.
 */
class MethodCError(val kind: Kind, msg: String) : RuntimeException(msg) {
    enum class Kind { BAD_LINK, EXPIRED, MAILBOX_GONE, RATE_LIMITED, STICK_BUSY, NETWORK, WEBRTC, HANDSHAKE, AGENT }

    /** Short user-facing text. */
    fun userText(): String = when (kind) {
        Kind.BAD_LINK -> "That QR isn't a StickLink pairing code."
        Kind.EXPIRED -> "This QR has expired. Tap Refresh StickLink QR on the kiosk, then scan again."
        Kind.MAILBOX_GONE -> "The kiosk no longer accepts this QR (expired, already used, or refreshed). Tap Refresh StickLink QR and scan the new one."
        Kind.RATE_LIMITED -> "StickLink is rate-limiting this network. Wait a minute and try again."
        Kind.STICK_BUSY -> "The stick didn't answer. It may already be chatting with another phone; close that session or Refresh the QR."
        Kind.NETWORK -> "Couldn't reach StickLink signalling. Check the phone's data connection."
        Kind.WEBRTC -> "Couldn't open a direct link to the stick (no TURN relay in this build). Try Wi-Fi or another network."
        Kind.HANDSHAKE -> "The stick's identity check failed. Refresh the QR and scan again."
        Kind.AGENT -> "Paired, but the stick's agent bridge didn't come up."
    }
}

class MethodCConfig(
    val iceServers: List<IceServer>? = null, // null → GET <joinOrigin>/lab/ice, falling back to DEFAULT_ICE
    val offerTimeoutMs: Long = 30_000,
    val gatherMs: Long = 8_000,
    val dcOpenTimeoutMs: Long = 90_000,
    val handshakeTimeoutMs: Long = 60_000,
    val agentReadyTimeoutMs: Long = 120_000,
    val keepaliveSec: Long = 30,
    val skipClientExpiryCheck: Boolean = false // lab only: prove the server refuses on its own
) {
    companion object {
        /** Same list as methodc_peer.ice_configuration() and index.ts joinIceServers(). */
        val DEFAULT_ICE = listOf(
            IceServer(listOf("stun:stun.l.google.com:19302")),
            IceServer(listOf("stun:stun.cloudflare.com:3478")),
            IceServer(listOf("stun:relay-tor1.stickllm.com:3478")),
        )
    }
}

class MethodCClient(
    private val target: JoinTarget,
    private val rtc: RtcFactory,
    private val cfg: MethodCConfig = MethodCConfig(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val log: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(): MethodCSession {
        val inv = target.invite
        if (!cfg.skipClientExpiryCheck && inv.isExpired()) {
            throw MethodCError(MethodCError.Kind.EXPIRED, "QR expired before connect")
        }
        val ice = cfg.iceServers ?: fetchIce()
        val mbx = inv.mailbox
        val mbxB = Bytes.b64u(mbx)
        val jk = Derive.jkQr(inv.inviteSecret)
        val sig = MailboxSocket(http, "${target.wsUrl}?mbx=$mbxB", log)
        var peer: RtcPeer? = null
        var ok = false
        try {
            sig.open()
            val welcome = sig.next(10_000) ?: throw MethodCError(MethodCError.Kind.NETWORK, "no welcome")
            if (str(welcome, "t") != "welcome") throw MethodCError(MethodCError.Kind.NETWORK, "want welcome")
            val n = Bytes.unb64u(str(welcome, "n") ?: "", 32)
            val mac = Derive.qrJoinMac(jk, n, mbx)
            sig.send(buildJsonObject {
                put("t", "pair.join"); put("id", 1); put("mbx", mbxB); put("mac", Bytes.b64u(mac))
            })
            val joined = sig.next(10_000) ?: throw sig.failure() ?: MethodCError(MethodCError.Kind.MAILBOX_GONE, "no pair.joined")
            when (str(joined, "t")) {
                "pair.joined" -> {}
                "err" -> {
                    // Uniform refusal (unknown / expired / closed / bad MAC) → err nf + close 4404.
                    if (str(joined, "c") == "rate") throw MethodCError(MethodCError.Kind.RATE_LIMITED, "rate")
                    throw MethodCError(MethodCError.Kind.MAILBOX_GONE, "pair.join refused (${str(joined, "c") ?: "?"})")
                }
                else -> throw MethodCError(MethodCError.Kind.MAILBOX_GONE, "unexpected ${str(joined, "t")}")
            }
            log("pair.joined")
            val home = Bytes.unb64u(str(joined, "home") ?: "", 16)
            val envK = Derive.envQr(inv.inviteSecret, mbx, home)
            val th = Derive.qrTh(mbx, home, inv.stickStaticPub)
            val boot = Derive.qrBootPsk(inv.inviteSecret, th)

            fun pmsg(obj: JsonObject) {
                val sealed = Derive.sealEnvQr(envK, mbx, 0x01, obj.toString().toByteArray())
                val body = Bytes.b64u(sealed)
                if (body.length > BOX_MAX) throw MethodCError(MethodCError.Kind.WEBRTC, "envelope too large (${body.length})")
                sig.send(buildJsonObject { put("t", "pmsg"); put("body", body) })
            }

            val pc = rtc.create(ice)
            peer = pc
            pmsg(buildJsonObject { put("k", "need-offer"); put("v", 1) })
            val offerDeadline = System.currentTimeMillis() + cfg.offerTimeoutMs
            var offerSdp: String? = null
            while (offerSdp == null) {
                val left = offerDeadline - System.currentTimeMillis()
                if (left <= 0) throw MethodCError(MethodCError.Kind.STICK_BUSY, "no offer from stick")
                val m = sig.next(left) ?: continue
                if (str(m, "t") == "err") throw MethodCError(MethodCError.Kind.MAILBOX_GONE, "signalling err ${str(m, "c")}")
                if (str(m, "t") != "pmsg") continue
                val pt = try {
                    Derive.openEnvQr(envK, mbx, 0x02, Bytes.unb64u(str(m, "body") ?: ""))
                } catch (e: Exception) {
                    log("drop undecryptable pmsg"); continue
                }
                val o = json.parseToJsonElement(String(pt)).jsonObject
                if (str(o, "k") == "offer") offerSdp = str(o, "sdp")
            }
            log("got offer")
            var answer = pc.answer(offerSdp!!, cfg.gatherMs)
            answer = Sdp.fitEnvelope(answer, BOX_MAX)
            pmsg(buildJsonObject { put("k", "answer"); put("sdp", answer) })
            log("sent answer (${answer.lines().count { it.startsWith("a=candidate") }} candidates)")
            val dc = try { pc.awaitChannel(cfg.dcOpenTimeoutMs) } catch (e: Exception) {
                throw MethodCError(MethodCError.Kind.WEBRTC, "datachannel did not open: ${e.message}")
            }
            log("datachannel open label=${dc.label} path=${pc.pathInfo()}")

            // Noise IKpsk2, phone = initiator with a fresh static (agent_join_client.js).
            val sPriv = Crypto.x25519Generate()
            val sPub = Crypto.x25519Public(sPriv)
            val prologue = Bytes.concat(Bytes.ascii("sl1/qr"), byteArrayOf(1), home, mbx, inv.stickStaticPub)
            val ini = Noise.Initiator(sPriv, inv.stickStaticPub, boot, prologue)
            dc.send(ini.writeMsg1())
            val msg2 = dc.recv(cfg.handshakeTimeoutMs) ?: throw MethodCError(MethodCError.Kind.HANDSHAKE, "no msg2")
            val sess = try { ini.readMsg2(msg2).first } catch (e: NoiseError) {
                throw MethodCError(MethodCError.Kind.HANDSHAKE, "noise msg2: ${e.message}")
            }
            log("noise ok")
            val tx = NoiseDc(dc, sess)
            tx.send(buildJsonObject { put("t", "ready"); put("static_pub", Bytes.b64u(sPub)) })
            val rI = Crypto.random(32)
            tx.send(buildJsonObject { put("t", "r_i"); put("r", Bytes.b64u(rI)) })
            val rR = tx.recv(cfg.handshakeTimeoutMs) ?: throw MethodCError(MethodCError.Kind.HANDSHAKE, "no r_r")
            if (str(rR, "t") != "r_r") throw MethodCError(MethodCError.Kind.HANDSHAKE, "want r_r")
            val hh = sess.handshakeHash
            val psk = Derive.pairPsk(rI, Bytes.unb64u(str(rR, "r") ?: "", 32), boot, hh, th, sPub, inv.stickStaticPub, keyEpoch = 0)
            val kc = Derive.pairConfirmKey(psk)
            tx.send(buildJsonObject { put("t", "confirm_i"); put("mac", Bytes.b64u(Derive.confirmI(kc, hh))) })
            val cr = tx.recv(cfg.handshakeTimeoutMs) ?: throw MethodCError(MethodCError.Kind.HANDSHAKE, "no confirm_r")
            if (str(cr, "t") != "confirm_r") throw MethodCError(MethodCError.Kind.HANDSHAKE, "want confirm_r")
            // The browser client does not check confirm_r; we do (stick computes it per pair_qr.confirm_mac).
            val macR = try { Bytes.unb64u(str(cr, "mac") ?: "", 32) } catch (e: Exception) { ByteArray(0) }
            if (!Bytes.ctEq(macR, Derive.confirmR(kc, hh))) throw MethodCError(MethodCError.Kind.HANDSHAKE, "confirm_r mismatch")
            Bytes.wipe(kc); Bytes.wipe(rI)
            // Keep sPriv + psk for always-on pins (do NOT wipe; no fresh static on resume).
            log("pair_psk+confirm ok")
            val deadline = System.currentTimeMillis() + cfg.agentReadyTimeoutMs
            var ready: JsonObject
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw MethodCError(MethodCError.Kind.AGENT, "no agent_ready")
                val m = tx.recv(left) ?: continue
                when (str(m, "t")) {
                    "agent_ready" -> { ready = m; break }
                    "ping" -> tx.send(buildJsonObject { put("t", "pong") })
                    else -> log("pre-ready ignore ${str(m, "t")}")
                }
            }
            val resumeOffer = ResumeCodec.fromAgentReady(ready, allowDebugHosts = target.debugHost, fallbackWs = target.wsUrl)
            val alwaysOn = when (resumeOffer) {
                is ResumeOffer.Present -> AlwaysOnResume(
                    token = resumeOffer.info.token,
                    origin = resumeOffer.info.origin,
                    ws = resumeOffer.info.ws,
                    home = home.copyOf(),
                    stickPub = inv.stickStaticPub.copyOf(),
                    phonePriv = sPriv.copyOf(),
                    phonePub = sPub.copyOf(),
                    pairPsk = psk.copyOf(),
                    joinedAt = System.currentTimeMillis(),
                )
                else -> null
            }
            Bytes.wipe(psk)
            log("agent_ready resume=${when (resumeOffer) { is ResumeOffer.Present -> "yes"; ResumeOffer.Missing -> "missing"; is ResumeOffer.Invalid -> "invalid" }} pins=${alwaysOn != null}")
            val s = MethodCSession(sig, pc, tx, cfg.keepaliveSec, log, home = Bytes.b64u(home), resume = resumeOffer, alwaysOn = alwaysOn)
            ok = true
            return s
        } catch (e: MethodCError) {
            throw e
        } catch (e: MailboxSocket.Refused) {
            throw e.asError()
        } catch (e: RtcClosed) {
            throw MethodCError(MethodCError.Kind.WEBRTC, "datachannel closed: ${e.message}")
        } catch (e: java.io.IOException) {
            throw MethodCError(MethodCError.Kind.NETWORK, e.message ?: "io")
        } finally {
            Bytes.wipe(jk)
            if (!ok) {
                try { peer?.close() } catch (_: Exception) {}
                sig.close()
            }
        }
    }

    private fun fetchIce(): List<IceServer> {
        return try {
            val req = Request.Builder().url(target.iceUrl).header("Accept", "application/json").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return MethodCConfig.DEFAULT_ICE
                val o = json.parseToJsonElement(r.body!!.string()).jsonObject
                val arr = o["iceServers"]?.jsonArray ?: return MethodCConfig.DEFAULT_ICE
                arr.map { e ->
                    val u = e.jsonObject["urls"]!!
                    val urls = if (u is JsonPrimitive) listOf(u.content) else u.jsonArray.map { it.jsonPrimitive.content }
                    IceServer(urls.filter { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") })
                }.filter { it.urls.isNotEmpty() }.ifEmpty { MethodCConfig.DEFAULT_ICE }
            }
        } catch (e: Exception) {
            log("ice fetch failed, using defaults")
            MethodCConfig.DEFAULT_ICE
        }
    }

    companion object {
        /** home.ts: pmsg body (b64u) ≤ L.BOX_MAX. */
        const val BOX_MAX = 3072
        internal fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
    }
}

/** Noise transport over the DC: u64le(n) || ChaChaPoly(k, n, ad="", JSON). */
class NoiseDc(private val dc: RtcChannel, private val sess: Noise.Session) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    val isOpen: Boolean get() = dc.isOpen

    fun send(o: JsonObject) {
        val pt = o.toString().toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val (n, ct) = sess.encrypt(pt)
            val hdr = ByteArray(8)
            var v = n
            for (i in 0 until 8) { hdr[i] = (v and 0xff).toByte(); v = v ushr 8 }
            dc.send(hdr + ct)
        }
    }

    fun recv(timeoutMs: Long): JsonObject? {
        val raw = dc.recv(timeoutMs) ?: return null
        if (raw.size < 8 + 16) throw MethodCError(MethodCError.Kind.HANDSHAKE, "short frame")
        var n = 0L
        for (i in 7 downTo 0) n = (n shl 8) or (raw[i].toLong() and 0xff)
        val pt = sess.decrypt(n, raw.copyOfRange(8, raw.size))
        return json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
    }
}

/** A live, authenticated Method C session. Owns the signalling socket, peer and DC. */
class MethodCSession internal constructor(
    private val sig: MailboxSocket,
    private val pc: RtcPeer,
    private val tx: NoiseDc,
    keepaliveSec: Long,
    private val log: (String) -> Unit,
    override val home: String,
    override val resume: ResumeOffer = ResumeOffer.Missing,
    val alwaysOn: AlwaysOnResume? = null,
) : LiveLink {
    private val alive = AtomicBoolean(true)
    @Volatile private var lastRx = System.currentTimeMillis()
    @Volatile var lostReason: String? = null
        private set
    val mux = AppMux({ tx.send(it) }, log)
    val path: String get() = pc.pathInfo()
    override val isAlive: Boolean get() = alive.get()
    override var onLost: ((String) -> Unit)? = null

    private val reader = Thread({
        try {
            while (alive.get()) {
                val m = try { tx.recv(1000) } catch (e: NoiseError) { log("drop bad frame"); continue } ?: continue
                lastRx = System.currentTimeMillis()
                if (MethodCClient.str(m, "t") == "ping") {
                    tx.send(buildJsonObject { put("t", "pong"); m["ts"]?.let { put("ts", it) } })
                    continue
                }
                mux.dispatch(m)
            }
        } catch (e: Exception) {
            lost("transport: ${e.message ?: e.javaClass.simpleName}")
        }
    }, "methodc-rx").apply { isDaemon = true; start() }

    private val pinger = Thread({
        try {
            var lastPing = System.currentTimeMillis()
            while (alive.get()) {
                Thread.sleep(1000)
                if (!alive.get()) break
                val now = System.currentTimeMillis()
                if (now - lastPing >= keepaliveSec * 1000) {
                    tx.send(buildJsonObject { put("t", "ping"); put("ts", now) })
                    lastPing = now
                }
                val st = pc.state()
                if (st == "failed" || st == "closed") { lost("webrtc $st"); break }
                // Resume-capable sticks ping every ~5 s; long silence means the path is dead
                // (airplane mode, Wi-Fi switch) even before ICE gives up. Old sticks may not
                // ping, so this only applies when the stick offered resume.
                if (resume is ResumeOffer.Present && now - lastRx > RX_SILENCE_MS) { lost("no traffic for ${RX_SILENCE_MS / 1000}s"); break }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            lost("keepalive: ${e.message}")
        }
    }, "methodc-ka").apply { isDaemon = true; start() }

    private fun lost(why: String) {
        if (alive.compareAndSet(true, false)) {
            lostReason = why
            log("session lost: $why")
            mux.failAll(why)
            try { onLost?.invoke(why) } catch (_: Exception) {}
            cleanup()
        }
    }

    /** Polite close without ending the pairing (stick re-offers resume). */
    fun close() = close(null)

    /**
     * Polite close: tell the stick. why="user" is an explicit Disconnect; the
     * stick clears its resume token (methodc_peer: close why user/disconnect/logout).
     */
    override fun close(why: String?) {
        if (alive.compareAndSet(true, false)) {
            try {
                tx.send(buildJsonObject { put("t", "close"); if (why != null) put("why", why) })
                if (why != null) Thread.sleep(300) // let SCTP flush before the PC closes
            } catch (_: Exception) {}
            mux.failAll("closed")
            cleanup()
        }
    }

    /** Lab/test: drop the transport abruptly (no close message), as a network loss would. */
    fun dropTransport(why: String = "test drop") {
        try { pc.close() } catch (_: Exception) {}
        lost(why)
    }

    companion object { const val RX_SILENCE_MS = 30_000L }

    private fun cleanup() {
        pinger.interrupt()
        try { pc.close() } catch (_: Exception) {}
        sig.close()
    }
}

/** Mailbox WebSocket (one per join), with close-code mapping. */
internal class MailboxSocket(private val http: OkHttpClient, private val url: String, private val log: (String) -> Unit) {
    class Refused(val httpCode: Int?, val wsCode: Int?, msg: String) : RuntimeException(msg) {
        fun asError(): MethodCError = when {
            httpCode == 429 -> MethodCError(MethodCError.Kind.RATE_LIMITED, "HTTP 429")
            httpCode == 404 || wsCode == 4404 -> MethodCError(MethodCError.Kind.MAILBOX_GONE, "mailbox refused (${httpCode ?: wsCode})")
            wsCode != null -> MethodCError(MethodCError.Kind.MAILBOX_GONE, "signalling closed $wsCode")
            else -> MethodCError(MethodCError.Kind.NETWORK, message ?: "signalling failed")
        }
    }

    private val q = LinkedBlockingQueue<Any>()
    private var ws: WebSocket? = null
    @Volatile private var fail: Refused? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun open() {
        val opened = LinkedBlockingQueue<Any>()
        val req = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "sticklink.1").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val proto = response.header("Sec-WebSocket-Protocol")
                if (proto != "sticklink.1") {
                    opened.put(Refused(null, null, "subprotocol mismatch")); webSocket.cancel(); return
                }
                opened.put(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { q.put(json.parseToJsonElement(text).jsonObject) } catch (_: Exception) {}
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val r = Refused(null, code, "closed $code")
                if (fail == null) fail = r
                q.put(r); webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val r = Refused(response?.code, null, response?.let { "HTTP ${it.code}" } ?: (t.message ?: "ws failure"))
                if (fail == null) fail = r
                opened.put(r); q.put(r)
            }
        })
        when (val o = opened.poll(20, TimeUnit.SECONDS)) {
            Unit -> log("signalling open")
            is Refused -> throw o
            else -> throw Refused(null, null, "signalling open timeout")
        }
    }

    fun failure(): MethodCError? = fail?.asError()

    fun send(o: JsonObject) {
        val s = o.toString()
        if (s.length > 4096) throw MethodCError(MethodCError.Kind.WEBRTC, "signalling frame too large")
        if (ws?.send(s) != true) throw fail ?: Refused(null, null, "signalling send failed")
    }

    /** Next JSON message; null on timeout; throws [Refused] when the socket closed. */
    fun next(timeoutMs: Long): JsonObject? {
        val o = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (o is Refused) { q.put(o); throw o }
        return o as JsonObject
    }

    fun close() {
        try { ws?.close(1000, null) } catch (_: Exception) {}
    }
}

/** SDP helpers. */
object Sdp {
    /**
     * Keep the sealed answer under the mailbox limit. Only if it is too large,
     * drop TCP candidates, then IPv6 candidates (UDP v4 host/srflx are what
     * matter for a phone). Otherwise the SDP is sent exactly as libwebrtc made it.
     */
    fun fitEnvelope(sdp: String, boxMax: Int): String {
        fun size(s: String): Int {
            val pt = """{"k":"answer","sdp":${JsonPrimitive(s)}}""".toByteArray().size
            return ((12 + pt + 16) * 4 + 2) / 3
        }
        if (size(sdp) <= boxMax) return sdp
        val nl = if (sdp.contains("\r\n")) "\r\n" else "\n"
        var lines = sdp.split(nl)
        lines = lines.filterNot { it.startsWith("a=candidate") && it.contains(" tcp ", ignoreCase = true) }
        var out = lines.joinToString(nl)
        if (size(out) <= boxMax) return out
        lines = lines.filterNot { it.startsWith("a=candidate") && it.split(" ").getOrNull(4)?.contains(":") == true }
        out = lines.joinToString(nl)
        return out
    }
}
