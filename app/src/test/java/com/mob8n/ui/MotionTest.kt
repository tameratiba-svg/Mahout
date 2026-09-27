package com.mob8n.ui

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 §9 ui-foundation: reduced motion (either switch) -> every spec snaps, no stagger, no loops. */
class MotionTest {
    @Test fun oneSwitchTruthTable() {
        for (scale in listOf(0f, 0.5f, 1f)) for (toggle in listOf(false, true))
            assertEquals("scale $scale toggle $toggle", toggle || scale == 0f, motionReduced(scale, toggle))
    }

    @Test fun reducedSnaps() {
        val m = MotionScheme(reduced = true)
        assertTrue(m.spatial<Float>() is SnapSpec)
        assertTrue(m.spatialFast<Float>() is SnapSpec)
        assertTrue(m.emphasis<Float>() is SnapSpec)
        assertTrue(m.effect<Float>() is SnapSpec)
        assertTrue(m.enterEffect<Float>(delayMs = 100) is SnapSpec)
        assertTrue(m.exitEffect<Float>() is SnapSpec)
        assertEquals(0, m.staggerMs(5))
        assertFalse(m.loops)
    }

    @Test fun fullMotion() {
        val m = MotionScheme(reduced = false)
        assertTrue(m.spatial<Float>() is SpringSpec)
        assertTrue(m.effect<Float>() is TweenSpec)
        assertEquals(MotionTokens.SHORT2, (m.effect<Float>() as TweenSpec).durationMillis)
        assertEquals(210, m.staggerMs(10))
        assertEquals(70, m.staggerMs(2))
        assertEquals(0, m.staggerMs(0))
        assertTrue(m.loops)
    }

    @Test fun caretBlinkShape() {
        assertEquals(1f, caretAlpha(0f), 0f)
        assertEquals(1f, caretAlpha(0.3f), 0f)
        assertEquals(0.5f, caretAlpha(0.44f), 0.01f)
        assertEquals(0f, caretAlpha(0.6f), 0f)
        assertEquals(1f, caretAlpha(1f), 0f)
    }
}
