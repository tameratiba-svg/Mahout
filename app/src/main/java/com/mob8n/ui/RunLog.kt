@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.core.NodeLog
import com.mob8n.core.NodeStatus
import com.mob8n.core.RunRecord
import com.mob8n.core.RunStatus
import com.mob8n.engine.Engine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val PRETTY = Json { prettyPrint = true }
fun prettyJson(e: JsonElement): String = PRETTY.encodeToString(JsonElement.serializer(), e)

/** Node-log tone for its status pill; the stripe uses the fixed signal colour (DESIGN6 §2.3). */
private fun nodeTone(s: NodeStatus): Tone = when (s) {
    NodeStatus.SUCCESS -> Tone.Positive
    NodeStatus.FAILED, NodeStatus.TIMEOUT -> Tone.Danger
    NodeStatus.ERROR_ROUTED, NodeStatus.SUSPENDED -> Tone.Caution
    NodeStatus.SKIPPED -> Tone.Neutral
}

private fun statusWord(s: RunStatus): String = s.name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
fun RunsScreen(workflowId: String?, engine: Engine, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val loaded by remember(workflowId) { engine.runs(workflowId) }.collectAsStateWithLifecycle(null)
    val runs = loaded ?: emptyList()
    val suspended by remember { engine.suspendedRuns() }.collectAsStateWithLifecycle(emptyList())
    val suspendedById = remember(suspended) { suspended.associateBy { it.runId } }
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    val gutter = pageGutter()
    Scaffold(
        Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = { MahoutTopBar(if (workflowId == null) "Runs" else runs.firstOrNull()?.workflowName?.let { "Runs · $it" } ?: "Runs", onBack = onBack, scrollBehavior = bar) },
        snackbarHost = { MahoutSnackbarHost(snack) },
    ) { pad ->
        if (loaded != null && runs.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.History, "No runs yet", "Runs appear here when a trigger fires or you tap Run now.")
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.m, gutter, Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(runs, key = { it.runId }, contentType = { "run" }) { r ->
                SectionCard(Modifier.fillMaxWidth(), description = "Run of ${r.workflowName}, ${r.status.name.lowercase()}", onClick = { onOpen(Screen.RunDetail(r.runId)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.workflowName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        StatusPill(statusWord(r.status), runTone(r.status), pulsing = r.status == RunStatus.RUNNING)
                    }
                    Text("${r.triggerType} · ${fmtTime(r.startedAt)} · ${fmtDuration(r.endedAt?.minus(r.startedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                    if (r.status == RunStatus.SUSPENDED) {
                        val s = suspendedById[r.runId]
                        Text(s?.let { "${it.title}${if (it.text.isNotBlank()) " — ${it.text}" else ""}" } ?: "Waiting", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            for (c in s?.choices ?: listOf(com.mob8n.core.DECISION_APPROVE, com.mob8n.core.DECISION_DENY)) {
                                PillButton("Resume: $c", onClick = {
                                    scope.launch { runCatching { engine.resume(r.runId, c) }.onFailure { snack.showSnackbar("Resume failed: ${it.message}") } }
                                }, tone = Tone.Neutral, contentDescription = "Resume with $c")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RunDetailScreen(runId: String, engine: Engine, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val logs by remember(runId) { engine.nodeLogs(runId) }.collectAsStateWithLifecycle(emptyList())
    val runState by remember(runId) { engine.run(runId) }.collectAsStateWithLifecycle(null)   // F41: one row, never falls off the newest-200 window
    val run: RunRecord? = runState
    val sorted = remember(logs) { logs.sortedBy { it.seq } }
    val gutter = pageGutter()
    Scaffold(topBar = {
        MahoutTopBar(run?.workflowName ?: "Run", onBack = onBack, actions = {
            if (run != null) IconButton(onClick = { onOpen(Screen.Editor(run.workflowId)) }) { Icon(Icons.Rounded.Edit, contentDescription = "Open workflow in editor") }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.m, gutter, Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            item(key = "header", contentType = "header") {
                if (run != null) Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        StatusPill(statusWord(run.status), runTone(run.status), pulsing = run.status == RunStatus.RUNNING)
                        Text(run.triggerType, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Started ${fmtTime(run.startedAt)} · ${fmtDuration(run.endedAt?.minus(run.startedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else Text("Run $runId", style = MaterialTheme.typography.bodySmall)
            }
            if (logs.isEmpty()) item(key = "empty") { Text("No node logs (yet).", Modifier.padding(top = Space.l)) }
            // Device crash fix: a node that suspends (Agent approval, wait_approval) logs two rows with the same seq+nodeId (SUSPENDED, then the outcome);
            // LazyColumn keys must be unique, so status+timestamp join the key.
            items(sorted, key = { "${it.seq}-${it.nodeId}-${it.status}-${it.at}" }, contentType = { "log" }) { log -> NodeLogCard(log, run?.workflowId?.let { wid -> { onOpen(Screen.Editor(wid, log.nodeId)) } }) }
        }
    }
}

/** DESIGN6 §6.7: SectionCard with a status stripe (signal colour, drawn behind: no intrinsic measurement), JSON in MonoBlock. */
@Composable
private fun NodeLogCard(log: NodeLog, onJump: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val motion = LocalMotion.current
    val turn by animateFloatAsState(if (open) 180f else 0f, motion.spatialFast(), label = "chevron")
    val stripe = nodeStatusColor(log.status)
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.drawBehind {
            val w = 4.dp.toPx()
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
            drawRoundRect(stripe, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
        }.padding(start = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Collapse" else "Expand") { open = !open }) {
                Column(Modifier.weight(1f)) {
                    Text("${log.seq + 1}. ${log.nodeName}", style = MaterialTheme.typography.titleSmall)
                    Text("${log.nodeType} · ${fmtDuration(log.durationMs)}", style = CodeSmallStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(log.status.name.lowercase().replace('_', ' '), nodeTone(log.status))
                if (onJump != null) IconButton(onClick = onJump) { Icon(Icons.Rounded.Edit, contentDescription = "Open ${log.nodeName} in editor") }
                Icon(Icons.Rounded.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            log.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs)) }
            ExpandableSection(open) {
                Column {
                    JsonBlock("Input (${log.input.size})", log.input)
                    for ((port, items) in log.output) JsonBlock("Output · $port", items)
                }
            }
        }
    }
}

@Composable
private fun JsonBlock(title: String, e: JsonElement) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Space.s, bottom = Space.xs))
    val text = remember(e) { prettyJson(e) }
    MonoBlock(text, Modifier.fillMaxWidth(), language = "JSON", maxLines = 40)
}
