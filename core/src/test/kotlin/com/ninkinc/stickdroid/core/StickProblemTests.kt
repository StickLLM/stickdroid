package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.3.6: tell "the stick has no model / Agent Zero isn't running" apart from a link drop.
 * 0.3.5 retried a failing send silently (the message was hidden as "direct") and called any
 * stick-side error "Connection interrupted".
 */
class StickProblemTests {
    private fun waitFor(ms: Long = 15_000, what: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what"); Thread.sleep(10) }
    }
    private fun setup(): Triple<FakeA0, QueueAndChatsTests.Link, QueueAndChatsTests.Rec> {
        val a0 = FakeA0(); return Triple(a0, QueueAndChatsTests.Link(a0), QueueAndChatsTests.Rec())
    }
    private fun conv(rec: QueueAndChatsTests.Rec) = A0Conversation(rec, pollMs = 20, kickAfterMs = 200)

    @Test fun agentDownIsReportedAndMessageWaitsVisiblyThenSends() {
        val (a0, link, rec) = setup()
        a0.down = true
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("hello world")
        waitFor(what = "problem") { rec.problems.any { it != null } }
        val msg = rec.problems.first { it != null }!!
        assertEquals(StickSetup.AGENT_DOWN_TEXT, msg)
        assertTrue(msg.contains("Start Agent Zero on the kiosk"), msg)
        assertFalse(msg.contains("starting", ignoreCase = true), "the stick's 'starting' isn't trusted: $msg")
        assertTrue(rec.interrupted.isEmpty(), "not a link drop")
        waitFor(what = "visible as waiting") { cv.queued().any { it.text == "hello world" && !it.onServer } }
        Thread.sleep(300)
        assertEquals(0, a0.messageAsync.get())
        a0.down = false
        waitFor(what = "reply") { rec.done.size == 1 }
        assertEquals("reply to: hello world", rec.done[0].second.answer)
        waitFor(what = "problem cleared") { rec.problems.last() == null }
        assertEquals(1, rec.problems.count { it != null }, "reported once, not every retry")
        cv.close()
    }

    @Test fun agentDownDuringReplyIsNotConnectionInterrupted() {
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("WAIT first"); waitFor { rec.busy }
        a0.down = true
        waitFor(what = "interrupted") { rec.interrupted.isNotEmpty() }
        assertTrue(rec.interrupted.all { it == A0Conversation.STICK_PREFIX + StickSetup.AGENT_DOWN_TEXT }, "${rec.interrupted}")
        Thread.sleep(700)
        a0.down = false
        waitFor(what = "reply") { rec.done.size == 1 }
        assertEquals("reply to: WAIT first", rec.done[0].second.answer)
        waitFor(what = "cleared") { rec.problems.last() == null }
        cv.close()
    }

    @Test fun modelNotConfiguredEndsAtOnceWithNoModel() {
        val (a0, link, rec) = setup()
        a0.modelConfigured = false
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("hello world")
        waitFor(ms = 3_000, what = "done") { rec.done.size == 1 }
        val t = rec.done[0].second
        assertTrue(t.noModel); assertNull(t.answer); assertFalse(t.stopped)
        assertEquals(listOf("hello world"), rec.starts)
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        assertTrue(rec.problems.isEmpty()); assertTrue(rec.interrupted.isEmpty())
        assertEquals(1, a0.messageAsync.get(), "not retried")
        a0.modelConfigured = true
        cv.submit("after loading a model")
        waitFor(what = "reply") { rec.done.size == 2 }
        assertEquals("reply to: after loading a model", rec.done[1].second.answer); assertFalse(rec.done[1].second.noModel)
        cv.close()
    }

    @Test fun sticksNoModelReplyIsFlagged() {
        val (_, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("NOMODEL hello world")
        waitFor(what = "done") { rec.done.size == 1 }
        assertTrue(rec.done[0].second.noModel)
        assertTrue(A0Chat.isNoModelReply(rec.done[0].second.answer))
        cv.close()
    }

    @Test fun errorCodesParsed() {
        assertEquals("agent_not_running", A0Chat.errorCode("""{"ok":false,"error":"agent_not_running","message":"x"}"""))
        assertEquals("no_model_loaded", A0Chat.errorCode("""{"error":{"message":"m","type":"no_model_loaded","code":503}}"""))
        assertNull(A0Chat.errorCode("forbidden path"))
        assertTrue(A0Chat.A0Error(503, "x", "agent_not_running").agentDown)
        assertTrue(A0Chat.A0Error(502, "x", "Agent is not running").agentDown)
        assertTrue(A0Chat.A0Error(502, "x", null).agentDown)          // bridge could not reach the gateway
        assertFalse(A0Chat.A0Error(500, "x", null).agentDown)
        assertFalse(A0Chat.A0Error(403, "x", null).agentDown)
    }

    @Test fun setupMessagesPerStickState() {
        fun st(state: String, msg: String) = A0Chat.AgentStatus(state, msg, false)
        assertEquals(StickSetup.SETUP_TEXT, StickSetup.forAgentDown(null))
        assertEquals(StickSetup.AGENT_DOWN_TEXT, StickSetup.forAgentDown(st("starting", "Agent Zero is starting...")))
        assertEquals(StickSetup.AGENT_DOWN_TEXT, StickSetup.forAgentDown(st("starting", "Starting Agent Zero container")))
        assertEquals(StickSetup.AGENT_DOWN_TEXT, StickSetup.forAgentDown(st("stopped", "Agent Zero is installed — open or start Agent")))
        assertEquals(StickSetup.AGENT_DOWN_TEXT, StickSetup.forAgentDown(st("running", "Agent")))
        assertEquals(StickSetup.installing("Pulling image 42%"), StickSetup.forAgentDown(st("installing", "Pulling image 42%")))
        assertEquals(StickSetup.blocked("Unlock vault to use Agent"), StickSetup.forAgentDown(st("locked", "Unlock vault to use Agent")))
        assertEquals(StickSetup.AGENT_DOWN_TEXT, StickSetup.forAgentDown(st("error", "")))
        for (t in listOf(StickSetup.SETUP_TEXT, StickSetup.AGENT_DOWN_TEXT, StickSetup.NO_MODEL_TEXT,
                StickSetup.blocked("Unlock vault to use Agent"))) assertFalse(t.contains("starting", ignoreCase = true), t)
        assertTrue(StickSetup.NO_MODEL_TEXT.contains("Load a model (like Qwen) on the kiosk"))
        assertTrue(StickSetup.SETUP_TEXT.contains("load a model (like Qwen) and start Agent Zero on the kiosk"))
    }

    @Test fun agentDownWithUnreadableStatusUsesGeneralSetupText() {
        val (a0, link, rec) = setup()
        a0.down = true; a0.downStatus = "not json"
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("hello world")
        waitFor(what = "problem") { rec.problems.any { it != null } }
        assertEquals(StickSetup.SETUP_TEXT, rec.problems.first { it != null })
        a0.down = false
        waitFor(what = "reply") { rec.done.size == 1 }
        cv.close()
    }

    @Test fun lockedStickSaysWhatToFix() {
        val (a0, link, rec) = setup()
        a0.down = true; a0.downStatus = """{"ok":true,"state":"locked","running":false,"message":"Unlock vault to use Agent"}"""
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("hello world")
        waitFor(what = "problem") { rec.problems.any { it != null } }
        assertEquals(StickSetup.blocked("Unlock vault to use Agent"), rec.problems.first { it != null })
        cv.close()
    }
}
