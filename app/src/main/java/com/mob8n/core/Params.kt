package com.mob8n.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Widget kind. The UI has exactly one composable per kind; nodes never touch UI. */
enum class ParamKind {
    TEXT,        // single line string
    MULTILINE,   // textarea
    NUMBER,      // JsonPrimitive number (double); `integer` via min/max + help
    BOOL,        // JsonPrimitive boolean
    ENUM,        // one of `options`
    DURATION,    // milliseconds (JsonPrimitive long); UI shows value + unit picker
    TIME,        // "HH:mm"
    APP,         // package name (app picker dialog)
    PLAYLIST,    // playlist name with suggestions from Room
    LABELS,      // JsonArray<string>; with definesPorts=true each label becomes an output port
    ROWS,        // JsonArray<JsonObject> rows shaped by `rows` (recursive ParamSpec columns)
    WORKFLOW,    // workflow id (string)
    SECRET,      // secret NAME looked up via ctx.secret(name); never templated, never logged, never a tool param
}

data class VisibleWhen(val key: String, val equalsAny: List<String>)

data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val help: String = "",
    val options: List<String> = emptyList(),
    val templated: Boolean = true,
    val min: Double? = null,
    val max: Double? = null,
    val rows: List<ParamSpec> = emptyList(),
    val definesPorts: Boolean = false,
    val visibleWhen: VisibleWhen? = null,
) {
    init {
        require(key.matches(Regex("[a-z][a-zA-Z0-9_]*"))) { "param key must be an identifier: $key" }
        if (kind == ParamKind.ENUM) require(options.isNotEmpty()) { "$key: ENUM needs options" }
        if (kind == ParamKind.ROWS) require(rows.isNotEmpty()) { "$key: ROWS needs a row schema" }
        if (definesPorts) require(kind == ParamKind.LABELS) { "$key: only LABELS may define ports" }
    }

    /** Human error for an UNRENDERED value, or null. Templates ({{...}}) are allowed in templated params. */
    fun validate(v: JsonElement?): String? {
        val absent = v == null || v is JsonNull ||
            (v is JsonPrimitive && v.isString && v.content.isBlank()) ||
            (v is JsonArray && v.isEmpty())
        if (absent) return if (required && default == null) "$label is required" else null
        val tpl = templated && v is JsonPrimitive && v.isString && v.content.contains("{{")
        return when (kind) {
            ParamKind.TEXT, ParamKind.MULTILINE, ParamKind.APP, ParamKind.PLAYLIST, ParamKind.WORKFLOW, ParamKind.SECRET ->
                if (v is JsonPrimitive) null else "$label must be text"
            ParamKind.NUMBER, ParamKind.DURATION -> {
                if (tpl) return null
                val d = v.asDouble() ?: return "$label must be a number"
                when {
                    min != null && d < min -> "$label must be >= ${fmt(min)}"
                    max != null && d > max -> "$label must be <= ${fmt(max)}"
                    else -> null
                }
            }
            ParamKind.BOOL -> if (tpl || v.asBool() != null) null else "$label must be true/false"
            ParamKind.ENUM -> if (v is JsonPrimitive && v.content in options) null else "$label must be one of $options"
            ParamKind.TIME -> if (tpl || (v is JsonPrimitive && v.content.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")))) null else "$label must be HH:mm"
            ParamKind.LABELS -> if (v is JsonArray && v.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() }) null else "$label must be a list of names"
            ParamKind.ROWS -> {
                if (v !is JsonArray) return "$label must be a list"
                v.forEachIndexed { i, r ->
                    val o = r as? JsonObject ?: return "$label row ${i + 1} is malformed"
                    for (c in rows) c.validate(o[c.key])?.let { return "$label row ${i + 1}: $it" }
                }
                null
            }
        }
    }

    /** Plain JSON-Schema fragment (Agent tools, Nano prompts). */
    fun jsonSchema(): JsonObject = buildJsonObject {
        when (kind) {
            ParamKind.NUMBER -> put("type", "number")
            ParamKind.DURATION -> put("type", "integer")
            ParamKind.BOOL -> put("type", "boolean")
            ParamKind.ENUM -> { put("type", "string"); put("enum", JsonArray(options.map { JsonPrimitive(it) })) }
            ParamKind.LABELS -> { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) }
            ParamKind.ROWS -> {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", JsonObject(rows.associate { it.key to it.strictSchema() }))
                    put("required", JsonArray(rows.map { JsonPrimitive(it.key) }))
                })
            }
            else -> put("type", "string")
        }
        put("description", description())
    }

    /** Strict-mode fragment: optional params are nullable (every property is listed in `required` by the caller). */
    fun strictSchema(): JsonObject =
        if (required && default == null) jsonSchema()
        else buildJsonObject {
            put("anyOf", JsonArray(listOf(JsonObject(jsonSchema().filterKeys { it != "description" }), buildJsonObject { put("type", "null") })))
            put("description", description() + " (null = default" + (default?.let { ": ${it.asText()}" } ?: "") + ")")
        }

    fun description(): String = listOf(
        label, help,
        if (kind == ParamKind.DURATION) "milliseconds" else "",
        if (kind == ParamKind.TIME) "HH:mm 24h" else "",
        if (kind == ParamKind.WORKFLOW) "workflow id" else "",
        if (kind == ParamKind.APP) "Android package name" else "",
    ).filter { it.isNotBlank() }.joinToString(". ")

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

// ---- short constructors (nodes use these) ----
fun text(key: String, label: String, default: String? = null, required: Boolean = false, help: String = "", templated: Boolean = true, visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.TEXT, required, default?.let { JsonPrimitive(it) }, help, templated = templated, visibleWhen = visibleWhen)
fun multiline(key: String, label: String, default: String? = null, required: Boolean = false, help: String = "", templated: Boolean = true, visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.MULTILINE, required, default?.let { JsonPrimitive(it) }, help, templated = templated, visibleWhen = visibleWhen)
fun number(key: String, label: String, default: Double? = null, min: Double? = null, max: Double? = null, required: Boolean = false, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.NUMBER, required, default?.let { JsonPrimitive(it) }, help, min = min, max = max, visibleWhen = visibleWhen)
fun bool(key: String, label: String, default: Boolean = false, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.BOOL, false, JsonPrimitive(default), help, templated = false, visibleWhen = visibleWhen)
fun choice(key: String, label: String, options: List<String>, default: String = options.first(), help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.ENUM, true, JsonPrimitive(default), help, options = options, templated = false, visibleWhen = visibleWhen)
fun durationMs(key: String, label: String, defaultMs: Long, minMs: Long = 0, maxMs: Long? = null, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.DURATION, false, JsonPrimitive(defaultMs), help, templated = false, min = minMs.toDouble(), max = maxMs?.toDouble(), visibleWhen = visibleWhen)
fun clockTime(key: String, label: String, default: String = "08:00", required: Boolean = true, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.TIME, required, JsonPrimitive(default), help, templated = false, visibleWhen = visibleWhen)
fun appPicker(key: String, label: String, required: Boolean = false, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.APP, required, null, help, templated = false, visibleWhen = visibleWhen)
fun playlistName(key: String, label: String, default: String = "Auto Liked", help: String = "") =
    ParamSpec(key, label, ParamKind.PLAYLIST, true, JsonPrimitive(default), help)
fun labels(key: String, label: String, default: List<String> = emptyList(), required: Boolean = false, definesPorts: Boolean = false, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.LABELS, required, if (default.isEmpty()) null else JsonArray(default.map { JsonPrimitive(it) }), help, templated = false, definesPorts = definesPorts, visibleWhen = visibleWhen)
fun rows(key: String, label: String, columns: List<ParamSpec>, required: Boolean = false, help: String = "", visibleWhen: VisibleWhen? = null) =
    ParamSpec(key, label, ParamKind.ROWS, required, null, help, rows = columns, visibleWhen = visibleWhen)
fun workflowPicker(key: String, label: String, required: Boolean = true, help: String = "") =
    ParamSpec(key, label, ParamKind.WORKFLOW, required, null, help, templated = false)
fun secret(key: String, label: String, help: String = "") =
    ParamSpec(key, label, ParamKind.SECRET, false, null, help, templated = false)
fun whenIs(key: String, vararg values: String) = VisibleWhen(key, values.toList())
