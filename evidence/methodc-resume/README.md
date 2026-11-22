# Method C durable resume: box evidence (0.2.5-methodc)

Lab (box only, loopback): a wrangler dev Worker built from the current private
Worker source (with `/v1/resume`, `resume.put`, `resume.clear`) on 127.0.0.1:8801.
The reference `methodc_peer.py` (commit 3932da9, copied read-only) runs as the
stick, and the mock Agent Zero gateway is on 8443. The lab sets
`JOIN_PUBLIC_BASE=https://127.0.0.1:8801`, because the Worker only accepts
https joins. The harness alone rewrites that to http for loopback. The app
accepts loopback hosts only when lab hosts are enabled. Nothing contacted
link.stickllm.com or relay-tor1.

Command: `interop methodc-resume --run-dir /workspace/mc-lab/run025 --dead-after-sec 60`
(it drives the same `LinkManager` and `MethodCClient` code as the app, with a 0600 temp JSON store in place of the Keystore store).

| Step | Check | run1 | run2 (final code) |
|---|---|---|---|
| 1 | scan → Live(durable), token persisted, chat | PASS | PASS |
| 2 | kill the DataChannel → "Reconnecting" → auto-resume with no scan, same token, chat | PASS (4 s) | PASS (4 s) |
| 3 | cold start (new manager, same store) → resumes with no QR, chat | PASS (2 s) | PASS (2 s) |
| 4 | Disconnect → close why=user, store cleared, `/v1/resume` → 404, stays Disconnected | PASS | PASS |
| 5 | rescan, then kiosk Generate → phone gives up → NeedScan("…Scan a new code…"), store cleared | PASS (91 s) | PASS (91 s) |

Files: `run1-console.txt`, `run2-console.txt`, `run2-peer.log`. A grep for
tokens, `t=` queries, join fragments, psk and sl1 strings found nothing.
Logs show only a token tag (`…` plus the last 4 characters).

In step 3, the cold start's first attempt picked up a join that was already
used (MAILBOX_GONE). The next fresh offer worked. See gap 2.

## Contract gaps (reported only; Worker and stick not patched)

1. **Resume mailbox lifetime vs. re-mint.** In resume mode the stick waits
   for a peer with `wait_peer_or_remint(timeout=None)`. The resume mailbox
   expires after 120 s (`MBX_RESUME_EXP_S`), and the stick does not appear to
   mint a fresh offer after that, although the handoff says it re-mints about
   every minute. Needs a real-device check: airplane mode for more than 2 min.
2. **`/v1/resume` serves stale joins.** The row lives for 10 min
   (`RESUME_OFFER_MS`) but its mailbox expires after 120 s, and the row is not
   deleted when a phone joins. The phone works around this by skipping joins it
   already tried or that have expired.
3. **Generate does not revoke.** `remint.req` clears the token on the stick
   only. There is no `resume.clear` and no 410, so the phone has to infer a
   dead token from about 180 s with no fresh offer. "Scan new code" appears
   about 3.5 min after Generate (box: 91 s with `--dead-after-sec 60`). The
   "Scan new code instead" button is always there.
4. **The `resume.clear` reply is swallowed.** After `close why=user` the
   peer's "ok" seems to be taken by `_drain_signalling`. The peer logged idle
   60 s later. The Worker did clear the row (404 straight after).
5. **The token is in tmpfs** (`/run/sticklink-resume.token`), so a stick reboot
   means a rescan.
6. **The token is sent in the query string** (`/v1/resume?t=`), so it can land
   in Cloudflare request logs. A header or POST would be better.
7. **The phone can't revoke while offline.** Disconnect while not live only
   clears the token locally. The stick keeps offering until its own timeout.
8. **Slow detection of a stick-side close.** The phone noticed a PeerConnection
   close on the stick only after about 30 s with no RX (the RX-silence
   watchdog). An explicit `{t:"close"}` from the stick before teardown would
   make this instant.
