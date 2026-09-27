package com.mob8n.core

import com.mob8n.core.Fakes.edge
import com.mob8n.core.Fakes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphTest {
    private val t = node("t", TRIGGER_MANUAL, "Trigger")

    @Test fun topoOrderIsDeterministicAndRespectsEdges() {
        // diamond: t -> b, t -> a, a -> c, b -> c. Roots are sorted by id; after that Kahn follows edge order (b before a here).
        val g = Graph(listOf(node("c", "test.pass", "C"), node("b", "test.pass", "B"), t, node("a", "test.pass", "A")),
            listOf(edge("t", "b"), edge("t", "a"), edge("a", "c"), edge("b", "c")))
        val order = g.topoOrder()!!
        assertEquals(listOf("t", "b", "a", "c"), order)
        assertEquals(order, Graph(g.nodes.reversed(), g.edges).topoOrder())                 // node list order is irrelevant
        assertEquals(listOf("t", "a", "b", "c"), Graph(g.nodes, g.edges.reversed()).topoOrder())   // edge order decides siblings
        for (e in g.edges) assertTrue(order.indexOf(e.from) < order.indexOf(e.to))
        // several roots: sorted by id
        val roots = Graph(listOf(node("z", TRIGGER_MANUAL, "Z"), node("m", TRIGGER_MANUAL, "M"), t), emptyList())
        assertEquals(listOf("m", "t", "z"), roots.topoOrder())
    }

    @Test fun cycleGivesNullAndValidationError() {
        val g = Graph(listOf(t, node("a", "test.pass", "A"), node("b", "test.pass", "B")), listOf(edge("t", "a"), edge("a", "b"), edge("b", "a")))
        assertNull(g.topoOrder())
        assertTrue(g.validate(Fakes.catalog).any { it.contains("cycle") })
    }

    @Test fun validGraphHasNoErrors() {
        val g = Graph(listOf(t, node("a", "test.set", "A", "field" to "x", "value" to "1")), listOf(edge("t", "a")))
        assertEquals(emptyList<String>(), g.validate(Fakes.catalog))
    }

    @Test fun validateReportsStructuralAndParamProblems() {
        val g = Graph(
            listOf(t, node("a", "test.nope", "A"), node("b", "test.set", "A"), node("c", "test.if", "C"), node("d", "test.pass", "bad.name")),
            listOf(edge("t", "a"), edge("a", "b"), edge("b", "zzz"), edge("c", "d", fromPort = "maybe"), edge("d", "c", toPort = "side"), edge("d", "t")),
        )
        val errs = g.validate(Fakes.catalog)
        assertTrue(errs.toString(), errs.any { it.contains("unknown node type test.nope") })
        assertTrue(errs.any { it == "Node names must be unique" })
        assertTrue(errs.any { it.contains("Dangling edge") })
        assertTrue(errs.any { it.contains("no output port 'maybe'") })
        assertTrue(errs.any { it.contains("no input port 'side'") })
        assertTrue(errs.any { it.contains("triggers have no inputs") })
        assertTrue(errs.any { it.contains("may not contain '.'") })
        assertTrue(errs.any { it.contains("Field is required") })        // test.set without field
        assertTrue(errs.any { it.contains("Field is required") && it.startsWith("C") })
    }

    @Test fun graphWithoutTriggerIsInvalid() {
        val g = Graph(listOf(node("a", "test.pass", "A")), emptyList())
        assertTrue(g.validate(Fakes.catalog).any { it.contains("at least one trigger") })
    }

    @Test fun leavesExcludeNodesWithNonErrorOutgoingEdges() {
        val g = Graph(listOf(t, node("a", "test.pass", "A"), node("b", "test.pass", "B")), listOf(edge("t", "a"), edge("a", "b", fromPort = ERROR)))
        assertEquals(listOf("a", "b"), g.leaves().map { it.id })
        assertTrue(g.hasErrorEdge("a"))
    }
}
