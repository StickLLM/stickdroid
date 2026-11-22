package com.ninkinc.stickdroid.core

/** Minimal streaming ASR surface (matches org.vosk.Recognizer). */
interface StreamingAsr {
    /** Feed PCM16 mono; true when the engine finalized a segment. */
    fun accept(pcm: ShortArray, n: Int): Boolean
    fun partialJson(): String
    fun resultJson(): String
    fun finalJson(): String
}

data class LoopStep(
    val decision: EndpointDecision,
    /** Composer text (segments + partial). */
    val text: String,
    val levelDb: Float,
    val textChanged: Boolean,
)

/**
 * One utterance: audio frames -> ASR -> endpointer. Shared by the Android
 * built-in recognizer and the box JVM transcription harness, so the box test
 * runs the same endpointing as the phone.
 */
class UtteranceLoop(
    private val asr: StreamingAsr,
    private val sampleRate: Int = 16000,
    cfg: EndpointConfig = EndpointConfig(),
) {
    val endpointer = Endpointer(cfg)
    private val buffer = TranscriptBuffer()
    private var lastText = ""

    fun onFrame(pcm: ShortArray, n: Int): LoopStep {
        val frameMs = (n * 1000L / sampleRate).toInt().coerceAtLeast(1)
        val db = Pcm.levelDb(pcm, n)
        if (!endpointer.acceptingAudio()) {
            // Lead-in (TTS tail, button click): count time, don't recognize.
            val d = endpointer.feed(frameMs, -90f, "")
            return LoopStep(d, lastText, db, false)
        }
        if (asr.accept(pcm, n)) buffer.onSegment(VoskJson.text(asr.resultJson()))
        else buffer.onPartial(VoskJson.partial(asr.partialJson()))
        val text = buffer.current()
        val changed = text != lastText
        lastText = text
        val d = endpointer.feed(frameMs, db, text)
        return LoopStep(d, text, db, changed)
    }

    /** Final transcript (flushes the engine). */
    fun finish(): String = buffer.finish(VoskJson.text(asr.finalJson()))
}
