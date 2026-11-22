package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URLEncoder

private const val TOKEN = "AbCdEfGhIjKlMnOpQrStUv" // 22 chars, b64u like the stick's
private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

/** A parseable prod join URL with a fresh (unexpired) sl1 QR; [n] varies the mailbox. */
internal fun joinUrl(n: Int, expOffsetSec: Long = 600): String {
    val inv = QrInvite(ByteArray(8) { (n + it).toByte() }, ByteArray(32) { 7 }, ByteArray(32) { (n * 3).toByte() },
        System.currentTimeMillis() / 1000 + expOffsetSec)
    val qr = Qr.format(inv)
    return "https://link.stickllm.com/browser/agent_join.html?ws=" +
        URLEncoder.encode(JoinLink.PROD_WS, Charsets.UTF_8) + "&qr=" + URLEncoder.encode(qr, Charsets.UTF_8).replace("+", "%20")
}

class ResumeParseTests {
    @Test fun parsesAgentReadyResume() {
        val r = ResumeCodec.fromAgentReady(obj("""{"t":"agent_ready","via":"sticklink-lab","resume":{"token":"$TOKEN","origin":"https://link.stickllm.com","ws":"wss://link.stickllm.com/v1/ws"}}"""))
        val i = (r as ResumeOffer.Present).info
        assertEquals(TOKEN, i.token); assertEquals(JoinLink.PROD_ORIGIN, i.origin); assertEquals(JoinLink.PROD_WS, i.ws)
        assertEquals("https://link.stickllm.com/v1/resume?t=$TOKEN", i.pollUrl())
    }

    @Test fun defaultsAndTrailingSlash() {
        val i = (ResumeCodec.fromAgentReady(obj("""{"t":"agent_ready","resume":{"token":"$TOKEN","origin":"https://link.stickllm.com/"}}""")) as ResumeOffer.Present).info
        assertEquals(JoinLink.PROD_ORIGIN, i.origin); assertEquals(JoinLink.PROD_WS, i.ws)
        val j = (ResumeCodec.fromAgentReady(obj("""{"t":"agent_ready","resume":{"token":"$TOKEN"}}""")) as ResumeOffer.Present).info
        assertEquals(JoinLink.PROD_ORIGIN, j.origin)
    }

    @Test fun oldStickHasNoResume() {
        assertEquals(ResumeOffer.Missing, ResumeCodec.fromAgentReady(obj("""{"t":"agent_ready","via":"sticklink-lab"}""")))
    }

    @Test fun rejectsBadResume() {
        fun inv(r: String) = ResumeCodec.fromAgentReady(obj("""{"t":"agent_ready","resume":$r}""")) is ResumeOffer.Invalid
        assertTrue(inv("""{"token":"short"}"""))
        assertTrue(inv("""{"token":"${"x".repeat(129)}"}"""))
        assertTrue(inv("""{"token":"has spaces in the token!!"}"""))
        assertTrue(inv("""{"origin":"https://link.stickllm.com"}"""))
        assertTrue(inv(""""str""""))
        assertTrue(inv("""{"token":"$TOKEN","origin":"https://evil.example"}"""))
        assertTrue(inv("""{"token":"$TOKEN","origin":"http://link.stickllm.com"}"""))
        assertTrue(inv("""{"token":"$TOKEN","ws":"wss://evil.example/v1/ws"}"""))
        // Lab hosts only when the debug lab switch is on.
        assertTrue(inv("""{"token":"$TOKEN","origin":"http://127.0.0.1:8801","ws":"ws://127.0.0.1:8801/v1/ws"}"""))
        val lab = ResumeCodec.fromAgentReady(obj("""{"t":"agent_ready","resume":{"token":"$TOKEN","origin":"http://127.0.0.1:8801","ws":"ws://127.0.0.1:8801/v1/ws"}}"""), allowDebugHosts = true)
        assertEquals("http://127.0.0.1:8801/v1/resume?t=$TOKEN", (lab as ResumeOffer.Present).info.pollUrl())
    }

    @Test fun neverPrintsFullToken() {
        val i = ResumeInfo(TOKEN, home = "homeid1234")
        assertFalse(i.toString().contains(TOKEN)); assertEquals("…StUv", i.tag())
        assertTrue(i.toString().contains("…StUv"))
    }

    @Test fun storageRoundTrip() {
        val i = ResumeInfo(TOKEN, JoinLink.PROD_ORIGIN, JoinLink.PROD_WS, "h0me", 1234L)
        assertEquals(i, ResumeCodec.decode(ResumeCodec.encode(i)))
        assertNull(ResumeCodec.decode("garbage"))
        assertNull(ResumeCodec.decode("""{"token":"short"}"""))
    }

    @Test fun resumeBody() {
        assertEquals("https://x/y", ResumeCodec.joinFromBody("""{"v":1,"join":"https://x/y","exp":1}"""))
        assertNull(ResumeCodec.joinFromBody("not found"))
        assertNull(ResumeCodec.joinFromBody("""{"v":1}"""))
    }
}

class ResumePolicyTests {
    private var t = 0L
    private val cfg = ResumeConfig(deadAfterMs = 60_000, maxReconnectMs = 600_000)
    private fun policy() = ResumePolicy(cfg) { t }

    @Test fun notFoundWaitsTwoSeconds() {
        val p = policy()
        assertEquals(ResumeDecision.Wait(2000, "no offer yet"), p.onPoll(PollOutcome.NotFound))
    }

    @Test fun offerConnectsAndTriedOfferIsSkipped() {
        val p = policy()
        val j = joinUrl(1)
        assertEquals(ResumeDecision.Connect(j), p.onPoll(PollOutcome.Offer(j)))
        p.markTried(j)
        assertTrue(p.onPoll(PollOutcome.Offer(j)) is ResumeDecision.Wait)
        assertTrue(p.onPoll(PollOutcome.Offer(joinUrl(2))) is ResumeDecision.Connect)
        assertTrue(p.onPoll(PollOutcome.Offer(joinUrl(3))) { false } is ResumeDecision.Wait) // expired / bad host
    }

    @Test fun backoffTwoToFifteen() {
        val p = policy()
        val waits = (1..6).map { (p.onPoll(PollOutcome.Network("x")) as ResumeDecision.Wait).ms }
        assertEquals(listOf(2000L, 4000L, 8000L, 15000L, 15000L, 15000L), waits)
        assertEquals(15000L, (p.onPoll(PollOutcome.Http(503)) as ResumeDecision.Wait).ms)
        // A 404 resets the backoff.
        p.onPoll(PollOutcome.NotFound)
        assertEquals(2000L, (p.onPoll(PollOutcome.Http(500)) as ResumeDecision.Wait).ms)
    }

    @Test fun deadTokenAfterLongNoOffer() {
        val p = policy()
        var d: ResumeDecision = p.onPoll(PollOutcome.NotFound)
        var polls = 1
        while (d is ResumeDecision.Wait) { t += (d as ResumeDecision.Wait).ms; d = p.onPoll(PollOutcome.NotFound); polls++ }
        assertEquals(ResumeDecision.GiveUp(clear = true, why = "stick stopped offering reconnects"), d)
        assertTrue(polls in 30..32)
    }

    @Test fun networkOutageDoesNotKillToken() {
        val p = policy()
        p.onPoll(PollOutcome.NotFound)
        t += 50_000
        p.onPoll(PollOutcome.Network("airplane")) // resets the dead timer
        t += 50_000
        assertTrue(p.onPoll(PollOutcome.NotFound) is ResumeDecision.Wait)
        t += 30_000
        assertTrue(p.onPoll(PollOutcome.NotFound) is ResumeDecision.Wait)
        t += 30_000
        assertTrue(p.onPoll(PollOutcome.NotFound) is ResumeDecision.GiveUp)
    }

    @Test fun suspendedAppDoesNotKillToken() {
        val p = policy()
        repeat(10) { p.onPoll(PollOutcome.NotFound); t += 2_000 } // 20 s of 404s
        t += 5 * 60_000 // app frozen in the background for 5 min
        assertTrue(p.onPoll(PollOutcome.NotFound) is ResumeDecision.Wait)
        assertTrue(p.onPoll(PollOutcome.Offer(joinUrl(11))) is ResumeDecision.Connect)
    }

    @Test fun rejectedTokenIsDeadNow() {
        assertEquals(true, (policy().onPoll(PollOutcome.Http(400)) as ResumeDecision.GiveUp).clear)
        assertEquals(true, (policy().onPoll(PollOutcome.Http(410)) as ResumeDecision.GiveUp).clear)
    }

    @Test fun connectFailures() {
        val p = policy()
        val j = joinUrl(4)
        assertEquals(2000L, (p.onConnectFailed(j, MethodCError.Kind.MAILBOX_GONE) as ResumeDecision.Wait).ms)
        assertTrue(p.wasTried(j))
        assertEquals(2000L, (p.onConnectFailed(joinUrl(5), MethodCError.Kind.WEBRTC) as ResumeDecision.Wait).ms)
        assertEquals(4000L, (p.onConnectFailed(joinUrl(6), MethodCError.Kind.NETWORK) as ResumeDecision.Wait).ms)
    }

    @Test fun overallCapKeepsToken() {
        val p = policy()
        t += 600_000
        assertEquals(false, (p.onPoll(PollOutcome.Network("x")) as ResumeDecision.GiveUp).clear)
    }
}


// LinkManager always-on lifecycle: see AlwaysOnLinkManagerTests (AlwaysOnStore + WSS path).
