# StickDroid 0.3.2-methodc (versionCode 10): Reconnect control + Disconnect in Settings

## Field context (0.3.1, Pixel 6a, 2026-11-20)
- 17:12 ET: network blip → auto-resume worked (sess.ok, noise ok psk=v2, token rotated). No rescan.
- 17:38 ET: server closed the live WSS with code=4403 reason=revoked. 0.3.1 did go to NeedScan internally,
  but the in-flight turn showed `Agent Zero: …revoked` and the screen said "Connection lost", so a soft drop
  and a revoke looked the same and there was no clear way out.

## Changes
1. **Disconnect → Settings → Connection** (confirm dialog). Same behaviour as before: `close{why:user}`,
   clear always-on pins, back to the scan/home screen. Removed from the chat header.
2. **Reconnect control** (`LinkManager.reconnectNow()`, pure core, unit-tested):
   | State | UI | Reconnect does |
   |---|---|---|
   | Soft drop: `Reconnecting` (between attempts) | spinner "Reconnecting…", detail, **Reconnect** | wakes the loop, retries sess.resume (PSK v2) now, backoff + give-up timer reset → `RETRYING_NOW` |
   | Attempt on the wire: `Connecting(viaResume)` | button disabled, label "Reconnecting…" | no-op → `IN_FLIGHT` (never a second concurrent resume; Worker would 4009-replace) |
   | Gave up: `Paused` | message, **Reconnect** + **Scan QR** | starts a fresh resume loop → `STARTED` |
   | ≥3 failed soft resumes | adds "Still can't reach the stick?…" + **Scan QR** next to Reconnect | n/a |
   | Revoked: WS 4403 (live or on resume), `sess.revoked`, 4401/auth after one read-only legacy check, GET 400 | "**Session ended on the stick. Scan a new StickLink QR.**" + **Scan QR** | nothing → `SCAN_REQUIRED`; all loops stopped (gen bump), pins cleared; never shows Reconnecting again |
   | Rate-limited: err `rate`, WS 4429, HTTP 429 | `Blocked`: message (stick/Worker text if sent, else "StickLink is limiting reconnects from this network. Waiting a minute before trying again."), countdown, Reconnect disabled until retry time | → `COOLDOWN`; loop waits the server's `retry`/Retry-After (default 60 s, clamp 5 s..15 min); foreground wakes don't cut it short |
   | Stick refused: err `busy` / `disabled` | `Blocked`: "Your stick says: <msg>" or a default; countdown | as above (default 30 s) |
   | err `offline` (stick not on the Worker) | soft: "Reconnecting… (your stick is offline right now)" | normal backoff |
   - Reconnect never calls the Method C connector or writes anything that mints/Generates; the only HTTP is the existing read-only `GET /v1/resume` check.
   - Chat bubble on a revoke now says "Session ended on the stick." (not `Agent Zero: 4403`); soft drops say "Connection interrupted."
   - Before 0.3.2 an unknown `sess.resume` err (incl. `rate`, `busy`, `offline`) was treated as AUTH → pairing cleared. Now only `auth`/`nf` are.
3. versionName 0.3.2-methodc, versionCode 10. PSK stays v2 (HKDF(pair_psk‖token, info sl1/sess-psk/v2)); no v1 path added.

## Tests (box only)
- **core unit: 152/152 pass** (133 previous + 19 new in `ReconnectTests.kt`):
  soft drop → tap → resume (no join/mint), tap resets backoff (2,4,8 → 2 s), Paused → STARTED,
  in-flight dedupe (3 taps during sess.resume → IN_FLIGHT, one attempt, no extra loop),
  live 4403 / `revoked` → NeedScan(revoked), no further resumes or waits, tap → SCAN_REQUIRED,
  resume REVOKED mid-loop stops, AUTH after legacy → revoked, N=3 fallback flag,
  rate-limited → Blocked with server retry (tap during cooldown → COOLDOWN, one retry after),
  stick busy message shown, no pins → SCAN_REQUIRED, Disconnect still sends close(user),
  Scan after revoke → live; err-frame mapping (rate/retry, busy, offline, auth, msg).
- **always-on E2E vs the live v2 stick peer** (`peer031` = stick-client methodc_peer.py v2, Worker = sticklink-server-030 src,
  mock A0/gateway; loopback, fresh D1 `d1-032`, ports 8802/8444): **26/26 PASS** — `evidence/methodc-always-on-032/run1-*`.
  New checks vs 0.3.1's 18: 2b soft drop + 5 Reconnect taps → exactly one resume, live in ~0.1 s, chat OK;
  5 revoke → NeedScan(revoked, new copy), Reconnect → SCAN_REQUIRED, 0 resume attempts over 5 s;
  6 Scan QR after revoke → live + chat.
- Nothing contacted link.stickllm.com, relay-tor1, the real stick/kiosk, or the phone. Logs show tokens as …last4 only.

## Not tested
- The Compose UI itself (no emulator/device run): buttons, countdown, Settings Disconnect dialog are compile-checked only.
- On-device: GrapheneOS/Wi-Fi-off/cellular, USB-unplugged, real network kill → Reconnect, real kiosk remint → revoked path.
- Rate-limit / busy paths against the real Worker (unit-tested with mapped errors only; the lab Worker wasn't driven into its limiter).
- Known, pre-existing (also in 0.3.1 evidence): 2× "drop bad sess.frame" right after each resume that follows a chat (sess.have replay); harmless.

## Artifact
`/workspace/stickdroid-dist/stickdroid-0.3.2-methodc-debug.apk` — 158243994 bytes,
sha256 `3bda23d645240eaa4b79282b4b118110c7ed0ff57e7c1d697c2925f8e8b4083d`, debug-signed with the same cert as 0.3.1
(`f3f523a8…bd27864`), so `adb install -r` upgrades in place and keeps the saved pairing.
