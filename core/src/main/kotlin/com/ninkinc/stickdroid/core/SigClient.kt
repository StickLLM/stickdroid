package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit as TU

class SigError(val code: String, val retry: String? = null) : RuntimeException(code)

/**
 * sticklink signalling WebSocket client (server/API.md, sigclient.py).
 * Subprotocol sticklink.1. The phone SHOULD keep this open after candidates.
 */
class SigClient(
    private val baseUrl: String,
    private val http: OkHttpClient = defaultHttp(),
    private val log: (String) -> Unit = {}
) {
    var nonce: ByteArray? = null
        private set
    var closeCode: Int? = null
        private set
    val events = LinkedBlockingQueue<JsonObject>()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Array<JsonObject?>>()
    private var ws: WebSocket? = null
    @Volatile var closed: Boolean = false
        private set

    fun connect(query: Map<String, String>, timeoutMs: Long = 10_000): JsonObject {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val q = query.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = baseUrl + sep + q
        val req = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        val latch = CountDownLatch(1)
        var welcome: JsonObject? = null
        var fail: Exception? = null
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val proto = response.header("Sec-WebSocket-Protocol")
                if (proto != null && proto != SUBPROTOCOL) {
                    fail = SigError("subprotocol")
                    latch.countDown()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_FRAME) return
                val m = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
                val t = m["t"]?.jsonPrimitive?.contentOrNull
                if (t == "pong") return
                if (welcome == null && t == "welcome") {
                    welcome = m
                    nonce = Bytes.unb64u(m["n"]!!.jsonPrimitive.content, 32)
                    latch.countDown()
                    return
                }
                val mid = m["id"]?.jsonPrimitive?.intOrNull
                val slot = if (mid != null) pending.remove(mid) else null
                if (slot != null) {
                    synchronized(slot) { slot[0] = m; (slot as Object).notifyAll() }
                } else {
                    events.offer(m)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                closed = true
                events.offer(buildJsonObject { put("t", "_closed"); put("code", code) })
                failPending(SigError("closed"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed = true
                if (welcome == null) {
                    fail = t as? Exception ?: RuntimeException(t)
                    latch.countDown()
                }
                events.offer(buildJsonObject { put("t", "_closed"); put("code", -1) })
                failPending(SigError("closed"))
            }
        })
        if (!latch.await(timeoutMs, TU.MILLISECONDS)) throw SigError("timeout")
        fail?.let { throw it }
        val w = welcome ?: throw SigError("no welcome")
        return w
    }

    fun send(msg: JsonObject) {
        val raw = msg.toString()
        if (raw.length > MAX_FRAME) throw SigError("size")
        ws?.send(raw) ?: throw SigError("closed")
    }

    fun request(msg: JsonObject, timeoutMs: Long = 10_000): JsonObject {
        val mid = ids.getAndIncrement()
        val body = buildJsonObject {
            for ((k, v) in msg) if (k != "id") put(k, v)
            put("id", mid)
        }
        val slot = arrayOfNulls<JsonObject>(1)
        pending[mid] = slot
        send(body)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(slot) {
            while (slot[0] == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    pending.remove(mid)
                    throw SigError("timeout")
                }
                (slot as Object).wait(left)
            }
        }
        val rep = slot[0]!!
        if (rep["t"]?.jsonPrimitive?.contentOrNull == "err") {
            throw SigError(rep["code"]?.jsonPrimitive?.contentOrNull ?: "?", rep["retry"]?.jsonPrimitive?.contentOrNull)
        }
        return rep
    }

    fun close() {
        try { ws?.close(1000, null) } catch (_: Exception) {}
        closed = true
    }

    private fun failPending(e: SigError) {
        for (s in pending.values) {
            synchronized(s) {
                if (s[0] == null) s[0] = buildJsonObject { put("t", "err"); put("code", e.code) }
                (s as Object).notifyAll()
            }
        }
        pending.clear()
    }

    companion object {
        const val SUBPROTOCOL = "sticklink.1"
        const val MAX_FRAME = 4096
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS) // we send app-level ping
            .build()
    }
}

object SigJson {
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) {
            when (v) {
                null -> {}
                is String -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Boolean -> put(k, v)
                is JsonObject -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }
}
