package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * StickLink APP-MUX v1 client, HTTP part — port of sticklink_app_mux.js
 * (createAppMux().http). The stick side is app_mux_bridge.py, which only
 * forwards /agent-zero, /socket.io, /api/agent, /api/csrf*, /api/pair/status
 * to the stick's loopback gateway (everything else → 403 "forbidden path").
 *
 *  → {t:http_req, id:"r<n>", method, path, headers, body_b64?}            (body ≤ 36 KiB)
 *  → {t:http_req, ..., body_len} + {t:http_chunk, role:req, seq, eof, body_b64}...
 *  ← {t:http_res, id, status, headers, body_b64|null}                     (single)
 *  ← {t:http_res, id, status, headers, body_len} + {t:http_chunk, role:res, ...}
 *  Body is gzip when headers["x-sticklink-ce"] == "gzip".
 */
class AppMux(
    private val sendPt: (JsonObject) -> Unit,
    private val log: (String) -> Unit = {},
    /**
     * Pause between request chunks after the first [PACE_AFTER]. The always-on
     * Worker drops sess frames over 100/s (burst 200; worker config SESS_RATE_PER_S /
     * SESS_BURST), and an 8 MiB upload is ~230 chunks.
     */
    private val chunkPaceMs: Long = CHUNK_PACE_MS,
) {
    data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    class MuxError(msg: String) : RuntimeException(msg)

    private class Pending {
        val done = java.util.concurrent.CountDownLatch(1)
        var status = 0
        var headers: Map<String, String> = emptyMap()
        val buf = ByteArrayOutputStream()
        var chunked = false
        var result: HttpResponse? = null
        var error: String? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val seq = AtomicInteger(1)
    private val slots = Semaphore(HTTP_MAX, true)

    fun http(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
             timeoutMs: Long = 180_000): HttpResponse {
        if (!slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) throw MuxError("mux busy")
        val id = "r" + seq.getAndIncrement()
        val p = Pending()
        pending[id] = p
        try {
            val hdrs = buildJsonObject { for ((k, v) in headers) put(k, v) }
            if (body == null || body.size <= CHUNK) {
                sendPt(buildJsonObject {
                    put("t", "http_req"); put("id", id); put("method", method.uppercase()); put("path", path)
                    put("headers", hdrs)
                    if (body != null && body.isNotEmpty()) put("body_b64", Bytes.b64u(body))
                })
            } else {
                sendPt(buildJsonObject {
                    put("t", "http_req"); put("id", id); put("method", method.uppercase()); put("path", path)
                    put("headers", hdrs); put("body_len", body.size)
                })
                var i = 0; var n = 0
                while (i < body.size) {
                    if (n >= PACE_AFTER && chunkPaceMs > 0) Thread.sleep(chunkPaceMs)
                    val end = minOf(i + CHUNK, body.size)
                    sendPt(buildJsonObject {
                        put("t", "http_chunk"); put("id", id); put("role", "req"); put("seq", n++)
                        put("eof", end >= body.size); put("body_b64", Bytes.b64u(body.copyOfRange(i, end)))
                    })
                    i = end
                }
            }
            if (!p.done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw MuxError("http timeout $path")
            p.error?.let { throw MuxError(it) }
            return p.result!!
        } finally {
            pending.remove(id)
            slots.release()
        }
    }

    /** Feed every post-agent_ready PT message here. */
    fun dispatch(m: JsonObject) {
        val t = s(m, "t") ?: return
        val id = s(m, "id") ?: return
        when (t) {
            "http_res" -> {
                val p = pending[id] ?: return
                p.status = (m["status"] as? JsonPrimitive)?.intOrNull ?: 0
                p.headers = (m["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: "" }
                    ?.mapKeys { it.key.lowercase() } ?: emptyMap()
                val hasBody = s(m, "body_b64") != null
                if (m["body_len"] != null && !hasBody) {
                    p.chunked = true
                    return
                }
                val raw = s(m, "body_b64")?.let { Bytes.unb64u(it) } ?: ByteArray(0)
                finish(p, raw)
            }
            "http_chunk" -> {
                if (s(m, "role") != "res") return
                val p = pending[id] ?: return
                val piece = s(m, "body_b64")?.let { Bytes.unb64u(it) } ?: ByteArray(0)
                p.buf.write(piece)
                if (p.buf.size() > MAX_BODY) { p.error = "body too large"; p.done.countDown(); return }
                if ((m["eof"] as? JsonPrimitive)?.booleanOrNull == true) finish(p, p.buf.toByteArray())
            }
            else -> {} // ws_* not used by the native chat path
        }
    }

    fun failAll(why: String) {
        for (p in pending.values) { p.error = why; p.done.countDown() }
    }

    private fun finish(p: Pending, raw: ByteArray) {
        try {
            val body = if (p.headers["x-sticklink-ce"]?.lowercase() == "gzip" && raw.isNotEmpty()) {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else raw
            p.result = HttpResponse(p.status, p.headers, body)
        } catch (e: Exception) {
            p.error = "gunzip failed"
        }
        p.done.countDown()
    }

    private fun s(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val CHUNK = 36 * 1024
        const val MAX_BODY = 8 * 1024 * 1024
        const val HTTP_MAX = 8
        const val CHUNK_PACE_MS = 15L
        const val PACE_AFTER = 16
    }
}
