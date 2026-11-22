package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Always-on WSS session resume (slice 1), matching StickLink methodc_peer.py +
 * docs/ARCHITECTURE-ALWAYS-ON-SESSION.md / GROK-STICKDROID-ALWAYS-ON-HANDOFF.
 *
 * Wake: open `{ws}?rt={token}` (compat) or `{ws}?sess=1&home=` (preferred by
 * Worker), subprotocol sticklink.1 → welcome → sess.resume → fresh Noise
 * IKpsk2 with pinned statics → sess.frame ciphertext → sess.have → app-mux.
 *
 * PSK v2 (LIVE stick + browser, 0.3.1): HKDF-SHA256(ikm=pair_psk‖utf8(token),
 * salt=empty, info="sl1/sess-psk/v2", len=32) — stick-client methodc_peer.py
 * sess_resume_psk(token, pair_psk) and agent_join_client.js sessResumePsk.
 * v1 (token only) is kept for vectors only; the live stick rejects it.
 *
 * [SessAuthUrl] isolates query-string vs home routing so auth can change later.
 */
object SessCrypto {
    const val SESS_VER: Int = 0x01
    val PSK_INFO_V1: ByteArray = Bytes.ascii("sl1/sess-psk/v1")
    val PSK_INFO_V2: ByteArray = Bytes.ascii("sl1/sess-psk/v2")

    fun tokenId(token: String): ByteArray =
        Crypto.sha256(token.trim().toByteArray(Charsets.UTF_8)).copyOf(8)

    /** Handoff + stick peer (methodc_peer.sess_resume_psk). */
    fun resumePskV1(token: String): ByteArray =
        Crypto.hkdf(token.trim().toByteArray(Charsets.UTF_8), ByteArray(0), PSK_INFO_V1, 32)

    /** Live browser client (agent_join_client.js sessResumePsk) — kept for vectors. */
    fun resumePskV2(token: String, pairPsk: ByteArray): ByteArray {
        require(pairPsk.size == 32)
        return Crypto.hkdf(Bytes.concat(pairPsk, token.trim().toByteArray(Charsets.UTF_8)), ByteArray(0), PSK_INFO_V2, 32)
    }

    fun prologue(home: ByteArray, token: String, phonePub: ByteArray, stickPub: ByteArray): ByteArray {
        require(home.size == 16 && phonePub.size == 32 && stickPub.size == 32)
        return Bytes.concat(
            Bytes.ascii("sl1/sess"),
            byteArrayOf(SESS_VER.toByte()),
            home,
            tokenId(token),
            phonePub,
            stickPub,
        )
    }
}

/**
 * How the phone authenticates the WSS upgrade. Prefer [RtQuery] per handoff;
 * [SessHome] matches the live Worker preferred route (token only in sess.resume).
 */
sealed class SessAuthUrl {
    abstract fun build(wsBase: String, token: String, homeB64u: String): String

    /** Legacy / handoff: `?rt=<token>`. Isolate so we can drop it without a rewrite. */
    data object RtQuery : SessAuthUrl() {
        override fun build(wsBase: String, token: String, homeB64u: String): String {
            val sep = if (wsBase.contains("?")) "&" else "?"
            return wsBase + sep + "rt=" + URLEncoder.encode(token, Charsets.UTF_8)
        }
    }

    /** Preferred Worker route: home is non-secret routing; token in first frame. */
    data object SessHome : SessAuthUrl() {
        override fun build(wsBase: String, token: String, homeB64u: String): String {
            val sep = if (wsBase.contains("?")) "&" else "?"
            return wsBase + sep + "sess=1&home=" + URLEncoder.encode(homeB64u, Charsets.UTF_8)
        }
    }
}

class SessResumeError(
    val kind: Kind,
    msg: String,
    /** Server-provided wait (err {retry} seconds or HTTP Retry-After), if any. */
    val retryAfterSec: Long? = null,
    /** Short, non-secret text the Worker/stick sent with the error, if any. */
    val stickMessage: String? = null,
    /** Worker err code (e.g. rate, busy, offline), if any. */
    val code: String? = null,
) : RuntimeException(msg) {
    /**
     * RATE_LIMITED: Worker said rate (err c=rate, WS close 4429, HTTP 429) → wait, don't spam.
     * STICK: a known stick-side refusal (busy / disabled) → show it, wait, don't spam.
     * OFFLINE: Worker says the stick isn't connected right now → soft retry.
     */
    enum class Kind { NETWORK, REVOKED, AUTH, HANDSHAKE, AGENT, UNAVAILABLE, RATE_LIMITED, STICK, OFFLINE }
}

/** Resume entry point used by [LinkManager] (seam for tests). */
fun interface SessConnector {
    /** Always-on resume with the stored pins (PSK v2). Throws [SessResumeError]. */
    fun connect(stored: AlwaysOnResume): LiveLink
}

/** Map a Worker `err` frame to a [SessResumeError] (sess.resume or handshake). */
internal fun sessErrFromFrame(m: JsonObject, where: String): SessResumeError {
    fun s(k: String) = (m[k] as? JsonPrimitive)?.contentOrNull
    val c = s("c") ?: s("code") ?: "?"
    val retry = (m["retry"] as? JsonPrimitive)?.longOrNull ?: (m["ra"] as? JsonPrimitive)?.longOrNull
    val msg = (s("msg") ?: s("message"))?.take(160)
    return when (c) {
        "rate" -> SessResumeError(SessResumeError.Kind.RATE_LIMITED, "$where rate", retry, msg, c)
        "busy", "disabled" -> SessResumeError(SessResumeError.Kind.STICK, "$where $c", retry, msg, c)
        "offline" -> SessResumeError(SessResumeError.Kind.OFFLINE, "$where offline", retry, msg, c)
        "auth", "nf" -> SessResumeError(SessResumeError.Kind.AUTH, "$where $c", null, msg, c)
        else -> SessResumeError(
            if (where == "sess.resume") SessResumeError.Kind.AUTH else SessResumeError.Kind.HANDSHAKE,
            "$where err $c", retry, msg, c,
        )
    }
}

/**
 * Always-on durable resume blob. Durable identity = pins + pair_psk, NOT the token.
 * Never log secrets; [tag] shows …+last4 of the token only.
 */
class AlwaysOnResume(
    val token: String,
    val origin: String,
    val ws: String,
    val home: ByteArray,          // 16 B
    val stickPub: ByteArray,      // 32 B
    val phonePriv: ByteArray,     // 32 B — same static as first IK
    val phonePub: ByteArray,      // 32 B
    val pairPsk: ByteArray? = null, // required for live v2 sess PSK (pair_psk‖token)
    val hwm: Long = 0,            // last acked stick seq (Noise nonce)
    val joinedAt: Long = 0,
    val exp: Long = 0,
) {
    fun tag(): String = "…" + token.takeLast(4)
    fun homeB64u(): String = Bytes.b64u(home)
    fun canWssResume(): Boolean =
        token.length in ResumeCodec.TOKEN_MIN..ResumeCodec.TOKEN_MAX &&
            home.size == 16 && stickPub.size == 32 && phonePriv.size == 32 && phonePub.size == 32

    fun withToken(t: String, exp: Long = this.exp) =
        AlwaysOnResume(t, origin, ws, home, stickPub, phonePriv, phonePub, pairPsk, hwm, joinedAt, exp)

    fun withHwm(n: Long) =
        AlwaysOnResume(token, origin, ws, home, stickPub, phonePriv, phonePub, pairPsk, n, joinedAt, exp)

    override fun toString(): String =
        "AlwaysOnResume(token=${tag()}, origin=$origin, hwm=$hwm, pins=yes)"
}

object AlwaysOnCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(a: AlwaysOnResume): String = buildJsonObject {
        put("v", 2)
        put("token", a.token)
        put("origin", a.origin)
        put("ws", a.ws)
        put("home", Bytes.b64u(a.home))
        put("stick_pub", Bytes.b64u(a.stickPub))
        put("phone_priv", Bytes.b64u(a.phonePriv))
        put("phone_pub", Bytes.b64u(a.phonePub))
        a.pairPsk?.let { put("pair_psk", Bytes.b64u(it)) }
        put("hwm", a.hwm)
        put("at", a.joinedAt)
        if (a.exp > 0) put("exp", a.exp)
    }.toString()

    fun decode(s: String): AlwaysOnResume? {
        return try {
            val o = json.parseToJsonElement(s).jsonObject
            val token = str(o, "token") ?: return null
            if (token.length < ResumeCodec.TOKEN_MIN || token.length > ResumeCodec.TOKEN_MAX) return null
            val home = Bytes.unb64u(str(o, "home") ?: return null, 16)
            val stickPub = Bytes.unb64u(str(o, "stick_pub") ?: return null, 32)
            val phonePriv = Bytes.unb64u(str(o, "phone_priv") ?: return null, 32)
            val phonePub = str(o, "phone_pub")?.let { Bytes.unb64u(it, 32) } ?: Crypto.x25519Public(phonePriv)
            val pairPsk = str(o, "pair_psk")?.let { Bytes.unb64u(it, 32) }
            AlwaysOnResume(
                token = token,
                origin = str(o, "origin") ?: JoinLink.PROD_ORIGIN,
                ws = str(o, "ws") ?: JoinLink.PROD_WS,
                home = home,
                stickPub = stickPub,
                phonePriv = phonePriv,
                phonePub = phonePub,
                pairPsk = pairPsk,
                hwm = (o["hwm"] as? JsonPrimitive)?.longOrNull ?: 0,
                joinedAt = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0,
                exp = (o["exp"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        } catch (_: Exception) { null }
    }

    /** Optional enrich: GET /v1/resume?t= → v:2 mode:wss. */
    fun enrichFromV2Body(body: String, base: AlwaysOnResume): AlwaysOnResume? {
        return try {
            val o = json.parseToJsonElement(body).jsonObject
            if ((o["v"] as? JsonPrimitive)?.contentOrNull != "2" &&
                (o["v"] as? JsonPrimitive)?.longOrNull != 2L) return null
            if (str(o, "mode") != "wss" && str(o, "mode") != null && str(o, "mode") != "sess") return null
            val stick = str(o, "stick_pub")?.let { Bytes.unb64u(it, 32) } ?: base.stickPub
            val home = str(o, "home")?.let { Bytes.unb64u(it, 16) } ?: base.home
            val ws = str(o, "ws") ?: base.ws
            AlwaysOnResume(base.token, base.origin, ws, home, stick, base.phonePriv, base.phonePub, base.pairPsk, base.hwm, base.joinedAt, base.exp)
        } catch (_: Exception) { null }
    }

    /** Legacy v:1 body with join URL (one-shot Method C fallback). */
    fun legacyJoinFromBody(body: String): String? {
        return try {
            val o = json.parseToJsonElement(body).jsonObject
            val v = (o["v"] as? JsonPrimitive)?.longOrNull ?: (o["v"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            if (v != null && v != 1L) return null
            str(o, "join")?.trim()?.takeIf { it.contains("qr=") }
        } catch (_: Exception) { null }
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
}

/** Platform store for the always-on blob (Android: Keystore AES-GCM resume.bin). */
interface AlwaysOnStore {
    fun load(): AlwaysOnResume?
    fun save(info: AlwaysOnResume)
    fun clear()
}

class MemoryAlwaysOnStore(private var v: AlwaysOnResume? = null) : AlwaysOnStore {
    var saves = 0; var clears = 0
    @Synchronized override fun load() = v
    @Synchronized override fun save(info: AlwaysOnResume) { v = info; saves++ }
    @Synchronized override fun clear() { v = null; clears++ }
}

/**
 * Connect via WSS always-on path. Does NOT mint a fresh Noise static — uses
 * [AlwaysOnResume.phonePriv] from the first pair.
 */
class WssSessClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val authUrl: SessAuthUrl = SessAuthUrl.SessHome,
    private val usePskV2: Boolean = true, // live stick + browser = v2 (pair_psk‖token)
    private val msg2TimeoutMs: Long = 15_000,
    private val keepaliveSec: Long = 30,
    private val log: (String) -> Unit = {},
) : SessConnector {
    private val json = Json { ignoreUnknownKeys = true }

    override fun connect(stored: AlwaysOnResume): SessSession {
        if (!stored.canWssResume()) throw SessResumeError(SessResumeError.Kind.UNAVAILABLE, "incomplete always-on pins")
        // Build URL without logging it (contains token when RtQuery).
        val url = authUrl.build(stored.ws, stored.token, stored.homeB64u())
        val sock = SessSocket(http, url, log)
        var ok = false
        try {
            sock.open()
            val welcome = sock.next(15_000) ?: throw SessResumeError(SessResumeError.Kind.NETWORK, "no welcome")
            if (str(welcome, "t") != "welcome") throw SessResumeError(SessResumeError.Kind.NETWORK, "want welcome")

            sock.send(buildJsonObject {
                put("t", "sess.resume"); put("id", 1)
                put("token", stored.token)
                put("phone_pub", Bytes.b64u(stored.phonePub))
            })
            val m = sock.next(30_000) ?: throw sock.failure() ?: SessResumeError(SessResumeError.Kind.NETWORK, "no sess.ok")
            when (str(m, "t")) {
                "sess.ok", "ok" -> log("sess.ok${if (m["remint"] != null) " (remint hint)" else ""}")
                "err" -> {
                    val e = sessErrFromFrame(m, "sess.resume")
                    log("sess.resume err c=${e.code}${e.retryAfterSec?.let { " retry=${it}s" } ?: ""}")
                    // If the socket already saw a 4403 close, report REVOKED (terminal) rather than AUTH.
                    if (e.kind == SessResumeError.Kind.AUTH) {
                        sock.failure()?.takeIf { it.kind == SessResumeError.Kind.REVOKED }?.let { throw it }
                    }
                    throw e
                }
                else -> throw SessResumeError(SessResumeError.Kind.AUTH, "want sess.ok got ${str(m, "t")}")
            }

            val pairPsk = stored.pairPsk
            val psk = when {
                usePskV2 && pairPsk != null -> SessCrypto.resumePskV2(stored.token, pairPsk)
                usePskV2 -> throw SessResumeError(SessResumeError.Kind.UNAVAILABLE, "no pair_psk saved (needs rescan)")
                else -> SessCrypto.resumePskV1(stored.token)
            }
            val pskVer = if (usePskV2) "v2" else "v1"
            val prologue = SessCrypto.prologue(stored.home, stored.token, stored.phonePub, stored.stickPub)
            val ini = Noise.Initiator(stored.phonePriv, stored.stickPub, psk, prologue)
            val msg1 = ini.writeMsg1()
            sock.send(buildJsonObject { put("t", "sess.frame"); put("body", Bytes.b64u(msg1)) })
            log("sess noise msg1 sent (${msg1.size} B, psk=$pskVer, prologue=${prologue.size} B)")

            var msg2: ByteArray? = null
            var framesRx = 0
            val tHs = System.currentTimeMillis()
            val deadline = tHs + msg2TimeoutMs
            while (msg2 == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val f = sock.next(minOf(left, 1_000)) ?: continue
                framesRx++
                when (str(f, "t")) {
                    "err" -> {
                        val e = sessErrFromFrame(f, "sess.frame")
                        log("sess.frame err c=${e.code}")
                        throw e
                    }
                    "sess.frame" -> {
                        val wire = try { Bytes.unb64u(str(f, "body") ?: "") } catch (_: Exception) { continue }
                        if (wire.size in 40 until 200) msg2 = wire
                        else log("sess.frame ignored during handshake (${wire.size} B)")
                    }
                    "sess.revoked" -> throw SessResumeError(SessResumeError.Kind.REVOKED, "sess.revoked")
                    else -> log("handshake: ignore ${str(f, "t")}")
                }
            }
            if (msg2 == null) {
                log("sess noise msg2 timeout after ${(System.currentTimeMillis() - tHs) / 1000}s (rx=$framesRx, psk=$pskVer)")
                throw SessResumeError(SessResumeError.Kind.HANDSHAKE, "no Noise msg2")
            }
            log("sess noise msg2 received (${msg2.size} B) after ${System.currentTimeMillis() - tHs} ms")
            val sess = try { ini.readMsg2(msg2).first } catch (e: NoiseError) {
                log("sess noise msg2 decrypt failed (psk=$pskVer) — PSK/prologue mismatch with stick")
                throw SessResumeError(SessResumeError.Kind.HANDSHAKE, "noise: ${e.message}")
            }
            log("sess noise ok (psk=$pskVer)")
            Bytes.wipe(psk)

            val tx = SessNoiseTx(sock, sess)
            if (stored.hwm > 0) {
                sock.send(buildJsonObject { put("t", "sess.have"); put("n", stored.hwm) })
                log("sess.have n=${stored.hwm}")
            }
            val session = SessSession(sock, tx, stored, keepaliveSec, log)
            ok = true
            return session
        } catch (e: SessSocket.Revoked) {
            throw SessResumeError(SessResumeError.Kind.REVOKED, "4403")
        } catch (e: SessSocket.Refused) {
            throw when (e.code) {
                4403 -> SessResumeError(SessResumeError.Kind.REVOKED, "4403")
                4401 -> SessResumeError(SessResumeError.Kind.AUTH, "4401")
                4429, 429 -> SessResumeError(SessResumeError.Kind.RATE_LIMITED, "rate ${e.code}", e.retryAfterSec, null, "rate")
                else -> SessResumeError(SessResumeError.Kind.NETWORK, e.message ?: "closed")
            }
        } catch (e: SessResumeError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SessResumeError(SessResumeError.Kind.NETWORK, e.message ?: "io")
        } finally {
            if (!ok) try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
}

/** Noise transport over sess.frame JSON (body = b64u of u64le‖ChaChaPoly). */
class SessNoiseTx(private val sock: SessSocket, private val sess: Noise.Session) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    val lastStickSeq = AtomicLong(-1)

    fun send(o: JsonObject) {
        val pt = o.toString().toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val (n, ct) = sess.encrypt(pt)
            val hdr = ByteArray(8)
            var v = n
            for (i in 0 until 8) { hdr[i] = (v and 0xff).toByte(); v = v ushr 8 }
            sock.send(buildJsonObject { put("t", "sess.frame"); put("body", Bytes.b64u(hdr + ct)) })
        }
    }

    fun decryptFrame(bodyB64u: String): JsonObject? {
        val raw = Bytes.unb64u(bodyB64u)
        if (raw.size < 8 + 16) return null
        var n = 0L
        for (i in 7 downTo 0) n = (n shl 8) or (raw[i].toLong() and 0xff)
        val pt = sess.decrypt(n, raw.copyOfRange(8, raw.size))
        if (n > lastStickSeq.get()) lastStickSeq.set(n)
        return json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
    }
}

/** Live always-on WSS session. */
class SessSession internal constructor(
    private val sock: SessSocket,
    private val tx: SessNoiseTx,
    private var stored: AlwaysOnResume,
    keepaliveSec: Long,
    private val log: (String) -> Unit,
) : LiveLink {
    private val alive = AtomicBoolean(true)
    @Volatile private var lastRx = System.currentTimeMillis()
    override val home: String get() = stored.homeB64u()
    override val resume: ResumeOffer get() = ResumeOffer.Present(ResumeInfo(stored.token, stored.origin, stored.ws, home, stored.joinedAt))
    val alwaysOn: AlwaysOnResume get() = stored
    val mux = AppMux({ tx.send(it) }, log)
    override val isAlive: Boolean get() = alive.get()
    override var onLost: ((String) -> Unit)? = null
    var onResumeUpdate: ((AlwaysOnResume) -> Unit)? = null

    private val reader = Thread({
        try {
            while (alive.get()) {
                val m = sock.next(1_000) ?: continue
                lastRx = System.currentTimeMillis()
                when (str(m, "t")) {
                    "sess.frame" -> {
                        val body = str(m, "body") ?: continue
                        val pt = try { tx.decryptFrame(body) } catch (e: Exception) { log("drop bad sess.frame"); continue } ?: continue
                        when (str(pt, "t")) {
                            "ping" -> tx.send(buildJsonObject { put("t", "pong"); pt["ts"]?.let { put("ts", it) } })
                            "resume_token" -> {
                                val t = str(pt, "token")?.trim()
                                if (t != null && t.length in ResumeCodec.TOKEN_MIN..ResumeCodec.TOKEN_MAX) {
                                    val exp = (pt["exp"] as? JsonPrimitive)?.longOrNull ?: 0
                                    stored = stored.withToken(t, exp)
                                    log("resume token rotated ${stored.tag()}")
                                    try { onResumeUpdate?.invoke(stored) } catch (_: Exception) {}
                                }
                            }
                            else -> mux.dispatch(pt)
                        }
                    }
                    "sess.revoked" -> lost("revoked")
                    "pong", "ping" -> {}
                    else -> {}
                }
            }
        } catch (e: SessSocket.Revoked) {
            lost("4403")
        } catch (e: Exception) {
            lost("transport: ${e.message ?: e.javaClass.simpleName}")
        }
    }, "sess-rx").apply { isDaemon = true; start() }

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
                if (now - lastRx > 90_000) { lost("no pong path for 90s"); break }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            lost("keepalive: ${e.message}")
        }
    }, "sess-ka").apply { isDaemon = true; start() }

    fun currentHwm(): Long = maxOf(stored.hwm, tx.lastStickSeq.get().coerceAtLeast(0))

    private fun lost(why: String) {
        if (alive.compareAndSet(true, false)) {
            log("sess lost: $why (rx_seq=${tx.lastStickSeq.get()})")
            mux.failAll(why)
            // Persist hwm before teardown so next wake can sess.have
            val h = currentHwm()
            if (h > stored.hwm) {
                stored = stored.withHwm(h)
                try { onResumeUpdate?.invoke(stored) } catch (_: Exception) {}
            }
            try { onLost?.invoke(why) } catch (_: Exception) {}
            cleanup()
        }
    }

    override fun close(why: String?) {
        if (alive.compareAndSet(true, false)) {
            try {
                tx.send(buildJsonObject { put("t", "close"); if (why != null) put("why", why) })
                if (why != null) Thread.sleep(200)
            } catch (_: Exception) {}
            mux.failAll("closed")
            cleanup()
        }
    }

    /**
     * Abrupt transport death (process kill / airplane). Do NOT send Noise close —
     * the stick keeps A0 + sess binding and waits for the next sess.peer.
     */
    fun dropTransport(why: String = "dropped") {
        if (alive.compareAndSet(true, false)) {
            log("sess dropped: $why (rx_seq=${tx.lastStickSeq.get()})")
            mux.failAll(why)
            val h = currentHwm()
            if (h > stored.hwm) {
                stored = stored.withHwm(h)
                try { onResumeUpdate?.invoke(stored) } catch (_: Exception) {}
            }
            try { onLost?.invoke(why) } catch (_: Exception) {}
            cleanup()
        }
    }

    private fun cleanup() {
        try { sock.close() } catch (_: Exception) {}
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
}

/** Low-level sticklink.1 WebSocket for sess.* control frames. */
class SessSocket(
    private val http: OkHttpClient,
    private val url: String,
    private val log: (String) -> Unit,
) {
    class Revoked(msg: String) : RuntimeException(msg)
    class Refused(val code: Int?, val reason: String?, msg: String, val retryAfterSec: Long? = null) : RuntimeException(msg)

    private val q = LinkedBlockingQueue<Any>(64)
    private var ws: WebSocket? = null
    @Volatile private var fail: Refused? = null
    @Volatile private var revoked = false

    fun open() {
        val req = Request.Builder().url(url)
            .header("Sec-WebSocket-Protocol", "sticklink.1")
            .build()
        val opened = java.util.concurrent.CountDownLatch(1)
        var openErr: Exception? = null
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject
                    q.offer(o)
                } catch (_: Exception) {}
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("sess ws closing code=$code reason=${reason.take(40)}")
                when (code) {
                    4403 -> { revoked = true; q.offer(Revoked("4403 $reason")) }
                    4401 -> { fail = Refused(code, reason, "ws auth 4401"); q.offer(fail!!) }
                    else -> { fail = Refused(code, reason, "ws closing $code"); q.offer(fail!!) }
                }
                webSocket.close(code, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("sess ws failure http=${response?.code ?: "-"} ${t.javaClass.simpleName}")
                openErr = Exception(t.message ?: "ws fail")
                fail = Refused(response?.code, null, t.message ?: "ws fail",
                    response?.header("retry-after")?.trim()?.toLongOrNull())
                q.offer(fail!!)
                opened.countDown()
            }
        })
        if (!opened.await(20, TimeUnit.SECONDS)) throw SessResumeError(SessResumeError.Kind.NETWORK, "ws open timeout")
        if (openErr != null && fail?.code == 429) throw fail!!
        openErr?.let { throw SessResumeError(SessResumeError.Kind.NETWORK, it.message ?: "ws open failed") }
        log("signalling open (sess)")
    }

    fun send(o: JsonObject) {
        val s = o.toString()
        if (ws?.send(s) != true) throw fail ?: Refused(null, null, "sess send failed")
    }

    fun next(timeoutMs: Long): JsonObject? {
        val o = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (o is Revoked) { q.put(o); throw o }
        if (o is Refused) { q.put(o); throw o }
        return o as JsonObject
    }

    fun failure(): SessResumeError? = when {
        revoked -> SessResumeError(SessResumeError.Kind.REVOKED, "4403")
        fail?.code == 4403 -> SessResumeError(SessResumeError.Kind.REVOKED, "4403")
        fail?.code == 4401 -> SessResumeError(SessResumeError.Kind.AUTH, "4401")
        fail?.code == 4429 || fail?.code == 429 ->
            SessResumeError(SessResumeError.Kind.RATE_LIMITED, "rate", fail?.retryAfterSec, null, "rate")
        fail != null -> SessResumeError(SessResumeError.Kind.NETWORK, fail!!.message ?: "closed")
        else -> null
    }

    /** Clean close so the Worker sends sess.peer leave and the stick UI isn't left "connected". */
    fun close() { try { ws?.close(1000, "bye") } catch (_: Exception) {} }
}
