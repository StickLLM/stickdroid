package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplyFilterTests {
    companion object {
        const val LIVE_DUMP =
            "Hello! Here are the details you requested:\n\n" +
                "- **Current Date and Time**: 2026-11-19 17:22:27 EST\n" +
                "- **Agent Information**:\n" +
                "  - **Agent Number**: 0\n" +
                "  - **Profile**: agent0\n" +
                "  - **LLM**: llama_cpp//opt/stickllm/models/appagent/qwen2.5-7b-instruct-q4_k_m-00001-of-00002.gguf\n" +
                "  - **Model**: Qwen 2.5 7B Instruct\n" +
                "  - **Runtime**: llama_cpp\n" +
                "  - **System info**: lab"
    }

    @Test fun liveDumpLeavesGreeting() {
        val shown = ReplyFilter.display(LIVE_DUMP, "hello", showSystemDetails = false)
        assertEquals("Hello!", shown)
        assertFalse(shown.contains("Agent Number"))
        assertFalse(shown.contains("qwen2.5"))
    }

    @Test fun normalRepliesUntouched() {
        val replies = listOf(
            "Sure, I can help with that.",
            "The capital of France is Paris.",
            "Hello! How can I help you today?",
            "Here's a short checklist:\n- buy milk\n- walk the dog"
        )
        for (r in replies) {
            assertEquals(r, ReplyFilter.display(r, "hello", showSystemDetails = false), r)
        }
    }

    @Test fun userAskedKeepsDump() {
        for (q in listOf("what time is it?", "What model are you running?", "show system info", "what's the LLM?")) {
            assertEquals(LIVE_DUMP, ReplyFilter.display(LIVE_DUMP, q, showSystemDetails = false), q)
            assertTrue(ReplyFilter.userAskedForSystemDetails(q), q)
        }
        assertFalse(ReplyFilter.userAskedForSystemDetails("hello"))
        assertFalse(ReplyFilter.userAskedForSystemDetails("tell me a joke sometime"))
    }

    @Test fun settingsToggleKeepsDump() {
        assertEquals(LIVE_DUMP, ReplyFilter.display(LIVE_DUMP, "hello", showSystemDetails = true))
    }

    @Test fun emptyAfterStripFallsBack() {
        val onlyDump =
            "Here are the details you requested:\n\n" +
                "- **Current Date and Time**: 2026-11-19 17:22:27 EST\n" +
                "- **Agent Number**: 0\n" +
                "- **Profile**: agent0"
        assertEquals(ReplyFilter.FALLBACK, ReplyFilter.display(onlyDump, "hi", showSystemDetails = false))
    }

    @Test fun mixedKeepsRealContent() {
        val mixed = "Hello! Here are the details you requested:\n\n" +
            "- **Current Date and Time**: noon\n" +
            "- **Agent Information**:\n" +
            "  - **LLM**: secret\n\n" +
            "The weather is nice today."
        val shown = ReplyFilter.display(mixed, "hello", showSystemDetails = false)
        assertTrue(shown.contains("The weather is nice today."))
        assertTrue(shown.startsWith("Hello!"))
        assertFalse(shown.contains("LLM"))
        assertFalse(shown.contains("details you requested"))
    }

    @Test fun phonePromptWrapsOnceAndUnwraps() {
        val sent = PhonePrompt.wrap("hello")
        assertTrue(sent.startsWith("hello"))
        assertTrue(sent.contains(PhonePrompt.INSTRUCTION))
        assertEquals("hello", PhonePrompt.unwrap(sent))
        assertEquals(sent, PhonePrompt.wrap(sent))
        assertEquals("plain", PhonePrompt.unwrap("plain"))
    }

    @Test fun imageWrapUsesVisionInstructionAndUnwraps() {
        val sent = PhonePrompt.wrap("what is this", hasImage = true)
        assertTrue(sent.startsWith("what is this"))
        assertTrue(sent.contains(PhonePrompt.IMAGE_INSTRUCTION))
        assertFalse(sent.contains("Reply briefly and conversationally in plain text"))
        assertEquals("what is this", PhonePrompt.unwrap(sent))
        assertEquals(sent, PhonePrompt.wrap(sent, hasImage = true))
        val plain = PhonePrompt.wrap("hello", hasImage = false)
        assertTrue(plain.contains(PhonePrompt.INSTRUCTION))
        assertFalse(plain.contains(PhonePrompt.IMAGE_INSTRUCTION))
        assertEquals("hello", PhonePrompt.unwrap(plain))
    }
}

class A0ChatPromptTests {
    @Test fun messageAsyncGetsWrappedTextAndDumpIsFiltered() {
        lateinit var mux: AppMux
        var received: String? = null
        var polls = 0
        mux = AppMux({ m ->
            if (m["t"]!!.jsonPrimitive.content != "http_req") return@AppMux
            val id = m["id"]!!.jsonPrimitive.content
            val path = m["path"]!!.jsonPrimitive.content
            val body = m["body_b64"]?.jsonPrimitive?.contentOrNull?.let { Bytes.unb64u(it) } ?: ByteArray(0)
            Thread {
                fun res(status: Int, json: String) {
                    mux.dispatch(buildJsonObject {
                        put("t", "http_res"); put("id", id); put("status", status)
                        putJsonObject("headers") { put("content-type", "application/json") }
                        put("body_b64", Bytes.b64u(json.toByteArray()))
                    })
                }
                when {
                    path.endsWith("/csrf_token") -> res(200, """{"ok":true,"token":"tok","runtime_id":"rt"}""")
                    path.endsWith("/message_async") -> {
                        val o = Json.parseToJsonElement(String(body)) as JsonObject
                        received = o["text"]!!.jsonPrimitive.content
                        res(200, """{"message":"Message received.","context":"c1"}""")
                    }
                    path.endsWith("/poll") -> {
                        polls++
                        val running = polls < 2
                        val content = if (polls < 2) "Hello!" else ReplyFilterTests.LIVE_DUMP
                        val escapedUser = (received ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        val escapedAns = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        res(200, """{"log_guid":"g1","log_version":$polls,"log_progress":"${if (running) "Thinking…" else "Waiting for input"}","contexts":[{"id":"c1","running":$running}],"logs":[{"no":0,"type":"user","content":"$escapedUser"},{"no":1,"type":"response","content":"$escapedAns"}]}""")
                    }
                    else -> res(404, """{"error":"no"}""")
                }
            }.start()
        })
        val turn = A0Chat(mux).ask("hello", timeoutMs = 5_000, pollMs = 20)
        val sent = received ?: error("no message_async body")
        assertTrue(sent.contains("hello"))
        assertTrue(sent.contains(PhonePrompt.INSTRUCTION))
        assertEquals("hello", PhonePrompt.unwrap(sent))
        assertTrue(turn.answer!!.contains("Agent Number"))
        assertEquals("Hello!", ReplyFilter.display(turn.answer!!, "hello", showSystemDetails = false))
    }
}
