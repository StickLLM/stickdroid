package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.3.5 fix: a message goes straight to Agent Zero unless a reply is really running in this chat
 * (A0's latest poll) or messages are already waiting; busy must recover after every way a turn ends.
 */
class QueueTransitionsTests {
    private fun waitFor(ms: Long = 15_000, what: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what"); Thread.sleep(10) }
    }
    private fun conv(rec: QueueAndChatsTests.Rec) = A0Conversation(rec, pollMs = 20, kickAfterMs = 200)
    private fun setup(): Triple<FakeA0, QueueAndChatsTests.Link, QueueAndChatsTests.Rec> {
        val a0 = FakeA0(); return Triple(a0, QueueAndChatsTests.Link(a0), QueueAndChatsTests.Rec())
    }
    /** Every tray snapshot the UI saw. */
    private fun everShownInTray(rec: QueueAndChatsTests.Rec, text: String) = rec.queues.any { q -> q.any { it.text == text } }

    @Test fun idleSendGoesStraightToA0AndNeverShowsInTheQueue() {
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        assertTrue(cv.wouldSendDirect())
        cv.submit("hello")
        waitFor(what = "reply") { rec.done.size == 1 }
        cv.submit("second, after the reply")
        waitFor(what = "reply 2") { rec.done.size == 2 }
        assertFalse(everShownInTray(rec, "hello"), "an idle send is not a queued message")
        assertFalse(everShownInTray(rec, "second, after the reply"))
        assertEquals(0, a0.queueAdds.get()); assertEquals(2, a0.messageAsync.get())
        assertEquals(listOf("reply to: hello", "reply to: second, after the reply"), rec.done.map { it.second.answer })
        waitFor(what = "idle") { !rec.busy && !cv.busy && cv.wouldSendDirect() }
        cv.close()
    }

    @Test fun sendDuringReplyQueuesThenNextSendAfterItIsDirect() {
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("WAIT long one"); waitFor { rec.busy }
        assertFalse(cv.wouldSendDirect())
        cv.submit("Q during reply")
        waitFor(what = "tray") { everShownInTray(rec, "Q during reply") }
        waitFor(what = "2 replies") { rec.done.size == 2 }
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        assertEquals(1, a0.queueAdds.get())
        cv.submit("after everything")
        waitFor(what = "3 replies") { rec.done.size == 3 }
        assertFalse(everShownInTray(rec, "after everything"))
        assertEquals(1, a0.queueAdds.get(), "the send after the reply went direct")
        assertEquals(2, a0.messageAsync.get())
        assertEquals(0, a0.interventions.get())
        cv.close()
    }

    @Test fun busyRecoversAfterStopAndNextSendIsDirect() {
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("SLOW task"); waitFor { rec.busy }
        cv.stop(keepQueue = true)
        waitFor(what = "stopped") { rec.done.size == 1 }
        assertTrue(rec.done[0].second.stopped)
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        cv.submit("after stop")
        waitFor(what = "reply") { rec.done.size == 2 }
        assertEquals(0, a0.queueAdds.get()); assertFalse(everShownInTray(rec, "after stop"))
        cv.close()
    }

    @Test fun stopSurvivesALinkResume() {
        // Stop pressed, then the link resumes with a new A0Chat: the stop must still end the turn.
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("SLOW task"); waitFor { rec.busy }
        link.down = true
        cv.stop(keepQueue = true)               // cancel() reaches nobody: link is down
        waitFor(what = "interrupted") { rec.interrupted.isNotEmpty() }
        a0.ctxs.values.first().stop = true      // the stop request lands on A0 (e.g. retried, or from the web UI)
        Thread.sleep(300)
        link.down = false
        cv.setChat(link.chat())
        waitFor(what = "turn ended") { rec.done.size == 1 }
        assertTrue(rec.done[0].second.stopped)
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        cv.close()
    }

    @Test fun turnWithoutAnswerEndsAndBusyClears() {
        // A0 ends the chain with an error and no response item: 0.3.4 waited 10 min, then forever.
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("ERR this fails")
        waitFor(ms = 5_000, what = "turn ended") { rec.done.size == 1 }
        assertNull(rec.done[0].second.answer); assertFalse(rec.done[0].second.stopped)
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        cv.submit("try again")
        waitFor(what = "reply") { rec.done.size == 2 }
        assertEquals("reply to: try again", rec.done[1].second.answer)
        assertEquals(0, a0.queueAdds.get())
        cv.close()
    }

    @Test fun busyRecoversAfterLinkDropWhenReplyFinishedMeanwhile() {
        val (a0, link, rec) = setup()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("WAIT first"); waitFor { rec.busy }
        link.down = true
        waitFor(what = "interrupted") { rec.interrupted.isNotEmpty() }
        Thread.sleep(800)                        // A0 finishes while the phone is away
        link.down = false
        cv.setChat(link.chat())
        waitFor(what = "reply") { rec.done.size == 1 }
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        cv.submit("after resume")
        waitFor(what = "reply 2") { rec.done.size == 2 }
        assertEquals(0, a0.queueAdds.get()); assertFalse(everShownInTray(rec, "after resume"))
        cv.close()
    }

    @Test fun chatSwitchClearsBusyAndNewChatSendsDirect() {
        val (a0, link, rec) = setup()
        val cv = conv(rec); val chat = link.chat(); cv.setChat(chat)
        cv.submit("SLOW in first chat"); waitFor { rec.busy }
        val fresh = chat.createChat()
        cv.switchTo(fresh)
        waitFor(what = "not busy") { !rec.busy && !cv.busy }
        waitFor(what = "direct ok") { cv.wouldSendDirect() }
        cv.submit("hello new chat")
        waitFor(what = "reply") { rec.done.any { it.first == "hello new chat" } }
        assertEquals(0, a0.queueAdds.get()); assertFalse(everShownInTray(rec, "hello new chat"))
        assertEquals(0, a0.interventions.get())
        cv.close()
    }

    @Test fun replyStartedElsewhereStillQueuesInsteadOfInterrupting() {
        // The phone thinks it's idle but A0 is running a reply typed in the web UI: queue, don't intervene.
        val (a0, link, rec) = setup()
        val c = a0.newCtx()
        val cv = conv(rec); cv.setChat(link.chat()); cv.switchTo(c.id)
        waitFor(what = "settled") { cv.wouldSendDirect() }
        a0.startFromWeb(c, "SLOW from the web UI")
        cv.submit("from the phone")
        waitFor(what = "queued on A0") { a0.queueAdds.get() == 1 }
        assertTrue(everShownInTray(rec, "from the phone"), "shown as queued once A0 said it was running")
        // A0 sends it when the web reply ends; the phone shows the right reply under it.
        waitFor(ms = 20_000, what = "phone reply") { rec.done.any { it.first == "from the phone" } }
        assertEquals("reply to: from the phone", rec.done.first { it.first == "from the phone" }.second.answer)
        assertEquals(0, a0.interventions.get())
        waitFor(what = "idle") { !rec.busy && cv.wouldSendDirect() }
        cv.close()
    }

    @Test fun scrollFollowOnlyWhenAtBottom() {
        val f = ScrollFollow()
        assertTrue(f.onLines(0, sentByMe = false))           // first layout: follow
        assertTrue(f.onLines(1, sentByMe = false))           // at bottom: follow new reply
        f.onScrolled(atBottom = false)                       // user scrolls up to read
        assertTrue(f.showJump)
        assertFalse(f.onLines(2, sentByMe = false))          // don't yank
        assertFalse(f.onLines(3, sentByMe = false))
        assertEquals(2, f.unseen)
        assertFalse(f.onLines(3, sentByMe = false))          // thinking bubble updates don't count
        assertEquals(2, f.unseen)
        f.onJump()
        assertFalse(f.showJump); assertEquals(0, f.unseen)
        assertTrue(f.onLines(4, sentByMe = false))
        f.onScrolled(atBottom = false)
        assertTrue(f.onLines(4, sentByMe = true))            // your own message brings you down
        assertFalse(f.showJump)
        f.onScrolled(atBottom = false); f.onLines(5, false); assertEquals(1, f.unseen)
        f.onScrolled(atBottom = true)                        // scrolling back down clears the dot
        assertEquals(0, f.unseen); assertFalse(f.showJump)
    }
}
