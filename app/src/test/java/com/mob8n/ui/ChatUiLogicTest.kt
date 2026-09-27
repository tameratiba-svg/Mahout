package com.mob8n.ui

import com.mob8n.ai.Decision
import com.mob8n.ai.LiveTool
import com.mob8n.ai.LiveSegment
import com.mob8n.ai.LiveTurn
import com.mob8n.ai.PendingCall
import com.mob8n.ai.PermissionMode
import com.mob8n.ai.Risk
import com.mob8n.ai.Verdict
import com.mob8n.core.JSON
import com.mob8n.engine.ChatMessage
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 §9 ui-chat: existing helpers unchanged + the new pure chat helpers. */
class ChatUiLogicTest {
    private var seq = 0
    private fun row(role: String, content: String) = ChatMessage(id = (++seq).toLong(), conversationId = "c", seq = seq, role = role,
        json = JSON.parseToJsonElement("""{"role":"$role","content":$content}""") as JsonObject, text = "", createdAt = 0)
    private fun user(t: String) = row("user", """[{"type":"text","text":"$t"}]""")
    private fun assistant(t: String, vararg tools: String) = row("assistant", "[" + (listOf("""{"type":"text","text":"$t"}""") +
        tools.map { """{"type":"tool_use","id":"$it","name":"list_workflows","input":{}}""" }).joinToString(",") + "]")
    private fun results(vararg ids: String, text: String = "ok") = row("user", "[" + ids.joinToString(",") { """{"type":"tool_result","tool_use_id":"$it","content":"$text"}""" } + "]")

    @Test fun existingHelpersUnchanged() {
        val asked = Decision(Verdict.ASK, PermissionMode.ASK, Risk.CODING, "")
        assertEquals("coding · asks (ask)", cardChip(asked, null))
        assertEquals("coding · denied (ask)", cardChip(asked, "denied by user" to true))
        assertEquals("Decide the 2 pending calls above first", composerHint(2, 2))
        assertEquals("Message the operator", composerHint(0, 0))
        val (uses, decided) = parsePending("""[{"type":"tool_use","id":"a","name":"run_shell","input":{"cmd":"ls"},"_decided":true},{"id":"b","name":"x"}]""")
        assertEquals(listOf("a", "b"), uses.map { it.id }); assertEquals(mapOf("a" to true), decided)
        assertEquals(emptyList<Any>() to emptyMap<String, Boolean>(), parsePending("junk"))
        val rows = listOf(user("first"), assistant("x", "t1"), results("t1"), user("go"), assistant("a", "t2", "t3"), results("t2", "t3"), assistant("b", "t4"))
        assertEquals(3, toolCallsInLastTurn(rows))
    }

    @Test fun lastUserTextSkipsToolResultRows() {
        val rows = listOf(user("first"), assistant("x", "t1"), results("t1"), user("second"), assistant("y", "t2"), results("t2"))
        assertEquals("second", lastUserText(rows))
        assertNull(lastUserText(emptyList()))
        assertNull(lastUserText(listOf(assistant("only"))))
    }

    @Test fun turnStartsAndLiveHeader() {
        val u1 = user("q"); val a1 = assistant("a", "t1"); val r1 = results("t1"); val a2 = assistant("b"); val u2 = user("q2")
        assertEquals(setOf(a1.id), turnStarts(listOf(u1, a1, r1, a2)))
        assertTrue(assistantSinceLastUser(listOf(u1, a1)))
        assertFalse(assistantSinceLastUser(listOf(u1, a1, r1, a2, u2)))
    }

    @Test fun lastTurnDraft() {
        val rows = listOf(user("build"), assistant("", "d1"), results("d1", text = """{\"draftId\":\"dr-9\",\"name\":\"X\"}"""))
        assertEquals("dr-9", lastTurnDraftId(rows))
        assertNull(lastTurnDraftId(rows + user("next")))
    }

    @Test fun unreadCount() {
        assertEquals(0, unreadSince(null, 40))
        assertEquals(3, unreadSince(37, 40))
        assertEquals(0, unreadSince(45, 40))
    }

    @Test fun fontScaleRules() {
        assertTrue(subtitleVisible(1.0f)); assertTrue(subtitleVisible(1.5f)); assertFalse(subtitleVisible(2.0f))
        assertEquals(6, composerMaxLines(1.3f)); assertEquals(4, composerMaxLines(2.0f))
    }

    @Test fun modeChip() {
        assertEquals("Ask", modeChipText(PermissionMode.ASK, 0, 1_000))
        assertEquals("Bypass · 41m", modeChipText(PermissionMode.BYPASS, 1_000 + 40 * 60_000 + 1, 1_000))
        assertEquals("Bypass expired", modeChipText(PermissionMode.BYPASS, 500, 1_000))
        assertEquals(Tone.Danger, modeTone(PermissionMode.BYPASS)); assertEquals(Tone.Caution, modeTone(PermissionMode.AUTO))
        assertEquals(Tone.Info, modeTone(PermissionMode.ASK)); assertEquals(Tone.Neutral, modeTone(PermissionMode.PLAN))
    }

    @Test fun glyphs() {
        assertEquals(GlyphState.Pending, toolGlyph(null))
        assertEquals(GlyphState.Done, toolGlyph("ok" to false))
        assertEquals(GlyphState.Failed, toolGlyph("boom" to true))
        assertEquals(GlyphState.Denied, toolGlyph("denied by user" to true))
        fun lt(s: LiveTool.State, r: String? = null) = liveGlyph(LiveTool("i", "n", s, result = r))
        assertEquals(GlyphState.Pending, lt(LiveTool.State.FORMING)); assertEquals(GlyphState.Pending, lt(LiveTool.State.QUEUED))
        assertEquals(GlyphState.Running, lt(LiveTool.State.RUNNING)); assertEquals(GlyphState.Done, lt(LiveTool.State.DONE))
        assertEquals(GlyphState.Failed, lt(LiveTool.State.FAILED, "x")); assertEquals(GlyphState.Denied, lt(LiveTool.State.FAILED, "denied by user"))
    }

    @Test fun liveTextJoinsSegments() {
        assertEquals("", liveText(null))
        assertEquals("one\n\ntwo", liveText(LiveTurn(0, listOf(LiveSegment("one "), LiveSegment(""), LiveSegment("two")), true)))
    }

    @Test fun composerHelpers() {
        assertEquals(SendKind.STOP, sendKind(busy = true, hasText = true))
        assertEquals(SendKind.SEND, sendKind(busy = false, hasText = true))
        assertEquals(SendKind.MIC, sendKind(busy = false, hasText = false))
        assertEquals("hello world", appendSpoken("hello ", "world"))
        assertEquals("world", appendSpoken("", " world "))
        assertEquals(TextFieldValue("/abc", TextRange(1)), insertSlash(TextFieldValue("abc", TextRange(3))))
        assertEquals(TextFieldValue("hi @", TextRange(4)), insertAt(TextFieldValue("hi", TextRange(2))))
        assertEquals(TextFieldValue("hi @", TextRange(4)), insertAt(TextFieldValue("hi ", TextRange(3))))
    }

    @Test fun dockTexts() {
        fun call(id: String, name: String) = PendingCall(id, name, JsonObject(emptyMap()), Decision(Verdict.ASK, PermissionMode.ASK, Risk.CODING, ""))
        assertEquals("Wants to run run_shell", dockHeader(listOf(call("a", "run_shell")), emptyMap()))
        assertEquals("Wants to run 3 tools · 1 decided", dockHeader(listOf(call("a", "x"), call("b", "y"), call("c", "z")), mapOf("b" to false)))
        assertEquals("", previewLine("  \n"))
        assertEquals("ls -la", previewLine("\n  ls -la\nsecond"))
    }

    @Test fun dockShowsAOneLinePreviewOnce() {
        val p = "enable_workflow {\"id\":\"seed-8\"}"
        assertEquals("{\"id\":\"seed-8\"}", summaryLine(p, "enable_workflow"))                   // the name is already on the row above
        assertEquals(false, previewHasDetails(p))                                                   // no Details toggle repeating the same line
        assertEquals(true, previewHasDetails("Save new workflow \"x\"\nNodes (2):"))
        assertEquals(true, previewHasDetails("x".repeat(200)))
        assertEquals("Disable workflow w1", summaryLine("Disable workflow w1", "disable_workflow"))
        assertEquals("run_shell", summaryLine("run_shell", "run_shell"))                           // never blank
    }
}
