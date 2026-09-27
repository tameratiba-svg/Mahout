package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.NodeException
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolSchemaTest {
    private val def = Fakes.http.toolDef()
    private val schema = def["input_schema"] as JsonObject
    private val props = schema["properties"] as JsonObject
    private fun required() = (schema["required"] as JsonArray).map { it.asTextOrNull() }

    @Test fun strictEveryNonSecretKeyRequiredSecretExcluded() {
        assertEquals("data_http", def["name"].asTextOrNull())
        assertEquals("HTTP Request: Calls a URL and returns the response", def["description"].asTextOrNull())
        assertEquals(JsonPrimitive(true), def["strict"])
        assertEquals("object", schema["type"].asTextOrNull())
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(listOf("url", "method", "body", "headers", "timeout", "tags", "json", "delay"), required())
        assertEquals(required().toSet(), props.keys)
        assertFalse("authSecret" in props)
    }

    @Test fun requiredParamKeepsPlainSchemaOptionalBecomesAnyOfNull() {
        val url = props["url"] as JsonObject
        assertEquals("string", url["type"].asTextOrNull())
        assertNull(url["anyOf"])
        val method = props["method"] as JsonObject
        val anyOf = method["anyOf"] as JsonArray
        assertEquals(2, anyOf.size)
        assertEquals("string", (anyOf[0] as JsonObject)["type"].asTextOrNull())
        assertEquals(listOf("GET", "POST"), ((anyOf[0] as JsonObject)["enum"] as JsonArray).map { it.asTextOrNull() })
        assertEquals("null", (anyOf[1] as JsonObject)["type"].asTextOrNull())
        assertTrue(method["description"].asTextOrNull()!!.contains("(null = default: GET)"))
    }

    @Test fun kindsMapToJsonTypes() {
        fun typeOf(k: String) = ((props[k] as JsonObject)["anyOf"] as JsonArray)[0].let { (it as JsonObject)["type"].asTextOrNull() }
        assertEquals("number", typeOf("timeout"))
        assertEquals("integer", typeOf("delay"))
        assertEquals("boolean", typeOf("json"))
        assertEquals("array", typeOf("tags"))
        assertEquals("string", ((((props["tags"] as JsonObject)["anyOf"] as JsonArray)[0] as JsonObject)["items"] as JsonObject)["type"].asTextOrNull())
    }

    @Test fun rowsAreRecursiveStrictObjects() {
        val headers = ((props["headers"] as JsonObject)["anyOf"] as JsonArray)[0] as JsonObject
        assertEquals("array", headers["type"].asTextOrNull())
        val items = headers["items"] as JsonObject
        assertEquals("object", items["type"].asTextOrNull())
        assertEquals(JsonPrimitive(false), items["additionalProperties"])
        assertEquals(listOf("name", "value"), (items["required"] as JsonArray).map { it.asTextOrNull() })
        val cols = items["properties"] as JsonObject
        assertNull((cols["name"] as JsonObject)["anyOf"])
        assertTrue((cols["value"] as JsonObject).containsKey("anyOf"))
    }

    @Test fun paramsFromToolInputDropsNullsAndUnknownKeysThenValidates() {
        val cleaned = Fakes.http.paramsFromToolInput(buildJsonObject {
            put("url", "https://x"); put("method", JsonNull); put("bogus", 1); put("authSecret", "leak"); put("timeout", 5)
        })
        assertEquals(setOf("url", "timeout"), cleaned.keys)
        try {
            Fakes.http.paramsFromToolInput(buildJsonObject { put("url", "https://x"); put("method", "PATCH") })
            fail("expected validation error")
        } catch (e: NodeException) { assertTrue(e.message!!.contains("Method")) }
    }

    @Test fun ifNodeEnumAndPortsDoNotLeakIntoSchema() {
        val d = Fakes.iff.toolDef()
        val p = (d["input_schema"] as JsonObject)["properties"] as JsonObject
        assertEquals(setOf("left", "op", "right"), p.keys)
        assertEquals("logic_if", d["name"].asTextOrNull())
    }

    @Test fun finishToolIsStrictAndAgentToolFilterUsesNodeIds() {
        val fin = AgentNode.finishDef()
        assertEquals(JsonPrimitive(true), fin["strict"])
        val s = fin["input_schema"] as JsonObject
        assertEquals(listOf("result"), (s["required"] as JsonArray).map { it.asTextOrNull() })
        assertEquals(JsonPrimitive(false), s["additionalProperties"])
        val catalog = Catalog(listOf(listOf(Fakes.FakeNode(Fakes.http), Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(Fakes.iff))))
        assertEquals(setOf("data_http", "action_notify"), AgentNode.toolSpecs(catalog, emptyList()).keys)   // iff is not agentTool
        assertEquals(setOf("action_notify"), AgentNode.toolSpecs(catalog, listOf("action.notify")).keys)
    }

    /** DESIGN3 §4.4: a strict:false (MCP) def keeps its own `required` and gets no additionalProperties injection; strict node defs are byte-identical to v2. */
    @Test fun strictFalseDefsPassThroughClaudeToolBuilder() {
        val loose = buildJsonObject {
            put("name", "mcp__srv__t"); put("description", "[MCP srv] t"); put("strict", false)
            put("input_schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("a", buildJsonObject { put("type", "string") }); put("b", buildJsonObject { put("type", "integer") }) })
                put("required", JsonArray(listOf(JsonPrimitive("a"))))
                put("\$defs", buildJsonObject { put("X", buildJsonObject { put("type", "string") }) })
            })
        }
        val lt = ClaudeClient.tool(loose)
        assertEquals(false, lt.strict().get())
        assertEquals(listOf("a"), lt.inputSchema().required().get())
        assertFalse(lt.inputSchema()._additionalProperties().containsKey("additionalProperties"))
        assertTrue(lt.inputSchema()._additionalProperties().containsKey("\$defs"))
        val noReq = ClaudeClient.tool(buildJsonObject { put("name", "n"); put("description", "d"); put("strict", false); put("input_schema", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }) })
        assertFalse(noReq.inputSchema().required().isPresent)
        val st = ClaudeClient.tool(Fakes.http.toolDef())
        assertEquals(true, st.strict().get())
        assertEquals(listOf("url", "method", "body", "headers", "timeout", "tags", "json", "delay"), st.inputSchema().required().get())
        assertEquals("false", st.inputSchema()._additionalProperties()["additionalProperties"].toString())
    }

    @Test fun aiNodesRegisterAllFourWithDesignIds() {
        assertEquals(listOf("ai.ask", "ai.classify", "ai.extract", "ai.agent", "ai.mcp_tool", "ai.mcp_resource", "ai.decide"), AiNodes.all.map { it.spec.id })
        assertEquals(listOf("other"), ClassifyNode.spec.outputs)
        assertEquals(listOf("work", "personal", "other"), ClassifyNode.spec.outputPorts(buildJsonObject { put("labels", JsonArray(listOf(JsonPrimitive("work"), JsonPrimitive("personal")))) }))
        assertEquals(listOf("main", "denied"), AgentNode.spec.outputs)
        assertEquals(180_000L, AgentNode.spec.timeoutMs)
        assertTrue(AiNodes.all.filter { it.spec.kind == com.mob8n.core.NodeKind.AI }.all { it.spec.timeoutMs >= 120_000L })
    }
}
