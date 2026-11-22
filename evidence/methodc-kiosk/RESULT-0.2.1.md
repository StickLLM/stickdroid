# StickDroid 0.2.1 evidence (box only), 2026-11-19 (America/Toronto, EST)

Phone-side only. No production link.stickllm.com, relay-tor1 or public STUN
was contacted (ICE lists were forced empty). No stick, kiosk, vault, A0 or
llama of Nink's was modified.

| Suite | Result |
|---|---|
| core unit tests (20 previous + 18 new: ReplyFilter, PhonePrompt+A0 mux, QrPreviewMapper, ScanQr) | **38/38 PASS** |
| run3 kiosk suite (`run3/`) | **10/10 PASS** |
| old phase-1 interop (`regression-interop-box-021/`) | **5/5 PASS** |
| `:app:assembleDebug` 0.2.1 | builds |

T04 still echoes the user's words only (the hidden phone instruction is
stripped by the mock before classify/echo). T09 sent `DUMP hello`; the mock
returned the system-details dump and the harness logged `FILTERED dump -> Hello!`.

APK: `dist/stickdroid-0.2.1-methodc-debug.apk` (94495656 bytes)
sha256 `1dbcaca098823e6854eb3636080c7deb4dad69b2f86a6a182203a8df4670773b`

Not tested here: camera overlay on a real viewfinder (needs the Pixel 6a).
