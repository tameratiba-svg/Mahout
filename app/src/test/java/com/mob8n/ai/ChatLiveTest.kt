package com.mob8n.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 D6/§9: the live turn is written around the UNCHANGED drive/AgentNode.loop and never changes what is persisted. */
class ChatLiveTest {
    private var clock = 0L
    private val flow = MutableStateFlow<LiveTurn?>(null)
    private val live = LiveRecorder(flow, { clock })
    private fun fresh() = AgentNode.State(mutableListOf(ClaudeClient.userMessage("hello")), 0, mutableListOf(), emptyList())
    private fun use(id: String, name: String) = Fakes.toolUse(id, name, buildJsonObject { put("command", "ls") })
    private val done = Fakes.turn("end_turn", Fakes.textBlock("All done."))

    /** The fake provider: streams the turn's text in 3-char pieces (10 ms apart) and its tool calls, like OpenAiCompat/ClaudeClient would. */
    private fun streamingStep(turns: List<Turn>, textSeen: MutableList<String>? = null, stream: Boolean = true): suspend (List<JsonObject>) -> Turn {
        var n = 0
        return { _ ->
            val t = turns[n++]
            live.beginStep()
            if (stream) {
                t.text.chunked(3).forEach { live.delta(StreamDelta.Text(it)); clock += 10; textSeen?.add("${flow.value!!.segments.size}|${flow.value!!.segments.last().text}") }
                t.toolUses.forEachIndexed { i, u -> live.delta(StreamDelta.ToolStart(i, u.id, u.name)); live.delta(StreamDelta.ToolArgs(i, 12)) }
            }
            clock += 50
            t.also(live::endStep)
        }
    }
    private fun wrapped(tools: Map<String, AgentTool>, mode: PermissionMode) = tools.mapValues { (_, t) ->
        ChatRunner.wrap(t, Permissions.decide(mode, Risk.CODING), { true }, live::done) { live.running(it) }
    }

    @Test fun segmentsGrowAndToolsRunThroughTheirStates() = runBlocking {
        val states = ArrayList<LiveTool.State>()
        val tools = wrapped(mapOf("list_workflows" to Fakes.opTool("list_workflows") { states += flow.value!!.segments.last().tools.single().state }), PermissionMode.AUTO)
        val seen = ArrayList<String>()
        val turns = listOf(Fakes.turn("tool_use", Fakes.textBlock("Checking your workflows."), use("l1", "list_workflows")), done)
        val stepBase = streamingStep(turns, seen)
        var afterTool: LiveTurn? = null
        var steps = 0
        val step: suspend (List<JsonObject>) -> Turn = { m -> if (steps++ == 1) afterTool = flow.value; stepBase(m) }
        val persisted = ArrayList<LiveTurn?>()
        val r = ChatRunner.drive(fresh(), tools, 12, step, persist = { live.clear(); persisted += flow.value }, await = { error("auto mode never asks") })
        assertEquals("end_turn", r.stopReason)
        // text grew through throttled publishes and never shrank
        val perCall = seen.groupBy({ it.substringBefore('|') }, { it.substringAfter('|') }).values
        assertEquals(2, perCall.size)
        assertTrue(seen.toString(), perCall.all { l -> l.zipWithNext().all { (a, b) -> b.startsWith(a) } } && perCall.first().let { it.last().length > it.first().length })
        assertEquals(listOf(LiveTool.State.RUNNING), states)                                   // RUNNING while it ran
        val seg = afterTool!!.segments.first()
        assertEquals("Checking your workflows.", seg.text)
        assertEquals(LiveTool.State.DONE, seg.tools.single().state); assertEquals("ok", seg.tools.single().result)
        assertEquals("l1", seg.tools.single().id); assertEquals(12, seg.tools.single().argsChars)
        assertTrue(afterTool!!.segments.size == 1 && !afterTool!!.streaming)                     // captured just before the next call starts
        assertEquals(listOf<LiveTurn?>(null), persisted)                                        // null after each flush
        assertNull(flow.value)
    }

    @Test fun deniedIdsFailWithDeniedByUser() = runBlocking {
        val snapshots = ArrayList<List<LiveTool>>()
        val shell = Fakes.opTool("run_shell", needsApproval = true) { snapshots += flow.value!!.segments.flatMap { it.tools } }
        val tools = wrapped(mapOf("run_shell" to shell), PermissionMode.ASK)
        val turns = listOf(Fakes.turn("tool_use", use("s1", "run_shell"), use("s2", "run_shell")), done)
        val flushedLive = ArrayList<LiveTurn?>()
        ChatRunner.drive(fresh(), tools, 12, streamingStep(turns), persist = { live.clear(); flushedLive += flow.value },
            await = { uses -> assertNull(flow.value); val m = mapOf("s1" to true, "s2" to false); live.decided(uses, m); m })
        val during = snapshots.single()
        assertEquals(LiveTool.State.RUNNING, during.first { it.id == "s1" }.state)
        val s2 = during.first { it.id == "s2" }
        assertEquals(LiveTool.State.FAILED, s2.state); assertEquals(ChatRunner.DENIED, s2.result)
        assertTrue(flushedLive.all { it == null }); assertNull(flow.value)
    }

    @Test fun failedToolAndResetAndThrottle() = runBlocking {
        live.beginStep()
        live.delta(StreamDelta.Text("a")); assertEquals("a", flow.value!!.segments.last().text)
        clock = 10; live.delta(StreamDelta.Text("b")); assertEquals("a", flow.value!!.segments.last().text)   // throttled
        clock = 40; live.delta(StreamDelta.Text("c")); assertEquals("abc", flow.value!!.segments.last().text)
        clock = 80; live.delta(StreamDelta.Reset); live.delta(StreamDelta.Text("x"))
        clock = 120; live.delta(StreamDelta.Text("y")); assertEquals("xy", flow.value!!.segments.last().text)
        live.endStep(Fakes.turn("tool_use", Fakes.textBlock("xy"), use("f1", "boom")))
        assertTrue(!flow.value!!.streaming)
        val boom = ChatRunner.wrap(Fakes.opTool("boom") { throw IllegalStateException("kaput") }, Permissions.decide(PermissionMode.AUTO, Risk.READ), { true }, live::done) { live.running(it) }
        runCatching { boom.call(buildJsonObject {}) }
        val t = flow.value!!.segments.single().tools.single()
        assertEquals(LiveTool.State.FAILED, t.state); assertEquals("kaput", t.result); assertEquals(120L, t.endedMs)
    }

    /** The persistence contract: rows are identical with and without streaming deltas. */
    @Test fun persistedMessagesIdenticalWithAndWithoutDeltas() = runBlocking {
        fun turns() = listOf(Fakes.turn("tool_use", Fakes.textBlock("Let me look."), use("l1", "list_workflows")), done)
        fun tools() = wrapped(mapOf("list_workflows" to Fakes.opTool("list_workflows")), PermissionMode.AUTO)
        val a = fresh(); val b = fresh()
        val rowsA = ArrayList<List<JsonObject>>(); val rowsB = ArrayList<List<JsonObject>>()
        ChatRunner.drive(a, tools(), 12, streamingStep(turns()), persist = { rowsA += a.messages.toList(); live.clear() }, await = { error("") })
        ChatRunner.drive(b, tools(), 12, streamingStep(turns(), stream = false), persist = { rowsB += b.messages.toList() }, await = { error("") })
        assertEquals(rowsB, rowsA)
        assertEquals(4, a.messages.size)
    }
}
