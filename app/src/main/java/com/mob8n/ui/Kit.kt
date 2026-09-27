@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mob8n.R
import com.mob8n.ai.Risk
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// DESIGN6 §4 components kit. Every animation takes its spec from LocalMotion; loops draw a static frame when reduced.

enum class Tone { Primary, Neutral, Positive, Caution, Danger, Info }
enum class GlyphState { Pending, Running, Done, Failed, Denied }

/** Top bar: titleLarge single line (ellipsis), optional subtitle (labelMedium, onSurfaceVariant), back arrow when onBack != null.
 *  Container animates surface -> surfaceContainer when scrollBehavior reports overlap. Window insets = statusBars.
 *  The subtitle hides above 150 % font scale (DESIGN6 §5.4); the bar grows with the text instead of clipping it. */
@Composable
fun MahoutTopBar(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}, scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val cs = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val sub = subtitle?.takeIf { fontScale <= 1.5f }
    val textDp = (if (sub != null) 28 + 16 else 28) * fontScale + 12
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = modifier,
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                onBack != null -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = actions,
        expandedHeight = maxOf(64f, textDp).dp,
        windowInsets = WindowInsets.statusBars,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface, scrolledContainerColor = cs.surfaceContainer),
        scrollBehavior = scrollBehavior,
    )
}

/** L1 card (`mahout.card`, light hairline), Radius.m, 16 dp padding, optional title row (titleMedium + trailing slot). Clickable -> pressScale + ripple.
 *  `description` becomes the merged contentDescription (DESIGN4 §6.3 rule: numbers stated in words). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier, title: String? = null, description: String? = null, onClick: (() -> Unit)? = null,
    tone: Tone = Tone.Neutral,
    trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val c = MaterialTheme.mahout
    val (bg, fg) = if (tone == Tone.Neutral) c.card to cs.onSurface else toneColors(tone, cs, c)
    val border = if (tone == Tone.Neutral && c.cardLine.alpha > 0f) BorderStroke(1.dp, c.cardLine) else null
    val shape = RoundedCornerShape(Radius.m)
    val sem = if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(Space.l)) {
            if (title != null || trailing != null) {
                // Title + trailing wrap as a flow (device phase: at 200 % font scale on a phone a wide trailing pill squeezed the title to 3 letters per line).
                if (title != null && trailing != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    Box(Modifier.align(Alignment.CenterVertically)) { trailing() }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(Space.s))
            }
            content()
        }
    }
    if (onClick != null) {
        val src = remember { MutableInteractionSource() }
        Surface(onClick = onClick, modifier = modifier.pressScale(src).then(sem), shape = shape, color = bg, contentColor = fg,
            border = border, interactionSource = src, content = body)
    } else {
        Surface(modifier = modifier.then(sem), shape = shape, color = bg, contentColor = fg, border = border, content = body)
    }
}

/** Pill button (Radius.full, 48 dp min height, labelLarge). Primary = filled primary, Neutral = tonal surfaceContainerHighest,
 *  Positive = success, Caution = warningContainer, Danger = error/onError. pressScale. */
@Composable
fun PillButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: Tone = Tone.Primary,
    enabled: Boolean = true, outlined: Boolean = false, contentDescription: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val c = MaterialTheme.mahout
    val src = remember { MutableInteractionSource() }
    val mod = modifier.heightIn(min = 48.dp).pressScale(src)
        .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
    val pad = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    val inner: @Composable RowScope.() -> Unit = {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(Space.s)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
    if (outlined) {
        val fg = when (tone) {                     // text on surface: every pair is in CONTRAST_PAIRS
            Tone.Primary -> cs.primary; Tone.Neutral -> cs.onSurface; Tone.Positive -> c.success
            Tone.Caution -> c.warning; Tone.Danger -> cs.error; Tone.Info -> c.info
        }
        OutlinedButton(onClick = onClick, modifier = mod, enabled = enabled, shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
            border = BorderStroke(1.dp, if (enabled) cs.outline else cs.outline.copy(alpha = 0.38f)),
            contentPadding = pad, interactionSource = src, content = inner)
    } else {
        val (bg, fg) = when (tone) {
            Tone.Primary -> cs.primary to cs.onPrimary
            Tone.Neutral -> cs.surfaceContainerHighest to cs.onSurface
            Tone.Positive -> c.success to c.onSuccess
            Tone.Caution -> c.warningContainer to c.onWarningContainer
            Tone.Danger -> cs.error to cs.onError
            Tone.Info -> cs.tertiary to cs.onTertiary
        }
        Button(onClick = onClick, modifier = mod, enabled = enabled, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
            contentPadding = pad, interactionSource = src, content = inner)
    }
}

/** Status pill: dot + text (labelMedium) on the tone's container colour; colours animate (effect spec); `pulsing` = Modifier.pulse on the dot. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = true, pulsing: Boolean = false) {
    val motion = LocalMotion.current
    val (bgT, fgT) = toneColors(tone, MaterialTheme.colorScheme, MaterialTheme.mahout)
    val bg by animateColorAsState(bgT, motion.effect(), label = "pillBg")
    val fg by animateColorAsState(fgT, motion.effect(), label = "pillFg")
    Row(
        modifier.clip(RoundedCornerShape(Radius.s)).background(bg).heightIn(min = 24.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(8.dp).pulse(pulsing, fg).background(fg, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Risk pill: container/on-container from riskColors(risk); text = the caller's words (Permissions.chip / riskLabel). */
@Composable
fun RiskPill(risk: Risk, text: String, modifier: Modifier = Modifier) {
    val (bg, fg) = riskColors(risk, MaterialTheme.mahout)
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = fg,
        modifier = modifier.clip(RoundedCornerShape(Radius.s)).background(bg).heightIn(min = 24.dp).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Pure: which slots of `to` changed versus `from`, slots aligned from the right (units stay units as the number grows). */
fun digitChanges(from: String, to: String): List<Boolean> = to.indices.map { i ->
    val j = from.length - (to.length - i)
    j < 0 || from[j] != to[i]
}

/** Rolling digits: each changed digit slides vertically (spatial spec) in a slot as wide as the widest digit, so width never jitters.
 *  Semantics: contentDescription = format(value) (only the final value is announced). */
@Composable
fun AnimatedCounter(value: Long, modifier: Modifier = Modifier, style: TextStyle = NumericStyle, format: (Long) -> String = { it.toString() }) {
    val motion = LocalMotion.current
    val text = format(value)
    val prev = remember { arrayOf(text, value.toString()) }          // plain holder: no extra recomposition
    val changes = digitChanges(prev[0], text)
    val up = value >= (prev[1].toLongOrNull() ?: value)
    SideEffect { prev[0] = text; prev[1] = value.toString() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digitW = remember(style, density) { with(density) { (0..9).maxOf { measurer.measure(it.toString(), style).size.width }.toDp() } }
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                if (!ch.isDigit()) Text(ch.toString(), style = style)
                else {
                    val roll = changes[i]
                    AnimatedContent(
                        targetState = ch, modifier = Modifier.width(digitW).clipToBounds(), contentAlignment = Alignment.Center, label = "digit",
                        transitionSpec = {
                            if (!roll || motion.reduced) EnterTransition.None togetherWith ExitTransition.None
                            else (slideInVertically(motion.spatial()) { if (up) it else -it } + fadeIn(motion.effect())) togetherWith
                                (slideOutVertically(motion.spatial()) { if (up) -it else it } + fadeOut(motion.exitEffect()))
                        },
                    ) { Text(it.toString(), style = style) }
                }
            }
        }
    }
}

/** Skeleton placeholders (Modifier.shimmer). */
@Composable
fun SkeletonLines(lines: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        repeat(lines) { i ->
            Box(Modifier.fillMaxWidth(if (i == lines - 1 && lines > 1) 0.6f else 1f).height(12.dp).clip(RoundedCornerShape(Radius.xs)).shimmer())
        }
    }
}

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Radius.m)).shimmer().semantics { contentDescription = "Loading" })
}

/** Centered empty state: BrandMark-tinted icon disc, headlineSmall title, bodyMedium body, optional action; enterOnce. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(Space.xl).enterOnce(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(Space.l))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.s))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(Space.xl)); action() }
    }
}

/** Two polylines (total in primary, failed in signal failed) drawn in drawWithCache; draws in over LONG2 on first show and on data change
 *  (PathMeasure.getSegment into a reused Path). `description` is the spoken series (DESIGN4 §6.3). Height 56 dp. */
@Composable
fun AnimatedSparkline(total: List<Int>, failed: List<Int>, description: String, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val primary = MaterialTheme.colorScheme.primary
    val failColor = nodeStatusColor(com.mob8n.core.NodeStatus.FAILED)
    val p = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(total, failed, motion.reduced) {
        if (motion.reduced) p.snapTo(1f) else { p.snapTo(0f); p.animateTo(1f, motion.effect(MotionTokens.LONG2)) }
    }
    Spacer(modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = description }.drawWithCache {
        val all = sparklineNorm(total + failed)                      // one scale for both series
        val pad = 3.dp.toPx()
        fun pathOf(v: List<Float>) = Path().apply {
            v.forEachIndexed { i, y ->
                val x = if (v.size == 1) size.width / 2 else i * size.width / (v.size - 1)
                val yy = pad + (1f - y) * (size.height - 2 * pad)
                if (i == 0) moveTo(x, yy) else lineTo(x, yy)
            }
        }
        val tp = pathOf(all.take(total.size)); val fp = pathOf(all.drop(total.size))
        val tm = PathMeasure().apply { setPath(tp, false) }
        val fm = PathMeasure().apply { setPath(fp, false) }
        val ts = Path(); val fs = Path()
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            val v = p.value
            // Drawn in: the full, stable paths (a fresh getSegment path every frame defeated the GPU path cache: device phase P6, Dashboard scroll).
            if (v >= 1f) { drawPath(tp, primary, style = stroke); drawPath(fp, failColor, style = stroke); return@onDrawBehind }
            ts.reset(); tm.getSegment(0f, tm.length * v, ts, true); drawPath(ts, primary, style = stroke)
            fs.reset(); fm.getSegment(0f, fm.length * v, fs, true); drawPath(fs, failColor, style = stroke)
        }
    })
}

/** Three dots rising in sequence (PULSE_MS/2 stagger); static "…" when reduced. contentDescription "Assistant is thinking". */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val motion = LocalMotion.current
    val sem = modifier.semantics { contentDescription = "Assistant is thinking" }
    if (!motion.loops) { Text("…", modifier = sem, color = color, style = MaterialTheme.typography.titleMedium); return }
    val t = rememberInfiniteTransition(label = "dots")
    val phase = t.animateFloat(0f, 1f, loopSpec(MotionTokens.PULSE_MS), label = "dotsPhase")
    Row(sem.heightIn(min = 20.dp), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(Modifier.size(6.dp).graphicsLayer {
                val local = (phase.value - i / 6f + 1f) % 1f                // the three rises span PULSE_MS / 2
                val bump = if (local < 0.4f) sin(PI.toFloat() * local / 0.4f) else 0f
                translationY = -4.dp.toPx() * bump; alpha = 0.4f + 0.6f * bump
            }.background(color, CircleShape))
        }
    }
}

/** Morphing glyph: Pending = hollow ring, Running = rotating arc (loop), Done = check drawn in, Failed = cross, Denied = slashed ring.
 *  Crossfade + scale (spatialFast) between states. Always paired with text by the caller. */
@Composable
fun StatusGlyph(state: GlyphState, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val motion = LocalMotion.current
    AnimatedContent(
        targetState = state, modifier = modifier.size(size), contentAlignment = Alignment.Center, label = "glyph",
        transitionSpec = {
            if (motion.reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(motion.effect()) + scaleIn(motion.spatialFast(), initialScale = 0.6f)) togetherWith fadeOut(motion.exitEffect())
        },
    ) { s -> GlyphCanvas(s, Modifier.size(size)) }
}

@Composable
private fun GlyphCanvas(s: GlyphState, modifier: Modifier) {
    val motion = LocalMotion.current
    val cs = MaterialTheme.colorScheme
    val c = MaterialTheme.mahout
    val rot = if (s == GlyphState.Running && motion.loops)
        rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, loopSpec(MotionTokens.CARET_MS), label = "spinAngle") else null
    val draw = remember { Animatable(if (s == GlyphState.Done && !motion.reduced) 0f else 1f) }
    LaunchedEffect(Unit) { if (draw.value < 1f) draw.animateTo(1f, motion.enterEffect()) }
    Spacer(modifier.drawWithCache {
        val w = size.minDimension
        val stroke = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val inset = stroke.width / 2
        val r = w / 2 - inset
        val mark = Path()
        onDrawBehind {
            when (s) {
                GlyphState.Pending -> drawCircle(cs.outline, r, style = stroke)
                GlyphState.Running -> rotate(rot?.value ?: 0f) {
                    drawArc(cs.primary, -90f, 270f, false, Offset(inset, inset), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = stroke)
                }
                GlyphState.Done -> {
                    drawCircle(c.success, w / 2)
                    // check: (0.28,0.52) -> (0.44,0.67) -> (0.73,0.36), drawn in by `draw`
                    val p = draw.value
                    mark.reset(); mark.moveTo(0.28f * w, 0.52f * w)
                    if (p < 0.4f) { val k = p / 0.4f; mark.lineTo((0.28f + 0.16f * k) * w, (0.52f + 0.15f * k) * w) }
                    else { val k = (p - 0.4f) / 0.6f; mark.lineTo(0.44f * w, 0.67f * w); mark.lineTo((0.44f + 0.29f * k) * w, (0.67f - 0.31f * k) * w) }
                    drawPath(mark, c.onSuccess, style = stroke)
                }
                GlyphState.Failed -> {
                    drawCircle(cs.error, w / 2)
                    drawLine(cs.onError, Offset(0.34f * w, 0.34f * w), Offset(0.66f * w, 0.66f * w), stroke.width, StrokeCap.Round)
                    drawLine(cs.onError, Offset(0.66f * w, 0.34f * w), Offset(0.34f * w, 0.66f * w), stroke.width, StrokeCap.Round)
                }
                GlyphState.Denied -> {
                    drawCircle(cs.onSurfaceVariant, r, style = stroke)
                    drawLine(cs.onSurfaceVariant, Offset(0.25f * w, 0.75f * w), Offset(0.75f * w, 0.25f * w), stroke.width, StrokeCap.Round)
                }
            }
        }
    })
}

/** AnimatedVisibility(expanded, enter = motion.expand(), exit = motion.collapse()). */
@Composable
fun ExpandableSection(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(expanded, modifier, enter = motion.expand(), exit = motion.collapse()) { content() }
}

/** Code block: codeBg, Radius.m, CodeStyle, horizontal scroll (no wrap), optional language label, Copy button (48 dp, "Copy code") ->
 *  ClipboardManager + "Copied" checkmark for 1.5 s; maxLines with "Show all N lines" when exceeded. Text selectable. */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, language: String? = null, copyable: Boolean = true, maxLines: Int = Int.MAX_VALUE) {
    val c = MaterialTheme.mahout
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val lines = remember(text) { text.lines() }
    val truncated = !showAll && lines.size > maxLines
    val shown = if (truncated) remember(text, maxLines) { lines.take(maxLines).joinToString("\n") } else text
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.m), color = c.codeBg, contentColor = c.onCode) {
        Column {
            if (language != null || copyable) {
                Row(Modifier.padding(start = Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Text(language.orEmpty(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    if (copyable) IconButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
                        if (copied) Icon(Icons.Rounded.Check, contentDescription = "Copied", modifier = Modifier.size(20.dp))
                        else Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(20.dp))
                    }
                }
            } else Spacer(Modifier.height(Space.m))
            SelectionContainer {
                Text(shown, style = CodeStyle, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = Space.l, end = Space.l, bottom = Space.m))
            }
            if (truncated) TextButton(onClick = { showAll = true }, modifier = Modifier.padding(start = Space.xs),
                colors = ButtonDefaults.textButtonColors(contentColor = c.onCode)) { Text("Show all ${lines.size} lines") }
        }
    }
}

/** App snackbar host: inverseSurface, Radius.m, action in inversePrimary. Every screen uses it (consistent toasts). */
@Composable
fun MahoutSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    SnackbarHost(state, modifier) { data ->
        Snackbar(data, shape = RoundedCornerShape(Radius.m), containerColor = cs.inverseSurface, contentColor = cs.inverseOnSurface,
            actionColor = cs.inversePrimary, dismissActionContentColor = cs.inverseOnSurface)
    }
}

/** The logo: R.drawable.ic_launcher_foreground on a navy gradient disc (brandNavy -> brandNavy2). contentDescription null (decorative). */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val c = MaterialTheme.mahout
    val brush = remember(c) { Brush.linearGradient(listOf(c.brandNavy, c.brandNavy2)) }
    Box(modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        // adaptive-icon foreground: 108-unit canvas, 72-unit visible circle -> draw at 1.5x and let the disc crop it
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}

/** Very soft teal radial glow (primary at 6 % alpha, top-end) drawn behind a screen; one drawBehind, no animation. */
fun Modifier.brandGlow(): Modifier = composed {
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    Modifier.drawWithCache {
        val x = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width
        val brush = Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(x, 0f), radius = maxOf(size.maxDimension * 0.7f, 1f))
        onDrawBehind { drawRect(brush) }
    }
}
