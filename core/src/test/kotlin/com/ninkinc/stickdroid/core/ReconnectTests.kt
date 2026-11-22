package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 0.3.2 Reconnect control: soft → resume, revoked → no loop + scan, dedupe, fallback, rate limit. */
class ReconnectStateMachineTests {
    private var t = 1_000_000L
    private val store = MemoryAlwaysOnStore()
    private val states = mutableListOf<LinkState>()
    private val log = mutableListOf<String>()
    /** Each await advances the fake clock; counts waits so tests can bound loops. */
    private var waits = 0
    private var wakes = 0
    private var onAwait: (Long) -> Unit = {}
    private val waiter = object : Waiter {
        override fun await(ms: Long) { waits++; t += ms; onAwait(ms); if (waits > 500) error("loop did not stop") }
        override fun wake() { wakes++ }
    }
    /** Spawned loops are queued and run explicitly with [runSpawned] (lets taps interleave). */
    private val spawned = ArrayDeque<() -> Unit>()
    private fun runSpawned() { while (spawned.isNotEmpty()) spawned.removeFirst()() }

    private class FakeLink(override val resume: ResumeOffer = ResumeOffer.Missing) : LiveLink {
        override val home: String? = null
        @Volatile var alive = true
        override val isAlive: Boolean get() = alive
        override var onLost: ((String) -> Unit)? = null
        var closedWith: String? = "never"
        override fun close(why: String?) { alive = false; closedWith = why }
        fun drop(why: String) { alive = false; onLost?.invoke(why) }
    }

    private var resumeCalls = 0
    private var resumeScript: (Int) -> LiveLink = { error("unscripted") }
    private val sess = SessConnector { info ->
        assertNotNull(info.pairPsk, "resume must have pair_psk (v2)")
        resumeCalls++
        resumeScript(resumeCalls)
    }
    private var connectCalls = 0
    private var httpCalls = 0
    private var httpReply: () -> Pair<Int, String> = { 404 to "" }

    private fun mgr(cfg: ResumeConfig = ResumeConfig(deadAfterMs = Long.MAX_VALUE / 4)) = LinkManager(
        store = store,
        connector = LinkConnector { connectCalls++; error("phone must not join/mint on reconnect") },
        http = ResumeHttp { httpCalls++; httpReply() },
        wss = sess,
        cfg = cfg,
        clock = { t },
        waiter = waiter,
        spawn = { _, b -> spawned.addLast(b) },
        log = { log += it },
        onState = { states += it },
    )

    /** Manager live on [first] via a cold-start resume. */
    private fun liveManager(first: FakeLink = FakeLink()): Pair<LinkManager, FakeLink> {
        store.save(sample())
        val m = mgr()
        resumeScript = { first }
        m.start(); runSpawned()
        assertEquals(LinkState.Live(durable = false, warning = LinkManager.OLD_STICK_WARNING).durable, (m.state as LinkState.Live).durable)
        // FakeLink has no pins → adopt clears store; restore pins (real SessSession carries them).
        store.save(sample())
        return m to first
    }

    private fun soft(kind: SessResumeError.Kind = SessResumeError.Kind.NETWORK) = SessResumeError(kind, "soft")

    @Test fun softDropReconnectRetriesResumeNowWithoutScan() {
        val (m, l1) = liveManager()
        val l2 = FakeLink()
        // first auto attempt fails (network), then the user taps Reconnect → resume succeeds
        resumeScript = { n -> if (n == 2) throw soft() else l2 }
        var tapResult: ReconnectResult? = null
        onAwait = { if (tapResult == null) tapResult = m.reconnectNow() }
        l1.drop("transport: wss flap")
        assertTrue(m.state is LinkState.Reconnecting)
        runSpawned()
        assertEquals(ReconnectResult.RETRYING_NOW, tapResult)
        assertTrue(m.state is LinkState.Live, "state=${m.state}")
        assertEquals(3, resumeCalls)
        assertEquals(0, connectCalls, "Reconnect must never Method-C join / mint")
        assertTrue(states.any { it is LinkState.Reconnecting }, "shows Reconnecting… progress")
        assertTrue(states.any { it is LinkState.Connecting && it.viaResume })
        assertEquals(0, m.softFailures, "reset on success")
    }

    @Test fun tapResetsBackoff() {
        val (m, l1) = liveManager()
        val waitsSeen = mutableListOf<Long>()
        resumeScript = { n -> if (n <= 5) throw soft() else FakeLink() }
        var tapped = false
        onAwait = { ms -> waitsSeen += ms; if (waitsSeen.size == 3 && !tapped) { tapped = true; m.reconnectNow() } }
        l1.drop("transport: lost")
        runSpawned()
        // 2s, 4s, 8s, then tap → backoff restarts at 2s
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 2_000L), waitsSeen.take(4))
        assertTrue(m.state is LinkState.Live)
    }

    @Test fun reconnectFromPausedStartsFreshLoop() {
        store.save(sample())
        val m = mgr(ResumeConfig(deadAfterMs = Long.MAX_VALUE / 4, maxReconnectMs = 10_000))
        resumeScript = { throw soft() }
        m.start(); runSpawned()
        assertTrue(m.state is LinkState.Paused, "state=${m.state}")
        val before = resumeCalls
        resumeScript = { FakeLink() }
        assertEquals(ReconnectResult.STARTED, m.reconnectNow())
        runSpawned()
        assertEquals(before + 1, resumeCalls)
        assertTrue(m.state is LinkState.Live)
    }

    @Test fun inFlightTapDoesNotStartSecondAttempt() {
        val (m, l1) = liveManager()
        val results = mutableListOf<ReconnectResult>()
        resumeScript = {
            // user hammers Reconnect while sess.resume is on the wire
            repeat(3) { results += m.reconnectNow() }
            FakeLink()
        }
        l1.drop("transport: lost")
        runSpawned()
        assertEquals(listOf(ReconnectResult.IN_FLIGHT, ReconnectResult.IN_FLIGHT, ReconnectResult.IN_FLIGHT), results)
        assertEquals(2, resumeCalls, "cold start + exactly one resume after the drop")
        assertTrue(spawned.isEmpty(), "no second loop spawned")
        assertTrue(m.state is LinkState.Live)
        assertEquals(ReconnectResult.ALREADY_LIVE, m.reconnectNow())
    }

    @Test fun liveRevoke4403StopsAndOffersScanOnly() {
        val (m, l1) = liveManager()
        l1.drop("4403")
        runSpawned()
        val s = m.state
        assertTrue(s is LinkState.NeedScan && s.revoked, "state=$s")
        assertEquals(LinkManager.REVOKED_MESSAGE, (s as LinkState.NeedScan).message)
        assertEquals("Session ended on the stick. Scan a new StickLink QR.", s.message)
        assertNull(store.load(), "stored session cleared")
        assertEquals(1, resumeCalls, "only the cold-start resume; none after revoke")
        assertFalse(states.dropWhile { it !is LinkState.NeedScan }.any { it is LinkState.Reconnecting })
        // Reconnect after revoke → scan path, no resume
        assertEquals(ReconnectResult.SCAN_REQUIRED, m.reconnectNow())
        runSpawned()
        assertEquals(1, resumeCalls)
        assertEquals(0, waits)
    }

    @Test fun sessRevokedReasonAlsoTerminal() {
        val (m, l1) = liveManager()
        l1.drop("revoked")
        runSpawned()
        assertTrue((m.state as LinkState.NeedScan).revoked)
    }

    @Test fun resumeFailsRevokedStopsLoop() {
        val (m, l1) = liveManager()
        resumeScript = { n -> if (n == 2) throw soft() else throw SessResumeError(SessResumeError.Kind.REVOKED, "4403") }
        l1.drop("transport: lost")
        runSpawned()
        val s = m.state
        assertTrue(s is LinkState.NeedScan && s.revoked, "state=$s")
        assertEquals(3, resumeCalls)
        assertNull(store.load())
        assertEquals(LinkState.NeedScan::class, states.last()::class, "never Reconnecting after revoke")
        assertEquals(ReconnectResult.SCAN_REQUIRED, m.reconnectNow())
        assertEquals(3, resumeCalls)
    }

    @Test fun authAfterLegacyTryEndsAsRevoked() {
        val (m, l1) = liveManager()
        resumeScript = { throw SessResumeError(SessResumeError.Kind.AUTH, "4401") }
        httpReply = { 404 to "" }
        l1.drop("transport: lost")
        runSpawned()
        assertTrue((m.state as LinkState.NeedScan).revoked)
        assertEquals(2, resumeCalls)
        assertEquals(1, httpCalls, "legacy GET once, read-only")
        assertEquals(0, connectCalls)
    }

    @Test fun revokeDuringBackoffWaitStopsLoop() {
        // e.g. user had already scanned elsewhere → Worker revokes; next attempt 4403 → stop.
        val (m, l1) = liveManager()
        resumeScript = { n -> if (n < 4) throw soft() else throw SessResumeError(SessResumeError.Kind.REVOKED, "sess.revoked") }
        l1.drop("transport: lost")
        runSpawned()
        assertTrue((m.state as LinkState.NeedScan).revoked)
        val w = waits
        runSpawned()
        assertEquals(w, waits, "no further waits/loops")
    }

    @Test fun scanFallbackOfferedAfterThreeSoftFailures() {
        val (m, l1) = liveManager()
        resumeScript = { n -> if (n <= 4) throw soft() else FakeLink() }
        val seen = mutableListOf<Pair<Int, Boolean>>()
        onAwait = { val s = m.state; if (s is LinkState.Reconnecting) seen += s.failures to s.offerScan }
        l1.drop("transport: lost")
        runSpawned()
        assertEquals(listOf(1 to false, 2 to false, 3 to true), seen.take(3))
        assertTrue(states.any { it is LinkState.Connecting && it.offerScan }, "fallback stays visible during the next attempt")
        assertTrue(m.state is LinkState.Live)
        assertEquals(0, m.softFailures)
    }

    @Test fun rateLimitedBlocksWithoutSpamming() {
        val (m, l1) = liveManager()
        resumeScript = { n ->
            if (n == 2) throw SessResumeError(SessResumeError.Kind.RATE_LIMITED, "rate", retryAfterSec = 40, code = "rate") else FakeLink()
        }
        val blockedSeen = mutableListOf<LinkState.Blocked>()
        val taps = mutableListOf<ReconnectResult>()
        onAwait = {
            (m.state as? LinkState.Blocked)?.let { b ->
                if (blockedSeen.isEmpty()) {
                    blockedSeen += b
                    // tap during cooldown: no new attempt
                    t -= it // undo this await's advance to tap "mid-wait"
                    taps += m.reconnectNow()
                    m.onForeground() // a foreground wake must not cut the wait short either
                    t += it
                }
            }
        }
        val tDrop = t
        l1.drop("transport: lost")
        runSpawned()
        assertEquals(1, blockedSeen.size)
        val b = blockedSeen[0]
        assertTrue(b.rateLimited)
        assertEquals(LinkManager.RATE_LIMITED_MESSAGE, b.message)
        assertEquals(tDrop + 40_000, b.retryAtMs)
        assertEquals(listOf(ReconnectResult.COOLDOWN), taps)
        assertEquals(3, resumeCalls, "exactly one retry after the cooldown")
        assertTrue(t - tDrop >= 40_000, "waited the server's retry")
        assertTrue(m.state is LinkState.Live)
    }

    @Test fun stickBusyShowsStickMessage() {
        val (m, l1) = liveManager()
        resumeScript = { n ->
            if (n == 2) throw SessResumeError(SessResumeError.Kind.STICK, "busy", retryAfterSec = 30, stickMessage = "No QR right now, try in a bit", code = "busy")
            else FakeLink()
        }
        var msg: String? = null
        onAwait = { (m.state as? LinkState.Blocked)?.let { if (msg == null) msg = it.message } }
        l1.drop("transport: lost")
        runSpawned()
        assertEquals("Your stick says: No QR right now, try in a bit", msg)
        assertTrue(m.state is LinkState.Live)
    }

    @Test fun reconnectWithoutPinsNeedsScan() {
        val m = mgr()
        m.start()
        assertEquals(ReconnectResult.SCAN_REQUIRED, m.reconnectNow())
        assertEquals(0, resumeCalls)
    }

    @Test fun disconnectFromSettingsStillClosesWithUser() {
        val (m, l1) = liveManager()
        m.disconnect(); runSpawned()
        assertEquals("user", l1.closedWith)
        assertNull(store.load())
        assertEquals(LinkState.Disconnected, m.state)
        assertEquals(ReconnectResult.SCAN_REQUIRED, m.reconnectNow())
    }

    @Test fun scanAfterRevokeWorksOnce() {
        val (m, l1) = liveManager()
        l1.drop("4403"); runSpawned()
        assertTrue((m.state as LinkState.NeedScan).revoked)
        var joins = 0
        val m2 = LinkManager(
            store = store,
            connector = LinkConnector { joins++; FakeLink() },
            http = ResumeHttp { 404 to "" }, wss = sess, clock = { t }, waiter = waiter,
            spawn = { _, b -> b() },
        )
        m2.connectScanned(JoinTarget("wss://link.stickllm.com/v1/ws", "https://link.stickllm.com",
            QrInvite(ByteArray(8), ByteArray(32) { 7 }, ByteArray(32) { 3 }, Long.MAX_VALUE), false))
        assertEquals(1, joins)
        assertTrue(m2.state is LinkState.Live)
    }
}

class SessErrMappingTests {
    @Test fun rateWithRetry() {
        val e = sessErrFromFrame(buildJsonObject { put("t", "err"); put("code", "rate"); put("retry", 42) }, "sess.resume")
        assertEquals(SessResumeError.Kind.RATE_LIMITED, e.kind)
        assertEquals(42L, e.retryAfterSec)
    }
    @Test fun busyAndOfflineAndAuth() {
        assertEquals(SessResumeError.Kind.STICK, sessErrFromFrame(buildJsonObject { put("code", "busy") }, "sess.resume").kind)
        assertEquals(SessResumeError.Kind.OFFLINE, sessErrFromFrame(buildJsonObject { put("c", "offline") }, "sess.frame").kind)
        assertEquals(SessResumeError.Kind.AUTH, sessErrFromFrame(buildJsonObject { put("code", "auth") }, "sess.resume").kind)
        assertEquals(SessResumeError.Kind.HANDSHAKE, sessErrFromFrame(buildJsonObject { put("code", "bad") }, "sess.frame").kind)
    }
    @Test fun stickMessageKept() {
        val e = sessErrFromFrame(buildJsonObject { put("code", "busy"); put("msg", "kiosk has no QR") }, "sess.resume")
        assertEquals("kiosk has no QR", e.stickMessage)
    }
    @Test fun revokeReasons() {
        assertTrue(LinkManager.isRevokeReason("4403"))
        assertTrue(LinkManager.isRevokeReason("revoked"))
        assertFalse(LinkManager.isRevokeReason("transport: closed"))
    }
}

private fun sample() = AlwaysOnResume(
    token = "abcdefghijklmnopqrstuv",
    origin = "https://link.stickllm.com",
    ws = "wss://link.stickllm.com/v1/ws",
    home = ByteArray(16) { 7 },
    stickPub = ByteArray(32) { 8 },
    phonePriv = ByteArray(32) { 1 },
    phonePub = ByteArray(32) { 2 },
    pairPsk = ByteArray(32) { 9 },
    hwm = 3,
)
