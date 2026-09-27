package com.mob8n.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonNull as SdkJsonNull
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.MessageCreateParams
import com.mob8n.core.NodeException
import com.mob8n.core.asBool
import com.mob8n.core.asText
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Claude via anthropic-java 2.65.0, always the beta messages path (DESIGN §8.2).
 * Transcripts are plain kotlinx JSON in Claude's wire shape ({role, content:[blocks]}); the SDK response is converted
 * back to wire JSON and parsed by [parseMessage] — the same path unit tests feed fixtures through, and the same model an
 * HttpURLConnection fallback would produce.
 */
object ClaudeClient {
    const val MODEL_OPUS = "claude-opus-5"
    const val MODEL_SONNET = "claude-sonnet-5"
    const val MODEL_HAIKU = "claude-haiku-4-5"
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    /** Cached per key value; rebuilt (old one closed) when the key changes. */
    private fun client(key: String): AnthropicClient {
        cached?.let { if (it.first == key) return it.second }
        synchronized(this) {
            cached?.let { if (it.first == key) return it.second }
            val c = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(120)).maxRetries(2).build()
            cached?.second?.let { runCatching { it.close() } }
            cached = key to c
            return c
        }
    }

    /** Single-shot completion (ai.ask / classify / extract / testKey). `imageBase64` = JPEG from [Images]. */
    suspend fun complete(key: String, req: LlmRequest, imageBase64: String? = null): LlmResult {
        val turn = step(key, req.model, req.effort, req.maxTokens, req.system, listOf(userMessage(req.prompt, imageBase64)), emptyList(), req.jsonSchema, req.timeoutMs)
        return toResult(turn, req)
    }

    /**
     * Stop reason was checked in [step]; here max_tokens => partial text for text requests but an error for schema requests
     * (partial JSON is never an answer: Classify would silently route to 'other'). json parsed + required keys verified when a schema was sent.
     */
    fun toResult(turn: Turn, req: LlmRequest): LlmResult {
        val text = turn.text
        val json = if (req.jsonSchema != null) {
            if (turn.stopReason == "max_tokens") throw NodeException("Claude hit max_tokens (${req.maxTokens}) before finishing the JSON answer; raise Max tokens")
            val obj = Llm.parseJsonObject(text) ?: throw NodeException("Claude returned invalid JSON")
            Llm.missingKeys(obj, req.jsonSchema).takeIf { it.isNotEmpty() }?.let { throw NodeException("Claude JSON is missing $it") }
            obj
        } else null
        return LlmResult(text, json, turn.stopReason, PROVIDER_CLAUDE, turn.model ?: req.model, usage = turn.usage)
    }

    /**
     * One model call over a wire-shaped transcript. `tools` are NodeSpec.toolDef() objects (strict).
     * Returns the assistant [Turn]; REFUSAL is already thrown; callers switch on max_tokens / tool_use / end_turn.
     */
    suspend fun step(
        key: String, model: String, effort: String, maxTokens: Int, system: String?,
        messages: List<JsonObject>, tools: List<JsonObject> = emptyList(), jsonSchema: JsonObject? = null, timeoutMs: Long = 120_000, temperature: Double? = null,
        onDelta: OnDelta? = null,
    ): Turn {
        val params = buildParams(model, effort, maxTokens, system, messages, tools, jsonSchema, temperature)
        val response = mapClaudeErrors(key, model, timeoutMs) {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val c = client(key)
                    (if (onDelta != null && !streamBroken) streamed(c, params, onDelta) else null) ?: c.beta().messages().create(params)
                }
            }
        }
        val wire = toKx(JsonValue.from(response)) as? JsonObject ?: throw NodeException("Claude returned an unreadable response")
        return check(parseMessage(wire))
    }

    /** D7: a streaming-only failure before the first delta turns streaming off for the process. */
    @Volatile var streamBroken = false

    /**
     * createStreaming + BetaMessageAccumulator; the accumulated BetaMessage goes through the same toKx/parseMessage as create().
     * HTTP/API errors (thrown by createStreaming, or an SSE error event) are real errors; any other failure while reading returns null
     * (the caller redoes with create()): before the first delta -> [streamBroken], after -> [StreamDelta.Reset]. Cancellation closes the stream.
     */
    private suspend fun streamed(c: AnthropicClient, params: MessageCreateParams, onDelta: OnDelta): BetaMessage? {
        val sr = c.beta().messages().createStreaming(params)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { sr.close() } }
            var emitted = false
            val r: Result<BetaMessage?> = try {
                sr.use {
                    val acc = BetaMessageAccumulator.create()
                    val it = sr.stream().iterator()
                    while (it.hasNext()) {
                        val ev = it.next()
                        acc.accumulate(ev)
                        for (d in ClaudeStream.deltas(ev)) { emitted = true; onDelta(d) }
                    }
                    Result.success(acc.message())
                }
            } catch (e: AnthropicServiceException) {
                Result.failure(e)
            } catch (e: Exception) {
                if (!cont.isActive) Result.failure(e)                                  // cancelled: the close caused this
                else { if (emitted) onDelta(StreamDelta.Reset) else streamBroken = true; Result.success(null) }
            }
            r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    }

    /** One error mapping for the streaming and non-streaming paths (identical messages). */
    private inline fun <T> mapClaudeErrors(key: String, model: String, timeoutMs: Long, block: () -> T): T =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            throw NodeException("Claude timed out after ${timeoutMs / 1000} s")
        } catch (e: RateLimitException) {
            throw NodeException("Claude: rate limited, retry later", e)
        } catch (e: UnauthorizedException) {
            throw NodeException("Invalid Claude API key (Settings > AI)", e)
        } catch (e: PermissionDeniedException) {
            throw NodeException("Claude API key is not allowed to use $model", e)
        } catch (e: NotFoundException) {
            throw NodeException("Model not found: $model", e)
        } catch (e: BadRequestException) {
            throw NodeException("Claude rejected the request: ${clean(e.message, key)}", e)
        } catch (e: AnthropicServiceException) {
            throw NodeException("Claude error ${e.statusCode()} ${e.errorType().orElse(null) ?: ""}: ${clean(e.message, key)}".trim(), e)
        } catch (e: AnthropicIoException) {
            throw NodeException("Network error reaching Claude: ${clean(e.message, key)}", e)
        } catch (e: IOException) {
            throw NodeException("Network error reaching Claude: ${clean(e.message, key)}", e)
        } catch (e: AnthropicException) {
            throw NodeException("Claude client error: ${clean(e.message, key)}", e)
        } catch (e: NodeException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NodeException("Claude call failed: ${clean(e.message ?: e.javaClass.simpleName, key)}", e)
        }

    /** Wire JSON -> Turn (used for SDK responses, fixtures and any HTTP fallback). `usage` read here (V7: input_tokens is already the uncached remainder). */
    fun parseMessage(m: JsonObject): Turn {
        val stop = m["stop_reason"].asTextOrNull() ?: "end_turn"
        val content = m["content"] as? JsonArray ?: JsonArray(emptyList())
        val details = (m["stop_details"] as? JsonObject)?.let { d ->
            listOfNotNull(d["category"].asTextOrNull(), d["explanation"].asTextOrNull()).joinToString(": ")
        }?.ifBlank { null }
        return Turn(stop, content, details, model = m["model"].asTextOrNull(), usage = Usage.fromClaude(m["usage"] as? JsonObject))
    }

    /** stop_reason is checked FIRST: refusal is never treated as an answer. */
    fun check(turn: Turn): Turn {
        if (turn.stopReason == "refusal") throw NodeException("Claude declined" + (turn.stopDetails?.let { ": $it" } ?: ""))
        return turn
    }

    // ---- wire-shape builders shared with Agent ----
    fun userMessage(text: String, imageBase64: String? = null): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            if (imageBase64 != null) add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject { put("type", "base64"); put("media_type", "image/jpeg"); put("data", imageBase64) })
            })
            add(buildJsonObject { put("type", "text"); put("text", text) })
        })
    }

    /** With an image the block's content is [{type:text,text},{type:image,source:{base64,<mediaType>}}] (app.ui_screenshot results, MCP image blocks). */
    fun toolResultBlock(toolUseId: String, content: String, isError: Boolean, imageBase64: String? = null, mediaType: String = "image/jpeg"): JsonObject = buildJsonObject {
        put("type", "tool_result"); put("tool_use_id", toolUseId); put("is_error", isError)
        if (imageBase64 == null) put("content", content)
        else put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", content) })
            add(buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", mediaType); put("data", imageBase64) }) })
        })
    }

    fun userMessage(blocks: List<JsonObject>): JsonObject = buildJsonObject { put("role", "user"); put("content", JsonArray(blocks)) }

    // ---- SDK request ----
    internal fun buildParams(
        model: String, effort: String, maxTokens: Int, system: String?, messages: List<JsonObject>, tools: List<JsonObject>, jsonSchema: JsonObject?, temperature: Double? = null,
    ): MessageCreateParams {
        val b = MessageCreateParams.builder().model(model).maxTokens(maxTokens.toLong())
        if (!system.isNullOrBlank()) b.system(system)
        if (temperature != null) b.temperature(temperature)
        // `_oai` = an OpenAI-compatible provider's echoed message (a transcript resumed under a different Default AI); never part of Claude's wire.
        messages.forEach { m -> b.addMessage(jv(JsonObject(m.filterKeys { it != OpenAiCompat.RAW_KEY })).convert(BetaMessageParam::class.java) ?: throw NodeException("Unreadable transcript message")) }
        // Never `thinking` / `budget_tokens` (adaptive thinking is the default on opus-5/sonnet-5; haiku has none).
        val oc = BetaOutputConfig.builder()
        var hasOc = false
        if (model != MODEL_HAIKU) { oc.effort(BetaOutputConfig.Effort.of(if (effort in AiPrefs.EFFORTS) effort else "high")); hasOc = true }
        if (jsonSchema != null) {
            val schema = BetaJsonOutputFormat.Schema.builder().apply { jsonSchema.forEach { (k, v) -> putAdditionalProperty(k, jv(v)) } }.build()
            oc.format(BetaJsonOutputFormat.builder().schema(schema).build()); hasOc = true
        }
        if (hasOc) b.outputConfig(oc.build())
        tools.forEach { b.addTool(BetaToolUnion.ofBetaTool(tool(it))) }
        if (model == MODEL_OPUS) { b.addBeta(FALLBACK_BETA); b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default")) }
        return b.build()
    }

    /** strict (node tools, v2 byte-identical): required = all keys + additionalProperties:false. strict:false (MCP): the schema's own required/additionalProperties, nothing injected. */
    internal fun tool(def: JsonObject): BetaTool {
        val schema = def["input_schema"] as? JsonObject ?: JsonObject(emptyMap())
        val strict = def["strict"].asBool() ?: true
        val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val own = (schema["required"] as? JsonArray)?.mapNotNull { it.asTextOrNull() }
        val is0 = BetaTool.InputSchema.builder()
            .properties(BetaTool.InputSchema.Properties.builder().apply { props.forEach { (k, v) -> putAdditionalProperty(k, jv(v)) } }.build())
        if (strict) is0.required(own ?: props.keys.toList()).putAdditionalProperty("additionalProperties", JsonValue.from(false))
        else {
            if (own != null) is0.required(own)
            schema.forEach { (k, v) -> if (k != "type" && k != "properties" && k != "required") is0.putAdditionalProperty(k, jv(v)) }
        }
        return BetaTool.builder().name(def["name"].asText()).description(def["description"].asText()).strict(strict).inputSchema(is0.build()).build()
    }

    // ---- JSON bridges (kotlinx <-> SDK JsonValue) ----
    fun toJava(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, toJava(v)) } }
        is JsonArray -> e.map { toJava(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
    }

    fun jv(e: JsonElement): JsonValue = toJava(e)?.let { JsonValue.from(it) } ?: SdkJsonNull.of()

    fun toKx(v: JsonValue): JsonElement = v.accept(object : JsonValue.Visitor<JsonElement> {
        override fun visitNull(): JsonElement = JsonNull
        override fun visitMissing(): JsonElement = JsonNull
        override fun visitBoolean(value: Boolean): JsonElement = JsonPrimitive(value)
        override fun visitNumber(value: Number): JsonElement = JsonPrimitive(value)
        override fun visitString(value: String): JsonElement = JsonPrimitive(value)
        override fun visitArray(values: List<JsonValue>): JsonElement = JsonArray(values.map { toKx(it) })
        override fun visitObject(values: Map<String, JsonValue>): JsonElement = JsonObject(values.mapValues { toKx(it.value) })
        override fun visitDefault(): JsonElement = JsonNull
    })

    /** Error text safe for logs/UI: never the key, never a wall of body. */
    private fun clean(msg: String?, key: String): String = (msg ?: "").replace(key, "***").replace(Regex("sk-ant-[A-Za-z0-9_-]+"), "***").take(300)
}

/** Pure mapping of one stream event to UI deltas (tool input JSON is reported by size only). */
object ClaudeStream {
    fun deltas(ev: BetaRawMessageStreamEvent): List<StreamDelta> = when {
        ev.isContentBlockStart() -> ev.asContentBlockStart().let { s ->
            val b = s.contentBlock()
            if (b.isToolUse()) b.asToolUse().let { listOf(StreamDelta.ToolStart(s.index().toInt(), it.id(), it.name())) } else emptyList()
        }
        ev.isContentBlockDelta() -> ev.asContentBlockDelta().let { e ->
            val d = e.delta()
            when {
                d.isText() -> listOf(StreamDelta.Text(d.asText().text()))
                d.isThinking() -> listOf(StreamDelta.Thinking(d.asThinking().thinking()))
                d.isInputJson() -> listOf(StreamDelta.ToolArgs(e.index().toInt(), d.asInputJson().partialJson().length))
                else -> emptyList()
            }
        }
        else -> emptyList()
    }
}
