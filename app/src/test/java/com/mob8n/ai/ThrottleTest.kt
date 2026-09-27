package com.mob8n.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleTest {
    @Test fun leadingEdgeWithinOnePeriod() {
        val t = Throttle(STREAM_FRAME_MS)
        assertTrue(t.ready(0)); assertFalse(t.ready(10)); assertFalse(t.ready(39))
        assertTrue(t.ready(40)); assertFalse(t.ready(79)); assertTrue(t.ready(80))
    }

    @Test fun thousandDeltasPerSecondPublishAtMost26Times() {
        val t = Throttle(STREAM_FRAME_MS)
        val n = (0 until 1000).count { t.ready(it.toLong()) }
        assertTrue("$n", n <= 26); assertEquals(25, n)
    }
}
