package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4 §5.3 / DESIGN4P P2 classification table, row by row, plus the invariant over the REAL six-lane catalog: no ACTION node can ever be READ. */
class RiskTest {
    private val catalog = Catalog(listOf(
        com.mob8n.triggers.TriggerNodes.all, com.mob8n.data.DataNodes.all, com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all, com.mob8n.ai.AiNodes.all, com.mob8n.apps.AppNodes.all,
    ))
    private fun op(name: String) = OperatorTools.riskOf(name, "operator", false, null)
    private fun node(spec: NodeSpec) = OperatorTools.riskOf(spec.toolName, "node", false, spec.id, isAction = spec.kind == NodeKind.ACTION)

    @Test fun operatorTableRowByRow() {
        for (n in listOf("list_workflows", "get_workflow", "describe_node", "draft_workflow", "list_runs", "get_run", "workspace_list", "workspace_read", "skill_list", "load_skill")) assertEquals(n, Risk.READ, op(n))
        for (n in listOf("run_workflow", "enable_workflow", "memory_update")) assertEquals(n, Risk.WRITE, op(n))
        for (n in listOf("disable_workflow", "delete_workflow", "save_workflow", "resume_run", "skill_create", "skill_update", "skill_delete", "workspace_delete")) assertEquals(n, Risk.ALWAYS, op(n))
        for (n in listOf("run_shell", "run_js", "workspace_write", "workspace_mkdir", "app_shell_run")) { assertEquals(n, Risk.CODING, op(n)); assertTrue(n in OperatorTools.CODING) }
        assertEquals(Risk.WRITE, OperatorTools.riskOf("workflow__x", "workflow", false, null))
        assertEquals(Risk.ALWAYS, OperatorTools.riskOf("workflow__x", "workflow", false, null, workflowAlways = true))   // P18b
        assertEquals(Risk.READ, OperatorTools.riskOf("mcp__s__t", "mcp", trustedMcp = true, null))
        assertEquals(Risk.ALWAYS, OperatorTools.riskOf("mcp__s__t", "mcp", trustedMcp = false, null))
        assertEquals(Risk.READ, OperatorTools.riskOf("knowledge_search", "knowledge", false, null))
        assertEquals(listOf(Risk.READ, Risk.WRITE, Risk.CODING, Risk.ALWAYS), Risk.entries)                            // the four PLAN-v5 §4 columns
    }

    @Test fun nodeRowsAndTheActionInvariantOverTheRealCatalog() {
        assertEquals(Risk.WRITE, node(catalog.spec("data.http")!!)); assertEquals(Risk.WRITE, node(catalog.spec("data.variable")!!))
        assertEquals(Risk.READ, node(catalog.spec("data.datetime")!!))
        assertEquals(Risk.WRITE, node(catalog.spec("action.notify")!!))
        assertEquals(Risk.WRITE, OperatorTools.riskOf("action_notify", "node", false, "action.notify", isAction = true))   // the ACTION-chip regression: never "read-only"
        assertEquals(Risk.ALWAYS, node(catalog.spec("action.write_file")!!)); assertEquals(Risk.ALWAYS, node(catalog.spec("app.launch_wait")!!))
        assertEquals(Risk.CODING, node(catalog.spec("app.shell_run")!!))                                                 // shell = coding (Auto runs it)
        val actions = catalog.nodes.map { it.spec }.filter { it.kind == NodeKind.ACTION }
        assertTrue(actions.size >= 35)
        for (s in actions) assertTrue("${s.id} must be DESTRUCTIVE, CODING or WRITE", s.id in OperatorTools.DESTRUCTIVE_IDS || node(s) == Risk.WRITE || node(s) == Risk.ALWAYS || node(s) == Risk.CODING)
        for (s in actions) assertFalse("${s.id} is READ", node(s) == Risk.READ)
        for (s in catalog.nodes.map { it.spec }.filter { it.id.startsWith("app.ui_") }) assertEquals(s.id, Risk.ALWAYS, node(s))
        for (id in OperatorTools.DESTRUCTIVE_IDS) assertEquals(id, if (id == OperatorTools.SHELL_RUN_ID) Risk.CODING else Risk.ALWAYS, OperatorTools.riskOf(id.replace('.', '_'), "node", false, id))
        assertTrue("app.shell_run" in OperatorTools.DESTRUCTIVE_IDS)
        for (id in OperatorTools.DESTRUCTIVE_IDS) assertTrue(id, Permissions.isAlwaysNode(id))
    }

    @Test fun riskOfBuiltToolsUsesNeedsApprovalAndCatalogIds() {
        val exec: suspend (NodeSpec, kotlinx.serialization.json.JsonObject) -> List<kotlinx.serialization.json.JsonObject> = { _, _ -> emptyList() }
        val fake = Catalog(listOf(listOf(Fakes.FakeNode(Fakes.http), Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(Fakes.datetime))))
        assertEquals(Risk.WRITE, OperatorTools.riskOf(AgentTool.node(Fakes.notify, exec), fake))
        assertEquals(Risk.WRITE, OperatorTools.riskOf(AgentTool.node(Fakes.http, exec), fake))
        assertEquals(Risk.READ, OperatorTools.riskOf(AgentTool.node(Fakes.datetime, exec), fake))
        val ui = NodeSpec("app.ui_tap", "Tap", NodeKind.ACTION, "Taps", agentTool = true)
        assertEquals(Risk.ALWAYS, OperatorTools.riskOf(AgentTool.node(ui, exec), Catalog(listOf(listOf(Fakes.FakeNode(ui))))))
        assertEquals(Risk.READ, OperatorTools.riskOf(Fakes.mcpTool("mcp__s__t", true, ToolOut("x")), fake))
        assertEquals(Risk.ALWAYS, OperatorTools.riskOf(Fakes.mcpTool("mcp__s__t", false, ToolOut("x")), fake))
        assertEquals(Risk.WRITE, OperatorTools.riskOf(Fakes.opTool("workflow__x", kind = "workflow", needsApproval = true), fake))
        assertEquals(Risk.CODING, OperatorTools.riskOf(Fakes.opTool("run_shell", needsApproval = true), fake))
        // P18b: the exposed workflow's graph decides
        val wf = Fakes.exposedWorkflow("w1", "Danger").let { it.copy(graph = it.graph.copy(nodes = it.graph.nodes + com.mob8n.core.NodeInstance("n", "app.ui_tap", "Tap", com.mob8n.core.item()))) }
        val name = WorkflowTools.toolName(wf.name, wf.id)
        assertEquals(Risk.ALWAYS, OperatorTools.riskOf(Fakes.opTool(name, kind = "workflow", needsApproval = true), fake, listOf(wf)))
        assertEquals(Risk.WRITE, OperatorTools.riskOf(Fakes.opTool(name, kind = "workflow", needsApproval = true), fake, listOf(Fakes.exposedWorkflow("w1", "Danger"))))
    }

    @Test fun gateFollowsTheMode() {
        val tools = mapOf(
            "list_workflows" to Fakes.opTool("list_workflows"), "run_workflow" to Fakes.opTool("run_workflow", needsApproval = true),
            "delete_workflow" to Fakes.opTool("delete_workflow", needsApproval = true), "run_shell" to Fakes.opTool("run_shell", needsApproval = true),
            "resume_run" to Fakes.opTool("resume_run", needsApproval = true),
        )
        val risk: (AgentTool) -> Risk = { OperatorTools.riskOf(it.name, it.kind, false, null) }
        fun asks(m: PermissionMode) = Permissions.gate(tools, m, risk).filterValues { it.needsApproval }.keys
        assertEquals(setOf("run_workflow", "delete_workflow", "run_shell", "resume_run"), asks(PermissionMode.ASK))
        assertEquals(setOf("delete_workflow", "resume_run"), asks(PermissionMode.AUTO))                                   // resume_run asks in Auto
        assertEquals(emptySet<String>(), asks(PermissionMode.BYPASS))                                                      // and runs only in Bypass
        assertEquals(emptySet<String>(), asks(PermissionMode.PLAN))                                                        // blocked tools never ask (they fail)
        assertTrue(Permissions.gate(tools, PermissionMode.ASK, risk)["list_workflows"] === tools["list_workflows"])       // unchanged instance when the flag is unchanged
        assertTrue(Permissions.gate(tools, PermissionMode.BYPASS, risk)["list_workflows"] === tools["list_workflows"])
    }
}
