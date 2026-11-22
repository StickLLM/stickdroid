package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Which speech-to-text engine the user prefers (Settings). */
enum class RecognizerPref { BuiltIn, System }

/** The engine actually used for a listen. */
enum class RecognizerEngine { BuiltIn, System }

data class EngineAvailability(
    /** Bundled on-device model (Vosk) is present in the APK and the native lib loads. */
    val builtIn: Boolean,
    /** Android has a RecognitionService (Google, Transcribro, ...). */
    val system: Boolean,
)

data class EngineChoice(
    val engine: RecognizerEngine?,
    /** True when the preferred engine was missing and we used the other one. */
    val fellBack: Boolean = false,
    val note: String? = null,
)

/**
 * Engine selection. Built-in is the default and needs nothing else on the
 * phone (GrapheneOS has no system recognizer). System is opt-in; if it is
 * picked but not installed we quietly use built-in and say so.
 */
object EngineSelector {
    fun select(pref: RecognizerPref, avail: EngineAvailability): EngineChoice = when (pref) {
        RecognizerPref.BuiltIn -> when {
            avail.builtIn -> EngineChoice(RecognizerEngine.BuiltIn)
            avail.system -> EngineChoice(
                RecognizerEngine.System, fellBack = true,
                note = "Built-in speech model couldn't load; using the phone's recognizer.",
            )
            else -> EngineChoice(null, note = "Speech recognition isn't available on this device.")
        }
        RecognizerPref.System -> when {
            avail.system -> EngineChoice(RecognizerEngine.System)
            avail.builtIn -> EngineChoice(
                RecognizerEngine.BuiltIn, fellBack = true,
                note = "No system recognizer on this phone; using the built-in one.",
            )
            else -> EngineChoice(null, note = "Speech recognition isn't available on this device.")
        }
    }

    fun anyAvailable(avail: EngineAvailability) = avail.builtIn || avail.system
}

/** Tunables for [Endpointer]. Times are in milliseconds of audio. */
data class EndpointConfig(
    /** Audio discarded right after the mic opens (TTS tail, button click). */
    val ignoreLeadMs: Int = 300,
    /** Give up if no speech starts within this long. */
    val startTimeoutMs: Int = 7000,
    /** End of speech: this much trailing silence after words were heard. */
    val endSilenceMs: Int = 1100,
    /** End anyway if the transcript stops changing this long (noisy room). */
    val textStallMs: Int = 2200,
    /** Hard cap on one utterance. */
    val maxUtteranceMs: Int = 30000,
    /** Frame counts as voice when this many dB above the adaptive noise floor. */
    val vadMarginDb: Float = 10f,
    /** Absolute floor for voice, in dBFS (quiet rooms). */
    val vadMinDb: Float = -50f,
    /** Voice needs at least this much (summed) to count as "speech started". */
    val minSpeechMs: Int = 200,
)

enum class EndpointDecision { Continue, EndOfSpeech, NoSpeech, MaxLength }

/**
 * Voice-activity / silence endpointer for the built-in recognizer.
 *
 * Fed one audio frame at a time with its level (dBFS) and the recognizer's
 * current text (partial + finalized segments). Pure, so it is unit-tested and
 * shared with the box JVM transcription harness.
 *
 * Speech "activity" is either an energy frame above the adaptive noise floor
 * or the transcript growing. The utterance ends after [EndpointConfig.endSilenceMs]
 * of no activity once words were heard, or when the transcript has stalled for
 * [EndpointConfig.textStallMs] even if the room stays loud.
 */
class Endpointer(private val cfg: EndpointConfig = EndpointConfig()) {
    var elapsedMs: Int = 0
        private set
    var speechMs: Int = 0
        private set
    var noiseFloorDb: Float = -60f
        private set
    private var lastActivityMs = 0
    private var lastTextChangeMs = 0
    private var lastText = ""
    private var floorInit = false
    var decided: EndpointDecision = EndpointDecision.Continue
        private set

    /** True once the lead-in has passed (audio before this should be ignored). */
    fun acceptingAudio(): Boolean = elapsedMs >= cfg.ignoreLeadMs

    val heardWords: Boolean get() = lastText.isNotBlank()

    fun isVoice(levelDb: Float): Boolean =
        levelDb >= cfg.vadMinDb && levelDb >= noiseFloorDb + cfg.vadMarginDb

    /**
     * @param frameMs duration of this frame.
     * @param levelDb frame RMS in dBFS (see [Pcm.levelDb]).
     * @param text recognizer text so far (finalized segments + current partial).
     */
    fun feed(frameMs: Int, levelDb: Float, text: String): EndpointDecision {
        if (decided != EndpointDecision.Continue) return decided
        elapsedMs += frameMs
        if (elapsedMs <= cfg.ignoreLeadMs) {
            lastActivityMs = elapsedMs
            lastTextChangeMs = elapsedMs
            return EndpointDecision.Continue
        }
        val voice = isVoice(levelDb)
        // Adaptive noise floor: fast down, slow up, only from non-voice frames.
        if (!floorInit) {
            noiseFloorDb = levelDb.coerceAtLeast(-90f); floorInit = true
        } else if (levelDb < noiseFloorDb) {
            noiseFloorDb = 0.7f * noiseFloorDb + 0.3f * levelDb
        } else if (!voice) {
            noiseFloorDb = 0.97f * noiseFloorDb + 0.03f * levelDb
        }
        val t = text.trim()
        if (t != lastText) {
            if (t.isNotEmpty()) {
                lastActivityMs = elapsedMs
                lastTextChangeMs = elapsedMs
            }
            lastText = t
        }
        if (voice) {
            speechMs += frameMs
            lastActivityMs = elapsedMs
        }
        decided = decide()
        return decided
    }

    private fun decide(): EndpointDecision {
        val listened = elapsedMs - cfg.ignoreLeadMs
        if (listened >= cfg.maxUtteranceMs) {
            return if (heardWords) EndpointDecision.MaxLength else EndpointDecision.NoSpeech
        }
        if (heardWords) {
            if (elapsedMs - lastActivityMs >= cfg.endSilenceMs) return EndpointDecision.EndOfSpeech
            if (elapsedMs - lastTextChangeMs >= cfg.textStallMs) return EndpointDecision.EndOfSpeech
            return EndpointDecision.Continue
        }
        // No words yet. Time out if nothing (words) started; voiced noise alone
        // gets a little longer but not forever.
        val limit = if (speechMs >= cfg.minSpeechMs) cfg.startTimeoutMs + cfg.endSilenceMs * 2 else cfg.startTimeoutMs
        return if (listened >= limit) EndpointDecision.NoSpeech else EndpointDecision.Continue
    }
}

/** 16-bit PCM helpers. */
object Pcm {
    /** RMS level of [n] samples in dBFS (-90 for digital silence). */
    fun levelDb(buf: ShortArray, n: Int = buf.size): Float {
        if (n <= 0) return -90f
        var sum = 0.0
        for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
        val rms = sqrt(sum / n) / 32768.0
        if (rms <= 1e-9) return -90f
        return max(-90.0, 20.0 * log10(rms)).toFloat()
    }

    /** Map dBFS to a 0..1 meter value (-60 dB -> 0, -10 dB -> 1). */
    fun meter(levelDb: Float): Float = ((levelDb + 60f) / 50f).coerceIn(0f, 1f)
}

/** Parses Vosk JSON results: {"partial": "..."} and {"text": "..."}. */
object VoskJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun partial(s: String?): String = field(s, "partial")
    fun text(s: String?): String = field(s, "text")

    private fun field(s: String?, key: String): String {
        if (s.isNullOrBlank()) return ""
        return try {
            val o = json.parseToJsonElement(s) as? JsonObject ?: return ""
            o[key]?.jsonPrimitive?.content?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * Accumulates Vosk output for one utterance. Vosk finalizes a segment at
 * short pauses; we keep listening until [Endpointer] says the user is done,
 * so a mid-sentence pause doesn't cut the message.
 */
class TranscriptBuffer {
    private val segments = mutableListOf<String>()
    var partial: String = ""
        private set

    fun onPartial(p: String) { partial = p.trim() }

    fun onSegment(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) segments += t
        partial = ""
    }

    /** What the user sees in the composer. */
    fun current(): String = (segments + listOfNotNull(partial.takeIf { it.isNotEmpty() })).joinToString(" ")

    /** Final transcript once the endpointer fires; [last] is Vosk's FinalResult. */
    fun finish(last: String): String {
        onSegment(last)
        return segments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }
}
