package com.mob8n.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 §9 ui-foundation: every text pair >= 4.5:1, every non-text signal >= 3:1, over the same tables the theme is built from. */
class ThemeContrastTest {
    private val m3Roles = listOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint",
        "inverseSurface", "inverseOnSurface", "error", "onError", "errorContainer", "onErrorContainer",
        "outline", "outlineVariant", "scrim", "surfaceBright", "surfaceDim",
        "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    )
    private val extTokens = listOf(
        "success", "onSuccess", "successContainer", "onSuccessContainer", "warning", "warningContainer", "onWarningContainer",
        "info", "infoContainer", "onInfoContainer", "userBubble", "onUserBubble", "assistantBubble", "onAssistantBubble",
        "card", "codeBg", "onCode", "riskRead", "onRiskRead", "riskWrite", "onRiskWrite", "riskCoding", "onRiskCoding",
        "riskAlways", "onRiskAlways", "caret",
    )

    @Test fun everyPairMeetsItsMinimum() {
        assertTrue(CONTRAST_PAIRS.size > 100)
        val fails = CONTRAST_PAIRS.filter { contrastRatio(it.fg, it.bg) < it.min }
            .map { "${it.label}: %.2f < ${it.min}".format(contrastRatio(it.fg, it.bg)) }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
        assertTrue(CONTRAST_PAIRS.all { it.min == 4.5 || it.min == 3.0 })
    }

    @Test fun tablesAreComplete() {
        for (r in m3Roles) { assertTrue("light $r", r in LIGHT_ROLES); assertTrue("dark $r", r in DARK_ROLES) }
        assertEquals(LIGHT_ROLES.keys, DARK_ROLES.keys)
        for (t in extTokens) { assertTrue("light $t", t in LIGHT_EXT); assertTrue("dark $t", t in DARK_EXT) }
        assertEquals(LIGHT_EXT.keys, DARK_EXT.keys)
        assertEquals(setOf("running", "success", "failed", "suspended", "cancelled", "errorRouted", "trigger", "data", "logic", "action", "ai"), SIGNAL.keys)
        // every colour opaque except the light card hairline (60 % outlineVariant) and the dark "no hairline"
        (LIGHT_ROLES + DARK_ROLES).forEach { (k, v) -> assertEquals(k, 0xFFL, v ushr 24) }
        (LIGHT_EXT + DARK_EXT + SIGNAL).filterKeys { it != "cardLine" }.forEach { (k, v) -> assertEquals(k, 0xFFL, v ushr 24) }
    }

    @Test fun signalsReadOnEverySurfaceTheyAreDrawnOn() {
        val bgs = listOf(LIGHT_ROLES.getValue("surface"), LIGHT_ROLES.getValue("surfaceContainerHigh"), DARK_ROLES.getValue("surface"), DARK_ROLES.getValue("surfaceContainerHigh"))
        for ((k, v) in SIGNAL) for (bg in bgs) assertTrue("$k on ${bg.toString(16)}", contrastRatio(v, bg) >= 3.0)
    }

    @Test fun contrastFormula() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF, 0xFF000000), 0.01)
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrastRatio(0xFF0B6B61, 0xFF0B6B61), 1e-9)
        assertEquals(6.38, contrastRatio(0xFFFFFFFF, 0xFF0B6B61), 0.01)       // DESIGN6 §2.1 onPrimary on primary (light)
        assertEquals(15.15, contrastRatio(0xFFE4ECF0, 0xFF0A1722), 0.01)      // onSurface on surface (dark)
    }
}
