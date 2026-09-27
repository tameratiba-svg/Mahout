package com.mob8n.ai

import com.mob8n.core.NodeException
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a streaming model call reports while it runs (DESIGN6 §5.1.1). Display only: the persisted Turn comes from the same parsers as today. */
sealed interface StreamDelta {
    data class Text(val text: String) : StreamDelta
    data class Thinking(val text: String) : StreamDelta
    data class ToolStart(val index: Int, val id: String, val name: String) : StreamDelta
    data class ToolArgs(val index: Int, val addedChars: Int) : StreamDelta   // size only: raw argument JSON never reaches the UI
    data object Reset : StreamDelta                                         // mid-stream failure: discard the segment, non-stream redo follows
}
typealias OnDelta = (StreamDelta) -> Unit
const val STREAM_FRAME_MS = 40L

object Sse {
    /** SSE framing: lines -> event data payloads. `data:` lines of one event joined by '\n'; blank line ends an event; lines starting
     *  with ':' (e.g. OpenRouter ": OPENROUTER PROCESSING"), `event:`, `id:`, `retry:` ignored; CRLF tolerated; "[DONE]" returned as-is. */
    fun events(lines: Sequence<String>): Sequence<String> = sequence {
        val buf = StringBuilder(); var has = false
        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            if (line.isEmpty()) { if (has) { yield(buf.toString()); buf.setLength(0); has = false }; continue }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            if ((if (colon < 0) line else line.substring(0, colon)) != "data") continue
            val v = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            if (has) buf.append('\n')
            buf.append(v); has = true
        }
        if (has) yield(buf.toString())
    }
}

/** A streaming-only failure (framing, idle timeout, truncation, or a chunk error carried as `cause`). Never an IOException: exchange() must not retry it. */
class StreamFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class Fallback { CACHE_AND_REDO, RESET_AND_REDO, THROW }

/** D7 (pure): provider/API errors inside the stream are real errors; anything else redoes the call without streaming (cached only before the first delta). */
fun streamFailureAction(emittedAny: Boolean, cause: Throwable): Fallback = when {
    cause is NodeException || cause is CancellationException -> Fallback.THROW
    emittedAny -> Fallback.RESET_AND_REDO
    else -> Fallback.CACHE_AND_REDO
}

/** Leading-edge throttle (pure; fake clock in tests). */
// ponytail: leading-edge only; the caller's final publish closes the trail
class Throttle(private val periodMs: Long) {
    private var last = Long.MIN_VALUE
    fun ready(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < periodMs) return false
        last = nowMs
        return true
    }
}

private val THINK_BLOCK = Regex("(?s)<(mm:)?think>.*?</(mm:)?think>|</(mm:)?think>")
private val THINK_OPEN = Regex("(?s)<(mm:)?think>.*$")
private val TAG_TAIL = Regex("<[/a-z:]{0,10}$")         // a tag still arriving ("</th", "</mm:think") stays hidden until its '>' lands

/** Display filter for partial text: strips <think>…</think> / <mm:think>…, lone closers (MiniMax-M3), and hides an unclosed <think> tail. */
fun streamVisible(raw: String): String = raw.replace(THINK_BLOCK, "").replace(THINK_OPEN, "").replace(TAG_TAIL, "").trimStart()

private val THINK_INNER = Regex("(?s)<(?:mm:)?think>(.*?)(?:</(?:mm:)?think>|$)")

/** Inline thinking (MiniMax reasoning_split=false, R1-style models) for the live "Show thinking" disclosure: the text inside every <think> block so far. */
fun streamThinking(raw: String): String =
    if (!raw.contains("think>")) "" else THINK_INNER.findAll(raw).joinToString("\n") { it.groupValues[1].replace(TAG_TAIL, "").trim() }.trim()

/** OpenAI-compatible chunk accumulator. Pure. `tolerateCumulative` (MiniMax): a chunk repeating the whole text so far is merged, not appended. */
class OpenAiStreamAccumulator(private val providerLabel: String, private val tolerateCumulative: Boolean = false) {
    /** Text joiner: plain append, or (tolerant) whole-so-far chunks detected once and merged. */
    // ponytail: cumulative-chunk detection is a prefix heuristic (MiniMax docs diff against the buffer); upgrade = per-provider flag from a captured stream
    private class Joiner(private val tolerant: Boolean) {
        val sb = StringBuilder(); private var incremental = false
        fun add(f: String): String {
            if (f.isEmpty()) return ""
            if (tolerant && !incremental && sb.isNotEmpty()) {
                if (f.startsWith(sb)) { val s = f.substring(sb.length); sb.append(s); return s }
                incremental = true
            }
            sb.append(f); return f
        }
    }
    private class Call { var id: String? = null; var type: String? = null; val name = StringBuilder(); val args = StringBuilder(); var argsObj: JsonObject? = null; var started = false }

    private var id: String? = null
    private var model: String? = null
    private val content = Joiner(tolerateCumulative)
    private val reasoningContent = StringBuilder()
    private val reasoning = StringBuilder()
    private val refusal = StringBuilder()
    private val details = LinkedHashMap<Int, LinkedHashMap<String, JsonElement>>()
    private val detailText = HashMap<Int, Joiner>()
    private val calls = HashMap<Int, Call>()
    private var finish: String? = null
    private var usage: JsonObject? = null
    var emittedAny: Boolean = false; private set
    /** A finish_reason arrived (EOF without [DONE] is then complete). */
    val finished: Boolean get() = finish != null

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** One parsed `data:` JSON chunk -> deltas. Throws NodeException for `{"error":{…}}` chunks and MiniMax `base_resp.status_code != 0`. */
    fun feed(chunk: JsonObject): List<StreamDelta> {
        chunk["error"]?.takeIf { it !is JsonNull }?.let {
            throw NodeException("$providerLabel error during streaming: ${OpenAiCompat.bodyMessage(chunk.toString())}")
        }
        (chunk["base_resp"] as? JsonObject)?.let { br ->
            val code = br["status_code"].asTextOrNull()?.toIntOrNull() ?: 0
            if (code != 0) throw NodeException(OpenAiCompat.minimaxError(providerLabel, code, br["status_msg"].asTextOrNull()))
        }
        if (id == null) id = str(chunk["id"])
        if (model == null) model = str(chunk["model"])
        (chunk["usage"] as? JsonObject)?.let { usage = it }
        val out = ArrayList<StreamDelta>()
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        str(choice?.get("finish_reason"))?.let { finish = it }
        val d = choice?.get("delta") as? JsonObject
        if (d != null) {
            str(d["content"])?.let { content.add(it).takeIf(String::isNotEmpty)?.let { s -> out += StreamDelta.Text(s) } }
            str(d["reasoning_content"])?.takeIf { it.isNotEmpty() }?.let { reasoningContent.append(it); out += StreamDelta.Thinking(it) }
            str(d["reasoning"])?.takeIf { it.isNotEmpty() }?.let { reasoning.append(it); out += StreamDelta.Thinking(it) }
            str(d["refusal"])?.let { refusal.append(it) }
            (d["reasoning_details"] as? JsonArray)?.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val idx = o["index"].asTextOrNull()?.toIntOrNull() ?: 0
                val m = details.getOrPut(idx) { LinkedHashMap() }
                for ((k, v) in o) if (k != "text" && (k !in m || m[k] is JsonNull)) m[k] = v            // other keys: first (non-null) wins
                str(o["text"])?.let { t -> detailText.getOrPut(idx) { Joiner(true) }.add(t).takeIf(String::isNotEmpty)?.let { s -> out += StreamDelta.Thinking(s) } }
            }
            (d["tool_calls"] as? JsonArray)?.forEachIndexed { pos, e ->
                val o = e as? JsonObject ?: return@forEachIndexed
                val idx = o["index"].asTextOrNull()?.toIntOrNull() ?: pos
                val c = calls.getOrPut(idx) { Call() }
                str(o["id"])?.takeIf { it.isNotBlank() && c.id.isNullOrBlank() }?.let { c.id = it }
                str(o["type"])?.let { if (c.type == null) c.type = it }
                val fn = o["function"] as? JsonObject
                str(fn?.get("name"))?.takeIf { it.isNotEmpty() && c.name.toString() != it }?.let { c.name.append(it) }   // a provider repeating the whole name is not doubled
                val added = when (val a = fn?.get("arguments")) {
                    is JsonObject -> { c.argsObj = a; a.toString().length }                  // Gemini quirk: an object, kept as the object
                    is JsonPrimitive -> if (a is JsonNull) 0 else { c.args.append(a.content); a.content.length }
                    else -> 0
                }
                if (!c.started && c.name.isNotEmpty()) { c.started = true; out += StreamDelta.ToolStart(idx, c.id ?: "", c.name.toString()) }
                if (added > 0 && c.started) out += StreamDelta.ToolArgs(idx, added)
            }
        }
        if (out.isNotEmpty()) emittedAny = true
        return out
    }

    /** A body equivalent to the non-streaming response: {id, model, choices:[{index:0, message:{role:"assistant", content, tool_calls?,
     *  reasoning_content?, reasoning?, reasoning_details?}, finish_reason}], usage?} -> fed to the EXISTING OpenAiCompat.parseResponse. */
    fun toResponse(): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        model?.let { put("model", it) }
        val tcs = calls.toSortedMap().values.toList()
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", if (content.sb.isEmpty() && tcs.isNotEmpty()) JsonNull else JsonPrimitive(content.sb.toString()))
            if (tcs.isNotEmpty()) put("tool_calls", buildJsonArray {
                tcs.forEach { c ->
                    add(buildJsonObject {
                        c.id?.let { put("id", it) }
                        put("type", c.type ?: "function")
                        put("function", buildJsonObject {
                            put("name", c.name.toString())
                            if (c.argsObj != null) put("arguments", c.argsObj!!) else put("arguments", c.args.toString())
                        })
                    })
                }
            })
            if (reasoningContent.isNotEmpty()) put("reasoning_content", reasoningContent.toString())
            if (reasoning.isNotEmpty()) put("reasoning", reasoning.toString())
            if (details.isNotEmpty()) put("reasoning_details", JsonArray(details.map { (idx, m) ->
                JsonObject(detailText[idx]?.let { m + ("text" to JsonPrimitive(it.sb.toString())) } ?: m)
            }))
            if (refusal.isNotEmpty()) put("refusal", refusal.toString())
        }
        put("choices", buildJsonArray {
            add(buildJsonObject { put("index", 0); put("message", message); put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull) })
        })
        usage?.let { put("usage", it) }
    }
}
