package com.mob8n.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudeParsingTest {
    private fun fixture(name: String): JsonObject {
        val text = javaClass.getResourceAsStream("/ai/$name.json")!!.bufferedReader().readText()
        return JSON.parseToJsonElement(text) as JsonObject
    }
    private val labelSchema = Llm.objectSchema(mapOf("label" to buildJsonObject { put("type", "string") }))
    private fun req(schema: JsonObject? = null) = LlmRequest(null, "p", jsonSchema = schema)

    @Test fun endTurnJoinsText() {
        val t = ClaudeClient.check(ClaudeClient.parseMessage(fixture("end_turn")))
        assertEquals("end_turn", t.stopReason)
        assertEquals("Hello there", t.text)
        assertTrue(t.toolUses.isEmpty())
        val r = ClaudeClient.toResult(t, req())
        assertEquals("Hello there", r.text); assertNull(r.json); assertEquals(PROVIDER_CLAUDE, r.provider)
    }

    @Test fun parallelToolUseCollectsAllBlocksWithParsedJsonInputs() {
        val t = ClaudeClient.check(ClaudeClient.parseMessage(fixture("tool_use_parallel")))
        assertEquals("tool_use", t.stopReason)
        assertEquals("I'll notify you and fetch the page.", t.text)
        val uses = t.toolUses
        assertEquals(listOf("toolu_01", "toolu_02"), uses.map { it.id })
        assertEquals(listOf("action_notify", "data_http"), uses.map { it.name })
        assertEquals(JsonNull, uses[0].input["channel"])
        assertEquals(JsonPrimitive(30), uses[1].input["timeout"])           // number, not "30"
        assertEquals(JsonPrimitive(true), uses[1].input["json"])
        assertEquals("https://example.com/a", uses[1].input["url"].asTextOrNull())
        // the assistant turn echoes back verbatim (ids preserved) for the next request
        val echoed = t.asMessage()
        assertEquals("assistant", echoed["role"].asTextOrNull())
        assertEquals(3, Fakes.blocks(echoed).size)
    }

    @Test fun maxTokensIsPartialForTextButAnErrorForSchema() {
        val t = ClaudeClient.check(ClaudeClient.parseMessage(fixture("max_tokens")))
        assertEquals("max_tokens", t.stopReason)
        assertEquals(1, t.toolUses.size)                                   // present in the wire, but callers must not run it
        val r = ClaudeClient.toResult(t, req())
        assertEquals("max_tokens", r.stopReason); assertNull(r.json); assertTrue(r.text.startsWith("Here is the first part"))
        // F27: a structured-output request cut off by max_tokens is an error, never a silent 'other' / missing-key answer
        try { ClaudeClient.toResult(t, req(labelSchema)); fail("expected max_tokens error") } catch (e: NodeException) {
            assertTrue(e.message!!.contains("max_tokens")); assertTrue(e.message!!.contains("4096"))
        }
    }

    @Test fun refusalIsCheckedFirstAndCarriesStopDetails() {
        val t = ClaudeClient.parseMessage(fixture("refusal"))
        assertEquals("refusal", t.stopReason)
        try { ClaudeClient.check(t); fail("expected refusal") } catch (e: NodeException) {
            assertTrue(e.message!!.startsWith("Claude declined"))
            assertTrue(e.message!!.contains("general_harms"))
            assertTrue(e.message!!.contains("harmful content"))
        }
    }

    @Test fun structuredOutputParsesAndValidatesRequiredKeys() {
        val t = ClaudeClient.check(ClaudeClient.parseMessage(fixture("structured_output")))
        val r = ClaudeClient.toResult(t, req(labelSchema))
        assertEquals("work", (r.json as JsonObject)["label"].asTextOrNull())
        val stricter = Llm.objectSchema(mapOf("label" to buildJsonObject { put("type", "string") }, "confidence" to buildJsonObject { put("type", "number") }))
        try { ClaudeClient.toResult(t, req(stricter)); fail("expected missing key") } catch (e: NodeException) { assertTrue(e.message!!.contains("confidence")) }
        val fenced = Turn("end_turn", Fakes.turn("end_turn", Fakes.textBlock("```json\n{\"label\": \"x\"}\n```")).content)
        assertEquals("x", (ClaudeClient.toResult(fenced, req(labelSchema)).json as JsonObject)["label"].asTextOrNull())
    }

    /** SDK path: wire JSON -> BetaMessage (Jackson) -> JsonValue -> kotlinx -> the same parseMessage the fixtures use. */
    @Test fun sdkBetaMessageRoundTripsThroughTheSameParser() {
        val wire = fixture("tool_use_parallel")
        val msg = ClaudeClient.jv(wire).convert(BetaMessage::class.java)!!
        assertEquals(BetaStopReason.TOOL_USE, msg.stopReason().get())
        assertEquals(2, msg.content().count { it.isToolUse() })
        val back = ClaudeClient.toKx(JsonValue.from(msg)) as JsonObject
        val t = ClaudeClient.check(ClaudeClient.parseMessage(back))
        assertEquals("tool_use", t.stopReason)
        assertEquals(listOf("toolu_01", "toolu_02"), t.toolUses.map { it.id })
        assertEquals(JsonPrimitive(30), t.toolUses[1].input["timeout"])
    }

    /** Request shape rules from DESIGN §8.2, verified on the built params. */
    @Test fun buildParamsFollowsModelRules() {
        val transcript = listOf(
            ClaudeClient.userMessage("hi", "QUJD"),
            Fakes.turn("tool_use", Fakes.toolUse("toolu_1", "data_http", buildJsonObject { put("url", "https://a") })).asMessage(),
            ClaudeClient.userMessage(listOf(ClaudeClient.toolResultBlock("toolu_1", "[]", false))),
        )
        val tools = listOf(Fakes.http.toolDef(), AgentNode.finishDef())
        val opus = ClaudeClient.buildParams(ClaudeClient.MODEL_OPUS, "xhigh", 1000, "sys", transcript, tools, null)
        assertEquals(3, opus.messages().size)
        assertTrue(opus.messages()[0].content().asBetaContentBlockParams()[0].isImage())
        assertTrue(opus.messages()[0].content().asBetaContentBlockParams()[1].isText())
        assertTrue(opus.messages()[1].content().asBetaContentBlockParams()[0].isToolUse())
        assertTrue(opus.messages()[2].content().asBetaContentBlockParams()[0].isToolResult())
        assertEquals("xhigh", opus.outputConfig().get().effort().get().asString())
        assertFalse(opus.outputConfig().get().format().isPresent)
        assertEquals(2, opus.tools().get().size)
        val http = opus.tools().get()[0].asBetaTool()
        assertEquals("data_http", http.name()); assertEquals(true, http.strict().get())
        assertEquals(listOf("url", "method", "body", "headers", "timeout", "tags", "json", "delay"), http.inputSchema().required().get())
        assertEquals("false", http.inputSchema()._additionalProperties()["additionalProperties"].toString())
        assertEquals(JsonPrimitive("default"), ClaudeClient.toKx(opus._additionalBodyProperties()["fallbacks"]!!))
        assertTrue(opus.betas().get().any { it.asString() == ClaudeClient.FALLBACK_BETA })
        assertEquals("sys", opus.system().get().asString())

        val haiku = ClaudeClient.buildParams(ClaudeClient.MODEL_HAIKU, "high", 500, null, listOf(ClaudeClient.userMessage("x")), emptyList(), labelSchema)
        assertFalse(haiku.outputConfig().get().effort().isPresent)                 // no effort on haiku
        assertTrue(haiku.outputConfig().get().format().isPresent)                  // structured output
        assertNull(haiku._additionalBodyProperties()["fallbacks"])                 // fallbacks only on opus
        assertFalse(haiku.betas().isPresent || haiku.betas().orElse(emptyList()).isNotEmpty())
        assertFalse(haiku.system().isPresent)

        val sonnet = ClaudeClient.buildParams(ClaudeClient.MODEL_SONNET, "low", 500, null, listOf(ClaudeClient.userMessage("x")), emptyList(), null)
        assertEquals("low", sonnet.outputConfig().get().effort().get().asString())
        assertNull(sonnet._additionalBodyProperties()["fallbacks"])
        assertNull(sonnet._additionalBodyProperties()["thinking"])
    }
}
