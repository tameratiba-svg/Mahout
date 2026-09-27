package com.mob8n.ai

import com.mob8n.core.NodeException
import com.mob8n.core.SECRET_CLAUDE_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN2 §5.2 resolution chain over the pure [Llm.resolveTarget] (AiPrefs/SharedPreferences are Android; the Context wrappers only feed these inputs). */
class LlmTargetTest {
    private val logs = mutableListOf<String>()
    private fun resolve(
        param: String, model: String? = null, effort: String? = null, temperature: Double? = null,
        defaultAi: DefaultAi? = null, preferOnDevice: Boolean = false, nano: Boolean = false, secrets: Map<String, String> = emptyMap(),
        effective: (Provider) -> Provider = { it },
    ) = Llm.resolveTarget(param, model, effort, temperature, defaultAi, preferOnDevice, nano, { secrets[it] }, effective) { logs += it }

    private fun expectError(contains: String, block: () -> Unit) {
        try { block(); fail("expected NodeException containing '$contains'") } catch (e: NodeException) { assertTrue(e.message, e.message!!.contains(contains)) }
    }

    @Test fun autoIsAnAliasOfDefaultAndExplicitDefaultAiWins() {
        val d = DefaultAi("minimax", "MiniMax-M2.7", "high", 0.4)
        val t = resolve(PROVIDER_AUTO, defaultAi = d, preferOnDevice = true, nano = true, secrets = mapOf("minimax_api_key" to "k1", SECRET_CLAUDE_KEY to "c"))
        assertEquals("minimax", t.providerId); assertEquals("MiniMax-M2.7", t.model); assertEquals("k1", t.key); assertEquals(0.4, t.temperature)
        assertEquals("MiniMax", t.label); assertTrue(t.supportsTools); assertTrue(t.supportsVision)
        assertEquals(t, resolve(PROVIDER_DEFAULT, defaultAi = d, preferOnDevice = true, nano = true, secrets = mapOf("minimax_api_key" to "k1", SECRET_CLAUDE_KEY to "c")))
        assertEquals(t, resolve("", defaultAi = d, secrets = mapOf("minimax_api_key" to "k1")))
    }

    @Test fun nodeValuesOverrideTheDefaultAi() {
        val d = DefaultAi("openai", "gpt-6-sol", "low", 0.1)
        val t = resolve(PROVIDER_DEFAULT, model = "gpt-6-luna", effort = "max", temperature = 1.5, defaultAi = d, secrets = mapOf("openai_api_key" to "k"))
        assertEquals("gpt-6-luna", t.model); assertEquals("max", t.effort); assertEquals(1.5, t.temperature)
    }

    @Test fun blankModelFallsBackToDefaultAiModelThenProviderDefault() {
        assertEquals("gpt-6-astra", resolve(PROVIDER_DEFAULT, defaultAi = DefaultAi("openai", "gpt-6-astra"), secrets = mapOf("openai_api_key" to "k")).model)
        assertEquals("gpt-6-sol", resolve(PROVIDER_DEFAULT, defaultAi = DefaultAi("openai", ""), secrets = mapOf("openai_api_key" to "k")).model)
        assertEquals("gpt-6-sol", resolve("openai", model = " ", secrets = mapOf("openai_api_key" to "k")).model)
        // explicit id + a DefaultAi for the SAME id -> its model; a different id -> the table default
        assertEquals("gpt-6-astra", resolve("openai", defaultAi = DefaultAi("openai", "gpt-6-astra"), secrets = mapOf("openai_api_key" to "k")).model)
        assertEquals("llama-3.3-70b-versatile", resolve("groq", defaultAi = DefaultAi("openai", "gpt-6-astra"), secrets = mapOf("groq_api_key" to "k")).model)
        assertEquals(ClaudeClient.MODEL_OPUS, resolve(PROVIDER_CLAUDE, secrets = mapOf(SECRET_CLAUDE_KEY to "c")).model)
        assertEquals(ClaudeClient.MODEL_SONNET, resolve(PROVIDER_DEFAULT, defaultAi = DefaultAi(PROVIDER_CLAUDE, ClaudeClient.MODEL_SONNET), secrets = mapOf(SECRET_CLAUDE_KEY to "c")).model)
    }

    @Test fun missingKeyNamesTheProvider() {
        expectError("OpenAI API key not set — Settings > AI") { resolve("openai") }
        expectError("Claude API key not set — Settings > AI") { resolve(PROVIDER_CLAUDE) }
        expectError("MiniMax API key not set") { resolve(PROVIDER_DEFAULT, defaultAi = DefaultAi("minimax", "")) }
        // ollama needs no key but needs a model
        expectError("Pick a model for Ollama (local network)") { resolve("ollama") }
        assertEquals("llama3", resolve("ollama", model = "llama3").model)
        assertNull(resolve("ollama", model = "llama3").key)
        expectError("Set the base URL for Custom") { resolve("custom", model = "x") }
        assertEquals("http://10.0.0.2:1234/v1", resolve("custom", model = "x", effective = { it.copy(baseUrl = "http://10.0.0.2:1234/v1") }).provider!!.baseUrl)
        expectError("Unknown AI provider 'bogus'") { resolve("bogus") }
    }

    @Test fun legacyChainWhenNoDefaultAiIsSet() {
        // prefer on-device + Nano available -> Nano
        val n = resolve(PROVIDER_AUTO, preferOnDevice = true, nano = true, secrets = mapOf(SECRET_CLAUDE_KEY to "c"))
        assertEquals(PROVIDER_NANO, n.providerId); assertEquals("gemini-nano", n.model); assertFalse(n.supportsTools); assertTrue(n.supportsVision)
        expectError("Gemini Nano (on-device) does not support tool calling") { Llm.requireTools(n) }
        // first PROVIDER_IDS entry with a key (Claude first), logged
        logs.clear()
        assertEquals(PROVIDER_CLAUDE, resolve(PROVIDER_DEFAULT, secrets = mapOf("groq_api_key" to "g", SECRET_CLAUDE_KEY to "c")).providerId)
        assertEquals(listOf("No Default AI set; using Claude"), logs)
        assertEquals("groq", resolve(PROVIDER_DEFAULT, preferOnDevice = true, nano = false, secrets = mapOf("groq_api_key" to "g")).providerId)
        // Nano available but not preferred and no key -> Nano
        assertEquals(PROVIDER_NANO, resolve(PROVIDER_DEFAULT, nano = true).providerId)
        expectError(ERR_NO_PROVIDER) { resolve(PROVIDER_DEFAULT) }
        // an explicit Nano choice needs no key and ignores the chain
        assertEquals(PROVIDER_NANO, resolve(PROVIDER_NANO, secrets = mapOf(SECRET_CLAUDE_KEY to "c")).providerId)
    }

    @Test fun claudeEffortIsValidatedAndTargetsExposeCapabilities() {
        val c = resolve(PROVIDER_CLAUDE, effort = "bogus", secrets = mapOf(SECRET_CLAUDE_KEY to "c"))
        assertEquals("high", c.effort); assertTrue(c.supportsTools && c.supportsVision); assertNull(c.provider)
        val g = resolve("groq", secrets = mapOf("groq_api_key" to "k"))
        assertTrue(g.supportsTools); assertFalse(g.supportsVision)
        assertEquals("Groq", g.label)
    }
}
