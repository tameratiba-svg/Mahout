@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.Mob8NApp
import com.mob8n.ai.ChatRunner
import com.mob8n.ai.ChatSettings
import com.mob8n.ai.OperatorTools
import com.mob8n.ai.SkillPresets
import com.mob8n.ai.Skills
import com.mob8n.ai.WorkflowTools
import com.mob8n.core.Catalog
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.Engine
import com.mob8n.engine.Skill
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Skills (DESIGN4 §9.6): list with enable switches, detail = rendered markdown + Edit / Delete / Use in chat, editor, "Restore presets".
 * One composable with three modes; Back walks editor -> detail -> list before App's parent handler takes over.
 */
@Composable
fun SkillsScreen(engine: Engine, catalog: Catalog, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val skills by engine.skills().collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    var menu by remember { mutableStateOf(false) }
    val selected = skills.firstOrNull { it.id == selectedId }
    fun msg(s: String) = scope.launch { snack.showSnackbar(s) }
    BackHandler(enabled = editing || selectedId != null) { if (editing) editing = false else selectedId = null }

    when {
        editing -> SkillEditor(selected, engine, catalog, onDone = { saved -> editing = false; if (saved != null) selectedId = saved.id }, onMessage = ::msg)
        selected != null -> Scaffold(
            topBar = {
                MahoutTopBar(selected.name, navigationIcon = { IconButton(onClick = { selectedId = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to skills") } }, actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit skill") }
                    IconButton(onClick = { deleting = selected }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete skill") }
                })
            },
            snackbarHost = { MahoutSnackbarHost(snack) },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = pageGutter(), vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    StatusPill(selected.createdBy, Tone.Info, Modifier.clearAndSetSemantics { contentDescription = "Created by ${selected.createdBy}" }, dot = false)
                    StatusPill("used ${selected.usageCount}×", Tone.Neutral, dot = false)
                    StatusPill(if (selected.enabled) "enabled" else "disabled", if (selected.enabled) Tone.Positive else Tone.Neutral)
                    for (t in selected.tags) StatusPill(t, Tone.Neutral, Modifier.clearAndSetSemantics { contentDescription = "Tag $t" }, dot = false)
                }
                if (selected.allowedTools.isNotEmpty()) Text("Tools this skill uses: ${selected.allowedTools.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                PillButton("Use in chat", onClick = {
                    val id = ChatRunner.newConversation(Mob8NApp.of(ctx), ChatSettings(skills = listOf(selected.name)))
                    onOpen(Screen.Chat(id))
                }, icon = Icons.Rounded.Forum, contentDescription = "Use ${selected.name} in a new chat")
                SectionCard { MarkdownText(Skills.render(selected, Skills.INSTR_MAX)) }
            }
        }
        else -> Scaffold(
            topBar = {
                MahoutTopBar("Skills", onBack = onBack, actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Restore presets") }, onClick = {
                                menu = false
                                scope.launch {
                                    // Re-seed by name so a deleted preset comes back while user edits to an existing one are kept.
                                    var n = 0
                                    for (p in SkillPresets.ALL) if (runCatching { engine.skill(p.name) }.getOrNull() == null) { runCatching { engine.saveSkill(p) }.onSuccess { n++ }.onFailure { msg("Could not restore ${p.name}: ${it.message}") } }
                                    msg(if (n == 0) "Presets already present" else "Restored $n preset${if (n == 1) "" else "s"}")
                                }
                            }, modifier = Modifier.semantics { contentDescription = "Restore preset skills" })
                        }
                    }
                })
            },
            snackbarHost = { MahoutSnackbarHost(snack) },
            floatingActionButton = { FloatingActionButton(onClick = { selectedId = null; editing = true }) { Icon(Icons.Rounded.Add, contentDescription = "New skill") } },
        ) { pad ->
            if (skills.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.AutoAwesome, "No skills yet", "Tap + or restore the presets from the menu.", Modifier.padding(Space.xl))
            }
            val motion = LocalMotion.current
            val gutter = pageGutter()
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(gutter, Space.s, gutter, 88.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                items(skills, key = { it.id }) { s ->
                    SectionCard(Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect()),
                        description = "Skill ${s.name}, ${if (s.enabled) "enabled" else "disabled"}", onClick = { selectedId = s.id }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text("${s.createdBy} · used ${s.usageCount}×" + (if (s.tags.isNotEmpty()) " · " + s.tags.joinToString(", ") else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
                            }
                            Switch(checked = s.enabled, onCheckedChange = { on -> scope.launch { runCatching { engine.saveSkill(s.copy(enabled = on, updatedAt = System.currentTimeMillis())) }.onFailure { msg("Could not update: ${it.message}") } } },
                                modifier = Modifier.semantics { contentDescription = "${if (s.enabled) "Disable" else "Enable"} ${s.name}" })
                        }
                    }
                }
            }
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete ${s.name}?") }, text = { Text("The chat operator and ai.agent nodes that reference it will no longer find it.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { runCatching { engine.deleteSkill(s.id) }.onSuccess { selectedId = null }.onFailure { msg("Delete failed: ${it.message}") } } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.semantics { contentDescription = "Confirm delete ${s.name}" }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Name / description / tags / allowedTools / instructions; `Skills.validate` before save; a duplicate name surfaces engine.saveSkill's error. */
@Composable
private fun SkillEditor(existing: Skill?, engine: Engine, catalog: Catalog, onDone: (Skill?) -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var description by rememberSaveable(existing?.id) { mutableStateOf(existing?.description ?: "") }
    var instructions by rememberSaveable(existing?.id) { mutableStateOf(existing?.instructions ?: "") }
    var tags by remember(existing?.id) { mutableStateOf(existing?.tags ?: emptyList()) }
    var tools by remember(existing?.id) { mutableStateOf(existing?.allowedTools ?: emptyList()) }
    val error = remember(name, description, instructions) { if (name.isBlank()) null else Skills.validate(Skills.normaliseName(name), description, instructions) }
    val toolSuggestions by produceState(emptyList<String>()) {
        val exposed = runCatching { engine.workflows().first().mapNotNull { WorkflowTools.exposed(it) }.map { WorkflowTools.toolName(it.workflowName, it.workflowId) } }.getOrDefault(emptyList())
        value = (OperatorTools.defs().mapNotNull { it["name"].asTextOrNull() } + exposed + catalog.agentTools().map { it.spec.toolName }).distinct()
    }
    Scaffold(topBar = {
        MahoutTopBar(if (existing == null) "New skill" else "Edit skill", navigationIcon = { IconButton(onClick = { onDone(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Cancel editing") } }, actions = {
            IconButton(enabled = name.isNotBlank() && error == null && instructions.isNotBlank(), onClick = {
                val now = System.currentTimeMillis()
                val s = Skill(existing?.id ?: UUID.randomUUID().toString(), Skills.normaliseName(name), description.trim(), instructions, tools, tags,
                    existing?.createdBy ?: "user", existing?.enabled ?: true, existing?.createdAt ?: now, now, existing?.usageCount ?: 0)
                scope.launch { runCatching { engine.saveSkill(s) }.onSuccess { onDone(s) }.onFailure { onMessage(it.message ?: "Save failed") } }
            }) { Icon(Icons.Rounded.Save, contentDescription = "Save skill") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = pageGutter(), vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = error != null && name.isNotBlank(),
                supportingText = { Text(error ?: "lowercase letters, digits and dashes, 2–40 chars; spaces become dashes") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Skill name" })
            OutlinedTextField(description, { description = it.take(Skills.DESC_MAX) }, label = { Text("Description") }, supportingText = { Text("${description.length}/${Skills.DESC_MAX} — shown to the model in the skills index") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Skill description" })
            LabelsField("Tags", "Free-form labels", tags, { tags = it.take(32) })
            LabelsField("Tools this skill uses", "Documentation for the model; approvals stay the enforcement", tools, { tools = it.take(32) }, suggestions = toolSuggestions)
            OutlinedTextField(instructions, { instructions = it.take(Skills.INSTR_MAX) }, label = { Text("Instructions (markdown)") }, minLines = 8,
                supportingText = { Text("${instructions.length}/${Skills.INSTR_MAX}") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Skill instructions" })
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { PillButton("Cancel", onClick = { onDone(null) }, tone = Tone.Neutral, outlined = true) }
        }
    }
}
