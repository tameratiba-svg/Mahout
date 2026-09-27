package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.Edge
import com.mob8n.core.Graph
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.asTextOrNull
import com.mob8n.core.item
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BuilderTest {
    private val manual = NodeSpec("trigger.manual", "Manual", NodeKind.TRIGGER, "Run button", inputs = emptyList())
    private val catalog = Catalog(listOf(listOf(Fakes.FakeNode(manual), Fakes.FakeNode(Fakes.http), Fakes.FakeNode(Fakes.notify), Fakes.FakeNode(Fakes.datetime), Fakes.FakeNode(Fakes.variable), Fakes.FakeNode(Fakes.iff))))
    private fun fixture(name: String): String = javaClass.getResourceAsStream("/ai/$name")!!.bufferedReader().readText()
    private fun parsed(name: String) = Builder.parse(fixture(name), catalog).getOrThrow()
    private fun errorsOf(name: String) = parsed(name).let { Builder.validate(it.second, catalog) }

    @Test fun compactCatalogHasOneLinePerNodeWithParamsPortsAndMarkers() {
        val lines = Builder.compactCatalog(catalog).lines()
        assertEquals(catalog.nodes.size, lines.size)
        for (n in catalog.nodes) assertEquals(1, lines.count { it.startsWith(n.spec.id + "|") })
        val http = lines.first { it.startsWith("data.http|") }
        assertTrue(http, http.startsWith("data.http|D|HTTP Request|Calls a URL and returns the response|url:s*,method:e=GET{GET,POST},body:s,headers:R[name:s*,value:s],timeout:n=30,tags:L,json:b=true,delay:ms=0|in:main|out:main"))
        assertFalse(http.contains("authSecret"))                                           // SECRET omitted
        assertTrue(http.endsWith("|fields:status,headers,body"))
        assertTrue(lines.first { it.startsWith("trigger.manual|") }.contains("|in:-|out:main|fields:at"))
        assertTrue(lines.first { it.startsWith("logic.if|") }.endsWith("|in:main|out:true,false"))
        val rules = Builder.catalogLine(ClassifyNode.spec)
        assertTrue(rules, rules.contains("labels:L*^") && rules.contains("|out:<labels>,other") && rules.contains("provider:e=default{default,claude,openai,"))
        assertTrue(rules.contains("effort:e=low{low,medium,high,xhigh,max}"))
        assertTrue(Builder.catalogLine(AskAiNode.spec).startsWith("ai.ask|I|Ask AI|Sends a prompt to the configured AI provider and adds the answer to the item|provider:e=default{"))
        assertTrue(Builder.catalogLine(AskAiNode.spec).contains("prompt:s*={{text}}"))
    }

    @Test fun systemPromptHasSkeletonFewShotAndCatalog() {
        val p = Builder.systemPrompt(catalog)
        assertTrue(p.startsWith("You design workflows for Mahout"))
        assertTrue(p.contains("Rules:\n1. Exactly one trigger node (kind T)"))
        assertTrue(p.contains("10. Prefer fewer nodes. Omit x and y. Return the complete workflow every time."))
        assertTrue(p.contains("11. ai.agent: never set askApproval=false or permissionMode other than \"inherit\""))
        assertTrue(p.contains(Builder.FEW_SHOT_JSON))
        assertTrue(p.contains("Recipes for app.action (recipe id: params):\n(app.action is not available in this build)"))
        assertTrue(p.contains("Catalog (id|kind|name|description|params|in|out[|LIST][|opt]):\n" + Builder.compactCatalog(catalog)))
        assertTrue(p.contains("{{\$node.Name.field}}"))
        // the few-shot is parseable JSON in the documented shape (validation against the REAL catalog lives in BuilderPromptTest)
        val fs = Llm.parseJsonObject(Builder.FEW_SHOT_JSON)!!
        assertEquals(6, (fs["nodes"] as JsonArray).size); assertEquals(5, (fs["edges"] as JsonArray).size)
        assertTrue(Builder.estimateTokens(p) > 100)
    }

    @Test fun promptsForBuildRefineAndRepair() {
        assertEquals("Create a workflow: notify me", Builder.userPrompt("notify me"))
        val g = parsed("builder_ok.json").second
        val refine = Builder.userPrompt("", g, "My flow", "add a toast")
        assertTrue(refine.startsWith("Current workflow (JSON):\n" + Builder.graphJson("My flow", g)))
        assertTrue(refine.endsWith("\n\nChange it as follows: add a toast\nReturn the complete updated workflow."))
        val gj = Llm.parseJsonObject(Builder.graphJson("My flow", g))!!
        assertEquals("n1", ((gj["nodes"] as JsonArray)[0] as JsonObject)["id"].asTextOrNull())              // short ids, no x/y
        assertFalse(((gj["nodes"] as JsonArray)[0] as JsonObject).containsKey("x"))
        assertEquals("n1", ((gj["edges"] as JsonArray)[0] as JsonObject)["from"].asTextOrNull())
        assertEquals("Your workflow failed validation:\n- a\n- b\nReturn the complete corrected workflow JSON.", Builder.repairPrompt(listOf("a", "b")))
    }

    @Test fun parseOkFencedAndWrapperProduceValidGraphsWithFreshIds() {
        for (f in listOf("builder_ok.json", "builder_fenced.txt", "builder_wrapper.json")) {
            val (name, g) = parsed(f)
            assertEquals(f, 3, g.nodes.size); assertEquals(2, g.edges.size)
            assertTrue(g.nodes.none { it.id == "n1" }); assertEquals(36, g.nodes[0].id.length)   // UUIDs
            assertEquals(setOf(g.nodes[0].id, g.nodes[1].id), g.edges.map { it.from }.toSet())
            assertEquals(emptyList<String>(), Builder.validate(g, catalog))
            if (f == "builder_wrapper.json") assertEquals("Wrapped", name) else assertEquals("Fetch and notify", name)
        }
        assertTrue(Builder.parse("no json here", catalog).isFailure)
        assertEquals(Builder.ERR_NO_JSON, Builder.parse("no json here", catalog).exceptionOrNull()!!.message)
        assertTrue(Builder.parse("{\"name\":\"x\"}", catalog).isFailure)
        assertEquals("duplicate node id n1", Builder.parse("{\"nodes\":[{\"id\":\"n1\",\"type\":\"trigger.manual\"},{\"id\":\"n1\",\"type\":\"action.notify\"}],\"edges\":[]}", catalog).exceptionOrNull()!!.message)
        assertEquals("Generated workflow", Builder.parse("{\"nodes\":[{\"type\":\"trigger.manual\"}],\"edges\":[]}", catalog).getOrThrow().first)
    }

    @Test fun badPortUnknownTypeNoTriggerAreReported() {
        val bad = errorsOf("builder_bad_port.json")
        assertTrue(bad.toString(), bad.any { it == "Check: no output port 'yes'" })
        assertTrue(bad.toString(), bad.any { it == "Orphan is not reachable from a trigger" })
        assertFalse(bad.any { it.contains("Notify is not reachable") })                    // reachable through the (bad) port edge
        val unknown = errorsOf("builder_unknown_type.json")
        assertTrue(unknown.toString(), unknown.any { it.startsWith("Ping: unknown node type action.notification; closest: ") && it.contains("action.notify") })
        val none = errorsOf("builder_no_trigger.json")
        assertTrue(none.toString(), none.contains("Add exactly one trigger node (kind T)"))
        assertFalse(none.any { it.contains("Workflow needs at least one trigger") })
    }

    @Test fun duplicateNamesAreRenamedAndDotsRemoved() {
        val g = parsed("builder_dup_names.json").second
        assertEquals(listOf("Run", "Notify", "Notify 2", "Notify 3"), g.nodes.map { it.name })
        assertEquals(emptyList<String>(), Builder.validate(g, catalog))
        val dotted = Builder.parse("{\"nodes\":[{\"id\":\"a\",\"type\":\"trigger.manual\",\"name\":\"v1.2 run\"}],\"edges\":[]}", catalog).getOrThrow().second
        assertEquals("v1 2 run", dotted.nodes[0].name)
    }

    @Test fun stringlyTypedParamsAreCoercedUnknownDroppedNullDropped() {
        val g = parsed("builder_stringly_typed.json").second
        val p = g.nodes[1].params
        assertEquals(JsonPrimitive(12), p["timeout"]); assertEquals(JsonPrimitive(true), p["json"]); assertEquals(JsonPrimitive(250), p["delay"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), p["tags"])
        assertFalse(p.containsKey("bogus")); assertFalse(p.containsKey("body"))
        assertEquals("main", g.edges[0].fromPort); assertEquals("main", g.edges[0].toPort)     // blank ports -> main
        assertEquals(emptyList<String>(), Builder.validate(g, catalog))
        val warn = Builder.parseDetailed(fixture("builder_stringly_typed.json"), catalog).getOrThrow().warnings
        assertEquals(listOf("Fetch: ignored unknown param bogus"), warn)
    }

    // ---------------------------------------------------------------- build() with fakes

    private class Fake(val first: String, val second: String, val firstStop: String = "end_turn") {
        val prompts = mutableListOf<String>(); var system = ""; var repairMessages: List<JsonObject> = emptyList()
        val ask: suspend (String, String) -> LlmResult = { sys, user -> system = sys; prompts += user; LlmResult(first, null, firstStop, "openai", "gpt-6-sol") }
        val again: suspend (String, List<JsonObject>) -> Turn = { _, msgs -> repairMessages = msgs; Turn("end_turn", JsonArray(listOf(Fakes.textBlock(second)))) }
    }
    private fun run(f: Fake, progress: MutableList<Builder.Progress> = mutableListOf(), current: Graph? = null, instruction: String? = null) = runBlocking {
        Builder.run(catalog, "fetch and notify", current, current?.let { "Cur" }, instruction, "OpenAI", "gpt-6-sol", f.ask, f.again) { progress += it }
    }

    @Test fun validFirstAnswerIsOneRound() {
        val f = Fake(fixture("builder_ok.json"), "unused")
        val progress = mutableListOf<Builder.Progress>()
        val r = run(f, progress)
        assertEquals(1, r.rounds); assertEquals(emptyList<String>(), r.errors); assertEquals("Fetch and notify", r.name); assertEquals(3, r.graph.nodes.size)
        assertEquals("OpenAI", r.providerLabel); assertEquals("gpt-6-sol", r.model)
        assertEquals(listOf(Builder.Progress.Asking, Builder.Progress.Validating), progress)
        assertEquals(listOf("Create a workflow: fetch and notify"), f.prompts)
        assertEquals(Builder.systemPrompt(catalog), f.system)
    }

    @Test fun invalidThenRepairedIsTwoRoundsAndTheRepairPromptListsEveryError() {
        val f = Fake(fixture("builder_bad_port.json"), fixture("builder_ok.json"))
        val progress = mutableListOf<Builder.Progress>()
        val r = run(f, progress)
        assertEquals(2, r.rounds); assertEquals(emptyList<String>(), r.errors); assertEquals(3, r.graph.nodes.size)
        assertEquals(listOf(Builder.Progress.Asking, Builder.Progress.Validating, Builder.Progress.Repairing), progress)
        assertEquals(3, f.repairMessages.size)
        assertEquals(listOf("user", "assistant", "user"), f.repairMessages.map { it["role"].asTextOrNull() })
        val repair = Fakes.blocks(f.repairMessages[2])[0]["text"].asTextOrNull()!!
        assertTrue(repair.startsWith("Your workflow failed validation:\n"))
        for (e in errorsOf("builder_bad_port.json")) assertTrue(e, repair.contains("- $e\n"))
        assertEquals(fixture("builder_bad_port.json"), Fakes.blocks(f.repairMessages[1])[0]["text"].asTextOrNull())   // the model sees its own answer
    }

    @Test fun stillInvalidAfterRepairKeepsErrors() {
        val r = run(Fake(fixture("builder_no_trigger.json"), fixture("builder_unknown_type.json")))
        assertEquals(2, r.rounds)
        assertTrue(r.errors.toString(), r.errors.any { it.startsWith("Ping: unknown node type action.notification") })
        assertEquals("Unknown", r.name)
        // unparsable repair keeps the round-1 graph and its errors
        val r2 = run(Fake(fixture("builder_no_trigger.json"), "sorry, no"))
        assertEquals(2, r2.rounds); assertTrue(r2.errors.contains("Add exactly one trigger node (kind T)")); assertEquals(2, r2.graph.nodes.size)
    }

    @Test fun noJsonTwiceAndOutOfTokensAreErrors() {
        try { run(Fake("nope", "still nope")); fail() } catch (e: NodeException) { assertEquals(Builder.ERR_NO_JSON, e.message) }
        try { run(Fake("{\"nodes\":[", "x", firstStop = "max_tokens")); fail() } catch (e: NodeException) { assertEquals(Builder.ERR_TOKENS, e.message) }
        assertEquals(Builder.ERR_NANO.substringBefore(" —"), "Build with AI needs a cloud provider")
    }

    @Test fun refineEmbedsTheCurrentGraphJson() {
        val cur = Graph(listOf(NodeInstance("id-1", "trigger.manual", "Run", item()), NodeInstance("id-2", "action.notify", "Ping", item("title" to "t"))), listOf(Edge("id-1", "main", "id-2", "main")))
        val f = Fake(fixture("builder_ok.json"), "unused")
        run(f, current = cur, instruction = "add a toast")
        assertEquals(Builder.userPrompt("", cur, "Cur", "add a toast"), f.prompts[0])
        assertTrue(f.prompts[0].contains(Builder.graphJson("Cur", cur)))
        assertTrue(f.prompts[0].contains("\"type\":\"action.notify\",\"name\":\"Ping\",\"params\":{\"title\":\"t\"}"))
    }

    /** DESIGN4P P16: an ai.agent set to run without asking is an issue with the `Runs unattended: ` prefix (validate appends it); a default agent is not. */
    @Test fun unattendedAgentsAreFlagged() {
        val agentCatalog = Catalog(listOf(listOf(Fakes.FakeNode(manual), AgentNode)))
        fun agent(id: String, name: String, vararg kv: Pair<String, kotlinx.serialization.json.JsonElement>) = NodeInstance(id, AgentNode.ID, name, kotlinx.serialization.json.JsonObject(mapOf("goal" to JsonPrimitive("g")) + kv))
        val g = Graph(
            listOf(NodeInstance("t", "trigger.manual", "Run", item()), agent("a1", "Quiet", "askApproval" to JsonPrimitive(false)), agent("a2", "Bypasser", "permissionMode" to JsonPrimitive("bypass")), agent("a3", "Default"), agent("a4", "Auto", "permissionMode" to JsonPrimitive("auto"))),
            listOf(Edge("t", "main", "a1", "main"), Edge("t", "main", "a2", "main"), Edge("t", "main", "a3", "main"), Edge("t", "main", "a4", "main")),
        )
        assertEquals(listOf("Runs unattended: Quiet (askApproval=false)", "Runs unattended: Bypasser (permissionMode=bypass)", "Runs unattended: Auto (permissionMode=auto)"), Builder.unattendedAgents(g))
        assertTrue(Builder.unattendedAgents(g).all { it.startsWith(Builder.UNATTENDED_PREFIX) })
        val issues = Builder.validate(g, agentCatalog)
        assertEquals(3, issues.count { it.startsWith(Builder.UNATTENDED_PREFIX) })
        val plain = Graph(listOf(NodeInstance("t", "trigger.manual", "Run", item()), agent("a3", "Default"), agent("a5", "Inherit", "permissionMode" to JsonPrimitive("inherit"), "askApproval" to JsonPrimitive(true))), listOf(Edge("t", "main", "a3", "main"), Edge("t", "main", "a5", "main")))
        assertEquals(emptyList<String>(), Builder.unattendedAgents(plain)); assertEquals(emptyList<String>(), Builder.validate(plain, agentCatalog))
        assertEquals(emptyList<String>(), Builder.unattendedAgents(parsed("builder_ok.json").second))
    }

    @Test fun outputHintsAndProgressTypes() {
        assertNull(Builder.OUTPUT_HINTS["nope"])
        assertTrue(Builder.OUTPUT_HINTS.keys.all { it.startsWith("trigger.") || it.startsWith("data.") || it.startsWith("ai.") || it == "app.shell_run" || it == "logic.js" })   // v4: the two coding nodes
        assertEquals(listOf("exitCode", "stdout", "stderr", "truncated", "timedOut", "ms", "outputFile"), Builder.OUTPUT_HINTS["app.shell_run"]); assertEquals(listOf("value"), Builder.OUTPUT_HINTS["logic.js"])
        assertEquals(3, listOf(Builder.Progress.Asking, Builder.Progress.Validating, Builder.Progress.Repairing).toSet().size)
    }

    /** DESIGN5 §5.4: rule 12 iff a decision engine is configured; decide/classify hints. */
    @Test fun decisionEngineRuleOnlyWhenConfigured() {
        val off = Builder.systemPrompt(catalog); val on = Builder.systemPrompt(catalog, decisionEngine = true)
        assertFalse(off.contains("12. A decision engine is configured")); assertTrue(on.contains("\n" + Builder.RULE_DECIDE + "\n"))
        assertTrue(on.contains("ai.decide") && on.contains("engine=\"system1\""))
        assertEquals(off.length + Builder.RULE_DECIDE.length + 1, on.length)
        assertEquals(listOf("answers", "decisions", "engine", "s1Model", "latencyMs"), Builder.OUTPUT_HINTS["ai.decide"])
        assertEquals(listOf("label", "provider", "confidence", "probabilities"), Builder.OUTPUT_HINTS["ai.classify"])
        val line = Builder.catalogLine(DecideNode.spec)
        assertTrue(line, line.startsWith("ai.decide|D|AI Decide (System 1)|") && line.contains("questions:R[name:s*,type:e=choice{choice,score,noul}") && line.contains("|fields:answers,decisions,engine,s1Model,latencyMs"))
    }

    @Test fun runPassesTheDecisionEngineFlag() = kotlinx.coroutines.runBlocking {
        var system = ""
        val ok = fixture("builder_ok.json")
        Builder.run(catalog, "x", null, null, null, "L", "m", first = { s, _ -> system = s; LlmResult(ok, null, "end_turn", "p", "m") }, again = { _, _ -> error("no repair") }, decisionEngine = true)
        assertTrue(system.contains(Builder.RULE_DECIDE))
    }
}
