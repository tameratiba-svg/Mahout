package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every operator def is strict and well-formed; names never collide with catalog tools, finish, knowledge_search or the mcp__/workflow__ prefixes. */
class OperatorToolsSchemaTest {
    private val catalog = Catalog(listOf(
        com.mob8n.triggers.TriggerNodes.all, com.mob8n.data.DataNodes.all, com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all, com.mob8n.ai.AiNodes.all, com.mob8n.apps.AppNodes.all,
    ))
    private val nameRe = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test fun everyDefIsStrictWithAllKeysRequiredAndNullableOptionals() {
        val defs = OperatorTools.defs()
        assertEquals(OperatorTools.NAMES.toSet(), defs.map { it["name"].asTextOrNull() }.toSet())
        assertEquals(defs.size, defs.map { it["name"] }.toSet().size)
        for (d in defs) {
            val name = d["name"].asTextOrNull()!!
            assertTrue(name, nameRe.matches(name))
            assertEquals(name, JsonPrimitive(true), d["strict"])
            assertTrue(name, d["description"].asTextOrNull()!!.isNotBlank())
            val schema = d["input_schema"] as JsonObject
            assertEquals(name, JsonPrimitive("object"), schema["type"]); assertEquals(name, JsonPrimitive(false), schema["additionalProperties"])
            val props = schema["properties"] as JsonObject
            assertEquals(name, props.keys.toList(), (schema["required"] as JsonArray).map { it.asTextOrNull() })
            for ((k, v) in props) {
                val p = v as JsonObject
                assertTrue("$name.$k needs a description", p["description"].asTextOrNull()!!.isNotBlank())
                val any = p["anyOf"] as? JsonArray
                if (any != null) assertTrue("$name.$k optional must allow null", any.any { (it as JsonObject)["type"].asTextOrNull() == "null" })
                else assertTrue("$name.$k needs a type", p["type"].asTextOrNull() != null)
            }
        }
        // spot-check shapes from the §5.3 table
        val byName = defs.associateBy { it["name"].asTextOrNull()!! }
        fun props(n: String) = ((byName[n]!!["input_schema"] as JsonObject)["properties"] as JsonObject)
        assertTrue(props("list_workflows").isEmpty())
        assertEquals(setOf("id", "items"), props("run_workflow").keys); assertTrue(props("run_workflow")["items"]!!.toString().contains("null"))
        assertEquals(setOf("command", "stdin", "timeoutMs", "cwd"), props("run_shell").keys)
        assertEquals(setOf("code", "input", "allowNodes", "allowNetwork", "timeoutMs"), props("run_js").keys)
        assertEquals(setOf("name", "description", "instructions", "allowedTools", "tags"), props("skill_create").keys)
        assertEquals(setOf("runId", "decision"), props("resume_run").keys)
    }

    @Test fun namesNeverCollideWithReservedOrCatalogNames() {
        val names = OperatorTools.NAMES.toSet()
        val catalogNames = catalog.nodes.map { it.spec.toolName }.toSet()
        assertTrue((names intersect catalogNames).isEmpty())
        assertFalse(AgentNode.TOOL_FINISH in names); assertFalse(AgentTool.KNOWLEDGE_SEARCH in names)
        assertTrue(names.none { it.startsWith(McpClient.sanitize("x", "y").substringBefore("__") + "__") || it.startsWith(WorkflowTools.PREFIX) })
        // node tool names use '_' where ids use '.', so "app_shell_run" (a node) and "run_shell" (operator) can never meet; the merge keeps operator names first
        val merged = AgentTool.merge(OperatorTools.NAMES.associateWith { Fakes.opTool(it) }, mapOf("list_workflows" to Fakes.mcpTool("list_workflows", true, ToolOut("x"))))
        assertEquals("operator", merged["list_workflows"]!!.kind); assertEquals("mcp", merged["list_workflows_2"]!!.kind)
    }

    @Test fun toolDefToOpenAiRoundTripsEveryDef() {
        for (d in OperatorTools.defs()) {
            val o = OpenAiCompat.toolDefToOpenAi(d, strict = true)
            assertEquals(JsonPrimitive("function"), o["type"])
            val fn = o["function"] as JsonObject
            assertEquals(d["name"], fn["name"]); assertEquals(d["description"], fn["description"]); assertEquals(d["input_schema"], fn["parameters"]); assertEquals(JsonPrimitive(true), fn["strict"])
        }
        // the Claude SDK builder accepts every def (strict: required = all keys, additionalProperties false)
        for (d in OperatorTools.defs()) ClaudeClient.tool(d)
    }

    @Test fun chatNodeToolsExcludeShellRunAndUiToolsUnlessEnabled() {
        val shell = NodeSpec("app.shell_run", "Shell", NodeKind.ACTION, "Runs sh", agentTool = true)
        val ui = NodeSpec("app.ui_tap", "Tap", NodeKind.ACTION, "Taps", agentTool = true)
        val shot = NodeSpec("app.ui_screenshot", "Screenshot", NodeKind.DATA, "Shoots", agentTool = true)
        val c = Catalog(listOf(listOf(Fakes.FakeNode(Fakes.http), Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(shell), Fakes.FakeNode(ui), Fakes.FakeNode(shot))))
        assertEquals(setOf("data_http", "action_notify"), OperatorTools.nodeSpecs(c, ChatSettings(), vision = true).keys)
        assertEquals(setOf("data_http", "action_notify", "app_ui_tap", "app_ui_screenshot"), OperatorTools.nodeSpecs(c, ChatSettings(uiAutomation = true), vision = true).keys)
        assertEquals(setOf("data_http", "action_notify", "app_ui_tap"), OperatorTools.nodeSpecs(c, ChatSettings(uiAutomation = true), vision = false).keys)
        assertEquals(setOf("action_notify"), OperatorTools.nodeSpecs(c, ChatSettings(nodeTools = listOf("action.notify", "app.shell_run")), vision = true).keys)
        // the real catalog: every agentTool node except ui tools is offered by default
        val real = OperatorTools.nodeSpecs(catalog, ChatSettings(), vision = true)
        assertFalse(real.containsKey("app_shell_run")); assertTrue(real.containsKey("data_http")); assertFalse(real.keys.any { it.startsWith("app_ui_") })
    }

    @Test fun previewForRendersTheApprovalCards() {
        assertEquals("ls -la\n(cwd: sub)", OperatorTools.previewFor(ToolUse("1", "run_shell", buildJsonObject { put("command", "ls -la"); put("cwd", "sub") })))
        val js = OperatorTools.previewFor(ToolUse("1", "run_js", buildJsonObject { put("code", "return 1"); put("allowNodes", JsonArray(listOf(JsonPrimitive("action.notify")))); put("allowNetwork", true) }))
        assertTrue(js.startsWith("return 1")); assertTrue(js.endsWith("may call: action.notify, data.http"))
        assertTrue(OperatorTools.previewFor(ToolUse("1", "run_js", buildJsonObject { put("code", "x") })).endsWith("(no nodes, no network)"))
        val w = OperatorTools.previewFor(ToolUse("1", "workspace_write", buildJsonObject { put("path", "a.md"); put("content", (1..30).joinToString("\n") { "line $it" }) }))
        assertTrue(w.startsWith("Write a.md:\nline 1")); assertTrue(w.contains("line 20")); assertFalse(w.contains("line 21"))
        val sk = OperatorTools.previewFor(ToolUse("1", "skill_create", buildJsonObject { put("name", "x"); put("description", "d"); put("instructions", "# Steps\n1. go") }))
        assertEquals("# x\n\nd\n\n# Steps\n1. go", sk)
        assertEquals("save_workflow: draft expired — ask for a new draft_workflow", OperatorTools.previewFor(ToolUse("1", "save_workflow", buildJsonObject { put("draftId", "nope") })))
        ChatDrafts.put(ChatDrafts.Draft("d1", "Battery alert", com.mob8n.core.Graph(listOf(com.mob8n.core.NodeInstance("n1", "trigger.manual", "Manual"))), null, listOf("x"), 1))
        val sv = OperatorTools.previewFor(ToolUse("1", "save_workflow", buildJsonObject { put("draftId", "d1"); put("enabled", true) }))
        assertTrue(sv.startsWith("Save new workflow \"Battery alert\" and enable it\nNodes (1):\n- Manual (trigger.manual)")); assertTrue(sv.contains("Issues:\n- x"))
        assertTrue(OperatorTools.previewFor(ToolUse("1", "mcp__s__t", buildJsonObject { put("a", "b") })).startsWith("mcp__s__t {"))
    }

    @Test fun parseItemsShapes() {
        assertEquals(listOf(com.mob8n.core.EMPTY), OperatorTools.parseItems(null)); assertEquals(listOf(com.mob8n.core.EMPTY), OperatorTools.parseItems("  "))
        assertEquals(2, OperatorTools.parseItems("""[{"a":1},{"a":2}]""").size)
        assertEquals(JsonPrimitive(1), OperatorTools.parseItems("""{"a":1}""").single()["a"])
        assertEquals(JsonPrimitive("hello"), OperatorTools.parseItems("hello").single()["text"])
        assertEquals(JsonPrimitive(3), OperatorTools.parseItems("[3]").single()["value"])
    }

    /** DESIGN5 §8.3: show_panel is a strict READ operator tool. */
    @Test fun showPanelIsStrictAndRead() {
        val d = OperatorTools.defs().single { it["name"].asTextOrNull() == "show_panel" }
        assertEquals(JsonPrimitive(true), d["strict"])
        val schema = d["input_schema"] as JsonObject
        assertEquals(listOf("name"), (schema["required"] as JsonArray).map { it.asTextOrNull() })
        assertEquals(Risk.READ, OperatorTools.riskOf("show_panel", "operator", false, null))
        assertTrue("show_panel" in OperatorTools.NAMES)
    }
}
