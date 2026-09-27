package com.mob8n.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 §6.1 / §9: pure navigation-kind table. */
class NavTransitionTest {
    @Test fun kinds() {
        assertEquals(NavKind.PUSH, navKind(Screen.List, Screen.Editor("w")))
        assertEquals(NavKind.POP, navKind(Screen.Editor("w"), Screen.List))
        assertEquals(NavKind.FADE_THROUGH, navKind(Screen.Dashboard, Screen.Skills))
        assertEquals(NavKind.PUSH, navKind(Screen.Chat(), Screen.Chat("c1")))
        assertEquals(NavKind.FADE_THROUGH, navKind(Screen.Chat("a"), Screen.Chat("b")))
        assertEquals(NavKind.NONE, navKind(Screen.Editor("w"), Screen.Editor("w")))
        assertEquals(NavKind.NONE, navKind(Screen.List, Screen.List))
        assertEquals(NavKind.PUSH, navKind(Screen.Runs("w"), Screen.RunDetail("r")))
        assertEquals(NavKind.POP, navKind(Screen.McpSettings, Screen.AiSettings))
        assertEquals(NavKind.PUSH, navKind(Screen.Editor("w"), Screen.Build("w")))
    }

    @Test fun contentKeyDistinguishesArguments() {
        assertEquals(Screen.Editor("w").contentKey, Screen.Editor("w").contentKey)
        assertTrue(Screen.Editor("w").contentKey != Screen.Editor("w", "n").contentKey)
        assertTrue(Screen.Chat().contentKey != Screen.Chat("a").contentKey)
    }

    @Test fun labels() {
        assertTrue(showNavLabels(1.0f))
        assertTrue(showNavLabels(1.3f))
        assertFalse(showNavLabels(2.0f))
    }
}
