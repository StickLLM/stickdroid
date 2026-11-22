# Method C kiosk-QR evidence (box only), 2026-11-19 (America/Toronto, EST)

Everything ran on the box. No production link.stickllm.com, relay-tor1 or
public STUN was contacted (ICE lists were forced empty, so host candidates only).
No stick, kiosk, vault, A0 or llama of Nink's was touched.

Setup: the real kiosk source (stickllm-072, read-only) for
`methodc_peer.py`, `app_mux_bridge.py`, `pair_qr.py`, `noise.py` and `crypto.py`,
plus the real signalling worker (`wrangler dev --local`). The stick peer is
wrapped by `scripts/methodc/run_stick_peer.py`, which swaps only ICE (to an
empty list) and the lab-grant mint (redeemed at the mock). The A0 and gateway
are `scripts/methodc/mock_gateway.py`, which enforces both the Bearer and CSRF.
The phone side is the stickdroid core code in the JVM harness (`interop methodc`,
webrtc-java/libwebrtc).

| Suite | Result |
|---|---|
| core unit tests (13 old + 7 Method C, incl. byte-for-byte vectors from the stick's own Python) | 20/20 PASS |
| run1 kiosk suite | 8/9 (T07 misclassified as BAD_LINK, fixed: `JoinLinkException.expired`) |
| run2 kiosk suite (`run2/`, `run2-console.txt`) | **10/10 PASS** |
| old phase-1 interop (`regression-interop-box/`) | 5/5 PASS (direct, peer-force-relay, udp-blocked, stick-force-relay, no-secrets) |
| `:app:assembleDebug` with stream-webrtc-android | builds; **not run** (no emulator / KVM access on the box) |

Run2 cases: T01 corrupt P (flipped) = MAILBOX_GONE; T02 corrupt P (short) =
BAD_LINK; T03 wrong host = BAD_LINK; T04 direct chat (2 turns, 143 kB answer
via gzip+chunks, Stop, `/api/speech` refused 403, 40 s hold) = ok; T05 reuse
while live = STICK_BUSY; T06 reuse after = MAILBOX_GONE; T07 expired (client) =
EXPIRED; T08 expired (server) = MAILBOX_GONE; T09 fresh scan = ok; T10 stick
dies = LOST in ~26 s.

Relay: N/A. Method C has no TURN.

Logs: `lab-logs/` (peer, mock, wrangler). Bearer suffixes are redacted and
no invite, P, K, mailbox id or join URL appears.
