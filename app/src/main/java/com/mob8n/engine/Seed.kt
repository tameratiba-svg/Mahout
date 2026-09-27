package com.mob8n.engine

import android.content.Context
import com.mob8n.core.EMPTY
import com.mob8n.core.ERROR
import com.mob8n.core.Edge
import com.mob8n.core.Graph
import com.mob8n.core.MAIN
import com.mob8n.core.NodeInstance
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.Workflow
import com.mob8n.core.item
import com.mob8n.engine.db.Mob8nDao
import com.mob8n.engine.db.toEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The 8 sample workflows of DESIGN §7.5 plus the 3 ObraMaestra samples of DESIGN5 §8.4 (seed-9..11), all inserted DISABLED exactly once.
 * settings.seeded covers a fresh install (all 11); settings.seeded_v5 gives existing installs exactly seed-9..11 (insert-ignore, so an id
 * the user already has is never overwritten). Param keys follow the lane specs (SeedV5Test / CatalogTest validate them).
 */
object Seed {
    private const val KEY_SEEDED = "seeded"
    private const val KEY_SEEDED_V5 = "seeded_v5"
    val V5_IDS = setOf("seed-9", "seed-10", "seed-11")
    private const val DX = 260f
    // ponytail: seeds hold a placeholder LAN IP and secret NAMES; a leading logic.note says so
    private const val MAC_IP = "192.168.1.10"
    private const val BRIDGE_SECRET = "godseye_bridge_token"
    private const val WEBHOOK_SECRET = "godseye_webhook_token"

    suspend fun seedIfNeeded(ctx: Context, dao: Mob8nDao, nowMs: Long) {
        val prefs = ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)
        if (seeded && prefs.getBoolean(KEY_SEEDED_V5, false)) return
        dao.insertWorkflowsIgnore(toInsert(seeded, workflows(nowMs)).map { it.toEntity() })   // @Insert runs in its own transaction
        prefs.edit().putBoolean(KEY_SEEDED, true).putBoolean(KEY_SEEDED_V5, true).apply()
    }

    /** Pure (SeedV5Test): a fresh install gets all 11; an install seeded before v5 gets exactly seed-9..11. */
    internal fun toInsert(seeded: Boolean, all: List<Workflow>): List<Workflow> = if (seeded) all.filter { it.id in V5_IDS } else all

    private fun note(i: Int, text: String) = n(i, "logic.note", "Setup first", item("text" to text))

    /** Node helper: ids n1..nN, laid out left-to-right 260 dp apart; `row` offsets a second chain vertically. */
    private fun n(i: Int, type: String, name: String, params: JsonObject = EMPTY, col: Int = i - 1, row: Int = 0) =
        NodeInstance(id = "n$i", type = type, name = name, params = params, x = col * DX, y = row * 200f)

    private fun e(from: Int, to: Int, fromPort: String = MAIN) = Edge("n$from", fromPort, "n$to", MAIN)

    fun workflows(nowMs: Long): List<Workflow> = listOf(
        Workflow("seed-1", "Auto Liked", false, Graph(
            nodes = listOf(
                n(1, "trigger.now_playing", "Now Playing", item("onlyWhenPlaying" to true)),
                n(2, "logic.dedupe_window", "Dedupe", item("keyTemplate" to "{{title}}|{{artist}}", "windowMs" to 600000L)),
                n(3, "action.add_to_playlist", "Add", item("playlist" to "Auto Liked")),
                n(4, "action.notify", "Notify", item("title" to "Added to Auto Liked", "text" to "{{title}} by {{artist}}", "importance" to "low")),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(3, 4)),
        ), nowMs),

        Workflow("seed-2", "Summarize shared link", false, Graph(
            nodes = listOf(
                n(1, "trigger.share", "Share", item("accept" to "url")),
                n(2, "data.http", "Fetch", item("method" to "GET", "url" to "{{url}}")),
                n(3, "logic.text", "Clip", item("op" to "truncate", "input" to "{{body}}", "maxLength" to 12000, "outputField" to "text")),
                n(4, "ai.ask", "Summarize", item("provider" to "auto", "prompt" to "Summarize this page in 5 bullet points:\n\n{{text}}", "outputField" to "summary")),
                n(5, "action.notify", "Notify", item("title" to "Summary", "text" to "{{url}}", "bigText" to "{{summary}}")),
                n(6, "action.clipboard_set", "Copy", item("text" to "{{summary}}"), col = 4, row = 1),
                n(7, "action.notify", "Notify fail", item("title" to "Could not fetch", "text" to "{{error}}"), col = 2, row = 1),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(3, 4), e(4, 5), e(4, 6), e(2, 7, ERROR)),
        ), nowMs),

        Workflow("seed-3", "Urgent chat → speak", false, Graph(
            nodes = listOf(
                n(1, "trigger.notification_posted", "Chat notif", item("packageName" to "com.whatsapp", "ignoreOngoing" to true)),
                n(2, "ai.classify", "Classify", buildJsonObject {
                    put("text", "{{title}}: {{text}}")
                    put("labels", buildJsonArray { add(JsonPrimitive("urgent")); add(JsonPrimitive("normal")); add(JsonPrimitive("spam")) })
                    put("instructions", "Classify the chat message urgency")
                }),
                n(3, "data.device_state", "State"),
                n(4, "logic.if", "BT?", buildJsonObject {
                    put("conditions", buildJsonArray { add(buildJsonObject { put("field", "btAudioConnected"); put("op", "is_true") }) })
                }),
                n(5, "action.tts", "Speak", item("text" to "Urgent message from {{title}}: {{text}}")),
            ),
            edges = listOf(e(1, 2), e(2, 3, "urgent"), e(3, 4), e(4, 5, "true")),
        ), nowMs),

        Workflow("seed-4", "Night charging mode", false, Graph(
            nodes = listOf(
                n(1, "trigger.charger", "Plugged", item("event" to "connected")),
                n(2, "logic.date", "Night?", item("op" to "is_between", "from" to "22:00", "to" to "06:00")),
                n(3, "action.ringer_dnd", "DND on", item("dnd" to "priority", "restoreVariable" to "night_prev")),
                n(4, "action.display_settings", "Dim", item("brightnessPercent" to 10, "autoBrightness" to "off", "restoreVariable" to "night_prev_display")),
                n(5, "trigger.charger", "Unplugged", item("event" to "disconnected"), col = 0, row = 1),
                n(6, "action.ringer_dnd", "Restore DND", item("op" to "restore", "restoreVariable" to "night_prev"), col = 1, row = 1),
                n(7, "action.display_settings", "Restore display", item("op" to "restore", "restoreVariable" to "night_prev_display"), col = 2, row = 1),
            ),
            edges = listOf(e(1, 2), e(2, 3, "true"), e(3, 4), e(5, 6), e(6, 7)),
        ), nowMs),

        Workflow("seed-5", "Morning briefing", false, Graph(
            nodes = listOf(
                n(1, "trigger.schedule", "08:00", item("mode" to "daily", "time" to "08:00")),
                n(2, "data.calendar_events", "Calendar", item("hours" to 12)),
                n(3, "logic.aggregate", "Agg", item("mode" to "all_items", "outputField" to "events")),
                n(4, "data.device_state", "State"),
                n(5, "ai.ask", "Plan", item("provider" to "auto", "prompt" to "Plan my day. Battery {{battery}}%. Events: {{events}}", "outputField" to "plan")),
                n(6, "action.notify", "Notify", item("title" to "Your day", "bigText" to "{{plan}}", "importance" to "high")),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(3, 4), e(4, 5), e(5, 6)),
        ), nowMs),

        Workflow("seed-6", "Screenshot agent", false, Graph(
            nodes = listOf(
                n(1, "trigger.new_photo", "Screenshot", item("kind" to "screenshot")),
                n(2, "ai.agent", "Agent", item(
                    "goal" to "Extract any URLs or text worth saving from this screenshot, save a note with them, then notify me with a one-line summary.",
                    "imageUri" to "{{uri}}", "askApproval" to true,
                )),
                n(3, "action.notify", "Done", item("title" to "Agent done", "text" to "{{result}}")),
                n(4, "action.toast", "Denied", item("text" to "Agent cancelled"), col = 2, row = 1),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(2, 4, "denied")),
        ), nowMs),

        Workflow("seed-7", "Home Wi-Fi", false, Graph(
            nodes = listOf(
                n(1, "trigger.network", "Home wifi", item("event" to "connected", "transport" to "wifi", "ssidMatch" to "HomeWifi")),
                n(2, "action.media_control", "Volume", item("command" to "set_volume", "stream" to "music", "percent" to 30)),
                n(3, "action.launch_app", "Music", item("packageName" to "com.google.android.apps.youtube.music")),
            ),
            edges = listOf(e(1, 2), e(2, 3)),
        ), nowMs),

        Workflow("seed-8", "Torch tile", false, Graph(
            nodes = listOf(
                n(1, "trigger.tile", "Tile"),
                n(2, "action.flashlight", "Torch", item("mode" to "toggle")),
                n(3, "action.toast", "Toast", item("text" to "Torch {{torchOn}}")),
            ),
            edges = listOf(e(1, 2), e(2, 3)),
        ), nowMs),

        // ---- v5 (DESIGN5 §8.4): ObraMaestra godSeye samples. The note sits right after the trigger so the graph stays reachable (Builder.validate).
        Workflow("seed-9", "ISR alarm triage", false, Graph(
            nodes = listOf(
                n(1, "trigger.webhook", "Alarm webhook", item("port" to 8787, "path" to "/godseye/alarm", "tokenSecret" to WEBHOOK_SECRET)),
                note(2, "Create the secret named $WEBHOOK_SECRET first (Settings > AI > secrets). On the Mac, forward godSeye's /events to this webhook " +
                    "(README: the SSE -> webhook forwarder line, with X-Token = that secret and <tablet-ip>:8787). Needs a decision engine: Settings > AI > Decision engine."),
                n(3, "ai.decide", "Decide", item(
                    "state" to "{{body}}",   // the webhook item is the HTTP envelope; the alarm JSON is its body
                    "questions" to listOf(
                        item("name" to "threat", "type" to "choice", "instructions" to "How should the operator treat this UAV alarm?",
                            "criteria" to "ignore: informational, no action\nmonitor: keep watching, no interruption\nalert: needs the operator now"),
                        item("name" to "urgency", "type" to "score", "instructions" to "How urgent is this alarm?", "criteria" to "low, medium, high, critical"),
                    ),
                )),
                n(4, "logic.switch", "Route", item("field" to "answers.threat", "cases" to listOf("alert", "monitor"))),
                n(5, "action.notify", "Alert", item("title" to "UAV ALERT {{body.kind}} {{body.vehicle}}", "text" to "{{body.message}}", "importance" to "high")),
                n(6, "action.tts", "Speak", item("text" to "UAV alert, {{body.vehicle}}: {{body.message}}")),
                n(7, "action.notify", "Monitor", item("title" to "UAV {{body.kind}}", "text" to "{{body.message}}", "importance" to "low"), col = 4, row = 1),
                n(8, "action.notify", "Engine failed", item("title" to "Decision engine failed", "text" to "{{error}}"), col = 3, row = 2),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(3, 4), e(4, 5, "alert"), e(5, 6), e(4, 7, "monitor"), e(3, 8, ERROR)),
        ), nowMs),

        Workflow("seed-10", "Telemetry to knowledge", false, Graph(
            nodes = listOf(
                n(1, "trigger.schedule", "Every 15 min", item("mode" to "interval", "everyMinutes" to 15)),
                note(2, "Edit the Snapshot URL: replace $MAC_IP with your Mac's LAN IP (ipconfig getifaddr en0) and forward godSeye's bridge :8790 " +
                    "(README). Create the secret named $BRIDGE_SECRET with the Bearer token ./start.sh printed."),
                n(3, "data.http", "Snapshot", item("method" to "GET", "url" to "http://$MAC_IP:8790/snapshot", "authSecret" to BRIDGE_SECRET,
                    "allowHttp" to true, "timeoutMs" to 10_000L)),
                n(4, "logic.template", "Summarise", item(
                    "template" to "UAV telemetry {{\$now}}\nsim_state: {{body.sim_state}}\nvehicles: {{body.vehicles}}\nmissions: {{body.missions}}\ncontacts: {{body.contacts}}",
                    "outputField" to "text")),
                n(5, "action.knowledge_add", "Save", item("source" to "text", "text" to "{{text}}", "name" to "UAV telemetry (latest)", "group" to "godseye",
                    "pinned" to false, "replace" to true)),
                n(6, "action.notify", "Fetch failed", item("title" to "Telemetry fetch failed", "text" to "{{error}}"), col = 3, row = 1),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(3, 4), e(4, 5), e(3, 6, ERROR)),
        ), nowMs),

        Workflow("seed-11", "Mission dry-run gate", false, Graph(
            nodes = listOf(
                n(1, "trigger.called", "Called", item(
                    "exposeAsTool" to true,
                    "toolDescription" to "Dry-run a godSeye grid search and report whether the fuel/BINGO gate passes (executes nothing)",
                    "inputs" to listOf(
                        item("name" to "vehicle", "type" to "string", "description" to "Vehicle name, e.g. Drone1", "required" to true),
                        item("name" to "polygon", "type" to "json", "description" to "[[lat,lon],…]", "required" to true),
                        item("name" to "alt_agl_m", "type" to "number", "description" to "Altitude above ground level, metres", "required" to true),
                        item("name" to "overlap_pct", "type" to "number", "description" to "Side overlap in PERCENT (30 = 30 %); default 20", "required" to false),
                    ),
                )),
                note(2, "Add the MCP server from the preset godseye-uav first (Settings > AI > MCP servers > Add preset…, host = your Mac's LAN IP, " +
                    "token pasted there). mission_dry_run executes nothing; never wire a mutating mission_* tool into ai.mcp_tool without logic.wait_approval in front."),
                n(3, "ai.mcp_tool", "Dry run", item("server" to "godseye-uav", "tool" to "mission_dry_run",
                    "arguments" to "{\"kind\":\"grid_search\",\"vehicle\":\"{{vehicle}}\",\"polygon\":{{polygon}},\"alt_agl_m\":{{alt_agl_m}},\"speed\":12," +
                        "\"camera\":\"front\",\"overlap_pct\":{{overlap_pct ?? 20}},\"pattern\":\"lawnmower\"}",
                    "timeoutMs" to 60_000L, "failOnToolError" to true)),
                n(4, "logic.if", "Fuel gate", item(
                    "conditions" to listOf(item("field" to "structured.est_fuel_pct", "op" to "lte", "value" to "60")), "combine" to "and")),
                n(5, "action.notify", "Dry run OK", item("title" to "Dry run OK",
                    "text" to "{{structured.est_time_s}} s, {{structured.est_fuel_pct}} % fuel — approve the real mission in chat")),
                n(6, "action.notify", "Dry run rejected", item("title" to "Dry run rejected",
                    "text" to "est fuel {{structured.est_fuel_pct}} % > 60 % or gate failed"), col = 4, row = 1),
                n(7, "action.notify", "Dry run failed", item("title" to "Dry run failed", "text" to "{{error}}"), col = 3, row = 2),
            ),
            edges = listOf(e(1, 2), e(2, 3), e(3, 4), e(4, 5, "true"), e(4, 6, "false"), e(3, 7, ERROR)),
        ), nowMs),
    )
}
