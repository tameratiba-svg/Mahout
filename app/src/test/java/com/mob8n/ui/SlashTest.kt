package com.mob8n.ui

import com.mob8n.ai.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 §9 ui-chat: slash parsing, suggestions and the Send-time resolution. */
class SlashTest {
    private val src = SlashSources(workflows = listOf("1a2b3c4d5e" to "Morning digest", "x" to "Evening summary", "y" to "morning backup"), skills = listOf("coding-on-device"), panels = listOf("Garage cam"))
    private fun sugg(text: String, cursor: Int = text.length) = parseSlash(text, cursor)?.let { slashSuggestions(it, src) }

    @Test fun slashAloneOffersAllSix() {
        assertEquals(listOf("/new", "/clear", "/mode", "/run", "/skill", "/panel"), sugg("/")!!.map { it.label })
    }

    @Test fun prefixFilters() {
        assertEquals(listOf("/mode"), sugg("/mo")!!.map { it.label })
        assertEquals("/mode ", sugg("/mo")!!.single().text)                  // takes an argument: completion leaves a space
        assertEquals("/new", sugg("/ne")!!.single().text)
    }

    @Test fun modeBypassNeedsConfirm() {
        val s = sugg("/mode b")!!
        assertEquals(listOf("bypass"), s.map { it.label })
        assertTrue(s.single().needsConfirm)
        assertEquals("/mode bypass", s.single().text)
        assertEquals(MODE_ARGS, sugg("/mode ")!!.map { it.label })
        assertTrue(sugg("/mode ")!!.filter { it.label != "bypass" }.none { it.needsConfirm })
    }

    @Test fun runFiltersWorkflowsCaseInsensitively() {
        assertEquals(listOf("Morning digest", "morning backup"), sugg("/run Morn")!!.map { it.label })
        assertEquals(listOf("Morning digest", "morning backup"), sugg("/run morn")!!.map { it.label })
        assertEquals(listOf("Evening summary"), sugg("/run summ")!!.map { it.label })
        assertEquals(listOf("coding-on-device"), sugg("/skill cod")!!.map { it.label })
        assertEquals(listOf("Garage cam"), sugg("/panel ga")!!.map { it.label })
    }

    @Test fun notACommand() {
        assertNull(parseSlash("/unknown text", 13))
        assertNull(parseSlash("/unknown", 8))
        assertNull(parseSlash("hello /mode", 11))
        assertNull(parseSlash("", 0))
        assertNull(parseSlash("/new now", 8))                                // /new takes no argument
    }

    @Test fun cursorOutsideTheCommandIsNull() {
        assertNull(parseSlash("/mode ask", 0))
        assertNull(parseSlash("/mode ask\nmore", 14))                        // second line
        assertNull(parseSlash("/mode ask", 3))                              // editing the command of a line with an argument
        assertEquals(SlashState.Arg(SlashCmd.MODE, "a"), parseSlash("/mode ask", 7))
        assertEquals(SlashState.Command("mo"), parseSlash("/mode", 3))
    }

    @Test fun resolveOnSend() {
        assertEquals(SlashAction.New, resolveSlash("/new"))
        assertEquals(SlashAction.Clear, resolveSlash(" /clear "))
        assertEquals(SlashAction.Mode(PermissionMode.BYPASS), resolveSlash("/mode bypass"))
        assertEquals(SlashAction.Mode(null), resolveSlash("/mode inherit"))
        assertEquals(SlashAction.Mode(PermissionMode.ASK), resolveSlash("/mode Ask"))
        assertTrue(resolveSlash("/mode sideways") is SlashAction.Incomplete)
        assertEquals(SlashAction.Run("Morning digest"), resolveSlash("/run Morning digest"))
        assertTrue(resolveSlash("/run") is SlashAction.Incomplete)
        assertEquals(SlashAction.Skill("coding-on-device"), resolveSlash("/skill coding-on-device"))
        assertEquals(SlashAction.Panel("Garage cam"), resolveSlash("/panel Garage cam"))
        // unknown / not a command -> null: the text is sent as a normal message, never swallowed
        assertNull(resolveSlash("/unknown text"))
        assertNull(resolveSlash("hello /mode ask"))
        assertNull(resolveSlash("/new chat please"))
        assertNull(resolveSlash("/run x\nsecond line"))
    }
}
