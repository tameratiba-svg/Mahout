package com.mob8n.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * DESIGN6 §6.4: one-time brand intro before the permissions onboarding. Logo scales 0.9 -> 1 + fades in (emphasis), then the title,
 * tagline and buttons rise in with a 120 ms stagger (~1.2 s). Tap anywhere completes the animation; reduced motion shows everything at once.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val motion = LocalMotion.current
    val brand = MaterialTheme.mahout
    val start = if (motion.reduced) 1f else 0f
    val logo = remember { Animatable(start) }
    val parts = remember { List(3) { Animatable(start) } }   // title, tagline, buttons
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, motion.emphasis()) }
        parts.forEachIndexed { i, a -> launch { a.animateTo(1f, motion.enterEffect(MotionTokens.LONG1, delayMs = 420 + 120 * i)) } }
    }
    fun Modifier.rise(a: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) = graphicsLayer {
        val v = a.value.coerceIn(0f, 1f)
        alpha = v; translationY = (1f - v) * 12.dp.toPx()
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(brand.brandNavy, brand.brandNavy2)))
            .pointerInput(Unit) { detectTapGestures { scope.launch { (parts + logo).forEach { launch { it.snapTo(1f) } } } } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl, vertical = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(Modifier.graphicsLayer {
                val v = logo.value
                alpha = v.coerceIn(0f, 1f); scaleX = 0.9f + 0.1f * v; scaleY = scaleX
            }, size = 112.dp)
            Spacer(Modifier.height(Space.xl))
            Text("Mahout", style = MaterialTheme.typography.displaySmall, color = brand.brandGoldLight, textAlign = TextAlign.Center, modifier = Modifier.rise(parts[0]))
            Spacer(Modifier.height(Space.s))
            Text("Automations you steer — on your phone.", style = MaterialTheme.typography.bodyLarge, color = brand.brandGoldLight, textAlign = TextAlign.Center,
                modifier = Modifier.rise(parts[1]))
            Spacer(Modifier.height(Space.xxl))
            Column(Modifier.rise(parts[2]), horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton("Get started", onClick = onDone)
                Spacer(Modifier.height(Space.s))
                TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = brand.brandGoldLight)) { Text("Skip") }
            }
        }
    }
}
