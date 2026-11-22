package com.ninkinc.stickdroid.core

/** How Agent Zero replies should be read aloud with the phone's TTS. */
enum class ReadAloudMode {
    /** Read every reply (except empty / non-speakable). */
    Always,
    /** Default: read a reply only when the user message was spoken. */
    AfterSpeech,
    Never,
}

enum class SpeechPhase { Idle, RequestingMic, Listening, Speaking }

enum class ListenFail { Silence, NoMatch, Busy, Unavailable, Error }

data class SpeechPrefs(
    val autoSend: Boolean = true,
    val readAloud: ReadAloudMode = ReadAloudMode.AfterSpeech,
    val handsFree: Boolean = false,
)

data class SpeechContext(
    val connected: Boolean = true,
    val busy: Boolean = false,
    val foreground: Boolean = true,
)

data class SpeechState(
    val phase: SpeechPhase = SpeechPhase.Idle,
    val partial: String = "",
    val rms: Float = 0f,
    val speakingLineId: String? = null,
    val lastSpoken: Boolean = false,
    val note: String? = null,
    val recognizerAvailable: Boolean = true,
    val micPermission: Boolean = false,
)

sealed class SpeechEvent {
    data object MicTapped : SpeechEvent()
    data object PermissionGranted : SpeechEvent()
    data object PermissionDenied : SpeechEvent()
    data class Partial(val text: String) : SpeechEvent()
    data class Rms(val level: Float) : SpeechEvent()
    data class FinalResult(val text: String) : SpeechEvent()
    data class ListenFailed(val reason: ListenFail) : SpeechEvent()
    data class ReplyReady(val lineId: String, val text: String) : SpeechEvent()
    data object TtsFinished : SpeechEvent()
    data object TtsFailed : SpeechEvent()
    data object StopSpeakingTapped : SpeechEvent()
    data object UserTypedSend : SpeechEvent()
    data object LeftForeground : SpeechEvent()
    data object Disconnect : SpeechEvent()
}

sealed class SpeechEffect {
    data object RequestMicPermission : SpeechEffect()
    data object StartListening : SpeechEffect()
    data object StopListening : SpeechEffect()
    data class Send(val text: String) : SpeechEffect()
    data class SetDraft(val text: String) : SpeechEffect()
    data class Speak(val lineId: String, val text: String) : SpeechEffect()
    data object StopSpeaking : SpeechEffect()
}

data class SpeechStep(val state: SpeechState, val effects: List<SpeechEffect> = emptyList())

/**
 * Phone-side speech state machine (mic in, TTS out, optional hands-free loop).
 * Pure and unit-testable: the Android layer just applies [SpeechEffect]s.
 */
object SpeechMachine {

    fun reduce(
        state: SpeechState,
        event: SpeechEvent,
        prefs: SpeechPrefs = SpeechPrefs(),
        ctx: SpeechContext = SpeechContext(),
    ): SpeechStep = when (event) {
        SpeechEvent.MicTapped -> onMic(state, prefs, ctx)
        SpeechEvent.PermissionGranted -> {
            val granted = state.copy(micPermission = true, note = null)
            if (state.phase == SpeechPhase.RequestingMic) startListen(granted, extra = emptyList())
            else SpeechStep(granted)
        }
        SpeechEvent.PermissionDenied -> SpeechStep(
            state.copy(
                phase = SpeechPhase.Idle,
                micPermission = false,
                partial = "",
                rms = 0f,
                note = "Microphone permission is needed to use speech. You can enable it in Android Settings.",
            )
        )
        is SpeechEvent.Partial -> {
            if (state.phase != SpeechPhase.Listening) SpeechStep(state)
            else SpeechStep(state.copy(partial = event.text, note = null), listOf(SpeechEffect.SetDraft(event.text)))
        }
        is SpeechEvent.Rms -> {
            if (state.phase != SpeechPhase.Listening) SpeechStep(state)
            else SpeechStep(state.copy(rms = event.level.coerceIn(0f, 1f)))
        }
        is SpeechEvent.FinalResult -> onFinal(state, prefs, event.text)
        is SpeechEvent.ListenFailed -> onListenFailed(state, prefs, event.reason)
        is SpeechEvent.ReplyReady -> onReply(state, prefs, ctx, event.lineId, event.text)
        SpeechEvent.TtsFinished -> afterSpeech(state, prefs, ctx, failed = false)
        SpeechEvent.TtsFailed -> afterSpeech(
            state.copy(note = "Couldn't read the reply aloud."),
            prefs, ctx, failed = true
        )
        SpeechEvent.StopSpeakingTapped -> afterSpeech(
            state,
            prefs,
            ctx,
            failed = false,
            extra = listOf(SpeechEffect.StopSpeaking),
        )
        SpeechEvent.UserTypedSend -> {
            val fx = mutableListOf<SpeechEffect>()
            if (state.phase == SpeechPhase.Listening) fx += SpeechEffect.StopListening
            if (state.phase == SpeechPhase.Speaking) fx += SpeechEffect.StopSpeaking
            SpeechStep(
                state.copy(
                    phase = SpeechPhase.Idle,
                    lastSpoken = false,
                    partial = "",
                    rms = 0f,
                    speakingLineId = null,
                    note = null,
                ),
                fx
            )
        }
        SpeechEvent.LeftForeground -> onBackground(state)
        SpeechEvent.Disconnect -> {
            val fx = mutableListOf<SpeechEffect>()
            if (state.phase == SpeechPhase.Listening) fx += SpeechEffect.StopListening
            if (state.phase == SpeechPhase.Speaking) fx += SpeechEffect.StopSpeaking
            SpeechStep(
                state.copy(
                    phase = SpeechPhase.Idle,
                    partial = "",
                    rms = 0f,
                    speakingLineId = null,
                    lastSpoken = false,
                    note = null,
                ),
                fx
            )
        }
    }

    private fun onMic(state: SpeechState, prefs: SpeechPrefs, ctx: SpeechContext): SpeechStep {
        if (!state.recognizerAvailable) {
            return SpeechStep(
                state.copy(
                    phase = SpeechPhase.Idle,
                    note = "Speech recognition isn't available on this device.",
                )
            )
        }
        if (!ctx.connected) return SpeechStep(state)
        if (state.phase == SpeechPhase.RequestingMic) return SpeechStep(state)
        if (state.phase == SpeechPhase.Listening) {
            val draft = state.partial.trim()
            val fx = mutableListOf<SpeechEffect>(SpeechEffect.StopListening)
            if (draft.isNotEmpty()) fx += SpeechEffect.SetDraft(draft)
            return SpeechStep(
                state.copy(phase = SpeechPhase.Idle, partial = "", rms = 0f, note = null),
                fx
            )
        }
        if (ctx.busy && state.phase != SpeechPhase.Speaking) return SpeechStep(state)
        val extra = if (state.phase == SpeechPhase.Speaking) listOf(SpeechEffect.StopSpeaking) else emptyList()
        return startListen(state.copy(speakingLineId = null, note = null), extra)
    }

    private fun onFinal(state: SpeechState, prefs: SpeechPrefs, raw: String): SpeechStep {
        if (state.phase != SpeechPhase.Listening && state.phase != SpeechPhase.Idle) {
            return SpeechStep(state)
        }
        val text = raw.trim()
        val base = state.copy(phase = SpeechPhase.Idle, partial = "", rms = 0f, note = null)
        if (text.isEmpty()) {
            return SpeechStep(base.copy(note = "Didn't catch that."))
        }
        return if (prefs.autoSend) {
            SpeechStep(
                base.copy(lastSpoken = true),
                listOf(SpeechEffect.SetDraft(""), SpeechEffect.Send(text))
            )
        } else {
            SpeechStep(
                base.copy(lastSpoken = true),
                listOf(SpeechEffect.SetDraft(text))
            )
        }
    }

    private fun onListenFailed(state: SpeechState, prefs: SpeechPrefs, reason: ListenFail): SpeechStep {
        if (state.phase != SpeechPhase.Listening) {
            return SpeechStep(state)
        }
        val draft = state.partial.trim()
        val fx = mutableListOf<SpeechEffect>(SpeechEffect.StopListening)
        if (draft.isNotEmpty()) fx += SpeechEffect.SetDraft(draft)
        val note = when (reason) {
            ListenFail.Silence -> if (prefs.handsFree) "Hands-free paused (silence)." else "Didn't catch that."
            ListenFail.NoMatch -> "Didn't catch that."
            ListenFail.Busy -> "The phone's recognizer is busy. Try again in a moment."
            ListenFail.Unavailable -> "Speech recognition isn't available on this device."
            ListenFail.Error -> "Speech recognition failed. Try again."
        }
        val available = state.recognizerAvailable && reason != ListenFail.Unavailable
        return SpeechStep(
            state.copy(
                phase = SpeechPhase.Idle,
                partial = "",
                rms = 0f,
                note = note,
                recognizerAvailable = available,
            ),
            fx
        )
    }

    private fun onReply(
        state: SpeechState,
        prefs: SpeechPrefs,
        ctx: SpeechContext,
        lineId: String,
        text: String,
    ): SpeechStep {
        // Never speak over an open mic: the recognizer would transcribe the TTS.
        // The user is already talking, so the reply just stays on screen.
        if (state.phase == SpeechPhase.Listening || state.phase == SpeechPhase.RequestingMic) {
            return SpeechStep(state)
        }
        val speakable = SpeakText.clean(text)
        val shouldSpeak = speakable.isNotEmpty() && when (prefs.readAloud) {
            ReadAloudMode.Always -> true
            ReadAloudMode.AfterSpeech -> state.lastSpoken
            ReadAloudMode.Never -> false
        }
        if (shouldSpeak) {
            return SpeechStep(
                state.copy(
                    phase = SpeechPhase.Speaking,
                    speakingLineId = lineId,
                    partial = "",
                    rms = 0f,
                    note = null,
                ),
                listOf(SpeechEffect.Speak(lineId, speakable))
            )
        }
        return maybeHandsFree(state.copy(speakingLineId = null, partial = "", rms = 0f), prefs, ctx)
    }

    private fun afterSpeech(
        state: SpeechState,
        prefs: SpeechPrefs,
        ctx: SpeechContext,
        failed: Boolean,
        extra: List<SpeechEffect> = emptyList(),
    ): SpeechStep {
        val next = state.copy(
            phase = SpeechPhase.Idle,
            speakingLineId = null,
            partial = "",
            rms = 0f,
            note = if (failed) (state.note ?: "Couldn't read the reply aloud.") else state.note,
        )
        return maybeHandsFree(next, prefs, ctx, extra)
    }

    private fun maybeHandsFree(
        state: SpeechState,
        prefs: SpeechPrefs,
        ctx: SpeechContext,
        extra: List<SpeechEffect> = emptyList(),
    ): SpeechStep {
        if (!prefs.handsFree || !ctx.foreground || !ctx.connected || ctx.busy) {
            return SpeechStep(state.copy(phase = SpeechPhase.Idle), extra)
        }
        return startListen(state, extra)
    }

    private fun startListen(state: SpeechState, extra: List<SpeechEffect>): SpeechStep {
        if (!state.recognizerAvailable) {
            return SpeechStep(
                state.copy(
                    phase = SpeechPhase.Idle,
                    note = "Speech recognition isn't available on this device.",
                ),
                extra
            )
        }
        if (!state.micPermission) {
            return SpeechStep(
                state.copy(phase = SpeechPhase.RequestingMic, note = null),
                extra + SpeechEffect.RequestMicPermission
            )
        }
        return SpeechStep(
            state.copy(phase = SpeechPhase.Listening, partial = "", rms = 0f, speakingLineId = null),
            extra + SpeechEffect.StartListening
        )
    }

    private fun onBackground(state: SpeechState): SpeechStep {
        // A runtime permission dialog also stops the activity. Ignore that so
        // we don't cancel the prompt or fail to start listening afterwards.
        if (state.phase == SpeechPhase.RequestingMic) return SpeechStep(state)
        val fx = mutableListOf<SpeechEffect>()
        if (state.phase == SpeechPhase.Listening) fx += SpeechEffect.StopListening
        if (state.phase == SpeechPhase.Speaking) fx += SpeechEffect.StopSpeaking
        val note = if (state.phase == SpeechPhase.Listening || state.phase == SpeechPhase.Speaking) {
            "Hands-free paused."
        } else {
            state.note
        }
        return SpeechStep(
            state.copy(
                phase = SpeechPhase.Idle,
                partial = "",
                rms = 0f,
                speakingLineId = null,
                note = note,
            ),
            fx
        )
    }
}
