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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThinkingStatusTests {
    @Test fun timerAndSlowHint() {
        assertEquals("Thinking… 0s", ThinkingStatus.timerLabel(0))
        assertEquals("Thinking… 12s", ThinkingStatus.timerLabel(12_000))
        assertEquals("Thinking… 12s", ThinkingStatus.timerLabel(12_999))
        assertEquals("Thinking… 60s", ThinkingStatus.timerLabel(60_000))
        val early = ThinkingStatus.lines(12_000, null)
        assertEquals(listOf("Thinking… 12s"), early)
        assertFalse(early.any { it.contains("slow") })
        val slow = ThinkingStatus.lines(60_000, null)
        assertEquals("Thinking… 60s", slow[0])
        assertEquals(ThinkingStatus.SLOW_HINT, slow[1])
        val withProg = ThinkingStatus.display(75_000, "Using a tool")
        assertTrue(withProg.startsWith("Thinking… 75s"))
        assertTrue(withProg.contains(ThinkingStatus.SLOW_HINT))
        assertTrue(withProg.contains("Using a tool"))
    }

    @Test fun sanitizeProgress() {
        assertNull(ThinkingStatus.sanitize(null))
        assertNull(ThinkingStatus.sanitize(""))
        assertNull(ThinkingStatus.sanitize("0"))
        assertNull(ThinkingStatus.sanitize("Waiting for input"))
        assertNull(ThinkingStatus.sanitize("Thinking…"))
        assertEquals("Searching", ThinkingStatus.sanitize("A0: Searching the web"))
        assertEquals("Using a tool", ThinkingStatus.sanitize("Agent 0: Using tool `search_engine`"))
        assertEquals("Running code", ThinkingStatus.sanitize("Running Python code"))
        assertEquals("Working step 3", ThinkingStatus.sanitize("  Working step 3  "))
        assertNull(ThinkingStatus.sanitize("Current Date and Time: 2026-11-19"))
        assertNull(ThinkingStatus.sanitize("LLM: llama_cpp//opt/stickllm/models/appagent/qwen2.5-7b.gguf"))
        val long = "x".repeat(200)
        val s = ThinkingStatus.sanitize(long)!!
        assertTrue(s.length <= 80)
        assertTrue(s.endsWith("…"))
        assertEquals("Searching", ThinkingStatus.sanitize("Searching\nsecond line ignored"))
    }

    @Test fun fromPollPrefersHeading() {
        assertEquals(
            "Using a tool",
            ThinkingStatus.fromPoll("Thinking…", listOf("A0: Thinking", "Using tool shell"))
        )
        assertEquals("Working step 2", ThinkingStatus.fromPoll("Working step 2", emptyList()))
        assertNull(ThinkingStatus.fromPoll("Waiting for input", emptyList()))
        assertNull(ThinkingStatus.fromPoll("0", listOf("")))
    }

    @Test fun headingsFromLogsSkipUserAndResponse() {
        val items = listOf(
            mapOf("type" to "user", "heading" to "You"),
            mapOf("type" to "agent", "heading" to "A0: Using tool search_engine"),
            mapOf("type" to "response", "heading" to "A0: Responding"),
            mapOf("type" to "info", "heading" to "Searching the knowledge base"),
        )
        val heads = ThinkingStatus.headingsFromLogs(items)
        assertEquals(
            listOf("A0: Using tool search_engine", "Searching the knowledge base"),
            heads
        )
        assertEquals("Searching", ThinkingStatus.fromPoll("Thinking…", heads))
    }
}

class A0ChatProgressTests {
    @Test fun delayedPollsEmitSanitizedProgressBeforeAnswer() {
        lateinit var mux: AppMux
        var polls = 0
        val seen = ArrayList<String>()
        mux = AppMux({ m ->
            if (m["t"]!!.jsonPrimitive.content != "http_req") return@AppMux
            val id = m["id"]!!.jsonPrimitive.content
            val path = m["path"]!!.jsonPrimitive.content
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
                    path.endsWith("/message_async") -> res(200, """{"message":"Message received.","context":"c1"}""")
                    path.endsWith("/poll") -> {
                        polls++
                        val running = polls < 4
                        val progress = when (polls) {
                            1 -> "Thinking…"
                            2 -> "Thinking…"
                            3 -> "Working step 1"
                            else -> "Waiting for input"
                        }
                        val heading = when (polls) {
                            1, 2 -> "A0: Thinking"
                            3 -> "Using tool search_engine"
                            else -> "A0: Responding"
                        }
                        val typ = if (polls < 4) "agent" else "response"
                        val content = if (polls < 4) "" else "Hello from a delayed reply"
                        val user = "hello"
                        res(200, """{"log_guid":"g1","log_version":$polls,"log_progress":"$progress","contexts":[{"id":"c1","running":$running}],"logs":[{"no":0,"type":"user","content":"$user"},{"no":1,"type":"$typ","heading":"$heading","content":"$content"}]}""")
                    }
                    else -> res(404, """{"error":"no"}""")
                }
            }.start()
        })
        val started = System.currentTimeMillis()
        val turn = A0Chat(mux).ask("hello", timeoutMs = 5_000, pollMs = 30) { seen.add(it) }
        val elapsed = System.currentTimeMillis() - started
        assertEquals("Hello from a delayed reply", turn.answer)
        assertTrue(seen.isNotEmpty(), "progress should be visible while the reply is delayed")
        assertTrue(seen.any { it == "Using a tool" || it == "Working step 1" }, seen.toString())
        assertFalse(seen.any { it.contains("gguf") || it.contains("Waiting for input") })
        val mid = ThinkingStatus.display(elapsed.coerceAtLeast(30), seen.lastOrNull())
        assertTrue(mid.startsWith("Thinking…"), mid)
        assertTrue(polls >= 4)
    }
}
