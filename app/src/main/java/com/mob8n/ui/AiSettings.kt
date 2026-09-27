@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.Mob8NApp
import com.mob8n.ai.AiPrefs
import com.mob8n.ai.DefaultAi
import com.mob8n.ai.HarnessPrefs
import com.mob8n.ai.McpPrefs
import com.mob8n.ai.PermissionMode
import com.mob8n.ai.NanoStatus
import com.mob8n.ai.ProviderState
import com.mob8n.ai.S1Prefs
import com.mob8n.ai.SystemOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Mirrors of com.mob8n.ai.PROVIDER_CLAUDE / PROVIDER_NANO (top-level ai constants are outside the ui import allow-list; AiPrefs.PROVIDER_IDS holds the same strings).
internal const val AI_CLAUDE = "claude"
internal const val AI_NANO = "on_device_gemini_nano"

/** A provider row is "connected" when it can be used right now: key saved, or no key needed (Ollama); Nano only when the model is on the device. */
internal fun ProviderState.connected(nano: NanoStatus): Boolean = if (id == AI_NANO) nano == NanoStatus.AVAILABLE else hasKey || !needsKey

/**
 * Settings > AI (DESIGN2 §5.3): one Default AI card + one card per provider in PROVIDER_IDS order (Gemini Nano last, its v1 card unchanged).
 * Reads/writes only AiPrefs; key drafts live in the text field until Save and are never shown again (maskedKey only).
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit = {}) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { AiPrefs.load(ctx); McpPrefs.load(ctx); HarnessPrefs.load(ctx) }
    val mcpServers by McpPrefs.servers.collectAsStateWithLifecycle()
    val mode by HarnessPrefs.mode.collectAsStateWithLifecycle()
    val bypassUntil by HarnessPrefs.bypassUntil.collectAsStateWithLifecycle()
    val convs by remember { Mob8NApp.of(ctx).engine.conversations() }.collectAsStateWithLifecycle(emptyList())
    val providers by AiPrefs.providers.collectAsStateWithLifecycle()
    val defaultAi by AiPrefs.defaultAi.collectAsStateWithLifecycle()
    val defaultLabel by AiPrefs.defaultLabel.collectAsStateWithLifecycle()
    val nano by AiPrefs.nanoStatus.collectAsStateWithLifecycle()
    val progress by AiPrefs.downloadProgress.collectAsStateWithLifecycle()
    val preferOnDevice by AiPrefs.preferOnDevice.collectAsStateWithLifecycle()

    Scaffold(topBar = { MahoutTopBar("AI settings", onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = pageGutter(), vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            DefaultAiCard(providers, defaultAi, defaultLabel, nano)
            // DESIGN4P P13: the GLOBAL permission mode (PLAN-v5 §4). Bypass goes through the confirmation dialog and arms its own 60-min expiry.
            SectionCard(title = "Permission mode") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    val nowMs = System.currentTimeMillis()
                    val inBypass = conversationsInBypass(convs, nowMs)
                    Text("The default for every chat and every ai.agent that inherits. A chat can override it in its own settings sheet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ModeSelector(value = mode, allowInherit = false, globalLabel = modeLabel(mode), scope = BypassScope.GLOBAL) { m ->
                        runCatching { HarnessPrefs.setMode(ctx, m ?: PermissionMode.ASK, confirmed = m == PermissionMode.BYPASS) }
                    }
                    Text("Current: ${modeStatus(mode, bypassUntil, nowMs)}" + (if (inBypass > 0) " · $inBypass chat${if (inBypass == 1) "" else "s"} in Bypass" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = if (mode == PermissionMode.BYPASS && bypassUntil > nowMs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { contentDescription = "Permission mode status: ${modeStatus(mode, bypassUntil, nowMs)}" })
                }
            }
            DecisionEngineCard()   // DESIGN5 §3.6: after the Permission-mode card
            Text("Providers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.s).semantics { heading() })
            Text("Keys are stored app-private on this device and excluded from backup. Paste a key, Save, then Test; the first provider you connect becomes the Default AI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (id in AiPrefs.PROVIDER_IDS) {
                val st = providers[id] ?: continue
                if (id == AI_NANO) NanoCard(nano, progress, preferOnDevice, isDefault = defaultAi?.provider == id)
                else ProviderCard(st, isDefault = defaultAi?.provider == id, defaultAi = defaultAi)
            }
            // DESIGN3 §6.2: remote MCP servers the Agent / ai.mcp_* nodes can call.
            SectionCard(title = "MCP servers", description = "MCP servers, ${mcpServers.size} configured", onClick = { onOpen(Screen.McpSettings) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }) {
                Text("${mcpServers.size} configured · remote tools for the AI Agent (Streamable HTTP)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppearanceCard()   // DESIGN6 §6.8
        }
    }
}

@Composable
private fun DefaultAiCard(providers: Map<String, ProviderState>, defaultAi: DefaultAi?, defaultLabel: String, nano: NanoStatus) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = providers.values.filter { it.connected(nano) }
    val current = defaultAi?.let { providers[it.provider] }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetchMsg by remember { mutableStateOf<String?>(null) }
    fun set(d: DefaultAi) { AiPrefs.setDefaultAi(ctx, d); testResult = null }

    SectionCard(title = "Default AI", trailing = { StatusPill(defaultLabel, if (defaultAi == null) Tone.Neutral else Tone.Info, Modifier.clearAndSetSemantics { contentDescription = "Default AI: $defaultLabel" }) }) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Used by every AI node whose provider is 'default' and by Build with AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected.isEmpty()) {
                Text("Not set — the first provider you connect becomes the default.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val labels = connected.map { it.label }
            EnumDropdown("Provider", labels, current?.label ?: "", { l ->
                connected.firstOrNull { it.label == l }?.let { set(DefaultAi(it.id, "", defaultAi?.effort ?: "high", null)) }
            }, help = if (defaultAi == null) "Not set — pick one, or connect a provider below" else "")
            val d = defaultAi ?: return@Column
            val models = current?.models ?: emptyList()
            // M5: at large font scales the field gets the full width and Fetch models moves below it (side by side it is too narrow to read).
            val stacked = LocalDensity.current.fontScale > 1.3f
            val field: @Composable (Modifier) -> Unit = { m ->
                SuggestField("Model", d.model, models, { set(d.copy(model = it)) },
                    help = if (d.model.isBlank()) "Blank = the provider's default model" else "", error = null, modifier = m.semantics { contentDescription = "Default model" })
            }
            val fetch: @Composable (Modifier) -> Unit = { m ->
                if (d.provider != AI_NANO && d.provider != AI_CLAUDE) TextButton(onClick = {
                    fetching = true; fetchMsg = null
                    scope.launch { fetchMsg = AiPrefs.fetchModels(ctx, d.provider).fold({ "${it.size} models" }, { it.message ?: "Fetch failed" }); fetching = false }
                }, enabled = !fetching, modifier = m.semantics { contentDescription = "Fetch models for default provider" }) { Text("Fetch models") }
            }
            if (stacked) { field(Modifier.fillMaxWidth()); fetch(Modifier) }
            else Row(verticalAlignment = Alignment.Top) { field(Modifier.weight(1f)); fetch(Modifier.padding(top = 8.dp)) }
            fetchMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (d.provider == AI_CLAUDE) EnumDropdown("Effort", AiPrefs.EFFORTS, d.effort, { set(d.copy(effort = it)) }, help = "Ignored by claude-haiku-4-5")
            if (d.provider != AI_NANO) TemperatureRow(d.temperature) { set(d.copy(temperature = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Test", onClick = {
                    testing = true; testResult = null
                    scope.launch { testResult = AiPrefs.testProvider(ctx, d.provider, d.model.ifBlank { null }).fold({ "OK: $it" }, { "Failed: ${it.message ?: "unknown error"}" }); testing = false }
                }, enabled = !testing, contentDescription = "Test default AI")
                if (testing) CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { AiPrefs.setDefaultAi(ctx, null); testResult = null }, modifier = Modifier.semantics { contentDescription = "Clear default AI" }) { Text("Clear") }
            }
            testResult?.let { ResultText(it) }
        }
    }
}

/** Temperature 0–2 with a "provider default" switch (null). */
@Composable
private fun TemperatureRow(value: Double?, onChange: (Double?) -> Unit) {
    SwitchRow("Provider default temperature", if (value == null) "" else "Temperature ${"%.1f".format(value)}", value == null) { onChange(if (it) null else 0.7) }
    if (value != null) Slider(
        value = value.toFloat(), onValueChange = { onChange((Math.round(it * 10) / 10.0)) }, valueRange = 0f..2f, steps = 19,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Temperature ${"%.1f".format(value)}" },
    )
}

@Composable
private fun ResultText(s: String) {
    Text(s, style = MaterialTheme.typography.bodySmall, color = if (s.startsWith("OK") || s.startsWith("Saved") || s.endsWith("models")) MaterialTheme.mahout.success else MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun ProviderCard(st: ProviderState, isDefault: Boolean, defaultAi: DefaultAi?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(st.id) { mutableStateOf(false) }
    var keyDraft by remember(st.id) { mutableStateOf("") }
    var reveal by remember(st.id) { mutableStateOf(false) }
    var urlDraft by remember(st.id, st.baseUrl) { mutableStateOf(st.baseUrl) }
    var urlError by remember(st.id) { mutableStateOf<String?>(null) }
    var busy by remember(st.id) { mutableStateOf(false) }
    var result by remember(st.id) { mutableStateOf<String?>(null) }
    var filter by remember(st.id) { mutableStateOf("") }
    val connected = st.hasKey || !st.needsKey
    fun useAsDefault(model: String = defaultAi?.takeIf { it.provider == st.id }?.model ?: "") {
        AiPrefs.setDefaultAi(ctx, DefaultAi(st.id, model, defaultAi?.effort ?: "high", defaultAi?.takeIf { it.provider == st.id }?.temperature))
        result = "Default AI: ${st.label}${if (model.isNotBlank()) " · $model" else ""}"
    }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }.semantics { contentDescription = "${st.label}: ${if (connected) "connected" else "no key"}${if (isDefault) ", default" else ""}. ${if (expanded) "Collapse" else "Expand"}" }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (connected) MaterialTheme.mahout.success else MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(Space.m))
                Text(st.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isDefault) StatusPill("Default", Tone.Primary, dot = false)
                RotatingChevron(expanded, Modifier.padding(start = Space.xs))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (st.tools) CapChip("tools"); if (st.jsonSchema || st.jsonObject) CapChip("JSON"); if (st.vision) CapChip("vision")
                if (!st.tools && !st.jsonSchema && !st.jsonObject && !st.vision) CapChip("text only")
                if (st.verifiedAt != null) CapChip("verified ${fmtTime(st.verifiedAt)}")
            }
            ExpandableSection(expanded) { Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {

            Text(if (st.hasKey) "Saved key: ${st.maskedKey}" else if (st.needsKey) "No key saved." else "No key needed (optional for cloud endpoints).", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = keyDraft, onValueChange = { keyDraft = it }, singleLine = true, label = { Text(if (st.hasKey) "Replace key" else if (st.needsKey) "API key" else "API key (optional)") },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),   // key hygiene: no IME dictionary learning
                trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide key" else "Reveal key") } },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${st.label} API key" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PillButton("Save", onClick = { AiPrefs.setProviderKey(ctx, st.id, keyDraft.trim()); keyDraft = ""; reveal = false; result = "Saved. Tap Test to verify." },
                    enabled = keyDraft.isNotBlank(), contentDescription = "Save ${st.label} key")
                if (st.hasKey) PillButton("Clear", onClick = { AiPrefs.setProviderKey(ctx, st.id, null); result = null }, tone = Tone.Neutral, outlined = true, contentDescription = "Clear ${st.label} key")
            }
            if (st.editableBaseUrl) {
                OutlinedTextField(
                    value = urlDraft, onValueChange = { urlDraft = it; urlError = null }, singleLine = true, label = { Text("Base URL") }, isError = urlError != null,
                    placeholder = { Text(if (st.id == "ollama") "http://<lan-ip>:11434/v1" else "https://host/v1") },
                    supportingText = { Text(urlError ?: "http:// only for localhost, *.local and private LAN addresses") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${st.label} base URL" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Save URL", onClick = {
                        try { AiPrefs.setProviderBaseUrl(ctx, st.id, urlDraft.trim().ifBlank { null }); urlError = null; result = "Saved base URL." }
                        catch (e: IllegalArgumentException) { urlError = e.message ?: "Invalid URL" }
                    }, enabled = urlDraft.trim() != st.baseUrl, tone = Tone.Neutral, outlined = true, contentDescription = "Save ${st.label} base URL")
                    TextButton(onClick = { AiPrefs.setProviderBaseUrl(ctx, st.id, null); urlError = null }, modifier = Modifier.semantics { contentDescription = "Reset ${st.label} base URL" }) { Text("Reset") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                PillButton("Fetch models", onClick = {
                    busy = true; result = null
                    scope.launch { result = AiPrefs.fetchModels(ctx, st.id).fold({ "${it.size} models" }, { it.message ?: "Fetch failed" }); busy = false }
                }, enabled = connected && !busy, tone = Tone.Neutral, outlined = true, contentDescription = "Fetch ${st.label} models")
                PillButton("Test", onClick = {
                    busy = true; result = null
                    scope.launch { result = AiPrefs.testProvider(ctx, st.id, defaultAi?.takeIf { it.provider == st.id }?.model?.ifBlank { null }).fold({ "OK: $it" }, { "Failed: ${it.message ?: "unknown error"}" }); busy = false }
                }, enabled = connected && !busy, contentDescription = "Test ${st.label}")
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (st.id == "minimax") {
                // M1: switchable without a rebuild; read on every call's target resolution.
                var split by remember { mutableStateOf(AiPrefs.minimaxReasoningSplit(ctx)) }
                SwitchRow("Separate reasoning", if (split) "reasoning_split on: thinking arrives apart from the answer" else "Off: thinking arrives inline as <think> and is hidden from the answer", split) {
                    split = it; AiPrefs.setMinimaxReasoningSplit(ctx, it)
                }
            }
            if (!isDefault) TextButton(onClick = { useAsDefault() }, enabled = connected, modifier = Modifier.semantics { contentDescription = "Use ${st.label} as Default AI" }) { Text("Use as Default AI") }
            result?.let { ResultText(it) }
            if (st.models.isNotEmpty()) {
                val big = st.models.size > 12
                if (big) OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("Filter ${st.models.size} models") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Filter ${st.label} models" })
                val shown = st.models.filter { filter.isBlank() || it.contains(filter, true) }
                // ponytail: first 40 matches as chips; upgrade = LazyColumn when providers list thousands of models
                FlowRow(Modifier.heightIn(max = 320.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (m in shown.take(40)) SuggestionChip(onClick = { useAsDefault(m) }, label = { Text(m, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.semantics { contentDescription = "Use ${st.label} model $m as default" })
                }
                if (shown.size > 40) Text("${shown.size - 40} more — narrow the filter", style = MaterialTheme.typography.bodySmall)
            }
            } }
        }
    }
}

@Composable
private fun CapChip(text: String) {
    StatusPill(text, Tone.Neutral, Modifier.clearAndSetSemantics { contentDescription = "Supports $text" }, dot = false)
}

/** Expand/collapse chevron: rotates 180° with the spatialFast spec (instant when motion is reduced). Decorative; the row states Expand/Collapse. */
@Composable
internal fun RotatingChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val r by animateFloatAsState(if (expanded) 180f else 0f, LocalMotion.current.spatialFast(), label = "chevron")
    Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = modifier.rotate(r))
}

/** Gemini Nano card, unchanged from v1 except for the Default badge. */
@Composable
private fun NanoCard(nano: NanoStatus, progress: Float?, preferOnDevice: Boolean, isDefault: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var nanoMsg by remember { mutableStateOf<String?>(null) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (nano == NanoStatus.AVAILABLE) MaterialTheme.mahout.success else MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(Space.m))
                Text("Gemini Nano (on-device)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isDefault) StatusPill("Default", Tone.Primary, dot = false)
            }
            Text(when (nano) {
                NanoStatus.UNKNOWN -> "Checking availability…"
                NanoStatus.UNAVAILABLE -> "Not available on this device"
                NanoStatus.DOWNLOADABLE -> "Available to download"
                NanoStatus.DOWNLOADING -> "Downloading…"
                NanoStatus.AVAILABLE -> "Ready"
            }, style = MaterialTheme.typography.bodyMedium)
            Text("Private and free, but 256-token answers: no tool calling, no Build with AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (nano == NanoStatus.DOWNLOADING || downloading) {
                val p = progress
                if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Download ${(p * 100).toInt()} percent" })
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (nano == NanoStatus.DOWNLOADABLE || nano == NanoStatus.UNKNOWN) PillButton("Download", onClick = {
                    downloading = true; nanoMsg = null
                    scope.launch { nanoMsg = AiPrefs.downloadNano(ctx).fold({ "Downloaded" }, { "Download failed: ${it.message ?: "unknown error"}" }); downloading = false }
                }, enabled = !downloading, contentDescription = "Download Gemini Nano")
                if (nano == NanoStatus.AVAILABLE && !isDefault) TextButton(onClick = { AiPrefs.setDefaultAi(ctx, DefaultAi(AI_NANO, "gemini-nano", "high", null)) },
                    modifier = Modifier.semantics { contentDescription = "Use Gemini Nano as Default AI" }) { Text("Use as Default AI") }
            }
            nanoMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SwitchRow("Prefer on-device", "Use Gemini Nano for provider = default while no Default AI is chosen", preferOnDevice) { AiPrefs.setPreferOnDevice(ctx, it) }
        }
    }
}

// ---- v5: System 1 decision engine (DESIGN5 §3.1 / §3.6). Reads/writes only S1Prefs; key drafts live in the fields until Save and are never shown again.

private val S1_ENGINE_LABELS = listOf(SystemOne.ENGINE_NONE to "None", SystemOne.ENGINE_JEV to "Jev (cloud)", SystemOne.ENGINE_LAYA to "Laya (your LAN)")

/** Pure: "Laya (your LAN) · verified 12:03" / "Jev (cloud) — key missing" / "Not configured". */
internal fun s1StatusLabel(default: String, jevHasKey: Boolean, layaUrl: String, verified: Long?): String {
    val label = S1_ENGINE_LABELS.firstOrNull { it.first == default }?.second
    val ready = when (default) { SystemOne.ENGINE_JEV -> jevHasKey; SystemOne.ENGINE_LAYA -> layaUrl.isNotBlank(); else -> false }
    return when {
        label == null || default == SystemOne.ENGINE_NONE -> "Not configured"
        !ready -> "$label — ${if (default == SystemOne.ENGINE_JEV) "key missing" else "URL missing"}"
        verified != null -> "$label · verified ${fmtTime(verified)}"
        else -> label
    }
}

@Composable
private fun DecisionEngineCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { S1Prefs.load(ctx) }
    val default by S1Prefs.defaultEngine.collectAsStateWithLifecycle()
    val layaUrl by S1Prefs.layaUrl.collectAsStateWithLifecycle()
    val jevHasKey by S1Prefs.jevHasKey.collectAsStateWithLifecycle()
    val jevMasked by S1Prefs.jevMaskedKey.collectAsStateWithLifecycle()
    val layaHasKey by S1Prefs.layaHasKey.collectAsStateWithLifecycle()
    val verified by S1Prefs.verifiedAt.collectAsStateWithLifecycle()
    val lastModel by S1Prefs.lastModel.collectAsStateWithLifecycle()
    val secondOpinion by S1Prefs.secondOpinion.collectAsStateWithLifecycle()
    var jevDraft by remember { mutableStateOf("") }
    var layaKeyDraft by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var urlDraft by remember(layaUrl) { mutableStateOf(layaUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }            // engine under Test
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }   // engine -> message
    val configured = when (default) { SystemOne.ENGINE_JEV -> jevHasKey; SystemOne.ENGINE_LAYA -> layaUrl.isNotBlank(); else -> false }
    val status = s1StatusLabel(default, jevHasKey, layaUrl, verified[default])
    fun test(engine: String) {
        busy = engine; result = null
        scope.launch { result = engine to S1Prefs.test(ctx, engine).fold({ it }, { "Failed: ${it.message ?: "unknown error"}" }); busy = null }
    }
    @Composable fun EngineResult(engine: String) {
        val r = result?.takeIf { it.first == engine }?.second
        if (busy == engine) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Text("  Testing…", style = MaterialTheme.typography.bodySmall) }
        if (r != null) Text(r, style = MaterialTheme.typography.bodySmall, color = if (r.startsWith("OK")) MaterialTheme.mahout.success else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val v = verified[engine]
        if (v != null) Text("Verified ${fmtTime(v)}${lastModel[engine]?.let { " · model $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val keyField: @Composable (String, String, String, (String) -> Unit) -> Unit = { label, desc, value, onChange ->
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),   // key hygiene: no IME dictionary learning
            trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide keys" else "Reveal keys") } },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc },
        )
    }

    SectionCard(title = "Decision engine (System 1)") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            StatusPill(status, if (configured) Tone.Positive else Tone.Neutral, Modifier.clearAndSetSemantics { contentDescription = "Decision engine status: $status" })
            Text("Typed decisions (pick a label, score a level, yes/no) in tens of milliseconds — no text generation. Used by AI Decide, AI Classify with engine = system1, trigger triage and the second opinion.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EnumDropdown("Default engine", S1_ENGINE_LABELS.map { it.second }, S1_ENGINE_LABELS.firstOrNull { it.first == default }?.second ?: "None", { l ->
                val e = S1_ENGINE_LABELS.first { it.second == l }.first
                runCatching { S1Prefs.setDefault(ctx, e) }.onFailure { result = e to (it.message ?: "Cannot use $l") }
            }, help = if (default != SystemOne.ENGINE_NONE && !configured) "Set it up below — until then decisions fail with \"${SystemOne.ERR_NOT_CONFIGURED}\"" else "Nodes with engine = default use this; there is no automatic fallback",
                modifier = Modifier.semantics { contentDescription = "Default decision engine: ${S1_ENGINE_LABELS.firstOrNull { it.first == default }?.second ?: "None"}" })

            // Jev (cloud)
            Text("Jev (cloud, api.typesafe.ai)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(if (jevHasKey) "Saved key: $jevMasked" else "No key saved.", style = MaterialTheme.typography.bodySmall)
            keyField(if (jevHasKey) "Replace Jev key" else "Jev API key", "Jev API key", jevDraft) { jevDraft = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                PillButton("Save", onClick = { S1Prefs.setJevKey(ctx, jevDraft.trim()); jevDraft = ""; reveal = false; result = SystemOne.ENGINE_JEV to "Saved. Tap Test to verify." },
                    enabled = jevDraft.isNotBlank(), contentDescription = "Save Jev key")
                if (jevHasKey) PillButton("Clear", onClick = { S1Prefs.setJevKey(ctx, null); result = null }, tone = Tone.Neutral, outlined = true, contentDescription = "Clear Jev key")
                PillButton("Test", onClick = { test(SystemOne.ENGINE_JEV) }, enabled = jevHasKey && busy == null, tone = Tone.Neutral, outlined = true, contentDescription = "Test Jev")
            }
            EngineResult(SystemOne.ENGINE_JEV)

            // Laya (LAN)
            Text("Laya (laya-serve on your LAN)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = urlDraft, onValueChange = { urlDraft = it; urlError = null }, singleLine = true, label = { Text("Laya URL") }, isError = urlError != null,
                placeholder = { Text("http://192.168.1.5:8000") },
                supportingText = { Text(urlError ?: "http:// only for localhost, *.local and private LAN addresses (10.x, 172.16-31.x, 192.168.x)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Laya URL" },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                PillButton("Save URL", onClick = {
                    try { S1Prefs.setLayaUrl(ctx, urlDraft.trim().ifBlank { null }); urlError = null; result = SystemOne.ENGINE_LAYA to "Saved. Tap Test to verify." }
                    catch (e: IllegalArgumentException) { urlError = e.message ?: "Invalid URL" }
                }, enabled = urlDraft.trim() != layaUrl, tone = Tone.Neutral, outlined = true, contentDescription = "Save Laya URL")
                if (layaUrl.isNotBlank()) TextButton(onClick = { S1Prefs.setLayaUrl(ctx, null); urlError = null; result = null }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Clear Laya URL" }) { Text("Clear") }
                PillButton("Test", onClick = { test(SystemOne.ENGINE_LAYA) }, enabled = layaUrl.isNotBlank() && busy == null, tone = Tone.Neutral, outlined = true, contentDescription = "Test Laya")
            }
            Text(if (layaHasKey) "Laya key saved (sent as Bearer)." else "Laya key: optional — only when laya-serve runs with LAYA_API_KEY.", style = MaterialTheme.typography.bodySmall)
            keyField(if (layaHasKey) "Replace Laya key" else "Laya key (optional)", "Laya API key", layaKeyDraft) { layaKeyDraft = it }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Save key", onClick = { S1Prefs.setLayaKey(ctx, layaKeyDraft.trim()); layaKeyDraft = ""; reveal = false; result = SystemOne.ENGINE_LAYA to "Saved Laya key." },
                    enabled = layaKeyDraft.isNotBlank(), tone = Tone.Neutral, outlined = true, contentDescription = "Save Laya key")
                if (layaHasKey) TextButton(onClick = { S1Prefs.setLayaKey(ctx, null) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Clear Laya key" }) { Text("Clear key") }
            }
            EngineResult(SystemOne.ENGINE_LAYA)

            // Second opinion (DESIGN5 §6.2): enabled only when the default engine is usable; never lowers a risk class.
            ListItem(
                headlineContent = { Text("Second opinion in Auto mode") },
                supportingContent = { Text(if (configured) "Before a coding tool runs unasked in Auto, the decision engine checks the input; a destructive-looking call asks you instead. It never lets anything run that would otherwise ask; if the engine fails the call runs as usual."
                    else "Configure a default engine first.") },
                trailingContent = { Switch(checked = secondOpinion && configured, onCheckedChange = { S1Prefs.setSecondOpinion(ctx, it) }, enabled = configured,
                    modifier = Modifier.semantics { contentDescription = "Second opinion in Auto mode${if (!configured) ", unavailable until a decision engine is configured" else ""}" }) },
                modifier = Modifier.clickable(enabled = configured) { S1Prefs.setSecondOpinion(ctx, !secondOpinion) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),   // sits on the card, not on the page
            )
            Text("Privacy: with Jev, the text being decided (notification or shared text, tool input) is sent to api.typesafe.ai; Laya keeps it on your Wi-Fi. Keys are stored app-private, excluded from backup and masked in logs.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- v6 Appearance (DESIGN6 §6.8 / D2 / D4) ----

private const val FONTS_LINE = "Fonts: Manrope, JetBrains Mono — SIL Open Font License 1.1"

@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    val dynamic by UiPrefs.dynamicColor.collectAsStateWithLifecycle()
    val reduce by UiPrefs.reduceMotion.collectAsStateWithLifecycle()
    var licences by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = "Appearance") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Mahout follows the system light / dark setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (Build.VERSION.SDK_INT >= 31) SwitchRow("Use wallpaper colours", "Safety colours always stay Mahout's", dynamic) { UiPrefs.setDynamicColor(ctx, it) }
            SwitchRow("Reduce motion", "Also on when Android's Remove animations is on. System sheets and switches follow Android's setting.", reduce) { UiPrefs.setReduceMotion(ctx, it) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { licences = true }.semantics { contentDescription = "$FONTS_LINE. Show licences" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(FONTS_LINE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = Space.l, vertical = Space.s))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
    if (licences) FontLicencesDialog { licences = false }
}

/** Both OFL texts from assets/licenses (read on IO; a missing file says so instead of failing). */
@Composable
private fun FontLicencesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            listOf("Manrope" to "licenses/Manrope-OFL.txt", "JetBrains Mono" to "licenses/JetBrainsMono-OFL.txt").joinToString("\n\n") { (name, path) ->
                "$name\n\n" + (runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull() ?: "(licence file missing: $path)")
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Font licences") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text ?: "Reading…", style = CodeSmallStyle.copy(color = MaterialTheme.colorScheme.onSurface))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") } },
    )
}
