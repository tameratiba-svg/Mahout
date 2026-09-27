package com.mob8n.core

import com.mob8n.core.Fakes.edge
import com.mob8n.core.Fakes.linear
import com.mob8n.core.Fakes.node
import com.mob8n.core.Fakes.wf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuspendResumeTest {
    private val p = InMemoryPersistence()
    private val suspended = ArrayList<SuspendedRun>()
    private var clock = 1_000L
    private val ex = Fakes.executor(p, Hooks(onSuspend = { suspended += it }), clock = { clock })
    private fun trig(w: Workflow) = w.graph.nodes.first { it.type == TRIGGER_MANUAL }
    private suspend fun run(w: Workflow, items: Items): Executor.Outcome { p.workflows[w.id] = w; return ex.start(w, trig(w), items, null, 0) }

    @Before fun reset() { WaitItemNode.executions = 0; PassNode.executions = 0 }

    @Test fun perItemSuspendPersistsPendingIndexAndPortsThenResumesWithoutReexecuting() = runBlocking {
        val w = linear("w", node("a", "test.pass", "A"), node("wt", "test.wait_item", "Wait"), node("b", "test.pass", "B"))
        val items = listOf(item("i" to 1), item("i" to 2, "wait" to true), item("i" to 3))
        val o = run(w, items)
        assertEquals(RunStatus.SUSPENDED, o.run.status)
        assertNull(o.run.endedAt)
        assertEquals("wt", o.run.failedNodeId)
        val s = p.suspended[o.run.runId]!!
        assertEquals(1, s.state.pendingIndex)
        assertEquals(1, s.state.pendingPorts[MAIN]!!.size)              // item 1 already produced before the suspend
        assertEquals(listOf("t", "a"), s.state.done)
        assertEquals(SuspendKind.APPROVAL, s.kind)
        assertEquals(1, s.payload.num("idx")!!.toInt())
        assertEquals(clock + 24 * 3600_000L, s.expiresAt)              // default approval TTL
        assertEquals(listOf(s), suspended)                              // hook called AFTER persistence
        assertTrue(p.events.indexOf("suspended") < p.events.indexOf("run:SUSPENDED"))
        assertEquals(NodeStatus.SUSPENDED, p.logsOf(o.run.runId).first { it.nodeName == "Wait" }.status)
        assertEquals(2, WaitItemNode.executions)
        assertEquals(3, PassNode.executions)                            // A ran for all 3 items; B not yet

        clock = 5_000L
        val r = ex.resume(o.run.runId, DECISION_APPROVE)!!
        assertEquals(RunStatus.SUCCESS, r.run.status)
        assertEquals(o.run.runId, r.run.runId)                         // RunRecord preserved across resume
        assertEquals(TRIGGER_MANUAL, r.run.triggerType); assertEquals(1_000L, r.run.startedAt); assertEquals(5_000L, r.run.endedAt)
        assertNull(r.run.failedNodeId)
        assertEquals(3, WaitItemNode.executions)                        // only item 3 executed after resume (item 2 went through resume())
        assertEquals(6, PassNode.executions)                            // B ran for 3 items; A did NOT re-run
        assertEquals(listOf(1, 2, 3), r.leafItems.map { it.num("i")!!.toInt() })
        assertEquals(listOf(true, null, true), r.leafItems.map { it.bool("seen") })   // default resume passes the pending item through unchanged
        assertTrue(p.suspended.isEmpty())
        assertNull(ex.resume(o.run.runId, DECISION_APPROVE))            // second resume is a no-op
    }

    @Test fun payloadReachesResumeAndDenyRoutesToDeclaredPort() = runBlocking {
        val w = wf("w", listOf(node("t", TRIGGER_MANUAL, "T"), node("ap", "test.approval", "Ask"), node("ok", "test.pass", "Ok"), node("no", "test.pass", "No")),
            listOf(edge("t", "ap"), edge("ap", "ok"), edge("ap", "no", "denied")))
        val o = run(w, listOf(item("i" to 1), item("i" to 2)))
        assertEquals(RunStatus.SUSPENDED, o.run.status)
        assertEquals("Approve 2?", p.suspended[o.run.runId]!!.title)
        assertEquals(listOf(DECISION_APPROVE, DECISION_DENY), p.suspended[o.run.runId]!!.choices)
        val approved = ex.resume(o.run.runId, DECISION_APPROVE)!!
        assertEquals(RunStatus.SUCCESS, approved.run.status)
        assertEquals(listOf("abc", "abc"), approved.leafItems.map { it.str("token") })   // payload handed back
        assertEquals(NodeStatus.SKIPPED, p.logsOf(o.run.runId).last { it.nodeName == "No" }.status)

        val o2 = run(w, listOf(item("i" to 1)))
        val denied = ex.resume(o2.run.runId, DECISION_DENY)!!
        assertEquals(RunStatus.SUCCESS, denied.run.status)
        val byName = p.logsOf(o2.run.runId).filter { it.at >= 0 }.associateBy { it.nodeName }
        assertEquals(NodeStatus.SUCCESS, byName["No"]!!.status)
        assertEquals(1, byName["No"]!!.input.size)
    }

    @Test fun defaultResumeDenyAndTimeoutFailTheRun() = runBlocking {
        val w = linear("w", node("wt", "test.wait_item", "Wait"))
        val o = run(w, listOf(item("wait" to true)))
        val denied = ex.resume(o.run.runId, DECISION_DENY)!!
        assertEquals(RunStatus.FAILED, denied.run.status)
        assertEquals("Denied by user", denied.run.error)
        assertEquals("wt", denied.run.failedNodeId)

        val o2 = run(w, listOf(item("wait" to true)))
        val timedOut = ex.resume(o2.run.runId, DECISION_TIMEOUT)!!
        assertEquals(RunStatus.FAILED, timedOut.run.status)
        assertTrue(timedOut.run.error!!.contains("Timed out"))
    }

    @Test fun timerSuspendCarriesResumeAtAndDefaultResumeContinues() = runBlocking {
        val timer = object : Node() {
            override val spec = NodeSpec("test.timer", "Timer", NodeKind.LOGIC, "suspends on a timer", mode = ExecMode.LIST)
            override suspend fun execute(ctx: ExecutionContext, input: NodeInput) = NodeResult.Suspend(SuspendKind.TIMER, "later", resumeAtMs = ctx.nowMs() + 60_000)
        }
        val ex2 = Executor(Catalog(listOf(Fakes.all + timer)), p, Hooks(onSuspend = { suspended += it }), null, nowMs = { clock }, logger = {})
        val w = linear("w", node("d", "test.timer", "Delay"), node("b", "test.pass", "B"))
        p.workflows["w"] = w
        val o = ex2.start(w, trig(w), listOf(item("i" to 1)), null, 0)
        assertEquals(RunStatus.SUSPENDED, o.run.status)
        val s = suspended.single()
        assertEquals(SuspendKind.TIMER, s.kind); assertEquals(clock + 60_000, s.resumeAtMs); assertEquals(s.resumeAtMs, s.expiresAt)
        val r = ex2.resume(o.run.runId, DECISION_TIMER)!!
        assertEquals(RunStatus.SUCCESS, r.run.status)
        assertEquals(true, r.leafItems.single().bool("passed"))
    }

    @Test fun saveSuspendedFailureFailsTheRun() = runBlocking {
        p.failSuspended = true
        val w = linear("w", node("ap", "test.approval", "Ask"))
        val o = run(w, listOf(item("i" to 1)))
        assertEquals(RunStatus.FAILED, o.run.status)
        assertEquals("could not persist suspended run", o.run.error)
        assertEquals("ap", o.run.failedNodeId)
        assertNotNull(o.run.endedAt)
        assertTrue(suspended.isEmpty())                                 // hook never called
        assertTrue(p.suspended.isEmpty())
        val log = p.logsOf(o.run.runId).first { it.nodeName == "Ask" }
        assertEquals(NodeStatus.FAILED, log.status)
        assertTrue(log.error!!.contains("disk full"))
    }

    /** F9: workflow deleted while waiting -> the run row is closed FAILED instead of staying SUSPENDED forever. */
    @Test fun resumeAfterWorkflowDeletedFailsTheRunRow() = runBlocking {
        val w = linear("w", node("wt", "test.wait_item", "Wait"), node("b", "test.pass", "B"))
        val o = run(w, listOf(item("wait" to true)))
        assertEquals(RunStatus.SUSPENDED, o.run.status)
        p.workflows.remove("w")
        clock = 9_000L
        assertNull(ex.resume(o.run.runId, DECISION_APPROVE))
        assertNull(p.loadSuspended(o.run.runId))
        val r = p.loadRun(o.run.runId)!!
        assertEquals(RunStatus.FAILED, r.status)
        assertEquals(9_000L, r.endedAt)
        assertEquals("wt", r.failedNodeId)
        assertEquals("workflow deleted", r.error)
        assertEquals(0, PassNode.executions)
    }

    /** F10: the suspended node was removed from the graph -> the decision has nowhere to land; fail explicitly, never SUCCESS. */
    @Test fun resumeAgainstEditedGraphWithoutTheSuspendedNodeFails() = runBlocking {
        val w = linear("w", node("wt", "test.wait_item", "Wait"), node("b", "test.pass", "B"))
        val o = run(w, listOf(item("wait" to true)))
        assertEquals(RunStatus.SUSPENDED, o.run.status)
        p.workflows["w"] = linear("w", node("b", "test.pass", "B"))       // Wait removed, trigger wired straight to B
        val r = ex.resume(o.run.runId, DECISION_APPROVE)!!
        assertEquals(RunStatus.FAILED, r.run.status)
        assertEquals("workflow changed while waiting", r.run.error)
        assertEquals("wt", r.run.failedNodeId)
        assertNotNull(r.run.endedAt)
        assertTrue(p.suspended.isEmpty())
        assertEquals(0, PassNode.executions)                            // B must not run on stale/empty items
    }

    @Test fun suspendedRunRoundTripsThroughJson() = runBlocking {
        val w = linear("w", node("ap", "test.approval", "Ask"))
        val o = run(w, listOf(item("i" to 1)))
        val s = p.suspended[o.run.runId]!!
        val back = JSON.decodeFromString(SuspendedRun.serializer(), JSON.encodeToString(SuspendedRun.serializer(), s))
        assertEquals(s, back)
    }
}
