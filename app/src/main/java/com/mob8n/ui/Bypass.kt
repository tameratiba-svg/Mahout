@file:OptIn(ExperimentalMaterial3Api::class)

package com.mob8n.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.Mob8NApp
import com.mob8n.ai.ChatRunner
import com.mob8n.ai.ChatSettings
import com.mob8n.ai.HarnessPrefs
import com.mob8n.ai.PermissionMode
import com.mob8n.ai.Permissions
import com.mob8n.engine.Conversation
import com.mob8n.engine.Engine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

// ---- pure (BypassUiTest) ----

/** Which scope a Bypass decision touches: the global default (Settings) or one conversation (chat sheet). Never both (DESIGN4P P3). */
enum class BypassScope { GLOBAL, CONVERSATION }

/** "Bypass mode — nothing asks — expires in 57 min — Stop"; N = ceil(remaining minutes), never below 1. */
fun bypassBannerText(untilMs: Long, nowMs: Long): String =
    "Bypass mode — nothing asks — expires in ${bypassMinutesLeft(untilMs, nowMs)} min — Stop"

fun bypassMinutesLeft(untilMs: Long, nowMs: Long): Long = max(1L, ceil((untilMs - nowMs) / 60_000.0).toLong())

/** Latest unexpired Bypass expiry across the global scope and every conversation whose own mode is BYPASS; 0 when none is active. */
fun bypassActiveUntil(globalUntil: Long, conversations: List<Conversation>, nowMs: Long): Long {
    val conv = conversations.maxOfOrNull { c -> ChatSettings.parse(c.settingsJson).let { s -> if (s.mode == PermissionMode.BYPASS) s.bypassUntil else 0L } } ?: 0L
    val until = max(globalUntil, conv)
    return if (until > nowMs) until else 0L
}

/** Conversations whose own mode is BYPASS and still unexpired (the Settings / Dashboard "N chats in Bypass" line). */
fun conversationsInBypass(conversations: List<Conversation>, nowMs: Long): Int =
    conversations.count { c -> ChatSettings.parse(c.settingsJson).let { it.mode == PermissionMode.BYPASS && it.bypassUntil > nowMs } }

fun modeLabel(m: PermissionMode?): String = when (m) { null -> "Inherit"; PermissionMode.PLAN -> "Plan"; PermissionMode.ASK -> "Ask"; PermissionMode.AUTO -> "Auto"; PermissionMode.BYPASS -> "Bypass" }

/** One sentence per mode (DESIGN4P §4.1 / PLAN-v5 §4). */
fun modeHelp(m: PermissionMode): String = when (m) {
    PermissionMode.PLAN -> "Plan — read-only tools run; everything else is blocked. The assistant drafts and previews, you switch mode to run."
    PermissionMode.ASK -> "Ask — safe, coding and destructive actions ask; read-only tools run."
    PermissionMode.AUTO -> "Auto — safe and coding actions run (including running any saved workflow you ask for, whatever it contains); destructive, UI-automation and untrusted MCP actions ask."
    PermissionMode.BYPASS -> "Bypass — nothing asks for 60 minutes, including deleting workflows and running any saved workflow, whatever it contains."
}

/** Status line for a stored mode + its own expiry: "Bypass — 41 min left" / "Bypass (expired) — behaves as Ask" / "Ask". */
fun modeStatus(mode: PermissionMode?, untilMs: Long, nowMs: Long): String = when {
    mode != PermissionMode.BYPASS -> modeLabel(mode)
    untilMs > nowMs -> "Bypass — ${bypassMinutesLeft(untilMs, nowMs)} min left"
    else -> "Bypass (expired) — behaves as Ask"
}

/** Snackbar after Stop everything: "Stopped 2 chats, 1 run; Bypass off". */
fun stoppedLine(chats: Int, runs: Int): String = "Stopped $chats chat${if (chats == 1) "" else "s"}, $runs run${if (runs == 1) "" else "s"}; Bypass off"

/** DESIGN4P §4.1 dialog body; the first line names the scope truthfully. */
fun bypassDialogBody(scope: BypassScope): String = (if (scope == BypassScope.GLOBAL)
    "Every chat and every ai.agent workflow that inherits the global mode stops asking for the next 60 minutes."
else "This chat stops asking for the next 60 minutes. Other chats, agents and workflows keep their own mode.") +
    "\nThe assistant will immediately:\n" +
    "• run shell commands, JavaScript and write, create or delete workspace files\n" +
    "• save, enable, disable and DELETE workflows and skills, and run any saved workflow you ask for, whatever it contains\n" +
    "• approve or deny workflow runs that are waiting for you (resume_run)\n" +
    "• run every action node, including UI automation (tapping and typing in other apps), untrusted MCP tools, downloads, intents and system-setting changes\n" +
    "Read-only tools and drafts behave as always. Bypass turns itself off after 60 minutes, and Stop everything (Dashboard or the red banner) ends it at once."

const val STOP_EVERYTHING_BODY = "Cancels every running chat turn and workflow run, closes every run waiting for a timer or approval, and turns Bypass off everywhere."

// ---- kill switch (DESIGN4P §4.4) ----

/** ORDER matters: clear Bypass first (synchronous, cannot fail), then chats, then runs. Never throws. */
suspend fun stopEverything(app: Mob8NApp): String {
    HarnessPrefs.clearBypass(app)
    runCatching { ChatRunner.clearConversationBypass(app.engine) }
    val chats = runCatching { ChatRunner.cancelAll(app) }.getOrDefault(0)
    val runs = runCatching { app.engine.cancelAllRuns() }.getOrDefault(0)
    return stoppedLine(chats, runs)
}

// ---- composables ----

/** Latest unexpired Bypass expiry (0 = none); re-evaluated at expiry. App.kt reads it once to show the banner and to consume the status-bar inset. */
@Composable
fun rememberBypassUntil(engine: Engine): Long {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HarnessPrefs.load(ctx) }
    val globalUntil by HarnessPrefs.bypassUntil.collectAsStateWithLifecycle()
    val convs by engine.conversations().collectAsStateWithLifecycle(emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val until = bypassActiveUntil(globalUntil, convs, now)
    LaunchedEffect(until) { if (until > 0) { delay(until - System.currentTimeMillis() + 50); now = System.currentTimeMillis() } }   // wakes once, at expiry (no per-second recomposition of App)
    return until
}

/**
 * Red banner rendered ONCE by App.kt above every screen while any scope's Bypass is unexpired (1-s countdown).
 * v5 carry-over: the app is edge-to-edge, so the red surface extends under the status bar but its text/Stop sit BELOW the clock
 * (statusBars padding); App.kt consumes that inset for the screen underneath so its top bar does not pad twice.
 */
@Composable
fun BypassBanner(until: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (until <= 0) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }   // 1-s countdown, scoped to the banner
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 52.dp).padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            // v6 (DESIGN6 §6.8): same text and semantics; the minutes use NumericStyle (tabular digits: no width jitter as they count down)
            val text = bypassBannerText(until, now).removeSuffix(" — Stop")
            val mins = bypassMinutesLeft(until, now).toString()
            val at = text.indexOf("in $mins min").let { if (it < 0) -1 else it + 3 }
            Text(buildAnnotatedString {
                if (at < 0) append(text) else {
                    append(text.substring(0, at))
                    withStyle(SpanStyle(fontFamily = NumericStyle.fontFamily, fontWeight = FontWeight.Bold, fontFeatureSettings = NumericStyle.fontFeatureSettings ?: "tnum")) { append(mins) }
                    append(text.substring(at + mins.length))
                }
            }, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = Space.s).semantics { liveRegion = LiveRegionMode.Assertive })
            TextButton(onClick = { confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop everything and turn off Bypass" }) { Text("Stop", fontWeight = FontWeight.Bold) }
        }
    }
    if (confirm) StopEverythingDialog(onDismiss = { confirm = false }, onConfirm = { confirm = false; scope.launch { result = stopEverything(Mob8NApp.of(ctx)) } })
    result?.let { AlertDialog(onDismissRequest = { result = null }, title = { Text("Stopped") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } }) }
}

/** "Stop everything?" — shared by the banner and the Dashboard card. */
@Composable
fun StopEverythingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Stop everything?") }, text = { Text(STOP_EVERYTHING_BODY) },
        confirmButton = { PillButton("Stop everything", onConfirm, tone = Tone.Danger, contentDescription = "Confirm stop everything") },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}

/** DESIGN4P §4.1, verbatim. Only the confirm button leads to a Bypass write. */
@Composable
fun BypassConfirmDialog(scope: BypassScope, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Turn on Bypass mode ${if (scope == BypassScope.GLOBAL) "for every chat and agent" else "for this chat"}?") },
        text = { Text(bypassDialogBody(scope), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { PillButton("Turn on Bypass", onConfirm, tone = Tone.Danger, contentDescription = "Confirm Bypass mode") },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}

/**
 * Four-way (or five-way with Inherit) mode selector. Picking Bypass opens [BypassConfirmDialog] first; `onChange(BYPASS)` fires only after
 * the user confirms. `globalLabel` names what Inherit resolves to ("Ask").
 */
@Composable
fun ModeSelector(value: PermissionMode?, allowInherit: Boolean, globalLabel: String, scope: BypassScope, onChange: (PermissionMode?) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val options: List<PermissionMode?> = (if (allowInherit) listOf(null) else emptyList()) + PermissionMode.entries
    // Device phase v6: above 130 % font scale four or five segments clip "Bypass" on a phone, so the segments go two per row.
    val rows = if (LocalDensity.current.fontScale > 1.3f) options.chunked(2) else listOf(options)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { for (row in rows) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        row.forEachIndexed { i, m ->
            SegmentedButton(selected = value == m, onClick = { if (m == PermissionMode.BYPASS) confirming = true else onChange(m) },   // device v4.1: an expired Bypass must be re-armable through the dialog (DESIGN4P §4.2)
                shape = SegmentedButtonDefaults.itemShape(i, row.size),
                colors = if (m == PermissionMode.BYPASS) SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.errorContainer, activeContentColor = MaterialTheme.colorScheme.onErrorContainer)
                    else SegmentedButtonDefaults.colors(),   // v6: the danger tokens mark Bypass when it is the selected mode
                modifier = Modifier.semantics { contentDescription = "Permission mode ${modeLabel(m)}${if (value == m) ", selected" else ""}" }) {
                Text(modeLabel(m), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    } }
    Text(if (value == null) "Inherit — the global mode from Settings (currently $globalLabel)." else modeHelp(value), style = MaterialTheme.typography.bodySmall,
        color = if (value == PermissionMode.BYPASS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (confirming) BypassConfirmDialog(scope, onConfirm = { confirming = false; onChange(PermissionMode.BYPASS) }, onDismiss = { confirming = false })
}
