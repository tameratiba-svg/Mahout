package com.mob8n.ui

import com.mob8n.engine.Stats
import com.mob8n.engine.UsageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4 §11 ui: sparkline normalisation with all-zero input, fmtTokens, fmtUsd(null) == "—", usage line wording. */
class DashboardMathTest {
    @Test fun sparklineNormalisation() {
        assertEquals(listOf(0f, 0f, 0f), sparklineNorm(listOf(0, 0, 0)))            // max(1, 0): flat baseline, no NaN
        assertTrue(sparklineNorm(listOf(0, 0)).none { it.isNaN() })
        assertEquals(listOf(0.5f, 1f, 0f), sparklineNorm(listOf(2, 4, 0)))
        assertEquals(listOf(1f), sparklineNorm(listOf(1)))
        assertEquals(emptyList<Float>(), sparklineNorm(emptyList()))
        assertEquals(listOf(0f, 1f), sparklineNorm(listOf(-3, 5)))                   // negatives clamp to the baseline
        assertTrue(sparklineNorm(listOf(3, 7, 7, 1)).all { it in 0f..1f })
    }

    @Test fun tokenAndUsdFormatting() {
        assertEquals("12.3k", Stats.fmtTokens(12_300))
        assertEquals("—", Stats.fmtUsd(null))
        assertEquals("$0.12", Stats.fmtUsd(0.12))
    }

    @Test fun usageLineWording() {
        val known = usageLine(UsageRow("claude", "claude-sonnet-5", 3, 12_300, 4_100, 8_000, 0.12, 0, false))
        assertTrue(known, known.startsWith("claude · claude-sonnet-5 — 3 calls"))
        assertTrue(known, "in 12.3k" in known && "out 4.1k" in known && "cached 8.0k" in known && known.endsWith("$0.12"))
        val unknown = usageLine(UsageRow("minimax", "MiniMax-M2.7", 1, 10, 5, 0, null, 1, false))
        assertTrue(unknown, unknown.endsWith("price unknown") && "1 call ·" in unknown && "cached" !in unknown)
        assertFalse(unknown, "$0" in unknown)                                          // unknown never shows as $0
        val est = usageLine(UsageRow("on_device_gemini_nano", "gemini-nano", 2, 400, 80, 0, 0.0, 0, true))
        assertTrue(est, "≈ in" in est)
        assertEquals("1 succeeded · 2 failed · 0 waiting", countsLine(com.mob8n.engine.RunCounts(3, 1, 2, 0, 0, 0)))
    }
}
