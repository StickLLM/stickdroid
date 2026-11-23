package com.ninkinc.stickdroid.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One phone chat with Agent Zero, with a message queue that matches A0 v2.15:
 *
 * - While a reply is running, A0's web UI does not post to message_async (that would be an
 *   *intervention* into the running agent: agent.py AgentContext.communicate L269-286). It
 *   uploads files (api/upload.py) and calls api/message_queue_add (webui/index.js L114-116,
 *   message-queue-store.js addToQueue). A0 keeps the queue on the server (context.data,
 *   helpers/message_queue.py) and sends the next item itself when a reply finishes
 *   (extensions/python/process_chain_end/_50_process_queue.py).
 * - A Stop (api/stop.py → kill_process) skips process_chain_end, so A0 does not auto-send after
 *   a Stop; the phone then sends the next item with api/message_queue_send if the user kept it.
 *
 * The phone side of the queue is memory only (no disk, by design): items not yet handed to A0
 * survive link drops and app backgrounding but not the app being killed. Items already on A0's
 * queue live on the stick.
 *
 * All network work runs on one worker thread, in order. Callbacks come from that thread.
 */
class A0Conversation(
    private val listener: Listener,
    private val log: (String) -> Unit = {},
    private val pollMs: Long = 1500,
    /** Kick A0's queue with message_queue_send if it sits idle this long (Stop, or an add that raced the end of a reply). */
    private val kickAfterMs: Long = 3000,
    private val timeoutMs: Long = 600_000,
) {
    interface Listener {
        /** Messages waiting to be sent, in order. */
        fun onQueue(items: List<Queued>) {}
        /** A turn began: show the user bubble and a thinking bubble. */
        fun onTurnStart(turnId: String, itemId: String?, text: String, attachments: List<Attachment>, attachmentNames: List<String>) {}
        fun onProgress(turnId: String, progress: String) {}
        fun onTurnDone(turnId: String, userText: String, turn: A0Chat.Turn) {}
        /** The link failed while waiting; the turn is kept and followed again after the link is back. */
        fun onTurnInterrupted(turnId: String, why: String) {}
        fun onBusy(busy: Boolean) {}
        fun onError(message: String) {}
        /** message_async / upload failed after short retries; [text]/[attachments] can be retried (memory only). */
        fun onSendFailed(turnId: String, text: String, attachments: List<Attachment>, message: String) {}
        /**
         * Something on the stick (not the link) is stopping messages: Agent Zero not running, or an
         * Agent Zero error. [message] is for the user. Called again with null once it works again.
         */
        fun onStickProblem(message: String?) {}
    }

    /**
     * A message from the phone. [direct] = sent straight away (Agent Zero idle, nothing queued): it is
     * never shown in the "Queued" tray. It only turns into a queued item if Agent Zero turns out to be
     * busy when the worker checks.
     */
    data class Queued(val id: String, val text: String, val attachments: List<Attachment>, val onServer: Boolean,
                      val direct: Boolean = false, val turnId: String? = null)

    private class Active(val turnId: String, val mid: String?, val afterNo: Int, var text: String,
                         var started: Boolean, val gen: Int, val adopt: Boolean = false)

    private val lock = Any()
    private val items = ArrayList<Queued>()
    private val removals = ArrayList<String?>() // A0 queue removals still to send; null = clear all
    private var active: Active? = null
    private var gen = 0
    private var adoptRunning = false
    @Volatile private var chat: A0Chat? = null
    @Volatile private var closed = false
    @Volatile var context: String = ""
        private set
    private var serverQueue: List<A0Chat.QueueItem> = emptyList()
    private var idleQueueSince = 0L
    private val wake = LinkedBlockingQueue<Int>()
    private var turnSeq = 0
    /** A0's `running` for [context] from the latest poll (false = idle or unknown). */
    @Volatile private var a0Running = false
    @Volatile private var stopRequested = false
    @Volatile private var stickProblem: String? = null
    private val announcedTurns = HashSet<String>()
    private val worker = Thread({ run() }, "a0-conversation").apply { isDaemon = true; start() }

    /** A reply is in progress in this chat (ours, or one A0 is running per its latest poll). */
    val busy: Boolean get() = synchronized(lock) { active?.started == true }
    /** The "Queued (n)" tray: messages really waiting (not the one being sent straight away). */
    fun queued(): List<Queued> = synchronized(lock) { items.filter { !it.direct } }
    /** Would a message sent now go straight to Agent Zero? (idle per A0's latest poll, nothing queued). */
    fun wouldSendDirect(): Boolean = synchronized(lock) { canSendDirect() }
    private fun canSendDirect() = active?.started != true && !a0Running && items.isEmpty() && serverQueue.isEmpty() && !adoptRunning

    /** Link up (a fresh A0Chat over the new mux) or down (null). The chat continues on resume. */
    fun setChat(c: A0Chat?) {
        if (c != null && context.isNotEmpty()) c.attach(context)
        chat = c
        poke()
    }

    /** Send [text]/[attachments] now, or queue them if a reply is running. Returns the item id. */
    fun submit(text: String, attachments: List<Attachment> = emptyList()): String {
        val q = synchronized(lock) {
            val direct = canSendDirect()
            Queued("sdq-" + Protocol.newId(), text, attachments, onServer = false, direct = direct,
                turnId = if (direct) newTurnId() else null).also { items.add(it) }
        }
        log(if (q.direct) "submit: direct" else "submit: queue (busy=${active?.started == true} a0Running=$a0Running local=${items.size - 1} a0=${serverQueue.size})")
        q.turnId?.let { announceStart(it, q) }
        emitQueue(); poke()
        return q.id
    }

    private fun announceStart(turnId: String, q: Queued) {
        if (!announcedTurns.add(turnId)) return
        listener.onTurnStart(turnId, q.id, q.text, q.attachments, q.attachments.map { it.name })
        listener.onProgress(turnId, ThinkingStatus.SENDING)
        // onBusy(true) only after message_async is accepted (A0 actually running the turn).
    }

    /** Remove a queued message before it is sent (also from A0's queue if it is already there). */
    fun remove(id: String) {
        synchronized(lock) {
            val q = items.firstOrNull { it.id == id } ?: return
            items.remove(q)
            if (q.onServer) removals.add(id)
        }
        emitQueue(); poke()
    }

    fun clearQueue() {
        synchronized(lock) {
            val anyServer = items.any { it.onServer } || serverQueue.isNotEmpty()
            items.clear()
            if (anyServer) removals.add(null)
        }
        emitQueue(); poke()
    }

    /** Stop the running reply. [keepQueue] false also clears the queue (here and on A0). */
    fun stop(keepQueue: Boolean) {
        if (!keepQueue) clearQueue()
        stopRequested = true
        val c = chat
        Thread({ try { c?.cancel() } catch (_: Exception) {} }, "a0-stop").start()
        poke()
    }

    /**
     * Show another chat (picked from A0's chat list, or a new one). Stops following the current
     * reply (it keeps running in its own chat on A0); messages not yet handed to A0 are dropped;
     * ones already on A0's queue stay there. If [ctx] is running, its reply is followed.
     */
    fun switchTo(ctx: String) {
        synchronized(lock) {
            gen++
            active = null
            items.clear(); removals.clear(); serverQueue = emptyList(); announcedTurns.clear()
            a0Running = false; stopRequested = false; idleQueueSince = 0L
            context = ctx
            adoptRunning = ctx.isNotEmpty()
        }
        chat?.attach(ctx)
        listener.onBusy(false)
        emitQueue(); poke()
    }

    fun close() { closed = true; poke() }

    private fun poke() { wake.offer(1) }
    private fun emitQueue() = listener.onQueue(queued())
    private fun newTurnId() = "t" + (++turnSeq) + "-" + Protocol.newId().take(6)

    private fun run() {
        var backoff = 250L
        while (!closed) {
            val idleWait = synchronized(lock) { if (items.isNotEmpty() || serverQueue.isNotEmpty() || adoptRunning) pollMs else 30_000L }
            wake.poll(idleWait, TimeUnit.MILLISECONDS)
            wake.clear()
            val c = chat ?: continue
            try {
                step(c)
                backoff = 250L
                if (stickProblem != null) { stickProblem = null; listener.onStickProblem(null) }
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                val a0e = e as? A0Chat.A0Error
                // Status and short error code only (never message text or tokens).
                log("conversation: ${e.javaClass.simpleName}" + (a0e?.let { " ${it.status} ${it.code ?: "-"}" } ?: ""))
                val a = synchronized(lock) { active }
                if (a0e != null && !(a0e.status in 400..499 && a0e.status != 403 || a0e.status == A0Chat.TIMEOUT)) {
                    // The stick answered, so the link is fine: Agent Zero is down or failing. Say so; keep
                    // the message (shown under Queued as "waiting to send") and retry.
                    val msg = describeStickProblem(c, a0e)
                    if (markQueued()) emitQueue()
                    if (a != null && a.started) listener.onTurnInterrupted(a.turnId, "$STICK_PREFIX$msg")
                    if (msg != stickProblem) { stickProblem = msg; listener.onStickProblem(msg) }
                } else if (e is A0Chat.A0Error && (e.status in 400..499 && e.status != 403 || e.status == A0Chat.TIMEOUT)) {
                    // Not a link problem: give up this turn rather than loop on it.
                    synchronized(lock) { if (active === a) active = null }
                    if (a != null && a.started) listener.onTurnInterrupted(a.turnId, "Agent Zero: $why")
                    else listener.onError("Agent Zero: $why")
                    listener.onBusy(false)
                } else if (a != null && a.started) listener.onTurnInterrupted(a.turnId, why)
                Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(4000L)
                poke()
            }
        }
    }

    private fun step(c: A0Chat) {
        if (context.isNotEmpty() && c.context != context) c.attach(context)
        applyRemovals(c)
        synchronized(lock) { active }?.let { follow(c, it); return }
        if (context.isEmpty()) {
            val first = synchronized(lock) { items.firstOrNull() } ?: return
            sendDirect(c, first); return
        }
        val adopting = synchronized(lock) { adoptRunning }
        // Only the chat list is needed to decide (running + A0 queue); the log is fetched when adopting.
        val snap = c.snapshot(context, if (adopting) 0 else NO_LOG)
        val info = snap.chats.firstOrNull { it.id == context }
        val running = info?.running == true
        a0Running = running
        val adopt = synchronized(lock) { serverQueue = info?.queue ?: emptyList(); adoptRunning.also { adoptRunning = false } }
        if (adopt && running) {
            // A chat picked from the list is mid-reply: follow its latest user message.
            val lastUser = snap.items.filter { it.value["type"]?.toString()?.trim('"') == "user" }.keys.maxOrNull() ?: -1
            synchronized(lock) { active = Active(newTurnId(), null, lastUser - 1, "", started = false, gen = gen, adopt = true) }
            poke(); return
        }
        if (running || serverQueue.isNotEmpty()) {
            // A0 is busy after all (e.g. a reply started from the web UI): queue, and show it in the tray.
            if (markQueued()) emitQueue()
            // Where our queued turns start: the log end before anything of ours can reach it.
            val baseNo = if (snap.items.isNotEmpty()) snap.maxNo else c.snapshot(context, 0).maxNo
            pushLocal(c)
            val ours = synchronized(lock) { items.any { it.onServer } }
            if (ours) synchronized(lock) { active = Active(newTurnId(), null, baseNo, "", started = false, gen = gen) }
            if (!running && serverQueue.isNotEmpty()) kickIfIdle(c, info)
            poke(); return
        }
        val first = synchronized(lock) { items.firstOrNull() } ?: return
        sendDirect(c, first)
    }

    private fun describeStickProblem(c: A0Chat, e: A0Chat.A0Error): String {
        if (!e.agentDown) return "Agent Zero on the stick had an error (HTTP ${e.status}${e.code?.let { ", $it" } ?: ""}). Retrying quietly; your message will send when it answers."
        val st = try { c.agentStatus() } catch (_: Exception) { null }
        return StickSetup.forAgentDown(st)
    }

    private fun markQueued(): Boolean = synchronized(lock) {
        var changed = false
        for (i in items.indices) if (items[i].direct) { items[i] = items[i].copy(direct = false); changed = true }
        changed
    }

    private fun sendDirect(c: A0Chat, q: Queued) {
        val mid = "sd-" + Protocol.newId()
        stopRequested = false
        val myGen = synchronized(lock) { gen }
        val turnId = q.turnId ?: newTurnId()
        announceStart(turnId, q)
        try {
            context = c.start(q.text, q.attachments, mid)
        } catch (e: A0Chat.A0Error) {
            if (e.noModel) {
                // A0 refused before logging anything: show the message and say why, don't wait for a reply.
                if (c.context.isNotEmpty()) context = c.context
                synchronized(lock) { items.remove(q) }
                emitQueue()
                log("message_async: model_not_configured")
                listener.onTurnDone(turnId, q.text, A0Chat.Turn(null, context, emptyList(), stopped = false, noModel = true))
                listener.onBusy(false)
                poke()
                return
            }
            if (e.sendFailed) {
                synchronized(lock) { items.remove(q) }
                emitQueue()
                log("message_async: send_timeout")
                listener.onSendFailed(turnId, q.text, q.attachments, e.message ?: A0Chat.SEND_FAIL_TEXT)
                listener.onBusy(false)
                poke()
                return
            }
            if (e.status in 400..499 && e.status != 403) {
                synchronized(lock) { items.remove(q) }
                emitQueue()
                listener.onError(e.message ?: "Agent Zero refused the message")
                listener.onBusy(false)
                return
            }
            throw e
        }
        listener.onProgress(turnId, "")
        val a = Active(turnId, mid, -1, q.text, started = true, gen = myGen)
        a0Running = true
        synchronized(lock) {
            items.remove(q)
            if (gen == myGen) active = a
        }
        emitQueue()
        listener.onBusy(true)
        poke()
    }

    private fun follow(c: A0Chat, a: Active) {
        val turn = c.awaitTurn(a.mid, a.text.takeIf { a.mid != null }, a.afterNo, timeoutMs, pollMs,
            onUser = { _, content, names ->
                if (!a.started) {
                    val q = takeMatching(content)
                    a.text = q?.text ?: PhonePrompt.unwrap(content)
                    a.started = true
                    emitQueue()
                    listener.onTurnStart(a.turnId, q?.id, a.text, q?.attachments ?: emptyList(), names)
                    listener.onBusy(true)
                }
            },
            onPoll = { info ->
                val live = synchronized(lock) { gen == a.gen && active === a }
                if (live) {
                    if (stickProblem != null) { stickProblem = null; listener.onStickProblem(null) }
                    a0Running = info?.running == true
                    synchronized(lock) { serverQueue = info?.queue ?: emptyList() }
                    // Anything typed while this turn runs is a real queued message now.
                    if (markQueued()) emitQueue()
                    applyRemovals(c)
                    val hasLocal = synchronized(lock) { items.any { !it.onServer } }
                    if (hasLocal && (info?.running == true || a.started)) pushLocal(c)
                    if (!a.started) {
                        val ours = synchronized(lock) { items.any { it.onServer } }
                        if (!ours && !a.adopt) return@awaitTurn false // nothing of ours is coming
                        if (info?.running == false && serverQueue.isNotEmpty()) kickIfIdle(c, info) else idleQueueSince = 0L
                    }
                }
                live
            },
            onProgress = { p -> if (a.started) listener.onProgress(a.turnId, p) },
            stopRequested = { stopRequested },
        )
        val next: Boolean
        synchronized(lock) {
            if (active !== a || gen != a.gen) return
            active = null
            next = items.any { it.onServer } || (turn != null && items.isNotEmpty())
            if (turn != null && items.any { it.onServer }) {
                active = Active(newTurnId(), null, turn.endNo, "", started = false, gen = gen)
            }
        }
        if (turn != null && a.started) listener.onTurnDone(a.turnId, a.text, turn)
        if (turn != null) { a0Running = false; stopRequested = false }
        if (!next || synchronized(lock) { active == null }) listener.onBusy(false)
        poke()
    }

    /** Hand every local item to A0's queue, in order (upload files first, like the web UI). */
    private fun pushLocal(c: A0Chat) {
        while (true) {
            val q = synchronized(lock) { items.firstOrNull { !it.onServer } } ?: return
            if (synchronized(lock) { items.indexOf(q) > 0 && !items[items.indexOf(q) - 1].onServer }) return
            try {
                val names = c.upload(q.attachments)
                c.queueAdd(q.text, names, q.id)
                log("queued on A0: ${q.attachments.size} file(s)")
            } catch (e: A0Chat.A0Error) {
                if (e.status in 400..499 && e.status != 403) {
                    synchronized(lock) { items.remove(q) }
                    emitQueue()
                    listener.onError("Couldn't queue a message: ${e.message}")
                    continue
                }
                throw e
            }
            synchronized(lock) {
                val i = items.indexOfFirst { it.id == q.id }
                if (i >= 0) items[i] = q.copy(onServer = true) else removals.add(q.id) // removed meanwhile
            }
            emitQueue()
        }
    }

    private fun applyRemovals(c: A0Chat) {
        while (true) {
            val op = synchronized(lock) { if (removals.isEmpty()) return else removals[0] }
            c.queueRemove(op)
            synchronized(lock) { removals.removeAt(0) }
        }
    }

    private fun kickIfIdle(c: A0Chat, info: A0Chat.ChatInfo?) {
        val now = System.currentTimeMillis()
        if (idleQueueSince == 0L) { idleQueueSince = now; return }
        if (now - idleQueueSince < kickAfterMs) return
        idleQueueSince = 0L
        log("A0 queue idle; message_queue_send")
        c.queueSendNext()
    }

    companion object {
        /** poll log_from past any log: A0 returns no log items, only the chat list (state_snapshot.py). */
        const val NO_LOG = 1_000_000_000
        /** [Listener.onTurnInterrupted] reason prefix: the stick answered but can't reply yet (text after it is for the user). */
        const val STICK_PREFIX = "stick: "
    }

    /** The queued item A0 just started: same text, else the oldest of ours that left A0's queue. */
    private fun takeMatching(content: String): Queued? = synchronized(lock) {
        val gone = items.filter { q -> q.onServer && serverQueue.none { it.id == q.id } }
        fun matches(q: Queued): Boolean {
            val want = content.trimEnd()
            val img = q.attachments.any { it.isImage || ImageShrink.isImageName(it.name) }
            return PhonePrompt.wrap(q.text, img).trimEnd() == want ||
                PhonePrompt.wrap(q.text, false).trimEnd() == want
        }
        val pick = gone.firstOrNull { matches(it) }
            ?: items.firstOrNull { it.onServer && matches(it) }
            ?: gone.firstOrNull()
        pick?.also { items.remove(it) }
    }
}
