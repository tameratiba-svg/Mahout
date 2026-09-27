@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.Mob8NApp
import com.mob8n.ai.HarnessPrefs
import com.mob8n.ai.McpPrefs
import com.mob8n.ai.PanelPrefs
import com.mob8n.ai.PermissionMode
import com.mob8n.apps.JsRuntime
import com.mob8n.apps.Shell
import com.mob8n.apps.Workspace
import com.mob8n.core.Catalog
import com.mob8n.core.RunStatus
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.Workflow
import com.mob8n.engine.Engine
import com.mob8n.engine.RunCounts
import com.mob8n.engine.Stats
import com.mob8n.engine.UsageRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

// ---- pure helpers (DashboardMathTest) ----

/** Sparkline heights in [0, 1] normalised to max(1, max): an all-zero series is a flat baseline, never NaN. */
fun sparklineNorm(values: List<Int>): List<Float> {
    val max = maxOf(1, values.maxOrNull() ?: 0).toFloat()
    return values.map { it.coerceAtLeast(0) / max }
}

/** "minimax · MiniMax-M2.7 — 3 calls · in 12.3k · out 4.1k · cached 8.0k · $0.12" (or "price unknown"; "≈" when estimated). */
fun usageLine(r: UsageRow): String {
    val cost = if (r.costUsd == null || r.unknownCost == r.calls) "price unknown" else (if (r.unknownCost > 0) "≥ " else "") + Stats.fmtUsd(r.costUsd)
    val approx = if (r.estimated) "≈ " else ""
    return "${r.provider} · ${r.model} — ${r.calls} call${if (r.calls == 1) "" else "s"} · ${approx}in ${Stats.fmtTokens(r.inTok)} · out ${Stats.fmtTokens(r.outTok)}" +
        (if (r.cachedTok > 0) " · cached ${Stats.fmtTokens(r.cachedTok)}" else "") + " · $cost"
}

/** M2: runs of workflows that no longer exist, one muted line (tap -> All runs); null when there are none. */
fun deletedWorkflowsLine(runs: Int): String? = if (runs <= 0) null else "Deleted workflows · $runs run${if (runs == 1) "" else "s"}"

fun countsLine(c: RunCounts): String = "${c.success} succeeded · ${c.failed} failed · ${c.suspended} waiting" + (if (c.running > 0) " · ${c.running} running" else "")

private const val DAY_MS = 86_400_000L
private val RANGES = listOf("today", "7d", "30d")

/**
 * Dashboard (DESIGN4 §6 + DESIGN4P §4.4 + DESIGN5 Panels): eleven cards in an adaptive grid, values from Engine flows aggregated by the pure `Stats`; never queries Room directly.
 * Every card is one semantics node whose contentDescription states its numbers (values are text, never colour-only).
 * v6 (DESIGN6 §6.6): pull-to-refresh, rolling counters, sparkline draw-in, skeletons while loading, first-load stagger.
 */
@Composable
fun DashboardScreen(engine: Engine, catalog: Catalog, onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var refreshKey by rememberSaveable { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val now = remember(tick, refreshKey) { System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }
    val runs by remember(now) { engine.runsSince(now - 30 * DAY_MS) }.collectAsStateWithLifecycle(emptyList())
    val workflowsOrNull by engine.workflows().collectAsStateWithLifecycle<List<Workflow>?>(null)   // null = not loaded yet: no "deleted" line from an empty first value
    val workflows = workflowsOrNull.orEmpty()
    val usage by remember(now) { engine.aiUsageSince(now - 30 * DAY_MS) }.collectAsStateWithLifecycle(emptyList())
    val stats = remember(runs, workflows, usage, now) { Stats.build(runs, workflows, usage, now, zone) }
    val host by engine.hostStatus.collectAsStateWithLifecycle()
    val convs by engine.conversations().collectAsStateWithLifecycle(emptyList())
    val jsStatus by JsRuntime.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { McpPrefs.load(ctx); HarnessPrefs.load(ctx); PanelPrefs.load(ctx) }
    val mcp by McpPrefs.servers.collectAsStateWithLifecycle()
    val panels by PanelPrefs.panels.collectAsStateWithLifecycle()
    val mode by HarnessPrefs.mode.collectAsStateWithLifecycle()
    val bypassUntil by HarnessPrefs.bypassUntil.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var stopping by remember { mutableStateOf(false) }
    // A pull clears the two slow values (skeletons show) and recomputes them; a resume keeps the old values until the new ones land.
    val knowledge by produceState<String?>(null, tick, refreshKey) { if (refreshing) value = null; value = runCatching { usageText(engine.knowledge.usage()) }.getOrNull() ?: "unavailable" }
    val storage by produceState<Triple<Long, Pair<Int, Long>, String>?>(null, tick, refreshKey) {
        if (refreshing) value = null
        value = withContext(Dispatchers.IO) { runCatching { Triple(engine.dbBytes(), Workspace.size(Workspace.root(ctx)), Workspace.root(ctx).path) }.getOrNull() }
    }
    LaunchedEffect(refreshKey) {
        if (!refreshing) return@LaunchedEffect
        delay(450)   // min visible time: a sub-frame refresh must still read as "done"
        withTimeoutOrNull(10_000) { snapshotFlow { knowledge != null && storage != null }.first { it } }
        refreshing = false
    }
    // M7: file / WebView-provider / gate checks are disk or binder calls: off the main thread, never in the first-compose frame of the destination.
    val shell by produceState("…") { value = withContext(Dispatchers.IO) { runCatching { Shell.status() }.getOrElse { "unknown" } } }
    val jsLine by produceState("…", jsStatus) { value = withContext(Dispatchers.IO) { runCatching { JsRuntime.statusLine(ctx) }.getOrElse { "unknown" } } }
    val missing = rememberGateCensus(workflows, catalog, tick).missing
    val prefs = remember { ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var usageRange by rememberSaveable { mutableStateOf(prefs.getString("dashboard_range", "7d")!!.takeIf { it in RANGES } ?: "7d") }
    var runsWeek by rememberSaveable { mutableStateOf(false) }
    var firstLoad by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { firstLoad = false }
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    val gutter = pageGutter()

    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), snackbarHost = { MahoutSnackbarHost(snack) }, topBar = {
        MahoutTopBar("Dashboard", scrollBehavior = bar, actions = {
            IconButton(onClick = { onOpen(Screen.Permissions) }) {
                BadgedBox(badge = { if (missing > 0) Badge { Text("$missing") } }) { Icon(Icons.Rounded.Security, contentDescription = "Permissions${if (missing > 0) ", $missing missing" else ""}") }
            }
            IconButton(onClick = { onOpen(Screen.AiSettings) }) { Icon(Icons.Rounded.SmartToy, contentDescription = "AI settings") }
        })
    }) { pad ->
      PullToRefreshBox(refreshing, onRefresh = { refreshing = true; refreshKey++ }, Modifier.fillMaxSize().padding(pad)) {
        LazyVerticalGrid(GridCells.Adaptive(minSize = 340.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(gutter, Space.m, gutter, Space.xl),
            horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            // 1. Runs
            item(key = "runs") {
                val c = if (runsWeek) stats.week else stats.today
                DashCard("Runs", "Runs ${if (runsWeek) "last 7 days" else "today"}: ${countsLine(c)}", 0, firstLoad, onClick = { onOpen(Screen.Runs()) }) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !runsWeek, onClick = { runsWeek = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Today") }
                        SegmentedButton(selected = runsWeek, onClick = { runsWeek = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("7 days") }
                    }
                    AnimatedCounter(c.total.toLong(), Modifier.padding(top = Space.s), format = { "$it run${if (it == 1L) "" else "s"}" })
                    Text(countsLine(c), style = MaterialTheme.typography.bodyMedium)
                    if (stats.perDay.all { it == 0 }) Text("No runs yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.s))
                    else {
                        AnimatedSparkline(stats.perDay, stats.perDayFailed, "Runs per day: ${stats.perDay.joinToString(", ")}; failures: ${stats.perDayFailed.joinToString(", ")}", Modifier.fillMaxWidth().padding(top = Space.s))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("7 days ago", style = MaterialTheme.typography.labelSmall); Text("today", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            // 2. Workflows
            item(key = "workflows") {
                val top = stats.workflows.take(10)
                val deleted = if (workflowsOrNull == null) 0 else stats.deletedRuns
                DashCard("Workflows", "Workflows: ${top.size} shown" + deletedWorkflowsLine(deleted)?.let { "; $it" }.orEmpty(), 1, firstLoad, onClick = { onOpen(Screen.Runs()) }) {
                    // only once workflows have loaded, and never above a "Deleted workflows" line (those runs exist)
                    if (top.isEmpty() && deleted == 0 && workflowsOrNull != null) Text("No runs yet", style = MaterialTheme.typography.bodySmall)
                    for (w in top) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(Screen.Runs(w.id)) }.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text("${w.runs} runs · avg ${fmtDuration(w.avgMs)} · last ${fmtTime(w.lastRunAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (w.failures > 0) "${w.failures} failed" else (w.lastStatus?.name?.lowercase() ?: "—"), style = MaterialTheme.typography.labelMedium,
                            color = if (w.failures > 0) MaterialTheme.colorScheme.error else if (w.lastStatus == RunStatus.SUCCESS) MaterialTheme.mahout.success else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    deletedWorkflowsLine(deleted)?.let { l ->
                        Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(Screen.Runs()) }.wrapContentHeight(Alignment.CenterVertically))
                    }
                    TextButton(onClick = { onOpen(Screen.Runs()) }) { Text("All runs") }
                }
            }
            // 3. AI usage
            item(key = "usage") {
                val rows = when (usageRange) { "today" -> stats.usageToday; "30d" -> stats.usage30d; else -> stats.usage7d }
                val total = rows.sumOf { it.inTok + it.outTok }
                val calls = rows.sumOf { it.calls }
                val cost = rows.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()
                val approx = rows.any { it.estimated }
                DashCard("AI usage", "AI usage $usageRange: $calls calls, ${Stats.fmtTokens(total)} tokens, ${Stats.fmtUsd(cost)}", 2, firstLoad) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RANGES.forEachIndexed { i, r ->
                            SegmentedButton(selected = usageRange == r, onClick = { usageRange = r; prefs.edit().putString("dashboard_range", r).apply() }, shape = SegmentedButtonDefaults.itemShape(i, RANGES.size)) {
                                Text(when (r) { "today" -> "Today"; "7d" -> "7 days"; else -> "30 days" })
                            }
                        }
                    }
                    if (rows.isEmpty()) Text("No AI calls yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.s))
                    else FlowRow(Modifier.padding(top = Space.s)) {
                        val head = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum")   // tabular: rolling digits never jitter
                        AnimatedCounter(calls.toLong(), style = head, format = { "$it call${if (it == 1L) "" else "s"}" })
                        Text(" · ${if (approx) "≈ " else ""}", style = head)
                        AnimatedCounter(total, style = head, format = { "${Stats.fmtTokens(it)} tokens" })
                        Text(" · ${if (cost == null) "price unknown" else Stats.fmtUsd(cost)}", style = head)
                    }
                    for (r in rows) Text(usageLine(r), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
                    if (stats.bySource.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.padding(top = Space.s)) {
                        for ((src, n) in stats.bySource.entries.sortedByDescending { it.value }) StatusPill("$src $n", Tone.Info, Modifier.semantics { contentDescription = "$n calls from $src" }, dot = false)
                    }
                    Text("Static price table; unknown models show tokens only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
                }
            }
            // 4. Host
            item(key = "host") {
                val lines = listOf(
                    "Notification listener: ${if (!host.listenerGranted) "not granted" else if (host.listenerConnected) "connected" else "granted, not bound"}",
                    "Background service: ${if (host.serviceRunning) "running" else "stopped"}",
                    "Runtime triggers in use: ${if (host.runtimeTriggersInUse) "yes" else "no"}",
                    "Active holds: ${engine.holds}",
                )
                DashCard("Host", "Host: " + lines.joinToString("; "), 3, firstLoad, onClick = { onOpen(Screen.Permissions) }) { for (l in lines) Text(l, style = MaterialTheme.typography.bodyMedium) }
            }
            // 5. Knowledge
            item(key = "knowledge") {
                DashCard("Knowledge", "Knowledge: ${knowledge ?: "loading"}", 4, firstLoad, onClick = { onOpen(Screen.Knowledge) }) {
                    val k = knowledge
                    if (k == null) SkeletonLines(1) else Text(k, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // 6. MCP servers
            item(key = "mcp") {
                val line = "${mcp.size} configured · ${mcp.count { it.enabled }} enabled · ${mcp.count { it.trusted }} trusted"
                DashCard("MCP servers", "MCP servers: $line", 5, firstLoad, onClick = { onOpen(Screen.McpSettings) }) { Text(if (mcp.isEmpty()) "No servers configured" else line, style = MaterialTheme.typography.bodyMedium) }
            }
            // 6b. Panels (DESIGN5 §8.3): live web pages / camera frames on the LAN.
            item(key = "panels") {
                DashCard("Panels", "Panels: ${panels.size} configured", 6, firstLoad, onClick = { onOpen(Screen.Panels()) }) {
                    Text(if (panels.isEmpty()) "No panels yet — add a web page or a camera feed" else "${panels.size} configured · " + panels.joinToString(", ") { it.title },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            // 7. Storage
            item(key = "storage") {
                val st = storage
                val lines = listOfNotNull(
                    st?.let { "Database: ${fmtBytes(it.first)}" },
                    st?.let { "Workspace: ${it.second.first} files · ${fmtBytes(it.second.second)}" },
                    "Runs kept: ${runs.size} in 30 days (500 cap)",
                    "Conversations: ${convs.size}",
                )
                DashCard("Storage", "Storage: " + lines.joinToString("; "), 7, firstLoad) {
                    if (st == null) SkeletonLines(1)
                    for (l in lines) Text(l, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // 8. Coding runtime
            item(key = "coding") {
                DashCard("Coding runtime", "Coding runtime: shell $shell; JavaScript $jsLine, $jsStatus", 8, firstLoad, onClick = { onOpen(Screen.Knowledge) }) {
                    Text("Shell: $shell", style = MaterialTheme.typography.bodyMedium)
                    Text("JavaScript: $jsLine · $jsStatus", style = MaterialTheme.typography.bodyMedium)
                    Text("Workspace: ${storage?.third ?: "…"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
                    Text(Workspace.VISIBILITY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onOpen(Screen.Knowledge) }) { Text("Files") }
                }
            }
            // 9. Chat
            item(key = "chat") {
                val awaiting = convs.count { it.status == "awaiting" }
                DashCard("Chat", "Chat: ${convs.size} conversations, $awaiting awaiting approval", 9, firstLoad, onClick = { onOpen(Screen.Chat()) }) {
                    Text("${convs.size} conversation${if (convs.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                    if (awaiting > 0) StatusPill("$awaiting awaiting your approval", Tone.Caution, Modifier.padding(top = Space.xs))
                    else Text("Nothing waiting for approval", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 10. Permissions (DESIGN4P §4.4): global mode, Bypass status, the kill switch. Danger tone while any Bypass is live (DESIGN6 §6.6).
            item(key = "permissions") {
                val nowMs = System.currentTimeMillis()
                val inBypass = conversationsInBypass(convs, nowMs)
                val activeUntil = bypassActiveUntil(bypassUntil, convs, nowMs)
                val lines = listOfNotNull(
                    if (activeUntil > 0) "Bypass active — ${bypassMinutesLeft(activeUntil, nowMs)} min left" else null,
                    if (mode == PermissionMode.BYPASS && bypassUntil > nowMs) null else "Mode: ${modeStatus(mode, bypassUntil, nowMs)}", if (inBypass > 0) "$inBypass chat${if (inBypass == 1) "" else "s"} in Bypass" else null,
                )
                DashCard("Permissions", "Permissions: " + lines.joinToString("; "), 10, firstLoad, tone = if (activeUntil > 0) Tone.Danger else Tone.Neutral) {
                    if (activeUntil <= 0) StatusPill(modeLabel(mode), modeTone(mode))
                    for (l in lines) Text(l, style = MaterialTheme.typography.bodyMedium, color = if ("Bypass" in l && "expired" !in l && activeUntil <= 0) MaterialTheme.colorScheme.error else Color.Unspecified)
                    Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpen(Screen.AiSettings) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Change the permission mode" }) { Text("Change") }
                        Spacer(Modifier.weight(1f))
                        PillButton("Stop everything", onClick = { stopping = true }, tone = Tone.Danger, contentDescription = "Stop everything: cancel all chats and runs, turn Bypass off")
                    }
                    Text("Cancels every chat turn and workflow run and turns Bypass off, in every mode.", style = MaterialTheme.typography.bodySmall,
                        color = if (activeUntil > 0) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
      }
    }
    if (stopping) StopEverythingDialog(onDismiss = { stopping = false }, onConfirm = { stopping = false; scope.launch { snack.showSnackbar(stopEverything(Mob8NApp.of(ctx))) } })
}

/** Mode pill tone (DESIGN6 §5.3.10): Plan Neutral · Ask Info · Auto Caution · Bypass Danger. */
fun modeTone(m: PermissionMode?): Tone = when (m) {
    PermissionMode.AUTO -> Tone.Caution
    PermissionMode.BYPASS -> Tone.Danger
    PermissionMode.ASK -> Tone.Info
    PermissionMode.PLAN, null -> Tone.Neutral
}

@Composable
private fun DashCard(title: String, description: String, index: Int, firstLoad: Boolean, onClick: (() -> Unit)? = null, tone: Tone = Tone.Neutral, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(Modifier.fillMaxWidth().enterOnce(index, firstLoad), title = title, description = description, onClick = onClick, tone = tone, content = content)
}
