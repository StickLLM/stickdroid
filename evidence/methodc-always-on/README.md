# StickDroid 0.3.0 always-on WSS resume (slice 1)

Lab (box only, loopback): Worker from private sticklink worker/src (sess.* / rt= compat / GET /v1/resume v2) on 127.0.0.1:8802. Stick peer from stickllm-sticklink-wt methodc_peer.py (PSK v1 + sess.open/bind). Mock A0 on 8443. Nothing contacted link.stickllm.com or relay-tor1.

Command: interop methodc-resume --run-dir /workspace/mc-lab/run030

## run5 (final) — 18/18 PASS

| Step | Check | Result |
|---|---|---|
| 1 | First QR Method C → Live(durable) + AlwaysOn pins + chat | PASS |
| 2 | Drop DC → WSS sess.resume → Noise IKpsk2 (pinned static) → chat | PASS (~0 s) |
| 3 | Cold start → WSS resume without QR → chat | PASS (~0 s) |
| 4 | Disconnect → clear pins, stays Disconnected | PASS |
| 5 | Rescan + Generate → NeedScan (AUTH/4401), pins cleared | PASS (3 s) |

Files: run5-console.txt, run5-peer.log. Token shown only as …+last4.

## Crypto (phone matches stick peer / GROK handoff)

- PSK = HKDF-SHA256(ikm=utf8(token), salt=empty, info=sl1/sess-psk/v1, 32)
- Prologue = sl1/sess || 0x01 || home(16) || SHA256(token)[0:8] || phone_pub || stick_pub
- Wake URL default: ?sess=1&home= (token only in sess.resume). SessAuthUrl.RtQuery kept as isolated compat (?rt=).
- Why not default ?rt=: revoked token → Worker HTTP 404 on upgrade → phone saw NETWORK forever. ?sess=1&home= opens, then sess.resume → 4401/4403.

## Contract notes for Sticklink

1. Handoff + stick peer use PSK v1; live agent_join_client.js uses v2 (pair_psk||token / sl1/sess-psk/v2). Phone is v1; stores pair_psk for a future switch.
2. Handoff emphasizes ?rt=; Worker + browser prefer ?sess=1&home=. Phone defaults to SessHome.
3. Generate clears the row → GET /v1/resume returns 404 (same as offline). Terminal signal is WS 4403/4401 on sess.resume.
4. GET 410 means expired-but-remintable, not Generate.
5. DERP stub only (slice 1).

## Dropped vs 0.2.5

Primary path is no longer poll-/v1/resume-then-remint-Method-C (legacy one-shot only). No 180s inferred-Generate. No fresh Noise static on resume.
