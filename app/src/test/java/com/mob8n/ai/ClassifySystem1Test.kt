package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeResult
import com.mob8n.core.PORT_OTHER
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN5 §5.3: ai.classify engine=system1 = one choice question to the decision engine. */
class ClassifySystem1Test {
    private var asked: Pair<String?, List<S1Question>>? = null
    private var askedState: JsonElement? = null

    @After fun restore() { DecideNode.decideFn = { ctx, engine, state, qs, timeout -> SystemOne.decide(SystemOne.target(ctx.requireAndroid(), engine), state, qs, timeout, "node", ctx.runId, ctx::log) } }

    private fun answer(choice: String) {
        DecideNode.decideFn = { _, engine, state, qs, _ ->
            asked = engine to qs; askedState = state
            val body = JSON.parseToJsonElement("""{"model":"laya-rl-agent","answers":{"label":{"type":"choice","choice":"$choice","probabilities":{"urgent":0.9,"later":0.1},"confidence":0.8}},"routing":{"model":"english"}}""") as JsonObject
            SystemOne.parse(body, qs, "laya", 40)
        }
    }

    private fun params(vararg extra: Pair<String, String>) = buildJsonObject {
        put("labels", JsonArray(listOf(JsonPrimitive("urgent"), JsonPrimitive("later")))); put("engine", "system1"); put("text", "{{text}}")
        extra.forEach { put(it.first, it.second) }
    }

    private suspend fun run(p: JsonObject): NodeResult.Out = ClassifyNode.execute(S1Ctx.ctx(ClassifyNode, p, item("text" to "call me NOW")), NodeInput(listOf(item()))) as NodeResult.Out

    @Test fun engineParamIsLastAndDefaultsGenerative() {
        val p = ClassifyNode.spec.params.last()
        assertEquals("engine", p.key); assertEquals(listOf("generative", "system1"), p.options); assertEquals(JsonPrimitive("generative"), p.default)
    }

    @Test fun questionFromLabelsAndRouting() = runBlocking {
        answer("urgent")
        val out = run(params("instructions" to "Is it urgent?"))
        val it = out.ports.getValue("urgent").single()
        assertEquals(JsonPrimitive("urgent"), it["label"]); assertEquals(JsonPrimitive("system1:laya"), it["provider"])
        assertEquals(0.8, (it["confidence"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(setOf("urgent", "later"), (it["probabilities"] as JsonObject).keys)
        val (engine, qs) = asked!!
        assertEquals("default", engine)
        val q = qs.single() as S1Question.Choice
        assertEquals("label", q.name); assertEquals("Is it urgent?", q.instructions); assertEquals(linkedMapOf("urgent" to "urgent", "later" to "later"), q.options)
        assertEquals(JsonPrimitive("call me NOW"), askedState)
    }

    @Test fun caseInsensitiveMatchAndUnknownGoesToOther() = runBlocking {
        answer("LATER")
        assertEquals(setOf("later"), run(params()).ports.keys)
        answer("maybe")
        assertEquals(setOf(PORT_OTHER), run(params()).ports.keys)
        assertEquals("Classify the text into exactly one label.", (asked!!.second.single()).instructions)
    }

    @Test fun imageIsRefusedHonestly() = runBlocking {
        answer("urgent")
        try { run(params("imageUri" to "content://x/1")); fail() } catch (e: NodeException) { assertEquals("System 1 engines do not accept images — use engine = generative", e.message) }
        assertEquals(null, asked)
    }
}
