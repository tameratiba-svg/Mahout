package com.mob8n.ai

import com.mob8n.core.asDouble
import com.mob8n.engine.AiUsageRow
import kotlinx.serialization.json.JsonObject

/** Token counts of one model call (DESIGN4 §3.3). inTok = UNCACHED input; cachedTok = cache reads. Clients sum repair retries into one value. */
data class TokenUsage(val inTok: Long, val outTok: Long, val cachedTok: Long = 0, val cacheWriteTok: Long = 0, val estimated: Boolean = false, val providerCostUsd: Double? = null) {
    operator fun plus(o: TokenUsage): TokenUsage = TokenUsage(
        inTok + o.inTok, outTok + o.outTok, cachedTok + o.cachedTok, cacheWriteTok + o.cacheWriteTok, estimated || o.estimated,
        if (providerCostUsd == null && o.providerCostUsd == null) null else (providerCostUsd ?: 0.0) + (o.providerCostUsd ?: 0.0),
    )
    companion object { val ZERO = TokenUsage(0, 0) }
}

/** Static price table (DESIGN4 V8). Unknown model -> null -> the Dashboard shows "price unknown", never $0. */
// ponytail: static price table; upgrade = fetch provider pricing
object Prices {
    data class Price(val inPerM: Double, val outPerM: Double, val cacheReadPerM: Double, val cacheWritePerM: Double, val verifiedAt: String)

    // Verified 2026-09-26 (claude-api skill, cached 2026-06-24): $/MTok in/out; cache read = 0.1× input, 5-min cache write = 1.25× input.
    val TABLE: Map<String, Price> = mapOf(
        "claude:claude-opus-5" to Price(5.0, 25.0, 0.5, 6.25, "2026-09-26"),
        "claude:claude-sonnet-5" to Price(2.0, 10.0, 0.2, 2.5, "2026-09-26"),
        "claude:claude-haiku-4-5" to Price(1.0, 5.0, 0.1, 1.25, "2026-09-26"),
        "$PROVIDER_NANO:${NanoClient.MODEL_NAME}" to Price(0.0, 0.0, 0.0, 0.0, "2026-09-26"),
        // DESIGN5 D12: every Laya model (laya-serve is self-hosted) costs 0 via the "laya:" prefix row; Jev has no published price -> absent ("price unknown").
        "laya:" to Price(0.0, 0.0, 0.0, 0.0, "2026-09-26 self-hosted"),
        // minimax / openai / openrouter / groq / deepseek / mistral / xai / together: NOT verified — absent (cost = null -> "price unknown"). ollama / custom: local, absent.
    )

    /** Exact "<provider>:<model>", then the LONGEST table key that is a prefix of the model within the provider; openrouter strips the "vendor/" prefix. */
    fun lookup(providerId: String, model: String, table: Map<String, Price> = TABLE): Price? {
        val m = if (providerId == "openrouter") model.substringAfter('/') else model
        table["$providerId:$m"]?.let { return it }
        val prefix = "$providerId:"
        return table.entries.filter { it.key.startsWith(prefix) && m.startsWith(it.key.removePrefix(prefix)) }.maxByOrNull { it.key.length }?.value
    }

    /** providerCostUsd (OpenRouter usage.cost) wins; else table math; else null. */
    fun cost(providerId: String, model: String, u: TokenUsage): Double? = u.providerCostUsd ?: lookup(providerId, model)?.let { p ->
        (u.inTok * p.inPerM + u.outTok * p.outPerM + u.cachedTok * p.cacheReadPerM + u.cacheWriteTok * p.cacheWritePerM) / 1_000_000
    }
}

/** Usage parsing + the ONE recording funnel (DESIGN4 V7). Rows reach Room through [sink] (Mob8NApp -> engine.recordAiUsage). */
object Usage {
    // ponytail: static Usage.sink; upgrade = recorder passed through LlmTarget
    @Volatile var sink: (AiUsageRow) -> Unit = {}

    private fun JsonObject.long(key: String): Long? = this[key].asDouble()?.toLong()

    /** Claude `usage`: input_tokens is already the uncached remainder. */
    fun fromClaude(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        val inTok = usage.long("input_tokens") ?: return null
        return TokenUsage(inTok, usage.long("output_tokens") ?: 0, usage.long("cache_read_input_tokens") ?: 0, usage.long("cache_creation_input_tokens") ?: 0)
    }

    /** OpenAI-compatible `usage`: prompt_tokens INCLUDES cached -> inTok = prompt - cached; `cost` (OpenRouter) becomes providerCostUsd. null when prompt_tokens is absent. */
    fun fromOpenAi(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        val prompt = usage.long("prompt_tokens") ?: return null
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0
        return TokenUsage((prompt - cached).coerceAtLeast(0), usage.long("completion_tokens") ?: 0, cached, 0, false, usage["cost"].asDouble())
    }

    /** Gemini Nano has no counters: chars / 4, flagged estimated (cost 0 via the table). */
    fun estimate(promptChars: Int, outChars: Int): TokenUsage = TokenUsage((promptChars / 4).toLong(), (outChars / 4).toLong(), estimated = true)

    /** null usage -> no row. source ∈ node|chat|builder|test|triage; ref = runId (node) or conversationId (chat). Never throws. */
    fun record(providerId: String, model: String, u: TokenUsage?, source: String, ref: String?, now: Long = System.currentTimeMillis()) {
        if (u == null) return
        runCatching {
            sink(AiUsageRow(
                ts = now, provider = providerId, model = model, source = source,
                inTok = u.inTok, outTok = u.outTok, cachedTok = u.cachedTok, cacheWriteTok = u.cacheWriteTok,
                costUsd = Prices.cost(providerId, model, u), estimated = u.estimated,
                runId = if (source == "chat") null else ref, conversationId = if (source == "chat") ref else null,
            ))
        }
    }
}
