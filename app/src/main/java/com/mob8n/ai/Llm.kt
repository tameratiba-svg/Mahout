package com.mob8n.ai

import android.content.Context
import com.mob8n.core.ExecutionContext
import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.Persistence
import com.mob8n.core.SECRET_CLAUDE_KEY
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PROVIDER_AUTO = "auto"
const val PROVIDER_NANO = "on_device_gemini_nano"
const val PROVIDER_CLAUDE = "claude"
const val PROVIDER_DEFAULT = "default"
/** ENUM options of the `provider` param: default, claude, 11 http ids, nano, auto (15; `auto` last, alias of default). */
val PROVIDERS: List<String> get() = Providers.OPTION_IDS + PROVIDER_AUTO

const val ERR_NO_KEY = "Claude API key not set — Settings > AI"
const val ERR_NO_PROVIDER = "No AI provider connected — Settings > AI"

data class LlmRequest(
    val system: String?, val prompt: String, val imageUri: String? = null, val jsonSchema: JsonObject? = null,
    val model: String = "", val effort: String = "high", val maxTokens: Int = 4096, val timeoutMs: Long = 120_000,
    val temperature: Double? = null,           // null = provider default; ignored by claude/nano
    val jsonMode: Boolean = false,             // json_object tier without a schema (Build with AI, ai.ask outputMode=json)
)  // model "" = the target's model

data class LlmResult(val text: String, val json: JsonElement?, val stopReason: String, val provider: String, val model: String, val usage: TokenUsage? = null)

/** One tool call requested by the model; `input` is parsed JSON (never string-matched). */
data class ToolUse(val id: String, val name: String, val input: JsonObject)

/**
 * Provider-neutral view of one assistant turn in Claude's wire shape: `content` is the raw content array
 * (text / tool_use / thinking ... blocks) so it can be echoed back verbatim in the transcript.
 * `raw` = the OpenAI-compatible provider's own message object (echoed as `_oai`, MiniMax needs its reasoning chain); `model` = what the provider reported.
 * `usage` = the provider's token counters (DESIGN4 V7), recorded by the Llm funnel; the default keeps every existing Turn(...) compiling.
 */
data class Turn(val stopReason: String, val content: JsonArray, val stopDetails: String? = null, val raw: JsonObject? = null, val model: String? = null, val usage: TokenUsage? = null) {
    val text: String get() = content.filterIsInstance<JsonObject>().filter { it["type"].asTextOrNull() == "text" }.joinToString("") { it["text"].asTextOrNull() ?: "" }
    val toolUses: List<ToolUse> get() = content.filterIsInstance<JsonObject>().filter { it["type"].asTextOrNull() == "tool_use" }.map {
        ToolUse(it["id"].asTextOrNull() ?: "", it["name"].asTextOrNull() ?: "", it["input"] as? JsonObject ?: JsonObject(emptyMap()))
    }
    fun asMessage(): JsonObject = buildJsonObject { put("role", "assistant"); put("content", content); if (raw != null) put(OpenAiCompat.RAW_KEY, raw) }

    /** DESIGN4P P6: tool_use ids unique and non-blank (blank -> `call_<n>`, repeat -> `<id>_2`, `_3` …); the echoed `raw.tool_calls[i].id` is rewritten in lock-step. Same instance when nothing changes. */
    fun withUniqueToolIds(): Turn {
        val seen = HashSet<String>(); var n = 0; var changed = false
        val newIds = ArrayList<String>()
        val fixed = content.map { b ->
            val o = b as? JsonObject ?: return@map b
            if (o["type"].asTextOrNull() != "tool_use") return@map b
            n++
            val id0 = o["id"].asTextOrNull().orEmpty()
            var id = if (id0.isBlank()) "call_$n" else id0
            var k = 2
            while (!seen.add(id)) id = "${if (id0.isBlank()) "call_$n" else id0}_${k++}"
            newIds += id
            if (id == id0) o else { changed = true; JsonObject(o + ("id" to JsonPrimitive(id))) }
        }
        if (!changed) return this
        val calls = raw?.get("tool_calls") as? JsonArray
        val raw2 = if (calls == null) raw else JsonObject(raw + ("tool_calls" to JsonArray(calls.mapIndexed { i, c -> if (c is JsonObject && i < newIds.size) JsonObject(c + ("id" to JsonPrimitive(newIds[i]))) else c })))
        return copy(content = JsonArray(fixed), raw = raw2)
    }
}

/** Resolved provider for one call. providerId ∈ {claude, on_device_gemini_nano, <http id>}; provider != null iff http. */
data class LlmTarget(val providerId: String, val provider: Provider?, val key: String?, val model: String, val effort: String, val temperature: Double?) {
    val label: String get() = Providers.label(providerId)
    val supportsTools: Boolean get() = providerId == PROVIDER_CLAUDE || provider?.tools == true
    val supportsVision: Boolean get() = providerId == PROVIDER_CLAUDE || providerId == PROVIDER_NANO || provider?.vision == true
}

object Llm {
    val PROVIDERS: List<String> get() = com.mob8n.ai.PROVIDERS

    fun claudeKey(ctx: ExecutionContext): String? = ctx.persistence.getSecret(SECRET_CLAUDE_KEY)?.takeIf { it.isNotBlank() }

    fun noKey(label: String) = NodeException("$label API key not set — Settings > AI")

    fun requireTools(t: LlmTarget) { if (!t.supportsTools) throw NodeException("${t.label} does not support tool calling — choose another provider for the Agent") }

    // ---------------------------------------------------------------- target resolution (DESIGN2 §5.2)

    /** Reads params provider/model/effort/temperature of the current node (each only when the spec declares it). */
    suspend fun target(ctx: ExecutionContext): LlmTarget = target(
        ctx.android, ctx.persistence,
        ctx.spec.param("provider")?.let { ctx.strOrNull("provider") } ?: PROVIDER_DEFAULT,
        ctx.spec.param("model")?.let { ctx.strOrNull("model") },
        ctx.spec.param("effort")?.let { ctx.strOrNull("effort") },
        ctx.spec.param("temperature")?.let { ctx.double("temperature") },
        ctx::log,
    )

    suspend fun target(android: Context?, persistence: Persistence, providerParam: String, model: String?, effort: String?, temperature: Double?, log: (String) -> Unit = {}): LlmTarget {
        val defaultAi = android?.let { AiPrefs.readDefaultAi(it) }
        val isDefault = (Providers.LEGACY_ALIASES[providerParam] ?: providerParam.ifBlank { PROVIDER_DEFAULT }) == PROVIDER_DEFAULT
        // One ML Kit status IPC only on the legacy "no Default AI chosen" path (v1 behaviour); never per item otherwise.
        val nano = isDefault && defaultAi == null && NanoClient.status() == NanoStatus.AVAILABLE
        return resolveTarget(
            providerParam, model, effort, temperature,
            defaultAi = defaultAi,
            preferOnDevice = android != null && AiPrefs.preferOnDevice(android),
            nanoAvailable = nano,
            secret = { persistence.getSecret(it)?.takeIf { s -> s.isNotBlank() } },
            effective = { p -> if (android != null) Providers.effective(android, p) else p },
            log = log,
        )
    }

    /** Build with AI / Test: same chain, no ExecutionContext. Nano availability = the last status the settings screen saw (no ML Kit IPC on the UI path). */
    fun defaultTarget(android: Context): LlmTarget = resolveTarget(
        PROVIDER_DEFAULT, null, null, null,
        defaultAi = AiPrefs.readDefaultAi(android), preferOnDevice = AiPrefs.preferOnDevice(android),
        nanoAvailable = AiPrefs.nanoStatus.value == NanoStatus.AVAILABLE,
        secret = { AiPrefs.secret(android, it) }, effective = { Providers.effective(android, it) }, log = {},
    )

    /**
     * Pure resolution (JVM-tested): `auto` -> `default`; default -> explicit DefaultAi else the V8 chain
     * (preferOnDevice && Nano AVAILABLE -> nano; first PROVIDER_IDS entry with a key; Nano if AVAILABLE; else "No AI provider connected").
     * Concrete id -> key check (needsKey), model fallback (node -> DefaultAi.model when the ids match -> provider default), base-URL override.
     */
    fun resolveTarget(
        providerParam: String, model: String?, effort: String?, temperature: Double?,
        defaultAi: DefaultAi?, preferOnDevice: Boolean, nanoAvailable: Boolean,
        secret: (String) -> String?, effective: (Provider) -> Provider = { it }, log: (String) -> Unit = {},
    ): LlmTarget {
        var id = Providers.LEGACY_ALIASES[providerParam] ?: providerParam.ifBlank { PROVIDER_DEFAULT }
        var m = model?.trim().orEmpty(); var e = effort?.takeIf { it.isNotBlank() }; var t = temperature
        if (id == PROVIDER_DEFAULT) {
            if (defaultAi != null) {
                id = defaultAi.provider
                if (m.isBlank()) m = defaultAi.model
                if (e == null) e = defaultAi.effort
                if (t == null) t = defaultAi.temperature
            } else {
                id = when {
                    preferOnDevice && nanoAvailable -> PROVIDER_NANO
                    else -> AiPrefs.PROVIDER_IDS.firstOrNull { it != PROVIDER_NANO && secret(Providers.keySecret(it)) != null }
                        ?.also { log("No Default AI set; using ${Providers.label(it)}") }
                        ?: if (nanoAvailable) PROVIDER_NANO else throw NodeException(ERR_NO_PROVIDER)
                }
            }
        }
        val eff = e ?: "high"
        return when (id) {
            PROVIDER_NANO -> LlmTarget(PROVIDER_NANO, null, null, NanoClient.MODEL_NAME, eff, null)
            PROVIDER_CLAUDE -> {
                val key = secret(SECRET_CLAUDE_KEY) ?: throw noKey("Claude")
                val mm = m.ifBlank { defaultAi?.takeIf { it.provider == PROVIDER_CLAUDE }?.model?.ifBlank { null } ?: ClaudeClient.MODEL_OPUS }
                LlmTarget(PROVIDER_CLAUDE, null, key, mm, if (eff in AiPrefs.EFFORTS) eff else "high", t)
            }
            else -> {
                val p0 = Providers.byId(id) ?: throw NodeException("Unknown AI provider '$id' — pick one in the node's Provider list")
                val key = secret(p0.keySecret)
                if (p0.needsKey && key == null) throw noKey(p0.label)
                val p = effective(p0)
                if (p.baseUrl.isBlank()) throw NodeException("Set the base URL for ${p.label} (Settings > AI)")
                val mm = m.ifBlank { defaultAi?.takeIf { it.provider == id }?.model?.ifBlank { null } ?: p.defaultModel }
                if (mm.isBlank()) throw NodeException("Pick a model for ${p.label} (Settings > AI > Fetch models)")
                LlmTarget(id, p, key, mm, eff, t)
            }
        }
    }

    // ---------------------------------------------------------------- calls

    /** nano -> NanoClient; claude -> ClaudeClient (image via Images); else OpenAiCompat. Blank req.model = the target's model; effort/temperature come from the target. Usage recorded as source "node", ref = runId. */
    suspend fun complete(ctx: ExecutionContext, t: LlmTarget, req: LlmRequest): LlmResult {
        val r = req.copy(model = req.model.ifBlank { t.model }, effort = t.effort, temperature = req.temperature ?: t.temperature)
        return when (t.providerId) {
            PROVIDER_NANO -> NanoClient.complete(ctx, r)
            PROVIDER_CLAUDE -> ClaudeClient.complete(t.key ?: throw noKey("Claude"), r, r.imageUri?.let { Images.base64(ctx.requireAndroid(), it) })
            else -> OpenAiCompat.complete(t.provider!!, t.key, r, r.imageUri?.let { Images.base64(ctx.requireAndroid(), it) }, ctx::log)
        }.also { Usage.record(t.providerId, it.model.ifBlank { t.model }, it.usage, "node", ctx.runId) }
    }

    /** No ExecutionContext (Build with AI, Test). Nano -> NodeException. Usage recorded as `source` (default "builder"). */
    suspend fun completeDirect(android: Context, t: LlmTarget, req: LlmRequest, log: (String) -> Unit = {}, source: String = "builder", ref: String? = null): LlmResult {
        val r = req.copy(model = req.model.ifBlank { t.model }, effort = t.effort, temperature = req.temperature ?: t.temperature)
        return when (t.providerId) {
            PROVIDER_NANO -> throw NodeException("Gemini Nano cannot be used here — pick a cloud provider in Settings > AI")
            PROVIDER_CLAUDE -> ClaudeClient.complete(t.key ?: throw noKey("Claude"), r, r.imageUri?.let { Images.base64(android, it) })
            else -> OpenAiCompat.complete(t.provider!!, t.key, r, r.imageUri?.let { Images.base64(android, it) }, log)
        }.also { Usage.record(t.providerId, it.model.ifBlank { t.model }, it.usage, source, ref) }
    }

    /**
     * One model call over a Claude-wire transcript (Agent, Build-with-AI repair, chat). Claude ignores temperature (adaptive thinking rejects it). Usage recorded after the call (V7).
     * `onDelta` != null streams (chat, DESIGN6 D5): display deltas only — the returned Turn is the one the non-streaming path would produce; null = the v5 path byte-for-byte.
     */
    suspend fun step(
        t: LlmTarget, maxTokens: Int, system: String?, messages: List<JsonObject>, tools: List<JsonObject>, jsonMode: Boolean, timeoutMs: Long, log: (String) -> Unit = {},
        source: String = "node", ref: String? = null,
        onDelta: OnDelta? = null,
    ): Turn = when (t.providerId) {
        PROVIDER_NANO -> throw NodeException("Gemini Nano cannot run the Agent (no tool calling); pick a cloud provider")
        PROVIDER_CLAUDE -> ClaudeClient.step(t.key ?: throw noKey("Claude"), t.model, t.effort, maxTokens, system, messages, tools, null, timeoutMs, null, onDelta)
        else -> OpenAiCompat.step(t.provider!!, t.key, t.model, maxTokens, t.temperature, system, messages, tools, jsonMode, timeoutMs, log, onDelta)
    }.withUniqueToolIds().also { recordTurn(t, it, source, ref) }

    /** The one place a Turn becomes an ai_usage row (JVM-tested with parsed fixtures). */
    internal fun recordTurn(t: LlmTarget, turn: Turn, source: String, ref: String?, now: Long = System.currentTimeMillis()) =
        Usage.record(t.providerId, turn.model ?: t.model, turn.usage, source, ref, now)

    /**
     * Nano's repair loop generalised: run `first`; parse + missingKeys; on failure run `again(problem, previous)` once;
     * still bad -> NodeException("<label> returned invalid JSON"). Returns (object, raw text of the accepted answer).
     */
    suspend fun jsonWithRepair(label: String, schema: JsonObject?, first: suspend () -> String, again: suspend (problem: String, previous: String) -> String, log: (String) -> Unit = {}): Pair<JsonObject, String> {
        var text = first()
        var obj = parseJsonObject(text)
        var missing = obj?.let { missingKeys(it, schema) } ?: emptyList()
        if (obj == null || missing.isNotEmpty()) {
            val problem = if (obj == null) "it was not a valid JSON object" else "it was missing keys $missing"
            log("$label JSON repair retry: $problem")
            text = again(problem, text.take(1000))
            obj = parseJsonObject(text)
            missing = obj?.let { missingKeys(it, schema) } ?: emptyList()
            if (obj == null || missing.isNotEmpty()) throw NodeException("$label returned invalid JSON")
        }
        return obj to text
    }

    // ---------------------------------------------------------------- v1 wrappers

    /** v1: provider id string -> resolved provider id (auto follows the default chain). */
    suspend fun resolve(ctx: ExecutionContext, provider: String): String = target(ctx.android, ctx.persistence, provider, null, null, null, ctx::log).providerId

    suspend fun complete(ctx: ExecutionContext, provider: String, req: LlmRequest): LlmResult =
        complete(ctx, target(ctx.android, ctx.persistence, provider, req.model, req.effort, req.temperature, ctx::log), req)

    // ---------------------------------------------------------------- JSON helpers (v1)

    /** Lenient JSON-object extraction for text-mode answers: strips ``` fences and any prose around the outermost {...}. */
    fun parseJsonObject(text: String): JsonObject? {
        val s = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSON.parseToJsonElement(s.substring(a, b + 1)) as? JsonObject }.getOrNull()
    }

    /** Required keys present? (Claude's structured output guarantees it, Nano / prompt-only providers do not) */
    fun missingKeys(obj: JsonObject, schema: JsonObject?): List<String> {
        val req = (schema?.get("required") as? JsonArray)?.mapNotNull { it.asTextOrNull() } ?: return emptyList()
        return req.filter { it !in obj }
    }

    /** {type:object, properties:{...}, required:[all], additionalProperties:false} from (name -> {type,...,description}). */
    fun objectSchema(props: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", buildJsonArray { props.keys.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    /** Human rendering of a schema's properties for prompt-based JSON (Nano, json_object / prompt-only tiers). */
    fun describeSchema(schema: JsonObject): String {
        val props = schema["properties"] as? JsonObject ?: return ""
        return props.entries.joinToString("\n") { (k, v) ->
            val o = v as? JsonObject
            val type = o?.get("type").asTextOrNull() ?: "string"
            val enum = (o?.get("enum") as? JsonArray)?.mapNotNull { it.asTextOrNull() }
            val desc = o?.get("description").asTextOrNull()
            buildString {
                append("- \"").append(k).append("\": ").append(type)
                if (enum != null) append(", one of ").append(enum.joinToString(" | "))
                if (!desc.isNullOrBlank()) append(" — ").append(desc)
            }
        }
    }
}
