# stickdroid progress log

Times are America/Barbados (AST, UTC-4).

## 2026-11-12

### Toolchain

- apt was blocked on this box (Debian InRelease via 198.18.0.1). Temurin
  17.0.21+9, Gradle 8.11.1, and Android cmdline-tools (platforms;android-35,
  build-tools;35.0.0) were installed under `/workspace/.tooling`.
- Python venv at `/workspace/.tooling/venv` with `--system-site-packages`
  and `websockets==13.1` (17.x broke `mock_signal`'s `handler(ws, path)`).

### Core and unit tests

- Kotlin JVM core in `core/` matches PAIRING-SPEC / stick-side
  `noise.py` and the v2 vectors.
- Fixed `MAX_NONCE`: Kotlin `1L shl 64` is 1 (shift mod 64), which made
  the first Noise tests fail with "nonce exhausted". Cap is now `1L shl 60`.
- `./gradlew :core:test` — **PASS** (13 tests):
  ikpsk2-v2 byte-exact, wrong PSK, prologue mismatch, replay window,
  qr-v1, kvm-usb and kvm-usb-extra shared derivations, signalling-v2
  hello/join/box, QR parser, NaCl box vs PyNaCl, fragments, protocol
  envelope.

### Session interop (no companiond)

- First run **FAIL** (`evidence/interop-first/`): mock_signal vs
  websockets 17, and derper required `-c` key file.
- After pinning websockets 13.1 and passing a throwaway `derper.key.json`:
  `scripts/interop-box.sh` → `evidence/interop-2/` — **PASS** all four
  modes (direct 24/24 udp, peer-force-relay 24/24 derp, udp-blocked
  32/32 derp, stick-force-relay 32/32 derp). No secrets in logs.

### App / APK

- Compose app, CameraX + ZXing, Keystore-backed `SecureStore`,
  `allowBackup=false`.
- Debug APK:
  `/workspace/stickdroid/dist/stickdroid-0.1.0-phase1-debug.apk`
  sha256 `e8ad9ec862c9604334ceb13488430e962f16a7aaf6386d91cd6f3d2835e210a4`
  (67 MiB, re-hashed 2026-11-12 08:00 AST; unchanged since assemble).
- Dependency audit of the debug runtime classpath: no Play / ML Kit /
  Firebase / AICore.
- Emulator screenshots: **NOT TESTED** (skipped; no KVM).

### companiond prototype

- New local branch `feature/companiond-prototype` on
  `/workspace/sticklink/stick-build/stickllm` (not a release branch).
  Files: `companiond.py`, `daemon.py` forwards `D_APP` with the
  authenticated slot, `--companion-sock`. Pre-existing `out/.gitkeep`
  deletion was left unstaged.
- 07:59 AST `scripts/test-companiond-local.py` →
  `evidence/companion-local/` — **PASS** (welcome, wait pulse, short
  echo, 2-page 14399 B answer, error envelope). Mock A0 only.
- 08:00 AST first full-stack run `evidence/companion-1/` — **FAIL** on
  force-relay: companiond process was reused, so the second mode was
  treated as a follow-up turn (no wait pulse). Direct mode had already
  **PASS**ed. Welcome was also bloated with the full pending long
  answer; preview is now truncated to 80 chars.
- 08:00 AST `scripts/interop-companion.sh` → `evidence/companion-2/` —
  **PASS** direct and force-relay (fresh companiond per mode). Each
  mode: welcome, wait pulse, `echo:hello from stickdroid`, then a
  2-page long answer. No secrets in logs. Handshake ~2.2 s direct,
  ~6.8 s force-relay (includes the 1.5 s DERP fallback timer).

### Still open / not tested

- Real Pixel 8 + real kiosk: **NOT TESTED** (needs Nink; we will not
  flash or remint).
- Production WSS / DERP, device Keystore, camera scan: **NOT TESTED**.
- sticklinkd QR mailbox (`pair.open`) still not implemented; lab
  pairing is file offer/accept.
- First-turn A0 progress is a wait pulse until A0 can return
  `context_id` immediately.
- Cancel does not call `api_terminate_chat` (would delete the chat).
- Voice / local bot: not this phase.
- Debug manifest still allows cleartext (lab HTTP derper).


## 2026-11-19 (America/Toronto, EST): Method C kiosk QR (branch feature/methodc-kiosk-qr)

- Phone side of StickLink Method C (Milestone A) ported from the kiosk's
  methodc_peer.py and agent_join_client.js. The phone scans the kiosk join URL
  (or a bare sl1:), joins the mailbox, then WebRTC DC "sl1", Noise IKpsk2,
  pair_psk confirm and APP-MUX, then A0 chat via /agent-zero/api/message_async + poll.
- core 20/20, box kiosk suite 10/10 (run2), old interop 5/5. See
  evidence/methodc-kiosk/RESULT.md.
- APK dist/stickdroid-0.2.0-methodc-debug.apk (sha256 in .sha256). Built, not
  run on a device or emulator.
- Edge-to-edge insets, network-security-config (no usesCleartextTraffic),
  scanner fixes (row stride, main-thread callback).
- Open: real kiosk and prod signalling NOT TESTED; Android WebRTC adapter not
  exercised at runtime; no reconnect (Milestone A: every session is a fresh scan).

## 2026-11-19 (America/Toronto, EST): 0.2.1 phone-side replies + scanner

- Hidden phone-context instruction on `/agent-zero/api/message_async`; user
  bubble still shows only what they typed. Display filter strips A0
  system-detail dumps; Settings toggle "Show system details" (default off)
  turns the filter off. User questions about time/model/system info skip it.
- Scan screen: dimmed viewfinder with corner brackets, a box that tracks
  ZXing result points (rotation, FILL_CENTER crop, row-stride width), green
  + haptic + 300 ms hold on a valid StickLink QR, amber/red and keep
  scanning on expired / non-StickLink, torch toggle when the camera has one.
- Tests: core **38/38** PASS (20 previous + 18 new: filter, prompt+A0 mux,
  preview mapping, scan classify). Method C kiosk suite **10/10** PASS
  (`evidence/methodc-kiosk/run3/`, including a mock DUMP turn filtered to
  "Hello!"). Phase-1 interop **5/5** PASS
  (`evidence/methodc-kiosk/regression-interop-box-021/`).
- APK `dist/stickdroid-0.2.1-methodc-debug.apk`
  sha256 `1dbcaca098823e6854eb3636080c7deb4dad69b2f86a6a182203a8df4670773b`.
- Untested on this build: the camera overlay on a real preview (needs the
  6a); live kiosk chat with the new filter (needs Nink to send "hello"
  again). No Agent Zero, stick, or kiosk code was changed.

## 2026-11-19 (America/Toronto, EST): 0.2.2 thinking bubble, zoom, connected-only input

- Waiting bubble: animated dots (reduced-motion respected), elapsed timer,
  sanitized one-line A0 progress, 60 s "Still working" hint. Replaced by
  the reply, or by the error on failure/disconnect. Send is Stop while busy.
- Chat composer (message box + Send) only when Connected; otherwise a
  prominent Scan StickLink QR button, with "Connecting…" during join.
- Scan screen: CameraX pinch-zoom, 1.0x-style indicator, double-tap reset.
  Tracking-box mapping unchanged (zoom crops preview and analysis together).
- Tests: core **44/44** PASS. Method C kiosk suite **10/10** PASS
  (`evidence/methodc-kiosk/run4/`, DUMP still filtered; WAIT delayed ~4.5 s
  and logged a thinking-bubble line). Phase-1 interop **5/5** PASS
  (`regression-interop-box-022/`).
- APK `dist/stickdroid-0.2.2-methodc-debug.apk` (94528424 bytes)
  sha256 `2090863a7c8611c0ae817b7cb54d92a4c8b312c6abdff6854cfb8117c72b4cd2`.
- Untested on this build: thinking bubble, pinch-zoom and the hidden
  composer on the real 6a UI (needs a live session). No A0/stick/kiosk
  code changed.

## 2026-11-19 (America/Toronto, EST): 0.2.3 host speech stopgap

- Phone-side only: Android SpeechRecognizer (mic in) and TextToSpeech (read
  filtered replies). No A0, stick, or kiosk changes. Box never contacted
  production.
- Mic button in the composer when Connected; hidden with a note if the
  system recognizer is unavailable. Runtime RECORD_AUDIO prompt before
  listen. Partials in the box, listening level, auto-send (toggle),
  on-device preferred, locale from the phone.
- Read-aloud: Never / After I speak (default) / Always. Markdown and code
  stripped; URLs spoken as "link". Tap the speaking bubble to stop. Stops
  also on mic or Send. Audio focus; TTS init failure is non-fatal.
- Hands-free (off by default): after TTS (or a skipped reading), listen
  again. Stops on silence or leaving the app.
- Tests: core **78/78** PASS (44 previous + 11 SpeakText + 23 SpeechMachine).
  Method C kiosk suite **10/10** PASS (`run5/`). Phase-1 interop **5/5**
  PASS (`regression-interop-box-023/`).
- APK `/workspace/stickdroid-dist/stickdroid-0.2.3-methodc-debug.apk`
  (94643288 bytes), not committed.
  sha256 `b556a3b62278096aa2b955dff0bf4209c815a7f55f9f58d13e0cd8ae7cf85022`.
- Untested on this build: real mic, TTS, audio focus, hands-free, and the
  permission prompt (need the Pixel 6a).

## 2026-11-19 (America/Toronto, EST): 0.2.4 built-in speech-to-text

- GrapheneOS has no system RecognitionService, so 0.2.3's mic was hidden on
  the 6a. 0.2.4 bundles an offline recognizer: Vosk 0.3.75 (Kaldi, Apache-2.0)
  with `vosk-model-small-en-us-0.15` (Apache-2.0, 40 MB zip / 68 MB unpacked)
  in APK assets. Unpacked to app storage on first use; no network.
- Engine choice in Settings: Built-in (default) or the phone's system
  recognizer. Pure `EngineSelector` falls back either way with a note.
- Endpointing (`core/Recognizer.kt`): 300 ms lead-in ignored (TTS tail/click),
  adaptive-noise-floor VAD, end after 1.1 s silence once words are heard,
  end on a 2.2 s transcript stall (noisy room), 7 s no-speech timeout, 30 s
  cap. `UtteranceLoop` joins Vosk segments so short pauses don't cut a
  sentence. AudioRecord VOICE_RECOGNITION + AEC/NS when available.
- TTS never overlaps the mic: SpeechMachine no longer speaks a reply while
  listening, and partials/finals are ignored while Speaking.
- Tests: core **101/101**, kiosk **10/10** (`run6/`), interop **5/5**
  (`regression-interop-box-024/`). Box transcription with the same model and
  endpointing code: `evidence/stt-box-024/`.
- APK `/workspace/stickdroid-dist/stickdroid-0.2.4-methodc-debug.apk`
  (158096538 bytes; +63 MB for model and natives)
  sha256 `fe3457e501cb40509c1b7ac5cae60a7066df05d0d9e14a65bc1a9e546588f136`.
- Build note: run `scripts/fetch-vosk-model.sh` once (checksum-pinned);
  preBuild fails clearly if the model is missing.

## 2026-11-19 (America/Toronto, EST): 0.2.5 durable Method C resume

- New `core` Resume.kt (parse, codec, policy) and LinkManager.kt (state machine:
  NeedScan / Connecting / Live / Reconnecting / Paused / Disconnected).
  MethodC.kt reads `agent_ready.resume`, supports `close(why)` and has an
  RX-silence watchdog.
- App: Keystore-encrypted resume store, plus Reconnecting/Paused UI with
  "Scan new code" and "Try again". Disconnect clears the pairing.
- Tests: core 127/127, resume E2E 17/17, kiosk 10/10, interop 5/5.
- APK `/workspace/stickdroid-dist/stickdroid-0.2.5-methodc-debug.apk` sha256 `5169b776c21ec0dcb47f41979bd341f57d9c79faceb051a2928cf4ad88aaef37`.
- Contract gaps (Worker/stick not patched): see `evidence/methodc-resume/README.md`.

## 2026-11-19 (America/Toronto, EST): 0.3.0 always-on WSS resume (slice 1)

- Persist AlwaysOnResume pins (token, origin, ws, home, stick_pub, phone_priv/pub, pair_psk, hwm).
- Wake via WSS sess.resume + Noise IKpsk2 (PSK v1 / sl1/sess prologue); default URL ?sess=1&home=.
- Disconnect / 4403 / 4401 → clear pins → NeedScan. Legacy Method C remint is one-shot only.
- APK sha256 . Commit message: feat(methodc): always-on WSS resume after first QR.
