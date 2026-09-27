@file:OptIn(ExperimentalMaterial3Api::class)

package com.mob8n.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.Note
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.apps.Workspace
import com.mob8n.engine.Engine
import com.mob8n.engine.knowledge.Hit
import com.mob8n.engine.knowledge.Knowledge
import com.mob8n.engine.knowledge.KnowledgeSource
import com.mob8n.engine.knowledge.SourceKind
import com.mob8n.engine.knowledge.Usage
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** "14 sources · 3 480 chunks · ≈ 4.1 MB text" (DESIGN3 §6.1). Pure. */
fun usageText(u: Usage): String = "${u.sources} sources · ${"%,d".format(u.chunks).replace(',', ' ')} chunks · ≈ ${fmtBytes(u.chars)} text"

fun fmtBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

/** "indexed 3 min ago" style relative time. */
fun fmtAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms).coerceAtLeast(0)
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        else -> "${d / 86_400_000} d ago"
    }
}

private fun kindIcon(k: SourceKind): ImageVector = when (k) {
    SourceKind.DOCUMENT -> Icons.Rounded.Description
    SourceKind.FOLDER -> Icons.Rounded.Folder
    SourceKind.URL -> Icons.Rounded.Link
    SourceKind.NOTES -> Icons.AutoMirrored.Rounded.Note
    SourceKind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
    SourceKind.TEXT -> Icons.AutoMirrored.Rounded.TextSnippet
}

/**
 * The knowledge base (DESIGN3 §6.1): stats, a search box to try queries, sources grouped by group (folders expand to their files),
 * pin / re-index / remove per row, Add menu (documents, folder, URL, paste, Notes / Playlist toggles), privacy card.
 * Every add runs in engine.scope so leaving the screen never cancels indexing; errors surface as snackbars.
 */
@Composable
fun KnowledgeScreen(engine: Engine, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val k = engine.knowledge
    val sources by k.sources.collectAsStateWithLifecycle(emptyList())
    val progress by k.progress.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf<Usage?>(null) }
    LaunchedEffect(sources, progress) { usage = runCatching { k.usage() }.getOrNull() }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    LaunchedEffect(query, sources) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(300)   // debounce while typing
        hits = runCatching { k.search(query, 10) }.getOrDefault(emptyList())
    }
    var addMenu by remember { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var removeRow by remember { mutableStateOf<KnowledgeSource?>(null) }
    var expanded by rememberSaveable { mutableStateOf("") }   // expanded folder ids, '\n'-joined (Bundle-safe)

    /** Long-running knowledge work: engine.scope (survives navigation); the snackbar waits at most 6 s for a host. */
    fun run(ok: String? = null, block: suspend () -> Unit) = engine.scope.launch {
        val r = runCatching { block() }
        val msg = r.fold({ ok }, { it.message ?: "Failed" }) ?: return@launch
        withTimeoutOrNull(6_000) { snack.showSnackbar(msg) }
    }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }   // Knowledge.addDocument does it too; harmless twice
            run { k.addDocument(uri) }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        tree ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        run { k.addFolder(tree) }
    }
    val notesOn = sources.any { it.kind == SourceKind.NOTES }
    val playlistOn = sources.any { it.kind == SourceKind.PLAYLIST }
    val tops = remember(sources) { sources.filter { it.parentId == null } }
    val children = remember(sources) { sources.filter { it.parentId != null }.groupBy { it.parentId!! } }
    val groups = remember(tops) { tops.groupBy { it.group } }

    Scaffold(
        topBar = {
            MahoutTopBar("Knowledge", onBack = onBack, actions = {
                Box {
                    IconButton(onClick = { topMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        DropdownMenuItem(text = { Text("Re-index all") }, onClick = { topMenu = false; for (s in tops) run { k.reindex(s.id) } }, modifier = Modifier.semantics { contentDescription = "Re-index all sources" })
                    }
                }
            })
        },
        snackbarHost = { MahoutSnackbarHost(snack) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add knowledge source") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Document(s)") }, leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                        onClick = { addMenu = false; docs.launch((Knowledge.SUPPORTED_MIMES + "*/*").toTypedArray()) }, modifier = Modifier.semantics { contentDescription = "Add documents" })
                    DropdownMenuItem(text = { Column { Text("Folder"); Text("Android 11+: pick a sub-folder, not Download or the root", style = MaterialTheme.typography.bodySmall) } },
                        leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) }, onClick = { addMenu = false; folder.launch(null) }, modifier = Modifier.semantics { contentDescription = "Add folder" })
                    DropdownMenuItem(text = { Text("URL") }, leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) }, onClick = { addMenu = false; urlDialog = true }, modifier = Modifier.semantics { contentDescription = "Add URL" })
                    DropdownMenuItem(text = { Text("Paste text") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, contentDescription = null) }, onClick = { addMenu = false; pasteDialog = true }, modifier = Modifier.semantics { contentDescription = "Add pasted text" })
                    DropdownMenuItem(text = { Text(if (notesOn) "Stop indexing my Notes" else "Index my Notes") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Note, contentDescription = null) },
                        onClick = { addMenu = false; run { k.setBuiltin(SourceKind.NOTES, !notesOn) } }, modifier = Modifier.semantics { contentDescription = "Index my Notes: ${if (notesOn) "on" else "off"}" })
                    DropdownMenuItem(text = { Text(if (playlistOn) "Stop indexing my Playlist" else "Index my Playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                        onClick = { addMenu = false; run { k.setBuiltin(SourceKind.PLAYLIST, !playlistOn) } }, modifier = Modifier.semantics { contentDescription = "Index my Playlist: ${if (playlistOn) "on" else "off"}" })
                }
            }
        },
    ) { pad ->
        val motion = LocalMotion.current
        val gutter = pageGutter()
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.s, gutter, 88.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            item(key = "search") {
                val u = usage
                if (u == null) SkeletonLines(1, Modifier.fillMaxWidth(0.6f).padding(vertical = Space.xs))
                else Text(usageText(u), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedTextField(query, { query = it }, label = { Text("Try a search") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = Space.xs).semantics { contentDescription = "Search knowledge" })
            }
            if (query.isNotBlank()) {
                if (hits.isEmpty()) item(key = "nohits") { Text("No matching passages", style = MaterialTheme.typography.bodySmall) }
                items(hits, key = { "hit:${it.sourceId}:${it.seq}" }) { h ->
                    SectionCard(Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect()).semantics(mergeDescendants = true) {}) {
                        Text("${h.source} · ${"%.2f".format(h.score)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(h.text.take(200), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
                    }
                }
            }
            if (sources.isEmpty()) item(key = "empty") {
                EmptyState(Icons.AutoMirrored.Rounded.LibraryBooks, "No sources yet", "Tap + to add documents, a folder, a URL or pasted text.", Modifier.fillMaxWidth().padding(top = Space.xl))
            }
            for ((group, rows) in groups) {
                if (group.isNotBlank()) item(key = "group:$group") { Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s).semantics { heading() }) }
                items(rows, key = { it.id }) { s ->
                    val isOpen = s.id in expanded.split('\n')
                    Column(Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        SourceRow(s, progress[s.id], k, ::run, onRemove = { removeRow = s },
                            expandable = s.kind == SourceKind.FOLDER, expanded = isOpen,
                            onToggle = { expanded = expanded.split('\n').filter { it.isNotBlank() }.let { if (isOpen) it - s.id else it + s.id }.joinToString("\n") })
                        if (s.kind == SourceKind.FOLDER) ExpandableSection(isOpen) {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) { for (c in children[s.id] ?: emptyList()) SourceRow(c, progress[c.id], k, ::run, onRemove = { removeRow = c }, indent = Space.xl) }
                        }
                    }
                }
            }
            item(key = "workspace") { WorkspaceFilesSection(k, ::run) }   // DESIGN4 §7.4 / V13
            item(key = "privacy") {
                SectionCard(Modifier.padding(top = Space.s).semantics(mergeDescendants = true) {}, title = "Knowledge stays on this device.") {
                    Text(KNOWLEDGE_PRIVACY.removePrefix("Knowledge stays on this device. "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
                }
            }
        }
    }

    if (urlDialog) TextDialog("Add URL", listOf("URL" to "https://"), onDismiss = { urlDialog = false }) { v -> urlDialog = false; run("Indexing ${v[0]}") { k.addUrl(v[0].trim()) } }
    if (pasteDialog) TextDialog("Paste text", listOf("Name" to "", "Text" to ""), multilineLast = true, paste = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "" },
        onDismiss = { pasteDialog = false }) { v -> pasteDialog = false; run("Indexing ${v[0]}") { k.addText(v[0].trim().ifBlank { v[1].lineSequence().first().take(40) }, v[1]) } }
    removeRow?.let { s ->
        AlertDialog(
            onDismissRequest = { removeRow = null }, title = { Text("Remove ${s.name}?") },
            text = { Text(if (s.kind == SourceKind.FOLDER) "The folder's files and their passages are removed from the index. Files on disk are untouched." else "Its passages are removed from the index. The file itself is untouched.") },
            confirmButton = { TextButton(onClick = { removeRow = null; run("Removed ${s.name}") { k.remove(s.id) } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = "Confirm remove ${s.name}" }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeRow = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceRow(
    s: KnowledgeSource, progress: Float?, k: Knowledge, run: (String?, suspend () -> Unit) -> Any, onRemove: () -> Unit,
    expandable: Boolean = false, expanded: Boolean = false, onToggle: () -> Unit = {}, indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var menu by remember { mutableStateOf(false) }
    SectionCard(Modifier.padding(start = indent).semantics(mergeDescendants = true) {}, onClick = if (expandable) onToggle else null) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(s.kind), contentDescription = s.kind.name.lowercase().replaceFirstChar(Char::titlecase), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(listOfNotNull(fmtBytes(s.bytes).takeIf { s.bytes > 0 }, "${s.chunks} chunks").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (expandable) RotatingChevron(expanded, Modifier.semantics { contentDescription = if (expanded) "Collapse folder" else "Expand folder" })
                IconToggleButton(checked = s.pinned, onCheckedChange = { on -> run(null) { k.setPinned(s.id, on) } }, modifier = Modifier.semantics { contentDescription = "Pinned: always included in agent prompts" }) {
                    Icon(if (s.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, contentDescription = null)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${s.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Re-index") }, onClick = { menu = false; run("Re-indexing ${s.name}") { k.reindex(s.id) } }, modifier = Modifier.semantics { contentDescription = "Re-index ${s.name}" })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() }, modifier = Modifier.semantics { contentDescription = "Remove ${s.name}" })
                    }
                }
            }
            when {
                progress != null -> {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = Space.xs))
                    Text("Indexing ${(progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                s.error != null -> Text(s.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                s.indexedAt != null -> Text("indexed ${fmtAgo(s.indexedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("Waiting to index…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

/** Small N-field dialog; `paste` adds a "Paste" button filling the last field from the clipboard. */
@Composable
private fun TextDialog(title: String, fields: List<Pair<String, String>>, multilineLast: Boolean = false, paste: (() -> String)? = null, onDismiss: () -> Unit, onOk: (List<String>) -> Unit) {
    val values = remember { fields.map { mutableStateOf(it.second) } }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, (label, _) ->
                    val last = i == fields.lastIndex
                    OutlinedTextField(values[i].value, { values[i].value = it }, label = { Text(label) }, singleLine = !(last && multilineLast), minLines = if (last && multilineLast) 4 else 1,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title $label" })
                }
                if (paste != null) TextButton(onClick = { values.last().value = paste() }, modifier = Modifier.semantics { contentDescription = "Paste from clipboard" }) { Text("Paste") }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(values.map { it.value }) }, enabled = values.last().value.isNotBlank(), modifier = Modifier.semantics { contentDescription = "Add" }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Workspace files (DESIGN4 §7.4, V12/V13): the coding sandbox's folder under Android/data — NOT browsable in the Files app on Android 11+, so this
 * section is the user's way to preview, Share (FileProvider), index or delete what run_shell / run_js / workspace_write produced.
 */
// ponytail: folders expand one level; upgrade = breadcrumb navigation
@Composable
private fun WorkspaceFilesSection(k: Knowledge, run: (String?, suspend () -> Unit) -> Any) {
    val ctx = LocalContext.current
    val root = remember { runCatching { Workspace.root(ctx) }.getOrNull() }
    var refresh by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(false) }
    var expandedDir by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Workspace.Entry?>(null) }
    var deleting by remember { mutableStateOf<Workspace.Entry?>(null) }
    val size by produceState(0 to 0L, refresh, root) { value = withContext(Dispatchers.IO) { root?.let { runCatching { Workspace.size(it) }.getOrNull() } ?: (0 to 0L) } }
    val entries by produceState(emptyList<Workspace.Entry>(), open, refresh, root) {
        if (!open) return@produceState   // keep the last listing while the section collapses (no "No files yet" flash)
        value = if (root == null) emptyList() else withContext(Dispatchers.IO) { runCatching { Workspace.list(root) }.getOrDefault(emptyList()) }
    }
    val children by produceState(emptyList<Workspace.Entry>(), expandedDir, refresh, root) {
        value = if (expandedDir.isBlank() || root == null) emptyList() else withContext(Dispatchers.IO) { runCatching { Workspace.list(root, expandedDir) }.getOrDefault(emptyList()) }
    }
    SectionCard(Modifier.padding(top = Space.s).semantics(mergeDescendants = true) {}, onClick = { open = !open }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.m))
                Text("Workspace files · ${size.first} file${if (size.first == 1) "" else "s"} · ${fmtBytes(size.second)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                RotatingChevron(open, Modifier.semantics { contentDescription = if (open) "Collapse workspace files" else "Expand workspace files" })
            }
            Text("${root?.path ?: "unavailable"} — ${Workspace.VISIBILITY}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    ExpandableSection(open) { Column {
        if (entries.isEmpty()) Text("No files yet. The chat operator's run_shell / run_js / workspace_write tools and the app.shell_run / logic.js nodes write here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        @Composable fun row(e: Workspace.Entry, indent: androidx.compose.ui.unit.Dp) {
            val name = e.path.substringAfterLast('/')
            SectionCard(Modifier.padding(start = indent, top = Space.xs), description = "${if (e.dir) "Folder" else "File"} $name",
                onClick = { if (e.dir) expandedDir = if (expandedDir == e.path) "" else e.path else preview = e }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (e.dir) Icons.Rounded.Folder else Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Space.m))
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(if (e.dir) fmtAgo(e.modified) else "${fmtBytes(e.bytes)} · ${fmtAgo(e.modified)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        for (e in entries) {
            row(e, 0.dp)
            if (e.dir && expandedDir == e.path) for (c in children) row(c, Space.xl)
        }
    } }
    preview?.let { e ->
        val name = e.path.substringAfterLast('/')
        val read by produceState<Workspace.Read?>(null, e.path) { value = withContext(Dispatchers.IO) { root?.let { runCatching { Workspace.read(it, e.path, 0, 64 * 1024) }.getOrNull() } } }
        AlertDialog(
            onDismissRequest = { preview = null }, title = { Text(name) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("${fmtBytes(e.bytes)} · modified ${fmtAgo(e.modified)}", style = MaterialTheme.typography.bodySmall)
                    when {
                        read == null -> Text("Reading…", style = MaterialTheme.typography.bodySmall)
                        read!!.binary -> Text("Binary file — no preview. Use Share to export it.", style = MaterialTheme.typography.bodySmall)
                        else -> Text(read!!.text + (if (read!!.truncated) "\n…(preview truncated)" else ""), style = CodeSmallStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Space.s))
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            val uri = Workspace.shareUri(ctx, Workspace.resolve(root!!, e.path))
                            val send = Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, "Share $name"))
                        }.onFailure { run("Share failed: ${it.message}") {} }
                    }, modifier = Modifier.semantics { contentDescription = "Share $name" }) { Text("Share") }
                    TextButton(enabled = read != null && !read!!.binary, onClick = {
                        preview = null
                        if (e.bytes > 2L * 1024 * 1024) run("Too large for knowledge (2 MB max)") {}
                        else run("Indexing $name") { val r = withContext(Dispatchers.IO) { Workspace.read(root!!, e.path, 0, 2 * 1024 * 1024) }; k.addText(name, r.text, group = "workspace") }
                    }, modifier = Modifier.semantics { contentDescription = "Add $name to knowledge" }) { Text("Add to knowledge") }
                    TextButton(onClick = { deleting = e; preview = null }, modifier = Modifier.semantics { contentDescription = "Delete $name" }) { Text("Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete ${e.path.substringAfterLast('/')}?") }, text = { Text("The file is removed from the workspace. Knowledge passages indexed from it stay until you remove that source.") },
            confirmButton = { TextButton(onClick = { deleting = null; run("Deleted ${e.path}") { withContext(Dispatchers.IO) { if (!Workspace.delete(root!!, e.path)) error("Not deleted (folder not empty?)") }; refresh++ } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.semantics { contentDescription = "Confirm delete ${e.path}" }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
