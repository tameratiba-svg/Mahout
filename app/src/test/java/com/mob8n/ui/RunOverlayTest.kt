package com.mob8n.ui

import com.mob8n.core.ERROR
import com.mob8n.core.Edge
import com.mob8n.core.Graph
import com.mob8n.core.MAIN
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** DESIGN6 §6.7 / §9: which nodes pulse and which edges flow while a run is live (inferred from node_logs). */
class RunOverlayTest {
    private fun n(id: String, type: String = "logic.set") = NodeInstance(id = id, type = type, name = id)
    private val t = n("T", "trigger.manual")
    private val a = n("A"); private val b = n("B"); private val c = n("C"); private val h = n("H")
    private val linear = Graph(listOf(t, a, b, c), listOf(Edge("T", MAIN, "A", MAIN), Edge("A", MAIN, "B", MAIN), Edge("B", MAIN, "C", MAIN)))

    @Test fun noLogsRunningActivatesTriggers() {
        assertEquals(RunOverlay(setOf("T"), emptySet()), runOverlay(linear, emptyMap(), running = true))
    }

    @Test fun linearChainActivatesTheNextNodeAndItsEdge() {
        val o = runOverlay(linear, mapOf("T" to NodeStatus.SUCCESS, "A" to NodeStatus.SUCCESS), running = true)
        assertEquals(setOf("B"), o.active)
        assertEquals(setOf(Edge("A", MAIN, "B", MAIN).key), o.flowing)
    }

    @Test fun errorRoutedActivatesOnlyTheErrorPortSuccessor() {
        val g = Graph(listOf(t, a, b, h), listOf(Edge("T", MAIN, "A", MAIN), Edge("A", MAIN, "B", MAIN), Edge("A", ERROR, "H", MAIN)))
        val o = runOverlay(g, mapOf("T" to NodeStatus.SUCCESS, "A" to NodeStatus.ERROR_ROUTED), running = true)
        assertEquals(setOf("H"), o.active)
        assertEquals(setOf(Edge("A", ERROR, "H", MAIN).key), o.flowing)
        // and a SUCCESS node never lights its error edge
        val ok = runOverlay(g, mapOf("T" to NodeStatus.SUCCESS, "A" to NodeStatus.SUCCESS), running = true)
        assertEquals(setOf("B"), ok.active)
    }

    @Test fun failedNodeActivatesNothing() {
        val o = runOverlay(linear, mapOf("T" to NodeStatus.SUCCESS, "A" to NodeStatus.FAILED), running = true)
        assertEquals(RunOverlay.NONE, o)
    }

    @Test fun notRunningIsEmpty() {
        assertEquals(RunOverlay.NONE, runOverlay(linear, emptyMap(), running = false))
        assertEquals(RunOverlay.NONE, runOverlay(linear, mapOf("T" to NodeStatus.SUCCESS), running = false))
    }
}
