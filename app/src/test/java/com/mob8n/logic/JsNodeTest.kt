package com.mob8n.logic

import android.content.Context
import android.content.ContextWrapper
import com.mob8n.apps.JsBridge
import com.mob8n.apps.JsRuntime
import com.mob8n.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** logic.js on the JVM: the WebView runner and the bridge factory are injected; no JavaScript is executed here (device plan step 12). */
class JsNodeTest {
    private val f = Fake()
    private val logs = ArrayList<String>()
    private val android: Context = ContextWrapper(null)   // android.jar stub (returnDefaultValues): a non-null Context for requireAndroid()
    private val defaultRunner = JsNode.runner
    private val defaultBridge = JsNode.bridge

    private class Call(val app: Context, val code: String, val input: JsonObject, val timeoutMs: Long, val bridge: JsBridge)
    private val calls = ArrayList<Call>()
    private val bridges = ArrayList<Pair<Set<String>, JsBridge>>()

    private fun fakeBridge(allow: Set<String>) = JsBridge(allow, { _, _ -> emptyList() }, { _, _ -> emptyList() }, { null }, { _, _ -> }, File("."), {})

    private fun install(result: (Call) -> JsRuntime.Run) {
        JsNode.bridge = { _, allow -> fakeBridge(allow).also { bridges += allow to it } }
        JsNode.runner = { app, code, input, t, b -> Call(app, code, input, t, b).also { calls += it }.let(result) }
    }

    @After fun restore() { JsNode.runner = defaultRunner; JsNode.bridge = defaultBridge }

    private fun ctx(params: JsonObject, items: Items, vars: Map<String, JsonPrimitive> = emptyMap()): Pair<ExecutionContext, NodeInput> {
        val input = NodeInput(items)
        val c = ExecutionContext(
            runId = "run-1", workflow = f.workflow, instance = NodeInstance("n1", JsNode.spec.id, "JS", params), spec = JsNode.spec, input = input,
            itemIndex = 0, upstream = emptyMap(), vars = vars, persistence = f.persistence, catalog = f.catalog, hooks = f.hooks, android = android,
            zone = ZoneId.of("UTC"), nowMs = { f.nowMs }, logger = { logs += it },
            runWorkflow = { _, xs -> xs }, runNode = { _, _, _ -> throw NodeException("runNode not faked") },
        )
        return c to input
    }

    private suspend fun run(params: JsonObject, items: Items, vars: Map<String, JsonPrimitive> = emptyMap()): Items {
        val (c, input) = ctx(params, items, vars)
        return (JsNode.execute(c, input) as NodeResult.Out).ports[MAIN]!!
    }

    private fun j(s: String) = Json.parseToJsonElement(s)

    // ---- spec ----
    @Test fun specIsLogicListNotAnAgentToolAndCodeIsUntemplated() {
        val s = JsNode.spec
        assertEquals("logic.js", s.id); assertEquals(NodeKind.LOGIC, s.kind); assertEquals(ExecMode.LIST, s.mode)
        assertFalse("ai.agent must never run JS ungated", s.agentTool)
        assertEquals(130_000L, s.timeoutMs)
        assertFalse(s.param("code")!!.templated); assertTrue(s.param("code")!!.required)
        assertEquals(listOf("per_item", "all_items"), s.param("mode")!!.options)
        assertEquals(ParamKind.LABELS, s.param("allowNodes")!!.kind)
        assertEquals(JsRuntime.MAX_TIMEOUT_MS.toDouble(), s.param("timeoutMs")!!.max)
        s.toolDef()   // schema derivation never throws (CatalogTest rule)
        assertTrue(LogicNodes.all.contains(JsNode)); assertEquals(27, LogicNodes.all.size)
    }

    // ---- normalize (pure) ----
    @Test fun normalizeArrayKeepsObjectsWrapsPrimitivesDropsNulls() {
        val out = JsNode.normalize(j("""[{"a":1}, 2, "x", null, true, [1]]"""))
        assertEquals(listOf(j("""{"a":1}"""), j("""{"value":2}"""), j("""{"value":"x"}"""), j("""{"value":true}"""), j("""{"value":[1]}""")), out)
    }
    @Test fun normalizeObjectNullAndPrimitive() {
        assertEquals(listOf(j("""{"a":1}""")), JsNode.normalize(j("""{"a":1}""")))
        assertEquals(emptyList<Item>(), JsNode.normalize(JsonNull))
        assertEquals(emptyList<Item>(), JsNode.normalize(j("[]")))
        assertEquals(listOf(j("""{"value":42}""")), JsNode.normalize(JsonPrimitive(42)))
        assertEquals(listOf(j("""{"value":"s"}""")), JsNode.normalize(JsonPrimitive("s")))
    }

    // ---- execute with an injected runner ----
    @Test fun perItemPassesItemItemsVarsModeOnceAndNormalisesTheArray() = runTest {
        install { JsRuntime.Run(j("""[{"battery":5,"low":true},{"battery":90,"low":false}]"""), listOf("hello", "world"), 3) }
        val items = listOf(item("battery" to 5), item("battery" to 90))
        val out = run(params("code" to "return {...item, low: item.battery < 20};"), items, mapOf("k" to JsonPrimitive("v")))
        assertEquals(1, calls.size)
        val c = calls.single()
        assertSame(android, c.app)
        assertEquals("return {...item, low: item.battery < 20};", c.code)
        assertEquals("per_item", c.input["mode"]!!.jsonPrimitive.content)
        assertEquals(items.first(), c.input["item"]!!.jsonObject)
        assertEquals(items, c.input["items"]!!.jsonArray.toList())
        assertEquals("v", c.input["vars"]!!.jsonObject["k"]!!.jsonPrimitive.content)
        assertEquals(30_000L, c.timeoutMs)   // default
        assertEquals(2, out.size); assertEquals(JsonPrimitive(true), out[0]["low"])
        assertEquals(listOf("[Test WF/JS] js: hello", "[Test WF/JS] js: world"), logs)
    }

    @Test fun allItemsModeIsPassedThroughAndSingleObjectBecomesOneItem() = runTest {
        install { JsRuntime.Run(j("""{"count":2}"""), emptyList(), 1) }
        val out = run(params("code" to "return {count: items.length};", "mode" to "all_items"), listOf(EMPTY, EMPTY))
        assertEquals("all_items", calls.single().input["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf(j("""{"count":2}""")), out)
        assertTrue(logs.isEmpty())
    }

    @Test fun emptyItemsGiveEmptyItemAndNullResultGivesNoItems() = runTest {
        install { JsRuntime.Run(JsonNull, emptyList(), 1) }
        val out = run(params("code" to "return null;"), emptyList())
        assertEquals(EMPTY, calls.single().input["item"]!!.jsonObject)
        assertEquals(0, (calls.single().input["items"] as JsonArray).size)
        assertTrue(out.isEmpty())
    }

    @Test fun runnerErrorsSurfaceAsNodeException() = runTest {
        install { throw NodeException("JavaScript timed out after 30 s") }
        try { run(params("code" to "while(true){}"), listOf(EMPTY)); fail("expected NodeException") }
        catch (e: NodeException) { assertTrue(e.message!!.contains("timed out")) }
    }

    @Test fun blankCodeIsRejectedBeforeTheRunnerIsCalled() = runTest {
        install { fail("runner must not run"); throw IllegalStateException() }
        try { run(params("code" to "   "), listOf(EMPTY)); fail("expected NodeException") }
        catch (e: NodeException) { assertTrue(e.message!!.contains("required")) }
        assertTrue(calls.isEmpty())
    }

    @Test fun missingAndroidRuntimeIsAClearError() = runTest {
        install { JsRuntime.Run(JsonNull, emptyList(), 1) }
        try { f.raw(JsNode, params("code" to "return 1;"), listOf(EMPTY)); fail("expected NodeException") }   // Fake ctx has android = null
        catch (e: NodeException) { assertTrue(e.message!!.contains("Android runtime")) }
    }

    // ---- allowNodes passthrough + timeout clamp ----
    @Test fun allowNodesReachTheBridgeAndTheSameBridgeReachesTheRunner() = runTest {
        install { JsRuntime.Run(j("[]"), emptyList(), 1) }
        run(params("code" to "mob8n.runNode('action.notify', {}); return [];", "allowNodes" to listOf("action.notify", "data.http", " ")), listOf(EMPTY))
        assertEquals(setOf("action.notify", "data.http"), bridges.single().first)
        assertEquals(setOf("action.notify", "data.http"), calls.single().bridge.allowNodes)
        assertSame(bridges.single().second, calls.single().bridge)
    }

    @Test fun emptyAllowNodesMeansNoNodes() = runTest {
        install { JsRuntime.Run(j("[]"), emptyList(), 1) }
        run(params("code" to "return [];"), listOf(EMPTY))
        assertEquals(emptySet<String>(), calls.single().bridge.allowNodes)
    }

    @Test fun timeoutIsClampedToTheRuntimeBounds() = runTest {
        install { JsRuntime.Run(j("[]"), emptyList(), 1) }
        run(params("code" to "return [];", "timeoutMs" to 999_999), listOf(EMPTY))
        run(params("code" to "return [];", "timeoutMs" to 1), listOf(EMPTY))
        run(params("code" to "return [];", "timeoutMs" to 45_000), listOf(EMPTY))
        assertEquals(listOf(JsRuntime.MAX_TIMEOUT_MS, 1_000L, 45_000L), calls.map { it.timeoutMs })
    }

    @Test fun codeIsNotTemplated() = runTest {
        install { JsRuntime.Run(j("[]"), emptyList(), 1) }
        run(params("code" to "return `{{item.name}}`;"), listOf(item("name" to "x")))
        assertEquals("return `{{item.name}}`;", calls.single().code)   // {{ stays literal: templated = false
    }
}
