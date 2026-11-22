package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Typed chat with Agent Zero through the Method C app-mux, using the same
 * A0 web-UI endpoints the browser join page drives (lab _e2e_phase1_app_mux.py):
 *
 *   GET  /agent-zero/api/csrf_token                      → {ok, token, runtime_id}
 *   POST /agent-zero/api/message_async {text, context, message_id}  (X-CSRF-Token)
 *        or, with attachments, multipart/form-data text/context/message_id + N×attachments
 *        (A0 api/message.py L30-50; web UI webui/index.js L142-155, both @ ba9b67df)
 *   POST /agent-zero/api/poll {context, log_from, notifications_from, timezone}
 *   POST /agent-zero/api/stop {context}                  (cancel)
 *
 *
 * 0.3.4, same endpoints as the A0 v2.15 web UI:
 *   POST /agent-zero/api/upload            multipart N x "file"  -> {filenames}     (api/upload.py)
 *   POST /agent-zero/api/message_queue_add {context, text, attachments:[filenames], item_id}
 *   POST /agent-zero/api/message_queue_remove {context, item_id?}   (no item_id = clear)
 *   POST /agent-zero/api/message_queue_send {context}               (send next now)
 *   POST /agent-zero/api/chat_create {current_context} -> {ok, ctxid}
 *   poll "contexts" = every chat (AgentContext.output(): id, name, no, created_at,
 *   last_message, running, message_queue); poll {context, log_from:0} = that chat's log.
 *
 * The stick bridge adds the loopback Bearer and keeps A0's cookies; the phone
 * never sees either. poll is incremental: log_from = last log_version, items
 * merged by "no"; a new log_guid resets.
 */
class A0Chat(private val mux: AppMux, private val log: (String) -> Unit = {}, private val timezone: String = "UTC") {
    data class Turn(
        val answer: String?,
        val context: String,
        val progress: List<String>,
        val stopped: Boolean,
        /** Every A0 log item after the user's message except the reply itself. */
        val steps: List<A0Step> = emptyList(),
        /** Log "no" of the user item, the reply, and the last item of this turn (before the next user item). */
        val userNo: Int = -1,
        val answerNo: Int? = null,
        val endNo: Int = -1,
        /** No model on the stick: A0's model_not_configured gate, or the stick's "no model loaded" reply. */
        val noModel: Boolean = false,
    )

    /** One entry of A0's server-side message queue (helpers/message_queue.py _sync_output). */
    data class QueueItem(val id: String, val seq: Int, val text: String, val attachments: List<String>)

    /** One chat as A0 lists it (agent.py AgentContext.output()). */
    data class ChatInfo(
        val id: String, val name: String?, val no: Int, val createdAt: String?, val lastMessage: String?,
        val running: Boolean, val queue: List<QueueItem>,
    ) {
        /** Same label as the web UI (chats-store.js displayName). */
        val label: String get() = name?.takeIf { it.isNotBlank() } ?: "Chat #$no"
    }

    class Snapshot(val chats: List<ChatInfo>, val items: Map<Int, JsonObject>, val logVersion: Int,
                   val guid: String?, val progress: String) {
        val maxNo: Int get() = items.keys.maxOrNull() ?: -1
    }

    /** One user message and its reply, rebuilt from a chat's log. */
    data class HistoryTurn(val userNo: Int, val userText: String, val attachments: List<String>,
                           val answer: String?, val steps: List<A0Step>, val stopped: Boolean)

    /**
     * Agent Zero (or the stick's gateway in front of it) answered, but not with what we asked for.
     * Never a link problem: those are [AppMux.MuxError]. [code] is the short "error" field of a JSON
     * error body (gateway: "agent_not_running", "Agent is not running", "pair_required"; OpenAI-style
     * bodies: error.type), never message text.
     */
    class A0Error(val status: Int, msg: String, val code: String? = null) : RuntimeException(msg) {
        /** The stick's gateway says Agent Zero itself is not up (stickllm-gateway _agent_embed_proxy). */
        val agentDown: Boolean get() = status == AGENT_DOWN ||
            (status in 502..504 && (code == null || code == "agent_not_running" || code.contains("not running", ignoreCase = true)))
        /** A0's own gate: no chat model configured (api/message.py respond, model_not_configured). */
        val noModel: Boolean get() = status == NO_MODEL
    }
    companion object {
        /** [A0Error.status] when a turn got no answer within its timeout (not a link error). */
        const val TIMEOUT = -1
        /** [A0Error.status]: message_async said model_not_configured (no chat model set on A0). */
        const val NO_MODEL = -2
        /** [A0Error.status]: the gateway's /api/agent/status says A0 isn't running. */
        const val AGENT_DOWN = -3
        /** Start of the reply StickLLM's llama relay gives when llama-server isn't listening
         *  (stickllm_agent.py NO_MODEL_MESSAGE / no_model_reply, 0.7.x). */
        const val STICK_NO_MODEL_PREFIX = "No AI model is loaded on StickLLM"

        fun isNoModelReply(answer: String?): Boolean = answer?.trimStart()?.startsWith(STICK_NO_MODEL_PREFIX) == true

        private val errJson = Json { ignoreUnknownKeys = true }
        /** Short error code from a JSON error body, if any ("error": "x" or "error": {"type": "x"}). */
        fun errorCode(text: String): String? = try {
            val o = errJson.parseToJsonElement(text).jsonObject
            when (val e = o["error"]) {
                is JsonPrimitive -> e.contentOrNull
                is JsonObject -> (e["type"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }?.take(60)
        } catch (_: Exception) { null }
    }

    private fun err(r: AppMux.HttpResponse, msg: String) = A0Error(r.status, msg, errorCode(r.text))

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var context: String = ""
        private set
    private var csrf: String? = null
    @Volatile private var cancel = false

    private fun base(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "X-StickLLM-Client" to "ui",
    )

    private fun token(force: Boolean = false): String {
        csrf?.let { if (!force) return it }
        val r = mux.http("GET", "/agent-zero/api/csrf_token", base())
        if (r.status != 200) throw err(r, "csrf_token HTTP ${r.status}")
        val o = json.parseToJsonElement(r.text).jsonObject
        val t = (o["token"] as? JsonPrimitive)?.contentOrNull ?: (o["csrf_token"] as? JsonPrimitive)?.contentOrNull
        if (t.isNullOrEmpty()) throw A0Error(r.status, "csrf_token: ${(o["error"] as? JsonPrimitive)?.contentOrNull ?: "no token"}")
        csrf = t
        return t
    }

    private fun post(path: String, body: JsonObject): AppMux.HttpResponse {
        var tok = token()
        val hdr = { t: String -> base() + mapOf("Content-Type" to "application/json", "X-CSRF-Token" to t) }
        var r = mux.http("POST", path, hdr(tok), body.toString().toByteArray())
        if (r.status == 403) { // our token went stale (A0 restart); the bridge also retries once
            tok = token(force = true)
            r = mux.http("POST", path, hdr(tok), body.toString().toByteArray())
        }
        return r
    }

    /** POST a pre-built body (multipart) with CSRF; same stale-token retry as [post]. */
    private fun postRaw(path: String, contentType: String, body: ByteArray): AppMux.HttpResponse {
        var tok = token()
        val hdr = { t: String -> base() + mapOf("Content-Type" to contentType, "X-CSRF-Token" to t) }
        var r = mux.http("POST", path, hdr(tok), body)
        if (r.status == 403) {
            tok = token(force = true)
            r = mux.http("POST", path, hdr(tok), body)
        }
        return r
    }

    /** What the stick says about Agent Zero (GET /api/agent/status; the bridge allows the /api/agent prefix). */
    data class AgentStatus(val state: String, val message: String, val running: Boolean)

    fun agentStatus(): AgentStatus? = try {
        val r = mux.http("GET", "/api/agent/status", base(), timeoutMs = 15_000)
        if (r.status != 200) null else {
            val o = json.parseToJsonElement(r.text).jsonObject
            AgentStatus(str(o, "state") ?: "", (str(o, "message") ?: "").take(200),
                (o["running"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    } catch (e: AppMux.MuxError) { throw e } catch (_: Exception) { null }

    fun newChat() { context = "" }

    /** Continue an existing A0 chat (after a link resume, or picked from the chat list). */
    fun attach(ctx: String) { context = ctx }

    fun cancel() {
        cancel = true
        val ctx = context
        if (ctx.isNotEmpty()) {
            try { post("/agent-zero/api/stop", buildJsonObject { put("context", ctx) }) } catch (e: Exception) { log("stop failed") }
        }
    }

    // ---- chats (web UI sidebar: chats-store.js newChat / selectChat) ----

    /** A0 api/chat_create.py: a fresh context, like the web UI's New chat. Returns its id. */
    fun createChat(): String {
        val r = post("/agent-zero/api/chat_create", buildJsonObject { put("current_context", context) })
        if (r.status != 200) throw err(r, "chat_create HTTP ${r.status}")
        val id = str(json.parseToJsonElement(r.text).jsonObject, "ctxid")
            ?: throw A0Error(200, "chat_create: no ctxid")
        context = id
        return id
    }

    /** All chats, newest first (state_snapshot.py sorts by created_at desc). */
    fun listChats(): List<ChatInfo> = snapshot("").chats

    /** A chat's whole log as user/reply turns (poll with log_from=0). */
    fun history(ctx: String): List<HistoryTurn> = buildHistory(snapshot(ctx).items)

    /** One poll. [ctx] "" lists chats only. */
    fun snapshot(ctx: String = context, logFrom: Int = 0): Snapshot {
        val pr = post("/agent-zero/api/poll", buildJsonObject {
            put("context", ctx); put("log_from", logFrom); put("notifications_from", 0); put("timezone", timezone)
        })
        if (pr.status != 200) throw err(pr, "poll HTTP ${pr.status}")
        return parseSnapshot(json.parseToJsonElement(pr.text).jsonObject)
    }

    private fun parseSnapshot(doc: JsonObject): Snapshot {
        val items = LinkedHashMap<Int, JsonObject>()
        (doc["logs"] as? JsonArray)?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val no = (o["no"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            items[no] = o
        }
        val chats = (doc["contexts"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { c ->
            val id = str(c, "id") ?: return@mapNotNull null
            ChatInfo(
                id = id, name = str(c, "name"), no = (c["no"] as? JsonPrimitive)?.intOrNull ?: 0,
                createdAt = str(c, "created_at"), lastMessage = str(c, "last_message"),
                running = (c["running"] as? JsonPrimitive)?.booleanOrNull ?: false,
                queue = (c["message_queue"] as? JsonArray)?.mapNotNull { q ->
                    val o = q as? JsonObject ?: return@mapNotNull null
                    QueueItem(str(o, "id") ?: return@mapNotNull null, (o["seq"] as? JsonPrimitive)?.intOrNull ?: 0,
                        str(o, "text") ?: "", (o["attachments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList())
                } ?: emptyList(),
            )
        } ?: emptyList()
        return Snapshot(chats, items, (doc["log_version"] as? JsonPrimitive)?.intOrNull ?: 0,
            str(doc, "log_guid"), (doc["log_progress"] as? JsonPrimitive)?.contentOrNull ?: "")
    }

    fun buildHistory(items: Map<Int, JsonObject>): List<HistoryTurn> {
        val sorted = items.toSortedMap()
        val users = sorted.filter { str(it.value, "type") == "user" }.keys.toList()
        return users.mapIndexed { i, u ->
            val end = users.getOrNull(i + 1) ?: Int.MAX_VALUE
            val span = sorted.filterKeys { it in u until end }
            val ans = span.entries.lastOrNull { str(it.value, "type") == "response" && !str(it.value, "content").isNullOrBlank() }
            val o = sorted[u]!!
            HistoryTurn(
                userNo = u,
                userText = PhonePrompt.unwrap(str(o, "content") ?: ""),
                attachments = ((o["kvps"] as? JsonObject)?.get("attachments") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                answer = ans?.let { str(it.value, "content") },
                steps = A0Steps.capture(span, u, ans?.key),
                stopped = ans == null && span.values.any { str(it, "type") == "info" && str(it, "content") == "Agent process stopped." },
            )
        }
    }

    // ---- A0 server-side message queue (webui message-queue-store.js addToQueue) ----

    /** api/upload.py: multipart N x "file"; returns the saved (safe_filename) names. */
    fun upload(files: List<Attachment>): List<String> {
        if (files.isEmpty()) return emptyList()
        val form = MultipartForm.build(emptyList(), files, fileField = "file")
        if (form.body.size > AttachmentRules.BRIDGE_MAX_BODY) {
            throw A0Error(413, "Attachments too large for StickLink (${AttachmentRules.mb(form.body.size.toLong())}; limit 8 MiB per message).")
        }
        log("upload ${files.size} file(s), ${form.body.size} B")
        val r = postRaw("/agent-zero/api/upload", form.contentType, form.body)
        if (r.status == 413) throw A0Error(413, "The stick refused the upload as too large (8 MiB limit per message).")
        if (r.status != 200) throw err(r, "upload HTTP ${r.status}")
        return (json.parseToJsonElement(r.text).jsonObject["filenames"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }

    /** api/message_queue_add.py. [text] gets the phone instruction like a direct message. Returns queue length. */
    fun queueAdd(text: String, filenames: List<String>, itemId: String): Int {
        val r = post("/agent-zero/api/message_queue_add", buildJsonObject {
            put("context", context); put("text", PhonePrompt.wrap(text)); put("item_id", itemId)
            put("attachments", JsonArray(filenames.map { JsonPrimitive(it) }))
        })
        if (r.status != 200) throw err(r, "message_queue_add HTTP ${r.status}")
        return (json.parseToJsonElement(r.text).jsonObject["queue_length"] as? JsonPrimitive)?.intOrNull ?: -1
    }

    /** api/message_queue_remove.py: one item, or the whole queue when [itemId] is null. */
    fun queueRemove(itemId: String?) {
        val r = post("/agent-zero/api/message_queue_remove", buildJsonObject {
            put("context", context); if (itemId != null) put("item_id", itemId)
        })
        if (r.status != 200) throw err(r, "message_queue_remove HTTP ${r.status}")
    }

    /** api/message_queue_send.py: send the next queued message now (A0 doesn't after a Stop). */
    fun queueSendNext(): Boolean {
        val r = post("/agent-zero/api/message_queue_send", buildJsonObject { put("context", context) })
        if (r.status != 200) throw err(r, "message_queue_send HTTP ${r.status}")
        return json.parseToJsonElement(r.text).jsonObject["sent_item_id"] != null
    }

    // ---- one turn ----

    /**
     * Send one message and wait for Agent Zero's response. [onProgress] gets the
     * live progress line (log_progress) and agent log headings as they change.
     */
    fun ask(text: String, timeoutMs: Long = 600_000, pollMs: Long = 1500,
            attachments: List<Attachment> = emptyList(), onProgress: (String) -> Unit = {}): Turn {
        val mid = "sd-" + Protocol.newId()
        start(text, attachments, mid)
        return awaitTurn(mid, text, -1, timeoutMs, pollMs, onProgress = onProgress)!!
    }

    /** message_async (JSON, or multipart with attachments). Returns the context. */
    fun start(text: String, attachments: List<Attachment>, mid: String): String {
        val sent = PhonePrompt.wrap(text)
        val r = if (attachments.isEmpty()) {
            post("/agent-zero/api/message_async", buildJsonObject {
                put("text", sent); put("context", context); put("message_id", mid)
            })
        } else {
            val form = MultipartForm.build(
                listOf("text" to sent, "context" to context, "message_id" to mid), attachments)
            if (form.body.size > AttachmentRules.BRIDGE_MAX_BODY) {
                throw A0Error(413, "Attachments too large for StickLink (${AttachmentRules.mb(form.body.size.toLong())}; limit 8 MiB per message).")
            }
            log("message_async multipart ${attachments.size} file(s), ${form.body.size} B")
            postRaw("/agent-zero/api/message_async", form.contentType, form.body)
        }
        if (r.status == 413) throw A0Error(413, "The stick refused the upload as too large (8 MiB limit per message).")
        if (r.status != 200) throw err(r, "message_async HTTP ${r.status}")
        val doc = json.parseToJsonElement(r.text).jsonObject
        val ctx = (doc["context"] as? JsonPrimitive)?.contentOrNull
            ?: throw A0Error(200, "message_async: no context")
        // A0 v2.15 gate (plugins/_model_config user_message_ui/_10_check_chat_model.py): no chat model set,
        // nothing logged, no task started. Not a link problem.
        if ((doc["model_not_configured"] as? JsonPrimitive)?.booleanOrNull == true) {
            context = ctx
            throw A0Error(NO_MODEL, "No chat model is configured in Agent Zero.")
        }
        context = ctx
        return ctx
    }

    /**
     * Wait for a turn's reply. With [mid] the user item is ours (id == message_id, or the text);
     * without, it is the first user item after [afterNo] (a message A0 sent from its queue, whose
     * log id is a fresh uuid: helpers/message_queue.py send_message).
     * The turn ends at its reply once A0 stops running or the next user item appears.
     * [onPoll] sees this chat's entry each poll; returning false abandons the wait (null).
     */
    fun awaitTurn(
        mid: String?, matchText: String?, afterNo: Int, timeoutMs: Long = 600_000, pollMs: Long = 1500,
        onUser: (no: Int, content: String, attachments: List<String>) -> Unit = { _, _, _ -> },
        onPoll: (ChatInfo?) -> Boolean = { true },
        onProgress: (String) -> Unit = {},
        /** The phone asked A0 to stop (survives a link resume, unlike [cancel] on one A0Chat). */
        stopRequested: () -> Boolean = { false },
    ): Turn? {
        cancel = false
        val ctx = context
        val sent = matchText?.let { PhonePrompt.wrap(it) }
        val items = LinkedHashMap<Int, JsonObject>()
        var logFrom = 0
        var guid: String? = null
        var userNo = -1
        var lastProgress = ""
        val progress = ArrayList<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        var idlePolls = 0 // consecutive polls where A0 says this chat is not running
        while (System.currentTimeMillis() < deadline) {
            val snap = snapshot(ctx, logFrom)
            if (guid != null && snap.guid != guid) { items.clear(); userNo = -1 }
            guid = snap.guid
            items.putAll(snap.items)
            logFrom = snap.logVersion
            val users = items.filter { str(it.value, "type") == "user" }.keys.sorted()
            if (userNo < 0) {
                userNo = if (mid != null) {
                    // A0 logs the user item with id = message_id (helpers/message_queue.py log_user_message).
                    users.firstOrNull { str(items[it]!!, "id") == mid }
                        ?: users.lastOrNull { no -> val c = str(items[no]!!, "content") ?: ""
                            // Match the user's words or the wrapped payload (real A0 and the mock
                            // both store whatever was in message_async.text).
                            matchText != null && (c.contains(matchText.take(200)) || c.contains(sent!!.take(200))) && no > afterNo }
                        ?: -1
                } else users.firstOrNull { it > afterNo } ?: -1
                if (userNo >= 0) {
                    val o = items[userNo]!!
                    onUser(userNo, str(o, "content") ?: "",
                        ((o["kvps"] as? JsonObject)?.get("attachments") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList())
                }
            }
            val nextUser = if (userNo >= 0) users.firstOrNull { it > userNo } else null
            val inTurn = { no: Int -> userNo >= 0 && no > userNo && (nextUser == null || no < nextUser) }
            val heads = ArrayList<String>()
            for ((no, o) in items) {
                if (!inTurn(no)) continue
                val typ = str(o, "type") ?: ""
                if (typ == "user" || typ == "response") continue
                str(o, "heading")?.takeIf { it.isNotBlank() }?.let { heads.add(it) }
            }
            if (userNo >= 0) {
                val shown = ThinkingStatus.fromPoll(if (nextUser == null) snap.progress else "", heads)
                if (shown != null && shown != lastProgress) {
                    lastProgress = shown; progress.add(shown); onProgress(shown)
                }
            }
            val info = snap.chats.firstOrNull { it.id == ctx }
            if (!onPoll(info)) return null
            // A chat missing from the list (deleted on the web UI) counts as not running.
            val running = info?.running ?: false
            idlePolls = if (running) 0 else idlePolls + 1
            val answerEntries = items.entries.filter { inTurn(it.key) }
                .filter { str(it.value, "type") == "response" && !str(it.value, "content").isNullOrBlank() }
            val answerNo = answerEntries.lastOrNull()?.key
            val finished = nextUser != null || running == false
            fun turn(stopped: Boolean): Turn {
                val span = items.filterKeys { userNo >= 0 && it >= userNo && (nextUser == null || it < nextUser) }
                val end = if (nextUser != null) nextUser - 1 else (items.keys.maxOrNull() ?: userNo)
                return Turn(answerEntries.lastOrNull()?.value?.let { str(it, "content") }, ctx, progress, stopped,
                    steps = if (userNo >= 0) A0Steps.capture(span, userNo, answerNo) else emptyList(),
                    userNo = userNo, answerNo = answerNo, endNo = end,
                    noModel = isNoModelReply(answerEntries.lastOrNull()?.value?.let { str(it, "content") }))
            }
            if (userNo >= 0 && answerNo != null && finished) return turn(stopped = false)
            if (userNo >= 0 && nextUser != null) return turn(stopped = true) // ended without a reply
            val stopping = cancel || stopRequested()
            if (stopping && !running && (userNo >= 0 || idlePolls >= 2)) return turn(stopped = true)
            // A0 says idle but no answer: it ended without one (error, Stop from the web UI, A0 restart).
            // Two idle polls in a row, so a response logged just as the task ended is not missed.
            if (userNo >= 0 && !running && idlePolls >= 2) return turn(stopped = false)
            // Our own message never showed up and A0 stays idle: give the turn up instead of waiting forever.
            if (mid != null && userNo < 0 && !running && idlePolls >= 3) return turn(stopped = false)
            Thread.sleep(pollMs)
        }
        throw A0Error(TIMEOUT, "timed out waiting for Agent Zero")
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
}
