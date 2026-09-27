package com.mob8n.core

import com.mob8n.core.Fakes.edge
import com.mob8n.core.Fakes.linear
import com.mob8n.core.Fakes.node
import com.mob8n.core.Fakes.wf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ExecutorTest {
    private val p = InMemoryPersistence()
    private val ex = Fakes.executor(p)
    private fun trig(w: Workflow) = w.graph.nodes.first { it.type == TRIGGER_MANUAL }
    private suspend fun run(w: Workflow, items: Items = listOf(item("n" to 1))): Executor.Outcome { p.workflows[w.id] = w; return ex.start(w, trig(w), items, null, 0) }

    @Before fun reset() { PassNode.executions = 0 }

    @Test fun linearRunSucceedsWithLogsAndLeafItems() = runBlocking {
        val w = linear("w", node("a", "test.pass", "A"), node("b", "test.set", "B", "field" to "who", "value" to "{{\$node.A.passed}}-{{n}}-{{\$workflow}}"))
        val o = run(w)
        assertEquals(RunStatus.SUCCESS, o.run.status)
        assertNotNull(o.run.endedAt)
        assertEquals(listOf("Trigger", "A", "B"), p.logsOf(o.run.runId).map { it.nodeName })
        assertTrue(p.logsOf(o.run.runId).all { it.status == NodeStatus.SUCCESS })
        assertEquals("true-1-WF w", o.leafItems.single().str("who"))
        assertEquals(RunStatus.SUCCESS, p.workflows["w"]!!.lastRunStatus)
    }

    @Test fun runRecordIsWrittenBeforeAnyNodeAndFinishedLast() = runBlocking {
        val o = run(linear("w", node("a", "test.pass", "A")))
        assertEquals("run:RUNNING", p.events.first())
        assertEquals("run:SUCCESS", p.events.last())
        assertEquals(o.run.runId, p.runs.keys.single())
    }

    @Test fun ifRoutesAndUntakenBranchIsSkipped() = runBlocking {
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("i", "test.if", "If", "field" to "ok"), node("y", "test.pass", "Yes"), node("z", "test.pass", "No")),
            listOf(edge("t", "i"), edge("i", "y", PORT_TRUE), edge("i", "z", PORT_FALSE)))
        val o = run(w, listOf(item("ok" to true)))
        assertEquals(RunStatus.SUCCESS, o.run.status)
        val byName = p.logsOf(o.run.runId).associateBy { it.nodeName }
        assertEquals(NodeStatus.SUCCESS, byName["Yes"]!!.status)
        assertEquals(NodeStatus.SKIPPED, byName["No"]!!.status)
        assertEquals(1, byName["If"]!!.output[PORT_TRUE].asArray()!!.size)
        assertEquals(true, o.leafItems.single().bool("passed"))
    }

    @Test fun errorWithoutEdgeFailsRunAtNodeAndKeepsSiblingLogs() = runBlocking {
        // topo order sorted by id: "a" (pass) runs before "b" (fail)
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("a", "test.pass", "Sibling"), node("b", "test.fail", "Boom")), listOf(edge("t", "a"), edge("t", "b")))
        val o = run(w)
        assertEquals(RunStatus.FAILED, o.run.status)
        assertEquals("b", o.run.failedNodeId)
        assertEquals("boom", o.run.error)
        val byName = p.logsOf(o.run.runId).associateBy { it.nodeName }
        assertEquals(NodeStatus.SUCCESS, byName["Sibling"]!!.status)
        assertEquals(NodeStatus.FAILED, byName["Boom"]!!.status)
        assertEquals("run:FAILED", p.events.last())
    }

    @Test fun errorWithEdgeIsRoutedAndDownstreamContinues() = runBlocking {
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("f", "test.fail", "Boom"), node("h", "test.pass", "Handler")), listOf(edge("t", "f"), edge("f", "h", ERROR)))
        val o = run(w)
        assertEquals(RunStatus.SUCCESS, o.run.status)
        val byName = p.logsOf(o.run.runId).associateBy { it.nodeName }
        assertEquals(NodeStatus.ERROR_ROUTED, byName["Boom"]!!.status)
        assertEquals("boom", byName["Boom"]!!.error)
        assertEquals(NodeStatus.SUCCESS, byName["Handler"]!!.status)
        val errItem = o.leafItems.single()
        assertEquals("boom", errItem.str("error")); assertEquals("Boom", errItem.str("node")); assertEquals("test.fail", errItem.str("nodeType"))
        assertEquals(1, errItem["input"]!!.asObject()!!["n"]!!.asDouble()!!.toInt())
    }

    @Test fun perItemPartialFailureKeepsGoodItems() = runBlocking {
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("f", "test.fail", "F", "always" to false), node("h", "test.pass", "H")), listOf(edge("t", "f"), edge("f", "h", ERROR)))
        val o = run(w, listOf(item("i" to 1), item("i" to 2, "bad" to true), item("i" to 3)))
        assertEquals(RunStatus.SUCCESS, o.run.status)
        val log = p.logsOf(o.run.runId).first { it.nodeName == "F" }
        assertEquals(NodeStatus.ERROR_ROUTED, log.status)
        assertEquals(listOf(1, 3), log.output[MAIN].asArray()!!.map { (it as Item).num("i")!!.toInt() })
        assertEquals(1, log.output[ERROR].asArray()!!.size)
        // F's only outgoing edge is `error`, so F is a leaf too: its 2 good items + H's 1 handled error item
        assertEquals(3, o.leafItems.size)
        assertEquals(2, o.leafItems.count { it.str("error") == null })
        assertEquals(1, o.leafItems.count { it.str("error") == "boom" && it.bool("passed") == true })
    }

    @Test fun timeoutProducesTimeoutStatusAndErrorItem() = runBlocking {
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("s", "test.slow", "Slow"), node("h", "test.pass", "H")), listOf(edge("t", "s"), edge("s", "h", ERROR)))
        val o = run(w)
        assertEquals(RunStatus.SUCCESS, o.run.status)
        val log = p.logsOf(o.run.runId).first { it.nodeName == "Slow" }
        assertEquals(NodeStatus.ERROR_ROUTED, log.status)                 // routed via the error edge; the timeout survives in the message
        assertEquals("Timed out after 50 ms", log.error)
        assertEquals("Timed out after 50 ms", o.leafItems.single().str("error"))
        // without an error edge the run fails with TIMEOUT logged
        val w2 = linear("w2", node("s", "test.slow", "Slow"))
        val o2 = run(w2)
        assertEquals(RunStatus.FAILED, o2.run.status)
        assertEquals(NodeStatus.TIMEOUT, p.logsOf(o2.run.runId).first { it.nodeName == "Slow" }.status)
    }

    /** F1: the run ceiling (TriggerHub.guarded's withTimeout) must propagate, not be misread as the node's own timeout and routed. */
    @Test fun outerCeilingCancelsRunAndDoesNotExecuteLaterNodes() = runBlocking {
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("s", "test.slow", "Slow").copy(timeoutMs = 5_000), node("h", "test.pass", "After")), listOf(edge("t", "s"), edge("s", "h", ERROR)))
        p.workflows["w"] = w
        var cancelled = false
        try { withTimeout(200) { ex.start(w, trig(w), listOf(item("n" to 1)), null, 0) } } catch (e: TimeoutCancellationException) { cancelled = true }
        assertTrue("outer TimeoutCancellationException must propagate", cancelled)
        assertEquals(0, PassNode.executions)
        assertTrue(p.logs.none { it.nodeName == "After" })
        assertTrue(p.logs.none { it.nodeName == "Slow" })                 // no per-node TIMEOUT log for the cancelled node either
        assertEquals(RunStatus.RUNNING, p.runs.values.single().status)   // terminal FAILED is written by TriggerHub.guarded (engine), not here
    }

    /** F6: stored secret values are masked in run.error, node_logs.error and the error item message stays intact for routing. */
    @Test fun nodeAndRunErrorStringsAreValueRedacted() = runBlocking {
        p.secrets["k"] = LeakNode.SECRET
        val masked = "HTTP 403 from https://x/?k=***: denied ***"
        val o = run(linear("w", node("l", "test.leak", "Leak")))
        assertEquals(RunStatus.FAILED, o.run.status)
        assertEquals(masked, o.run.error)
        assertEquals(masked, p.logsOf(o.run.runId).first { it.nodeName == "Leak" }.error)
        val routed = run(wf("w2", listOf(node("t", TRIGGER_MANUAL, "T"), node("l", "test.leak", "Leak"), node("h", "test.pass", "H")), listOf(edge("t", "l"), edge("l", "h", ERROR))))
        assertEquals(RunStatus.SUCCESS, routed.run.status)
        val log = p.logsOf(routed.run.runId).first { it.nodeName == "Leak" }
        assertEquals(NodeStatus.ERROR_ROUTED, log.status)
        assertEquals(masked, log.error)
        assertFalse(log.output.toString().contains(LeakNode.SECRET))
        assertFalse(p.runs.values.any { it.error?.contains(LeakNode.SECRET) == true })
    }

    /** F11: {{$count}} is the whole batch for PER_ITEM nodes, not 1. */
    @Test fun countIsTheBatchTotalForPerItemNodes() = runBlocking {
        val w = linear("w", node("a", "test.set", "A", "field" to "pos", "value" to "{{\$index}}/{{\$count}}"))
        val o = run(w, listOf(item("i" to 1), item("i" to 2), item("i" to 3)))
        assertEquals(listOf("0/3", "1/3", "2/3"), o.leafItems.map { it.str("pos") })
    }

    @Test fun mergeSeesBothPortsViaByPort() = runBlocking {
        fun w(mode: String) = wf("w-$mode", listOf(node("t", TRIGGER_MANUAL, "T"), node("a", "test.set", "A", "field" to "a", "value" to "1"),
            node("b", "test.set", "B", "field" to "b", "value" to "2"), node("m", "test.merge", "M", "mode" to mode)),
            listOf(edge("t", "a"), edge("t", "b"), edge("a", "m", MAIN, PORT_A), edge("b", "m", MAIN, PORT_B)))
        val append = run(w("append"))
        assertEquals(2, append.leafItems.size)
        assertEquals(listOf(1, null), append.leafItems.map { it.num("a")?.toInt() })
        val combine = run(w("combine_by_position"))
        val only = combine.leafItems.single()
        assertEquals(1, only.num("a")!!.toInt()); assertEquals(2, only.num("b")!!.toInt())
    }

    @Test fun disabledNodeIsSkippedAndEmitsNothing() = runBlocking {
        val w = linear("w", node("a", "test.pass", "A").copy(disabled = true), node("b", "test.pass", "B"))
        val o = run(w)
        assertEquals(RunStatus.SUCCESS, o.run.status)
        assertEquals(listOf(NodeStatus.SKIPPED, NodeStatus.SKIPPED), p.logsOf(o.run.runId).drop(1).map { it.status })
        assertTrue(o.leafItems.isEmpty())
    }

    @Test fun subWorkflowReturnsLeafItemsAndDepthIsLimited() = runBlocking {
        val callee = wf("callee", listOf(node("c", TRIGGER_CALLED, "Called"), node("s", "test.set", "S", "field" to "sub", "value" to "yes")), listOf(edge("c", "s")))
        p.workflows["callee"] = callee
        val o = run(linear("w", node("x", "test.sub", "X", "workflow" to "callee")))
        assertEquals(RunStatus.SUCCESS, o.run.status)
        assertEquals("yes", o.leafItems.single().str("sub"))
        assertEquals(o.run.runId, p.runs.values.first { it.workflowId == "callee" }.parentRunId)

        // self-recursion: manual + called triggers, sub calls itself -> depth limit
        val rec = wf("rec", listOf(node("t", TRIGGER_MANUAL, "T"), node("c", TRIGGER_CALLED, "C"), node("x", "test.sub", "X", "workflow" to "rec")), listOf(edge("t", "x"), edge("c", "x")))
        val o2 = run(rec)
        assertEquals(RunStatus.FAILED, o2.run.status)
        assertTrue(o2.run.error!!, o2.run.error!!.contains("depth limit"))
    }

    @Test fun runNodeRejectsTriggersAndSuspendButRunsTools() = runBlocking {
        val w = linear("w", node("a", "test.pass", "A"))
        p.workflows["w"] = w
        try { ex.runNode("r", w, TRIGGER_MANUAL, EMPTY, EMPTY, emptyMap(), emptyMap(), 0); fail("trigger accepted") }
        catch (e: NodeException) { assertTrue(e.message!!.contains("Triggers")) }
        try { ex.runNode("r", w, "test.approval", EMPTY, EMPTY, emptyMap(), emptyMap(), 0); fail("suspend accepted") }
        catch (e: NodeException) { assertTrue(e.message!!.contains("cannot suspend")) }
        try { ex.runNode("r", w, "test.set", EMPTY, EMPTY, emptyMap(), emptyMap(), 0); fail("invalid params accepted") }
        catch (e: NodeException) { assertTrue(e.message!!.contains("required")) }
        val out = ex.runNode("r", w, "test.set", item("field" to "k", "value" to "v"), item("z" to 1), emptyMap(), emptyMap(), 0)
        assertEquals("v", out.single().str("k"))
        // through a node (Agent-style)
        val o = run(linear("w2", node("tool", "test.tool", "Tool", "tool" to "test.set", "paramsJson" to """{"field":"k","value":"{{n}}"}""")))
        assertEquals(RunStatus.SUCCESS, o.run.status)
        assertEquals("1", o.leafItems.single().str("k"))
    }

    @Test fun matchInstancesAppliesAcceptsAndToItemsAcrossEnabledWorkflows() = runBlocking {
        fun w(id: String, match: String?, enabled: Boolean = true, disabled: Boolean = false) =
            wf(id, listOf(node("e", "trigger.event", "E", "match" to match).copy(disabled = disabled), node("a", "test.pass", "A")), listOf(edge("e", "a")), enabled)
        p.workflows["yes"] = w("yes", "x"); p.workflows["no"] = w("no", "y"); p.workflows["off"] = w("off", "x", enabled = false); p.workflows["any"] = w("any", null)
        p.workflows["dis"] = w("dis", "x", disabled = true)
        val enabled = p.enabledWorkflows()
        val matched = EventTrigger.matchInstances(enabled, item("kind" to "x"))
        assertEquals(setOf("yes", "any"), matched.map { it.first.id }.toSet())
        assertTrue(matched.all { it.second.id == "e" && it.third.single().str("kind") == "x" })
        assertTrue(EventTrigger.matchInstances(enabled, item("kind" to "x", "drop" to true)).isEmpty())
    }

    @Test fun unknownNodeTypeFailsRun() = runBlocking {
        val o = run(linear("w", node("a", "test.missing", "A")))
        assertEquals(RunStatus.FAILED, o.run.status)
        assertNotNull(o.run.endedAt)
        assertEquals("a", o.run.failedNodeId)
        assertTrue(o.run.error!!.contains("unknown node type"))
    }

    @Test fun asyncRunWorkflowCarriesDepthAndStartRefusesBeyondMax() = runBlocking {
        val fired = ArrayList<Triple<String, Items, Int>>()
        val ex2 = Fakes.executor(p, Hooks(fireWorkflow = { id, items, depth -> fired += Triple(id, items, depth) }))
        val w = linear("w", node("f", "test.fire", "Fire", "workflow" to "other"))
        p.workflows[w.id] = w
        val o = ex2.start(w, trig(w), listOf(item("n" to 1)), null, 2)
        assertEquals(RunStatus.SUCCESS, o.run.status)
        assertEquals(Triple("other", listOf(item("n" to 1)), 3), fired.single())          // depth = caller depth + 1
        val refused = ex2.start(w, trig(w), listOf(item("n" to 1)), null, ex2.maxDepth + 1)
        assertEquals(RunStatus.FAILED, refused.run.status)
        assertEquals("Sub-workflow depth limit ${ex2.maxDepth} reached", refused.run.error)
        assertNotNull(refused.run.endedAt)
        assertEquals(1, fired.size)                                                       // nothing ran, nothing fired
        assertTrue(p.logs.none { it.runId == refused.run.runId })
    }

    @Test fun upstreamTemplatesSeeNamedNodesAndVars() = runBlocking {
        p.vars["greeting"] = JsonPrimitive("hi")
        val w = linear("w", node("a", "test.set", "First", "field" to "x", "value" to "{{\$vars.greeting}}"), node("b", "test.set", "Second", "field" to "y", "value" to "{{\$node.First.x}}!"))
        assertEquals("hi!", run(w).leafItems.single().str("y"))
    }
}
