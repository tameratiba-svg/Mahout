# Mahout v4.1 — Permission modes · per-call approval · Bypass safety + kill switch · rename · draft layout

Side-track ("P") to `DESIGN4.md`, planned in `PLAN-v5.md` §3 (name) and §4 (modes). Product name **Mahout**, package/applicationId **`com.mob8n`** (unchanged — the tablet holds the user's MiniMax key and Room data; a package rename is a fresh install). Self-contained for two parallel implementers (**ai**, **ui**) and one **integrator** who do not talk. Code on disk wins; every deviation is recorded in §10 at integration time. Revised 2026-09-26 (round 2) against the review's ten must-fix findings — scope escalation, `run_js` in Auto, the mixed-batch transcript invariant, duplicate tool ids, the ACTION audit chip, hanging Deferreds, `guarded` cancellation matching, `stopEverything` order, Builder-authored Bypass, the awaiting-window race — plus the cheap items (MCP in Plan, workflow tools with destructive nodes, clock hardening, persisted partial decisions, banner on every screen, icon centring/monochrome, keep-list).

Ground truth read for this design (2026-09-26, v4 verified on the Pixel Tablet, 473 tests, 135 nodes): `ai/OperatorTools.kt` (`riskOf`/`gate`/`previewFor`, `Risk { READ, WRITE, ALWAYS }`, coding tools by NAME in `CODING`), `ai/Chat.kt` (`ChatRunner.drive` = one `CompletableDeferred<Boolean>` per batch, `decisions[id]`, `Persister` meta, `runTurn` resume path), `ai/Agent.kt` (`AgentNode.loop` checks `tools[name].needsApproval` per batch, `State.pending`, `withApproval`, `NodeResult.Suspend` title/text per batch), `ui/Chat.kt` (`ApprovalBubble(pending, onApprove, onDeny)` = one Approve for the batch; `ToolCard` risk chip from `riskFor`), `ui/BuildWithAi.kt` (`draftFrom` already lays out; `ui/Chat.kt openDraft` and `save_workflow` do not; `Builder.parse` creates `NodeInstance` with `x = y = 0`), `engine/TriggerHub.kt` (runs start under `guarded()` inside `engine.scope.launch`, no Job tracking, no cancel surface), `engine/DelayedRunWorker.kt` (`cancelAllResumes(ctx, runId)`), `engine/Notifs.kt`, `res/` (3-node DAG launcher, `ic_tile`), `strings.xml`, `AndroidManifest.xml`.

---

## 1. Decisions

| # | Decision | Why |
|---|---|---|
| P1 | **One policy function.** `Permissions.decide(mode, risk) -> Decision(verdict RUN\|ASK\|BLOCK, …)` is the only place that reads the PLAN-v5 §4 table. Chat, `ai.agent` and Builder save all go through `Permissions.gate(tools, mode, risk)`, which maps RUN → `withApproval(false)`, ASK → `withApproval(true)`, BLOCK → `AgentTool.blocked(message)` (a tool whose `call` throws `NodeException`, so the UNCHANGED loop records an `is_error` result and the model re-plans). `AgentNode.loop` gains no parameter. | Reuse the loop; one table, one test matrix. |
| P2 | **`Risk` gains `CODING`** (`READ, WRITE, CODING, ALWAYS`): `riskOf(name in CODING) = Risk.CODING`. The v4 "coding by name inside gate()" special case disappears; PLAN-v5 §4 has exactly these four columns. | The matrix needs four classes; a name check inside `gate` cannot express Auto. |
| P3 | **Mode resolution order: `ai.agent` param > conversation > global**, and **every Bypass scope expires against its OWN timestamp**: a conversation `BYPASS` is `ASK` once `ChatSettings.bypassUntil <= now`; a global `BYPASS` is `ASK` once `settings.bypass_until <= now`. Arming one scope never touches another (a "for this chat" Bypass cannot re-arm the global one — review finding "scope escalation"). Global default `ASK` (settings key `permission_mode`). `ChatSettings.mode: PermissionMode? = null` (null = inherit), `ChatSettings.bypassUntil: Long = 0`. `ai.agent` param `permissionMode` ENUM `inherit\|plan\|ask\|auto\|bypass`, default `inherit` (`bypass` on the node = the author's edit-time choice, no timestamp, P4). | PLAN-v5 §4: global default, per-conversation and per-agent override; the dialog text "for this chat" must be literally true. |
| P4 | **Legacy toggles are derived, not deleted.** `autoApproveSafe`/`autoApproveCoding` stay in `ChatSettings` (old rows parse); they are read ONLY when `mode == null`: both true → `AUTO`, anything else → inherit. The settings sheet replaces the two Switches with a four-way mode selector (+ "Inherit (global: Ask)"). `askApproval=false` on `ai.agent` keeps today's meaning (no gate at all) and equals `permissionMode = bypass` on the node. | Compatibility with stored conversations and saved workflows; "zero behaviour change for `ai.agent`" unless the author picks a mode. |
| P5 | **Plan = read-only tools run, everything else BLOCKED** with `is_error "plan mode: <tool> is blocked (read-only tools only). Draft and preview; the user switches to Ask or Auto to run it."`. `draft_workflow` is READ so the assistant still drafts; `save_workflow` is blocked and its tool card keeps **Open in editor** (the draft stays in `ChatDrafts`), which is the "Builder save honours Plan (preview only)" path: saving becomes the USER's action in the editor. The Build-with-AI screen and the editor's own Save are user actions and never gated. | The model never writes in Plan; the user always can. |
| P6 | **Per-call approval.** `Outcome.NeedApproval(pending)` is unchanged; the chat collects ONE decision per pending `tool_use` (`ChatRunner.decide(app, conv, callId, ok)`), completes the batch when every **distinct** pending id is decided (`decideAll` = the explicit **Approve all** / **Deny all** buttons and the notification buttons). The batch (`Batch(pending, decided, deferred)`) is registered in `decisions[id]` BEFORE the Awaiting status / notification is published, so a tap can never land between them (finding "decision lost in the awaiting window"). Approved calls run, denied calls get `is_error "denied by user"` **in the same results message** (transcript invariant V4): `state.pending` stays the WHOLE batch in original order and `AgentNode.State.denied: Map<id, reason>` is consumed by `loop()` when it executes it (denied ids are not executed; a `steps[]` row records the denial; every id gets exactly one `tool_result`). A batch denied in full is today's path (`denials++`, model re-plans, > 2 ends the turn). **Tool_use ids are unique and non-blank by construction**: `Llm.step` applies `Turn.withUniqueToolIds()` (blank → `call_N`, repeat → `<id>_2`, `_3` …, the echoed `_oai` `tool_calls[i].id` rewritten in lock-step) so a `Map<id, Boolean>` can always cover the batch (finding "duplicate ids deadlock"). `ai.agent` keeps ONE Suspend per batch (`// ponytail`), but its `title`/`text` list every call with its mode. | Brief §2; the Claude wire rejects tool_results that are not immediately after their tool_use, so a mixed batch must yield one message. |
| P7 | **Bypass safety = four things that are never simplified away**: (a) confirmation dialog with the exact list of what runs unasked and a truthful scope line; (b) a 60-min timestamp **per scope** (`settings.bypass_until` for global, `ChatSettings.bypassUntil` for a conversation), checked on every decision (gate time) AND re-checked inside every tool wrapper at call time (an expired Bypass turns the call into `is_error "Bypass expired — this call now needs approval"`, next turn gates as ASK); (c) persistent red banner rendered ONCE in `ui/App.kt` above every screen while `max(global bypass_until, any conversation's bypassUntil where mode == BYPASS) > now`, text `Bypass mode — nothing asks — expires in N min — Stop`; (d) kill switch **Stop everything** on the Dashboard and in the banner, in this order: `HarnessPrefs.clearBypass` + conversation sweep (synchronous, cannot fail) → `ChatRunner.cancelAll` → `Engine.cancelAllRuns()`. Setting the clock (`TIME_SET`) or booting clears every Bypass (wall-clock hardening). | Brief §3; PLAN-v5 §4 Bypass row; findings "scope escalation", "stopEverything order", "wall-clock". |
| P8 | **Audit visibility.** Every assistant row with tool_use blocks persists `meta.mode` (effective mode) AND `meta.gate = { "<tool_use id>": "<risk>/<verdict>" }` (e.g. `"toolu_1": "coding/RUN"`), written by `Persister.flush` from the same `Decision` the gate used — risk and verdict are **frozen per id** so a later MCP-trust toggle or a riskOf table change never rewrites history. The tool card chip is `<risk> · <verdict by mode>`: `read-only`, `safe action · ran (auto)`, `coding · approved (ask)`, `destructive · blocked (plan)`, `… · ran (bypass)`. UI: `meta.gate[id]` → chip; rows without `gate` (v4 history) recompute via `riskFor` with `isAction` from the catalog (finding "audit chip wrong for ACTION"). `Log.i(LOG_TAG, "chat <id>: <tool> <verdict> by <mode>")` per decision is the audit line. | Brief §1 "every tool card shows the mode that decided it". |
| P9 | **`Engine.cancelAllRuns()`** (new engine surface, integrator): `TriggerHub` tracks `runJobs: ConcurrentHashMap<Job, String /* wf.id */>` for every `guarded()` execution; cancel + join them with `CancellationException("stopped by user")`; `guarded` writes `failRunning(wf.id, "stopped by user")` on **any `CancellationException` that is not a `TimeoutCancellationException`** (never by matching the message — child `JobCancellationException`s do not carry the parent's text; the kill switch is the only other way a guarded job is cancelled), and `cancelAllRuns` writes the same row for every cancelled `wf.id` after the join (idempotent belt-and-braces); every `CompletableDeferred` handed out by `fire`/`fireWorkflow`/`launchRun`/`resume` is completed with `null` in a `finally` so `DelayedRunWorker.doWork().await()`, `runManual` and `fire().await()` never hang on a cancelled job (finding "kill switch leaves Deferreds hanging"); then every SUSPENDED run is closed FAILED "stopped by user" (`deleteSuspended`, `Notifs.cancelApproval`, `DelayedRunWorker.cancelAllResumes(app, runId)`) — including timer suspensions (`logic.wait_until`, `wait_approval`), which the confirm dialog names. Scheduled future runs (`schedule_run`, `sched:` tags) are NOT touched; they are not running, and a trigger firing right after the sweep starts a new run under the (now ASK) mode — the snackbar count is a snapshot. | Brief §3 kill switch; reuses `failRunning` and the `Engine.delete` cleanup pattern. |
| P10 | **Rename = user-visible strings + launcher icon only.** `app_name` "Mahout", top bars, onboarding "Set up Mahout", channel texts, disclosures, node descriptions, prompts ("You are the Mahout operator"), README/docs H1 "Mahout (package `com.mob8n`)". NOT renamed: packages, `applicationId`, file/class names (`Mob8NApp`, `Mob8NTheme`, `Theme.Mob8N`), `LOG_TAG = "Mob8N"`, DB `mob8n.db`, FileProvider authority, `mob8n://` deep links, the on-disk export folders `Documents/Mob8N` / `Music/Mob8N` (`Files.SUBDIR`; renaming would orphan existing exports — text says the real path). | Brief §4. |
| P11 | **Launcher = adaptive icon from `docs/brand/mahout-logo.svg`**: `ic_launcher_foreground` (elephant + nodes, SVG paths verbatim inside a scaled `<group>`), `ic_launcher_background` solid `#0F2A3F`, `ic_launcher_monochrome` (white silhouette, no eye) referenced by `<monochrome>` in both mipmap XMLs. `ic_tile` (24 dp notification/tile glyph) unchanged: the three nodes ARE the mark's workflow motif and a 24 dp elephant is illegible. | Brief §4; no new dependency. |
| P12 | **Draft layout = `needsLayout(graph)` in the ui lane.** `Builder` emits `x = y = 0`; `BuildWithAi.draftFrom` already lays out, but `ui/Chat.kt openDraft` and `save_workflow`-saved workflows do not. Fix once where every graph enters the editor: `EditorScreen` load applies `autoLayout` when `needsLayout` (≥ 2 nodes all at 0,0; marks `dirty = false`, positions persist on the next Save) and `openDraft` lays out before parking in `Drafts`. The ai lane cannot import `ui.autoLayout` (import rules), so `save_workflow` keeps storing 0,0 — the editor repairs on open. | Brief §5, smallest diff. |
| P13 | **Settings home = `object HarnessPrefs` in `ai/Permissions.kt`** (not `AiPrefs`): two keys (`permission_mode`, `bypass_until`) in `SETTINGS_PREFS`, two `StateFlow`s, sync reads for the execution path, `setMode(ctx, mode, confirmed)` refusing Bypass without `confirmed = true`; `setMode(BYPASS)` is the ONLY writer of a non-zero `bypass_until`, and `setMode(any other mode)` / `clearBypass` zero it. There is no `armBypass`: a conversation Bypass is `ChatSettings(mode = BYPASS, bypassUntil = now + TTL)` saved on its own row. | "Keep tiny"; `AiPrefs` is provider plumbing; no cross-scope writes. |
| P14 | **Notification buttons stay batch-level** (`Approve all` / `Deny all` → `Engine.chatDecision(id, ok)` → `ChatRunner.decideAll`); the text lists every call. Per-call decisions are the in-app bubbles. | No manifest/receiver change; a notification has two buttons. |
| P15 | **`run_js` is gated per call in Auto.** `riskOf(run_js) = CODING` (runs in Auto), but its `allowNodes` may name destructive/UI nodes the table says must ask. `Permissions.sanitizeJsAllow(input, mode)`: when `mode == AUTO`, every id with `isAlwaysNode(id)` (`DESTRUCTIVE_IDS` ∪ `AgentNode.isUiTool`) is REMOVED from `allowNodes` before the tool runs; `mob8n.runNode(id)` then fails with the existing "not in this script's allowNodes" error plus the hint `— it needs approval in auto mode: call the node tool directly so the user can approve it`. In ASK the whole call was approved by the user (the preview prints "may call: …"); in BYPASS everything runs; in PLAN `run_js` is blocked. `ai.agent` has no JS tool (`logic.js` is `agentTool = false`); `app_shell_run` in an Auto agent is CODING by the PLAN-v5 table (shell = coding) and needs no extra rule. | Finding "run_js escapes the mode table in Auto"; the loop's per-tool `needsApproval` is untouched. |
| P16 | **Builder cannot author an unattended agent silently.** `Builder.validate` flags every `ai.agent` node with `askApproval = false` or `permissionMode ∈ {auto, bypass}` as the issue `Runs unattended: <node name> (askApproval=false | permissionMode=bypass) — the user sets this in the editor`; the Builder prompt gains rule 11 (never set them); the issue is printed by `previewFor("save_workflow")` (Issues section), by the `draft_workflow` result and by the draft cards (`ui/Chat.kt`, `ui/BuildWithAi.kt`) in the **error colour** with the text (never colour-only). Saving such a draft is still possible (the user approved a preview that names it). | Finding "Builder-authored Bypass with no confirmation and no visibility"; P4's "no dialog for saved workflows" stays truthful because the save preview is the disclosure. |
| P17 | **Per-call decisions survive process death.** `Conversation.pendingJson` stays an array of tool_use blocks; each block may carry `"_decided": true|false`; `parsePending` strips the key when it rebuilds `ToolUse`s and returns the decided map alongside. `decide()` with no live job registers the `Batch` (seeded from the stored decisions) FIRST, then launches `runTurn(text = null, resume = true)`, which adopts that Batch — a second tap during the resume window lands in the map instead of being dropped. The legacy `resumeDecision == false` branch ("denied by user" for ALL pending) is used only when every id is denied. | Findings "decision lost in the awaiting window", "process-death resume with partial decisions"; deletes the "decisions in process memory" ponytail. |
| P18 | **Two table gaps closed.** (a) `Permissions.gate` blocks every `kind == "mcp"` tool in PLAN regardless of trust (a trusted server's write tools are not read-only; `readOnlyHint` = upgrade). (b) `riskOf` for `kind == "workflow"` tools (`workflow__*`, `WorkflowTools.all`) is `ALWAYS` when that workflow's graph contains a node in `DESTRUCTIVE_IDS` or `isUiTool` (`riskOf(t, catalog, workflows)`; `runTurn` loads `workflows` before gating); `run_workflow` (by id, per call) stays WRITE and the Auto/Bypass help texts + the dialog say "run any saved workflow, whatever it contains" (`// ponytail`). | "Other flaws" 1–2. |

### 1.1 Never simplify away
Bypass confirmation dialog + per-scope 60-min expiry + call-time re-check + red banner on every screen + kill switch (clearBypass FIRST) · a conversation Bypass never arms the global one · per-call Approve/Deny with an explicit Approve all · frozen risk+verdict+mode on every tool card (`meta.gate`, `meta.mode`) · Plan blocks every non-READ tool (including `save_workflow` and every MCP tool) · Bypass requires `confirmed = true` at the prefs layer, not only in the dialog · transcript invariant (mixed batch → ONE results message covering EVERY id; ids unique and non-blank) · Batch registered before Awaiting is published · `run_js` allow-list sanitised in Auto · unattended `ai.agent` flagged in every draft/save preview · guarded FAILED row on any non-timeout cancellation + Deferreds completed in `finally` · every DESIGN4 §1.1 item · a11y: 48 dp targets, `contentDescription` on every Approve/Deny/Stop, banner `liveRegion = Assertive`, values never colour-only (the banner says "Bypass mode" in words; the unattended line says "Runs unattended").

### 1.2 Ponytail marks (comment in code)
`// ponytail: ai.agent suspends once per batch; upgrade = per-call Suspend choices` · `// ponytail: legacy autoApprove* map to AUTO only when both are on; upgrade = drop the fields at Room v4` · `// ponytail: Bypass expiry re-checked per tool call, not mid-call; upgrade = cooperative cancel of the running tool` · `// ponytail: ai.agent checks Bypass expiry at run start only (run <= 10 min)` · `// ponytail: runs cancelled by the kill switch get their FAILED row from TriggerHub.guarded (+ cancelAllRuns after join); upgrade = NonCancellable finish() in Executor.drive` · `// ponytail: run_js allow-list sanitised by mode (ASK = the whole call was approved), not per id; upgrade = per-id approval inside the script` · `// ponytail: run_workflow (by id) stays WRITE in Auto; upgrade = graph-aware risk per call` · `// ponytail: MCP blocked in Plan by kind; upgrade = honour readOnlyHint` · `// ponytail: reins drawn solid (VectorDrawable has no dash array); upgrade = dotted path` · `// ponytail: save_workflow stores x=y=0, the editor lays out on open; upgrade = autoLayout in core` · `// ponytail: ic_tile keeps the 3-node glyph; upgrade = 24 dp elephant` · `// ponytail: monochrome icon draws nodes/edges as a 0.4-alpha tone; upgrade = separate silhouette art`.

---

## 2. Exact shared Kotlin surfaces (frozen once merged)

### 2.1 `ai/Permissions.kt` — NEW (ai lane; ui + integrator import only what is listed)
```kotlin
package com.mob8n.ai

/** PLAN-v5 §4. Order matters (ordinal = permissiveness); `key` is the settings / ai.agent wire value. */
@Serializable
enum class PermissionMode {
    PLAN, ASK, AUTO, BYPASS;
    val key: String get() = name.lowercase()
    companion object {
        const val INHERIT = "inherit"
        fun parse(s: String?): PermissionMode? = entries.firstOrNull { it.key == s?.trim()?.lowercase() }   // "inherit"/null/junk -> null
    }
}

enum class Verdict { RUN, ASK, BLOCK }

/** One policy answer. `reason` is the human line shown on the card / in the blocked tool result. */
data class Decision(val verdict: Verdict, val mode: PermissionMode, val risk: Risk, val reason: String)

object Permissions {
    const val BYPASS_TTL_MS = 60 * 60_000L
    const val STOPPED_BY_USER = "stopped by user"
    const val BLOCKED_PREFIX = "plan mode: "
    const val EXPIRED = "Bypass expired — this call now needs approval; ask the user to approve it or switch mode"

    /** The §4 table, pure. PLAN: READ runs, else BLOCK. ASK: READ runs, else ASK. AUTO: READ/WRITE/CODING run, ALWAYS asks. BYPASS: everything runs. */
    fun decide(mode: PermissionMode, risk: Risk): Decision

    /** "plan mode: <name> is blocked (read-only tools only). Draft and preview; the user switches to Ask or Auto to run it." (save_workflow adds "Open in editor to save it yourself.") */
    fun blockedMessage(toolName: String): String

    /** agent param > conversation > global; a BYPASS at EITHER scope collapses to ASK when ITS OWN until <= now (P3). An agent-param BYPASS never reaches here (modeFor -> null, ungated). Pure. */
    fun resolve(agentParam: PermissionMode? = null, conversation: PermissionMode? = null, conversationUntil: Long = 0L, global: PermissionMode, globalUntil: Long, now: Long): PermissionMode

    /** Pure: withApproval per verdict; BLOCK -> AgentTool.blocked(blockedMessage(name)). PLAN additionally BLOCKs every `kind == "mcp"` tool whatever its risk (P18a). Same instance when nothing changes (RiskTest asserts). */
    fun gate(tools: Map<String, AgentTool>, mode: PermissionMode, risk: (AgentTool) -> Risk): Map<String, AgentTool>

    /** Pure: DESTRUCTIVE_IDS or AgentNode.isUiTool — the node ids the §4 table never lets Auto run. */
    fun isAlwaysNode(id: String): Boolean
    /** Pure (P15): mode == AUTO -> `allowNodes` minus every isAlwaysNode id (second = the dropped ids, logged and appended to the tool result as "refused in auto mode: …"); any other mode -> (input, empty). Applied to `run_js` input in ChatRunner.wrap before the call. */
    fun sanitizeJsAllow(input: JsonObject, mode: PermissionMode): Pair<JsonObject, List<String>>

    /** Chat/UI label: "read-only" | "safe action" | "coding" | "destructive"; verdict label: "ran" | "asks" | "blocked". */
    fun riskLabel(r: Risk): String
    fun verdictLabel(v: Verdict): String
    /** "safe action · ran (auto)" — the tool-card chip text (P8). */
    fun chip(d: Decision): String = "${riskLabel(d.risk)} · ${verdictLabel(d.verdict)} (${d.mode.key})"
    /** P8 frozen audit: { "<tool_use id>": "<risk.name.lowercase>/<verdict.name>" } for one assistant row; `parseGate("coding/RUN", mode)` -> Decision? (null on junk). */
    fun gateMeta(uses: List<ToolUse>, decisionFor: (toolName: String) -> Decision?): JsonObject
    fun parseGate(s: String?, mode: PermissionMode): Decision?
}

/** settings["permission_mode"] (default ASK) + settings["bypass_until"] (epoch ms, 0 = none). DESIGN4P P13. The GLOBAL scope only; conversation Bypass lives in ChatSettings. */
object HarnessPrefs {
    const val KEY_MODE = "permission_mode"
    const val KEY_BYPASS_UNTIL = "bypass_until"
    val mode: StateFlow<PermissionMode>              // ASK until load()
    val bypassUntil: StateFlow<Long>                 // 0 until load()
    fun load(ctx: Context)                           // idempotent re-read into the flows (screens call it in a LaunchedEffect, like AiPrefs.load)
    fun readMode(ctx: Context): PermissionMode       // sync, execution path (ChatRunner, AgentNode)
    fun readBypassUntil(ctx: Context): Long
    fun isBypassActive(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = readBypassUntil(ctx) > now
    /** Writes the global mode. BYPASS requires confirmed = true (IllegalArgumentException("Bypass needs confirmation") otherwise) and arms bypass_until = now + BYPASS_TTL_MS. Every other mode writes bypass_until = 0. The only writer of a non-zero bypass_until. */
    fun setMode(ctx: Context, mode: PermissionMode, confirmed: Boolean = false, now: Long = System.currentTimeMillis())
    /** Kill switch / banner Stop / TIME_SET / BOOT: bypass_until = 0; a stored global BYPASS is written back to ASK. Synchronous (commit), never throws. */
    fun clearBypass(ctx: Context)
    /** Permissions.resolve(null, s?.modeOrLegacy(), s?.bypassUntil ?: 0, readMode, readBypassUntil, now). */
    fun effective(ctx: Context, s: ChatSettings? = null, now: Long = System.currentTimeMillis()): PermissionMode
}
```
`decide` matrix (frozen; `PermissionsTest` pins every cell):

| mode \ risk | READ | WRITE | CODING | ALWAYS |
|---|---|---|---|---|
| PLAN | RUN | BLOCK | BLOCK | BLOCK |
| ASK | RUN | ASK | ASK | ASK |
| AUTO | RUN | RUN | RUN | ASK |
| BYPASS | RUN | RUN | RUN | RUN |

`reason` strings: RUN → `"read-only"` / `"auto-approved by <mode>"`; ASK → `"asks in <mode>"`; BLOCK → `blockedMessage(name)`. `resume_run` is `ALWAYS`: it asks in Plan/Ask/Auto and runs only in a confirmed, unexpired Bypass — the Bypass dialog names it (§4.1).

### 2.2 `ai/Chat.kt` — changed members
```kotlin
@Serializable data class ChatSettings(
    val autoApproveSafe: Boolean = false, val autoApproveCoding: Boolean = false,   // kept for compatibility; read only when mode == null (P4)
    val mode: PermissionMode? = null,                                               // null = inherit the global mode
    val bypassUntil: Long = 0L,                                                     // P3: this conversation's own Bypass expiry (epoch ms); meaningful only with mode == BYPASS
    val uiAutomation: Boolean = false, val nodeTools: List<String> = emptyList(), val mcpServers: List<String> = emptyList(),
    val knowledge: List<String> = listOf("all"), val skills: List<String>? = null, val maxSteps: Int = 12, val maxTokens: Int = 4096,
) {
    /** mode, else AUTO when BOTH legacy toggles are on, else null (inherit). // ponytail: legacy autoApprove* map to AUTO only when both are on */
    fun modeOrLegacy(): PermissionMode?
    fun json(): String; companion object { fun parse(json: String): ChatSettings }   // unchanged; `mode` serialises as "ask" etc. via @SerialName("plan") … on each constant; FORMAT keeps coerceInputValues = true so a junk/unknown `mode` string reads as null (PermissionsTest 6 pins it — never turn it off, old rows would stop parsing)
}

enum class Risk { READ, WRITE, CODING, ALWAYS }   // P2

/** One pending tool call awaiting the USER (P6). `decision` = the gate's Decision for this tool (risk, verdict ASK, mode). */
data class PendingCall(val id: String, val toolName: String, val input: JsonObject, val decision: Decision) {
    fun toolUse(): ToolUse = ToolUse(id, toolName, input)
}

object ChatRunner {
    sealed class Status {
        data object Idle : Status(); data class Thinking(val sinceMs: Long) : Status(); data class Running(val tool: String) : Status()
        /** decided = per-call decisions already taken (approved = true / denied = false); the batch completes when decided.keys covers every DISTINCT pending id (ids are unique by construction, P6). */
        data class Awaiting(val pending: List<PendingCall>, val decided: Map<String, Boolean> = emptyMap()) : Status()
        data class Error(val message: String) : Status()
    }
    /** One awaiting batch. Registered in decisions[conversationId] BEFORE any status/notification is published; `decided` is a ConcurrentHashMap; `deferred` completes with decided.toMap() when it covers pending. */
    private class Batch(val pending: List<PendingCall>, val decided: MutableMap<String, Boolean>, val deferred: CompletableDeferred<Map<String, Boolean>>)

    fun status(conversationId: String): StateFlow<Status>
    fun send(app: Context, conversationId: String, text: String)                                   // unchanged
    /** One call. Live Batch -> record + publish Awaiting(pending, decided). No Batch and no live job (process death) -> register a Batch rebuilt from pendingJson (its stored `_decided` + this decision), persist the decision into pendingJson, THEN launch runTurn(text = null, resume = true), which adopts the Batch. No Batch but a live job (resume in flight) cannot happen any more: the Batch exists before the job. */
    fun decide(app: Context, conversationId: String, callId: String, ok: Boolean)
    /** Every still-undecided pending call. The notification buttons and Approve all / Deny all land here. */
    fun decideAll(app: Context, conversationId: String, ok: Boolean)
    /** Kept for Engine.chatDecision / ApprovalReceiver: == decideAll. */
    fun decide(app: Context, conversationId: String, approve: Boolean) = decideAll(app, conversationId, approve)
    fun cancel(app: Context, conversationId: String)                                              // unchanged
    /** Kill switch: cancels every live turn (their NonCancellable epilogues close dangling tool_use with "cancelled by user"), completes nothing, returns the count. */
    fun cancelAll(app: Context): Int
    fun newConversation(app: Context, settings: ChatSettings = ChatSettings()): String              // unchanged

    // ---- pure-ish core (JVM-tested)
    /** await returns the per-call decisions (every id of `pending` present). All false -> denial path (denials++, one denial results message, model re-plans). Otherwise state.pending = outcome.pending (ALL calls, original order) and state.denied = the denied ids -> "denied by user"; the loop executes the batch, skipping denied ids, and writes ONE results message with exactly one tool_result per tool_use (ChatLoopTest 9 pins this). */
    internal suspend fun drive(state: AgentNode.State, tools: Map<String, AgentTool>, maxSteps: Int, step: suspend (List<JsonObject>) -> Turn,
        persist: suspend (pending: Boolean) -> Unit, await: suspend (List<ToolUse>) -> Map<String, Boolean>, now: () -> Long = System::currentTimeMillis): Driven
    /** Pure: PendingCall per tool_use with decide(mode, riskOf(tool, catalog, workflows)). Unknown tool name -> Risk.ALWAYS. */
    internal fun pendingCalls(uses: List<ToolUse>, tools: Map<String, AgentTool>, risk: (AgentTool) -> Risk, mode: PermissionMode): List<PendingCall>
    /** Pure: "Chat wants to run 3 tools" / "1. run_shell — ls -la\n2. workspace_write — Write a.txt\n3. delete_workflow — Delete workflow x" (each line ≤ 120 chars, ≤ 8 lines then "+N more"). */
    internal fun notificationTitle(p: List<PendingCall>): String
    internal fun notificationText(p: List<PendingCall>): String
    /** Pure: the call-time wrapper. Verdict RUN because of BYPASS but bypassActive() now false -> throws NodeException(Permissions.EXPIRED). tool.name == "run_js" -> input passes through Permissions.sanitizeJsAllow(input, decision.mode) first; dropped ids are appended to the result text (P15). */
    internal fun wrap(tool: AgentTool, decision: Decision, bypassActive: () -> Boolean, onRun: (String) -> Unit): AgentTool
    /** Pure: pendingJson(uses, decided) writes `_decided` on decided blocks; parsePending(json) -> Pair<List<ToolUse>, Map<String, Boolean>> strips it. */
    internal fun pendingJson(uses: List<ToolUse>, decided: Map<String, Boolean> = emptyMap()): String
    internal fun parsePending(json: String?): Pair<List<ToolUse>, Map<String, Boolean>>
}
```
`runTurn(app, engine, id, text: String?, resume: Boolean)` changes (§5.2 of DESIGN4, steps renumbered in place):
- step 1: `mode = Permissions.resolve(null, s.modeOrLegacy(), s.bypassUntil, HarnessPrefs.readMode(app), HarnessPrefs.readBypassUntil(app), now)`; log `"mode=<key>"`. `bypassActive = { Permissions.resolve(same args, now = System.currentTimeMillis()) == BYPASS }` — the call-time re-check is scope-aware (a conversation Bypass expires on its own clock).
- step 6: `workflows` loaded first; `gated = Permissions.gate(OperatorTools.all(..., mode), mode) { OperatorTools.riskOf(it, catalog, workflows) }`; `decisionOf = { name -> gated[name]?.let { decide(mode, risk(it)) } }`; `tools = gated.mapValues { wrap(it, decisionOf(it.name)!!, bypassActive) { st.value = Status.Running(it) } }`.
- `Persister.flush`: assistant rows get `put("mode", mode.key)` and, when the row has tool_use blocks, `put("gate", Permissions.gateMeta(uses, decisionOf))`.
- `await` lambda, in THIS order: `pending = pendingCalls(uses, tools, risk, mode)`; `batch = decisions[id] ?: Batch(pending, ConcurrentHashMap(), CompletableDeferred())` (an existing Batch = the resume path registered it); `decisions[id] = batch`; `setConversationState(id, "awaiting", pendingJson(uses, batch.decided))`; `st.value = Awaiting(pending, batch.decided.toMap())`; when not foreground `engine.postChatApproval(id, notificationTitle(pending), notificationText(pending))`; if `batch.decided` already covers pending (resume with everything decided) complete the deferred at once; `map = try { batch.deferred.await() } finally { decisions.remove(id, batch); engine.cancelChatApproval(id) }`. Each `decide(callId)`: `batch.decided[callId] = ok`; `setConversationState(id, "awaiting", pendingJson(uses, decided))` (P17, fire-and-forget in engine.scope); `st.value = Awaiting(pending, decided.toMap())`; `if (decided.keys.containsAll(pending.map { it.id })) deferred.complete(decided.toMap())`.
- resume path (`text == null && resume`): `(pending, stored) = parsePending(conv.pendingJson)`; the Batch registered by `decide()` already exists (`decisions[id]`, seeded with `stored` + the tap). Every id denied → the existing "denied by user for all" branch (results row, `state.pending = emptyList()`, model re-plans). Otherwise `runTurn` calls the SAME `await` lambda directly, before `drive` (`val map = await(pending)` — it adopts the Batch, republishes Awaiting, and returns as soon as every id is decided), then `state.pending = pending; state.denied = map.filterValues { !it }.mapValues { "denied by user" }` and `drive` runs the batch first as today (`AgentNode.loop` executes `state.pending`, skipping denied ids, ONE results message). `drive` gains no parameter.
- `ChatRunner.cancelAll`: `jobs.values.forEach { it.cancel(CancellationException("stopped by user")) }`; awaiting batches are cancelled with their job (the epilogue writes `is_error "cancelled by user"` results and `setConversationState(idle)` — existing behaviour). Called AFTER `HarnessPrefs.clearBypass` (§4.4).

`ai/Llm.kt` — `Turn.withUniqueToolIds(): Turn` (pure, P6): walks `content` tool_use blocks in order; `id.isBlank()` → `call_<n>`; a repeat → `<id>_2`, `_3` …; the same index in `raw["tool_calls"]` (when present) gets the same new id so the echoed `_oai` message and our tool_results agree. `Llm.step` returns `turn.withUniqueToolIds()` for every provider. `OpenAiCompat` line 418 keeps its `call_${i+1}` fallback (it is now redundant but harmless).

Prompt line (static rules, `ChatPrompt.static`): replace the approval sentence with: `- Permission mode: some tools pause for the user's approval (Ask/Auto), run at once (Auto/Bypass) or are blocked (Plan: read-only tools only — draft and preview, then ask the user to switch mode). Never claim an action succeeded before its tool result says so. "denied by user" / "plan mode" results mean stop that action and ask what they want instead.` The `<context>` gains one line `Permission mode: <key>`. `ChatPromptTest` budgets unchanged (static < 3 000).

### 2.3 `ai/Agent.kt` — changed members
```kotlin
class AgentTool(...) {
    fun withApproval(needs: Boolean): AgentTool                                        // unchanged
    /** Same def, never asks, every call fails with `message` (Plan mode). kind unchanged so steps[].kind stays honest. */
    fun blocked(message: String): AgentTool = AgentTool(name, def, false, kind, rejectTemplates) { throw NodeException(message) }
}
object AgentNode : Node() {
    class State(val messages: MutableList<JsonObject>, var step: Int, val steps: MutableList<JsonObject>, var pending: List<ToolUse>) {
        /** Chat per-call approval (P6): tool_use ids of `pending` the user denied -> reason; consumed by loop() when the batch executes. Not in toPayload (chat only). */
        var denied: Map<String, String> = emptyMap()
    }
    // loop(): `val results = batch.map { tu -> denied[tu.id]?.let { why -> steps += item("tool" to tu.name, "kind" to (tools[tu.name]?.kind ?: "unknown"), "input" to tu.input, "error" to why, "ms" to 0); ClaudeClient.toolResultBlock(tu.id, why, true) } ?: runTool(tu, tools, state.steps, now) }; state.denied = emptyMap()`
    // params: appended after `skills`:
    //   choice("permissionMode", "Permission mode", listOf("inherit", "plan", "ask", "auto", "bypass"), "inherit",
    //          help = "inherit = the global mode from Settings. plan = read-only tools only. ask = every action asks. auto = safe + coding actions run, destructive / UI / untrusted MCP ask. bypass = nothing asks (same as 'Ask approval' off). 'Allow UI automation tools' still decides whether UI tools are offered at all.")
    /** Pure (AgentToolsTest): null = legacy ungated loop (askApproval=false, or param bypass); otherwise the mode to gate with. */
    fun modeFor(param: String?, askApproval: Boolean, global: PermissionMode, bypassUntil: Long, now: Long): PermissionMode? = when {
        param == "bypass" || (PermissionMode.parse(param) == null && !askApproval) -> null
        else -> Permissions.resolve(PermissionMode.parse(param), null, 0L, global, bypassUntil, now)
    }
    /** Pure: Suspend title/text listing every call with its mode (P6). "Agent wants to: run_shell, workspace_write" / "1. run_shell (coding · asks in ask) {command:…}\n2. …" each ≤ 300 chars. */
    fun approvalTitle(pending: List<ToolUse>): String
    fun approvalText(pending: List<ToolUse>, tools: Map<String, AgentTool>, mode: PermissionMode, risk: (AgentTool) -> Risk): String
}
```
`run()` change: `val mode = modeFor(ctx.strOrNull("permissionMode"), ctx.bool("askApproval"), HarnessPrefs.readMode(a), HarnessPrefs.readBypassUntil(a), ctx.nowMs())` (Android absent → `ASK` defaults, tests never reach it); `val tools = if (mode == null) merged else Permissions.gate(merged, mode) { OperatorTools.riskOf(it, ctx.catalog, workflows) }`; `loop(..., askApproval = mode != null || ctx.bool("askApproval"), ...)`; `ctx.log("agent mode=${mode?.key ?: "ungated"}")`; Suspend uses `approvalTitle/approvalText`. `allowUiAutomation` unchanged (filters the spec map before gating). Note: `riskOf(t, catalog, …)` for `node` tools resolves the id through `catalog.agentTools()`; `app_shell_run` is `Risk.CODING` (in `CODING`), so Auto runs it — matches PLAN-v5 §4 ("Coding (shell/js/workspace writes)"). `logic.js` is not an agent tool, so P15 is chat-only.

### 2.4 `ai/OperatorTools.kt` — changed members
```kotlin
fun riskOf(name: String, kind: String, trustedMcp: Boolean, nodeId: String?, isAction: Boolean = false, workflowAlways: Boolean = false): Risk
    // first branch: name in CODING -> Risk.CODING; kind == "workflow" && workflowAlways -> Risk.ALWAYS (P18b); the rest unchanged
fun riskOf(t: AgentTool, catalog: Catalog, workflows: List<Workflow> = emptyList()): Risk
    // workflowAlways = t.kind == "workflow" && workflows.firstOrNull { WorkflowTools.toolName(it.name, it.id) == t.name }?.graph?.nodes?.any { Permissions.isAlwaysNode(it.type) } == true
@Deprecated("use Permissions.gate") fun gate(tools: Map<String, AgentTool>, s: ChatSettings, risk: (AgentTool) -> Risk): Map<String, AgentTool> =
    Permissions.gate(tools, s.modeOrLegacy() ?: PermissionMode.ASK, risk)                                    // one-line shim so nothing else in the tree changes; deleted when ChatLoopTest/RiskTest are updated — ai lane's choice
fun all(app, engine, catalog, conv, s, t, log, mode: PermissionMode): Map<String, AgentTool>                 // mode only feeds run_js: JsBridge.forChat(engine, allow) receives the sanitised allow set (wrap already stripped the ids; `all` re-applies sanitizeJsAllow defensively so the bridge never sees an ALWAYS id in AUTO)
fun previewFor(tu: ToolUse): String                                                                           // run_js: ids that AUTO would refuse are suffixed " (refused in auto mode)"; save_workflow: the draft's issues incl. "Runs unattended: …" lines (P16) are always printed, even when the draft has no other issue
```
`run_shell` def description: "…Asks the user unless the permission mode is Auto or Bypass." `save_workflow` description: "…The user approves the preview (blocked in Plan mode: open the draft in the editor instead)." `run_js` description adds: "In auto mode, allowNodes ids that need approval (destructive or UI nodes) are refused — call those node tools directly."

`ai/Builder.kt` (P16): `fun unattendedAgents(graph: Graph): List<String>` (pure) = `"Runs unattended: <name> (askApproval=false)"` / `"… (permissionMode=bypass|auto)"` per `ai.agent` node; `validate()` appends them; prompt rule `11. ai.agent: never set askApproval=false or permissionMode other than "inherit" — the user chooses that in the editor.`; `BuilderPromptTest` budget < 44 000 still holds (+ ~120 chars).

### 2.5 `engine/Engine.kt` — v4.1 additions (integrator implements; ui calls)
```kotlin
/** Kill switch (DESIGN4P P9): cancels every executing run (TriggerHub Jobs) and joins them; their rows become FAILED "stopped by user"; every SUSPENDED run is closed FAILED "stopped by user" (timers + notifications cancelled). Returns runs cancelled + suspended closed. Never throws. */
suspend fun cancelAllRuns(): Int
```
`TriggerHub` additions (`private const val STOPPED_BY_USER = "stopped by user"` — engine cannot import ai):
- `private val runJobs = ConcurrentHashMap<Job, String>()` (job → `wf.id`); `guarded()` puts `coroutineContext.job` for its duration and adds `catch (e: CancellationException) { if (e !is TimeoutCancellationException) try { persistence.dao.failRunning(wf.id, STOPPED_BY_USER, System.currentTimeMillis()) } catch (_: Exception) {}; throw e }` BEFORE the existing `TimeoutCancellationException` handler order-wise is irrelevant (`TimeoutCancellationException` is caught by its own clause first in source order; keep it first).
- `fire`, `fireWorkflow`, `launchRun`, `resume`: each `launch { try { … } catch … finally { if (!d.isCompleted) d.complete(null) } }` (`result.complete(emptyList())` for `fire`) — a cancelled job never leaves its Deferred pending, so `DelayedRunWorker.doWork().await()` returns (the worker then returns `Result.success()`; the row was written by `guarded`), `runManual` and `fire().await()` return.
- `suspend fun cancelAllRuns(): Int`: `val snapshot = runJobs.toMap(); snapshot.keys.forEach { it.cancel(CancellationException(STOPPED_BY_USER)) }; snapshot.keys.forEach { runCatching { it.join() } }; snapshot.values.toSet().forEach { runCatching { persistence.dao.failRunning(it, STOPPED_BY_USER, now) } }` (idempotent: `failRunning` matches RUNNING rows only); then the SUSPENDED sweep. `HostService.idleWatch` needs no change (`activeRuns` drops as jobs finish).

`triggers/SystemReceiver.kt` (integrator, P7 wall-clock hardening): `ACTION_TIME_CHANGED`, `ACTION_BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED` → `HarnessPrefs.clearBypass(ctx); ChatRunner.clearConversationBypass(engine)` before the existing `host.fire(...)` (`handle()` already has `engine`). The only `triggers → ai` import (`com.mob8n.ai.{HarnessPrefs, ChatRunner}`), noted in §7.

`ai/Chat.kt` — `suspend fun ChatRunner.clearConversationBypass(engine: Engine): Int`: every conversation whose `ChatSettings.mode == BYPASS` is saved back with `mode = null, bypassUntil = 0`; returns the count. Used by `stopEverything` (§4.4) and `SystemReceiver`.

### 2.6 `engine/Notifs.kt` — text only
`postChatApproval(ctx, conversationId, title, text)` unchanged; buttons relabelled **Approve all** / **Deny all** (`ApprovalReceiver` → `Engine.chatDecision` → `ChatRunner.decideAll`). `BigTextStyle` already shows the per-call list (≤ 4 000 chars).

### 2.7 `ui` — new/changed composables (ui lane; nothing outside `ui/` imports them)
```kotlin
// ui/Bypass.kt (NEW)
fun bypassBannerText(untilMs: Long, nowMs: Long): String        // "Bypass mode — nothing asks — expires in 57 min — Stop" (N = ceil((until-now)/60000), min 1); pure (BypassUiTest)
fun bypassActiveUntil(globalUntil: Long, conversations: List<Conversation>, nowMs: Long): Long   // pure: max(globalUntil, every conv with ChatSettings.mode == BYPASS -> bypassUntil) if > now else 0
fun modeLabel(m: PermissionMode?): String                        // null -> "Inherit", else "Plan" | "Ask" | "Auto" | "Bypass"
fun modeHelp(m: PermissionMode): String                          // one sentence each (§4.1 texts); AUTO/BYPASS mention "runs any saved workflow you ask for, whatever it contains" (P18b)
@Composable fun BypassBanner(engine: Engine, modifier: Modifier = Modifier)          // renders only while bypassActiveUntil(HarnessPrefs.bypassUntil, engine.conversations(), now) > 0 (1 s tick); red (errorContainer), 48 dp Stop, liveRegion Assertive
@Composable fun BypassConfirmDialog(scope: BypassScope /* GLOBAL | CONVERSATION */, onConfirm: () -> Unit, onDismiss: () -> Unit)
@Composable fun ModeSelector(value: PermissionMode?, allowInherit: Boolean, globalLabel: String, scope: BypassScope, onChange: (PermissionMode?) -> Unit)   // SingleChoiceSegmentedButtonRow; picking Bypass opens BypassConfirmDialog first; onChange fires only after confirmation
suspend fun stopEverything(app: Mob8NApp): String                // ORDER: HarnessPrefs.clearBypass + ChatRunner.clearConversationBypass -> ChatRunner.cancelAll -> engine.cancelAllRuns -> "Stopped 2 chats, 1 run; Bypass off" (§4.4)
```
`ui/Chat.kt`: `ApprovalBubble` → `PendingCallCard(call, decided: Boolean?, onApprove, onDeny, onOpenDraft)` per call + `ApprovalHeader(pending, decided, onApproveAll, onDenyAll)`; `ToolCard(tu, kind, decision: Decision, result, ms)` chip = `Permissions.chip(decision)` where `decision = Permissions.parseGate(meta.gate[tu.id], mode) ?: Decision(mode, riskFor(name, kind, catalog, servers), …)` (the fallback `riskFor` passes `isAction = catalog.agentTools().firstOrNull { it.spec.toolName == name }?.spec?.kind == NodeKind.ACTION`); `save_workflow` card offers **Open in editor** when `ChatDrafts.map` still holds the draft (blocked-in-Plan path); draft summaries render every issue line starting with `Runs unattended:` in `MaterialTheme.colorScheme.error` (P16). Settings sheet: `ModeSelector(scope = CONVERSATION)` (inherit allowed) replaces the two auto-approve Switches; confirming Bypass saves `s.copy(mode = BYPASS, bypassUntil = now + Permissions.BYPASS_TTL_MS)`; picking any other mode saves `bypassUntil = 0`. `ui/AiSettings.kt`: section "Permission mode" (`ModeSelector(allowInherit = false, scope = GLOBAL)` + current Bypass status line incl. "N chats in Bypass"). `ui/Dashboard.kt`: card 10 "Permissions" (mode, Bypass status, **Stop everything** button → confirm dialog → `stopEverything`). `ui/WorkflowList.kt`: title "Mahout". `ui/App.kt`: `BypassBanner(engine)` rendered ONCE in a `Column` above the two-pane `Row` / the phone `Scaffold` content, so every screen (Editor, Runs, Skills, Knowledge, Settings, MCP, Permissions included) shows it; no screen renders its own. `ui/BuildWithAi.kt`: the draft card's issue list uses the same error tint for `Runs unattended:` lines.

### 2.8 Keys, names, ids (frozen)
| Item | Value |
|---|---|
| settings keys | `permission_mode` (`plan\|ask\|auto\|bypass`, default `ask`), `bypass_until` (Long epoch ms, 0 = none; global scope only) |
| ChatSettings JSON | `"mode": "plan"\|"ask"\|"auto"\|"bypass"` or absent/null = inherit; `"bypassUntil": <epoch ms>` (absent = 0) |
| ai.agent param | `permissionMode` (ENUM `inherit\|plan\|ask\|auto\|bypass`, default `inherit`, not templated) — appended AFTER `skills` (last param) |
| ChatMessage.meta | `mode` (String key) and `gate` (`{ "<tool_use id>": "<Risk.name.lowercase()>/<Verdict.name>" }`, e.g. `"coding/RUN"`, `"always/ASK"`, `"write/BLOCK"`) on assistant rows with tool_use blocks |
| Conversation.pendingJson | JSON array of tool_use blocks; a block may carry `"_decided": true\|false` (P17); `parsePending` strips it |
| Builder issue | `Runs unattended: <node> (askApproval=false)` / `(permissionMode=bypass)` / `(permissionMode=auto)` (prefix `Runs unattended: `) |
| tool result texts | `denied by user` · `plan mode: …` (prefix `plan mode: `) · `Bypass expired — …` · `… not in this script's allowNodes — it needs approval in auto mode: call the node tool directly so the user can approve it` · run rows `stopped by user` |
| tool_use ids | unique + non-blank per assistant turn (`Turn.withUniqueToolIds`: `call_N`, `<id>_2`) |
| notification buttons | `Approve all` / `Deny all` (same PendingIntents/extras as v4) |
| Bypass TTL | 60 min (`Permissions.BYPASS_TTL_MS`) |
| res | `app_name` Mahout · `ic_launcher_foreground` / `ic_launcher_background` / `ic_launcher_monochrome` |

---

## 3. Per-call approval UX (`ui/Chat.kt`)

Thread (reverse `LazyColumn`, newest at the bottom). While `Status.Awaiting(pending, decided)` (or `conv.status == "awaiting"` with `ChatRunner.status` Idle after process death: `(uses, decided) = parsePending(conv.pendingJson)` — the stored `_decided` map pre-fills the cards' Approved/Denied labels (P17) — with `Decision` from the last assistant row's `meta.gate[id]`, falling back to `riskFor` + `settings.modeOrLegacy() ?: HarnessPrefs.mode`), the list shows:

1. **`ApprovalHeader`** (key `approval-header`, `tertiaryContainer`): `"Wants to run 3 tools · 1 decided"`, `liveRegion = Polite`; buttons **Approve all** (`Button`, contentDescription `"Approve all 3 pending tool calls"`) and **Deny all** (`OutlinedButton`, `"Deny all pending tool calls"`); both 48 dp; disabled once every call is decided. Below: one line `"Mode: Ask — safe, coding and destructive actions ask"` (from `modeHelp`).
2. **One `PendingCallCard` per call** (key `pending:<id>`, same order as the batch): monospace tool name, chip `Permissions.chip(Decision)` (e.g. `coding · asks (ask)`), the v4 preview (`OperatorTools.previewFor`: skill markdown, command/code with "Show all N lines", `run_js` allow-list line, `save_workflow` draft summary + **Open in editor**, draft-expired warning), then **Approve** / **Deny** (48 dp, `contentDescription "Approve run_shell"` / `"Deny run_shell"`). Once decided the buttons are replaced by a label `"Approved — waiting for the other calls"` / `"Denied"` (text, not colour), and the card gets `contentDescription "run_shell approved, waiting"`. A single-call batch shows the header without the "all" buttons (redundant) — the card's own buttons suffice.
3. Composer placeholder `"Decide the 3 pending calls above first"`; Send disabled while any call is undecided.

After the batch completes, the assistant row's `ToolCard`s render as today with chip `Permissions.chip(Decision(...))` computed from `meta.mode` + `riskFor`; a denied call shows the `is_error "denied by user"` result in the error tint; a blocked call shows `plan mode: …`; an expired-Bypass call shows `Bypass expired — …`.

Notification (backgrounded): title `notificationTitle(pending)` = `"Chat wants to run 3 tools"` (1 → `"Chat wants to run run_shell"`), text = numbered lines `"<n>. <tool> — <preview first line ≤ 120>"`, ≤ 8 lines then `"+N more"`; buttons **Approve all** / **Deny all**; tapping the body opens `mob8n://chat/<id>` at the header.

Denials: a fully denied batch increments `denials` (max 2, then the note "Stopped after 3 denials — tell me what you want instead"); a mixed batch does not count as a denial (the model got real results).

Tap timing: a tap (bubble or notification) can never be lost — `decisions[id]` holds the `Batch` before the Awaiting status or the notification exists (P6), and after process death `decide()` creates the Batch before it launches the resume turn (P17). A tap on a call that is already decided is a no-op (the card shows the label, buttons are gone).

---

## 4. Bypass safety

### 4.1 Dialog (verbatim, `BypassConfirmDialog`)
Title: **Turn on Bypass mode <scopeLabel>?** — `scopeLabel` = `"for every chat and agent"` (GLOBAL) / `"for this chat"` (CONVERSATION).
Body (first line differs by scope):
```
GLOBAL:       Every chat and every ai.agent workflow that inherits the global mode stops asking for the next 60 minutes.
CONVERSATION: This chat stops asking for the next 60 minutes. Other chats, agents and workflows keep their own mode.
The assistant will immediately:
• run shell commands, JavaScript and write, create or delete workspace files
• save, enable, disable and DELETE workflows and skills, and run any saved workflow you ask for, whatever it contains
• approve or deny workflow runs that are waiting for you (resume_run)
• run every action node, including UI automation (tapping and typing in other apps), untrusted MCP tools, downloads, intents and system-setting changes
Read-only tools and drafts behave as always. Bypass turns itself off after 60 minutes, and Stop everything (Dashboard or the red banner) ends it at once.
```
Buttons: **Turn on Bypass** (confirm; `contentDescription "Confirm Bypass mode"`), **Cancel**. Only the confirm button calls `HarnessPrefs.setMode(ctx, BYPASS, confirmed = true)` (GLOBAL) or `engine.saveConversation(conv.copy(settingsJson = s.copy(mode = BYPASS, bypassUntil = now + Permissions.BYPASS_TTL_MS).json()))` (CONVERSATION — touches NOTHING global, P3/P13). A saved workflow's `ai.agent permissionMode = bypass` / `askApproval = false` is the author's explicit choice at edit time (a v1 capability); the param help says so, no dialog (P4) — and the Builder cannot slip it in unseen (P16).

### 4.2 Expiry
Each scope carries its own `until = now + 60 min` when armed (`settings.bypass_until` / `ChatSettings.bypassUntil`). `Permissions.resolve` folds an expired BYPASS at either scope to ASK at gate time; `ChatRunner.wrap` re-checks the SAME resolution at call time for tools whose verdict was RUN by BYPASS and fails them with `Permissions.EXPIRED` (the next turn gates as ASK; the model re-plans and the calls come back as approvals). `ai.agent` checks at run start only (a run is ≤ 10 min). Housekeeping needs nothing: an expired timestamp is inert. `HarnessPrefs.load` + `engine.conversations()` + a 1-s ticker in `BypassBanner` drive the countdown; when it reaches 0 the banner disappears and `HarnessPrefs.clearBypass` is NOT called automatically (the stored global `BYPASS` stays and resolves to ASK; Settings shows "Bypass (expired) — behaves as Ask" so the user sees why nothing runs unasked and can re-arm — re-arming is `setMode(BYPASS, confirmed)` through the dialog again, never a side effect of another scope). An expired conversation row (`mode == BYPASS`, `bypassUntil <= now`) shows "Bypass (expired) — behaves as Ask" in its sheet. **Clock hardening**: `SystemReceiver` clears every Bypass on `TIME_SET` and boot (§2.5); Bypass never survives a clock change.

### 4.3 Banner
`BypassBanner`: full-width `Surface(color = errorContainer)` rendered ONCE by `ui/App.kt` between the status bar inset and the screen content (both layouts), so it is visible on every screen; height ≥ 48 dp, text `bypassBannerText(until, now)` in `onErrorContainer` with `liveRegion = Assertive` on first appearance, trailing **Stop** `TextButton` (48 dp, `contentDescription "Stop everything and turn off Bypass"`) → confirm dialog "Stop everything?" ("Cancels every running chat turn and workflow run, closes every run waiting for a timer or approval, and turns Bypass off everywhere.") → `stopEverything`. Shown iff `bypassActiveUntil(HarnessPrefs.bypassUntil, conversations, now) > 0` — whichever scope armed it; `until` = the latest expiry.

### 4.4 Kill switch — **Stop everything**
Dashboard card 10 "Permissions": `"Mode: Ask"` (+ `"Bypass active — 41 min left"` / `"2 chats in Bypass"` when armed), **Change** → `Screen.AiSettings`, **Stop everything** (`Button`, error colours, 48 dp) → the same confirm dialog → `stopEverything(app)`, in THIS order (a slow or failing join must never leave Bypass armed):
1. `HarnessPrefs.clearBypass(ctx)` — synchronous prefs commit, cannot fail: banner gone, global BYPASS → ASK. Then `ChatRunner.clearConversationBypass(engine)` (Room writes; `runCatching`). From here every new turn/agent gates as ASK.
2. `ChatRunner.cancelAll(app)` — every live turn cancelled; epilogues write `is_error "cancelled by user"` + `setConversationState(idle)` + cancel their notifications.
3. `engine.cancelAllRuns()` — §2.5: executing runs → FAILED `stopped by user` (guarded + after-join sweep); SUSPENDED runs (incl. `logic.wait_until` / `wait_approval` timers) closed FAILED `stopped by user` with timers/notifications cancelled; every hosted Deferred completes.
Snackbar `"Stopped 2 chats, 1 run; Bypass off"` (counts are a snapshot; a trigger may start a new run under ASK right after). Works in every mode (it is a stop button, not only a Bypass exit). `HostService` stops itself after its normal 60 s idle.

---

## 5. Rename checklist (exact files / strings)

Rule: rename the WORD the user reads; keep every identifier, path, package, scheme and DB name. **Keep-list** (grep before AND after; a blanket replace is forbidden): `Mob8NApp`, `Mob8NTheme`, `Theme.Mob8N`, `LOG_TAG = "Mob8N"` AND the literal `Log.w("Mob8N", …)` tag in `Mob8NApp.kt:52` (`adb logcat -s Mob8N` must keep working), `com.mob8n.*`, `com.mob8n.SHORTCUT`, `com.mob8n.files` (FileProvider), `mob8n://`, `mob8n.db`, `Files.SUBDIR = "Mob8N"` and the literal paths `Documents/Mob8N`, `Music/Mob8N`, the `.mob8n/` workspace folder, the `mob8n` JS bridge object (`mob8n.runNode`, `mob8n.http` — an API identifier that is user-visible in the `run_js` description and prompt; stays), `MOB8N_SERIAL`, `install.sh`, and every `Mob8N` inside a code comment or `docs/DESIGN*.md` contract text.

### 5.1 `res/values/strings.xml` (integrator)
```xml
<string name="app_name">Mahout</string>
<string name="share_target_label">Run Mahout workflow</string>
<string name="tile_label">Mahout</string>
<string name="shortcut_1">Run workflow 1</string> … <!-- unchanged -->
<string name="ui_automation_label">Mahout UI automation</string>
<string name="ui_automation_description">Lets Mahout workflows read the screen and tap, type and scroll in other apps only while a UI-automation step runs. Idle otherwise; nothing is recorded except screenshots you explicitly take.</string>
```
`themes.xml` (`Theme.Mob8N`), `shortcuts.xml`, `file_paths.xml`, `accessibility_service_config.xml`, `AndroidManifest.xml`: unchanged (identifiers; the manifest reads `@string/app_name`).

### 5.2 Launcher icon (integrator; `docs/brand/mahout-logo.svg` → VectorDrawable, 108 dp viewport, art scaled ×0.13 and centred on the INK, not on the 512 box, so it fills the 66 dp safe circle)
Ink bbox in SVG space (paths below incl. stroke widths): x ≈ 88…364, y ≈ 104…474 → centre ≈ (226, 289), 276 × 370 units. At scale 0.13 that is 35.9 × 48.1 dp, half-diagonal 30.0 dp < 33 dp safe radius. `translate = 54 − 0.13 × centre = (24.6, 16.4)`. (The earlier 0.11 / translate 25.84 sat low-left at ~42 % of the visible circle: it centred the 512 box, not the ink.) The integrator re-measures the bbox once the paths are in place (`aapt`/Android Studio preview) and adjusts translate only; scale stays 0.13 unless the half-diagonal exceeds 32 dp.
`<monochrome>` in `mipmap-anydpi-v26/*.xml` is honoured on API 33+ only and silently ignored on 26–32 — that is correct and complete for minSdk 26; do NOT add `mipmap-anydpi-v33` copies or legacy PNG mipmaps.
`res/drawable/ic_launcher_background.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF0F2A3F" android:pathData="M0,0h108v108h-108z" />
</vector>
```
`res/drawable/ic_launcher_foreground.xml` (paths are the SVG's, in its 512-unit space; circles converted to two arcs; ink gradient `#F6E7C8→#E9C46A` flattened to its midpoint `#EFD599`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <!-- Mahout mark: elephant head in profile, trunk carrying a three-node workflow (docs/brand/mahout-logo.svg). 512-unit art, scale 0.13, translate = 54 - 0.13 * ink centre (226, 289) -> ink centred, inside the 66 dp safe zone. -->
    <group android:scaleX="0.13" android:scaleY="0.13" android:translateX="24.6" android:translateY="16.4">
        <!-- ear (behind the head) -->
        <path android:fillColor="#FFEFD599" android:fillAlpha="0.92" android:pathData="M212,150 C150,118 96,150 92,214 C88,280 132,330 196,318 C214,314 224,300 226,284 L226,176 Z" />
        <!-- head -->
        <path android:fillColor="#FFEFD599" android:pathData="M180,196 a92,92 0 1,0 184,0 a92,92 0 1,0 -184,0 Z" />
        <!-- forehead notch to the trunk root -->
        <path android:fillColor="#FFEFD599" android:pathData="M312,268 C338,262 352,250 356,240 L356,296 C356,312 344,320 330,318 L300,300 Z" />
        <!-- trunk -->
        <path android:strokeColor="#FFEFD599" android:strokeWidth="44" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M332,292 C336,330 330,366 312,396 C296,422 296,446 322,452" />
        <!-- tusk -->
        <path android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.9" android:strokeWidth="12" android:strokeLineCap="round" android:pathData="M262,286 C270,306 288,316 306,314" />
        <!-- eye -->
        <path android:fillColor="#FF0F2A3F" android:pathData="M285,184 a15,15 0 1,0 30,0 a15,15 0 1,0 -30,0 Z" />
        <path android:fillColor="#FFF6E7C8" android:pathData="M302,179 a4,4 0 1,0 8,0 a4,4 0 1,0 -8,0 Z" />
        <!-- workflow riding the trunk: edges (dark outline, then teal) -->
        <path android:strokeColor="#FF0F2A3F" android:strokeWidth="12" android:strokeLineCap="round" android:pathData="M334,322 L320,380 M320,380 L344,434" />
        <path android:strokeColor="#FF7FD1C5" android:strokeWidth="6" android:strokeLineCap="round" android:pathData="M334,322 L320,380 M320,380 L344,434" />
        <!-- nodes -->
        <path android:fillColor="#FF7FD1C5" android:strokeColor="#FF0F2A3F" android:strokeWidth="6" android:pathData="M318,322 a16,16 0 1,0 32,0 a16,16 0 1,0 -32,0 Z" />
        <path android:fillColor="#FF7FD1C5" android:strokeColor="#FF0F2A3F" android:strokeWidth="6" android:pathData="M304,380 a16,16 0 1,0 32,0 a16,16 0 1,0 -32,0 Z" />
        <path android:fillColor="#FF7FD1C5" android:strokeColor="#FF0F2A3F" android:strokeWidth="6" android:pathData="M328,434 a16,16 0 1,0 32,0 a16,16 0 1,0 -32,0 Z" />
        <!-- reins: ear -> first node. ponytail: solid at 55 % (VectorDrawable has no dash array); upgrade = dotted path -->
        <path android:strokeColor="#FF7FD1C5" android:strokeAlpha="0.55" android:strokeWidth="6" android:strokeLineCap="round" android:pathData="M150,300 C210,330 270,330 318,322" />
    </group>
</vector>
```
`res/drawable/ic_launcher_monochrome.xml` — same `<group>` and the same `pathData` for ear, head, notch, trunk and tusk at `#FFFFFFFF` (alphas kept); the two eye paths and the 12-wide dark edge path omitted (themed icons use alpha only, so the eye would fill in). The workflow motif must still read as a tone on the white trunk: the 6-wide edge path, the reins and the three node circles are drawn `#FFFFFFFF` with `fillAlpha`/`strokeAlpha = 0.4` and **no stroke on the nodes** (an alpha-1 white node on the alpha-1 white trunk vanishes). `// ponytail: monochrome nodes as a 0.4 tone; upgrade = separate silhouette art`. Both `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` add `<monochrome android:drawable="@drawable/ic_launcher_monochrome" />` after `<foreground>`. `ic_tile.xml` unchanged (P11). Verify on the tablet: launcher, Recents, app info, Settings > Accessibility, the QS tile label, themed-icon toggle (nodes visible as a tone).

### 5.3 Kotlin strings (user-visible; owner in brackets)
ui lane — `ui/WorkflowList.kt:99` `Text("Mahout")` · `ui/Permissions.kt`: `SELF = "Mahout itself"`, `UI_AUTOMATION_DISCLOSURE` ("Mahout reads the screen only …", "stay in Mahout's private cache …"), `OVERLAY_DISCLOSURE` (3×), `BACKGROUND_LOCATION_WHY` (2×), `KNOWLEDGE_PRIVACY` (2×), `MCP_PRIVACY` (check), why-texts at :252/:263, Settings paths :282–287 ("… > Mahout", "Settings > Accessibility > Mahout UI automation"), title :447 `"Set up Mahout"`, :490 `"Coding tools run as Mahout itself (no root, no extra permission)."` · `ui/Chat.kt:192` "talk to the Mahout operator" · `ui/Playlist.kt:55` keep `Music/Mob8N` (path) but say "Mahout writes Music/Mob8N/<name>.m3u".
ai lane — `ai/Chat.kt:374` `"You are the Mahout operator: … inside the Mahout automation app …"` (+ `ChatPromptTest:35` → `startsWith("You are the Mahout operator")`) · `ai/Agent.kt:206` "inside the Mahout app" · `ai/OperatorTools.kt:131` "as Mahout's own sandboxed user" · `ai/Builder.kt:143` "You design workflows for Mahout, an n8n-style automation app …" · `ai/SkillPresets.kt:12/63/65` ("Mahout's own sandboxed user", "Use Mahout's sandboxed shell, JavaScript engine and workspace correctly and truthfully.", "Create, refine and debug Mahout workflows.") + `SkillsTest:71` · `ai/Providers.kt:35` `X-OpenRouter-Title` → `"Mahout"` + `ProvidersTest:51` · `ai/McpClient.kt:198/375` `clientInfo.name = "Mahout"`, :206 "Mahout supports …", :479 "not supported by Mahout" + `McpClientTest:52`.
integrator — `core/Gates.kt:136` `Gate("Background host (notification access or Mahout service)")`, `:143` `Gate("Only while Mahout is open")` (two string literals in `core/`; `GatesTest`/`PermissionStatusTest` do not pin them — verify with grep before and after) · `triggers/QsTileService.kt:31` "Mahout tile, active/inactive" · `triggers/ComponentTriggers.kt:30–31` name `"Shared to Mahout"`, description "…shared to Mahout…", `:52` "the Mahout Quick Settings tile", `:120` "a Mahout notification" · `triggers/EntryActivity.kt:48/55/86` toasts `"Mahout: …"` · `triggers/RuntimeTriggers.kt:442`, `WebhookTrigger.kt:37`, `SystemTriggers.kt:163` · `actions/Knowledge.kt:43`, `Intents.kt:165/184`, `Misc.kt:48` (`ClipData.newPlainText("Mahout", t)`), `:123`, `Files.kt:118` ("in Mahout's storage or the public Documents/Mob8N folder"), `Playlist.kt:49` ("a named Mahout playlist … export Music/Mob8N/<playlist>.m3u"), `Notify.kt:116/161` · `data/Http.kt:128/146`, `StoreNodes.kt:38/41`, `DeviceNodes.kt:379/442` · `apps/CodingNodes.kt:24/26`, `UiAutomationService.kt:61` ("enable Mahout in Settings > Accessibility"), `JsRuntime.kt:211` ("Blocked by Mahout"), `Recipes.kt:182/233` · `engine/Notifs.kt:34` "Shown while Mahout keeps automations running in the background", `:119` `"Mahout automations active"` · `engine/knowledge/Knowledge.kt:394` ("inside Mahout's own storage"), `:555` `User-Agent "Mahout/knowledge"`. Comments mentioning Mob8N may stay.
Node names/descriptions feed the Builder prompt: `BuilderPromptTest` budget (< 44 000) is unaffected (same length ±10 chars).

### 5.4 README / docs (integrator)
- `README.md` H1 → `# Mahout (package com.mob8n)`; first paragraph starts "Mahout (formerly Mob8N) is an n8n-style, on-device workflow-automation app …"; every prose "Mob8N" → "Mahout" except paths, package names, `MOB8N_SERIAL`, `Mob8NApp.kt`, `mob8n://`, the Documents/Music folders and the Project-layout tree; add a v4.1 section (modes table, per-call approval, Bypass safety, kill switch, rename note, draft layout) before "Tablet setup"; "Deviations from DESIGN4P.md" section at the end.
- `docs/DESIGN.md`, `DESIGN2.md`, `DESIGN3.md`, `DESIGN3P.md`, `DESIGN4.md`: one line under each H1: `_Product name since v4.1: **Mahout** (package \`com.mob8n\`). "Mob8N" below is the historical name and still the identifier._` — no other edits (frozen contracts).
- `PLAN-v5.md` already says Mahout.

### 5.5 Onboarding / permissions / notification texts
"Set up Mahout" (title, `ui/Permissions.kt:447`), Skip unchanged; channel `approvals` name "Approvals" (no brand) unchanged, channel `host` description as §5.3 (re-calling `createNotificationChannel` with the same id updates the description; the channel NAME "Background host" is unchanged and carries no brand, so Settings > App notifications shows "Background host" beside "Mahout" — expected, documented, nothing to fix); host notification title "Mahout automations active"; chat approval notification title/text per §3; HostService "Stop" unchanged.

---

## 6. Builder draft autoLayout hook (ui lane)

`ui/ParamLogic.kt`:
```kotlin
/** A Builder graph lands with every node at (0,0) (Builder.parse gives NodeInstance no positions): lay it out before it is shown. Pure (LayoutTest). */
fun needsLayout(graph: Graph): Boolean = graph.nodes.size > 1 && graph.nodes.all { it.x == 0f && it.y == 0f }
```
Hooks:
1. `ui/Editor.kt` `LaunchedEffect(workflowId)` load: `val g = if (needsLayout(wf.graph)) autoLayout(wf.graph) else wf.graph; loaded = wf.copy(graph = g); graph = g; dirty = false` — covers `save_workflow`-saved workflows opened later from the list and every draft; positions persist on the next Save (the user's Save already re-reads the row and writes name + graph).
2. `ui/Chat.kt openDraft`: `Drafts.map[d.id] = Workflow(id = d.id, name = d.name, graph = autoLayout(d.graph), updatedAt = d.createdAt)`.
3. `ui/BuildWithAi.kt draftFrom` and the Refine result already call `autoLayout` — unchanged.
`autoLayout` itself is unchanged (`topoOrder() ?: return graph` keeps cyclic graphs). `// ponytail: save_workflow stores x=y=0, the editor lays out on open; upgrade = autoLayout in core`.

---

## 7. File ownership (zero overlap; paths under `app/src/main/java/com/mob8n/` unless noted)

### ai lane
| File | Change |
|---|---|
| `ai/Permissions.kt` | NEW: `PermissionMode`, `Verdict`, `Decision`, `Permissions` (incl. `isAlwaysNode`, `sanitizeJsAllow`, `gateMeta`/`parseGate`, MCP-in-Plan block), `HarnessPrefs` (§2.1) |
| `ai/Chat.kt` | `Risk` += `CODING`; `ChatSettings.mode` + `bypassUntil` + `modeOrLegacy()`; `PendingCall`; `Batch`; `Status.Awaiting(pending, decided)`; `decide(callId)` (Batch-first, P17), `decideAll`, `cancelAll`, `clearConversationBypass`, `pendingCalls`, `notificationTitle/Text`, `wrap` (EXPIRED + `sanitizeJsAllow`), `pendingJson(uses, decided)`/`parsePending`; `drive` await → `Map<String, Boolean>`, whole batch + `denied`; `runTurn` scope-aware mode resolution, Batch registered before publish, resume with persisted decisions; `Persister` `meta.mode` + `meta.gate`; `ChatPrompt.static` mode sentence + `<context>` line; rename strings |
| `ai/Llm.kt` | `Turn.withUniqueToolIds()` applied in `Llm.step` (P6) |
| `ai/OperatorTools.kt` | `riskOf` CODING branch + `workflowAlways` (P18b) + `(t, catalog, workflows)`; `all(..., mode)`; `gate` shim (or removal); descriptions (`run_shell`, `save_workflow`, `run_js`); `previewFor` run_js refused-ids suffix + unattended lines; rename string |
| `ai/Agent.kt` | `AgentTool.blocked`; `State.denied` consumed in `loop`; param `permissionMode`; `modeFor`, `approvalTitle/Text`; `run()` gating; rename string |
| `ai/Builder.kt` | `unattendedAgents(graph)`, `validate` appends them, prompt rule 11 (P16); rename string |
| `ai/SkillPresets.kt`, `ai/Providers.kt`, `ai/McpClient.kt` | rename strings only (§5.3) |
| tests `app/src/test/java/com/mob8n/ai/` | NEW `PermissionsTest`; EDIT `ChatLoopTest`, `RiskTest`, `AgentToolsTest`, `BuilderTest` (unattended), `LlmTest`/`OpenAiCompatTest` (unique ids), `McpNodesSpecTest` (last-5 params), `ChatPromptTest`, `BuilderPromptTest` (budget), `SkillsTest`, `ProvidersTest`, `McpClientTest`, `Fakes.kt` (helpers `pendingDecisions`, `agentSpecWithMode`) |

### ui lane
| File | Change |
|---|---|
| `ui/Bypass.kt` | NEW (§2.7): banner, dialog (scoped text), `ModeSelector`, `stopEverything` (clearBypass FIRST), pure `bypassBannerText`/`bypassActiveUntil`/`modeLabel`/`modeHelp` |
| `ui/App.kt` | `BypassBanner(engine)` once above both layouts (§4.3) |
| `ui/Chat.kt` | per-call bubbles (§3), `ToolCard` chip from `meta.gate` with `riskFor(isAction)` fallback, settings sheet `ModeSelector(CONVERSATION)` writing `bypassUntil`, `openDraft` layout, red `Runs unattended:` lines, rename string |
| `ui/BuildWithAi.kt` | red `Runs unattended:` issue lines |
| `ui/Dashboard.kt` | card 10 "Permissions" with **Stop everything** (no banner of its own) |
| `ui/WorkflowList.kt` | title "Mahout" |
| `ui/AiSettings.kt` | "Permission mode" section (global `ModeSelector(GLOBAL)`, Bypass status/expiry line, "N chats in Bypass") |
| `ui/Editor.kt` | `needsLayout` on load |
| `ui/ParamLogic.kt` | `needsLayout` |
| `ui/Permissions.kt` | rename strings (§5.3) |
| `ui/Playlist.kt` | wording (§5.3) |
| tests `app/src/test/java/com/mob8n/ui/` | NEW `BypassUiTest` (banner text, `bypassActiveUntil`, labels, `needsLayout`), EDIT `PermissionStatusTest` only if it pins a renamed text |
| in-sandbox stubs only | `ai/Permissions.kt` stub (enum, `Decision`, `parseGate`, `HarnessPrefs` no-ops), `ChatRunner.decide(callId)`/`decideAll`/`cancelAll`/`clearConversationBypass`/`Status.Awaiting(pending, decided)`, `PendingCall`, `ChatSettings.bypassUntil`, `Engine.cancelAllRuns()` — never copied back |

### integrator (after both lanes land)
| File | Change |
|---|---|
| `engine/Engine.kt` | `cancelAllRuns()` (§2.5) |
| `engine/TriggerHub.kt` | `runJobs` (Job → wf.id), `guarded` non-timeout cancellation row, Deferreds completed in `finally`, `cancelAllRuns()` with after-join sweep |
| `triggers/SystemReceiver.kt` | `TIME_SET` / boot → `HarnessPrefs.clearBypass` + `ChatRunner.clearConversationBypass` (§2.5) |
| `engine/Notifs.kt` | button labels "Approve all"/"Deny all"; rename strings |
| `engine/knowledge/Knowledge.kt`, `core/Gates.kt`, `triggers/*`, `actions/*`, `data/*`, `apps/*` | rename strings (§5.3) — `core/` diff limited to the two `Gate(...)` labels, asserted by eye at integration |
| `res/values/strings.xml`, `res/drawable/ic_launcher_{foreground,background,monochrome}.xml`, `res/mipmap-anydpi-v26/*.xml` | §5.1, §5.2 |
| `Mob8NApp.kt` | unchanged unless a lane needs a wiring line (`engine.chatDecision` already points at `ChatRunner::decide(id, ok)`, which is `decideAll`) |
| tests | `CatalogTest` (+ `ai.agent` last param `permissionMode` ENUM default `inherit`, options frozen), `CatalogGatesTest` (135 rows unchanged), `TriggerFilterTest` (renamed `trigger.share` name if pinned — grep first), `BuilderPromptTest` (budget still < 44 000) |
| docs | `README.md`, the five DESIGN H1 lines, this file §10 |

Import rules: ui → `ai.{PermissionMode, Verdict, Decision, Permissions, HarnessPrefs, PendingCall, ChatRunner, ChatSettings, Risk, OperatorTools.riskOf/previewFor, ChatDrafts, Builder.UNATTENDED_PREFIX}`; ai never imports ui; engine never imports ai (hence the local `STOPPED_BY_USER` const in `TriggerHub`); NEW and the only one of its kind: `triggers/SystemReceiver.kt` → `ai.{HarnessPrefs, ChatRunner}` for the clock-change Bypass clear.

---

## 8. Tests (JUnit 4, pure JVM; `isReturnDefaultValues = true`)

### ai
- **`PermissionsTest`** (NEW): (1) `decide` full 4×4 matrix incl. `reason` prefixes; (2) `resolve` order: agent > conversation > global; (3) **per-scope expiry**: `resolve(global = BYPASS, globalUntil = now)` → ASK, `globalUntil = now + 1` → BYPASS; `resolve(conversation = BYPASS, conversationUntil = now, global = ASK, …)` → ASK; `conversation = BYPASS, conversationUntil = now + 1, global = ASK, globalUntil = 0` → BYPASS (a conversation Bypass needs no global timestamp); **no escalation**: `conversation = null, global = BYPASS, globalUntil = 0` → ASK even when another conversation's until is in the future (there is no shared state to read: the signature makes it impossible; the test documents it); (4) `gate`: RUN → `needsApproval == false`, ASK → true, BLOCK → tool throws `NodeException` starting with `plan mode: ` and `needsApproval == false`; same instance when unchanged; PLAN blocks a trusted `kind == "mcp"` READ tool (P18a); (5) `PermissionMode.parse("inherit"/null/"Auto ")`; (6) `ChatSettings.modeOrLegacy()` (both toggles → AUTO, one → null, `mode` wins) and JSON round-trip `"mode":"plan"`, `"bypassUntil"`, missing keys → null/0, **junk `"mode":"bypas"` → null (coerceInputValues pinned)**; (7) `blockedMessage("save_workflow")` mentions "Open in editor"; (8) `chip(Decision)` wording; (17) `isAlwaysNode` (`action.toggle_workflow`, `app.ui_tap`, `app.launch_wait` true; `action.notify`, `data.http` false); `sanitizeJsAllow` AUTO drops exactly those ids and returns them, keeps `data.http`; ASK/BYPASS/PLAN unchanged; (18) `gateMeta`/`parseGate` round-trip (`"coding/RUN"`), junk → null.
- **`ChatLoopTest`** (EDIT + NEW cases): existing tests re-expressed with `ChatSettings(mode = …)`; (9) **mixed batch**: `await = { mapOf("s1" to true, "s2" to false) }` → `calls == ["run_shell"]` (s1 only), ONE results message whose tool_result ids are exactly `[s1, s2]` in that order (`s1` ok, `s2` `is_error "denied by user"`), the next model request contains no tool_use without a tool_result, `denials == 0`, `steps` has a row with `error == "denied by user"`; (10) Approve all (`decideAll` semantics: every id true) equals v4 approve path; (11) deny all → v4 denial path (3rd ends the turn); (12) Plan: `Script(turn(tool_use, shell, list_workflows), done)` with `gate(mode = PLAN)` → no await (`await = { error("must not ask") }`), `list_workflows` ran, shell result `is_error` starting `plan mode: `, model called twice; (13) Auto: run_shell/workspace_write/run_workflow/data_http run, delete_workflow/mcp untrusted/app.ui_* ask; a `workflow__*` tool whose graph holds `action.send_intent` asks (P18b); (14) Bypass: everything runs incl. `resume_run`; (15) `wrap` with `bypassActive = { false }` on a BYPASS-RUN decision → `NodeException(EXPIRED)` result, on an AUTO-RUN decision → runs; `wrap(run_js, AUTO)` hands the tool an input without `app.ui_tap` in `allowNodes` and the result text names the refused id; (16) `pendingCalls` risk/mode per call, unknown tool → ALWAYS; `notificationText` numbering, ≤ 8 lines, "+N more"; (19) `pendingJson(uses, mapOf(s1 to true))` → `parsePending` returns the uses without `_decided` and the map.
- **`LlmTest` / `OpenAiCompatTest`** (EDIT): `Turn.withUniqueToolIds()` — ids `["a", "a", "", "a"]` → `["a", "a_2", "call_3", "a_3"]`; `raw.tool_calls[i].id` rewritten in lock-step; a Turn with unique ids is the same instance.
- **`BuilderTest`** (EDIT): `unattendedAgents` on a graph with `ai.agent{askApproval:false}` and one with `permissionMode:"bypass"` → two `Runs unattended:` issues via `validate`; a default agent → none; prompt contains rule 11.
- **`RiskTest`** (EDIT): coding names → `Risk.CODING`; ACTION invariant now "never READ"; `riskOf(name, "node", …, isAction = true)` for `action_notify` is WRITE and the ui `riskFor` helper agrees (the ACTION-chip regression); `gateFollowsTheToggles` → `gateFollowsTheMode` (four modes, `resume_run` asks in AUTO, runs in BYPASS).
- **`AgentToolsTest`** (EDIT +3): `modeFor` table (`bypass` → null; inherit + `askApproval=false` → null; inherit + true → global with expiry; `plan/ask/auto` params win over a BYPASS global); a Plan-gated loop never returns NeedApproval and blocks `action_notify` with `plan mode: `; `approvalText` lists every call with mode; `AgentNode.spec.params.last().key == "permissionMode"` (ENUM, default `inherit`, not templated); `State.denied` consumed by `loop` (mixed batch → ONE results message, `denied` reset).
- **`McpNodesSpecTest`** :49–51 → `takeLast(5)` = `mcpServers, knowledge, includeWorkflows, skills, permissionMode`.
- **`ChatPromptTest`**, **`SkillsTest`**, **`ProvidersTest`**, **`McpClientTest`**: renamed literals.

### ui
- **`BypassUiTest`** (NEW, pure): `bypassBannerText(now + 57.2 min, now)` → `"Bypass mode — nothing asks — expires in 58 min — Stop"`; 30 s left → `1 min`; `bypassActiveUntil(0, [conv(mode = BYPASS, until = now + 5 min)], now)` → that until; an expired conversation and global 0 → 0; global wins when later; a conversation with `mode = ASK` and a stale `bypassUntil` → ignored; `modeLabel(null) == "Inherit"`; `needsLayout`: two nodes at 0,0 → true; one node → false; one node moved → false; `autoLayout(needsLayout graph)` gives distinct positions.
- **`PermissionStatusTest`**: only if a renamed text was pinned (grep shows none).

### integrator
- **`CatalogTest`**: `+ agentPermissionModeParam` (last param, ENUM, options `inherit plan ask auto bypass`, default `inherit`); node count 135 and lane sizes unchanged.
- **`TriggerFilterTest` / `CatalogGatesTest`**: re-run; touch only if a renamed name is pinned.
- `engine`: `cancelAllRuns` has no pure seam (TriggerHub needs Room + WorkManager) — device plan §9 step 7 covers it; `DelayedRunWorkerTest` unchanged (`cancelAllResumes(ctx, runId)` reused per suspended run).
- Full gate: `glock.sh $SB :app:compileDebugKotlin` per lane, `:app:testDebugUnitTest --tests 'com.mob8n.ai.*'` (ai), `--tests 'com.mob8n.ui.*'` (ui), whole suite at integration (expect ≈ 473 + ~25 green).

---

## 9. Device plan (Pixel Tablet, Android 16, MiniMax-M3 as Default AI; `MOB8N_SERIAL=<tablet> ./install.sh` = `adb install -r` over the live v4 data — never uninstall; debug build so `adb shell run-as com.mob8n` reaches `shared_prefs/settings.xml`; re-grant Accessibility after any `am force-stop`)

0. **Install + rename**: launcher shows the elephant mark titled **Mahout** (long-press → App info "Mahout"); Recents card "Mahout"; themed icons on/off both render; Settings > Accessibility lists "Mahout UI automation"; QS tile "Mahout"; Workflows top bar "Mahout"; Dashboard host card / HostService notification "Mahout automations active" (start a `sleep 20` shell turn and press Home); `adb logcat -s Mob8N` still works (tag unchanged); MiniMax key, MCP servers, knowledge, skills, 8 workflows all present (same package, same DB).
1. **Onboarding text**: `adb shell am force-stop com.mob8n; adb shell run-as com.mob8n sed -i 's/<boolean name="onboarded" value="true" \/>//' shared_prefs/settings.xml`; launch → "Set up Mahout" with Skip; Skip → shell.
2. **Ask (default)**: Settings shows "Permission mode: Ask"; chat "run Torch tile" → ONE pending card (`run_workflow`, chip `safe action · asks (ask)`) with Approve/Deny (no "all" buttons for a single call) → Approve → runs; tool card chip `safe action · approved (ask)`.
3. **Mixed batch, per-call**: "write hello.txt with 'hi', then disable workflow seed-1, then list runs" → header "Wants to run 3 tools" + three cards (`workspace_write` coding, `disable_workflow` destructive, `list_runs` read-only runs at once — may arrive in a separate batch depending on the model; if it does, that is correct too) → Approve `workspace_write`, Deny `disable_workflow` → the batch completes only after both decisions; ONE results row: file written, `disable_workflow` `denied by user` (error tint); MiniMax accepts the transcript (no 400); the assistant acknowledges the denial. Repeat with **Approve all** → both run. Repeat with **Deny all** ×3 → "Stopped after 3 denials" note.
4. **Notification**: start a turn needing approval, Home → notification "Chat wants to run 2 tools" with the numbered list and **Approve all** / **Deny all**; tap Approve all → both run while backgrounded; tap the body → thread opens at the header.
5. **Process death mid-decision**: batch of 2, approve one, `am force-stop com.mob8n`, reopen → the approved card still reads "Approved — waiting for the other calls" (P17, `_decided` in pendingJson), deny the other → the turn resumes, ONE results row (one ran, one `denied by user`). Repeat approving the second one and tapping it twice quickly → no revert, no dropped tap. Repeat with a fully denied batch → the "denied by user" results row and the model re-plans (legacy branch).
6. **Plan**: Settings → Plan (no dialog); chat "create a workflow that notifies me when battery < 20% and save it" → `draft_workflow` runs (READ), `save_workflow` card shows `destructive · blocked (plan)` with result `plan mode: …` and **Open in editor** → editor opens the draft laid out (no stacked nodes) → Save from the editor works (user action) → Back. "run `ls`" → `run_shell` blocked, no bubble, assistant explains. Conversation sheet → mode Ask (overrides global Plan) → `ls` asks again.
7. **Auto**: Settings → Auto; "run `id; uname -a` and write the output to sys.txt" → `run_shell` + `workspace_write` run without a bubble (chips `coding · ran (auto)`); "delete workflow <test one>" → asks (`destructive · asks (auto)`); untrusted deepwiki MCP call → asks; UI automation on + "tap Settings" → asks; "send me a notification" (`action_notify`, ACTION) → runs, chip `safe action · ran (auto)` (not "read-only" — the ACTION-chip regression). **run_js escape**: "use run_js with allowNodes [action.notify, action.toggle_workflow] to notify me and disable seed-1" → the script's `mob8n.runNode("action.toggle_workflow")` fails with "… needs approval in auto mode …", seed-1 stays enabled, the notification fired, the preview line reads "may call: action.notify, action.toggle_workflow (refused in auto mode)"; the model then calls `disable_workflow` directly → asks. Same script in Ask → one approval for `run_js`, both nodes run.
8. **Bypass dialog + banner**: Settings → Bypass → dialog text as §4.1 (GLOBAL first line) → Cancel leaves Ask/Auto; confirm → red banner "Bypass mode — nothing asks — expires in 60 min — Stop" on EVERY screen (Chat list, thread, Dashboard, Workflows, Editor, Runs, Skills, Knowledge, Settings, MCP, Permissions center); `settings.xml` has `bypass_until`; "disable seed-1 then enable it" runs both without asking (chips `destructive · ran (bypass)`); `resume_run` on a suspended `logic.wait_approval` run → runs (dialog warned). **Per-conversation Bypass without escalation**: Stop everything first (mode Ask). Chat A → sheet → Bypass → dialog first line "This chat stops asking…" → confirm → banner appears; `settings.xml` `bypass_until` is still 0/absent and `permission_mode` still `ask`; chat A runs `disable seed-1` unasked; chat B (inherit) "run `ls`" → ASKS; workflow `Manual → ai.agent{inherit, askApproval:true}` → SUSPENDS. Then Settings → Bypass (global) confirmed once, wait for or force its expiry (step 9 edit) so the stored global is `bypass` with an expired timestamp; chat C → sheet → Bypass "for this chat" → confirm → chat B still ASKS and the agent still SUSPENDS (the Monday/Wednesday scenario). Stop → banner gone, chat A/C sheets read Inherit.
9. **Bypass expiry**: with global Bypass on, `am force-stop`, `run-as com.mob8n sed -i 's/name="bypass_until" value="[0-9]*"/name="bypass_until" value="1"/' shared_prefs/settings.xml`, relaunch → no banner; Settings shows "Bypass (expired) — behaves as Ask"; "run `ls`" → asks (`coding · asks (ask)`). Conversation expiry + clock hardening: chat A in Bypass → set the device clock forward 61 min in Settings > Date & time → `TIME_SET` CLEARS every Bypass (banner gone, chat A's sheet reads Inherit, `permission_mode` unchanged), set the clock back → still Inherit/Ask (a clock moved back cannot revive Bypass). The pure per-scope expiry itself is pinned by PermissionsTest (3); on the device it is observed with the debug-lowered TTL below if that route is taken. Call-time re-check: Bypass on → "run `sleep 15; echo a` then `echo b`" (two calls in one batch); while the first runs, in a second `adb shell` set `bypass_until` to 1 → the second call's result is `Bypass expired — …` and the next turn asks (the sed edit is read by the process because `SharedPreferences` re-reads only on process start: if the in-memory cache masks it, record the observation and test the re-check by waiting out a 60-min timer once, or by temporarily lowering `BYPASS_TTL_MS` in a debug build — record which).
10. **Kill switch mid-run**: Bypass on; chat "run `sleep 100`"; manual-run a workflow with `logic.delay 90s`; another with `logic.wait_approval` (SUSPENDED); a third scheduled via `action.schedule_run` +30 s so it is running inside `DelayedRunWorker` (`logic.delay 120s`) when you press Stop; Dashboard → **Stop everything** → confirm → snackbar "Stopped 1 chat, 3 runs; Bypass off"; **the banner disappears before the snackbar** (clearBypass first); chat row shows `cancelled by user`; Runs screen: the delay runs FAILED "stopped by user" (both, within seconds — not "process died" after 15 min), the suspended run FAILED "stopped by user", its approval notification gone, no WorkManager `expire:`/`resume:` left (`adb shell dumpsys jobscheduler | grep com.mob8n` shows none for those ids) and the `DelayedRunWorker` job is gone too (its `await()` returned, no 10-min hold); banner gone; Settings mode = Ask; HostService notification disappears within ~60 s; `ps -A | grep sleep` via a fresh (Ask) `run_shell` → none.
11. **ai.agent modes**: workflow `Manual → ai.agent{goal:"notify me 'hi'", permissionMode: inherit, askApproval: true}` with global Ask → SUSPENDED, notification text lists "1. action_notify (safe action · asks in ask) {title…}" → Approve → SUCCESS. `permissionMode: auto` → runs straight through. `permissionMode: plan` → `action_notify` result `plan mode: …`, agent ends with an explanation (no suspend). `askApproval: false` (inherit) → unchanged v4 behaviour (runs). Global Bypass active + inherit → runs; Bypass expired (step 9 edit) → suspends.
12. **Draft layout + unattended flag**: Build with AI (6-node draft) → nodes spread in layers (regression); chat `save_workflow` (Ask, approve) → open the saved workflow from the list → laid out on open; edit + Save → reopen keeps positions. Then "build a workflow with an ai.agent that never asks for approval" → the draft card and the `save_workflow` preview show the red line `Runs unattended: <node> (askApproval=false)` (or the Builder's repair round removed the flag — record which); if saved, the workflow's agent node shows the flag in the editor.
13. **Dashboard card + a11y**: card 10 shows "Mode: Ask"; TalkBack reads the banner on appearance ("Bypass mode…"), each pending card's Approve/Deny with the tool name, the header's "Approve all 3 pending tool calls", the "Runs unattended" line as text; 48 dp targets by eye at 941 dp and `wm size 411x914` (banner wraps to two lines, Stop stays visible, also on Editor and Settings); `wm size reset`.
14. **Regression**: Auto Liked via media keys, Summarize shared link, skills load, knowledge search, MCP deepwiki (untrusted asks in Ask/Auto), Permissions center count unchanged, Room stays v3 (no migration).
15. **Duplicate tool ids** (MiniMax): "run `echo a` and `echo b` in two separate run_shell calls" repeatedly until a batch of 2 arrives; `adb logcat -s Mob8N | grep tool_use` shows distinct ids; if the provider ever repeats an id, the second card is `<id>_2` and both decisions complete the batch. Also MCP trusted server in Plan → its tools are blocked with `plan mode: `.

---

## 10. Integration record (2026-09-26)

**Gate**: `assembleDebug` + `testDebugUnitTest` green — **498 tests, 0 failures** (v4: 473; +25: `PermissionsTest`, `BypassUiTest`, new `ChatLoopTest` / `AgentToolsTest` / `RiskTest` / `BuilderTest` / `OpenAiCompatTest` cases, `CatalogTest.agentPermissionModeParamIsLastAndFrozen`). 135 nodes, lane sizes `36,18,27,35,6,13` unchanged, Build-with-AI prompt 33,598 chars (< 44 000; rule 11 + rename), `RegexIcuLintTest` green (no new `Regex` literals), Room stays v3, manifest/Gradle untouched. `aapt2 dump badging app-debug.apk` → `application-label:'Mahout'`. Device plan §9 NOT yet run (integration was code + JVM only; the kill switch, banner, icon and TalkBack items need the tablet).

### 10.1 What the integrator did
- **No stubs to delete in the tree**: both lanes copied back only their owned dirs; the sandbox-only stubs (`sandbox-ui/.../ai/*`, `sandbox-ai/.../ui/Chat.kt` two-liner, `Engine.cancelAllRuns() = 0`) stayed in the scratchpad. `ui/Bypass.kt` lost its `import com.mob8n.ai.clearConversationBypass` line (the ai lane made it a `ChatRunner` member — call syntax identical).
- **`engine/TriggerHub.kt`** (§2.5): `runJobs: ConcurrentHashMap<Job, String>` filled in `guarded()` (`coroutineContext.job`, removed in `finally`); a new `catch (e: CancellationException)` clause after the `TimeoutCancellationException` one writes `failRunning(wf.id, "stopped by user")` under `NonCancellable` and rethrows (never matches on the message); `fire`/`fireWorkflow`/`launchRun`/`resume` complete their Deferreds in `finally` (`emptyList()` / `null`); `cancelAllRuns()` = snapshot → cancel(`CancellationException("stopped by user")`) → join → after-join `failRunning` per distinct `wf.id` → SUSPENDED sweep (`Notifs.cancelApproval`, `DelayedRunWorker.cancelAllResumes`, `deleteSuspended`, run row FAILED `stopped by user` with `failedNodeId`), count = running + suspended, never throws. `TriggerHub.STOPPED_BY_USER` is a companion const (engine cannot import ai). Scheduled future runs are untouched.
- **`engine/Engine.kt`**: `suspend fun cancelAllRuns(): Int` delegating to the hub inside `try/catch` (returns 0 on failure).
- **`triggers/SystemReceiver.kt`**: `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED` and `TIME_CHANGED` call `HarnessPrefs.clearBypass(ctx)` + `ChatRunner.clearConversationBypass(engine)` (each guarded) before the existing `host.fire(...)` — the only `triggers → ai` import, as §7 allows.
- **`engine/Notifs.kt`**: chat approval buttons **Approve all** / **Deny all** (same PendingIntents → `Engine.chatDecision` → `ChatRunner.decide(app, id, ok)` == `decideAll`); channel `host` description and the host notification title say Mahout.
- **Rename** (§5.1, §5.3 integrator rows): `strings.xml` (`app_name`, `share_target_label`, `tile_label`, `ui_automation_label/description`); `core/Gates.kt` two `Gate(...)` labels only (`core/` diff = those two literals); `triggers/{QsTileService, ComponentTriggers ×4, EntryActivity ×3, RuntimeTriggers, WebhookTrigger, SystemTriggers}`; `actions/{Knowledge, Intents ×2, Misc ×2, Files, Playlist, Notify ×2}`; `data/{Http ×2, StoreNodes ×2, DeviceNodes ×2}`; `apps/{CodingNodes ×2, UiAutomationService, JsRuntime, Recipes ×2}`; `engine/knowledge/Knowledge.kt` (path error text + `User-Agent: Mahout/knowledge`). Keep-list verified by grep after: `Mob8NApp`, `Mob8NTheme`, `Theme.Mob8N`, `LOG_TAG = "Mob8N"`, `Log.w("Mob8N", …)` in `Mob8NApp.kt:52`, `Files.SUBDIR = "Mob8N"`, `Documents/Mob8N`, `Music/Mob8N`, `mob8n://`, `com.mob8n.*`, code comments — all untouched. No test pinned a renamed literal outside the ai lane's own edits (`TriggerFilterTest`, `CatalogGatesTest`, `PermissionStatusTest` unchanged).
- **Launcher icon** (§5.2): `ic_launcher_background.xml` solid `#0F2A3F`; `ic_launcher_foreground.xml` = the SVG paths verbatim (circles → two arcs, ink gradient flattened to `#EFD599`) in a `<group scale 0.13>`; the ink bbox was re-measured numerically (x 92…364, y 104…474, centre (228, 289), 35.4 × 48.1 dp, half-diagonal 29.9 dp < 33) → **translate (24.4, 16.4)** (contract said 24.6 — the 0.2 dp difference is the ear's true left edge); `ic_launcher_monochrome.xml` white silhouette, no eye, no dark edge outline, nodes/edges/reins at 0.4 alpha with no node stroke; both `mipmap-anydpi-v26` XMLs gained `<monochrome>`. `ic_tile.xml` unchanged (P11).
- **Docs**: README H1 `# Mahout (package com.mob8n)`, first paragraph "Mahout (formerly Mob8N)…", new "v4.1" section before "Tablet setup", "Deviations from DESIGN4P.md" before "Ponytail notes", ponytail list updated; one italic line under each of the five DESIGN H1s.
- **Tests**: `CatalogTest.agentPermissionModeParamIsLastAndFrozen` (last param `permissionMode`, ENUM, options `inherit plan ask auto bypass`, default `inherit`, not templated).

### 10.2 Deviations from this contract (code on disk wins)
ai lane:
1. `Permissions.decide(mode, risk, toolName = "this tool")` — extra defaulted param so a BLOCK reason is `blockedMessage(name)`; `Permissions.decideFor(tool, mode, risk)` is the single home of the P18a MCP-in-Plan rule (used by `gate`, `pendingCalls`, `AgentNode.approvalText`, the Persister meta).
2. `AgentNode.run()` passes `askApproval = (mode != null)` to `loop()` — the spec's `mode != null || ctx.bool("askApproval")` would re-gate an ungated `permissionMode=bypass` agent and suspend it.
3. `ChatPrompt.system(..., mode: PermissionMode = ASK)` trailing defaulted param writes the `Permission mode: <key>` context line.
4. `runTurn` resume path re-awaits the stored batch through the same `await` lambda AFTER tools are built and skips the early `setConversationState("running")` so `pendingJson` survives; an all-denied batch appends its denial results to `state.messages` (flushed by the Persister).
5. `Batch.pending` is a `var` (empty until adoption) + `allDefault` flag so `decide()`/`decideAll()` after process death register the Batch synchronously; `record()` ignores unknown ids; a tap on a decided call is a no-op (`putIfAbsent`).
6. `publish()` persists partial decisions only while the batch is incomplete (avoids an "awaiting" write landing after the turn's "running" write).
7. `wrap` fails an expired Bypass only when `decision.mode == BYPASS && risk != READ`; the run_js note reads `refused in auto mode: <ids> — call those node tools directly so the user can approve them`.
8. `OperatorTools.gate(tools, ChatSettings, risk)` kept as a `@Deprecated` shim; `OperatorTools.all(...)` has a REQUIRED trailing `mode`.
9. `notificationText` caps each whole line at 120 chars. `ChatRunner.DENIED = "denied by user"` const. `clearConversationBypass` is a member (not an extension).
10. `Turn.withUniqueToolIds` tests live in `OpenAiCompatTest` (no `LlmTest` exists); `Fakes.kt` unchanged.
ui lane:
11. `UNATTENDED_PREFIX`/`unattendedAgents(graph)` duplicated as ui-local pure helpers in `ui/ParamLogic.kt` so the Editor flags EVERY graph (drafts and saved workflows); `ui/BuildWithAi.kt` unchanged (its results open in the Editor). `// ponytail` marks the duplication; upgrade = one helper in core.
12. Picking any mode in the chat sheet also writes `autoApproveSafe = autoApproveCoding = false` so a later "Inherit" truly inherits.
13. Finished tool cards read `coding · approved (ask)` / `coding · denied (ask)` (ui `cardChip`) instead of `asks`; pending cards use `Permissions.chip` verbatim.
14. The banner's Stop result is a small "Stopped" dialog (the App root has no snackbar host); the Dashboard card uses a snackbar. The banner is rendered once in `App.kt` in a Column wrapping both layouts AND onboarding.
15. Reverse `LazyColumn`: pending cards first, `ApprovalHeader` after them (so it appears above); Approve all / Deny all only for batches of 2+.
16. `modeHelp()` sentences are the lane's own wording (the contract gave none); Dashboard card 10 keyed `"permissions"` with its own `SnackbarHost`.
integrator:
17. Foreground `translate` is (24.4, 16.4), not (24.6, 16.4) — measured, see §10.1.
18. `TriggerHub.cancelAllRuns` writes `failedNodeId = s.nodeId` on closed SUSPENDED rows (mirrors `Engine.delete`), which §2.5 did not specify.
19. `Engine.cancelAllRuns` wraps the hub call in `try/catch` → 0 (belt-and-braces to "never throws").

### 10.3 Open items (device)
The whole of §9 (install over live v4 data with `install -r`, per-call approval on MiniMax, process-death partial decisions, Plan/Auto/Bypass on the tablet, kill switch mid-run incl. the `DelayedRunWorker` hold, clock hardening, themed icon, TalkBack) is still to be run; nothing here was verified on hardware.
