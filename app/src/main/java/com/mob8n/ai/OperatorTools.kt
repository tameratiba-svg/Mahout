package com.mob8n.ai

import android.content.Context
import com.mob8n.apps.JsBridge
import com.mob8n.apps.JsRuntime
import com.mob8n.apps.Shell
import com.mob8n.apps.Workspace
import com.mob8n.core.Catalog
import com.mob8n.core.EMPTY
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.MAIN
import com.mob8n.core.NodeException
import com.mob8n.core.ParamKind
import com.mob8n.core.RunStatus
import com.mob8n.core.Workflow
import com.mob8n.core.asBool
import com.mob8n.core.asDouble
import com.mob8n.core.asText
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import com.mob8n.core.str
import com.mob8n.engine.Conversation
import com.mob8n.engine.Engine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The chat operator's tool surface (DESIGN4 §5.3): workflow/run/skill/memory operator tools, catalog node tools, workflows-as-tools, MCP, knowledge
 * and the coding sandbox, each classified READ / WRITE / CODING / ALWAYS and gated per conversation by the permission mode (Permissions.gate).
 * Pure parts: riskOf, defs, previewFor.
 */
object OperatorTools {
    /** Node ids that are ALWAYS approval class in the chat (plus every app.ui_* id via AgentNode.isUiTool). */
    val DESTRUCTIVE_IDS: Set<String> = setOf(
        "app.launch_wait", "action.write_file", "action.download", "action.toggle_workflow", "action.knowledge_remove", "action.send_intent",
        "action.ringer_dnd", "action.display_settings", "action.set_ringtone", "action.wallpaper", "action.schedule_run", "app.shell_run",
    )
    /** Coding tools (Risk.CODING): run in Auto/Bypass, ask in Ask, blocked in Plan (PLAN-v5 §4). */
    val CODING: Set<String> = setOf("run_shell", "run_js", "workspace_write", "workspace_mkdir", "app_shell_run")
    val ALWAYS_NAMES: Set<String> = setOf("disable_workflow", "delete_workflow", "save_workflow", "resume_run", "skill_create", "skill_update", "skill_delete", "workspace_delete")
    val WRITE_NAMES: Set<String> = setOf("run_workflow", "enable_workflow", "memory_update")
    /** Operator tool names (the fixed ones; `workflow__` / `mcp__` are prefixes). */
    val NAMES: List<String> = listOf(
        "list_workflows", "get_workflow", "describe_node", "run_workflow", "enable_workflow", "disable_workflow", "draft_workflow", "save_workflow", "delete_workflow",
        "list_runs", "get_run", "resume_run", "run_shell", "run_js", "workspace_list", "workspace_read", "workspace_write", "workspace_mkdir", "workspace_delete",
        "skill_list", "load_skill", "skill_create", "skill_update", "skill_delete", "memory_update", "show_panel",
    )
    const val SHELL_RUN_ID = "app.shell_run"
    /** DESIGN5 §8.3: Mob8NApp points this at uiIntents (mob8n://panel/<slug>); show_panel calls it only while an activity is visible. */
    @Volatile var openPanel: (slug: String) -> Unit = {}
    const val RESULT_CAP = 8 * 1024
    const val OUT_CAP = 4 * 1024
    private const val READ_CHUNK = 6_000

    // ---------------------------------------------------------------- classification (pure)

    /** DESIGN4 §5.3 / DESIGN4P P2+P18b table. `isAction` = the node's kind is ACTION (AgentTool.node encodes it in needsApproval; RiskTest passes the catalog's kind); `workflowAlways` = the exposed workflow's graph holds a destructive/UI node. */
    fun riskOf(name: String, kind: String, trustedMcp: Boolean, nodeId: String?, isAction: Boolean = false, workflowAlways: Boolean = false): Risk = when {
        name in CODING -> Risk.CODING
        name in ALWAYS_NAMES -> Risk.ALWAYS
        kind == "mcp" -> if (trustedMcp) Risk.READ else Risk.ALWAYS
        kind == "workflow" -> if (workflowAlways) Risk.ALWAYS else Risk.WRITE
        kind == "node" -> when {
            nodeId != null && (nodeId in DESTRUCTIVE_IDS || AgentNode.isUiTool(nodeId)) -> Risk.ALWAYS
            nodeId in AgentNode.NEEDS_APPROVAL_IDS || isAction -> Risk.WRITE
            else -> Risk.READ
        }
        name in WRITE_NAMES -> Risk.WRITE
        else -> Risk.READ
    }

    /** Risk of a built (UNGATED) tool: the node id is recovered from the catalog by tool name; a trusted MCP tool is the one AgentTool.mcp left ungated; a `workflow__*` tool is ALWAYS when its graph holds a destructive/UI node (P18b). */
    // ponytail: run_workflow (by id) stays WRITE in Auto; upgrade = graph-aware risk per call
    fun riskOf(t: AgentTool, catalog: Catalog, workflows: List<Workflow> = emptyList()): Risk {
        val nodeId = if (t.kind == "node") catalog.agentTools().firstOrNull { it.spec.toolName == t.name }?.spec?.id else null
        val wfAlways = t.kind == "workflow" && workflows.firstOrNull { WorkflowTools.toolName(it.name, it.id) == t.name }?.graph?.nodes?.any { Permissions.isAlwaysNode(it.type) } == true
        return riskOf(t.name, t.kind, trustedMcp = t.kind == "mcp" && !t.needsApproval, nodeId = nodeId, isAction = t.kind == "node" && t.needsApproval, workflowAlways = wfAlways)
    }

    /** v4 shim: the legacy toggles map to a mode (both on = AUTO, else ASK) and go through the one policy function. */
    @Deprecated("use Permissions.gate")
    fun gate(tools: Map<String, AgentTool>, s: ChatSettings, risk: (AgentTool) -> Risk): Map<String, AgentTool> = Permissions.gate(tools, s.modeOrLegacy() ?: PermissionMode.ASK, risk)

    // ---------------------------------------------------------------- strict schema helpers (shared with Skills / WorkflowTools)

    internal fun prop(type: String, desc: String): JsonObject = buildJsonObject { put("type", type); put("description", desc) }
    internal fun opt(type: String, desc: String): JsonObject = buildJsonObject {
        put("anyOf", buildJsonArray { add(buildJsonObject { put("type", type) }); add(buildJsonObject { put("type", "null") }) }); put("description", desc)
    }
    internal fun optArr(desc: String): JsonObject = buildJsonObject {
        put("anyOf", buildJsonArray { add(buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) }); add(buildJsonObject { put("type", "null") }) })
        put("description", desc)
    }
    /** {name, description, strict:true, input_schema: objectSchema} — every property required, optionals nullable. */
    internal fun def(name: String, description: String, props: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("name", name); put("description", description); put("strict", true); put("input_schema", Llm.objectSchema(props))
    }
    internal fun cap(s: String, max: Int = RESULT_CAP): String = if (s.length > max) s.take(max) + "…(truncated)" else s
    private fun enc(o: JsonObject) = JSON.encodeToString(JsonObject.serializer(), o)
    private fun enc(a: JsonArray) = JSON.encodeToString(JsonArray.serializer(), a)

    /** Pure operator defs (workflows, runs, memory, coding, workspace + the five skill defs) for schema tests. */
    fun defs(): List<JsonObject> = listOf(
        def("list_workflows", "List the user's workflows: id, name, enabled, last run, trigger types, tool name when exposed as a tool.", emptyMap()),
        def("get_workflow", "One workflow: nodes with types, validation issues and the compact graph JSON.", mapOf("id" to prop("string", "Workflow id (from list_workflows)"))),
        def("describe_node", "Exact params (keys, kinds, defaults, options), ports and output fields of one catalog node.", mapOf("id" to prop("string", "Catalog node id such as data.http"))),
        def("run_workflow", "Run a workflow now at its Manual (or first) trigger and wait for it; returns runId, status, error.", mapOf(
            "id" to prop("string", "Workflow id"), "items" to opt("string", "JSON array of objects (or one object) as the trigger items; null = one empty item"))),
        def("enable_workflow", "Enable a workflow so its triggers fire.", mapOf("id" to prop("string", "Workflow id"))),
        def("disable_workflow", "Disable a workflow (asks the user).", mapOf("id" to prop("string", "Workflow id"))),
        def("draft_workflow", "Build or change a workflow with the workflow builder (uses the full node catalog). Returns a draftId, nodes, edges and issues; nothing is saved until save_workflow.", mapOf(
            "description" to opt("string", "What the new workflow should do (one precise paragraph); null when refining"),
            "workflowId" to opt("string", "Existing workflow to change; null = new"),
            "instruction" to opt("string", "The change to apply to the existing workflow"))),
        def("save_workflow", "Save a draft from draft_workflow as a new workflow (disabled unless enabled=true) or over the workflow it refines. The user approves the preview (blocked in Plan mode: open the draft in the editor instead).", mapOf(
            "draftId" to prop("string", "draftId from draft_workflow"), "enabled" to opt("boolean", "Enable after saving (null = keep/disabled)"))),
        def("delete_workflow", "Delete a workflow permanently (asks the user).", mapOf("id" to prop("string", "Workflow id"))),
        def("list_runs", "Recent runs, newest first.", mapOf("workflowId" to opt("string", "Only this workflow; null = all"), "limit" to opt("integer", "Max rows (null = 20, max 50)"))),
        def("get_run", "One run with its per-node log: status, duration, error and the first output item.", mapOf("runId" to prop("string", "Run id"))),
        def("resume_run", "Decide a run that is waiting for approval (asks the user; the decision must be one of the run's choices).", mapOf(
            "runId" to prop("string", "Suspended run id"), "decision" to prop("string", "approve | deny (or another listed choice)"))),
        def("memory_update", "Replace the operator memory note shown in every conversation (≤ 4096 chars, no secrets or private data).", mapOf("text" to prop("string", "The whole note"))),
        def("run_shell", "Run a shell command with /system/bin/sh as Mahout's own sandboxed user, cwd = the workspace (toybox tools only; no root, pm, settings, input, python, node, curl). Asks the user unless the permission mode is Auto or Bypass.", mapOf(
            "command" to prop("string", "Command line for sh -c"), "stdin" to opt("string", "Text piped to stdin"),
            "timeoutMs" to opt("integer", "1000..120000 (null = 30000)"), "cwd" to opt("string", "Workspace-relative directory (null = root)"))),
        def("run_js", "Run JavaScript (body of an async function; item/items/\$vars/mob8n/console in scope; no network) in the sandboxed engine. mob8n.runNode only for ids in allowNodes; mob8n.http only with allowNetwork. In auto mode, allowNodes ids that need approval (destructive or UI nodes) are refused — call those node tools directly.", mapOf(
            "code" to prop("string", "JavaScript source"), "input" to opt("string", "JSON object bound to `item` (null = {})"),
            "allowNodes" to optArr("Catalog node ids the script may call via mob8n.runNode"), "allowNetwork" to opt("boolean", "Allow mob8n.http (adds data.http)"),
            "timeoutMs" to opt("integer", "1000..120000 (null = 30000)"))),
        def("workspace_list", "List workspace files and folders.", mapOf("dir" to opt("string", "Workspace-relative folder (null = root)"))),
        def("workspace_read", "Read a text file from the workspace (≤ $READ_CHUNK chars per call; use offset for more).", mapOf(
            "path" to prop("string", "Workspace-relative path"), "offset" to opt("integer", "Start char (null = 0)"), "limit" to opt("integer", "Max chars (null = $READ_CHUNK)"))),
        def("workspace_write", "Write (or append to) a text file in the workspace; parent folders are created.", mapOf(
            "path" to prop("string", "Workspace-relative path"), "content" to prop("string", "File text"), "append" to opt("boolean", "Append instead of overwrite"))),
        def("workspace_mkdir", "Create a workspace folder.", mapOf("dir" to prop("string", "Workspace-relative folder"))),
        def("workspace_delete", "Delete a workspace file or an empty folder (asks the user).", mapOf("path" to prop("string", "Workspace-relative path"))),
        def("show_panel", "Open a configured Panels tile by title (Dashboard > Panels).", mapOf("name" to prop("string", "Panel title"))),
    ) + Skills.defs()

    // ---------------------------------------------------------------- the live tool map

    private fun req(input: JsonObject, key: String): String = input[key].asTextOrNull()?.trim()?.ifBlank { null } ?: throw NodeException("$key is required")

    /** run_workflow `items`: null -> one empty item; JSON array/object text; anything else -> {text}. */
    internal fun parseItems(raw: String?): Items {
        val s = raw?.trim()?.ifBlank { null } ?: return listOf(EMPTY)
        return when (val e = runCatching { JSON.parseToJsonElement(s) }.getOrNull()) {
            is JsonArray -> e.map { it as? JsonObject ?: item("value" to it) }.ifEmpty { listOf(EMPTY) }
            is JsonObject -> listOf(e)
            else -> listOf(item("text" to s))
        }
    }

    /** Pure: every agentTool node (ChatSettings.nodeTools narrows) minus app.shell_run (run_shell is the same capability) minus ui tools unless uiAutomation. */
    // ponytail: whole tool list per turn; upgrade = run_node + describe_node compact mode
    fun nodeSpecs(catalog: Catalog, s: ChatSettings, vision: Boolean) =
        AgentNode.filterTools(AgentNode.toolSpecs(catalog, s.nodeTools), s.uiAutomation, vision).filterValues { it.id != SHELL_RUN_ID }

    /** Every tool of one conversation, merged (operator names first so nothing can shadow them). `mode` only feeds run_js (its allow-list is sanitised in Auto, P15). */
    suspend fun all(app: Context, engine: Engine, catalog: Catalog, conv: Conversation, s: ChatSettings, t: LlmTarget, log: (String) -> Unit, mode: PermissionMode): Map<String, AgentTool> {
        val d = defs().associateBy { it["name"].asText() }
        val operator = LinkedHashMap<String, AgentTool>()
        fun add(name: String, call: suspend (JsonObject) -> ToolOut) {
            operator[name] = AgentTool(name, d.getValue(name), riskOf(name, "operator", false, null) != Risk.READ, "operator", rejectTemplates = false, call)
        }
        suspend fun wf(id: String): Workflow = engine.workflow(id) ?: throw NodeException("No workflow with id $id (use list_workflows)")

        add("list_workflows") { _ ->
            val rows = engine.workflows().first().sortedByDescending { it.updatedAt }.map { w -> buildJsonObject {
                put("id", w.id); put("name", w.name); put("enabled", w.enabled); put("lastRunStatus", w.lastRunStatus?.name); put("lastRunAt", w.lastRunAt)
                put("triggers", JsonArray(w.graph.nodes.filter { it.type.startsWith("trigger.") && !it.disabled }.map { JsonPrimitive(it.type) }))
                WorkflowTools.exposed(w)?.let { put("toolName", WorkflowTools.toolName(it.workflowName, it.workflowId)) }
            } }
            ToolOut(cap(enc(JsonArray(rows))))
        }
        add("get_workflow") { input ->
            val w = wf(req(input, "id"))
            val issues = Builder.validate(w.graph, catalog)
            val nodes = w.graph.nodes.joinToString("\n") { "- ${it.name} (${it.type})" + if (it.disabled) " [disabled]" else "" }
            ToolOut(cap("${w.name} [${w.id}] ${if (w.enabled) "enabled" else "disabled"}\nNodes:\n$nodes\nIssues: ${issues.ifEmpty { listOf("none") }.joinToString("; ")}\nGraph JSON:\n${Builder.graphJson(w.name, w.graph)}"))
        }
        add("describe_node") { input ->
            val id = req(input, "id")
            val spec = catalog.spec(id) ?: throw NodeException("Unknown node '$id'. Catalog:\n" + cap(Builder.catalogIndex(catalog), RESULT_CAP - 200))
            ToolOut(cap(buildString {
                appendLine(Builder.catalogLine(spec))
                appendLine("Params (key:kind, * = required, =default, {options}):")
                spec.params.filter { it.kind != ParamKind.SECRET }.forEach { p -> appendLine("- ${Builder.paramLine(p)}" + (if (p.help.isNotBlank()) " — ${p.help}" else "")) }
                Builder.OUTPUT_HINTS[spec.id]?.let { appendLine("Output fields: ${it.joinToString(", ")}") }
                append("Tool name: ${spec.toolName}" + if (spec.agentTool) "" else " (not callable from chat; use it inside a workflow)")
            }))
        }
        add("run_workflow") { input ->
            val w = wf(req(input, "id"))
            val runId = engine.runManual(w.id, parseItems(input["items"].asTextOrNull())) ?: throw NodeException("${w.name} could not start (no trigger node, or the host refused)")
            val run = engine.run(runId).first()
            ToolOut(enc(buildJsonObject { put("runId", runId); put("workflow", w.name); put("status", run?.status?.name); put("error", run?.error) }), isError = run?.status == RunStatus.FAILED)
        }
        add("enable_workflow") { input -> val w = wf(req(input, "id")); engine.setEnabled(w.id, true); ToolOut("Enabled '${w.name}'") }
        add("disable_workflow") { input -> val w = wf(req(input, "id")); engine.setEnabled(w.id, false); ToolOut("Disabled '${w.name}'") }
        add("draft_workflow") { input ->
            val target = input["workflowId"].asTextOrNull()?.ifBlank { null }?.let { wf(it) }
            val desc = input["description"].asTextOrNull().orEmpty().trim()
            val instr = input["instruction"].asTextOrNull()?.trim()?.ifBlank { null }
            if (target == null && desc.isBlank()) throw NodeException("description is required for a new workflow")
            if (target != null && instr == null && desc.isBlank()) throw NodeException("instruction is required when changing an existing workflow")
            val r = Builder.build(app, catalog, desc, target?.graph, target?.name, instr ?: desc.ifBlank { null })
            val draft = ChatDrafts.Draft(UUID.randomUUID().toString().take(8), r.name, r.graph, target?.id, r.errors, System.currentTimeMillis())
            ChatDrafts.put(draft)
            ToolOut(cap(enc(buildJsonObject {
                put("draftId", draft.id); put("name", draft.name); put("targetWorkflowId", target?.id)
                put("nodes", JsonArray(r.graph.nodes.map { n -> buildJsonObject { put("name", n.name); put("type", n.type) } }))
                put("edges", r.graph.edges.size); put("issues", JsonArray(r.errors.map(::JsonPrimitive)))
                put("next", "Show the user the nodes; call save_workflow(draftId) only when they agree")
            })))
        }
        add("save_workflow") { input ->
            val draft = ChatDrafts.map[req(input, "draftId")] ?: throw NodeException("draft expired; call draft_workflow again")
            val id = if (draft.targetWorkflowId != null) {
                val target = wf(draft.targetWorkflowId); engine.save(target.copy(graph = draft.graph, name = draft.name)); target.id
            } else {
                val w = Workflow(UUID.randomUUID().toString(), draft.name, enabled = false, graph = draft.graph, updatedAt = System.currentTimeMillis()); engine.save(w); w.id
            }
            if (input["enabled"].asBool() == true) engine.setEnabled(id, true)
            ChatDrafts.map.remove(draft.id)
            ToolOut(enc(buildJsonObject { put("id", id); put("name", draft.name); put("enabled", engine.workflow(id)?.enabled ?: false); put("issues", JsonArray(draft.issues.map(::JsonPrimitive))) }))
        }
        add("delete_workflow") { input -> val w = wf(req(input, "id")); engine.delete(w.id); ToolOut("Deleted '${w.name}' [${w.id}]") }
        add("list_runs") { input ->
            val wfId = input["workflowId"].asTextOrNull()?.ifBlank { null }
            val limit = (input["limit"].asDouble()?.toInt() ?: 20).coerceIn(1, 50)
            val rows = engine.runs(wfId, limit).first().map { r -> buildJsonObject {
                put("runId", r.runId); put("workflow", r.workflowName); put("status", r.status.name); put("trigger", r.triggerType)
                put("startedAt", r.startedAt); put("endedAt", r.endedAt); put("error", r.error)
            } }
            ToolOut(cap(enc(JsonArray(rows))))
        }
        add("get_run") { input ->
            val runId = req(input, "runId")
            val r = engine.run(runId).first() ?: throw NodeException("No run $runId")
            val logs = engine.nodeLogs(runId).first()
            ToolOut(cap(enc(buildJsonObject {
                put("runId", r.runId); put("workflow", r.workflowName); put("status", r.status.name); put("startedAt", r.startedAt); put("endedAt", r.endedAt); put("error", r.error)
                put("nodes", JsonArray(logs.map { l -> buildJsonObject {
                    put("name", l.nodeName); put("type", l.nodeType); put("status", l.status.name); put("ms", l.durationMs); put("error", l.error)
                    val first = (l.output[MAIN] as? JsonArray)?.firstOrNull()
                    if (first != null) { val s = JSON.encodeToString(JsonElement.serializer(), first); if (s.length <= 2048) put("output", first) else put("output", s.take(2048) + "…") }
                } }))
            })))
        }
        add("resume_run") { input ->
            val runId = req(input, "runId"); val decision = req(input, "decision")
            val sr = engine.suspendedRuns().first().firstOrNull { it.runId == runId } ?: throw NodeException("No run is waiting for approval with id $runId")
            if (decision !in sr.choices) throw NodeException("decision must be one of ${sr.choices}")
            engine.resume(runId, decision)
            ToolOut("Resumed $runId with '$decision' (${sr.title})")
        }
        add("memory_update") { input -> val text = input["text"].asText(); ChatPrefs.setMemory(app, text); ToolOut("Operator memory updated (${text.take(ChatPrompt.MEMORY_MAX).length} chars)") }

        // ---- coding sandbox (apps lane objects; always coding-risk in gate())
        val knowledgeIds = engine.knowledge.resolve(s.knowledge)
        add("run_shell") { input ->
            val cmd = req(input, "command")
            val root = Workspace.root(app)
            val dir = Workspace.resolve(root, input["cwd"].asTextOrNull().orEmpty())
            val timeout = (input["timeoutMs"].asDouble()?.toLong() ?: Shell.DEFAULT_TIMEOUT_MS).coerceIn(1_000, Shell.MAX_TIMEOUT_MS)
            log("sh: " + Shell.redactedCommand(cmd, engine.persistence.allSecretValues()))
            val r = Shell.run(cmd, dir, input["stdin"].asTextOrNull(), timeout, spillDir = root)
            var head: String? = null
            if (r.stdout.length > OUT_CAP) {   // the model sees 4 KB; the whole captured stdout goes to a workspace file
                head = ".mob8n/out-${System.currentTimeMillis()}.txt"
                runCatching { Workspace.write(root, head!!, r.stdout) }.onFailure { head = null }
            }
            ToolOut(enc(buildJsonObject {
                put("exitCode", r.exitCode); put("stdout", r.stdout.take(OUT_CAP)); put("stderr", r.stderr.take(OUT_CAP))
                put("truncated", r.stdoutTruncated || r.stdout.length > OUT_CAP); put("timedOut", r.timedOut); put("ms", r.ms)
                put("outputFile", head ?: r.outputFile); if (head != null && r.outputFile != null) put("spillFile", r.outputFile)
            }), isError = r.timedOut)
        }
        add("run_js") { raw ->
            val input = Permissions.sanitizeJsAllow(raw, mode).first   // ChatRunner.wrap already stripped the ids; belt and braces so the bridge never sees an ALWAYS id in AUTO
            val code = req(input, "code")
            val allow = ((input["allowNodes"] as? JsonArray)?.mapNotNull { it.asTextOrNull()?.trim()?.ifBlank { null } }?.toSet() ?: emptySet()) +
                (if (input["allowNetwork"].asBool() == true) setOf("data.http") else emptySet())
            val item = input["input"].asTextOrNull()?.let { runCatching { JSON.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: EMPTY
            val timeout = (input["timeoutMs"].asDouble()?.toLong() ?: JsRuntime.DEFAULT_TIMEOUT_MS).coerceIn(1_000, JsRuntime.MAX_TIMEOUT_MS)
            val vars = runCatching { engine.persistence.allVariables() }.getOrDefault(emptyMap())
            log("js: ${code.length} chars, allow=$allow")   // scripts are never logged, only their length
            val run = JsRuntime.run(app, code, buildJsonObject { put("item", item); put("items", JsonArray(listOf(item))); put("vars", JsonObject(vars)); put("mode", "single") },
                timeout, JsBridge.forChat(engine, allow, Workspace.root(app), knowledgeIds, log))
            ToolOut(cap(enc(buildJsonObject { put("value", run.value); put("logs", JsonArray(run.logs.map(::JsonPrimitive))); put("ms", run.ms) })))
        }
        add("workspace_list") { input ->
            val rows = Workspace.list(Workspace.root(app), input["dir"].asTextOrNull().orEmpty()).map { e -> buildJsonObject { put("path", e.path); put("dir", e.dir); put("bytes", e.bytes); put("modified", e.modified) } }
            ToolOut(cap(enc(JsonArray(rows))).ifBlank { "[]" })
        }
        add("workspace_read") { input ->
            val r = Workspace.read(Workspace.root(app), req(input, "path"), (input["offset"].asDouble()?.toInt() ?: 0).coerceAtLeast(0), (input["limit"].asDouble()?.toInt() ?: READ_CHUNK).coerceIn(1, READ_CHUNK))
            ToolOut(enc(buildJsonObject { put("text", r.text); put("truncated", r.truncated); put("totalChars", r.totalChars); put("binary", r.binary) }))
        }
        add("workspace_write") { input ->
            val path = req(input, "path")
            val n = Workspace.write(Workspace.root(app), path, input["content"].asText(), input["append"].asBool() ?: false)
            ToolOut("Wrote $n bytes to $path")
        }
        add("workspace_mkdir") { input -> val dir = req(input, "dir"); Workspace.mkdir(Workspace.root(app), dir); ToolOut("Created $dir") }
        add("workspace_delete") { input ->
            val path = req(input, "path")
            if (!Workspace.delete(Workspace.root(app), path)) throw NodeException("Nothing deleted at $path (missing, or a non-empty folder)")
            ToolOut("Deleted $path")
        }

        // ---- Panels (READ: navigation only; panels are never agent-driven, DESIGN5 §8.3)
        add("show_panel") { input ->
            val name = req(input, "name")
            val p = PanelPrefs.byName(app, name)
            if (p == null) {
                val titles = PanelPrefs.read(app).map { it.title }
                ToolOut(if (titles.isEmpty()) "No panels configured — the user adds them under Dashboard > Panels" else "No panel named '$name'. Panels: ${titles.joinToString(", ")}", isError = true)
            } else if (runCatching { com.mob8n.Mob8NApp.of(app).visibleActivities > 0 }.getOrDefault(false)) {
                openPanel(PanelPrefs.slug(p.title)); ToolOut("Opened panel ${p.title}")
            } else ToolOut("Mahout is not in the foreground — open Dashboard > Panels > ${p.title}")
        }

        // ---- node tools
        val nodeTools = nodeSpecs(catalog, s, t.supportsVision).mapValues { (_, spec) ->
            AgentTool.node(spec, exec = { sp, p -> log("node ${sp.id}"); engine.runNode(sp.id, p, EMPTY, conv.id) },
                attach = { sp, items -> if (sp.id == AgentNode.SCREENSHOT_ID) items.firstOrNull()?.str("uri")?.let { Images.base64(app, it) } else null })
        }
        val workflowTools = WorkflowTools.tools(WorkflowTools.all(engine.workflows().first()), { id, items -> engine.runCalled(id, items) }, log)
        val mcp = McpTools.forServers(app, s.mcpServers, t.supportsVision, log)
        val knowledge = if (knowledgeIds.isEmpty()) emptyMap() else mapOf(AgentTool.KNOWLEDGE_SEARCH to AgentTool.knowledge { q, k -> engine.knowledge.search(q, k, knowledgeIds) })
        val skills = Skills.tools(engine, s) { System.currentTimeMillis() }
        return AgentTool.merge(operator, skills, nodeTools, workflowTools, mcp, knowledge, log = log)
    }

    // ---------------------------------------------------------------- approval card text (pure)

    private fun pretty(e: JsonElement?, max: Int = 400): String = (e?.let { JSON.encodeToString(JsonElement.serializer(), it) } ?: "").let { if (it.length > max) it.take(max) + "…" else it }

    /** Human preview per pending tool_use: draft summary, skill markdown, command, code + allow-list, path + first lines; else name + input. */
    fun previewFor(tu: ToolUse): String {
        val i = tu.input
        return when (tu.name) {
            "save_workflow" -> {
                val d = ChatDrafts.map[i["draftId"].asTextOrNull()] ?: return "save_workflow: draft expired — ask for a new draft_workflow"
                buildString {
                    append(if (d.targetWorkflowId != null) "Replace workflow [${d.targetWorkflowId.take(8)}] with \"${d.name}\"" else "Save new workflow \"${d.name}\"")
                    if (i["enabled"].asBool() == true) append(" and enable it")
                    append("\nNodes (${d.graph.nodes.size}):\n").append(d.graph.nodes.joinToString("\n") { "- ${it.name} (${it.type})" })
                    append("\nEdges: ${d.graph.edges.size}")
                    val issues = (d.issues + Builder.unattendedAgents(d.graph)).distinct()   // P16: "Runs unattended:" lines are always printed
                    if (issues.isNotEmpty()) append("\nIssues:\n").append(issues.joinToString("\n") { "- $it" })
                }
            }
            "skill_create" -> "# ${i["name"].asText()}\n\n${i["description"].asText()}\n\n${i["instructions"].asText()}"
            "skill_update" -> "Update skill ${i["name"].asText()}:\n" + i.filterKeys { it != "name" }.filterValues { it !is kotlinx.serialization.json.JsonNull }
                .entries.joinToString("\n") { (k, v) -> "$k → ${if (v is JsonPrimitive && v.isString) v.content else pretty(v)}" }.ifBlank { "(no changes)" }
            "skill_delete" -> "Delete skill ${i["name"].asText()}"
            "run_shell" -> i["command"].asText() + (i["cwd"].asTextOrNull()?.let { "\n(cwd: $it)" } ?: "") + (i["stdin"].asTextOrNull()?.let { "\n(stdin: ${it.length} chars)" } ?: "")
            "run_js" -> {
                val allow = ((i["allowNodes"] as? JsonArray)?.mapNotNull { it.asTextOrNull() } ?: emptyList()) + (if (i["allowNetwork"].asBool() == true) listOf("data.http") else emptyList())
                i["code"].asText() + "\nmay call: " + (allow.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ") { if (Permissions.isAlwaysNode(it)) "$it (refused in auto mode)" else it } ?: "(no nodes, no network)")
            }
            "workspace_write" -> "${if (i["append"].asBool() == true) "Append to" else "Write"} ${i["path"].asText()}:\n" + i["content"].asText().lines().take(20).joinToString("\n")
            "workspace_delete" -> "Delete workspace file ${i["path"].asText()}"
            "delete_workflow" -> "Delete workflow ${i["id"].asText()} permanently"
            "disable_workflow" -> "Disable workflow ${i["id"].asText()}"
            "resume_run" -> "Resume run ${i["runId"].asText()} with '${i["decision"].asText()}'"
            "memory_update" -> "Replace operator memory with:\n" + i["text"].asText().take(400)
            else -> tu.name + " " + pretty(i)
        }
    }
}
