package com.mob8n.ai

import com.mob8n.core.NodeException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN4P §2.1: the PLAN-v5 §4 table, scope-aware resolution with per-scope Bypass expiry, gate, run_js sanitising, the frozen audit meta. */
class PermissionsTest {
    private val M = PermissionMode.entries; private val R = Risk.entries
    private fun d(m: PermissionMode, r: Risk) = Permissions.decide(m, r, "run_shell")

    /** (1) the full 4×4 matrix incl. reason prefixes. */
    @Test fun decideMatrix() {
        val expect = mapOf(
            PermissionMode.PLAN to listOf(Verdict.RUN, Verdict.BLOCK, Verdict.BLOCK, Verdict.BLOCK),
            PermissionMode.ASK to listOf(Verdict.RUN, Verdict.ASK, Verdict.ASK, Verdict.ASK),
            PermissionMode.AUTO to listOf(Verdict.RUN, Verdict.RUN, Verdict.RUN, Verdict.ASK),
            PermissionMode.BYPASS to listOf(Verdict.RUN, Verdict.RUN, Verdict.RUN, Verdict.RUN),
        )
        for (m in M) for ((i, r) in R.withIndex()) {
            val dec = d(m, r)
            assertEquals("$m/$r", expect.getValue(m)[i], dec.verdict); assertEquals(m, dec.mode); assertEquals(r, dec.risk)
            when (dec.verdict) {
                Verdict.RUN -> assertEquals(if (r == Risk.READ) "read-only" else "auto-approved by ${m.key}", dec.reason)
                Verdict.ASK -> assertEquals("asks in ${m.key}", dec.reason)
                Verdict.BLOCK -> assertTrue(dec.reason.startsWith(Permissions.BLOCKED_PREFIX + "run_shell is blocked"))
            }
        }
        assertEquals(listOf("plan", "ask", "auto", "bypass"), M.map { it.key })   // ordinal = permissiveness
    }

    /** (2)+(3) resolution order and per-scope expiry; no escalation between scopes. */
    @Test fun resolveOrderAndPerScopeExpiry() {
        val now = 1_000_000L
        assertEquals(PermissionMode.PLAN, Permissions.resolve(PermissionMode.PLAN, PermissionMode.AUTO, 0, PermissionMode.BYPASS, now + 1, now))       // agent > conversation > global
        assertEquals(PermissionMode.AUTO, Permissions.resolve(null, PermissionMode.AUTO, 0, PermissionMode.BYPASS, now + 1, now))
        assertEquals(PermissionMode.BYPASS, Permissions.resolve(null, null, 0, PermissionMode.BYPASS, now + 1, now))
        assertEquals(PermissionMode.ASK, Permissions.resolve(global = PermissionMode.BYPASS, globalUntil = now, now = now))                                // expired global -> ASK
        assertEquals(PermissionMode.ASK, Permissions.resolve(conversation = PermissionMode.BYPASS, conversationUntil = now, global = PermissionMode.ASK, globalUntil = 0, now = now))
        assertEquals(PermissionMode.BYPASS, Permissions.resolve(conversation = PermissionMode.BYPASS, conversationUntil = now + 1, global = PermissionMode.ASK, globalUntil = 0, now = now))   // needs no global timestamp
        assertEquals(PermissionMode.ASK, Permissions.resolve(conversation = null, global = PermissionMode.BYPASS, globalUntil = 0, now = now))            // another chat's until is not readable here: no escalation by construction
        assertEquals(PermissionMode.PLAN, Permissions.resolve(conversation = PermissionMode.PLAN, conversationUntil = 0, global = PermissionMode.BYPASS, globalUntil = now + 1, now = now))
        assertEquals(PermissionMode.ASK, Permissions.resolve(global = PermissionMode.ASK, globalUntil = now + 99, now = now))                            // a stale timestamp without BYPASS is inert
    }

    /** (4) gate: verdict -> needsApproval / blocked; unchanged instance; PLAN blocks a trusted MCP READ tool (P18a). */
    @Test fun gateMapsVerdictsAndBlocksMcpInPlan() = runBlocking {
        val tools = mapOf(
            "list_workflows" to Fakes.opTool("list_workflows"), "run_workflow" to Fakes.opTool("run_workflow", needsApproval = true),
            "run_shell" to Fakes.opTool("run_shell", needsApproval = true), "mcp__s__t" to Fakes.mcpTool("mcp__s__t", true, ToolOut("x")),
        )
        val risk: (AgentTool) -> Risk = { OperatorTools.riskOf(it.name, it.kind, it.kind == "mcp" && !it.needsApproval, null) }
        val ask = Permissions.gate(tools, PermissionMode.ASK, risk)
        assertSame(tools["list_workflows"], ask["list_workflows"]); assertTrue(ask["run_workflow"]!!.needsApproval); assertTrue(ask["run_shell"]!!.needsApproval); assertFalse(ask["mcp__s__t"]!!.needsApproval)
        val auto = Permissions.gate(tools, PermissionMode.AUTO, risk)
        assertFalse(auto["run_workflow"]!!.needsApproval); assertFalse(auto["run_shell"]!!.needsApproval)
        val plan = Permissions.gate(tools, PermissionMode.PLAN, risk)
        assertSame(tools["list_workflows"], plan["list_workflows"])
        for (n in listOf("run_shell", "run_workflow", "mcp__s__t")) {
            val t = plan[n]!!
            assertFalse(n, t.needsApproval); assertEquals(tools[n]!!.kind, t.kind)
            try { t.call(buildJsonObject {}); fail(n) } catch (e: NodeException) { assertTrue(e.message!!.startsWith("plan mode: $n is blocked")) }
        }
        assertEquals(Verdict.BLOCK, Permissions.decideFor(tools["mcp__s__t"]!!, PermissionMode.PLAN, Risk.READ).verdict)
        assertEquals(Verdict.RUN, Permissions.decideFor(tools["list_workflows"]!!, PermissionMode.PLAN, Risk.READ).verdict)
    }

    /** (5) parse; (6) modeOrLegacy + JSON round trip incl. junk mode -> null (coerceInputValues pinned); (7) blockedMessage; (8) chip wording. */
    @Test fun parseLegacyJsonAndLabels() {
        assertNull(PermissionMode.parse("inherit")); assertNull(PermissionMode.parse(null)); assertNull(PermissionMode.parse("bypas"))
        assertEquals(PermissionMode.AUTO, PermissionMode.parse("Auto ")); assertEquals(PermissionMode.BYPASS, PermissionMode.parse("bypass"))
        assertEquals(PermissionMode.AUTO, ChatSettings(autoApproveSafe = true, autoApproveCoding = true).modeOrLegacy())
        assertNull(ChatSettings(autoApproveSafe = true).modeOrLegacy()); assertNull(ChatSettings(autoApproveCoding = true).modeOrLegacy()); assertNull(ChatSettings().modeOrLegacy())
        assertEquals(PermissionMode.PLAN, ChatSettings(autoApproveSafe = true, autoApproveCoding = true, mode = PermissionMode.PLAN).modeOrLegacy())   // mode wins
        val s = ChatSettings(mode = PermissionMode.PLAN, bypassUntil = 42L)
        assertTrue(s.json(), s.json().contains("\"mode\":\"plan\"") && s.json().contains("\"bypassUntil\":42"))
        assertEquals(s, ChatSettings.parse(s.json()))
        assertEquals(PermissionMode.BYPASS, ChatSettings.parse("""{"mode":"bypass","bypassUntil":7}""").mode)
        assertNull(ChatSettings.parse("{}").mode); assertEquals(0L, ChatSettings.parse("{}").bypassUntil)
        assertNull(ChatSettings.parse("""{"mode":"bypas"}""").mode); assertNull(ChatSettings.parse("""{"mode":null}""").mode)
        assertTrue(Permissions.blockedMessage("save_workflow").contains("Open in editor")); assertFalse(Permissions.blockedMessage("run_shell").contains("Open in editor"))
        assertTrue(Permissions.blockedMessage("run_shell").startsWith("plan mode: run_shell is blocked (read-only tools only)."))
        assertEquals("safe action · ran (auto)", Permissions.chip(Permissions.decide(PermissionMode.AUTO, Risk.WRITE)))
        assertEquals("coding · asks (ask)", Permissions.chip(Permissions.decide(PermissionMode.ASK, Risk.CODING)))
        assertEquals("destructive · blocked (plan)", Permissions.chip(Permissions.decide(PermissionMode.PLAN, Risk.ALWAYS)))
        assertEquals("read-only · ran (bypass)", Permissions.chip(Permissions.decide(PermissionMode.BYPASS, Risk.READ)))
        assertEquals(60 * 60_000L, Permissions.BYPASS_TTL_MS); assertEquals("stopped by user", Permissions.STOPPED_BY_USER)
    }

    /** (17) isAlwaysNode + run_js allow-list sanitising by mode (P15). */
    @Test fun alwaysNodesAndJsAllowListSanitising() {
        for (id in listOf("action.toggle_workflow", "app.ui_tap", "app.launch_wait", "app.shell_run")) assertTrue(id, Permissions.isAlwaysNode(id))
        for (id in listOf("action.notify", "data.http", "data.datetime")) assertFalse(id, Permissions.isAlwaysNode(id))
        val input = buildJsonObject { put("code", "1"); put("allowNodes", JsonArray(listOf("action.notify", "action.toggle_workflow", "app.ui_tap", "data.http").map(::JsonPrimitive))) }
        val (clean, dropped) = Permissions.sanitizeJsAllow(input, PermissionMode.AUTO)
        assertEquals(listOf("action.toggle_workflow", "app.ui_tap"), dropped)
        assertEquals(listOf("action.notify", "data.http"), (clean["allowNodes"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive("1"), clean["code"])
        for (m in listOf(PermissionMode.ASK, PermissionMode.BYPASS, PermissionMode.PLAN)) { val (same, none) = Permissions.sanitizeJsAllow(input, m); assertSame(input, same); assertTrue(none.isEmpty()) }
        val noList = buildJsonObject { put("code", "1") }
        assertSame(noList, Permissions.sanitizeJsAllow(noList, PermissionMode.AUTO).first)
    }

    /** (18) gateMeta / parseGate round trip; junk -> null. */
    @Test fun gateMetaRoundTrip() {
        val uses = listOf(ToolUse("t1", "run_shell", buildJsonObject {}), ToolUse("t2", "list_workflows", buildJsonObject {}), ToolUse("t3", "nope", buildJsonObject {}))
        val meta = Permissions.gateMeta(uses) { n -> when (n) { "run_shell" -> Permissions.decide(PermissionMode.AUTO, Risk.CODING); "list_workflows" -> Permissions.decide(PermissionMode.AUTO, Risk.READ); else -> null } }
        assertEquals(JsonPrimitive("coding/RUN"), meta["t1"]); assertEquals(JsonPrimitive("read/RUN"), meta["t2"]); assertNull(meta["t3"])
        val back = Permissions.parseGate("coding/RUN", PermissionMode.AUTO)!!
        assertEquals(Risk.CODING, back.risk); assertEquals(Verdict.RUN, back.verdict); assertEquals(PermissionMode.AUTO, back.mode); assertEquals("auto-approved by auto", back.reason)
        assertEquals(Verdict.ASK, Permissions.parseGate("always/ASK", PermissionMode.AUTO)!!.verdict)
        assertEquals("plan mode: ", Permissions.parseGate("write/BLOCK", PermissionMode.PLAN)!!.reason.take(11))
        assertNull(Permissions.parseGate("junk", PermissionMode.ASK)); assertNull(Permissions.parseGate("coding/MAYBE", PermissionMode.ASK)); assertNull(Permissions.parseGate(null, PermissionMode.ASK))
    }
}
