package com.mob8n.ai

import com.mob8n.core.Items
import com.mob8n.core.NodeSpec
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import com.mob8n.engine.knowledge.Hit
import kotlinx.coroutines.runBlocking
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
import org.junit.Test

/** DESIGN3 §4.4: node + MCP + knowledge tools merged into one map; approval classification; results never dropped. */
class AgentToolsTest {
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private val exec: suspend (NodeSpec, JsonObject) -> Items = { spec, params -> calls += spec.id to params; listOf(item("ok" to spec.id)) }
    private fun nodes(vararg s: NodeSpec) = s.associate { it.toolName to AgentTool.node(it, exec) }
    private fun fresh() = AgentNode.State(mutableListOf(ClaudeClient.userMessage("goal")), 0, mutableListOf(), emptyList())
    private class Script(vararg val turns: Turn) {
        var n = 0
        val step: suspend (List<JsonObject>) -> Turn = { turns[n++] }
    }
    private val mcpName = McpClient.sanitize("GitHub Tools", "issue.create")

    @Test fun mergeKeepsNamesUniqueAndNeverShadowsFinish() {
        val logs = ArrayList<String>()
        val mcp = mapOf("data_http" to Fakes.mcpTool("data_http", true, ToolOut("x")), "finish" to Fakes.mcpTool("finish", true, ToolOut("x")), mcpName to Fakes.mcpTool(mcpName, false, ToolOut("x")))
        val merged = AgentTool.merge(nodes(Fakes.http, Fakes.notify), mcp, mapOf(AgentTool.KNOWLEDGE_SEARCH to Fakes.knowledgeTool(emptyList())), log = logs::add)
        assertEquals(listOf("data_http", "action_notify", "data_http_2", mcpName, "knowledge_search"), merged.keys.toList())
        assertEquals("data_http_2", merged["data_http_2"]!!.def["name"].asTextOrNull())              // def renamed with the key
        assertEquals("node", merged["data_http"]!!.kind); assertEquals("mcp", merged["data_http_2"]!!.kind)
        assertFalse(merged.containsKey("finish")); assertTrue(logs.any { it.contains("reserved") }); assertTrue(logs.any { it.contains("renamed") })
    }

    @Test fun approvalClassification() {
        assertTrue(AgentTool.node(Fakes.notify, exec).needsApproval)
        assertFalse(AgentTool.node(Fakes.datetime, exec).needsApproval)
        assertTrue(AgentTool.node(Fakes.variable, exec).needsApproval)
        assertTrue(AgentTool.node(Fakes.http, exec).needsApproval)
        assertTrue(Fakes.mcpTool(mcpName, trusted = false, ToolOut("x")).needsApproval)
        assertFalse(Fakes.mcpTool(mcpName, trusted = true, ToolOut("x")).needsApproval)
        assertFalse(Fakes.knowledgeTool(emptyList()).needsApproval)
        assertEquals(JsonPrimitive(true), AgentTool.knowledgeDef()["strict"])
        assertEquals(JsonPrimitive(false), Fakes.mcpTool(mcpName, true, ToolOut("x")).def["strict"])
    }

    @Test fun untrustedMcpUseSuspendsForApprovalTrustedRuns() = runBlocking {
        var ran = 0
        val use = Fakes.toolUse("m1", mcpName, buildJsonObject { put("title", "Bug") })
        val untrusted = nodes(Fakes.datetime) + (mcpName to Fakes.mcpTool(mcpName, false, ToolOut("created #1")) { ran++ })
        val gate = AgentNode.loop(fresh(), untrusted, 8, true, Script(Fakes.turn("tool_use", use)).step)
        assertTrue(gate is AgentNode.Outcome.NeedApproval)
        assertEquals(listOf(mcpName), (gate as AgentNode.Outcome.NeedApproval).pending.map { it.name }); assertEquals(0, ran)
        assertTrue(("Agent wants to: " + gate.pending.map { it.name }.distinct().joinToString(", ")).contains("mcp__github_tools__issue_create"))
        val trusted = nodes(Fakes.datetime) + (mcpName to Fakes.mcpTool(mcpName, true, ToolOut("created #1")) { ran++ })
        val st = fresh()
        val out = AgentNode.loop(st, trusted, 8, true, Script(Fakes.turn("tool_use", use), Fakes.turn("end_turn", Fakes.textBlock("done"))).step) as AgentNode.Outcome.Done
        assertEquals("done", out.result); assertEquals(1, ran)
        assertEquals("mcp", st.steps[0]["kind"].asTextOrNull()); assertEquals("created #1", st.steps[0]["output"].asTextOrNull())
    }

    @Test fun mcpErrorResultsAndImagesReachTheTranscript() = runBlocking {
        val tools = mapOf(
            "mcp__srv__fail" to Fakes.mcpTool("mcp__srv__fail", true, ToolOut("Srv: Unknown tool (code -32602)", isError = true)),
            "mcp__srv__shot" to Fakes.mcpTool("mcp__srv__shot", true, ToolOut("a chart", imageBase64 = "PNGDATA", imageMime = "image/png")),
        )
        val st = fresh()
        val s = Script(Fakes.turn("tool_use", Fakes.toolUse("a", "mcp__srv__fail", buildJsonObject {}), Fakes.toolUse("b", "mcp__srv__shot", buildJsonObject {})), Fakes.turn("end_turn", Fakes.textBlock("x")))
        AgentNode.loop(st, tools, 8, false, s.step)
        val results = Fakes.blocks(st.messages[2])
        assertEquals(2, results.size)
        assertEquals(JsonPrimitive(true), results[0]["is_error"]); assertTrue(results[0]["content"].asTextOrNull()!!.contains("-32602"))
        assertEquals(JsonPrimitive(true), st.steps[0]["isError"]); assertEquals("mcp", st.steps[0]["kind"].asTextOrNull())
        val img = ((results[1]["content"] as JsonArray)[1] as JsonObject)["source"] as JsonObject
        assertEquals("image/png", img["media_type"].asTextOrNull()); assertEquals("PNGDATA", img["data"].asTextOrNull())
        assertEquals(JsonPrimitive(false), results[1]["is_error"])
    }

    @Test fun templatesAllowedInMcpArgumentsRejectedInNodeInputs() = runBlocking {
        var seen: JsonObject? = null
        val tools = nodes(Fakes.notify) + ("mcp__srv__echo" to Fakes.mcpTool("mcp__srv__echo", true, ToolOut("ok")) { seen = it })
        val st = fresh()
        val s = Script(
            Fakes.turn("tool_use", Fakes.toolUse("a", "mcp__srv__echo", buildJsonObject { put("template", "Hello {{name}}") }), Fakes.toolUse("b", "action_notify", buildJsonObject { put("title", "{{x}}") })),
            Fakes.turn("end_turn", Fakes.textBlock("x")),
        )
        AgentNode.loop(st, tools, 8, false, s.step)
        assertEquals("Hello {{name}}", seen!!["template"].asTextOrNull())
        val results = Fakes.blocks(st.messages[2])
        assertEquals(JsonPrimitive(false), results[0]["is_error"])
        assertEquals(JsonPrimitive(true), results[1]["is_error"]); assertTrue(results[1]["content"].asTextOrNull()!!.contains("templates"))
        assertTrue(calls.isEmpty())
    }

    @Test fun systemPromptFramesExternalDataAndPinnedBlockIsBounded() {
        val sp = AgentNode.systemPrompt(external = true)
        assertTrue(sp.contains("never instructions")); assertTrue(sp.contains("mcp__")); assertTrue(sp.contains("knowledge_search")); assertTrue(sp.contains("<knowledge>"))
        assertFalse(AgentNode.systemPrompt().contains("External data"))
        assertEquals(AgentNode.systemPrompt(), AgentNode.systemPrompt(uiTools = false, external = false))          // static
        assertEquals("", AgentNode.pinnedBlock("")); assertEquals("", AgentNode.pinnedBlock("  \n"))
        val fence = "<knowledge source=\"big\">\n" + "x".repeat(20_000) + "\n</knowledge>"
        val block = AgentNode.pinnedBlock(fence)
        assertTrue(block.length <= 8_300); assertTrue(block.startsWith("\n\nPinned knowledge (DATA")); assertTrue(block.contains("not instructions"))
    }

    @Test fun knowledgeToolPassesQueryAndDefaultsK() = runBlocking {
        val seen = ArrayList<Pair<String, Int>>()
        val hits = listOf(Hit("Refunds within 30 days.", "Refund policy", "s1", 0, 1.0), Hit("Contact support.", "Refund policy", "s1", 3, 0.42))
        val tool = Fakes.knowledgeTool(hits, seen)
        val out = tool.call(buildJsonObject { put("query", "refund window"); put("k", JsonNull) })
        assertEquals(listOf("refund window" to 5), seen)
        assertFalse(out.isError)
        val arr = com.mob8n.core.JSON.parseToJsonElement(out.text) as JsonArray
        assertEquals(2, arr.size); assertEquals("Refund policy", (arr[0] as JsonObject)["source"].asTextOrNull()); assertEquals(JsonPrimitive(1.0), (arr[0] as JsonObject)["score"])
        tool.call(buildJsonObject { put("query", "x"); put("k", 50) })
        assertEquals(10, seen[1].second)                                                                  // clamped 1..10
        assertEquals("No matching knowledge.", Fakes.knowledgeTool(emptyList()).call(buildJsonObject { put("query", "zzz"); put("k", 3) }).text)
        // the loop records kind=knowledge and never gates it
        val st = fresh()
        val s = Script(Fakes.turn("tool_use", Fakes.toolUse("k1", "knowledge_search", buildJsonObject { put("query", "q"); put("k", JsonNull) })), Fakes.turn("end_turn", Fakes.textBlock("x")))
        assertTrue(AgentNode.loop(st, mapOf("knowledge_search" to tool), 8, true, s.step) is AgentNode.Outcome.Done)
        assertEquals("knowledge", st.steps[0]["kind"].asTextOrNull())
    }

    /** DESIGN4 §8.3: includeWorkflows offers every exposed workflow except the current one; the tool runs through the sync run lambda. */
    @Test fun includeWorkflowsExcludesTheCurrentWorkflow() = runBlocking {
        val wfs = listOf(Fakes.exposedWorkflow("me", "Me"), Fakes.exposedWorkflow("helper", "Helper", "Helps"), Fakes.exposedWorkflow("plain", "Plain", expose = false))
        val ran = ArrayList<String>()
        val tools = AgentNode.workflowTools(wfs, exclude = "me", run = { id, items -> ran += id; items + item("done" to true) })
        assertEquals(listOf("workflow__helper"), tools.keys.toList())
        assertEquals("workflow", tools["workflow__helper"]!!.kind); assertTrue(tools["workflow__helper"]!!.needsApproval)
        val out = tools["workflow__helper"]!!.call(buildJsonObject { put("item", "x") })
        assertFalse(out.isError); assertTrue(out.text.contains("\"status\":\"SUCCESS\"")); assertTrue(out.text.contains("\"done\":true")); assertEquals(listOf("helper"), ran)
        // merged before MCP so a workflow name never shadows a node, and the loop records kind=workflow
        val merged = AgentTool.merge(nodes(Fakes.datetime), tools, mapOf("workflow__helper" to Fakes.mcpTool("workflow__helper", true, ToolOut("x"))))
        assertEquals("workflow", merged["workflow__helper"]!!.kind); assertEquals("mcp", merged["workflow__helper_2"]!!.kind)
        val st = fresh()
        AgentNode.loop(st, tools, 8, false, Script(Fakes.turn("tool_use", Fakes.toolUse("w1", "workflow__helper", buildJsonObject { put("item", JsonNull) })), Fakes.turn("end_turn", Fakes.textBlock("ok"))).step)
        assertEquals("workflow", st.steps[0]["kind"].asTextOrNull())
        assertTrue(AgentNode.spec.params.any { it.key == "includeWorkflows" }); assertTrue(AgentNode.spec.params.any { it.key == "skills" })
    }

    /** DESIGN4 §9.4: selected skills ride in the FIRST user message as fenced DATA, never in the static system prompt. */
    @Test fun skillsBlockIsFencedDataOutsideTheSystemPrompt() {
        val block = Skills.agentBlock(listOf(Fakes.skill("workspace-notes", "Notes", "1. write\n2. read")))
        assertTrue(block.contains("<skill name=\"workspace-notes\">")); assertTrue(block.contains("</skill>")); assertTrue(block.contains(Skills.SAFETY))
        val first = ClaudeClient.userMessage("goal" + AgentNode.itemBlock(item("a" to 1)) + block)
        assertTrue(Fakes.blocks(first)[0]["text"].asTextOrNull()!!.contains("<skill name="))
        assertFalse(AgentNode.systemPrompt().contains("<skill")); assertFalse(AgentNode.systemPrompt(true, true).contains("<skill"))
    }

    /** DESIGN4P §2.3: modeFor table — bypass/askApproval=false -> ungated (null); inherit -> global with its expiry; an explicit param beats a BYPASS global. */
    @Test fun modeForTable() {
        val now = 5_000L
        assertNull(AgentNode.modeFor("bypass", true, PermissionMode.ASK, 0, now))
        assertNull(AgentNode.modeFor("inherit", false, PermissionMode.ASK, 0, now)); assertNull(AgentNode.modeFor(null, false, PermissionMode.BYPASS, now + 1, now))
        assertEquals(PermissionMode.ASK, AgentNode.modeFor("inherit", true, PermissionMode.ASK, 0, now)); assertEquals(PermissionMode.ASK, AgentNode.modeFor(null, true, PermissionMode.ASK, 0, now))
        assertEquals(PermissionMode.BYPASS, AgentNode.modeFor("inherit", true, PermissionMode.BYPASS, now + 1, now))
        assertEquals(PermissionMode.ASK, AgentNode.modeFor("inherit", true, PermissionMode.BYPASS, now, now))            // expired global Bypass -> ASK
        for (p in listOf("plan", "ask", "auto")) assertEquals(PermissionMode.parse(p), AgentNode.modeFor(p, true, PermissionMode.BYPASS, now + 1, now))
        assertEquals(PermissionMode.AUTO, AgentNode.modeFor("auto", false, PermissionMode.ASK, 0, now))                 // an explicit mode gates even with askApproval off
        val p = AgentNode.spec.params.last()
        assertEquals("permissionMode", p.key); assertEquals(com.mob8n.core.ParamKind.ENUM, p.kind); assertEquals(JsonPrimitive("inherit"), p.default); assertFalse(p.templated)
        assertEquals(listOf("inherit", "plan", "ask", "auto", "bypass"), p.options)
    }

    /** A Plan-gated loop never returns NeedApproval: action_notify fails with `plan mode: `, data_datetime runs. */
    @Test fun planGatedLoopBlocksInsteadOfAsking() = runBlocking {
        val catalog = com.mob8n.core.Catalog(listOf(listOf(Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(Fakes.datetime))))
        val tools = Permissions.gate(nodes(Fakes.notify, Fakes.datetime), PermissionMode.PLAN) { OperatorTools.riskOf(it, catalog) }
        val st = fresh()
        val s = Script(Fakes.turn("tool_use", Fakes.toolUse("a", "action_notify", buildJsonObject { put("title", "hi") }), Fakes.toolUse("b", "data_datetime", buildJsonObject { put("format", "iso") })), Fakes.turn("end_turn", Fakes.textBlock("x")))
        val out = AgentNode.loop(st, tools, 8, true, s.step)
        assertTrue(out is AgentNode.Outcome.Done)
        val results = Fakes.blocks(st.messages[2])
        assertEquals(JsonPrimitive(true), results[0]["is_error"]); assertTrue(results[0]["content"].asTextOrNull()!!.startsWith("plan mode: action_notify is blocked"))
        assertEquals(JsonPrimitive(false), results[1]["is_error"])
        assertEquals(listOf("data.datetime"), calls.map { it.first })
        assertEquals("node", st.steps[0]["kind"].asTextOrNull())
        // approvalText lists every call with its class and mode
        val pending = listOf(Fakes.toolUse("a", "action_notify", buildJsonObject { put("title", "hi") }), Fakes.toolUse("b", "data_http", buildJsonObject { put("url", "https://x") })).let { Turn("tool_use", JsonArray(it)).toolUses }
        val text = AgentNode.approvalText(pending, nodes(Fakes.notify, Fakes.http), PermissionMode.ASK) { OperatorTools.riskOf(it, com.mob8n.core.Catalog(listOf(listOf(Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(Fakes.http))))) }
        assertEquals(listOf("1. action_notify (safe action · asks in ask) {\"title\":\"hi\"}", "2. data_http (safe action · asks in ask) {\"url\":\"https://x\"}"), text.lines())
        assertEquals("Agent wants to: action_notify, data_http", AgentNode.approvalTitle(pending))
        assertTrue(AgentNode.approvalText(pending, emptyMap(), PermissionMode.ASK) { Risk.READ }.contains("(unknown tool)"))
    }

    /** State.denied is consumed by loop(): a mixed batch yields ONE results message (denied id = is_error, never executed) and `denied` resets. */
    @Test fun deniedIdsAreRecordedNotExecuted() = runBlocking {
        val st = fresh()
        val uses = Turn("tool_use", JsonArray(listOf(Fakes.toolUse("a", "action_notify", buildJsonObject { put("title", "hi") }), Fakes.toolUse("b", "data_datetime", buildJsonObject { put("format", "iso") })))).toolUses
        st.messages += Fakes.turn("tool_use", *uses.map { Fakes.toolUse(it.id, it.name, it.input) }.toTypedArray()).asMessage()
        st.pending = uses; st.denied = mapOf("a" to "denied by user")
        val out = AgentNode.loop(st, nodes(Fakes.notify, Fakes.datetime), 8, false, Script(Fakes.turn("end_turn", Fakes.textBlock("x"))).step)
        assertTrue(out is AgentNode.Outcome.Done)
        val results = Fakes.blocks(st.messages[2])
        assertEquals(listOf("a", "b"), results.map { it["tool_use_id"].asTextOrNull() })
        assertEquals("denied by user", results[0]["content"].asTextOrNull()); assertEquals(JsonPrimitive(true), results[0]["is_error"]); assertEquals(JsonPrimitive(false), results[1]["is_error"])
        assertEquals(listOf("data.datetime"), calls.map { it.first })
        assertEquals("denied by user", st.steps[0]["error"].asTextOrNull()); assertEquals("node", st.steps[0]["kind"].asTextOrNull()); assertEquals(JsonPrimitive(0), st.steps[0]["ms"])
        assertTrue(st.denied.isEmpty()); assertTrue(st.pending.isEmpty())
        val blocked = AgentTool.node(Fakes.notify, exec).blocked("plan mode: x")
        assertFalse(blocked.needsApproval); assertEquals("node", blocked.kind); assertEquals("action_notify", blocked.name)
        try { blocked.call(buildJsonObject {}); org.junit.Assert.fail() } catch (e: com.mob8n.core.NodeException) { assertEquals("plan mode: x", e.message) }
    }

    /** AgentTool.mcp end to end over the transport fake: name, loose def, error -> is_error text (never a thrown run failure). */
    @Test fun mcpToolOverFakeTransport() = runBlocking {
        val server = McpServer("agt1", "GitHub Tools", "https://mcp.example.com/mcp", trusted = false)
        McpClient.invalidate(server.id)
        McpClient.transport = { _, _, body, _ ->
            val req = com.mob8n.core.JSON.parseToJsonElement(body) as JsonObject
            val id = req["id"].asTextOrNull()?.toInt() ?: 0
            val resp = if (req["method"].asTextOrNull() == "tools/call") """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"issue #7 created"}]}}"""
                else """{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"Unknown tool: nope"}}"""
            McpClient.HttpResp(200, mapOf("content-type" to "application/json"), resp)
        }
        val t = McpClient.Tool(server.id, server.name, "issue.create", null, "Creates an issue", buildJsonObject { put("type", "object") }, null, emptyMap())
        val tool = AgentTool.mcp(t, server, null, vision = false, timeoutMs = 5_000) {}
        assertEquals(mcpName, tool.name); assertTrue(tool.needsApproval); assertEquals("mcp", tool.kind); assertFalse(tool.rejectTemplates)
        assertEquals(JsonPrimitive(false), tool.def["strict"])
        val out = tool.call(buildJsonObject { put("title", "Bug") })
        assertEquals("issue #7 created", out.text); assertFalse(out.isError)
    }
}
