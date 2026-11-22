# StickLLM companion protocol (prototype, proto 1)

Carried as Sticklink `D_APP` (0x20) datagrams over an already-paired Noise
session. The sl1 pipe is not a reliable stream: every application message
is prefixed with the §6.1 fragment header and may be split so each
datagram's app body stays at or under 1250 bytes.

This is the Phase 1 typed-chat protocol. Voice is out of scope here.

## Framing (on the wire, inside Noise DATA)

Unix-socket frame between `sticklinkd` and `companiond` (not visible on the
phone link):

```
slot[8] || uint32be(len) || body
```

`slot` is the Noise-authenticated pair slot. The phone cannot set it;
`sticklinkd` tags each frame from the live session. `len` is the size of
one fragment (`body`), not the reassembled message, and must be ≤ 2048.

Each `body` is one fragment:

```
flags[1] || seq[4 be] || index[2 be] || payload
flags = (version=1 << 4) | (FLAG_LAST=0x01 if last fragment)
```

Reassemble by `seq`. A message is complete when the last fragment has
arrived and indices `0..last` are all present. Max reassembled size is
64 KiB. Both the Kotlin core (`Fragments`) and `companiond.py` use this
layout.

## JSON envelope (reassembled payload)

```
{"t": "<type>", "s": <seq>, "a": <ack>, "b": { ... }}
```

`s` and `a` are optional. `b` may be omitted when empty. Unknown fields
are ignored. Types:

| t                | direction     | b fields | meaning |
|------------------|---------------|----------|---------|
| `hello`          | phone → stick | `proto`, `app`, `dev`, optional `resume.a` | Open or resume a companion session. |
| `welcome`        | stick → phone | `proto`, `stick`, `a0` (`up`/`down`), `caps` (list), `turns` (pending answer previews) | Stick is ready. `turns` lists unfinished deliveries as `{turn, conv, preview}` (preview truncated to 80 chars). |
| `ping` / `pong`  | either        | — | Liveness. |
| `ack`            | either        | — | Cumulative ack of `a`. Not required in proto 1. |
| `conv.new`       | phone → stick | `conv` | Start a fresh conversation (clears the A0 `context_id` for that conv). |
| `conv.end`       | phone → stick | `conv` | Phone is done with a conversation (stick may drop cached context). |
| `turn.start`     | phone → stick | `turn`, `conv`, `text`, optional `ctx` | New user message. `turn` is a 96-bit b64u id from the phone. `text` max 8 KiB. Duplicate `turn` is idempotent (`queued: false` replay of `turn.accepted`). |
| `turn.accepted`  | stick → phone | `turn`, `conv`, `queued` | Stick has the turn and will run it. |
| `progress`       | stick → phone | `turn`, `stage`, `text`, `el` (seconds) | Status line. First turn of a new conv uses `stage=wait` ("Working on it.") because stock Agent Zero only returns `context_id` when `api_message` finishes, so `api_log_get` cannot be polled yet. Later turns may use stages from the A0 log (`think`, etc.). |
| `answer`         | stick → phone | `turn`, `text`, `part`, `parts`, `ms` | Final answer, paged at 8000 characters (`part` is 1-based). Combine `part=1..parts` in order. |
| `answer.ack`     | phone → stick | `turn` | Phone has the full answer; stick may drop the pending copy. |
| `turn.cancel`    | phone → stick | `turn` | Request stop. |
| `turn.cancelled` | stick → phone | `turn`, `kept_history` | Prototype always sets `kept_history: true`. It does **not** call A0 `api_terminate_chat`, because that route deletes the whole conversation in current Agent Zero. |
| `error`          | stick → phone | `turn?`, `code`, `msg` | `too_large`, `a0_auth`, `a0_down`, `internal`. `msg` is safe to show. |

## Stick-side path

```
phone  --Sticklink D_APP-->  sticklinkd  --unix socket-->  companiond
                                                           |
                                                           | loopback HTTP
                                                           v
                                               Agent Zero External API
                                               POST /api/api_message
                                               POST /api/api_log_get
```

`companiond` holds the A0 API key. The phone never sees it. `companiond`
binds a Unix socket (mode 0600). Unless `STICKLINK_TESTING=1`, it refuses
clients whose `SO_PEERCRED` uid is not its own.

## Notes and ambiguities (follow stick-side behaviour)

- QR Method C (`sl1:M&K&P&E` mailbox, `pair.open`) is specified and the
  phone core can parse and validate it, but current `sticklinkd` has not
  implemented the mailbox. Box interop and the app's lab path use
  test-file pairing (`pair-offer` / `pair-accept`).
- Later sessions use Noise_IKpsk2, 96-byte v2 prologue, phone as
  initiator, and the phone keeps the signalling WebSocket open after
  candidates.
- First-turn progress is a wait pulse (option C in the design review)
  until a small A0 route can return `context_id` immediately.
- Cancel is confirm-and-keep-history in this prototype.

## Lab endpoints

- companiond: `python -m sticklink.companiond --socket PATH --a0-url http://127.0.0.1:15099 --a0-key-file FILE`
- sticklinkd: `python -m sticklink.daemon run ... --companion-sock PATH`
  (or `STICKLINK_COMPANION_SOCK`). Only on branch
  `feature/companiond-prototype` in the stick-build tree.


## StickLink Method C (Milestone A), phone side

Source of truth: the kiosk's methodc_peer.py, agent_join_client.js and pair_qr.py.
No stick change.

1. QR / link: `https://link.stickllm.com/browser/agent_join.html?ws=wss://link.stickllm.com/v1/ws&qr=<urlenc sl1:M=..&K=..&P=..&E=..>`.
   K and P are 32-byte base64url. E is unix expiry (60 s). Host is allowlisted;
   lab hosts are accepted only in debug builds with the toggle on.
2. WSS `?mbx=` with subprotocol sticklink.1: welcome{n}, then
   pair.join{mac=HMAC(jk,"sl1/qr-join-mac"||n||mbx)}, then pair.joined{conn,home}.
   A bad or expired mailbox gets `err nf` / 4404 / HTTP 404.
3. Sealed pmsg (env_k, AD enc(mbx)||mbx||dir, body ≤3072 b64): need-offer,
   offer (from stick), answer (from phone, non-trickle, 8 s gather).
4. DC "sl1": Noise IKpsk2 (prologue "sl1/qr"||1||home||mbx||K, psk boot from
   the invite and TH). Then ready, r_i, r_r, pair_psk with confirm_i and confirm_r
   (the phone verifies confirm_r), then agent_ready.
5. APP-MUX v1 http_req/res/chunk (36 KiB, gzip), ping/pong 15 s, {t:close}.
   The stick bridge allows only /agent-zero, /socket.io, /api/agent, /api/csrf
   and /api/pair/status.
6. No device record on either side, so no reconnect. Every session needs a new QR.
