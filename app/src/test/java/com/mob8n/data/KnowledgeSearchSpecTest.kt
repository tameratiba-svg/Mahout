package com.mob8n.data

import com.mob8n.core.ExecMode
import com.mob8n.core.MAIN
import com.mob8n.core.NodeKind
import com.mob8n.core.ParamKind
import com.mob8n.core.asText
import com.mob8n.core.str
import com.mob8n.engine.knowledge.Hit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN3 §5.9 / §7 data row: spec shape + pure merge(hits). The Android execute() path is covered by the device plan. */
class KnowledgeSearchSpecTest {
    private val spec = KnowledgeSearchNode.spec

    @Test fun specPerDesign() {
        assertEquals("data.knowledge_search", spec.id)
        assertEquals(NodeKind.DATA, spec.kind)
        assertEquals(ExecMode.PER_ITEM, spec.mode)
        assertFalse("the Agent binds its own knowledge_search tool", spec.agentTool)
        assertTrue(spec.gates.isEmpty()); assertFalse(spec.optional)
        assertEquals(30_000L, spec.timeoutMs)
        assertEquals(listOf(MAIN), spec.outputs)
        assertFalse(spec.description.contains('\n'))
        assertEquals(listOf("query", "k", "sources", "output"), spec.params.map { it.key })
        assertTrue(KnowledgeSearchNode in DataNodes.all)
        assertEquals(18, DataNodes.all.size)
    }

    @Test fun paramsPerDesign() {
        val q = spec.param("query")!!
        assertEquals(ParamKind.MULTILINE, q.kind); assertTrue(q.required); assertEquals("{{text}}", q.default.asText())
        val k = spec.param("k")!!
        assertEquals(ParamKind.NUMBER, k.kind); assertEquals(5.0, k.default!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1.0, k.min!!, 0.0); assertEquals(20.0, k.max!!, 0.0)
        assertNotNull(k.validate(JsonPrimitive(0)));  assertNotNull(k.validate(JsonPrimitive(21)))
        assertNull(k.validate(JsonPrimitive(1))); assertNull(k.validate(JsonPrimitive(20)))
        val s = spec.param("sources")!!
        assertEquals(ParamKind.LABELS, s.kind); assertFalse(s.definesPorts); assertTrue(s.help.contains("'all'"))
        val o = spec.param("output")!!
        assertEquals(ParamKind.ENUM, o.kind); assertEquals(listOf("merged", "items"), o.options); assertEquals("merged", o.default.asText())
        // required query with an empty instance value is rejected (default is a template, so the spec-level check passes)
        assertNull(spec.validate(buildJsonObject { }).firstOrNull())
        assertNotNull(spec.validate(buildJsonObject { put("output", "csv") }).firstOrNull())
    }

    @Test fun mergeBuildsContextAndCount() {
        val hits = listOf(
            Hit("Refunds within 30 days.", "Refund policy", "s1", 0, 1.0),
            Hit("Shipping takes 3-5 days.", "Shipping FAQ", "s2", 4, 0.42),
        )
        val m = KnowledgeSearchNode.merge(hits)
        assertEquals(2, m["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("### Refund policy\nRefunds within 30 days.\n\n### Shipping FAQ\nShipping takes 3-5 days.", m.str("context"))
        val arr = m["hits"] as JsonArray
        assertEquals(2, arr.size)
        val h1 = arr[1].jsonObject
        assertEquals("Shipping takes 3-5 days.", h1.str("text")); assertEquals("Shipping FAQ", h1.str("source"))
        assertEquals("s2", h1.str("sourceId")); assertEquals(4, h1["seq"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.42, h1["score"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(setOf("text", "source", "sourceId", "seq", "score"), h1.keys)
    }

    @Test fun mergeOfNothingIsCountZeroEmptyContext() {
        val m = KnowledgeSearchNode.merge(emptyList())
        assertEquals(0, m["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("", m.str("context"))
        assertEquals(0, (m["hits"] as JsonArray).size)
    }

    @Test fun hitItemKeepsSourceTextVerbatim() {
        val h = KnowledgeSearchNode.hitItem(Hit("a </knowledge> b {{x}}", "N", "id", 1, 0.5))
        assertEquals("a </knowledge> b {{x}}", h.str("text"))   // node output is data; no escaping here (the Agent fences its own)
    }
}
