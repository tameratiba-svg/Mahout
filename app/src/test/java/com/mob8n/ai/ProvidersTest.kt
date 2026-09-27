package com.mob8n.ai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProvidersTest {
    @Test fun elevenHttpRowsWithUniqueIdsAndSecretNames() {
        assertEquals(11, Providers.HTTP.size)
        assertEquals(11, Providers.HTTP.map { it.id }.toSet().size)
        for (p in Providers.HTTP) {
            assertEquals("${p.id}_api_key", p.keySecret)
            assertTrue(p.id, p.id == "custom" || p.baseUrl.isNotBlank())
            assertFalse(p.baseUrl.endsWith("/"))
            assertTrue(p.chatPath.startsWith("/") && p.modelsPath.startsWith("/"))
        }
        assertEquals(listOf("openai", "openrouter", "minimax", "gemini", "groq", "deepseek", "mistral", "xai", "together", "ollama", "custom"), Providers.HTTP.map { it.id })
    }

    @Test fun optionIdsAre14InOrderAndAutoIsAnAlias() {
        assertEquals(14, Providers.OPTION_IDS.size)
        assertEquals("default", Providers.OPTION_IDS.first()); assertEquals(PROVIDER_CLAUDE, Providers.OPTION_IDS[1]); assertEquals(PROVIDER_NANO, Providers.OPTION_IDS.last())
        assertEquals(mapOf(PROVIDER_AUTO to Providers.DEFAULT), Providers.LEGACY_ALIASES)
        assertEquals(15, PROVIDERS.size); assertEquals(PROVIDER_AUTO, PROVIDERS.last())
        assertEquals(listOf(PROVIDER_CLAUDE) + Providers.HTTP.map { it.id } + PROVIDER_NANO, AiPrefs.PROVIDER_IDS)
        assertEquals(PROVIDERS, Llm.PROVIDERS)
    }

    @Test fun minimaxRowMatchesVerifiedFacts() {
        val m = Providers.byId("minimax")!!
        assertEquals("https://api.minimax.io/v1", m.baseUrl)
        assertEquals("max_completion_tokens", m.maxTokensParam)
        assertEquals(JsonPrimitive(true), m.extraBody["reasoning_split"])
        assertTrue(m.tools); assertFalse(m.strictTools); assertFalse(m.jsonSchema); assertFalse(m.jsonObject); assertTrue(m.vision)
        assertEquals("MiniMax-M2.7", m.defaultModel)
        assertEquals(listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed", "MiniMax-M2.5", "MiniMax-M2.5-highspeed", "MiniMax-M2.1", "MiniMax-M2.1-highspeed", "MiniMax-M2"), m.staticModels)
    }

    @Test fun strictToolsOnlyOpenAiAndXai() {
        assertEquals(setOf("openai", "xai"), Providers.HTTP.filter { it.strictTools }.map { it.id }.toSet())
        assertEquals("max_completion_tokens", Providers.byId("openai")!!.maxTokensParam)
        assertEquals("max_tokens", Providers.byId("groq")!!.maxTokensParam)
        assertFalse(Providers.byId("groq")!!.vision)
        assertFalse(Providers.byId("deepseek")!!.jsonSchema); assertTrue(Providers.byId("deepseek")!!.jsonObject)
        assertEquals(setOf("ollama", "custom"), Providers.HTTP.filter { !it.needsKey }.map { it.id }.toSet())
        assertEquals(setOf("ollama", "custom"), Providers.HTTP.filter { it.editableBaseUrl }.map { it.id }.toSet())
        assertEquals("Mahout", Providers.byId("openrouter")!!.extraHeaders["X-OpenRouter-Title"])
        assertNull(Providers.byId(PROVIDER_CLAUDE)); assertNull(Providers.byId("default"))
        assertEquals("Claude", Providers.label(PROVIDER_CLAUDE)); assertEquals("whatever", Providers.label("whatever"))
        assertEquals("claude_api_key", Providers.keySecret(PROVIDER_CLAUDE)); assertEquals("groq_api_key", Providers.keySecret("groq"))
    }

    @Test fun httpBaseUrlsOnlyForLanHosts() {
        assertEquals("http://192.168.1.5:11434/v1", Providers.normalizeBaseUrl(" http://192.168.1.5:11434/v1/ "))
        assertEquals("http://localhost:11434/v1", Providers.normalizeBaseUrl("http://localhost:11434/v1"))
        assertEquals("http://10.0.0.7:1234/v1", Providers.normalizeBaseUrl("http://10.0.0.7:1234/v1"))
        assertEquals("http://172.20.0.3/v1", Providers.normalizeBaseUrl("http://172.20.0.3/v1"))
        assertEquals("http://mac.local:11434/v1", Providers.normalizeBaseUrl("http://mac.local:11434/v1"))
        assertEquals("https://example.com/v1", Providers.normalizeBaseUrl("https://example.com/v1/"))
        for (bad in listOf("http://example.com/v1", "http://172.32.0.1/v1", "http://8.8.8.8/v1", "ftp://x.local", "not a url", "")) {
            try { Providers.normalizeBaseUrl(bad); fail("expected rejection of $bad") } catch (e: IllegalArgumentException) {}
        }
        assertTrue(Providers.isLanHost("127.0.0.1")); assertFalse(Providers.isLanHost("192.169.0.1")); assertFalse(Providers.isLanHost("example.local.com"))
    }
}
