package com.mob8n.ai

import android.net.Uri
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.mob8n.core.ExecutionContext
import com.mob8n.core.NodeException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlin.math.min

enum class NanoStatus { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** Gemini Nano via ML Kit GenAI Prompt API 1.0.0-beta2 (DESIGN §8.3): no system role, no function calling, no typed output. */
object NanoClient {
    const val MODEL_NAME = "gemini-nano"
    private const val GEN_TIMEOUT_MS = 60_000L
    /** ponytail: Nano output capped at 256 tokens (DESIGN says 1024) for latency on-device; upgrade = expose per-node cap once beta latency is measured. */
    private const val MAX_OUTPUT_TOKENS = 256
    /** Room kept for the JSON repair retry suffix (rejection sentence ~150 chars + previous answer take(1000)). */
    private const val RETRY_RESERVE_CHARS = 1200
    private val model: GenerativeModel by lazy { Generation.getClient() }
    @Volatile private var tokenLimit: Int? = null

    suspend fun status(): NanoStatus = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }
    } catch (e: Exception) { NanoStatus.UNAVAILABLE }

    fun download(): Flow<DownloadStatus> = model.download()

    suspend fun warmup() { runCatching { model.warmup() } }

    fun statusError(s: NanoStatus): NodeException? = when (s) {
        NanoStatus.AVAILABLE -> null
        NanoStatus.DOWNLOADABLE -> NodeException("Gemini Nano not downloaded — Settings > AI")
        NanoStatus.DOWNLOADING -> NodeException("Gemini Nano is still downloading — try again in a few minutes")
        else -> NodeException("Gemini Nano unsupported on this device; switch provider to Claude")
    }

    /** System prompt is prepended to the user text (beta2 has no system role). JSON mode = prompt + fence strip + one repair retry. */
    suspend fun complete(ctx: ExecutionContext, req: LlmRequest): LlmResult {
        ctx.requireAndroid()
        statusError(status())?.let { throw it }
        val base = buildString { if (!req.system.isNullOrBlank()) append(req.system).append("\n\n"); append(req.prompt) }
        val schema = req.jsonSchema
        // Body is capped BEFORE the schema instruction is appended, so truncation never eats the schema text and the repair retry suffix stays within budget.
        val schemaText = schema?.let { "\n\nRespond with ONLY a JSON object (no prose, no code fences) with exactly these keys:\n" + Llm.describeSchema(it) } ?: ""
        val prompt = cap(ctx, base, reserve = schemaText.length + (if (schema == null) 0 else RETRY_RESERVE_CHARS)) + schemaText
        val image = req.imageUri?.let {
            try { ImagePart(Uri.parse(it)) } catch (e: Exception) { throw NodeException("Cannot read image $it: ${e.message}") }
        }
        if (req.maxTokens > MAX_OUTPUT_TOKENS) ctx.log("Gemini Nano maxTokens ${req.maxTokens} clamped to $MAX_OUTPUT_TOKENS")
        var text = generate(image, prompt, req.maxTokens)
        var usage = Usage.estimate(prompt.length, text.length)                    // no counters on-device: chars/4, estimated (V7)
        if (schema == null) return LlmResult(text, null, "end_turn", PROVIDER_NANO, MODEL_NAME, usage = usage)
        var obj = Llm.parseJsonObject(text)
        var missing = obj?.let { Llm.missingKeys(it, schema) } ?: emptyList()
        if (obj == null || missing.isNotEmpty()) {
            val problem = if (obj == null) "it was not a valid JSON object" else "it was missing keys $missing"
            ctx.log("Gemini Nano JSON repair retry: $problem")
            val retry = "$prompt\n\nYour previous answer was rejected because $problem. Previous answer:\n${text.take(1000)}\n\nRespond with ONLY the JSON object."
            text = generate(image, retry, req.maxTokens)
            usage += Usage.estimate(retry.length, text.length)
            obj = Llm.parseJsonObject(text)
            missing = obj?.let { Llm.missingKeys(it, schema) } ?: emptyList()
            if (obj == null || missing.isNotEmpty()) throw NodeException("Gemini Nano returned invalid JSON")
        }
        return LlmResult(text, obj, "end_turn", PROVIDER_NANO, MODEL_NAME, usage = usage)
    }

    private suspend fun generate(image: ImagePart?, prompt: String, maxTokens: Int): String {
        val request = if (image != null) generateContentRequest(image, TextPart(prompt)) { temperature = 0.2f; maxOutputTokens = min(maxTokens, MAX_OUTPUT_TOKENS) }
        else generateContentRequest(TextPart(prompt)) { temperature = 0.2f; maxOutputTokens = min(maxTokens, MAX_OUTPUT_TOKENS) }
        return try {
            val r = withTimeout(GEN_TIMEOUT_MS) { model.generateContent(request) }
            r.candidates.firstOrNull()?.text ?: ""
        } catch (e: TimeoutCancellationException) {
            throw NodeException("Gemini Nano timed out after ${GEN_TIMEOUT_MS / 1000} s")
        } catch (e: GenAiException) {
            throw NodeException("Gemini Nano error ${e.errorCode}: ${e.message}", e)
        } catch (e: NodeException) {
            throw e
        } catch (e: Exception) {
            throw NodeException("Gemini Nano failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Input cap from getTokenLimit() (consulted once); `reserve` = chars appended after capping (schema text, repair suffix).
     * ponytail: ~3 chars/token estimate and the reserve is a char estimate too, upgrade = countTokens() round trip.
     */
    private suspend fun cap(ctx: ExecutionContext, prompt: String, reserve: Int = 0): String {
        val limit = tokenLimit ?: runCatching { model.getTokenLimit() }.getOrNull()?.also { tokenLimit = it } ?: 4000
        val capped = nanoCapChars(prompt, limit, reserve)
        if (capped.length < prompt.length) ctx.log("Gemini Nano prompt truncated from ${prompt.length} to ${capped.length} chars (token limit $limit, reserve $reserve)")
        return capped
    }
}

/** Pure arithmetic behind [NanoClient.cap] (JVM-testable; the object itself needs ML Kit): body <= limit*3 - reserve chars. */
internal fun nanoCapChars(prompt: String, limit: Int, reserve: Int): String {
    val maxChars = (limit * 3 - reserve).coerceAtLeast(0)
    return if (prompt.length <= maxChars) prompt else prompt.take(maxChars)
}
