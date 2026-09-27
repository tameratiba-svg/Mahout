package com.mob8n.logic

import com.mob8n.core.*

object NoteNode : Node() {
    override val spec = NodeSpec(
        id = "logic.note", name = "Note", kind = NodeKind.LOGIC,
        description = "Documentation only; passes items through unchanged.",
        params = listOf(multiline("text", "Note", templated = false)),
        mode = ExecMode.LIST,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = out(input.items)
}

object RunWorkflowNode : Node() {
    override val spec = NodeSpec(
        id = "logic.run_workflow", name = "Run Workflow", kind = NodeKind.LOGIC,
        description = "Run another workflow with these items; wait for its result or fire and forget.",
        params = listOf(
            workflowPicker("workflow", "Workflow"),
            bool("waitForResult", "Wait for result", true, help = "off: items pass through unchanged"),
        ),
        mode = ExecMode.LIST,
        timeoutMs = 300_000,   // sub-workflow may itself take a while
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val id = ctx.req("workflow")
        if (id == ctx.workflow.id) throw NodeException("Run Workflow: a workflow cannot call itself")
        if (ctx.bool("waitForResult")) return out(ctx.runWorkflow(id, input.items))
        ctx.hooks.fireWorkflow(id, input.items, ctx.depth + 1)
        return out(input.items)
    }
}

object StopErrorNode : Node() {
    override val spec = NodeSpec(
        id = "logic.stop_error", name = "Stop and Error", kind = NodeKind.LOGIC,
        description = "Fail the run (or route to the error output) with a message.",
        params = listOf(text("message", "Message", "Stopped", required = true)),
        outputs = emptyList(),
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = throw NodeException(ctx.strOrNull("message") ?: "Stopped")
}
