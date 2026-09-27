package com.mob8n.ai

import com.mob8n.core.Items
import com.mob8n.core.RunStatus
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import com.mob8n.engine.Engine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4 §8.2 pure surface + the tool over a fake run lambda. */
class WorkflowToolsTest {
    private val nameRe = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test fun toolNamesSlugAndTruncateStably() {
        assertEquals("workflow__morning_briefing", WorkflowTools.toolName("Morning Briefing!", "id"))
        assertEquals("workflow__workflow", WorkflowTools.toolName("!!!", "id"))
        val long = "This is a very long workflow name that goes on and on and on for ever!"
        assertTrue(long.length >= 70)
        val n1 = WorkflowTools.toolName(long, "id-1"); val n2 = WorkflowTools.toolName(long, "id-1"); val n3 = WorkflowTools.toolName(long, "id-2")
        assertEquals(64, n1.length); assertEquals(n1, n2); assertTrue(n1 != n3); assertTrue(nameRe.matches(n1)); assertTrue(n1.startsWith("workflow__this_is_a_very_long"))
        assertEquals("_" + McpClient.fnv1a32hex("id-1").take(8), n1.takeLast(9))
    }

    @Test fun exposedDependsOnTheTriggerParamsNotOnEnabled() {
        assertNotNull(WorkflowTools.exposed(Fakes.exposedWorkflow("a", "A", enabled = false)))
        assertNotNull(WorkflowTools.exposed(Fakes.exposedWorkflow("a", "A", enabled = true)))
        assertNull(WorkflowTools.exposed(Fakes.exposedWorkflow("a", "A", expose = false)))
        assertNull(WorkflowTools.exposed(Fakes.exposedWorkflow("a", "A", triggerDisabled = true)))
        assertNull(WorkflowTools.exposed(com.mob8n.core.Workflow("b", "B", true, com.mob8n.core.Graph(listOf(com.mob8n.core.NodeInstance("m", "trigger.manual", "M"))))))
        val e = WorkflowTools.exposed(Fakes.exposedWorkflow("a", "A", "d".repeat(400), listOf(Fakes.inputRow("url"), Fakes.inputRow("", "string"))))!!
        assertEquals(WorkflowTools.DESC_MAX, e.description.length); assertEquals(1, e.inputs.size); assertEquals("t-a", e.calledNodeId)
    }

    @Test fun allSortsExcludesAndCapsAndMergeRenamesCollisions() {
        val wfs = listOf(Fakes.exposedWorkflow("1", "Zeta"), Fakes.exposedWorkflow("2", "alpha"), Fakes.exposedWorkflow("3", "Alpha"), Fakes.exposedWorkflow("4", "Beta", expose = false))
        val all = WorkflowTools.all(wfs)
        assertEquals(listOf("alpha", "Alpha", "Zeta"), all.map { it.workflowName })
        assertEquals(listOf("Alpha", "Zeta"), WorkflowTools.all(wfs, exclude = setOf("2")).map { it.workflowName })
        val tools = WorkflowTools.tools(all, { _, _ -> Engine.CalledResult("r", RunStatus.SUCCESS, emptyList(), null) })
        assertEquals(listOf("workflow__alpha", "workflow__alpha_2", "workflow__zeta"), tools.keys.toList())
        assertEquals("workflow__alpha_2", tools["workflow__alpha_2"]!!.def["name"].asTextOrNull())
        assertEquals(WorkflowTools.MAX_TOOLS, WorkflowTools.all((1..50).map { Fakes.exposedWorkflow("$it", "W$it") }).size)
    }

    @Test fun toolDefIsStrictWithTypedRowsOrFreeFormItem() {
        val e = WorkflowTools.exposed(Fakes.exposedWorkflow("a", "Fetch Page", "Downloads a page", listOf(
            Fakes.inputRow("url", "string", "Page URL"), Fakes.inputRow("count", "number", required = false), Fakes.inputRow("dry", "boolean"), Fakes.inputRow("opts", "json", "Options", required = false),
        )))!!
        val d = WorkflowTools.toolDef(e)
        assertEquals("workflow__fetch_page", d["name"].asTextOrNull()); assertEquals("[Workflow] Fetch Page: Downloads a page", d["description"].asTextOrNull()); assertEquals(JsonPrimitive(true), d["strict"])
        val schema = d["input_schema"] as JsonObject
        val props = schema["properties"] as JsonObject
        assertEquals(listOf("url", "count", "dry", "opts"), (schema["required"] as JsonArray).map { it.asTextOrNull() }); assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(JsonPrimitive("string"), (props["url"] as JsonObject)["type"]); assertEquals(JsonPrimitive("boolean"), (props["dry"] as JsonObject)["type"])
        val count = (props["count"] as JsonObject)["anyOf"] as JsonArray
        assertEquals(listOf("number", "null"), count.map { (it as JsonObject)["type"].asTextOrNull() })
        val opts = props["opts"] as JsonObject
        assertEquals(listOf("string", "null"), (opts["anyOf"] as JsonArray).map { (it as JsonObject)["type"].asTextOrNull() }); assertTrue(opts["description"].asTextOrNull()!!.endsWith("(JSON text)"))
        val free = WorkflowTools.toolDef(WorkflowTools.exposed(Fakes.exposedWorkflow("b", "Free"))!!)
        val fp = (free["input_schema"] as JsonObject)["properties"] as JsonObject
        assertEquals(setOf("item"), fp.keys); assertTrue(fp["item"].toString().contains("null")); assertTrue(free["description"].asTextOrNull()!!.endsWith("Runs the workflow and returns its leaf items"))
        ClaudeClient.tool(d); OpenAiCompat.toolDefToOpenAi(d, true)
    }

    @Test fun toItemCoercesDropsAndWraps() {
        val e = WorkflowTools.exposed(Fakes.exposedWorkflow("a", "A", inputs = listOf(Fakes.inputRow("url"), Fakes.inputRow("count", "number"), Fakes.inputRow("dry", "boolean"), Fakes.inputRow("opts", "json"), Fakes.inputRow("note", "string", required = false))))!!
        val item = WorkflowTools.toItem(e, buildJsonObject { put("url", "https://a"); put("count", "3"); put("dry", "true"); put("opts", """{"a":1}"""); put("note", JsonNull); put("extra", "x") })
        assertEquals(setOf("url", "count", "dry", "opts"), item.keys)
        assertEquals(JsonPrimitive(3), item["count"]); assertEquals(JsonPrimitive(true), item["dry"]); assertEquals(JsonPrimitive(1), (item["opts"] as JsonObject)["a"]); assertEquals(JsonPrimitive("https://a"), item["url"])
        assertEquals(JsonPrimitive(2.5), WorkflowTools.toItem(e, buildJsonObject { put("count", 2.5) })["count"])
        assertEquals(JsonPrimitive("not json"), WorkflowTools.toItem(e, buildJsonObject { put("opts", "not json") })["opts"])   // unparsable json row stays text
        val free = WorkflowTools.exposed(Fakes.exposedWorkflow("b", "Free"))!!
        assertEquals(JsonPrimitive("hello"), WorkflowTools.toItem(free, buildJsonObject { put("item", "hello") })["text"])
        assertEquals(JsonPrimitive(1), WorkflowTools.toItem(free, buildJsonObject { put("item", """{"a":1}""") })["a"])
        assertEquals(com.mob8n.core.EMPTY, WorkflowTools.toItem(free, buildJsonObject { put("item", JsonNull) }))
    }

    @Test fun summarizeCapsAndWordsSuspended() {
        val s = WorkflowTools.summarize("r1", RunStatus.SUCCESS, listOf(item("a" to 1)), null)
        assertEquals("""{"runId":"r1","status":"SUCCESS","items":[{"a":1}],"error":null}""", s)
        assertTrue(WorkflowTools.summarize("r1", RunStatus.SUSPENDED, emptyList(), null).contains("waiting for the user's approval (Runs screen)"))
        val big = WorkflowTools.summarize("r", RunStatus.SUCCESS, (1..200).map { item("blob" to "x".repeat(100)) }, null)
        assertTrue(big.length <= WorkflowTools.RESULT_CAP + 20); assertTrue(big.endsWith("…(truncated)"))
        assertTrue(WorkflowTools.summarize(null, null, emptyList(), "no trigger").contains("\"error\":\"no trigger\""))
    }

    @Test fun promptLinesAndToolOverRun() = runBlocking {
        val list = WorkflowTools.all(listOf(Fakes.exposedWorkflow("1", "Alpha", "Does alpha"), Fakes.exposedWorkflow("2", "Beta")))
        assertEquals("workflow__alpha — Does alpha\nworkflow__beta — runs Beta", WorkflowTools.promptLines(list))
        assertEquals("workflow__alpha — Does alpha\n+1 more", WorkflowTools.promptLines(list, 50))
        val calls = ArrayList<Pair<String, Items>>()
        val tool = WorkflowTools.tool(list[0]) { id, items -> calls += id to items; Engine.CalledResult("run-9", RunStatus.SUCCESS, listOf(item("ok" to true)), null) }
        assertEquals("workflow__alpha", tool.name); assertTrue(tool.needsApproval); assertEquals("workflow", tool.kind); assertTrue(tool.rejectTemplates)
        val out = tool.call(buildJsonObject { put("item", "hi") })
        assertFalse(out.isError); assertTrue(out.text.contains("\"runId\":\"run-9\"")); assertEquals(listOf("1" to listOf(item("text" to "hi"))), calls)
        assertTrue(WorkflowTools.tool(list[0]) { _, _ -> Engine.CalledResult("r", RunStatus.FAILED, emptyList(), "boom") }.call(buildJsonObject { put("item", JsonNull) }).isError)
        assertTrue(WorkflowTools.tool(list[0]) { _, _ -> Engine.CalledResult(null, null, emptyList(), "no trigger") }.call(buildJsonObject { put("item", JsonNull) }).isError)
        assertFalse(WorkflowTools.tool(list[0]) { _, _ -> Engine.CalledResult("r", RunStatus.SUSPENDED, emptyList(), null) }.call(buildJsonObject { put("item", JsonNull) }).isError)
    }
}
