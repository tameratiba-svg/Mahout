package com.mob8n.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4 V8: verified Claude rows, unknown stays unknown, provider cost wins, longest prefix wins. */
class PricesTest {
    private fun usd(model: String, u: TokenUsage, provider: String = PROVIDER_CLAUDE) = Prices.cost(provider, model, u)

    @Test fun claudeRowsMatchTheVerifiedTable() {
        assertEquals(5.0, usd(ClaudeClient.MODEL_OPUS, TokenUsage(1_000_000, 0))!!, 1e-9)
        assertEquals(25.0, usd(ClaudeClient.MODEL_OPUS, TokenUsage(0, 1_000_000))!!, 1e-9)
        assertEquals(0.5, usd(ClaudeClient.MODEL_OPUS, TokenUsage(0, 0, cachedTok = 1_000_000))!!, 1e-9)
        assertEquals(6.25, usd(ClaudeClient.MODEL_OPUS, TokenUsage(0, 0, cacheWriteTok = 1_000_000))!!, 1e-9)
        assertEquals(2.0 + 10.0, usd(ClaudeClient.MODEL_SONNET, TokenUsage(1_000_000, 1_000_000))!!, 1e-9)
        assertEquals(1.0, usd(ClaudeClient.MODEL_HAIKU, TokenUsage(1_000_000, 0))!!, 1e-9)
        assertEquals("2026-09-26", Prices.TABLE["claude:claude-opus-5"]!!.verifiedAt)
    }

    @Test fun unknownIsNullAndNanoIsZero() {
        assertNull(usd("MiniMax-M2.7", TokenUsage(1000, 10), provider = "minimax"))
        assertNull(usd("gpt-6-sol", TokenUsage(1000, 10), provider = "openai"))
        assertNull(Prices.lookup("openrouter", "anthropic/claude-opus-5"))                     // vendor prefix stripped, then no openrouter row -> unknown
        assertEquals(0.0, usd(NanoClient.MODEL_NAME, TokenUsage(1000, 10, estimated = true), provider = PROVIDER_NANO)!!, 1e-9)
        assertEquals(0.42, usd("anything", TokenUsage(1, 1, providerCostUsd = 0.42), provider = "openrouter")!!, 1e-9)   // OpenRouter usage.cost wins
        assertEquals(0.42, usd(ClaudeClient.MODEL_OPUS, TokenUsage(1_000_000, 0, providerCostUsd = 0.42))!!, 1e-9)
    }

    @Test fun prefixLookupLongestWins() {
        val table = mapOf("x:a" to Prices.Price(1.0, 1.0, 0.0, 0.0, "v"), "x:ab" to Prices.Price(2.0, 2.0, 0.0, 0.0, "v"), "y:abc" to Prices.Price(9.0, 9.0, 0.0, 0.0, "v"))
        assertEquals(2.0, Prices.lookup("x", "abc-2026", table)!!.inPerM, 1e-9)
        assertEquals(1.0, Prices.lookup("x", "a-9", table)!!.inPerM, 1e-9)
        assertNull(Prices.lookup("x", "zzz", table)); assertNull(Prices.lookup("z", "abc", table))
        assertEquals(5.0, Prices.lookup(PROVIDER_CLAUDE, "claude-opus-5-20261001")!!.inPerM, 1e-9)
    }

    @Test fun usageParsingAndArithmetic() {
        val oai = Usage.fromOpenAi(buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5); put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 7) }); put("cost", 0.001) })!!
        assertEquals(TokenUsage(3, 5, 7, 0, false, 0.001), oai)
        assertEquals(TokenUsage(10, 5), Usage.fromOpenAi(buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) }))
        assertNull(Usage.fromOpenAi(null)); assertNull(Usage.fromOpenAi(buildJsonObject { put("input_tokens", 3) }))
        assertEquals(TokenUsage(12, 4, 3, 2), Usage.fromClaude(buildJsonObject { put("input_tokens", 12); put("output_tokens", 4); put("cache_read_input_tokens", 3); put("cache_creation_input_tokens", 2) }))
        assertNull(Usage.fromClaude(null))
        assertEquals(TokenUsage(25, 2, estimated = true), Usage.estimate(100, 11))
        val sum = TokenUsage(1, 2, 3, 4) + TokenUsage(10, 20, 30, 40, estimated = true, providerCostUsd = 0.5)
        assertEquals(TokenUsage(11, 22, 33, 44, true, 0.5), sum)
        assertNull((TokenUsage(1, 1) + TokenUsage(1, 1)).providerCostUsd)
        assertTrue(TokenUsage.ZERO.inTok == 0L && TokenUsage.ZERO.outTok == 0L)
    }

    /** DESIGN5 D12: laya: prefix row = 0 for every routed model; Jev has no row -> price unknown (never fabricated). */
    @Test fun systemOneEngines() {
        val u = TokenUsage(158, 0)
        assertEquals(0.0, Prices.cost("laya", "multilingual", u)!!, 1e-12)
        assertEquals(0.0, Prices.cost("laya", "laya-rl-agent", u)!!, 1e-12)
        assertNull(Prices.cost("jev", "jev-latest", u)); assertNull(Prices.cost("jev", "jev-1.13.0", u))
    }
}
