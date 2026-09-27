package com.mob8n.ui

import com.mob8n.core.Catalog
import com.mob8n.core.NodeKind
import com.mob8n.logic.LogicNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F32: port hit boxes on one card edge must not overlap, or the lower port steals taps meant for the one drawn above it. */
class PortHitTest {
    @Test fun hitBoxesNeverExceedTheSlotPitch() {
        for (n in 1..5) {
            assertTrue("inputs=$n", portHitHeight(n, true) <= CARD_H / (n + 1))
            assertTrue("outputs=$n", portHitHeight(n, false) <= CARD_H / (n + 2))   // +1 for the error diamond, +1 for the edge margins
            assertTrue(portHitHeight(n, true) > 0f && portHitHeight(n, false) > 0f)
        }
        assertEquals(30.666f, portHitHeight(1, false), 0.001f)     // single output + error: pitch 92/3
        assertEquals(23f, portHitHeight(2, false), 0.001f)         // IF: true/false/error at 23/46/69 dp
        assertEquals(46f, portHitHeight(1, true), 0.001f)          // one input: still capped by the card height
        assertEquals(30.666f, portHitHeight(2, true), 0.001f)      // merge a/b
    }

    @Test fun catalogSpecsWithDefaultParamsFitTheirCards() {
        val catalog = Catalog(listOf(LogicNodes.all))
        for (spec in catalog.nodes.map { it.spec }.filter { it.kind != NodeKind.TRIGGER }) {
            val outs = spec.outputPorts(defaultParams(spec)).size
            assertTrue(spec.id, portHitHeight(outs, false) * (outs + 1) <= CARD_H)   // outputs + error diamond stack inside the card
            assertTrue(spec.id, portHitHeight(spec.inputs.size, true) * spec.inputs.size <= CARD_H)
        }
    }
}
