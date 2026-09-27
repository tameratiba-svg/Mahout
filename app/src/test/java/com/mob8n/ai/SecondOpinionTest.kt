package com.mob8n.ai

import com.mob8n.core.NodeException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN5 §3.4 / §6.2: CODING+RUN+AUTO only, p > 0.7, fail-open, never lowers a class. */
class SecondOpinionTest {
    private val laya = S1Target("laya", "http://10.0.0.61:8000/v1/systemone", null, null)
    private fun decision(mode: PermissionMode, risk: Risk) = Permissions.decide(mode, risk, "run_shell")
    private val input = buildJsonObject { put("command", "rm -rf /sdcard/* && echo sk-secret-123") }
    private val auto = decision(PermissionMode.AUTO, Risk.CODING)

    private fun decider(p: Double?, seen: MutableList<JsonElement>? = null): suspend (S1Target, JsonElement) -> S1Result = { _, state ->
        seen?.add(state)
        if (p == null) throw NodeException("Cannot reach Laya at 10.0.0.61:8000")
        S1Result("laya", "laya-rl-agent", mapOf("destructive" to S1Answer.Noul("destructive", p, p >= 0.5, null)), null, 40, null, JsonObject(emptyMap()))
    }

    @Test fun candidateTruthTable() {
        for (mode in PermissionMode.entries) for (risk in Risk.entries) {
            val d = decision(mode, risk)
            assertEquals("$mode/$risk", mode == PermissionMode.AUTO && risk == Risk.CODING, SecondOpinion.candidate(d))
        }
        assertFalse(SecondOpinion.candidate(Decision(Verdict.ASK, PermissionMode.AUTO, Risk.CODING, "asks")))
        assertFalse(SecondOpinion.candidate(Decision(Verdict.BLOCK, PermissionMode.AUTO, Risk.CODING, "blocked")))
    }

    @Test fun stateRedactsSecretsAndCaps() {
        val s = SecondOpinion.state("run_shell", input, Risk.CODING, listOf("sk-secret-123"))
        assertEquals(JsonPrimitive("run_shell"), s["tool"]); assertEquals(JsonPrimitive("coding"), s["class"])
        assertFalse(s.toString().contains("sk-secret-123")); assertTrue(s["input"].toString().contains("rm -rf"))
        val big = SecondOpinion.state("run_js", buildJsonObject { put("code", "x".repeat(10_000)) }, Risk.CODING, emptyList())
        assertEquals(SecondOpinion.INPUT_CAP, (big["input"] as JsonPrimitive).content.length)
    }

    @Test fun escalateReasonAndNulls() = runBlocking {
        val seen = ArrayList<JsonElement>()
        val logs = ArrayList<String>()
        assertEquals("second opinion: looks destructive beyond 'coding' (p=0.83)",
            SecondOpinion.escalate(true, { laya }, decider(0.83, seen), listOf("sk-secret-123"), "run_shell", auto, input) { logs += it })
        assertFalse(seen.single().toString().contains("sk-secret-123"))
        assertNull(SecondOpinion.escalate(false, { laya }, decider(0.99), emptyList(), "run_shell", auto, input) {})                  // flag off
        assertNull(SecondOpinion.escalate(true, { laya }, decider(0.7), emptyList(), "run_shell", auto, input) {})                   // p <= 0.7
        assertNull(SecondOpinion.escalate(true, { throw NodeException(SystemOne.ERR_NOT_CONFIGURED) }, decider(0.99), emptyList(), "run_shell", auto, input) { logs += it })   // unconfigured
        assertNull(SecondOpinion.escalate(true, { laya }, decider(null), emptyList(), "run_shell", auto, input) { logs += it })      // engine down -> fail open
        assertTrue(logs.any { it.startsWith("second opinion unavailable") })
        // not a candidate: never asks the engine, never lowers (ASK stays ASK elsewhere; READ/WRITE/ALWAYS and non-Auto modes untouched)
        val never: suspend (S1Target, JsonElement) -> S1Result = { _, _ -> throw AssertionError("must not call") }
        assertNull(SecondOpinion.escalate(true, { laya }, never, emptyList(), "run_shell", decision(PermissionMode.BYPASS, Risk.CODING), input) {})
        assertNull(SecondOpinion.escalate(true, { laya }, never, emptyList(), "workspace_list", decision(PermissionMode.AUTO, Risk.READ), input) {})
        assertNull(SecondOpinion.escalate(true, { laya }, never, emptyList(), "action_notify", decision(PermissionMode.AUTO, Risk.WRITE), input) {})
        // a slow engine is cut at 3 s
        val slow: suspend (S1Target, JsonElement) -> S1Result = { t, s -> delay(10_000); decider(0.99)(t, s) }
        val t0 = System.currentTimeMillis()
        assertNull(SecondOpinion.escalate(true, { laya }, slow, emptyList(), "run_shell", auto, input) {})
        assertTrue(System.currentTimeMillis() - t0 < 6_000)
    }
}
