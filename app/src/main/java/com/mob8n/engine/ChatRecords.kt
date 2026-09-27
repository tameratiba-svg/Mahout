package com.mob8n.engine

import com.mob8n.core.EMPTY
import com.mob8n.core.JSON
import com.mob8n.core.Redaction
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.db.AiUsageEntity
import com.mob8n.engine.db.ConversationEntity
import com.mob8n.engine.db.MessageEntity
import com.mob8n.engine.db.SkillEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---------------------------------------------------------------- records (DESIGN4 §3.2; every lane may import these through Engine)

data class Conversation(
    val id: String, val title: String, val createdAt: Long, val updatedAt: Long,
    val settingsJson: String /* ChatSettings JSON (ai lane owns the shape; unknown keys ignored) */,
    val pendingJson: String? /* JSON array of tool_use blocks awaiting approval */,
    val status: String /* idle | running | awaiting | error */, val lastError: String?,
)

/** One Claude-wire message. role user|assistant|note. json = {role, content:[blocks], _oai?} (redacted, image-free). text = plain projection <= 4 KB (search/preview). */
data class ChatMessage(
    val id: Long = 0, val conversationId: String, val seq: Int = 0, val role: String, val json: JsonObject,
    val text: String, val meta: JsonObject = EMPTY /* {pending:Boolean, ms:{toolUseId:Long}, kind:{toolUseId:String}, provider, model, usage:{in,out,cached}, error, cancelled} */,
    val createdAt: Long,
)

data class Skill(
    val id: String, val name: String, val description: String, val instructions: String, val allowedTools: List<String>, val tags: List<String>,
    val createdBy: String /* user | assistant | preset */, val enabled: Boolean, val createdAt: Long, val updatedAt: Long, val usageCount: Int,
)

data class AiUsageRow(
    val id: Long = 0, val ts: Long, val provider: String, val model: String, val source: String /* node|chat|builder|test */,
    val inTok: Long /* uncached input */, val outTok: Long, val cachedTok: Long /* cache reads */, val cacheWriteTok: Long,
    val costUsd: Double? /* null = price unknown */, val estimated: Boolean, val runId: String?, val conversationId: String?,
)

// ---------------------------------------------------------------- entity mappers

private val STRINGS = ListSerializer(String.serializer())
private fun parseObject(s: String): JsonObject = runCatching { JSON.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: EMPTY
private fun parseStrings(s: String): List<String> = runCatching { JSON.decodeFromString(STRINGS, s) }.getOrDefault(emptyList())
private fun encode(o: JsonObject): String = JSON.encodeToString(JsonObject.serializer(), o)

fun ConversationEntity.toRecord() = Conversation(id, title, createdAt, updatedAt, settingsJson, pendingJson, status, lastError)
fun Conversation.toEntity() = ConversationEntity(id, title, createdAt, updatedAt, settingsJson, pendingJson, status, lastError)

fun MessageEntity.toRecord() = ChatMessage(id, conversationId, seq, role, parseObject(json), text, parseObject(metaJson), createdAt)
fun ChatMessage.toEntity() = MessageEntity(id, conversationId, seq, role, encode(json), text, encode(meta), createdAt)

fun SkillEntity.toRecord() = Skill(id, name, description, instructions, parseStrings(allowedToolsJson), parseStrings(tagsJson), createdBy, enabled, createdAt, updatedAt, usageCount)
fun Skill.toEntity() = SkillEntity(id, name, description, instructions, JSON.encodeToString(STRINGS, allowedTools), JSON.encodeToString(STRINGS, tags), createdBy, enabled, createdAt, updatedAt, usageCount)

fun AiUsageEntity.toRecord() = AiUsageRow(id, ts, provider, model, source, inTok, outTok, cachedTok, cacheWriteTok, costUsd, estimated, runId, conversationId)
fun AiUsageRow.toEntity() = AiUsageEntity(id, ts, provider, model, source, inTok, outTok, cachedTok, cacheWriteTok, costUsd, estimated, runId, conversationId)

// ---------------------------------------------------------------- ChatStore: what happens to a message before it reaches Room (DESIGN4 V5 / §4.3)

/**
 * Pure, JVM-tested helper behind Engine.appendMessage / messagesTail. Every stored row is redacted (secret values + secret-named keys), image-free
 * (image blocks and tool_result inner images become a text placeholder) and <= MAX_ROW_CHARS: `_oai` is dropped first (logged), then every tool_result
 * text is truncated to TOOL_RESULT_CAP, then the whole content collapses to one "(message too large)" block. `text` is capped at TEXT_CAP.
 * seq is assigned by the DAO transaction (COALESCE(MAX(seq), -1) + 1); the model transcript is rebuilt from these rows each turn, so a secret that
 * leaked into a tool result is masked before the next model call.
 */
internal object ChatStore {
    const val MAX_ROW_CHARS = 256 * 1024
    const val TOOL_RESULT_CAP = 8 * 1024
    const val TEXT_CAP = 4096
    const val REMOVED_IMAGE = "(image removed)"
    const val RAW_KEY = "_oai"
    private const val TRUNCATED = "…[truncated]"

    fun prepare(m: ChatMessage, secrets: Collection<String>, log: (String) -> Unit = {}): ChatMessage {
        var json = stripImages(Redaction.redact(m.json, secrets) as JsonObject)
        val meta = Redaction.redact(m.meta, secrets) as JsonObject
        if (encode(json).length > MAX_ROW_CHARS && json.containsKey(RAW_KEY)) {
            log("chat: raw echo dropped, row too large"); json = JsonObject(json - RAW_KEY)
        }
        if (encode(json).length > MAX_ROW_CHARS) json = truncateToolResults(json)
        if (encode(json).length > MAX_ROW_CHARS) {
            val n = encode(json).length
            json = JsonObject(json + ("content" to JsonArray(listOf(textBlock("(message too large: $n chars)")))))
        }
        return m.copy(json = json, meta = meta, text = Redaction.redactText(m.text, secrets).take(TEXT_CAP))
    }

    /** Newest-first DAO rows -> the newest rows whose json sizes sum <= maxChars, oldest first (the first kept row is never dropped even when oversize). */
    fun tail(newestFirst: List<MessageEntity>, maxChars: Int): List<ChatMessage> {
        val kept = ArrayList<MessageEntity>()
        var total = 0
        for (e in newestFirst) {
            if (kept.isNotEmpty() && total + e.json.length > maxChars) break
            kept += e; total += e.json.length
        }
        return kept.asReversed().map { it.toRecord() }
    }

    private fun textBlock(t: String): JsonObject = buildJsonObject { put("type", "text"); put("text", t) }
    private fun typeOf(b: JsonElement): String? = (b as? JsonObject)?.get("type").asTextOrNull()

    /** Top-level image blocks and images inside tool_result content arrays become REMOVED_IMAGE text (the Agent's withoutImages rule). */
    fun stripImages(o: JsonObject): JsonObject {
        val content = o["content"] as? JsonArray ?: return o
        val out = content.map { b ->
            when {
                typeOf(b) == "image" -> textBlock(REMOVED_IMAGE)
                typeOf(b) == "tool_result" -> {
                    val inner = (b as JsonObject)["content"] as? JsonArray
                    if (inner == null || inner.none { typeOf(it) == "image" }) b
                    else JsonObject(b + ("content" to JsonArray(inner.map { if (typeOf(it) == "image") textBlock(REMOVED_IMAGE) else it })))
                }
                else -> b
            }
        }
        return if (out == content.toList()) o else JsonObject(o + ("content" to JsonArray(out)))
    }

    /** Every tool_result text (string content or text blocks inside the content array) capped at TOOL_RESULT_CAP + marker. */
    fun truncateToolResults(o: JsonObject): JsonObject {
        val content = o["content"] as? JsonArray ?: return o
        fun cap(s: String) = if (s.length > TOOL_RESULT_CAP) s.take(TOOL_RESULT_CAP) + TRUNCATED else s
        val out = content.map { b ->
            if (typeOf(b) != "tool_result") return@map b
            b as JsonObject
            when (val c = b["content"]) {
                is JsonPrimitive -> if (c.isString) JsonObject(b + ("content" to JsonPrimitive(cap(c.content)))) else b
                is JsonArray -> JsonObject(b + ("content" to JsonArray(c.map { x ->
                    if (typeOf(x) == "text") JsonObject((x as JsonObject) + ("text" to JsonPrimitive(cap(x["text"].asTextOrNull() ?: "")))) else x
                })))
                else -> b
            }
        }
        return JsonObject(o + ("content" to JsonArray(out)))
    }
}
