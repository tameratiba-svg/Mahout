package com.mob8n.ai

import android.content.Context
import com.mob8n.core.SECRET_CLAUDE_KEY
import com.mob8n.core.SETTINGS_PREFS
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** One OpenAI-compatible provider row (DESIGN2 §3.2 / §4.1). Claude (SDK) and Gemini Nano (ML Kit) are not rows. */
data class Provider(
    val id: String, val label: String,
    val baseUrl: String,                        // no trailing slash; chat = baseUrl + chatPath
    val keySecret: String,                      // "<id>_api_key" (claude: SECRET_CLAUDE_KEY)
    val defaultModel: String,                   // "" = first id from Fetch models
    val tools: Boolean, val strictTools: Boolean, val jsonSchema: Boolean, val jsonObject: Boolean, val vision: Boolean,
    val maxTokensParam: String = "max_tokens",  // "max_completion_tokens": openai, minimax
    val extraBody: Map<String, JsonElement> = emptyMap(),     // minimax: {"reasoning_split": true}
    val extraHeaders: Map<String, String> = emptyMap(),       // openrouter attribution
    val chatPath: String = "/chat/completions", val modelsPath: String = "/models",
    val needsKey: Boolean = true, val editableBaseUrl: Boolean = false,
    val staticModels: List<String> = emptyList(),             // minimax fallback list
)

/** The data table behind the ONE HTTP client (DESIGN2 V1). Verified 2026-09-25 (docs/DESIGN2.md §4.1, scratchpad/ai-facts.md). */
object Providers {
    const val DEFAULT = "default"                             // == PROVIDER_DEFAULT in Llm.kt
    private const val KEY_BASE_URL = "ai_base_url_"
    /** Settings key (Boolean; a "true"/"false" String is accepted too, for adb-edited prefs): MiniMax reasoning mode, read on every target resolution (no rebuild). */
    const val KEY_MINIMAX_SPLIT = "minimax_reasoning_split"
    // Device A/B (DESIGN6 §12.5): split=true misplaced the reasoning/answer boundary in 6 of 21 turns (incl. unrepairable spills), inline 2 of 28.
    const val MINIMAX_SPLIT_DEFAULT = false

    val HTTP: List<Provider> = listOf(
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "openai_api_key", "gpt-6-sol",
            tools = true, strictTools = true, jsonSchema = true, jsonObject = true, vision = true, maxTokensParam = "max_completion_tokens"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter_api_key", "openrouter/auto",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = true,
            extraHeaders = mapOf("HTTP-Referer" to "https://github.com/mob8n/mob8n", "X-OpenRouter-Title" to "Mahout")),
        // MiniMax: no response_format / tool_choice (400), reasoning goes to reasoning_details with reasoning_split, <think> stripped defensively.
        // vision = MiniMax-M3 only (the table flag says "may accept images"; M2.x models reject image parts with a 2013 parameter error).
        Provider("minimax", "MiniMax", "https://api.minimax.io/v1", "minimax_api_key", "MiniMax-M2.7",
            tools = true, strictTools = false, jsonSchema = false, jsonObject = false, vision = true, maxTokensParam = "max_completion_tokens",
            extraBody = mapOf("reasoning_split" to JsonPrimitive(true)),
            staticModels = listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed", "MiniMax-M2.5", "MiniMax-M2.5-highspeed", "MiniMax-M2.1", "MiniMax-M2.1-highspeed", "MiniMax-M2")),
        Provider("gemini", "Gemini (OpenAI-compatible)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini_api_key", "gemini-3.8-flash",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = true),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "groq_api_key", "llama-3.3-70b-versatile",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = false),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek_api_key", "deepseek-flash",
            tools = true, strictTools = false, jsonSchema = false, jsonObject = true, vision = true),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral_api_key", "mistral-small-latest",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = true),
        Provider("xai", "xAI (Grok)", "https://api.x.ai/v1", "xai_api_key", "grok-4.7",
            tools = true, strictTools = true, jsonSchema = true, jsonObject = true, vision = true),
        Provider("together", "Together AI", "https://api.together.ai/v1", "together_api_key", "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = true),
        Provider("ollama", "Ollama (local network)", "http://localhost:11434/v1", "ollama_api_key", "",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = true, needsKey = false, editableBaseUrl = true),
        Provider("custom", "Custom (OpenAI-compatible)", "", "custom_api_key", "",
            tools = true, strictTools = false, jsonSchema = true, jsonObject = true, vision = true, needsKey = false, editableBaseUrl = true),
    )

    /** 14 ids in node-ENUM order (default, claude, 11 http rows, nano). */
    val OPTION_IDS: List<String> = listOf(DEFAULT, PROVIDER_CLAUDE) + HTTP.map { it.id } + listOf(PROVIDER_NANO)
    /** Accepted forever: graphs saved with provider=auto keep working. */
    val LEGACY_ALIASES: Map<String, String> = mapOf(PROVIDER_AUTO to DEFAULT)

    private val byId: Map<String, Provider> = HTTP.associateBy { it.id }

    /** HTTP rows only (null for default/claude/nano/unknown). */
    fun byId(id: String): Provider? = byId[id]

    fun label(id: String): String = when (id) {
        DEFAULT -> "Default (Settings > AI)"
        PROVIDER_AUTO -> "Default (Settings > AI)"
        PROVIDER_CLAUDE -> "Claude"
        PROVIDER_NANO -> "Gemini Nano (on-device)"
        else -> byId[id]?.label ?: id
    }

    /** Secret name for any concrete id (claude keeps the v1 name). */
    fun keySecret(id: String): String = if (id == PROVIDER_CLAUDE) SECRET_CLAUDE_KEY else byId[id]?.keySecret ?: "${id}_api_key"

    /** Applies the MiniMax reasoning-mode setting and the user base-URL override (ollama/custom); validates the http:// host rule (V17). */
    fun effective(android: Context, p: Provider): Provider {
        val prefs = android.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (p.id == "minimax") return withReasoningSplit(p, parseFlag(prefs.all[KEY_MINIMAX_SPLIT], MINIMAX_SPLIT_DEFAULT))
        if (!p.editableBaseUrl) return p
        val override = prefs.getString(KEY_BASE_URL + p.id, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: return p
        return p.copy(baseUrl = normalizeBaseUrl(override))
    }

    /** Pure: MiniMax body flag. false = thinking inline as <think>…</think> in content (stripped for display/parsing, echoed raw in history). */
    fun withReasoningSplit(p: Provider, split: Boolean): Provider =
        if (p.id != "minimax") p else p.copy(extraBody = p.extraBody + ("reasoning_split" to JsonPrimitive(split)))

    /** Pure: a stored pref value (Boolean, or "true"/"false" from a hand-edited prefs file) -> Boolean; anything else -> default. */
    fun parseFlag(v: Any?, default: Boolean): Boolean = when (v) {
        is Boolean -> v
        is String -> v.trim().lowercase().toBooleanStrictOrNull() ?: default
        else -> default
    }

    /** Pure: trims, strips trailing '/', requires http(s); http:// only for loopback / *.local / RFC-1918 hosts. Throws IllegalArgumentException. */
    fun normalizeBaseUrl(raw: String): String {
        val s = raw.trim().trimEnd('/')
        val uri = try { URI(s) } catch (e: Exception) { throw IllegalArgumentException("Not a valid URL") }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("URL needs a host, e.g. http://192.168.1.5:11434/v1")
        when (uri.scheme?.lowercase()) {
            "https" -> {}
            "http" -> require(isLanHost(host)) { "Plain http:// is only allowed for local-network hosts (localhost, *.local, 10.x, 172.16-31.x, 192.168.x); use https://" }
            else -> throw IllegalArgumentException("URL must start with http:// or https://")
        }
        return s
    }

    /** loopback, *.local, RFC-1918 (10/8, 172.16/12, 192.168/16). */
    fun isLanHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".localhost")) return true
        val parts = h.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val a = parts[0].toInt(); val b = parts[1].toInt()
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** Pref key of a provider's base-URL override (shared with AiPrefs). */
    fun baseUrlKey(id: String): String = KEY_BASE_URL + id
}
