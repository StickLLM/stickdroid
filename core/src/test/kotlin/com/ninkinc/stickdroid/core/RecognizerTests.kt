package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineSelectorTests {
    private val both = EngineAvailability(builtIn = true, system = true)
    private val builtOnly = EngineAvailability(builtIn = true, system = false)
    private val sysOnly = EngineAvailability(builtIn = false, system = true)
    private val none = EngineAvailability(builtIn = false, system = false)

    @Test fun defaultBuiltInUsesBuiltIn() {
        val c = EngineSelector.select(RecognizerPref.BuiltIn, both)
        assertEquals(RecognizerEngine.BuiltIn, c.engine); assertFalse(c.fellBack); assertNull(c.note)
    }
    @Test fun grapheneOsNoSystemRecognizerStillWorks() {
        val c = EngineSelector.select(RecognizerPref.BuiltIn, builtOnly)
        assertEquals(RecognizerEngine.BuiltIn, c.engine); assertFalse(c.fellBack)
    }
    @Test fun systemPickedAndPresent() {
        val c = EngineSelector.select(RecognizerPref.System, both)
        assertEquals(RecognizerEngine.System, c.engine); assertFalse(c.fellBack)
    }
    @Test fun systemPickedButMissingFallsBackToBuiltIn() {
        val c = EngineSelector.select(RecognizerPref.System, builtOnly)
        assertEquals(RecognizerEngine.BuiltIn, c.engine); assertTrue(c.fellBack)
        assertTrue(c.note!!.contains("built-in"))
    }
    @Test fun builtInBrokenFallsBackToSystem() {
        val c = EngineSelector.select(RecognizerPref.BuiltIn, sysOnly)
        assertEquals(RecognizerEngine.System, c.engine); assertTrue(c.fellBack)
    }
    @Test fun nothingAvailable() {
        for (p in RecognizerPref.values()) {
            val c = EngineSelector.select(p, none)
            assertNull(c.engine); assertTrue(c.note!!.contains("isn't available"))
        }
        assertFalse(EngineSelector.anyAvailable(none)); assertTrue(EngineSelector.anyAvailable(builtOnly))
    }
}

class EndpointerTests {
    private val quiet = -70f
    private val loud = -25f
    private val cfg = EndpointConfig()

    /** Feed [ms] of audio in 100 ms frames; returns the last decision. */
    private fun Endpointer.run(ms: Int, db: Float, text: String): EndpointDecision {
        var d = EndpointDecision.Continue
        repeat(ms / 100) { d = feed(100, db, text) }
        return d
    }

    @Test fun ignoresLeadIn() {
        val e = Endpointer(cfg)
        assertFalse(e.acceptingAudio())
        e.run(300, loud, "")
        assertTrue(e.acceptingAudio())
        assertEquals(0, e.speechMs) // TTS tail / click in the lead-in is not speech
    }

    @Test fun endsAfterTrailingSilence() {
        val e = Endpointer(cfg)
        e.run(500, quiet, "")
        assertEquals(EndpointDecision.Continue, e.run(1000, loud, "hello there"))
        assertEquals(EndpointDecision.Continue, e.run(1000, quiet, "hello there"))
        assertEquals(EndpointDecision.EndOfSpeech, e.run(200, quiet, "hello there"))
    }

    @Test fun shortPauseDoesNotCutTheSentence() {
        val e = Endpointer(cfg)
        e.run(500, quiet, "")
        e.run(1000, loud, "what is")
        assertEquals(EndpointDecision.Continue, e.run(700, quiet, "what is"))
        assertEquals(EndpointDecision.Continue, e.run(1000, loud, "what is the weather"))
        assertEquals(EndpointDecision.EndOfSpeech, e.run(1200, quiet, "what is the weather"))
    }

    @Test fun noSpeechTimesOut() {
        val e = Endpointer(cfg)
        assertEquals(EndpointDecision.Continue, e.run(7000, quiet, ""))
        assertEquals(EndpointDecision.NoSpeech, e.run(400, quiet, ""))
    }

    @Test fun loudRoomEndsWhenTextStalls() {
        // Constant fan noise above the VAD threshold: text stall still ends it.
        val e = Endpointer(cfg)
        e.run(400, quiet, "")
        e.run(1000, loud, "turn on the lights")
        assertEquals(EndpointDecision.Continue, e.run(1000, loud, "turn on the lights"))
        assertEquals(EndpointDecision.EndOfSpeech, e.run(1000, loud, "turn on the lights"))
    }

    @Test fun voicedNoiseWithoutWordsEventuallyNoSpeech() {
        val e = Endpointer(cfg)
        e.run(400, quiet, "")
        var d = EndpointDecision.Continue
        var t = 0
        while (d == EndpointDecision.Continue && t < 20000) { d = e.feed(100, if (t % 400 < 200) loud else quiet, ""); t += 100 }
        assertEquals(EndpointDecision.NoSpeech, d)
        assertTrue(t <= cfg.startTimeoutMs + 2 * cfg.endSilenceMs + 500)
    }

    @Test fun maxLengthCaps() {
        val e = Endpointer(cfg.copy(maxUtteranceMs = 5000))
        e.run(300, quiet, "")
        var d = EndpointDecision.Continue
        var i = 0
        while (d == EndpointDecision.Continue) { d = e.feed(100, loud, "word ${i++}") }
        assertEquals(EndpointDecision.MaxLength, d)
        assertEquals(5300, e.elapsedMs)
    }

    @Test fun decisionIsSticky() {
        val e = Endpointer(cfg)
        e.run(7600, quiet, "")
        assertEquals(EndpointDecision.NoSpeech, e.decided)
        assertEquals(EndpointDecision.NoSpeech, e.feed(100, loud, "late words"))
    }

    @Test fun noiseFloorAdapts() {
        val e = Endpointer(cfg)
        e.run(300, quiet, "")
        e.run(2000, -45f, "")
        assertFalse(e.isVoice(-44f)) // steady -45 dB hum became the floor
        assertTrue(e.isVoice(-20f))
    }
}

class PcmAndVoskTests {
    @Test fun levels() {
        assertEquals(-90f, Pcm.levelDb(ShortArray(160)))
        val full = ShortArray(160) { if (it % 2 == 0) 32767 else -32767 }
        assertTrue(Pcm.levelDb(full) > -0.1f)
        val tenth = ShortArray(160) { if (it % 2 == 0) 3277 else -3277 }
        assertEquals(-20f, Pcm.levelDb(tenth), 0.1f)
        assertEquals(0f, Pcm.meter(-80f)); assertEquals(1f, Pcm.meter(0f)); assertEquals(0.5f, Pcm.meter(-35f), 0.001f)
    }

    @Test fun parsesVosk() {
        assertEquals("hello wor", VoskJson.partial("{\n  \"partial\" : \"hello wor\"\n}"))
        assertEquals("hello world", VoskJson.text("{\"text\": \" hello world \"}"))
        assertEquals("", VoskJson.text("{\"text\": \"\"}"))
        assertEquals("", VoskJson.text("not json"))
        assertEquals("", VoskJson.partial(null))
        assertEquals("", VoskJson.text("{\"result\":[],\"text\":\"\"}"))
    }

    @Test fun transcriptBufferJoinsSegments() {
        val b = TranscriptBuffer()
        b.onPartial("what is")
        assertEquals("what is", b.current())
        b.onSegment("what is the")
        b.onPartial("weather")
        assertEquals("what is the weather", b.current())
        assertEquals("what is the weather today", b.finish("weather today"))
        assertEquals("", TranscriptBuffer().finish(""))
    }
}

/** The built-in mic must never be open while TTS is speaking (no self-transcription). */
class SpeechTtsExclusionTests {
    private val ready = SpeechState(recognizerAvailable = true, micPermission = true)
    private val ctx = SpeechContext(connected = true, busy = false, foreground = true)

    @Test fun micWhileSpeakingStopsTtsBeforeListening() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "a")
        val out = SpeechMachine.reduce(speaking, SpeechEvent.MicTapped, SpeechPrefs(), ctx)
        val iStop = out.effects.indexOf(SpeechEffect.StopSpeaking)
        val iListen = out.effects.indexOf(SpeechEffect.StartListening)
        assertTrue(iStop in 0 until iListen)
    }

    @Test fun replyWhileListeningIsNotSpokenOverTheMic() {
        val listening = ready.copy(phase = SpeechPhase.Listening, lastSpoken = true, partial = "and also")
        val out = SpeechMachine.reduce(listening, SpeechEvent.ReplyReady("r", "hi"), SpeechPrefs(readAloud = ReadAloudMode.Always), ctx)
        assertEquals(SpeechPhase.Listening, out.state.phase)
        assertTrue(out.effects.none { it is SpeechEffect.Speak })
        assertEquals("and also", out.state.partial)
    }

    @Test fun partialsIgnoredWhileSpeaking() {
        val speaking = ready.copy(phase = SpeechPhase.Speaking, speakingLineId = "a")
        val out = SpeechMachine.reduce(speaking, SpeechEvent.Partial("echo"), SpeechPrefs(), ctx)
        assertTrue(out.effects.isEmpty()); assertEquals("", out.state.partial)
        val fin = SpeechMachine.reduce(speaking, SpeechEvent.FinalResult("echo"), SpeechPrefs(), ctx)
        assertTrue(fin.effects.none { it is SpeechEffect.Send })
    }

    @Test fun handsFreeListensOnlyAfterTtsDone() {
        val prefs = SpeechPrefs(handsFree = true)
        val r = SpeechMachine.reduce(ready.copy(lastSpoken = true), SpeechEvent.ReplyReady("r", "Sure."), prefs, ctx)
        assertEquals(SpeechPhase.Speaking, r.state.phase)
        assertFalse(r.effects.contains(SpeechEffect.StartListening))
        val done = SpeechMachine.reduce(r.state, SpeechEvent.TtsFinished, prefs, ctx)
        assertEquals(SpeechPhase.Listening, done.state.phase)
        assertTrue(done.effects.contains(SpeechEffect.StartListening))
    }
}

class UtteranceLoopTests {
    /** Fake ASR: "hears" a word for every loud frame, finalizes on [finalizeAt]. */
    private class FakeAsr(val words: List<String>, val finalizeAt: Set<Int>) : StreamingAsr {
        var loud = 0; var fed = 0; private var seg = mutableListOf<String>(); private var lastResult = ""
        override fun accept(pcm: ShortArray, n: Int): Boolean {
            fed++
            if (Pcm.levelDb(pcm, n) > -40f && loud < words.size) seg += words[loud++]
            if (fed in finalizeAt && seg.isNotEmpty()) { lastResult = seg.joinToString(" "); seg = mutableListOf(); return true }
            return false
        }
        override fun partialJson() = "{\"partial\":\"${seg.joinToString(" ")}\"}"
        override fun resultJson() = "{\"text\":\"$lastResult\"}"
        override fun finalJson() = "{\"text\":\"${seg.joinToString(" ")}\"}"
    }

    private fun frame(loud: Boolean) = ShortArray(1600) { if (!loud) 0 else if (it % 2 == 0) 3000 else -3000 }

    @Test fun leadInNotFedToAsrAndSegmentsJoin() {
        val asr = FakeAsr(listOf("open", "the", "door", "please"), finalizeAt = setOf(5))
        val loop = UtteranceLoop(asr)
        // 300 ms lead-in that is loud (TTS tail): must not reach the ASR.
        repeat(3) { loop.onFrame(frame(true), 1600) }
        assertEquals(0, asr.fed)
        var last: LoopStep? = null
        val pattern = listOf(true, true, false, true, true) + List(15) { false }
        for (l in pattern) { last = loop.onFrame(frame(l), 1600); if (last.decision != EndpointDecision.Continue) break }
        assertEquals(EndpointDecision.EndOfSpeech, last!!.decision)
        assertEquals("open the door please", loop.finish())
    }
}
