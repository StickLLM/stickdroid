package com.ninkinc.stickdroid.interop

import com.ninkinc.stickdroid.core.A0Chat
import com.ninkinc.stickdroid.core.AlwaysOnCodec
import com.ninkinc.stickdroid.core.AlwaysOnResume
import com.ninkinc.stickdroid.core.AlwaysOnStore
import com.ninkinc.stickdroid.core.AppMux
import com.ninkinc.stickdroid.core.JoinLink
import com.ninkinc.stickdroid.core.LinkManager
import com.ninkinc.stickdroid.core.LinkState
import com.ninkinc.stickdroid.core.LiveLink
import com.ninkinc.stickdroid.core.MethodCClient
import com.ninkinc.stickdroid.core.MethodCConfig
import com.ninkinc.stickdroid.core.MethodCSession
import com.ninkinc.stickdroid.core.OkResumeHttp
import com.ninkinc.stickdroid.core.ResumeHttp
import com.ninkinc.stickdroid.core.SessAuthUrl
import com.ninkinc.stickdroid.core.SessSession
import com.ninkinc.stickdroid.core.WssSessClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Box E2E for always-on WSS resume (slice 1):
 *   methodc-resume --run-dir DIR
 *
 * 1 first QR Method C → pins saved → chat
 * 2 drop DC → WSS ?rt= sess.resume (no QR/ICE) → chat
 * 3 cold start → WSS resume → chat
 * 4 Disconnect → clear pins
 * 2b soft drop + 5 Reconnect taps → one resume, live (0.3.2)
 * 2c attachments (multipart message_async) over WSS sess, 413 above 8 MiB (0.3.3)
 * 2d message queue (A0's own queue, as the web UI) + chat list/history/new chat (0.3.4)
 * 2e idle sends go straight to A0; only sends during a running reply queue (0.3.5)
 * 2f no model / Agent Zero down on the stick are told apart from a link drop (0.3.6); setup wording (0.3.7)
 * 5 rescan + Generate → 4403 / NeedScan(revoked), Reconnect → SCAN_REQUIRED, no loop
 * 6 Scan QR after revoke → live (0.3.2)
 *
 * Lab seam: https→http rewrite for loopback GET /v1/resume only.
 */
fun cmdMethodCResume(a: Map<String, String>): Int {
    val t0 = System.currentTimeMillis()
    fun log(m: String) = println("%7.2fs %s".format((System.currentTimeMillis() - t0) / 1000.0, m))
    val run = File(a["run-dir"] ?: error("--run-dir"))
    val tmp = Files.createTempDirectory("sd-alwayson-")
    val storeFile = tmp.resolve("resume.json").toFile()
    val store = object : AlwaysOnStore {
        override fun load(): AlwaysOnResume? =
            if (storeFile.exists()) AlwaysOnCodec.decode(storeFile.readText()) else null
        override fun save(info: AlwaysOnResume) {
            storeFile.writeText(AlwaysOnCodec.encode(info))
            Files.setPosixFilePermissions(storeFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        override fun clear() { storeFile.delete() }
    }
    val ok = OkResumeHttp()
    val http = ResumeHttp { url -> ok.get(url.replaceFirst("https://127.0.0.1:", "http://127.0.0.1:")) }
    val connector = com.ninkinc.stickdroid.core.LinkConnector { target ->
        MethodCClient(target, JvmRtcFactory(), MethodCConfig(iceServers = emptyList(), keepaliveSec = 5), log = { log("  mc: $it") }).connect()
    }
    val wss = WssSessClient(
        authUrl = SessAuthUrl.SessHome,
        keepaliveSec = 5,
        log = { log("  wss: $it") },
    )
    var fails = 0
    fun check(name: String, cond: Boolean, extra: String = "") {
        println("CHECK ${if (cond) "PASS" else "FAIL"} $name $extra")
        if (!cond) fails++
    }
    fun newMgr() = LinkManager(
        store = store,
        connector = connector,
        http = http,
        wss = wss,
        allowDebugHosts = { true },
        log = { log("link: $it") },
        onState = { s -> log("STATE ${s.toString().take(140)}") },
    )
    fun waitState(m: LinkManager, sec: Long, p: (LinkState) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + sec * 1000
        while (System.currentTimeMillis() < end) { if (p(m.state)) return true; Thread.sleep(200) }
        return p(m.state)
    }
    fun muxOf(l: LiveLink?): AppMux? = when (l) {
        is MethodCSession -> l.mux
        is SessSession -> l.mux
        else -> null
    }
    fun chat(m: LinkManager, label: String): Boolean {
        val mux = muxOf(m.current) ?: return false
        return try {
            val turn = A0Chat(mux).ask("hello $label", timeoutMs = 60_000, pollMs = 500)
            log("chat[$label] answer len=${turn.answer?.length} text=${turn.answer?.take(60)}")
            !turn.answer.isNullOrBlank()
        } catch (e: Exception) { log("chat[$label] failed ${e.message}"); false }
    }
    fun kioskUrl(prevExp: Long?): String {
        val f = File(run, "sticklink-join.json")
        val end = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < end) {
            try {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject
                val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                val exp = (o["exp"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                if (url.startsWith("http") && exp != null && exp != prevExp) return url
            } catch (_: Exception) {}
            Thread.sleep(300)
        }
        error("no kiosk QR published")
    }

    // 1 — first scan
    val m1 = newMgr()
    m1.connectScanned(JoinLink.parse(kioskUrl(null), allowDebugHosts = true))
    check("1 scan → live durable", waitState(m1, 120) { it == LinkState.Live(durable = true) }, "state=${m1.state}")
    val saved = store.load()
    check("1 always-on pins persisted", saved?.canWssResume() == true, "tag=${saved?.tag()} home=${saved?.homeB64u()?.take(8)}")
    check("1 phone static pinned (not wiped)", saved?.phonePriv?.size == 32)
    check("1 chat", chat(m1, "first"))

    // 2 — drop DC → WSS resume (no QR)
    val tDrop = System.currentTimeMillis()
    when (val cur = m1.current) {
        is MethodCSession -> cur.dropTransport("harness: DC killed")
        is SessSession -> cur.dropTransport("harness: WSS killed")
        else -> error("no live link to drop")
    }
    check("2 reconnecting shown", waitState(m1, 10) { it is LinkState.Reconnecting || it is LinkState.Connecting })
    check("2 WSS auto-resumed (no scan)", waitState(m1, 150) { it == LinkState.Live(durable = true) }, "after ${(System.currentTimeMillis() - tDrop) / 1000}s")
    run {
        val now = store.load()
        // Live stick rotates the resume token after Noise (PT resume_token); either is fine as long as pins are kept.
        check("2 pins kept (token ${if (now?.token == saved?.token) "kept" else "rotated"})",
            now != null && now.stickPub.contentEquals(saved!!.stickPub) && now.home.contentEquals(saved.home))
    }
    check("2 live is WSS sess (or Method C if stick still on DC)", m1.current != null)
    check("2 chat after resume", chat(m1, "resumed"))

    // 2b — 0.3.2 Reconnect tap after a soft drop: retries the same always-on resume now,
    // hammering the button does not start a second concurrent attempt, no new QR.
    run {
        val attemptsBefore = m1.resumeAttempts
        val taps = mutableListOf<com.ninkinc.stickdroid.core.ReconnectResult>()
        when (val cur = m1.current) {
            is MethodCSession -> cur.dropTransport("harness: soft drop for Reconnect")
            is SessSession -> cur.dropTransport("harness: soft drop for Reconnect")
            else -> {}
        }
        val tTap = System.currentTimeMillis()
        repeat(5) { taps += m1.reconnectNow(); Thread.sleep(20) }
        log("reconnect taps → $taps")
        check("2b Reconnect → live without QR", waitState(m1, 60) { it == LinkState.Live(durable = true) }, "after ${System.currentTimeMillis() - tTap} ms")
        check("2b exactly one resume attempt for 5 taps", m1.resumeAttempts - attemptsBefore == 1, "attempts=${m1.resumeAttempts - attemptsBefore} taps=$taps")
        check("2b chat after Reconnect", chat(m1, "reconnect-tap"))
    }

    // 2c — 0.3.3 attachments over the always-on WSS path: multipart message_async like the
    // A0 web UI, chunked + paced through app-mux, parsed by werkzeug in the mock A0.
    run {
        val cur = m1.current
        check("2c live link is WSS sess", cur is SessSession, "link=${cur?.javaClass?.simpleName}")
        val mux = muxOf(cur)
        if (mux == null) { check("2c mux", false); return@run }
        val rnd = java.util.Random(33)
        val img = ByteArray(220_000).also { rnd.nextBytes(it) }
        val big = ByteArray(6_500_000).also { rnd.nextBytes(it) }
        val note = "hello from StickDroid 0.3.3\n".toByteArray()
        val files = listOf(
            com.ninkinc.stickdroid.core.Attachment("photo-20261120-233000.jpg", "image/jpeg", img),
            com.ninkinc.stickdroid.core.Attachment("big data.bin", "application/octet-stream", big),
            com.ninkinc.stickdroid.core.Attachment("notes: v1?.txt", "text/plain", note),
        )
        fun sha16(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)
        val t0 = System.currentTimeMillis()
        val turn = try {
            A0Chat(mux, log = { log("  a0: $it") }).ask("ATTACH what are these?", timeoutMs = 120_000, pollMs = 500, attachments = files)
        } catch (e: Exception) { log("2c upload failed ${e.message}"); null }
        val ans = turn?.answer ?: ""
        log("2c answer (${System.currentTimeMillis() - t0} ms): ${ans.take(400)}")
        check("2c reply received", ans.startsWith("MOCK-A0 reply to: ATTACH what are these?"))
        check("2c image arrived intact", ans.contains("photo-20261120-233000.jpg (${img.size} B, sha256 ${sha16(img)})"))
        check("2c 6.5 MB file arrived intact (chunked+paced)", ans.contains("big data.bin (${big.size} B, sha256 ${sha16(big)})"))
        // A0 safe_filename: ':' and '?' → '_'
        check("2c A0 safe_filename applied", ans.contains("notes_ v1_.txt (${note.size} B, sha256 ${sha16(note)})"))
        val steps = turn?.steps ?: emptyList()
        log("2c steps: " + steps.joinToString(" | ") { "${it.type}/${it.tool}/${it.heading}" })
        check("2c steps captured (tool vision_load with paths)", steps.any { it.tool == "vision_load" && it.kvps.any { kv -> kv.first == "paths" && kv.second.contains("/a0/usr/uploads/photo-20261120-233000.jpg") } })
        // Bridge limit is real: 8.5 MiB raw body → stick app_mux_bridge 413 (phone pre-checks before this).
        val r413 = try { mux.http("POST", "/agent-zero/api/message_async", mapOf("Content-Type" to "application/octet-stream"), ByteArray(8 * 1024 * 1024 + 512 * 1024)).status } catch (e: Exception) { -1 }
        check("2c stick bridge refuses > 8 MiB with 413", r413 == 413, "status=$r413")
        check("2c link still live after uploads", m1.state == LinkState.Live(durable = true) && (m1.current?.isAlive == true))
        check("2c chat after uploads", chat(m1, "after-upload"))
    }

    // 2d — 0.3.4 queue + chats over the always-on WSS path, driven through A0Conversation
    // (the class the app's ChatViewModel uses): while a reply runs, more messages go to A0's
    // server-side queue (upload + message_queue_add, like webui/index.js L114-116) and A0 sends
    // them itself when the reply ends; never message_async into a running reply (= intervention).
    run {
        val done = java.util.concurrent.CopyOnWriteArrayList<Pair<String, com.ninkinc.stickdroid.core.A0Chat.Turn>>()
        val starts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val busyFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val trayEver = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() // every text ever shown in "Queued (n)"
        val interrupted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val problems = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val conv = com.ninkinc.stickdroid.core.A0Conversation(object : com.ninkinc.stickdroid.core.A0Conversation.Listener {
            override fun onQueue(items: List<com.ninkinc.stickdroid.core.A0Conversation.Queued>) { items.forEach { trayEver += it.text } }
            override fun onTurnStart(turnId: String, itemId: String?, text: String, attachments: List<com.ninkinc.stickdroid.core.Attachment>, attachmentNames: List<String>) { starts += text; log("2d start: $text files=$attachmentNames") }
            override fun onTurnDone(turnId: String, userText: String, turn: com.ninkinc.stickdroid.core.A0Chat.Turn) { done += userText to turn; log("2d done: $userText -> ${turn.answer?.take(120)} stopped=${turn.stopped}") }
            override fun onTurnInterrupted(turnId: String, why: String) { interrupted += why; log("2d interrupted: $why") }
            override fun onStickProblem(message: String?) { problems += message; log("2d stick problem: $message") }
            override fun onBusy(b: Boolean) { busyFlag.set(b) }
            override fun onError(message: String) { log("2d error: $message") }
        }, log = { log("  conv: $it") }, pollMs = 400, kickAfterMs = 3000)
        fun waitFor(sec: Long, p: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + sec * 1000
            while (System.currentTimeMillis() < end) { if (p()) return true; Thread.sleep(100) }
            return p()
        }
        fun stats(): Map<String, Int> = try {
            val f = File(System.getenv("MOCK_STATS") ?: "/tmp/mock-gw-stats.json")
            Thread.sleep(5200) // mock dumps stats every 5 s
            (kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject)
                .mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content.toIntOrNull() ?: 0 }
        } catch (e: Exception) { log("2d stats: ${e.message}"); emptyMap() }
        val mux0: AppMux = muxOf(m1.current) ?: run { check("2d mux", false); null } ?: return@run
        val chat0 = com.ninkinc.stickdroid.core.A0Chat(mux0, log = { log("  a0: $it") })
        val s0 = stats()
        conv.setChat(chat0)

        // order: one running + two queued (second with a photo)
        val photo = ByteArray(150_000).also { java.util.Random(34).nextBytes(it) }
        conv.submit("WAIT head of queue")
        check("2d first reply running", waitFor(20) { busyFlag.get() })
        conv.submit("Q1 first queued")
        conv.submit("Q2 second queued", listOf(com.ninkinc.stickdroid.core.Attachment("photo-20261121-100500.jpg", "image/jpeg", photo)))
        check("2d both queued on A0 while running", waitFor(20) { conv.queued().size == 2 && conv.queued().all { it.onServer } } || done.size >= 2,
            "queued=${conv.queued().map { it.onServer }}")
        check("2d three replies", waitFor(90) { done.size >= 3 }, "done=${done.size}")
        check("2d replies in order", done.map { it.first } == listOf("WAIT head of queue", "Q1 first queued", "Q2 second queued"), "${done.map { it.first }}")
        check("2d each reply matches its message", done.size >= 3 && done[0].second.answer == "MOCK-A0 reply to: WAIT" &&
            done[1].second.answer == "MOCK-A0 reply to: Q1 first queued" &&
            done[2].second.answer?.startsWith("MOCK-A0 reply to: Q2 second queued | attachments: photo-20261121-100500.jpg (150000 B") == true,
            done.joinToString(" / ") { it.second.answer?.take(80) ?: "null" })
        val ctx = conv.context
        val s1 = stats()
        fun d(k: String) = (s1[k] ?: 0) - (s0[k] ?: 0)
        check("2d A0 queue used: 2 queue_add, 1 upload, 2 auto-sent, 1 message_async, 0 interventions",
            d("queue_add") == 2 && d("upload") == 1 && d("queue_sent") == 2 && d("message_async") == 1 && d("intervention") == 0,
            "queue_add=${d("queue_add")} upload=${d("upload")} queue_sent=${d("queue_sent")} message_async=${d("message_async")} intervention=${d("intervention")}")

        // queue survives a link drop + always-on resume (memory only; same A0 chat)
        done.clear()
        conv.submit("WAIT before drop")
        check("2d running before drop", waitFor(20) { busyFlag.get() })
        conv.submit("Q3 queued across a drop")
        waitFor(20) { conv.queued().any { it.onServer } }
        when (val cur = m1.current) {
            is MethodCSession -> cur.dropTransport("harness: drop with queue")
            is SessSession -> cur.dropTransport("harness: drop with queue")
            else -> {}
        }
        conv.setChat(null)
        check("2d resumed after drop", waitState(m1, 120) { it == LinkState.Live(durable = true) && m1.current?.isAlive == true })
        conv.setChat(com.ninkinc.stickdroid.core.A0Chat(muxOf(m1.current)!!, log = { log("  a0: $it") }))
        check("2d queued message delivered after resume", waitFor(90) { done.size >= 2 }, "done=${done.map { it.first }}")
        check("2d same A0 chat after resume", conv.context == ctx && done.all { it.second.context == ctx })
        check("2d order kept across drop", done.map { it.first } == listOf("WAIT before drop", "Q3 queued across a drop"))

        // remove one queued, then Stop + clear the rest (A0 does not auto-send after a Stop)
        done.clear()
        conv.submit("SLOW long task")
        check("2d SLOW running", waitFor(20) { busyFlag.get() })
        val q4 = conv.submit("Q4 removed before send")
        conv.submit("Q5 cleared by stop")
        waitFor(20) { conv.queued().size == 2 && conv.queued().all { it.onServer } }
        conv.remove(q4)
        Thread.sleep(1500)
        conv.stop(keepQueue = false)
        check("2d stopped turn reported", waitFor(30) { done.size >= 1 } && done[0].second.stopped)
        Thread.sleep(6000)
        val qAfter = try { com.ninkinc.stickdroid.core.A0Chat(muxOf(m1.current)!!).snapshot(ctx).chats.firstOrNull { it.id == ctx }?.queue } catch (e: Exception) { null }
        check("2d stop+clear: nothing else sent, A0 queue empty", done.size == 1 && qAfter?.isEmpty() == true && conv.queued().isEmpty(), "done=${done.map { it.first }} a0queue=${qAfter?.size}")

        // Stop + keep: the phone sends the kept message (message_queue_send)
        done.clear()
        conv.submit("SLOW second task")
        check("2d SLOW 2 running", waitFor(20) { busyFlag.get() })
        conv.submit("Q6 kept after stop")
        waitFor(20) { conv.queued().any { it.onServer } }
        conv.stop(keepQueue = true)
        check("2d stop+keep: kept message answered", waitFor(60) { done.size >= 2 }, "done=${done.map { it.first }}")
        check("2d stop+keep order", done.map { it.first } == listOf("SLOW second task", "Q6 kept after stop") && done[0].second.stopped &&
            done[1].second.answer == "MOCK-A0 reply to: Q6 kept after stop")

        // chats: A0's own list + history + new chat (api/chat_create), like the web UI sidebar
        val c2 = com.ninkinc.stickdroid.core.A0Chat(muxOf(m1.current)!!)
        val list = c2.listChats()
        log("2d chats: " + list.joinToString { "${it.label}(${it.id.take(4)})" })
        check("2d chat list has this chat", list.any { it.id == ctx })
        val hist = c2.history(ctx)
        log("2d history: " + hist.joinToString(" | ") { it.userText.take(30) })
        val texts = hist.map { it.userText }
        check("2d history from A0 in order (phone prompt hidden)",
            listOf("WAIT head of queue", "Q1 first queued", "Q2 second queued", "WAIT before drop", "Q3 queued across a drop", "Q6 kept after stop")
                .let { want -> want.all { it in texts } && want.map { texts.indexOf(it) } == want.map { texts.indexOf(it) }.sorted() } &&
                texts.none { it.contains("[Phone app]") } && "Q4 removed before send" !in texts && "Q5 cleared by stop" !in texts)
        check("2d history keeps attachment names", hist.firstOrNull { it.userText == "Q2 second queued" }?.attachments == listOf("photo-20261121-100500.jpg"))
        val fresh = c2.createChat()
        conv.switchTo(fresh)
        done.clear()
        conv.submit("hello new chat")
        check("2d new chat reply", waitFor(30) { done.size >= 1 } && done[0].second.context == fresh && done[0].second.answer == "MOCK-A0 reply to: hello new chat")
        val list2 = c2.listChats()
        check("2d new chat listed first (newest) and old one kept", list2.firstOrNull()?.id == fresh && list2.any { it.id == ctx }, list2.joinToString { it.label })
        check("2d new chat history is only its own", c2.history(fresh).map { it.userText } == listOf("hello new chat"))
        conv.switchTo(ctx)
        done.clear()
        conv.submit("back in the first chat")
        check("2d switch back continues the first chat", waitFor(30) { done.size >= 1 } && done[0].second.context == ctx)

        // 2e — 0.3.5: nothing running + nothing queued → straight to A0 (message_async), never the queue.
        check("2e idle after reply (busy cleared, would send direct)", waitFor(20) { !busyFlag.get() && !conv.busy && conv.wouldSendDirect() })
        val e0 = stats()
        done.clear()
        conv.submit("E1 idle goes direct")
        check("2e idle send answered", waitFor(30) { done.size >= 1 } && done[0].second.answer == "MOCK-A0 reply to: E1 idle goes direct")
        val e1 = stats()
        fun de(a: Map<String, Int>, b: Map<String, Int>, k: String) = (b[k] ?: 0) - (a[k] ?: 0)
        check("2e idle send: 0 queue_add, 1 message_async, never shown as queued",
            de(e0, e1, "queue_add") == 0 && de(e0, e1, "message_async") == 1 && "E1 idle goes direct" !in trayEver,
            "queue_add=${de(e0, e1, "queue_add")} message_async=${de(e0, e1, "message_async")} tray=${"E1 idle goes direct" in trayEver}")
        waitFor(20) { !busyFlag.get() && conv.wouldSendDirect() }
        done.clear()
        conv.submit("WAIT E2 running reply")
        check("2e reply running", waitFor(20) { busyFlag.get() } && !conv.wouldSendDirect())
        conv.submit("E3 sent during the reply")
        check("2e send during reply is queued", waitFor(20) { "E3 sent during the reply" in trayEver })
        check("2e both answered in order", waitFor(60) { done.size >= 2 } && done.map { it.first } == listOf("WAIT E2 running reply", "E3 sent during the reply"),
            "${done.map { it.first }}")
        check("2e busy clears after the queued reply", waitFor(20) { !busyFlag.get() && conv.wouldSendDirect() })
        conv.submit("E4 after the reply goes direct")
        check("2e next send answered", waitFor(30) { done.size >= 3 } && done[2].second.answer == "MOCK-A0 reply to: E4 after the reply goes direct")
        val e2 = stats()
        check("2e A0 calls: 1 queue_add (E3 only), 2 message_async (E2, E4), 0 interventions; E4 never queued",
            de(e1, e2, "queue_add") == 1 && de(e1, e2, "message_async") == 2 && de(e1, e2, "intervention") == 0 && "E4 after the reply goes direct" !in trayEver,
            "queue_add=${de(e1, e2, "queue_add")} message_async=${de(e1, e2, "message_async")} intervention=${de(e1, e2, "intervention")}")

        // 2f — 0.3.6: the stick has no model / Agent Zero is down. Not "Connection interrupted".
        waitFor(20) { !busyFlag.get() && conv.wouldSendDirect() }
        done.clear(); interrupted.clear(); problems.clear()
        conv.submit("NOMODELCFG hello world")             // A0 model gate: model_not_configured
        check("2f model_not_configured → no-model notice at once", waitFor(20) { done.size >= 1 } && done[0].second.noModel && done[0].second.answer == null,
            "done=${done.map { it.second.noModel }}")
        check("2f busy clears, not a link problem", waitFor(10) { !busyFlag.get() && conv.wouldSendDirect() } && interrupted.isEmpty() && problems.isEmpty())
        done.clear()
        conv.submit("NOMODEL hello world")                // the stick's llama relay "no model loaded" reply
        check("2f stick no-model reply flagged", waitFor(20) { done.size >= 1 } && done[0].second.noModel &&
            com.ninkinc.stickdroid.core.A0Chat.isNoModelReply(done[0].second.answer))
        waitFor(20) { !busyFlag.get() && conv.wouldSendDirect() }
        val downFile = System.getenv("MOCK_DOWN_FILE")?.let { File(it) }
        if (downFile == null) check("2f MOCK_DOWN_FILE set", false) else {
            done.clear(); interrupted.clear(); problems.clear()
            downFile.writeText("down")                       // gateway: 503 agent_not_running
            conv.submit("hello while agent down")
            check("2f agent down → 'Start Agent Zero on the kiosk' (never 'starting')", waitFor(30) { problems.any { it == com.ninkinc.stickdroid.core.StickSetup.AGENT_DOWN_TEXT } } &&
                    problems.none { it?.contains("starting", ignoreCase = true) == true },
                "${problems}")
            check("2f agent down: message shown as waiting, no 'Connection interrupted'", waitFor(10) { conv.queued().any { it.text == "hello while agent down" } } &&
                interrupted.isEmpty(), "${problems.firstOrNull()}")
            downFile.delete()
            check("2f sent once Agent Zero is back", waitFor(60) { done.size >= 1 } && done[0].second.answer == "MOCK-A0 reply to: hello while agent down")
            check("2f problem cleared", waitFor(20) { problems.lastOrNull() == null })
            check("2f link stayed live throughout", m1.state == LinkState.Live(durable = true) && (m1.current?.isAlive == true))
        }
        conv.close()
        check("2d link still live", m1.state == LinkState.Live(durable = true) && (m1.current?.isAlive == true))
    }

    // 3 — cold start
    val tKill = System.currentTimeMillis()
    // Process death: tear the socket without Noise close (stick keeps sess binding).
    when (val cur = m1.current) {
        is MethodCSession -> cur.dropTransport("harness: process killed")
        is SessSession -> cur.dropTransport("harness: process killed")
        else -> {}
    }
    // Bump gen so m1's reconnect loop stops; do not send close.
    m1.shutdown()
    val m2 = newMgr()
    m2.start()
    check("3 cold start WSS resume without QR", waitState(m2, 150) { it == LinkState.Live(durable = true) }, "after ${(System.currentTimeMillis() - tKill) / 1000}s")
    check("3 chat after cold start", chat(m2, "coldstart"))

    // 4 — Disconnect
    m2.disconnect()
    check("4 state Disconnected", m2.state == LinkState.Disconnected)
    check("4 store cleared", store.load() == null)
    Thread.sleep(2000)
    check("4 still Disconnected", m2.state == LinkState.Disconnected)

    // 5 — Generate → NeedScan (4403 or revoke)
    Thread.sleep(15_000) // let Worker rate limit cool down after Disconnect
    val prevExp = try {
        (kotlinx.serialization.json.Json.parseToJsonElement(File(run, "sticklink-join.json").readText()) as kotlinx.serialization.json.JsonObject)["exp"]?.toString()?.toLongOrNull()
    } catch (_: Exception) { null }
    File(run, "sticklink-join-remint.req").writeText("")
    val m3 = newMgr()
    m3.connectScanned(JoinLink.parse(kioskUrl(prevExp), allowDebugHosts = true))
    check("5 rescan → live", waitState(m3, 120) { it == LinkState.Live(durable = true) })
    val exp5 = try {
        (kotlinx.serialization.json.Json.parseToJsonElement(File(run, "sticklink-join.json").readText()) as kotlinx.serialization.json.JsonObject)["exp"]?.toString()?.toLongOrNull()
    } catch (_: Exception) { null }
    val tGen = System.currentTimeMillis()
    File(run, "sticklink-join-remint.req").writeText("")
    // Give the stick a moment to sess.revoke, then force the phone onto the WSS path
    // (Method C DC may linger until RX-silence otherwise).
    Thread.sleep(3_000)
    when (val cur = m3.current) {
        is MethodCSession -> cur.dropTransport("harness: after Generate")
        is SessSession -> cur.dropTransport("harness: after Generate")
        else -> {}
    }
    check("5 Generate → NeedScan", waitState(m3, 120) {
        it is LinkState.NeedScan
    }, "after ${(System.currentTimeMillis() - tGen) / 1000}s state=${m3.state}")
    check("5 pins cleared", store.load() == null)
    run {
        val s5 = m3.state as? LinkState.NeedScan
        check("5 revoked path (Session ended on the stick)", s5?.revoked == true && s5.message == LinkManager.REVOKED_MESSAGE, "state=$s5")
        val a5 = m3.resumeAttempts
        val tap = m3.reconnectNow()
        check("5 Reconnect after revoke → SCAN_REQUIRED", tap == com.ninkinc.stickdroid.core.ReconnectResult.SCAN_REQUIRED, "tap=$tap")
        Thread.sleep(5_000)
        check("5 no resume loop after revoke", m3.resumeAttempts == a5 && m3.state is LinkState.NeedScan, "attempts+${m3.resumeAttempts - a5} state=${m3.state}")
    }
    val newUrl = runCatching { kioskUrl(exp5) }.getOrNull()
    check("5 kiosk has a new QR", newUrl != null)
    m3.shutdown()

    // 6 — Scan QR after revoke works once
    if (newUrl != null) {
        val m4 = newMgr()
        m4.scanNew()
        m4.connectScanned(JoinLink.parse(newUrl, allowDebugHosts = true))
        check("6 Scan QR after revoke → live", waitState(m4, 120) { it == LinkState.Live(durable = true) }, "state=${m4.state}")
        check("6 chat after rescan", chat(m4, "after-revoke"))
        m4.disconnect()
        Thread.sleep(500)
    }
    storeFile.delete(); tmp.toFile().delete()
    println("RESULT always-on-e2e fails=$fails ${if (fails == 0) "PASS" else "FAIL"}")
    return if (fails == 0) 0 else 1
}
