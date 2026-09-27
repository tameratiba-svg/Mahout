package com.mob8n.actions

import com.mob8n.core.ExecMode
import com.mob8n.core.NodeException
import com.mob8n.core.NodeKind
import com.mob8n.core.ParamKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN3 §7 actions row: pickSource matrix + the two knowledge action specs. */
class KnowledgeActionsTest {
    private fun blank(block: () -> Unit) {
        try { block(); fail("expected NodeException") } catch (e: NodeException) { assertEquals("Give a document URI, a URL or text", e.message) }
    }

    @Test fun autoPrefersUrlThenUriThenText() {
        assertEquals("url" to "https://x", pickSource("auto", "t", "content://d", "https://x"))
        assertEquals("uri" to "content://d", pickSource("auto", "t", "content://d", ""))
        assertEquals("uri" to "content://d", pickSource("auto", "", "content://d", "  "))
        assertEquals("text" to "t", pickSource("auto", "t", "", ""))
        assertEquals("text" to "t", pickSource("auto", "t", " ", "\n"))
    }

    @Test fun explicitSourceUsesOnlyThatValue() {
        assertEquals("text" to "t", pickSource("text", "t", "content://d", "https://x"))
        assertEquals("uri" to "content://d", pickSource("uri", "t", "content://d", "https://x"))
        assertEquals("url" to "https://x", pickSource("url", "t", "content://d", "https://x"))
        blank { pickSource("text", "", "content://d", "https://x") }
        blank { pickSource("uri", "t", "", "https://x") }
        blank { pickSource("url", "t", "content://d", "   ") }
    }

    @Test fun allBlankThrowsUserText() {
        blank { pickSource("auto", "", "", "") }
        blank { pickSource("auto", " ", "\t", "\n") }
        blank { pickSource("bogus", "t", "u", "w") }   // unknown choice value never silently picks something
    }

    @Test fun addSpec() {
        val s = KnowledgeAddNode.spec
        assertEquals("action.knowledge_add", s.id); assertEquals(NodeKind.ACTION, s.kind); assertEquals(ExecMode.PER_ITEM, s.mode)
        assertTrue(s.agentTool); assertEquals(180_000L, s.timeoutMs); assertTrue(s.gates.isEmpty())
        assertEquals(listOf("source", "text", "uri", "url", "name", "group", "pinned", "replace"), s.params.map { it.key })
        val source = s.param("source")!!
        assertEquals(ParamKind.ENUM, source.kind); assertEquals(listOf("auto", "text", "uri", "url"), source.options); assertEquals(JsonPrimitive("auto"), source.default)
        // visibleWhen per source: each value field shows for auto + its own choice only
        for ((k, kind) in listOf("text" to ParamKind.MULTILINE, "uri" to ParamKind.TEXT, "url" to ParamKind.TEXT)) {
            val p = s.param(k)!!
            assertEquals(k, kind, p.kind); assertFalse(k, p.required)
            assertEquals(k, "source", p.visibleWhen!!.key); assertEquals(k, listOf("auto", k), p.visibleWhen!!.equalsAny)
            assertEquals(k, JsonPrimitive("{{$k}}"), p.default)
        }
        for (k in listOf("name", "group", "pinned", "replace")) assertNull(k, s.param(k)!!.visibleWhen)
        assertEquals(JsonPrimitive("{{fileName ?? title ?? subject ?? url}}"), s.param("name")!!.default)
        assertEquals(JsonPrimitive(false), s.param("pinned")!!.default)
        assertEquals(JsonPrimitive(true), s.param("replace")!!.default)
        assertTrue(s.validate(kotlinx.serialization.json.buildJsonObject {}).isEmpty())   // nothing required at edit time; blanks fail at run time via pickSource
    }

    @Test fun addToolDefIsStrictAndCoversEveryParam() {
        val def = KnowledgeAddNode.spec.toolDef()
        assertEquals("action_knowledge_add", def["name"]!!.jsonPrimitive.content)
        assertTrue(def["strict"]!!.jsonPrimitive.boolean)
        val props = def["input_schema"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(KnowledgeAddNode.spec.params.map { it.key }.toSet(), props.keys)
    }

    @Test fun removeSpec() {
        val s = KnowledgeRemoveNode.spec
        assertEquals("action.knowledge_remove", s.id); assertEquals(NodeKind.ACTION, s.kind); assertEquals(ExecMode.PER_ITEM, s.mode)
        assertFalse(s.agentTool); assertEquals(10_000L, s.timeoutMs)
        assertEquals(listOf("name"), s.params.map { it.key })
        val name = s.param("name")!!
        assertEquals(ParamKind.TEXT, name.kind); assertTrue(name.required); assertNull(name.default)
        assertEquals(listOf("name is required").size, s.validate(kotlinx.serialization.json.buildJsonObject {}).size)
    }

    @Test fun registeredInActionNodes() {
        assertEquals(35, ActionNodes.all.size)
        assertTrue(ActionNodes.all.contains(KnowledgeAddNode)); assertTrue(ActionNodes.all.contains(KnowledgeRemoveNode))
        assertEquals(ActionNodes.all.size, ActionNodes.all.map { it.spec.id }.toSet().size)
    }
}
