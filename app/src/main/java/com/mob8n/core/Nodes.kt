package com.mob8n.core

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class NodeKind { TRIGGER, DATA, LOGIC, ACTION, AI }

/** PER_ITEM: execute() once per incoming item (n8n default). LIST: once with the whole list (+ byPort for multi-input nodes). */
enum class ExecMode { PER_ITEM, LIST }

data class NodeSpec(
    val id: String,                          // "lane.snake_case", stable, stored in graphs
    val name: String,                        // palette label
    val kind: NodeKind,
    val description: String,                 // one sentence; also the Agent tool description
    val params: List<ParamSpec> = emptyList(),
    val inputs: List<String> = listOf(MAIN), // triggers: emptyList(); merge: listOf(PORT_A, PORT_B)
    val outputs: List<String> = listOf(MAIN),// static ports; ERROR is implicit on every node
    val mode: ExecMode = ExecMode.PER_ITEM,
    val timeoutMs: Long = 30_000,            // per execute(); NodeInstance.timeoutMs overrides
    val gates: List<Gate> = emptyList(),     // checked by the executor before execute(); UI shows badges
    val optional: Boolean = false,           // device/OEM dependent or limited; palette badge
    val agentTool: Boolean = false,          // exposed to the Agent node (explicit opt-in per node)
) {
    init {
        require(id.matches(Regex("[a-z]+\\.[a-z][a-z0-9_]*"))) { "node id must be lane.snake_case: $id" }
        require(params.map { it.key }.toSet().size == params.size) { "$id: duplicate param keys" }
        require(params.count { it.definesPorts } <= 1) { "$id: at most one LABELS param may define ports" }
        require(ERROR !in outputs && ERROR !in inputs) { "$id: 'error' port is implicit" }
    }
    fun param(key: String): ParamSpec? = params.firstOrNull { it.key == key }

    /** Static ports + label-defined ports for a concrete instance's params. */
    fun outputPorts(params: JsonObject): List<String> {
        val p = this.params.firstOrNull { it.definesPorts } ?: return outputs
        val dyn = ((params[p.key] as? JsonArray) ?: (p.default as? JsonArray))?.mapNotNull { it.asTextOrNull() }?.filter { it.isNotBlank() } ?: emptyList()
        return dyn + outputs.filter { it !in dyn }
    }

    fun validate(params: JsonObject): List<String> = this.params.mapNotNull { it.validate(params[it.key]) }

    /** Claude tool name: ^[a-zA-Z0-9_-]{1,128}$ */
    val toolName: String get() = id.replace('.', '_')

    /** Strict Claude tool definition {name, description, strict:true, input_schema}. SECRET params are excluded. */
    fun toolDef(): JsonObject {
        val ps = params.filter { it.kind != ParamKind.SECRET }
        return buildJsonObject {
            put("name", toolName)
            put("description", "$name: $description")
            put("strict", true)
            put("input_schema", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", JsonObject(ps.associate { it.key to it.strictSchema() }))
                put("required", JsonArray(ps.map { JsonPrimitive(it.key) }))
            })
        }
    }

    /** Tool input -> node params: drop nulls (meaning "use default"), then validate. */
    fun paramsFromToolInput(input: JsonObject): JsonObject {
        val cleaned = JsonObject(input.filterValues { it !is JsonNull }.filterKeys { k -> params.any { it.key == k && it.kind != ParamKind.SECRET } })
        validate(cleaned).firstOrNull()?.let { throw NodeException("Invalid tool input: $it") }
        return cleaned
    }
}

class NodeInput(
    val items: Items,
    /** per input port; single-input nodes see byPort[MAIN] == items */
    val byPort: Map<String, Items> = mapOf(MAIN to items),
) {
    /** PER_ITEM convenience: the one item being processed. */
    val item: Item get() = items.firstOrNull() ?: EMPTY
}

enum class SuspendKind { APPROVAL, TIMER }

sealed class NodeResult {
    /** port -> items. Ports not present emit nothing. */
    data class Out(val ports: Ports) : NodeResult()
    /**
     * Park the run. The executor persists state FIRST, then Hooks.onSuspend posts a notification (APPROVAL: one button per
     * choice) or enqueues a timer (TIMER: resumeAtMs). Later the executor calls node.resume(ctx, input, decision, payload).
     */
    data class Suspend(
        val kind: SuspendKind,
        val reason: String,
        val title: String = reason,
        val text: String = "",
        val choices: List<String> = listOf(DECISION_APPROVE, DECISION_DENY),
        val resumeAtMs: Long? = null,          // TIMER: when to resume; APPROVAL: expiry (default now + 24h)
        val payload: JsonObject = EMPTY,       // node-private state (e.g. Agent transcript) handed back on resume
    ) : NodeResult()
}
val NONE: NodeResult = NodeResult.Out(emptyMap())
fun out(items: Items): NodeResult = NodeResult.Out(mapOf(MAIN to items))
fun out(item: Item): NodeResult = NodeResult.Out(mapOf(MAIN to listOf(item)))
fun route(port: String, item: Item): NodeResult = NodeResult.Out(mapOf(port to listOf(item)))
fun route(port: String, items: Items): NodeResult = NodeResult.Out(mapOf(port to items))
fun ports(vararg pairs: Pair<String, Items>): NodeResult = NodeResult.Out(pairs.toMap())

/**
 * ONE node == ONE `object X : Node()` appended to its lane's `all` list. Nothing else.
 * Throw NodeException (or anything) for failures: the executor routes makeErrorItem(...) to ERROR or fails the run.
 */
abstract class Node {
    abstract val spec: NodeSpec
    abstract suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult
    /**
     * Called after a Suspend with the user's decision (a button id) or DECISION_TIMER / DECISION_TIMEOUT.
     * Default: route to the port named like the decision if it exists; approve/timer -> MAIN; anything else -> error.
     */
    open suspend fun resume(ctx: ExecutionContext, input: NodeInput, decision: String, payload: JsonObject): NodeResult = when {
        decision in spec.outputPorts(ctx.instance.params) -> NodeResult.Out(mapOf(decision to input.items))
        decision == DECISION_APPROVE || decision == DECISION_TIMER -> out(input.items)
        decision == DECISION_TIMEOUT -> throw NodeException("Timed out waiting for approval")
        else -> throw NodeException("Denied by user")
    }
}

/** Which process component delivers this trigger's raw events (drives UI badges + TriggerHub registration). */
enum class Hosting {
    COMPONENT,        // Activity/TileService/UI/engine calls host.fire directly
    MANIFEST,         // manifest-registered BroadcastReceiver (exempt broadcasts only)
    RUNTIME_RECEIVER, // Context.registerReceiver inside a live host; attach() registers it
    LISTENER,         // NotificationListenerService callbacks
    WORK_MANAGER,     // schedule()/unschedule() enqueue WorkManager / AlarmManager work
    HOST_ATTACHED,    // attach() starts a sensor/observer/socket/callback while a host lives
}

data class TriggerInstance(val workflowId: String, val nodeId: String, val params: JsonObject)

/** What the engine gives Android components and trigger nodes. Concrete, built by TriggerHub. */
class TriggerHost(
    val android: Context?,
    /** Deliver a raw event; the hub matches enabled workflows, runs accepts()/toItems(), starts runs. Thread-safe, non-blocking. */
    val fire: (specId: String, event: JsonObject) -> Unit,
    /** Start ONE workflow from a specific trigger node, bypassing accepts() (tile, shortcut, notification action, share chooser). Workers await the Deferred to hold the process (F16). */
    val fireWorkflow: (workflowId: String, nodeId: String, items: Items) -> Deferred<*>,
    /** Enabled instances of a trigger spec (params for attach()/schedule()). */
    val instancesOf: (specId: String) -> List<TriggerInstance>,
)

abstract class TriggerNode : Node() {
    abstract val hosting: Hosting
    /** Filter a raw event against one instance's params (package regex, SSID, threshold...). */
    open fun accepts(params: JsonObject, event: JsonObject): Boolean = true
    /** Shape the raw event into the items emitted on MAIN. Empty list == no run. */
    open fun toItems(params: JsonObject, event: JsonObject): Items = listOf(event)
    /** RUNTIME_RECEIVER / HOST_ATTACHED: start listening for ALL given instances; return a closer. Called on the host's main thread. */
    open fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? = null
    /** WORK_MANAGER: (re)enqueue durable work for one instance; idempotent (unique name "trig:${workflowId}:${nodeId}"). */
    open fun schedule(host: TriggerHost, instance: TriggerInstance) {}
    open fun unschedule(host: TriggerHost, instance: TriggerInstance) {}
    /** Idempotent re-arm from boot/start/housekeeping (F15): must never cancel the pending work that woke the process. Default = schedule(). */
    open fun rearm(host: TriggerHost, instance: TriggerInstance) = schedule(host, instance)
    /** Triggers are identity nodes: the executor passes toItems() as input. */
    final override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = out(input.items)
}

/**
 * The ONE fan-out matcher (raw event -> every enabled, non-disabled instance of this trigger that accepts it and yields items).
 * Pure: the caller (TriggerHub.fire) starts each run under its own guard. Exceptions in accepts()/toItems() skip that instance.
 */
fun TriggerNode.matchInstances(workflows: List<Workflow>, event: JsonObject, logger: (String) -> Unit = {}): List<Triple<Workflow, NodeInstance, Items>> {
    val matched = ArrayList<Triple<Workflow, NodeInstance, Items>>()
    for (wf in workflows) for (n in wf.graph.nodes) {
        if (n.type != spec.id || n.disabled) continue
        val ok = try { accepts(n.params, event) } catch (e: Exception) { logger("accepts ${wf.name}/${n.name}: ${e.message}"); false }
        if (!ok) continue
        val items = try { toItems(n.params, event) } catch (e: Exception) { logger("toItems ${wf.name}/${n.name}: ${e.message}"); emptyList() }
        if (items.isEmpty()) continue
        matched += Triple(wf, n, items)
    }
    return matched
}

/** Built once in Mob8NApp from the five lane lists. */
class Catalog(lanes: List<List<Node>>) {
    val nodes: List<Node> = lanes.flatten()
    private val byId: Map<String, Node> = nodes.associateBy { it.spec.id }
    init {
        kotlin.require(byId.size == nodes.size) { "duplicate node ids: ${nodes.groupBy { it.spec.id }.filter { it.value.size > 1 }.keys}" }
    }
    fun node(id: String): Node? = byId[id]
    fun require(id: String): Node = byId[id] ?: throw NodeException("Unknown node type $id")
    fun spec(id: String): NodeSpec? = byId[id]?.spec
    val triggers: List<TriggerNode> get() = nodes.filterIsInstance<TriggerNode>()
    fun trigger(id: String): TriggerNode? = byId[id] as? TriggerNode
    fun agentTools(): List<Node> = nodes.filter { it.spec.agentTool && it.spec.kind != NodeKind.TRIGGER }
    fun search(q: String): List<Node> = if (q.isBlank()) nodes else nodes.filter { it.spec.name.contains(q, true) || it.spec.description.contains(q, true) || it.spec.id.contains(q, true) }
}
