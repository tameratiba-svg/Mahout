package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.Items
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.Workflow
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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

/** DESIGN4 §5.2 / DESIGN4P §2.2: the chat turn is the UNCHANGED Agent loop plus the permission-mode gate, per-call approval and denial re-planning (drive). */
class ChatLoopTest {
    private val calls = mutableListOf<String>()
    private val exec: suspend (NodeSpec, JsonObject) -> Items = { spec, _ -> calls += spec.id; listOf(item("ok" to spec.id)) }
    private val uiTap = NodeSpec("app.ui_tap", "Tap", NodeKind.ACTION, "Taps", agentTool = true)
    private val catalog = Catalog(listOf(listOf(Fakes.FakeNode(Fakes.http), Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(Fakes.datetime), Fakes.FakeNode(uiTap))))
    /** An exposed workflow whose graph holds a destructive node (P18b). */
    private val wfAlways: Workflow = Fakes.exposedWorkflow("wx", "Wx").let { it.copy(graph = it.graph.copy(nodes = it.graph.nodes + NodeInstance("n2", "action.send_intent", "Send", item()))) }
    private val wfPlain: Workflow = Fakes.exposedWorkflow("wp", "Wp")
    private val workflows = listOf(wfAlways, wfPlain)
    private val wfAlwaysName = WorkflowTools.toolName(wfAlways.name, wfAlways.id)
    private val wfPlainName = WorkflowTools.toolName(wfPlain.name, wfPlain.id)

    /** Operator + node + workflow + knowledge + mcp tools shaped like OperatorTools.all builds them (approval flags as constructed, before the gate). */
    private fun raw(): Map<String, AgentTool> {
        val ops = OperatorTools.NAMES.associateWith { n -> Fakes.opTool(n, needsApproval = OperatorTools.riskOf(n, "operator", false, null) != Risk.READ) { calls += n } }
        val nodes = listOf(Fakes.http, Fakes.notify, Fakes.datetime, uiTap).associate { it.toolName to AgentTool.node(it, exec) }
        val wfs = listOf(wfAlwaysName, wfPlainName).associateWith { n -> Fakes.opTool(n, kind = "workflow", needsApproval = true) { calls += n } }
        val mcp = mapOf("mcp__srv__ro" to Fakes.mcpTool("mcp__srv__ro", true, ToolOut("x")) { calls += "mcp_trusted" }, "mcp__srv__rw" to Fakes.mcpTool("mcp__srv__rw", false, ToolOut("x")) { calls += "mcp_untrusted" })
        return AgentTool.merge(ops, nodes, wfs, mcp, mapOf(AgentTool.KNOWLEDGE_SEARCH to Fakes.knowledgeTool(emptyList())))
    }
    private val risk: (AgentTool) -> Risk = { OperatorTools.riskOf(it, catalog, workflows) }
    private fun gated(mode: PermissionMode = PermissionMode.ASK) = Permissions.gate(raw(), mode, risk)
    private fun use(id: String, name: String, vararg kv: Pair<String, String>) = Fakes.toolUse(id, name, buildJsonObject { kv.forEach { put(it.first, it.second) } })
    private fun fresh() = AgentNode.State(mutableListOf(ClaudeClient.userMessage("hello")), 0, mutableListOf(), emptyList())
    private class Script(vararg val turns: Turn) {
        val seen = mutableListOf<List<JsonObject>>()
        val step: suspend (List<JsonObject>) -> Turn = { msgs -> seen += msgs.toList(); turns[seen.size - 1] }
    }
    private val done = Fakes.turn("end_turn", Fakes.textBlock("done"))
    private fun shell(id: String) = use(id, "run_shell", "command" to "ls")
    private val approveAll: suspend (List<ToolUse>) -> Map<String, Boolean> = { p -> p.associate { it.id to true } }
    private val denyAll: suspend (List<ToolUse>) -> Map<String, Boolean> = { p -> p.associate { it.id to false } }
    private fun asks(tools: Map<String, AgentTool>, u: JsonObject) = runBlocking { AgentNode.loop(fresh(), tools, 12, true, Script(Fakes.turn("tool_use", u), done).step) is AgentNode.Outcome.NeedApproval }

    /** (1) a coding tool asks in the default mode: the loop stops with the batch pending and nothing ran. */
    @Test fun codingToolStopsForApprovalByDefault() = runBlocking {
        val st = fresh()
        val out = AgentNode.loop(st, gated(), 12, true, Script(Fakes.turn("tool_use", shell("s1"))).step)
        assertTrue(out is AgentNode.Outcome.NeedApproval)
        assertEquals(listOf("s1"), st.pending.map { it.id })
        assertTrue(calls.isEmpty())
    }

    /** (2)+(10) approve all: the batch runs once, ALL results in ONE user message, the model answers, Done; the assistant row was persisted as pending first. */
    @Test fun approveAllRunsBatchOnceAndPersistsPending() = runBlocking {
        val script = Script(Fakes.turn("tool_use", Fakes.textBlock("running"), shell("s1"), shell("s2")), done)
        val st = fresh()
        val persisted = ArrayList<Pair<Int, Boolean>>(); val awaited = ArrayList<List<String>>()
        val r = ChatRunner.drive(st, gated(), 12, script.step, persist = { p -> persisted += st.messages.size to p }, await = { p -> awaited += p.map { it.id }; p.associate { it.id to true } })
        assertEquals("end_turn", r.stopReason); assertEquals(0, r.denials); assertTrue(r.notes.isEmpty())
        assertEquals(listOf("run_shell", "run_shell"), calls)
        assertEquals(listOf(listOf("s1", "s2")), awaited)
        assertEquals(4, st.messages.size)                                                    // user, assistant, ONE results message, assistant
        assertEquals(listOf("s1", "s2"), Fakes.blocks(st.messages[2]).map { it["tool_use_id"].asTextOrNull() })
        assertTrue(Fakes.blocks(st.messages[2]).all { it["is_error"] == JsonPrimitive(false) })
        assertEquals(listOf(2 to true, 4 to false), persisted)                                 // pending row persisted before the wait; the rest after Done
        assertEquals(2, script.seen.size)                                                     // approval did not cost a model call
        assertTrue(st.denied.isEmpty())
    }

    /** (3)+(11) deny all: is_error "denied by user" per id, the model re-plans (3 model calls); a third denial ends the turn with a note. */
    @Test fun denyAllAppendsErrorResultsAndThirdDenialEndsTheTurn() = runBlocking {
        val script = Script(Fakes.turn("tool_use", shell("a1"), shell("a2")), Fakes.turn("tool_use", shell("b1")), Fakes.turn("tool_use", shell("c1")), done)
        val st = fresh()
        val r = ChatRunner.drive(st, gated(), 12, script.step, persist = {}, await = denyAll)
        assertEquals("denied", r.stopReason); assertEquals(3, r.denials)
        assertEquals(3, script.seen.size)                                                     // deny -> model called again, twice
        assertTrue(calls.isEmpty())
        assertEquals(7, st.messages.size)                                                     // user + 3 × (assistant, denial results)
        val denial = Fakes.blocks(st.messages[2])
        assertEquals(listOf("a1", "a2"), denial.map { it["tool_use_id"].asTextOrNull() })
        assertTrue(denial.all { it["is_error"] == JsonPrimitive(true) && it["content"].asTextOrNull() == "denied by user" })
        assertTrue(r.notes.single().contains("denials"))
        // two denials followed by a plain answer end normally
        val script2 = Script(Fakes.turn("tool_use", shell("a1")), Fakes.turn("tool_use", shell("b1")), done)
        val r2 = ChatRunner.drive(fresh(), gated(), 12, script2.step, persist = {}, await = denyAll)
        assertEquals("end_turn", r2.stopReason); assertEquals(2, r2.denials); assertEquals(3, script2.seen.size)
    }

    /** (9) mixed batch: only the approved call runs; ONE results message covers EVERY id in order (denied = is_error); not a denial; a steps row records it. */
    @Test fun mixedBatchYieldsOneResultsMessageCoveringEveryId() = runBlocking {
        val script = Script(Fakes.turn("tool_use", shell("s1"), use("s2", "workspace_write", "path" to "a.txt", "content" to "x")), done)
        val st = fresh()
        val r = ChatRunner.drive(st, gated(), 12, script.step, persist = {}, await = { mapOf("s1" to true, "s2" to false) })
        assertEquals("end_turn", r.stopReason); assertEquals(0, r.denials)
        assertEquals(listOf("run_shell"), calls)
        assertEquals(4, st.messages.size)
        val results = Fakes.blocks(st.messages[2])
        assertEquals(listOf("s1", "s2"), results.map { it["tool_use_id"].asTextOrNull() })
        assertEquals(JsonPrimitive(false), results[0]["is_error"]); assertEquals(JsonPrimitive(true), results[1]["is_error"]); assertEquals("denied by user", results[1]["content"].asTextOrNull())
        val uses = Fakes.blocks(script.seen[1][1]).filter { it["type"].asTextOrNull() == "tool_use" }.map { it["id"].asTextOrNull() }
        assertEquals(uses.toSet(), Fakes.blocks(script.seen[1][2]).map { it["tool_use_id"].asTextOrNull() }.toSet())   // no tool_use without a tool_result in the next request
        assertEquals(2, st.steps.size); assertEquals("denied by user", st.steps[1]["error"].asTextOrNull()); assertEquals("operator", st.steps[1]["kind"].asTextOrNull())
        assertTrue(st.denied.isEmpty())
        // an id missing from the decision map counts as denied (never executed, never dropped)
        val st2 = fresh()
        ChatRunner.drive(st2, gated(), 12, Script(Fakes.turn("tool_use", shell("x1"), shell("x2")), done).step, persist = {}, await = { mapOf("x1" to true) })
        assertEquals(listOf("run_shell", "run_shell"), calls)
        assertEquals(listOf(false, true), Fakes.blocks(st2.messages[2]).map { it["is_error"] == JsonPrimitive(true) })
    }

    /** (12) Plan: read-only tools run, everything else is blocked with `plan mode: ` — no await, the model re-plans. */
    @Test fun planBlocksNonReadToolsWithoutAsking() = runBlocking {
        val script = Script(Fakes.turn("tool_use", shell("s"), use("l", "list_workflows"), use("m", "mcp__srv__ro"), use("w", "save_workflow", "draftId" to "d")), done)
        val st = fresh()
        val r = ChatRunner.drive(st, gated(PermissionMode.PLAN), 12, script.step, persist = {}, await = { error("must not ask") })
        assertEquals("end_turn", r.stopReason); assertEquals(2, script.seen.size)
        assertEquals(listOf("list_workflows"), calls)
        val results = Fakes.blocks(st.messages[2])
        assertEquals(4, results.size)
        assertTrue(results[0]["content"].asTextOrNull()!!.startsWith("plan mode: run_shell is blocked")); assertEquals(JsonPrimitive(true), results[0]["is_error"])
        assertEquals(JsonPrimitive(false), results[1]["is_error"])
        assertTrue(results[2]["content"].asTextOrNull()!!.startsWith("plan mode: mcp__srv__ro"))                 // P18a: trusted MCP is not read-only in Plan
        assertTrue(results[3]["content"].asTextOrNull()!!.contains("Open in editor"))
        assertEquals("operator", st.steps[0]["kind"].asTextOrNull())
    }

    /** (13) Auto: safe + coding run; destructive, untrusted MCP, UI automation and a workflow tool holding a destructive node ask (P18b). */
    @Test fun autoRunsSafeAndCodingAsksForDestructive() = runBlocking {
        val tools = gated(PermissionMode.AUTO)
        val ok = Script(Fakes.turn("tool_use", shell("s"), use("w", "workspace_write", "path" to "a", "content" to "x"), use("r", "run_workflow", "id" to "x"), use("h", "data_http", "url" to "https://a"), use("n", "action_notify", "title" to "hi"), use("p", wfPlainName)), done)
        assertTrue(AgentNode.loop(fresh(), tools, 12, true, ok.step) is AgentNode.Outcome.Done)
        assertEquals(listOf("run_shell", "workspace_write", "run_workflow", "data.http", "action.notify", wfPlainName), calls)
        for (u in listOf(use("d", "delete_workflow", "id" to "x"), use("m", "mcp__srv__rw"), use("t", "app_ui_tap"), use("a", wfAlwaysName), use("rr", "resume_run", "runId" to "1", "decision" to "approve"), use("dd", "workspace_delete", "path" to "a")))
            assertTrue(u.toString(), asks(tools, u))
        assertEquals(6, calls.size)
        assertEquals(Risk.ALWAYS, risk(raw()[wfAlwaysName]!!)); assertEquals(Risk.WRITE, risk(raw()[wfPlainName]!!))
        // legacy toggles: both on = AUTO, one alone = inherit (ASK here); the deprecated shim still gates
        @Suppress("DEPRECATION") val safeOnly = OperatorTools.gate(raw(), ChatSettings(autoApproveSafe = true), risk)
        assertTrue(asks(safeOnly, use("r", "run_workflow", "id" to "x")))
        @Suppress("DEPRECATION") val both = OperatorTools.gate(raw(), ChatSettings(autoApproveSafe = true, autoApproveCoding = true), risk)
        assertFalse(asks(both, shell("s2"))); assertTrue(asks(both, use("d", "delete_workflow", "id" to "x")))
    }

    /** (14) Bypass: everything runs, including resume_run and untrusted MCP. */
    @Test fun bypassRunsEverything() = runBlocking {
        val tools = gated(PermissionMode.BYPASS)
        val s = Script(Fakes.turn("tool_use", shell("s"), use("d", "delete_workflow", "id" to "x"), use("m", "mcp__srv__rw"), use("t", "app_ui_tap"), use("rr", "resume_run", "runId" to "1", "decision" to "approve"), use("a", wfAlwaysName)), done)
        val st = fresh()
        val r = ChatRunner.drive(st, tools, 12, s.step, persist = {}, await = { error("must not ask") })
        assertEquals("end_turn", r.stopReason)
        assertEquals(listOf("run_shell", "delete_workflow", "mcp_untrusted", "app.ui_tap", "resume_run", wfAlwaysName), calls)
        assertTrue(Fakes.blocks(st.messages[2]).all { it["is_error"] == JsonPrimitive(false) })
    }

    /** (15) wrap: an expired Bypass fails the call that only Bypass let through; AUTO decisions are untouched; run_js loses ALWAYS ids in AUTO and says so. */
    @Test fun wrapReChecksBypassAndSanitisesRunJs() = runBlocking {
        var seen: JsonObject? = null
        val shellTool = Fakes.opTool("run_shell", needsApproval = true) { seen = it }
        val expired = ChatRunner.wrap(shellTool, Permissions.decide(PermissionMode.BYPASS, Risk.CODING), bypassActive = { false }) {}
        try { expired.call(buildJsonObject { put("command", "ls") }); fail() } catch (e: NodeException) { assertEquals(Permissions.EXPIRED, e.message) }
        assertNull(seen)
        assertEquals("ok", ChatRunner.wrap(shellTool, Permissions.decide(PermissionMode.BYPASS, Risk.CODING), bypassActive = { true }) {}.call(buildJsonObject {}).text)
        assertEquals("ok", ChatRunner.wrap(shellTool, Permissions.decide(PermissionMode.AUTO, Risk.CODING), bypassActive = { false }) {}.call(buildJsonObject {}).text)
        assertEquals("ok", ChatRunner.wrap(Fakes.opTool("list_workflows"), Permissions.decide(PermissionMode.BYPASS, Risk.READ), bypassActive = { false }) {}.call(buildJsonObject {}).text)   // read-only never depends on Bypass
        val ran = ArrayList<String>()
        val js = Fakes.opTool("run_js", needsApproval = true) { seen = it }
        val input = buildJsonObject { put("code", "1"); put("allowNodes", JsonArray(listOf("action.notify", "app.ui_tap").map(::JsonPrimitive))) }
        val out = ChatRunner.wrap(js, Permissions.decide(PermissionMode.AUTO, Risk.CODING), bypassActive = { false }) { ran += it }.call(input)
        assertEquals(listOf("action.notify"), (seen!!["allowNodes"] as JsonArray).map { (it as JsonPrimitive).content })
        assertTrue(out.text, out.text.contains("refused in auto mode: app.ui_tap")); assertEquals(listOf("run_js"), ran)
        ChatRunner.wrap(js, Permissions.decide(PermissionMode.ASK, Risk.CODING), bypassActive = { false }) {}.call(input)
        assertEquals(2, (seen!!["allowNodes"] as JsonArray).size)                                          // ASK: the whole call was approved as previewed
        val d = Permissions.decide(PermissionMode.ASK, Risk.CODING)
        val w = ChatRunner.wrap(shellTool, d, { true }) {}
        assertEquals(shellTool.needsApproval, w.needsApproval); assertEquals(shellTool.kind, w.kind); assertEquals(shellTool.def, w.def)
    }

    /** (16) pendingCalls carries risk + mode per call (unknown -> ALWAYS); notification title/text shape. */
    @Test fun pendingCallsAndNotificationText() {
        val uses = listOf(ToolUse("s1", "run_shell", buildJsonObject { put("command", "ls -la") }), ToolUse("u1", "nope", buildJsonObject {}), ToolUse("l1", "list_workflows", buildJsonObject {}), ToolUse("m1", "mcp__srv__ro", buildJsonObject {}))
        val p = ChatRunner.pendingCalls(uses, raw(), risk, PermissionMode.ASK)
        assertEquals(listOf("s1", "u1", "l1", "m1"), p.map { it.id })
        assertEquals(Risk.CODING, p[0].decision.risk); assertEquals(Verdict.ASK, p[0].decision.verdict); assertEquals(PermissionMode.ASK, p[0].decision.mode)
        assertEquals(Risk.ALWAYS, p[1].decision.risk); assertEquals(Verdict.ASK, p[1].decision.verdict)
        assertEquals(Risk.READ, p[2].decision.risk); assertEquals(Verdict.RUN, p[2].decision.verdict)
        assertEquals(Verdict.BLOCK, ChatRunner.pendingCalls(uses, raw(), risk, PermissionMode.PLAN)[3].decision.verdict)   // P18a via decideFor
        assertEquals(uses[0], p[0].toolUse())
        assertEquals("Chat wants to run run_shell", ChatRunner.notificationTitle(p.take(1)))
        assertEquals("Chat wants to run 4 tools", ChatRunner.notificationTitle(p))
        val text = ChatRunner.notificationText(p)
        assertEquals(listOf("1. run_shell — ls -la", "2. nope — nope {}", "3. list_workflows — list_workflows {}", "4. mcp__srv__ro — mcp__srv__ro {}"), text.lines())
        val many = ChatRunner.pendingCalls((1..10).map { ToolUse("i$it", "run_shell", buildJsonObject { put("command", "x".repeat(300)) }) }, raw(), risk, PermissionMode.ASK)
        val lines = ChatRunner.notificationText(many).lines()
        assertEquals(9, lines.size); assertEquals("+2 more", lines.last()); assertTrue(lines.all { it.length <= 120 }); assertTrue(lines[7].startsWith("8. run_shell — "))
    }

    /** (19) pendingJson carries `_decided` per decided block; parsePending strips it and returns the map. */
    @Test fun pendingJsonRoundTripsDecisions() {
        val uses = listOf(ToolUse("s1", "run_shell", buildJsonObject { put("command", "ls") }), ToolUse("s2", "workspace_write", buildJsonObject { put("path", "a") }))
        val json = ChatRunner.pendingJson(uses, mapOf("s1" to true))
        assertTrue(json, json.contains("\"_decided\":true")); assertEquals(1, Regex("_decided").findAll(json).count())
        val (back, decided) = ChatRunner.parsePending(json)
        assertEquals(uses, back); assertEquals(mapOf("s1" to true), decided)
        assertEquals(uses to emptyMap<String, Boolean>(), ChatRunner.parsePending(ChatRunner.pendingJson(uses)))
        assertEquals(emptyList<ToolUse>() to emptyMap<String, Boolean>(), ChatRunner.parsePending(null)); assertEquals(emptyList<ToolUse>(), ChatRunner.parsePending("{nope").first)
        assertEquals(mapOf("s1" to true, "s2" to false), ChatRunner.parsePending(ChatRunner.pendingJson(uses, mapOf("s1" to true, "s2" to false))).second)
    }

    /** (6) READ tools never stop, in every mode. */
    @Test fun readToolsNeverStop() = runBlocking {
        for (m in PermissionMode.entries) {
            calls.clear()
            val tools = gated(m)
            val s = Script(Fakes.turn("tool_use", use("a", "list_workflows"), use("b", "data_datetime", "format" to "iso"), use("c", "knowledge_search", "query" to "q"), use("e", "skill_list"), use("f", "workspace_read", "path" to "a")), done)
            val st = fresh()
            val r = ChatRunner.drive(st, tools, 12, s.step, persist = {}, await = { error("must not ask") })
            assertEquals("end_turn", r.stopReason)
            assertEquals(listOf("list_workflows", "data.datetime", "skill_list", "workspace_read"), calls)
            assertEquals(5, Fakes.blocks(st.messages[2]).size)
        }
    }

    /** (7) Nano cannot run tools: a clear message, zero model calls. */
    @Test fun nanoTargetIsRefusedBeforeAnyModelCall() {
        val nano = LlmTarget(PROVIDER_NANO, null, null, NanoClient.MODEL_NAME, "high", null)
        assertEquals(ChatRunner.ERR_NANO, ChatRunner.checkTarget(nano))
        assertTrue(ChatRunner.ERR_NANO.contains("cannot run tools"))
        assertNull(ChatRunner.checkTarget(LlmTarget(PROVIDER_CLAUDE, null, "k", ClaudeClient.MODEL_OPUS, "high", null)))
        assertNull(ChatRunner.checkTarget(LlmTarget("minimax", Providers.byId("minimax"), "k", "MiniMax-M2.7", "high", null)))
    }

    /** (8) max_steps appends the "say 'continue'" note; a hallucinated finish is inert and its tool_use gets a result (V1/V4). */
    @Test fun maxStepsNoteAndFinishIsClosed() = runBlocking {
        val loopForever = Fakes.turn("tool_use", use("a", "list_workflows"))
        val r = ChatRunner.drive(fresh(), gated(), 2, Script(loopForever, loopForever, loopForever).step, persist = {}, await = approveAll)
        assertEquals("max_steps", r.stopReason); assertTrue(r.notes.single().contains("Stopped after 2 tool steps"))
        val st = fresh()
        val r2 = ChatRunner.drive(st, gated(), 12, Script(Fakes.turn("tool_use", Fakes.toolUse("f", "finish", buildJsonObject { put("result", "All set") }))).step, persist = {}, await = approveAll)
        assertEquals("finish", r2.stopReason)
        assertEquals(3, st.messages.size)                                                     // the finish tool_use has a (is_error) tool_result: transcript stays valid
        assertEquals("f", Fakes.blocks(st.messages[2]).single()["tool_use_id"].asTextOrNull())
    }

    @Test fun settingsRoundTripLeniently() {
        val s = ChatSettings(autoApproveCoding = true, nodeTools = listOf("data.http"), skills = listOf("a"), mode = PermissionMode.AUTO)
        assertEquals(s, ChatSettings.parse(s.json()))
        assertEquals(ChatSettings(), ChatSettings.parse("")); assertEquals(ChatSettings(), ChatSettings.parse("{nope"))
        assertEquals(ChatSettings(autoApproveSafe = true), ChatSettings.parse("""{"autoApproveSafe":true,"future":1}"""))
        assertNull(ChatSettings.parse("{}").skills)
    }

    /** DESIGN5 §6.2: escalated ids ask with the reason; gateMeta freezes <risk>/ASK; drive reports escalations BEFORE the pending persist. */
    @Test fun secondOpinionEscalationFlow() = runBlocking {
        val tools = gated(PermissionMode.AUTO)
        val raw = raw()
        val calls1 = ChatRunner.pendingCalls(listOf(ToolUse("s1", "run_shell", buildJsonObject { put("command", "rm -rf /sdcard/*") })), raw, risk, PermissionMode.AUTO, mapOf("s1" to "second opinion: looks destructive beyond 'coding' (p=0.83)"))
        val d = calls1.single().decision
        assertEquals(Verdict.ASK, d.verdict); assertEquals(Risk.CODING, d.risk); assertEquals(PermissionMode.AUTO, d.mode); assertTrue(d.reason.startsWith("second opinion"))
        assertEquals("coding · asks (auto)", Permissions.chip(d))
        assertEquals(Verdict.RUN, ChatRunner.pendingCalls(listOf(ToolUse("s1", "run_shell", buildJsonObject { })), raw, risk, PermissionMode.AUTO).single().decision.verdict)
        val meta = Permissions.gateMeta(listOf(ToolUse("s1", "run_shell", buildJsonObject { }), ToolUse("l1", "list_workflows", buildJsonObject { })),
            { n -> raw[n]?.let { Permissions.decideFor(it, PermissionMode.AUTO, risk(it)) } }, setOf("s1"))
        assertEquals("coding/ASK", meta["s1"].asTextOrNull()); assertEquals("read/RUN", meta["l1"].asTextOrNull())
        // drive: onEscalation strictly before persist(true), then the approved call runs
        val order = ArrayList<String>()
        val st = fresh()
        val r = ChatRunner.drive(st, tools, 12, Script(Fakes.turn("tool_use", shell("s1")), done).step,
            persist = { p -> order += "persist:$p" }, await = { p -> order += "await"; p.associate { it.id to true } },
            escalate = { tu -> if (tu.name == "run_shell") "second opinion: x" else null }, onEscalation = { order += "escalation:${it.keys}" })
        assertEquals("end_turn", r.stopReason)
        assertEquals(listOf("escalation:[s1]", "persist:true", "await", "persist:false"), order)
        assertTrue("run_shell" in calls)
        // without the flag (default lambdas) run_shell runs unasked in Auto
        calls.clear(); val order2 = ArrayList<String>()
        ChatRunner.drive(fresh(), tools, 12, Script(Fakes.turn("tool_use", shell("s2")), done).step, persist = { order2 += "persist:$it" }, await = { error("must not ask") })
        assertEquals(listOf("persist:false"), order2); assertTrue("run_shell" in calls)
    }
}
