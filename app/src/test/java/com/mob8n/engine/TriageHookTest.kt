package com.mob8n.engine

import com.mob8n.core.Graph
import com.mob8n.core.Items
import com.mob8n.core.NodeInstance
import com.mob8n.core.Workflow
import com.mob8n.core.item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN5 §6.1: TriggerHub.triaged == TriageHook.apply (pure). */
class TriageHookTest {
    private val wf = Workflow("w", "WF", true, Graph(emptyList()))
    private val items: Items = listOf(item("text" to "hello"), item("text" to "call me NOW"))
    private fun node(engine: String?) = NodeInstance("t", "trigger.notification_posted", "Notif",
        if (engine == null) item() else item(TriggerHub.TRIAGE_KEY to engine))

    @Test fun keyMatchesTriggersLaneParam() = assertEquals("triageEngine", TriggerHub.TRIAGE_KEY)

    @Test fun nullPreFilterPassesItems() = runTest {
        assertSame(items, TriageHook.apply(null, wf, node("laya"), items))
    }

    @Test fun offOrAbsentNeverInvokesThePreFilter() = runTest {
        var calls = 0
        val f: suspend (Workflow, NodeInstance, Items) -> Items = { _, _, _ -> calls++; emptyList() }
        assertSame(items, TriageHook.apply(f, wf, node(null), items))
        assertSame(items, TriageHook.apply(f, wf, node("off"), items))
        assertEquals(0, calls)
    }

    @Test fun keptItemsComeFromThePreFilter() = runTest {
        val kept = TriageHook.apply({ _, n, its -> assertEquals("default", n.params[TriggerHub.TRIAGE_KEY]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }); its.drop(1) }, wf, node("default"), items)
        assertEquals(items.drop(1), kept)
    }

    @Test fun emptyDropsWithOneLogLine() = runTest {
        val logs = ArrayList<String>()
        assertNull(TriageHook.apply({ _, _, _ -> emptyList() }, wf, node("laya"), items, log = { logs += it }))
        assertEquals(listOf("triage WF/Notif: dropped"), logs)
    }

    @Test fun throwFailsOpen() = runTest {
        val logs = ArrayList<String>()
        assertSame(items, TriageHook.apply({ _, _, _ -> throw IllegalStateException("Cannot reach Laya") }, wf, node("laya"), items, log = { logs += it }))
        assertTrue(logs.single().contains("Cannot reach Laya"))
    }

    @Test fun ceilingFailsOpenOnVirtualClock() = runTest {
        val start = testScheduler.currentTime
        assertSame(items, TriageHook.apply({ _, _, its -> delay(60_000); emptyList<com.mob8n.core.Item>().also { its.size } }, wf, node("jev"), items))
        assertEquals(TriggerHub.TRIAGE_CEILING_MS, testScheduler.currentTime - start)
    }

    @Test fun callerCancellationPropagates() = runTest {
        val thrown = runCatching { TriageHook.apply({ _, _, _ -> throw CancellationException("kill switch") }, wf, node("laya"), items) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
