package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Minimal RFC 7578 parser for tests: returns (headers, body) per part. */
internal fun parseMultipart(contentType: String, body: ByteArray): List<Pair<Map<String, String>, ByteArray>> {
    val boundary = contentType.substringAfter("boundary=")
    val s = String(body, Charsets.ISO_8859_1)
    val delim = "--$boundary"
    assertTrue(s.endsWith("$delim--\r\n"), "closing delimiter")
    val out = ArrayList<Pair<Map<String, String>, ByteArray>>()
    var i = s.indexOf(delim)
    while (true) {
        i += delim.length
        if (s.startsWith("--", i)) break
        assertTrue(s.startsWith("\r\n", i)); i += 2
        val hEnd = s.indexOf("\r\n\r\n", i)
        val headers = s.substring(i, hEnd).split("\r\n").associate {
            it.substringBefore(":").lowercase() to String(it.substringAfter(": ").toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
        val next = s.indexOf("\r\n$delim", hEnd + 4)
        out += headers to s.substring(hEnd + 4, next).toByteArray(Charsets.ISO_8859_1)
        i = next + 2
    }
    return out
}

class MultipartFormTests {
    @Test fun matchesA0WebUiFormData() {
        val png = ByteArray(300) { (it * 7).toByte() }
        val pdf = "%PDF-1.4 hello\r\n--not-a-boundary\r\n".toByteArray()
        val f = MultipartForm.build(
            listOf("text" to "look at these", "context" to "ctx1", "message_id" to "sd-1"),
            listOf(Attachment("photo.png", "image/png", png), Attachment("doc.pdf", "application/pdf", pdf)),
            boundary = "----B0undary")
        assertEquals("multipart/form-data; boundary=----B0undary", f.contentType)
        val parts = parseMultipart(f.contentType, f.body)
        assertEquals(5, parts.size)
        assertEquals("form-data; name=\"text\"", parts[0].first["content-disposition"])
        assertEquals("look at these", String(parts[0].second))
        assertEquals("ctx1", String(parts[1].second))
        assertEquals("sd-1", String(parts[2].second))
        assertEquals("form-data; name=\"attachments\"; filename=\"photo.png\"", parts[3].first["content-disposition"])
        assertEquals("image/png", parts[3].first["content-type"])
        assertTrue(png.contentEquals(parts[3].second))
        assertEquals("form-data; name=\"attachments\"; filename=\"doc.pdf\"", parts[4].first["content-disposition"])
        assertTrue(pdf.contentEquals(parts[4].second))
    }

    @Test fun emptyContextFieldStillSent() {
        // web UI appends context even when it's "" (new chat)
        val f = MultipartForm.build(listOf("text" to "t", "context" to "", "message_id" to "m"),
            listOf(Attachment("a.txt", "", "x".toByteArray())), boundary = "b")
        val parts = parseMultipart(f.contentType, f.body)
        assertEquals("", String(parts[1].second))
        assertEquals("application/octet-stream", parts[3].first["content-type"])
    }

    @Test fun filenameEscapingAndPathStrip() {
        assertEquals("evil.txt", MultipartForm.cleanFilename("../../etc/evil.txt"))
        assertEquals("x.jpg", MultipartForm.cleanFilename("C:\\Users\\me\\x.jpg"))
        assertEquals("file", MultipartForm.cleanFilename("  "))
        assertEquals("a%22b%0D%0Ac", MultipartForm.quoteParam("a\"b\r\nc"))
        val f = MultipartForm.build(emptyList(), listOf(Attachment("résumé \"v2\".pdf", "application/pdf", ByteArray(1))), boundary = "b")
        val cd = parseMultipart(f.contentType, f.body)[0].first["content-disposition"]!!
        assertEquals("form-data; name=\"attachments\"; filename=\"résumé %22v2%22.pdf\"", cd)
    }

    @Test fun boundaryRandomAndNotInBody() {
        val a = MultipartForm.newBoundary(); val b = MultipartForm.newBoundary()
        assertFalse(a == b)
        assertTrue(a.length > 30)
    }

    @Test fun estimateIsUpperBound() {
        val files = listOf(Attachment("p.jpg", "image/jpeg", ByteArray(5000)), Attachment("n.txt", "text/plain", ByteArray(10)))
        val fields = listOf("text" to "hello", "context" to "c", "message_id" to "m")
        val real = MultipartForm.build(fields, files).body.size
        val est = MultipartForm.estimate(fields, files.map { it.name to it.size })
        assertTrue(est >= real, "est=$est real=$real")
        assertTrue(est - real < 1024)
    }
}

class AttachmentRulesTests {
    @Test fun perFileCapIsBridgeBound() {
        assertEquals(8L * 1024 * 1024 - 64 * 1024, AttachmentRules.MAX_PER_FILE)
        assertNull(AttachmentRules.checkAdd(emptyList(), "a.jpg", 3_000_000))
        val big = AttachmentRules.checkAdd(emptyList(), "video.mp4", 21_000_000)
        assertNotNull(big); assertTrue(big!!.contains("video.mp4") && big.contains("8 MiB"))
    }
    @Test fun totalCap() {
        val e = AttachmentRules.checkAdd(listOf(5_000_000L), "b.jpg", 4_000_000)
        assertNotNull(e); assertTrue(e!!.contains("separate message"))
    }
    @Test fun countCap() {
        assertNotNull(AttachmentRules.checkAdd(List(10) { 10L }, "x", 1))
    }
}

class A0StepsTests {
    private fun item(no: Int, type: String, heading: String = "", content: String = "", kv: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject { put("no", no); put("id", "i$no"); put("type", type); put("heading", heading); put("content", content); put("kvps", kv) }

    @Test fun capturesEverythingAfterUserExceptReply() {
        val items = linkedMapOf(
            0 to item(0, "user", content = "old"),
            1 to item(1, "response", content = "old reply"),
            2 to item(2, "user", content = "hi"),
            3 to item(3, "agent", "icon://network_intelligence A0: Thinking"),
            4 to item(4, "tool", "icon://construction Using tool 'code_execution_tool'", "",
                buildJsonObject { put("runtime", "terminal"); put("code", "uname -a"); put("_tool_name", "code_execution_tool") }),
            5 to item(5, "code_exe", "icon://terminal Terminal", "Linux stick 6.8"),
            6 to item(6, "response", "A0: Responding", "Linux stick 6.8 it is."),
        )
        val steps = A0Steps.capture(items, userNo = 2, answerNo = 6)
        assertEquals(listOf(3, 4, 5), steps.map { it.no })
        assertEquals("A0: Thinking", steps[0].heading)
        assertEquals("code_execution_tool", steps[1].tool)
        assertEquals(listOf("runtime" to "terminal", "code" to "uname -a"), steps[1].kvps)
        assertEquals("code_exe", steps[2].type)
        assertEquals("Linux stick 6.8", steps[2].content)
    }

    @Test fun toolNameFromHeadingWhenNoKvp() {
        val s = A0Steps.fromItem(1, item(1, "tool", "icon://construction Using tool 'search_engine:query'"))
        assertEquals("search_engine", s.tool)
    }

    @Test fun truncatedAround4k() {
        val big = "x".repeat(20_000)
        val s = A0Steps.fromItem(1, item(1, "code_exe", "Terminal", big,
            buildJsonObject { put("code", "y".repeat(9000)); putJsonArray("list") { add(JsonPrimitive("a")) } }))
        val total = s.heading.length + s.content.length + s.kvps.sumOf { it.first.length + it.second.length }
        assertTrue(s.truncated)
        assertTrue(total <= A0Steps.MAX_STEP_CHARS + 2, "total=$total")
    }

    @Test fun nonStringKvps() {
        val s = A0Steps.fromItem(1, item(1, "tool", "t", kv = buildJsonObject {
            put("n", 3); putJsonArray("paths") { add(JsonPrimitive("/a")); add(JsonPrimitive("/b")) }
            putJsonObject("o") { put("k", "v") }
        }))
        assertEquals(listOf("n" to "3", "paths" to "/a, /b", "o" to "{\"k\":\"v\"}"), s.kvps)
        assertFalse(s.truncated)
    }

    @Test fun capped() {
        val items = (0..400).associateWith { item(it, if (it == 0) "user" else "agent", "h$it") }
        assertEquals(A0Steps.MAX_STEPS, A0Steps.capture(items, 0).size)
    }
}

/** A0Chat over a fake stick bridge (reassembles app-mux chunks like app_mux_bridge.py). */
class A0ChatAttachmentTests {
    private val json = Json { ignoreUnknownKeys = true }
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun bridge(handler: (Req) -> Pair<Int, String>, frames: MutableList<JsonObject> = ArrayList()): AppMux {
        lateinit var mux: AppMux
        val bufs = HashMap<String, Triple<JsonObject, java.io.ByteArrayOutputStream, Int>>()
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun serve(head: JsonObject, body: ByteArray) {
            val h = (head["headers"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            val (st, txt) = handler(Req(s(head, "method")!!, s(head, "path")!!, h, body))
            Thread {
                mux.dispatch(buildJsonObject {
                    put("t", "http_res"); put("id", s(head, "id")); put("status", st)
                    putJsonObject("headers") { put("content-type", "application/json") }
                    put("body_b64", Bytes.b64u(txt.toByteArray()))
                })
            }.start()
        }
        mux = AppMux({ m ->
            frames += m
            when (s(m, "t")) {
                "http_req" -> if (m["body_len"] != null) bufs[s(m, "id")!!] = Triple(m, java.io.ByteArrayOutputStream(), 0)
                    else serve(m, s(m, "body_b64")?.let { Bytes.unb64u(it) } ?: ByteArray(0))
                "http_chunk" -> {
                    val b = bufs[s(m, "id")!!]!!
                    b.second.write(Bytes.unb64u(s(m, "body_b64")!!))
                    if (b.second.size() > AttachmentRules.BRIDGE_MAX_BODY) {
                        bufs.remove(s(m, "id")!!)
                        Thread { mux.dispatch(buildJsonObject { put("t", "http_res"); put("id", s(m, "id")); put("status", 413); put("body_b64", Bytes.b64u("body too large".toByteArray())) }) }.start()
                    } else if ((m["eof"] as JsonPrimitive).content == "true") { bufs.remove(s(m, "id")!!); serve(b.first, b.second.toByteArray()) }
                }
            }
        }, chunkPaceMs = 0)
        return mux
    }

    private fun a0(onMessage: (Req) -> Unit): (Req) -> Pair<Int, String> {
        var mid = ""; var text = ""
        return { r ->
            when (r.path) {
                "/agent-zero/api/csrf_token" -> 200 to """{"ok":true,"token":"tok"}"""
                "/agent-zero/api/message_async" -> {
                    assertEquals("tok", r.headers["X-CSRF-Token"])
                    onMessage(r)
                    val ct = r.headers["Content-Type"]!!
                    if (ct.startsWith("multipart/form-data")) {
                        val parts = parseMultipart(ct, r.body)
                        fun field(n: String) = parts.first { it.first["content-disposition"] == "form-data; name=\"$n\"" }.second.toString(Charsets.UTF_8)
                        mid = field("message_id"); text = field("text")
                    } else {
                        val o = json.parseToJsonElement(String(r.body)).jsonObject
                        mid = (o["message_id"] as JsonPrimitive).content; text = (o["text"] as JsonPrimitive).content
                    }
                    200 to """{"message":"Message received.","context":"c1"}"""
                }
                "/agent-zero/api/poll" -> 200 to buildJsonObject {
                    put("log_guid", "g"); put("log_version", 4); put("log_progress", "")
                    put("logs", buildJsonArray {
                        // user item content deliberately different: matched by id=message_id
                        add(buildJsonObject { put("no", 0); put("id", mid); put("type", "user"); put("content", "x"); putJsonObject("kvps") { putJsonArray("attachments") { add(JsonPrimitive("photo.jpg")) } } })
                        add(buildJsonObject { put("no", 1); put("id", "t"); put("type", "tool"); put("heading", "icon://construction Using tool 'vision_load'"); put("content", ""); putJsonObject("kvps") { put("_tool_name", "vision_load"); put("paths", "/a0/usr/uploads/photo.jpg") } })
                        add(buildJsonObject { put("no", 2); put("id", "r"); put("type", "response"); put("heading", "A0: Responding"); put("content", "I see a cat") })
                    })
                    put("contexts", buildJsonArray { add(buildJsonObject { put("id", "c1"); put("running", false) }) })
                }.toString()
                else -> 404 to "{}"
            }
        }
    }

    @Test fun uploadsMultipartOverChunkedMuxAndCapturesSteps() {
        var seen: Req? = null
        val frames = ArrayList<JsonObject>()
        val img = ByteArray(200_000) { (it % 251).toByte() }
        val chat = A0Chat(bridge(a0 { seen = it }, frames))
        val turn = chat.ask("what is this?", pollMs = 10, attachments = listOf(Attachment("photo.jpg", "image/jpeg", img)))
        assertEquals("I see a cat", turn.answer)
        val r = seen!!
        assertTrue(r.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
        val parts = parseMultipart(r.headers["Content-Type"]!!, r.body)
        assertEquals(listOf("text", "context", "message_id", "attachments"),
            parts.map { Regex("name=\"([^\"]+)\"").find(it.first["content-disposition"]!!)!!.groupValues[1] })
        val sentText = String(parts[0].second)
        assertTrue(sentText.startsWith("what is this?"), "phone instruction appended like text-only")
        assertTrue(sentText.contains(PhonePrompt.IMAGE_INSTRUCTION), "image send must ask A0 to call vision_load")
        assertFalse(sentText.contains("Reply briefly and conversationally in plain text"))
        assertTrue(img.contentEquals(parts[3].second))
        assertTrue(frames.count { (it["t"] as JsonPrimitive).content == "http_chunk" } >= 5, "body went chunked")
        assertEquals(1, turn.steps.size)
        assertEquals("vision_load", turn.steps[0].tool)
        assertEquals(listOf("paths" to "/a0/usr/uploads/photo.jpg"), turn.steps[0].kvps)
    }

    @Test fun textOnlyStaysJson() {
        var seen: Req? = null
        val turn = A0Chat(bridge(a0 { seen = it })).ask("hi", pollMs = 10)
        assertEquals("application/json", seen!!.headers["Content-Type"])
        assertEquals("I see a cat", turn.answer)
    }

    @Test fun overBridgeLimitRefusedBeforeSending() {
        var calls = 0
        val e = assertThrows(A0Chat.A0Error::class.java) {
            A0Chat(bridge(a0 { calls++ })).ask("x", pollMs = 10,
                attachments = listOf(Attachment("big.bin", "application/octet-stream", ByteArray(8 * 1024 * 1024))))
        }
        assertEquals(413, e.status)
        assertEquals(0, calls)
    }

    @Test fun pacingSleepsAfterFirstChunks() {
        val frames = ArrayList<JsonObject>()
        val mux = AppMux({ frames += it }, chunkPaceMs = 5)
        val t0 = System.nanoTime()
        Thread { try { mux.http("POST", "/agent-zero/x", emptyMap(), ByteArray(AppMux.CHUNK * 26), timeoutMs = 50) } catch (_: Exception) {} }
            .apply { start(); join() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(27, frames.size) // head + 26 chunks
        assertTrue(ms >= (26 - AppMux.PACE_AFTER) * 5, "paced ms=$ms")
    }
}
