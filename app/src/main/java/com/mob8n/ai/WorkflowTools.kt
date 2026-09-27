package com.mob8n.ai

import com.mob8n.core.EMPTY
import com.mob8n.core.Item
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.RunStatus
import com.mob8n.core.TRIGGER_CALLED
import com.mob8n.core.Workflow
import com.mob8n.core.asBool
import com.mob8n.core.asDouble
import com.mob8n.core.asText
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import com.mob8n.engine.Engine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

/**
 * Workflows as tools (DESIGN4 §8): three params on `trigger.called` (`exposeAsTool`, `toolDescription`, `inputs` rows) make a workflow a strict tool
 * `workflow__<slug>` for the chat operator (Engine.runCalled) and `ai.agent` includeWorkflows (ctx.runWorkflow). Pure except [tool]'s run lambda.
 */
object WorkflowTools {
    const val PREFIX = "workflow__"; const val NAME_MAX = 64; const val RESULT_CAP = 8 * 1024; const val MAX_TOOLS = 40; const val DESC_MAX = 300
    private const val SLUG_KEEP = 45
    private val NON_SLUG = Regex("[^a-z0-9]+")
    private val TYPES = setOf("string", "number", "boolean", "json")

    data class Exposed(val workflowId: String, val workflowName: String, val description: String, val inputs: List<JsonObject>, val calledNodeId: String)

    /** lowercase, [^a-z0-9]+ -> "_", trim "_", blank -> "workflow"; PREFIX + slug ≤ 64 else PREFIX + slug.take(45) + "_" + fnv1a32(workflowId).hex.take(8). */
    fun toolName(workflowName: String, workflowId: String): String {
        val slug = workflowName.lowercase().replace(NON_SLUG, "_").trim('_').ifBlank { "workflow" }
        val full = PREFIX + slug
        return if (full.length <= NAME_MAX) full else PREFIX + slug.take(SLUG_KEEP).trimEnd('_') + "_" + McpClient.fnv1a32hex(workflowId).take(8)
    }

    /** null unless a non-disabled trigger.called node has exposeAsTool = true. Workflow `enabled` is irrelevant (like logic.run_workflow). */
    fun exposed(wf: Workflow): Exposed? {
        val n = wf.graph.nodes.firstOrNull { it.type == TRIGGER_CALLED && !it.disabled && it.params["exposeAsTool"].asBool() == true } ?: return null
        val rows = (n.params["inputs"] as? JsonArray)?.filterIsInstance<JsonObject>()?.filter { !it["name"].asTextOrNull().isNullOrBlank() } ?: emptyList()
        return Exposed(wf.id, wf.name, n.params["toolDescription"].asTextOrNull()?.trim()?.take(DESC_MAX).orEmpty(), rows, n.id)
    }

    /** Sorted by workflow name, ≤ MAX_TOOLS; equal tool names are resolved by AgentTool.merge ("_2") in [tools]. */
    fun all(workflows: List<Workflow>, exclude: Set<String> = emptySet()): List<Exposed> =
        workflows.filter { it.id !in exclude }.mapNotNull(::exposed).sortedBy { it.workflowName.lowercase() }.take(MAX_TOOLS)

    private fun typeOf(row: JsonObject): String = row["type"].asTextOrNull()?.takeIf { it in TYPES } ?: "string"
    private fun required(row: JsonObject): Boolean = row["required"].asBool() ?: true
    private fun schemaType(t: String) = if (t == "json") "string" else t

    /** Strict def: typed rows (nullable when not required, json rows are strings carrying JSON text); no rows -> one nullable free-form `item`. */
    fun toolDef(e: Exposed): JsonObject {
        val props: Map<String, JsonObject> = if (e.inputs.isEmpty()) {
            mapOf("item" to OperatorTools.opt("string", "JSON object or plain text (becomes {text})"))
        } else e.inputs.associate { r ->
            val t = typeOf(r)
            val d = (r["description"].asTextOrNull().orEmpty().ifBlank { r["name"].asText() }) + (if (t == "json") " (JSON text)" else "")
            r["name"].asText() to if (required(r)) OperatorTools.prop(schemaType(t), d) else OperatorTools.opt(schemaType(t), d)
        }
        return OperatorTools.def(toolName(e.workflowName, e.workflowId), "[Workflow] ${e.workflowName}: ${e.description.ifBlank { "Runs the workflow and returns its leaf items" }}".take(1024), props)
    }

    private fun coerce(t: String, v: JsonElement): JsonElement = when (t) {
        "number" -> v.asDouble()?.let { d -> if (d == floor(d) && abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: v
        "boolean" -> v.asBool()?.let { JsonPrimitive(it) } ?: v
        "json" -> if (v is JsonPrimitive && v.isString) runCatching { JSON.parseToJsonElement(v.content) }.getOrNull() ?: v else v
        else -> if (v is JsonPrimitive) JsonPrimitive(v.content) else v
    }

    /** Tool input -> ONE item: typed fields coerced, extras and nulls dropped; free form -> parsed JSON object or {text}. */
    fun toItem(e: Exposed, input: JsonObject): Item {
        if (e.inputs.isEmpty()) {
            val raw = input["item"].asTextOrNull()?.trim()?.ifBlank { null } ?: return EMPTY
            return runCatching { JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: item("text" to raw)
        }
        return buildJsonObject {
            for (r in e.inputs) {
                val name = r["name"].asText()
                val v = input[name] ?: continue
                if (v is JsonNull) continue
                put(name, coerce(typeOf(r), v))
            }
        }
    }

    /** JSON {runId,status,items,error} ≤ cap; SUSPENDED is a result, not an error: the user decides on the Runs screen. */
    fun summarize(runId: String?, status: RunStatus?, leaves: Items, error: String?, cap: Int = RESULT_CAP): String {
        val o = buildJsonObject {
            put("runId", runId); put("status", status?.name); put("items", JsonArray(leaves))
            put("error", if (status == RunStatus.SUSPENDED) "waiting for the user's approval (Runs screen)" else error)
        }
        val s = JSON.encodeToString(JsonObject.serializer(), o)
        return if (s.length > cap) s.take(cap) + "…(truncated)" else s
    }

    /** "workflow__x — description" per line, whole lines ≤ cap, then "+N more". */
    fun promptLines(list: List<Exposed>, cap: Int = 2_048): String {
        val sb = StringBuilder(); var shown = 0
        for (e in list) {
            val line = toolName(e.workflowName, e.workflowId) + " — " + e.description.ifBlank { "runs ${e.workflowName}" }.replace('\n', ' ') + "\n"
            if (sb.length + line.length > cap - 16) break
            sb.append(line); shown++
        }
        if (shown < list.size) sb.append("+${list.size - shown} more")
        return sb.toString().trimEnd()
    }

    fun tool(e: Exposed, run: suspend (workflowId: String, items: Items) -> Engine.CalledResult): AgentTool =
        AgentTool(toolName(e.workflowName, e.workflowId), toolDef(e), needsApproval = true, kind = "workflow", rejectTemplates = true) { input ->
            val r = run(e.workflowId, listOf(toItem(e, input)))
            ToolOut(summarize(r.runId, r.status, r.leafItems, r.error), isError = r.status == RunStatus.FAILED || r.runId == null && r.status != RunStatus.SUCCESS)
        }

    /** Tools keyed by name; duplicate names (two workflows with the same name) become "_2" through AgentTool.merge. */
    fun tools(list: List<Exposed>, run: suspend (workflowId: String, items: Items) -> Engine.CalledResult, log: (String) -> Unit = {}): Map<String, AgentTool> =
        AgentTool.merge(*list.map { e -> tool(e, run).let { mapOf(it.name to it) } }.toTypedArray(), log = log)
}
