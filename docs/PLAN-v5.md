# Mahout — plan for v4.1 / v5 (2026-09-26)

Inputs analysed: `hellogumbo/awesome-jev`, `NandhaKishorM/laya`, `/Users/ankur/AI_EXPERIMENTS/ObraMaestra`, and the user's asks: quick on-device decisions with Laya/Jev, a proper product name + logo, ObraMaestra (simulation + OSINT "God's Eye") running with the harness, and harness permission modes.

## 1. What Jev and Laya actually are

| | Jev (TypeSafe AI) | Laya (Convai Innovations, Apache-2.0) |
|---|---|---|
| Kind | Cloud **System One decision model**: state + typed questions → typed answers with calibrated probabilities, **no text generation** | Open-source, self-hosted engine with the **same wire protocol** (`POST /v1/systemone`) |
| Wire | `POST https://api.typesafe.ai/v1/systemone`, `Authorization: Bearer`, body `{state, model:"jev-latest", questions:{name:{type: choice\|score\|noul, instructions, criteria}}}` → `{model, answers:{name:{type, choice/score/noul, probabilities, confidence}}, usage:{input_tokens, output_tokens}}` (401/422/429/529) | `laya-serve` on `:8000`, identical request/answer schema (+ `routing.model`); also an MCP server (`laya_predict/route/shortlist/preset/status`) |
| Models | `jev-1.x` (~236–276 ms p50, $0.042/1M tokens) | `laya` (ModernBERT-large 421M, en), `laya-multilingual` (mmBERT-base 322M, 100+ langs, 8k ctx), `laya-typed-decisions`; 33 ms/question on a T4 |
| Android | No SDK | **Python only** (PyTorch/ONNX). Not an Android SDK. |

`awesome-jev` is a community directory of Jev clients/integrations (60+ SDKs incl. Kotlin, MCP servers, routers/gates, coding-agent skills); nothing there is an on-device runtime.

**What "quick speeds" means for the harness.** Every place we currently spend a 3 s generative round-trip on a *judgement* — Classify, If/Switch-by-AI, notification triage, agent tool pre-routing, approval risk scoring, guardrails — is a System 1 call that returns in tens of ms and costs almost nothing. The generative LLM stays for planning/writing/tool loops.

### 1.1 Running Laya "on the device"
Three tiers, cheapest first:
1. **LAN Laya** (`laya-serve` on the Mac/PC; the app already has the LAN-provider pattern) — zero device cost, ~40–80 ms on the LAN. **Ship first.**
2. **Jev cloud** — same client, key in Settings.
3. **On-device ONNX** — Laya ships an `ONNXAgent`; export `laya-multilingual` to ONNX int8 (~330 MB + 34 MB tokenizer) and run it with **ONNX Runtime Android** (`com.microsoft.onnxruntime:onnxruntime-android`, Play-compatible; XNNPACK/NNAPI). Expect roughly 150–400 ms per 512-token state on a Pixel Tablet CPU, offline. Optional download from Settings, RAM/latency verified on the tablet before it is on by default. Ceiling: model size and cold-start; upgrade = smaller distilled checkpoint if the Laya project publishes one.

### 1.2 Harness changes (v5 "System 1")
- `ai/SystemOne.kt`: one client for the Jev wire protocol; provider rows `jev` (cloud, key) and `laya` (LAN URL, no key); usage recorded into `ai_usage` like every other call.
- Node `ai.decide` (DATA, agentTool=true): ROWS param `questions` (name, type choice/score/noul, instructions, criteria) → item with `answers.<name>` (+ probabilities/confidence). `ai.classify` gains `engine = generative | system1`.
- Agent/chat: `riskOf()` gets an optional System 1 second opinion for free-text tool inputs; notification-posted triggers get a `triage` option (score urgency in ~50 ms before waking the generative model).
- Builder rule: prefer `ai.decide` for routing questions.

## 2. ObraMaestra with the harness
`ObraMaestra/` = three desktop-scale projects; the tablet is the **operator console**, not the host:

| Component | What it is | How Mahout uses it |
|---|---|---|
| `godseye` (Python) | Agentic UAV ISR mission sim: **MCP server** `:8791/mcp` (Streamable HTTP + Bearer, 45 tools: `uav_*`, `mission_*` incl. `mission_dry_run`, `sim_*`, threat/identify reports), telemetry bridge `:8790` (`/snapshot`, `/camera/{vehicle}`, `/events` SSE), AirSim or a built-in fake | **Already supported**: add it as an MCP server in Settings (LAN URL + token, *untrusted* → every `uav_*`/`mission_*` call goes through the approval gate; `mission_dry_run`/`uav_get_*` read-only). Preset skill `uav-isr-operator` encodes the task → plan → dry-run → execute → monitor doctrine. Sample workflows: `/events` alarm → `ai.decide` (threat class/urgency) → notify/TTS; scheduled telemetry snapshot → knowledge. |
| `gods-eye-view` (JS, Cesium) | Photorealistic globe command center `:4173` with providers (aircraft, ships, satellites, FIRMS, CCTV, transit…) | A **Panels** screen: WebView tiles of GEV (and the telemetry PIP) on the tablet — Play-compatible; deep links from chat ("show me the tracked target"). `data.http` nodes on the read-only bridge feeds. |
| `airsim` (Unreal/C++) | UAV/car physics | Never on the device; commanded only through godseye's MCP. |

`.mcp.json` in ObraMaestra contains a bearer token — it is treated as a secret; Mahout stores it in `secrets` like every MCP auth.

## 3. Product name + logo
**Mahout** (the rider who guides the elephant): a small operator steering a very large model from a phone, with reins (approvals), a harness (workflows), and doctrine (skills). No Play collision found; "Yoke" (fitness app) and "Reins" were the runners-up.
- Keep `applicationId com.mob8n` (on-device data and keys survive); rename everything user-visible: `app_name`, launcher icon (elephant mark, `docs/brand/mahout-*.svg`), README/docs, package label; repo folder rename is optional. Ceiling: a package rename is a fresh install.

## 4. Permission modes (v4.1)
Single `PermissionMode` enum used by chat, `ai.agent`, Builder save and every node approval:

| Mode | Read-only tools | Safe actions (notify, playlist, log…) | Coding (shell/js/workspace writes) | Destructive / UI automation / untrusted MCP / delete |
|---|---|---|---|---|
| **Plan** | run | ask | ask | ask |
| **Ask** (default) | run | ask | ask | ask |
| **Auto** | run | run | run | ask |
| **Bypass** | run | run | run | run — explicit confirmation, 60-min expiry, persistent red banner, Dashboard kill switch |

Global default in Settings; per-conversation and per-agent-node override; the mode is shown on every tool card; audit row per auto-approved action. Existing toggles (`autoApproveSafe`, `autoApproveCoding`, `askApproval`) become derived from the mode.

## 5. Sequencing
1. **v4** (running): chat harness, dashboard, shell/JS/workspace, workflows-as-tools, skills.
2. **v4.1**: permission modes + rename/logo (small, cross-cutting, do together).
3. **v5**: System 1 tier (LAN Laya first, Jev cloud, optional on-device ONNX) + ObraMaestra bridge (godseye MCP preset, Panels screen, skill, sample workflows).
