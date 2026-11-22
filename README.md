# stickdroid

Phase 0 + Phase 1 prototype of a thin Android / GrapheneOS companion for
StickLLM. Typed chat, plus phone-side voice until real Phase 2 voice lands: an offline speech recognizer built into the app (Vosk, works on GrapheneOS) and the phone's TTS.

The phone pairs with the kiosk over Sticklink, does not talk to Agent
Zero itself, and never holds the A0 API key. A small `companiond` on the
stick calls A0 on loopback and ships progress plus the final answer back
over the existing Noise pipe.

Source is published at [github.com/StickLLM/stickdroid](https://github.com/StickLLM/stickdroid).
This repository is the Android client only; nothing here is automatically
pushed, flashed, or deployed to a production stick.

## Now: StickLink Method C kiosk QR (0.3.7-methodc)

0.3.7: friendly setup messages: "Start Agent Zero on the kiosk" / "Load a model (like Qwen) on the kiosk"; never a false "starting". See evidence/methodc-kiosk/RESULT-0.3.7.md.
0.3.5: idle sends go straight to Agent Zero (queue only while a reply runs; busy state always recovers); jump-to-newest button. See evidence/methodc-kiosk/RESULT-0.3.5.md.
0.3.6: accurate errors: "No model is loaded on the stick" instead of "Connection interrupted"; Agent Zero down/erroring is shown as a stick problem and the message waits visibly. See evidence/methodc-kiosk/RESULT-0.3.6.md.

0.3.4: messages sent during a reply queue on Agent Zero's own queue (in order, removable, Stop asks keep/clear); ☰ chats menu from Agent Zero's chat list with New chat; no chat data stored on the phone. See evidence/methodc-kiosk/RESULT-0.3.4.md.


0.3.3: + attachments (Files / Photos / Camera) sent as Agent Zero multipart message_async, optional "Steps (N)" on replies, live "Show system details". See evidence/methodc-kiosk/RESULT-0.3.3.md.

0.3.2: Reconnect control (soft drop → retry always-on resume now; revoked → "Session ended on the stick" + Scan QR; rate-limit cooldown) and Disconnect moved into Settings. See evidence/methodc-kiosk/RESULT-0.3.2.md.

0.3.1: hotfix, sess resume uses the live v2 PSK (pair_psk‖token) so WSS resume completes against the real stick; non-secret handshake logs. See evidence/methodc-kiosk/RESULT-0.3.1.md.

0.3.0: always-on WSS resume after first QR.

The app's main path is the kiosk's **StickLink QR** (Devices & Pairing, then
Refresh StickLink QR). See `INSTALL-6a.md` for the Pixel 6a (stock Android 17)
install and live-test steps, `PROTOCOL.md` for the flow, and
`evidence/methodc-kiosk/RESULT.md` and `RESULT-0.2.1.md` / `RESULT-0.2.2.md` / `RESULT-0.2.3.md` / `RESULT-0.2.4.md` / `RESULT-0.2.5.md` for box test results. 0.2.5 keeps the pairing after the first QR: on transport loss or a cold start it polls `/v1/resume` and reconnects without a scan, and only Disconnect (or a Generate on the stick) ends it (`evidence/methodc-resume/`). 0.2.4 builds speech-to-text into the app (offline Vosk model in assets; run `scripts/fetch-vosk-model.sh` before building; licenses in `THIRD_PARTY.md`). 0.2.3 added host speech (mic in, TTS out, optional hands-free) on the phone only. 0.2.2 added a waiting bubble, pinch-zoom, and hides the chat box until you are connected. Box harness:
`scripts/methodc/` (mock A0/gateway, stick peer wrapper, test orchestrator).
The sections below describe the older phase-1 lab path, which is still
available under Settings.

## Layout

| Path | What |
|------|------|
| `core/` | Pure Kotlin/JVM Sticklink client: QR parse, HKDF, X25519, ChaCha20-Poly1305, Ed25519, Noise IKpsk2, signalling, DERP, D_APP fragments, companion JSON. Unit-testable on the JVM. |
| `interop/` | CLI harness (`pair`, `connect`, `chat`) used against the box test stack. Same `core` as the app. |
| `app/` | Android app (minSdk 31, target/compile 35, Compose). CameraX + ZXing QR. No Google Play services, ML Kit, or Firebase. |
| `scripts/` | Box interop and a mock Agent Zero API. |
| `PROTOCOL.md` | App message protocol. |
| `PROGRESS.md` | Dated lab log (America/Barbados). |
| `evidence/` | Interop logs and RESULT notes. |
| `dist/` | Debug APK and sha256 (kept outside git). |

Stick-side prototype (not in this repo): see the
[StickLLM/stickllm](https://github.com/StickLLM/stickllm) tree, branch
`feature/companiond-prototype`:

- `overlay/usr/local/lib/sticklink/companiond.py`
- `daemon.py` forwards authenticated `D_APP` frames to that socket

## Build

Needs JDK 17, the Android SDK, and the Gradle wrapper (tested with Temurin 17
and Gradle 8.11.1). From the repo root:

```bash
./gradlew :core:test
./gradlew :interop:installDist
./scripts/fetch-vosk-model.sh
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
# A copy may also be kept in a local dist/ folder (not in git)
```

## Box tests (throwaway identities only)

These scripts default to a lab checkout of the stick-side sources. Override
`SL_ROOT`, `PY`, and `JAVA_HOME` to point at your own toolchain and a
StickLLM / StickLink checkout.

```bash
# later-session echo, 4 path modes (direct, peer-force-relay, udp-blocked, stick-force-relay)
./scripts/interop-box.sh evidence/interop-run

# companiond + mock A0, no Noise
PYTHONPATH=<stickllm-overlay>/usr/local/lib \
  python scripts/test-companiond-local.py

# full stack: pair, handshake, hello, short turn, paged long turn; direct and DERP
./scripts/interop-companion.sh evidence/companion-run
```

The stack is local `mock_signal` (ws://127.0.0.1:8788), HTTP derper
(:3341), `sticklinkd`, and (for companion tests) `companiond` plus
`scripts/mock_a0.py`. Fixtures come from the sticklink dev fixtures
(`stick1`). `STICKLINK_TESTING=1` and `STICKLINK_FAST_KDF=1` are set.
Logs are scanned for `pair_psk` / invite / seed material.

## What is verified vs not

See `PROGRESS.md` for dates, hashes, and log paths. Short version:

**PASS**

- JVM unit tests against qr-v1, ikpsk2-v2, kvm-usb shared derivations,
  signalling-v2, fragment and NaCl-box cross-checks, QR parser.
- Box interop of later sessions, four path modes, echo round-trips.
- companiond against mock A0 (local unix socket and full stack, direct
  and force-relay): welcome, first-turn wait pulse, short answer, 2-page
  long answer, error path.
- Debug APK assembled. Runtime classpath has no Play / ML Kit / Firebase.
- APK sha256 recorded under `dist/`.

**NOT TESTED**

- Real Pixel 8, GrapheneOS, or any real kiosk / stick.
- Production signalling (`wss://link.stickllm.com/v1/ws`) and
  `relay-tor1.stickllm.com`.
- Android Keystore on a device (the code is in `SecureStore.kt`; only
  compiled, not run on hardware).
- Camera QR scan on a device.
- Emulator screenshots (x86_64 without KVM skipped).
- Voice, wake word, on-device LLM.
- QR Method C mailbox (`pair.open` / `pair.join`) end-to-end: the phone
  can parse an `sl1:` QR, but current `sticklinkd` does not implement
  the mailbox. Lab pairing is the test-file offer/accept path; the app
  can also paste lab pairing JSON.
- `companiond` on a real stick image or against a real Agent Zero.

## App behaviour (Phase 1)

1. Home: scan an `sl1:` QR, or paste an `sl1:` string / lab pairing JSON.
2. If the QR is Method C and the stick has no mailbox, the UI explains
   that; use lab pairing JSON against a current `sticklinkd`.
3. Chat: Connect / Disconnect, text input, progress lines, final
   answers, Settings (signalling and DERP URLs), Unpair.
4. Pairing material is encrypted with an Android Keystore AES-GCM key,
   stored under `noBackupFilesDir`, and `allowBackup` is false.

Default endpoints in the app are the production hostnames, overridable
in Settings. The debug manifest allows cleartext so the lab HTTP derper
works; tighten this before a real-network build.

## What a real Pixel 8 + kiosk test would need (Nink)

This box will not flash, deploy, remint, or touch the real stick, vault,
Agent Zero, or llama.

1. Sideload `dist/stickdroid-0.1.0-phase1-debug.apk` on an unlocked
   Pixel 8 running GrapheneOS (no Play required).
2. Install the `feature/companiond-prototype` stick-side pieces
   (`companiond.py` and the `daemon.py` forwarder) on a **test** stick
   or image, with `--companion-sock` and an A0 key file that only
   companiond can read. Point `--a0-url` at the stick's loopback A0
   (`http://127.0.0.1:5099`).
3. Pairing: until `pair.open` exists on the kiosk, use a lab offer JSON
   (or implement Method C on the stick first). Do not remint production
   slots casually; prefer a throwaway slot.
4. In Settings, set signalling and DERP to the test or production
   endpoints you actually intend, then try same-LAN first, then LTE
   with DERP fallback.
5. Grant camera only if you are scanning a real `sl1:` QR.

## License

StickDroid's own code is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE)
(`PolyForm-Noncommercial-1.0.0`): free for personal and other noncommercial use.
Commercial use, including selling devices that ship with StickDroid or offering paid
services based on it, needs a separate commercial license from Nink Inc. Contact via
GitHub: [github.com/StickLLM](https://github.com/StickLLM).

Third-party components remain under their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md).

Questions or feedback: [@stickllm on X](https://x.com/stickllm)
