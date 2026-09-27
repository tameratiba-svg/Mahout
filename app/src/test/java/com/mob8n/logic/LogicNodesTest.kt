package com.mob8n.logic

import com.mob8n.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

class LogicNodesTest {
    private val f = Fake()

    private inline fun expectError(contains: String = "", block: () -> Unit) {
        try { block(); fail("expected NodeException") } catch (e: NodeException) { assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains)) }
    }

    // ---- catalog ----
    @Test fun catalogHas27UniqueIdsMatchingDesign() {
        val ids = LogicNodes.all.map { it.spec.id }
        assertEquals(27, ids.size); assertEquals(27, ids.toSet().size)
        assertEquals(listOf("logic.if", "logic.switch", "logic.merge", "logic.split_batches", "logic.flatten", "logic.aggregate", "logic.sort", "logic.limit", "logic.unique",
            "logic.dedupe_window", "logic.rate_limit", "logic.delay", "logic.wait_until", "logic.wait_approval", "logic.set_fields", "logic.template", "logic.json", "logic.text",
            "logic.math", "logic.date", "logic.counter", "logic.repeat", "logic.note", "logic.run_workflow", "logic.stop_error", "logic.execute_once", "logic.js"), ids)
        LogicNodes.all.forEach { assertEquals(NodeKind.LOGIC, it.spec.kind); it.spec.toolDef() }   // schema derivation never throws
        assertEquals(listOf("urgent", "spam", "fallback"), SwitchNode.spec.outputPorts(params("cases" to listOf("urgent", "spam"))))
    }

    // ---- if ----
    private fun cond(field: String, op: String, value: String = "") = item("field" to field, "op" to op, "value" to value)

    @Test fun ifNumericVsStringCompare() = runTest {
        val gt = params("conditions" to rowsOf(cond("n", "gt", "9")))
        assertEquals(PORT_TRUE, f.port(IfNode, gt, item("n" to 10)))          // numeric: 10 > 9
        assertEquals(PORT_TRUE, f.port(IfNode, gt, item("n" to "10")))        // both parse -> numeric
        assertEquals(PORT_TRUE, f.port(IfNode, params("conditions" to rowsOf(cond("n", "lt", "b"))), item("n" to "abc")))   // string compare
        assertEquals(PORT_FALSE, f.port(IfNode, gt, item("n" to 9)))   // 9 > 9 is false
        assertEquals(PORT_TRUE, f.port(IfNode, params("conditions" to rowsOf(cond("n", "eq", "5.0"))), item("n" to 5)))
        assertEquals(PORT_TRUE, f.port(IfNode, params("conditions" to rowsOf(cond("s", "eq", "a"))), item("s" to "a")))
        assertEquals(PORT_FALSE, f.port(IfNode, params("conditions" to rowsOf(cond("s", "eq", "A"))), item("s" to "a")))
    }

    @Test fun ifAllOps() = runTest {
        val it0 = item("t" to "Hello World", "e" to "", "arr" to listOf("x", "y"), "b" to true, "meta" to mapOf("artist" to "Band"))
        suspend fun ok(field: String, op: String, v: String = "") = PORT_TRUE == f.port(IfNode, params("conditions" to rowsOf(cond(field, op, v))), it0)
        assertTrue(ok("t", "contains", "World")); assertFalse(ok("t", "not_contains", "World"))
        assertTrue(ok("arr", "contains", "y")); assertTrue(ok("arr", "not_contains", "z"))
        assertTrue(ok("t", "starts_with", "Hell")); assertTrue(ok("t", "ends_with", "rld"))
        assertTrue(ok("t", "regex", "^H.*d$")); assertFalse(ok("t", "regex", "^x"))
        assertTrue(ok("t", "exists")); assertTrue(ok("nope", "not_exists")); assertFalse(ok("nope", "exists"))
        assertTrue(ok("e", "empty")); assertTrue(ok("nope", "empty")); assertTrue(ok("t", "not_empty")); assertFalse(ok("arr", "empty"))
        assertTrue(ok("b", "is_true")); assertFalse(ok("b", "is_false")); assertFalse(ok("nope", "is_false"))
        assertTrue(ok("meta.artist", "eq", "Band")); assertTrue(ok("\$json.meta.artist", "eq", "Band")); assertTrue(ok("arr[1]", "eq", "y"))
        assertTrue(ok("t", "neq", "x")); assertTrue(ok("t", "lte", "Hello World")); assertTrue(ok("t", "gte", "Hello World")); assertFalse(ok("t", "lt", "A"))
        expectError("regex") { f.port(IfNode, params("conditions" to rowsOf(cond("t", "regex", "["))), it0) }
        expectError("operator") { f.port(IfNode, params("conditions" to rowsOf(cond("t", "bogus"))), it0) }
    }

    @Test fun ifCombineAndFilterMode() = runTest {
        val two = rowsOf(cond("a", "eq", "1"), cond("b", "eq", "2"))
        assertEquals(PORT_FALSE, f.port(IfNode, params("conditions" to two), item("a" to 1, "b" to 3)))
        assertEquals(PORT_TRUE, f.port(IfNode, params("conditions" to two, "combine" to "or"), item("a" to 1, "b" to 3)))
        val out = f.run(IfNode, params("conditions" to two, "filterMode" to true), listOf(item("a" to 1, "b" to 2), item("a" to 0, "b" to 0)))
        assertEquals(1, out[PORT_TRUE]!!.size); assertNull(out[PORT_FALSE])
        expectError("condition") { run { f.port(IfNode, params("conditions" to emptyList<Item>()), EMPTY) } }
    }

    @Test fun ifValueTemplated() = runTest {
        assertEquals(PORT_TRUE, f.port(IfNode, params("conditions" to rowsOf(cond("a", "eq", "{{b}}"))), item("a" to 7, "b" to 7)))
    }

    // ---- switch ----
    @Test fun switchEqualsContainsRegexFallback() = runTest {
        val p = params("field" to "label", "cases" to listOf("Urgent", "spam"))
        assertEquals("Urgent", f.port(SwitchNode, p, item("label" to "urgent")))              // ignoreCase default
        assertEquals("fallback", f.port(SwitchNode, p.add("ignoreCase" to false), item("label" to "urgent")))
        assertEquals("fallback", f.port(SwitchNode, p, item("label" to "normal")))
        assertEquals("spam", f.port(SwitchNode, p.add("matchMode" to "contains"), item("label" to "this is SPAM")))
        val rx = params("field" to "label", "cases" to listOf("^ur.*", "sp.m"), "matchMode" to "regex")
        assertEquals("sp.m", f.port(SwitchNode, rx, item("label" to "Spam")))
        assertEquals("^ur.*", f.port(SwitchNode, rx, item("label" to "urgent")))
        expectError("regex") { run { f.port(SwitchNode, params("field" to "label", "cases" to listOf("["), "matchMode" to "regex"), item("label" to "x")) } }
    }

    // ---- merge ----
    @Test fun mergeModes() = runTest {
        val a = listOf(item("id" to 1, "x" to "a1"), item("id" to 2, "x" to "a2"))
        val b = listOf(item("id" to 2, "y" to "b2"), item("id" to 9, "y" to "b9"), item("id" to 3))
        val bp = mapOf(PORT_A to a, PORT_B to b)
        assertEquals(5, f.run(MergeNode, params(), a + b, bp)[MAIN]!!.size)
        val pos = f.run(MergeNode, params("mode" to "combine_by_position"), a + b, bp)[MAIN]!!
        assertEquals(3, pos.size); assertEquals(2, pos[0].num("id")!!.toInt()); assertEquals("a1", pos[0].str("x")); assertEquals("b2", pos[0].str("y"))
        assertEquals(item("id" to 3), pos[2])
        val byF = f.run(MergeNode, params("mode" to "combine_by_field", "field" to "id"), a + b, bp)[MAIN]!!
        assertEquals(2, byF.size); assertNull(byF[0].str("y")); assertEquals("b2", byF[1].str("y"))
        assertTrue(f.run(MergeNode, params("requireBoth" to true), a, mapOf(PORT_A to a, PORT_B to emptyList()))[MAIN].isNullOrEmpty())
        expectError("required") { run { f.run(MergeNode, params("mode" to "combine_by_field"), a + b, bp) } }
    }

    @Test fun mergeByFieldSkipsAbsentKeys() = runTest {
        // rows lacking the join field must pass through, not cross-join with every other key-less row (or with "" keys)
        val a2 = listOf(item("x" to "a1"), item("x" to "a2"), item("id" to 2, "x" to "a3"), item("id" to "", "x" to "a4"))
        val b2 = listOf(item("y" to "b1"), item("y" to "b2"), item("id" to 2, "y" to "b3"))
        val r = f.run(MergeNode, params("mode" to "combine_by_field", "field" to "id"), a2 + b2, mapOf(PORT_A to a2, PORT_B to b2))[MAIN]!!
        assertEquals(4, r.size)
        assertNull(r[0].str("y")); assertNull(r[1].str("y")); assertEquals("b3", r[2].str("y")); assertNull(r[3].str("y"))
    }

    // ---- split_batches / flatten / aggregate / sort / limit / unique / repeat ----
    @Test fun splitBatchesShape() = runTest {
        val items = (1..5).map { item("n" to it) }
        val out = f.run(SplitBatchesNode, params("batchSize" to 2), items)
        val main = out[MAIN]!!; val done = out[PORT_DONE]!!.single()
        assertEquals(3, main.size)
        assertEquals(2, main[0]["batch"]!!.jsonArray.size); assertEquals(0, main[0].num("index")!!.toInt()); assertEquals(false, main[0].bool("isLast"))
        assertEquals(1, main[2]["batch"]!!.jsonArray.size); assertEquals(true, main[2].bool("isLast")); assertEquals(3, main[2].num("total")!!.toInt())
        assertEquals(5, done["items"]!!.jsonArray.size); assertEquals(3, done.num("total")!!.toInt())
        assertEquals(5, f.run(SplitBatchesNode, params(), items)[MAIN]!!.size)   // default 1 per batch
    }

    @Test fun flattenShapes() = runTest {
        val src = item("id" to 1, "tags" to listOf("a", "b"), "objs" to listOf(mapOf("k" to 1), mapOf("k" to 2)))
        val tags = f.run(FlattenNode, params("field" to "tags", "into" to "tag"), listOf(src))[MAIN]!!
        assertEquals(2, tags.size); assertEquals("a", tags[0].str("tag")); assertEquals(1, tags[0].num("id")!!.toInt()); assertNull(tags[0]["tags"])
        val objs = f.run(FlattenNode, params("field" to "objs", "keepParent" to false), listOf(src))[MAIN]!!
        assertEquals(item("k" to 2), objs[1])
        assertTrue(f.run(FlattenNode, params("field" to "missing"), listOf(src))[MAIN]!!.isEmpty())
        assertEquals("x", f.run(FlattenNode, params("field" to "s"), listOf(item("s" to "x")))[MAIN]!!.single().str("value"))   // scalar = one element
        // nested array path with keepParent: siblings of the array (body.total/body.page) must survive
        val nested = f.run(FlattenNode, params("field" to "body.items"), listOf(item("body" to mapOf("total" to 42, "page" to 1, "items" to listOf(mapOf("k" to 1), mapOf("k" to 2))))))[MAIN]!!
        assertEquals(2, nested.size)
        assertEquals(42, nested[0]["body"]!!.jsonObject["total"]!!.jsonPrimitive.int); assertEquals(1, nested[0]["body"]!!.jsonObject["page"]!!.jsonPrimitive.int)
        assertEquals(1, nested[0].num("k")!!.toInt()); assertEquals(2, nested[1].num("k")!!.toInt())
    }

    @Test fun aggregateModes() = runTest {
        val items = listOf(item("v" to 3, "s" to "a"), item("v" to 1, "s" to "b"), item("v" to 2))
        val all = f.run(AggregateNode, params("outputField" to "events"), items)[MAIN]!!.single()
        assertEquals(3, all["events"]!!.jsonArray.size); assertEquals(3, all.num("count")!!.toInt())
        assertEquals(3, f.run(AggregateNode, params("mode" to "count"), items)[MAIN]!!.single().num("result")!!.toInt())
        suspend fun agg(mode: String, field: String = "v") = f.run(AggregateNode, params("mode" to mode, "field" to field), items)[MAIN]!!.single()["result"]
        assertEquals(JsonPrimitive(6), agg("sum")); assertEquals(JsonPrimitive(2), agg("avg")); assertEquals(JsonPrimitive(1), agg("min")); assertEquals(JsonPrimitive(3), agg("max"))
        assertEquals(JsonPrimitive("a, b"), agg("join", "s"))
        assertEquals(2, (agg("field_values", "s") as JsonArray).size)
        expectError("not a number") { agg("sum", "s") }
        expectError("required") { agg("sum", "") }
    }

    @Test fun sortLimitUniqueRepeat() = runTest {
        val items = listOf(item("n" to 10, "s" to "b"), item("n" to 9, "s" to "a"), item("n" to "100", "s" to "c"))
        assertEquals(listOf(9, 10, 100), f.run(SortNode, params("field" to "n"), items)[MAIN]!!.map { it.num("n")!!.toInt() })  // numeric although one is a string
        assertEquals(listOf("c", "b", "a"), f.run(SortNode, params("field" to "s", "order" to "desc"), items)[MAIN]!!.map { it.str("s") })
        val mixed = items + item("n" to "x")
        assertEquals("x", f.run(SortNode, params("field" to "n"), mixed)[MAIN]!!.last().str("n"))   // falls back to string compare
        assertEquals(listOf("b", "a"), f.run(LimitNode, params("count" to 2), items)[MAIN]!!.map { it.str("s") })
        assertEquals(listOf("c"), f.run(LimitNode, params("count" to 1, "from" to "last"), items)[MAIN]!!.map { it.str("s") })
        val dups = listOf(item("a" to 1, "b" to 1), item("a" to 1, "b" to 2), item("a" to 1, "b" to 1))
        assertEquals(2, f.run(UniqueNode, params(), dups)[MAIN]!!.size)
        assertEquals(1, f.run(UniqueNode, params("fields" to listOf("a")), dups)[MAIN]!!.size)
        val rep = f.run(RepeatNode, params("times" to 3), listOf(item("x" to 1)))[MAIN]!!
        assertEquals(3, rep.size); assertEquals(2, rep[2].num("repeatIndex")!!.toInt()); assertEquals(1, rep[2].num("x")!!.toInt())
    }

    // ---- dedupe_window / rate_limit / execute_once / counter (fake clock + in-memory state) ----
    @Test fun dedupeWindow() = runTest {
        val p = params("keyTemplate" to "{{title}}|{{artist}}", "windowMs" to 10_000)
        val song = item("title" to "Song", "artist" to "Band")
        assertEquals(MAIN, f.port(DedupeWindowNode, p, song))
        assertEquals(DedupeWindowNode.DUPLICATE, f.port(DedupeWindowNode, p, song))
        assertEquals(MAIN, f.port(DedupeWindowNode, p, item("title" to "Other", "artist" to "Band")))
        assertEquals(MAIN, f.port(DedupeWindowNode, p, song, instanceId = "n2"))   // state is per node instance
        f.nowMs += 9_999; assertEquals(DedupeWindowNode.DUPLICATE, f.port(DedupeWindowNode, p, song))
        f.nowMs += 1; assertEquals(MAIN, f.port(DedupeWindowNode, p, song))          // window elapsed
        assertEquals(DedupeWindowNode.DUPLICATE, f.port(DedupeWindowNode, params("keyTemplate" to ""), song))   // blank key falls back to the default template
    }

    @Test fun rateLimit() = runTest {
        val p = params("maxRuns" to 2, "perMs" to 1_000)
        assertEquals(MAIN, f.port(RateLimitNode, p)); assertEquals(MAIN, f.port(RateLimitNode, p))
        assertEquals(RateLimitNode.LIMITED, f.port(RateLimitNode, p))
        f.nowMs += 500; assertEquals(RateLimitNode.LIMITED, f.port(RateLimitNode, p))
        f.nowMs += 500; assertEquals(MAIN, f.port(RateLimitNode, p))                 // first two slid out of the window
        assertEquals(MAIN, f.port(RateLimitNode, p))
        assertEquals(RateLimitNode.LIMITED, f.port(RateLimitNode, p))
    }

    @Test fun executeOnce() = runTest {
        val p = params("windowMs" to 1_000)
        assertEquals(MAIN, f.run(ExecuteOnceNode, p, listOf(EMPTY)).keys.single())
        assertEquals(ExecuteOnceNode.SKIPPED, f.run(ExecuteOnceNode, p, listOf(EMPTY)).keys.single())
        f.nowMs += 1_000; assertEquals(MAIN, f.run(ExecuteOnceNode, p, listOf(EMPTY)).keys.single())
        val forever = params("windowMs" to 0)
        assertEquals(MAIN, f.run(ExecuteOnceNode, forever, listOf(EMPTY), instanceId = "n2").keys.single())
        f.nowMs += 99_999_999; assertEquals(ExecuteOnceNode.SKIPPED, f.run(ExecuteOnceNode, forever, listOf(EMPTY), instanceId = "n2").keys.single())
    }

    @Test fun counter() = runTest {
        assertEquals(JsonPrimitive(1), f.one(CounterNode, params())["count"])
        assertEquals(JsonPrimitive(3), f.one(CounterNode, params("step" to 2))["count"])
        assertEquals(JsonPrimitive(2), f.one(CounterNode, params("op" to "decrement"))["count"])
        assertEquals(JsonPrimitive(2), f.one(CounterNode, params("op" to "get"))["count"])
        assertEquals(JsonPrimitive(0), f.one(CounterNode, params("op" to "reset"))["count"])
        assertEquals(JsonPrimitive(1), f.one(CounterNode, params("name" to "shared", "outputField" to "c"))["c"])
        assertEquals(JsonPrimitive(2), f.one(CounterNode, params("name" to "shared"), instanceId = "n2")["count"])   // shared across instances
        assertEquals(JsonPrimitive(2), f.persistence.vars["shared"])
    }

    // ---- delay / wait_until / wait_approval ----
    @Test fun delayInlineVsSuspend() = runTest {
        val items = listOf(item("a" to 1))
        val quick = f.raw(DelayNode, params("delayMs" to 3_000), items)
        assertEquals(out(items), quick)
        val long = f.raw(DelayNode, params("delayMs" to 60_000), items) as NodeResult.Suspend
        assertEquals(SuspendKind.TIMER, long.kind); assertEquals(f.nowMs + 60_000, long.resumeAtMs)
        // default resume on timer passes items through
        val input = NodeInput(items)
        assertEquals(out(items), DelayNode.resume(f.ctx(DelayNode, params(), input), input, DECISION_TIMER, EMPTY))
    }

    @Test fun waitUntil() = runTest {
        val f2 = Fake(nowMs = java.time.ZonedDateTime.of(2024, 1, 10, 9, 30, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(), zone = ZoneId.of("Europe/Berlin"))
        val items = listOf(EMPTY)
        val later = f2.raw(WaitUntilNode, params("time" to "10:00"), items) as NodeResult.Suspend
        assertEquals(f2.nowMs + 30 * 60_000, later.resumeAtMs)
        val tomorrow = f2.raw(WaitUntilNode, params("time" to "09:00"), items) as NodeResult.Suspend
        assertEquals(f2.nowMs + (23 * 60 + 30) * 60_000, tomorrow.resumeAtMs)
        assertEquals(out(items), f2.raw(WaitUntilNode, params("time" to "09:00", "skipIfPast" to true), items))
        expectError("HH:mm") { run { f2.raw(WaitUntilNode, params("time" to "25:99"), items) } }
    }

    @Test fun waitApprovalSuspendAndResumeMapping() = runTest {
        val items = listOf(item("title" to "Song"))
        val s = f.raw(WaitApprovalNode, params("title" to "Add {{title}}?", "timeoutMs" to 5_000), items) as NodeResult.Suspend
        assertEquals(SuspendKind.APPROVAL, s.kind); assertEquals("Add Song?", s.title); assertEquals("""{"title":"Song"}""", s.text)
        assertEquals(listOf(DECISION_APPROVE, DECISION_DENY), s.choices); assertEquals(f.nowMs + 5_000, s.resumeAtMs)
        val input = NodeInput(items); val ctx = f.ctx(WaitApprovalNode, params(), input)
        assertEquals(mapOf(WaitApprovalNode.APPROVED to items), (WaitApprovalNode.resume(ctx, input, DECISION_APPROVE, EMPTY) as NodeResult.Out).ports)
        assertEquals(mapOf(WaitApprovalNode.DENIED to items), (WaitApprovalNode.resume(ctx, input, DECISION_DENY, EMPTY) as NodeResult.Out).ports)
        assertEquals(mapOf(WaitApprovalNode.TIMEOUT to items), (WaitApprovalNode.resume(ctx, input, DECISION_TIMEOUT, EMPTY) as NodeResult.Out).ports)
        expectError("decision") { run { WaitApprovalNode.resume(ctx, input, "maybe", EMPTY) } }
    }

    // ---- set_fields / template / json / text ----
    @Test fun setFieldsTypedValues() = runTest {
        val src = item("n" to 5, "old" to "x", "gone" to 1, "obj" to mapOf("k" to 1))
        val p = params(
            "set" to rowsOf(item("name" to "num", "value" to "{{n}}"), item("name" to "txt", "value" to "n={{n}}"), item("name" to "copy", "value" to "{{obj}}"), item("name" to "lit", "value" to "hello")),
            "rename" to rowsOf(item("from" to "old", "to" to "new")),
            "remove" to listOf("gone"),
        )
        val r = f.one(SetFieldsNode, p, src)
        assertEquals(JsonPrimitive(5), r["num"]); assertEquals(JsonPrimitive("n=5"), r["txt"]); assertEquals(item("k" to 1), r["copy"]); assertEquals(JsonPrimitive("hello"), r["lit"])
        assertEquals("x", r.str("new")); assertNull(r["old"]); assertNull(r["gone"]); assertEquals(JsonPrimitive(5), r["n"])
        val only = f.one(SetFieldsNode, p.add("keepOnlySet" to true), src)
        assertEquals(setOf("num", "txt", "copy", "lit"), only.keys)
    }

    @Test fun templateAndJson() = runTest {
        assertEquals("Hi Bob, 3 items", f.one(TemplateNode, params("template" to "Hi {{name}}, {{count}} items", "outputField" to "msg"), item("name" to "Bob", "count" to 3)).str("msg"))
        val parsed = f.one(JsonNode, params("field" to "body"), item("body" to """{"a":1,"b":[1,2]}"""))
        assertEquals(JsonPrimitive(1), parsed["body"]!!.path("a"))
        val spread = f.one(JsonNode, params("field" to "body", "spread" to true), item("body" to """{"a":1}""", "keep" to true))
        assertEquals(JsonPrimitive(1), spread["a"]); assertEquals(JsonPrimitive(true), spread["keep"])
        assertEquals("""{"a":1}""", f.one(JsonNode, params("mode" to "stringify", "field" to "o", "outputField" to "s"), item("o" to mapOf("a" to 1))).str("s"))
        expectError("Invalid JSON") { run { f.one(JsonNode, params("field" to "body"), item("body" to "{nope")) } }
    }

    @Test fun textOps() = runTest {
        suspend fun t(op: String, vararg extra: Pair<String, Any?>, src: Item = item("text" to "  Hello World 42 ")) =
            f.one(TextNode, params("op" to op, *extra), src)["text"]
        assertEquals(JsonPrimitive("Hello World 42"), t("trim"))
        assertEquals(JsonPrimitive("  HELLO WORLD 42 "), t("upper")); assertEquals(JsonPrimitive("  hello world 42 "), t("lower"))
        assertEquals(JsonPrimitive("42"), t("regex_extract", "pattern" to "(\\d+)"))
        assertEquals(JsonPrimitive("World"), t("regex_extract", "pattern" to "W\\w+"))
        assertEquals(JsonNull, t("regex_extract", "pattern" to "xyz"))
        assertEquals(listOf("Hello", "World"), (t("regex_extract_all", "pattern" to "[A-Z]\\w+") as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("  Hello There 42 "), t("replace", "pattern" to "W(or)ld", "replacement" to "There"))
        assertEquals(JsonPrimitive("  Hello or 42 "), t("replace", "pattern" to "W(or)ld", "replacement" to "\$1"))
        assertEquals(3, (t("split", "separator" to ",", src = item("text" to "a,b,c")) as JsonArray).size)
        assertEquals(JsonPrimitive("a|b"), t("join", "separator" to "|", "input" to "{{arr}}", src = item("arr" to listOf("a", "b"))))
        assertEquals(JsonPrimitive(2), t("length", "input" to "{{arr}}", src = item("arr" to listOf("a", "b"))))
        assertEquals(JsonPrimitive(5), t("length", src = item("text" to "abcde")))
        assertEquals(JsonPrimitive("Hel"), t("truncate", "maxLength" to 3, src = item("text" to "Hello")))
        assertEquals(JsonPrimitive("hello-world-42"), t("slugify"))
        assertEquals(JsonPrimitive("B"), t("upper", "input" to "{{other}}", "outputField" to "text", src = item("other" to "b")))
        expectError("not an array") { t("join") }
        expectError("required") { t("replace") }
        // bad replacement strings surface as a NodeException with a hint, not a raw Matcher error
        expectError("literal") { t("replace", "pattern" to "World", "replacement" to "\$") }
        expectError("literal") { t("replace", "pattern" to "World", "replacement" to "Price: \$5") }
        expectError("literal") { t("replace", "pattern" to "W(or)ld", "replacement" to "\$5") }
        expectError("literal") { t("replace", "pattern" to "World", "replacement" to "\\") }
        assertEquals(JsonPrimitive("  Hello $ 42 "), t("replace", "pattern" to "World", "replacement" to "\\\$"))   // escaped literal dollar works
    }

    // ---- math ----
    @Test fun mathEvaluator() = runTest {
        suspend fun m(e: String, decimals: Int = 2) = f.one(MathNode, params("expression" to e, "decimals" to decimals), item("a" to 7, "b" to "2"))["result"]
        assertEquals(JsonPrimitive(14), m("2 + 3 * 4"))
        assertEquals(JsonPrimitive(20), m("(2 + 3) * 4"))
        assertEquals(JsonPrimitive(-6), m("-2 * 3"))
        assertEquals(JsonPrimitive(512), m("2 ^ 3 ^ 2"))            // right associative
        assertEquals(JsonPrimitive(-8), m("-2 ^ 3"))
        assertEquals(JsonPrimitive(1), m("10 % 3"))
        assertEquals(JsonPrimitive(3.33), m("10 / 3"))
        assertEquals(JsonPrimitive(3), m("10 / 3", 0))
        assertEquals(JsonPrimitive(3.5), m("{{a}} / {{b}}"))         // templated operands
        assertEquals(JsonPrimitive(5), m("min(7, 5, 9)")); assertEquals(JsonPrimitive(9), m("max(7, 5, 9)"))
        assertEquals(JsonPrimitive(4), m("abs(-4)")); assertEquals(JsonPrimitive(3), m("floor(3.7)")); assertEquals(JsonPrimitive(4), m("ceil(3.2)"))
        assertEquals(JsonPrimitive(4), m("round(3.7)")); assertEquals(JsonPrimitive(3), m("sqrt(9)")); assertEquals(JsonPrimitive(8), m("pow(2, 3)"))
        assertEquals(JsonPrimitive(1500), m("1.5e3"))
        assertEquals(JsonPrimitive(2.5), m("(1 + (2 * 3) - 2) / 2"))
        expectError("division by zero") { m("1 / 0") }
        expectError("division by zero") { m("1 % 0") }
        expectError("unknown function") { m("foo(1)") }
        expectError("expected ')'") { m("(1 + 2") }
        expectError("unexpected") { m("1 + 2 )") }
        expectError("Math") { m("") }
        expectError("sqrt") { m("sqrt(-1)") }
        expectError("Math") { m("1 +") }
    }

    // ---- date ----
    @Test fun dateIsBetweenOvernight() = runTest {
        fun b(t: String, from: String, to: String) = DateNode.isBetween(LocalTime.parse(t), LocalTime.parse(from), LocalTime.parse(to))
        assertTrue(b("23:00", "22:00", "06:00")); assertTrue(b("02:00", "22:00", "06:00")); assertTrue(b("22:00", "22:00", "06:00"))
        assertFalse(b("06:00", "22:00", "06:00")); assertFalse(b("12:00", "22:00", "06:00"))
        assertTrue(b("10:00", "09:00", "17:00")); assertFalse(b("17:00", "09:00", "17:00")); assertFalse(b("08:59", "09:00", "17:00"))
        assertTrue(b("00:00", "12:00", "12:00"))
        val zone = ZoneId.of("America/New_York")
        val f2 = Fake(nowMs = java.time.ZonedDateTime.of(2024, 3, 9, 23, 30, 0, 0, zone).toInstant().toEpochMilli(), zone = zone)   // Saturday 23:30 local
        assertEquals(PORT_TRUE, f2.port(DateNode, params("op" to "is_between", "from" to "22:00", "to" to "06:00")))
        assertEquals(PORT_FALSE, f2.port(DateNode, params("op" to "is_between", "from" to "08:00", "to" to "17:00")))
        assertEquals(PORT_TRUE, f2.port(DateNode, params("op" to "is_weekend")))
        assertEquals(PORT_FALSE, f2.port(DateNode, params("op" to "is_weekday")))
        assertEquals(PORT_TRUE, f2.port(DateNode, params("op" to "is_weekday", "input" to "2024-03-11")))
    }

    @Test fun dateFormatAddDiff() = runTest {
        val f2 = Fake(nowMs = java.time.ZonedDateTime.of(2024, 3, 9, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli())
        assertEquals("2024-03-09 23:30", f2.one(DateNode, params()).str("date"))
        assertEquals("Saturday", f2.one(DateNode, params("pattern" to "EEEE", "outputField" to "dow")).str("dow"))
        assertEquals("09/03/2024", f2.one(DateNode, params("input" to "{{ts}}", "pattern" to "dd/MM/yyyy"), item("ts" to f2.nowMs)).str("date"))
        assertEquals("2024-03-10T01:30Z", f2.one(DateNode, params("op" to "add", "amount" to 2, "unit" to "hours")).str("date"))
        assertEquals("2024-03-12T23:30Z", f2.one(DateNode, params("op" to "add", "amount" to 3, "unit" to "days")).str("date"))
        assertEquals(JsonPrimitive(90), f2.one(DateNode, params("op" to "diff_minutes", "input" to "2024-03-09T10:00:00Z", "input2" to "2024-03-09T11:30:00+00:00"))["date"])
        assertEquals(JsonPrimitive(-30), f2.one(DateNode, params("op" to "diff_minutes", "input" to "2024-03-09T10:00", "input2" to "2024-03-09T09:30"))["date"])
        assertEquals(JsonPrimitive(30), f2.one(DateNode, params("op" to "diff_minutes", "input" to "23:00", "input2" to "23:30"))["date"])
        // epoch seconds (< 1e10) vs ms; short digit strings are rejected instead of silently becoming 1970
        assertEquals("2024-05-29", f2.one(DateNode, params("input" to "1717000000", "pattern" to "yyyy-MM-dd")).str("date"))
        assertEquals("2024-05-29", f2.one(DateNode, params("input" to "1717000000000", "pattern" to "yyyy-MM-dd")).str("date"))
        assertEquals(1_717_000_000_000L, DateNode.parse("1717000000", ZoneId.of("UTC"), 0).toInstant().toEpochMilli())
        expectError("epoch") { DateNode.parse("2025", ZoneId.of("UTC"), 0) }
        expectError("epoch") { run { f2.one(DateNode, params("input" to "-2025")) } }
        expectError("parse") { run { f2.one(DateNode, params("input" to "yesterday")) } }
        expectError("second input") { run { f2.one(DateNode, params("op" to "diff_minutes")) } }
        expectError("pattern") { run { f2.one(DateNode, params("pattern" to "qqqqqqqq{")) } }
    }

    // ---- note / run_workflow / stop_error ----
    @Test fun flowNodes() = runTest {
        val items = listOf(item("a" to 1), item("a" to 2))
        assertEquals(items, f.run(NoteNode, params("text" to "docs {{a}}"), items)[MAIN])
        val sub = f.run(RunWorkflowNode, params("workflow" to "wf-2"), items)[MAIN]!!
        assertEquals(2, sub.size); assertEquals(true, sub[0].bool("sub"))
        val fire = f.run(RunWorkflowNode, params("workflow" to "wf-2", "waitForResult" to false), items)[MAIN]!!
        assertEquals(items, fire); assertEquals(listOf("wf-2" to items), f.fired)
        expectError("itself") { run { f.run(RunWorkflowNode, params("workflow" to "wf-1"), items) } }
        expectError("required") { run { f.run(RunWorkflowNode, params(), items) } }
        f.subResult = { _, _ -> throw NodeException("Callee failed: boom") }
        expectError("boom") { run { f.run(RunWorkflowNode, params("workflow" to "wf-2"), items) } }
        expectError("Battery 9") { run { f.one(StopErrorNode, params("message" to "Battery {{b}}"), item("b" to 9)) } }
        expectError("Stopped") { run { f.one(StopErrorNode, params()) } }
    }

    // ---- through the real executor: if -> set_fields -> aggregate, and a dedupe error edge ----
    @Test fun executorEndToEnd() = runTest {
        val trig = object : TriggerNode() {
            override val hosting = Hosting.COMPONENT
            override val spec = NodeSpec(TRIGGER_MANUAL, "Manual", NodeKind.TRIGGER, "manual", inputs = emptyList())
        }
        val catalog = Catalog(listOf(listOf(trig), LogicNodes.all))
        val g = Graph(
            nodes = listOf(
                NodeInstance("t", TRIGGER_MANUAL, "Manual"),
                NodeInstance("i", "logic.if", "Big", params("conditions" to rowsOf(cond("n", "gte", "5")))),
                NodeInstance("s", "logic.set_fields", "Double", params("set" to rowsOf(item("name" to "twice", "value" to "{{n}}")))),
                NodeInstance("m", "logic.math", "Times2", params("expression" to "{{twice}} * 2", "outputField" to "twice")),
                NodeInstance("a", "logic.aggregate", "Sum", params("mode" to "sum", "field" to "twice")),
                NodeInstance("e", "logic.stop_error", "Boom", params("message" to "small {{n}}")),
            ),
            edges = listOf(Edge("t", MAIN, "i"), Edge("i", PORT_TRUE, "s"), Edge("s", MAIN, "m"), Edge("m", MAIN, "a"), Edge("i", PORT_FALSE, "e"), Edge("e", ERROR, "a", MAIN)),
        )
        val wf = Workflow("wf-x", "X", true, g)
        assertEquals(emptyList<String>(), g.validate(catalog))
        val ex = Executor(catalog, f.persistence, nowMs = { f.nowMs })
        val out = ex.start(wf, g.nodes[0], listOf(item("n" to 5), item("n" to 2), item("n" to 10)), null, 0)
        assertEquals(RunStatus.SUCCESS, out.run.status)
        // aggregate sees 2 math items (10, 20) plus the routed error item (no `twice` field, skipped by the filter)
        assertEquals(JsonPrimitive(30), out.leafItems.single()["result"])
        assertEquals(3, out.leafItems.single().num("count")!!.toInt())
    }
}
