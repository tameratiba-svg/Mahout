package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.engine.AiUsageRow
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** DESIGN4 V7: the three clients fill Turn.usage from fixtures; Usage.record is the one funnel into the sink. */
class UsageParsingTest {
    private fun fixture(name: String): JsonObject = JSON.parseToJsonElement(javaClass.getResourceAsStream("/ai/$name.json")!!.bufferedReader().readText()) as JsonObject
    private val rows = ArrayList<AiUsageRow>()
    private val minimax = Providers.byId("minimax")!!
    private val openai = Providers.byId("openai")!!

    @Before fun sink() { Usage.sink = { rows += it } }
    @After fun unsink() { Usage.sink = {} }

    @Test fun claudeFixtureCarriesUsageAndModel() {
        val t = ClaudeClient.parseMessage(fixture("end_turn"))
        assertEquals(TokenUsage(12, 4, 0, 0), t.usage); assertEquals("claude-opus-5", t.model)
        assertEquals(TokenUsage(12, 4, 0, 0), ClaudeClient.toResult(t, LlmRequest(null, "p")).usage)
        assertNull(ClaudeClient.parseMessage(JsonObject(fixture("end_turn") - "usage")).usage)
    }

    @Test fun openAiCompatibleFixturesNormaliseCachedTokens() {
        assertEquals(TokenUsage(10, 5), OpenAiCompat.parseResponse(minimax, fixture("minimax_think"), "MiniMax-M2.7").usage)
        assertEquals(TokenUsage(10, 5), OpenAiCompat.parseResponse(openai, fixture("oai_stop"), "gpt-6-sol").usage)
        val cached = OpenAiCompat.parseResponse(openai, fixture("oai_cached_usage"), "gpt-6-sol").usage!!
        assertEquals(3L, cached.inTok); assertEquals(7L, cached.cachedTok); assertEquals(5L, cached.outTok); assertFalse(cached.estimated); assertNull(cached.providerCostUsd)
        assertNull(OpenAiCompat.parseResponse(openai, JsonObject(fixture("oai_stop") - "usage"), "gpt-6-sol").usage)
    }

    @Test fun recordWritesOneRowWithSourceRefAndCost() {
        Usage.record(PROVIDER_CLAUDE, ClaudeClient.MODEL_OPUS, TokenUsage(1_000_000, 0, cachedTok = 1_000_000), "chat", "conv-1", now = 42L)
        val r = rows.single()
        assertEquals(42L, r.ts); assertEquals(PROVIDER_CLAUDE, r.provider); assertEquals(ClaudeClient.MODEL_OPUS, r.model); assertEquals("chat", r.source)
        assertEquals("conv-1", r.conversationId); assertNull(r.runId); assertEquals(5.5, r.costUsd!!, 1e-9); assertEquals(1_000_000L, r.inTok); assertEquals(1_000_000L, r.cachedTok); assertFalse(r.estimated)
        Usage.record("minimax", "MiniMax-M2.7", TokenUsage(10, 5), "node", "run-7")
        assertEquals("run-7", rows[1].runId); assertNull(rows[1].conversationId); assertNull(rows[1].costUsd)   // price unknown, never $0
        Usage.record("minimax", "MiniMax-M2.7", null, "test", null)
        assertEquals(2, rows.size)                                                              // null usage -> no row
        Usage.sink = { throw IllegalStateException("db closed") }
        Usage.record(PROVIDER_CLAUDE, ClaudeClient.MODEL_OPUS, TokenUsage(1, 1), "test", null)   // never throws
    }

    @Test fun llmStepRecordsOncePerTurn() {
        val t = LlmTarget(PROVIDER_CLAUDE, null, "k", ClaudeClient.MODEL_SONNET, "high", null)
        Llm.recordTurn(t, ClaudeClient.parseMessage(fixture("end_turn")), "node", "run-1", now = 7L)
        val r = rows.single()
        assertEquals("claude-opus-5", r.model)                                                  // the model the provider reported wins over the requested one
        assertEquals("node", r.source); assertEquals("run-1", r.runId); assertEquals(7L, r.ts); assertEquals(12L, r.inTok); assertEquals(4L, r.outTok)
        assertEquals(12 * 5.0 / 1_000_000 + 4 * 25.0 / 1_000_000, r.costUsd!!, 1e-12)
        Llm.recordTurn(t, Turn("end_turn", kotlinx.serialization.json.JsonArray(emptyList())), "chat", "c")
        assertEquals(1, rows.size)                                                              // a turn without usage records nothing
        assertTrue(Usage.estimate(400, 40).estimated)
    }

    /** DESIGN5 §4.6: S1 rows carry provider = engine and sources triage|test|node (runId column for non-chat refs). */
    @Test fun systemOneRowsCarryEngineAndSource() {
        Usage.record("laya", "laya-rl-agent", TokenUsage(158, 0), "triage", "wf-9", now = 1L)
        Usage.record("jev", "jev-1.13.0", TokenUsage(42, 3), "test", null, now = 2L)
        assertEquals(listOf("laya", "jev"), rows.map { it.provider }); assertEquals(listOf("triage", "test"), rows.map { it.source })
        assertEquals("wf-9", rows[0].runId); assertNull(rows[0].conversationId)
        assertEquals(0.0, rows[0].costUsd!!, 1e-12); assertNull(rows[1].costUsd)
        // the real Laya reply's usage block parses to (158, 0)
        val body = JSON.parseToJsonElement(javaClass.getResourceAsStream("/s1/laya_reply.json")!!.bufferedReader().readText()) as JsonObject
        val qs = listOf(S1Question.Choice("department", "x", linkedMapOf("billing" to "", "technical" to "", "other" to "")))
        assertEquals(TokenUsage(158, 0), SystemOne.parse(body, qs, "laya", 1).usage)
    }
}
