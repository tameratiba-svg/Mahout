package com.mob8n.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import java.util.UUID

class Executor(
    val catalog: Catalog,
    val persistence: Persistence,
    val hooks: Hooks = Hooks(),
    val android: Context? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val nowMs: () -> Long = { System.currentTimeMillis() },
    val logger: (String) -> Unit = {},
    val maxDepth: Int = 3,
    val snapshotItems: Int = 50,
    val approvalTtlMs: Long = 24 * 3600_000L,
) {
    data class Outcome(val run: RunRecord, val leafItems: Items)
    private data class Resume(val nodeId: String, val decision: String, val payload: JsonObject)

    // Fan-out (raw event -> matching instances) is TriggerNode.matchInstances (Nodes.kt); TriggerHub starts each match under its own guard.

    /** Start ONE workflow at a given trigger node with items (manual, tile, shortcut, share chooser, sub-workflow). */
    suspend fun start(wf: Workflow, trigger: NodeInstance, items: Items, parentRunId: String?, depth: Int): Outcome {
        val spec = catalog.require(trigger.type).spec
        if (depth > maxDepth) {   // F7: async run_workflow chains (A->B->A) carry depth through Hooks.fireWorkflow; runSub checks before calling
            val now = nowMs()
            val failed = RunRecord(UUID.randomUUID().toString(), wf.id, wf.name, trigger.type, RunStatus.FAILED, now, now, trigger.id, "Sub-workflow depth limit $maxDepth reached", parentRunId)
            persistence.saveRun(failed)
            return Outcome(failed, emptyList())
        }
        val run = RunRecord(UUID.randomUUID().toString(), wf.id, wf.name, trigger.type, RunStatus.RUNNING, nowMs(), parentRunId = parentRunId)
        persistence.saveRun(run)                                   // run row exists BEFORE any node executes
        val ports: Ports = mapOf(MAIN to items)
        var state = EngineState(outputs = mapOf(trigger.name to JsonArray(items)), done = listOf(trigger.id))
        state = deliver(wf.graph, trigger.id, ports, state)
        saveLog(run, 0, trigger, spec, emptyList(), ports, NodeStatus.SUCCESS, null, 0)
        return drive(run, wf, state.copy(seq = 1), null, depth)
    }

    /** Resume a suspended run with a decision (button id, DECISION_TIMER or DECISION_TIMEOUT). */
    suspend fun resume(runId: String, decision: String): Outcome? {
        val s = persistence.loadSuspended(runId) ?: return null
        persistence.deleteSuspended(runId)
        val wf = persistence.loadWorkflow(s.workflowId) ?: run {
            // workflow deleted while waiting: close the run row instead of leaving it SUSPENDED forever
            persistence.loadRun(runId)?.let { persistence.saveRun(it.copy(status = RunStatus.FAILED, endedAt = nowMs(), failedNodeId = s.nodeId, error = "workflow deleted")) }
            return null
        }
        val run = (persistence.loadRun(runId) ?: RunRecord(runId, wf.id, wf.name, "resume", RunStatus.RUNNING, nowMs()))
            .copy(status = RunStatus.RUNNING, endedAt = null, failedNodeId = null, error = null)
        persistence.saveRun(run)
        return drive(run, wf, s.state, Resume(s.nodeId, decision, s.payload), s.depth)
    }

    private suspend fun drive(run: RunRecord, wf: Workflow, state0: EngineState, resume0: Resume?, depth: Int): Outcome {
        var state = state0
        var resume = resume0
        val g = wf.graph
        val order = g.topoOrder() ?: return finish(run, wf, state, RunStatus.FAILED, null, "workflow has a cycle")
        val vars = try { persistence.allVariables() } catch (e: Exception) { emptyMap() }
        val secrets = try { persistence.allSecretValues() } catch (e: Exception) { emptyList() }

        for (id in order) {
            if (id in state.done) continue
            val inst = g.node(id) ?: continue
            val node = catalog.node(inst.type)
            if (node == null) {
                saveLog(run, state.seq, inst, null, emptyList(), emptyMap(), NodeStatus.FAILED, "unknown node type ${inst.type}", 0)
                return finish(run, wf, state, RunStatus.FAILED, id, "unknown node type ${inst.type}")
            }
            val spec = node.spec
            if (spec.kind == NodeKind.TRIGGER) { state = state.copy(done = state.done + id); continue }

            val byPort: Map<String, Items> = spec.inputs.associateWith { port ->
                g.incoming(id).filter { it.toPort == port }.flatMap { e -> state.edgeItems[e.key]?.mapNotNull { it as? JsonObject } ?: emptyList() }
            }
            val all = byPort.values.flatten()
            if (all.isEmpty() || inst.disabled) {
                saveLog(run, state.seq, inst, spec, emptyList(), emptyMap(), NodeStatus.SKIPPED, null, 0)
                state = state.copy(done = state.done + id, seq = state.seq + 1); continue
            }

            val upstream: Map<String, Items> = state.outputs.mapValues { (_, v) -> v.mapNotNull { it as? JsonObject } }
            val startedAt = nowMs()
            val timeout = inst.timeoutMs ?: spec.timeoutMs
            val batches: List<NodeInput> = if (spec.mode == ExecMode.LIST) listOf(NodeInput(all, byPort)) else all.map { NodeInput(listOf(it)) }
            val ports = LinkedHashMap<String, MutableList<Item>>()
            val resuming = resume?.nodeId == id
            var startIdx = 0
            if (resuming) {
                startIdx = state.pendingIndex.coerceIn(0, maxOf(0, batches.size - 1))
                state.pendingPorts.forEach { (p, arr) -> ports[p] = arr.mapNotNull { it as? JsonObject }.toMutableList() }
            }
            val gateMissing: Gate? = android?.let { a -> spec.gates.firstOrNull { it.enforced && !it.granted(a) } }
            var status = NodeStatus.SUCCESS
            var suspended: NodeResult.Suspend? = null
            var suspendedAt = 0

            for (i in startIdx until batches.size) {
                val input = batches[i]
                val ctx = ctx(run.runId, wf, inst, spec, input, i, all.size, upstream, vars, depth)
                val result: NodeResult = try {
                    if (gateMissing != null) throw NodeException("Needs ${gateMissing.label}")
                    val r = resume
                    withTimeout(timeout) {
                        if (resuming && i == startIdx && r != null) node.resume(ctx, input, r.decision, r.payload) else node.execute(ctx, input)
                    }
                } catch (e: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive) throw e   // outer run ceiling (TriggerHub.guarded) cancelled us, not this node's timeout
                    status = NodeStatus.TIMEOUT
                    NodeResult.Out(mapOf(ERROR to listOf(ctx.errorItem(NodeException("Timed out after $timeout ms")))))
                } catch (e: CancellationException) { throw e
                } catch (e: VirtualMachineError) { throw e
                } catch (e: Throwable) {
                    if (status != NodeStatus.TIMEOUT) status = NodeStatus.FAILED
                    logger("node ${inst.name} failed: ${Redaction.redactText(e.message ?: "", secrets)}")
                    NodeResult.Out(mapOf(ERROR to listOf(ctx.errorItem(e))))
                }
                when (result) {
                    is NodeResult.Suspend -> { suspended = result; suspendedAt = i; break }
                    is NodeResult.Out -> result.ports.forEach { (p, items) -> ports.getOrPut(p) { ArrayList() } += items }
                }
            }
            if (resuming) resume = null
            val dur = nowMs() - startedAt

            val susp = suspended
            if (susp != null) {
                val pending = state.copy(pendingIndex = suspendedAt, pendingPorts = ports.mapValues { JsonArray(it.value) })
                val expires = susp.resumeAtMs ?: (nowMs() + approvalTtlMs)
                val sr = SuspendedRun(run.runId, wf.id, id, susp.kind, susp.reason, susp.title, susp.text, susp.choices, susp.resumeAtMs, susp.payload, pending, depth, nowMs(), expires)
                try { persistence.saveSuspended(sr) } catch (e: Exception) {
                    saveLog(run, state.seq, inst, spec, all, emptyMap(), NodeStatus.FAILED, "could not persist suspended run: ${e.message}", dur)
                    return finish(run, wf, state, RunStatus.FAILED, id, "could not persist suspended run")
                }
                saveLog(run, state.seq, inst, spec, all, emptyMap(), NodeStatus.SUSPENDED, susp.reason, dur)
                val out = finish(run, wf, state, RunStatus.SUSPENDED, id, null)
                try { hooks.onSuspend(sr) } catch (e: Exception) { logger("onSuspend: ${e.message}") }
                return out
            }

            val errors = ports.remove(ERROR).orEmpty()
            var err: String? = null
            if (errors.isNotEmpty()) {
                err = errors.first()["error"].asText()
                if (g.hasErrorEdge(id)) {
                    status = NodeStatus.ERROR_ROUTED
                    ports[ERROR] = errors.toMutableList()
                } else {
                    saveLog(run, state.seq, inst, spec, all, ports, if (status == NodeStatus.TIMEOUT) status else NodeStatus.FAILED, err, dur)
                    return finish(run, wf, state, RunStatus.FAILED, id, err)
                }
            }
            saveLog(run, state.seq, inst, spec, all, ports, status, err, dur)
            state = deliver(g, id, ports, state).copy(
                done = state.done + id, seq = state.seq + 1,
                outputs = state.outputs + (inst.name to JsonArray(ports[MAIN].orEmpty())),
                pendingIndex = 0, pendingPorts = emptyMap(),
            )
        }
        // the suspended node is gone (graph edited while waiting): the decision had nowhere to land, so do not report SUCCESS
        resume?.let { return finish(run, wf, state, RunStatus.FAILED, it.nodeId, "workflow changed while waiting") }
        return finish(run, wf, state, RunStatus.SUCCESS, null, null)
    }

    /** Run a node in isolation (Agent tool call). */
    suspend fun runNode(runId: String, wf: Workflow, specId: String, params: JsonObject, item: Item, upstream: Map<String, Items>, vars: Map<String, JsonElement>, depth: Int): Items {
        val node = catalog.node(specId) ?: throw NodeException("Unknown node $specId")
        val spec = node.spec
        if (spec.kind == NodeKind.TRIGGER) throw NodeException("Triggers cannot be used as tools")
        spec.validate(params).firstOrNull()?.let { throw NodeException(it) }
        android?.let { a -> spec.gates.firstOrNull { it.enforced && !it.granted(a) }?.let { throw NodeException("Needs ${it.label}") } }
        val inst = NodeInstance(id = "tool-" + UUID.randomUUID(), type = specId, name = spec.name, params = params)
        val input = NodeInput(listOf(item))
        val ctx = ctx(runId, wf, inst, spec, input, 0, 1, upstream, vars, depth)
        return when (val r = withTimeout(spec.timeoutMs) { node.execute(ctx, input) }) {
            is NodeResult.Suspend -> throw NodeException("${spec.name} cannot suspend inside a tool call")
            is NodeResult.Out -> {
                r.ports[ERROR]?.firstOrNull()?.let { throw NodeException(it["error"].asText()) }
                r.ports[MAIN].orEmpty()
            }
        }
    }

    private fun deliver(g: Graph, fromId: String, ports: Map<String, List<Item>>, state: EngineState): EngineState {
        val m = state.edgeItems.toMutableMap()
        for ((port, items) in ports) for (e in g.outgoing(fromId, port)) m[e.key] = JsonArray((m[e.key] ?: JsonArray(emptyList())) + items)
        return state.copy(edgeItems = m)
    }

    private suspend fun finish(run: RunRecord, wf: Workflow, state: EngineState, status: RunStatus, failedNode: String?, error: String?): Outcome {
        val secrets = try { persistence.allSecretValues() } catch (e: Exception) { emptyList() }
        val r = run.copy(status = status, endedAt = if (status == RunStatus.SUSPENDED) null else nowMs(), failedNodeId = failedNode, error = error?.let { Redaction.redactText(it, secrets) })
        try { persistence.saveRun(r) } catch (e: Exception) { logger("saveRun: ${e.message}") }
        val leaves = wf.graph.leaves().flatMap { n -> state.outputs[n.name]?.mapNotNull { it as? JsonObject } ?: emptyList() }
        return Outcome(r, leaves)
    }

    private suspend fun saveLog(run: RunRecord, seq: Int, inst: NodeInstance, spec: NodeSpec?, input: Items, ports: Map<String, List<Item>>, st: NodeStatus, err: String?, dur: Long) {
        try {
            val secrets = persistence.allSecretValues()
            persistence.saveNodeLog(NodeLog(
                run.runId, seq, inst.id, inst.name, spec?.id ?: inst.type, st,
                Redaction.redact(JsonArray(input.take(snapshotItems)), secrets).jsonArray,
                Redaction.redact(JsonObject(ports.mapValues { JsonArray(it.value.take(snapshotItems)) }), secrets).jsonObject,
                err?.let { Redaction.redactText(it, secrets) }, nowMs(), dur,
            ))
        } catch (e: Exception) { logger("saveNodeLog ${inst.name}: ${e.message}") }   // a log write never fails a run
    }

    private fun ctx(runId: String, wf: Workflow, inst: NodeInstance, spec: NodeSpec, input: NodeInput, idx: Int, count: Int, upstream: Map<String, Items>, vars: Map<String, JsonElement>, depth: Int) =
        ExecutionContext(runId, wf, inst, spec, input, idx, upstream, vars, persistence, catalog, hooks, android, zone, nowMs, logger,
            runWorkflow = { calleeId, items -> runSub(runId, calleeId, items, depth) },
            runNode = { specId, params, item -> runNode(runId, wf, specId, params, item, upstream, vars, depth) },
            itemCount = count, depth = depth)

    private suspend fun runSub(parentRunId: String, calleeId: String, items: Items, depth: Int): Items {
        if (depth >= maxDepth) throw NodeException("Sub-workflow depth limit $maxDepth reached")
        val callee = persistence.loadWorkflow(calleeId) ?: throw NodeException("Workflow $calleeId not found")
        val trig = callee.graph.nodes.firstOrNull { it.type == TRIGGER_CALLED && !it.disabled } ?: throw NodeException("${callee.name} has no 'Called by Workflow' trigger")
        val out = start(callee, trig, items, parentRunId, depth + 1)
        if (out.run.status == RunStatus.FAILED) throw NodeException("${callee.name} failed: ${out.run.error}")
        if (out.run.status == RunStatus.SUSPENDED) throw NodeException("${callee.name} suspended; sub-workflows cannot wait for approval")
        return out.leafItems
    }
}
