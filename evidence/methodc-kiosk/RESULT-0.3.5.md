# StickDroid 0.3.5-methodc (versionCode 13): send straight away when idle, queue only during a reply; jump-to-newest button

Fixes Nink's report on 0.3.4 (2026-11-21 11:17 AM ET): "it wants to put everything into the queue... if there's nothing in the queue, it just gets sent straight away."
Box build and tests only: not installed, not pushed.

## Root cause (0.3.4)
The phone's logcat could not be read: at 11:17-11:35 AM ET the Pixel 6a was not attached to the desktop (`adb devices` empty), so this is from the code, reproduced in tests.

1. **Every message went through the queue first.** `A0Conversation.submit()` always appended the message to the queue list and announced it
   (`onQueue`), so even on an idle chat it appeared under **Queued (1) · waiting to send** until the worker thread had
   (a) polled A0 with `log_from=0` (the whole chat log, over StickLink; slow on a long chat) and (b) posted `message_async`.
   On the stick that is seconds, so every send looked queued.
2. **"Busy" could stay on after a reply, so later sends really were queued.** `A0Chat.awaitTurn()` only ended a turn when a `response` item
   existed *and* A0 said not running. A turn that ended without a response (LLM error, a Stop that reached A0 after a link resume, a Stop from
   the web UI, A0 restart) was polled for 10 minutes, then the timeout (status 0) was treated as a link error, so the turn was kept and followed
   again forever. `busy` stayed true, Send stayed **Queue**, and every new message was pushed to A0's queue, where nothing sends it
   (A0 is idle; the phone only kicked A0's queue when it wasn't following a turn).
3. **Stop didn't survive a link resume.** The cancel flag lived on the `A0Chat` object; after a resume the turn was followed with a new `A0Chat`
   whose flag was false (same endless follow as 2).
4. The Send button label used only `busy`.

## Fix
- **Direct unless really busy** (`A0Conversation.canSendDirect()`): a message goes straight to `message_async` when this chat is not running per
  A0's latest poll for *this* context (`contexts[].running`), no reply is being followed, and both the phone's and A0's queue for this chat are empty.
  Such a message is marked `direct` and never shown in the tray. If the worker's check finds A0 running after all (e.g. a reply started in the web
  UI), it becomes a normal queued item (shown, upload + `message_queue_add`), never an intervention.
- The pre-send check polls with `log_from = 1e9` (A0 returns no log items, only the chat list; `state_snapshot.py` / `log.output` slice), so it's quick.
- **Turns always end:** `awaitTurn` ends a turn when A0 reports the chat not running on two polls in a row, with or without an answer
  ("(no answer)" / "Stopped."), gives up if our own message never shows and A0 stays idle (3 polls), and treats a missing chat as not running.
  A timeout (`A0Chat.TIMEOUT`) now ends the turn instead of retrying forever.
- **Stop survives a resume:** `A0Conversation.stopRequested` is passed to every `awaitTurn`.
- Busy recovers after: reply end, Stop, a turn with no answer, link drop/resume, chat switch (switch resets running/queue state).
- Send button: **Send** when idle; **Queue** only while a reply runs or messages are already waiting.
- Log lines `submit: direct` / `submit: queue (busy=… a0Running=… local=… a0=…)` (no message text) to check this on the phone next time.

## Also in 0.3.5: jump to newest (Nink, 11:18 AM ET)
- A small floating **down-arrow** at the bottom-right of the messages appears when you scroll up away from the newest message; tap it to jump to the newest.
  A badge counts replies that arrived while you were scrolled up (9+ max).
- The list no longer jumps to the bottom while you're reading older messages. It follows new messages only when you're at the bottom, or when you send one.
- Logic in `core/ScrollFollow.kt` (unit-tested); the Compose wiring is in `ui/Screens.kt`.

## Tests
- **Core unit tests: 188/188** (was 179). New `QueueTransitionsTests` (9): idle send goes direct and never shows as queued; send during a reply
  queues, then the next send after it is direct; busy clears after Stop and the next send is direct; Stop survives a link resume; a turn that ends
  without an answer ends and clears busy; busy clears after a link drop when the reply finished meanwhile; chat switch clears busy and the new chat
  sends direct; a reply started in the web UI makes the phone queue (not interrupt) and the phone's reply lands under the right message; scroll-follow rules.
  The 0.3.4 queue tests still pass (ran the queue suites 3 extra times: stable).
- **Box E2E: 69/69 checks PASS** (`interop methodc-resume`, local StickLink Worker 127.0.0.1:8802 → stick peer → mock A0 8444). New step **2e**:
  idle send = 0 queue_add, 1 message_async, never in the tray; send during a running reply is queued and both are answered in order; busy clears;
  the next send is direct: 1 queue_add (only the one during the reply), 2 message_async, 0 interventions. All 0.3.4 checks (2d etc.) still pass.
  Evidence: `evidence/methodc-always-on-035/`.
- **Compile-checked only:** the Compose UI (Send/Queue label, jump-to-newest button, badge, no-yank scrolling), the ViewModel wiring, behaviour on
  the phone and with the real Agent Zero on the stick.

## No user data on the phone
Unchanged from 0.3.4: the queue is in memory only; chats and history come from A0; nothing new is written to disk.

## APK
`dist/stickdroid-0.3.5-methodc-debug.apk` (158522990 bytes), also `%USERPROFILE%\stickdroid-dist\`.
sha256 `e9356be60cdf70c7682764473aa89c490c6844abaf28212c37eb5601e785d686`. Debug signer SHA-256 `f3f523a8…bd27864` (same as 0.3.1-0.3.4; installs over it, keep data).
