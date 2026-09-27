package com.mob8n.ai

import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN6 §9: Claude stream events -> BetaMessageAccumulator -> the SAME parseMessage Turn as the non-streaming create() path. */
class ClaudeStreamTest {
    private fun text(name: String) = javaClass.getResourceAsStream("/sse/$name")!!.bufferedReader().readText()
    /** SDK deserialization (the same Jackson mapping the SDK applies to wire events), via JsonValue.convert. */
    private fun events(name: String): List<BetaRawMessageStreamEvent> = text(name).lines().filter { it.isNotBlank() }
        .map { ClaudeClient.jv(JSON.parseToJsonElement(it)).convert(BetaRawMessageStreamEvent::class.java)!! }
    private fun turnOf(msg: BetaMessage) = ClaudeClient.parseMessage(ClaudeClient.toKx(JsonValue.from(msg)) as JsonObject)
    private fun accumulate(name: String, deltas: MutableList<StreamDelta> = ArrayList()): BetaMessage {
        val acc = BetaMessageAccumulator.create()
        for (ev in events(name)) { acc.accumulate(ev); deltas += ClaudeStream.deltas(ev) }
        return acc.message()
    }

    @Test fun accumulatedMessageEqualsNonStreaming() {
        val deltas = ArrayList<StreamDelta>()
        val streamed = ClaudeClient.check(turnOf(accumulate("claude_text_tool.jsonl", deltas)))
        val wire = JSON.parseToJsonElement(text("claude_text_tool.json")) as JsonObject
        val nonStreamed = turnOf(ClaudeClient.jv(wire).convert(BetaMessage::class.java)!!)          // what create() returns for the same answer
        assertEquals(nonStreamed, streamed)
        val raw = ClaudeClient.parseMessage(wire)
        assertEquals(raw.text, streamed.text); assertEquals(raw.toolUses, streamed.toolUses); assertEquals(raw.stopReason, streamed.stopReason)
        assertEquals(raw.usage, streamed.usage); assertEquals(raw.model, streamed.model)
        assertEquals("tool_use", streamed.stopReason); assertEquals("I'll fetch the page.", streamed.text)

        assertEquals(listOf(StreamDelta.Text("I'll fetch"), StreamDelta.Text(" the page."), StreamDelta.ToolStart(1, "toolu_01", "data_http")),
            deltas.filter { it !is StreamDelta.ToolArgs })
        val args = deltas.filterIsInstance<StreamDelta.ToolArgs>()
        assertTrue(args.all { it.index == 1 })
        assertEquals("{\"url\":\"https://example.com/a\",\"timeout\":30}".length, args.sumOf { it.addedChars })
        assertTrue(deltas.indexOfFirst { it is StreamDelta.ToolStart } < deltas.indexOfFirst { it is StreamDelta.ToolArgs })
    }

    @Test fun refusalOnTheAccumulatedMessageThrows() {
        try { ClaudeClient.check(turnOf(accumulate("claude_refusal.jsonl"))); fail() } catch (e: NodeException) {
            assertTrue(e.message, e.message!!.startsWith("Claude declined"))
        }
    }
}
