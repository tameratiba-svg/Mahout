package com.mob8n.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F42: edge hit radius is 24 *screen* dp (so it grows in graph units when zoomed out) and the curve is sampled at three points. */
class CanvasHitTest {
    private val a = Offset(200f, 46f)
    private val b = Offset(460f, 176f)
    private val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)   // symmetric control points: t=0.5 lands on the midpoint

    @Test fun hitAtFitZoomMissAtFullZoom() {
        val off30 = Offset(mid.x, mid.y + 30f)
        assertFalse("30 graph-dp = 30 screen dp at scale 1", edgeHit(off30, a, b, 1f))
        assertTrue("30 graph-dp = 10.5 screen dp at scale 0.35", edgeHit(off30, a, b, 0.35f))
        val off20 = Offset(mid.x, mid.y + 20f)
        assertTrue("20 graph-dp = 20 screen dp at scale 1", edgeHit(off20, a, b, 1f))
        assertFalse("20 graph-dp = 50 screen dp at scale 2.5", edgeHit(off20, a, b, 2.5f))
    }

    @Test fun quarterPointsAreHitToo() {
        // The cubic starts horizontally from a, so at t=0.25 it is still near a.y: a tap there is far from the midpoint but on the curve.
        val quarter = Offset(a.x + 0.25f * (b.x - a.x), a.y + 20f)
        assertTrue(edgeHit(quarter, a, b, 1f))
        assertFalse(edgeHit(Offset(a.x, b.y), a, b, 1f))     // the opposite corner of the bounding box is not on the curve
    }
}
