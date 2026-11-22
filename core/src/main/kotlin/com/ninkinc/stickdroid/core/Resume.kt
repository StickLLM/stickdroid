package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Durable Method C resume (StickLink "stay paired until Disconnect or Generate").
 *
 * After agent_ready the stick sends `resume {token, origin, ws}`. The phone keeps
 * it (Keystore-encrypted on Android) and, on transport loss or cold start, polls
 * `GET {origin}/v1/resume?t=token` for a fresh join URL, then runs a normal
 * Method C connect with it. Only user Disconnect or a dead token (stick
 * Generate) clears it.
 *
 * The token is a bearer secret: [toString] and [tag] never show it in full.
 */
class ResumeInfo(
    val token: String,
    val origin: String = JoinLink.PROD_ORIGIN,
    val ws: String = JoinLink.PROD_WS,
    val home: String? = null,
    val joinedAt: Long = 0,
) {
    /** Log-safe handle: last 4 chars only. */
    fun tag(): String = "…" + token.takeLast(4)

    /** Contains the token: never log this. */
    fun pollUrl(): String =
        origin.trimEnd('/') + "/v1/resume?t=" + URLEncoder.encode(token, Charsets.UTF_8)

    fun withJoinedAt(t: Long, home: String?) = ResumeInfo(token, origin, ws, home ?: this.home, t)

    override fun toString(): String = "ResumeInfo(token=${tag()}, origin=$origin, ws=$ws, home=${home?.take(6)})"
    override fun equals(other: Any?): Boolean = other is ResumeInfo && other.token == token &&
        other.origin == origin && other.ws == ws && other.home == home && other.joinedAt == joinedAt
    override fun hashCode(): Int = token.hashCode()
}

/** What agent_ready said about resume. */
sealed class ResumeOffer {
    data class Present(val info: ResumeInfo) : ResumeOffer()
    /** Old stick/peer: no resume field. Keep old behaviour (fresh scan next time). */
    data object Missing : ResumeOffer()
    /** A resume field we refuse (bad token / host not allowed). Treated like Missing. */
    data class Invalid(val reason: String) : ResumeOffer()
}

object ResumeCodec {
    private val json = Json { ignoreUnknownKeys = true }
    const val TOKEN_MIN = 16
    const val TOKEN_MAX = 128

    /**
     * Parse `agent_ready.resume`. origin/ws must pass the same host allowlist as
     * QR join links (link.stickllm.com in production; loopback only in lab).
     */
    fun fromAgentReady(msg: JsonObject, allowDebugHosts: Boolean = false, fallbackWs: String = JoinLink.PROD_WS): ResumeOffer {
        val r = msg["resume"] ?: return ResumeOffer.Missing
        val o = r as? JsonObject ?: return ResumeOffer.Invalid("resume not an object")
        val token = str(o, "token")?.trim() ?: return ResumeOffer.Invalid("no token")
        if (token.length < TOKEN_MIN || token.length > TOKEN_MAX) return ResumeOffer.Invalid("token length")
        if (!token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' || it == '=' }) {
            return ResumeOffer.Invalid("token charset")
        }
        val ws = str(o, "ws")?.trim()?.ifEmpty { null } ?: fallbackWs
        val origin = str(o, "origin")?.trim()?.trimEnd('/')?.ifEmpty { null } ?: JoinLink.PROD_ORIGIN
        if (!originAllowed(origin, allowDebugHosts)) return ResumeOffer.Invalid("origin not allowed")
        if (!wsAllowed(ws, allowDebugHosts)) return ResumeOffer.Invalid("ws not allowed")
        val canonOrigin = if (isProd(origin)) JoinLink.PROD_ORIGIN else origin
        val canonWs = if (isProdWs(ws)) JoinLink.PROD_WS else ws
        return ResumeOffer.Present(ResumeInfo(token, canonOrigin, canonWs))
    }

    fun originAllowed(origin: String, allowDebug: Boolean): Boolean {
        if (isProd(origin)) return true
        val u = try { URI(origin) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        if (u.rawQuery != null || u.rawFragment != null || u.rawUserInfo != null) return false
        if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) return false
        return allowDebug && host in JoinLink.DEBUG_HOSTS && (u.scheme == "http" || u.scheme == "https")
    }

    private fun wsAllowed(ws: String, allowDebug: Boolean): Boolean {
        if (isProdWs(ws)) return true
        val u = try { URI(ws) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return allowDebug && host in JoinLink.DEBUG_HOSTS && (u.scheme == "ws" || u.scheme == "wss") && u.rawPath == "/v1/ws"
    }

    private fun isProd(origin: String): Boolean {
        val u = try { URI(origin) } catch (_: Exception) { return false }
        return u.scheme == "https" && u.host?.lowercase() == JoinLink.PROD_HOST && (u.port == -1 || u.port == 443) &&
            (u.rawPath.isNullOrEmpty() || u.rawPath == "/") && u.rawQuery == null && u.rawUserInfo == null
    }

    private fun isProdWs(ws: String): Boolean {
        val u = try { URI(ws) } catch (_: Exception) { return false }
        return u.scheme == "wss" && u.host?.lowercase() == JoinLink.PROD_HOST && (u.port == -1 || u.port == 443) &&
            u.rawPath == "/v1/ws" && u.rawQuery == null
    }

    /** Storage form (encrypted by the platform store). */
    fun encode(i: ResumeInfo): String = buildJsonObject {
        put("v", 1); put("token", i.token); put("origin", i.origin); put("ws", i.ws)
        i.home?.let { put("home", it) }; put("at", i.joinedAt)
    }.toString()

    fun decode(s: String): ResumeInfo? = try {
        val o = json.parseToJsonElement(s).jsonObject
        val t = str(o, "token")
        if (t == null || t.length < TOKEN_MIN || t.length > TOKEN_MAX) null
        else ResumeInfo(
            t,
            str(o, "origin") ?: JoinLink.PROD_ORIGIN,
            str(o, "ws") ?: JoinLink.PROD_WS,
            str(o, "home"),
            (o["at"] as? JsonPrimitive)?.longOrNull ?: 0,
        )
    } catch (_: Exception) { null }

    /** `/v1/resume` 200 body: {"v":1,"join":"https://…/agent_join.html?ws=…&qr=sl1:…","exp":…}. */
    fun joinFromBody(body: String): String? = try {
        val o = json.parseToJsonElement(body).jsonObject
        str(o, "join")?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /** Log-safe, stable id for a join URL (to skip offers we already tried). */
    fun joinKey(join: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(join.toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
}

/** Platform storage (Android: Keystore AES-GCM file next to PairingState). */
interface ResumeStore {
    fun load(): ResumeInfo?
    fun save(info: ResumeInfo)
    fun clear()
}

class MemoryResumeStore(private var v: ResumeInfo? = null) : ResumeStore {
    var saves = 0; var clears = 0
    @Synchronized override fun load() = v
    @Synchronized override fun save(info: ResumeInfo) { v = info; saves++ }
    @Synchronized override fun clear() { v = null; clears++ }
}

data class ResumeConfig(
    /** 404 (stick not offering yet): wait this long. */
    val notFoundWaitMs: Long = 2_000,
    /** Network / 5xx backoff 2 s → 15 s. */
    val backoffMinMs: Long = 2_000,
    val backoffMaxMs: Long = 15_000,
    /**
     * Dead token: no fresh offer (only 404 / already-tried / expired joins) for
     * this long while the network works. The stick re-offers while paired, so a
     * long silence means Generate (or the stick lost its token, e.g. reboot).
     */
    val deadAfterMs: Long = 180_000,
    /** Stop auto-retrying (token kept, user can retry) after this long. */
    val maxReconnectMs: Long = 30 * 60_000,
)

sealed class PollOutcome {
    data class Offer(val join: String) : PollOutcome()
    data object NotFound : PollOutcome()
    data class Http(val code: Int) : PollOutcome()
    data class Network(val why: String) : PollOutcome()
}

sealed class ResumeDecision {
    data class Connect(val join: String) : ResumeDecision()
    data class Wait(val ms: Long, val why: String) : ResumeDecision()
    /** [clear]: the token is dead; delete it and ask for a new scan. */
    data class GiveUp(val clear: Boolean, val why: String) : ResumeDecision()
}

/**
 * Pure poll/backoff/dead-token policy for one reconnect episode.
 * Clock injected for tests.
 */
class ResumePolicy(private val cfg: ResumeConfig = ResumeConfig(), private val now: () -> Long = System::currentTimeMillis) {
    private val startedAt = now()
    private var staleSince: Long? = null
    private var backoff = cfg.backoffMinMs
    private val tried = LinkedHashSet<String>()
    private var lastStep: Long? = null

    /**
     * A long gap between steps means we were suspended (Doze / app freezer while
     * backgrounded), not that the stick stayed silent: restart the dead-token clock.
     */
    private fun noteStep() {
        val t = now()
        val prev = lastStep
        if (prev != null && t - prev > maxOf(cfg.notFoundWaitMs * 5, cfg.backoffMaxMs * 2)) staleSince = null
        lastStep = t
    }

    /** Mark a join URL as used (the one we just connected with, or one that failed). */
    fun markTried(join: String) {
        tried += ResumeCodec.joinKey(join)
        while (tried.size > 64) tried.remove(tried.first())
    }

    fun wasTried(join: String) = ResumeCodec.joinKey(join) in tried

    fun onPoll(o: PollOutcome, joinUsable: (String) -> Boolean = { true }): ResumeDecision {
        noteStep()
        timeout()?.let { return it }
        return when (o) {
            is PollOutcome.Offer ->
                if (wasTried(o.join) || !joinUsable(o.join)) stale("stale offer") else ResumeDecision.Connect(o.join)
            PollOutcome.NotFound -> stale("no offer yet")
            is PollOutcome.Http -> when (o.code) {
                // Worker: 400 = malformed token; 410 = (future) revoked. Never valid again.
                400, 410 -> ResumeDecision.GiveUp(clear = true, why = "resume token rejected (${o.code})")
                else -> backoff("http ${o.code}")
            }
            is PollOutcome.Network -> backoff("network")
        }
    }

    fun onConnectFailed(join: String, kind: MethodCError.Kind?): ResumeDecision {
        markTried(join)
        noteStep()
        timeout()?.let { return it }
        return when (kind) {
            // Offer already used / expired / refused, or not our stick: like "no offer".
            MethodCError.Kind.MAILBOX_GONE, MethodCError.Kind.EXPIRED, MethodCError.Kind.BAD_LINK,
            MethodCError.Kind.HANDSHAKE -> stale("offer refused (${kind.name})")
            else -> backoff("connect ${kind?.name ?: "error"}")
        }
    }

    private fun stale(why: String): ResumeDecision {
        backoff = cfg.backoffMinMs
        val t = now()
        val since = staleSince ?: t.also { staleSince = it }
        if (t - since >= cfg.deadAfterMs) return ResumeDecision.GiveUp(clear = true, why = "stick stopped offering reconnects")
        return ResumeDecision.Wait(cfg.notFoundWaitMs, why)
    }

    private fun backoff(why: String): ResumeDecision {
        staleSince = null // can't tell a dead token while the network is failing
        val w = backoff
        backoff = (backoff * 2).coerceAtMost(cfg.backoffMaxMs)
        return ResumeDecision.Wait(w, why)
    }

    private fun timeout(): ResumeDecision? =
        if (now() - startedAt >= cfg.maxReconnectMs) ResumeDecision.GiveUp(clear = false, why = "gave up after ${cfg.maxReconnectMs / 60_000} min") else null
}
