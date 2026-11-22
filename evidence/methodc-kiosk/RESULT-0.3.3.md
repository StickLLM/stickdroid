# StickDroid 0.3.3-methodc (versionCode 11): attachments (+), Agent Zero steps, live "Show system details"

## How Agent Zero v2.15 takes attachments (agent0ai/agent-zero @ ba9b67df, pinned in StickLLM 0.7.7 `overlay/usr/local/share/stickllm/agent-zero.pin`)
- **Endpoint:** the same `POST /api/message_async` (`api/message_async.py` subclasses `api/message.py` `Message`). There is no separate upload step.
- **Format:** `api/message.py` L29-50: if `request.content_type.startswith("multipart/form-data")` it reads
  `request.form["text"|"context"|"message_id"]` and `request.files.getlist("attachments")`. Each file is saved as
  `safe_filename(attachment.filename)` (`helpers/security.py` L21-50) into `usr/uploads`, and the agent gets the path `/a0/usr/uploads/<name>`.
  Otherwise (L51-57) it takes JSON `{text, context, message_id}`.
- **Web UI does exactly this:** `webui/index.js` L142-155 builds `FormData` with `text`, `context`, `message_id`, then one `attachments` part per file,
  and POSTs it with `api.fetchApi`. That adds `X-CSRF-Token` (`webui/js/api.js` L78). Text-only messages stay JSON (L157-170).
- **CSRF:** `helpers/api.py` L190-201 `csrf_protect`: the `X-CSRF-Token` header (or cookie) must equal the session token, otherwise it returns 403. The phone sends the same header as for text, re-fetches the token on a 403 and retries once.
- **User log item:** `helpers/message_queue.py` L141-147 logs `type=user, id=message_id, kvps={"attachments": [names]}`. 0.3.3 now matches its own user item by `id == message_id` before falling back to matching the text.
- **A0 server limit:** 5 GiB (`helpers/ui_server.py` L47 `UPLOAD_LIMIT_BYTES`, applied L90-102). The StickLLM gateway's `/agent-zero` relay (`stickllm-gateway` `_relay_http`) doesn't add a cap.
- **One A0 behaviour to know:** files are saved by name, so a second upload with the same name overwrites the first. The web UI does the same. Camera shots are named `photo-YYYYMMDD-HHMMSS.jpg` to avoid clashes.

## Limits over StickLink (the binding ones)
- **Stick app-mux bridge:** a request body over 8 MiB is refused with HTTP 413. Source: `app_mux_bridge.py` L63 `MAX_BODY = 8 * 1024 * 1024`, checked at L189. I read this in the box copy of the live v2 stick-client (`peer031` = `sd031`); `sticklink` isn't in the public StickLLM repo, so I didn't diff it against the 0.7.7 image.
  - In the app: per message, files plus form fields must fit in 8 MiB − 64 KiB (≈ 8.32 MB).
  - The per-file cap is the nominal 20 MB, clamped to that bridge limit, and there's a 10-file cap.
  - Adding a file that's too big shows a clear error naming the file and the 8 MiB link limit. A body over 8 MiB is also refused before sending (A0Error 413).
- **Always-on Worker frame rate:** `SESS_RATE_PER_S = 100`, `SESS_BURST = 200`. An 8 MiB upload is about 230 chunks of 36 KiB, so AppMux now waits 15 ms between request chunks after the first 16 (≈ 66 frames/s).
  The E2E sent a 6.7 MB body with no `err rate`.

## Changes
1. **'+' button left of the composer** opens a bottom sheet with three options:
   - **Files:** SAF `OpenMultipleDocuments`, `*/*`.
   - **Photos:** Android Photo Picker `PickMultipleVisualMedia(10)`, images only.
   - **Camera:** `TakePicture` into `cacheDir/camera/` via a FileProvider (`${applicationId}.files`). It asks for CAMERA at runtime only when Camera is tapped, and the cache file is deleted after it's read. No new permissions (the manifest still has INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO).
   - **Chips:** chips above the composer show a thumbnail or file icon, the name, the size and a remove (×) button. Errors appear above the composer with a dismiss button.
   - **Sending:** Send works with attachments and no text. The user's bubble shows image thumbnails (up to 3, then "+N more") and file name · size for other files.
2. **Settings → "Show Agent Zero steps"** (off by default):
   - Every A0 log item after the user's message, except the reply itself, is kept on the reply: type, heading (icon:// prefix removed), tool name (`kvps._tool_name` or the "Using tool '…'" heading), content and kvps, about 4 KB per step, up to 200 steps.
   - It's shown as a collapsed "▸ Steps (N)" section; tap to expand. The Thinking bubble is unchanged.
   - Steps are always captured, so switching the setting on also shows them on earlier replies.
3. **"Show system details"** now changes earlier replies too: the raw reply is stored and `ReplyFilter` runs when it's displayed. The subtitle now reads: "Show the date, model and agent info Agent Zero adds to replies. Off hides it. Changes earlier replies too."
4. Version 0.3.3-methodc, versionCode 11.

## Tests (box only)
- **core unit tests: 169/169 pass.** That's the 152 from before plus 17 new:
  - multipart layout and order matching the web UI FormData
  - empty context still sent
  - filename escaping and path stripping
  - random boundary
  - size estimate
  - per-file, total and count limits
  - steps capture, tool name, ~4 KB truncation, non-string kvps, 200-step cap
  - A0Chat multipart over a fake chunk-reassembling bridge, with the user item matched by message_id and steps captured
  - text-only messages stay JSON
  - over-8-MiB refused before sending
  - chunk pacing
- **mock A0:** `scripts/methodc/mock_gateway.py` now parses multipart with werkzeug's `Request` (Flask 3.0.3 / werkzeug as in A0 requirements, in `venv-a0mock`). It runs the multipart branch from A0 `api/message.py` L31-50 and a verbatim copy of `safe_filename`. It logs the user item with `id=message_id` and `kvps.attachments`, emits an A0-shaped tool step (`_tool_name`), and replies with each saved file's name, size and sha256.
- **always-on E2E against the live v2 stick peer + Worker** (fresh D1 `d1-033`, ports 8802/8444, loopback): **37/37 PASS**, see `evidence/methodc-always-on-033/`. New 2c checks:
  - The upload goes over the WSS sess link.
  - A multipart with 3 files (220 KB image, 6.5 MB binary, a `notes: v1?.txt`) arrives byte-exact (sha256 matched) and the reply comes back.
  - A0's `safe_filename` turned the last name into `notes_ v1_.txt`.
  - The `vision_load` tool step and its paths are captured.
  - A raw 8.5 MiB body gets a real 413 from the stick bridge.
  - The link is still live afterwards and chat works.
  - All 0.3.2 checks still pass.
- Nothing contacted link.stickllm.com, relay-tor1, the real stick/kiosk, or the phone. Logs show tokens only as …last4.

## Compile-checked only (not run on a device or emulator)
- All Compose UI: the '+' sheet, the SAF/Photo Picker/TakePicture launchers, the runtime CAMERA prompt, FileProvider URIs, chips and thumbnails (BitmapFactory), bubble thumbnails, the Steps section, the Settings toggles, and re-rendering when "Show system details" changes.
- ContentResolver name/size queries and reads of real provider URIs (Drive, Photos, Downloads).
- Real Agent Zero v2.15: the multipart format is matched from source and against werkzeug in the mock, but not sent to a real A0. How a real model handles the uploaded files is untested.

## Artifact
`stickdroid-0.3.3-methodc-debug.apk`: 158342766 bytes, sha256 `a0f1a835e29d0ba56dd6804a3d18ca216186221fc085ab8b82de6984bce8d8e5`. It's debug-signed with the same certificate as 0.3.1/0.3.2 (`f3f523a8…bd27864`).
