package com.ninkinc.stickdroid

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ninkinc.stickdroid.core.EndpointConfig
import com.ninkinc.stickdroid.core.EndpointDecision
import com.ninkinc.stickdroid.core.ListenFail
import com.ninkinc.stickdroid.core.Pcm
import com.ninkinc.stickdroid.core.SpeechEvent
import com.ninkinc.stickdroid.core.StreamingAsr
import com.ninkinc.stickdroid.core.UtteranceLoop
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in, fully offline speech-to-text: Vosk (Kaldi) with the small
 * US-English model bundled in the APK assets. Needs no system
 * RecognitionService (GrapheneOS has none) and no network.
 *
 * The model is unpacked from assets to app-private storage on first use.
 * Audio never leaves the phone; only the final text is sent to Agent Zero.
 */
class BuiltInRecognizer(
    context: Context,
    private val onEvent: (SpeechEvent) -> Unit,
    private val onStatus: (Status) -> Unit = {},
) {
    enum class Status { NotLoaded, Loading, Ready, Failed }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "stt-builtin").apply { priority = Thread.MAX_PRIORITY - 1 } }
    private val generation = AtomicInteger(0)
    @Volatile private var model: Model? = null
    @Volatile var status: Status = Status.NotLoaded
        private set
    @Volatile private var active = false

    /** The model ships in assets (no download). */
    val bundled: Boolean = try {
        app.assets.list(MODEL)?.contains("am") == true
    } catch (_: Exception) { false }

    fun available(): Boolean = bundled && status != Status.Failed

    /** Unpack + load in the background so the first tap is quick. */
    fun warmUp() {
        if (!bundled || status == Status.Ready || status == Status.Loading) return
        worker.execute { ensureModel() }
    }

    fun start(config: EndpointConfig = EndpointConfig()) {
        val gen = generation.incrementAndGet()
        active = true
        worker.execute { runListen(gen, config) }
    }

    /** Cancel the current listen; emits nothing (like SpeechRecognizer.cancel). */
    fun stop() {
        generation.incrementAndGet()
        active = false
    }

    fun release() {
        stop()
        worker.execute {
            try { model?.close() } catch (_: Throwable) {}
            model = null
            setStatus(Status.NotLoaded)
        }
        worker.shutdown()
    }

    private fun ensureModel(): Model? {
        model?.let { return it }
        setStatus(Status.Loading)
        return try {
            val dir = unpack()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val t0 = System.nanoTime()
            val m = Model(dir.absolutePath)
            Log.i(TAG, "model loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            model = m
            setStatus(Status.Ready)
            m
        } catch (t: Throwable) {
            Log.e(TAG, "built-in model failed: ${t.javaClass.simpleName}: ${t.message}")
            setStatus(Status.Failed)
            null
        }
    }

    /** Copy assets/MODEL to filesDir/vosk/MODEL once (marker per app version). */
    private fun unpack(): File {
        val root = File(app.filesDir, "vosk")
        val dest = File(root, MODEL)
        val marker = File(dest, ".unpacked-${BuildConfig.VERSION_CODE}")
        if (marker.exists()) return dest
        val t0 = System.nanoTime()
        dest.deleteRecursively()
        copyAssetDir(MODEL, dest)
        marker.writeText("ok")
        Log.i(TAG, "model unpacked in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return dest
    }

    private fun copyAssetDir(path: String, out: File) {
        val kids = app.assets.list(path).orEmpty()
        if (kids.isEmpty()) {
            out.parentFile?.mkdirs()
            app.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            return
        }
        out.mkdirs()
        for (k in kids) copyAssetDir("$path/$k", File(out, k))
    }

    @SuppressLint("MissingPermission") // SpeechMachine only starts listening after RECORD_AUDIO is granted.
    private fun runListen(gen: Int, config: EndpointConfig) {
        fun live() = active && generation.get() == gen
        val m = ensureModel()
        if (m == null) {
            if (live()) post(gen, SpeechEvent.ListenFailed(ListenFail.Unavailable))
            return
        }
        if (!live()) return
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 4))
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord: ${t.message}"); post(gen, SpeechEvent.ListenFailed(ListenFail.Error)); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); post(gen, SpeechEvent.ListenFailed(ListenFail.Busy)); return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) try { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Throwable) { null } else null
        val ns = if (NoiseSuppressor.isAvailable()) try { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Throwable) { null } else null
        var recognizer: Recognizer? = null
        try {
            recognizer = Recognizer(m, RATE.toFloat())
            val r = recognizer
            val asr = object : StreamingAsr {
                override fun accept(pcm: ShortArray, n: Int) = r.acceptWaveForm(pcm, n)
                override fun partialJson(): String = r.partialResult
                override fun resultJson(): String = r.result
                override fun finalJson(): String = r.finalResult
            }
            val loop = UtteranceLoop(asr, RATE, config)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                post(gen, SpeechEvent.ListenFailed(ListenFail.Busy)); return
            }
            val buf = ShortArray(FRAME)
            var lastMeter = -1f
            while (live()) {
                val n = rec.read(buf, 0, FRAME)
                if (n < 0) { post(gen, SpeechEvent.ListenFailed(ListenFail.Error)); return }
                if (n == 0) continue
                val step = loop.onFrame(buf, n)
                val meter = Pcm.meter(step.levelDb)
                if (kotlin.math.abs(meter - lastMeter) > 0.02f) { lastMeter = meter; post(gen, SpeechEvent.Rms(meter)) }
                if (step.textChanged && step.text.isNotEmpty()) post(gen, SpeechEvent.Partial(step.text))
                when (step.decision) {
                    EndpointDecision.Continue -> {}
                    EndpointDecision.EndOfSpeech, EndpointDecision.MaxLength -> {
                        val text = loop.finish()
                        post(gen, if (text.isBlank()) SpeechEvent.ListenFailed(ListenFail.NoMatch) else SpeechEvent.FinalResult(text))
                        return
                    }
                    EndpointDecision.NoSpeech -> {
                        val text = loop.finish()
                        post(gen, if (text.isBlank()) SpeechEvent.ListenFailed(ListenFail.Silence) else SpeechEvent.FinalResult(text))
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listen failed: ${t.javaClass.simpleName}: ${t.message}")
            post(gen, SpeechEvent.ListenFailed(ListenFail.Error))
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            aec?.release(); ns?.release()
            try { recognizer?.close() } catch (_: Throwable) {}
            if (generation.get() == gen) active = false
        }
    }

    private fun post(gen: Int, ev: SpeechEvent) {
        main.post { if (generation.get() == gen) onEvent(ev) }
    }

    private fun setStatus(s: Status) {
        status = s
        main.post { onStatus(s) }
    }

    companion object {
        const val MODEL = "vosk-model-small-en-us-0.15"
        private const val TAG = "StickSTT"
        private const val RATE = 16000
        private const val FRAME = 1600 // 100 ms
    }
}
