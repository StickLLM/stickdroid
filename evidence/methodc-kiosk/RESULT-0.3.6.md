# StickDroid 0.3.6-methodc: accurate "no model" / "agent down" errors

Built and tested on the box only. Not installed, not pushed. The real stick, kiosk, Agent Zero, llama, link.stickllm.com and relay-tor1 were not touched.

## What Nink saw (0.3.5)

He sent "hello world" with no model loaded and got nothing, then "Connection interrupted".

Phone logcat (%USERPROFILE%\stickdroid-dist\logcat-1310.txt, 13:02-13:11 America/Toronto):
- 13:02:25.102 `submit: direct`, then `conversation: A0Error` 85 ms later, then retries every ~4 s.
- 13:02:43 the second message went to the local queue.
- 13:04:27 `queued on A0: 0 file(s)` (one call got through), then A0Error again until 13:11.
- 0.3.5 did not log the HTTP status, so the exact code is unknown. 0.3.6 logs `conversation: A0Error <status> <code>`.

## Root cause (phone side)

- 0.3.5 handled every non-4xx `A0Error` (a reply that did come from the stick, e.g. a gateway 502/503 or an A0 500) like a link error:
  - with no turn started, it retried silently forever and the "direct" message was not shown in the tray ("nothing");
  - with a turn started, the UI said "Connection interrupted — waiting for the link…".
- A0's `model_not_configured` reply (HTTP 200) was not handled; it would end as "(no answer)".

## What the stick returns (source refs)

StickLLM 0.7.7 source copy (/tmp/sl77) and Agent Zero v2.15 (ba9b67df):
- Gateway `overlay/usr/local/bin/stickllm-gateway`:
  - `_agent_embed_proxy` L4727-4760: if `_agent_http_ready(5099)` (L1261-1300, GET /api/csrf_token, 1.5 s timeout, cached) fails, it returns 503 `{"ok":false,"error":"agent_not_running","message":"Agent Zero is not running yet."}`.
  - On a connection exception it returns 502 `{"ok":false,"error":"Agent is not running"}` (~L4782).
  - `/api/agent/status` L6008 → `_agent_status` L3412-3430 → `stickllm_agent.status_document` (`overlay/usr/local/lib/stickllm_agent.py` L3238-3320): state/message/running, no model info.
- llama relay (`stickllm_agent.py`): when llama-server is not listening (`run_tcp_relay` L1775-1785), `no_model_reply` L1672-1720 answers chat/completions with a normal 200 completion containing NO_MODEL_MESSAGE (L1665-1669, "No AI model is loaded on StickLLM right now…"); other paths get 503 `{"error":{"type":"no_model_loaded"}}`.
- Agent Zero: `plugins/_model_config/extensions/python/user_message_ui/_10_check_chat_model.py` sets `model_not_configured` when `is_chat_model_configured` (helpers/model_config.py L1003) is false; `api/message.py` L66-71 returns before starting a task; `api/message_async.py` returns 200 `{"message":"Model not configured.","model_not_configured":true,"context":…}`.

So with llama down but Agent Zero up, the stick should reply with the NO_MODEL_MESSAGE text. The fast failures (85 ms) in Nink's log point at Agent Zero itself not answering (the gateway's 503/502), not llama. This is unconfirmed until a 0.3.6 log shows the status and code.

## The 0.3.6 fix (app-only)

- `core/.../A0Chat.kt`:
  - L82 `A0Error(status, msg, code)` with `agentDown` / `noModel`; `errorCode()` L104 parses the gateway/A0 error code; every HTTP error now carries it.
  - L335 `model_not_configured` → `A0Error(NO_MODEL)`.
  - L164 `agentStatus()` reads `/api/agent/status` (already allowed by the bridge).
  - L100 `isNoModelReply()`; `Turn.noModel` is set at L422.
- `core/.../A0Conversation.kt`:
  - L169-182: a stick-side error (not 4xx, not timeout) becomes a "stick problem" (`describeStickProblem` L232, using the stick's own status message); the message is shown as "waiting to send" and sent when the agent is back; a running turn is no longer reported as a link drop.
  - L253-261: no model → the turn ends at once with `noModel`, no retry.
  - The link-error path is unchanged.
- `app/.../ChatViewModel.kt` L180: `NO_MODEL_TEXT` = "No model is loaded on the stick. Load one on the stick (StickLLM home screen → Models) to chat."; L200 `onStickProblem`.
- `app/.../ui/Screens.kt`: red stick-problem line under the chat while connected.
- versionCode 14, versionName 0.3.6-methodc.

## Tests (box)

- Core unit tests: 193/193 (run twice). New: `StickProblemTests.kt` (5 tests); FakeA0 gained agent-down / no-model modes.
- E2E `interop methodc-resume` against the local lab (wrangler 8802/9232, mock gateway 8444, box peer): 77/77 PASS, including new step 2f (model_not_configured, stick no-model reply, agent down via MOCK_DOWN_FILE, recovery, link stays up). Evidence: `evidence/methodc-always-on-036/`.
- Compile-checked only: the stick-problem line, the NO_MODEL_TEXT display, and behaviour against the real gateway/A0/llama.

## APK

`stickdroid-0.3.6-methodc-debug.apk`, 158,522,990 bytes, sha256 `2dd993005a8fcbbd69f367b2583be4ae830272ef448a106a4b8f2a5f45d732eb`, same debug signer as 0.3.1-0.3.5. Copied to %USERPROFILE%\stickdroid-dist\ (hash checked). Not installed.

## Loading a model from the phone: needs a stick-side change

- The API exists: gateway `POST /api/control` (L6790+), pair auth (`self._auth()`), forwarded to stickllm-control on 127.0.0.1:8091 `/action`. Actions (`handle()` L5739+):
  - `status` / `status_light` L5746-5806: catalog, `active_model`, `loaded`, `llama_service` (progress_pct), download. **It also returns `hf_token`.**
  - `model_activate` {id, ctx_size, kv_type} L5861-5872 → `activate_model` L4738.
  - `model_unload` L5896 / L1715; `llama_warmup` {wait} L5873; `restore_last_model` L5880.
  - `/api/models/status` L5972-5982 (storage and probe only).
- The phone can't reach it today: the StickLink app-mux bridge (`app_mux_bridge.py`) only allows `/agent-zero`, `/socket.io`, `/api/agent`, `/api/csrf`, `/api/pair/status` (ALLOWED_PREFIXES L66-72, `path_allowed` L108-112; everything else is 403, L156-166). The bridge's loopback Bearer (`mint_dev_token`, methodc_peer.py L1121-1137) would pass `_auth()` if the path were allowed.
- `/api/control` must NOT simply be added to the allowlist: it also carries vault_delete, model_delete, wifi and so on, and `status` leaks `hf_token`.

### Handoff note for Cursor (stick side)

Add a narrow phone-safe model API, e.g. under the already-allowed `/api/agent` prefix:
- `GET /api/agent/models` → `{active_model, loaded, state, progress_pct, models:[{id, name, size, ctx_default}]}` (from control `status_light`, no hf_token, no paths).
- `POST /api/agent/models/load` {id, ctx_size?} → `model_activate`; `POST /api/agent/models/unload` → `model_unload`.
- Same pair/bridge Bearer auth, ids validated against the catalog, rate-limited, one load at a time.
Alternatively, the bridge can allow `/api/control` with a body check that only lets through those actions and strips `hf_token`. The gateway route is cleaner. The phone model picker waits for Nink's OK and for this endpoint.
