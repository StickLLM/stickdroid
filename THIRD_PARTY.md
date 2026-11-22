# Third-party components

## In the APK

| Component | Version | License | Use |
|---|---|---|---|
| Vosk Android (`com.alphacephei:vosk-android`) | 0.3.75 | Apache-2.0 | Offline speech-to-text engine (Kaldi) |
| JNA (`net.java.dev.jna:jna` aar) | 5.18.1 | LGPL-2.1-or-later **or** Apache-2.0 (dual); used under Apache-2.0 | Vosk's native binding |
| `vosk-model-small-en-us-0.15` (Alpha Cephei) | 0.15 | Apache-2.0 | Bundled US-English model, in APK assets |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`) | 1.80 | MIT | Crypto in `core` (HKDF, X25519, ChaCha20-Poly1305, Ed25519) |
| OkHttp (`com.squareup.okhttp3:okhttp`) | 4.12.0 | Apache-2.0 | HTTP(S) / WebSocket client |
| Stream WebRTC Android (`io.getstream:stream-webrtc-android`) | 1.3.10 | Apache-2.0 (wrapper); bundled libwebrtc is BSD-3-Clause | WebRTC DataChannel for StickLink Method C |
| ZXing (`com.google.zxing:core`) | 3.5.3 | Apache-2.0 | QR scanning |
| AndroidX / Jetpack Compose (Compose BOM `2024.10.01`; `activity-compose` 1.9.3; Lifecycle 2.8.7; `navigation-compose` 2.8.3; CameraX 1.4.0) | as pinned in `app/build.gradle.kts` | Apache-2.0 | UI, navigation, camera |
| Kotlin coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-core` / `kotlinx-coroutines-android`) | 1.9.0 | Apache-2.0 | Async / Android dispatchers |
| Kotlin serialization (`org.jetbrains.kotlinx:kotlinx-serialization-json`) | 1.7.3 | Apache-2.0 | JSON |

The Vosk model is fetched at build time by `scripts/fetch-vosk-model.sh`
(sha256 `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`),
not committed. The phone never downloads anything for speech.

## Box-only (not in the APK)

| Component | Version | License | Use |
|---|---|---|---|
| Vosk (`com.alphacephei:vosk`) | 0.3.45 | Apache-2.0 | `voskbox` desktop transcription harness |
| JNA (`net.java.dev.jna:jna`) | 5.13.0 | LGPL-2.1-or-later **or** Apache-2.0 (dual); used under Apache-2.0 | `voskbox` native binding |
| webrtc-java (`dev.onvoid.webrtc:webrtc-java`) | 1.0.1 | Apache-2.0 (wrapper); bundled libwebrtc is BSD-3-Clause | JVM WebRTC for the interop harness |
