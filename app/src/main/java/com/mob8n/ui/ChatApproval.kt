@file:OptIn(ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mob8n.ai.ChatDrafts
import com.mob8n.ai.OperatorTools
import com.mob8n.ai.PendingCall
import com.mob8n.ai.Permissions
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray

/** Dock header (DESIGN4P §3.1 wording): "Wants to run run_shell" / "Wants to run 3 tools · 1 decided". */
internal fun dockHeader(pending: List<PendingCall>, decided: Map<String, Boolean>): String {
    val done = pending.count { it.id in decided }
    return (if (pending.size == 1) "Wants to run ${pending[0].toolName}" else "Wants to run ${pending.size} tools") + (if (done > 0) " · $done decided" else "")
}

/** First non-blank line of a preview (the dock row's one-line summary). */
internal fun previewLine(preview: String): String = preview.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** The dock row's summary: the preview line without a leading "<toolName> " (the name is already on the row above). */
internal fun summaryLine(preview: String, toolName: String): String = previewLine(preview).let { l -> l.removePrefix("$toolName ").ifBlank { l } }

/** Details only when they add something the summary line does not already show: more than one non-blank line, or a line too long for the row. */
internal fun previewHasDetails(preview: String): Boolean =
    preview.lineSequence().count { it.isNotBlank() } > 1 || previewLine(preview).length > SUMMARY_MAX

private const val SUMMARY_MAX = 120

/**
 * Approval dock (DESIGN6 §5.3.9, D11): replaces the composer while any call is undecided. L3, top corners Radius.l, 3 dp shadow, hairline,
 * a 4 dp stripe in the batch's highest-risk colour; header (Polite live region) + mode line + one row per call (Approve / Deny, Details) +
 * Approve all / Deny all when n > 1. Max 60 % of the screen, scrolls inside. Data path, strings and contentDescriptions per DESIGN4P §3.
 */
@Composable
internal fun ApprovalDock(
    pending: List<PendingCall>, decided: Map<String, Boolean>, onDecide: (id: String, ok: Boolean) -> Unit, onDecideAll: (Boolean) -> Unit,
    onOpenDraft: (ChatDrafts.Draft) -> Unit, modifier: Modifier = Modifier,
) {
    if (pending.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val mc = MaterialTheme.mahout
    val n = pending.size
    val done = pending.count { it.id in decided }
    val stripe = riskColors(pending.maxOf { it.decision.risk }, mc).second
    val hairline = cs.outlineVariant
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val haptic = rememberDecisionHaptic()
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(topStart = Radius.l, topEnd = Radius.l), shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().drawBehind {
            drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            val top = 18.dp.toPx(); val bottom = size.height - 14.dp.toPx()
            if (bottom > top) drawRoundRect(stripe, Offset(0f, top), Size(4.dp.toPx(), bottom - top), CornerRadius(4.dp.toPx()))
        }) {
        Column(Modifier.navigationBarsPadding().imePadding().heightIn(max = maxH).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = Space.l, top = Space.m, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(cs.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PanTool, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(14.dp))
                }
                Text(dockHeader(pending, decided), style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                    modifier = Modifier.padding(start = Space.s).semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text("Mode: ${modeHelp(pending[0].decision.mode)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp))
            for (c in pending) PendingCallRow(c, decided[c.id], startExpanded = n == 1,
                onApprove = { haptic(true); onDecide(c.id, true) }, onDeny = { haptic(false); onDecide(c.id, false) }, onOpenDraft = onOpenDraft)
            if (n > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                PillButton("Approve all", onClick = { haptic(true); onDecideAll(true) }, enabled = done < n, contentDescription = "Approve all $n pending tool calls")
                PillButton("Deny all", onClick = { haptic(false); onDecideAll(false) }, tone = Tone.Neutral, outlined = true, enabled = done < n, contentDescription = "Deny all pending tool calls")
            }
        }
    }
}

/** Approve = CONFIRM, Deny = REJECT on API 30+; LongPress below. */
@Composable
private fun rememberDecisionHaptic(): (Boolean) -> Unit {
    val view = LocalView.current
    val hf = LocalHapticFeedback.current
    return remember(view, hf) {
        { ok: Boolean ->
            if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
            else hf.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

/**
 * One pending call (DESIGN4P §3.2): risk pill · name, the second-opinion reason (DESIGN5 §6.2), a one-line preview + Details (the full v4
 * preview: skill markdown / command or code with "Show all N lines" + run_js allow-list / draft summary with Open in editor and
 * "Runs unattended" lines in the error tint), then Approve / Deny, morphing to the decided label.
 */
@Composable
private fun PendingCallRow(c: PendingCall, decided: Boolean?, startExpanded: Boolean, onApprove: () -> Unit, onDeny: () -> Unit, onOpenDraft: (ChatDrafts.Draft) -> Unit) {
    val motion = LocalMotion.current
    val cs = MaterialTheme.colorScheme
    val tu = remember(c.id) { c.toolUse() }
    val preview = remember(c.id) { runCatching { OperatorTools.previewFor(tu) }.getOrElse { tu.input.toString() } }
    val state = when (decided) { true -> "approved, waiting"; false -> "denied"; null -> "pending approval" }
    var expanded by rememberSaveable(c.id) { mutableStateOf(startExpanded) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, motion.spatialFast(), label = "details")
    val draft = if (tu.name == "save_workflow") ChatDrafts.map[tu.input["draftId"].asTextOrNull()] else null
    Surface(color = cs.surfaceContainerHighest, contentColor = cs.onSurface, shape = RoundedCornerShape(Radius.m), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${c.toolName} $state" }) {
        Column(Modifier.padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                RiskPill(c.decision.risk, Permissions.chip(c.decision))
                Text(c.toolName, style = CodeSmallStyle, modifier = Modifier.align(Alignment.CenterVertically))
            }
            // DESIGN5 §6.2: an escalated call shows the second-opinion reason under the chip (baseline reasons only repeat the chip).
            if (c.decision.reason.startsWith("second opinion")) Text(c.decision.reason, style = MaterialTheme.typography.bodySmall)
            // M3: a one-line preview is shown once, wrapped in full (never cut: the user approves exactly what they can read); Details only
            // when the preview has more than the summary line.
            if (!previewHasDetails(preview) && draft == null) Text(summaryLine(preview, c.toolName), style = if (tu.name == "run_shell") CodeSmallStyle else MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs))
            else {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.s)).clickable { expanded = !expanded }
                .semantics { role = Role.Button; contentDescription = "${if (expanded) "Hide" else "Show"} details of ${c.toolName}" },
                verticalAlignment = Alignment.CenterVertically) {
                // expanded: the full preview below already starts with this line
                Text(if (expanded) "" else summaryLine(preview, c.toolName), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Details", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(start = Space.s))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp).rotate(chevron))
            }
            ExpandableSection(expanded) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    when (tu.name) {
                        "skill_create", "skill_update" -> MarkdownText(preview)
                        "run_shell", "run_js", "workspace_write" -> {
                            MonoBlock(preview, Modifier.fillMaxWidth(), maxLines = 20)
                            if (tu.name == "run_js") {
                                val allow = ((tu.input["allowNodes"] as? JsonArray)?.mapNotNull { it.asTextOrNull() } ?: emptyList()) + (if (tu.input["allowNetwork"].asTextOrNull() == "true") listOf("data.http") else emptyList())
                                Text(if (allow.isEmpty()) "may call: no nodes (no network)" else "may call: ${allow.distinct().joinToString(", ")}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        // DESIGN4P P16: "Runs unattended: …" lines in the error tint (the words carry the meaning, the colour only underlines it).
                        "save_workflow" -> for (line in preview.lines()) Text(line, style = MaterialTheme.typography.bodySmall,
                            color = if (UNATTENDED_PREFIX in line) cs.error else cs.onSurface)
                        else -> Text(preview, style = MaterialTheme.typography.bodySmall)
                    }
                    if (draft != null) OpenDraftButton(draft, onOpenDraft)
                }
            }
            }
            // Always visible, never behind Details: approving an expired draft would fail.
            if (tu.name == "save_workflow" && draft == null)
                Text("Draft no longer in memory — approving will fail; ask for a new draft.", style = MaterialTheme.typography.bodySmall, color = cs.error)
            AnimatedContent(decided, label = "decision",
                transitionSpec = { ((fadeIn(motion.effect()) + scaleIn(motion.spatialFast(), initialScale = 0.9f)) togetherWith fadeOut(motion.exitEffect())).using(SizeTransform { _, _ -> motion.spatial() }) }) { d ->
                when (d) {
                    true -> Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        StatusGlyph(GlyphState.Done); Text("Approved — waiting for the other calls", style = MaterialTheme.typography.labelLarge)
                    }
                    false -> Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        StatusGlyph(GlyphState.Denied); Text("Denied", style = MaterialTheme.typography.labelLarge)
                    }
                    null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        PillButton("Approve", onClick = onApprove, contentDescription = "Approve ${c.toolName}")
                        PillButton("Deny", onClick = onDeny, tone = Tone.Neutral, outlined = true, contentDescription = "Deny ${c.toolName}")
                    }
                }
            }
        }
    }
}
