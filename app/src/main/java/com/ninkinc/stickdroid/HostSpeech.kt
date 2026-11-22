package com.ninkinc.stickdroid

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ninkinc.stickdroid.core.EngineAvailability
import com.ninkinc.stickdroid.core.EngineSelector
import com.ninkinc.stickdroid.core.ListenFail
import com.ninkinc.stickdroid.core.RecognizerEngine
import com.ninkinc.stickdroid.core.RecognizerPref
import com.ninkinc.stickdroid.core.SpeechEvent
import java.util.Locale

/**
 * Phone speech: speech-to-text from the built-in Vosk engine (default, offline)
 * or Android's system SpeechRecognizer (opt-in), and TextToSpeech for replies.
 * Callbacks are always posted on the main thread.
 */
class HostSpeech(
    context: Context,
    private val onEvent: (SpeechEvent) -> Unit,
    private val onBuiltInStatus: (BuiltInRecognizer.Status) -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val builtIn = BuiltInRecognizer(app, ::onBuiltInEvent, { onBuiltInStatus(it) })

    /** User's engine choice (Settings). */
    var pref: RecognizerPref = RecognizerPref.BuiltIn
    /** Engine used for the current / last listen. */
    var activeEngine: RecognizerEngine? = null
        private set
    /** Why a fallback engine was used, if any. */
    var engineNote: String? = null
        private set

    val builtInStatus: BuiltInRecognizer.Status get() = builtIn.status

    var recognitionAvailable: Boolean = probeRecognition()
        private set
    var onDevice: Boolean = false
        private set
    var ttsReady: Boolean = false
        private set
    var ttsFailed: Boolean = false
        private set

    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice: Boolean = false
    private var tts: TextToSpeech? = null
    private var focusReq: AudioFocusRequest? = null
    private var listening = false
    private var speaking = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            if (listening) stopListening()
            if (speaking) {
                stopSpeaking()
                emit(SpeechEvent.TtsFinished)
            }
        }
    }

    init {
        tts = TextToSpeech(app) { status ->
            main.post {
                if (status != TextToSpeech.SUCCESS) {
                    ttsReady = false
                    ttsFailed = true
                    tts?.shutdown()
                    tts = null
                    return@post
                }
                val engine = tts ?: return@post
                val lang = engine.setLanguage(Locale.getDefault())
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        main.post {
                            speaking = false
                            abandonFocus()
                            emit(SpeechEvent.TtsFinished)
                        }
                    }
                    @Deprecated("deprecated")
                    override fun onError(utteranceId: String?) {
                        main.post {
                            speaking = false
                            abandonFocus()
                            emit(SpeechEvent.TtsFailed)
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post {
                            speaking = false
                            abandonFocus()
                            emit(SpeechEvent.TtsFailed)
                        }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        // stopSpeaking() issues its own event when the user asked to stop.
                    }
                })
                ttsReady = true
                ttsFailed = false
            }
        }
    }

    fun refreshAvailability() {
        recognitionAvailable = probeRecognition()
    }

    /** Android has a system RecognitionService (none on GrapheneOS by default). */
    fun systemAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(app) || onDeviceAvailable()

    fun availability() = EngineAvailability(builtIn = builtIn.available(), system = systemAvailable())

    /** Unpack/load the built-in model ahead of the first tap. */
    fun warmUp() {
        if (EngineSelector.select(pref, availability()).engine == RecognizerEngine.BuiltIn) builtIn.warmUp()
    }

    fun startListening() {
        runMain {
            val choice = EngineSelector.select(pref, availability())
            engineNote = choice.note.takeIf { choice.fellBack }
            activeEngine = choice.engine
            if (choice.engine == null) {
                recognitionAvailable = false
                emit(SpeechEvent.ListenFailed(ListenFail.Unavailable))
                return@runMain
            }
            stopListeningLocked()
            if (choice.engine == RecognizerEngine.BuiltIn) {
                onDevice = true
                requestFocus(transientExclusive = true)
                listening = true
                builtIn.start()
                return@runMain
            }
            val firstOnDevice = onDeviceAvailable()
            fun bind(preferOnDevice: Boolean): Boolean {
                destroyRecognizerLocked()
                val r = try {
                    if (preferOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                    else SpeechRecognizer.createSpeechRecognizer(app)
                } catch (_: Exception) {
                    return false
                }
                usingOnDevice = preferOnDevice
                onDevice = preferOnDevice
                r.setRecognitionListener(listener)
                recognizer = r
                return true
            }
            if (!(firstOnDevice && bind(true)) && !bind(false)) {
                recognitionAvailable = probeRecognition()
                emit(SpeechEvent.ListenFailed(ListenFail.Unavailable))
                return@runMain
            }
            requestFocus(transientExclusive = true)
            listening = true
            try {
                recognizer!!.startListening(intent(preferOffline = usingOnDevice || firstOnDevice))
            } catch (_: Exception) {
                if (usingOnDevice && bind(false)) {
                    try {
                        recognizer!!.startListening(intent(preferOffline = false))
                    } catch (_: Exception) {
                        listening = false
                        abandonFocus()
                        emit(SpeechEvent.ListenFailed(ListenFail.Error))
                    }
                } else {
                    listening = false
                    abandonFocus()
                    emit(SpeechEvent.ListenFailed(ListenFail.Error))
                }
            }
        }
    }

    fun stopListening() {
        runMain { stopListeningLocked() }
    }

    fun speak(text: String, utteranceId: String): Boolean {
        var ok = false
        runMain {
            if (!ttsReady || tts == null) {
                ttsFailed = true
                emit(SpeechEvent.TtsFailed)
                return@runMain
            }
            requestFocus(transientExclusive = false)
            val r = tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (r == TextToSpeech.ERROR) {
                speaking = false
                abandonFocus()
                emit(SpeechEvent.TtsFailed)
            } else {
                speaking = true
                ok = true
            }
        }
        return ok
    }

    fun stopSpeaking() {
        runMain {
            if (speaking || tts != null) {
                try { tts?.stop() } catch (_: Exception) {}
            }
            speaking = false
            abandonFocus()
        }
    }

    fun shutdown() {
        runMain {
            stopListeningLocked()
            builtIn.release()
            try { tts?.stop() } catch (_: Exception) {}
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
            ttsReady = false
            speaking = false
            abandonFocus()
        }
    }

    private fun intent(preferOffline: Boolean) = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            // Typical range is roughly -2..10 dB; map to 0..1 for the level bar.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            emit(SpeechEvent.Rms(level))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            emit(SpeechEvent.Partial(text))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            abandonFocus()
            val text = firstResult(results).orEmpty()
            emit(SpeechEvent.FinalResult(text))
        }

        override fun onError(error: Int) {
            listening = false
            abandonFocus()
            emit(SpeechEvent.ListenFailed(mapError(error)))
        }
    }

    private fun mapError(error: Int): ListenFail = when (error) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenFail.Silence
        SpeechRecognizer.ERROR_NO_MATCH -> ListenFail.NoMatch
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> ListenFail.Busy
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_SERVER -> ListenFail.Error
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenFail.Error
        else -> {
            if (error == SpeechRecognizer.ERROR_AUDIO) ListenFail.Error
            else ListenFail.Error
        }
    }

    private fun firstResult(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun probeRecognition(): Boolean = EngineSelector.anyAvailable(availability())

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)

    private fun onBuiltInEvent(ev: SpeechEvent) {
        if (ev is SpeechEvent.FinalResult || ev is SpeechEvent.ListenFailed) {
            listening = false
            if (!speaking) abandonFocus()
        }
        onEvent(ev)
    }

    private fun stopListeningLocked() {
        builtIn.stop()
        if (listening) {
            listening = false
            try { recognizer?.stopListening() } catch (_: Exception) {}
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
        destroyRecognizerLocked()
        if (!speaking) abandonFocus()
    }

    private fun destroyRecognizerLocked() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun requestFocus(transientExclusive: Boolean) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val kind = if (transientExclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(kind)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, main)
            .setAcceptsDelayedFocusGain(false)
            .build()
        focusReq?.let { try { audio.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focusReq = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusReq?.let { try { audio.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focusReq = null
    }

    private fun emit(event: SpeechEvent) {
        main.post { onEvent(event) }
    }

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post(block)
    }
}
