package com.mob8n.logic

import com.mob8n.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Resolve a dotted field path against an item; tolerates a leading `$json.` and a bare `{{...}}` wrapper. */
internal fun Item.field(path: String): JsonElement? {
    val p = path.trim().removePrefix("{{").removeSuffix("}}").trim().removePrefix("\$json").removePrefix(".")
    if (p.isEmpty()) return this
    return this.path(p)
}

internal fun JsonElement?.isAbsent() = this == null || this is JsonNull
internal fun JsonElement?.isEmptyValue(): Boolean = when (this) {
    null, is JsonNull -> true
    is JsonPrimitive -> content.isEmpty()
    is JsonArray -> isEmpty()
    is JsonObject -> isEmpty()
}

internal fun compileRegex(pattern: String): Regex =
    try { Regex(pattern) } catch (e: Exception) { throw NodeException("Invalid regex '$pattern': ${e.message}") }

/** One condition; numeric compare when both sides parse as numbers, else string compare. */
internal fun evalCondition(actual: JsonElement?, op: String, expected: String): Boolean {
    val a = actual.asText()
    val an = actual.asDouble(); val en = expected.toDoubleOrNull()
    fun cmp(): Int = if (an != null && en != null) an.compareTo(en) else a.compareTo(expected)
    return when (op) {
        "eq" -> if (an != null && en != null) an == en else a == expected
        "neq" -> if (an != null && en != null) an != en else a != expected
        "contains" -> if (actual is JsonArray) actual.any { it.asText() == expected } else a.contains(expected)
        "not_contains" -> if (actual is JsonArray) actual.none { it.asText() == expected } else !a.contains(expected)
        "starts_with" -> a.startsWith(expected)
        "ends_with" -> a.endsWith(expected)
        "regex" -> compileRegex(expected).containsMatchIn(a)
        "gt" -> cmp() > 0
        "gte" -> cmp() >= 0
        "lt" -> cmp() < 0
        "lte" -> cmp() <= 0
        "exists" -> !actual.isAbsent()
        "not_exists" -> actual.isAbsent()
        "empty" -> actual.isEmptyValue()
        "not_empty" -> !actual.isEmptyValue()
        "is_true" -> actual.asBool() == true
        "is_false" -> actual.asBool() == false
        else -> throw NodeException("Unknown operator '$op'")
    }
}

object IfNode : Node() {
    val OPS = listOf("eq", "neq", "contains", "not_contains", "starts_with", "ends_with", "regex", "gt", "gte", "lt", "lte", "exists", "not_exists", "empty", "not_empty", "is_true", "is_false")
    override val spec = NodeSpec(
        id = "logic.if", name = "If", kind = NodeKind.LOGIC,
        description = "Route each item to true/false by comparing fields (numeric when both sides are numbers).",
        params = listOf(
            rows("conditions", "Conditions", listOf(
                text("field", "Field", required = true, help = "dotted path, e.g. meta.artist"),
                choice("op", "Operator", OPS, "eq"),
                text("value", "Value"),
            ), required = true),
            choice("combine", "Combine", listOf("and", "or"), "and"),
            bool("filterMode", "Filter mode", false, help = "drop false items instead of routing them"),
        ),
        outputs = listOf(PORT_TRUE, PORT_FALSE),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val item = input.item
        val rows = ctx.rows("conditions")
        if (rows.isEmpty()) throw NodeException("If: at least one condition is required")
        val results = rows.map { r ->
            val field = r.str("field") ?: throw NodeException("If: condition field is required")
            evalCondition(item.field(field), r.str("op") ?: "eq", r.str("value") ?: "")
        }
        val ok = if (ctx.str("combine") == "or") results.any { it } else results.all { it }
        return when {
            ok -> route(PORT_TRUE, item)
            ctx.bool("filterMode") -> NONE
            else -> route(PORT_FALSE, item)
        }
    }
}

object SwitchNode : Node() {
    const val FALLBACK = "fallback"
    override val spec = NodeSpec(
        id = "logic.switch", name = "Switch", kind = NodeKind.LOGIC,
        description = "Route an item to the output named by the case its field value matches, else to fallback.",
        params = listOf(
            text("field", "Field", required = true, help = "dotted path whose value is matched"),
            labels("cases", "Cases", required = true, definesPorts = true, help = "one output per case"),
            choice("matchMode", "Match", listOf("equals", "contains", "regex"), "equals"),
            bool("ignoreCase", "Ignore case", true),
        ),
        outputs = listOf(FALLBACK),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val item = input.item
        val value = item.field(ctx.req("field")).asText()
        val ic = ctx.bool("ignoreCase")
        val mode = ctx.str("matchMode")
        val port = ctx.labels("cases").firstOrNull { case ->
            when (mode) {
                "contains" -> value.contains(case, ic)
                "regex" -> (try { Regex(case, if (ic) setOf(RegexOption.IGNORE_CASE) else emptySet()) } catch (e: Exception) { throw NodeException("Invalid regex '$case': ${e.message}") }).containsMatchIn(value)
                else -> value.equals(case, ic)
            }
        } ?: FALLBACK
        return route(port, item)
    }
}
