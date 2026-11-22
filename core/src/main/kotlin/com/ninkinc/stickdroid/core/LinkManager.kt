package com.ninkinc.stickdroid.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A live session as the manager sees it ([MethodCSession] or [SessSession]). */
interface LiveLink {
    val resume: ResumeOffer
    val home: String?
    val isAlive: Boolean
    var onLost: ((String) -> Unit)?
    /** Polite close. why="user" ends the pairing on the stick. */
    fun close(why: String?)
}

fun interface LinkConnector {
    /** Full Method C connect (first QR or legacy resume join). Throws [MethodCError]. */
    fun connect(target: JoinTarget): LiveLink
}

fun interface ResumeHttp {
    /** GET; returns (status, body). Never log [url] (may contain token). */
    fun get(url: String): Pair<Int, String>
}

interface Waiter {
    fun await(ms: Long)
    fun wake()
}

class MonitorWaiter : Waiter {
    private val lock = Object()
    private var woken = false
    override fun await(ms: Long) = synchronized(lock) {
        if (!woken) lock.wait(ms.coerceAtLeast(1))
        woken = false
    }
    override fun wake() = synchronized(lock) { woken = true; lock.notifyAll() }
}

class OkResumeHttp(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
) : ResumeHttp {
    override fun get(url: String): Pair<Int, String> {
        val req = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store").build()
        http.newCall(req).execute().use { r -> return r.code to (r.body?.string()?.take(8192) ?: "") }
    }
}

sealed class LinkState {
    /**
     * No usable pairing: scan a StickLink QR. [revoked] = the stick/Worker ended the
     * session (4403 / sess.revoke / token gone): never auto-resume, offer Scan QR.
     */
    data class NeedScan(val message: String? = null, val revoked: Boolean = false) : LinkState()
    /** viaResume=true: an always-on resume attempt is in flight (Reconnect is a no-op). */
    data class Connecting(val viaResume: Boolean, val failures: Int = 0) : LinkState() {
        val offerScan: Boolean get() = viaResume && failures >= LinkManager.SCAN_FALLBACK_AFTER
    }
    data class Live(val durable: Boolean, val warning: String? = null) : LinkState()
    /** Soft drop, waiting between always-on resume attempts. [failures] = failed soft resumes so far. */
    data class Reconnecting(val attempt: Int, val detail: String, val failures: Int = 0) : LinkState() {
        val offerScan: Boolean get() = failures >= LinkManager.SCAN_FALLBACK_AFTER
    }
    /**
     * Worker/stick refused for now (rate limit, busy, disabled). Pins kept; no resume
     * until [retryAtMs] (no spamming). [message] is the stick's text when it sent one.
     */
    data class Blocked(val message: String, val retryAtMs: Long, val rateLimited: Boolean) : LinkState()
    data class Paused(val message: String) : LinkState()
    data object Disconnected : LinkState()
}

/** Outcome of the user's Reconnect tap ([LinkManager.reconnectNow]). */
enum class ReconnectResult {
    /** A fresh always-on resume loop was started (from Paused / idle-after-drop). */
    STARTED,
    /** The running loop was woken to retry now with backoff reset. */
    RETRYING_NOW,
    /** A resume (or scan join) is already in flight; nothing new started. */
    IN_FLIGHT,
    /** Rate-limited / stick busy: wait until the retry time. */
    COOLDOWN,
    /** Revoked / no pins / disconnected: resume can't work, scan a new QR. */
    SCAN_REQUIRED,
    ALREADY_LIVE,
}

/**
 * Pairing lifecycle for always-on slice 1:
 *  - First QR → Method C → persist AlwaysOnResume pins (no transport keys)
 *  - Wake / transport loss → WSS sess.resume (PSK v2, primary); legacy Method C once
 *  - Reconnect tap → retry the same resume now (deduped, backoff reset); never mints codes
 *  - Disconnect / 4403 / sess.revoke → clear storage → NeedScan (revoked: no resume loop)
 *  - Rate limit / stick busy → Blocked until retry time (no spamming)
 *  - No 180s inferred-Generate; only 400 / 4401-after-legacy / 4403 clear pairing
 */
class LinkManager(
    private val store: AlwaysOnStore,
    private val connector: LinkConnector,
    private val http: ResumeHttp,
    private val wss: SessConnector = WssSessClient(),
    private val allowDebugHosts: () -> Boolean = { false },
    private val cfg: ResumeConfig = ResumeConfig(deadAfterMs = Long.MAX_VALUE / 4), // no inferred Generate
    private val clock: () -> Long = System::currentTimeMillis,
    private val waiter: Waiter = MonitorWaiter(),
    private val spawn: (String, () -> Unit) -> Unit = { n, b -> Thread(b, n).apply { isDaemon = true; start() } },
    private val log: (String) -> Unit = {},
    private val onState: (LinkState) -> Unit = {},
    private val onLive: (LiveLink) -> Unit = {},
) {
    @Volatile var state: LinkState = LinkState.NeedScan(); private set
    @Volatile var current: LiveLink? = null; private set
    private var gen = 0
    private var legacyTried = false
    /** gen of the running reconnect loop (-1 = none). */
    private var loopGen = -1
    /** A resume/legacy connect is on the wire right now (dedupe Reconnect taps). */
    @Volatile private var inFlight = false
    /** Set by a Reconnect tap: next loop iteration restarts backoff and the give-up timer. */
    @Volatile private var resetBackoff = false
    /** Failed soft resumes since the last Live (drives the Scan QR fallback). */
    @Volatile var softFailures = 0; private set
    @Volatile private var blockedUntil = 0L
    /** Count of always-on resume attempts started (tests / diagnostics). */
    @Volatile var resumeAttempts = 0; private set

    private fun set(s: LinkState) { state = s; onState(s) }

    fun start() {
        val saved = store.load()
        if (saved != null && saved.canWssResume()) {
            log("cold start with saved resume ${saved.tag()}")
            beginReconnect("app start")
        } else set(LinkState.NeedScan())
    }

    fun hasResume(): Boolean = store.load()?.canWssResume() == true

    fun connectScanned(target: JoinTarget) {
        val my = synchronized(this) { ++gen }
        waiter.wake()
        legacyTried = false
        softFailures = 0; blockedUntil = 0
        set(LinkState.Connecting(viaResume = false))
        spawn("methodc-join") {
            try {
                val l = connector.connect(target)
                if (!adopt(l, my)) l.close(null)
            } catch (e: MethodCError) {
                if (isCurrent(my)) set(LinkState.NeedScan(e.userText()))
            } catch (e: Exception) {
                if (isCurrent(my)) set(LinkState.NeedScan("Couldn't join: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    fun disconnect() {
        val l: LiveLink?
        synchronized(this) { gen++; l = current; current = null }
        waiter.wake()
        store.clear()
        legacyTried = false
        softFailures = 0; blockedUntil = 0
        set(LinkState.Disconnected)
        log("user disconnect")
        if (l != null) spawn("methodc-close") { try { l.close("user") } catch (_: Exception) {} }
    }

    fun scanNew() {
        val l: LiveLink?
        synchronized(this) { gen++; l = current; current = null }
        waiter.wake()
        store.clear()
        legacyTried = false
        softFailures = 0; blockedUntil = 0
        set(LinkState.NeedScan())
        if (l != null) spawn("methodc-close") { try { l.close("user") } catch (_: Exception) {} }
    }

    /**
     * The Reconnect button. Soft drop with valid pins → retry the always-on resume
     * (sess.resume, PSK v2) now, backoff reset. Never starts a second concurrent
     * attempt, never resumes after a revoke, never mints/Generates a code.
     */
    fun reconnectNow(): ReconnectResult {
        val s = state
        val decision: ReconnectResult = synchronized(this) {
            when {
                s is LinkState.Live -> ReconnectResult.ALREADY_LIVE
                s is LinkState.NeedScan || s is LinkState.Disconnected -> ReconnectResult.SCAN_REQUIRED
                store.load()?.canWssResume() != true -> ReconnectResult.SCAN_REQUIRED
                inFlight || s is LinkState.Connecting -> ReconnectResult.IN_FLIGHT
                s is LinkState.Blocked && clock() < s.retryAtMs -> ReconnectResult.COOLDOWN
                loopGen == gen -> ReconnectResult.RETRYING_NOW
                else -> ReconnectResult.STARTED
            }
        }
        log("reconnect tap → $decision")
        when (decision) {
            ReconnectResult.RETRYING_NOW -> { resetBackoff = true; blockedUntil = 0; waiter.wake() }
            ReconnectResult.STARTED -> { resetBackoff = false; blockedUntil = 0; beginReconnect("user reconnect") }
            ReconnectResult.SCAN_REQUIRED ->
                if (s !is LinkState.NeedScan && s !is LinkState.Disconnected) set(LinkState.NeedScan())
            else -> {}
        }
        return decision
    }

    /** Back-compat alias (Paused "Try again"). */
    fun retryNow() { reconnectNow() }

    fun onForeground() {
        val l = current
        when {
            l != null && !l.isAlive -> lost(l, "found dead after background")
            inFlight -> {}
            state is LinkState.Reconnecting -> waiter.wake()
            state is LinkState.Paused -> beginReconnect("foreground")
        }
    }

    fun shutdown() {
        val l: LiveLink?
        synchronized(this) { gen++; l = current; current = null }
        waiter.wake()
        // Persist hwm if SessSession
        (l as? SessSession)?.let {
            val h = it.currentHwm()
            val cur = store.load()
            if (cur != null && h > cur.hwm) store.save(cur.withHwm(h))
        }
        try { l?.close(null) } catch (_: Exception) {}
    }

    private fun isCurrent(my: Int) = synchronized(this) { gen == my }

    private fun adopt(l: LiveLink, my: Int): Boolean {
        synchronized(this) {
            if (gen != my) return false
            current = l
        }
        softFailures = 0; blockedUntil = 0
        val always = when (l) {
            is MethodCSession -> l.alwaysOn
            is SessSession -> l.alwaysOn
            else -> null
        }
        val st = when {
            always != null && always.canWssResume() -> {
                store.save(always)
                log("always-on pins saved ${always.tag()}")
                if (l is SessSession) {
                    l.onResumeUpdate = { store.save(it) }
                }
                LinkState.Live(durable = true)
            }
            l.resume is ResumeOffer.Present -> {
                // Token without pins: cannot WSS-resume; soft warning
                store.clear()
                LinkState.Live(durable = false, warning = OLD_STICK_WARNING)
            }
            else -> {
                store.clear()
                LinkState.Live(durable = false, warning = OLD_STICK_WARNING)
            }
        }
        onLive(l)
        set(st)
        l.onLost = { why -> lost(l, why) }
        if (!l.isAlive) lost(l, "lost during setup")
        return true
    }

    private fun lost(l: LiveLink, why: String) {
        synchronized(this) {
            if (current !== l) return
            current = null
        }
        log("transport lost: $why")
        if (isRevokeReason(why)) {
            endRevoked("live link $why")
            return
        }
        if (store.load()?.canWssResume() != true) {
            set(LinkState.NeedScan("Session ended. Scan a new StickLink QR."))
            return
        }
        beginReconnect(why)
    }

    /** Terminal: stop every resume/backoff loop, forget pins, ask for a new QR. */
    private fun endRevoked(why: String) {
        synchronized(this) { gen++; loopGen = -1 }
        waiter.wake()
        store.clear()
        legacyTried = false
        softFailures = 0; blockedUntil = 0; resetBackoff = false
        log("session ended by stick ($why); resume stopped")
        set(LinkState.NeedScan(REVOKED_MESSAGE, revoked = true))
    }

    private fun beginReconnect(why: String) {
        val my = synchronized(this) { ++gen; loopGen = gen; gen }
        set(LinkState.Reconnecting(0, "Reconnecting…", softFailures))
        spawn("methodc-resume") {
            try { reconnectLoop(my) } finally { synchronized(this) { if (loopGen == my) loopGen = -1 } }
        }
    }

    /** Wait [ms], but return early if the user tapped Reconnect (then backoff restarts). */
    private fun pause(ms: Long) { waiter.await(ms) }

    private fun reconnectLoop(my: Int) {
        var attempt = 0
        var backoff = cfg.backoffMinMs
        var started = clock()
        while (isCurrent(my)) {
            if (resetBackoff) { resetBackoff = false; backoff = cfg.backoffMinMs; started = clock() }
            if (clock() - started >= cfg.maxReconnectMs) {
                if (isCurrent(my)) set(LinkState.Paused("Couldn't reach your stick. Make sure it's on and online, then tap Reconnect."))
                return
            }
            val info = store.load()
            if (info == null || !info.canWssResume()) {
                if (isCurrent(my)) set(LinkState.NeedScan())
                return
            }
            attempt++
            if (isCurrent(my)) set(LinkState.Reconnecting(attempt, "Reconnecting…", softFailures))
            // Primary: WSS always-on (PSK v2). One attempt at a time.
            try {
                if (!isCurrent(my)) return
                set(LinkState.Connecting(viaResume = true, failures = softFailures))
                resumeAttempts++
                inFlight = true
                val l = try { wss.connect(info) } finally { inFlight = false }
                if (!adopt(l, my)) l.close(null)
                else log("wss resumed ${info.tag()}")
                return
            } catch (e: SessResumeError) {
                log("wss resume failed ${e.kind}")
                if (!isCurrent(my)) return
                when (e.kind) {
                    SessResumeError.Kind.REVOKED -> { endRevoked("resume ${e.message}"); return }
                    SessResumeError.Kind.AUTH -> {
                        // 4401 / err auth: token unknown after Generate/revoke (Worker clears the row).
                        // Try legacy once; if that also fails, it's over (do not poll forever).
                        if (!legacyTried) {
                            legacyTried = true
                            if (tryLegacyOnce(info, my)) return
                        }
                        if (isCurrent(my)) endRevoked("resume auth")
                        return
                    }
                    SessResumeError.Kind.RATE_LIMITED, SessResumeError.Kind.STICK -> {
                        softFailures++
                        val rate = e.kind == SessResumeError.Kind.RATE_LIMITED
                        val waitMs = ((e.retryAfterSec ?: (if (rate) DEFAULT_RATE_WAIT_S else DEFAULT_STICK_WAIT_S)) * 1000)
                            .coerceIn(MIN_BLOCK_MS, MAX_BLOCK_MS)
                        blockedUntil = clock() + waitMs
                        set(LinkState.Blocked(blockedMessage(e), blockedUntil, rate))
                        // Hold off: wakes (foreground etc.) don't cut the wait short.
                        while (isCurrent(my) && clock() < blockedUntil) pause(blockedUntil - clock())
                        backoff = cfg.backoffMinMs
                    }
                    SessResumeError.Kind.UNAVAILABLE, SessResumeError.Kind.HANDSHAKE,
                    SessResumeError.Kind.NETWORK, SessResumeError.Kind.AGENT, SessResumeError.Kind.OFFLINE -> {
                        softFailures++
                        // Probe legacy GET once in a while: 400 = token malformed/gone.
                        if (probeRevoked(info)) { if (isCurrent(my)) endRevoked("probe 400"); return }
                        if (!legacyTried && (e.kind == SessResumeError.Kind.UNAVAILABLE || e.kind == SessResumeError.Kind.HANDSHAKE)) {
                            legacyTried = true
                            if (tryLegacyOnce(info, my)) return
                        }
                        if (isCurrent(my)) set(LinkState.Reconnecting(attempt, detail(e.kind.name.lowercase()), softFailures))
                        pause(backoff)
                        backoff = (backoff * 2).coerceAtMost(cfg.backoffMaxMs)
                    }
                }
            } catch (e: Exception) {
                log("wss resume error ${e.javaClass.simpleName}")
                softFailures++
                if (isCurrent(my)) set(LinkState.Reconnecting(attempt, "Reconnecting… (no network yet)", softFailures))
                pause(backoff)
                backoff = (backoff * 2).coerceAtMost(cfg.backoffMaxMs)
            }
        }
    }

    /** 400 on GET /v1/resume → token malformed/gone. Never log the URL. */
    private fun probeRevoked(info: AlwaysOnResume): Boolean = try {
        val (code, _) = http.get(
            info.origin.trimEnd('/') + "/v1/resume?t=" +
                java.net.URLEncoder.encode(info.token, Charsets.UTF_8)
        )
        // 400 = malformed. 410 here means expired-but-slot-valid remint — NOT revoke.
        code == 400
    } catch (_: Exception) { false }

    /** One-shot GET /v1/resume?t= legacy Method C if v:1 join with qr=. Read-only: never mints. */
    private fun tryLegacyOnce(info: AlwaysOnResume, my: Int): Boolean {
        return try {
            // Never log poll URL (contains token).
            inFlight = true
            val (code, body) = http.get(info.origin.trimEnd('/') + "/v1/resume?t=" +
                java.net.URLEncoder.encode(info.token, Charsets.UTF_8))
            when (code) {
                410, 400 -> {
                    if (isCurrent(my)) endRevoked("legacy $code")
                    true
                }
                200 -> {
                    AlwaysOnCodec.enrichFromV2Body(body, info)?.let { store.save(it); return false }
                    val join = AlwaysOnCodec.legacyJoinFromBody(body) ?: ResumeCodec.joinFromBody(body)
                    if (join != null && join.contains("qr=")) {
                        log("legacy Method C fallback once")
                        set(LinkState.Connecting(viaResume = true, failures = softFailures))
                        val target = JoinLink.parse(join, allowDebugHosts = allowDebugHosts())
                        val l = connector.connect(target)
                        if (!adopt(l, my)) l.close(null)
                        true
                    } else false
                }
                else -> false
            }
        } catch (e: MethodCError) {
            log("legacy connect failed ${e.kind}"); false
        } catch (e: IOException) {
            false
        } catch (e: Exception) {
            false
        } finally {
            inFlight = false
        }
    }

    private fun detail(why: String) = when {
        why.contains("network") -> "Reconnecting… (no network yet)"
        why.contains("offline") -> "Reconnecting… (your stick is offline right now)"
        why.contains("handshake") -> "Reconnecting… (link attempt failed, retrying)"
        else -> "Reconnecting… (waiting for the stick)"
    }

    private fun blockedMessage(e: SessResumeError): String {
        val stick = e.stickMessage?.trim()?.takeIf { it.isNotEmpty() }
        return when (e.kind) {
            SessResumeError.Kind.RATE_LIMITED ->
                if (stick != null) "StickLink: $stick" else RATE_LIMITED_MESSAGE
            else -> when {
                stick != null -> "Your stick says: $stick"
                e.code == "disabled" -> "StickLink is turned off on your stick. Turn it on in Devices & Pairing."
                else -> "Your stick is busy right now. Trying again shortly."
            }
        }
    }

    companion object {
        /** After this many failed soft resumes, also offer Scan QR next to Reconnect. */
        const val SCAN_FALLBACK_AFTER = 3
        const val DEFAULT_RATE_WAIT_S = 60L
        const val DEFAULT_STICK_WAIT_S = 30L
        const val MIN_BLOCK_MS = 5_000L
        const val MAX_BLOCK_MS = 15 * 60_000L

        const val OLD_STICK_WARNING =
            "This stick doesn't support staying connected yet (needs a stick update). If the link drops you'll need to scan again."
        const val REVOKED_MESSAGE = "Session ended on the stick. Scan a new StickLink QR."
        const val DEAD_TOKEN_MESSAGE = REVOKED_MESSAGE
        const val DISCONNECTED_MESSAGE = "Disconnected. Scan a new StickLink QR to pair again."
        const val RATE_LIMITED_MESSAGE =
            "StickLink is limiting reconnects from this network. Waiting a minute before trying again."

        /** onLost reasons that mean the stick/Worker ended the session. */
        fun isRevokeReason(why: String): Boolean =
            why.contains("4403") || why.contains("revoked") || why.contains("sess.revoke")
    }
}
