package com.mob8n.ai

import com.mob8n.core.NodeKind
import com.mob8n.core.ParamKind
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNodesSpecTest {
    @Test fun specsMatchDesign3() {
        val t = McpToolNode.spec; val r = McpResourceNode.spec
        assertEquals("ai.mcp_tool", t.id); assertEquals("ai.mcp_resource", r.id)
        assertEquals(NodeKind.DATA, t.kind); assertEquals(NodeKind.DATA, r.kind)
        assertFalse(t.agentTool); assertFalse(r.agentTool)
        assertFalse(t.optional); assertTrue(r.optional)
        assertEquals(130_000L, t.timeoutMs); assertEquals(60_000L, r.timeoutMs)
        assertEquals(listOf("server", "tool", "arguments", "timeoutMs", "failOnToolError"), t.params.map { it.key })
        assertEquals(listOf("server", "uri", "list"), r.params.map { it.key })
        for (s in listOf(t, r)) {
            val server = s.param("server")!!
            assertEquals(ParamKind.TEXT, server.kind); assertTrue(server.required); assertFalse(server.templated)
            assertTrue(s.toolDef()["input_schema"] is JsonObject)                     // derives a tool def like every spec (CatalogTest)
        }
        assertFalse(t.param("tool")!!.templated); assertTrue(t.param("tool")!!.required)
        assertEquals(JsonPrimitive("{}"), t.param("arguments")!!.default); assertTrue(t.param("arguments")!!.templated)
        val timeout = t.param("timeoutMs")!!
        assertEquals(ParamKind.DURATION, timeout.kind); assertEquals(60_000.0, timeout.min!! * 12, 0.0); assertEquals(120_000.0, timeout.max!!, 0.0)
        assertTrue(timeout.max!! < t.timeoutMs)
        assertEquals(JsonPrimitive(true), t.param("failOnToolError")!!.default)
        assertEquals(JsonPrimitive("{{uri}}"), r.param("uri")!!.default); assertTrue(r.param("uri")!!.required)
        assertTrue(t.description.contains("logic.wait_approval"))
    }

    @Test fun aiNodesListSixAndBuilderHintsCoverTheNewDataNodes() {
        assertEquals(listOf("ai.ask", "ai.classify", "ai.extract", "ai.agent", "ai.mcp_tool", "ai.mcp_resource", "ai.decide"), AiNodes.all.map { it.spec.id })
        assertEquals(listOf("text", "content", "structured", "isError", "server", "tool"), Builder.OUTPUT_HINTS["ai.mcp_tool"])
        assertEquals(listOf("text", "mimeType", "uri", "server"), Builder.OUTPUT_HINTS["ai.mcp_resource"])
        assertTrue(Builder.OUTPUT_HINTS.containsKey("data.knowledge_search"))
        assertTrue(Builder.catalogLine(McpToolNode.spec).contains("|fields:text,content"))
        assertEquals(listOf("mcpServers", "knowledge", "includeWorkflows", "skills", "permissionMode"), AgentNode.spec.params.takeLast(5).map { it.key })   // v4 appends includeWorkflows + skills; v4.1 permissionMode (DESIGN4P §2.3)
        assertTrue(AgentNode.spec.params.takeLast(5).filter { it.key != "includeWorkflows" && it.key != "permissionMode" }.all { it.kind == ParamKind.LABELS && !it.templated })
        assertEquals(ParamKind.ENUM, AgentNode.spec.param("permissionMode")!!.kind); assertEquals(JsonPrimitive("inherit"), AgentNode.spec.param("permissionMode")!!.default)
        assertEquals(ParamKind.BOOL, AgentNode.spec.param("includeWorkflows")!!.kind)
    }

    @Test fun rawContentDropsImageBytes() {
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "hi") })
            add(buildJsonObject { put("type", "image"); put("data", "AAAA"); put("mimeType", "image/png") })
        }
        val out = withoutBinary(content)
        assertEquals("hi", (out[0] as JsonObject)["text"].asTextOrNull())
        assertEquals("<base64 omitted>", (out[1] as JsonObject)["data"].asTextOrNull()); assertEquals("image/png", (out[1] as JsonObject)["mimeType"].asTextOrNull())
        assertNull((out[0] as JsonObject)["data"])
        assertTrue(withoutBinary(JsonArray(emptyList())).isEmpty())
    }
}
