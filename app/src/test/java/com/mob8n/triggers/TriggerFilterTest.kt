package com.mob8n.triggers

import com.mob8n.core.EMPTY
import com.mob8n.core.add
import com.mob8n.core.NodeKind
import com.mob8n.core.item
import com.mob8n.core.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TriggerFilterTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private fun ms(iso: String, zone: ZoneId) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    @Test fun catalogHas36UniqueTriggerIds() {
        assertEquals(36, TriggerNodes.all.size)
        assertEquals(36, TriggerNodes.all.map { it.spec.id }.toSet().size)
        assertTrue(TriggerNodes.all.all { it.spec.kind == NodeKind.TRIGGER && it.spec.inputs.isEmpty() })
        assertTrue(TriggerNodes.all.all { it.spec.id.startsWith("trigger.") })
    }

    // ---- notifications ----
    private val notif = item("packageName" to "com.whatsapp", "title" to "Alice", "text" to "call me NOW", "bigText" to "call me NOW please", "ongoing" to false, "isGroupSummary" to false)

    @Test fun notificationPackageAndRegexFilters() {
        val t = NotificationPostedTrigger
        assertTrue(t.accepts(EMPTY, notif))
        assertTrue(t.accepts(item("packageName" to "com.whatsapp"), notif))
        assertFalse(t.accepts(item("packageName" to "com.telegram"), notif))
        assertTrue(t.accepts(item("titleRegex" to "^Ali"), notif))
        assertFalse(t.accepts(item("titleRegex" to "^Bob"), notif))
        assertTrue(t.accepts(item("textRegex" to "(?i)now"), notif))
        assertTrue(t.accepts(item("textRegex" to "please"), notif))          // matches bigText too
        assertFalse(t.accepts(item("textRegex" to "later"), notif))
    }

    @Test fun notificationOngoingAndSummaryDefaults() {
        val t = NotificationPostedTrigger
        assertFalse(t.accepts(EMPTY, notif.plus("ongoing" to true)))                       // ignoreOngoing default true
        assertTrue(t.accepts(item("ignoreOngoing" to false), notif.plus("ongoing" to true)))
        assertFalse(t.accepts(EMPTY, notif.plus("isGroupSummary" to true)))
        assertEquals(null, t.toItems(EMPTY, notif).single()["isGroupSummary"])
        assertEquals("Alice", t.toItems(EMPTY, notif).single().str("title"))
        assertTrue(NotificationRemovedTrigger.accepts(item("titleRegex" to "Ali"), notif))
        assertFalse(NotificationRemovedTrigger.accepts(item("packageName" to "x"), notif))
    }

    // ---- network ----
    private val wifi = item("connected" to true, "transport" to "wifi", "ssid" to "HomeWifi-5G", "metered" to false)

    @Test fun networkTransportAndSsid() {
        val t = NetworkTrigger
        assertTrue(t.accepts(EMPTY, wifi))                                              // default event=connected
        assertFalse(t.accepts(EMPTY, wifi.plus("connected" to false)))
        assertTrue(t.accepts(item("event" to "either"), wifi.plus("connected" to false)))
        assertTrue(t.accepts(item("transport" to "wifi"), wifi))
        assertFalse(t.accepts(item("transport" to "cellular"), wifi))
        assertTrue(t.accepts(item("transport" to "wifi", "ssidMatch" to "HomeWifi"), wifi))
        assertFalse(t.accepts(item("ssidMatch" to "Office"), wifi))
        assertFalse(t.accepts(item("ssidMatch" to "HomeWifi"), wifi.plus("ssid" to null)))  // no location -> ssid null never matches
    }

    // ---- battery threshold edges ----
    @Test fun batteryEdgeDetection() {
        val t = BatteryLevelTrigger
        fun ev(prev: Int, level: Int) = item("level" to level, "previous" to prev, "charging" to false)
        assertTrue(t.accepts(item("percent" to 20), ev(21, 20)))       // crossing down onto threshold
        assertTrue(t.accepts(item("percent" to 20), ev(25, 15)))       // jump across
        assertFalse(t.accepts(item("percent" to 20), ev(20, 19)))      // already below: no re-fire
        assertFalse(t.accepts(item("percent" to 20), ev(19, 25)))      // rising while in below mode
        assertTrue(t.accepts(item("mode" to "above", "percent" to 80), ev(79, 80)))
        assertFalse(t.accepts(item("mode" to "above", "percent" to 80), ev(80, 81)))
        assertFalse(t.accepts(EMPTY, item("level" to 10, "charging" to false, "systemEvent" to "low")))   // manifest event never drives thresholds
        assertTrue(t.accepts(item("mode" to "system_low"), item("level" to 10, "systemEvent" to "low")))
        assertFalse(t.accepts(item("mode" to "system_low"), item("level" to 30, "systemEvent" to "okay")))
        assertFalse(t.accepts(item("mode" to "system_low"), ev(21, 20)))
        assertNull(t.toItems(EMPTY, ev(21, 20)).single()["previous"])
    }

    // ---- schedule math ----
    @Test fun dailyNextOccurrence() {
        val s = ScheduleTrigger
        val p = item("mode" to "daily", "time" to "08:00")
        assertEquals(ms("2026-09-25T08:00", utc), s.nextRunMs(p, ms("2026-09-25T07:59", utc), utc))
        assertEquals(ms("2026-09-26T08:00", utc), s.nextRunMs(p, ms("2026-09-25T08:00", utc), utc))   // strictly after now
        assertEquals(ms("2026-09-26T08:00", utc), s.nextRunMs(p, ms("2026-09-25T23:30", utc), utc))
    }

    @Test fun weeklySelectedDays() {
        val s = ScheduleTrigger
        val p = item("mode" to "weekly", "time" to "09:00", "days" to listOf("Mon", "Fri"))
        // 2026-09-23 is a Wednesday
        assertEquals(ms("2026-09-25T09:00", utc), s.nextRunMs(p, ms("2026-09-23T10:00", utc), utc))
        assertEquals(ms("2026-09-28T09:00", utc), s.nextRunMs(p, ms("2026-09-25T09:00", utc), utc))   // Fri 09:00 exactly -> next Mon
        assertEquals(ms("2026-09-28T09:00", utc), s.nextRunMs(item("mode" to "weekly", "time" to "09:00", "days" to listOf("Monday")), ms("2026-09-25T09:00", utc), utc))
        assertNull(s.nextRunMs(item("mode" to "weekly", "time" to "09:00", "days" to emptyList<String>()), ms("2026-09-25T09:00", utc), utc))
        assertEquals("Fri", s.weekday(ms("2026-09-25T09:00", utc), utc))
    }

    @Test fun dstSpringForwardGapAndDayLength() {
        val s = ScheduleTrigger
        // New York springs forward 2026-03-08 at 02:00 -> 02:30 does not exist; ZonedDateTime shifts it to 03:30 EDT
        val p = item("mode" to "daily", "time" to "02:30")
        val sat = ms("2026-03-07T02:30", ny)
        val sun = s.nextRunMs(p, sat, ny)!!
        assertEquals("2026-03-08T03:30-04:00", Instant.ofEpochMilli(sun).atZone(ny).toOffsetDateTime().toString())
        assertEquals(24 * 3600_000L, sun - sat)                                   // wall-clock gap absorbed: still 24 h apart
        val p8 = item("mode" to "daily", "time" to "08:00")
        val satEight = ms("2026-03-07T08:00", ny)
        assertEquals(23 * 3600_000L, s.nextRunMs(p8, satEight, ny)!! - satEight)    // the DST day is 23 h long
        // fall back 2026-11-01: 25 h day
        val fallSat = ms("2026-10-31T08:00", ny)
        assertEquals(25 * 3600_000L, s.nextRunMs(p8, fallSat, ny)!! - fallSat)
    }

    @Test fun intervalAndOnce() {
        val s = ScheduleTrigger
        val t0 = ms("2026-09-25T10:00", utc)
        assertEquals(t0 + 30 * 60_000, s.nextRunMs(item("mode" to "interval"), t0, utc))
        assertEquals(t0 + 15 * 60_000, s.nextRunMs(item("mode" to "interval", "everyMinutes" to 5), t0, utc))   // 15-min floor
        assertEquals(ms("2026-12-24T18:00", utc), s.nextRunMs(item("mode" to "once", "at" to "2026-12-24T18:00"), t0, utc))
        assertNull(s.nextRunMs(item("mode" to "once", "at" to "2020-01-01T00:00"), t0, utc))
        assertNull(s.nextRunMs(item("mode" to "once"), t0, utc))
        // F15 premise: at/after the fire instant the slot is exhausted (once) or moves forward (daily); with KEEP a cold-start rearm
        // therefore never cancels the request that woke the process, and an exhausted `once` is left pending rather than unscheduled.
        assertNull(s.nextRunMs(item("mode" to "once", "at" to "2026-09-25T10:00"), t0, utc))
        assertEquals(ms("2026-09-26T08:00", utc), s.nextRunMs(item("mode" to "daily", "time" to "08:00"), ms("2026-09-25T08:00", utc) + 1_000, utc))
        val fired = s.fireItem(ms("2026-09-25T10:00", utc), t0, utc)
        assertEquals("2026-09-25T10:00Z", fired.str("scheduledFor"))
        assertEquals("Fri", fired.str("weekday"))
    }

    // ---- misc filters ----
    @Test fun shareShakeGeofenceWebhookFilters() {
        val text = item("text" to "see https://example.com/a", "url" to "https://example.com/a", "mimeType" to "text/plain")
        val img = item("uri" to "file:///x.jpg", "mimeType" to "image/jpeg")
        assertTrue(ShareTrigger.accepts(item("accept" to "url"), text)); assertFalse(ShareTrigger.accepts(item("accept" to "url"), img))
        assertTrue(ShareTrigger.accepts(item("accept" to "image"), img)); assertTrue(ShareTrigger.accepts(item("accept" to "file"), img))
        assertFalse(ShareTrigger.accepts(item("accept" to "text"), img)); assertTrue(ShareTrigger.accepts(EMPTY, img))
        assertEquals("https://example.com/a", ShareTrigger.urlIn("see https://example.com/a."))
        assertTrue(ShakeTrigger.accepts(EMPTY, item("gForce" to 2.3))); assertFalse(ShakeTrigger.accepts(item("sensitivity" to "low"), item("gForce" to 2.3)))
        assertTrue(GeofenceTrigger.accepts(EMPTY, item("entering" to true))); assertFalse(GeofenceTrigger.accepts(EMPTY, item("entering" to false)))
        assertTrue(GeofenceTrigger.accepts(item("event" to "either"), item("entering" to false)))
        assertTrue(WebhookTrigger.accepts(EMPTY, item("path" to "/hook/"))); assertFalse(WebhookTrigger.accepts(item("path" to "/x"), item("path" to "/hook")))
        assertTrue(PackageTrigger.accepts(item("event" to "installed", "packageRegex" to "^com\\.spotify"), item("packageName" to "com.spotify.music", "event" to "installed")))
        assertFalse(PackageTrigger.accepts(item("event" to "removed"), item("packageName" to "a", "event" to "installed")))
        assertTrue(NowPlayingTrigger.accepts(EMPTY, item("state" to "playing", "sourceApp" to "a")))
        assertFalse(NowPlayingTrigger.accepts(EMPTY, item("state" to "paused")))
        assertTrue(NowPlayingTrigger.accepts(item("onlyWhenPlaying" to false), item("state" to "paused")))
        assertFalse(NowPlayingTrigger.accepts(item("sourceApp" to "b"), item("state" to "playing", "sourceApp" to "a")))
    }

    @Test fun webhookTokenCompare() {
        assertTrue(WebhookTrigger.tokenOk("s3cret", "s3cret"))
        assertFalse(WebhookTrigger.tokenOk("s3cres", "s3cret"))
        assertFalse(WebhookTrigger.tokenOk("s3cret", "s3cret2"))
        assertFalse(WebhookTrigger.tokenOk(null, "s3cret"))      // header missing
        assertFalse(WebhookTrigger.tokenOk("x", ""))             // blank secret never authorizes
        assertFalse(WebhookTrigger.tokenOk("x", null))
        assertFalse(WebhookTrigger.tokenOk("", ""))
    }

    @Test fun chargerEventFilter() {
        val t = ChargerTrigger
        val on = item("connected" to true, "plugged" to "usb"); val off = item("connected" to false, "plugged" to "none")
        assertTrue(t.accepts(EMPTY, on)); assertTrue(t.accepts(EMPTY, off))                          // default either
        assertTrue(t.accepts(item("event" to "connected"), on)); assertFalse(t.accepts(item("event" to "connected"), off))
        assertTrue(t.accepts(item("event" to "disconnected"), off)); assertFalse(t.accepts(item("event" to "disconnected"), on))
        assertEquals("trig:wf:n:chg", t.workName(com.mob8n.core.TriggerInstance("wf", "n", EMPTY)))
        assertEquals(com.mob8n.core.Hosting.RUNTIME_RECEIVER, t.hosting)                           // K1: attach() owns the live path
    }

    @Test fun packageTriggerIsRuntimeHostedAndMapsBroadcasts() {
        assertEquals(com.mob8n.core.Hosting.RUNTIME_RECEIVER, PackageTrigger.hosting)   // F17: PACKAGE_* are not manifest-exempt
        assertTrue(com.mob8n.core.Gate.LiveHost in PackageTrigger.spec.gates)
        assertEquals("updated", PackageTrigger.eventKind("android.intent.action.PACKAGE_REPLACED", replacing = true))
        assertEquals("installed", PackageTrigger.eventKind("android.intent.action.PACKAGE_ADDED", replacing = false))
        assertNull(PackageTrigger.eventKind("android.intent.action.PACKAGE_ADDED", replacing = true))     // half of an update
        assertEquals("removed", PackageTrigger.eventKind("android.intent.action.PACKAGE_REMOVED", replacing = false))
        assertNull(PackageTrigger.eventKind("android.intent.action.PACKAGE_REMOVED", replacing = true))
        assertNull(PackageTrigger.eventKind("android.intent.action.SCREEN_ON", replacing = false))
    }

    @Test fun batterySystemLowEventShape() {
        // F18: the runtime LOW/OKAY receiver emits systemEvent (+ level when the sticky BATTERY_CHANGED is available, else null)
        val t = BatteryLevelTrigger
        assertTrue(t.accepts(item("mode" to "system_low"), item("level" to null, "charging" to false, "systemEvent" to "low")))
        assertFalse(t.accepts(item("mode" to "system_low"), item("level" to null, "charging" to false, "systemEvent" to "okay")))
        assertNull(t.toItems(item("mode" to "system_low"), item("level" to 5, "systemEvent" to "low")).single()["systemEvent"])
    }

    @Test fun webhookParsesRequest() {
        val raw = "POST /hook?a=1&b=x%20y HTTP/1.1\r\nHost: h\r\nX-Token: s3cret\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"k\":\"v\",\"n\":1}"
        val r = WebhookTrigger.parse(raw.byteInputStream())!!
        assertEquals("POST", r.method); assertEquals("/hook", r.path)
        assertEquals(mapOf("a" to "1", "b" to "x y"), r.query)
        assertEquals("s3cret", r.headers["x-token"])
        assertEquals("v", (r.body as JsonObject).str("k"))
        assertFalse(r.tooLarge)
        assertTrue(WebhookTrigger.parse("GET / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n".byteInputStream())!!.tooLarge)
        assertNull(WebhookTrigger.parse("".byteInputStream()))
    }

    // ---- v4 (DESIGN4 §8.1): workflows-as-tools params on trigger.called ----
    @Test fun calledTriggerToolParamsDefaultsAndGraphs() {
        val s = CalledByWorkflowTrigger.spec
        assertEquals(listOf("exposeAsTool", "toolDescription", "inputs"), s.params.map { it.key })
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), s.param("exposeAsTool")!!.default)
        assertTrue("every existing graph must stay valid", s.params.none { it.required })
        assertFalse(s.param("toolDescription")!!.templated)
        assertEquals(listOf("name", "type", "description", "required"), s.param("inputs")!!.rows.map { it.key })
        assertEquals(listOf("string", "number", "boolean", "json"), s.param("inputs")!!.rows[1].options)
        assertEquals("exposeAsTool", s.param("inputs")!!.visibleWhen!!.key)
        assertEquals(com.mob8n.core.ExecMode.LIST, s.mode); assertTrue(s.inputs.isEmpty())
        // run-time behaviour unchanged: the params are ignored and the caller's item flows in
        val ev = item("a" to 1)
        assertTrue(CalledByWorkflowTrigger.accepts(item("exposeAsTool" to true), ev))
        assertEquals(listOf(ev), CalledByWorkflowTrigger.toItems(item("exposeAsTool" to true), ev))
        // legacy and exposed graphs validate against the full six-lane catalog; no seeded graph has a called node (DESIGN4 §13.13)
        val catalog = com.mob8n.core.Catalog(listOf(TriggerNodes.all, com.mob8n.data.DataNodes.all, com.mob8n.logic.LogicNodes.all,
            com.mob8n.actions.ActionNodes.all, com.mob8n.ai.AiNodes.all, com.mob8n.apps.AppNodes.all))
        val legacy = com.mob8n.core.Graph(listOf(com.mob8n.core.NodeInstance("t", com.mob8n.core.TRIGGER_CALLED, "Called")))
        val exposed = com.mob8n.core.Graph(listOf(com.mob8n.core.NodeInstance("t", com.mob8n.core.TRIGGER_CALLED, "Called", item(
            "exposeAsTool" to true, "toolDescription" to "Fetches a page",
            "inputs" to listOf(item("name" to "url", "type" to "string", "description" to "Page URL", "required" to true))))))
        assertEquals(emptyList<String>(), legacy.validate(catalog))
        assertEquals(emptyList<String>(), exposed.validate(catalog))
        val seeds = com.mob8n.engine.Seed.workflows(0L)
        // DESIGN4 §13.13 held for seeds 1-8; DESIGN5 §8.4 seed-11 ("Mission dry-run gate") is the one seed exposed as a tool.
        assertEquals(listOf("seed-11"), seeds.filter { wf -> wf.graph.nodes.any { it.type == com.mob8n.core.TRIGGER_CALLED } }.map { it.id })
        assertTrue(seeds.flatMap { it.graph.validate(catalog) }.isEmpty())
    }

    // ---- v5 (DESIGN5 §3.3 / §9): System 1 triage params on trigger.notification_posted + trigger.share ----
    @Test fun triageParamsAreLastAndShareDecideColumns() {
        val tail = listOf(TriageParams.ENGINE, TriageParams.QUESTIONS, TriageParams.ON_ERROR, TriageParams.STATE)
        assertEquals(listOf("packageName", "titleRegex", "textRegex", "ignoreOngoing", "ignoreGroupSummary") + tail, NotificationPostedTrigger.spec.params.map { it.key })
        assertEquals(listOf("accept") + tail, ShareTrigger.spec.params.map { it.key })
        for (s in listOf(NotificationPostedTrigger.spec, ShareTrigger.spec)) {
            val eng = s.param(TriageParams.ENGINE)!!
            assertEquals(listOf("off", "default", "jev", "laya"), eng.options)
            assertEquals(kotlinx.serialization.json.JsonPrimitive("off"), eng.default)
            val q = s.param(TriageParams.QUESTIONS)!!
            assertEquals(com.mob8n.ai.DecideNode.QUESTION_COLUMNS.map { it.key } + "threshold", q.rows.map { it.key })
            assertEquals(listOf("noul", "score"), q.rows.first { it.key == "type" }.options)              // choice questions are not triage rows
            assertEquals(kotlinx.serialization.json.JsonPrimitive("noul"), q.rows.first { it.key == "type" }.default)
            assertEquals(kotlinx.serialization.json.JsonPrimitive(0.7), q.rows.last().default)
            assertEquals(com.mob8n.core.ParamKind.NUMBER, q.rows.last().kind)
            assertFalse(q.required)
            assertEquals(TriageParams.ENGINE, q.visibleWhen!!.key); assertEquals(TriageParams.ON.toList(), q.visibleWhen!!.equalsAny)
            val onErr = s.param(TriageParams.ON_ERROR)!!
            assertEquals(listOf("run", "drop"), onErr.options); assertEquals(kotlinx.serialization.json.JsonPrimitive("run"), onErr.default)
            assertEquals(TriageParams.ENGINE, onErr.visibleWhen!!.key)
            val st = s.param(TriageParams.STATE)!!
            assertTrue(st.templated); assertFalse(st.required); assertEquals(TriageParams.ENGINE, st.visibleWhen!!.key)
            assertTrue(q.help.contains("urgent enough to interrupt"))
            assertTrue("every existing graph must stay valid", s.params.filter { it.key in tail }.none { it.required && it.default == null })
        }
    }

    @Test fun triageParamsDoNotChangeAcceptsOrToItems() {
        val triage = item(TriageParams.ENGINE to "laya", TriageParams.ON_ERROR to "drop", TriageParams.STATE to "{{title}}",
            TriageParams.QUESTIONS to listOf(item("name" to "urgent", "type" to "noul", "instructions" to "Is this urgent?", "criteria" to "", "threshold" to 0.9)))
        val t = NotificationPostedTrigger
        // the existing fixtures re-run with triage set: identical verdicts and items (the engine lane filters, not the trigger)
        assertTrue(t.accepts(triage, notif))
        assertFalse(t.accepts(triage.add("packageName" to "com.telegram"), notif))
        assertTrue(t.accepts(triage.add("textRegex" to "please"), notif)); assertFalse(t.accepts(triage.add("textRegex" to "later"), notif))
        assertFalse(t.accepts(triage, notif.plus("ongoing" to true)))
        assertEquals(t.toItems(EMPTY, notif), t.toItems(triage, notif))
        assertNull(t.toItems(triage, notif).single()["triage"])
        val text = item("text" to "see https://example.com/a", "url" to "https://example.com/a", "mimeType" to "text/plain")
        val img = item("uri" to "file:///x.jpg", "mimeType" to "image/jpeg")
        assertTrue(ShareTrigger.accepts(triage, img)); assertTrue(ShareTrigger.accepts(triage.add("accept" to "url"), text))
        assertFalse(ShareTrigger.accepts(triage.add("accept" to "url"), img))
        assertEquals(listOf(img), ShareTrigger.toItems(triage, img))
        // the triage rows validate against the spec (ROWS -> RowsEditor, zero ui code) and a graph with them stays valid
        assertNull(t.spec.param(TriageParams.QUESTIONS)!!.validate(triage[TriageParams.QUESTIONS]))
        assertNull(t.spec.param(TriageParams.STATE)!!.validate(triage[TriageParams.STATE]))
        assertEquals(TriageParams.NOTIFICATION_STATE, (t.spec.param(TriageParams.STATE)!!.default as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(TriageParams.SHARE_STATE, (ShareTrigger.spec.param(TriageParams.STATE)!!.default as kotlinx.serialization.json.JsonPrimitive).content)
        assertNotNull(t.spec.param(TriageParams.ENGINE)!!.validate(kotlinx.serialization.json.JsonPrimitive("device")))   // no on-device engine in v5 (D8)
    }

    private fun JsonObject.plus(p: Pair<String, Any?>): JsonObject = add(p)
}
