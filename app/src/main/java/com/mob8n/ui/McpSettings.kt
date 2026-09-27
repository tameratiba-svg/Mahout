@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.ai.McpAuth
import com.mob8n.ai.McpPrefs
import com.mob8n.ai.McpPreset
import com.mob8n.ai.McpPresets
import com.mob8n.ai.McpServer
import kotlinx.coroutines.launch
import java.util.UUID

const val MCP_HELP = "Remote MCP servers over Streamable HTTP (https://…/mcp). Servers that need OAuth sign-in are not supported — paste a token instead. Android cannot run local (stdio) MCP servers."
private val AUTH_LABELS = listOf("None", "Bearer token", "Custom header")
private fun McpAuth.label() = AUTH_LABELS[ordinal]
private fun authOf(label: String) = McpAuth.entries[AUTH_LABELS.indexOf(label).coerceAtLeast(0)]

/** Settings > AI > MCP servers (DESIGN3 §6.2). Reads/writes only through McpPrefs; the secret draft lives in the field until Save and is never shown again. */
@Composable
fun McpSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { McpPrefs.load(ctx) }
    val servers by McpPrefs.servers.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf<String?>(null) }   // preset note shown in the edit sheet
    var fabMenu by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { MahoutTopBar("MCP servers", onBack = onBack) },
        snackbarHost = { MahoutSnackbarHost(snack) },
        floatingActionButton = {
            // DESIGN5 §8.1: FAB -> "Add server" / "Add preset…"
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add MCP server or preset") }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text("Add server") }, onClick = { fabMenu = false; editing = McpServer(UUID.randomUUID().toString(), "", ""); isNew = true; editNote = null },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add server" })
                    DropdownMenuItem(text = { Text("Add preset…") }, onClick = { fabMenu = false; presets = true },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add preset" })
                }
            }
        },
    ) { pad ->
        val motion = LocalMotion.current
        val gutter = pageGutter()
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.s, gutter, 88.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            item(key = "help") { Text(MCP_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (servers.isEmpty()) item(key = "empty") { EmptyState(Icons.Rounded.Hub, "No servers yet", "Tap + to add one.", Modifier.fillMaxWidth().padding(top = Space.xl)) }
            items(servers, key = { it.id }) { s ->
                SectionCard(Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect()),
                    description = "MCP server ${s.name}, ${if (s.enabled) "enabled" else "disabled"}${if (s.trusted) ", trusted" else ""}. Tap to edit", onClick = { editing = s; isNew = false }) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(runCatching { Uri.parse(s.url).host }.getOrNull() ?: s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.trusted) StatusPill("trusted", Tone.Caution, Modifier.clearAndSetSemantics { contentDescription = "Trusted: tools run without approval" })
                            Switch(checked = s.enabled, onCheckedChange = { on -> McpPrefs.save(ctx, s.copy(enabled = on), null) }, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "${if (s.enabled) "Disable" else "Enable"} ${s.name}" })
                        }
                        when {
                            s.lastError != null -> Text(s.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            s.testedAt != null -> Text("Tested ${fmtTime(s.testedAt)} · ${s.lastTools.size} tools${s.protocol?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                            else -> Text("Not tested yet", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.lastTools.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (t in s.lastTools.take(12)) SuggestionChip(onClick = {}, label = { Text(t, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.semantics { contentDescription = "Tool $t" })
                            if (s.lastTools.size > 12) Text("+${s.lastTools.size - 12}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
    editing?.let { s -> McpEditSheet(s, isNew, onClose = { editing = null; editNote = null }, note = editNote) }
    if (presets) PresetSheet(onDismiss = { presets = false }, onPick = { server, note -> presets = false; editing = server; isNew = true; editNote = note })
}

/** DESIGN5 §8.1: pick a preset + the Mac's LAN IP -> the edit sheet prefilled (untrusted; McpPrefs.save enforces the LAN rule on Save). */
@Composable
private fun PresetSheet(onDismiss: () -> Unit, onPick: (McpServer, String) -> Unit) {
    var picked by remember { mutableStateOf<McpPreset?>(McpPresets.ALL.firstOrNull()) }
    var host by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.l, end = Space.l, bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Add preset", style = MaterialTheme.typography.titleLarge)
            for (p in McpPresets.ALL) ListItem(
                headlineContent = { Text(p.name) }, supportingContent = { Text(p.urlTemplate) },
                leadingContent = { RadioButton(selected = picked == p, onClick = null) },
                modifier = Modifier.selectable(selected = picked == p, role = Role.RadioButton) { picked = p }.semantics { contentDescription = "Preset ${p.name}, ${p.urlTemplate}" },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            picked?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Mac IP on your LAN") }, singleLine = true, placeholder = { Text("192.168.1.10") },
                supportingText = { Text("Replaces ${McpPresets.HOST} in the URL; http:// is only allowed for LAN addresses") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mac IP on your LAN" })
            PillButton("Continue", onClick = { picked?.let { onPick(McpPresets.instantiate(it, host), it.note) } }, enabled = picked != null && host.isNotBlank(),
                contentDescription = "Continue with preset ${picked?.name ?: ""}")
            ListItem(headlineContent = { Text("Laya decision engine") }, supportingContent = { Text("Settings > AI > Decision engine (HTTP API, not MCP)") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Laya decision engine: set it up under Settings, AI, Decision engine. It is an HTTP API, not an MCP server" })
        }
    }
}

@Composable
private fun McpEditSheet(initial: McpServer, isNew: Boolean, onClose: () -> Unit, note: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var auth by remember { mutableStateOf(initial.auth) }
    var headerName by remember { mutableStateOf(initial.headerName) }
    var secretDraft by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var trusted by remember { mutableStateOf(initial.trusted) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(!isNew) }
    var hasSecret by remember { mutableStateOf(initial.hasSecret) }

    /** Upsert; secret "" keeps the stored one (null), a typed draft replaces it, auth NONE removes it. Validation text comes from McpPrefs. */
    fun save(): Boolean {
        val s = initial.copy(name = name.trim(), url = url.trim(), auth = auth, headerName = headerName.trim().ifBlank { "Authorization" }, trusted = trusted)
        return try {
            McpPrefs.save(ctx, s, when { auth == McpAuth.NONE -> ""; secretDraft.isNotEmpty() -> secretDraft; else -> null })
            if (auth == McpAuth.NONE) hasSecret = false else if (secretDraft.isNotEmpty()) hasSecret = true
            secretDraft = ""; reveal = false; error = null; saved = true; true
        } catch (e: IllegalArgumentException) { error = e.message ?: "Invalid"; false }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.l, end = Space.l, bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(if (isNew) "Add MCP server" else "Edit ${initial.name}", style = MaterialTheme.typography.titleLarge)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, supportingText = { Text("Used in the Agent's MCP servers list and as the tool prefix; ≤ 40 characters") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "MCP server name" })
            OutlinedTextField(url, { url = it; error = null }, label = { Text("URL") }, singleLine = true, isError = error != null, placeholder = { Text("https://host/mcp") },
                supportingText = { Text(error ?: "Streamable HTTP endpoint; http:// only for localhost, *.local and private LAN addresses") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "MCP server URL" })
            EnumDropdown("Auth", AUTH_LABELS, auth.label(), { auth = authOf(it) }, modifier = Modifier.semantics { contentDescription = "Authentication type" })
            if (auth == McpAuth.HEADER) OutlinedTextField(headerName, { headerName = it }, label = { Text("Header name") }, singleLine = true, placeholder = { Text("X-API-Key") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Custom header name" })
            if (auth != McpAuth.NONE) {
                OutlinedTextField(
                    value = secretDraft, onValueChange = { secretDraft = it }, singleLine = true,
                    label = { Text(if (hasSecret) "Replace ${if (auth == McpAuth.BEARER) "token" else "header value"}" else if (auth == McpAuth.BEARER) "Bearer token" else "Header value") },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),   // secret hygiene: no IME dictionary learning
                    trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide secret" else "Reveal secret") } },
                    supportingText = {
                        Text(when {
                            secretDraft.isNotEmpty() && secretDraft.length < 8 -> "Warning: tokens shorter than 8 characters cannot be masked in logs"
                            hasSecret -> "A secret is saved and never shown again; leave blank to keep it"
                            else -> "Stored app-private on this device, excluded from backup, masked in logs"
                        })
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "MCP secret" },
                )
            }
            SwitchRow("Trusted — run tools without approval", "Only for read-only servers you control. Tool results are still treated as data.", trusted) { trusted = it }
            // ponytail: boolean trust; upgrade = honour readOnlyHint per tool
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                PillButton("Save", onClick = { if (save()) result = "Saved." }, enabled = name.isNotBlank() && url.isNotBlank() && !busy, contentDescription = "Save MCP server")
                PillButton("Test", onClick = {
                    if (!save()) return@PillButton
                    busy = true; result = null
                    scope.launch { result = McpPrefs.test(ctx, initial.id).fold({ "OK: $it" }, { "Failed: ${it.message ?: "unknown error"}" }); busy = false }
                }, enabled = name.isNotBlank() && url.isNotBlank() && !busy, tone = Tone.Neutral, outlined = true, contentDescription = "Save and test MCP server")
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                if (!isNew || saved) TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Delete MCP server" }) { Text("Delete") }
            }
            result?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("OK") || it.startsWith("Saved")) MaterialTheme.mahout.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${name.ifBlank { "this server" }}?") }, text = { Text("Its saved token is removed too. Workflows naming it will fail until you add it again.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; McpPrefs.delete(ctx, initial.id); onClose() }, modifier = Modifier.semantics { contentDescription = "Confirm delete" }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
