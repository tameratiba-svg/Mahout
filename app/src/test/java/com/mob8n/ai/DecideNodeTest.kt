package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Hooks
import com.mob8n.core.InMemoryPersistence
import com.mob8n.core.Item
import com.mob8n.core.JSON
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.ParamKind
import com.mob8n.core.Workflow
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

/** Minimal ExecutionContext for ai nodes (android = null; the S1 engine is faked through DecideNode.decideFn). */
internal object S1Ctx {
    fun ctx(node: Node, params: JsonObject, item: Item = item("text" to "I was billed twice")): ExecutionContext {
        val input = NodeInput(listOf(item))
        return ExecutionContext(
            runId = "run-1", workflow = Workflow("wf-1", "WF"), instance = NodeInstance("n1", node.spec.id, node.spec.name, params),
            spec = node.spec, input = input, itemIndex = 0, upstream = emptyMap(), vars = emptyMap(), persistence = InMemoryPersistence(),
            catalog = Catalog(listOf(listOf(node))), hooks = Hooks(), android = null, zone = ZoneId.of("UTC"), nowMs = { 0L }, logger = {},
            runWorkflow = { _, _ -> emptyList() }, runNode = { _, _, _ -> emptyList() },
        )
    }

    /** The real Laya capture parsed against its questions, rebuilt from rows. */
    fun layaResult(questions: List<S1Question>): S1Result {
        val body = JSON.parseToJsonElement(javaClass.getResourceAsStream("/s1/laya_reply.json")!!.bufferedReader().readText()) as JsonObject
        return SystemOne.parse(body, questions, "laya", 118)
    }
}

class DecideNodeTest {
    private val seen = ArrayList<Triple<String?, JsonElement, List<S1Question>>>()
    private val seenTimeout = ArrayList<Long>()

    @After fun restore() { DecideNode.decideFn = { ctx, engine, state, qs, timeout -> SystemOne.decide(SystemOne.target(ctx.requireAndroid(), engine), state, qs, timeout, "node", ctx.runId, ctx::log) } }

    private val rows = JsonArray(listOf(
        buildJsonObject { put("name", "department"); put("type", "choice"); put("instructions", "Which department should handle this?"); put("criteria", "billing: invoices, payments, refunds\ntechnical: bugs, outages\nother: everything else") },
        buildJsonObject { put("name", "urgency"); put("type", "score"); put("instructions", "How urgent is this?"); put("criteria", "not urgent, soon, critical") },
        buildJsonObject { put("name", "churn_risk"); put("type", "noul"); put("instructions", "Does the user threaten to cancel or leave?"); put("criteria", "") },
    ))

    @Test fun specPins() {
        val s = DecideNode.spec
        assertEquals("ai.decide", s.id); assertEquals(NodeKind.DATA, s.kind); assertEquals(8_000L, s.timeoutMs); assertTrue(s.gates.isEmpty()); assertTrue(s.agentTool)
        assertEquals(listOf("state", "questions", "engine", "timeoutMs"), s.params.map { it.key })
        assertEquals(listOf("name", "type", "instructions", "criteria"), s.param("questions")!!.rows.map { it.key })
        assertEquals(DecideNode.QUESTION_COLUMNS, s.param("questions")!!.rows)
        assertEquals(listOf("choice", "score", "noul"), DecideNode.QUESTION_COLUMNS[1].options)
        assertTrue(DecideNode.QUESTION_COLUMNS.none { it.templated && it.kind != ParamKind.ENUM })          // instructions/criteria are the schema: untemplated
        assertEquals(listOf("default", "jev", "laya"), s.param("engine")!!.options); assertEquals(JsonPrimitive("default"), s.param("engine")!!.default)
        assertEquals(1_000.0, s.param("timeoutMs")!!.min); assertEquals(7_000.0, s.param("timeoutMs")!!.max)
        assertTrue(s.outputs == listOf("main")); assertTrue(s.params.none { it.definesPorts })
        assertFalse(s.id in AgentNode.NEEDS_APPROVAL_IDS)                                                  // Risk READ like ai.ask
        // strict tool def: ROWS -> array of strict objects
        val def = s.toolDef()
        assertEquals("ai_decide", def["name"].asTextOrNull()); assertEquals(JsonPrimitive(true), def["strict"])
        val q = ((def["input_schema"] as JsonObject)["properties"] as JsonObject)["questions"] as JsonObject
        assertEquals("array", q["type"].asTextOrNull())
        val items = q["items"] as JsonObject
        assertEquals(JsonPrimitive(false), items["additionalProperties"])
        assertEquals(listOf("name", "type", "instructions", "criteria"), (items["required"] as JsonArray).map { it.asTextOrNull() })
        assertTrue(s.validate(buildJsonObject { put("questions", rows) }).isEmpty())
    }

    @Test fun outputMappingForAllThreeTypes() {
        val qs = SystemOne.questionsFromRows(rows.map { it as JsonObject })
        val out = DecideNode.outputOf(item("text" to "x", "answers" to item("earlier" to 1)), S1Ctx.layaResult(qs))
        val a = out["answers"] as JsonObject
        assertEquals(JsonPrimitive(1), a["earlier"])                                                       // earlier answers kept
        assertEquals(JsonPrimitive("billing"), a["department"]); assertEquals(0.9862, (a["department_confidence"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(0.9981, ((a["department_probabilities"] as JsonObject)["billing"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertNull(a["department_label"])
        assertEquals(JsonPrimitive(1), a["urgency"]); assertEquals(JsonPrimitive("soon"), a["urgency_label"])
        assertEquals(1.4035, (a["urgency_score"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(setOf("0", "1", "2"), (a["urgency_probabilities"] as JsonObject).keys)
        assertEquals(0.8263, (a["churn_risk"] as JsonPrimitive).content.toDouble(), 1e-9); assertEquals(JsonPrimitive("true"), a["churn_risk_label"])
        assertEquals(setOf("true", "false"), (a["churn_risk_probabilities"] as JsonObject).keys)
        assertEquals(setOf("department", "urgency", "churn_risk"), (out["decisions"] as JsonObject).keys)   // the raw wire answers
        assertEquals(JsonPrimitive("laya"), out["engine"]); assertEquals(JsonPrimitive("english"), out["s1Model"]); assertEquals(JsonPrimitive(118L), out["latencyMs"])
        assertEquals(JsonPrimitive("x"), out["text"])
    }

    @Test fun noulWithoutConfidenceHasNoConfidenceKey() {
        val body = JSON.parseToJsonElement("""{"model":"jev-1","answers":{"spam":{"type":"noul","noul":0.2}}}""") as JsonObject
        val a = DecideNode.outputOf(item(), SystemOne.parse(body, listOf(S1Question.Noul("spam", "x")), "jev", 1))["answers"] as JsonObject
        assertNull(a["spam_confidence"]); assertEquals(JsonPrimitive("false"), a["spam_label"])
    }

    @Test fun executeUsesTheSeamWithRenderedStateAndClampedTimeout() = runBlocking {
        DecideNode.decideFn = { _, engine, state, qs, timeout -> seen += Triple(engine, state, qs); seenTimeout += timeout; S1Ctx.layaResult(qs) }
        val params = buildJsonObject { put("questions", rows); put("engine", "laya"); put("timeoutMs", 99_000) }
        val r = DecideNode.execute(S1Ctx.ctx(DecideNode, params), NodeInput(listOf(item("text" to "hi")))) as NodeResult.Out
        val out = r.ports.getValue("main").single()
        assertEquals(JsonPrimitive("billing"), (out["answers"] as JsonObject)["department"])
        assertEquals("laya", seen.single().first)
        assertEquals(buildJsonObject { put("text", "I was billed twice") }, seen.single().second)   // default state {{$json}} = the item as JSON
        assertEquals(3, seen.single().third.size); assertEquals(7_000L, seenTimeout.single())
    }

    @Test fun malformedRowsFailBeforeAnyCall() = runBlocking {
        DecideNode.decideFn = { _, _, _, _, _ -> fail("must not call"); throw IllegalStateException() }
        val bad = buildJsonObject { put("questions", JsonArray(listOf(buildJsonObject { put("name", "x"); put("type", "choice"); put("instructions", "q"); put("criteria", "only-one") }))) }
        try { DecideNode.execute(S1Ctx.ctx(DecideNode, bad), NodeInput(listOf(item()))); fail() } catch (e: NodeException) { assertTrue(e.message!!.startsWith("AI Decide: question x: choice needs 2")) }
        val dup = buildJsonObject { put("questions", JsonArray(listOf(rows[2], rows[2]))) }
        try { DecideNode.execute(S1Ctx.ctx(DecideNode, dup), NodeInput(listOf(item()))); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("duplicate")) }
    }

    @Test fun inTheAiLaneList() {
        assertEquals(7, AiNodes.all.size); assertTrue(AiNodes.all.last() === DecideNode)
    }
}
