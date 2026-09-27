package com.mob8n.ui

// Pure (no Android/Compose) helpers behind the widgets and the editor; unit-tested in ui/ParamWidgetMappingTest.

import com.mob8n.core.Catalog
import com.mob8n.core.ERROR
import com.mob8n.core.Graph
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeSpec
import com.mob8n.core.ParamSpec
import com.mob8n.core.asText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min

/** visibleWhen: hide when the controlling param's current value (or its default) is not in equalsAny. */
fun isVisible(p: ParamSpec, siblings: List<ParamSpec>, params: JsonObject): Boolean {
    val vw = p.visibleWhen ?: return true
    val cur = params[vw.key]?.takeUnless { it is JsonNull } ?: siblings.firstOrNull { it.key == vw.key }?.default
    return cur.asText() in vw.equalsAny
}

/** Params minus those hidden by visibleWhen; unknown keys are kept. Stale hidden values would otherwise fail Graph.validate (DESIGN §9.4). */
fun visibleParams(spec: NodeSpec, params: JsonObject): JsonObject =
    JsonObject(params.filterKeys { k -> spec.param(k)?.let { isVisible(it, spec.params, params) } ?: true })

/** The graph node with exactly one param changed (null removes it); used for the ports-only live push from the sheet. */
fun withParam(node: NodeInstance, key: String, v: JsonElement?): NodeInstance =
    node.copy(params = JsonObject(node.params.toMutableMap().also { if (v == null) it.remove(key) else it[key] = v }))

/** Drops edges leaving `n` from an output port its current params no longer define (error edges always stay). */
// ponytail: prunes only the edited node's edges; upgrade path = full graph re-validate on any param change
fun pruneEdgesFor(graph: Graph, n: NodeInstance, spec: NodeSpec?): Graph {
    if (spec == null) return graph
    val ports = spec.outputPorts(n.params)
    return graph.copy(edges = graph.edges.filter { it.from != n.id || it.fromPort == ERROR || it.fromPort in ports })
}

/** What leaving the editor should do: go, offer Save/Discard, or (invalid graph) Discard only. */
enum class LeaveAction { Go, AskSaveOrDiscard, AskDiscardOnly }
fun leaveAction(dirty: Boolean, errorsEmpty: Boolean): LeaveAction = when {
    !dirty -> LeaveAction.Go
    errorsEmpty -> LeaveAction.AskSaveOrDiscard
    else -> LeaveAction.AskDiscardOnly
}

/** Distinct dropdown label per workflow so two "Untitled" workflows resolve to different ids. */
// ponytail: 6-char id suffix, upgrade to full id or an id-keyed dropdown if needed
internal fun workflowLabel(name: String, id: String) = "$name · ${id.take(6)}"

/** Vertical extent of a port hit box: 48 dp, or the slot pitch when several ports share a card edge so boxes never overlap. */
// ponytail: <48dp vertically when >1 port, upgrade = grow CARD_H with port count
internal fun portHitHeight(count: Int, input: Boolean): Float = min(48f, CARD_H / (count + if (input) 1 else 2))

/** NUMBER field text -> stored value: whole numbers as long, decimals as double, templates as string, blank as null. */
fun parseNumber(text: String): JsonElement? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (t.contains("{{")) return JsonPrimitive(t)
    if (!t.last().isDigit()) return JsonPrimitive(t) // "5." / "-" while typing: keep raw so the caret survives
    t.toLongOrNull()?.let { return JsonPrimitive(it) }
    t.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    return JsonPrimitive(t) // invalid text is kept so ParamSpec.validate can report it
}

val DURATION_UNITS: List<Pair<String, Long>> = listOf("s" to 1_000L, "min" to 60_000L, "h" to 3_600_000L, "d" to 86_400_000L)

/** Largest unit that divides ms evenly (seconds fallback). Returns (amount, unitLabel). */
fun splitDuration(ms: Long): Pair<Double, String> {
    for ((label, factor) in DURATION_UNITS.asReversed()) if (ms > 0 && ms % factor == 0L) return (ms / factor).toDouble() to label
    return ms / 1000.0 to "s"
}

fun joinDuration(amount: Double, unit: String): Long = (amount * (DURATION_UNITS.firstOrNull { it.first == unit }?.second ?: 1_000L)).toLong()

/** `spec.name`, `spec.name 2`, ... unique within the graph; dots stripped (names are template identifiers). */
fun uniqueName(base: String, graph: Graph): String {
    val clean = base.replace('.', ' ').trim().ifBlank { "Node" }
    val taken = graph.nodes.map { it.name }.toSet()
    if (clean !in taken) return clean
    var i = 2
    while ("$clean $i" in taken) i++
    return "$clean $i"
}

fun defaultParams(spec: NodeSpec): JsonObject = JsonObject(spec.params.filter { it.default != null }.associate { it.key to it.default!! })

fun newNode(spec: NodeSpec, graph: Graph, x: Float, y: Float, id: String): NodeInstance =
    NodeInstance(id = id, type = spec.id, name = uniqueName(spec.name, graph), params = defaultParams(spec), x = x, y = y)

const val CARD_W = 200f
const val CARD_H = 92f

/** Layered by longest path from the sources (topo order), 260 dp per layer, 130 dp per row. Cyclic graphs keep their positions. */
fun autoLayout(graph: Graph): Graph {
    val order = graph.topoOrder() ?: return graph
    val layer = HashMap<String, Int>()
    for (id in order) layer[id] = (graph.incoming(id).mapNotNull { layer[it.from] }.maxOrNull() ?: -1) + 1
    val rowInLayer = HashMap<Int, Int>()
    val pos = HashMap<String, Pair<Float, Float>>()
    for (id in order) {
        val l = layer[id]!!
        val r = rowInLayer.getOrDefault(l, 0)
        rowInLayer[l] = r + 1
        pos[id] = 40f + l * 260f to 40f + r * 130f
    }
    return graph.copy(nodes = graph.nodes.map { n -> pos[n.id]?.let { (x, y) -> n.copy(x = x, y = y) } ?: n })
}

/** A Builder graph lands with every node at (0,0) (Builder.parse gives NodeInstance no positions): lay it out before it is shown (DESIGN4P P12). */
// ponytail: save_workflow stores x=y=0, the editor lays out on open; upgrade = autoLayout in core
fun needsLayout(graph: Graph): Boolean = graph.nodes.size > 1 && graph.nodes.all { it.x == 0f && it.y == 0f }

const val UNATTENDED_PREFIX = "Runs unattended: "
/** ai.agent nodes that never pause for approval (askApproval=false, or permissionMode auto/bypass) — DESIGN4P P16 wording, shown as text in the error tint. */
// ponytail: duplicates Builder.unattendedAgents (ai lane) over the Graph so the editor can flag saved workflows too; upgrade = one helper in core
fun unattendedAgents(graph: Graph): List<String> = graph.nodes.filter { it.type == "ai.agent" }.mapNotNull { n ->
    val mode = n.params["permissionMode"]?.asText()?.lowercase()
    when {
        n.params["askApproval"]?.asText() == "false" -> "$UNATTENDED_PREFIX${n.name} (askApproval=false)"
        mode == "bypass" || mode == "auto" -> "$UNATTENDED_PREFIX${n.name} (permissionMode=$mode)"
        else -> null
    }
}

/** Transitive upstream ids, nearest first; direct predecessors flagged. */
fun upstreamChain(graph: Graph, nodeId: String): List<Pair<NodeInstance, Boolean>> {
    val direct = graph.upstreamOf(nodeId).map { it.id }.toSet()
    val seen = LinkedHashSet<String>()
    val q = ArrayDeque(direct)
    while (q.isNotEmpty()) {
        val id = q.removeFirst()
        if (!seen.add(id)) continue
        graph.upstreamOf(id).forEach { if (it.id !in seen) q.addLast(it.id) }
    }
    return seen.mapNotNull { id -> graph.node(id)?.let { it to (id in direct) } }
}

/** Snippet text for one upstream field: direct predecessor -> {{key}}, further up -> {{$node.Name.key}}. */
fun fieldSnippet(upstream: NodeInstance, direct: Boolean, key: String): String =
    if (direct) "{{$key}}" else "{{\$node.${upstream.name}.$key}}"

val GLOBAL_SNIPPETS: List<String> = listOf(
    "{{\$json}}", "{{\$now}}", "{{\$date}}", "{{\$time}}", "{{\$epoch}}", "{{\$index}}", "{{\$count}}", "{{\$vars.x}}", "{{field ?? \"default\"}}",
)

// ponytail: hand-kept map of documented output fields (DESIGN §4) for nodes that never ran; upgrade = NodeSpec.outputFields when core reopens.
val FALLBACK_OUTPUT_FIELDS: Map<String, List<String>> = mapOf(
    "trigger.now_playing" to listOf("title", "artist", "album", "durationMs", "sourceApp", "state", "positionMs", "at"),
    "trigger.notification_posted" to listOf("packageName", "appName", "title", "text", "bigText", "subText", "key", "category", "postTime", "ongoing"),
    "trigger.notification_removed" to listOf("packageName", "title", "text", "key", "reason"),
    "trigger.share" to listOf("text", "url", "subject", "uri", "mimeType", "fileName", "sizeBytes"),
    "trigger.tile" to listOf("tileState", "at"),
    "trigger.shortcut" to listOf("at"),
    "trigger.manual" to listOf("at"),
    "data.device_state" to listOf("battery", "charging", "plugged", "wifiSsid", "networkType", "metered", "screenOn", "powerSave", "ringerMode", "dnd", "btAudioConnected", "wiredHeadset", "outputDevice", "brightness", "orientation"),
    "data.now_playing" to listOf("title", "artist", "album", "durationMs", "sourceApp", "state", "positionMs"),
    "data.active_notifications" to listOf("packageName", "appName", "title", "text", "key", "postTime", "ongoing"),
    "data.location" to listOf("lat", "lng", "accuracyM", "altitude", "speed", "provider", "time"),
    "data.calendar_events" to listOf("title", "begin", "end", "beginIso", "endIso", "location", "description", "calendar", "allDay", "eventId"),
    "data.contact_lookup" to listOf("found", "contactName", "phones", "emails", "lookupKey"),
    "ai.ask" to listOf("answer", "provider", "model", "stopReason"),
    "ai.classify" to listOf("label", "confidence"),
    "ai.decide" to listOf("answers", "decisions", "engine", "s1Model", "latencyMs"),   // == Builder.OUTPUT_HINTS["ai.decide"] (deliberate duplicate, DESIGN5 §5.4)
    "ai.agent" to listOf("result", "steps", "stopReason", "truncated"),
    // apps lane (DESIGN2 §7.5)
    "app.capabilities" to listOf("package", "appName", "isSystem", "capabilities", "recipes"),
    "app.recipes" to listOf("recipe", "label", "params", "available", "template"),
    "app.action" to listOf("launched", "viaNotification", "recipe", "package", "data"),
    "app.launch_wait" to listOf("launched", "viaNotification", "foreground", "waitedMs"),
    "app.ui_read" to listOf("package", "activity", "count", "truncated", "nodes"),
    "app.ui_tap" to listOf("tapped", "method", "target"),
    "app.ui_long_press" to listOf("pressed", "method"),
    "app.ui_type" to listOf("typed", "length", "field"),
    "app.ui_scroll" to listOf("scrolled"),
    "app.ui_wait_for" to listOf("found", "waitedMs", "target"),
    "app.ui_global" to listOf("performed"),
    "app.ui_screenshot" to listOf("uri", "width", "height", "bytes"),
    // v3 knowledge + MCP (DESIGN3 §6.4)
    "data.knowledge_search" to listOf("context", "count", "hits", "text", "source", "sourceId", "seq", "score"),
    "action.knowledge_add" to listOf("sourceId", "name", "kind", "chunks", "chars", "bytes"),
    "action.knowledge_remove" to listOf("removed"),
    "ai.mcp_tool" to listOf("text", "content", "structured", "isError", "server", "tool"),
    "ai.mcp_resource" to listOf("text", "mimeType", "uri", "server"),
    // v4 coding (DESIGN4 §7.3)
    "app.shell_run" to listOf("exitCode", "stdout", "stderr", "truncated", "timedOut", "ms", "outputFile"),
    "logic.js" to listOf("value"),
)

/** Documented output keys for a node type when no run exists yet. */
fun fallbackKeys(specId: String, catalog: Catalog): List<String> =
    FALLBACK_OUTPUT_FIELDS[specId] ?: catalog.spec(specId)?.params?.firstOrNull { it.key == "outputField" }?.default?.asText()?.let { listOf(it) } ?: emptyList()

