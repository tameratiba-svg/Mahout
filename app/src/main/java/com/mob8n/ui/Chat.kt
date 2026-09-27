@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.mob8n.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.Mob8NApp
import com.mob8n.ai.ChatDrafts
import com.mob8n.ai.ChatPrefs
import com.mob8n.ai.ChatPrompt
import com.mob8n.ai.ChatRunner
import com.mob8n.ai.ChatSettings
import com.mob8n.ai.Decision
import com.mob8n.ai.HarnessPrefs
import com.mob8n.ai.LiveTool
import com.mob8n.ai.LiveTurn
import com.mob8n.ai.Llm
import com.mob8n.ai.LlmTarget
import com.mob8n.ai.McpPrefs
import com.mob8n.ai.McpServer
import com.mob8n.ai.OperatorTools
import com.mob8n.ai.PanelPrefs
import com.mob8n.ai.PendingCall
import com.mob8n.ai.PermissionMode
import com.mob8n.ai.Permissions
import com.mob8n.ai.Risk
import com.mob8n.ai.ToolUse
import com.mob8n.ai.Verdict
import com.mob8n.ai.streamVisible
import com.mob8n.core.Catalog
import com.mob8n.core.Gate
import com.mob8n.core.JSON
import com.mob8n.core.NodeKind
import com.mob8n.core.Workflow
import com.mob8n.core.asBool
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.ChatMessage
import com.mob8n.engine.Conversation
import com.mob8n.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

// ---- pure helpers over Claude-wire rows (DESIGN4 §3.2 ChatMessage.json = {role, content:[blocks]}) ----

private fun blocks(m: ChatMessage): List<JsonObject> = (m.json["content"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
private fun JsonObject.type() = this["type"].asTextOrNull()
private fun textBlocks(m: ChatMessage): String = blocks(m).filter { it.type() == "text" }.mapNotNull { it["text"].asTextOrNull() }.joinToString("\n").trim()
private fun toolUses(m: ChatMessage): List<ToolUse> = blocks(m).filter { it.type() == "tool_use" }.mapNotNull { b ->
    ToolUse(b["id"].asTextOrNull() ?: return@mapNotNull null, b["name"].asTextOrNull() ?: "?", (b["input"] as? JsonObject) ?: JsonObject(emptyMap()))
}
/** tool_result content as text: a string or an array of text blocks (images were replaced by a placeholder on write, V5). */
private fun resultText(b: JsonObject): String = when (val c = b["content"]) {
    is JsonArray -> c.filterIsInstance<JsonObject>().mapNotNull { it["text"].asTextOrNull() }.joinToString("\n")
    null -> ""
    else -> c.asTextOrNull().orEmpty()
}
/** tool_use.id -> (text, isError) across every row; built once per row list, never per recomposition. */
private fun resultsOf(rows: List<ChatMessage>): Map<String, Pair<String, Boolean>> {
    val out = HashMap<String, Pair<String, Boolean>>()
    for (r in rows) for (b in blocks(r)) if (b.type() == "tool_result") b["tool_use_id"].asTextOrNull()?.let { out[it] = resultText(b) to (b["is_error"].asTextOrNull() == "true") }
    return out
}
private fun isPlainUser(m: ChatMessage) = m.role == "user" && blocks(m).none { it.type() == "tool_result" }
/** tool_use blocks since the last plain user message (drives the "Save as skill?" chip, §5.6). */
internal fun toolCallsInLastTurn(rowsAsc: List<ChatMessage>): Int {
    val start = rowsAsc.indexOfLast(::isPlainUser)
    return rowsAsc.drop(start + 1).filter { it.role == "assistant" }.sumOf { toolUses(it).size }
}
/** Text of the newest plain user message (Retry / Edit & resend, DESIGN6 D12); null when there is none. */
internal fun lastUserText(rowsAsc: List<ChatMessage>): String? = rowsAsc.lastOrNull(::isPlainUser)?.let(::textBlocks)?.takeIf { it.isNotBlank() }
/** Assistant rows that open a turn (first assistant row after a plain user message): they carry the "Mahout · model" line. */
internal fun turnStarts(rowsAsc: List<ChatMessage>): Set<Long> {
    val out = HashSet<Long>(); var seen = false
    for (r in rowsAsc) { if (isPlainUser(r)) seen = false else if (r.role == "assistant") { if (!seen) out += r.id; seen = true } }
    return out
}
/** True when the current turn already has a persisted assistant row (the live turn then needs no header). */
internal fun assistantSinceLastUser(rowsAsc: List<ChatMessage>): Boolean = rowsAsc.drop(rowsAsc.indexOfLast(::isPlainUser) + 1).any { it.role == "assistant" }
private val DRAFT_ID = Regex("\"draftId\"\\s*:\\s*\"([^\"]+)\"")
/** The newest draftId a draft_workflow result returned in the last turn (drives the "Open draft" follow-up chip). */
internal fun lastTurnDraftId(rowsAsc: List<ChatMessage>): String? =
    rowsAsc.drop(rowsAsc.indexOfLast(::isPlainUser) + 1).flatMap { r -> blocks(r).filter { it.type() == "tool_result" }.map(::resultText) }
        .mapNotNull { DRAFT_ID.find(it)?.groupValues?.get(1) }.lastOrNull()
/** Unread items since the user left the bottom (`baseline` = item count then; null = at the bottom). */
internal fun unreadSince(baseline: Int?, now: Int): Int = if (baseline == null) 0 else (now - baseline).coerceAtLeast(0)
/** The top-bar subtitle hides at large font scales (the title keeps one line). */
internal fun subtitleVisible(fontScale: Float): Boolean = fontScale <= 1.5f
/** Composer max lines: 6, or 4 when fontScale > 1.5 (§5.4). */
internal fun composerMaxLines(fontScale: Float): Int = if (fontScale > 1.5f) 4 else 6
/** Mode chip text: "Plan" / "Ask" / "Auto" / "Bypass · 41m" / "Bypass expired". */
internal fun modeChipText(mode: PermissionMode, untilMs: Long, nowMs: Long): String = when {
    mode != PermissionMode.BYPASS -> modeLabel(mode)
    untilMs > nowMs -> "Bypass · ${bypassMinutesLeft(untilMs, nowMs)}m"
    else -> "Bypass expired"
}
internal fun modeTone(mode: PermissionMode, active: Boolean = true): Tone = when (mode) {
    PermissionMode.PLAN -> Tone.Neutral; PermissionMode.ASK -> Tone.Info; PermissionMode.AUTO -> Tone.Caution
    PermissionMode.BYPASS -> if (active) Tone.Danger else Tone.Info
}
/** Persisted tool card glyph: no result = pending; "denied by user" = denied; error = failed; else done. */
internal fun toolGlyph(result: Pair<String, Boolean>?): GlyphState = when {
    result == null -> GlyphState.Pending
    result.second && result.first.startsWith("denied by user") -> GlyphState.Denied
    result.second -> GlyphState.Failed
    else -> GlyphState.Done
}
internal fun liveGlyph(t: LiveTool): GlyphState = when (t.state) {
    LiveTool.State.FORMING, LiveTool.State.QUEUED -> GlyphState.Pending
    LiveTool.State.RUNNING -> GlyphState.Running
    LiveTool.State.DONE -> GlyphState.Done
    LiveTool.State.FAILED -> if (t.result?.startsWith("denied by user") == true) GlyphState.Denied else GlyphState.Failed
}
/** Visible text of a live turn (think tags stripped), segments separated by a blank line. */
internal fun liveText(t: LiveTurn?): String = t?.segments.orEmpty().map { streamVisible(it.text).trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

/** Conversation.pendingJson (array of tool_use blocks, each optionally `_decided: true|false`, DESIGN4P P17) -> ToolUse list + stored per-call decisions; lenient. */
internal fun parsePending(json: String?): Pair<List<ToolUse>, Map<String, Boolean>> = runCatching {
    val uses = ArrayList<ToolUse>(); val decided = LinkedHashMap<String, Boolean>()
    for (b in JSON.parseToJsonElement(json ?: return@runCatching uses to decided).jsonArray.filterIsInstance<JsonObject>()) {
        val id = b["id"].asTextOrNull() ?: continue
        uses += ToolUse(id, b["name"].asTextOrNull() ?: "?", (b["input"] as? JsonObject) ?: JsonObject(emptyMap()))
        b["_decided"].asBool()?.let { decided[id] = it }
    }
    uses to decided
}.getOrDefault(emptyList<ToolUse>() to emptyMap())
private fun kindOf(name: String, meta: JsonObject?, id: String, catalog: Catalog): String =
    (meta?.get("kind") as? JsonObject)?.get(id).asTextOrNull() ?: when {
        name.startsWith("mcp__") -> "mcp"; name.startsWith("workflow__") -> "workflow"; name == "knowledge_search" -> "knowledge"
        catalog.agentTools().any { it.spec.toolName == name } -> "node"; else -> "operator"
    }
private fun riskFor(name: String, kind: String, catalog: Catalog, servers: List<McpServer>): Risk {
    val node = if (kind == "node") catalog.agentTools().firstOrNull { it.spec.toolName == name }?.spec else null
    // ponytail: trusted-server match by lowercased name prefix (McpClient.sanitize lives in the ai lane); upgrade = kind "mcp_trusted" in meta
    val trusted = kind == "mcp" && servers.any { s -> s.trusted && name.startsWith("mcp__" + s.name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")) }
    return runCatching { OperatorTools.riskOf(name, kind, trusted, node?.id, isAction = node?.kind == NodeKind.ACTION) }.getOrDefault(Risk.ALWAYS)
}
/** The mode that decided an assistant row (meta.mode, DESIGN4P P8); v4 rows fall back to the conversation's / global mode. */
private fun rowMode(meta: JsonObject?, fallback: PermissionMode): PermissionMode = PermissionMode.parse(meta?.get("mode").asTextOrNull()) ?: fallback
/** Frozen `meta.gate[id]` ("coding/RUN") when present; otherwise recomputed from the catalog's risk under `mode` (v4 history). */
private fun decisionFor(tu: ToolUse, meta: JsonObject?, mode: PermissionMode, kind: String, catalog: Catalog, servers: List<McpServer>): Decision =
    Permissions.parseGate((meta?.get("gate") as? JsonObject)?.get(tu.id).asTextOrNull(), mode) ?: Permissions.decide(mode, riskFor(tu.name, kind, catalog, servers))
/** Tool-card chip: `<risk> · <verdict> (<mode>)`; a call that ASKED and has a result reads "approved" / "denied" — the decision the user took. */
internal fun cardChip(d: Decision, result: Pair<String, Boolean>?): String =
    if (d.verdict == Verdict.ASK && result != null) "${Permissions.riskLabel(d.risk)} · ${if (result.second && result.first.startsWith("denied by user")) "denied" else "approved"} (${d.mode.key})"
    else Permissions.chip(d)
/** Composer placeholder while a batch is pending. */
internal fun composerHint(undecided: Int, pending: Int): String = when {
    undecided > 0 -> "Decide the $undecided pending call${if (undecided == 1) "" else "s"} above first"
    pending > 0 -> "Waiting for the assistant…"
    else -> "Message the operator"
}
private const val CARD_CAP = 2 * 1024
internal fun cap(s: String) = if (s.length > CARD_CAP) s.take(CARD_CAP) + "…(${s.length - CARD_CAP} more chars)" else s

private val MENTIONS_SAVER = listSaver<List<Mention>, String>(
    save = { l -> l.map { "${it.kind.name}\u0000${it.name}\u0000${it.id.orEmpty()}" } },
    restore = { l -> l.mapNotNull { s -> s.split('\u0000').takeIf { it.size == 3 }?.let { p -> runCatching { Mention(MentionKind.valueOf(p[0]), p[1], p[2].ifEmpty { null }) }.getOrNull() } } },
)

// ---- history pane ----

/**
 * Conversation history (DESIGN4 §5.6, DESIGN6 §5.3.18): search over messages.text (300 ms debounce), FAB New chat, long-press Rename / Delete;
 * selection animates (container colour + a 3 dp start indicator), rows slide on reorder. `compact` = drawer: no FAB, "New chat" as the first row.
 */
@Composable
fun ChatListPane(engine: Engine, selectedId: String?, onOpen: (Screen) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ctx = LocalContext.current
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val convs by engine.conversations().collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }   // conversationId -> first matching snippet
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyMap(); return@LaunchedEffect }
        delay(300)
        hits = runCatching { engine.searchMessages(query, 50) }.getOrDefault(emptyList()).groupBy { it.conversationId }.mapValues { (_, l) -> l.first().text.take(120) }
    }
    val shown = if (query.isBlank()) convs else convs.filter { it.id in hits }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    fun newChat() {
        val id = runCatching { ChatRunner.newConversation(Mob8NApp.of(ctx)) }.getOrElse { scope.launch { snack.showSnackbar("Could not start a chat: ${it.message}") }; return }
        onOpen(Screen.Chat(id))
    }
    var fabIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fabIn = true }

    Scaffold(
        modifier = modifier,
        topBar = { MahoutTopBar("Chat") },
        snackbarHost = { MahoutSnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        floatingActionButton = {
            if (!compact) AnimatedVisibility(fabIn, enter = motion.enter()) {
                FloatingActionButton(onClick = ::newChat) { Icon(Icons.Rounded.Add, contentDescription = "New chat") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad), contentPadding = PaddingValues(Space.m, Space.s, Space.m, if (compact) Space.l else 88.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (compact) item(key = "new") {
                Surface(onClick = ::newChat, shape = RoundedCornerShape(Radius.m), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "New chat" }) {
                    Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, contentDescription = null); Spacer(Modifier.width(Space.s)); Text("New chat", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(query, { query = it }, label = { Text("Search messages") }, singleLine = true, shape = RoundedCornerShape(Radius.s),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chat messages" })
            }
            if (convs.isEmpty()) item(key = "empty") {
                Text(if (compact) "No chats yet." else "No chats yet. Tap + to talk to the Mahout operator: it can list, run, build and fix your workflows.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
            }
            else if (shown.isEmpty()) item(key = "nohit") { Text("No messages match", style = MaterialTheme.typography.bodySmall) }
            items(shown, key = { it.id }, contentType = { "conv" }) { c ->
                ConversationRow(c, c.id == selectedId, hits[c.id], onOpen = { onOpen(Screen.Chat(c.id)) }, onMenu = { menuFor = c },
                    modifier = Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect())) {
                    DropdownMenu(expanded = menuFor?.id == c.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; renaming = c }, modifier = Modifier.semantics { contentDescription = "Rename ${c.title}" })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; deleting = c }, modifier = Modifier.semantics { contentDescription = "Delete ${c.title}" })
                    }
                }
            }
        }
    }
    renaming?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename chat") },
            text = { OutlinedTextField(title, { title = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Chat title" }) },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { renaming = null; scope.launch { runCatching { engine.saveConversation(c.copy(title = title.trim())) }.onFailure { snack.showSnackbar("Rename failed: ${it.message}") } } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { c ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${c.title}?") }, text = { Text("Its messages are removed. Workflows, skills and files it created stay.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { runCatching { engine.deleteConversation(c.id) }.onSuccess { if (c.id == selectedId) onOpen(Screen.Chat()) }.onFailure { snack.showSnackbar("Delete failed: ${it.message}") } } }, modifier = Modifier.semantics { contentDescription = "Confirm delete ${c.title}" }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ConversationRow(c: Conversation, selected: Boolean, hit: String?, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier, menu: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val bg by animateColorAsState(if (selected) cs.secondaryContainer else MaterialTheme.mahout.card, motion.effect(), label = "convBg")
    val fg by animateColorAsState(if (selected) cs.onSecondaryContainer else cs.onSurface, motion.effect(), label = "convFg")
    val indicator by animateDpAsState(if (selected) 3.dp else 0.dp, motion.spatial(), label = "convInd")
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.m)
    Box(modifier.fillMaxWidth()) {
        Surface(color = bg, contentColor = fg, shape = shape,
            modifier = Modifier.fillMaxWidth().pressScale(src).clip(shape)
                .combinedClickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onOpen, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onMenu() })
                .semantics { contentDescription = "Chat ${c.title}, ${c.status}${if (selected) ", selected" else ""}" }) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(indicator).fillMaxHeight().background(cs.primary))
                Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).align(Alignment.CenterVertically))
                        when (c.status) {
                            "awaiting" -> StatusPill("awaiting approval", Tone.Caution)
                            "running" -> StatusPill("working…", Tone.Primary, pulsing = true)
                            "error" -> StatusPill("error", Tone.Danger)
                        }
                    }
                    Text(fmtAgo(c.updatedAt), style = MaterialTheme.typography.bodySmall, color = if (selected) fg else cs.onSurfaceVariant)
                    hit?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                }
            }
        }
        menu()
    }
}

// ---- thread ----

/**
 * One conversation (DESIGN4 §5.6, DESIGN6 §5): phone = ModalNavigationDrawer (chat list) around the thread; tablet = the thread only
 * (App shows the list pane beside it). Streaming live turn, tool cards, approval dock replacing the composer, slash commands, mentions.
 */
@Composable
fun ChatScreen(conversationId: String, engine: Engine, catalog: Catalog, onBack: () -> Unit, onOpen: (Screen) -> Unit, twoPane: Boolean = false) {
    if (twoPane) { ChatThread(conversationId, engine, catalog, onOpen, openDrawer = null); return }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer, gesturesEnabled = drawer.isOpen,   // edge swipe stays with predictive back and text selection
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                ChatListPane(engine, conversationId, onOpen = { s -> scope.launch { drawer.close() }; onOpen(s) }, compact = true)
            }
        },
    ) { ChatThread(conversationId, engine, catalog, onOpen, openDrawer = { scope.launch { drawer.open() } }) }
}

@Composable
private fun ChatThread(conversationId: String, engine: Engine, catalog: Catalog, onOpen: (Screen) -> Unit, openDrawer: (() -> Unit)?) {
    val ctx = LocalContext.current
    val app = remember { Mob8NApp.of(ctx) }
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val fontScale = LocalDensity.current.fontScale
    val convs by engine.conversations().collectAsStateWithLifecycle(emptyList())
    val conv = convs.firstOrNull { it.id == conversationId }
    val rows by remember(conversationId) { engine.messages(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val status by remember(conversationId) { ChatRunner.status(conversationId) }.collectAsStateWithLifecycle()
    val settings = remember(conv?.settingsJson) { ChatSettings.parse(conv?.settingsJson ?: "{}") }
    LaunchedEffect(Unit) { McpPrefs.load(ctx); HarnessPrefs.load(ctx) }
    val servers by McpPrefs.servers.collectAsStateWithLifecycle()
    val globalMode by HarnessPrefs.mode.collectAsStateWithLifecycle()
    val globalUntil by HarnessPrefs.bypassUntil.collectAsStateWithLifecycle()
    val fallbackMode = settings.modeOrLegacy() ?: globalMode   // for rows / pending blocks without a persisted mode (v4 history)
    val tick = rememberResumeTick()
    val target by produceState<LlmTarget?>(null, tick) { value = withContext(Dispatchers.IO) { runCatching { Llm.defaultTarget(ctx) }.getOrNull() } }
    // composer sources (§5.3.12/13)
    val workflows by engine.workflows().collectAsStateWithLifecycle(emptyList())
    val skills by engine.skills().collectAsStateWithLifecycle(emptyList())
    val knowledgeNames by produceState(emptyList<String>(), tick) { value = runCatching { engine.knowledge.names() }.getOrDefault(emptyList()) }
    val panels = remember(tick) { runCatching { PanelPrefs.read(ctx) }.getOrDefault(emptyList()) }
    val sources = remember(workflows, skills, panels) { SlashSources(workflows.map { it.id to it.name }, skills.filter { it.enabled }.map { it.name }, panels.map { it.title }) }

    // ponytail: append-only transcript (Retry / Edit & resend append new messages, D12) and Hide is a per-session view filter;
    // upgrade = branch/fork rows + a persisted hidden flag
    var hidden by rememberSaveable(conversationId) { mutableStateOf(listOf<Long>()) }   // D12: session-only view filter
    val rowsAsc = remember(rows) { rows.sortedBy { it.seq } }
    val visible = remember(rowsAsc, hidden) { rowsAsc.asReversed().filter { (it.role != "user" || textBlocks(it).isNotEmpty()) && it.id !in hidden } }   // tool_result-only rows pair into cards
    val results = remember(rows) { resultsOf(rows) }
    val starts = remember(rowsAsc) { turnStarts(rowsAsc) }
    val liveHeader = remember(rowsAsc) { !assistantSinceLastUser(rowsAsc) }
    // DESIGN4P §3: the live batch, or (after process death) the persisted one with its stored per-call decisions and the last row's frozen gate.
    val (pending, decided) = remember(status, conv?.pendingJson, conv?.status, rowsAsc, fallbackMode, servers) {
        val live = status as? ChatRunner.Status.Awaiting
        when {
            live != null -> live.pending to live.decided
            conv?.status == "awaiting" -> {
                val (uses, stored) = parsePending(conv.pendingJson)
                val last = rowsAsc.lastOrNull { it.role == "assistant" }
                val mode = rowMode(last?.meta, fallbackMode)
                val why = last?.meta?.get("secondOpinion") as? JsonObject   // DESIGN5 §6.2: the frozen reason survives process death
                uses.map { tu -> val d = decisionFor(tu, last?.meta, mode, kindOf(tu.name, last?.meta, tu.id, catalog), catalog, servers)
                    PendingCall(tu.id, tu.name, tu.input, why?.get(tu.id).asTextOrNull()?.let { d.copy(reason = it) } ?: d) } to stored
            }
            else -> emptyList<PendingCall>() to emptyMap()
        }
    }
    val undecided = pending.count { it.id !in decided }
    val busy = status is ChatRunner.Status.Thinking || status is ChatRunner.Status.Running
    val idle = !busy && pending.isEmpty() && status !is ChatRunner.Status.Awaiting
    val saveAsSkill = remember(rowsAsc, status) { status is ChatRunner.Status.Idle && pending.isEmpty() && toolCallsInLastTurn(rowsAsc) >= 3 }
    val openDraftChip = remember(rowsAsc, status) { if (status is ChatRunner.Status.Idle) lastTurnDraftId(rowsAsc)?.let { ChatDrafts.map[it] } else null }
    val lastUser = remember(rowsAsc) { lastUserText(rowsAsc) }
    val lastUserId = remember(rowsAsc) { rowsAsc.lastOrNull(::isPlainUser)?.id }
    val lastAssistantId = remember(rowsAsc) { rowsAsc.lastOrNull { it.role == "assistant" }?.id }

    // Live turn (§5.1.6): `shown` keeps the last non-null LiveTurn and clears at the next rows emission or after 400 ms (no flicker, no double).
    val shown = remember(conversationId) { mutableStateOf<LiveTurn?>(null) }
    val rowsState = rememberUpdatedState(rows)
    LaunchedEffect(conversationId) {
        var rowsAtLive: Any? = null   // rows as of the last live publish: the flush's Room emission can land before `live` turns null (device phase: a 400 ms double)
        ChatRunner.live(conversationId).collectLatest { t ->
            if (t != null) { shown.value = t; rowsAtLive = rowsState.value }
            else if (shown.value != null) {
                if (rowsState.value === rowsAtLive) withTimeoutOrNull(400) { snapshotFlow { rowsState.value }.first { it !== rowsAtLive } }
                shown.value = null
            }
        }
    }
    val liveCount by remember { derivedStateOf { shown.value?.segments?.size ?: 0 } }
    val liveActive by remember { derivedStateOf { shown.value != null } }
    val statusState = rememberUpdatedState(status)
    val showDots by remember { derivedStateOf {
        statusState.value is ChatRunner.Status.Thinking && shown.value?.segments?.lastOrNull()?.let { streamVisible(it.text).isBlank() && it.tools.isEmpty() } != false
    } }

    var draft by rememberSaveable(conversationId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var mentions by rememberSaveable(conversationId, stateSaver = MENTIONS_SAVER) { mutableStateOf(emptyList<Mention>()) }
    var imagePath by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    var sheet by remember { mutableStateOf(false) }
    var confirmBypass by remember { mutableStateOf(false) }
    fun msg(s: String) = scope.launch { snack.showSnackbar(s) }
    fun send(text: String, image: String? = null) { runCatching { ChatRunner.send(app, conversationId, text, image) }.onFailure { msg("Could not send: ${it.message}") } }
    fun openDraft(d: ChatDrafts.Draft) { Drafts.map[d.id] = Workflow(id = d.id, name = d.name, graph = autoLayout(d.graph), updatedAt = d.createdAt); onOpen(Screen.Editor(d.id)) }   // DESIGN4P P12
    fun copy(text: String) { clipboard.setText(AnnotatedString(text)); msg("Copied") }
    fun saveSettings(n: ChatSettings) { val c = conv ?: return; scope.launch { runCatching { engine.saveConversation(c.copy(settingsJson = n.json())) }.onFailure { msg("Could not save settings: ${it.message}") } } }
    // Same save as the settings sheet (DESIGN4P P3/P4): Bypass only ever arrives here after BypassConfirmDialog.
    fun saveMode(m: PermissionMode?) {
        val t = System.currentTimeMillis()
        saveSettings(settings.copy(mode = m, bypassUntil = if (m == PermissionMode.BYPASS) t + Permissions.BYPASS_TTL_MS else 0L, autoApproveSafe = false, autoApproveCoding = false))
    }
    fun newChat(withSettings: Boolean) {
        // Bypass never carries over silently: a /clear of a Bypass chat starts in Inherit (the user re-arms it through the dialog).
        val carried = if (withSettings) settings.let { if (it.mode == PermissionMode.BYPASS) it.copy(mode = null, bypassUntil = 0L) else it } else ChatSettings()
        val id = runCatching { ChatRunner.newConversation(app, carried) }.getOrElse { msg("Could not start a chat: ${it.message}"); return }
        onOpen(Screen.Chat(id))
        if (withSettings && settings.mode == PermissionMode.BYPASS) msg("Bypass is not carried over — use /mode bypass to turn it on again")
    }
    fun clearDraft() { draft = TextFieldValue(""); mentions = emptyList(); imagePath = null }
    fun submit() {
        val raw = draft.text.trim()
        if (raw.isEmpty()) return
        when (val a = resolveSlash(raw)) {
            null -> { send(composeOutgoing(raw, mentions), imagePath); clearDraft() }
            SlashAction.New -> { clearDraft(); newChat(false) }
            SlashAction.Clear -> { clearDraft(); newChat(true) }
            is SlashAction.Mode -> { draft = TextFieldValue(""); if (a.mode == PermissionMode.BYPASS) confirmBypass = true else { saveMode(a.mode); msg("Mode: ${modeLabel(a.mode)}") } }
            is SlashAction.Run -> {
                val wf = workflows.firstOrNull { it.name.equals(a.name, ignoreCase = true) } ?: workflows.firstOrNull { it.id == a.name }
                if (wf == null) { msg("No workflow named ${a.name}"); return }
                draft = TextFieldValue("")
                scope.launch {   // the user's own action, same as the list's Run now (allowed in every mode)
                    val runId = runCatching { engine.runManual(wf.id) }.getOrElse { snack.showSnackbar("Run failed: ${it.message}"); return@launch }
                    if (runId == null) snack.showSnackbar("No Manual trigger in ${wf.name}")
                    else if (snack.showSnackbar("Run started", actionLabel = "View", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onOpen(Screen.RunDetail(runId))
                }
            }
            is SlashAction.Skill -> {
                val sk = skills.firstOrNull { it.enabled && it.name.equals(a.name, ignoreCase = true) }
                if (sk == null) msg("No enabled skill named ${a.name}") else { mentions = (mentions + Mention(MentionKind.SKILL, sk.name)).distinct(); draft = TextFieldValue("") }
            }
            is SlashAction.Panel -> {
                val p = runCatching { PanelPrefs.byName(ctx, a.name) }.getOrNull()
                if (p == null) msg("No panel named ${a.name}") else { draft = TextFieldValue(""); onOpen(Screen.Panels(PanelPrefs.slug(p.title))) }
            }
            is SlashAction.Incomplete -> msg(a.hint)
        }
    }

    val effectiveMode = settings.modeOrLegacy() ?: globalMode
    val modeUntil = if (settings.modeOrLegacy() != null) settings.bypassUntil else globalUntil
    val modelLabel = target?.model?.takeIf { it.isNotBlank() }

    Scaffold(
        topBar = {
            MahoutTopBar(
                title = conv?.title ?: "Chat",
                subtitle = target?.let { "${it.label} · ${it.model}" }?.takeIf { subtitleVisible(fontScale) },
                navigationIcon = openDrawer?.let { open -> { IconButton(onClick = open) { Icon(Icons.Rounded.Menu, contentDescription = "Open chat list") } } },
                actions = {
                    ModeChip(settings.modeOrLegacy(), effectiveMode, modeUntil, globalMode, globalUntil, onPick = ::saveMode, onBypass = { confirmBypass = true })
                    IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.Tune, contentDescription = "Chat settings") }
                },
            )
        },
        snackbarHost = { MahoutSnackbarHost(snack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),   // the composer / dock pad the navigation bar and IME exactly once
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).brandGlow()) {
            val listState = rememberLazyListState()
            // Keyed rows keep the old first-visible item anchored, so rows inserted at index 0 (newest, bottom) would land off-screen.
            // Follow the newest row unless the user's last scroll left the list away from the bottom (a Room emission can add several rows at once).
            var follow by remember { mutableStateOf(true) }
            LaunchedEffect(listState.isScrollInProgress) { if (!listState.isScrollInProgress) follow = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            LaunchedEffect(visible.size, liveCount, showDots, saveAsSkill) { if (follow) listState.scrollToItem(0) }   // instant: rows inserted mid-animation would leave the animation ending away from the bottom
            val awayPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            val away by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > awayPx } }
            var baseline by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(away) { baseline = if (away) visible.size + liveCount else null }
            val unread = unreadSince(baseline, visible.size + liveCount)
            val runningTool = (status as? ChatRunner.Status.Running)?.tool

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val side = maxOf(pageGutter(), (maxWidth - 760.dp) / 2)   // tablet reading column: centred, max 760 dp
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(side, Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    // Reverse layout: index 0 sits at the bottom.
                    if (saveAsSkill || openDraftChip != null) item(key = "followups", contentType = "chips") {
                        FlowRow(Modifier.fillMaxWidth().animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            if (saveAsSkill) AssistChip(onClick = { send("Save what you just did as a skill") }, label = { Text("Save as skill?") }, modifier = Modifier.semantics { contentDescription = "Save what the assistant just did as a skill" })
                            openDraftChip?.let { d -> AssistChip(onClick = { openDraft(d) }, label = { Text("Open draft") }, modifier = Modifier.semantics { contentDescription = "Open draft ${d.name} in the editor" }) }
                        }
                    }
                    if (showDots) item(key = "thinking", contentType = "thinking") {
                        ThinkingRow((status as? ChatRunner.Status.Thinking)?.sinceMs, Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = null))
                    }
                    for (i in liveCount - 1 downTo 0) item(key = "live:$i", contentType = "live") {
                        LiveSegmentView(shown, i, header = i == 0 && liveHeader && !showDots, model = modelLabel, toolView = { t -> liveToolView(t, catalog, servers, effectiveMode) },
                            onOpenDraft = ::openDraft, onCopy = ::copy,
                            modifier = Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = null).animateContentSize(motion.spatial(), alignment = Alignment.BottomStart))   // bottom-aligned: the newest line + caret stay visible while the height catches up
                    }
                    items(visible, key = { it.id }, contentType = { it.role }) { row ->
                        val m = Modifier.animateItem(fadeInSpec = if (liveActive) null else motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect())
                        val canAct = idle
                        when (row.role) {
                            "user" -> {
                                val text = textBlocks(row)
                                val actions = buildList {
                                    add(act("Copy") { copy(text) })
                                    if (row.id == lastUserId && canAct) add(act("Edit & resend") { draft = TextFieldValue(text, TextRange(text.length)); scope.launch { runCatching { focus.requestFocus() } } })
                                    add(act("Hide") { hidden = hidden + row.id; scope.launch { if (snack.showSnackbar("Hidden from view — the assistant still remembers it", actionLabel = "Undo") == SnackbarResult.ActionPerformed) hidden = hidden - row.id } })
                                }
                                MessageActions(actions, m) { UserBubble(text, row.meta["image"].asTextOrNull()) }
                            }
                            "assistant" -> {
                                val text = textBlocks(row)
                                val actions = buildList {
                                    if (text.isNotEmpty()) add(act("Copy") { copy(text) })
                                    if (row.id == lastAssistantId && canAct && lastUser != null) add(act("Retry") { send(lastUser) })
                                    add(act("Hide") { hidden = hidden + row.id; scope.launch { if (snack.showSnackbar("Hidden from view — the assistant still remembers it", actionLabel = "Undo") == SnackbarResult.ActionPerformed) hidden = hidden - row.id } })
                                }
                                MessageActions(actions, m) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                        if (row.id in starts) AssistantHeader(row.meta["model"].asTextOrNull() ?: modelLabel)
                                        if (text.isNotEmpty()) MarkdownBlocks(text)
                                        val ms = row.meta["ms"] as? JsonObject
                                        val mode = rowMode(row.meta, fallbackMode)
                                        for (tu in toolUses(row)) {
                                            val kind = kindOf(tu.name, row.meta, tu.id, catalog)
                                            val d = decisionFor(tu, row.meta, mode, kind, catalog, servers)
                                            val res = results[tu.id]
                                            val glyph = if (res == null && runningTool == tu.name) GlyphState.Running else toolGlyph(res)
                                            ToolCard(ToolView(tu.id, tu.name, kind, d.risk, cardChip(d, res), glyph, tu.input, res?.first, res?.second == true, ms?.get(tu.id).asTextOrNull()?.toLongOrNull()),
                                                onOpenDraft = ::openDraft, onCopy = ::copy)
                                        }
                                    }
                                }
                            }
                            else -> Text(row.text.ifBlank { textBlocks(row) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = m.fillMaxWidth().padding(horizontal = Space.s).semantics { contentDescription = "Note: ${row.text}" })
                        }
                    }
                    if (visible.isEmpty() && liveCount == 0 && !showDots) item(key = "empty", contentType = "empty") {
                        Column(Modifier.fillMaxWidth().padding(top = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                            EmptyState(Icons.Rounded.Forum, "Ask Mahout", "It can list, run, build and fix your workflows, search your knowledge, or write a script. Actions that change things pause for your approval.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                for (sgg in EMPTY_SUGGESTIONS) SuggestionChip(onClick = { draft = TextFieldValue(sgg, TextRange(sgg.length)); scope.launch { runCatching { focus.requestFocus() } } },
                                    label = { Text(sgg) }, modifier = Modifier.semantics { contentDescription = "Suggestion: $sgg" })
                            }
                        }
                    }
                }
                JumpFab(away, unread, Modifier.align(Alignment.BottomEnd).padding(Space.l)) { scope.launch { if (motion.reduced) listState.scrollToItem(0) else listState.animateScrollToItem(0) } }
            }
            StatusRow(conversationId, status, conv, shown)
            AnimatedContent(targetState = undecided > 0, label = "dock",
                transitionSpec = {
                    (if (targetState) (slideInVertically(motion.emphasis()) { it / 2 } + fadeIn(motion.effect())) togetherWith fadeOut(motion.exitEffect())
                    else fadeIn(motion.effect()) togetherWith (slideOutVertically(motion.exitEffect()) { it / 2 } + fadeOut(motion.exitEffect())))
                        .using(SizeTransform(clip = false) { _, _ -> motion.spatial() })
                }) { docked ->
                if (docked) ApprovalDock(pending, decided,
                    onDecide = { id, ok -> ChatRunner.decide(app, conversationId, id, ok) },
                    onDecideAll = { ok -> ChatRunner.decideAll(app, conversationId, ok) }, onOpenDraft = ::openDraft)
                else ChatComposer(
                    draft = draft, onDraft = { draft = it }, hint = composerHint(undecided, pending.size), enabled = pending.isEmpty(), busy = busy,
                    maxLines = composerMaxLines(fontScale), mentions = mentions, onRemoveMention = { mm -> mentions = mentions - mm }, onAddMention = { mm -> mentions = (mentions + mm).distinct() },
                    imagePath = imagePath, onImage = { imagePath = it }, visionOk = target?.supportsVision == true,
                    sources = sources, knowledge = knowledgeNames, onSend = ::submit, onStop = { ChatRunner.cancel(app, conversationId) }, onMessage = { msg(it) }, focus = focus,
                )
            }
        }
    }
    if (sheet && conv != null) ChatSettingsSheet(conv, settings, engine, catalog, onClose = { sheet = false }, onMessage = { msg(it) })
    if (confirmBypass) BypassConfirmDialog(BypassScope.CONVERSATION, onConfirm = { confirmBypass = false; saveMode(PermissionMode.BYPASS) }, onDismiss = { confirmBypass = false })
}

/** Jump-to-latest FAB (§5.3.7): 40 dp visual / 48 dp target, badge = unread count. */
@Composable
private fun JumpFab(visible: Boolean, unread: Int, modifier: Modifier, onClick: () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(visible, enter = motion.enter(), exit = motion.exit(), modifier = modifier) {
        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
            SmallFloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = "Jump to latest" + if (unread > 0) ", $unread new" else "" }) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = null)
            }
        }
    }
}

private fun act(label: String, f: () -> Unit): Pair<String, () -> Unit> = label to f

private val EMPTY_SUGGESTIONS = listOf("What ran today?", "Why did my last run fail?", "Build a workflow that…", "Search my knowledge for…")


@Composable
private fun AssistantHeader(model: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        BrandMark(size = 20.dp)
        Text(if (model.isNullOrBlank()) "Mahout" else "Mahout · $model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** User bubble: navy/gold ink, BubbleUserShape, max 85 % (phone) / 560 dp; a leading "Context: …" line is shown small; image thumbnail above. */
@Composable
private fun UserBubble(text: String, imagePath: String?) {
    val c = MaterialTheme.mahout
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.85f).dp.coerceAtMost(560.dp)
    val (context, body) = remember(text) { if (text.startsWith("Context: ") && "\n\n" in text) text.substringBefore("\n\n") to text.substringAfter("\n\n") else null to text }
    val colors = MaterialTheme.colorScheme; val typography = MaterialTheme.typography
    // inline code without a chip background and links in the bubble's own ink (teal links fail contrast on navy)
    val annotated = remember(body, colors, typography) { markdownToAnnotated(body, MdStyle.of(colors, typography).copy(code = CodeSmallStyle.toSpanStyle(), link = SpanStyle(textDecoration = TextDecoration.Underline))) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (imagePath != null) {
            val thumb = rememberThumb(imagePath, 160.dp)
            when {
                thumb.bitmap != null -> Image(BitmapPainter(thumb.bitmap), contentDescription = "Attached image", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 160.dp, height = 120.dp).clip(RoundedCornerShape(Radius.s)))
                !thumb.loading -> AssistChip(onClick = {}, label = { Text("(image)") }, modifier = Modifier.semantics { contentDescription = "Attached image no longer available" })
            }
        }
        Surface(color = c.userBubble, contentColor = c.onUserBubble, shape = BubbleUserShape, modifier = Modifier.widthIn(max = maxW).semantics { contentDescription = "You said" }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                context?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onUserBubble.copy(alpha = 0.8f)) }
                Text(annotated, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * Message actions (§5.3.5): long-press opens a menu (haptic), a pointer hover or keyboard focus fades in a small action row at the top-end,
 * TalkBack gets the same actions as customActions. `actions` = label -> action.
 */
@Composable
private fun MessageActions(actions: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    var menu by remember { mutableStateOf(false) }
    val current = rememberUpdatedState(actions)
    Box(modifier.fillMaxWidth()
        .hoverable(src).focusable(interactionSource = src)
        .pointerInput(Unit) { detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) }
        .semantics { customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } } }) {
        content()
        AnimatedVisibility(hovered || focused, enter = fadeIn(motion.effect()), exit = fadeOut(motion.exitEffect()), modifier = Modifier.align(Alignment.TopEnd)) {
            Surface(shape = RoundedCornerShape(Radius.s), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
                Row {
                    for ((label, run) in current.value) IconButton(onClick = run) { Icon(actionIcon(label), contentDescription = label) }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((label, run) in current.value) DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(actionIcon(label), contentDescription = null) }, onClick = { menu = false; run() })
        }
    }
}

private fun actionIcon(label: String) = when (label) {
    "Retry" -> Icons.Rounded.Refresh; "Edit & resend" -> Icons.Rounded.Edit; "Hide" -> Icons.Rounded.VisibilityOff; else -> Icons.Rounded.ContentCopy
}

/** "Thinking · 4 s" with TypingDots before the first token (or for non-streaming providers); the ticker is scoped to this row. */
@Composable
private fun ThinkingRow(sinceMs: Long?, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) { while (sinceMs != null) { now = System.currentTimeMillis(); delay(1_000) } }
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        BrandMark(size = 20.dp)
        TypingDots()
        Text(if (sinceMs == null) "Thinking" else "Thinking · ${((now - sinceMs) / 1000).coerceAtLeast(0)} s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One live (unpersisted) segment: thinking disclosure, streaming markdown with the caret, live tool cards. Reads `shown` only here. */
@Composable
private fun LiveSegmentView(shown: State<LiveTurn?>, index: Int, header: Boolean, model: String?, toolView: (LiveTool) -> ToolView,
                            onOpenDraft: (ChatDrafts.Draft) -> Unit, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = shown.value
    val seg = t?.segments?.getOrNull(index)
    val streaming = t != null && t.streaming && index == t.segments.lastIndex
    val text = remember(seg?.text) { streamVisible(seg?.text.orEmpty()).trim() }
    val blink = rememberBlink(streaming)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (seg == null) return@Column
        val hasContent = text.isNotEmpty() || seg.tools.isNotEmpty() || seg.thinking.isNotBlank()
        if (header && hasContent) AssistantHeader(model)
        if (seg.thinking.isNotBlank()) {
            var open by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (open) "Hide thinking" else "Show thinking" }) {
                Text("Thinking", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(if (open) 180f else 0f))
            }
            ExpandableSection(open) { Text(seg.thinking.takeLast(2 * 1024), style = CodeSmallStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (text.isNotEmpty()) MarkdownBlocks(text, caretAlpha = if (streaming) blink else null)
        for (lt in seg.tools) ToolCard(toolView(lt), onOpenDraft = onOpenDraft, onCopy = onCopy)
    }
}

private fun liveToolView(t: LiveTool, catalog: Catalog, servers: List<McpServer>, mode: PermissionMode): ToolView {
    val kind = kindOf(t.name, null, t.id, catalog)
    val d = Permissions.decide(mode, riskFor(t.name, kind, catalog, servers))
    val err = t.state == LiveTool.State.FAILED
    return ToolView(t.id, t.name, kind, d.risk, Permissions.chip(d), liveGlyph(t), null, t.result, err,
        ms = if (t.startedMs != null && t.endedMs != null) t.endedMs - t.startedMs else null,
        startedMs = t.startedMs.takeIf { t.state == LiveTool.State.RUNNING },
        formingChars = t.argsChars.takeIf { t.state == LiveTool.State.FORMING })
}

/** One tool card's data: persisted (`ToolUse` + result) or live (`LiveTool`). */
internal data class ToolView(
    val id: String, val name: String, val kind: String, val risk: Risk, val chip: String, val glyph: GlyphState, val input: JsonObject?,
    val result: String?, val isError: Boolean, val ms: Long?, val startedMs: Long? = null, val formingChars: Int? = null,
)

/**
 * Tool-call card (§5.3.8): L2, collapsed by default (errors auto-expand): glyph · name · elapsed (live ticker while running) · chevron; kind + risk pill;
 * expanded: Input and Output MonoBlocks (≤ 2 KB). Semantics kept: "Tool <name>, <chip>, <no result yet|failed|done>".
 */
@Composable
private fun ToolCard(v: ToolView, onOpenDraft: (ChatDrafts.Draft) -> Unit, onCopy: (String) -> Unit) {
    val motion = LocalMotion.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable(v.id) { mutableStateOf(v.isError) }
    LaunchedEffect(v.isError) { if (v.isError) expanded = true }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, motion.spatialFast(), label = "chev")
    val inputText = remember(v.input) { v.input?.takeIf { it.isNotEmpty() }?.let { cap(runCatching { prettyJson(it) }.getOrDefault(it.toString())) } }
    var menu by remember { mutableStateOf(false) }
    val copyActions = buildList {
        inputText?.let { add(act("Copy input") { onCopy(it) }) }
        v.result?.let { add(act("Copy output") { onCopy(it) }) }
    }
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(Radius.m),
        modifier = Modifier.fillMaxWidth()
            .pointerInput(copyActions.size) { if (copyActions.isNotEmpty()) detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) }
            .semantics(mergeDescendants = true) {
                contentDescription = "Tool ${v.name}, ${v.chip}, ${if (v.result == null) "no result yet" else if (v.isError) "failed" else "done"}"
                customActions = copyActions.map { (l, r) -> CustomAccessibilityAction(l) { r(); true } }
            }) {
        Column(Modifier.padding(start = Space.m, end = Space.xs, bottom = Space.s)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }
                .semantics { role = Role.Button; contentDescription = "${if (expanded) "Hide" else "Show"} input of ${v.name}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatusGlyph(v.glyph)
                Text(v.name, style = CodeSmallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when {
                    v.startedMs != null -> ElapsedTicker(v.startedMs)
                    v.ms != null -> Text(fmtDuration(v.ms), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(end = Space.s).rotate(chevron))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (v.kind != "operator") Text(v.kind, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                RiskPill(v.risk, v.chip)
                v.formingChars?.let { Text("preparing… $it chars", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically)) }
            }
            // Blocked-in-Plan path (DESIGN4P P5): the draft stays in memory, saving becomes the user's action in the editor.
            if (v.name == "save_workflow") ChatDrafts.map[v.input?.get("draftId").asTextOrNull()]?.let { d -> OpenDraftButton(d, onOpenDraft) }
            ExpandableSection(expanded) {
                Column(Modifier.padding(top = Space.s, end = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (inputText != null) { Text("INPUT", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant); MonoBlock(inputText, Modifier.fillMaxWidth(), language = "json") }
                    when {
                        v.result == null -> Text(if (v.glyph == GlyphState.Running) "running…" else "waiting…", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        v.isError -> {
                            val (bg, fg) = riskColors(Risk.ALWAYS, MaterialTheme.mahout)
                            Text("ERROR", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(Radius.s), modifier = Modifier.fillMaxWidth()) {
                                Text(cap(v.result), style = CodeSmallStyle, modifier = Modifier.padding(Space.s))
                            }
                        }
                        else -> { Text("OUTPUT", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant); MonoBlock(cap(v.result), Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((l, r) in copyActions) DropdownMenuItem(text = { Text(l) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }, onClick = { menu = false; r() })
        }
    }
}

@Composable
private fun ElapsedTicker(startedMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedMs) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    Text(fmtDuration((now - startedMs).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun OpenDraftButton(d: ChatDrafts.Draft, onOpenDraft: (ChatDrafts.Draft) -> Unit) {
    TextButton(onClick = { onOpenDraft(d) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Open draft ${d.name} in the editor" }) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null); Spacer(Modifier.width(Space.xs)); Text("Open in editor")
    }
}

/**
 * Status line (§5.4): shows Running / Awaiting / errors; its node is the polite live region. The spoken text is the status, or the
 * streaming announcements from `nextAnnouncement` ("Mahout is responding", then new sentences ≤ every 2.5 s, the remainder at the end).
 */
@Composable
private fun StatusRow(conversationId: String, status: ChatRunner.Status, conv: Conversation?, shown: State<LiveTurn?>) {
    val text = when (val s = status) {
        is ChatRunner.Status.Thinking -> ""
        is ChatRunner.Status.Running -> "Running ${s.tool}…"
        is ChatRunner.Status.Awaiting -> "Waiting for your approval"
        is ChatRunner.Status.Error -> s.message
        ChatRunner.Status.Idle -> if (conv?.status == "awaiting") "Waiting for your approval" else conv?.lastError ?: ""
    }
    var spoken by remember(conversationId) { mutableStateOf("") }
    LaunchedEffect(text) { if (text.isNotEmpty()) spoken = text }
    val statusNow = rememberUpdatedState(status)
    LaunchedEffect(conversationId) {
        var upTo = 0; var lastMs = 0L; var prev = ""
        snapshotFlow { liveText(shown.value) to (statusNow.value is ChatRunner.Status.Thinking || statusNow.value is ChatRunner.Status.Running) }.collect { (live, busy) ->
            val now = System.currentTimeMillis()
            if (prev.isNotEmpty() && !live.startsWith(prev.take(upTo))) {   // the live text was flushed to rows (or reset): speak what is left of it
                nextAnnouncement(prev, upTo, now, lastMs, done = true)?.let { spoken = it.first; lastMs = now }
                upTo = 0
            }
            if (busy) nextAnnouncement(live, upTo, now, lastMs, done = false)?.let { (a, u) -> spoken = a; upTo = u; lastMs = now }
            else if (live.isEmpty()) lastMs = 0L   // turn over: the next one starts with "Mahout is responding"
            prev = live
        }
    }
    val cs = MaterialTheme.colorScheme
    // ponytail: 1 dp min height keeps the live-region node on screen while there is nothing to show; upgrade = announceForAccessibility-free overlay node
    Row(Modifier.fillMaxWidth().heightIn(min = 1.dp).padding(horizontal = Space.l).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically) {
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelMedium, color = if (status is ChatRunner.Status.Error) cs.error else cs.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = Space.xs))
    }
}

/**
 * Mode chip (§5.3.10): StatusPill look (Plan Neutral · Ask Info · Auto Caution · Bypass Danger "Bypass · 41m"); tap -> menu with Inherit and
 * the four modes, each with its one-line help. Bypass always goes through BypassConfirmDialog (onBypass).
 */
@Composable
private fun ModeChip(own: PermissionMode?, effective: PermissionMode, until: Long, globalMode: PermissionMode, globalUntil: Long, onPick: (PermissionMode?) -> Unit, onBypass: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(effective, until) { while (effective == PermissionMode.BYPASS && until > now) { delay(30_000); now = System.currentTimeMillis() } }
    val text = modeChipText(effective, until, now)
    Box {
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.s)).clickable { menu = true }
            .semantics { role = Role.Button; contentDescription = "Permission mode $text, change" }, contentAlignment = Alignment.Center) {
            StatusPill(text, modeTone(effective, effective != PermissionMode.BYPASS || until > now), Modifier.padding(horizontal = Space.xs))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val options: List<PermissionMode?> = listOf(null) + PermissionMode.entries
            for (m in options) DropdownMenuItem(
                text = {
                    Column(Modifier.widthIn(max = 320.dp).padding(vertical = Space.xs)) {
                        Text(if (m == null) "Inherit (${modeStatus(globalMode, globalUntil, now)})" else modeLabel(m) + if (own == m) " ✓" else "", style = MaterialTheme.typography.labelLarge)
                        Text(if (m == null) "Use the global mode from Settings." else modeHelp(m), style = MaterialTheme.typography.bodySmall,
                            color = if (m == PermissionMode.BYPASS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = { menu = false; if (m == PermissionMode.BYPASS) onBypass() else onPick(m) },
                modifier = Modifier.semantics { contentDescription = "Permission mode ${modeLabel(m)}${if (own == m) ", selected" else ""}" },
            )
        }
    }
}

/** Per-conversation settings (ChatSettings JSON on the conversation row) + operator memory. */
@Composable
private fun ChatSettingsSheet(conv: Conversation, s: ChatSettings, engine: Engine, catalog: Catalog, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    val a11y = remember(tick) { runCatching { Gate.Accessibility.granted(ctx) }.getOrDefault(false) }
    val skills by engine.skills().collectAsStateWithLifecycle(emptyList())
    val knowledgeNames by produceState(emptyList<String>()) { value = runCatching { engine.knowledge.names() }.getOrDefault(emptyList()) }
    val mcpNames = remember { runCatching { McpPrefs.names(ctx) }.getOrDefault(emptyList()) }
    val nodeIds = remember(catalog) { catalog.agentTools().map { it.spec.id }.filter { it != "app.shell_run" } }
    var memory by remember { mutableStateOf(runCatching { ChatPrefs.memory(ctx) }.getOrDefault("")) }
    fun save(n: ChatSettings) = scope.launch { runCatching { engine.saveConversation(conv.copy(settingsJson = n.json())) }.onFailure { onMessage("Could not save settings: ${it.message}") } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.l).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("Chat settings", style = MaterialTheme.typography.titleLarge)
            // DESIGN4P P3/P4: per-conversation mode (null = inherit); legacy autoApprove* read only through modeOrLegacy and cleared once a mode is picked.
            val globalMode by HarnessPrefs.mode.collectAsStateWithLifecycle()
            val globalUntil by HarnessPrefs.bypassUntil.collectAsStateWithLifecycle()
            val now = remember(s) { System.currentTimeMillis() }
            SectionCard(title = "Permission mode", tone = if (s.mode == PermissionMode.BYPASS) Tone.Danger else Tone.Neutral) {
                ModeSelector(value = s.modeOrLegacy(), allowInherit = true, globalLabel = modeStatus(globalMode, globalUntil, now), scope = BypassScope.CONVERSATION) { m ->
                    val t = System.currentTimeMillis()
                    save(s.copy(mode = m, bypassUntil = if (m == PermissionMode.BYPASS) t + Permissions.BYPASS_TTL_MS else 0L, autoApproveSafe = false, autoApproveCoding = false))
                }
                if (s.mode == PermissionMode.BYPASS) Text("This chat: ${modeStatus(s.mode, s.bypassUntil, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            SectionCard(title = "Tools") {
                SwitchRow("UI automation", if (a11y) UI_AUTOMATION_DISCLOSURE else "Needs the Accessibility service (Permissions). $UI_AUTOMATION_DISCLOSURE", s.uiAutomation && a11y) { if (a11y) save(s.copy(uiAutomation = it)) else onMessage("Grant the Accessibility service in Permissions first") }
                LabelsField("Node tools", "Catalog node ids the operator may call. Empty = every agent-tool node.", s.nodeTools, { save(s.copy(nodeTools = it)) }, suggestions = nodeIds)
                LabelsField("MCP servers", "Servers from Settings > AI > MCP. Untrusted servers ask every time.", s.mcpServers, { save(s.copy(mcpServers = it)) }, suggestions = mcpNames)
                LabelsField("Knowledge sources", "\"all\" = every source", s.knowledge, { save(s.copy(knowledge = it)) }, suggestions = listOf("all") + knowledgeNames)
            }
            SectionCard(title = "Skills") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = s.skills == null, onClick = { save(s.copy(skills = null)) }, label = { Text("All enabled") }, modifier = Modifier.semantics { contentDescription = "Offer all enabled skills" })
                    for (sk in skills) {
                        val on = s.skills?.contains(sk.name) == true
                        FilterChip(selected = on, onClick = { val cur = s.skills ?: emptyList(); save(s.copy(skills = if (on) cur - sk.name else cur + sk.name)) }, label = { Text(sk.name) },
                            modifier = Modifier.semantics { contentDescription = "Skill ${sk.name} ${if (on) "selected" else "not selected"}" })
                    }
                }
            }
            SectionCard(title = "Operator memory") {
                Text("Notes the assistant keeps between chats (memory_update). Shown to it as memory, never as your instructions. Keep secrets out.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(memory, { memory = it.take(ChatPrompt.MEMORY_MAX) }, minLines = 3, supportingText = { Text("${memory.length}/${ChatPrompt.MEMORY_MAX}") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Operator memory" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { runCatching { ChatPrefs.setMemory(ctx, memory) }.onSuccess { onMessage("Memory saved") }.onFailure { onMessage("Could not save memory: ${it.message}") } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save memory") }
                    TextButton(onClick = { memory = ""; runCatching { ChatPrefs.setMemory(ctx, "") } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Clear operator memory" }) { Text("Clear") }
                }
            }
            Text("After an approval from a notification or a restart, the assistant sees *** where a stored secret value was; a one-off confusion is expected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}
