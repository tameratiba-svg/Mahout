package com.mob8n

import com.mob8n.core.Catalog
import com.mob8n.core.NodeKind
import com.mob8n.core.TriggerNode
import com.mob8n.engine.attachesInHost
import com.mob8n.engine.schedulesDurably
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration gate: the six lane catalogs (exactly what Mob8NApp feeds Catalog) contain every DESIGN §4 / DESIGN2 §7.5 / DESIGN3 §5.9+§4.5 id
 * exactly once (v4 / DESIGN4 V10: + `app.shell_run`, `logic.js` = 135; v5 / DESIGN5 §9: + `ai.decide` = 136). Catalog's init throws on duplicates at app start, so this must stay green.
 */
class CatalogTest {
    private val lanes = listOf(
        com.mob8n.triggers.TriggerNodes.all,
        com.mob8n.data.DataNodes.all,
        com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all,
        com.mob8n.ai.AiNodes.all,
        com.mob8n.apps.AppNodes.all,
    )

    /** DESIGN §4.1-4.5 + DESIGN2 §7.5 + DESIGN3 W13 + DESIGN4 V10 + DESIGN5 §9, in table order (36 + 18 + 27 + 35 + 7 + 13 = 136). */
    private val designIds = listOf(
        "trigger.now_playing", "trigger.notification_posted", "trigger.notification_removed", "trigger.share", "trigger.tile", "trigger.shortcut",
        "trigger.manual", "trigger.called", "trigger.notification_action", "trigger.schedule", "trigger.boot", "trigger.charger",
        "trigger.battery_level", "trigger.network", "trigger.bluetooth", "trigger.headset", "trigger.screen", "trigger.unlocked",
        "trigger.airplane", "trigger.ringer", "trigger.volume", "trigger.dnd", "trigger.power_save", "trigger.time_changed",
        "trigger.locale", "trigger.package", "trigger.download", "trigger.new_photo", "trigger.calendar_upcoming", "trigger.phone_call",
        "trigger.sms", "trigger.geofence", "trigger.shake", "trigger.nfc", "trigger.clipboard", "trigger.webhook",
        "data.device_state", "data.now_playing", "data.active_notifications", "data.location", "data.calendar_events", "data.contact_lookup",
        "data.media_list", "data.read_file", "data.clipboard", "data.installed_apps", "data.app_info", "data.variable",
        "data.http", "data.datetime", "data.random", "data.storage", "data.sensor", "data.knowledge_search",
        "logic.if", "logic.switch", "logic.merge", "logic.split_batches", "logic.flatten", "logic.aggregate", "logic.sort",
        "logic.limit", "logic.unique", "logic.dedupe_window", "logic.rate_limit", "logic.delay", "logic.wait_until",
        "logic.wait_approval", "logic.set_fields", "logic.template", "logic.json", "logic.text", "logic.math",
        "logic.date", "logic.counter", "logic.repeat", "logic.note", "logic.run_workflow", "logic.stop_error", "logic.execute_once", "logic.js",
        "action.add_to_playlist", "action.media_control", "action.notify", "action.cancel_notification", "action.reply_notification",
        "action.launch_app", "action.open_url", "action.send_intent", "action.share", "action.dial", "action.compose_sms",
        "action.compose_email", "action.navigate", "action.add_calendar_event", "action.add_contact", "action.set_alarm", "action.tts",
        "action.play_sound", "action.vibrate", "action.clipboard_set", "action.toast", "action.ringer_dnd", "action.display_settings",
        "action.set_ringtone", "action.settings_panel", "action.flashlight", "action.wallpaper", "action.write_file", "action.download",
        "action.save_note", "action.schedule_run", "action.toggle_workflow", "action.log", "action.knowledge_add", "action.knowledge_remove",
        "ai.ask", "ai.classify", "ai.extract", "ai.agent", "ai.mcp_tool", "ai.mcp_resource", "ai.decide",
        "app.capabilities", "app.recipes", "app.action", "app.launch_wait", "app.ui_read", "app.ui_tap", "app.ui_long_press",
        "app.ui_type", "app.ui_scroll", "app.ui_wait_for", "app.ui_global", "app.ui_screenshot", "app.shell_run",
    )

    @Test fun designListItselfIs136Unique() {
        assertEquals(136, designIds.size)
        assertEquals(136, designIds.toSet().size)
    }

    @Test fun catalogBuildsWithoutDuplicates() {
        val catalog = Catalog(lanes)   // init throws "duplicate node ids" on a clash
        assertEquals(136, catalog.nodes.size)
        assertEquals(136, catalog.nodes.map { it.spec.id }.toSet().size)
    }

    @Test fun everyDesignIdPresentExactlyOnce() {
        val ids = lanes.flatten().map { it.spec.id }
        val missing = designIds - ids.toSet()
        val extra = ids.toSet() - designIds.toSet()
        val dupes = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("missing from catalogs: $missing", missing.isEmpty())
        assertTrue("not in DESIGN §4 / DESIGN2 §7.5 / DESIGN3 W13 / DESIGN4 V10 / DESIGN5 §9: $extra", extra.isEmpty())
        assertTrue("duplicated: $dupes", dupes.isEmpty())
    }

    @Test fun laneSizesMatchDesign() {
        assertEquals(36, lanes[0].size); assertEquals(18, lanes[1].size); assertEquals(27, lanes[2].size)
        assertEquals(35, lanes[3].size); assertEquals(7, lanes[4].size); assertEquals(13, lanes[5].size)
    }

    @Test fun everyTriggerIsTriggerNodeWithHostingAndNoInputs() {
        val catalog = Catalog(lanes)
        val triggers = catalog.nodes.filter { it.spec.kind == NodeKind.TRIGGER }
        assertEquals(36, triggers.size)
        for (t in triggers) {
            assertTrue("${t.spec.id} must extend TriggerNode", t is TriggerNode)
            (t as TriggerNode).hosting   // non-null by type; accessing proves the object initialises on the JVM
            assertTrue("${t.spec.id} must have no inputs", t.spec.inputs.isEmpty())
            assertTrue("${t.spec.id} is never an agent tool", !t.spec.agentTool)
        }
        assertTrue(catalog.nodes.filter { it is TriggerNode }.all { it.spec.kind == NodeKind.TRIGGER })
    }

    @Test fun idsMatchTheirLanePrefixAndKind() {
        for (n in lanes.flatten()) {
            val prefix = n.spec.id.substringBefore('.')
            if (prefix == "app") { assertTrue("${n.spec.id} must be DATA or ACTION", n.spec.kind == NodeKind.DATA || n.spec.kind == NodeKind.ACTION); continue }
            if (prefix == "ai") { assertTrue("${n.spec.id} must be AI or DATA (DESIGN3 W13)", n.spec.kind == NodeKind.AI || n.spec.kind == NodeKind.DATA); continue }
            val expected = when (n.spec.kind) {
                NodeKind.TRIGGER -> "trigger"; NodeKind.DATA -> "data"; NodeKind.LOGIC -> "logic"
                NodeKind.ACTION -> "action"; NodeKind.AI -> "ai"
            }
            assertEquals("${n.spec.id} kind/prefix mismatch", expected, prefix)
        }
    }

    /** DESIGN4 V10: the two coding nodes — `logic.js` is LOGIC and never an agent tool (ai.agent must not run JS ungated); `app.shell_run` is an ACTION agent tool. */
    @Test fun codingNodesHaveTheDesignKindsAndToolFlags() {
        val catalog = Catalog(lanes)
        val js = catalog.spec("logic.js")!!; val sh = catalog.spec("app.shell_run")!!
        assertEquals(NodeKind.LOGIC, js.kind); assertTrue("logic.js must not be an agent tool", !js.agentTool); assertTrue(!js.param("code")!!.templated)
        assertEquals(NodeKind.ACTION, sh.kind); assertTrue("app.shell_run is an agent tool", sh.agentTool); assertTrue(sh.gates.isEmpty() && js.gates.isEmpty())
    }

    /** DESIGN4P §2.8: `ai.agent`'s LAST param is `permissionMode` (ENUM inherit|plan|ask|auto|bypass, default inherit, not templated). */
    @Test fun agentPermissionModeParamIsLastAndFrozen() {
        val p = Catalog(lanes).spec("ai.agent")!!.params.last()
        assertEquals("permissionMode", p.key)
        assertEquals(com.mob8n.core.ParamKind.ENUM, p.kind)
        assertEquals(listOf("inherit", "plan", "ask", "auto", "bypass"), p.options)
        assertEquals("inherit", (p.default as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertTrue("permissionMode must not be templated", !p.templated)
    }

    @Test fun everySpecDerivesAToolDefWithoutThrowing() {
        for (n in lanes.flatten()) n.spec.toolDef()
    }

    /** F23/K1: the hub's attach/durable predicates (spec-id allow-lists) cover the two triggers whose hosting enum under-describes them. */
    @Test fun hubPredicatesCoverNewPhotoAndCharger() {
        val catalog = Catalog(lanes)
        assertTrue(catalog.trigger("trigger.new_photo")!!.attachesInHost())
        assertTrue(catalog.trigger("trigger.new_photo")!!.schedulesDurably())
        assertTrue(!catalog.trigger("trigger.schedule")!!.attachesInHost())
        assertTrue(catalog.trigger("trigger.schedule")!!.schedulesDurably())
        assertTrue(catalog.trigger("trigger.charger")!!.attachesInHost())
        assertTrue(catalog.trigger("trigger.charger")!!.schedulesDurably())
        assertTrue(!catalog.trigger("trigger.screen")!!.schedulesDurably())
        assertTrue(catalog.trigger("trigger.package")!!.attachesInHost())
    }

    /** DESIGN5 §2/§5.2: `ai.decide` is DATA (never wakes the host), an agent tool, ungated, spec timeout 8 000 (param 1–7 s). */
    @Test fun decideNodeIsFastDataToolWithoutGates() {
        val d = Catalog(lanes).spec("ai.decide")!!
        assertEquals(NodeKind.DATA, d.kind); assertTrue(d.agentTool); assertTrue(d.gates.isEmpty()); assertEquals(8_000L, d.timeoutMs)
        assertEquals(listOf("state", "questions", "engine", "timeoutMs"), d.params.map { it.key })
        assertEquals(listOf("default", "jev", "laya"), d.param("engine")!!.options)
        assertEquals(com.mob8n.ai.DecideNode.QUESTION_COLUMNS.map { it.key }, d.param("questions")!!.rows.map { it.key })
    }

    /** DESIGN5 D4: `ai.classify`'s LAST param is `engine` (generative|system1, default generative). */
    @Test fun classifyEngineParamIsLast() {
        val p = Catalog(lanes).spec("ai.classify")!!.params.last()
        assertEquals("engine", p.key)
        assertEquals(com.mob8n.core.ParamKind.ENUM, p.kind)
        assertEquals(listOf("generative", "system1"), p.options)
        assertEquals("generative", (p.default as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    /** DESIGN5 §3.3: the triage params are the LAST four on both triageable triggers (v5 device phase adds triageState); `off` by default, fail-open by default. */
    @Test fun triageParamsAreLastOnBothTriggers() {
        val catalog = Catalog(lanes)
        for (id in listOf("trigger.notification_posted", "trigger.share")) {
            val ps = catalog.spec(id)!!.params
            assertEquals(id, listOf("triageEngine", "triageQuestions", "triageOnError", "triageState"), ps.takeLast(4).map { it.key })
            assertEquals(com.mob8n.core.ParamKind.TEXT, ps.last().kind); assertTrue(ps.last().templated)
            assertEquals(listOf("off", "default", "jev", "laya"), ps.first { it.key == "triageEngine" }.options)
            assertEquals("off", (ps.first { it.key == "triageEngine" }.default as? kotlinx.serialization.json.JsonPrimitive)?.content)
            assertEquals("run", (ps.first { it.key == "triageOnError" }.default as? kotlinx.serialization.json.JsonPrimitive)?.content)
        }
        assertEquals("{{appName}}: {{title}} — {{text}} {{bigText}}", (catalog.spec("trigger.notification_posted")!!.params.last().default as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("{{subject}} {{text}} {{url}}", (catalog.spec("trigger.share")!!.params.last().default as kotlinx.serialization.json.JsonPrimitive).content)
    }

    /** The 11 seeded graphs (engine lane; v5 adds seed-9..11) must validate against the real lane specs: catches param/port drift between Seed.kt and §4. */
    @Test fun seededWorkflowsValidateAgainstCatalog() {
        val catalog = Catalog(lanes)
        val seeds = com.mob8n.engine.Seed.workflows(0L)
        assertEquals(11, seeds.size)
        assertEquals(com.mob8n.engine.Seed.V5_IDS, seeds.map { it.id }.filter { it in com.mob8n.engine.Seed.V5_IDS }.toSet())
        val problems = seeds.flatMap { wf -> wf.graph.validate(catalog).map { "${wf.id} (${wf.name}): $it" } }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
