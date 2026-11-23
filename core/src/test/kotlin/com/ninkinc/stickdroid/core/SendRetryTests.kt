package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SendRetryTests {
    private fun csrfOk(mux: AppMux, id: String) {
        mux.dispatch(buildJsonObject {
            put("t", "http_res"); put("id", id); put("status", 200)
            putJsonObject("headers") { put("content-type", "application/json") }
            put("body_b64", Bytes.b64u("""{"ok":true,"token":"tok"}""".toByteArray()))
        })
    }

    private fun asyncOk(mux: AppMux, id: String, ctx: String = "c1") {
        mux.dispatch(buildJsonObject {
            put("t", "http_res"); put("id", id); put("status", 200)
            putJsonObject("headers") { put("content-type", "application/json") }
            put("body_b64", Bytes.b64u("""{"message":"Message received.","context":"$ctx"}""".toByteArray()))
        })
    }

    @Test fun messageAsyncTimesOutThenRetriesThenFails() {
        val attempts = AtomicInteger()
        lateinit var mux: AppMux
        mux = AppMux({ m ->
            if (m["t"]?.jsonPrimitive?.contentOrNull != "http_req") return@AppMux
            val path = m["path"]!!.jsonPrimitive.contentOrNull!!
            val id = m["id"]!!.jsonPrimitive.contentOrNull!!
            when {
                path.endsWith("csrf_token") -> csrfOk(mux, id)
                path.endsWith("message_async") -> attempts.incrementAndGet()
            }
        }, chunkPaceMs = 0)
        val chat = A0Chat(mux, sendTimeoutMs = 80, sendRetries = 2)
        val e = assertThrows(A0Chat.A0Error::class.java) { chat.start("hello", emptyList(), "sd-1") }
        assertTrue(e.sendFailed)
        assertEquals(A0Chat.SEND_TIMEOUT, e.status)
        assertEquals(3, attempts.get())
        assertTrue(e.message!!.contains("Retry"))
    }

    @Test fun messageAsyncSucceedsOnSecondAttempt() {
        val attempts = AtomicInteger()
        lateinit var mux: AppMux
        mux = AppMux({ m ->
            if (m["t"]?.jsonPrimitive?.contentOrNull != "http_req") return@AppMux
            val path = m["path"]!!.jsonPrimitive.contentOrNull!!
            val id = m["id"]!!.jsonPrimitive.contentOrNull!!
            Thread {
                when {
                    path.endsWith("csrf_token") -> csrfOk(mux, id)
                    path.endsWith("message_async") -> {
                        if (attempts.incrementAndGet() == 1) return@Thread
                        asyncOk(mux, id)
                    }
                }
            }.start()
        }, chunkPaceMs = 0)
        val chat = A0Chat(mux, sendTimeoutMs = 80, sendRetries = 2)
        val ctx = chat.start("hello", emptyList(), "sd-1")
        assertEquals("c1", ctx)
        assertEquals(2, attempts.get())
    }
}
