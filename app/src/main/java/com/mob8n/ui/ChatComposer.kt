@file:OptIn(ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mob8n.ai.ChatImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one 48 dp button at the composer's end (§5.3.6). */
internal enum class SendKind { MIC, SEND, STOP }
internal fun sendKind(busy: Boolean, hasText: Boolean): SendKind = when { busy -> SendKind.STOP; hasText -> SendKind.SEND; else -> SendKind.MIC }

/** Voice result appended to the draft, space-separated; never auto-sent (D19). */
internal fun appendSpoken(draft: String, spoken: String): String = if (draft.isBlank()) spoken.trim() else draft.trimEnd() + " " + spoken.trim()

/** [+] > Command: "/" at the start of the draft (the only place a command is active). */
internal fun insertSlash(v: TextFieldValue): TextFieldValue = if (v.text.startsWith("/")) v.copy(selection = TextRange(1)) else TextFieldValue("/" + v.text, TextRange(1))

/** [+] > Mention: "@" at the cursor, after a space when needed. */
internal fun insertAt(v: TextFieldValue): TextFieldValue {
    val c = v.selection.end.coerceIn(0, v.text.length)
    val pre = if (c > 0 && !v.text[c - 1].isWhitespace()) " @" else "@"
    return TextFieldValue(v.text.substring(0, c) + pre + v.text.substring(c), TextRange(c + pre.length))
}

/** Popup rows: slash suggestions or mention candidates. */
private sealed interface PopupItem {
    data class Slash(val s: SlashSuggestion) : PopupItem
    data class Men(val m: Mention) : PopupItem
}

/**
 * Composer (§5.3.11–15): L2 bar with a top hairline; [+] menu (Photo when the default model reads images, Mention, Command) · growing field
 * (Radius.l, 1–6 lines then scrolls) · Mic / Send / Stop morph. Above the field: the slash / @-mention popup (a surface in this column, not
 * a Popup window, so IME focus never breaks; arrow / Enter / Tab / Esc on hardware keyboards) and the context chips (mentions, image).
 */
@Composable
internal fun ChatComposer(
    draft: TextFieldValue, onDraft: (TextFieldValue) -> Unit, hint: String, enabled: Boolean, busy: Boolean, maxLines: Int,
    mentions: List<Mention>, onRemoveMention: (Mention) -> Unit, onAddMention: (Mention) -> Unit,
    imagePath: String?, onImage: (String?) -> Unit, visionOk: Boolean,
    sources: SlashSources, knowledge: List<String>,
    onSend: () -> Unit, onStop: () -> Unit, onMessage: (String) -> Unit, focus: FocusRequester, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val motion = LocalMotion.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val latestDraft by rememberUpdatedState(draft)

    // ---- popup state (pure parsing in ChatSlash.kt) ----
    val cursor = draft.selection.end
    val slash = remember(draft.text, cursor) { parseSlash(draft.text, cursor) }
    val mentionQ = remember(draft.text, cursor) { if (slash == null) parseMention(draft.text, cursor) else null }
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val items: List<PopupItem> = remember(slash, mentionQ, sources, knowledge, dismissedFor, draft.text) {
        when {
            dismissedFor == draft.text -> emptyList()
            slash != null -> slashSuggestions(slash, sources).map { PopupItem.Slash(it) }
            mentionQ != null -> mentionSuggestions(mentionQ.query, sources.workflows, sources.skills, knowledge).map { PopupItem.Men(it) }
            else -> emptyList()
        }
    }
    var selected by remember(items) { mutableIntStateOf(0) }
    fun pick(item: PopupItem) {
        when (item) {
            // picking the row that is already complete sends it (hardware Enter would otherwise re-pick the same row forever)
            is PopupItem.Slash -> if (item.s.text == draft.text.trim()) onSend() else onDraft(TextFieldValue(item.s.text, TextRange(item.s.text.length)))
            is PopupItem.Men -> {
                val q = mentionQ ?: return
                val (t, c) = removeMentionQuery(draft.text, q, cursor)
                onDraft(TextFieldValue(t, TextRange(c)))
                onAddMention(item.m)
            }
        }
    }

    // ---- attach + voice (no permissions: photo picker, system speech UI) ----
    var attachMenu by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ChatImages.import(ctx, uri) } }.onSuccess { onImage(it) }.onFailure { onMessage("Could not attach the image: ${it.message}") }
        }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { spoken ->
            val t = appendSpoken(latestDraft.text, spoken); onDraft(TextFieldValue(t, TextRange(t.length)))
        }
    }
    fun startVoice() {
        try {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Mahout"))
        } catch (_: ActivityNotFoundException) { onMessage("No speech recognizer on this device") }
    }

    val hairline = cs.outlineVariant
    Surface(color = cs.surfaceContainer, modifier = modifier.fillMaxWidth().drawBehind { drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            AnimatedVisibility(items.isNotEmpty() && enabled, enter = motion.expand(), exit = motion.collapse()) {
                Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(Radius.l), shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.heightIn(max = 288.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
                        items.forEachIndexed { i, item -> PopupRow(item, i == selected, onClick = { pick(item) }) }
                    }
                }
            }
            AnimatedVisibility(mentions.isNotEmpty() || imagePath != null, enter = motion.expand(), exit = motion.collapse()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (imagePath != null) {
                        val thumb = rememberThumb(imagePath, 48.dp)
                        InputChip(selected = false, onClick = { onImage(null) }, label = { Text("Image") },
                            avatar = { thumb.bitmap?.let { Image(BitmapPainter(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(InputChipDefaults.AvatarSize).clip(RoundedCornerShape(Radius.xs))) } },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.semantics { contentDescription = "Remove attached image" })
                    }
                    for (m in mentions) InputChip(selected = false, onClick = { onRemoveMention(m) }, label = { Text("${m.kind.word}: ${m.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.semantics { contentDescription = "Remove mention ${m.name}" })
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Box {
                    RoundButton(onClick = { attachMenu = true }, container = cs.surfaceContainerHighest, content = cs.onSurfaceVariant, description = "Attach or insert", enabled = enabled) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        if (visionOk) DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) }, onClick = {
                            attachMenu = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("Mention") }, leadingIcon = { Icon(Icons.Rounded.AlternateEmail, contentDescription = null) }, onClick = {
                            attachMenu = false; onDraft(insertAt(draft)); runCatching { focus.requestFocus() }
                        })
                        DropdownMenuItem(text = { Text("Command") }, leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) }, onClick = {
                            attachMenu = false; onDraft(insertSlash(draft)); runCatching { focus.requestFocus() }
                        })
                    }
                }
                BasicTextField(
                    value = draft, onValueChange = onDraft, enabled = enabled, maxLines = maxLines,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).animateContentSize(motion.spatial())
                        .background(cs.surfaceContainerHighest, RoundedCornerShape(Radius.l))
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || items.isEmpty()) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { selected = (selected + 1) % items.size; true }
                                Key.DirectionUp -> { selected = (selected - 1 + items.size) % items.size; true }
                                Key.Enter, Key.NumPadEnter, Key.Tab -> { pick(items[selected.coerceIn(0, items.lastIndex)]); true }
                                Key.Escape -> { dismissedFor = draft.text; true }
                                else -> false
                            }
                        }
                        .semantics { contentDescription = "Message" },
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = Space.l, vertical = Space.m), contentAlignment = Alignment.CenterStart) {
                            if (draft.text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                SendStopButton(sendKind(busy, draft.text.isNotBlank()), enabled = enabled, onSend = onSend, onStop = onStop, onMic = ::startVoice)
            }
        }
    }
}

@Composable
private fun PopupRow(item: PopupItem, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (label, help, danger) = when (item) {
        is PopupItem.Slash -> Triple(item.s.label, item.s.help, item.s.needsConfirm)
        is PopupItem.Men -> Triple("@${item.m.name}", item.m.kind.word, false)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (on) cs.primaryContainer else cs.surfaceContainerHigh)
        .clickable(onClick = onClick).semantics { role = Role.Button; selected = on; contentDescription = "$label, $help" }
        .padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(label, style = CodeSmallStyle, color = if (danger) cs.error else if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(min = 64.dp))
        Text(help, style = MaterialTheme.typography.bodySmall, color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, description: String, enabled: Boolean = true, icon: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = container, contentColor = content, interactionSource = src,
        modifier = Modifier.size(48.dp).pressScale(src).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

/** Mic (empty, idle) · Send (text) · Stop (busy): AnimatedContent with scale 0.6 -> 1 + fade (emphasis); Stop rotates in 90°. */
@Composable
private fun SendStopButton(kind: SendKind, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit, onMic: () -> Unit) {
    val motion = LocalMotion.current
    val cs = MaterialTheme.colorScheme
    AnimatedContent(kind, label = "send",
        transitionSpec = { (scaleIn(motion.emphasis(), initialScale = 0.6f) + fadeIn(motion.effect())) togetherWith (scaleOut(motion.exitEffect(), targetScale = 0.6f) + fadeOut(motion.exitEffect())) using SizeTransform(clip = false) { _, _ -> motion.spatial() } }) { k ->
        when (k) {
            SendKind.STOP -> {
                val rot = remember { Animatable(if (motion.reduced) 0f else -90f) }
                LaunchedEffect(Unit) { rot.animateTo(0f, motion.emphasis()) }
                RoundButton(onClick = onStop, container = cs.inverseSurface, content = cs.inverseOnSurface, description = "Stop the assistant") {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = rot.value })
                }
            }
            SendKind.SEND -> RoundButton(onClick = onSend, container = if (enabled) cs.primary else cs.surfaceContainerHighest, content = if (enabled) cs.onPrimary else cs.onSurfaceVariant, description = "Send", enabled = enabled) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
            }
            SendKind.MIC -> RoundButton(onClick = onMic, container = cs.surfaceContainerHighest, content = cs.onSurfaceVariant, description = "Voice input", enabled = enabled) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
            }
        }
    }
}

// ---- image thumbnails (composer chip + user bubble) ----

internal class Thumb(val loading: Boolean, val bitmap: ImageBitmap?)

/** Decodes `path` on IO with inSampleSize (≤ ~2× the target size); `loading` until done, then bitmap or null (missing / evicted file). */
@Composable
internal fun rememberThumb(path: String, max: Dp): Thumb {
    val px = with(LocalDensity.current) { max.roundToPx() }
    val t by produceState(Thumb(true, null), path, px) { value = Thumb(false, withContext(Dispatchers.IO) { decodeThumb(path, px) }) }
    return t
}

private fun decodeThumb(path: String, maxPx: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    var s = 1
    while (o.outWidth / (s * 2) >= maxPx && o.outHeight / (s * 2) >= maxPx) s *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()
