@file:OptIn(ExperimentalMaterial3Api::class)

package com.mob8n.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.engine.Engine
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(engine: Engine, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val names by remember { engine.playlistNames() }.collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    val current = selected ?: names.firstOrNull()
    val entries by remember(current) { engine.playlist(current) }.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = { MahoutTopBar("Playlist", onBack = onBack) },
        snackbarHost = { MahoutSnackbarHost(snack) },
    ) { pad ->
        val motion = LocalMotion.current
        val gutter = pageGutter()
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (names.isNotEmpty()) EnumDropdown("Playlist", names, current ?: "", { selected = it }, modifier = Modifier.padding(horizontal = gutter, vertical = Space.s))
            Text("Export .m3u: the \"Export playlist\" action makes Mahout write Music/Mob8N/<name>.m3u", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = gutter))
            if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, if (names.isEmpty()) "No playlist entries yet" else "Empty playlist", "The \"Add to playlist\" action adds songs here.", Modifier.padding(Space.xl))
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = gutter, top = Space.s, end = gutter, bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                items(entries, key = { it.id }) { e ->
                    ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.mahout.card),
                        modifier = Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect()).clip(RoundedCornerShape(Radius.m)),
                        headlineContent = { Text(e.title, maxLines = 1) },
                        supportingContent = { Text(listOfNotNull(e.artist, e.album, e.sourceApp, fmtTime(e.addedAt)).joinToString(" · "), style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { runCatching { engine.deletePlaylistEntry(e.id) }.onFailure { snack.showSnackbar("Delete failed: ${it.message}") } } }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Remove ${e.title}")
                            }
                        },
                    )
                }
            }
        }
    }
}
