package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechMachineTests {
    private val ready = SpeechState(recognizerAvailable = true, micPermission = true)
    private val connected = SpeechContext(connected = true, busy = false, foreground = true)

    private fun step(
        state: SpeechState,
        event: SpeechEvent,
        prefs: SpeechPrefs = SpeechPrefs(),
        ctx: SpeechContext = connected,
    ) = SpeechMachine.reduce(state, event, prefs, ctx)

    @Test fun hidesWhenRecognizerMissing() {
        val s = SpeechState(recognizerAvailable = false, micPermission = true)
        val out = step(s, SpeechEvent.MicTapped)
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.state.note!!.contains("isn't available"))
        assertTrue(out.effects.isEmpty())
    }

    @Test fun requestsPermissionBeforeListen() {
        val s = SpeechState(recognizerAvailable = true, micPermission = false)
        val out = step(s, SpeechEvent.MicTapped)
        assertEquals(SpeechPhase.RequestingMic, out.state.phase)
        assertEquals(listOf(SpeechEffect.RequestMicPermission), out.effects)
        val granted = SpeechMachine.reduce(out.state, SpeechEvent.PermissionGranted)
        assertEquals(SpeechPhase.Listening, granted.state.phase)
        assertTrue(granted.state.micPermission)
        assertEquals(listOf(SpeechEffect.StartListening), granted.effects)
    }

    @Test fun permissionDeniedStaysIdleWithPrompt() {
        val s = SpeechState(phase = SpeechPhase.RequestingMic, recognizerAvailable = true, micPermission = false)
        val out = step(s, SpeechEvent.PermissionDenied)
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertFalse(out.state.micPermission)
        assertTrue(out.state.note!!.contains("permission"))
    }

    @Test fun grantedMicStartsListening() {
        val out = step(ready, SpeechEvent.MicTapped)
        assertEquals(SpeechPhase.Listening, out.state.phase)
        assertEquals(listOf(SpeechEffect.StartListening), out.effects)
    }

    @Test fun partialsAndRmsUpdateDraft() {
        val listening = step(ready, SpeechEvent.MicTapped).state
        val p = step(listening, SpeechEvent.Partial("hello there"))
        assertEquals("hello there", p.state.partial)
        assertEquals(listOf(SpeechEffect.SetDraft("hello there")), p.effects)
        val r = step(p.state, SpeechEvent.Rms(0.6f))
        assertEquals(0.6f, r.state.rms, 0.001f)
        val clamped = step(p.state, SpeechEvent.Rms(2f))
        assertEquals(1f, clamped.state.rms, 0.001f)
    }

    @Test fun finalAutoSendsByDefault() {
        val listening = step(ready, SpeechEvent.MicTapped).state
        val out = step(listening, SpeechEvent.FinalResult("  hello stick  "))
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.state.lastSpoken)
        assertEquals("", out.state.partial)
        assertTrue(out.effects.contains(SpeechEffect.Send("hello stick")))
        assertTrue(out.effects.contains(SpeechEffect.SetDraft("")))
    }

    @Test fun finalWithoutAutoSendFillsDraft() {
        val listening = step(ready, SpeechEvent.MicTapped).state
        val out = step(listening, SpeechEvent.FinalResult("hold this"), SpeechPrefs(autoSend = false))
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.state.lastSpoken)
        assertTrue(out.effects.contains(SpeechEffect.SetDraft("hold this")))
        assertFalse(out.effects.any { it is SpeechEffect.Send })
    }

    @Test fun tapAgainCancelsListening() {
        val listening = step(ready, SpeechEvent.MicTapped).state
        val withPartial = step(listening, SpeechEvent.Partial("halfway")).state
        val out = step(withPartial, SpeechEvent.MicTapped)
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.effects.contains(SpeechEffect.StopListening))
        assertTrue(out.effects.contains(SpeechEffect.SetDraft("halfway")))
        assertFalse(out.effects.any { it is SpeechEffect.Send })
    }

    @Test fun micWhileSpeakingStopsTtsAndListens() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "r1", lastSpoken = true)
        val out = step(speaking, SpeechEvent.MicTapped)
        assertEquals(SpeechPhase.Listening, out.state.phase)
        assertNull(out.state.speakingLineId)
        assertTrue(out.effects.contains(SpeechEffect.StopSpeaking))
        assertTrue(out.effects.contains(SpeechEffect.StartListening))
    }

    @Test fun typedSendStopsSpeechAndClearsSpokenFlag() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "r1", lastSpoken = true)
        val out = step(speaking, SpeechEvent.UserTypedSend)
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertFalse(out.state.lastSpoken)
        assertTrue(out.effects.contains(SpeechEffect.StopSpeaking))
    }

    @Test fun afterSpeechReadsOnlySpokenReplies() {
        val spoken = ready.copy(lastSpoken = true)
        val spokenOut = step(spoken, SpeechEvent.ReplyReady("a1", "Hello **world**"))
        assertEquals(SpeechPhase.Speaking, spokenOut.state.phase)
        assertEquals(listOf(SpeechEffect.Speak("a1", "Hello world")), spokenOut.effects)

        val typed = ready.copy(lastSpoken = false)
        val typedOut = step(typed, SpeechEvent.ReplyReady("a2", "Hello **world**"))
        assertEquals(SpeechPhase.Idle, typedOut.state.phase)
        assertTrue(typedOut.effects.isEmpty())
    }

    @Test fun alwaysReadsTypedRepliesToo() {
        val typed = ready.copy(lastSpoken = false)
        val out = step(typed, SpeechEvent.ReplyReady("a3", "Hi"), SpeechPrefs(readAloud = ReadAloudMode.Always))
        assertEquals(listOf(SpeechEffect.Speak("a3", "Hi")), out.effects)
    }

    @Test fun neverDoesNotSpeak() {
        val spoken = ready.copy(lastSpoken = true)
        val out = step(spoken, SpeechEvent.ReplyReady("a4", "Hi"), SpeechPrefs(readAloud = ReadAloudMode.Never))
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertFalse(out.effects.any { it is SpeechEffect.Speak })
    }

    @Test fun handsFreeListensAfterTts() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "r1", lastSpoken = true)
        val prefs = SpeechPrefs(handsFree = true)
        val out = step(speaking, SpeechEvent.TtsFinished, prefs)
        assertEquals(SpeechPhase.Listening, out.state.phase)
        assertTrue(out.effects.contains(SpeechEffect.StartListening))
    }

    @Test fun handsFreeListensAfterSkippedTts() {
        val state = ready.copy(lastSpoken = false)
        val prefs = SpeechPrefs(handsFree = true, readAloud = ReadAloudMode.AfterSpeech)
        val out = step(state, SpeechEvent.ReplyReady("a5", "Quiet reply"), prefs)
        assertEquals(SpeechPhase.Listening, out.state.phase)
        assertTrue(out.effects.contains(SpeechEffect.StartListening))
    }

    @Test fun silenceTimeoutStopsHandsFree() {
        val listening = ready.copy(phase = SpeechPhase.Listening)
        val prefs = SpeechPrefs(handsFree = true)
        val out = step(listening, SpeechEvent.ListenFailed(ListenFail.Silence), prefs)
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.state.note!!.contains("Hands-free paused"))
        assertFalse(out.effects.contains(SpeechEffect.StartListening))
        assertTrue(out.effects.contains(SpeechEffect.StopListening))
    }

    @Test fun leftForegroundStopsListenAndTts() {
        val listening = ready.copy(phase = SpeechPhase.Listening, partial = "hi")
        val out = step(listening, SpeechEvent.LeftForeground, SpeechPrefs(handsFree = true))
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.effects.contains(SpeechEffect.StopListening))
        assertTrue(out.state.note!!.contains("paused"))

        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "r1")
        val out2 = step(speaking, SpeechEvent.LeftForeground, SpeechPrefs(handsFree = true))
        assertEquals(SpeechPhase.Idle, out2.state.phase)
        assertTrue(out2.effects.contains(SpeechEffect.StopSpeaking))
        assertNull(out2.state.speakingLineId)
    }

    @Test fun permissionPromptIgnoresActivityPause() {
        val s = ready.copy(phase = SpeechPhase.RequestingMic, micPermission = false)
        val out = step(s, SpeechEvent.LeftForeground)
        assertEquals(SpeechPhase.RequestingMic, out.state.phase)
        assertTrue(out.effects.isEmpty())
    }

    @Test fun blankFinalDoesNotSend() {
        val listening = ready.copy(phase = SpeechPhase.Listening)
        val out = step(listening, SpeechEvent.FinalResult("   "))
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertFalse(out.effects.any { it is SpeechEffect.Send })
        assertTrue(out.state.note!!.contains("Didn't catch"))
    }

    @Test fun ttsFailureIsGracefulAndHandsFreeContinues() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "r1")
        val out = step(speaking, SpeechEvent.TtsFailed, SpeechPrefs(handsFree = true))
        assertEquals(SpeechPhase.Listening, out.state.phase)
        assertTrue(out.state.note!!.contains("Couldn't read"))
        assertTrue(out.effects.contains(SpeechEffect.StartListening))
        assertNull(out.state.speakingLineId)
    }

    @Test fun disconnectClearsSpeech() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "r1", lastSpoken = true)
        val out = step(speaking, SpeechEvent.Disconnect)
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertFalse(out.state.lastSpoken)
        assertTrue(out.effects.contains(SpeechEffect.StopSpeaking))
    }

    @Test fun micIgnoredWhileBusyThinking() {
        val out = step(ready, SpeechEvent.MicTapped, ctx = SpeechContext(connected = true, busy = true, foreground = true))
        assertEquals(SpeechPhase.Idle, out.state.phase)
        assertTrue(out.effects.isEmpty())
    }

    @Test fun urlsAreNotSpokenFromReply() {
        val spoken = ready.copy(lastSpoken = true)
        val out = step(spoken, SpeechEvent.ReplyReady("a6", "See https://example.com/secret"))
        val speak = out.effects.filterIsInstance<SpeechEffect.Speak>().single()
        assertEquals("See link", speak.text)
        assertFalse(speak.text.contains("example.com"))
    }
}
