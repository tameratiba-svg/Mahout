@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mob8n.core.Catalog
import com.mob8n.core.Node
import com.mob8n.core.NodeKind

/** Node palette (DESIGN §9.2): search over the catalog, one section per NodeKind, gated/limited chips. */
@Composable
fun PaletteSheet(catalog: Catalog, onPick: (Node) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results = remember(q) { catalog.search(q) }
    var firstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { firstLoad = false }
    val sections = remember(results) { NodeKind.entries.map { k -> k to results.filter { it.spec.kind == k } }.filter { it.second.isNotEmpty() } }
    // DESIGN6 §6.7: half -> full drag, pinned search, sections stagger in once.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = Radius.l, topEnd = Radius.l)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = q, onQueryChange = { q = it }, onSearch = {}, expanded = false, onExpandedChange = {},
                        placeholder = { Text("Search nodes") }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        modifier = Modifier.semantics { contentDescription = "Search nodes" },
                    )
                },
                expanded = false, onExpandedChange = {}, windowInsets = WindowInsets(0.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {}
            if (sections.isEmpty()) Text("No nodes match \"$q\"", Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                sections.forEachIndexed { si, (kind, nodes) ->
                    item(key = "h-$kind", contentType = "header") {
                        Row(Modifier.enterOnce(si, firstLoad).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(kindIcon(kind), contentDescription = null, tint = kindColor(kind))
                            Spacer(Modifier.width(8.dp))
                            Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, color = kindColor(kind))
                        }
                    }
                    items(nodes, key = { it.spec.id }, contentType = { "node" }) { n ->
                        val s = n.spec
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = {
                                Column {
                                    Text(s.description, style = MaterialTheme.typography.bodySmall)
                                    if (s.optional || s.gates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (s.optional) GateLabel("gated/limited", "Device-dependent or limited")
                                        for (g in s.gates) GateLabel(g.title, if (g.enforced) "Needs ${g.title}" else "Optional: ${g.title}")
                                    }
                                }
                            },
                            leadingContent = { Icon(nodeIcon(s.id, s.kind), contentDescription = null, tint = kindColor(s.kind)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.enterOnce(si, firstLoad).clickable { onPick(n) }.semantics { contentDescription = "Add ${s.name}" },
                        )
                    }
                }
            }
        }
    }
}

/** Non-interactive outlined label (was AssistChip with an empty onClick: a dead button that swallowed taps meant for the row). */
@Composable
private fun GateLabel(text: String, description: String) = Surface(
    shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface,
    modifier = Modifier.semantics { contentDescription = description },
) { Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
