@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mob8n.core.Catalog
import com.mob8n.core.Gate
import com.mob8n.core.GrantKind
import com.mob8n.core.Graph
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeSpec
import com.mob8n.engine.Engine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Schema-driven node configuration (DESIGN §9.4). `onLive` is called when a definesPorts LABELS param changes so the canvas
 * ports follow; `onSave` commits name/params/timeout.
 */
@Composable
fun ParamSheet(
    node: NodeInstance, spec: NodeSpec, graph: Graph, workflowId: String, engine: Engine, catalog: Catalog,
    onLive: (NodeInstance) -> Unit, onSave: (NodeInstance) -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val grant = rememberGranter()
    var name by remember(node.id) { mutableStateOf(node.name) }
    var params by remember(node.id) { mutableStateOf(node.params) }
    val original = remember(node.id) { node }   // `node` is re-read from the live graph after each onLive; Cancel restores this one
    var timeoutText by remember(node.id) { mutableStateOf(node.timeoutMs?.let { (it / 1000).toString() } ?: "") }
    val helper = remember(graph, node.id) { UpstreamHelper(graph, node.id, workflowId, engine, catalog) }

    val visible = spec.params.filter { isVisible(it, spec.params, params) }
    val nameError = when {
        name.isBlank() -> "Name is required"
        name.contains('.') -> "Name may not contain '.'"
        graph.nodes.any { it.id != node.id && it.name == name } -> "Another node has this name"
        else -> null
    }
    val timeoutError = if (timeoutText.isNotBlank() && (timeoutText.toLongOrNull() ?: 0L) <= 0L) "Whole seconds > 0" else null
    val valid = nameError == null && timeoutError == null && visible.all { it.validate(params[it.key]) == null }

    fun set(key: String, v: JsonElement?) {
        val m = params.toMutableMap(); if (v == null) m.remove(key) else m[key] = v
        params = JsonObject(m)
        // ports-only live push: successive port edits stack on the graph node, unrelated pending edits stay in the sheet until Save
        if (spec.param(key)?.definesPorts == true) onLive(withParam(node, key, v))
    }
    // hidden (visibleWhen) params are dropped so the sheet's visibility-based `valid` matches what Graph.validate sees
    fun build() = node.copy(name = name.trim(), params = visibleParams(spec, params), timeoutMs = timeoutText.toLongOrNull()?.times(1000))
    // ponytail: dirty stays true after a reverted live edit; upgrade path = compare graph to loaded.graph in Editor
    fun cancel() { if (node != original) onLive(original); onDismiss() }

    ModalBottomSheet(onDismissRequest = ::cancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = Radius.l, topEnd = Radius.l)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(nodeIcon(spec.id, spec.kind), contentDescription = null, tint = kindColor(spec.kind))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium)
                    Text(spec.description, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Node name") }, singleLine = true, isError = nameError != null, shape = RoundedCornerShape(Radius.s),
                supportingText = { Text(nameError ?: "Used as {{\$node.${name.ifBlank { "Name" }}.field}} downstream") }, modifier = Modifier.fillMaxWidth())
            if (spec.gates.isNotEmpty()) {
                Text("Needs", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (g in spec.gates) {
                        val ok = remember(g, tick) { runCatching { g.granted(ctx) }.getOrDefault(false) }
                        AssistChip(
                            onClick = { if (!ok && g.grantable()) grant(g) },
                            label = { Text(if (ok) g.title else if (g.grantable()) "Grant: ${g.title}" else "Missing: ${g.title}") },
                            leadingIcon = { StatusIcon(ok, info = g.kind == GrantKind.INFO || !g.enforced) },
                            modifier = Modifier.semantics { contentDescription = "${g.label}: ${if (ok) "granted" else "missing"}" },
                        )
                    }
                }
            }
            OutlinedTextField(timeoutText, { timeoutText = it.filter { c -> c.isDigit() } }, label = { Text("Timeout (s)") }, singleLine = true, shape = RoundedCornerShape(Radius.s),
                isError = timeoutError != null, supportingText = { Text(timeoutError ?: "Default ${spec.timeoutMs / 1000} s") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (visible.isEmpty()) Text("No settings for this node.", style = MaterialTheme.typography.bodyMedium)
            for (p in visible) ParamWidget(p, params[p.key], { set(p.key, it) }, engine, helper)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Cancel", onClick = ::cancel, tone = Tone.Neutral, outlined = true)
                Spacer(Modifier.width(Space.s))
                PillButton("Save", onClick = { onSave(build()) }, enabled = valid)
            }
        }
    }
}
