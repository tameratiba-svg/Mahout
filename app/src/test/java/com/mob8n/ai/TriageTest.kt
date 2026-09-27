package com.mob8n.ai

import com.mob8n.core.NodeException
import com.mob8n.core.item
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN5 §3.3 / §6.1: pure spec/passes/annotate + the filter core with a fake decider. */
class TriageTest {
    private val urgentRow = buildJsonObject { put("name", "urgent"); put("type", "noul"); put("instructions", "Is this message urgent enough to interrupt the user?"); put("threshold", 0.7) }
    private val levelRow = buildJsonObject { put("name", "level"); put("type", "score"); put("instructions", "How urgent?"); put("criteria", "low, medium, high"); put("threshold", 1) }
    private fun params(engine: String? = "default", onError: String? = null, vararg rows: JsonObject) = buildJsonObject {
        engine?.let { put(Triage.ENGINE, it) }
        put(Triage.QUESTIONS, JsonArray(rows.toList()))
        onError?.let { put(Triage.ON_ERROR, it) }
    }
    private val laya = S1Target("laya", "http://10.0.0.61:8000/v1/systemone", null, null)

    private fun result(p: Double, level: Int = 2): S1Result = S1Result("laya", "laya-rl-agent", mapOf(
        "urgent" to S1Answer.Noul("urgent", p, p >= 0.5, 0.9),
        "level" to S1Answer.Score("level", level.toDouble(), level, "x", emptyMap(), null),
    ), TokenUsage(10, 0), 64, null, JsonObject(emptyMap()))

    @Test fun specNullWhenOffOrNoRowsAndRejectsChoice() {
        assertNull(Triage.spec(params(engine = null, rows = arrayOf(urgentRow))))
        assertNull(Triage.spec(params(engine = "off", rows = arrayOf(urgentRow))))
        assertNull(Triage.spec(params(engine = "laya")))
        val s = Triage.spec(params("laya", "drop", urgentRow, levelRow))!!
        assertEquals("laya", s.engine); assertEquals("drop", s.onError); assertEquals(mapOf("urgent" to 0.7, "level" to 1.0), s.thresholds)
        assertTrue(s.questions[0] is S1Question.Noul); assertTrue(s.questions[1] is S1Question.Score)
        assertEquals("run", Triage.spec(params("jev", null, urgentRow))!!.onError)
        val choice = buildJsonObject { put("name", "c"); put("type", "choice"); put("instructions", "x"); put("criteria", "a\nb") }
        try { Triage.spec(params("laya", null, choice)); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("noul or score")) }
        // a row without a type defaults to noul (the TriageParams column default); a missing threshold is 0.7
        val bare = buildJsonObject { put("name", "u"); put("instructions", "x") }
        val b = Triage.spec(params("laya", null, bare))!!
        assertTrue(b.questions.single() is S1Question.Noul); assertEquals(0.7, b.thresholds["u"]!!, 1e-9)
    }

    @Test fun passesIsAndOverRows() {
        val s = Triage.spec(params("laya", null, urgentRow, levelRow))!!
        assertTrue(Triage.passes(s, result(0.7, 1).answers))
        assertFalse(Triage.passes(s, result(0.69, 2).answers))
        assertFalse(Triage.passes(s, result(0.9, 0).answers))
        assertFalse(Triage.passes(s, mapOf("urgent" to S1Answer.Noul("urgent", 0.9, true, null))))   // missing answer fails its row
    }

    @Test fun annotateShape() {
        val t = Triage.annotate(item("text" to "hi"), result(0.83))["triage"] as JsonObject
        assertEquals(0.83, (t["urgent"] as JsonPrimitive).content.toDouble(), 1e-9); assertEquals(0.9, (t["urgent_confidence"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(JsonPrimitive(2), t["level"]); assertNull(t["level_confidence"])
        assertEquals(JsonPrimitive("laya"), t["engine"]); assertEquals(JsonPrimitive(64L), t["latencyMs"])
    }

    private class FakeDecider(val p: (JsonElement) -> Double?) {
        val sources = ArrayList<String>(); val refs = ArrayList<String?>()
        val fn: suspend (S1Target, JsonElement, List<S1Question>, String, String?) -> S1Result = { _, state, _, source, ref ->
            sources += source; refs += ref
            val v = p(state) ?: throw NodeException("Cannot reach Laya at 10.0.0.61:8000")
            S1Result("laya", "laya-rl-agent", mapOf("urgent" to S1Answer.Noul("urgent", v, v >= 0.5, null)), TokenUsage(5, 0), 50, null, JsonObject(emptyMap()))
        }
    }

    @Test fun filterKeepsAndDropsPerItemWithTriageSource() = runBlocking {
        val d = FakeDecider { s -> if ((s as JsonObject)["text"].toString().contains("NOW")) 0.95 else 0.1 }
        val items = listOf(item("text" to "lunch?"), item("text" to "call me NOW"))
        val kept = Triage.filter(params("default", null, urgentRow), items, "wf-1", "wf/Trigger", { laya }, d.fn) {}
        assertEquals(1, kept.size); assertEquals(JsonPrimitive("call me NOW"), kept[0]["text"])
        assertEquals(0.95, ((kept[0]["triage"] as JsonObject)["urgent"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(listOf("triage", "triage"), d.sources); assertEquals(listOf("wf-1", "wf-1"), d.refs)
        val none = Triage.filter(params("default", null, urgentRow), listOf(items[0]), "wf-1", "wf/Trigger", { laya }, d.fn) {}
        assertTrue(none.isEmpty())
    }

    @Test fun onErrorRunFailsOpenAndDropFailsClosed() = runBlocking {
        val down = FakeDecider { null }
        val items = listOf(item("text" to "a"))
        val logs = ArrayList<String>()
        assertEquals(items, Triage.filter(params("laya", "run", urgentRow), items, "wf", "w", { laya }, down.fn) { logs += it })
        assertTrue(logs.any { it.contains("Cannot reach Laya") && it.contains("fail open") })
        assertTrue(Triage.filter(params("laya", "drop", urgentRow), items, "wf", "w", { laya }, down.fn) {}.isEmpty())
        // unconfigured engine (ERR_* from target) follows onError too, and never calls the decider
        val never = FakeDecider { fail("must not call"); null }
        val unconf: (String) -> S1Target = { throw NodeException(SystemOne.ERR_NOT_CONFIGURED) }
        assertEquals(items, Triage.filter(params("default", null, urgentRow), items, "wf", "w", unconf, never.fn) {})
        assertTrue(Triage.filter(params("default", "drop", urgentRow), items, "wf", "w", unconf, never.fn) {}.isEmpty())
        // malformed rows follow onError as well (never throws)
        val bad = buildJsonObject { put("name", "c"); put("type", "choice"); put("instructions", "x") }
        assertEquals(items, Triage.filter(params("laya", "run", bad), items, "wf", "w", { laya }, never.fn) {})
        // off -> untouched, no call
        assertEquals(items, Triage.filter(params("off", "drop", urgentRow), items, "wf", "w", { laya }, never.fn) {})
    }

    // ---- v5 device phase: F3 triageState, F4 one "dropped" line ----
    @Test fun stateIsTheTemplatedMessageTextWithJsonFallback() {
        val notif = item("packageName" to "com.whatsapp", "appName" to "WhatsApp", "title" to "Mum", "text" to "call me NOW, emergency", "key" to "0|x", "bigText" to null, "postTime" to 1L)
        assertEquals(JsonPrimitive("WhatsApp: Mum — call me NOW, emergency"), Triage.stateFor("{{appName}}: {{title}} — {{text}}", notif, 0, 1, "wf"))
        assertEquals(JsonPrimitive("https://x.org/a"), Triage.stateFor("{{subject}} {{text}} {{url}}", item("url" to "https://x.org/a"), 0, 1, "wf"))   // nulls render empty, trimmed
        assertEquals(notif, Triage.stateFor("", notif, 0, 1, "wf"))        // blank template -> whole item JSON (v5.0 behaviour)
        assertEquals(notif, Triage.stateFor(null, notif, 0, 1, "wf"))
        assertEquals(notif, Triage.stateFor("{{nope}}", notif, 0, 1, "wf")) // blank result -> whole item JSON
        // the shipped default reads bigText (BigTextStyle body) too; an all-null item renders ": —" -> whole item JSON, not punctuation
        val mail = item("appName" to "Gmail", "title" to "Mum", "text" to "Re: tomorrow", "bigText" to "call me NOW, emergency")
        assertEquals(JsonPrimitive("Gmail: Mum — Re: tomorrow call me NOW, emergency"), Triage.stateFor(com.mob8n.triggers.TriageParams.NOTIFICATION_STATE, mail, 0, 1, "wf"))
        assertEquals(JsonPrimitive("WhatsApp: Mum — call me NOW, emergency"), Triage.stateFor(com.mob8n.triggers.TriageParams.NOTIFICATION_STATE, notif, 0, 1, "wf"))
        val empty = item("packageName" to "com.x", "appName" to null, "title" to null, "text" to null, "bigText" to null)
        assertEquals(empty, Triage.stateFor(com.mob8n.triggers.TriageParams.NOTIFICATION_STATE, empty, 0, 1, "wf"))
    }

    @Test fun filterSendsTheTemplateAndDoesNotLogDropped() = runBlocking {
        val states = ArrayList<JsonElement>()
        val d = FakeDecider { s -> states += s; 0.1 }
        val items = listOf(item("title" to "Mum", "text" to "lunch?"))
        val logs = ArrayList<String>()
        // absent param -> the trigger spec default; explicit param wins; explicit blank -> item JSON
        assertTrue(Triage.filter(params("laya", null, urgentRow), items, "wf", "w", { laya }, d.fn, defaultState = "{{title}}: {{text}}", log = { logs += it }).isEmpty())
        val withParam = JsonObject(params("laya", null, urgentRow) + (Triage.STATE to JsonPrimitive("{{text}}")))
        Triage.filter(withParam, items, "wf", "w", { laya }, d.fn, defaultState = "{{title}}: {{text}}", log = { logs += it })
        val blank = JsonObject(params("laya", null, urgentRow) + (Triage.STATE to JsonPrimitive("")))
        Triage.filter(blank, items, "wf", "w", { laya }, d.fn, defaultState = "{{title}}: {{text}}", log = { logs += it })
        assertEquals(listOf(JsonPrimitive("Mum: lunch?"), JsonPrimitive("lunch?"), items[0]), states)
        assertTrue("TriageHook logs the drop, not Triage (F4)", logs.none { it.contains("dropped") })
    }
}
