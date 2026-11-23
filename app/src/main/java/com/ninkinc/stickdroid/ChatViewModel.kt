package com.ninkinc.stickdroid

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.ninkinc.stickdroid.core.A0Chat
import com.ninkinc.stickdroid.core.A0Conversation
import com.ninkinc.stickdroid.core.StickSetup
import com.ninkinc.stickdroid.core.CameraShots
import com.ninkinc.stickdroid.core.A0Step
import com.ninkinc.stickdroid.core.Attachment
import com.ninkinc.stickdroid.core.AttachmentRules
import com.ninkinc.stickdroid.core.ImageShrink
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.ninkinc.stickdroid.core.ConnectConfig
import com.ninkinc.stickdroid.core.JoinLink
import com.ninkinc.stickdroid.core.JoinLinkException
import com.ninkinc.stickdroid.core.LinkConnector
import com.ninkinc.stickdroid.core.LinkManager
import com.ninkinc.stickdroid.core.SessSession
import com.ninkinc.stickdroid.core.SessAuthUrl
import com.ninkinc.stickdroid.core.WssSessClient
import com.ninkinc.stickdroid.core.LinkState
import com.ninkinc.stickdroid.core.ReconnectResult
import com.ninkinc.stickdroid.core.OkResumeHttp
import com.ninkinc.stickdroid.core.MethodCClient
import com.ninkinc.stickdroid.core.MethodCConfig
import com.ninkinc.stickdroid.core.MethodCError
import com.ninkinc.stickdroid.core.MethodCSession
import com.ninkinc.stickdroid.core.PairingState
import com.ninkinc.stickdroid.core.Protocol
import com.ninkinc.stickdroid.core.ReadAloudMode
import com.ninkinc.stickdroid.core.ReplyFilter
import com.ninkinc.stickdroid.core.SessionClient
import com.ninkinc.stickdroid.core.SpeechContext
import com.ninkinc.stickdroid.core.SpeechEffect
import com.ninkinc.stickdroid.core.SpeechEvent
import com.ninkinc.stickdroid.core.SpeechMachine
import com.ninkinc.stickdroid.core.SpeechPrefs
import com.ninkinc.stickdroid.core.SpeechState
import com.ninkinc.stickdroid.core.ThinkingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatLine(
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val status: Boolean = false,
    val thinking: Boolean = false,
    val startedAtMs: Long? = null,
    val progress: String? = null,
    val error: Boolean = false,
    val speaking: Boolean = false,
    /** Agent Zero reply as received; filtered at display time ("Show system details"). */
    val raw: String? = null,
    /** The user text this reply answers (ReplyFilter keeps details the user asked for). */
    val userText: String = "",
    /** Files sent with a user message (thumbnails / names in the bubble). */
    val attachments: List<SentAttachment> = emptyList(),
    /** A0 log items for this reply ("Show Agent Zero steps"). */
    val steps: List<A0Step> = emptyList(),
    /** Waiting for message_async to be accepted (show "Sending…" instead of "Thinking…"). */
    val sending: Boolean = false,
    /** This error bubble can be retried (same text/attachments, still in memory). */
    val retryable: Boolean = false,
    val retryTurnId: String? = null,
)

/** What the user's bubble shows for a sent file. */
data class SentAttachment(val name: String, val mime: String, val size: Long, val thumb: ImageBitmap?)

/** A message waiting to be sent while Agent Zero is replying (memory only). */
data class QueuedView(val id: String, val text: String, val attachments: List<SentAttachment>, val onServer: Boolean)

/** One chat in the drawer, as Agent Zero lists it. */
data class ChatEntry(val id: String, val label: String, val date: String, val running: Boolean)

/** A file waiting in the composer (chip). Bytes stay in memory until sent or removed. */
data class PendingAttachment(val id: String, val file: Attachment, val thumb: ImageBitmap?)


/**
 * Primary path: StickLink Method C (Milestone A). Scan the kiosk's "StickLink QR"
 * (https://link.stickllm.com/browser/agent_join.html?ws=..&qr=sl1:..), join within
 * its 60 s window, then chat with Agent Zero over the stick's app-mux bridge.
 * Milestone A keeps no pairing on either side, so nothing secret is stored and a
 * new session always needs a fresh scan (Refresh StickLink QR on the kiosk).
 *
 * Secondary (lab only): the phase-1 companiond pairing JSON path.
 */
private const val TAG = "StickDroid"

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val rtc by lazy { AndroidRtcFactory(app) }
    private val host = HostSpeech(app, { ev -> ui { onSpeechEvent(ev) } }, { st -> builtInStatus = st })

    var paired: Boolean by mutableStateOf(store.hasPairing())
        private set
    var connected: Boolean by mutableStateOf(false)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var status: String by mutableStateOf("Open Devices & Pairing on the kiosk, then scan its StickLink QR.")
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    /** Agent Zero down/failing on the stick (the link itself is fine); null when it works. */
    var stickProblem: String? by mutableStateOf(null)
        private set
    val lines = mutableStateListOf<ChatLine>()
    var draft by mutableStateOf("")
    var wsUrl by mutableStateOf(store.signallingWs)
    var derpUrl by mutableStateOf(store.derpUrl)
    var labHosts by mutableStateOf(store.labHosts)
    var showSystemDetails by mutableStateOf(store.showSystemDetails)
        private set
    var showSteps by mutableStateOf(store.showSteps)
        private set
    /** Files queued in the composer. */
    val pending = mutableStateListOf<PendingAttachment>()
    /** Attachment problem to show above the composer (size limit, read failure…). */
    var attachError: String? by mutableStateOf(null)
    private var cameraFile: java.io.File? = null
    /** Messages queued behind the running reply (memory only; see A0Conversation). */
    val queued = mutableStateListOf<QueuedView>()
    /** Stop pressed with messages queued: ask keep / clear. */
    var askStopQueue by mutableStateOf(false)
    /** Agent Zero's chats for the drawer (fetched live; nothing is cached on the phone). */
    val chats = mutableStateListOf<ChatEntry>()
    var chatsLoading by mutableStateOf(false)
        private set
    var chatsError: String? by mutableStateOf(null)
        private set
    /** The A0 context shown ("" = a new chat not started yet). */
    var currentChat by mutableStateOf("")
        private set
    private val sentThumbs = HashMap<String, List<SentAttachment>>()
    private val turnStarted = HashMap<String, Long>()
    private val retryByTurn = HashMap<String, Pair<String, List<com.ninkinc.stickdroid.core.Attachment>>>()
    private val a0conv: A0Conversation = A0Conversation(object : A0Conversation.Listener {
        override fun onQueue(items: List<A0Conversation.Queued>) = ui {
            queued.clear()
            queued.addAll(items.map { q ->
                QueuedView(q.id, q.text, sentThumbs[q.id] ?: q.attachments.map { SentAttachment(it.name, it.mime, it.size.toLong(), null) }, q.onServer)
            })
        }
        override fun onTurnStart(turnId: String, itemId: String?, text: String, attachments: List<Attachment>, attachmentNames: List<String>) = ui {
            val sent = itemId?.let { sentThumbs.remove(it) }
                ?: if (attachments.isNotEmpty()) attachments.map { SentAttachment(it.name, it.mime, it.size.toLong(), null) }
                else attachmentNames.map { SentAttachment(it, "", -1, null) }
            val now = System.currentTimeMillis()
            turnStarted[turnId] = now
            lastError = null
            lines.add(ChatLine(turnId, fromMe = true, text = text, attachments = sent))
            lines.add(ChatLine(turnId + "/a", fromMe = false, text = ThinkingStatus.sendingLabel(0),
                thinking = true, sending = true, startedAtMs = now, progress = ThinkingStatus.SENDING))
            currentChat = a0conv.context
            busy = true
        }
        override fun onProgress(turnId: String, progress: String) = ui {
            val sending = progress == ThinkingStatus.SENDING
            if (!sending) status = progress.ifBlank { "Connected." }
            val started = turnStarted[turnId] ?: System.currentTimeMillis()
            val i = lines.indexOfFirst { it.id == turnId + "/a" && (it.thinking || it.error) }
            if (i >= 0) {
                val label = if (sending) ThinkingStatus.sendingLabel(System.currentTimeMillis() - started)
                    else ThinkingStatus.timerLabel(System.currentTimeMillis() - started)
                lines[i] = lines[i].copy(thinking = true, error = false, sending = sending,
                    retryable = false, progress = if (sending) ThinkingStatus.SENDING else progress.ifBlank { null },
                    text = label, startedAtMs = started)
            }
        }
        override fun onSendFailed(turnId: String, text: String, attachments: List<com.ninkinc.stickdroid.core.Attachment>, message: String) = ui {
            retryByTurn[turnId] = text to attachments
            failThinking(message, turnId + "/a", retryable = true, retryTurnId = turnId)
            busy = false
        }
        override fun onTurnDone(turnId: String, userText: String, turn: A0Chat.Turn) = ui {
            val thinkId = turnId + "/a"
            val raw = turn.answer
            val shown = when {
                turn.noModel -> StickSetup.NO_MODEL_TEXT
                raw.isNullOrBlank() -> if (turn.stopped) "Stopped." else "(no answer)"
                else -> ReplyFilter.display(raw, userText, showSystemDetails)
            }
            replaceLine(thinkId, ChatLine(thinkId, fromMe = false, text = shown,
                raw = raw?.takeIf { it.isNotBlank() }, userText = userText, steps = turn.steps))
            turnStarted.remove(turnId)
            status = "Connected."
            onSpeechEvent(SpeechEvent.ReplyReady(thinkId, shown))
        }
        override fun onTurnInterrupted(turnId: String, why: String) = ui {
            failThinking(when {
                LinkManager.isRevokeReason(why) -> "Session ended on the stick."
                // The stick answered but can't reply yet (setup message from StickSetup): show it as is.
                why.startsWith(A0Conversation.STICK_PREFIX) -> why.removePrefix(A0Conversation.STICK_PREFIX)
                why.startsWith("Agent Zero:") -> why
                else -> "Connection interrupted — waiting for the link; the reply will show when it's back."
            }, turnId + "/a")
        }
        override fun onBusy(busy: Boolean) = ui { if (connected) this@ChatViewModel.busy = busy }
        override fun onError(message: String) = ui { lastError = message }
        override fun onStickProblem(message: String?) = ui { stickProblem = message }
    }, log = { m -> Log.i(TAG, m) })
    var autoSendSpeech by mutableStateOf(store.autoSendSpeech)
        private set
    var readAloud by mutableStateOf(store.readAloud)
        private set
    var handsFree by mutableStateOf(store.handsFree)
        private set
    var speechEngine by mutableStateOf(store.speechEngine)
        private set
    /** Built-in model state, for "Preparing speech model…" in the composer. */
    var builtInStatus by mutableStateOf(BuiltInRecognizer.Status.NotLoaded)
        private set
    /** Engine used for the current listen (null before the first). */
    var activeEngine by mutableStateOf<com.ninkinc.stickdroid.core.RecognizerEngine?>(null)
        private set
    var systemRecognizerInstalled by mutableStateOf(false)
        private set
    var speech by mutableStateOf(
        SpeechState(
            recognizerAvailable = host.recognitionAvailable,
            micPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    )
        private set
    var wantMicPermission by mutableStateOf(false)
        private set
    var speechOnDevice by mutableStateOf(false)
        private set

    private var inForeground = true
    @Volatile private var mc: MethodCSession? = null
    @Volatile private var chat: A0Chat? = null
    private var resumedOnce = false

    /** Method C pairing lifecycle (scan, durable resume, Disconnect). */
    var linkState: LinkState by mutableStateOf(LinkState.NeedScan())
        private set
    /** Soft warning, e.g. an old stick without durable resume. */
    var linkNote: String? by mutableStateOf(null)
        private set
    /** Failed soft resumes since the last Live (after 3, Scan QR is offered next to Reconnect). */
    var softFailures: Int by mutableStateOf(0)
        private set
    /** Paired with resumable pins but not live (Reconnecting / Connecting via resume / Blocked / Paused). */
    val canDisconnect: Boolean
        get() = connected || linkState is LinkState.Reconnecting || linkState is LinkState.Paused ||
            linkState is LinkState.Blocked || (linkState as? LinkState.Connecting)?.viaResume == true
    /** The stick ended the session (4403 / sess.revoke): only a new QR helps. */
    val sessionEnded: Boolean get() = (linkState as? LinkState.NeedScan)?.revoked == true
    private val link = LinkManager(
        store = store.resumeStore,
        connector = LinkConnector { target ->
            MethodCClient(target, rtc, MethodCConfig(), log = { m -> ui { if (busy) status = step(m) ?: status } }).connect()
        },
        http = OkResumeHttp(),
        wss = WssSessClient(
            authUrl = SessAuthUrl.SessHome,
            log = { m -> android.util.Log.i("StickLink", m) },
        ),
        allowDebugHosts = { store.labHosts },
        log = { m -> android.util.Log.i("StickLink", m) },
        onState = { s -> ui { applyLink(s) } },
        onLive = { l ->
            when (l) {
                is MethodCSession -> {
                    mc = l
                    chat = A0Chat(l.mux, log = { m -> Log.i(TAG, m) }, timezone = java.util.TimeZone.getDefault().id).also { a0conv.setChat(it) }
                }
                is SessSession -> {
                    mc = null
                    chat = A0Chat(l.mux, log = { m -> Log.i(TAG, m) }, timezone = java.util.TimeZone.getDefault().id).also { a0conv.setChat(it) }
                    // SessSession is the live link; keep a handle for path display via link.current
                }
                else -> {}
            }
        },
    )
    private var client: SessionClient? = null
    private var recvAck = 0
    private var sendSeq = 2
    private var conv: String = Protocol.newId()
    private var lastUserText: String = ""
    private var applyingSpeech = false

    private fun ui(block: () -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).post(block) }

    init {
        PhoneData.cleanUp(app) // 0.3.4: no user data kept on the phone (camera leftovers > 15 min)
        link.start() // saved resume token → reconnect without a scan
        host.pref = store.speechEngine
        systemRecognizerInstalled = host.systemAvailable()
        speech = speech.copy(recognizerAvailable = host.recognitionAvailable)
    }

    private fun prefs() = SpeechPrefs(autoSendSpeech, readAloud, handsFree)
    private fun speechCtx() = SpeechContext(connected = connected, busy = busy, foreground = inForeground)

    fun onSpeechEvent(event: SpeechEvent) {
        if (applyingSpeech) {
            // Nested events (e.g. Speak -> TTS already failed) apply after the current reduce.
            val step = SpeechMachine.reduce(speech, event, prefs(), speechCtx())
            speech = step.state
            applySpeechEffects(step.effects)
            return
        }
        applyingSpeech = true
        try {
            val step = SpeechMachine.reduce(speech, event, prefs(), speechCtx())
            speech = step.state
            applySpeechEffects(step.effects)
        } finally {
            applyingSpeech = false
        }
    }

    private fun applySpeechEffects(effects: List<SpeechEffect>) {
        for (fx in effects) when (fx) {
            SpeechEffect.RequestMicPermission -> wantMicPermission = true
            SpeechEffect.StartListening -> {
                wantMicPermission = false
                host.startListening()
                speechOnDevice = host.onDevice
                activeEngine = host.activeEngine
                host.engineNote?.let { n -> speech = speech.copy(note = n) }
            }
            SpeechEffect.StopListening -> host.stopListening()
            is SpeechEffect.SetDraft -> draft = fx.text
            is SpeechEffect.Send -> {
                draft = ""
                send(fx.text, fromSpeech = true)
            }
            is SpeechEffect.Speak -> {
                if (!host.ttsReady) {
                    onSpeechEvent(SpeechEvent.TtsFailed)
                } else {
                    markSpeaking(fx.lineId, true)
                    host.speak(fx.text, fx.lineId)
                }
            }
            SpeechEffect.StopSpeaking -> {
                host.stopSpeaking()
                clearSpeaking()
            }
        }
    }

    fun onMicTapped() {
        host.warmUp()
        host.refreshAvailability()
        speech = speech.copy(recognizerAvailable = host.recognitionAvailable)
        onSpeechEvent(SpeechEvent.MicTapped)
    }

    fun onMicPermissionResult(granted: Boolean) {
        wantMicPermission = false
        onSpeechEvent(if (granted) SpeechEvent.PermissionGranted else SpeechEvent.PermissionDenied)
    }

    fun onMicPromptCancelled() {
        wantMicPermission = false
        onSpeechEvent(SpeechEvent.PermissionDenied)
    }

    fun syncMicPermission(granted: Boolean) {
        speech = speech.copy(micPermission = granted)
    }

    fun onAppForeground(foreground: Boolean) {
        inForeground = foreground
        if (!foreground) {
            onSpeechEvent(SpeechEvent.LeftForeground)
        } else {
            link.onForeground()
            host.refreshAvailability()
            speech = speech.copy(recognizerAvailable = host.recognitionAvailable)
            speechOnDevice = host.onDevice
        }
    }

    fun stopSpeaking() {
        onSpeechEvent(SpeechEvent.StopSpeakingTapped)
        clearSpeaking()
    }

    private fun markSpeaking(id: String, on: Boolean) {
        val i = lines.indexOfFirst { it.id == id }
        if (i >= 0) lines[i] = lines[i].copy(speaking = on)
        lines.forEachIndexed { idx, line ->
            if (line.speaking && line.id != id) lines[idx] = line.copy(speaking = false)
        }
    }

    private fun clearSpeaking() {
        lines.forEachIndexed { idx, line ->
            if (line.speaking) lines[idx] = line.copy(speaking = false)
        }
    }

    fun onScannedQr(text: String) {
        val target = try {
            JoinLink.parse(text, allowDebugHosts = store.labHosts)
        } catch (e: JoinLinkException) {
            lastError = e.message; status = if (e.expired) "QR expired." else "QR rejected."
            return
        }
        lastError = null
        linkNote = null
        busy = true
        status = "Joining the stick…"
        link.connectScanned(target)
    }

    /** UI side of [LinkManager] state changes (main thread). */
    private fun applyLink(s: LinkState) {
        val wasConnected = connected
        linkState = s
        softFailures = when (s) {
            is LinkState.Reconnecting -> s.failures
            is LinkState.Connecting -> if (s.viaResume) s.failures else 0
            is LinkState.Blocked, is LinkState.Paused -> link.softFailures
            else -> 0
        }
        if (s !is LinkState.Live) a0conv.setChat(null)
        when (s) {
            is LinkState.Live -> {
                connected = true; busy = a0conv.busy; lastError = null
                linkNote = s.warning
                val path = when (val l = link.current) { is MethodCSession -> l.path.substringBefore(' '); is SessSession -> "wss"; else -> (mc?.path ?: "").substringBefore(' ') }
                status = if (s.durable) "Connected to the stick ($path). Stays paired until you Disconnect."
                else "Connected to the stick ($path)."
                host.warmUp()
                lines.add(ChatLine(Protocol.newId(), false, if (resumedOnce) "Reconnected." else "Paired with the kiosk. Say hello.", status = true))
                resumedOnce = true
            }
            is LinkState.Connecting -> {
                if (!s.viaResume) { busy = true; status = "Joining the stick…" }
                else status = "Reconnecting… joining the stick"
            }
            is LinkState.Reconnecting -> {
                connected = false; busy = false
                status = s.detail
                if (wasConnected) {
                    failThinking("Connection interrupted — reconnecting…")
                    onSpeechEvent(SpeechEvent.Disconnect)
                }
            }
            is LinkState.Paused -> { connected = false; busy = false; status = s.message }
            is LinkState.Blocked -> {
                connected = false; busy = false
                status = s.message
                if (wasConnected) { failThinking("Connection interrupted."); onSpeechEvent(SpeechEvent.Disconnect) }
            }
            is LinkState.NeedScan -> {
                connected = false; busy = false; resumedOnce = false
                if (s.revoked) {
                    status = LinkManager.REVOKED_MESSAGE
                    lastError = null
                    if (lines.any { it.thinking }) failThinking("Session ended on the stick.")
                    if (wasConnected) onSpeechEvent(SpeechEvent.Disconnect)
                } else {
                    status = "Open Devices & Pairing on the kiosk, then scan its StickLink QR."
                    if (s.message != null) lastError = s.message
                    if (wasConnected) { failThinking("Connection lost."); onSpeechEvent(SpeechEvent.Disconnect) }
                }
            }
            LinkState.Disconnected -> {
                connected = false; busy = false; resumedOnce = false; linkNote = null
                status = LinkManager.DISCONNECTED_MESSAGE
            }
        }
    }

    fun scanNewCode() { link.scanNew() }
    fun retryReconnect() { reconnect() }

    /**
     * Reconnect button: retry the always-on resume now (deduped in [LinkManager]).
     * Never mints a code. Revoked → the UI shows Scan QR instead.
     */
    fun reconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val r = link.reconnectNow()
            ui {
                when (r) {
                    ReconnectResult.COOLDOWN -> (linkState as? LinkState.Blocked)?.let { status = it.message }
                    ReconnectResult.SCAN_REQUIRED -> if (!sessionEnded) status = "Scan a new StickLink QR to pair again."
                    else -> {}
                }
            }
        }
    }

    private fun step(m: String): String? = when {
        m.startsWith("pair.joined") -> "Kiosk accepted the QR. Setting up a direct link…"
        m.startsWith("got offer") -> "Stick answered. Connecting…"
        m.startsWith("datachannel open") -> "Link open. Checking the stick's identity…"
        m.startsWith("pair_psk+confirm ok") -> "Verified. Starting the agent bridge…"
        else -> null
    }

    fun sendDraft() {
        val t = draft.trim()
        if ((t.isEmpty() && pending.isEmpty()) || !connected) return
        draft = ""
        send(t, fromSpeech = false)
    }

    /** Reply text as shown now: raw replies are filtered with the current "Show system details". */
    fun displayText(line: ChatLine): String =
        line.raw?.let { ReplyFilter.display(it, line.userText, showSystemDetails) } ?: line.text

    fun send(text: String, fromSpeech: Boolean = false) {
        val t = text.trim()
        if ((t.isEmpty() && pending.isEmpty()) || !connected) return
        if (!fromSpeech) onSpeechEvent(SpeechEvent.UserTypedSend)
        lastUserText = t
        val c = chat
        if (c == null) {
            if (busy) return
            if (pending.isNotEmpty()) { attachError = "Attachments need a StickLink connection."; return }
            sendLegacy(t); return
        }
        val files = pending.map { it.file }
        val sent = pending.map { SentAttachment(it.file.name, it.file.mime, it.file.size.toLong(), it.thumb) }
        pending.clear(); attachError = null
        lastError = null
        if (files.isNotEmpty()) Log.i(TAG, "submit attachments=${files.size} bytes=${files.sumOf { it.bytes.size }} queued=$busy")
        // Sent now if Agent Zero is idle, otherwise queued on A0 behind the running reply.
        val id = a0conv.submit(t, files)
        if (sent.isNotEmpty()) sentThumbs[id] = sent
    }

    // ---- queue ----

    fun removeQueued(id: String) { sentThumbs.remove(id); a0conv.remove(id) }
    fun clearQueue() { sentThumbs.clear(); a0conv.clearQueue() }

    // ---- chats (Agent Zero's own list; nothing is stored on the phone) ----

    fun refreshChats() {
        val c = chat ?: run { chatsError = "Connect to the stick to see your chats."; return }
        chatsLoading = true; chatsError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = c.listChats().map { ChatEntry(it.id, it.label, shortDate(it.lastMessage ?: it.createdAt), it.running) }
                ui { chats.clear(); chats.addAll(list); chatsLoading = false }
            } catch (e: Exception) {
                ui { chatsLoading = false; chatsError = "Couldn't load chats: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    /** Show an A0 chat: its history comes from A0 (poll log_from=0); the queue does not move with it. */
    fun openChat(id: String) {
        val c = chat ?: return
        if (id == currentChat) return
        chatsLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snap = c.snapshot(id)
                val running = snap.chats.firstOrNull { it.id == id }?.running == true
                var hist = c.buildHistory(snap.items)
                // A running chat's last turn is followed live (A0Conversation.switchTo adopts it).
                if (running && hist.isNotEmpty()) hist = hist.dropLast(1)
                ui {
                    sentThumbs.clear()
                    a0conv.switchTo(id)
                    currentChat = id
                    lines.clear()
                    for (h in hist) {
                        val tid = "h" + h.userNo + "-" + id.take(6)
                        lines.add(ChatLine(tid, fromMe = true, text = h.userText,
                            attachments = h.attachments.map { SentAttachment(it, "", -1, null) }))
                        val raw = h.answer
                        lines.add(ChatLine(tid + "/a", fromMe = false,
                            text = when { !raw.isNullOrBlank() -> ReplyFilter.display(raw, h.userText, showSystemDetails)
                                h.stopped -> "Stopped." else -> "(no answer)" },
                            raw = raw?.takeIf { it.isNotBlank() }, userText = h.userText, steps = h.steps))
                    }
                    chatsLoading = false
                    status = "Connected."
                }
            } catch (e: Exception) {
                ui { chatsLoading = false; chatsError = "Couldn't open the chat: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun shortDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
            t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.getDefault()))
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(iso.take(19)).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.getDefault()))
            } catch (_: Exception) { iso.take(16).replace('T', ' ') }
        }
    }

    private fun replaceLine(id: String, line: ChatLine) {
        val i = lines.indexOfFirst { it.id == id }
        if (i >= 0) lines[i] = line else lines.add(line)
    }

    private fun updateThinking(id: String, block: (ChatLine) -> ChatLine) {
        val i = lines.indexOfFirst { it.id == id && it.thinking }
        if (i >= 0) lines[i] = block(lines[i])
    }

    /** Replace the waiting bubble with an error; otherwise keep the under-list error. */
    private fun failThinking(message: String, id: String? = null, retryable: Boolean = false, retryTurnId: String? = null) {
        val i = if (id != null) lines.indexOfFirst { it.id == id && (it.thinking || it.error) }
        else lines.indexOfLast { it.thinking }
        if (i >= 0) {
            val old = lines[i]
            lines[i] = old.copy(thinking = false, sending = false, error = true, text = message,
                progress = null, speaking = false, retryable = retryable, retryTurnId = retryTurnId)
            lastError = null
        } else {
            lastError = message
        }
    }

    /** Resend a turn that failed at message_async (attachments stay in memory only). */
    fun retrySend(turnId: String) {
        val payload = retryByTurn.remove(turnId) ?: return
        if (!connected) { lastError = "Connect to the stick first, then tap Retry."; return }
        lines.removeAll { it.id == turnId || it.id == turnId + "/a" }
        lastError = null
        val id = a0conv.submit(payload.first, payload.second)
        if (payload.second.isNotEmpty()) {
            sentThumbs[id] = payload.second.map { SentAttachment(it.name, it.mime, it.size.toLong(), null) }
        }
    }

    /** Stop: with messages queued, ask whether to keep them ([askStopQueue]). */
    fun stopTurn() {
        if (chat == null) { viewModelScope.launch(Dispatchers.IO) { chat?.cancel() }; return }
        if (queued.isNotEmpty()) askStopQueue = true else a0conv.stop(keepQueue = true)
    }

    fun confirmStop(keepQueue: Boolean) {
        askStopQueue = false
        if (!keepQueue) sentThumbs.clear()
        a0conv.stop(keepQueue)
    }

    /** New chat: a fresh A0 context (api/chat_create, like the web UI's New chat). */
    fun newChat() {
        val c = chat
        if (c == null) { lines.clear(); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = c.createChat()
                ui { sentThumbs.clear(); a0conv.switchTo(id); currentChat = id; lines.clear(); refreshChats() }
            } catch (e: Exception) {
                ui { lastError = "Couldn't start a new chat: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    /** The only in-app way to end the pairing: {t:close, why:user}, forget the resume token. */
    fun disconnect() {
        mc = null; chat = null
        a0conv.setChat(null)
        a0conv.switchTo("") // Disconnect forgets the queue and the current chat (they stay on A0)
        sentThumbs.clear(); currentChat = ""; chats.clear()
        link.disconnect()
        viewModelScope.launch(Dispatchers.IO) { client?.close(); client = null }
        failThinking("Disconnected.")
        onSpeechEvent(SpeechEvent.Disconnect)
    }

    fun updateShowSystemDetails(v: Boolean) {
        store.showSystemDetails = v
        showSystemDetails = store.showSystemDetails
    }

    fun updateShowSteps(v: Boolean) {
        store.showSteps = v
        showSteps = store.showSteps
    }

    // ---- Attachments ('+' sheet) ----

    fun removeAttachment(id: String) { pending.removeAll { it.id == id } }

    /** Files / Photo Picker results. Reads each (size-checked) off the main thread. */
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        attachError = null
        viewModelScope.launch(Dispatchers.IO) {
            for (u in uris) addOne(u, null, null)
        }
    }

    /** A cache file the camera app writes into (FileProvider, app cache only). */
    fun newCameraUri(): Uri {
        val app = getApplication<Application>()
        val dir = java.io.File(app.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // nothing older is needed
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = java.io.File(dir, "photo-$stamp.jpg")
        cameraFile = f
        return FileProvider.getUriForFile(app, app.packageName + ".files", f)
    }

    fun onCameraResult(ok: Boolean) {
        val dir = java.io.File(getApplication<Application>().cacheDir, "camera")
        // cameraFile is null if the process was recreated while the camera was open; take the newest shot then.
        val f = cameraFile ?: CameraShots.newest(dir, System.currentTimeMillis())
        cameraFile = null
        val len = f?.takeIf { it.exists() }?.length() ?: 0L
        Log.i(TAG, "camera result ok=$ok bytes=$len")
        if (f == null || len == 0L) {
            f?.delete()
            if (ok) attachError = "The camera didn't return a photo. Try again, or attach it from Photos."
            return
        }
        // Some camera apps report cancel but still wrote a full photo; keep it only when the camera said OK.
        if (!ok) { f.delete(); return }
        viewModelScope.launch(Dispatchers.IO) {
            try { addOne(Uri.fromFile(f), f.name, "image/jpeg", file = f) } finally { f.delete() }
        }
    }

    /**
     * A finished camera shot whose result never reached us (e.g. the launcher was not registered when the
     * camera returned). Called when the chat screen appears; only picks up recent, non-empty photos.
     */
    fun recoverCameraShot() {
        if (cameraFile != null) return // a shot is in progress; its result will arrive
        val dir = java.io.File(getApplication<Application>().cacheDir, "camera")
        val f = CameraShots.newest(dir, System.currentTimeMillis()) ?: return
        Log.i(TAG, "camera shot recovered bytes=${f.length()}")
        viewModelScope.launch(Dispatchers.IO) {
            try { addOne(Uri.fromFile(f), f.name, "image/jpeg", file = f) } finally { f.delete() }
        }
    }

    private suspend fun addOne(u: Uri, nameHint: String?, mimeHint: String?, file: java.io.File? = null) {
        val cr = getApplication<Application>().contentResolver
        var name = nameHint ?: "file"
        var size = -1L
        if (file != null) size = file.length()
        else try {
            cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { i -> size = c.getLong(i) }
                }
            }
        } catch (_: Exception) {}
        val mime = mimeHint ?: cr.getType(u) ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val current = withContext(Dispatchers.Main) { pending.map { it.file.size.toLong() } }
        if (size >= 0) AttachmentRules.checkAdd(current, name, size)?.let { e -> ui { attachError = e }; return }
        val bytes = try {
            val input = if (file != null) file.inputStream() else cr.openInputStream(u)
            input?.use { readCapped(it, AttachmentRules.MAX_PER_FILE + 1) }
        } catch (e: Exception) { null }
        if (bytes == null) { ui { attachError = "Couldn't read \"$name\"." }; return }
        val (sendName, sendMime, sendBytes) = maybeShrinkImage(name, mime, bytes)
        AttachmentRules.checkAdd(current, sendName, sendBytes.size.toLong())?.let { e -> ui { attachError = e }; return }
        val thumb = if (sendMime.startsWith("image/")) thumbnail(sendBytes) else null
        val p = PendingAttachment(Protocol.newId(), Attachment(sendName, sendMime, sendBytes), thumb)
        Log.i(TAG, "attachment ready mime=$sendMime bytes=${sendBytes.size} name=$sendName")
        ui {
            // Re-check against what's queued now (several picks may land together).
            val err = AttachmentRules.checkAdd(pending.map { it.file.size.toLong() }, sendName, sendBytes.size.toLong())
            if (err != null) attachError = err else pending.add(p)
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) break
        }
        return out.toByteArray()
    }

    /** Shrink so the long side is at most [ImageShrink.MAX_LONG_SIDE] and send JPEG. */
    private fun maybeShrinkImage(name: String, mime: String, bytes: ByteArray): Triple<String, String, ByteArray> {
        if (!ImageShrink.isImageMime(mime) && !ImageShrink.isImageName(name)) return Triple(name, mime, bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (!ImageShrink.shouldResize(w, h)) return Triple(name, mime, bytes)
        val (tw, th) = ImageShrink.targetSize(w, h)
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return Triple(name, mime, bytes)
        val scaled = if (decoded.width == tw && decoded.height == th) decoded
            else Bitmap.createScaledBitmap(decoded, tw, th, true)
        val out = java.io.ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, ImageShrink.JPEG_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!ok) return Triple(name, mime, bytes)
        val shrunk = out.toByteArray()
        if (shrunk.isEmpty()) return Triple(name, mime, bytes)
        Log.i(TAG, "image shrink ${w}x${h} ${bytes.size}B -> ${tw}x${th} ${shrunk.size}B jpeg")
        return Triple(ImageShrink.jpegName(name), "image/jpeg", shrunk)
    }

    private fun thumbnail(bytes: ByteArray, max: Int = 320): ImageBitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > max * 2 || o.outHeight / sample > max * 2) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    } catch (_: Throwable) { null }

    fun updateAutoSendSpeech(v: Boolean) {
        store.autoSendSpeech = v
        autoSendSpeech = store.autoSendSpeech
    }

    fun updateReadAloud(v: ReadAloudMode) {
        store.readAloud = v
        readAloud = store.readAloud
    }

    fun updateSpeechEngine(v: com.ninkinc.stickdroid.core.RecognizerPref) {
        if (speech.phase == com.ninkinc.stickdroid.core.SpeechPhase.Listening) onSpeechEvent(SpeechEvent.UserTypedSend)
        store.speechEngine = v
        speechEngine = store.speechEngine
        host.pref = speechEngine
        refreshEngines()
    }

    fun refreshEngines() {
        host.refreshAvailability()
        systemRecognizerInstalled = host.systemAvailable()
        speech = speech.copy(recognizerAvailable = host.recognitionAvailable)
    }

    fun updateHandsFree(v: Boolean) {
        store.handsFree = v
        handsFree = store.handsFree
    }

    fun saveEndpoints() {
        store.signallingWs = wsUrl.trim()
        store.derpUrl = derpUrl.trim()
        store.labHosts = labHosts
        labHosts = store.labHosts
    }

    // ---- Lab-only phase-1 path (companiond pairing JSON), unchanged behaviour ----

    fun importTestPairing(json: String) {
        viewModelScope.launch {
            try {
                store.save(PairingState.fromPeerJson(json))
                paired = true
                status = "Lab pairing stored. Tap Connect (lab)."
                lastError = null
            } catch (e: Exception) {
                lastError = "Could not import pairing."
            }
        }
    }

    fun connectLegacy() {
        viewModelScope.launch {
            saveEndpoints()
            status = "Connecting (lab)…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val st = store.load() ?: return@withContext false
                    client?.close()
                    val cli = SessionClient(st, ConnectConfig(ws = store.signallingWs, derp = store.derpUrl.takeIf { it.isNotBlank() }))
                    val hs = cli.connect()
                    if (hs) { client = cli; startRx(cli) }
                    hs
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName; false
                }
            }
            connected = ok
            status = if (ok) "Connected (lab)." else (lastError ?: "Could not connect.")
        }
    }

    fun unpair() {
        disconnect()
        store.clear()
        paired = false
        lines.clear()
        conv = Protocol.newId()
        status = "Lab pairing removed."
    }

    private fun sendLegacy(t: String) {
        val turn = Protocol.newId()
        lines.add(ChatLine(turn, fromMe = true, text = t))
        viewModelScope.launch(Dispatchers.IO) {
            try { client?.sendApp(Protocol.turnStart(sendSeq++, recvAck, turn, conv, t).toBytes()) } catch (e: Exception) { lastError = e.message }
        }
    }

    private fun startRx(cli: SessionClient) {
        Thread({
            while (client === cli) {
                val raw = cli.recvApp(1000) ?: continue
                try {
                    val env = Protocol.parse(raw)
                    env.s?.let { recvAck = it }
                    val text = when (env.t) {
                        "answer" -> Protocol.str(env.b, "text")
                        "progress" -> Protocol.str(env.b, "text") ?: Protocol.str(env.b, "stage")
                        "error" -> "Error: ${Protocol.str(env.b, "msg")}"
                        else -> null
                    } ?: continue
                    val shown = if (env.t == "answer") {
                        ReplyFilter.display(text, lastUserText, showSystemDetails)
                    } else text
                    val id = Protocol.newId()
                    ui {
                        lines.add(ChatLine(id, false, shown, status = env.t != "answer",
                            raw = if (env.t == "answer") text else null, userText = lastUserText))
                        if (env.t == "answer") onSpeechEvent(SpeechEvent.ReplyReady(id, shown))
                    }
                    if (env.t == "answer") Protocol.str(env.b, "turn")?.let {
                        cli.sendApp(Protocol.answerAck(sendSeq++, recvAck, it).toBytes())
                    }
                } catch (_: Exception) {}
            }
        }, "companion-rx").apply { isDaemon = true; start() }
    }

    override fun onCleared() {
        val c = client
        link.shutdown() // not a Disconnect: the resume token stays for the next start
        Thread { c?.close() }.start()
        host.shutdown()
        a0conv.close()
        super.onCleared()
    }
}
