@file:OptIn(ExperimentalFoundationApi::class)

package com.mob8n.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mob8n.core.Catalog
import com.mob8n.core.ERROR
import com.mob8n.core.Edge
import com.mob8n.core.Graph
import com.mob8n.core.Hosting
import com.mob8n.core.MAIN
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.NodeStatus
import com.mob8n.core.RunStatus
import com.mob8n.engine.HostStatus
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MIN_SCALE = 0.35f
private const val MAX_SCALE = 2.5f
private val RED = nodeStatusColor(NodeStatus.FAILED)          // DESIGN6 §2.3 signal colours (fixed, non-text)
private val AMBER = nodeStatusColor(NodeStatus.SUSPENDED)
private val GREY = nodeStatusColor(null)

// ---- live run overlay (DESIGN6 §6.7; pure, RunOverlayTest) ----

/** Nodes to pulse and edges to animate while a run is in flight. */
data class RunOverlay(val active: Set<String>, val flowing: Set<String /* Edge.key */>) {
    companion object { val NONE = RunOverlay(emptySet(), emptySet()) }
}

/**
 * When `running`: active = unlogged nodes with an incoming edge from a node logged SUCCESS (any non-error port) or ERROR_ROUTED (its
 * `error` port only); before any log, the trigger nodes. flowing = those incoming edges.
 * ponytail: running node inferred from node_logs (logged on completion); upgrade = engine.activeNodes flow
 */
fun runOverlay(graph: Graph, logs: Map<String, NodeStatus>, running: Boolean): RunOverlay {
    if (!running) return RunOverlay.NONE
    if (logs.isEmpty()) return RunOverlay(graph.nodes.filter { it.type.startsWith("trigger.") && !it.disabled }.map { it.id }.toSet(), emptySet())
    val flowing = graph.edges.filter { e ->
        e.to !in logs && when (logs[e.from]) {
            NodeStatus.SUCCESS -> e.fromPort != ERROR
            NodeStatus.ERROR_ROUTED -> e.fromPort == ERROR
            else -> false
        }
    }
    return RunOverlay(flowing.map { it.to }.toSet(), flowing.map { it.key }.toSet())
}

/** One edge, built once per graph/spec change (not per draw): endpoints in graph dp, cubic path in px. */
private class EdgeGeom(val key: String, val isErr: Boolean, val a: Offset, val b: Offset, val path: Path)

/** A deleted node's outline, fading out in the edge layer. */
private class Ghost(val x: Float, val y: Float, val alpha: Animatable<Float, AnimationVector1D>)

/** Pan/zoom owned by the editor so toolbar actions (fit, centre-on-node) can drive it. Graph units are dp at scale 1. */
class CanvasState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)

    /** Graph coordinates (dp) at the viewport centre, minus half a card, so a new node lands centred. */
    fun centreForNewNode(density: Float): Offset {
        val cx = (viewport.width / 2f - offset.x) / scale / density
        val cy = (viewport.height / 2f - offset.y) / scale / density
        return Offset(cx - CARD_W / 2, cy - CARD_H / 2)
    }

    fun fit(graph: Graph, density: Float) {
        if (graph.nodes.isEmpty() || viewport == IntSize.Zero) { scale = 1f; offset = Offset.Zero; return }
        val minX = graph.nodes.minOf { it.x } - 24; val minY = graph.nodes.minOf { it.y } - 24
        val maxX = graph.nodes.maxOf { it.x } + CARD_W + 24; val maxY = graph.nodes.maxOf { it.y } + CARD_H + 24
        val w = (maxX - minX) * density; val h = (maxY - minY) * density
        scale = min(viewport.width / w, viewport.height / h).coerceIn(MIN_SCALE, MAX_SCALE)
        offset = Offset(viewport.width / 2f - (minX + maxX) / 2 * density * scale, viewport.height / 2f - (minY + maxY) / 2 * density * scale)
    }

    fun centreOn(n: NodeInstance, density: Float) {
        offset = Offset(viewport.width / 2f - (n.x + CARD_W / 2) * density * scale, viewport.height / 2f - (n.y + CARD_H / 2) * density * scale)
    }
}

@Composable
fun rememberCanvasState(): CanvasState = remember { CanvasState() }

/** Port centre in graph dp. Inputs on the left, outputs on the right, error diamond bottom-right. */
private fun portPos(n: NodeInstance, spec: NodeSpec?, port: String, input: Boolean): Offset {
    if (input) {
        val ins = spec?.inputs ?: listOf(MAIN)
        val i = ins.indexOf(port).coerceAtLeast(0)
        return Offset(n.x, n.y + CARD_H * (i + 1) / (ins.size + 1))
    }
    val outs = spec?.outputPorts(n.params) ?: listOf(MAIN)
    val i = if (port == ERROR) outs.size else outs.indexOf(port).coerceAtLeast(0)
    return Offset(n.x + CARD_W, n.y + CARD_H * (i + 1) / (outs.size + 2))
}

/** Point at parameter t on the edge cubic drawn by GraphCanvas (graph dp). */
private fun bezierAt(a: Offset, b: Offset, t: Float): Offset {
    val dx = max(40f, abs(b.x - a.x) / 2)
    val c1 = Offset(a.x + dx, a.y); val c2 = Offset(b.x - dx, b.y)
    val u = 1 - t
    return a * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + b * (t * t * t)
}

/** Tap within 24 screen dp of the curve (sampled at t = 0.25/0.5/0.75); `g` is in graph dp, so the radius is divided by the zoom scale. */
internal fun edgeHit(g: Offset, a: Offset, b: Offset, scale: Float): Boolean =
    listOf(0.25f, 0.5f, 0.75f).any { t -> val m = bezierAt(a, b, t); hypot(m.x - g.x, m.y - g.y) < 24f / scale }

@Composable
fun GraphCanvas(
    graph: Graph, onGraph: (Graph) -> Unit, catalog: Catalog, state: CanvasState, hostStatus: HostStatus,
    nodeStatus: Map<String, NodeStatus>, highlightNodeId: String?, onConfigure: (String) -> Unit, onMessage: (String) -> Unit,
    modifier: Modifier = Modifier, overlay: RunOverlay = RunOverlay.NONE, running: Boolean = false,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val tick = rememberResumeTick()
    var connectFrom by remember { mutableStateOf<Pair<String, String>?>(null) }   // nodeId, port
    var selectedEdge by remember { mutableStateOf<String?>(null) }
    var edgeToDelete by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val specs = remember(graph.nodes.map { it.id to it.type }) { graph.nodes.associate { it.id to catalog.spec(it.type) } }
    val motion = LocalMotion.current
    val animScope = rememberCoroutineScope()
    // Edge draw-in (MEDIUM2) for keys not seen in the previous composition; existing edges stay static.
    val edgeKeys = graph.edges.map { it.key }
    val prevEdges = remember { arrayOf(edgeKeys.toSet()) }
    val freshEdges = remember(edgeKeys) { edgeKeys.filter { it !in prevEdges[0] } }
    val drawIn = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }
    drawIn.keys.retainAll(edgeKeys.toSet())
    if (!motion.reduced) for (k in freshEdges) drawIn.getOrPut(k) { Animatable(0f) }
    LaunchedEffect(freshEdges) { for (k in freshEdges) drawIn[k]?.let { a -> animScope.launch { a.animateTo(1f, motion.enterEffect(MotionTokens.MEDIUM2)) } } }
    // Node appear (enterOnce for ids not in the loaded graph) / disappear (ghost outline fading over SHORT2).
    val initialIds = remember { graph.nodes.map { it.id }.toSet() }
    val nodeIds = graph.nodes.map { it.id }
    val prevNodes = remember { arrayOf(graph.nodes) }
    val goneNodes = remember(nodeIds) { prevNodes[0].filter { it.id !in nodeIds } }
    val ghosts = remember { mutableStateListOf<Ghost>() }
    LaunchedEffect(goneNodes) {
        if (!motion.reduced) for (n in goneNodes) { val g = Ghost(n.x, n.y, Animatable(1f)); ghosts += g; animScope.launch { g.alpha.animateTo(0f, motion.effect(MotionTokens.SHORT2)); ghosts -= g } }
    }
    SideEffect { prevEdges[0] = edgeKeys.toSet(); prevNodes[0] = graph.nodes }
    // Flowing dots: one frame ticker (FLOW_MS per traversal), read only in draw; none when reduced (static 4 dp edge instead).
    val phase = remember { mutableFloatStateOf(0f) }
    if (overlay.flowing.isNotEmpty() && motion.loops) LaunchedEffect(Unit) {
        while (true) withFrameNanos { t -> phase.floatValue = ((t / 1_000_000L) % MotionTokens.FLOW_MS) / MotionTokens.FLOW_MS.toFloat() }
    }
    val edgeGeoms = remember(graph.edges, graph.nodes, specs, density) {
        graph.edges.mapNotNull { e ->
            val f = graph.node(e.from) ?: return@mapNotNull null; val t = graph.node(e.to) ?: return@mapNotNull null
            val a = portPos(f, specs[f.id], e.fromPort, false); val b = portPos(t, specs[t.id], e.toPort, true)
            val ap = a * density; val bp = b * density
            val dx = max(40f * density, abs(bp.x - ap.x) / 2)
            EdgeGeom(e.key, e.fromPort == ERROR, a, b, Path().apply { moveTo(ap.x, ap.y); cubicTo(ap.x + dx, ap.y, bp.x - dx, bp.y, bp.x, bp.y) })
        }
    }
    val strokes = remember(density) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f * density, 8f * density))
        object {
            val plain = Stroke(2.5f * density); val plainErr = Stroke(2.5f * density, pathEffect = dash)
            val sel = Stroke(5f * density); val selErr = Stroke(5f * density, pathEffect = dash)
            val flow = Stroke(3f * density); val flowStatic = Stroke(4f * density); val ghost = Stroke(2f * density)
        }
    }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val gateMissing = remember(specs, tick) { specs.mapValues { (_, s) -> s?.gates?.any { g -> g.enforced && runCatching { !g.granted(ctx) }.getOrDefault(false) } ?: false } }   // DESIGN3P D14: enforced gates only paint red

    fun edgeAt(screen: Offset): Edge? {
        val g = Offset((screen.x - state.offset.x) / state.scale / density, (screen.y - state.offset.y) / state.scale / density)
        return graph.edges.firstOrNull { e ->
            val f = graph.node(e.from) ?: return@firstOrNull false; val t = graph.node(e.to) ?: return@firstOrNull false
            edgeHit(g, portPos(f, specs[f.id], e.fromPort, false), portPos(t, specs[t.id], e.toPort, true), state.scale)
        }
    }
    fun update(id: String, f: (NodeInstance) -> NodeInstance) = onGraph(graph.copy(nodes = graph.nodes.map { if (it.id == id) f(it) else it }))
    fun connect(to: String, toPort: String) {
        val (from, fromPort) = connectFrom ?: run { onMessage("Tap an output port first, then an input port"); return }
        connectFrom = null
        val e = Edge(from, fromPort, to, toPort)
        when {
            from == to -> onMessage("A node can't connect to itself")
            graph.edges.any { it.key == e.key } -> onMessage("Already connected")
            else -> {
                val g2 = graph.copy(edges = graph.edges + e)
                val before = graph.validate(catalog).toSet()
                val fresh = g2.validate(catalog).filter { it !in before }
                if (fresh.isEmpty()) onGraph(g2) else onMessage(fresh.first())
            }
        }
    }

    val transformable = rememberTransformableState { zoom, pan, _ ->
        val newScale = (state.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val c = Offset(state.viewport.width / 2f, state.viewport.height / 2f)
        state.offset = c - (c - state.offset) * (newScale / state.scale) + pan
        state.scale = newScale
    }

    Box(
        modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLowest).onSizeChanged { state.viewport = it }
            .transformable(transformable)
            .pointerInput(graph, connectFrom) {
                detectTapGestures(
                    onTap = { p ->
                        if (connectFrom != null) { connectFrom = null; return@detectTapGestures }
                        selectedEdge = edgeAt(p)?.key
                    },
                    onLongPress = { p -> edgeAt(p)?.let { selectedEdge = it.key; edgeToDelete = it.key } },
                )
            }
            .semantics { contentDescription = "Workflow canvas: ${graph.nodes.size} nodes, ${graph.edges.size} connections" },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = state.scale; scaleY = state.scale; translationX = state.offset.x; translationY = state.offset.y
            transformOrigin = TransformOrigin(0f, 0f)
        }) {
            val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant
            val flowColor = MaterialTheme.colorScheme.primary
            val ghostColor = MaterialTheme.colorScheme.outline
            Spacer(Modifier.fillMaxSize().drawBehind {
                val loops = motion.loops
                for (g in edgeGeoms) {
                    val p = drawIn[g.key]?.value ?: 1f
                    val flowing = g.key in overlay.flowing
                    val path = if (p >= 1f) g.path else {
                        measure.setPath(g.path, false); segment.reset()
                        measure.getSegment(0f, measure.length * p, segment, true); segment
                    }
                    val stroke = when {
                        flowing -> if (loops) strokes.flow else strokes.flowStatic
                        g.key == selectedEdge -> if (g.isErr) strokes.selErr else strokes.sel
                        else -> if (g.isErr) strokes.plainErr else strokes.plain
                    }
                    drawPath(path, if (flowing) flowColor else if (g.isErr) RED else edgeColor, style = stroke)
                    if (flowing && loops) for (k in 0..2) {
                        val pt = bezierAt(g.a, g.b, (phase.floatValue + k / 3f) % 1f) * density
                        drawCircle(flowColor, radius = 4f * density, center = pt)
                    }
                }
                for (gh in ghosts) drawRoundRect(ghostColor.copy(alpha = gh.alpha.value), Offset(gh.x * density, gh.y * density), Size(CARD_W * density, CARD_H * density),
                    CornerRadius(12f * density), style = strokes.ghost)
            })
            for (n in graph.nodes) key(n.id) {
                val spec = specs[n.id]
                val hosting = catalog.trigger(n.type)?.hosting
                NodeCard(
                    n, spec,
                    gateMissing = gateMissing[n.id] == true,
                    needsHost = (hosting == Hosting.RUNTIME_RECEIVER || hosting == Hosting.HOST_ATTACHED) && !hostStatus.listenerGranted && !hostStatus.serviceRunning,
                    lastStatus = nodeStatus[n.id], highlighted = n.id == highlightNodeId, menuOpen = menuFor == n.id,
                    appear = n.id !in initialIds, active = n.id in overlay.active, running = running,
                    onDrag = { d -> update(n.id) { it.copy(x = it.x + d.x / density, y = it.y + d.y / density) } },
                    onTap = { if (connectFrom != null) connectFrom = null else onConfigure(n.id) },
                    onLongPress = { menuFor = n.id }, onMenuDismiss = { menuFor = null },
                    onMenu = { action ->
                        menuFor = null
                        when (action) {
                            "Configure" -> onConfigure(n.id)
                            "Rename" -> renameId = n.id
                            "Disable", "Enable" -> update(n.id) { it.copy(disabled = !it.disabled) }
                            "Duplicate" -> onGraph(graph.copy(nodes = graph.nodes + n.copy(id = UUID.randomUUID().toString(), name = uniqueName(n.name, graph), x = n.x + 40, y = n.y + 40)))
                            "Delete" -> deleteId = n.id
                        }
                    },
                )
            }
            for (n in graph.nodes) {
                val spec = specs[n.id]
                val ins = spec?.inputs ?: listOf(MAIN)
                if (spec?.kind != NodeKind.TRIGGER) for (p in ins) {
                    val c = portPos(n, spec, p, true)
                    Port(n.name, p, c, color = if (connectFrom != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        label = if (ins.size > 1) p else null, input = true, highlighted = connectFrom != null, hitH = portHitHeight(ins.size, true)) { connect(n.id, p) }
                }
                val outs = spec?.outputPorts(n.params) ?: listOf(MAIN)
                val outH = portHitHeight(outs.size, false)   // the error diamond shares the output pitch
                for (p in outs) {
                    val c = portPos(n, spec, p, false)
                    val sel = connectFrom == (n.id to p)
                    Port(n.name, p, c, color = if (sel) MaterialTheme.colorScheme.primary else kindColor(spec?.kind ?: NodeKind.LOGIC),
                        label = if (outs.size > 1 || p != MAIN) p else null, input = false, highlighted = sel, hitH = outH) {
                        connectFrom = if (sel) null else (n.id to p); selectedEdge = null
                    }
                }
                val ec = portPos(n, spec, ERROR, false)
                Port(n.name, ERROR, ec, color = RED, label = null, input = false, highlighted = connectFrom == (n.id to ERROR), diamond = true, hitH = outH) {
                    connectFrom = if (connectFrom == (n.id to ERROR)) null else (n.id to ERROR); selectedEdge = null
                }
            }
        }

        connectFrom?.let { (id, port) ->
            Surface(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(8.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(Radius.m), tonalElevation = 3.dp) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Connecting ${graph.node(id)?.name ?: ""} · $port → tap an input port", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { connectFrom = null }) { Text("Cancel") }
                }
            }
        }
        selectedEdge?.let { key ->
            // DESIGN6 §6.3 (5): the canvas runs under the nav bar; its controls stay in the safe area.
            if (graph.edges.any { it.key == key }) PillButton("Delete connection", onClick = { edgeToDelete = key }, icon = Icons.Rounded.Close, tone = Tone.Neutral,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(12.dp))
        }
    }

    edgeToDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { edgeToDelete = null }, title = { Text("Delete connection?") },
            confirmButton = { TextButton(onClick = { onGraph(graph.copy(edges = graph.edges.filter { it.key != key })); edgeToDelete = null; selectedEdge = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { edgeToDelete = null }) { Text("Cancel") } },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null }, title = { Text("Delete ${graph.node(id)?.name ?: "node"}?") }, text = { Text("Its connections are removed too.") },
            confirmButton = { TextButton(onClick = { onGraph(Graph(graph.nodes.filter { it.id != id }, graph.edges.filter { it.from != id && it.to != id })); deleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
        )
    }
    renameId?.let { id ->
        var name by remember(id) { mutableStateOf(graph.node(id)?.name ?: "") }
        val err = when {
            name.isBlank() -> "Name is required"; name.contains('.') -> "No dots"
            graph.nodes.any { it.id != id && it.name == name } -> "Name already used"; else -> null
        }
        AlertDialog(
            onDismissRequest = { renameId = null }, title = { Text("Rename node") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, isError = err != null, supportingText = { Text(err ?: "Used as {{\$node.Name.field}}") }) },
            confirmButton = { Button(enabled = err == null, onClick = { update(id) { it.copy(name = name.trim()) }; renameId = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NodeCard(
    n: NodeInstance, spec: NodeSpec?, gateMissing: Boolean, needsHost: Boolean, lastStatus: NodeStatus?, highlighted: Boolean, menuOpen: Boolean,
    appear: Boolean, active: Boolean, running: Boolean,
    onDrag: (Offset) -> Unit, onTap: () -> Unit, onLongPress: () -> Unit, onMenuDismiss: () -> Unit, onMenu: (String) -> Unit,
) {
    val kind = spec?.kind ?: NodeKind.LOGIC
    val drag by rememberUpdatedState(onDrag) // pointerInput is keyed on the id only; always call the newest lambda
    val motion = LocalMotion.current
    val shape = MaterialTheme.shapes.medium
    // Completion flash: a status that newly becomes SUCCESS/FAILED during a live run washes the card in its signal colour (0.35 -> 0, LONG2).
    val flash = remember { Animatable(0f) }
    val prevStatus = remember { arrayOf(lastStatus) }
    val live by rememberUpdatedState(running)
    LaunchedEffect(lastStatus) {
        val was = prevStatus[0]; prevStatus[0] = lastStatus
        if (live && !motion.reduced && lastStatus != was && lastStatus in FLASH) { flash.snapTo(0.35f); flash.animateTo(0f, motion.effect(MotionTokens.LONG2)) }
    }
    val flashColor = nodeStatusColor(lastStatus)
    val runRing = if (!active) Modifier else if (motion.loops) Modifier.pulse(true, statusColor(RunStatus.RUNNING), shape) else Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
    Box(Modifier.offset(n.x.dp, n.y.dp).enterOnce(enabled = appear)) {
        Card(
            Modifier.size(CARD_W.dp, CARD_H.dp).then(runRing)
                .drawWithContent { drawContent(); val a = flash.value; if (a > 0f) drawRoundRect(flashColor.copy(alpha = a), cornerRadius = CornerRadius(Radius.m.toPx())) }
                .pointerInput(n.id) { detectDragGestures { change, d -> change.consume(); drag(d) } }
                .combinedClickable(onClick = onTap, onLongClick = onLongPress, onClickLabel = "Configure ${n.name}", onLongClickLabel = "Node menu")
                .then(if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape) else Modifier)
                .semantics { contentDescription = "${n.name}, ${spec?.name ?: n.type}${if (n.disabled) ", disabled" else ""}${if (active) ", running" else ""}" },
            colors = CardDefaults.cardColors(containerColor = if (n.disabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(6.dp).fillMaxHeight().background(if (n.disabled) GREY else kindColor(kind)))
                Column(Modifier.padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 6.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(nodeIcon(n.type, kind), contentDescription = null, tint = kindColor(kind), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(n.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    Text(spec?.name ?: n.type, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (gateMissing) Dot(RED, "Permission missing")
                        if (needsHost) Dot(AMBER, "Needs background host")
                        if (n.disabled) Dot(GREY, "Disabled")
                        if (lastStatus != null) Dot(nodeStatusColor(lastStatus), "Last run: ${lastStatus.name.lowercase()}", hollow = true)
                        if (spec == null) Text("unknown type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        if (active) Text("running…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)   // never motion-only
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            for (a in listOf("Configure", "Rename", if (n.disabled) "Enable" else "Disable", "Duplicate", "Delete"))
                DropdownMenuItem(text = { Text(a) }, onClick = { onMenu(a) })
        }
    }
}

@Composable
private fun Dot(color: Color, description: String, hollow: Boolean = false) {
    Box(Modifier.size(10.dp).then(if (hollow) Modifier.border(2.dp, color, CircleShape) else Modifier.background(color, CircleShape)).semantics { contentDescription = description })
}

/** 48 dp wide hit box centred on the port (hitH tall: see portHitHeight); circle (or red diamond for `error`). Label sits inside the card, 4 dp clear of the circle. */
@Composable
private fun Port(nodeName: String, port: String, centre: Offset, color: Color, label: String?, input: Boolean, highlighted: Boolean, diamond: Boolean = false, hitH: Float = 48f, onClick: () -> Unit) {
    Box(
        Modifier.offset((centre.x - 24).dp, (centre.y - hitH / 2).dp).size(48.dp, hitH.dp)
            .combinedClickable(onClick = onClick, onClickLabel = if (input) "Connect here" else "Start connection")
            .semantics { contentDescription = "$port port of $nodeName" },
        contentAlignment = Alignment.Center,
    ) {
        if (diamond) Box(Modifier.size(if (highlighted) 14.dp else 11.dp).rotate(45f).background(color))
        else Box(Modifier.size(if (highlighted) 18.dp else 14.dp).background(color, CircleShape).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
    }
    if (label != null) Text(
        label, fontSize = 11.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (input) TextAlign.Start else TextAlign.End,
        modifier = Modifier.offset((if (input) centre.x + 11 else centre.x - 11 - PORT_LABEL_W).dp, (centre.y - 8).dp).width(PORT_LABEL_W.dp),
    )
}

private val FLASH = setOf(NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.TIMEOUT)

/** Width reserved for a port label inside the card (circle radius 7 dp + 4 dp gap, then this much text). */
private const val PORT_LABEL_W = 72f
