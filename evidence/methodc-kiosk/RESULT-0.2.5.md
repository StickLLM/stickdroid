# StickDroid 0.2.5-methodc: box results (2026-11-19, America/Toronto)

APK `/workspace/stickdroid-dist/stickdroid-0.2.5-methodc-debug.apk`, 158162070 bytes,
sha256 `5169b776c21ec0dcb47f41979bd341f57d9c79faceb051a2928cf4ad88aaef37`, versionCode 7.

- Core unit tests: **127/127 PASS** (101 existing + 26 resume tests: parse,
  persist, clear on Disconnect, 404/200/backoff, dead-token escape, old-stick
  fallback, suspended-app clock).
- Resume E2E on the box: **17/17 checks PASS**. See `../methodc-resume/README.md`.
- Kiosk regression (old 072 peer): **10/10 PASS** (`run7-console.txt`, `run7/`).
- Interop regression: **5/5 PASS**, no secrets in the logs (`regression-interop-box-025/`).
- `:app:assembleDebug`: OK.

## Behaviour

- `agent_ready.resume` {token, origin, ws} is parsed and checked. The token is
  16–128 characters, b64url. Origin and ws must be link.stickllm.com; loopback
  is allowed only with lab hosts on. It is saved AES-GCM encrypted with the
  app's Keystore key in `noBackupFilesDir/resume.bin`, and it survives a kill.
- Transport loss (DC/PC closed or failed, or more than 30 s with no RX when
  the stick supports resume) shows "Reconnecting…" and polls
  `GET {origin}/v1/resume?t=…`:
  - 200: parse ws and qr from `join` and run the full Method C connect. Joins
    that were already tried or have expired are skipped.
  - 404: wait 2 s.
  - Other errors: back off 2 s → 15 s.
  - 400 or 410: the token is dead.
  - About 180 s with no usable offer: dead token. The token is cleared and the
    screen says "Scan a new code".
  - After a 30 min cap: Paused, with "Try again" and "Scan new code". The token
    is kept.
  - "Scan new code instead" is shown the whole time.
- Cold start with a saved token reconnects without a QR.
- Disconnect sends Noise `{t:"close", why:"user"}`, tears down and clears the
  token. The screen says "Disconnected — scan a new code on the stick."
- Backgrounding is not a disconnect. Coming back to the foreground wakes the
  poller, and Paused retries. A long suspension does not count toward the
  dead-token clock.
- An old stick with no `resume` keeps the old behaviour and shows a soft
  warning that it can't auto-reconnect.
- Logs never contain the full token, pair_psk, join or Keystore material.

## Contract gaps

See `../methodc-resume/README.md`.
