package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent Zero v2.15 semantics for the queue/chat endpoints (ba9b67df):
 * message_async on a running context = intervention (agent.py L269-286), A0's own queue
 * (helpers/message_queue.py) auto-sent after a normal finish only (_50_process_queue.py),
 * Stop skips it (api/stop.py kill_process), upload.py "file" parts, chat_create, poll contexts.
 */
class FakeA0 {
    private val json = Json { ignoreUnknownKeys = true }
    class Ctx(val id: String, val no: Int, var name: String? = null) {
        val logs = ArrayList<JsonObject>(); val updates = ArrayList<Int>()
        @Volatile var running = false; @Volatile var stop = false
        val queue = ArrayList<Triple<String, String, List<String>>>() // id, text, attachment paths
        var seq = 0; val guid = "g-$id"
        val createdAt = "2026-11-21T09:%02d:00".format(no)
    }
    val ctxs = LinkedHashMap<String, Ctx>()
    val uploads = HashMap<String, ByteArray>()
    val interventions = AtomicInteger(); val queueAdds = AtomicInteger(); val queueSends = AtomicInteger()
    val messageAsync = AtomicInteger(); val uploadsCalls = AtomicInteger()
    /** Agent Zero not up: the StickLLM gateway answers 503 agent_not_running (stickllm-gateway _agent_embed_proxy). */
    @Volatile var down = false
    /** false = A0's model gate: message_async → model_not_configured (plugins/_model_config _10_check_chat_model.py). */
    @Volatile var modelConfigured = true
    /** What /api/agent/status says while [down] (the real stick says "starting" even when nobody started it). */
    @Volatile var downStatus = """{"ok":true,"state":"starting","running":false,"message":"Agent Zero is starting..."}"""
    private var nextNo = 1

    @Synchronized fun newCtx(id: String = "c" + nextNo, name: String? = null): Ctx =
        Ctx(id, nextNo++, name).also { ctxs[id] = it }

    @Synchronized fun add(c: Ctx, type: String, content: String, id: String = Protocol.newId(), heading: String = "",
                          attachments: List<String> = emptyList(), kvps: JsonObject? = null): Int {
        val no = c.logs.size
        c.logs.add(buildJsonObject {
            put("no", no); put("id", id); put("type", type); put("heading", heading); put("content", content)
            put("kvps", kvps ?: buildJsonObject { putJsonArray("attachments") { attachments.forEach { add(JsonPrimitive(it)) } } })
        })
        c.updates.add(no); return no
    }
    @Synchronized fun upd(c: Ctx, no: Int, content: String) {
        c.logs[no] = JsonObject(c.logs[no] + ("content" to JsonPrimitive(content))); c.updates.add(no)
    }

    /** helpers/message_queue.py send_message → context.communicate → new task. */
    private fun communicate(c: Ctx, text: String, paths: List<String>) {
        if (c.running) { interventions.incrementAndGet(); return }
        c.running = true; c.stop = false
        Thread { agent(c, text, paths) }.start()
    }

    private fun agent(c: Ctx, text: String, paths: List<String>) {
        val user = PhonePrompt.unwrap(text)
        add(c, "agent", "", heading = "A0: Thinking")
        var stopped = false
        when {
            user.startsWith("SLOW") -> { for (i in 0 until 100) { if (c.stop) { stopped = true; break }; Thread.sleep(50) } }
            user.startsWith("WAIT") -> Thread.sleep(600)
            else -> Thread.sleep(80)
        }
        if (user.startsWith("NOMODEL")) {
            // StickLLM's llama relay with no llama-server: a normal response with this text (stickllm_agent.py no_model_reply).
            add(c, "response", "No AI model is loaded on StickLLM right now, so I can't answer yet. Open Models on the StickLLM home screen, load a model, wait until it shows Running, then send your message again.", heading = "No model loaded")
            c.running = false
            return
        }
        if (user.startsWith("ERR")) {
            add(c, "error", "LLM call failed")
            c.running = false
            return // the chain ended with an error: no response item
        }
        if (stopped) {
            add(c, "info", "Agent process stopped.")
            c.running = false
            return // kill_process: no process_chain_end → A0 does not auto-send its queue
        }
        if (paths.isNotEmpty()) add(c, "tool", "", heading = "icon://construction Using tool 'vision_load'",
            kvps = buildJsonObject { put("_tool_name", "vision_load"); put("paths", paths.joinToString()) })
        val files = paths.joinToString { p -> val n = p.substringAfterLast('/'); "$n(${uploads[n]?.size ?: -1})" }
        add(c, "response", "reply to: $user" + (if (files.isNotEmpty()) " | files: $files" else ""), heading = "A0: Responding")
        c.running = false
        // process_chain_end → _50_process_queue: wait until not running, then send_next
        Thread {
            Thread.sleep(30)
            val item = synchronized(this) { if (!c.running && c.queue.isNotEmpty()) c.queue.removeAt(0) else null }
            if (item != null) sendItem(c, item)
        }.start()
    }

    /** A message typed in A0's web UI (not from this phone). */
    fun startFromWeb(c: Ctx, text: String) {
        add(c, "user", text, id = java.util.UUID.randomUUID().toString())
        communicate(c, text, emptyList())
    }

    private fun sendItem(c: Ctx, item: Triple<String, String, List<String>>) {
        add(c, "user", item.second, id = java.util.UUID.randomUUID().toString(), attachments = item.third.map { it.substringAfterLast('/') })
        communicate(c, item.second, item.third)
    }

    private fun output(c: Ctx) = buildJsonObject {
        put("id", c.id); put("no", c.no); c.name?.let { put("name", it) }
        put("created_at", c.createdAt); put("last_message", c.createdAt); put("running", c.running)
        putJsonArray("message_queue") {
            synchronized(this@FakeA0) { c.queue.toList() }.forEachIndexed { i, q ->
                add(buildJsonObject { put("id", q.first); put("seq", i + 1); put("text", q.second.take(100))
                    putJsonArray("attachments") { q.third.forEach { add(JsonPrimitive(it.substringAfterLast('/'))) } } })
            }
        }
    }

    fun handler(r: A0ChatAttachmentTests.Req): Pair<Int, String> {
        if (r.path == "/api/agent/status") return 200 to (if (down) downStatus
            else """{"ok":true,"state":"running","running":true,"message":"Agent"}""")
        if (down && r.path.startsWith("/agent-zero")) return 503 to """{"ok":false,"error":"agent_not_running","message":"Agent Zero is not running yet."}"""
        if (r.path == "/agent-zero/api/csrf_token") return 200 to """{"ok":true,"token":"tok"}"""
        if (r.headers["X-CSRF-Token"] != "tok") return 403 to "CSRF token missing or invalid"
        val ct = r.headers["Content-Type"] ?: ""
        if (ct.startsWith("multipart/form-data")) {
            val parts = parseMultipart(ct, r.body)
            fun name(h: Map<String, String>) = Regex("name=\"([^\"]+)\"").find(h["content-disposition"]!!)!!.groupValues[1]
            fun fname(h: Map<String, String>) = Regex("filename=\"([^\"]+)\"").find(h["content-disposition"]!!)!!.groupValues[1]
            return when (r.path) {
                "/agent-zero/api/upload" -> {
                    uploadsCalls.incrementAndGet()
                    val names = parts.filter { name(it.first) == "file" }.map { (h, b) -> fname(h).also { uploads[it] = b } }
                    200 to buildJsonObject { putJsonArray("filenames") { names.forEach { add(JsonPrimitive(it)) } } }.toString()
                }
                "/agent-zero/api/message_async" -> {
                    messageAsync.incrementAndGet()
                    fun field(n: String) = parts.first { name(it.first) == n }.second.toString(Charsets.UTF_8)
                    val files = parts.filter { name(it.first) == "attachments" }.map { (h, b) -> fname(h).also { uploads[it] = b } }
                    val c = ctxs[field("context")] ?: newCtx()
                    add(c, "user", field("text"), id = field("message_id"), attachments = files)
                    communicate(c, field("text"), files.map { "/a0/usr/uploads/$it" })
                    200 to """{"message":"Message received.","context":"${c.id}"}"""
                }
                else -> 404 to "{}"
            }
        }
        val b = if (r.body.isEmpty()) JsonObject(emptyMap()) else json.parseToJsonElement(String(r.body)).jsonObject
        fun s(k: String) = (b[k] as? JsonPrimitive)?.contentOrNull
        return when (r.path) {
            "/agent-zero/api/message_async" -> {
                messageAsync.incrementAndGet()
                val c = ctxs[s("context") ?: ""] ?: newCtx()
                if (!modelConfigured) return 200 to """{"message":"Model not configured.","model_not_configured":true,"context":"${c.id}"}"""
                add(c, "user", s("text")!!, id = s("message_id")!!)
                communicate(c, s("text")!!, emptyList())
                200 to """{"message":"Message received.","context":"${c.id}"}"""
            }
            "/agent-zero/api/chat_create" -> { val c = newCtx(); 200 to """{"ok":true,"ctxid":"${c.id}","message":"Context created."}""" }
            "/agent-zero/api/poll" -> {
                val c = ctxs[s("context") ?: ""]
                val from = (b["log_from"] as? JsonPrimitive)?.contentOrNull?.toInt() ?: 0
                200 to synchronized(this) { buildJsonObject {
                    put("context", c?.id ?: "")
                    put("contexts", buildJsonArray { ctxs.values.sortedByDescending { it.createdAt }.forEach { add(output(it)) } })
                    if (c != null) {
                        val seen = HashSet<Int>()
                        put("logs", buildJsonArray { c.updates.drop(from).forEach { if (seen.add(it)) add(c.logs[it]) } })
                        put("log_version", c.updates.size); put("log_guid", c.guid)
                        put("log_progress", if (c.running) "Thinking…" else "Waiting for input")
                    } else { put("logs", buildJsonArray {}); put("log_version", 0); put("log_guid", ""); put("log_progress", 0) }
                } }.toString()
            }
            "/agent-zero/api/stop" -> { ctxs[s("context")]?.stop = true; 200 to """{"message":"Agent process stopped."}""" }
            "/agent-zero/api/message_queue_add" -> {
                val c = ctxs[s("context")] ?: return 404 to "Context not found"
                queueAdds.incrementAndGet()
                val att = (b["attachments"] as? JsonArray)?.map { "/a0/usr/uploads/" + (it as JsonPrimitive).content } ?: emptyList()
                synchronized(this) { c.queue.add(Triple(s("item_id") ?: Protocol.newId(), s("text") ?: "", att)) }
                200 to """{"ok":true,"item_id":"${s("item_id")}","queue_length":${c.queue.size}}"""
            }
            "/agent-zero/api/message_queue_remove" -> {
                val c = ctxs[s("context")] ?: return 404 to "Context not found"
                val id = s("item_id")
                synchronized(this) { if (id == null) c.queue.clear() else c.queue.removeAll { it.first == id } }
                200 to """{"ok":true,"remaining":${c.queue.size}}"""
            }
            "/agent-zero/api/message_queue_send" -> {
                val c = ctxs[s("context")] ?: return 404 to "Context not found"
                queueSends.incrementAndGet()
                val item = synchronized(this) { if (c.queue.isEmpty()) null else c.queue.removeAt(0) }
                    ?: return 200 to """{"ok":true,"message":"Queue empty"}"""
                sendItem(c, item)
                200 to """{"ok":true,"sent_item_id":"${item.first}"}"""
            }
            else -> 404 to "{}"
        }
    }
}

class QueueAndChatsTests {
    /** Fake stick bridge (same as A0ChatAttachmentTests); [down] makes the link fail like a dropped transport. */
    class Link(val a0: FakeA0) {
        @Volatile var down = false
        fun chat(): A0Chat {
            lateinit var mux: AppMux
            val bufs = HashMap<String, Pair<JsonObject, java.io.ByteArrayOutputStream>>()
            fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun serve(head: JsonObject, body: ByteArray) {
                val h = (head["headers"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
                val (st, txt) = a0.handler(A0ChatAttachmentTests.Req(s(head, "method")!!, s(head, "path")!!, h, body))
                Thread {
                    mux.dispatch(buildJsonObject {
                        put("t", "http_res"); put("id", s(head, "id")); put("status", st)
                        putJsonObject("headers") { put("content-type", "application/json") }
                        put("body_b64", Bytes.b64u(txt.toByteArray()))
                    })
                }.start()
            }
            mux = AppMux({ m ->
                if (down) throw AppMux.MuxError("transport: link down")
                when (s(m, "t")) {
                    "http_req" -> if (m["body_len"] != null) bufs[s(m, "id")!!] = m to java.io.ByteArrayOutputStream()
                        else serve(m, s(m, "body_b64")?.let { Bytes.unb64u(it) } ?: ByteArray(0))
                    "http_chunk" -> {
                        val b = bufs[s(m, "id")!!]!!
                        b.second.write(Bytes.unb64u(s(m, "body_b64")!!))
                        if ((m["eof"] as JsonPrimitive).content == "true") { bufs.remove(s(m, "id")!!); serve(b.first, b.second.toByteArray()) }
                    }
                }
            }, chunkPaceMs = 0)
            return A0Chat(mux)
        }
    }

    class Rec : A0Conversation.Listener {
        val starts = CopyOnWriteArrayList<String>(); val done = CopyOnWriteArrayList<Pair<String, A0Chat.Turn>>()
        val queues = CopyOnWriteArrayList<List<A0Conversation.Queued>>(); val errors = CopyOnWriteArrayList<String>()
        val interrupted = CopyOnWriteArrayList<String>(); @Volatile var busy = false
        val problems = CopyOnWriteArrayList<String?>()
        override fun onStickProblem(message: String?) { problems += message }
        override fun onQueue(items: List<A0Conversation.Queued>) { queues += items }
        override fun onTurnStart(turnId: String, itemId: String?, text: String, attachments: List<Attachment>, attachmentNames: List<String>) { starts += text }
        override fun onTurnDone(turnId: String, userText: String, turn: A0Chat.Turn) { done += userText to turn }
        override fun onTurnInterrupted(turnId: String, why: String) { interrupted += why }
        override fun onBusy(busy: Boolean) { this.busy = busy }
        override fun onError(message: String) { errors += message }
    }

    private fun waitFor(ms: Long = 15_000, what: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what"); Thread.sleep(10) }
    }
    private fun conv(rec: Rec) = A0Conversation(rec, pollMs = 20, kickAfterMs = 200)

    @Test fun twoQueuedWhileReplyRunsArriveInOrderViaA0Queue() {
        val a0 = FakeA0(); val link = Link(a0); val rec = Rec()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("WAIT first")
        waitFor(what = "busy") { rec.busy }
        cv.submit("Q1 second")
        cv.submit("Q2 third", listOf(Attachment("cat.jpg", "image/jpeg", ByteArray(5000) { 7 })))
        waitFor(what = "3 replies") { rec.done.size == 3 }
        assertEquals(listOf("WAIT first", "Q1 second", "Q2 third"), rec.done.map { it.first })
        assertEquals(listOf("reply to: WAIT first", "reply to: Q1 second", "reply to: Q2 third | files: cat.jpg(5000)"),
            rec.done.map { it.second.answer })
        assertEquals(listOf("WAIT first", "Q1 second", "Q2 third"), rec.starts)
        assertEquals(0, a0.interventions.get(), "never message_async into a running reply")
        assertEquals(1, a0.messageAsync.get()); assertEquals(2, a0.queueAdds.get()); assertEquals(1, a0.uploadsCalls.get())
        waitFor(what = "idle") { !rec.busy }
        assertTrue(rec.queues.last().isEmpty())
        cv.close()
    }

    @Test fun removeQueuedBeforeItSends() {
        val a0 = FakeA0(); val link = Link(a0); val rec = Rec()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("WAIT first"); waitFor { rec.busy }
        val q1 = cv.submit("Q1 drop me"); cv.submit("Q2 keep")
        waitFor(what = "on A0 queue") { cv.queued().count { it.onServer } == 2 }
        cv.remove(q1)
        waitFor(what = "2 replies") { rec.done.size == 2 }
        Thread.sleep(300)
        assertEquals(listOf("WAIT first", "Q2 keep"), rec.done.map { it.first })
        assertTrue(a0.ctxs.values.first().queue.isEmpty())
        cv.close()
    }

    @Test fun stopKeepsQueueAndPhoneSendsNextBecauseA0DoesNotAfterStop() {
        val a0 = FakeA0(); val link = Link(a0); val rec = Rec()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("SLOW task"); waitFor { rec.busy }
        cv.submit("Q1 after stop")
        waitFor { cv.queued().any { it.onServer } }
        cv.stop(keepQueue = true)
        waitFor(what = "2 turns") { rec.done.size == 2 }
        assertTrue(rec.done[0].second.stopped)
        assertEquals("reply to: Q1 after stop", rec.done[1].second.answer)
        assertTrue(a0.queueSends.get() >= 1, "phone kicked A0's queue (message_queue_send)")
        cv.close()
    }

    @Test fun stopAndClearQueue() {
        val a0 = FakeA0(); val link = Link(a0); val rec = Rec()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("SLOW task"); waitFor { rec.busy }
        cv.submit("Q1 never")
        waitFor { cv.queued().any { it.onServer } }
        cv.stop(keepQueue = false)
        waitFor(what = "stopped") { rec.done.size == 1 }
        Thread.sleep(800)
        assertEquals(1, rec.done.size); assertTrue(rec.done[0].second.stopped)
        assertTrue(a0.ctxs.values.first().queue.isEmpty()); assertTrue(cv.queued().isEmpty())
        cv.close()
    }

    @Test fun queueSurvivesLinkDropAndResume() {
        val a0 = FakeA0(); val link = Link(a0); val rec = Rec()
        val cv = conv(rec); cv.setChat(link.chat())
        cv.submit("WAIT first"); waitFor { rec.busy }
        link.down = true                       // transport drops while a reply runs
        cv.submit("Q1 queued while down")
        waitFor(what = "interrupted") { rec.interrupted.isNotEmpty() }
        assertEquals(listOf("Q1 queued while down"), cv.queued().map { it.text })
        Thread.sleep(700)                      // A0 finishes the first reply meanwhile
        link.down = false
        cv.setChat(link.chat())                // resume: new mux, same A0 chat
        waitFor(what = "both replies") { rec.done.size == 2 }
        assertEquals(listOf("WAIT first", "Q1 queued while down"), rec.done.map { it.first })
        assertEquals("reply to: WAIT first", rec.done[0].second.answer)
        assertEquals(1, a0.ctxs.size, "continued in the same A0 chat")
        cv.close()
    }

    @Test fun chatListHistoryNewChatAndSwitch() {
        val a0 = FakeA0()
        val old = a0.newCtx(name = "Garden plans")
        a0.add(old, "user", PhonePrompt.wrap("what to plant?"), attachments = listOf("bed.jpg"))
        a0.add(old, "agent", "", heading = "A0: Thinking")
        a0.add(old, "response", "Tomatoes.")
        a0.add(old, "user", "and when?")
        a0.add(old, "response", "In May.")
        val link = Link(a0); val chat = link.chat()
        val chats = chat.listChats()
        assertEquals(listOf("Garden plans"), chats.map { it.label })
        val h = chat.history(old.id)
        assertEquals(listOf("what to plant?", "and when?"), h.map { it.userText })
        assertEquals(listOf("Tomatoes.", "In May."), h.map { it.answer })
        assertEquals(listOf("bed.jpg"), h[0].attachments)
        assertEquals(1, h[0].steps.size)
        val id = chat.createChat()
        assertEquals(setOf(old.id, id), chat.listChats().map { it.id }.toSet())
        assertEquals("Chat #2", chat.listChats().first { it.id == id }.label)

        val rec = Rec(); val cv = conv(rec); cv.setChat(chat)
        cv.switchTo(old.id)
        cv.submit("third question")
        waitFor { rec.done.size == 1 }
        assertEquals(old.id, rec.done[0].second.context)
        assertEquals(3, chat.history(old.id).size)
        cv.close()
    }

    @Test fun switchingToARunningChatFollowsItsReply() {
        val a0 = FakeA0(); val link = Link(a0)
        val other = link.chat()
        other.start("WAIT from the web UI", emptyList(), "web-1")
        val rec = Rec(); val cv = conv(rec); cv.setChat(link.chat())
        cv.switchTo(other.context)
        waitFor(what = "adopted reply") { rec.done.size == 1 }
        assertEquals("WAIT from the web UI", rec.done[0].first)
        assertEquals("reply to: WAIT from the web UI", rec.done[0].second.answer)
        cv.close()
    }

    @Test fun awaitTurnStopsAtTheNextUserItem() {
        val a0 = FakeA0(); val c = a0.newCtx()
        a0.add(c, "user", "one", id = "m1"); a0.add(c, "user", "two"); a0.add(c, "response", "answer to two")
        val chat = Link(a0).chat(); chat.attach(c.id)
        val t = chat.awaitTurn("m1", "one", -1, timeoutMs = 2000, pollMs = 10)!!
        assertNull(t.answer, "turn one ended without a reply; two's reply is not taken")
        assertTrue(t.stopped)
        assertEquals(0, t.endNo)
        assertFalse(a0.ctxs[c.id]!!.running)
    }

    @Test fun directSubmitAnnouncesBeforeMessageAsyncReturns() {
        val hold = java.util.concurrent.CountDownLatch(1)
        val a0 = FakeA0()
        val rec = Rec()
        val cv = conv(rec)
        lateinit var mux: AppMux
        val bufs = HashMap<String, Pair<kotlinx.serialization.json.JsonObject, java.io.ByteArrayOutputStream>>()
        fun s(o: kotlinx.serialization.json.JsonObject, k: String) =
            (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        fun serve(head: kotlinx.serialization.json.JsonObject, body: ByteArray) {
            val path = s(head, "path")!!
            Thread {
                if (path.endsWith("message_async")) hold.await(5, java.util.concurrent.TimeUnit.SECONDS)
                val h = (head["headers"] as kotlinx.serialization.json.JsonObject).mapValues {
                    (it.value as kotlinx.serialization.json.JsonPrimitive).content
                }
                val (st, txt) = a0.handler(A0ChatAttachmentTests.Req(s(head, "method")!!, path, h, body))
                mux.dispatch(kotlinx.serialization.json.buildJsonObject {
                    put("t", "http_res"); put("id", s(head, "id")); put("status", st)
                    putJsonObject("headers") { put("content-type", "application/json") }
                    put("body_b64", Bytes.b64u(txt.toByteArray()))
                })
            }.start()
        }
        mux = AppMux({ m ->
            when (s(m, "t")) {
                "http_req" -> if (m["body_len"] != null) bufs[s(m, "id")!!] = m to java.io.ByteArrayOutputStream()
                    else serve(m, s(m, "body_b64")?.let { Bytes.unb64u(it) } ?: ByteArray(0))
                "http_chunk" -> {
                    val b = bufs[s(m, "id")!!]!!
                    b.second.write(Bytes.unb64u(s(m, "body_b64")!!))
                    if ((m["eof"] as kotlinx.serialization.json.JsonPrimitive).content == "true") {
                        bufs.remove(s(m, "id")!!); serve(b.first, b.second.toByteArray())
                    }
                }
            }
        }, chunkPaceMs = 0)
        cv.setChat(A0Chat(mux))
        cv.submit("hello now")
        assertEquals(listOf("hello now"), rec.starts)
        assertFalse(rec.busy) // A0 has not accepted the turn yet
        assertEquals(0, a0.messageAsync.get())
        hold.countDown()
        waitFor(what = "reply") { rec.done.size == 1 }
        assertEquals("reply to: hello now", rec.done[0].second.answer)
    }

}
