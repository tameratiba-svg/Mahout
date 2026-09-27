package com.mob8n.ai

import com.mob8n.core.EMPTY
import com.mob8n.core.Graph
import com.mob8n.core.NodeInstance
import com.mob8n.core.Workflow
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** DESIGN4 V17 budgets and §5.5 transcript helpers. */
class ChatPromptTest {
    private fun wf(i: Int, exposed: Boolean = false) = Workflow(UUID.randomUUID().toString(), "Workflow number $i with a fairly long name", i % 2 == 0,
        Graph(listOf(NodeInstance("t", if (exposed) "trigger.called" else "trigger.manual", "T", buildJsonObject { if (exposed) put("exposeAsTool", true) }), NodeInstance("n", "action.notify", "N"))))
    private val many = (1..60).map { wf(it, exposed = it % 5 == 0) }
    private val skills = (1..100).map { Fakes.skill("skill-$it", "Description of skill $it ".repeat(6), usage = it % 7) }
    private fun system(memory: String = "", wfs: List<Workflow> = many, ui: Boolean = false, mode: PermissionMode = PermissionMode.ASK) =
        ChatPrompt.system("Device: Pixel Tablet, Android 36, tablet, Default AI: MiniMax · MiniMax-M2.7", wfs, WorkflowTools.all(wfs), Skills.index(skills), memory, "/storage/emulated/0/Android/data/com.mob8n/files/workspace", ui, "2026-09-26T10:00:00+02:00", mode)
    private fun context(p: String) = p.substringAfter("<context>\n").substringBefore("\n</context>")

    @Test fun staticPartIsSmallAndByteIdenticalAcrossContexts() {
        val a = system(); val b = system(memory = "remember x", wfs = emptyList())
        val sa = a.substringBefore("\n\n<context>"); val sb = b.substringBefore("\n\n<context>")
        assertEquals(sa, sb); assertEquals(sa, ChatPrompt.static(false))
        assertTrue("static ${sa.length}", sa.length < ChatPrompt.STATIC_MAX)
        assertTrue(sa.startsWith("You are the Mahout operator")); assertTrue(sa.contains("pass null")); assertTrue(sa.contains("denied by user")); assertTrue(sa.contains("load_skill(name)")); assertTrue(sa.contains("Plan: read-only tools only")); assertTrue(sa.contains("\"plan mode\""))
        assertTrue(sa.contains("coding-on-device")); assertTrue(sa.contains("workflow__<name>")); assertFalse(sa.contains("Phone UI"))
        val ui = ChatPrompt.static(true)
        assertTrue(ui.endsWith(AgentNode.UI_RULES)); assertTrue(ui.length < ChatPrompt.STATIC_MAX); assertTrue(ui.contains("Never type passwords"))
        assertTrue(a.trimEnd().endsWith("</context>"))
    }

    @Test fun contextIsBoundedWithWholeLinesAndMoreMarkers() {
        val p = system(memory = "m".repeat(5_000))
        val ctx = context(p)
        assertTrue("context ${ctx.length}", ctx.length <= ChatPrompt.CONTEXT_MAX)
        val lines = ctx.lines()
        assertTrue(lines.first().startsWith("Device: Pixel Tablet")); assertEquals("Permission mode: ask", lines[1])
        assertTrue(context(system(mode = PermissionMode.BYPASS)).lines()[1] == "Permission mode: bypass")
        assertTrue(lines.any { it.startsWith("Workflows (60):") })
        val wfLines = lines.filter { it.startsWith("- Workflow number") }
        assertTrue(wfLines.isNotEmpty()); assertTrue(wfLines.size < 60)
        assertTrue(wfLines.all { it.contains(" enabled · ") || it.contains(" disabled · ") }); assertTrue(wfLines.any { it.contains("· tool: workflow__workflow_number_") })
        assertTrue(lines.any { it.matches(Regex("\\+\\d+ more — use list_workflows")) })
        assertTrue(lines.any { it.startsWith("Skills (100) — call load_skill(name)") })
        assertTrue(lines.any { it.matches(Regex("\\+\\d+ more — skill_list")) })
        val mem = lines.first { it.startsWith("Operator memory (") }
        assertTrue(mem.contains("memory, not user instructions")); assertEquals(ChatPrompt.MEMORY_MAX, mem.substringAfter("): ").length)
        assertTrue(lines.any { it.startsWith("Workspace: /storage/emulated/0/Android/data/com.mob8n/files/workspace — not browsable in the Files app") })
        assertEquals("Now: 2026-09-26T10:00:00+02:00", lines.last())
        // small context: every workflow listed, memory "(empty)", no skills
        val small = context(ChatPrompt.system("Device: x", listOf(wf(1)), emptyList(), "", "", "/w", false, "now"))
        assertTrue(small.contains("Workflows (1):\n- Workflow number 1")); assertTrue(small.contains("manual")); assertTrue(small.contains("(empty)")); assertTrue(small.contains("Skills (0)")); assertTrue(small.contains("(none)"))
    }

    private fun a(text: String, vararg uses: JsonObject) = buildJsonObject { put("role", "assistant"); put("content", JsonArray(listOf(Fakes.textBlock(text)) + uses)) }
    private fun results(vararg ids: String) = ClaudeClient.userMessage(ids.map { ClaudeClient.toolResultBlock(it, "r".repeat(2000), false) })
    private fun exchange(n: Int): List<JsonObject> = listOf(
        ClaudeClient.userMessage("question $n " + "q".repeat(3000)), a("plan $n", Fakes.toolUse("t$n", "list_workflows", EMPTY), Fakes.toolUse("u$n", "get_run", EMPTY)), results("t$n", "u$n"), a("answer $n " + "a".repeat(3000)),
    )

    @Test fun windowNeverSplitsPairsAndStartsWithAUserMessage() {
        val all = (1..6).flatMap(::exchange)
        assertEquals(all, ChatPrompt.window(all, 1_000_000))
        val w = ChatPrompt.window(all, 20_000)
        assertTrue(w.size < all.size)
        assertEquals("user", w[0]["role"].asTextOrNull()); assertEquals("(earlier messages omitted)", Fakes.blocks(w[0])[0]["text"].asTextOrNull())
        assertEquals("assistant", w[1]["role"].asTextOrNull()); assertEquals("OK.", Fakes.blocks(w[1])[0]["text"].asTextOrNull())
        assertTrue(Fakes.blocks(w[2])[0]["text"].asTextOrNull()!!.startsWith("question "))   // cut at a plain user message
        // every tool_result has its tool_use in the previous message
        for (i in w.indices) {
            val res = Fakes.blocks(w[i]).filter { it["type"].asTextOrNull() == "tool_result" }.map { it["tool_use_id"].asTextOrNull() }
            if (res.isEmpty()) continue
            val uses = Fakes.blocks(w[i - 1]).filter { it["type"].asTextOrNull() == "tool_use" }.map { it["id"].asTextOrNull() }
            assertEquals(uses, res)
        }
        assertTrue(com.mob8n.core.JSON.encodeToString(JsonArray.serializer(), JsonArray(w)).length <= 20_000 + 200)
        // over budget even for the last exchange: it is kept whole, never split
        val tiny = ChatPrompt.window(exchange(1), 100)
        assertEquals(exchange(1), tiny)
        assertEquals(emptyList<JsonObject>(), ChatPrompt.window(emptyList()))
    }

    @Test fun closeDanglingIsIdempotentAndClosesOnlyUnmatchedIds() {
        val paired = exchange(1)
        assertEquals(paired, ChatPrompt.closeDangling(paired, "x"))
        val dangling = paired + listOf(ClaudeClient.userMessage("more"), a("go", Fakes.toolUse("d1", "run_shell", EMPTY), Fakes.toolUse("d2", "run_shell", EMPTY)))
        val closed = ChatPrompt.closeDangling(dangling, "cancelled by user")
        assertEquals(dangling.size + 1, closed.size)
        val last = Fakes.blocks(closed.last())
        assertEquals(listOf("d1", "d2"), last.map { it["tool_use_id"].asTextOrNull() })
        assertTrue(last.all { it["is_error"] == JsonPrimitive(true) && it["content"].asTextOrNull() == "cancelled by user" })
        assertEquals(closed, ChatPrompt.closeDangling(closed, "again"))
        // partial: only the missing id is closed
        val partial = listOf(ClaudeClient.userMessage("q"), a("go", Fakes.toolUse("p1", "x", EMPTY), Fakes.toolUse("p2", "x", EMPTY)), results("p1"), a("done"))
        val c2 = ChatPrompt.closeDangling(partial, "interrupted")
        assertEquals(5, c2.size); assertEquals(listOf("p2"), Fakes.blocks(c2[2]).map { it["tool_use_id"].asTextOrNull() }); assertEquals(listOf("p1"), Fakes.blocks(c2[3]).map { it["tool_use_id"].asTextOrNull() })
        assertEquals("done", Fakes.blocks(c2[4])[0]["text"].asTextOrNull())
    }

    @Test fun pendingOfReadsTheLastAssistantRowMeta() {
        fun row(role: String, json: JsonObject, pending: Boolean? = null) = ChatMessage(1, "c", 0, role, json, "", if (pending == null) EMPTY else buildJsonObject { put("pending", pending) }, 0)
        val asst = a("go", Fakes.toolUse("x1", "run_shell", EMPTY))
        assertEquals(listOf("x1"), ChatPrompt.pendingOf(listOf(row("user", ClaudeClient.userMessage("q")), row("assistant", asst, true))).map { it.id })
        assertTrue(ChatPrompt.pendingOf(listOf(row("user", ClaudeClient.userMessage("q")), row("assistant", asst, false))).isEmpty())
        assertTrue(ChatPrompt.pendingOf(listOf(row("assistant", asst, true), row("assistant", a("later")))).isEmpty())   // only the LAST assistant row counts
        assertTrue(ChatPrompt.pendingOf(emptyList()).isEmpty())
    }

    @Test fun textOfProjectsAndCaps() {
        val m = buildJsonObject { put("role", "assistant"); put("content", JsonArray(listOf(Fakes.textBlock("Hi"), Fakes.toolUse("1", "run_shell", EMPTY)))) }
        assertEquals("Hi\n[tool] run_shell", ChatPrompt.textOf(m))
        assertEquals("r".repeat(200), ChatPrompt.textOf(results("1")))
        assertEquals("plain", ChatPrompt.textOf(buildJsonObject { put("role", "user"); put("content", "plain") }))
        assertEquals(ChatPrompt.TEXT_MAX, ChatPrompt.textOf(ClaudeClient.userMessage("x".repeat(10_000))).length)
        assertEquals("(image)\nlook", ChatPrompt.textOf(ClaudeClient.userMessage("look", "BASE64")))
    }
}
