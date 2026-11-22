# StickDroid 0.3.4-methodc (versionCode 12): message queue, chats drawer, no user data on the phone

Built on `fix/camera-result-0.3.4` (6ac90a9, camera result fix). Box build and tests only: not installed, not pushed.

## How Agent Zero v2.15 handles a message sent while a reply is running (agent0ai/agent-zero @ ba9b67df)
- **`message_async` into a running chat is an *intervention*, not a queue.** `agent.py` `communicate()` L269-286: if the context is running,
  the message is injected into the current reply (the agent sees it mid-task). The phone never does this.
- **The native queue is server-side**, in `helpers/message_queue.py`:
  - `add` L45-71 stores `{id, text, attachments}` in `context.data`; `_sync_output` L29-42 mirrors it to `output_data["message_queue"]`
    (items `{id, seq, text≤100 chars, attachments (basenames), attachment_count}`), so it shows up in every poll's `contexts[]`.
  - `remove` (no `item_id` = clear all), `pop_first`, `send_message` L150-158 (logs a `user` item with a fresh uuid id, content = text),
    `send_next`, `send_all_aggregated`.
  - APIs: `api/message_queue_add.py` `{context, text, attachments: [filenames], item_id}` → `{ok, item_id, queue_length}`;
    `api/message_queue_remove.py` `{context, item_id?}`; `api/message_queue_send.py` `{context, item_id | send_all | (pop first)}`.
  - Files are uploaded first with `api/upload.py` (N × multipart `file`, `safe_filename`) → `{filenames}`.
- **Auto-send:** `extensions/python/process_chain_end/_50_process_queue.py` sends the next queued item after a reply finishes normally
  (waits until the context is not running, up to 60 s). One item per reply, so items go out in order.
- **Stop:** `api/stop.py` kills the process; `agent.py` `_process_chain` L301-320 then skips `process_chain_end`, so **A0 does not auto-send after a Stop**.
- **Persistence:** the queue lives in `context.data` and is saved with the chat (`persist_chat` L200), i.e. on the stick.
- **Web UI:** `webui/index.js` L107-116 queues when `running || hasQueue`; an empty Send with a queue = send all.
  `message-queue-store.js` `addToQueue`: `/upload` with FormData `file` (L94-106), then `/message_queue_add` (L109-122); also `removeItem`, `clearQueue`, `sendItem`, `sendAll`.

## How Agent Zero lists chats (same list as the web UI)
- Every `poll` returns `contexts` = `AgentContext.output()` (`agent.py` L196-218: `id, name, created_at, no, log_guid, log_version, log_length,
  paused, last_message, type, running, **output_data` incl. `message_queue`), built in `helpers/state_snapshot.py` L304-361, newest `created_at` first.
- History: `poll {context, log_from: 0}` → log items (`helpers/log.py` `output` L396-411).
- New chat: `api/chat_create.py {current_context}` → `{ok, ctxid}`. (`chat_reset.py`, `chat_remove.py` exist; the phone doesn't use them.)
- Web UI names: `chats-store.js` `displayName` L147-150 (`parent_context_label || name || "Chat #"+no`), `selectChat` L153, `newChat` L266-284.

## What 0.3.4 does
1. **Queue (matches the web UI):** the composer stays usable during a reply; Send becomes **Queue**.
   - Idle → `message_async` as before. Busy → upload attachments (`/upload`) then `message_queue_add`, strictly in order (one worker thread).
   - A **Queued (n)** tray above the composer lists each item (text + attachment names), marked "waiting on Agent Zero" (on the stick's queue) or
     "waiting to send" (still on the phone, e.g. link down). Each has an **×** (removes from the phone, or `message_queue_remove` on A0). **Clear queue** clears both.
   - A0 auto-sends the next item when a reply finishes; the phone follows each queued turn and shows its reply under the right message
     (a turn ends at the next user item, so replies can't be mixed up).
   - **Stop** still stops the current reply. With items queued it asks **Stop, keep queue** / **Stop and clear queue**. Keep → the phone sends the next item
     (`message_queue_send`) because A0 doesn't after a Stop.
   - Link drop / resume / backgrounding: items not yet on A0 wait in memory and are pushed when the link is back; the reply in progress is re-followed.
   - **Memory only.** The phone part of the queue lives in the ViewModel (process). It survives link drops and backgrounding, **not an app kill**
     (items already on A0's queue live on the stick and are still sent).
2. **Chats drawer (hamburger):** lists Agent Zero's chats from `contexts` (name or "Chat #n", date, a dot if running), current one highlighted,
   **New chat** on top. Tapping a chat loads its history from A0 (phone prompt wrapper hidden, attachment names kept) and continues in it.
   If a reply is running there, the phone follows it. Switching with unsent phone-side items asks first (they are dropped; the old reply keeps running on A0).
   The current chat is kept across link resumes (memory only).
3. **No user data on the phone** (Nink, 9:58 AM rule). See the inventory below. `PhoneData.cleanUp()` runs at app start.
4. Version 0.3.4-methodc, versionCode 12.

## On-disk inventory after 0.3.4 (everything the app keeps)
| Where | What | Notes |
|---|---|---|
| `noBackupFilesDir/pairing.bin` | pairing record (pinned statics, token) | AES-GCM, key in Android Keystore (SecureStore) |
| `noBackupFilesDir/resume.bin` | always-on resume record | AES-GCM, Keystore key |
| `shared_prefs/stickdroid.xml` | settings only: ws, derp, lab_hosts, show_system_details, show_a0_steps, speech_auto_send, speech_read_aloud, speech_engine, speech_hands_free | no chat text, no context ids |
| `filesDir/vosk/<model>` + `.unpacked-<versionCode>` | offline speech model (app asset unpacked) | no user audio |
| `cache/camera/photo-*.jpg` | camera shot, transient | deleted as soon as it is read into the message; leftovers > 15 min deleted at start; newer ones are recovered once then deleted |

- Chats, replies, history, the chat list and the queue: **never on disk**, in this or any earlier version (checked all writers: SecureStore, prefs, Vosk unpack, camera cache).
  Upgrade cleanup still deletes any `chats/ history/ transcripts/ queue/ outbox/` dirs and any SQLite databases, in case.
- Voice: `AudioRecord` PCM goes straight to Vosk in memory; nothing is recorded to a file. TTS doesn't write files.
- Picker files (Files/Photos) are read through their content URI into memory; nothing is copied to disk.
- Logs (`logcat`) carry no message text, filenames, tokens or keys.

## Tests
- **Core unit tests: 179/179** (`./gradlew :core:test`), incl. new `QueueAndChatsTests` (8): two messages queued during a reply arrive in order through
  A0's queue (0 interventions); remove; Stop+keep; Stop+clear; link drop/resume with a queued item; chat list/history/new chat/switch; adopt a running chat;
  a turn ends at the next user item. Plus `CameraShotsTests`.
- **Box E2E: 60/60 checks PASS** (`interop methodc-resume`, real StickLink Worker on 127.0.0.1:8802 → stick peer `peer031` app-mux → mock A0 gateway with
  v2.15 queue semantics on 8444). New step 2d: WAIT running → Q1 + Q2 (150 KB photo) queued → replies in order WAIT, Q1, Q2, each matching its message;
  mock stats queue_add=2, upload=1, queue_sent=2 (auto), message_async=1, **intervention=0**; queued item across a link drop delivered in order, same chat;
  remove + Stop/clear sends nothing else (A0 queue 0); Stop/keep answers the kept item; chat list, history from A0, new chat listed first, its history only its own, switch back continues.
  Evidence: `evidence/methodc-always-on-034/` (run1-console.txt, peer.log, mock-gateway.log, mock-stats.json).
- **Compile-checked only:** all Compose UI (drawer, queue tray/chips, Stop dialog, switch dialog, composer Queue button), the ViewModel wiring,
  behaviour on the phone, and real Agent Zero (the E2E uses a mock that implements the A0 code paths above).

## Known limits
- Same-name uploads overwrite each other in A0 `usr/uploads` (A0 behaviour; camera names are time-stamped).
- After an app kill, phone-side items not yet on A0 are lost (by design: memory only).

## APK
`dist/stickdroid-0.3.4-methodc-debug.apk` (158490226 bytes), also `%USERPROFILE%\stickdroid-dist\`.
sha256 `5933f3bf0f7b4b5c1ac2b23a428a3db22fe71ecd595f0e16fde3059d30885763`. Debug signer SHA-256 `f3f523a8…bd27864` (same as 0.3.1-0.3.3, installs over it).
