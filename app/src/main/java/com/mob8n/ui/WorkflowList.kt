@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Note
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.ToggleOff
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.ai.WorkflowTools
import com.mob8n.core.Catalog
import com.mob8n.core.Graph
import com.mob8n.core.Hosting
import com.mob8n.core.NodeInstance
import com.mob8n.core.RunStatus
import com.mob8n.core.TRIGGER_MANUAL
import com.mob8n.core.Workflow
import com.mob8n.engine.Engine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

fun newWorkflow(): Workflow = Workflow(
    id = UUID.randomUUID().toString(), name = "Untitled",
    graph = Graph(nodes = listOf(NodeInstance(id = UUID.randomUUID().toString(), type = TRIGGER_MANUAL, name = "Manual", x = 40f, y = 40f))),
    updatedAt = System.currentTimeMillis(),
)

/** Last-run pill tone (DESIGN6 §6.5): the words carry the meaning, the tone underlines it. */
fun runTone(s: RunStatus?): Tone = when (s) {
    RunStatus.RUNNING -> Tone.Primary
    RunStatus.SUCCESS -> Tone.Positive
    RunStatus.FAILED -> Tone.Danger
    RunStatus.SUSPENDED -> Tone.Caution
    RunStatus.CANCELLED, null -> Tone.Neutral
}

@Composable
fun WorkflowListScreen(engine: Engine, catalog: Catalog, selectedId: String?, onOpen: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val tick = rememberResumeTick()
    val loadedList by engine.workflows().collectAsStateWithLifecycle(null)
    val workflows = loadedList ?: emptyList()
    val host by engine.hostStatus.collectAsStateWithLifecycle()
    val census = rememberGateCensus(workflows, catalog, tick)
    val missing = census.missing
    var deleteWf by remember { mutableStateOf<Workflow?>(null) }
    var fabMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    // First data frame staggers in (enterOnce); later inserts/moves use animateItem. Saveable: Back does not replay the stagger.
    var firstLoad by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(workflows.isNotEmpty()) { if (workflows.isNotEmpty()) firstLoad = false }
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    fun msg(s: String) = scope.launch { snack.showSnackbar(s) }
    fun runNow(wf: Workflow) = scope.launch {
        val id = runCatching { engine.runManual(wf.id) }.getOrElse { snack.showSnackbar("Run failed: ${it.message}"); return@launch }
        if (id == null) snack.showSnackbar("No Manual trigger in ${wf.name}") else snack.showSnackbar("Run started")
    }
    fun setEnabled(wf: Workflow, on: Boolean) = scope.launch {
        runCatching { engine.setEnabled(wf.id, on) }.onFailure { snack.showSnackbar("Could not ${if (on) "enable" else "disable"}: ${it.message}") }
    }

    Scaffold(
        modifier = modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            MahoutTopBar("Mahout", subtitle = loadedList?.let { l -> "${l.size} workflow${if (l.size == 1) "" else "s"} · ${l.count { it.enabled }} on" }, scrollBehavior = bar, actions = {
                IconButton(onClick = { onOpen(Screen.Runs()) }) { Icon(Icons.Rounded.History, contentDescription = "Runs") }
                IconButton(onClick = { onOpen(Screen.Knowledge) }) { Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = "Knowledge") }
                IconButton(onClick = { onOpen(Screen.Permissions) }) {
                    BadgedBox(badge = { if (missing > 0) Badge { Text("$missing") } }) { Icon(Icons.Rounded.Security, contentDescription = "Permissions${if (missing > 0) ", $missing missing" else ""}") }
                }
                IconButton(onClick = { onOpen(Screen.AiSettings) }) { Icon(Icons.Rounded.SmartToy, contentDescription = "AI settings") }
                // DESIGN3 §6.4: six icons crowd a 360 dp phone, so Notes and Playlist live in the overflow.
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Notes") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Note, contentDescription = null) }, onClick = { moreMenu = false; onOpen(Screen.Notes) },
                            modifier = Modifier.semantics { contentDescription = "Notes" })
                        DropdownMenuItem(text = { Text("Playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) }, onClick = { moreMenu = false; onOpen(Screen.Playlist) },
                            modifier = Modifier.semantics { contentDescription = "Playlist" })
                    }
                }
            })
        },
        snackbarHost = { MahoutSnackbarHost(snack) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Rounded.Add, contentDescription = "Create workflow") }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text("New workflow") }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) }, onClick = {
                        fabMenu = false
                        scope.launch {
                            val wf = newWorkflow()
                            runCatching { engine.save(wf) }.onSuccess { onOpen(Screen.Editor(wf.id)) }.onFailure { snack.showSnackbar("Could not create: ${it.message}") }
                        }
                    }, modifier = Modifier.semantics { contentDescription = "New workflow" })
                    DropdownMenuItem(text = { Text("Build with AI") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) }, onClick = { fabMenu = false; onOpen(Screen.Build()) },
                        modifier = Modifier.semantics { contentDescription = "Build with AI" })
                }
            }
        },
    ) { pad ->
        val gutter = pageGutter()
        if (loadedList != null && workflows.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.AccountTree, "No workflows yet", "Tap + to create one, or describe it and let Build with AI draft it.",
                action = { PillButton("Build with AI", onClick = { onOpen(Screen.Build()) }, icon = Icons.Rounded.AutoAwesome, tone = Tone.Neutral) })
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.s, gutter, 88.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            itemsIndexed(workflows, key = { _, wf -> wf.id }, contentType = { _, _ -> "workflow" }) { index, wf ->
                val motion = LocalMotion.current
                val triggers = remember(wf.graph) { wf.graph.triggers(catalog) }
                val needsHost = remember(wf.graph, host) {
                    wf.enabled && !host.listenerGranted && !host.serviceRunning &&
                        triggers.any { catalog.trigger(it.type)?.hosting.let { h -> h == Hosting.RUNTIME_RECEIVER || h == Hosting.HOST_ATTACHED } }
                }
                val needsPerm = wf.id in census.workflowIds
                val exposed = remember(wf.graph) { WorkflowTools.exposed(wf) }   // DESIGN4 §8.1: callable by the chat operator / ai.agent
                var menu by remember { mutableStateOf(false) }
                val selected = wf.id == selectedId
                val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, motion.effect(), label = "selected")
                SwipeRow(
                    wf, enabled = !menu, onRun = { runNow(it) }, onToggle = { setEnabled(it, !it.enabled) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = if (motion.reduced) null else motion.effect(),
                        placementSpec = if (motion.reduced) null else motion.spatial(),
                        fadeOutSpec = if (motion.reduced) null else motion.exitEffect(),
                    ).enterOnce(index, firstLoad),
                ) {
                    SectionCard(
                        Modifier.fillMaxWidth().border(2.dp, ring, RoundedCornerShape(Radius.m)).semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Run now") { runNow(wf); true },
                                CustomAccessibilityAction(if (wf.enabled) "Disable" else "Enable") { setEnabled(wf, !wf.enabled); true },
                            )
                        },
                        description = "Workflow ${wf.name}", onClick = { onOpen(Screen.Editor(wf.id)) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(wf.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                            Switch(checked = wf.enabled, onCheckedChange = { on -> setEnabled(wf, on) },
                                thumbContent = { Icon(if (wf.enabled) Icons.Rounded.Check else Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) },
                                modifier = Modifier.semantics { contentDescription = "${if (wf.enabled) "Disable" else "Enable"} ${wf.name}" })
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${wf.name}") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Run now") }, onClick = { menu = false; runNow(wf) })
                                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                                        menu = false
                                        scope.launch {
                                            val copy = wf.copy(id = UUID.randomUUID().toString(), name = "${wf.name} copy", enabled = false, updatedAt = System.currentTimeMillis(), lastRunStatus = null, lastRunAt = null)
                                            runCatching { engine.save(copy) }.onFailure { snack.showSnackbar("Duplicate failed: ${it.message}") }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Runs") }, onClick = { menu = false; onOpen(Screen.Runs(wf.id)) })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleteWf = wf })
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            ClickPill(
                                wf.lastRunStatus?.name?.lowercase()?.replaceFirstChar { it.uppercase() }?.let { "$it · ${fmtTime(wf.lastRunAt)}" } ?: "Never run",
                                runTone(wf.lastRunStatus), "Last run ${wf.lastRunStatus?.name ?: "never"}", pulsing = wf.lastRunStatus == RunStatus.RUNNING,
                            ) { onOpen(Screen.Runs(wf.id)) }
                            for (t in triggers) {
                                val s = catalog.spec(t.type)
                                Icon(nodeIcon(t.type, com.mob8n.core.NodeKind.TRIGGER), contentDescription = s?.name ?: t.type, tint = kindColor(com.mob8n.core.NodeKind.TRIGGER), modifier = Modifier.align(Alignment.CenterVertically).size(20.dp))
                            }
                            if (needsHost) ClickPill("needs host", Tone.Caution, "Needs background host") { onOpen(Screen.Permissions) }
                            if (needsPerm) ClickPill("needs permission", Tone.Danger, "Needs permission") { onOpen(Screen.Permissions) }
                            if (exposed != null) ClickPill("tool", Tone.Info, "Exposed as AI tool ${WorkflowTools.toolName(wf.name, wf.id)}") { onOpen(Screen.Editor(wf.id, exposed.calledNodeId)) }
                        }
                    }
                }
            }
        }
    }

    deleteWf?.let { wf ->
        AlertDialog(
            onDismissRequest = { deleteWf = null }, title = { Text("Delete ${wf.name}?") }, text = { Text("Runs and logs of this workflow are removed too.") },
            confirmButton = { TextButton(onClick = { deleteWf = null; scope.launch { runCatching { engine.delete(wf.id) }.onFailure { msg("Delete failed: ${it.message}") } } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteWf = null }) { Text("Cancel") } },
        )
    }
}

/** A StatusPill that is also a 48 dp button (the v4 chips opened Runs / Permissions / the exposing node). */
@Composable
private fun ClickPill(text: String, tone: Tone, description: String, pulsing: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description; role = Role.Button }, contentAlignment = Alignment.Center) {
        StatusPill(text, tone, pulsing = pulsing)
    }
}

/**
 * DESIGN6 §6.5 swipe actions: start->end = Run now, end->start = Enable/Disable. The action runs in confirmValueChange, which returns false
 * so the row snaps back; a LongPress haptic marks the threshold crossing; the background icon grows 0.8 -> 1 with the drag.
 */
@Composable
private fun SwipeRow(wf: Workflow, enabled: Boolean, onRun: (Workflow) -> Unit, onToggle: (Workflow) -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(wf)
    val haptic = LocalHapticFeedback.current
    val last = remember { longArrayOf(0L) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            // ponytail: time guard in case the sheet re-confirms one release; upgrade = none needed if M3 guarantees one call
            val now = System.currentTimeMillis()
            if (v != SwipeToDismissBoxValue.Settled && now - last[0] > 500) {
                last[0] = now
                if (v == SwipeToDismissBoxValue.StartToEnd) onRun(current) else onToggle(current)
            }
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    LaunchedEffect(state) {
        snapshotFlow { state.targetValue }.drop(1).collect { if (it != SwipeToDismissBoxValue.Settled) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    var width by remember { mutableIntStateOf(1) }
    SwipeToDismissBox(
        state, modifier = modifier.onSizeChanged { width = it.width.coerceAtLeast(1) },
        enableDismissFromStartToEnd = enabled, enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val run = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val idle = state.dismissDirection == SwipeToDismissBoxValue.Settled
            val cs = MaterialTheme.colorScheme
            val bg = if (idle) Color.Transparent else if (run) cs.primary else cs.secondaryContainer
            val fg = if (run) cs.onPrimary else cs.onSecondaryContainer
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.m)).background(bg).padding(horizontal = Space.xl),
                horizontalArrangement = if (run) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!idle) {
                    Icon(if (run) Icons.Rounded.PlayArrow else if (current.enabled) Icons.Rounded.ToggleOff else Icons.Rounded.ToggleOn, contentDescription = null, tint = fg,
                        modifier = Modifier.graphicsLayer {
                            val p = (abs(runCatching { state.requireOffset() }.getOrDefault(0f)) / (width * 0.35f)).coerceIn(0f, 1f)
                            scaleX = 0.8f + 0.2f * p; scaleY = scaleX
                        })
                    Spacer(Modifier.width(Space.s))
                    Text(if (run) "Run" else if (current.enabled) "Disable" else "Enable", color = fg, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { content() }
}

