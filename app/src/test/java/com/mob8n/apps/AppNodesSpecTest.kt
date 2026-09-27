package com.mob8n.apps

import com.mob8n.core.ExecMode
import com.mob8n.core.Gate
import com.mob8n.core.MAIN
import com.mob8n.core.NodeKind
import com.mob8n.core.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNodesSpecTest {
    private val specs = AppNodes.all.map { it.spec }

    @Test fun thirteenAppIds() {
        assertEquals(13, specs.size)
        assertEquals(13, specs.map { it.id }.toSet().size)
        assertEquals(listOf("app.capabilities", "app.recipes", "app.action", "app.launch_wait", "app.ui_read", "app.ui_tap", "app.ui_long_press", "app.ui_type",
            "app.ui_scroll", "app.ui_wait_for", "app.ui_global", "app.ui_screenshot", "app.shell_run"), specs.map { it.id })
        for (s in specs) {
            assertTrue(s.id, s.id.matches(Regex("app\\.[a-z][a-z0-9_]*")))
            assertTrue("${s.id} kind", s.kind == NodeKind.DATA || s.kind == NodeKind.ACTION)
            s.toolDef()
            assertTrue("${s.id} description one line", !s.description.contains('\n'))
        }
    }

    @Test fun kindsGatesOptionalAgentToolExactlyPerDesign() {
        val a11y = listOf<Gate>(Gate.Accessibility)
        val none = emptyList<Gate>()
        val expected = mapOf(              // id -> (kind, mode, gates, optional, agentTool, timeoutMs)  (DESIGN3P §5.4)
            "app.capabilities" to listOf(NodeKind.DATA, ExecMode.LIST, none, false, true, 30_000L),
            "app.recipes" to listOf(NodeKind.DATA, ExecMode.LIST, none, false, true, 10_000L),
            "app.action" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, listOf(Gate.Overlay, Gate.Advisory(Gate.PostNotifications)), false, true, 30_000L),
            "app.launch_wait" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, a11y, true, true, 35_000L),
            "app.ui_read" to listOf(NodeKind.DATA, ExecMode.PER_ITEM, a11y, true, true, 15_000L),
            "app.ui_tap" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, a11y, true, true, 15_000L),
            "app.ui_long_press" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, a11y, true, false, 15_000L),
            "app.ui_type" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, a11y, true, true, 15_000L),
            "app.ui_scroll" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, a11y, true, true, 30_000L),
            "app.ui_wait_for" to listOf(NodeKind.DATA, ExecMode.PER_ITEM, a11y, true, true, 70_000L),
            "app.ui_global" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, a11y, true, true, 10_000L),
            "app.ui_screenshot" to listOf(NodeKind.DATA, ExecMode.PER_ITEM, a11y, true, true, 15_000L),
            "app.shell_run" to listOf(NodeKind.ACTION, ExecMode.PER_ITEM, none, false, true, 130_000L),   // v4 DESIGN4 §7.1
        )
        for (s in specs) {
            val e = expected[s.id]!!
            assertEquals("${s.id} kind", e[0], s.kind); assertEquals("${s.id} mode", e[1], s.mode)
            assertEquals("${s.id} gates", e[2], s.gates)
            assertEquals("${s.id} optional", e[3], s.optional); assertEquals("${s.id} agentTool", e[4], s.agentTool); assertEquals("${s.id} timeout", e[5], s.timeoutMs)
        }
        assertFalse(UiLongPressNode.spec.agentTool)
    }

    @Test fun portsAndParams() {
        assertEquals(listOf(MAIN, "timeout"), UiWaitForNode.spec.outputs)
        for (s in specs - UiWaitForNode.spec) assertEquals(s.id, listOf(MAIN), s.outputs)
        assertEquals(ParamKind.SECRET, UiTypeNode.spec.param("secret")!!.kind)
        assertTrue(UiTypeNode.spec.param("text")!!.required)
        assertTrue(UiTypeNode.spec.toolDef()["input_schema"].toString().let { !it.contains("\"secret\"") })   // SECRET never a tool param
        assertEquals(listOf("down", "up", "left", "right"), UiScrollNode.spec.param("direction")!!.options)
        assertEquals(listOf("back", "home", "recents", "notifications", "quick_settings"), UiGlobalNode.spec.param("action")!!.options)
        assertEquals(ParamKind.APP, LaunchWaitNode.spec.param("packageName")!!.kind)
        assertEquals(ParamKind.APP, RecipesNode.spec.param("packageName")!!.kind)
        assertEquals(16, Capabilities.PROBES.size); assertEquals(16, Capabilities.PROBES.map { it.first }.toSet().size)
        for (k in listOf("text", "contentDescription", "viewId", "className", "index", "nth", "x", "y", "exact", "waitMs")) assertTrue("tap has $k", UiTapNode.spec.param(k) != null)
        assertEquals(1000.0, LaunchWaitNode.spec.param("timeoutMs")!!.min); assertEquals(30000.0, LaunchWaitNode.spec.param("timeoutMs")!!.max)
        assertEquals(500.0, UiWaitForNode.spec.param("timeoutMs")!!.min); assertEquals(60000.0, UiWaitForNode.spec.param("timeoutMs")!!.max)
        assertEquals(ParamKind.BOOL, UiWaitForNode.spec.param("exact")!!.kind)   // v3 carry-over: exact text/contentDescription match
        assertEquals("false", UiWaitForNode.spec.param("exact")!!.default.toString())
    }
}
