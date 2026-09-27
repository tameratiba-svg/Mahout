package com.mob8n.ui

import com.mob8n.ai.ChatSettings
import com.mob8n.ai.Decision
import com.mob8n.ai.PermissionMode
import com.mob8n.ai.Permissions
import com.mob8n.ai.Risk
import com.mob8n.ai.Verdict
import com.mob8n.engine.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4P §8 ui: banner countdown text, per-scope Bypass activity, mode labels, dialog scope lines, card chips, kill-switch line. Pure JVM. */
class BypassUiTest {
    private val now = 1_700_000_000_000L
    private fun conv(s: ChatSettings) = Conversation("c-${s.hashCode()}", "t", now, now, s.json(), null, "idle", null)

    @Test fun bannerTextRoundsUpAndNeverShowsZero() {
        assertEquals("Bypass mode — nothing asks — expires in 58 min — Stop", bypassBannerText(now + (57.2 * 60_000).toLong(), now))
        assertEquals("Bypass mode — nothing asks — expires in 1 min — Stop", bypassBannerText(now + 30_000, now))
        assertEquals("Bypass mode — nothing asks — expires in 60 min — Stop", bypassBannerText(now + Permissions.BYPASS_TTL_MS, now))
        assertEquals(1L, bypassMinutesLeft(now - 5, now))                                     // already past: still "1", the caller hides the banner
    }

    @Test fun activeUntilIsPerScopeAndIgnoresExpiredOrNonBypassRows() {
        val armed = conv(ChatSettings(mode = PermissionMode.BYPASS, bypassUntil = now + 5 * 60_000))
        assertEquals(now + 5 * 60_000, bypassActiveUntil(0, listOf(armed), now))              // a conversation Bypass needs no global timestamp
        assertEquals(0L, bypassActiveUntil(0, listOf(conv(ChatSettings(mode = PermissionMode.BYPASS, bypassUntil = now))), now))   // expired -> 0
        assertEquals(0L, bypassActiveUntil(0, emptyList(), now))
        assertEquals(now + 9 * 60_000, bypassActiveUntil(now + 9 * 60_000, listOf(armed), now))   // global wins when later
        assertEquals(now + 5 * 60_000, bypassActiveUntil(now + 1, listOf(armed), now))          // conversation wins when later
        assertEquals(0L, bypassActiveUntil(0, listOf(conv(ChatSettings(mode = PermissionMode.ASK, bypassUntil = now + 60_000))), now))   // stale until on an ASK row is ignored
        assertEquals(1, conversationsInBypass(listOf(armed, conv(ChatSettings(mode = PermissionMode.ASK))), now))
        assertEquals(0, conversationsInBypass(listOf(conv(ChatSettings(mode = PermissionMode.BYPASS, bypassUntil = now - 1))), now))
    }

    @Test fun labelsHelpAndStatus() {
        assertEquals("Inherit", modeLabel(null))
        assertEquals(listOf("Plan", "Ask", "Auto", "Bypass"), PermissionMode.entries.map { modeLabel(it) })
        for (m in PermissionMode.entries) assertTrue(m.name, modeHelp(m).startsWith(modeLabel(m) + " —"))
        assertTrue(modeHelp(PermissionMode.AUTO).contains("whatever it contains"))            // P18b wording
        assertTrue(modeHelp(PermissionMode.BYPASS).contains("whatever it contains"))
        assertEquals("Ask", modeStatus(PermissionMode.ASK, 0, now))
        assertEquals("Inherit", modeStatus(null, 0, now))
        assertEquals("Bypass — 41 min left", modeStatus(PermissionMode.BYPASS, now + 41 * 60_000, now))
        assertEquals("Bypass (expired) — behaves as Ask", modeStatus(PermissionMode.BYPASS, now, now))
        assertEquals("Stopped 2 chats, 1 run; Bypass off", stoppedLine(2, 1))
        assertEquals("Stopped 1 chat, 0 runs; Bypass off", stoppedLine(1, 0))
    }

    @Test fun dialogBodyNamesTheScopeTruthfully() {
        val g = bypassDialogBody(BypassScope.GLOBAL); val c = bypassDialogBody(BypassScope.CONVERSATION)
        assertTrue(g.startsWith("Every chat and every ai.agent workflow that inherits the global mode stops asking for the next 60 minutes."))
        assertTrue(c.startsWith("This chat stops asking for the next 60 minutes. Other chats, agents and workflows keep their own mode."))
        for (b in listOf(g, c)) {
            assertTrue(b.contains("resume_run")); assertTrue(b.contains("DELETE workflows")); assertTrue(b.contains("UI automation")); assertTrue(b.contains("Stop everything"))
        }
        assertFalse(c.contains("Every chat"))
    }

    @Test fun toolCardChipFreezesRiskVerdictAndMode() {
        val asked = Decision(Verdict.ASK, PermissionMode.ASK, Risk.CODING, "asks in ask")
        assertEquals("coding · asks (ask)", cardChip(asked, null))                                  // still pending
        assertEquals("coding · approved (ask)", cardChip(asked, "ok" to false))
        assertEquals("coding · approved (ask)", cardChip(asked, "boom" to true))                    // approved, then the tool failed
        assertEquals("coding · denied (ask)", cardChip(asked, "denied by user" to true))
        assertEquals("safe action · ran (auto)", cardChip(Decision(Verdict.RUN, PermissionMode.AUTO, Risk.WRITE, ""), "ok" to false))
        assertEquals("destructive · blocked (plan)", cardChip(Decision(Verdict.BLOCK, PermissionMode.PLAN, Risk.ALWAYS, ""), "plan mode: x" to true))
        assertEquals("Decide the 2 pending calls above first", composerHint(2, 2))
        assertEquals("Decide the 1 pending call above first", composerHint(1, 3))
        assertEquals("Waiting for the assistant…", composerHint(0, 3))
        assertEquals("Message the operator", composerHint(0, 0))
    }

    @Test fun parsePendingStripsStoredDecisions() {
        val (uses, decided) = parsePending("""[{"type":"tool_use","id":"s1","name":"run_shell","input":{"command":"ls"},"_decided":true},{"type":"tool_use","id":"s2","name":"workspace_write","input":{}}]""")
        assertEquals(listOf("s1", "s2"), uses.map { it.id })
        assertEquals("ls", uses[0].input["command"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(mapOf("s1" to true), decided)
        assertEquals(emptyList<Any>() to emptyMap<String, Boolean>(), parsePending(null))
        assertEquals(emptyList<Any>() to emptyMap<String, Boolean>(), parsePending("garbage"))
    }
}
