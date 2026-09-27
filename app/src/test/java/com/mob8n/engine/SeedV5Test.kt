package com.mob8n.engine

import com.mob8n.ai.Builder
import com.mob8n.core.Catalog
import com.mob8n.core.ERROR
import com.mob8n.core.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN5 §8.4 / §9: the 11 seeds validate against the real six-lane catalog (incl. ai.decide) and seed-9..11 reach existing installs. */
class SeedV5Test {
    private val catalog = Catalog(listOf(
        com.mob8n.triggers.TriggerNodes.all, com.mob8n.data.DataNodes.all, com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all, com.mob8n.ai.AiNodes.all, com.mob8n.apps.AppNodes.all,
    ))
    private val seeds = Seed.workflows(0L)
    private val v5 = seeds.filter { it.id in Seed.V5_IDS }

    @Test fun elevenSeedsAndV5AreDisabled() {
        assertEquals(11, seeds.size)
        assertEquals(seeds.size, seeds.map { it.id }.toSet().size)
        assertEquals(listOf("seed-9", "seed-10", "seed-11"), v5.map { it.id })
        assertEquals(listOf("ISR alarm triage", "Telemetry to knowledge", "Mission dry-run gate"), v5.map { it.name })
        assertTrue(seeds.none { it.enabled })
    }

    @Test fun everySeedValidatesAgainstCatalogAndBuilder() {
        val problems = seeds.flatMap { wf -> Builder.validate(wf.graph, catalog).map { "${wf.id} (${wf.name}): $it" } }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun everyParamKeyExistsInItsSpecIncludingRowColumns() {
        val problems = ArrayList<String>()
        for (wf in seeds) for (n in wf.graph.nodes) {
            val spec = catalog.spec(n.type)
            if (spec == null) { problems += "${wf.id}/${n.name}: unknown ${n.type}"; continue }
            for ((k, v) in n.params) {
                val p = spec.param(k)
                if (p == null) { problems += "${wf.id}/${n.name}: no param '$k' on ${n.type}"; continue }
                if (p.kind == ParamKind.ROWS) (v as kotlinx.serialization.json.JsonArray).forEach { row ->
                    (row as kotlinx.serialization.json.JsonObject).keys.filter { c -> p.rows.none { it.key == c } }
                        .forEach { problems += "${wf.id}/${n.name}: $k has no column '$it'" }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun edgesReferenceExistingPorts() {
        for (wf in seeds) {
            val byId = wf.graph.nodes.associateBy { it.id }
            for (e in wf.graph.edges) {
                val f = byId.getValue(e.from); val t = byId.getValue(e.to)
                if (e.fromPort != ERROR) assertTrue("${wf.id}: ${f.name} has no port ${e.fromPort}", e.fromPort in catalog.spec(f.type)!!.outputPorts(f.params))
                assertTrue("${wf.id}: ${t.name} has no input ${e.toPort}", e.toPort in catalog.spec(t.type)!!.inputs)
            }
        }
    }

    @Test fun v5SeedsLeadWithNoteAndReferenceSecretsByNameOnly() {
        for (wf in v5) {
            val note = wf.graph.nodes.single { it.type == "logic.note" }
            val trigger = wf.graph.nodes.first()
            assertTrue(catalog.spec(trigger.type)!!.kind == com.mob8n.core.NodeKind.TRIGGER)
            assertTrue("${wf.id}: the note follows the trigger", wf.graph.edges.any { it.from == trigger.id && it.to == note.id })
            assertTrue(wf.graph.edges.none { it.from == trigger.id && it.to != note.id })
        }
        val all = v5.joinToString("\n") { it.graph.nodes.joinToString { n -> n.params.toString() } }
        assertTrue(all.contains("\"godseye_bridge_token\"")); assertTrue(all.contains("\"godseye_webhook_token\""))
        assertFalse("secrets never ride in URLs", all.contains("token=") || all.contains("key="))
        val http = v5.flatMap { it.graph.nodes }.single { it.type == "data.http" }
        assertEquals("godseye_bridge_token", http.params["authSecret"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test fun v5SeedShapes() {
        val (s9, s10, s11) = v5
        val decide = s9.graph.nodes.single { it.type == "ai.decide" }
        assertTrue(s9.graph.edges.any { it.from == decide.id && it.fromPort == ERROR })
        assertEquals(setOf("alert", "monitor"), s9.graph.edges.filter { it.from == s9.graph.nodes.single { n -> n.type == "logic.switch" }.id }.map { it.fromPort }.toSet())
        assertTrue(s10.graph.nodes.any { it.type == "action.knowledge_add" })
        val mcp = s11.graph.nodes.single { it.type == "ai.mcp_tool" }
        assertEquals("mission_dry_run", (mcp.params["tool"] as kotlinx.serialization.json.JsonPrimitive).content)   // executes nothing; no mutator runs unapproved
        assertTrue(s11.graph.nodes.none { it.type == "ai.mcp_tool" && (it.params["tool"] as kotlinx.serialization.json.JsonPrimitive).content != "mission_dry_run" })
    }

    @Test fun existingInstallsGetExactlyTheV5Seeds() {
        assertEquals(Seed.V5_IDS, Seed.toInsert(seeded = true, all = seeds).map { it.id }.toSet())
        assertEquals(11, Seed.toInsert(seeded = false, all = seeds).size)
    }
}
