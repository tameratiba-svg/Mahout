@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.ai.AiPrefs
import com.mob8n.ai.McpPrefs
import com.mob8n.core.label
import com.mob8n.core.Catalog
import com.mob8n.core.Graph
import com.mob8n.core.ParamKind
import com.mob8n.core.ParamSpec
import com.mob8n.core.asText
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** DESIGN6 §6.7: text fields use Radius.s; the focused border stays M3's primary. */
private val FieldShape = RoundedCornerShape(Radius.s)

/** Everything the `{ }` helper needs to list upstream fields for one node. */
class UpstreamHelper(val graph: Graph, val nodeId: String, val workflowId: String, val engine: Engine, val catalog: Catalog)

/** (section title, snippets): one section per upstream node + "Globals". */
@Composable
fun rememberSnippets(h: UpstreamHelper?): List<Pair<String, List<String>>> {
    val chain = remember(h) { h?.let { upstreamChain(it.graph, it.nodeId) } ?: emptyList() }
    val keys by produceState(emptyMap<String, List<String>>(), chain) {
        if (h == null) return@produceState
        value = chain.associate { (n, _) ->
            n.id to runCatching { h.engine.lastOutputKeys(h.workflowId, n.id) }.getOrDefault(emptyList()).ifEmpty { fallbackKeys(n.type, h.catalog) }
        }
    }
    return chain.map { (n, direct) -> n.name to (keys[n.id] ?: emptyList()).map { fieldSnippet(n, direct, it) } }.filter { it.second.isNotEmpty() } +
        ("Globals" to GLOBAL_SNIPPETS)
}

/** One composable per ParamKind (DESIGN §9.4). `value` is the raw stored JSON; `onChange(null)` clears. */
@Composable
fun ParamWidget(p: ParamSpec, value: JsonElement?, onChange: (JsonElement?) -> Unit, engine: Engine, helper: UpstreamHelper?, modifier: Modifier = Modifier) {
    val error = remember(value, p) { p.validate(value) }
    val v = value?.takeUnless { it is JsonNull }
    when (p.kind) {
        ParamKind.TEXT, ParamKind.MULTILINE -> if (helper != null && p.kind == ParamKind.TEXT && (p.key == "server" || p.key == "tool") && helper.graph.node(helper.nodeId)?.type?.startsWith("ai.mcp_") == true) {
            // DESIGN3 §6.3: MCP server names from Settings; tool names = lastTools of the server named in the node's committed params.
            val ctx = LocalContext.current
            LaunchedEffect(Unit) { McpPrefs.load(ctx) }
            val servers by McpPrefs.servers.collectAsStateWithLifecycle()
            val server = helper.graph.node(helper.nodeId)?.params?.get("server").asTextOrNull()?.trim()
            val suggestions = remember(servers, server, p.key) {
                if (p.key == "server") servers.filter { it.enabled }.map { it.name } else servers.firstOrNull { it.name.equals(server, true) }?.lastTools ?: emptyList()
            }
            SuggestField(p.label, v.asText(), suggestions, { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) }, p.help, error, modifier)
        } else if (p.key == "model" && helper?.graph?.node(helper.nodeId)?.type?.startsWith("ai.") == true) {
            // DESIGN2 §2 (optional): model suggestions from the node's provider (or every connected provider for default/auto).
            val ctx = LocalContext.current
            LaunchedEffect(Unit) { AiPrefs.load(ctx) }   // idempotent; fills providers when the settings screen was never opened this process
            val providers by AiPrefs.providers.collectAsStateWithLifecycle()
            val provider = helper.graph.node(helper.nodeId)?.params?.get("provider").asTextOrNull()
            val models = remember(providers, provider) { providers[provider]?.models ?: providers.values.filter { it.hasKey }.flatMap { it.models }.distinct() }
            SuggestField(p.label, v.asText(), models, { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) }, p.help, error, modifier)
        } else TemplateTextField(
            label = p.label, value = v.asText(), onChange = { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) },
            multiline = p.kind == ParamKind.MULTILINE, help = p.help, error = error, snippets = if (p.templated) rememberSnippets(helper) else null, modifier = modifier,
        )
        ParamKind.NUMBER -> {
            val range = listOfNotNull(p.min?.let { "min ${fmtNum(it)}" }, p.max?.let { "max ${fmtNum(it)}" }).joinToString(", ")
            TemplateTextField(
                label = p.label, value = numText(v), onChange = { onChange(parseNumber(it)) }, multiline = false,
                help = listOf(p.help, range).filter { it.isNotBlank() }.joinToString(" · "), error = error,
                snippets = if (p.templated) rememberSnippets(helper) else null, keyboard = KeyboardType.Decimal, modifier = modifier,
            )
        }
        ParamKind.BOOL -> SwitchRow(p.label, p.help, v?.let { it.asText().toBooleanStrictOrNull() } ?: false) { onChange(JsonPrimitive(it)) }
        ParamKind.ENUM -> EnumDropdown(p.label, p.options, v.asText(), { onChange(JsonPrimitive(it)) }, help = p.help, error = error, modifier = modifier)
        ParamKind.DURATION -> DurationField(p, v, onChange, error, modifier)
        ParamKind.TIME -> TimeField(p, v.asText(), onChange, error, modifier)
        ParamKind.APP -> AppField(p, v.asText(), onChange, error, modifier)
        ParamKind.PLAYLIST -> {
            val names by engine.playlistNames().collectAsStateWithLifecycle(emptyList())
            SuggestField(p.label, v.asText(), names, { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) }, p.help, error, modifier)
        }
        ParamKind.LABELS -> {
            // DESIGN3 §6.3: knowledge source / MCP server suggestions as chips (tap = add).
            val type = helper?.graph?.node(helper.nodeId)?.type
            val ctx = LocalContext.current
            val knowledgeKeys = (type == "ai.agent" && p.key == "knowledge") || (type == "data.knowledge_search" && p.key == "sources")
            val suggestions by produceState(emptyList<String>(), type, p.key) {
                this.value = when {   // this.value: the outer `value` is ParamWidget's JsonElement
                    knowledgeKeys -> runCatching { engine.knowledge.names() }.getOrDefault(emptyList())
                    type == "ai.agent" && p.key == "mcpServers" -> runCatching { McpPrefs.load(ctx); McpPrefs.names(ctx) }.getOrDefault(emptyList())
                    // DESIGN4 §9.4 / §7.3: skill names from the Skills table; node ids a logic.js script may call.
                    type == "ai.agent" && p.key == "skills" -> runCatching { engine.skills().first().map { it.name } }.getOrDefault(emptyList())
                    type == "logic.js" && p.key == "allowNodes" -> helper?.catalog?.agentTools()?.map { it.spec.id } ?: emptyList()
                    else -> emptyList()
                }
            }
            LabelsEditor(p, (v as? JsonArray)?.mapNotNull { it.asTextOrNull() } ?: emptyList(), { onChange(if (it.isEmpty()) null else JsonArray(it.map(::JsonPrimitive))) }, error, modifier, suggestions)
        }
        ParamKind.ROWS -> RowsEditor(p, (v as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList(), { onChange(if (it.isEmpty()) null else JsonArray(it)) }, error, engine, helper, modifier)
        ParamKind.WORKFLOW -> {
            val wfs by engine.workflows().collectAsStateWithLifecycle(emptyList())
            val options = wfs.map { workflowLabel(it.name, it.id) }   // resolved by index, so duplicate names cannot store the wrong id
            val sel = wfs.indexOfFirst { it.id == v.asText() }
            EnumDropdown(p.label, options, if (sel >= 0) options[sel] else "", { n -> wfs.getOrNull(options.indexOf(n))?.let { onChange(JsonPrimitive(it.id)) } }, help = p.help, error = error, modifier = modifier)
        }
        ParamKind.SECRET -> SecretNameField(p, v.asText(), onChange, error, modifier)
    }
}

private fun fmtNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
/** Field text for a stored NUMBER: the primitive's own literal ("0.0" stays "0.0"), so typing "0.0" is not snapped back to "0" (F33). */
internal fun numText(v: JsonElement?): String = (v as? JsonPrimitive)?.content ?: ""

/** Text field whose external value can be reset without losing the caret while typing; optional `{ }` helper popover. */
@Composable
fun TemplateTextField(
    label: String, value: String, onChange: (String) -> Unit, multiline: Boolean, help: String, error: String?,
    snippets: List<Pair<String, List<String>>>?, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier,
    visual: VisualTransformation = VisualTransformation.None, trailing: (@Composable () -> Unit)? = null,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) { if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length)) }
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = tfv, onValueChange = { tfv = it; onChange(it.text) }, label = { Text(label) }, modifier = modifier.fillMaxWidth(), shape = FieldShape,
        singleLine = !multiline, minLines = if (multiline) 3 else 1, isError = error != null,
        supportingText = { Text(error ?: help) }, visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = if (trailing != null) trailing else if (snippets != null) ({
            IconButton(onClick = { open = true }) { Icon(Icons.Rounded.DataObject, contentDescription = "Insert field from upstream node") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(Modifier.widthIn(max = 340.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    for ((title, list) in snippets) {
                        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in list) SuggestionChip(onClick = {
                                val sel = tfv.selection
                                val t = tfv.text.replaceRange(sel.min, sel.max, s)
                                tfv = TextFieldValue(t, TextRange(sel.min + s.length)); onChange(t); open = false
                            }, label = { Text(s, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.semantics { contentDescription = "Insert $s" })
                        }
                    }
                }
            }
        }) else null,
    )
}

@Composable
fun SwitchRow(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) }, supportingContent = if (help.isNotBlank()) ({ Text(help) }) else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label }) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),   // sits on sheets / cards of any tone
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
fun EnumDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit, help: String = "", error: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, label = { Text(label) }, isError = error != null, enabled = enabled, shape = FieldShape,
            supportingText = if (error != null || help.isNotBlank()) ({ Text(error ?: help) }) else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(Radius.m)) {
            for (o in options) DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false })
        }
    }
}

/** Free text with suggestions (PLAYLIST, AI model ids). */
@Composable
internal fun SuggestField(label: String, value: String, suggestions: List<String>, onChange: (String) -> Unit, help: String, error: String?, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val shown = suggestions.filter { it.contains(value, true) && it != value }
    ExposedDropdownMenuBox(expanded = open && shown.isNotEmpty(), onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = { onChange(it); open = true }, label = { Text(label) }, singleLine = true, isError = error != null, shape = FieldShape,
            supportingText = { Text(error ?: help) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = open && shown.isNotEmpty(), onDismissRequest = { open = false }) {
            for (s in shown) DropdownMenuItem(text = { Text(s) }, onClick = { onChange(s); open = false })
        }
    }
}

@Composable
private fun DurationField(p: ParamSpec, v: JsonElement?, onChange: (JsonElement?) -> Unit, error: String?, modifier: Modifier) {
    val raw = (v as? JsonPrimitive)?.takeIf { it.isString }?.content   // partial text while typing ("1.") stays raw
    val ms = if (raw == null) v.asText().toDoubleOrNull()?.toLong() else null
    var unit by remember(p.key) { mutableStateOf(ms?.let { splitDuration(it).second } ?: "s") }
    val amountText = raw ?: ms?.let { m -> fmtNum(m.toDouble() / (DURATION_UNITS.firstOrNull { it.first == unit }?.second ?: 1000L)) } ?: ""
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TemplateTextField(
            label = p.label, value = amountText,
            onChange = { t -> onChange(when { t.isBlank() -> null; t.last().isDigit() -> t.toDoubleOrNull()?.let { JsonPrimitive(joinDuration(it, unit)) } ?: JsonPrimitive(t); else -> JsonPrimitive(t) }) },
            multiline = false, help = p.help, error = error, snippets = null, keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        EnumDropdown("Unit", DURATION_UNITS.map { it.first }, unit, { u -> unit = u; ms?.let { onChange(JsonPrimitive(joinDuration(amountText.toDoubleOrNull() ?: 0.0, u))) } }, modifier = Modifier.width(110.dp))
    }
}

@Composable
private fun TimeField(p: ParamSpec, value: String, onChange: (JsonElement?) -> Unit, error: String?, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val parts = value.split(':')
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.minimumInteractiveComponentSize().semantics { contentDescription = "${p.label}: ${value.ifBlank { "not set" }}" }) {
            Text("${p.label}: ${value.ifBlank { "pick time" }}")
        }
        Text(error ?: p.help, style = MaterialTheme.typography.bodySmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        val st = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(JsonPrimitive("%02d:%02d".format(st.hour, st.minute))); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state = st) },
        )
    }
}

private class AppEntry(val label: String, val pkg: String, val icon: ImageBitmap?)

@Composable
private fun AppField(p: ParamSpec, value: String, onChange: (JsonElement?) -> Unit, error: String?, modifier: Modifier) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val shown = remember(value) { if (value.isBlank()) "choose app" else ctx.packageManager.label(value) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f).minimumInteractiveComponentSize().semantics { contentDescription = "${p.label}: $shown" }) { Text("${p.label}: $shown") }
            if (value.isNotBlank()) IconButton(onClick = { onChange(null) }) { Icon(Icons.Rounded.Close, contentDescription = "Clear ${p.label}") }
        }
        Text(error ?: p.help, style = MaterialTheme.typography.bodySmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        val apps by produceState<List<AppEntry>?>(null) {
            this.value = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                runCatching {
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                        .map { ri -> AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()) }
                        .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
                }.getOrDefault(emptyList())
            }
        }
        var q by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { open = false }) {
            Card {
                Column(Modifier.padding(16.dp).heightIn(max = 560.dp)) {
                    OutlinedTextField(q, { q = it }, label = { Text("Search apps") }, singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth())
                    val list = apps
                    if (list == null) Text("Loading…", Modifier.padding(16.dp))
                    else LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(list.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }, key = { it.pkg }) { a ->
                            ListItem(
                                headlineContent = { Text(a.label) }, supportingContent = { Text(a.pkg, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { a.icon?.let { Image(it, contentDescription = null, Modifier.size(36.dp)) } },
                                modifier = Modifier.clickable { onChange(JsonPrimitive(a.pkg)); open = false }.semantics { contentDescription = "Pick ${a.label}" },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelsEditor(p: ParamSpec, labels: List<String>, onChange: (List<String>) -> Unit, error: String?, modifier: Modifier, suggestions: List<String> = emptyList()) =
    LabelsField(p.label, p.help, labels, onChange, error, modifier, suggestions)

/** Ordered string list with suggestion chips (LABELS params; also the chat settings sheet and the skill editor, DESIGN4 §5.6 / §9.6). */
@Composable
internal fun LabelsField(label: String, help: String, labels: List<String>, onChange: (List<String>) -> Unit, error: String? = null, modifier: Modifier = Modifier, suggestions: List<String> = emptyList()) {
    var draft by remember { mutableStateOf("") }
    fun add() { val t = draft.trim(); if (t.isNotEmpty() && t !in labels) onChange(labels + t); draft = "" }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        val open = suggestions.filter { it !in labels }
        if (open.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in open.take(24)) SuggestionChip(onClick = { onChange(labels + s) }, label = { Text(s, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.semantics { contentDescription = "Add $s" })
        }
        labels.forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(l, Modifier.weight(1f))
                IconButton(onClick = { if (i > 0) onChange(labels.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move $l up") }
                IconButton(onClick = { if (i < labels.lastIndex) onChange(labels.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < labels.lastIndex) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move $l down") }
                IconButton(onClick = { onChange(labels - l) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove $l") }
            }
        }
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, label = { Text("Add ${label.lowercase()}") }, singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { IconButton(onClick = { add() }) { Icon(Icons.Rounded.Add, contentDescription = "Add") } },
            isError = error != null, supportingText = { Text(error ?: help) },
        )
    }
}

@Composable
private fun RowsEditor(p: ParamSpec, rows: List<JsonObject>, onChange: (List<JsonObject>) -> Unit, error: String?, engine: Engine, helper: UpstreamHelper?, modifier: Modifier) {
    Column(modifier) {
        Text(p.label, style = MaterialTheme.typography.labelLarge)
        rows.forEachIndexed { i, row ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Row ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { onChange(rows.toMutableList().apply { removeAt(i) }) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove row ${i + 1}") }
                    }
                    for (col in p.rows) if (isVisible(col, p.rows, row)) {
                        ParamWidget(col, row[col.key], { nv ->
                            val m = row.toMutableMap(); if (nv == null) m.remove(col.key) else m[col.key] = nv
                            onChange(rows.toMutableList().apply { set(i, JsonObject(m)) })
                        }, engine, helper)
                    }
                }
            }
        }
        TextButton(onClick = { onChange(rows + JsonObject(p.rows.filter { it.default != null }.associate { it.key to it.default!! })) }) {
            Icon(Icons.Rounded.Add, contentDescription = null); Text("Add row")
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else if (p.help.isNotBlank()) Text(p.help, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SecretNameField(p: ParamSpec, value: String, onChange: (JsonElement?) -> Unit, error: String?, modifier: Modifier) {
    var reveal by remember { mutableStateOf(false) }
    Column(modifier) {
        TemplateTextField(
            label = p.label, value = value, onChange = { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) }, multiline = false,
            help = listOf(p.help, "Secret NAME; the value lives in Settings > AI / secrets").filter { it.isNotBlank() }.joinToString(". "), error = error, snippets = null,
            visual = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide" else "Reveal") } },
        )
        if (value.isBlank()) SuggestionChip(onClick = { onChange(JsonPrimitive(com.mob8n.core.SECRET_CLAUDE_KEY)) }, label = { Text(com.mob8n.core.SECRET_CLAUDE_KEY) }, modifier = Modifier.semantics { contentDescription = "Use claude_api_key" })
    }
}
