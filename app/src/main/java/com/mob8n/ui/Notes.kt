@file:OptIn(ExperimentalMaterial3Api::class)

package com.mob8n.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.engine.Engine
import kotlinx.coroutines.launch

@Composable
fun NotesScreen(engine: Engine, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val notes by remember { engine.notes() }.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = { MahoutTopBar("Notes", onBack = onBack) },
        snackbarHost = { MahoutSnackbarHost(snack) },
    ) { pad ->
        if (notes.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            EmptyState(Icons.AutoMirrored.Rounded.StickyNote2, "No notes yet", "The \"Save note\" action writes here.", Modifier.padding(Space.xl))
        }
        val motion = LocalMotion.current
        val gutter = pageGutter()
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.s, gutter, Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(notes, key = { it.id }) { n ->
                var open by remember { mutableStateOf(false) }
                SectionCard(Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect())) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(fmtTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { open = !open }, modifier = Modifier.semantics { contentDescription = if (open) "Collapse" else "Expand" }) { RotatingChevron(open) }
                            // DESIGN3 §5.1: one note -> one TEXT knowledge source (same name replaces; long indexing runs in engine.scope so leaving the screen never cancels it)
                            IconButton(onClick = {
                                engine.scope.launch {
                                    val r = runCatching { engine.knowledge.addText(n.title, n.body) }
                                    snack.showSnackbar(r.fold({ "Added to knowledge: ${it.name}" }, { it.message ?: "Could not add to knowledge" }))
                                }
                            }) { Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = "Add ${n.title} to knowledge") }
                            IconButton(onClick = { scope.launch { runCatching { engine.deleteNote(n.id) }.onFailure { snack.showSnackbar("Delete failed: ${it.message}") } } }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete note ${n.title}")
                            }
                        }
                        Text(n.body, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 3, modifier = Modifier.padding(top = Space.xs))
                        n.runId?.let { rid -> androidx.compose.material3.TextButton(onClick = { onOpen(Screen.RunDetail(rid)) }) { Text("Open run") } }
                    }
                }
            }
        }
    }
}
