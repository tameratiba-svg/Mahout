package com.mob8n

import com.mob8n.ai.Builder
import com.mob8n.core.Catalog
import com.mob8n.core.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build with AI prompt over the REAL six lane catalogs (DESIGN2 §6.1/§9): size budget, one line per node, every id / ENUM option / `^` marker present,
 * no SECRET keys, the few-shot validates, OUTPUT_HINTS reference existing ids.
 */
class BuilderPromptTest {
    private val lanes = listOf(
        com.mob8n.triggers.TriggerNodes.all,
        com.mob8n.data.DataNodes.all,
        com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all,
        com.mob8n.ai.AiNodes.all,
        com.mob8n.apps.AppNodes.all,
    )
    private val catalog = Catalog(lanes)

    @Test fun compactCatalogCoversEveryNodeExactlyOnceWithoutSecrets() {
        val text = Builder.compactCatalog(catalog)
        val lines = text.lines()
        assertEquals(catalog.nodes.size, lines.size)
        for (n in catalog.nodes) {
            val line = lines.single { it.startsWith(n.spec.id + "|") }
            for (p in n.spec.params) {
                if (p.kind == ParamKind.SECRET) assertTrue("${n.spec.id}.${p.key} secret leaked", !line.contains(p.key + ":"))
                else {
                    assertTrue("${n.spec.id}.${p.key} missing", line.contains(p.key + ":"))
                    if (p.kind == ParamKind.ENUM) for (o in p.options) assertTrue("${n.spec.id}.${p.key} option $o", line.contains(o))
                    if (p.definesPorts) assertTrue("${n.spec.id}.${p.key} ^", line.contains("${p.key}:L") && line.contains("^") && line.contains("out:<${p.key}>"))
                }
            }
        }
    }

    @Test fun systemPromptFitsTheBudgetAndPrintsItsSize() {
        val p = Builder.systemPrompt(catalog)
        println("Build-with-AI system prompt: ${p.length} chars (~${Builder.estimateTokens(p)} tokens at 3 chars/token) over ${catalog.nodes.size} nodes")
        assertTrue("system prompt is ${p.length} chars", p.length < 44_000)   // DESIGN4 §2: +2 nodes, trigger.called params, rule-6 sentence
        // DESIGN5 §5.4: rule 12 (decision engine configured) is the larger variant; both stay under the budget.
        val d = Builder.systemPrompt(catalog, decisionEngine = true)
        println("Build-with-AI system prompt with rule 12: ${d.length} chars")
        assertTrue("decision-engine prompt is ${d.length} chars", d.length < 44_000)
        assertTrue("rule 12 only when a decision engine is configured", d.contains("A decision engine is configured") && d.length > p.length && !p.contains("A decision engine is configured"))
    }

    @Test fun fewShotValidatesAgainstTheRealCatalog() {
        val (name, graph) = Builder.parse(Builder.FEW_SHOT_JSON, catalog).getOrThrow()
        assertEquals("Summarize shared link", name)
        assertEquals(6, graph.nodes.size)
        assertEquals(emptyList<String>(), Builder.validate(graph, catalog))
    }

    @Test fun outputHintsReferenceExistingIds() {
        for (id in Builder.OUTPUT_HINTS.keys) assertTrue("OUTPUT_HINTS has unknown id $id", catalog.spec(id) != null)
        assertEquals(listOf("exitCode", "stdout", "stderr", "truncated", "timedOut", "ms", "outputFile"), Builder.OUTPUT_HINTS["app.shell_run"])   // DESIGN4 §7.3
        assertEquals(listOf("value"), Builder.OUTPUT_HINTS["logic.js"])
        assertEquals(listOf("answers", "decisions", "engine", "s1Model", "latencyMs"), Builder.OUTPUT_HINTS["ai.decide"])   // DESIGN5 §5.4
        assertEquals(listOf("label", "provider", "confidence", "probabilities"), Builder.OUTPUT_HINTS["ai.classify"])
    }

    @Test fun recipeLinesComeFromTheRealAppActionSpec() {
        val p = Builder.systemPrompt(catalog)
        assertTrue("recipe section present", p.contains("Recipes for app.action"))
        assertTrue("a real recipe id is listed", p.contains("youtube_search"))
    }

    @Test fun aiNodesExposeProviderModelEffortTemperature() {
        for (id in listOf("ai.ask", "ai.classify", "ai.extract", "ai.agent")) {
            val s = catalog.spec(id)!!
            assertEquals(id, "default", s.param("provider")!!.default!!.toString().trim('"'))
            assertEquals(id, 15, s.param("provider")!!.options.size)
            assertTrue(id, s.param("model")!!.kind == ParamKind.TEXT && s.param("temperature")!!.kind == ParamKind.NUMBER && s.param("effort") != null)
        }
        assertEquals(false, (catalog.spec("ai.agent")!!.param("allowUiAutomation")!!.default!!.toString().toBoolean()))
    }
}
