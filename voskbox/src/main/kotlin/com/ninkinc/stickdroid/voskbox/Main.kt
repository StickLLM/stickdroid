package com.ninkinc.stickdroid.voskbox

import com.ninkinc.stickdroid.core.EndpointDecision
import com.ninkinc.stickdroid.core.StreamingAsr
import com.ninkinc.stickdroid.core.UtteranceLoop
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Usage: voskbox <modelDir> <file.wav (16 kHz mono s16le)> [trailingSilenceMs] */
fun main(args: Array<String>) {
    val modelDir = args[0]
    val wav = File(args[1])
    val tail = args.getOrNull(2)?.toInt() ?: 3000
    LibVosk.setLogLevel(LogLevel.WARNINGS)
    val bytes = wav.readBytes()
    val dataAt = findData(bytes)
    val pcm = ByteBuffer.wrap(bytes, dataAt + 8, bytes.size - dataAt - 8).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val samples = ShortArray(pcm.remaining()).also { pcm.get(it) }
    // Lead with 500 ms and trail with silence, like a mic opening before and after speech.
    val lead = ShortArray(8000)
    val all = lead + samples + ShortArray(16 * tail)
    val t0 = System.nanoTime()
    Model(modelDir).use { model ->
        val tLoad = (System.nanoTime() - t0) / 1e6
        Recognizer(model, 16000f).use { rec ->
            val asr = object : StreamingAsr {
                override fun accept(pcm: ShortArray, n: Int) = rec.acceptWaveForm(pcm, n)
                override fun partialJson(): String = rec.partialResult
                override fun resultJson(): String = rec.result
                override fun finalJson(): String = rec.finalResult
            }
            val loop = UtteranceLoop(asr)
            val frame = 1600
            var i = 0
            var decision = EndpointDecision.Continue
            val t1 = System.nanoTime()
            while (i < all.size && decision == EndpointDecision.Continue) {
                val n = minOf(frame, all.size - i)
                val buf = all.copyOfRange(i, i + n)
                val step = loop.onFrame(buf, n)
                if (step.textChanged) println("partial @${loop.endpointer.elapsedMs}ms: ${step.text}")
                decision = step.decision
                i += n
            }
            val text = loop.finish()
            val tRun = (System.nanoTime() - t1) / 1e6
            val speechMs = samples.size / 16
            println("model_load_ms=${"%.0f".format(tLoad)} audio_ms=${all.size / 16} speech_ms=$speechMs")
            println("decision=$decision at_ms=${loop.endpointer.elapsedMs} (speech ends at ${500 + speechMs} ms) process_ms=${"%.0f".format(tRun)}")
            println("TRANSCRIPT: $text")
        }
    }
}

private fun findData(b: ByteArray): Int {
    var p = 12
    while (p + 8 <= b.size) {
        val id = String(b, p, 4, Charsets.US_ASCII)
        val len = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (id == "data") return p
        p += 8 + len
    }
    error("no data chunk")
}
