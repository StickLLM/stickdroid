# Install StickDroid 0.3.7-methodc on the Pixel 6a

Phone: Pixel 6a, **stock Android 17** (API 37, build CP3A.261005.002.A1).
GrapheneOS is **not** needed. The app has **no Google Play dependency**: no
Play services, ML Kit or Firebase (QR decoding is ZXing; WebRTC is
stream-webrtc-android's prebuilt libwebrtc).

APK: `/workspace/stickdroid-dist/stickdroid-0.3.7-methodc-debug.apk` (158539374 bytes, debug-signed, same debug key as 0.3.1-0.3.6 so it installs over it; do not clear data)
sha256: `8e0bdd2ef0b48c9d6684b67bfeb65dcdeab4a4373547cf71add806e803192ef5`

Check it before installing: `sha256sum /workspace/stickdroid-dist/stickdroid-0.3.7-methodc-debug.apk`

## Route A: USB file copy, then tap to install

1. Plug the phone in. In the USB notification, pick **File transfer**.
2. Copy the APK into the phone's **Download** folder.
3. On the phone, open **Files**, go to Downloads and tap the APK.
4. When asked, allow **Install unknown apps** for Files (Settings opens; flip
   the switch; go back). Tap **Install**.
5. Play Protect may offer to scan an unknown app. Scanning is fine; the app
   does not need Play to run.

## Route B: adb (ADB is already authorised on the desktop)

```
adb devices                      # shows the 6a as "device"
adb install -r stickdroid-0.3.7-methodc-debug.apk
```

If an older StickDroid with a different signature is installed,
`adb uninstall com.ninkinc.stickdroid` first. That only removes the
phase-1 lab pairing; the kiosk keeps nothing about this phone.

## Android 16/17 notes for this build

- targetSdk 35, minSdk 31. It installs and runs on API 37.
- Edge-to-edge is always on for target 35 (Android 15+). The app calls
  `enableEdgeToEdge()` and pads with safe-drawing insets, so the
  keyboard and bars don't cover the chat box.
- Camera is a normal runtime permission, asked on the first scan.
- No foreground service (so no FGS type is needed). The session only lives
  while the app is on screen. If you switch away for long, the session may
  drop and you just scan a fresh QR.
- Local-network permission (Android 17) only applies to apps targeting 37.
  This app targets 35 and only talks to link.stickllm.com and the stick
  over WebRTC, so nothing extra is needed.
- Release config is TLS-only. Cleartext is allowed only in this debug build,
  and only to 127.0.0.1/localhost/10.0.2.2 (box lab).
- 16 KB page-size aligned native libs (arm64-v8a, plus x86_64 for an emulator).

## 0.2.3 (phone-side only)

Host speech as a stopgap until Phase 2 voice, using the phone's built-in
Android recognizer and TTS (not the stick, and not a custom voice stack):

- A mic button in the composer when you are **Connected**. Partial
  transcripts show in the box, with a listening level bar. When you stop
  talking the app auto-sends (Settings: **Auto-send after speaking**).
  Tap the mic again to cancel. On-device recognition is preferred when
  Android offers it; otherwise the default system recognizer. Language
  follows the phone locale. If `SpeechRecognizer` is not available (some
  GrapheneOS builds), the mic is hidden and a short note explains why.
- The first mic tap asks for **Microphone** with an in-app prompt, then
  the system permission dialog. It will not silently fail the way an
  earlier mic path did.
- Replies are read aloud with TTS after a spoken question (Settings:
  **Never** / **After I speak** / **Always**). Markdown and code are
  stripped; URLs are spoken as "link". Tap a speaking bubble to stop.
  Speaking also stops when you tap the mic or Send. Audio focus is
  requested; TTS init failure is non-fatal.
- **Hands-free** (off by default): after a reply is read, listen again.
  Stops on silence or when you leave the app.

## 0.2.2 (phone-side only)


- While waiting for a reply: an assistant "thinking" bubble with animated
  dots (static if you have reduced motion on), a timer (`Thinking… 12s`),
  and a short live status when Agent Zero reports one (for example
  "Using a tool"). After 60 seconds it adds "Still working — the stick's
  model can be slow". The bubble is replaced by the answer, or by the
  error if the turn fails or the session drops. Send becomes **Stop**
  while a turn is running.
- The message box and Send button appear only when you are **Connected**.
  Otherwise the chat screen shows a prominent **Scan StickLink QR**
  button, plus "Connecting…" while a join is in progress.
- Scanner: pinch to zoom, a small **2.0x** readout, double-tap to reset.
  The QR tracking box stays aligned because zoom is applied in CameraX
  (preview and decoder crop together).

## 0.2.1 (phone-side only)

- Replies: the app asks Agent Zero to keep phone chats short, and hides
  system-detail dumps (date/time, agent number, profile, model path)
  unless you asked for them. Settings has **Show system details** (off
  by default) if you want the raw reply.
- Scanner: a viewfinder with a box that tracks the QR, turns green on a
  valid StickLink code (short buzz, then "Got it, connecting…"), or
  amber/red if the code is expired or not StickLink. Torch toggle if
  the camera has a flash. Invalid codes stay on the scan screen.

## Live test (with the real kiosk)

**Rate limit:** the kiosk can open only 5 QRs per 10 min and 20 per day, so
press Refresh only when you're ready to scan.

1. Phone on Wi-Fi or mobile data, whichever the kiosk hint suggests. Method C
   is STUN-only, with **no TURN relay**. If one network fails at "Setting up a
   direct link…", try the other.
2. Open StickDroid. Grant camera when asked.
3. On the kiosk: **Devices & Pairing**, then **Refresh StickLink QR**.
4. Tap **Scan StickLink QR** and point at the QR **straight away**. Each QR
   lives about 60 s and must be joined within that time.
5. Watch the status line: "Kiosk accepted the QR", then "Stick answered",
   then "Link open", then "Verified", then **"Connected to the stick (direct)"**.
6. Type a short message and tap Send. You'll see progress lines, then the
   Agent Zero answer. **Stop** cancels a running turn.
7. Tap **Disconnect** when done. A new session needs a **new** QR (Refresh
   again).
8. Don't press Refresh on the kiosk while you're chatting. It ends the
   session (only one phone session at a time).

There is **no Allow button** on the kiosk in this lab milestone. The stick
accepts the QR holder automatically.

If something fails, note the exact red error line and the time. The app logs
no keys, invites or tokens.

## 0.3.0 (phone-side only): always-on WSS resume

After one QR the phone stays paired. Wake / Wi-Fi / process death uses WSS
 +  + fresh Noise IK with **pinned** statics
(no new QR, no WebRTC remint). Disconnect or Generate/revoke (4403/4401) → scan again.

Real-device checks (Nink):
1. Scan live QR once; confirm Agent Zero.
2. Force-stop app → reopen → WSS resume, no scan.
3. Airplane 30–60s → auto-resume.
4. Disconnect → must need new QR.
5. Generate on stick while app asleep → fail resume → scan.

## 0.2.5 (phone-side only): durable resume

After the first QR the phone stays paired. On a network drop, an app kill or
a reboot of the app it shows "Reconnecting…" and comes back without a new QR.
Only **Disconnect** ends the pairing (then scan a new code). After **Generate**
on the stick the phone stops trying after about 3 min and asks for a new scan.
You can tap "Scan new code instead" at any time.

Real-device checks still to do (Nink):
4. Airplane mode for 30–60 s, then off: the phone auto-reconnects.
5. Force-stop the app, then reopen it: it resumes with no QR.
6. Disconnect: a new QR is needed, and the old token does not reconnect.
7. Connect, then Generate on the stick: resume fails and the phone asks for a new scan.

## 0.2.4 (phone-side only)

- Speech-to-text is built in (Vosk, offline, English). Works on GrapheneOS,
  which has no system recognizer. No Google, no internet for speech.
- First mic tap after install unpacks the model (~68 MB, a few seconds,
  "Preparing speech model…"). Later taps are quick.
- Settings → Speech recognizer: Built-in (default) or Phone's system (only
  if one is installed, e.g. Google or Transcribro).
- The mic is closed while a reply is read aloud, so it can't hear itself.

## 0.3.1 hotfix: sess resume PSK v2
Install over 0.3.0 with `adb install -r` and do not clear data. The saved pairing is kept and resumes without a rescan.

## 0.3.2 (phone-side only): Reconnect control, Disconnect in Settings

- **Disconnect** moved from the chat header to **Settings → Connection** (with a confirm). Same behaviour: tells the stick `close{why:user}`, forgets the resume record, back to the scan screen.
- When the link drops (soft: network blip, WSS flap), the chat shows **Reconnecting…** with a **Reconnect** button. It retries the same always-on resume now (sess.resume, PSK v2), backoff reset. It's disabled while an attempt is on the wire, so taps can't start a second one. After 3 failed attempts a **Scan QR** fallback appears too.
- When the stick ends the session (WS 4403, sess.revoke, token gone), all retrying stops and the chat shows **Session ended on the stick. Scan a new StickLink QR.** with a **Scan QR** button.
- Rate-limited / stick busy: shows the message (the stick's own text if it sent one) and a countdown; Reconnect is disabled until the server's retry time.
- Reconnect never mints or Generates a code.

## 0.3.3: attachments, Agent Zero steps, live "Show system details"

- **+** left of the message box: **Files**, **Photos**, **Camera**. Camera asks for the camera permission only the first time you use it. Files show as chips above the box with an × to remove them.
- Limit over StickLink: about 8.3 MB per message in total (the stick's link carries at most 8 MiB per request). Bigger files get a clear error.
- Settings → **Show Agent Zero steps** (off by default) adds a collapsed "Steps (N)" under each reply.
- Settings → **Show system details** now changes earlier replies too.

## 0.3.4: queue messages, chats menu, nothing personal stored on the phone

- **Queue:** while Agent Zero is replying you can keep typing. Send says **Queue**; messages (and attachments) wait in **Queued (n)** above the box, in order, each with an × to remove it. Agent Zero sends the next one when the reply finishes (its own queue, same as the web UI). **Stop** stops the reply and asks **Stop, keep queue** or **Stop and clear queue**.
- The queue survives a network drop and switching apps. It is kept in memory only, so **force-stopping the app loses messages that hadn't reached the stick yet**.
- **☰ menu:** your Agent Zero chats (same list as the web UI) with dates, the current one highlighted, and **New chat**. Tap a chat to load its history from the stick and carry on there.
- The phone keeps no chat text, history or queue on disk. Only the pairing (encrypted, Keystore) and settings, plus the speech model. Camera photos are deleted once attached; leftovers older than 15 min are cleaned at start.

Checks on the phone (Nink):
1. Ask something slow, then send two more messages: both show in Queued (2), and the replies come in order.
2. Remove one queued message with ×: it is never answered.
3. Stop with items queued: Keep → the next one is answered; Clear → nothing more is sent.
4. ☰ → New chat, ask something, ☰ → switch back to the old chat: its history loads.
5. Take a camera photo and send it: the reply mentions it.

## 0.3.5: send straight away when nothing is running; jump to newest

- When Agent Zero isn't replying and nothing is waiting, a message is **sent straight away** (button says **Send**; nothing appears under Queued). Only messages sent while a reply is running are queued (button says **Queue**).
- Fixed: after some replies (an error with no answer, or a Stop around a reconnect) the app stayed "busy" and queued everything afterwards.
- A small **down-arrow** button appears when you scroll up; tap it to jump to the newest message. A number on it counts replies that arrived meanwhile. The chat no longer jumps to the bottom while you're reading older messages.

Checks on the phone (Nink):
1. Send a message with nothing running: it goes straight away and never shows under Queued.
2. While a reply is running, send another: it shows under Queued (1) and is answered after the first.
3. When that's done, send again: it says Send and goes straight away.
4. Stop a reply, then send: it goes straight away.
5. Scroll up during a long chat while a reply arrives: the view stays put, the down-arrow shows a 1, tapping it jumps to the newest.

## 0.3.6: say what's really wrong when the stick can't answer

- No chat model on the stick: the bubble says "No model is loaded on the stick. Load one on the stick (StickLLM home screen → Models) to chat." (from A0's `model_not_configured` or the stick's no-model reply) instead of "Connection interrupted".
- Agent Zero not running / erroring (gateway 502/503, A0 5xx): a red line under the chat says so (with the stick's own status message from /api/agent/status), the message shows as "waiting to send" and goes out when the agent is back. "Connection interrupted" is now only for real link drops.
- Logs now record `conversation: A0Error <status> <code>` (no text, no tokens).
- Same install as above (`adb install -r`, keep data). No model picker yet: loading a model from the phone needs a stick-side endpoint (see evidence/methodc-kiosk/RESULT-0.3.6.md).

## 0.3.7: friendly setup messages

- Agent Zero not running: "Agent Zero isn't running on the stick. Start Agent Zero on the kiosk (and load a model like Qwen if you haven't) to use StickLLM from your phone. Your message will send once it's running." Never "starting" (the stick says that even when nothing is starting).
- No model: "No model is loaded on the stick. Load a model (like Qwen) on the kiosk, then send your message again."
- Unknown which: "To use StickLLM from your phone, load a model (like Qwen) and start Agent Zero on the kiosk. Your message will send once Agent Zero is running."
- Held messages still send by themselves once Agent Zero answers. Same install (`adb install -r`, keep data). See evidence/methodc-kiosk/RESULT-0.3.7.md.
