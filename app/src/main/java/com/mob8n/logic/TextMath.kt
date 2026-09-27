package com.mob8n.logic

import com.mob8n.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object SetFieldsNode : Node() {
    override val spec = NodeSpec(
        id = "logic.set_fields", name = "Set Fields", kind = NodeKind.LOGIC,
        description = "Set, remove or rename fields on each item; {{templates}} keep their JSON type.",
        params = listOf(
            rows("set", "Set", listOf(text("name", "Name", required = true), text("value", "Value", help = "{{field}} keeps numbers/objects"))),
            labels("remove", "Remove fields"),
            rows("rename", "Rename", listOf(text("from", "From", required = true), text("to", "To", required = true))),
            bool("keepOnlySet", "Keep only set fields", false),
        ),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val src = input.item
        val m = LinkedHashMap<String, JsonElement>(if (ctx.bool("keepOnlySet")) emptyMap() else src)
        // raw rows: renderJson per cell so "{{count}}" stays a number and "{{$json}}" an object
        for (r in (ctx.raw("set") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
            val name = ctx.render(r.str("name") ?: continue).trim()
            if (name.isEmpty()) continue
            m[name] = ctx.renderJson(r.str("value") ?: "")
        }
        for (r in ctx.rows("rename")) {
            val from = r.str("from") ?: continue; val to = r.str("to") ?: continue
            if (from in m && from != to) m[to] = m.remove(from)!!
        }
        for (k in ctx.labels("remove")) m.remove(k)
        return out(JsonObject(m))
    }
}

object TemplateNode : Node() {
    override val spec = NodeSpec(
        id = "logic.template", name = "Template", kind = NodeKind.LOGIC,
        description = "Render a multiline {{template}} into one text field (build a prompt or message).",
        params = listOf(multiline("template", "Template", required = true), text("outputField", "Output field", "text")),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult =
        out(input.item.add((ctx.strOrNull("outputField") ?: "text") to ctx.str("template")))
}

object JsonNode : Node() {
    override val spec = NodeSpec(
        id = "logic.json", name = "JSON", kind = NodeKind.LOGIC,
        description = "Parse a JSON string field into a value (optionally spread into the item), or stringify a field.",
        params = listOf(
            choice("mode", "Mode", listOf("parse", "stringify"), "parse"),
            text("field", "Field", required = true),
            text("outputField", "Output field", help = "default: same as field"),
            bool("spread", "Spread object fields into item", false, visibleWhen = whenIs("mode", "parse")),
        ),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val item = input.item
        val field = ctx.req("field"); val outF = ctx.strOrNull("outputField") ?: field
        val v = item.field(field)
        return out(when (ctx.str("mode")) {
            "stringify" -> item.add(outF to (v ?: JsonNull).let { if (it is JsonPrimitive && it.isString) it.content else JSON.encodeToString(JsonElement.serializer(), it) })
            else -> {
                val s = v.asText()
                val parsed = if (v is JsonObject || v is JsonArray) v
                else try { JSON.parseToJsonElement(s) } catch (e: Exception) { throw NodeException("Invalid JSON in '$field': ${e.message?.lineSequence()?.first()}") }
                if (ctx.bool("spread") && parsed is JsonObject) item.addAll(parsed) else item.add(outF to parsed)
            }
        })
    }
}

object TextNode : Node() {
    override val spec = NodeSpec(
        id = "logic.text", name = "Text", kind = NodeKind.LOGIC,
        description = "String operations: regex extract/replace, case, trim, split/join, length, truncate, slugify.",
        params = listOf(
            choice("op", "Operation", listOf("regex_extract", "regex_extract_all", "replace", "upper", "lower", "trim", "split", "join", "length", "truncate", "slugify"), "trim"),
            text("input", "Input", "{{text}}"),
            text("pattern", "Pattern", help = "regex for regex_extract/replace", visibleWhen = whenIs("op", "regex_extract", "regex_extract_all", "replace")),
            text("replacement", "Replacement", help = "\$1 for groups", visibleWhen = whenIs("op", "replace")),
            text("separator", "Separator", ",", visibleWhen = whenIs("op", "split", "join")),
            number("maxLength", "Max length", 100.0, min = 0.0, visibleWhen = whenIs("op", "truncate")),
            text("outputField", "Output field", "text"),
        ),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        // renderJson keeps an array input as an array (join/length); everything else works on text
        val raw = ctx.renderJson(ctx.raw("input").asText().ifEmpty { "{{text}}" })
        val s = raw.asText()
        val outF = ctx.strOrNull("outputField") ?: "text"
        val result: JsonElement = when (val op = ctx.str("op")) {
            "regex_extract" -> compileRegex(ctx.req("pattern")).find(s)?.let { m -> JsonPrimitive(m.groupValues.getOrNull(1) ?: m.value) } ?: JsonNull
            "regex_extract_all" -> JsonArray(compileRegex(ctx.req("pattern")).findAll(s).map { m -> JsonPrimitive(m.groupValues.getOrNull(1) ?: m.value) }.toList())
            "replace" -> {
                val repl = ctx.str("replacement"); val re = compileRegex(ctx.req("pattern"))
                // a lone '$' / dangling '\' is IllegalArgumentException; '$5' with no group 5 is IndexOutOfBoundsException
                fun bad(e: RuntimeException): Nothing = throw NodeException("Text replace: bad replacement '$repl' (use \\\$ for a literal $, \$1 for groups): ${e.message}", e)
                JsonPrimitive(try { re.replace(s, repl) } catch (e: IllegalArgumentException) { bad(e) } catch (e: IndexOutOfBoundsException) { bad(e) })
            }
            "upper" -> JsonPrimitive(s.uppercase())
            "lower" -> JsonPrimitive(s.lowercase())
            "trim" -> JsonPrimitive(s.trim())
            "split" -> JsonArray(s.split(ctx.str("separator").ifEmpty { "," }).map { JsonPrimitive(it) })
            "join" -> JsonPrimitive((raw as? JsonArray ?: throw NodeException("Text join: input is not an array")).joinToString(ctx.str("separator")) { it.asText() })
            "length" -> JsonPrimitive(if (raw is JsonArray) raw.size else s.length)
            "truncate" -> JsonPrimitive(s.take((ctx.int("maxLength") ?: 100).coerceAtLeast(0)))
            "slugify" -> JsonPrimitive(s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
            else -> throw NodeException("Text: unknown op '$op'")
        }
        return out(input.item.add(outF to result))
    }
}

object MathNode : Node() {
    override val spec = NodeSpec(
        id = "logic.math", name = "Math", kind = NodeKind.LOGIC,
        description = "Evaluate an arithmetic expression (+ - * / % ^, parentheses, min max abs round floor ceil sqrt pow).",
        params = listOf(
            text("expression", "Expression", required = true, help = "e.g. ({{battery}} - 20) * 2"),
            text("outputField", "Output field", "result"),
            number("decimals", "Decimals", 2.0, min = 0.0, max = 15.0),
        ),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val v = eval(ctx.req("expression"))
        val d = (ctx.int("decimals") ?: 2).coerceIn(0, 15)
        val rounded = BigDecimal(v).setScale(d, RoundingMode.HALF_UP).toDouble()
        return out(input.item.add((ctx.strOrNull("outputField") ?: "result") to num(rounded)))
    }

    private val FUNCS: Map<String, (List<Double>) -> Double> = mapOf(
        "min" to { a -> a.minOrNull() ?: throw NodeException("min() needs an argument") },
        "max" to { a -> a.maxOrNull() ?: throw NodeException("max() needs an argument") },
        "abs" to { a -> Math.abs(one(a, "abs")) },
        "round" to { a -> Math.rint(one(a, "round")) },   // ponytail: round() = nearest even on .5 (Math.rint); upgrade = HALF_UP if users complain
        "floor" to { a -> Math.floor(one(a, "floor")) },
        "ceil" to { a -> Math.ceil(one(a, "ceil")) },
        "sqrt" to { a -> one(a, "sqrt").let { if (it < 0) throw NodeException("sqrt of negative number") else Math.sqrt(it) } },
        "pow" to { a -> if (a.size != 2) throw NodeException("pow() needs 2 arguments") else Math.pow(a[0], a[1]) },
    )
    private fun one(a: List<Double>, f: String) = a.singleOrNull() ?: throw NodeException("$f() needs exactly 1 argument")

    /** Recursive descent: expr = term (('+'|'-') term)*; term = unary (('*'|'/'|'%') unary)*; unary = ('-'|'+') unary | atom ('^' unary)? */
    fun eval(src: String): Double {
        val s = src.trim()
        if (s.isEmpty()) throw NodeException("Math: expression is empty")
        val p = Parser(s)
        val v = p.expr()
        p.skipWs()
        if (p.i < s.length) p.fail("unexpected '${p.peek()}'")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(msg: String): Nothing = throw NodeException("Math: $msg at position $i in '$s'")
        private fun check(d: Double): Double { if (d.isNaN() || d.isInfinite()) throw NodeException("Math: result is not a finite number"); return d }

        fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return check(v)
                }
            }
        }
        private fun term(): Double {
            var v = unary()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { i++; v *= unary() }
                    '/' -> { i++; val r = unary(); if (r == 0.0) throw NodeException("Math: division by zero"); v /= r }
                    '%' -> { i++; val r = unary(); if (r == 0.0) throw NodeException("Math: division by zero"); v %= r }
                    else -> return check(v)
                }
            }
        }
        private fun unary(): Double {
            skipWs()
            if (peek() == '-') { i++; return -unary() }
            if (peek() == '+') { i++; return unary() }
            val base = atom()
            skipWs()
            if (peek() == '^') { i++; return check(Math.pow(base, unary())) }
            return base
        }
        private fun atom(): Double {
            skipWs()
            val c = peek()
            when {
                c == '(' -> { i++; val v = expr(); skipWs(); if (peek() != ')') fail("expected ')'"); i++; return v }
                c.isDigit() || c == '.' -> {
                    val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                        var j = i + 1
                        if (j < s.length && (s[j] == '-' || s[j] == '+')) j++
                        if (j < s.length && s[j].isDigit()) { i = j; while (i < s.length && s[i].isDigit()) i++ }
                    }
                    return s.substring(st, i).toDoubleOrNull() ?: fail("bad number '${s.substring(st, i)}'")
                }
                c.isLetter() -> {
                    val st = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(st, i).lowercase()
                    if (name == "pi") return Math.PI
                    if (name == "e") return Math.E
                    val f = FUNCS[name] ?: fail("unknown function '$name'")
                    skipWs(); if (peek() != '(') fail("expected '(' after $name"); i++
                    val args = ArrayList<Double>()
                    skipWs()
                    if (peek() != ')') { args += expr(); skipWs(); while (peek() == ',') { i++; args += expr(); skipWs() } }
                    if (peek() != ')') fail("expected ')'"); i++
                    return check(f(args))
                }
                c == '\u0000' -> fail("unexpected end of expression")
                else -> fail("unexpected '$c'")
            }
        }
    }
}

object DateNode : Node() {
    override val spec = NodeSpec(
        id = "logic.date", name = "Date", kind = NodeKind.LOGIC,
        description = "Format a date, add time, diff two dates in minutes, or test is_between (overnight aware) / is_weekday / is_weekend.",
        params = listOf(
            choice("op", "Operation", listOf("format", "diff_minutes", "is_between", "is_weekday", "is_weekend", "add"), "format"),
            text("input", "Input", "{{\$now}}", help = "ISO date-time, epoch seconds or ms, yyyy-MM-dd or HH:mm"),
            text("input2", "Second input", visibleWhen = whenIs("op", "diff_minutes")),
            text("pattern", "Pattern", help = "java DateTimeFormatter, default yyyy-MM-dd HH:mm", visibleWhen = whenIs("op", "format")),
            clockTime("from", "From", "22:00", visibleWhen = whenIs("op", "is_between")),
            clockTime("to", "To", "06:00", visibleWhen = whenIs("op", "is_between")),
            number("amount", "Amount", 0.0, visibleWhen = whenIs("op", "add")),
            choice("unit", "Unit", listOf("minutes", "hours", "days"), "minutes", visibleWhen = whenIs("op", "add")),
            text("outputField", "Output field", "date"),
        ),
        outputs = listOf(MAIN, PORT_TRUE, PORT_FALSE),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val item = input.item
        val outF = ctx.strOrNull("outputField") ?: "date"
        val now = ctx.nowMs()
        val t = parse(ctx.strOrNull("input") ?: "", ctx.zone, now)
        fun yesNo(b: Boolean) = route(if (b) PORT_TRUE else PORT_FALSE, item.add(outF to b))
        return when (val op = ctx.str("op")) {
            "format" -> out(item.add(outF to (try { DateTimeFormatter.ofPattern(ctx.strOrNull("pattern") ?: "yyyy-MM-dd HH:mm") } catch (e: Exception) { throw NodeException("Date: bad pattern: ${e.message}") }).format(t)))
            "diff_minutes" -> {
                val t2 = parse(ctx.strOrNull("input2") ?: throw NodeException("Date: second input is required"), ctx.zone, now)
                out(item.add(outF to num((t2.toInstant().toEpochMilli() - t.toInstant().toEpochMilli()) / 60_000.0)))
            }
            "is_between" -> yesNo(isBetween(t.toLocalTime(), hhmm(ctx.str("from")), hhmm(ctx.str("to"))))
            "is_weekday" -> yesNo(t.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            "is_weekend" -> yesNo(t.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            "add" -> {
                val n = ctx.long("amount") ?: 0L
                val r = when (ctx.str("unit")) { "hours" -> t.plusHours(n); "days" -> t.plusDays(n); else -> t.plusMinutes(n) }
                out(item.add(outF to r.toOffsetDateTime().toString()))
            }
            else -> throw NodeException("Date: unknown op '$op'")
        }
    }

    /** from<=to: [from,to); from>to (overnight): t>=from || t<to; from==to: always true. */
    fun isBetween(t: LocalTime, from: LocalTime, to: LocalTime): Boolean = when {
        from == to -> true
        from < to -> !t.isBefore(from) && t.isBefore(to)
        else -> !t.isBefore(from) || t.isBefore(to)
    }

    fun hhmm(s: String): LocalTime = try { LocalTime.parse(s.trim(), DateTimeFormatter.ofPattern("H:mm")) } catch (e: Exception) { throw NodeException("Date: '$s' is not HH:mm") }

    /** Accepts: blank (= now), epoch seconds or ms (values < 1e10 are seconds; digit strings shorter than 9 are rejected), ISO offset/zoned/local date-time, yyyy-MM-dd, HH:mm (today). */
    fun parse(s0: String, zone: ZoneId, nowMs: Long): ZonedDateTime {
        val s = s0.trim()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (s.isEmpty()) return now
        s.toLongOrNull()?.let {
            if (s.trimStart('-').length < 9) throw NodeException("Date: '$s' is not an epoch (seconds or ms) or ISO date")
            // ponytail: 3 copies of the 1e10 seconds/ms heuristic (here, data/MiscNodes.parseBase, actions/Intents.parseStart); upgrade path: core/Dates.kt epochToMs
            val ms = if (abs(it) < 10_000_000_000L) it * 1000 else it
            return Instant.ofEpochMilli(ms).atZone(zone)
        }
        runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(zone) }
        runCatching { return ZonedDateTime.parse(s).withZoneSameInstant(zone) }
        runCatching { return LocalDateTime.parse(s).atZone(zone) }
        runCatching { return LocalDate.parse(s).atStartOfDay(zone) }
        runCatching { return now.with(LocalTime.parse(s, DateTimeFormatter.ofPattern("H:mm"))) }
        throw NodeException("Date: cannot parse '$s'")
    }
}
