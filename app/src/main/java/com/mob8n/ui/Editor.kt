@file:OptIn(ExperimentalMaterial3Api::class)

package com.mob8n.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.core.Catalog
import com.mob8n.core.Graph
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeStatus
import com.mob8n.core.RunStatus
import com.mob8n.core.TRIGGER_CALLED
import com.mob8n.core.Workflow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mob8n.engine.Engine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** Newest run of the open workflow, as the canvas needs it. */
data class RunView(val running: Boolean, val logs: Map<String, NodeStatus>) { companion object { val IDLE = RunView(false, emptyMap()) } }

/** Workflow editor (DESIGN §9.2 Editor + §9.3 Canvas). Whole graph is one MutableState; Save writes via engine.save. */
@Composable
fun EditorScreen(workflowId: String, focusNodeId: String?, engine: Engine, catalog: Catalog, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val snack = remember { SnackbarHostState() }
    val canvas = rememberCanvasState()
    val hostStatus by engine.hostStatus.collectAsStateWithLifecycle()

    var loaded by remember(workflowId) { mutableStateOf<Workflow?>(null) }
    var isDraft by remember(workflowId) { mutableStateOf(false) }          // loaded from Drafts (AI-generated, never saved)
    var refining by remember { mutableStateOf(false) }
    var missing by remember(workflowId) { mutableStateOf(false) }
    var name by remember(workflowId) { mutableStateOf("") }
    var graph by remember(workflowId) { mutableStateOf(Graph()) }
    var dirty by remember(workflowId) { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }   // pending navigation awaiting the unsaved-changes dialog
    var focusId by remember(workflowId, focusNodeId) { mutableStateOf(focusNodeId) }

    LaunchedEffect(workflowId) {
        val saved = runCatching { engine.workflow(workflowId) }.getOrNull()
        val wf = saved ?: Drafts.map[workflowId]
        if (wf == null) { missing = true; return@LaunchedEffect }
        val g = if (needsLayout(wf.graph)) autoLayout(wf.graph) else wf.graph   // DESIGN4P P12: Builder / save_workflow graphs arrive at 0,0; positions persist on the next Save
        loaded = wf.copy(graph = g); name = wf.name; graph = g; dirty = false; isDraft = saved == null
        if (isDraft) { val e = wf.graph.validate(catalog); if (e.isNotEmpty()) snack.showSnackbar("Generated workflow needs fixes (${e.size}): ${e.first()}") }
    }
    // Zoom to fit once the viewport is known (or centre on the deep-linked node).
    LaunchedEffect(loaded?.id, canvas.viewport) {
        val wf = loaded ?: return@LaunchedEffect
        if (canvas.viewport.width == 0) return@LaunchedEffect
        val f = focusId?.let { id -> wf.graph.node(id) }
        if (f != null) canvas.centreOn(f, density) else canvas.fit(wf.graph, density)
    }
    // Newest run's per-node status for the canvas dots + whether it is still running (DESIGN6 §6.7 live overlay).
    @Suppress("OPT_IN_USAGE")
    val runView by remember(workflowId) {
        engine.runs(workflowId, 1).flatMapLatest { runs ->
            runs.firstOrNull()?.let { r -> engine.nodeLogs(r.runId).map { logs -> RunView(r.status == RunStatus.RUNNING, logs.associate { it.nodeId to it.status }) } } ?: flowOf(RunView.IDLE)
        }
    }.collectAsStateWithLifecycle(RunView.IDLE)
    val overlay = remember(graph, runView) { runOverlay(graph, runView.logs, runView.running) }

    val errors = remember(graph) { graph.validate(catalog) }
    fun setGraph(g: Graph) { graph = g; dirty = true }
    fun msg(s: String) = scope.launch { snack.showSnackbar(s) }
    suspend fun save(): Boolean {
        val wf = loaded ?: return false
        if (errors.isNotEmpty()) { snack.showSnackbar(errors.first()); return false }
        return try {
            // Re-read the row so a toggle made elsewhere while editing (two-pane Switch, schedule.once auto-disable) is not reverted: the editor owns only name + graph.
            val fresh = engine.workflow(workflowId) ?: wf
            val updated = fresh.copy(name = name.trim().ifBlank { "Untitled" }, graph = graph, updatedAt = System.currentTimeMillis())
            engine.save(updated); loaded = updated; dirty = false
            if (isDraft) { Drafts.map.remove(workflowId); isDraft = false }
            true
        } catch (e: Exception) { snack.showSnackbar("Save failed: ${e.message}"); false }
    }
    /** Unsaved edits: ask before navigating away (DESIGN §9.2). */
    fun guard(go: () -> Unit) { if (!isDraft && leaveAction(dirty, errors.isEmpty()) == LeaveAction.Go) go() else leaving = go }
    BackHandler(enabled = dirty || isDraft) { leaving = onBack }   // a draft is unsaved by definition: always confirm   // innermost enabled handler wins over App's screen.parent handler
    // ponytail: deep-link into Editor (App.kt uiIntents) bypasses the dirty guard, ceiling = rare notification tap mid-edit; upgrade = route uiIntents through guard

    Scaffold(
        topBar = {
            // Deviation (DESIGN6 §6.7): the title is the editable workflow name, so this stays an M3 TopAppBar with a composable title;
            // MahoutTopBar takes a String. Same statusBars insets and surface colours.
            TopAppBar(
                title = {
                    BasicTextField(
                        value = name, onValueChange = { name = it; dirty = true }, singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Workflow name" },
                        decorationBox = { inner -> Box { if (name.isEmpty()) Text("Workflow name", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
                    )
                },
                navigationIcon = { IconButton(onClick = { guard(onBack) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    PillButton("Save", onClick = { scope.launch { if (save()) snack.showSnackbar("Saved") } }, tone = Tone.Neutral, outlined = !(dirty || isDraft),
                        enabled = loaded != null && errors.isEmpty(), contentDescription = if (dirty) "Save (unsaved changes)" else "Save")
                    Spacer(Modifier.width(Space.xs))
                    PillButton("Run", icon = Icons.Rounded.PlayArrow, contentDescription = "Run now", enabled = loaded != null, onClick = {
                        scope.launch {
                            if ((dirty || isDraft) && !save()) return@launch
                            val runId = runCatching { engine.runManual(workflowId) }.getOrElse { snack.showSnackbar("Run failed: ${it.message}"); return@launch }
                            if (runId == null) snack.showSnackbar("Run did not start (needs a Manual trigger)")
                            else if (snack.showSnackbar("Run started", actionLabel = "View") == SnackbarResult.ActionPerformed) onOpen(Screen.RunDetail(runId))
                        }
                    })
                    IconButton(onClick = { moreOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More actions") }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text("Validate") }, onClick = { moreOpen = false; msg(if (errors.isEmpty()) "Workflow is valid" else errors.joinToString("\n")) })
                        DropdownMenuItem(text = { Text("Auto-layout") }, onClick = { moreOpen = false; setGraph(autoLayout(graph)); canvas.fit(graph, density) })
                        DropdownMenuItem(text = { Text("Zoom to fit") }, onClick = { moreOpen = false; canvas.fit(graph, density) })
                        DropdownMenuItem(text = { Text("Refine with AI…") }, onClick = { moreOpen = false; refining = true })
                        DropdownMenuItem(text = { Text("Build with AI…") }, onClick = { moreOpen = false; guard { onOpen(Screen.Build(if (isDraft) null else workflowId)) } })
                        DropdownMenuItem(text = { Text("Runs") }, onClick = { moreOpen = false; guard { onOpen(Screen.Runs(workflowId)) } })
                        // DESIGN4 §8.1: the trigger.called param sheet IS the workflows-as-tools settings UI; add the node when missing.
                        DropdownMenuItem(text = { Text("Expose as tool…") }, onClick = {
                            moreOpen = false
                            val existing = graph.nodes.firstOrNull { it.type == TRIGGER_CALLED }
                            val spec = catalog.spec(TRIGGER_CALLED)
                            when {
                                existing != null -> editingNode = existing.id
                                spec == null -> msg("Called-by-Workflow trigger is not in this build")
                                else -> {
                                    val n = newNode(spec, graph, 40f, 40f, UUID.randomUUID().toString()).let { it.copy(params = JsonObject(it.params + ("exposeAsTool" to JsonPrimitive(true)))) }
                                    setGraph(graph.copy(nodes = graph.nodes + n)); editingNode = n.id
                                }
                            }
                        }, modifier = Modifier.semantics { contentDescription = "Expose as tool" })
                    }
                },
            )
        },
        snackbarHost = { MahoutSnackbarHost(snack) },
        floatingActionButton = {
            if (loaded != null) FloatingActionButton(onClick = { paletteOpen = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add node") }
        },
    ) { pad ->
        when {
            missing -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = androidx.compose.ui.Alignment.Center) {
                EmptyState(Icons.Rounded.SearchOff, "Workflow not found", "It may have been deleted, or the draft expired.")
            }
            loaded == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            // DESIGN6 §6.3 (5): the canvas draws edge-to-edge under the nav bar (only the top bar is padded); its controls use safeDrawing.
            else -> Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).consumeWindowInsets(PaddingValues(top = pad.calculateTopPadding()))) {
                GraphCanvas(
                    graph = graph, onGraph = ::setGraph, catalog = catalog, state = canvas, hostStatus = hostStatus, nodeStatus = runView.logs,
                    highlightNodeId = focusId, onConfigure = { editingNode = it; focusId = null }, onMessage = { msg(it) },
                    modifier = Modifier.fillMaxSize(), overlay = overlay, running = runView.running,
                )
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(Space.s)) {
                    if (isDraft) AssistChip(
                        onClick = { scope.launch { if (save()) snack.showSnackbar("Saved") } }, label = { Text("Draft — not saved") },
                        modifier = Modifier.semantics { contentDescription = "Draft, not saved. Tap to save" },
                    )
                    // DESIGN4P P16: an agent that never asks is named in words (never colour-only) wherever the graph is shown.
                    for (line in remember(graph) { unattendedAgents(graph) }) Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { contentDescription = line })
                }
            }
        }
    }

    if (refining) RefineWithAiDialog(catalog, graph, name, onDismiss = { refining = false }, onResult = { g, errs ->
        refining = false
        val previous = graph
        setGraph(autoLayout(g)); canvas.fit(graph, density)
        scope.launch {
            val text = if (errs.isEmpty()) "Refined" else "Refined with ${errs.size} issue(s): ${errs.first()}"
            if (snack.showSnackbar(text, actionLabel = "Undo") == SnackbarResult.ActionPerformed) { setGraph(previous); canvas.fit(previous, density) }
        }
    })

    if (paletteOpen) PaletteSheet(catalog, onPick = { n ->
        val c = canvas.centreForNewNode(density)
        setGraph(graph.copy(nodes = graph.nodes + newNode(n.spec, graph, c.x, c.y, UUID.randomUUID().toString())))
        paletteOpen = false
    }, onDismiss = { paletteOpen = false })

    editingNode?.let { id ->
        val node = graph.node(id)
        val spec = node?.let { catalog.spec(it.type) }
        if (node == null || spec == null) { editingNode = null; if (node != null) msg("Unknown node type ${node.type}"); return@let }
        // Replace the node and drop edges from ports its new params no longer define (F38); onSave reports how many.
        fun replaced(n: NodeInstance) = pruneEdgesFor(graph.copy(nodes = graph.nodes.map { if (it.id == n.id) n else it }), n, spec)
        ParamSheet(
            node, spec, graph, workflowId, engine, catalog,
            onLive = { n -> setGraph(replaced(n)) },
            onSave = { n ->
                val g = replaced(n)
                if (g.edges.size != graph.edges.size) msg("Removed ${graph.edges.size - g.edges.size} connection(s) to deleted ports")
                setGraph(g); editingNode = null
            },
            onDismiss = { editingNode = null },
        )
    }

    leaving?.let { go ->
        AlertDialog(
            onDismissRequest = { leaving = null }, title = { Text(if (isDraft) "Discard generated workflow?" else "Unsaved changes") },
            text = { Text(if (errors.isEmpty()) (if (isDraft) "This AI-generated workflow has not been saved." else "Save before leaving?") else "Workflow is invalid: ${errors.first()}") },
            confirmButton = {
                Row {
                    if (isDraft) TextButton(onClick = { leaving = null }, modifier = Modifier.semantics { contentDescription = "Keep editing" }) { Text("Keep editing") }
                    if (errors.isEmpty()) TextButton(onClick = { scope.launch { if (save()) { leaving = null; go() } } }) { Text("Save") }
                }
            },
            dismissButton = { TextButton(onClick = { leaving = null; if (isDraft) Drafts.map.remove(workflowId); go() }) { Text("Discard") } },
        )
    }
}
