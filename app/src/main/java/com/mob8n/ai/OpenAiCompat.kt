package com.mob8n.ai

import android.util.Log
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.NodeException
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ONE OpenAI-compatible chat-completions client (DESIGN2 §3.2, §4): java.net.HttpURLConnection + kotlinx JSON, driven by [Providers].
 * The Agent transcript stays in Claude wire shape; [toOpenAiMessages] / [parseResponse] translate at request time (V2).
 * Never sends tool_choice, n, logprobs, logit_bias, parallel_tool_calls, messages[].name or reasoning_effort (V3).
 */
object OpenAiCompat {
    /** Key under which an assistant message stores the provider's own message object (echoed verbatim; MiniMax needs its reasoning chain). */
    const val RAW_KEY = "_oai"
    private const val CONNECT_MS = 20_000
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private const val RETRY_MS = 2_000L
    private const val STREAM_IDLE_MS = 60_000L
    private const val MAX_STREAM_CHARS = 8 * 1024 * 1024
    private const val JSON_SUFFIX = "\n\nRespond with ONLY a JSON object (no prose, no code fences)"

    // ponytail: one 400-downgrade retry cached per provider id for the process lifetime; upgrade = per model
    val quirks = ConcurrentHashMap<String, MutableSet<String>>()
    fun quirksOf(id: String): Set<String> = quirks[id]?.toSet() ?: emptySet()
    private fun addQuirk(id: String, q: String) { quirks.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(q) }

    /** Non-2xx status with its body; mapped to NodeException at the API boundary. */
    internal class HttpFailure(val status: Int, val body: String?) : Exception("HTTP $status")

    // ---------------------------------------------------------------- public suspend API

    /** Single shot (ai.ask/classify/extract, Test, Build with AI). imageBase64 = JPEG from Images. Handles the structured-output tiers + one repair retry. */
    suspend fun complete(p: Provider, key: String?, req: LlmRequest, imageBase64: String?, log: (String) -> Unit): LlmResult {
        if (p.needsKey && key.isNullOrBlank()) throw Llm.noKey(p.label)
        if (imageBase64 != null && !p.vision) throw NodeException("${p.label} (${req.model}) does not accept images — remove Image URI or pick a vision-capable provider")
        val schema = req.jsonSchema
        var stop = "end_turn"; var model = req.model
        var usage: TokenUsage? = null                                             // summed over the repair retry (V7)
        suspend fun ask(prompt: String): String {
            val turn = call(p, key, req.model, req.maxTokens, req.temperature, req.system, listOf(ClaudeClient.userMessage(prompt, imageBase64)), emptyList(), schema, req.jsonMode, req.timeoutMs, log)
            stop = turn.stopReason; model = turn.model ?: req.model
            usage = turn.usage?.let { u -> usage?.plus(u) ?: u } ?: usage
            if (schema != null && turn.stopReason == "max_tokens") throw NodeException("${p.label} hit max_tokens (${req.maxTokens}) before finishing the JSON answer; raise Max tokens")
            return turn.text
        }
        if (schema == null && !req.jsonMode) return LlmResult(ask(req.prompt), null, stop, p.id, model, usage = usage)
        val again: suspend (String, String) -> String = { problem, previous ->
            ask(req.prompt + "\n\nYour previous answer was rejected because $problem. Previous answer:\n$previous\n\nRespond with ONLY the JSON object.")
        }
        if (schema != null) {
            val (obj, text) = Llm.jsonWithRepair(p.label, schema, { ask(req.prompt) }, again, log)
            return LlmResult(text, obj, stop, p.id, model, usage = usage)
        }
        // jsonMode without a schema (ai.ask json, Build with AI): lenient — one repair, then the caller decides what an unparsable answer means.
        var text = ask(req.prompt)
        var obj = Llm.parseJsonObject(text)
        if (obj == null && stop != "max_tokens") {
            log("${p.label} JSON repair retry: not a JSON object")
            text = again("it was not a valid JSON object", text.take(1000)); obj = Llm.parseJsonObject(text)
        }
        return LlmResult(text, obj, stop, p.id, model, usage = usage)
    }

    /** One model call over a CLAUDE-wire transcript (Agent). tools = NodeSpec.toolDef() objects. Returns a Claude-shaped Turn. */
    suspend fun step(
        p: Provider, key: String?, model: String, maxTokens: Int, temperature: Double?, system: String?,
        messages: List<JsonObject>, tools: List<JsonObject> = emptyList(), jsonMode: Boolean = false, timeoutMs: Long = 120_000, log: (String) -> Unit = {},
        onDelta: OnDelta? = null,
    ): Turn {
        if (p.needsKey && key.isNullOrBlank()) throw Llm.noKey(p.label)
        if (tools.isNotEmpty() && !p.tools) throw NodeException("${p.label} does not support tool calling — choose another provider for the Agent")
        return call(p, key, model, maxTokens, temperature, system, messages, tools, null, jsonMode, timeoutMs, log, onDelta)
    }

    /** GET baseUrl+modelsPath -> data[].id, sorted; Gemini "models/" prefix stripped; Together keeps namespaced ids; 404 -> static list when the table has one. */
    suspend fun listModels(p: Provider, key: String?, timeoutMs: Long = 20_000): List<String> {
        if (p.needsKey && key.isNullOrBlank()) throw Llm.noKey(p.label)
        if (p.baseUrl.isBlank()) throw NodeException("Set the base URL for ${p.label} (Settings > AI)")
        val (status, text) = exchange(p, key, "GET", p.baseUrl + p.modelsPath, null, timeoutMs)
        if (status == 404 && p.staticModels.isNotEmpty()) return p.staticModels
        if (status == 404) throw NodeException("${p.label} has no models endpoint at ${p.baseUrl}${p.modelsPath} (404) — type the model name instead")
        if (status !in 200..299) throw NodeException(errorMessage(p, status, text, "", key))
        val body = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw NodeException("${p.label} returned an unreadable models list")
        val rows = (body["data"] as? JsonArray) ?: (body["models"] as? JsonArray) ?: JsonArray(emptyList())
        val ids = rows.filterIsInstance<JsonObject>().mapNotNull { (it["id"] ?: it["name"]).asTextOrNull()?.removePrefix("models/") }.filter { it.isNotBlank() }.distinct().sorted()
        return if (ids.isEmpty() && p.staticModels.isNotEmpty()) p.staticModels else ids
    }

    // ---------------------------------------------------------------- the request loop (retry + 400 downgrade)

    private suspend fun call(
        p: Provider, key: String?, model: String, maxTokens: Int, temperature: Double?, system: String?,
        messages: List<JsonObject>, tools: List<JsonObject>, schema: JsonObject?, jsonMode: Boolean, timeoutMs: Long, log: (String) -> Unit,
        onDelta: OnDelta? = null,
    ): Turn {
        if (p.baseUrl.isBlank()) throw NodeException("Set the base URL for ${p.label} (Settings > AI)")
        var downgraded = false
        var streamOff = false                                                     // D7: this call redoes without streaming
        while (true) {
            val q = quirksOf(p.id)
            val tier = tier(p, schema, jsonMode, q)
            val stream = onDelta != null && !streamOff && tier == 0 && "no_stream" !in q   // json/schema calls never stream
            val msgs = if (tier >= 2) withSuffix(messages, jsonSuffix(schema)) else messages
            val body = buildBody(p, model, maxTokens, temperature, toOpenAiMessages(system, msgs), tools, p.strictTools, schema, jsonMode, q, stream)
            log("POST ${p.baseUrl}${p.chatPath} model=$model tools=${tools.size} json=${tierName(tier)}" + if (stream) " stream" else "")
            val t0 = System.currentTimeMillis()
            val acc = if (stream) OpenAiStreamAccumulator(p.label, tolerateCumulative = p.id == "minimax") else null
            val r = try {
                exchange(p, key, "POST", p.baseUrl + p.chatPath, body.toString(), timeoutMs,
                    acc?.let { a -> { input: InputStream -> readSse(input.bufferedReader(Charsets.UTF_8).lineSequence(), a, onDelta!!) } })
            } catch (e: StreamFailure) {
                val cause = e.cause ?: e
                when (streamFailureAction(acc?.emittedAny == true, cause)) {
                    Fallback.THROW -> throw if (cause is NodeException) NodeException(clean(cause.message, key), cause.cause) else cause
                    Fallback.CACHE_AND_REDO -> addQuirk(p.id, "no_stream")
                    Fallback.RESET_AND_REDO -> onDelta?.invoke(StreamDelta.Reset)
                }
                log("${p.label} streaming failed (${clean(e.message, key)}); retrying without streaming")
                streamOff = true; continue
            }
            val (status, text) = r
            if (status == 400 && !downgraded) {
                val quirk = quirkFor400(text, sentFeatures(body))
                if (quirk != null) { addQuirk(p.id, quirk); downgraded = true; log("${p.label} rejected the request; retrying with $quirk"); continue }
            }
            if (status !in 200..299) throw NodeException(errorMessage(p, status, text, model, key))
            val json = if (r.sse) acc!!.toResponse() else {
                if (stream) { addQuirk(p.id, "no_stream"); log("${p.label} answered without streaming; streaming off for this provider") }   // 200 but not text/event-stream
                runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: if (stream) { log("${p.label} streaming failed (unreadable body); retrying without streaming"); streamOff = true; continue }
                    else throw NodeException("${p.label} returned an unreadable response")
            }
            val turn = parseResponse(p, json, model)
            if (debugLog && p.id == "minimax") runCatching { Log.d(LOG_TAG, minimaxDebugLine(body, turn.text)) }   // M1 A/B: never the key, never the full content
            // One-time per provider: a `usage` object without prompt_tokens is the fix path for exotic counters (DESIGN4 §6.4).
            (json["usage"] as? JsonObject)?.takeIf { turn.usage == null && usageKeysLogged.add(p.id) }?.let { log("${p.label} usage keys=${it.keys}") }
            log("${p.label} ${finishOf(json)} ${System.currentTimeMillis() - t0} ms")
            return turn
        }
    }

    /** Set once from Mob8NApp: true only on a debuggable build (no BuildConfig in this app). Gates the MiniMax A/B line. */
    @Volatile var debugLog: Boolean = false

    /** "minimax split=<b> first40=<first 40 chars of the visible answer, newlines escaped>" (pure; M1 A/B). */
    fun minimaxDebugLine(body: JsonObject, visible: String): String =
        "minimax split=${body["reasoning_split"].asTextOrNull() ?: "unset"} first40=${visible.take(40).replace("\n", "\\n")}"

    private fun finishOf(body: JsonObject): String = ((body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("finish_reason").asTextOrNull() ?: "?"
    private fun tierName(t: Int) = when (t) { 1 -> "json_schema"; 2 -> "json_object"; 3 -> "prompt"; else -> "none" }

    /**
     * One HTTP exchange with exactly one retry (after 2 s or Retry-After <= 10 s) on 429/502/503/IOException.
     * Returns (status, body) for every status; network failures become NodeException. Cancellation disconnects the socket.
     */
    private suspend fun exchange(p: Provider, key: String?, method: String, url: String, body: String?, timeoutMs: Long, sse: ((InputStream) -> Unit)? = null): Response {
        var attempt = 0
        while (true) {
            attempt++
            val r = try {
                raw(p, key, method, url, body, timeoutMs, sse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw NodeException("${p.label} timed out after ${timeoutMs / 1000} s", e)
            } catch (e: IOException) {
                if (attempt == 1) { delay(RETRY_MS); continue }
                throw NodeException(networkMessage(p, e, url, key), e)
            }
            if (attempt == 1 && (r.status == 429 || r.status == 502 || r.status == 503)) {
                delay(r.retryAfterMs ?: RETRY_MS); continue
            }
            return r
        }
    }

    /** `sse` = the body was a text/event-stream consumed by the reader (the accumulator holds the answer; `body` is empty). */
    private data class Response(val status: Int, val body: String, val retryAfterMs: Long?, val sse: Boolean = false)
    private val usageKeysLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** `sse` != null: streaming request — a 200 text/event-stream body goes to `sse` on this IO thread; every failure after that point is a [StreamFailure]. */
    private suspend fun raw(p: Provider, key: String?, method: String, url: String, body: String?, timeoutMs: Long, sse: ((InputStream) -> Unit)? = null): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val conn = URL(url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            try {
                conn.requestMethod = method
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = (if (sse != null) minOf(timeoutMs, STREAM_IDLE_MS) else timeoutMs).coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt()   // streaming: idle gap between chunks
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", if (sse != null) "text/event-stream" else "application/json")
                if (!key.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $key")   // never logged
                p.extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
                val status = try { conn.responseCode } catch (e: SocketTimeoutException) { if (sse != null) throw StreamFailure("no response within ${conn.readTimeout / 1000} s", e) else throw e }
                if (sse != null && status == 200 && conn.contentType?.lowercase()?.startsWith("text/event-stream") == true) {
                    try { conn.inputStream.use { sse(it) } }
                    catch (e: StreamFailure) { throw e }
                    catch (e: Exception) { throw StreamFailure(e.message ?: e.javaClass.simpleName, e) }
                    cont.resume(Response(200, "", null, sse = true))
                    return@suspendCancellableCoroutine
                }
                val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { readCapped(it) } ?: ""
                val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..10 }?.let { it * 1000 }
                cont.resume(Response(status, text, retryAfter))
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun readCapped(s: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf); if (n < 0) break
            if (out.size() + n > MAX_BODY_BYTES) { out.write(buf, 0, MAX_BODY_BYTES - out.size()); break }
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** SSE lines -> accumulator + deltas. [StreamFailure] on > 3 unreadable chunks, > 8 MB, or EOF without [DONE] and without finish_reason. */
    internal fun readSse(lines: Sequence<String>, acc: OpenAiStreamAccumulator, onDelta: OnDelta) {
        var total = 0L; var bad = 0; var done = false
        val capped = lines.onEach { total += it.length + 1; if (total > MAX_STREAM_CHARS) throw StreamFailure("stream larger than ${MAX_STREAM_CHARS / (1024 * 1024)} MB") }
        for (data in Sse.events(capped)) {
            if (data.trim() == "[DONE]") { done = true; break }
            // a server that omits the blank line between events: the joined payload splits back into its lines
            val chunks = parseChunk(data)?.let { listOf(it) } ?: data.lines().map { parseChunk(it) }.takeIf { l -> l.size > 1 && l.all { it != null } }?.filterNotNull()
            if (chunks == null) { if (++bad > 3) throw StreamFailure("unreadable stream chunks"); continue }
            for (c in chunks) acc.feed(c).forEach(onDelta)
        }
        if (!done && !acc.finished) throw StreamFailure("stream ended early")
    }

    private fun parseChunk(s: String): JsonObject? = runCatching { JSON.parseToJsonElement(s) as? JsonObject }.getOrNull()

    private fun networkMessage(p: Provider, e: IOException, url: String, key: String?): String {
        val host = runCatching { URL(url).host }.getOrNull() ?: p.baseUrl
        return when (e) {
            is UnknownHostException, is ConnectException, is SSLException ->
                "Cannot reach ${p.label} at $host — check network / base URL" + if (p.id == "ollama") " — is Ollama running and reachable on this Wi-Fi?" else ""
            else -> "Network error reaching ${p.label}: ${clean(e.message ?: e.javaClass.simpleName, key)}"
        }
    }

    // ---------------------------------------------------------------- pure: tiers + prompt suffix

    /** 0 = plain text, 1 = response_format json_schema, 2 = response_format json_object (+ described keys), 3 = prompt-only (MiniMax). */
    fun tier(p: Provider, schema: JsonObject?, jsonMode: Boolean, quirks: Set<String>): Int = when {
        schema == null && !jsonMode -> 0
        schema != null && p.jsonSchema && "no_json_schema" !in quirks -> 1
        p.jsonObject && "no_json_object" !in quirks -> 2
        else -> 3
    }

    /** Prompt suffix used by tiers 2 and 3 (DeepSeek's json_object also needs the word "json" in the prompt). */
    fun jsonSuffix(schema: JsonObject?): String =
        if (schema != null) "$JSON_SUFFIX with exactly these keys:\n${Llm.describeSchema(schema)}" else "$JSON_SUFFIX."

    /** Appends the suffix to the text of the LAST user message (the prompt), leaving everything else untouched. */
    fun withSuffix(messages: List<JsonObject>, suffix: String): List<JsonObject> {
        val i = messages.indexOfLast { it["role"].asTextOrNull() == "user" }
        if (i < 0) return messages
        val m = messages[i]
        val blocks = blocksOf(m)
        val last = blocks.indexOfLast { it["type"].asTextOrNull() == "text" }
        val newBlocks = if (last < 0) blocks + textBlock(suffix.trimStart()) else blocks.mapIndexed { j, b -> if (j == last) JsonObject(b + ("text" to JsonPrimitive((b["text"].asTextOrNull() ?: "") + suffix))) else b }
        return messages.toMutableList().also { it[i] = JsonObject(m + ("content" to JsonArray(newBlocks))) }
    }

    private fun textBlock(s: String): JsonObject = buildJsonObject { put("type", "text"); put("text", s) }

    // ---------------------------------------------------------------- pure: Claude wire -> OpenAI messages (§4.3)

    private fun blocksOf(m: JsonObject): List<JsonObject> = when (val c = m["content"]) {
        is JsonArray -> c.filterIsInstance<JsonObject>()
        is JsonPrimitive -> listOf(textBlock(c.content))
        else -> emptyList()
    }

    fun toOpenAiMessages(system: String?, wire: List<JsonObject>): JsonArray = buildJsonArray {
        if (!system.isNullOrBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
        for (m in wire) {
            val role = m["role"].asTextOrNull() ?: "user"
            if (role == "assistant") {
                val raw = m[RAW_KEY] as? JsonObject
                if (raw != null) { add(echo(raw)); continue }
                val blocks = blocksOf(m)
                val text = blocks.filter { it["type"].asTextOrNull() == "text" }.joinToString("") { it["text"].asTextOrNull() ?: "" }
                val calls = blocks.filter { it["type"].asTextOrNull() == "tool_use" }
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", if (text.isEmpty() && calls.isNotEmpty()) JsonNull else JsonPrimitive(text))
                    if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls.map { tu ->
                        buildJsonObject {
                            put("id", tu["id"].asTextOrNull() ?: ""); put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tu["name"].asTextOrNull() ?: "")
                                put("arguments", JSON.encodeToString(JsonElement.serializer(), tu["input"] ?: JsonObject(emptyMap())))
                            })
                        }
                    }))
                })
                continue
            }
            val blocks = blocksOf(m)
            val results = blocks.filter { it["type"].asTextOrNull() == "tool_result" }
            val others = blocks.filter { it["type"].asTextOrNull() != "tool_result" }
            val followUps = ArrayList<JsonObject>()
            for (r in results) {
                val id = r["tool_use_id"].asTextOrNull() ?: ""
                val inner = when (val c = r["content"]) { is JsonArray -> c.filterIsInstance<JsonObject>(); is JsonPrimitive -> listOf(textBlock(c.content)); else -> emptyList() }
                var text = inner.filter { it["type"].asTextOrNull() == "text" }.joinToString("\n") { it["text"].asTextOrNull() ?: "" }
                val images = inner.filter { it["type"].asTextOrNull() == "image" }
                if (r["is_error"].asTextOrNull() == "true") text = "ERROR: $text"
                if (images.isNotEmpty()) {
                    text += " (screenshot attached in the next message)"
                    followUps += userParts(listOf(textBlock("Screenshot from tool call $id")) + images)
                }
                add(buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", text) })   // never `name` (Groq 400)
            }
            followUps.forEach { add(it) }
            if (others.isNotEmpty()) add(userParts(others))
        }
    }

    /** Provider message echoed back: null-valued fields and `annotations` dropped (OpenAI rejects unknown message keys), reasoning fields kept. */
    private fun echo(raw: JsonObject): JsonObject = buildJsonObject {
        put("role", "assistant")
        raw.forEach { (k, v) -> if (k != "role" && k != "annotations" && !(v is JsonNull && k != "content")) put(k, v) }
        if (!raw.containsKey("content")) put("content", JsonNull)
    }

    private fun userParts(blocks: List<JsonObject>): JsonObject {
        val texts = blocks.filter { it["type"].asTextOrNull() == "text" }
        val hasImage = blocks.any { it["type"].asTextOrNull() == "image" }
        if (!hasImage) return buildJsonObject { put("role", "user"); put("content", texts.joinToString("\n") { it["text"].asTextOrNull() ?: "" }) }
        return buildJsonObject {
            put("role", "user")
            put("content", JsonArray(blocks.mapNotNull { b ->
                when (b["type"].asTextOrNull()) {
                    "text" -> buildJsonObject { put("type", "text"); put("text", b["text"].asTextOrNull() ?: "") }
                    "image" -> {
                        val src = b["source"] as? JsonObject
                        val media = src?.get("media_type").asTextOrNull() ?: "image/jpeg"
                        val data = src?.get("data").asTextOrNull() ?: ""
                        buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:$media;base64,$data") }) }
                    }
                    else -> null
                }
            }))
        }
    }

    // ---------------------------------------------------------------- pure: request body (§4.2)

    fun toolDefToOpenAi(def: JsonObject, strict: Boolean): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", def["name"].asTextOrNull() ?: "")
            put("description", def["description"].asTextOrNull() ?: "")
            put("parameters", def["input_schema"] ?: buildJsonObject { put("type", "object") })
            if (strict && def["strict"] != JsonPrimitive(false)) put("strict", true)   // MCP defs (strict:false) are never sent strict
        })
    }

    fun buildBody(
        p: Provider, model: String, maxTokens: Int, temperature: Double?, messages: JsonArray, tools: List<JsonObject>, strict: Boolean,
        jsonSchema: JsonObject?, jsonObject: Boolean, quirks: Set<String>, stream: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", messages)
        val other = if (p.maxTokensParam == "max_tokens") "max_completion_tokens" else "max_tokens"
        put(if ("max_tokens_swap" in quirks) other else p.maxTokensParam, maxTokens)
        put("stream", stream)
        if (stream && "no_stream_options" !in quirks) put("stream_options", buildJsonObject { put("include_usage", true) })
        if (temperature != null && "no_temperature" !in quirks) put("temperature", if (p.id == "minimax") temperature.coerceIn(0.01, 2.0) else temperature)
        if (tools.isNotEmpty()) put("tools", JsonArray(tools.map { toolDefToOpenAi(it, strict && p.strictTools && "no_strict" !in quirks) }))
        when (tier(p, jsonSchema, jsonObject, quirks)) {
            1 -> put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject { put("name", "result"); put("strict", true); put("schema", jsonSchema!!) })
            })
            2 -> put("response_format", buildJsonObject { put("type", "json_object") })
        }
        p.extraBody.forEach { (k, v) -> put(k, v) }
    }

    /** Which downgradable features a body carries (input of [quirkFor400]). */
    fun sentFeatures(body: JsonObject): Set<String> {
        val s = HashSet<String>()
        if ((body["tools"] as? JsonArray)?.any { ((it as? JsonObject)?.get("function") as? JsonObject)?.get("strict").asTextOrNull() == "true" } == true) s += "strict"
        (body["response_format"] as? JsonObject)?.get("type").asTextOrNull()?.let { s += it }
        if (body.containsKey("max_tokens")) s += "max_tokens"
        if (body.containsKey("max_completion_tokens")) s += "max_completion_tokens"
        if (body.containsKey("temperature")) s += "temperature"
        if (body["stream"] == JsonPrimitive(true)) s += "stream"
        if (body.containsKey("stream_options")) s += "stream_options"
        return s
    }

    /** "no_strict" | "no_json_schema" | "no_json_object" | "max_tokens_swap" | "no_temperature" | "no_stream_options" | "no_stream" | null — only for features that were actually sent. */
    fun quirkFor400(body: String, sent: Set<String>): String? {
        val b = body.lowercase()
        return when {
            "strict" in sent && b.contains("strict") -> "no_strict"
            "json_schema" in sent && (b.contains("json_schema") || b.contains("response_format")) -> "no_json_schema"
            "json_object" in sent && (b.contains("json_object") || b.contains("response_format")) -> "no_json_object"
            ("max_tokens" in sent || "max_completion_tokens" in sent) && (b.contains("max_tokens") || b.contains("max_completion_tokens")) -> "max_tokens_swap"
            "temperature" in sent && b.contains("temperature") -> "no_temperature"
            "stream_options" in sent && b.contains("stream_options") -> "no_stream_options"
            "stream" in sent && STREAM_WORD.containsMatchIn(b) -> "no_stream"
            else -> null
        }
    }

    private val STREAM_WORD = Regex("\\bstream\\b")   // not "upstream", not "stream_options"

    // ---------------------------------------------------------------- pure: response (§4.4)

    /** choices[0].message -> Claude-shaped Turn. Throws NodeException on refusal / content_filter / MiniMax base_resp error. */
    fun parseResponse(p: Provider, body: JsonObject, requestedModel: String): Turn {
        (body["base_resp"] as? JsonObject)?.let { br ->
            val code = br["status_code"].asTextOrNull()?.toIntOrNull() ?: 0
            if (code != 0) throw NodeException(minimaxError(p.label, code, br["status_msg"].asTextOrNull()))
        }
        val choice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw NodeException("${p.label} returned no choices")
        val msg = choice["message"] as? JsonObject ?: throw NodeException("${p.label} returned no message")
        msg["refusal"].asTextOrNull()?.takeIf { it.isNotBlank() }?.let { throw NodeException("${p.label} declined: ${it.take(300)}") }
        val finish = choice["finish_reason"].asTextOrNull()
        when (finish) {
            "content_filter" -> throw NodeException("${p.label} blocked the response (content filter)")
            "insufficient_system_resource" -> throw NodeException("${p.label}: server overloaded (insufficient_system_resource), retry later")
            "aborted" -> throw NodeException("${p.label} aborted the response, retry")
        }
        val rawText = when (val c = msg["content"]) {
            is JsonPrimitive -> if (c is JsonNull) "" else c.content
            is JsonArray -> c.filterIsInstance<JsonObject>().filter { it["type"].asTextOrNull() == "text" }.joinToString("") { it["text"].asTextOrNull() ?: "" }
            else -> ""
        }
        val calls = (msg["tool_calls"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        val text = if (p.id == "minimax") repairGluedAnswer(reasoningOf(msg, rawText), rawText.replace(THINK, "").replace(THINK_LEADING_OPEN, ""), inline = "think>" in rawText)
            else stripThink(rawText)
        val content = buildJsonArray {
            if (text.isNotEmpty() || calls.isEmpty()) add(textBlock(text))
            calls.forEachIndexed { i, tc ->
                val fn = tc["function"] as? JsonObject
                val input: JsonObject = when (val a = fn?.get("arguments")) {
                    is JsonObject -> a                                                     // Gemini quirk: already an object
                    is JsonPrimitive -> if (a is JsonNull || a.content.isBlank()) JsonObject(emptyMap())
                        else runCatching { JSON.parseToJsonElement(a.content) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())   // invalid -> {} -> "Invalid tool input" result, never dropped
                    else -> JsonObject(emptyMap())
                }
                add(buildJsonObject {
                    put("type", "tool_use"); put("id", tc["id"].asTextOrNull()?.ifBlank { null } ?: "call_${i + 1}")
                    put("name", fn?.get("name").asTextOrNull() ?: ""); put("input", input)
                })
            }
        }
        val stop = when {
            finish == "length" -> "max_tokens"                    // partial turn: the loop never runs its tools
            finish == "tool_calls" || calls.isNotEmpty() -> "tool_use"
            else -> "end_turn"
        }
        return Turn(stop, content, null, raw = msg, model = body["model"].asTextOrNull() ?: requestedModel, usage = Usage.fromOpenAi(body["usage"] as? JsonObject))
    }

    private val THINK = Regex("(?s)<(mm:)?think>.*?</(mm:)?think>|</(mm:)?think>")   // MiniMax-M3 with reasoning_split emits a lone closing </think> or </mm:think> before its answer (device phase)
    private val THINK_LEADING_OPEN = Regex("(?s)^\\s*<(mm:)?think>.*$")                 // truncated (max_tokens) inline thinking: nothing after it is an answer

    /** Removes <think>…</think> blocks (MiniMax with reasoning_split=false, DeepSeek-R1 via Ollama), stray closing tags and a leading unclosed block, trims. */
    fun stripThink(text: String): String = text.replace(THINK, "").replace(THINK_LEADING_OPEN, "").trim()

    private val INLINE_THINK = Regex("(?s)<(?:mm:)?think>(.*?)</(?:mm:)?think>")
    // Glued token: sentence punctuation, then (no space) a capitalised fragment of <= 12 letters (letters only: a closing quote after it never matches).
    private val GLUED = Regex("[.!?:)\"'\u201D](\\p{Lu}\\p{L}{0,11})$")
    private val ONLY_PUNCT = Regex("^[.!?,;:\\s]+$")

    /** The model's reasoning for this message: reasoning_content, reasoning_details[].text, reasoning, else the last inline <think> block. */
    internal fun reasoningOf(msg: JsonObject, content: String): String =
        msg["reasoning_content"].asTextOrNull()?.takeIf { it.isNotBlank() }
            ?: (msg["reasoning_details"] as? JsonArray)?.filterIsInstance<JsonObject>()?.joinToString("") { it["text"].asTextOrNull() ?: "" }?.takeIf { it.isNotBlank() }
            ?: msg["reasoning"].asTextOrNull()?.takeIf { it.isNotBlank() }
            ?: INLINE_THINK.findAll(content).lastOrNull()?.groupValues?.get(1).orEmpty()

    /**
     * M1 (device-measured 2026-09-26, both reasoning modes): MiniMax-M3 sometimes emits the answer's first token glued to the end of its
     * reasoning ("…two concise reasons.Two" + content "common reasons…"; inline: "…One sentence.Every\n</think>\n\nday at 08:00…";
     * v6: "…information.\"T" + "orch tile\" is…"). Display/parsing text only: the raw message is echoed verbatim. Applied only when the
     * answer starts in lower case and the reasoning ends in punctuation immediately followed by a capitalised fragment. `rawAnswer` is the
     * content with think tags removed but NOT trimmed: the tokenizer puts a word's leading space on the next token, so content starting
     * with whitespace means the fragment was a whole word ("Two"+" common"), content starting with a letter means a word piece ("T"+"orch",
     * "Th"+"ere"). `inline` (the content had think tags, whose "\n</think>\n\n" hides that signal): fall back to length, a single letter
     * other than I/A is a word piece, anything longer a word. An answer that is only punctuation (the reasoning's final period) is dropped.
     */
    fun repairGluedAnswer(reasoning: String, rawAnswer: String, inline: Boolean = false): String {
        val answer = rawAnswer.trim()
        if (answer.isNotEmpty() && ONLY_PUNCT.matches(answer)) return ""
        if (answer.isEmpty() || !(answer[0].isLowerCase() || answer[0] == '\'' || answer[0] == '’')) return answer
        val frag = GLUED.find(reasoning.trimEnd())?.groupValues?.get(1) ?: return answer
        // ponytail: length heuristic, no dictionary — a lone capital other than I/A is a word piece ("T"+"orch"),
        // anything longer is a whole word ("You"+"have"); mis-joins "Th"+"ere". Split mode drops the leading space
        // (device v6.1), so whitespace only ever proves a word. Upgrade = keep split off (the default) or a word list.
        val piece = frag.length == 1 && frag != "I" && frag != "A"
        val word = when {
            answer[0] == '\'' || answer[0] == '’' -> false        // "I" + "'m here"
            !inline && rawAnswer[0].isWhitespace() -> true
            else -> !piece
        }
        return frag + (if (word) " " else "") + answer
    }

    internal fun minimaxError(label: String, code: Int, msg: String?): String = when (code) {
        1004 -> "Invalid $label API key (Settings > AI)"
        1008 -> "$label: insufficient credits/balance — top up your account"
        1002 -> "$label: rate limited, retry later"
        1039 -> "$label: token limit exceeded — shorten the prompt or lower Max tokens"
        2013 -> "$label rejected the request (parameter error): ${(msg ?: "").take(300)}"
        1027 -> "$label blocked the response (content filter)"
        else -> "$label error $code: ${(msg ?: "").take(300)}"
    }

    // ---------------------------------------------------------------- pure: errors (§4.5)

    /** Message text from an error body: {error:{message}} | {base_resp:{status_msg}} | {detail} | {message} | raw text; <= 300 chars. */
    fun bodyMessage(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val o = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return body.take(300)
        val err = o["error"]
        val m = when (err) {
            is JsonObject -> err["message"].asTextOrNull()
            is JsonPrimitive -> err.content
            else -> null
        } ?: (o["base_resp"] as? JsonObject)?.get("status_msg").asTextOrNull()
            ?: o["detail"]?.let { if (it is JsonPrimitive) it.content else it.toString() }
            ?: o["message"].asTextOrNull()
            ?: body
        return m.take(300)
    }

    fun errorMessage(p: Provider, status: Int, body: String?, model: String, key: String?): String {
        val msg = clean(bodyMessage(body), key)
        val l = p.label
        val code = runCatching { ((JSON.parseToJsonElement(body ?: "") as? JsonObject)?.get("base_resp") as? JsonObject)?.get("status_code").asTextOrNull()?.toIntOrNull() }.getOrNull()
        if (code != null && code != 0) return clean(minimaxError(p.label, code, msg), key)
        return when (status) {
            400 -> "$l rejected the request: $msg"
            401 -> "Invalid $l API key (Settings > AI)"
            402 -> "$l: insufficient credits/balance — top up your account"
            403 -> "$l: forbidden — key not allowed for $model, or content blocked"
            404 -> "Model not found on $l: $model (use Fetch models, check base URL)"
            408 -> "$l timed out (408)"
            413, 422 -> "$l: request too large / invalid parameters: $msg"
            429 -> "$l: rate limited, retry later"
            502 -> if (p.id == "openrouter") "$l error 502: model is down or unavailable" else "$l error 502: $msg"
            503 -> if (p.id == "openrouter") "$l error 503: no provider available for $model" else "$l error 503: $msg"
            else -> if (status >= 500) "$l error $status: $msg" else "$l error $status: $msg"
        }.let { clean(it, key) }
    }

    private val KEY_PATTERNS = listOf(
        Regex("Bearer\\s+\\S+"), Regex("sk-or-\\S+"), Regex("sk-[A-Za-z0-9_-]{8,}"), Regex("gsk_\\S+"), Regex("xai-\\S+"), Regex("AIza\\S+"),
    )

    /** Masks the key value and common key shapes; <= 300 chars. */
    fun clean(msg: String?, key: String?): String {
        var s = msg ?: ""
        if (!key.isNullOrBlank()) s = s.replace(key, "***")
        for (re in KEY_PATTERNS) s = s.replace(re, "***")
        return s.take(300)
    }
}
