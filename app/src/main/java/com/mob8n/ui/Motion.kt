package com.mob8n.ui

import android.content.res.Resources
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// DESIGN6 §3: motion is a token system with one switch (LocalMotion). Every spec below snaps and every loop is static when reduced.

object MotionTokens {
    const val SHORT1 = 90; const val SHORT2 = 150; const val MEDIUM1 = 220; const val MEDIUM2 = 300; const val LONG1 = 380; const val LONG2 = 600
    const val CARET_MS = 1000; const val SHIMMER_MS = 1300; const val PULSE_MS = 1400; const val FLOW_MS = 1200
    const val STAGGER_MS = 35; const val STAGGER_MAX = 6; const val PRESS_SCALE = 0.97f; const val STREAM_FRAME_MS = 40L
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Pure (MotionTest): the one switch. */
fun motionReduced(systemAnimatorScale: Float, inAppReduce: Boolean): Boolean = inAppReduce || systemAnimatorScale == 0f

// ponytail: 12 dp rise converted with the system density (MotionScheme holds no Density); upgrade = pass LocalDensity if a display override differs
private fun risePx(): Int = (12 * (runCatching { Resources.getSystem().displayMetrics.density }.getOrNull() ?: 2.5f)).roundToInt()

@Immutable
class MotionScheme(val reduced: Boolean) {
    fun <T> spatial(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(0.85f, 420f)
    fun <T> spatialFast(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(0.9f, 900f)
    fun <T> emphasis(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(0.72f, 380f)
    fun <T> effect(durationMs: Int = MotionTokens.SHORT2, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, delayMs, MotionTokens.Standard)
    fun <T> enterEffect(durationMs: Int = MotionTokens.MEDIUM1, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, delayMs, MotionTokens.Decelerate)
    fun <T> exitEffect(durationMs: Int = MotionTokens.SHORT1): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, 0, MotionTokens.Accelerate)

    /** Item/element enter: fade + 12 dp rise. */
    fun enter(delayMs: Int = 0): EnterTransition = if (reduced) EnterTransition.None else
        fadeIn(enterEffect(MotionTokens.MEDIUM1, delayMs)) + slideInVertically(enterEffect(MotionTokens.MEDIUM1, delayMs)) { risePx() }
    fun exit(): ExitTransition = if (reduced) ExitTransition.None else
        fadeOut(exitEffect()) + slideOutVertically(exitEffect()) { risePx() / 2 }
    fun expand(): EnterTransition = if (reduced) EnterTransition.None else expandVertically(spatial()) + fadeIn(enterEffect())
    fun collapse(): ExitTransition = if (reduced) ExitTransition.None else shrinkVertically(spatial()) + fadeOut(exitEffect())
    fun staggerMs(index: Int): Int = if (reduced) 0 else minOf(index, MotionTokens.STAGGER_MAX) * MotionTokens.STAGGER_MS
    /** For looping effects: false -> draw the static frame. */
    val loops: Boolean get() = !reduced
}

/** Linear restart loop for the looping effects (only started when `loops`). */
internal fun loopSpec(periodMs: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(periodMs, easing = LinearEasing))

val LocalMotion = staticCompositionLocalOf { MotionScheme(reduced = false) }

/** Settings.Global.ANIMATOR_DURATION_SCALE, re-read on every resume (rememberResumeTick); 1f if unreadable.
 *  ponytail: no ContentObserver; upgrade = observe Settings.Global if users flip it without leaving the app. */
@Composable
fun rememberSystemAnimatorScale(): Float {
    val cr = LocalContext.current.contentResolver
    val tick = rememberResumeTick()
    return remember(tick) { runCatching { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) }
}

/** Scale to PRESS_SCALE while pressed (graphicsLayer; no recomposition per frame). No-op when reduced. */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val motion = LocalMotion.current
    if (motion.reduced) return@composed Modifier
    val pressed by interactionSource.collectIsPressedAsState()
    val s = animateFloatAsState(if (pressed) MotionTokens.PRESS_SCALE else 1f, motion.spatialFast(), label = "press")
    Modifier.graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** Skeleton sweep drawn in drawWithCache/drawWithContent; static fill when reduced. */
fun Modifier.shimmer(): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    val base = cs.surfaceContainerHighest
    val motion = LocalMotion.current
    if (!motion.loops) return@composed Modifier.drawWithCache { onDrawBehind { drawRect(base) } }
    val highlight = cs.surfaceContainerLow
    val t = rememberInfiniteTransition(label = "shimmer")
    val p = t.animateFloat(0f, 1f, loopSpec(MotionTokens.SHIMMER_MS), label = "sweep")
    Modifier.drawWithCache {
        val band = size.width * 0.5f
        val brush = Brush.horizontalGradient(listOf(base, highlight, base), startX = 0f, endX = band)
        val bandSize = Size(band, size.height)
        onDrawBehind {
            drawRect(base)
            clipRect { translate(left = -band + p.value * (size.width + band)) { drawRect(brush, size = bandSize) } }
        }
    }
}

/** First-appearance fade + rise with stagger; `enabled = false` renders immediately (callers pass a firstLoad flag). */
fun Modifier.enterOnce(index: Int = 0, enabled: Boolean = true): Modifier = composed {
    val motion = LocalMotion.current
    // M7: an item composed after the first load (or with reduced motion) never animates: no graphicsLayer at all, one RenderNode fewer per card.
    val animate = remember { enabled && !motion.reduced }
    if (!animate) return@composed Modifier
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { if (a.value < 1f) a.animateTo(1f, motion.enterEffect(MotionTokens.MEDIUM1, motion.staggerMs(index))) }
    Modifier.graphicsLayer {
        val v = a.value
        alpha = v; translationY = (1f - v) * 12.dp.toPx()
    }
}

/** Soft pulsing ring (outline alpha/scale in graphicsLayer) while `active`; static 2 dp ring when reduced. */
fun Modifier.pulse(active: Boolean, color: Color, shape: Shape = CircleShape): Modifier = composed {
    if (!active) return@composed Modifier
    val motion = LocalMotion.current
    if (!motion.loops) return@composed Modifier.border(2.dp, color, shape)
    val t = rememberInfiniteTransition(label = "pulse")
    val p = t.animateFloat(0f, 1f, loopSpec(MotionTokens.PULSE_MS), label = "ring")
    Modifier.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(2.dp.toPx())
        val center = Offset(size.width / 2f, size.height / 2f)
        onDrawWithContent {
            drawContent()
            drawOutline(outline, color, style = stroke)
            val v = p.value
            scale(1f + 0.35f * v, pivot = center) { drawOutline(outline, color, alpha = (1f - v) * 0.6f, style = stroke) }
        }
    }
}

/** Caret alpha 1 -> 0 -> 1 over CARET_MS (read in graphicsLayer); constant 1f when reduced or !active. */
@Composable
fun rememberBlink(active: Boolean): State<Float> {
    val motion = LocalMotion.current
    if (!active || !motion.loops) return remember { mutableFloatStateOf(1f) }
    val t = rememberInfiniteTransition(label = "caret")
    val phase = t.animateFloat(0f, 1f, loopSpec(MotionTokens.CARET_MS), label = "blink")
    return remember { derivedStateOf { caretAlpha(phase.value) } }
}

/** Caret alpha at a phase 0..1 of CARET_MS: 500 ms on, 500 ms off, 120 ms fades. Pure. */
internal fun caretAlpha(phase: Float): Float {
    val ms = phase * MotionTokens.CARET_MS
    return when {
        ms < 380f -> 1f
        ms < 500f -> 1f - (ms - 380f) / 120f
        ms < 880f -> 0f
        else -> ((ms - 880f) / 120f).coerceAtMost(1f)
    }
}
