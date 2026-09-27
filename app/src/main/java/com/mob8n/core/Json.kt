package com.mob8n.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** The data currency. Every edge carries List<Item>. */
typealias Item = JsonObject
typealias Items = List<JsonObject>
/** port name -> items */
typealias Ports = Map<String, Items>

val JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; prettyPrint = false }
val EMPTY: Item = JsonObject(emptyMap())

const val MAIN = "main"
const val ERROR = "error"
const val PORT_TRUE = "true"
const val PORT_FALSE = "false"
const val PORT_DONE = "done"
const val PORT_OTHER = "other"
const val PORT_A = "a"
const val PORT_B = "b"

// Stable ids/constants shared across lanes
const val TRIGGER_MANUAL = "trigger.manual"
const val TRIGGER_CALLED = "trigger.called"
const val TRIGGER_NOTIFICATION_ACTION = "trigger.notification_action"
const val SECRETS_PREFS = "secrets"            // SharedPreferences file (backup-excluded); ai lane writes, engine reads
const val SECRET_CLAUDE_KEY = "claude_api_key"
const val SETTINGS_PREFS = "settings"          // non-secret app settings
const val LOG_TAG = "Mob8N"
const val DECISION_APPROVE = "approve"
const val DECISION_DENY = "deny"
const val DECISION_TIMEOUT = "timeout"
const val DECISION_TIMER = "timer"

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
    is Iterable<*> -> JsonArray(v.map(::toJson))
    is Array<*> -> JsonArray(v.map(::toJson))
    else -> JsonPrimitive(v.toString())
}

fun item(vararg pairs: Pair<String, Any?>): Item = buildJsonObject { for ((k, v) in pairs) put(k, toJson(v)) }
/** Copy of this item with fields added/overwritten. */
fun Item.add(vararg pairs: Pair<String, Any?>): Item = JsonObject(this + pairs.associate { it.first to toJson(it.second) })
fun Item.addAll(other: JsonObject): Item = JsonObject(this + other)
fun Item.without(keys: Collection<String>): Item = JsonObject(filterKeys { it !in keys })

fun JsonElement?.asText(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> content
    else -> JSON.encodeToString(JsonElement.serializer(), this)
}
fun JsonElement?.asTextOrNull(): String? = if (this == null || this is JsonNull) null else asText()
fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
fun Item.str(key: String): String? = this[key].asTextOrNull()
fun Item.num(key: String): Double? = this[key].asDouble()
fun Item.bool(key: String): Boolean? = this[key].asBool()

/** The single path-index literal (K4: escaped for Android ICU, which rejects bare `[`); Template shares it via segments(). */
private val INDEX_RE = Regex("""\[(\d+)\]""")

/** "a.b[0].c" -> ["a", "b", "0", "c"] */
fun segments(path: String): List<String> = path.replace(INDEX_RE, ".$1").split('.').filter { it.isNotEmpty() }

/** Dotted path with optional [n] indexes: "a.b[0].c" == "a.b.0.c". Returns null when absent. */
fun JsonElement.path(path: String): JsonElement? = path(segments(path))

fun JsonElement.path(segs: List<String>): JsonElement? {
    var cur: JsonElement = this
    for (seg in segs) {
        cur = when (cur) {
            is JsonObject -> cur[seg] ?: return null
            is JsonArray -> seg.toIntOrNull()?.let { cur.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return cur
}

/** n8n-style error item delivered on the ERROR port. */
fun makeErrorItem(message: String, nodeName: String, nodeType: String, input: JsonElement): Item = buildJsonObject {
    put("error", message); put("node", nodeName); put("nodeType", nodeType); put("input", input)
}

class NodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
