# StickDroid 0.2.3 evidence (box only), 2026-11-19 (America/Toronto, EST)

Phone-side only. No production link.stickllm.com, relay-tor1 or public STUN
was contacted (ICE lists were forced empty). No stick, kiosk, vault, A0 or
llama of Nink's was modified.

| Suite | Result |
|---|---|
| core unit tests (44 from 0.2.2 + 11 SpeakText + 23 SpeechMachine) | **78/78 PASS** |
| run5 kiosk suite (`run5/`) | **10/10 PASS** |
| old phase-1 interop (`regression-interop-box-023/`) | **5/5 PASS** |
| `:app:assembleDebug` 0.2.3 | builds |

T09 DUMP still filtered to "Hello!". T09 WAIT delayed ~4.6 s and logged
`THINKING bubble after 4575ms -> Thinking… 4s | Searching`.

APK: `/workspace/stickdroid-dist/stickdroid-0.2.3-methodc-debug.apk` (94643288 bytes)
sha256 `b556a3b62278096aa2b955dff0bf4209c815a7f55f9f58d13e0cd8ae7cf85022`

Not tested here: real microphone, SpeechRecognizer, TextToSpeech, audio
focus, hands-free looping, or the runtime RECORD_AUDIO prompt. Those need
the Pixel 6a (stock Android 17). Some GrapheneOS builds may have no system
recognizer; the app hides the mic and shows a note in that case, also
untested here.
