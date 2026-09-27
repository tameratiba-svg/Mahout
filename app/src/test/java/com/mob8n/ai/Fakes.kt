package com.mob8n.ai

import com.mob8n.core.EMPTY
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.labels
import com.mob8n.core.multiline
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.rows
import com.mob8n.core.secret
import com.mob8n.core.text
import com.mob8n.engine.knowledge.Hit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared fake specs (shaped like other lanes' http / notify / if nodes, which this lane may not import). */
object Fakes {
    val http = NodeSpec(
        "data.http", "HTTP Request", NodeKind.DATA, "Calls a URL and returns the response",
        params = listOf(
            text("url", "URL", required = true),
            choice("method", "Method", listOf("GET", "POST")),
            multiline("body", "Body"),
            rows("headers", "Headers", listOf(text("name", "Name", required = true), text("value", "Value"))),
            secret("authSecret", "Auth secret"),
            number("timeout", "Timeout", 30.0, 1.0, 120.0),
            labels("tags", "Tags"),
            bool("json", "Parse JSON", true),
            durationMs("delay", "Delay", 0),
        ),
        agentTool = true,
    )
    val notify = NodeSpec(
        "action.notify", "Notify", NodeKind.ACTION, "Shows a notification",
        params = listOf(text("title", "Title", required = true), text("text", "Text")), agentTool = true,
    )
    /** Genuinely read-only DATA tool (no network, no state): must never trip the approval gate. */
    val datetime = NodeSpec(
        "data.datetime", "Date/Time", NodeKind.DATA, "Adds the current date and time",
        params = listOf(text("format", "Format")), agentTool = true,
    )
    /** DATA-kind but writes cross-workflow state: gated like ACTION. */
    val variable = NodeSpec(
        "data.variable", "Variable", NodeKind.DATA, "Reads or writes a global variable",
        params = listOf(choice("op", "Operation", listOf("get", "set")), text("name", "Name", required = true), text("value", "Value")), agentTool = true,
    )
    val iff = NodeSpec(
        "logic.if", "If", NodeKind.LOGIC, "Routes by condition",
        params = listOf(text("left", "Left", required = true), choice("op", "Operator", listOf("equals", "contains")), text("right", "Right")),
        outputs = listOf("true", "false"),
    )

    class FakeNode(override val spec: NodeSpec) : Node() {
        override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = out(EMPTY)
    }

    // ---- v3: MCP / knowledge tool fakes (no network; the AgentTool seam is what the loop sees) ----

    /** A loose MCP-shaped def: strict:false, schema passed through. */
    fun mcpDef(name: String): JsonObject = buildJsonObject {
        put("name", name); put("description", "[MCP fake] $name"); put("strict", false)
        put("input_schema", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("title", buildJsonObject { put("type", "string") }) }) })
    }

    /** kind "mcp", gated unless `trusted`; `onCall` sees the raw input (templates allowed). */
    fun mcpTool(name: String, trusted: Boolean, out: ToolOut, onCall: (JsonObject) -> Unit = {}): AgentTool =
        AgentTool(name, mcpDef(name), needsApproval = !trusted, kind = "mcp", rejectTemplates = false) { input -> onCall(input); out }

    /** knowledge_search over a fixed hit list; records (query, k). */
    fun knowledgeTool(hits: List<Hit>, seen: MutableList<Pair<String, Int>> = ArrayList()): AgentTool = AgentTool.knowledge { q, k -> seen += q to k; hits }

    // ---- v4: chat operator fakes ----

    /** Operator-shaped tool (kind "operator" unless given): the real strict def when the name is an operator name, else a strict one-field def; records calls. */
    fun opTool(name: String, kind: String = "operator", needsApproval: Boolean = false, out: ToolOut = ToolOut("ok"), onCall: (JsonObject) -> Unit = {}): AgentTool {
        val def = OperatorTools.defs().firstOrNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == name }
            ?: OperatorTools.def(name, "[fake] $name", mapOf("title" to OperatorTools.prop("string", "Title")))
        return AgentTool(name, def, needsApproval, kind, rejectTemplates = false) { input -> onCall(input); out }
    }

    /** A workflow whose trigger.called node exposes it as a tool (DESIGN4 §8.1 params). */
    fun exposedWorkflow(id: String, name: String, description: String = "", inputs: List<JsonObject> = emptyList(), enabled: Boolean = true, expose: Boolean = true, triggerDisabled: Boolean = false): com.mob8n.core.Workflow =
        com.mob8n.core.Workflow(id, name, enabled, com.mob8n.core.Graph(listOf(com.mob8n.core.NodeInstance("t-$id", com.mob8n.core.TRIGGER_CALLED, "Called", buildJsonObject {
            put("exposeAsTool", expose); put("toolDescription", description); put("inputs", kotlinx.serialization.json.JsonArray(inputs))
        }, disabled = triggerDisabled))))

    fun inputRow(name: String, type: String = "string", description: String = "", required: Boolean = true): JsonObject =
        buildJsonObject { put("name", name); put("type", type); put("description", description); put("required", required) }

    fun skill(name: String, description: String = "Does $name", instructions: String = "# $name\n\n1. Do it.", enabled: Boolean = true, usage: Int = 0, tools: List<String> = emptyList(), tags: List<String> = emptyList()) =
        com.mob8n.engine.Skill("id-$name", name, description, instructions, tools, tags, "user", enabled, 1L, 1L, usage)

    fun textBlock(s: String): JsonObject = buildJsonObject { put("type", "text"); put("text", s) }
    fun toolUse(id: String, name: String, input: JsonObject): JsonObject = buildJsonObject { put("type", "tool_use"); put("id", id); put("name", name); put("input", input) }
    fun turn(stop: String, vararg blocks: JsonObject) = Turn(stop, JsonArray(blocks.toList()))
    fun blocks(m: JsonObject): List<JsonObject> = (m["content"] as JsonArray).filterIsInstance<JsonObject>()
    fun str(e: JsonElement?): String? = (e as? kotlinx.serialization.json.JsonPrimitive)?.content
}
