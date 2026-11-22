package com.ninkinc.stickdroid.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninkinc.stickdroid.core.ReadAloudMode
import com.ninkinc.stickdroid.core.SpeechPhase
import com.ninkinc.stickdroid.core.LinkState
import com.ninkinc.stickdroid.core.RecognizerEngine
import com.ninkinc.stickdroid.core.RecognizerPref
import com.ninkinc.stickdroid.BuiltInRecognizer
import com.ninkinc.stickdroid.core.ThinkingStatus
import kotlinx.coroutines.delay
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ninkinc.stickdroid.ChatLine
import com.ninkinc.stickdroid.BuildConfig
import com.ninkinc.stickdroid.ChatViewModel
import com.ninkinc.stickdroid.SentAttachment
import com.ninkinc.stickdroid.core.A0Step
import com.ninkinc.stickdroid.core.A0Steps
import com.ninkinc.stickdroid.core.AttachmentRules
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import com.ninkinc.stickdroid.core.ScrollFollow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import com.ninkinc.stickdroid.QueuedView
import kotlinx.coroutines.launch

@Composable
fun AppNav(
    vm: ChatViewModel,
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home", modifier = Modifier.safeDrawingPadding()) {
        composable("home") { HomeScreen(vm, nav, cameraGranted, onRequestCamera) }
        composable("scan") { ScanScreen(vm, nav, cameraGranted, onRequestCamera) }
        composable("chat") { ChatScreen(vm, nav, cameraGranted, onRequestCamera, micGranted, onRequestMic, onOpenAppSettings) }
        composable("settings") { SettingsScreen(vm, nav) }
    }
}

@Composable
fun HomeScreen(vm: ChatViewModel, nav: NavHostController, cameraGranted: Boolean, onRequestCamera: () -> Unit) {
    var paste by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("StickLLM", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("A light companion for Agent Zero. On the kiosk open Devices & Pairing, then scan the StickLink QR (not the 6-digit LAN code). Each session needs a fresh scan.")
        Text(vm.status, style = MaterialTheme.typography.bodyMedium)
        vm.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (cameraGranted) nav.navigate("scan") else onRequestCamera()
        }, modifier = Modifier.fillMaxWidth()) { Text("Scan StickLink QR") }
        if (vm.connected || vm.busy || vm.canDisconnect) {
            Button(onClick = { nav.navigate("chat") }, modifier = Modifier.fillMaxWidth()) { Text("Open chat") }
        }
        Text("Or paste the join link / sl1: code (or a lab pairing JSON):", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(paste, { paste = it }, modifier = Modifier.fillMaxWidth().height(120.dp), maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val t = paste.trim()
                if (t.startsWith("{")) vm.importTestPairing(t)
                else if (t.isNotEmpty()) { vm.onScannedQr(t); nav.navigate("chat") }
            }) { Text("Use pasted text") }
            TextButton(onClick = { nav.navigate("settings") }) { Text("Settings") }
        }
    }
}

@Composable
fun ScanScreen(vm: ChatViewModel, nav: NavHostController, cameraGranted: Boolean, onRequestCamera: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scan the kiosk QR", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { nav.popBackStack() }) { Text("Close") }
        }
        if (!cameraGranted) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Camera permission is needed to scan the pairing QR.")
                Button(onClick = onRequestCamera) { Text("Grant camera") }
            }
        } else {
            QrScanner(
                modifier = Modifier.fillMaxWidth().weight(1f),
                allowDebugHosts = vm.labHosts
            ) { text ->
                vm.onScannedQr(text)
                nav.navigate("chat") { popUpTo("home") }
            }
        }
    }
}

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    nav: NavHostController,
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val list = rememberLazyListState()
    val connecting = vm.busy && !vm.connected
    val listening = vm.speech.phase == SpeechPhase.Listening
    // Always composed on this route, so camera/picker results land even if the link dropped meanwhile.
    val attach = rememberAttachLaunchers(vm)
    LaunchedEffect(Unit) { vm.recoverCameraShot() }
    // Follow new messages only while you're at the bottom; never yank the list while you read older ones.
    val follow = remember { ScrollFollow() }
    var showJump by remember { mutableStateOf(false) }
    var unseen by remember { mutableIntStateOf(0) }
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last != null && last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 8)
        }
    }
    var autoScrolling by remember { mutableStateOf(false) }
    suspend fun toNewest() {
        autoScrolling = true
        try {
            list.animateScrollToItem((vm.lines.size - 1).coerceAtLeast(0))
            list.scrollBy(1_000_000f) // the end of a reply taller than the screen
        } finally { autoScrolling = false }
    }
    LaunchedEffect(list) {
        // Only the user's own scrolling changes "following"; our scrolls and new lines arriving don't.
        snapshotFlow { Triple(list.isScrollInProgress, atBottom, autoScrolling) }.collect { (scrolling, bottom, auto) ->
            if (!auto && (scrolling || bottom)) { follow.onScrolled(bottom); showJump = follow.showJump; unseen = follow.unseen }
        }
    }
    val replyCount = vm.lines.count { !it.fromMe && !it.thinking && !it.status }
    var seenSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.lines.size, replyCount, vm.lines.lastOrNull()?.text?.length) {
        val n = vm.lines.size
        val mine = n > seenSize && vm.lines.subList(seenSize.coerceIn(0, n), n).any { it.fromMe }
        seenSize = n
        val go = follow.onLines(replyCount, mine)
        showJump = follow.showJump; unseen = follow.unseen
        if (go && n > 0) toNewest()
    }
    if (vm.wantMicPermission) {
        AlertDialog(
            onDismissRequest = { vm.onMicPromptCancelled() },
            title = { Text("Use the microphone?") },
            text = {
                Text(
                    if (vm.speechEngine == RecognizerPref.BuiltIn)
                        "StickLLM listens with a speech model built into the app, so it works without Google and without internet. Audio stays on the phone and isn’t recorded or sent anywhere; only the text goes to Agent Zero."
                    else
                        "StickLLM will use the phone’s system speech recognizer. Audio is handled by that recognizer, not sent as a recording to the stick."
                )
            },
            confirmButton = { TextButton(onClick = onRequestMic) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { vm.onMicPromptCancelled() }) { Text("Not now") } }
        )
    }
    if (vm.askStopQueue) {
        AlertDialog(
            onDismissRequest = { vm.askStopQueue = false },
            title = { Text("Stop this reply?") },
            text = { Text("${vm.queued.size} queued message${if (vm.queued.size == 1) "" else "s"} will be sent after it. Keep them, or clear the queue?") },
            confirmButton = { TextButton(onClick = { vm.confirmStop(keepQueue = true) }) { Text("Stop, keep queue") } },
            dismissButton = { TextButton(onClick = { vm.confirmStop(keepQueue = false) }) { Text("Stop and clear queue") } },
        )
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var confirmSwitch by remember { mutableStateOf<String?>(null) } // chat id, or "" for New chat
    val doSwitch = { id: String ->
        scope.launch { drawer.close() }
        if (id.isEmpty()) vm.newChat() else vm.openChat(id)
    }
    val askSwitch = { id: String ->
        if (vm.busy || vm.queued.isNotEmpty()) confirmSwitch = id else doSwitch(id)
    }
    confirmSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text(if (target.isEmpty()) "Start a new chat?" else "Switch chat?") },
            text = { Text("The current reply keeps running in its chat on Agent Zero. Messages still queued here and not yet handed to Agent Zero are dropped.") },
            confirmButton = { TextButton(onClick = { confirmSwitch = null; doSwitch(target) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text("Cancel") } },
        )
    }
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) vm.refreshChats() }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = vm.connected,
        drawerContent = { ChatsDrawer(vm, onNew = { askSwitch("") }, onOpen = { askSwitch(it) }) },
    ) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { drawer.open() } }, enabled = vm.connected) {
                Icon(Icons.Filled.Menu, contentDescription = "Chats")
            }
            Column(Modifier.weight(1f)) {
                Text("StickLLM", fontWeight = FontWeight.SemiBold)
                Text(vm.status, style = MaterialTheme.typography.bodySmall)
            }
            // Disconnect lives in Settings (0.3.2).
            TextButton(onClick = { nav.navigate("settings") }) { Text("Settings") }
        }
        Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.lines, key = { it.id }) { line ->
                Bubble(line, vm.displayText(line), vm.showSteps, onStopSpeaking = { vm.stopSpeaking() })
            }
        }
        if (showJump && vm.lines.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    follow.onJump(); showJump = false; unseen = 0
                    scope.launch { toNewest() }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                BadgedBox(badge = { if (unseen > 0) Badge { Text(if (unseen > 9) "9+" else "$unseen") } }) {
                    Icon(Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (unseen > 0) "Jump to newest message ($unseen new)" else "Jump to newest message")
                }
            }
        }
        }
        vm.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (vm.connected) vm.stickProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        vm.linkNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        vm.speech.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        if (!micGranted && vm.speech.note?.contains("Android Settings") == true) {
            TextButton(onClick = onOpenAppSettings, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Open Android Settings") }
        }
        if (vm.connected) {
            QueueTray(vm)
            AttachmentTray(vm)
            if (listening) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val label = when {
                        vm.activeEngine == RecognizerEngine.BuiltIn && vm.builtInStatus != BuiltInRecognizer.Status.Ready ->
                            "Preparing speech model… (first time takes a few seconds)"
                        vm.activeEngine == RecognizerEngine.BuiltIn -> "Listening… (built-in, offline)"
                        vm.speechOnDevice -> "Listening… (on-device)"
                        else -> "Listening…"
                    }
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(vm.speech.rms.coerceIn(0.06f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AttachButton(attach, enabled = vm.connected)
                OutlinedTextField(
                    vm.draft, { vm.draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (listening) "Listening…" else "Message") },
                    enabled = vm.connected
                )
                if (vm.speech.recognizerAvailable) {
                    IconButton(
                        onClick = { vm.onMicTapped() },
                        enabled = vm.connected && (!vm.busy || vm.speech.phase == SpeechPhase.Speaking || listening)
                    ) {
                        Icon(
                            imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (listening) "Cancel listening" else "Speak",
                            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                if (vm.busy) {
                    // Composer stays usable while Agent Zero replies: Send queues (A0's own message queue).
                    OutlinedButton(onClick = { vm.stopTurn() }) { Text("Stop") }
                    Spacer(Modifier.width(4.dp))
                }
                Button(onClick = { vm.sendDraft() }, enabled = vm.draft.isNotBlank() || vm.pending.isNotEmpty()) {
                    // "Queue" only while a reply is really running (or messages are already waiting); otherwise sent straight away.
                    Text(if (vm.busy || vm.queued.isNotEmpty()) "Queue" else "Send")
                }
            }
            if (!vm.speech.recognizerAvailable) {
                Text(
                    "Speech recognition isn’t available (the built-in speech model couldn’t load and the phone has no recognizer). You can still type.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ls = vm.linkState
                val scan = { if (cameraGranted) nav.navigate("scan") else onRequestCamera() }
                val scanFallback = vm.softFailures >= com.ninkinc.stickdroid.core.LinkManager.SCAN_FALLBACK_AFTER
                when {
                    ls is LinkState.Reconnecting || (ls is LinkState.Connecting && ls.viaResume) -> {
                        // Soft drop: pins still valid, no new QR needed. Reconnect retries the
                        // always-on resume now; while one is in flight the button is disabled.
                        val inFlight = ls is LinkState.Connecting
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Reconnecting…", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            if (ls is LinkState.Reconnecting && ls.detail != "Reconnecting…") "${ls.detail.removePrefix("Reconnecting… ")} Keep the stick on; no new QR needed."
                            else "Keep the stick on. No new QR needed.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { vm.reconnect() }, enabled = !inFlight, modifier = Modifier.fillMaxWidth()) {
                            Text(if (inFlight) "Reconnecting…" else "Reconnect")
                        }
                        if (scanFallback) {
                            Text("Still can't reach the stick? You can scan a new StickLink QR instead.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { vm.scanNewCode(); scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
                        }
                    }
                    ls is LinkState.Blocked -> {
                        // Rate-limited / stick refused: don't spam resume; show the stick's message.
                        var now by remember { mutableStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(ls.retryAtMs) {
                            while (System.currentTimeMillis() < ls.retryAtMs) { delay(1000); now = System.currentTimeMillis() }
                            now = System.currentTimeMillis()
                        }
                        val left = ((ls.retryAtMs - now) / 1000).coerceAtLeast(0)
                        Text(ls.message, fontWeight = FontWeight.SemiBold)
                        Text(if (left > 0) "Trying again automatically in ${left}s." else "Trying again…", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.reconnect() }, enabled = left <= 0, modifier = Modifier.fillMaxWidth()) {
                            Text(if (left > 0) "Reconnect (${left}s)" else "Reconnect")
                        }
                        if (scanFallback) {
                            OutlinedButton(onClick = { vm.scanNewCode(); scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
                        }
                    }
                    ls is LinkState.Paused -> {
                        Text(ls.message, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { vm.reconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                        OutlinedButton(onClick = { vm.scanNewCode(); scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
                    }
                    ls is LinkState.NeedScan && ls.revoked -> {
                        // Revoked: resume can't work and is not retried. One way out: a new QR.
                        Text("Session ended on the stick. Scan a new StickLink QR.", fontWeight = FontWeight.SemiBold)
                        Button(onClick = { vm.scanNewCode(); scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
                    }
                    else -> {
                        if (connecting) {
                            Text("Connecting…", fontWeight = FontWeight.SemiBold)
                        }
                        Button(onClick = scan, modifier = Modifier.fillMaxWidth()) {
                            Text(if (ls is LinkState.NeedScan && ls.message != null) "Scan new code" else "Scan StickLink QR")
                        }
                    }
                }
            }
        }
    }
    } // ModalNavigationDrawer
}

@Composable
private fun Bubble(line: ChatLine, shownText: String, showSteps: Boolean, onStopSpeaking: () -> Unit) {
    if (line.thinking) {
        ThinkingBubble(line)
        return
    }
    val align = if (line.fromMe) Alignment.End else Alignment.Start
    val bg = when {
        line.error -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        line.status -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        line.fromMe -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surface
    }
    val color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .then(if (line.speaking) Modifier.clickable { onStopSpeaking() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (line.attachments.isNotEmpty()) SentAttachments(line.attachments)
            if (shownText.isNotEmpty()) SelectionContainer {
                Text(
                    shownText,
                    color = color,
                    style = if (line.status || line.error) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
                )
            }
            if (line.speaking) {
                Text(
                    "Speaking… tap to stop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (showSteps && line.steps.isNotEmpty()) StepsSection(line.id, line.steps)
        }
    }
}

@Composable
private fun SentAttachments(items: List<SentAttachment>) {
    val images = items.filter { it.thumb != null }
    if (images.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in images.take(3)) {
                Image(
                    a.thumb!!, contentDescription = a.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (images.size == 1) 180.dp else 88.dp).clip(RoundedCornerShape(10.dp))
                )
            }
        }
        if (images.size > 3) Text("+${images.size - 3} more image(s)", style = MaterialTheme.typography.bodySmall)
    }
    for (a in items.filter { it.thumb == null }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("${a.name} · ${AttachmentRules.humanSize(a.size)}", style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Steps (N)": every A0 log item after the user's message, collapsed until tapped. */
@Composable
private fun StepsSection(lineId: String, steps: List<A0Step>) {
    var open by remember(lineId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + "Steps (${steps.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 2.dp)
        )
        if (open) {
            for (st in steps) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(A0Steps.label(st), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    val meta = listOfNotNull(st.type.takeIf { it.isNotBlank() }, st.tool?.let { "tool: $it" }).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Column {
                            if (st.content.isNotBlank()) Text(st.content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            for ((k, v) in st.kvps) Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (st.truncated) Text("(truncated)", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Chips for files waiting to be sent, plus any attachment error. */
/** Hamburger drawer: Agent Zero's own chat list (same chats as the web UI), New chat, current highlighted. */
@Composable
private fun ChatsDrawer(vm: ChatViewModel, onNew: () -> Unit, onOpen: (String) -> Unit) {
    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (vm.chatsLoading) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
        vm.chatsError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (!vm.chatsLoading && vm.chats.isEmpty() && vm.chatsError == null) {
            Text("No chats on Agent Zero yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(vm.chats, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (c.id == vm.currentChat) FontWeight.SemiBold else FontWeight.Normal)
                            Text(c.date + if (c.running) " · replying…" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    selected = c.id == vm.currentChat,
                    onClick = { onOpen(c.id) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        Text("Chats live on the stick (Agent Zero). Nothing is saved on this phone.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    }
}

/** "Queued (n)": messages waiting behind the running reply; remove one, or clear all. */
@Composable
private fun QueueTray(vm: ChatViewModel) {
    if (vm.queued.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Queued (${vm.queued.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearQueue() }) { Text("Clear queue") }
        }
        for (q in vm.queued) QueuedChip(q, onRemove = { vm.removeQueued(q.id) })
    }
}

@Composable
private fun QueuedChip(q: QueuedView, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(q.text.ifBlank { "(attachments only)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val files = if (q.attachments.isEmpty()) "" else "${q.attachments.size} file${if (q.attachments.size == 1) "" else "s"} · "
            Text(files + if (q.onServer) "waiting on Agent Zero" else "waiting to send",
                style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove from queue") }
    }
}

@Composable
private fun AttachmentTray(vm: ChatViewModel) {
    vm.attachError?.let { e ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.attachError = null }) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
    if (vm.pending.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(vm.pending, key = { it.id }) { p ->
            Row(
                Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (p.thumb != null) {
                    Image(p.thumb, contentDescription = p.file.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                } else {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.padding(start = 6.dp).widthIn(max = 140.dp)) {
                    Text(p.file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(AttachmentRules.humanSize(p.file.size.toLong()), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { vm.removeAttachment(p.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${p.file.name}")
                }
            }
        }
    }
}

/**
 * Activity-result launchers for the '+' sheet. Registered at the top of ChatScreen (always composed while the
 * chat route is shown), NOT inside the composer: the composer leaves composition whenever the link drops
 * (`if (vm.connected)`), and Android drops StickDroid's network a few seconds after it goes to the background
 * (camera open), so a launcher registered in the composer was gone by the time the camera returned and the
 * photo was silently lost (0.3.3 bug, 2026-11-21).
 */
class AttachLaunchers(val files: () -> Unit, val photos: () -> Unit, val camera: () -> Unit)

@Composable
private fun rememberAttachLaunchers(vm: ChatViewModel): AttachLaunchers {
    val ctx = LocalContext.current
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addUris(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentRules.MAX_FILES)) { vm.addUris(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.onCameraResult(ok) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(vm.newCameraUri())
        else vm.attachError = "Camera permission is needed to take a photo. You can still attach files and photos."
    }
    return remember(files, photos, camera, camPerm) {
        AttachLaunchers(
            files = { files.launch(arrayOf("*/*")) },
            photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            camera = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    camera.launch(vm.newCameraUri())
                } else camPerm.launch(Manifest.permission.CAMERA)
            },
        )
    }
}

/** The '+' button and its sheet: Files (SAF), Photos (Photo Picker), Camera (TakePicture). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachButton(launchers: AttachLaunchers, enabled: Boolean) {
    var sheet by remember { mutableStateOf(false) }
    IconButton(onClick = { sheet = true }, enabled = enabled) {
        Icon(Icons.Filled.Add, contentDescription = "Attach files, photos or camera")
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(
                "Attach (up to ${AttachmentRules.mb(AttachmentRules.MAX_TOTAL)} per message over StickLink)",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp)
            )
            ListItem(
                headlineContent = { Text("Files") },
                supportingContent = { Text("Any document from the phone or cloud storage") },
                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                modifier = Modifier.clickable { sheet = false; launchers.files() }
            )
            ListItem(
                headlineContent = { Text("Photos") },
                supportingContent = { Text("Pick one or more images") },
                leadingContent = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                modifier = Modifier.clickable { sheet = false; launchers.photos() }
            )
            ListItem(
                headlineContent = { Text("Camera") },
                supportingContent = { Text("Take a photo") },
                leadingContent = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                modifier = Modifier.clickable { sheet = false; launchers.camera() }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThinkingBubble(line: ChatLine) {
    val context = LocalContext.current
    val reduceMotion = remember {
        val cr = context.contentResolver
        fun scale(name: String): Float = try {
            Settings.Global.getFloat(cr, name, 1f)
        } catch (_: Exception) { 1f }
        scale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
            scale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(line.id) {
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = now - (line.startedAtMs ?: now)
    val secs = elapsed.coerceAtLeast(0L) / 1000L
    val rest = ThinkingStatus.lines(elapsed, line.progress).drop(1)
    val dots = if (reduceMotion) "…" else ".".repeat((((now / 300) % 3) + 1).toInt())
    val title = if (reduceMotion) "Thinking… ${secs}s" else "Thinking$dots ${secs}s"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            for (extra in rest) {
                Text(extra, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: ChatViewModel, nav: NavHostController) {
    LaunchedEffect(Unit) { vm.refreshEngines() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Connection", style = MaterialTheme.typography.titleSmall)
        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        if (vm.canDisconnect) {
            var confirm by remember { mutableStateOf(false) }
            Text("Stays paired with this stick until you Disconnect (or the stick ends the session).", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Disconnect from the stick?") },
                    text = { Text("This ends the pairing on the stick and forgets it on this phone. To use the stick again you'll scan a new StickLink QR.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = false
                            vm.disconnect()
                            nav.navigate("home") { popUpTo("home") { inclusive = true } }
                        }) { Text("Disconnect") }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
                )
            }
        } else {
            Text("Not paired. Scan a StickLink QR on the kiosk (Devices & Pairing) to connect.", style = MaterialTheme.typography.bodySmall)
        }
        Text("StickLink uses the signalling address inside the kiosk QR (link.stickllm.com only). After the first scan the phone keeps an encrypted resume record so it can reconnect without a new QR.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show system details")
                Text("Show the date, model and agent info Agent Zero adds to replies. Off hides it. Changes earlier replies too.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = vm.showSystemDetails, onCheckedChange = { vm.updateShowSystemDetails(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show Agent Zero steps")
                Text("Keep Agent Zero's steps (tools, commands, notes) on each reply under a collapsed “Steps”. Tap to expand. Off by default.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = vm.showSteps, onCheckedChange = { vm.updateShowSteps(it) })
        }
        Text("Phone speech (runs on the phone, not the stick)", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Speech recognizer")
            Text(
                if (vm.speechEngine == RecognizerPref.BuiltIn)
                    "Built-in: offline English model inside the app. No Google or internet needed."
                else if (vm.systemRecognizerInstalled)
                    "System: the phone’s own recognizer (for example Google or Transcribro)."
                else
                    "System recognizer isn’t installed on this phone, so the built-in one is used.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.speechEngine == RecognizerPref.BuiltIn,
                    onClick = { vm.updateSpeechEngine(RecognizerPref.BuiltIn) },
                    label = { Text("Built-in (offline)") }
                )
                FilterChip(
                    selected = vm.speechEngine == RecognizerPref.System,
                    onClick = { vm.updateSpeechEngine(RecognizerPref.System) },
                    enabled = vm.systemRecognizerInstalled || vm.speechEngine == RecognizerPref.System,
                    label = { Text(if (vm.systemRecognizerInstalled) "Phone’s system" else "Phone’s system (not installed)") }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-send after speaking")
                Text("Send the transcript when you stop talking", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = vm.autoSendSpeech, onCheckedChange = { vm.updateAutoSendSpeech(it) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Read replies aloud")
            Text("Default is to read a reply only when you spoke the question", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.readAloud == ReadAloudMode.Never,
                    onClick = { vm.updateReadAloud(ReadAloudMode.Never) },
                    label = { Text("Never") }
                )
                FilterChip(
                    selected = vm.readAloud == ReadAloudMode.AfterSpeech,
                    onClick = { vm.updateReadAloud(ReadAloudMode.AfterSpeech) },
                    label = { Text("After I speak") }
                )
                FilterChip(
                    selected = vm.readAloud == ReadAloudMode.Always,
                    onClick = { vm.updateReadAloud(ReadAloudMode.Always) },
                    label = { Text("Always") }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hands-free")
                Text("After a reply is read, listen again automatically. The mic is closed while a reply is being read, so it doesn’t hear itself. Stops on silence or when you leave the app. Off by default.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = vm.handsFree, onCheckedChange = { vm.updateHandsFree(it) })
        }
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lab hosts (127.0.0.1 / 10.0.2.2), debug only", modifier = Modifier.weight(1f))
                Switch(checked = vm.labHosts, onCheckedChange = { vm.labHosts = it })
            }
        }
        Text("Lab-only phase-1 path (companiond pairing JSON):", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(vm.wsUrl, { vm.wsUrl = it }, label = { Text("Lab signalling WebSocket") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vm.derpUrl, { vm.derpUrl = it }, label = { Text("Lab DERP URL") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveEndpoints(); nav.popBackStack() }) { Text("Save") }
            if (vm.paired) OutlinedButton(onClick = { vm.connectLegacy() }) { Text("Connect (lab)") }
            if (vm.paired) OutlinedButton(onClick = { vm.unpair() }) { Text("Remove lab pairing") }
        }
        TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
