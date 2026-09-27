package com.mob8n.logic

import com.mob8n.core.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** Minimal in-memory Persistence for logic tests; state TTL is honoured against the fake clock. */
class InMemoryPersistence(val now: () -> Long) : Persistence {
    val workflows = HashMap<String, Workflow>()
    val runs = HashMap<String, RunRecord>()
    val logs = ArrayList<NodeLog>()
    val suspended = HashMap<String, SuspendedRun>()
    val vars = HashMap<String, JsonElement>()
    private val state = HashMap<String, Pair<JsonElement, Long?>>()   // "scope/key" -> (value, expiresAt)
    val secrets = HashMap<String, String>()

    override suspend fun loadWorkflow(id: String) = workflows[id]
    override suspend fun enabledWorkflows() = workflows.values.filter { it.enabled }
    override suspend fun saveRun(run: RunRecord) { runs[run.runId] = run }
    override suspend fun loadRun(runId: String) = runs[runId]
    override suspend fun saveNodeLog(log: NodeLog) { logs += log }
    override suspend fun saveSuspended(s: SuspendedRun) { suspended[s.runId] = s }
    override suspend fun loadSuspended(runId: String) = suspended[runId]
    override suspend fun deleteSuspended(runId: String) { suspended.remove(runId) }
    override suspend fun getVariable(key: String) = vars[key]
    override suspend fun setVariable(key: String, value: JsonElement?) { if (value == null) vars.remove(key) else vars[key] = value }
    override suspend fun allVariables(): Map<String, JsonElement> = vars.toMap()
    override suspend fun getState(scope: String, key: String): JsonElement? {
        val (v, exp) = state["$scope/$key"] ?: return null
        if (exp != null && now() >= exp) { state.remove("$scope/$key"); return null }
        return v
    }
    override suspend fun putState(scope: String, key: String, value: JsonElement?, ttlMs: Long?) {
        if (value == null) state.remove("$scope/$key") else state["$scope/$key"] = value to ttlMs?.let { now() + it }
    }
    override fun getSecret(name: String) = secrets[name]
    override fun allSecretValues(): Collection<String> = secrets.values
    override suspend fun addPlaylistEntry(e: PlaylistEntry) = true
    override suspend fun addNote(n: Note) = 1L
}

/** Fake ExecutionContext factory + one-call runners. */
class Fake(var nowMs: Long = 1_700_000_000_000L, val zone: ZoneId = ZoneId.of("UTC")) {
    val persistence = InMemoryPersistence { nowMs }
    val catalog = Catalog(listOf(LogicNodes.all))
    val fired = ArrayList<Pair<String, Items>>()
    var subResult: (String, Items) -> Items = { _, items -> items.map { it.add("sub" to true) } }
    val hooks = Hooks(fireWorkflow = { id, items, _ -> fired += id to items })
    val workflow = Workflow("wf-1", "Test WF")

    fun ctx(node: Node, params: JsonObject, input: NodeInput, index: Int = 0, upstream: Map<String, Items> = emptyMap(), instanceId: String = "n1") =
        ExecutionContext(
            runId = "run-1", workflow = workflow,
            instance = NodeInstance(instanceId, node.spec.id, node.spec.name, params),
            spec = node.spec, input = input, itemIndex = index, upstream = upstream, vars = persistence.vars.toMap(),
            persistence = persistence, catalog = catalog, hooks = hooks, android = null, zone = zone, nowMs = { nowMs }, logger = {},
            runWorkflow = { id, items -> subResult(id, items) },
            runNode = { _, _, _ -> throw NodeException("runNode not faked") },
        )

    /** Execute as the executor would: PER_ITEM -> once per item, LIST -> once with byPort. Ports concatenated in order. */
    suspend fun run(node: Node, params: JsonObject, items: Items, byPort: Map<String, Items>? = null, instanceId: String = "n1"): Ports {
        if (node.spec.mode == ExecMode.LIST) {
            val r = node.execute(ctx(node, params, NodeInput(items, byPort ?: mapOf(MAIN to items)), instanceId = instanceId), NodeInput(items, byPort ?: mapOf(MAIN to items)))
            return (r as NodeResult.Out).ports
        }
        val ports = LinkedHashMap<String, MutableList<Item>>()
        items.forEachIndexed { i, it ->
            val input = NodeInput(listOf(it))
            val r = node.execute(ctx(node, params, input, i, instanceId = instanceId), input) as NodeResult.Out
            r.ports.forEach { (p, xs) -> ports.getOrPut(p) { ArrayList() } += xs }
        }
        return ports
    }

    suspend fun one(node: Node, params: JsonObject, item: Item = EMPTY, instanceId: String = "n1"): Item = run(node, params, listOf(item), instanceId = instanceId)[MAIN]!!.single()
    suspend fun port(node: Node, params: JsonObject, item: Item = EMPTY, instanceId: String = "n1"): String = run(node, params, listOf(item), instanceId = instanceId).keys.single()

    suspend fun raw(node: Node, params: JsonObject, items: Items): NodeResult {
        val input = NodeInput(items)
        return node.execute(ctx(node, params, input), input)
    }
}

fun params(vararg pairs: Pair<String, Any?>): JsonObject = item(*pairs)
fun rowsOf(vararg rows: Item) = rows.toList()
