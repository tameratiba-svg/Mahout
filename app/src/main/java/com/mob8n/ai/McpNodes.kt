package com.mob8n.ai

import com.mob8n.core.ExecutionContext
import com.mob8n.core.JSON
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.asTextOrNull
import com.mob8n.core.bool
import com.mob8n.core.durationMs
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// MCP as plain nodes (DESIGN3 §4.5). Both resolve the server by name at run time (NodeSpec is compile-time; the ui shows suggestions).

private fun serverParam() = text("server", "MCP server", required = true, templated = false, help = "Name from Settings > AI > MCP servers")

private fun serverOf(ctx: ExecutionContext): McpServer {
    val name = ctx.str("server").trim()
    val s = McpPrefs.byName(ctx.requireAndroid(), name) ?: throw NodeException("MCP server '$name' is not configured (Settings > AI > MCP servers)")
    if (!s.enabled) throw NodeException("MCP server '$name' is disabled")
    return s
}

/** Raw content blocks with image data replaced by "<base64 omitted>" (items must stay small; the run log stores them). */
internal fun withoutBinary(content: JsonArray): JsonArray = JsonArray(content.map { b ->
    val o = b as? JsonObject ?: return@map b
    if (o["type"].asTextOrNull() == "image" && o.containsKey("data")) JsonObject(o + ("data" to JsonPrimitive("<base64 omitted>"))) else o
})

object McpToolNode : Node() {
    override val spec = NodeSpec(
        id = "ai.mcp_tool", name = "MCP Tool", kind = NodeKind.DATA,
        description = "Calls one tool on a remote MCP server (Streamable HTTP). Runs without approval — put logic.wait_approval in front for destructive tools",
        params = listOf(
            serverParam(),
            text("tool", "Tool name", required = true, templated = false, help = "As listed by the server"),
            multiline("arguments", "Arguments (JSON)", "{}", help = "JSON object; {{templates}} allowed"),
            durationMs("timeoutMs", "Timeout", 60_000, 5_000, 120_000),
            bool("failOnToolError", "Fail on tool error", true, help = "isError results go to the error port"),
        ),
        timeoutMs = 130_000,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val s = serverOf(ctx)
        val tool = ctx.req("tool").trim()
        val args = runCatching { JSON.parseToJsonElement(ctx.strOrNull("arguments") ?: "{}") }.getOrNull() as? JsonObject ?: throw NodeException("Arguments must be a JSON object")
        val secret = McpPrefs.secret(ctx.requireAndroid(), s)
        val r = McpClient.callTool(s, secret, tool, args, (ctx.long("timeoutMs") ?: McpClient.CALL_MS).coerceIn(5_000, McpClient.CALL_MAX_MS), ctx::log)
        val text = McpClient.render(r, vision = false).text
        if (r.isError && ctx.bool("failOnToolError")) throw NodeException("$tool: ${text.ifBlank { "tool error" }}".take(500))
        return out(ctx.item.add("server" to s.name, "tool" to tool, "text" to text, "content" to withoutBinary(r.content), "structured" to (r.structured ?: JsonNull), "isError" to r.isError))
    }
}

object McpResourceNode : Node() {
    override val spec = NodeSpec(
        id = "ai.mcp_resource", name = "MCP Resource", kind = NodeKind.DATA,
        description = "Reads (or lists) resources of a remote MCP server; chain into action.knowledge_add to keep a snapshot as knowledge",
        params = listOf(
            serverParam(),
            text("uri", "Resource URI", "{{uri}}", required = true),
            bool("list", "List resources instead", false),
        ),
        timeoutMs = 60_000, optional = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val s = serverOf(ctx)
        val secret = McpPrefs.secret(ctx.requireAndroid(), s)
        if (ctx.bool("list")) {
            return out(McpClient.listResources(s, secret, ctx::log).map { r ->
                ctx.item.add("server" to s.name, "uri" to r.uri, "name" to r.name, "mimeType" to r.mimeType, "description" to r.description, "size" to r.size)
            })
        }
        val uri = ctx.req("uri").trim()
        val contents = McpClient.readResource(s, secret, uri, ctx.timeoutMs.coerceAtMost(McpClient.CALL_MAX_MS), ctx::log)
        val first = contents.filterIsInstance<JsonObject>().firstOrNull() ?: throw NodeException("Resource not found: $uri")
        val text = contents.filterIsInstance<JsonObject>().mapNotNull { it["text"].asTextOrNull() }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        val base = ctx.item.add("server" to s.name, "uri" to uri, "mimeType" to first["mimeType"].asTextOrNull())
        return out(
            if (text != null) base.add("text" to text.let { if (it.length > 2 * 1024 * 1024) it.take(2 * 1024 * 1024) + "…(truncated)" else it })
            else base.add("blob" to true, "bytes" to (first["blob"].asTextOrNull()?.length ?: 0).toLong() * 3 / 4),
        )
    }
}
