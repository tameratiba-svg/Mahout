package com.mob8n.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val graph: Graph = Graph(),
    val updatedAt: Long = 0L,
    val lastRunStatus: RunStatus? = null,
    val lastRunAt: Long? = null,
)

@Serializable
data class NodeInstance(
    val id: String,                 // unique within the graph (UUID)
    val type: String,               // NodeSpec.id
    val name: String,               // unique within the graph, no '.', used by {{$node.Name.field}}
    val params: JsonObject = EMPTY,
    val x: Float = 0f,
    val y: Float = 0f,
    val disabled: Boolean = false,
    val timeoutMs: Long? = null,    // overrides spec.timeoutMs
)

/** fromPort: MAIN, ERROR, "true", a label...; toPort: MAIN (or PORT_A/PORT_B for merge). */
@Serializable
data class Edge(val from: String, val fromPort: String = MAIN, val to: String, val toPort: String = MAIN) {
    val key: String get() = "$from:$fromPort>$to:$toPort"
}

@Serializable
data class Graph(val nodes: List<NodeInstance> = emptyList(), val edges: List<Edge> = emptyList()) {
    fun node(id: String): NodeInstance? = nodes.firstOrNull { it.id == id }
    fun incoming(nodeId: String): List<Edge> = edges.filter { it.to == nodeId }
    fun outgoing(nodeId: String, port: String): List<Edge> = edges.filter { it.from == nodeId && it.fromPort == port }
    fun hasErrorEdge(nodeId: String): Boolean = outgoing(nodeId, ERROR).isNotEmpty()
    fun upstreamOf(nodeId: String): List<NodeInstance> = incoming(nodeId).mapNotNull { node(it.from) }
    /** Nodes with no outgoing non-error edge: their MAIN items are a sub-workflow's return value. */
    fun leaves(): List<NodeInstance> = nodes.filter { n -> edges.none { it.from == n.id && it.fromPort != ERROR } }
    fun triggers(catalog: Catalog): List<NodeInstance> = nodes.filter { catalog.spec(it.type)?.kind == NodeKind.TRIGGER }

    /** Kahn's algorithm over ALL edges; null when cyclic. Deterministic (sorted by id at each level). */
    fun topoOrder(): List<String>? {
        val ids = nodes.map { it.id }.toSet()
        val indeg = nodes.associate { it.id to 0 }.toMutableMap()
        for (e in edges) if (e.to in ids && e.from in ids) indeg[e.to] = indeg[e.to]!! + 1
        val q = ArrayDeque(indeg.filterValues { it == 0 }.keys.sorted())
        val out = ArrayList<String>(nodes.size)
        while (q.isNotEmpty()) {
            val n = q.removeFirst(); out += n
            for (e in edges.filter { it.from == n && it.to in ids }) { indeg[e.to] = indeg[e.to]!! - 1; if (indeg[e.to] == 0) q.addLast(e.to) }
        }
        return if (out.size == nodes.size) out else null
    }

    /** Structural + param validation. Empty list == valid. Tolerates unknown node types (reports them). */
    fun validate(catalog: Catalog): List<String> {
        val errs = ArrayList<String>()
        if (nodes.map { it.name }.toSet().size != nodes.size) errs += "Node names must be unique"
        for (n in nodes) {
            if (n.name.isBlank()) errs += "${n.id}: name is empty"
            if (n.name.contains('.')) errs += "${n.name}: name may not contain '.'"
            val s = catalog.spec(n.type)
            if (s == null) { errs += "${n.name}: unknown node type ${n.type}"; continue }
            s.validate(n.params).forEach { errs += "${n.name}: $it" }
        }
        val byId = nodes.associateBy { it.id }
        for (e in edges) {
            val f = byId[e.from]; val t = byId[e.to]
            if (f == null || t == null) { errs += "Dangling edge ${e.key}"; continue }
            val fs = catalog.spec(f.type); val ts = catalog.spec(t.type)
            if (fs != null && e.fromPort != ERROR && e.fromPort !in fs.outputPorts(f.params)) errs += "${f.name}: no output port '${e.fromPort}'"
            if (ts != null && e.toPort !in ts.inputs) errs += "${t.name}: no input port '${e.toPort}'"
            if (ts != null && ts.kind == NodeKind.TRIGGER) errs += "${t.name}: triggers have no inputs"
        }
        if (nodes.none { catalog.spec(it.type)?.kind == NodeKind.TRIGGER }) errs += "Workflow needs at least one trigger"
        if (topoOrder() == null) errs += "Workflow contains a cycle"
        return errs
    }
}
