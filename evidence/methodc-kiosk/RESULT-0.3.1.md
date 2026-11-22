# StickDroid 0.3.1-methodc: sess resume PSK hotfix

## Field symptom (0.3.0, Pixel 6a)
Logcat repeated `signalling open (sess)` → `sess.ok` about every 16 s, with no Noise or transport-up log. The app showed disconnected while the stick UI said the phone was connected.

## Root cause
The PSK version didn't match. 0.3.0 keyed the sess-resume Noise IKpsk2 with **v1**:
HKDF(token, info "sl1/sess-psk/v1"). The **live** stick and browser use **v2**:
HKDF(pair_psk ‖ utf8(token), salt empty, info "sl1/sess-psk/v2").
- stick-client `methodc_peer.py` L132-137 `sess_resume_psk(token, pair_psk)` (v2), used by `noise_sess_responder_on_wss` L317-360
- browser `agent_join_client.js` L104-106 `sessResumePsk` (v2), used by runWssResumeAgent
- The 0.3.0 lab used the older stickllm-sticklink-wt peer (v1), so the lab passed while the real stick failed.

In IKpsk2, msg1 doesn't depend on the PSK. The stick accepted msg1, saw the phone as connected, and sent a v2-keyed msg2. The phone couldn't decrypt it, failed with HANDSHAKE, and looped.
The prologue (`sl1/sess`‖0x01‖home‖SHA256(token)[:8]‖phone_pub‖stick_pub, 97 B) already matched.

## Fix
- WssSessClient defaults to v2. If pair_psk is missing it fails with UNAVAILABLE and never silently falls back to v1.
- msg2 wait cut from 60 s to 15 s. On any failure the WS closes cleanly with code 1000 so the Worker and stick drop the stale peer.
- Non-secret logs:
  - psk version
  - msg1 size
  - msg2 size and latency, or timeout with rx count
  - msg2 decrypt failure
  - sess.err code
  - WS close code and reason, and failure HTTP code
  - rx seq on lost or dropped
- Tests:
  - PSK v1/v2 and tokenId vectors generated from the live stick crypto
  - a test showing a v1 initiator fails against a v2 responder
  - a test that v2 is the default
- E2E harness: the live stick rotates the token after Noise, so the check is now "pins kept (token rotated or kept)".

## Tests
- core unit: 133/133
- always-on E2E against the **live v2 stick peer** and the current Worker (lab, box-local): 18/18 PASS, see `evidence/methodc-always-on-031/run2-*`
