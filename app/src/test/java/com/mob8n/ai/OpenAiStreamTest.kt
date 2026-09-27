package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN6 §9 golden equivalence: the streamed answer parses to the SAME Turn as the non-streamed one (content, stop, echo, usage, model). */
class OpenAiStreamTest {
    private fun text(name: String) = javaClass.getResourceAsStream("/sse/$name")!!.bufferedReader().readText()
    private fun json(name: String) = JSON.parseToJsonElement(text("$name.json")) as JsonObject
    private val openai = Providers.byId("openai")!!
    private val minimax = Providers.byId("minimax")!!
    private val openrouter = Providers.byId("openrouter")!!
    private val deepseek = Providers.byId("deepseek")!!

    private class Run(val acc: OpenAiStreamAccumulator, val deltas: List<StreamDelta>)
    private fun stream(p: Provider, name: String): Run {
        val acc = OpenAiStreamAccumulator(p.label, tolerateCumulative = p.id == "minimax")
        val deltas = ArrayList<StreamDelta>()
        OpenAiCompat.readSse(text("$name.sse").lineSequence(), acc) { deltas += it }
        return Run(acc, deltas)
    }
    private fun echo(t: Turn) = OpenAiCompat.toOpenAiMessages(null, listOf(t.asMessage()))

    private fun golden(p: Provider, name: String, model: String = "requested"): Pair<Turn, Run> {
        val run = stream(p, name)
        val s = OpenAiCompat.parseResponse(p, run.acc.toResponse(), model)
        val n = OpenAiCompat.parseResponse(p, json(name), model)
        assertEquals(n.content, s.content); assertEquals(n.stopReason, s.stopReason); assertEquals(n.usage, s.usage); assertEquals(n.model, s.model)
        assertEquals(echo(n), echo(s))
        assertTrue(run.acc.emittedAny)
        return s to run
    }
    private fun texts(r: Run) = r.deltas.filterIsInstance<StreamDelta.Text>().joinToString("") { it.text }
    private fun thinking(r: Run) = r.deltas.filterIsInstance<StreamDelta.Thinking>().joinToString("") { it.text }

    @Test fun openaiTextWithIncludeUsageFinalChunk() {
        val (t, r) = golden(openai, "openai_text")
        assertEquals("Hello there, \"friend\" — ünïcode.", t.text); assertEquals("end_turn", t.stopReason)
        assertTrue(t.usage != null)                                                              // usage came from the choices:[] chunk
        assertEquals(t.text, texts(r))
    }

    @Test fun openaiParallelToolsSplitMidTokenAndMidEscape() {
        val (t, r) = golden(openai, "openai_tools_parallel")
        assertEquals("tool_use", t.stopReason)
        assertEquals(listOf("call_a1" to "action_notify", "call_b2" to "data_http"), t.toolUses.map { it.id to it.name })
        assertEquals("Hi \"you\"", (t.toolUses[0].input["title"] as kotlinx.serialization.json.JsonPrimitive).content)
        val starts = r.deltas.filterIsInstance<StreamDelta.ToolStart>()
        assertEquals(listOf(StreamDelta.ToolStart(0, "call_a1", "action_notify"), StreamDelta.ToolStart(1, "call_b2", "data_http")), starts)
        val calls = (((json("openai_tools_parallel")["choices"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)["message"] as JsonObject)["tool_calls"] as kotlinx.serialization.json.JsonArray
        for (i in 0..1) {
            val args = (((calls[i] as JsonObject)["function"] as JsonObject)["arguments"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals(args.length, r.deltas.filterIsInstance<StreamDelta.ToolArgs>().filter { it.index == i }.sumOf { it.addedChars })
        }
        // every ToolArgs of an index comes after its ToolStart
        for (s in starts) assertTrue(r.deltas.indexOf(s) < r.deltas.indexOfFirst { it is StreamDelta.ToolArgs && it.index == s.index })
    }

    @Test fun minimaxReasoningSplitKeepsReasoningDetailsInTheEcho() {
        val (t, r) = golden(minimax, "minimax_reasoning_split")
        assertEquals("Running it now.", t.text); assertEquals(listOf("run_workflow"), t.toolUses.map { it.name })
        assertEquals("The user wants the digest run.", thinking(r))
        val echoed = echo(t)[0] as JsonObject
        assertTrue(echoed.toString(), echoed.containsKey("reasoning_details"))
        assertTrue(t.usage != null)                                                              // usage on the last (finish) chunk
    }

    @Test fun minimaxThinkTagsStrippedForDisplayAndInTheTurn() {
        val (t, r) = golden(minimax, "minimax_think_tags")
        assertEquals("All good.", t.text)
        assertEquals("All good.", streamVisible(texts(r)))
    }

    @Test fun minimaxCumulativeChunksAreMergedNotDoubled() {
        val acc = OpenAiStreamAccumulator("MiniMax", tolerateCumulative = true)
        val lines = listOf("Hel", "Hello", "Hello world").map { "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$it\"}}]}\n" } +
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
        val out = ArrayList<StreamDelta>()
        OpenAiCompat.readSse(lines.joinToString("\n").lineSequence(), acc) { out += it }
        assertEquals("Hello world", OpenAiCompat.parseResponse(minimax, acc.toResponse(), "m").text)
        assertEquals("Hello world", out.filterIsInstance<StreamDelta.Text>().joinToString("") { it.text })
    }

    @Test fun openrouterCommentsIgnoredReasoningShown() {
        val (t, r) = golden(openrouter, "openrouter_comments")
        assertEquals("Answer: 42", t.text); assertEquals("Thinking hard.", thinking(r))
        assertTrue(t.usage != null)
    }

    @Test fun deepseekReasoningContent() {
        val (t, r) = golden(deepseek, "deepseek_reasoning_content")
        assertEquals("Done.", t.text); assertEquals("First, check.", thinking(r))
    }

    @Test fun minimaxBaseRespErrorMidStreamIsARealError() {
        try { stream(minimax, "minimax_base_resp_error"); fail() } catch (e: NodeException) {
            assertTrue(e.message, e.message!!.contains("insufficient credits/balance"))
            assertEquals(Fallback.THROW, streamFailureAction(true, e))
        }
    }

    @Test fun errorChunkIsARealErrorWithoutFallback() {
        try { stream(openai, "error_chunk"); fail() } catch (e: NodeException) {
            assertTrue(e.message, e.message!!.contains("The server had an error"))
            assertEquals(Fallback.THROW, streamFailureAction(true, e))
        }
    }

    @Test fun truncatedStreamSignalsFallback() {
        val acc = OpenAiStreamAccumulator(openai.label)
        try { OpenAiCompat.readSse(text("truncated_no_done.sse").lineSequence(), acc) {}; fail() } catch (e: StreamFailure) {
            assertTrue(acc.emittedAny)
            assertEquals(Fallback.RESET_AND_REDO, streamFailureAction(acc.emittedAny, e.cause ?: e))
        }
    }

    @Test fun eofAfterFinishReasonWithoutDoneIsComplete() {
        val (t, _) = golden(minimax, "minimax_reasoning_split")                                 // this fixture has no [DONE]
        assertEquals("tool_use", t.stopReason)
    }

    @Test fun unreadableChunksToleratedUpToThree() {
        val ok = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":\"stop\"}]}\n\n"
        OpenAiCompat.readSse(("data: nope\n\n".repeat(3) + ok).lineSequence(), OpenAiStreamAccumulator("X")) {}
        try { OpenAiCompat.readSse(("data: nope\n\n".repeat(4) + ok).lineSequence(), OpenAiStreamAccumulator("X")) {}; fail() } catch (e: StreamFailure) {}
    }

    @Test fun geminiObjectArgumentsKeptAsObject() {
        val acc = OpenAiStreamAccumulator("Gemini")
        val chunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"f\",\"arguments\":{\"a\":1}}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
        OpenAiCompat.readSse(chunk.lineSequence(), acc) {}
        val t = OpenAiCompat.parseResponse(openai, acc.toResponse(), "m")
        assertEquals("1", t.toolUses[0].input["a"].toString())
    }

    // ---------------------------------------------------------------- M1: MiniMax reasoning_split=false (inline <think> in content)

    private fun contentChunk(t: String) = "data: " + kotlinx.serialization.json.buildJsonObject {
        put("model", kotlinx.serialization.json.JsonPrimitive("MiniMax-M2.7"))
        put("choices", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject {
            put("index", kotlinx.serialization.json.JsonPrimitive(0))
            put("delta", kotlinx.serialization.json.buildJsonObject { put("content", kotlinx.serialization.json.JsonPrimitive(t)) })
        }) })
    } + "\n"
    private val usageChunk = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"%s\"}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":7,\"total_tokens\":57}}\n"

    @Test fun minimaxInlineThinkAcrossChunkBoundariesNeverShowsAndEchoesRaw() {
        val parts = listOf("<th", "ink>\nThe user asks about the ", "Porch tile.</th", "ink", ">\n\nHere", " are your 8 workflows.")
        val lines = parts.map(::contentChunk) + usageChunk.format("stop") + "data: [DONE]\n"
        val acc = OpenAiStreamAccumulator("MiniMax", tolerateCumulative = true)
        val seen = StringBuilder(); val shown = ArrayList<String>()
        OpenAiCompat.readSse(lines.joinToString("\n").lineSequence(), acc) { d -> if (d is StreamDelta.Text) { seen.append(d.text); shown += streamVisible(seen.toString()) } }
        for (v in shown) { assertTrue(v, "think" !in v && "Porch" !in v && "<" !in v) }            // (a) no partial/whole <think> ever displayed
        assertEquals("Here are your 8 workflows.", shown.last())
        assertEquals("The user asks about the Porch tile.", streamThinking(seen.toString()))        // live "Show thinking" still has the reasoning
        assertEquals("The user asks about the", streamThinking("<think>The user asks about the</th"))
        val t = OpenAiCompat.parseResponse(minimax, acc.toResponse(), "MiniMax-M2.7")
        assertEquals("Here are your 8 workflows.", t.text); assertEquals("end_turn", t.stopReason)   // (b) stripped for display / parsing
        val echoed = echo(t)[0] as JsonObject
        assertEquals(parts.joinToString(""), (echoed["content"] as kotlinx.serialization.json.JsonPrimitive).content)   // (c) raw chain echoed
        assertEquals(Usage.fromOpenAi(JSON.parseToJsonElement("{\"prompt_tokens\":50,\"completion_tokens\":7,\"total_tokens\":57}") as JsonObject), t.usage)   // (d)
    }

    @Test fun minimaxInlineThinkThenToolCalls() {
        val tool = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"enable_workflow\",\"arguments\":\"{\\\"id\\\":\"}}]}}]}\n" +
            "\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"seed-8\\\"}\"}}]}}]}\n"
        val lines = listOf("<think>Enable seed-8 ", "for the user.</think>").map(::contentChunk) + tool + usageChunk.format("tool_calls") + "data: [DONE]\n"
        val acc = OpenAiStreamAccumulator("MiniMax", tolerateCumulative = true)
        val out = ArrayList<StreamDelta>()
        OpenAiCompat.readSse(lines.joinToString("\n").lineSequence(), acc) { out += it }
        assertEquals("", streamVisible(out.filterIsInstance<StreamDelta.Text>().joinToString("") { it.text }))
        val t = OpenAiCompat.parseResponse(minimax, acc.toResponse(), "MiniMax-M2.7")
        assertEquals("tool_use", t.stopReason); assertEquals("", t.text)
        assertEquals(listOf("enable_workflow"), t.toolUses.map { it.name }); assertEquals("seed-8", t.toolUses[0].input["id"].let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(t.content.none { (it as JsonObject)["type"].toString().contains("text") })          // no empty text block beside the call
        val echoed = echo(t)[0] as JsonObject
        assertEquals("<think>Enable seed-8 for the user.</think>", (echoed["content"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(echoed.containsKey("tool_calls")); assertTrue(t.usage != null)
    }
}
