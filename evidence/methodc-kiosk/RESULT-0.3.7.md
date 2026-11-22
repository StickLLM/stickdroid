# StickDroid 0.3.7-methodc: friendly setup messages (no false "starting")

Built and tested on the box only. Not installed, not pushed. The real stick, kiosk, Agent Zero, llama, link.stickllm.com and relay-tor1 were not touched.

## Why

Nink (4:56-4:58 PM ET): Agent Zero does not auto-start on the stick; you start it on the kiosk. 0.3.6 said "Agent Zero isn't running on the stick (Agent Zero is starting...)", which is misleading. His wording: "You need to load a model and harness like qwen and agent zero on the kiosk to use this application."

The "starting" came from the stick itself. `/api/agent/status` (`stickllm_agent.py` `status_document`, the `elif not upstream_up:` branch) reports `state: "starting"`, `message: "Agent Zero is starting..."` whenever the image is installed but the container isn't up, even when nobody started it. 0.3.6 passed that message through. 0.3.7 never repeats it.

## Messages (exact strings, `core/.../StickSetup.kt`)

| When | Text |
|---|---|
| Agent Zero down (gateway 503 `agent_not_running` / 502 "Agent is not running"), status says starting / stopped / running / unknown | `AGENT_DOWN_TEXT` (L19): "Agent Zero isn't running on the stick. Start Agent Zero on the kiosk (and load a model like Qwen if you haven't) to use StickLLM from your phone. Your message will send once it's running." |
| No model: A0 `model_not_configured`, or the stick relay's "No AI model is loaded" reply | `NO_MODEL_TEXT` (L24): "No model is loaded on the stick. Load a model (like Qwen) on the kiosk, then send your message again." |
| Agent Zero down and `/api/agent/status` couldn't be read (we don't know which is missing) | `SETUP_TEXT` (L14): "To use StickLLM from your phone, load a model (like Qwen) and start Agent Zero on the kiosk. Your message will send once Agent Zero is running." |
| Status `installing` (real progress) | `installing()` (L28): "Agent Zero is still installing on the stick (<stick's progress line>). Your message will send once it's running." |
| Status locked / settings_missing / no_ssd / low_space / pull_failed / error / needs_install, with a stick message | `blocked()` (L33): "Agent Zero can't run on the stick yet: <stick's message>. Fix that on the kiosk and start Agent Zero; your message will send once it's running." |
| A0 answered with another 5xx (not "agent down") | "Agent Zero on the stick had an error (HTTP <n>[, <code>]). Retrying quietly; your message will send when it answers." (`A0Conversation.kt` L232) |

- "starting" is never shown. `StickSetup.clean()` drops any stick message containing "starting", and the "starting" state maps to `AGENT_DOWN_TEXT`.
- Mapping: `StickSetup.forAgentDown` (L41), called from `A0Conversation.describeStickProblem` (L232).

## Behaviour kept from 0.3.6

- A held message stays visible as "waiting to send", polling continues quietly (backoff up to 4 s), and the message sends automatically once Agent Zero answers. The problem line then clears.
- No model: the turn ends at once with `NO_MODEL_TEXT`, with no retry. The user loads a model and sends again.
- Link drops are still the only case that says "Connection interrupted".
- `A0Conversation.kt` L181: an interrupted turn's reason now uses `STICK_PREFIX` (L380). `ChatViewModel.kt` L193 shows the setup text as is, with no "Agent Zero: " prefix and no "Connection interrupted".
- `ChatViewModel.kt` L179: no-model replies use `StickSetup.NO_MODEL_TEXT`. The old app-local NO_MODEL_TEXT was removed.
- versionCode 15, versionName 0.3.7-methodc.

## Tests (box)

- Core unit tests: **196/196** pass (193 before, plus 3 new).
  - `StickProblemTests`: setupMessagesPerStickState, agentDownWithUnreadableStatusUsesGeneralSetupText, lockedStickSaysWhatToFix.
  - The agent-down tests now require `AGENT_DOWN_TEXT` exactly and no "starting", even though FakeA0 status says "starting".
- E2E `interop methodc-resume`: **77/77 PASS** (`evidence/methodc-always-on-037/run1-console.txt`).
  - Lab: local StickLink Worker (sticklink-server-030) on 127.0.0.1:8802/9232 with a fresh local D1 seeded with the lab device rows, box stick peer (peer031), mock gateway 8444.
  - Step 2f now checks the agent-down text is `AGENT_DOWN_TEXT` and never "starting", while the mock's `/api/agent/status` says "starting" like the real stick.
  - Note: a first attempt was pointed at the wrong local Worker copy (no `/v1/resume`), so the resume steps failed (lab setup, unrelated to 0.3.7). The rerun on the correct Worker passed 77/77. That console was overwritten.
- Compile-checked only: the Android UI (problem line, bubble text) and behaviour against the real gateway, A0 and llama.

## APK

`stickdroid-0.3.7-methodc-debug.apk`, 158,539,374 bytes, sha256 `8e0bdd2ef0b48c9d6684b67bfeb65dcdeab4a4373547cf71add806e803192ef5`, signer f3f523a8…bd27864 (same debug key as 0.3.1-0.3.6). Copied to `dist/` and `%USERPROFILE%\stickdroid-dist\` (hash checked). Not installed.

## Note for Cursor (stick side)

- Unchanged from RESULT-0.3.6.md: loading a model from the phone needs a narrow phone-safe endpoint, e.g. `GET /api/agent/models`, `POST /api/agent/models/load`, `POST /api/agent/models/unload` under the already-allowed `/api/agent` prefix. No hf_token. Never open all of `/api/control`.
- `/api/agent/status` says `state: "starting"` / "Agent Zero is starting..." when the container simply isn't running and nothing is starting it (`status_document`, the `not upstream_up` branch). Please report `stopped` (or a distinct state) unless a start is actually in progress, so clients can say "starting" truthfully.
- **Nink is considering having Agent Zero auto-start once a model is loaded.** If that lands, the phone's held message will send by itself as soon as Agent Zero answers. Ideally `/api/agent/status` then reports `starting` only during that real start.
