package com.mob8n.core

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Persistence impl #2 (DESIGN §3.7): plain maps + an event trail so tests can assert write ORDER. */
class InMemoryPersistence : Persistence {
    val workflows = HashMap<String, Workflow>()
    val runs = LinkedHashMap<String, RunRecord>()
    val logs = ArrayList<NodeLog>()
    val suspended = HashMap<String, SuspendedRun>()
    val vars = HashMap<String, JsonElement>()
    val state = HashMap<Pair<String, String>, Pair<JsonElement, Long?>>()
    val secrets = HashMap<String, String>()
    val playlist = ArrayList<PlaylistEntry>()
    val notes = ArrayList<Note>()
    val events = ArrayList<String>()
    var failSuspended = false
    var now: () -> Long = { 0L }

    override suspend fun loadWorkflow(id: String) = workflows[id]
    override suspend fun enabledWorkflows() = workflows.values.filter { it.enabled }
    override suspend fun saveRun(run: RunRecord) {
        runs[run.runId] = run; events += "run:${run.status}"
        workflows[run.workflowId]?.let { workflows[run.workflowId] = it.copy(lastRunStatus = run.status, lastRunAt = run.endedAt ?: run.startedAt) }
    }
    override suspend fun loadRun(runId: String) = runs[runId]
    override suspend fun saveNodeLog(log: NodeLog) { logs += log; events += "log:${log.nodeName}:${log.status}" }
    override suspend fun saveSuspended(s: SuspendedRun) { if (failSuspended) throw IllegalStateException("disk full"); suspended[s.runId] = s; events += "suspended" }
    override suspend fun loadSuspended(runId: String) = suspended[runId]
    override suspend fun deleteSuspended(runId: String) { suspended.remove(runId) }
    override suspend fun getVariable(key: String) = vars[key]
    override suspend fun setVariable(key: String, value: JsonElement?) { if (value == null) vars.remove(key) else vars[key] = value }
    override suspend fun allVariables(): Map<String, JsonElement> = vars.toMap()
    override suspend fun getState(scope: String, key: String): JsonElement? {
        val (v, exp) = state[scope to key] ?: return null
        if (exp != null && exp < now()) { state.remove(scope to key); return null }
        return v
    }
    override suspend fun putState(scope: String, key: String, value: JsonElement?, ttlMs: Long?) {
        if (value == null) state.remove(scope to key) else state[scope to key] = value to ttlMs?.let { now() + it }
    }
    override fun getSecret(name: String) = secrets[name]
    override fun allSecretValues(): Collection<String> = secrets.values
    override suspend fun addPlaylistEntry(e: PlaylistEntry): Boolean {
        if (playlist.any { it.playlist == e.playlist && it.title == e.title && it.artist == e.artist }) return false
        playlist += e.copy(id = playlist.size + 1L); return true
    }
    override suspend fun addNote(n: Note): Long { notes += n.copy(id = notes.size + 1L); return notes.size.toLong() }

    fun logsOf(runId: String) = logs.filter { it.runId == runId }
}

// ---------------------------------------------------------------- fake nodes (pure Kotlin; ids use the reserved `test.` lane)

object ManualTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(TRIGGER_MANUAL, "Manual", NodeKind.TRIGGER, "Run button", inputs = emptyList())
}
object CalledTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(TRIGGER_CALLED, "Called", NodeKind.TRIGGER, "Sub-workflow entry", inputs = emptyList())
}
/** Trigger with accepts()/toItems() so TriggerHub-style fan-out can be exercised through TriggerNode.matchInstances. */
object EventTrigger : TriggerNode() {
    override val hosting = Hosting.MANIFEST
    override val spec = NodeSpec("trigger.event", "Event", NodeKind.TRIGGER, "Test event", params = listOf(text("match", "Match")), inputs = emptyList())
    override fun accepts(params: JsonObject, event: JsonObject) = params.str("match").let { it == null || it == event.str("kind") }
    override fun toItems(params: JsonObject, event: JsonObject): Items = if (event.bool("drop") == true) emptyList() else listOf(event)
}

/** PER_ITEM identity + {passed:true}; counts executions for "not re-executed after resume" assertions. */
object PassNode : Node() {
    var executions = 0
    override val spec = NodeSpec("test.pass", "Pass", NodeKind.LOGIC, "adds passed=true", agentTool = true)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult { executions++; return out(input.item.add("passed" to true)) }
}
/** PER_ITEM: item + {field: rendered TEXT value} (templates + $node + $vars) — the same ctx.str path real nodes use. */
object SetNode : Node() {
    override val spec = NodeSpec("test.set", "Set", NodeKind.LOGIC, "sets a field", params = listOf(text("field", "Field", required = true), text("value", "Value")), agentTool = true)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput) = out(input.item.add(ctx.req("field") to ctx.str("value")))
}
/** PER_ITEM router on a boolean field -> true/false ports. */
object IfNode : Node() {
    override val spec = NodeSpec("test.if", "If", NodeKind.LOGIC, "routes on a boolean field", params = listOf(text("field", "Field", required = true)), outputs = listOf(PORT_TRUE, PORT_FALSE))
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput) = route(if (input.item.bool(ctx.req("field")) == true) PORT_TRUE else PORT_FALSE, input.item)
}
/** PER_ITEM: throws for items with bad=true (or every item when `always`). */
object FailNode : Node() {
    override val spec = NodeSpec("test.fail", "Fail", NodeKind.LOGIC, "throws", params = listOf(bool("always", "Always", true)))
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        if (ctx.bool("always") || input.item.bool("bad") == true) throw NodeException("boom")
        return out(input.item)
    }
}
object SlowNode : Node() {
    override val spec = NodeSpec("test.slow", "Slow", NodeKind.LOGIC, "sleeps 10 s", timeoutMs = 50)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult { delay(10_000); return out(input.item) }
}
/** PER_ITEM: throws with a stored secret value embedded in the message (an HTTP error echoing a key in the URL). */
object LeakNode : Node() {
    const val SECRET = "sk-live-abcdef123456"
    override val spec = NodeSpec("test.leak", "Leak", NodeKind.LOGIC, "throws a message containing a secret")
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = throw NodeException("HTTP 403 from https://x/?k=$SECRET: denied $SECRET")
}
/** LIST with two input ports. */
object MergeNode : Node() {
    override val spec = NodeSpec("test.merge", "Merge", NodeKind.LOGIC, "append or combine_by_position",
        params = listOf(choice("mode", "Mode", listOf("append", "combine_by_position"))), inputs = listOf(PORT_A, PORT_B), mode = ExecMode.LIST)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = input.byPort[PORT_A].orEmpty(); val b = input.byPort[PORT_B].orEmpty()
        return if (ctx.str("mode") == "append") out(a + b) else out(a.indices.map { i -> a[i].addAll(b.getOrElse(i) { EMPTY }) })
    }
}
/** LIST node that always suspends for approval; resume: approve -> main (+token from payload), anything else -> denied port. */
object ApprovalNode : Node() {
    override val spec = NodeSpec("test.approval", "Approval", NodeKind.LOGIC, "suspends", outputs = listOf(MAIN, "denied"), mode = ExecMode.LIST)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult =
        NodeResult.Suspend(SuspendKind.APPROVAL, "ok?", title = "Approve ${input.items.size}?", payload = item("token" to "abc"))
    override suspend fun resume(ctx: ExecutionContext, input: NodeInput, decision: String, payload: JsonObject): NodeResult =
        if (decision == DECISION_APPROVE) out(input.items.map { it.add("token" to payload.str("token")) }) else route("denied", input.items)
}
/** PER_ITEM node that suspends only for items with wait=true; other items pass with {seen:true}. Default Node.resume. */
object WaitItemNode : Node() {
    var executions = 0
    override val spec = NodeSpec("test.wait_item", "WaitItem", NodeKind.LOGIC, "suspends per item")
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        executions++
        return if (input.item.bool("wait") == true) NodeResult.Suspend(SuspendKind.APPROVAL, "wait", payload = item("idx" to ctx.itemIndex)) else out(input.item.add("seen" to true))
    }
}
/** LIST: synchronous sub-workflow call. */
object SubNode : Node() {
    override val spec = NodeSpec("test.sub", "Sub", NodeKind.LOGIC, "runs another workflow", params = listOf(text("workflow", "Workflow", required = true, templated = false)), mode = ExecMode.LIST)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput) = out(ctx.runWorkflow(ctx.str("workflow"), input.items))
}
/** LIST: fire-and-forget sub-workflow through Hooks.fireWorkflow with depth + 1 (logic.run_workflow waitForResult=false shape). */
object FireNode : Node() {
    override val spec = NodeSpec("test.fire", "Fire", NodeKind.LOGIC, "fires another workflow async", params = listOf(text("workflow", "Workflow", required = true, templated = false)), mode = ExecMode.LIST)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult { ctx.hooks.fireWorkflow(ctx.str("workflow"), input.items, ctx.depth + 1); return out(input.items) }
}
/** LIST: Agent-style tool call through ctx.runNode. */
object ToolNode : Node() {
    override val spec = NodeSpec("test.tool", "Tool", NodeKind.AI, "calls a node as a tool", params = listOf(text("tool", "Tool", required = true, templated = false), text("paramsJson", "Params", templated = false)), mode = ExecMode.LIST)
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val p = ctx.raw("paramsJson")?.asText()?.takeIf { it.isNotBlank() }?.let { JSON.parseToJsonElement(it) as JsonObject } ?: EMPTY
        return out(ctx.runNode(ctx.str("tool"), p, input.item))
    }
}

object Fakes {
    val all: List<Node> = listOf(ManualTrigger, CalledTrigger, EventTrigger, PassNode, SetNode, IfNode, FailNode, SlowNode, LeakNode, MergeNode, ApprovalNode, WaitItemNode, SubNode, FireNode, ToolNode)
    val catalog = Catalog(listOf(all))

    fun node(id: String, type: String, name: String = id, vararg params: Pair<String, Any?>) = NodeInstance(id, type, name, item(*params))
    fun edge(from: String, to: String, fromPort: String = MAIN, toPort: String = MAIN) = Edge(from, fromPort, to, toPort)
    fun wf(id: String, nodes: List<NodeInstance>, edges: List<Edge>, enabled: Boolean = true) = Workflow(id, "WF $id", enabled, Graph(nodes, edges))

    /** Manual trigger `t` -> the given chain of node ids in order. */
    fun linear(id: String, vararg chain: NodeInstance): Workflow {
        val t = node("t", TRIGGER_MANUAL, "Trigger")
        val nodes = listOf(t) + chain
        return wf(id, nodes, nodes.zipWithNext { a, b -> edge(a.id, b.id) })
    }

    fun executor(p: InMemoryPersistence, hooks: Hooks = Hooks(), clock: () -> Long = { 1_000L }) =
        Executor(Fakes.catalog, p, hooks, android = null, nowMs = clock, logger = {})

    fun j(s: String): JsonElement = JsonPrimitive(s)
}
