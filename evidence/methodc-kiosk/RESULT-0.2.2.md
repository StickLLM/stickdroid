# StickDroid 0.2.2 evidence (box only), 2026-11-19 (America/Toronto, EST)

Phone-side only. No production link.stickllm.com, relay-tor1 or public STUN
was contacted (ICE lists were forced empty). No stick, kiosk, vault, A0 or
llama of Nink's was modified.

| Suite | Result |
|---|---|
| core unit tests (38 from 0.2.1 + 6 new: ThinkingStatus, delayed A0 progress, zoom mapping note) | **44/44 PASS** |
| run4 kiosk suite (`run4/`) | **10/10 PASS** |
| old phase-1 interop (`regression-interop-box-022/`) | **5/5 PASS** |
| `:app:assembleDebug` 0.2.2 | builds |

T09 DUMP still filtered to "Hello!". T09 WAIT delayed ~4.6 s and logged
`THINKING bubble after 4572ms -> Thinking… 4s | Searching`.

APK: `dist/stickdroid-0.2.2-methodc-debug.apk` (94528424 bytes)
sha256 `2090863a7c8611c0ae817b7cb54d92a4c8b312c6abdff6854cfb8117c72b4cd2`

Not tested here: thinking bubble, pinch-zoom, or the connected-only composer
on a real camera/session (needs the Pixel 6a).
