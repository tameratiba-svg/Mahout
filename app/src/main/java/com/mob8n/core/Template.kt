package com.mob8n.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Syntax (one, simple):
 *   {{title}} {{meta.artist}} {{items[0].name}}           current item fields (dotted path, [n] index)
 *   {{$json}}                                             whole current item as JSON
 *   {{$node.Now Playing.artist}} {{$node.HTTP.body.x}}    a named upstream node's FIRST main item; {{$node.X.all}} = all its items
 *   {{$vars.count}}                                       global variable
 *   {{$now}} {{$date}} {{$time}} {{$epoch}} {{$index}} {{$count}} {{$runId}} {{$workflow}}
 *   {{field ?? "default"}}  {{field ?? 0}}                default when missing/null (JSON literal or bare text)
 */
data class Scope(
    val item: Item, val index: Int, val count: Int,
    val upstream: Map<String, Items>, val vars: Map<String, JsonElement>,
    val nowMs: Long, val zone: ZoneId, val runId: String, val workflowName: String,
)

object Template {
    private val RE = Regex("""\{\{\s*(.+?)\s*\}\}""")

    fun hasTemplate(s: String): Boolean = RE.containsMatchIn(s)

    /** String interpolation; non-primitive values are serialized as JSON. */
    fun render(t: String, s: Scope): String = if (!t.contains("{{")) t else RE.replace(t) { m -> text(eval(m.groupValues[1], s)) }

    /** Whole-string expression keeps its JSON type (numbers/objects/arrays); otherwise a rendered string. */
    fun renderJson(t: String, s: Scope): JsonElement {
        // matchEntire forces the lazy group to swallow "a}} {{b" in "{{a}} {{b}}"; only ONE match may claim the whole string
        val whole = RE.matchEntire(t.trim())?.takeIf { RE.findAll(t).count() == 1 }
        return if (whole != null) eval(whole.groupValues[1], s) else JsonPrimitive(render(t, s))
    }

    fun eval(expr: String, s: Scope): JsonElement {
        val parts = expr.split("??", limit = 2)
        val v = resolve(parts[0].trim(), s)
        if (v != null && v !is JsonNull) return v
        val def = parts.getOrNull(1)?.trim() ?: return JsonNull
        return runCatching { JSON.parseToJsonElement(def) }.getOrElse { JsonPrimitive(def.trim('"', '\'')) }
    }

    fun text(e: JsonElement?): String = e.asText()

    private fun resolve(path: String, s: Scope): JsonElement? {
        val segs = segments(path)
        if (segs.isEmpty()) return null
        fun fmt(p: String) = JsonPrimitive(DateTimeFormatter.ofPattern(p).format(Instant.ofEpochMilli(s.nowMs).atZone(s.zone)))
        return when (segs[0]) {
            "\$json" -> if (segs.size == 1) s.item else s.item.path(segs.drop(1))
            "\$node" -> {
                val items = s.upstream[segs.getOrNull(1) ?: return null] ?: return null
                val rest = segs.drop(2)
                if (rest.firstOrNull() == "all") JsonArray(items).let { arr -> if (rest.size == 1) arr else arr.path(rest.drop(1)) }
                else (items.firstOrNull() ?: EMPTY).let { if (rest.isEmpty()) it else it.path(rest) }
            }
            "\$vars" -> s.vars[segs.getOrNull(1) ?: return null]?.let { if (segs.size == 2) it else it.path(segs.drop(2)) }
            "\$now" -> JsonPrimitive(Instant.ofEpochMilli(s.nowMs).atZone(s.zone).toOffsetDateTime().toString())
            "\$epoch" -> JsonPrimitive(s.nowMs)
            "\$date" -> fmt("yyyy-MM-dd")
            "\$time" -> fmt("HH:mm")
            "\$runId" -> JsonPrimitive(s.runId)
            "\$index" -> JsonPrimitive(s.index)
            "\$count" -> JsonPrimitive(s.count)
            "\$workflow" -> JsonPrimitive(s.workflowName)
            else -> s.item.path(segs)
        }
    }

    /** Keys of an item (one nested level) for the "fields from upstream" helper. */
    fun keysOf(item: JsonObject?): List<String> = item?.flatMap { (k, v) ->
        if (v is JsonObject) listOf(k) + v.keys.map { "$k.$it" } else listOf(k)
    } ?: emptyList()
}

/** Run-log redaction: masks values under secret-looking keys and any string containing a stored secret value. */
object Redaction {
    private val KEY_RE = Regex("(?i)(api[_-]?key|secret|token|password|passwd|authorization|x-api-key)")
    const val MASK = "***"
    fun redact(e: JsonElement, secrets: Collection<String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (KEY_RE.containsMatchIn(k)) JsonPrimitive(MASK) else redact(v, secrets) })
        is JsonArray -> JsonArray(e.map { redact(it, secrets) })
        is JsonPrimitive -> if (e.isString) redactText(e.content, secrets).let { if (it == e.content) e else JsonPrimitive(it) } else e
    }
    /** Mask every stored secret value (>= 8 chars) inside a free-text string (node/run error messages, log lines). */
    fun redactText(s: String, secrets: Collection<String>): String {
        var r = s
        for (x in secrets) if (x.length >= 8 && r.contains(x)) r = r.replace(x, MASK)
        return r
    }
}
