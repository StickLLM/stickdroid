# StickDroid 0.2.4 evidence (box only), 2026-11-19 (America/Toronto, EST)

Phone-side only. No production link.stickllm.com, relay-tor1 or public STUN
was contacted (lab wrangler/mock on 127.0.0.1, ICE lists forced empty). No
stick, kiosk, vault, A0 or llama of Nink's was touched.

| Suite | Result |
|---|---|
| core unit tests (78 from 0.2.3 + 6 EngineSelector + 9 Endpointer + 3 Pcm/Vosk JSON/transcript + 3 TTS-exclusion + 1 UtteranceLoop) | **101/101 PASS** |
| run6 kiosk suite (`run6/`, `run6-console.txt`) | **10/10 PASS** |
| old phase-1 interop (`regression-interop-box-024/`) | **5/5 PASS** |
| box Vosk transcription (`../stt-box-024/`) | 8 clips, see README there |
| `:app:assembleDebug` 0.2.4 | builds; libvosk/libjnidispatch 16 KB aligned |

APK: `/workspace/stickdroid-dist/stickdroid-0.2.4-methodc-debug.apk` (158096538 bytes)
sha256 `fe3457e501cb40509c1b7ac5cae60a7066df05d0d9e14a65bc1a9e546588f136`
