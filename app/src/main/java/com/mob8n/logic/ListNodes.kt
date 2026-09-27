package com.mob8n.logic

import com.mob8n.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object MergeNode : Node() {
    override val spec = NodeSpec(
        id = "logic.merge", name = "Merge", kind = NodeKind.LOGIC,
        description = "Combine items from inputs a and b: append, pair by position (b wins), or join by a field.",
        params = listOf(
            choice("mode", "Mode", listOf("append", "combine_by_position", "combine_by_field"), "append"),
            text("field", "Join field", help = "combine_by_field: field present on both sides", visibleWhen = whenIs("mode", "combine_by_field")),
            bool("requireBoth", "Require both inputs", false, help = "emit nothing when either side is empty"),
        ),
        inputs = listOf(PORT_A, PORT_B),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = input.byPort[PORT_A].orEmpty(); val b = input.byPort[PORT_B].orEmpty()
        if (ctx.bool("requireBoth") && (a.isEmpty() || b.isEmpty())) return NONE
        return out(when (ctx.str("mode")) {
            "combine_by_position" -> (0 until maxOf(a.size, b.size)).map { i -> (a.getOrNull(i) ?: EMPTY).addAll(b.getOrNull(i) ?: EMPTY) }
            "combine_by_field" -> {
                val f = ctx.req("field")
                // left join: a rows without a partner (or without the key) pass through unchanged; absent keys never match
                a.flatMap { x ->
                    val k = x.field(f)
                    if (k.isAbsent()) listOf(x)
                    else b.filter { y -> !y.field(f).isAbsent() && y.field(f).asText() == k.asText() }.map { y -> x.addAll(y) }.ifEmpty { listOf(x) }
                }
            }
            else -> a + b
        })
    }
}

object SplitBatchesNode : Node() {
    override val spec = NodeSpec(
        id = "logic.split_batches", name = "Split in Batches", kind = NodeKind.LOGIC,
        description = "Emit one item per batch of N input items, then one item on done with everything.",
        params = listOf(number("batchSize", "Batch size", 1.0, min = 1.0)),
        outputs = listOf(MAIN, PORT_DONE),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val size = (ctx.int("batchSize") ?: 1).coerceAtLeast(1)
        val batches = input.items.chunked(size)
        return ports(
            MAIN to batches.mapIndexed { i, b -> item("batch" to JsonArray(b), "index" to i, "total" to batches.size, "isLast" to (i == batches.lastIndex)) },
            PORT_DONE to listOf(item("total" to batches.size, "count" to input.items.size, "items" to JsonArray(input.items))),
        )
    }
}

object FlattenNode : Node() {
    override val spec = NodeSpec(
        id = "logic.flatten", name = "Flatten", kind = NodeKind.LOGIC,
        description = "Explode an array field into one item per element.",
        params = listOf(
            text("field", "Array field", required = true),
            text("into", "Element field", "value", help = "field name for non-object elements"),
            bool("keepParent", "Keep parent fields", true),
        ),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val field = ctx.req("field"); val into = ctx.strOrNull("into") ?: "value"; val keep = ctx.bool("keepParent")
        return out(input.items.flatMap { it ->
            val v = it.field(field)
            val els: List<JsonElement> = when {
                v.isAbsent() -> emptyList()
                v is JsonArray -> v
                else -> listOf(v!!)
            }
            // ponytail: only a bare top-level key is stripped; a nested array path stays in the output so its siblings survive. upgrade = deep-remove at path if fan-out size matters
            val base = if (!keep) EMPTY else if ('.' in field || '[' in field) it else it.without(listOf(field))
            els.map { el -> if (el is JsonObject) base.addAll(el) else base.add(into to el) }
        })
    }
}

object AggregateNode : Node() {
    override val spec = NodeSpec(
        id = "logic.aggregate", name = "Aggregate", kind = NodeKind.LOGIC,
        description = "Reduce all items to one: collect items or a field's values, count/sum/avg/min/max a field, or join values.",
        params = listOf(
            choice("mode", "Mode", listOf("all_items", "field_values", "count", "sum", "avg", "min", "max", "join"), "all_items"),
            text("field", "Field", help = "for field_values/sum/avg/min/max/join"),
            text("separator", "Separator", ", ", visibleWhen = whenIs("mode", "join")),
            text("outputField", "Output field", "result"),
        ),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val mode = ctx.str("mode"); val outF = ctx.strOrNull("outputField") ?: "result"
        val items = input.items
        if (mode == "all_items") return out(item(outF to JsonArray(items), "count" to items.size))
        if (mode == "count") return out(item(outF to items.size))
        val field = ctx.req("field")
        val values = items.map { it.field(field) }.filter { !it.isAbsent() }.map { it!! }
        fun nums(): List<Double> = values.map { it.asDouble() ?: throw NodeException("Aggregate: '$field' value '${it.asText()}' is not a number") }
        val result: JsonElement = when (mode) {
            "field_values" -> JsonArray(values)
            "join" -> JsonPrimitive(values.joinToString(ctx.str("separator")) { it.asText() })
            "sum" -> num(nums().sum())
            "avg" -> num(nums().let { if (it.isEmpty()) 0.0 else it.average() })
            "min" -> nums().minOrNull()?.let(::num) ?: kotlinx.serialization.json.JsonNull
            "max" -> nums().maxOrNull()?.let(::num) ?: kotlinx.serialization.json.JsonNull
            else -> throw NodeException("Aggregate: unknown mode '$mode'")
        }
        return out(item(outF to result, "count" to items.size))
    }
}

/** Integral doubles become JSON integers so templates print "3" instead of "3.0". */
internal fun num(d: Double): JsonPrimitive = if (d.isFinite() && d == Math.rint(d) && Math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

object SortNode : Node() {
    override val spec = NodeSpec(
        id = "logic.sort", name = "Sort", kind = NodeKind.LOGIC,
        description = "Sort items by a field, ascending or descending (numeric when every value is a number).",
        params = listOf(text("field", "Field", required = true), choice("order", "Order", listOf("asc", "desc"), "asc")),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val f = ctx.req("field")
        val keyed = input.items.map { it to it.field(f) }
        val numeric = keyed.all { it.second.asDouble() != null }
        val cmp: Comparator<Pair<Item, JsonElement?>> =
            if (numeric) compareBy { it.second.asDouble() } else compareBy(nullsLast()) { it.second.asTextOrNull() }
        val sorted = keyed.sortedWith(cmp).map { it.first }
        return out(if (ctx.str("order") == "desc") sorted.asReversed() else sorted)
    }
}

object LimitNode : Node() {
    override val spec = NodeSpec(
        id = "logic.limit", name = "Limit", kind = NodeKind.LOGIC,
        description = "Keep only the first or last N items.",
        params = listOf(number("count", "Count", 10.0, min = 1.0), choice("from", "From", listOf("first", "last"), "first")),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val n = (ctx.int("count") ?: 10).coerceAtLeast(1)
        return out(if (ctx.str("from") == "last") input.items.takeLast(n) else input.items.take(n))
    }
}

object UniqueNode : Node() {
    override val spec = NodeSpec(
        id = "logic.unique", name = "Unique", kind = NodeKind.LOGIC,
        description = "Remove duplicate items, compared by the given fields (or the whole item when none).",
        params = listOf(labels("fields", "Compare fields", help = "empty = whole item")),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val fields = ctx.labels("fields")
        return out(input.items.distinctBy { it -> if (fields.isEmpty()) it.toString() else fields.map { f -> it.field(f).asText() } })
    }
}

object RepeatNode : Node() {
    override val spec = NodeSpec(
        id = "logic.repeat", name = "Repeat", kind = NodeKind.LOGIC,
        description = "Emit each item N times with a repeatIndex field.",
        params = listOf(number("times", "Times", 2.0, min = 1.0, max = 100.0)),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val n = (ctx.int("times") ?: 2).coerceIn(1, 100)
        return out((0 until n).map { input.item.add("repeatIndex" to it) })
    }
}
