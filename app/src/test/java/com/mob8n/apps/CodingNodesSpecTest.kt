package com.mob8n.apps

import com.mob8n.core.ExecMode
import com.mob8n.core.MAIN
import com.mob8n.core.NodeKind
import com.mob8n.core.ParamKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingNodesSpecTest {
    private val s = ShellRunNode.spec

    @Test fun shellRunSpec() {
        assertEquals("app.shell_run", s.id); assertEquals("app_shell_run", s.toolName)
        assertEquals(NodeKind.ACTION, s.kind); assertEquals(ExecMode.PER_ITEM, s.mode)
        assertTrue(s.agentTool); assertFalse(s.optional); assertEquals(emptyList<Any>(), s.gates)
        assertEquals(listOf(MAIN), s.outputs)
        assertEquals(listOf("command", "stdin", "cwd", "timeoutMs", "failOnNonZero"), s.params.map { it.key })
        assertTrue(s.param("command")!!.required); assertEquals(ParamKind.MULTILINE, s.param("command")!!.kind)
        assertFalse(s.param("cwd")!!.templated)                       // a path is never a template target
        assertTrue(s.param("stdin")!!.templated)                      // item data goes through stdin
        assertEquals(1_000.0, s.param("timeoutMs")!!.min); assertEquals(Shell.MAX_TIMEOUT_MS.toDouble(), s.param("timeoutMs")!!.max)
        assertEquals("30000", s.param("timeoutMs")!!.default.toString())
        assertEquals("true", s.param("failOnNonZero")!!.default.toString())
        assertTrue("node timeout covers the max command timeout", s.timeoutMs >= Shell.MAX_TIMEOUT_MS + 5_000)
        assertTrue(s.param("command")!!.help.contains("sh file.sh"))
        assertFalse(s.description.contains('\n'))
    }

    @Test fun toolDefStrict() {
        val d = s.toolDef()
        assertEquals("app_shell_run", d["name"]!!.jsonPrimitive.content)
        assertEquals("true", d["strict"].toString())
        val schema = d["input_schema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"].toString())
        val props = schema["properties"]!!.jsonObject
        assertEquals(props.keys, schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(props["stdin"]!!.jsonObject.containsKey("anyOf"))      // optional -> nullable
        assertFalse(props["command"]!!.jsonObject.containsKey("anyOf"))   // required
    }

    @Test fun inAppNodes() {
        assertTrue(AppNodes.all.contains(ShellRunNode))
        assertEquals(13, AppNodes.all.size)
    }
}
