package com.mob8n.actions

import com.mob8n.core.Catalog
import com.mob8n.core.NodeKind
import com.mob8n.core.PlaylistEntry
import com.mob8n.core.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    private val a = PlaylistEntry(playlist = "Auto Liked", title = "Song A", artist = "Band", addedAt = 1L, mediaStoreId = 7L)
    private val b = PlaylistEntry(playlist = "Auto Liked", title = "Solo", artist = null, addedAt = 2L)

    @Test fun rendersExtendedM3uWithPathsWhenKnown() {
        val text = M3u.render("Auto Liked", listOf(a, b), mapOf(7L to "/storage/emulated/0/Music/a.mp3"))
        assertEquals("#EXTM3U\n#PLAYLIST:Auto Liked\n#EXTINF:-1,Band - Song A\n/storage/emulated/0/Music/a.mp3\n#EXTINF:-1,Solo\nSolo\n", text)
    }

    @Test fun appendIsIdempotentAndStartsFreshFiles() {
        val fresh = M3u.append(null, "Auto Liked", a, null)
        assertEquals("#EXTM3U\n#PLAYLIST:Auto Liked\n#EXTINF:-1,Band - Song A\nBand - Song A\n", fresh)
        val twice = M3u.append(fresh, "Auto Liked", a, null)
        assertEquals(fresh, twice)
        val withB = M3u.append(fresh.trimEnd(), "Auto Liked", b, null)
        assertTrue(withB.endsWith("#EXTINF:-1,Solo\nSolo\n"))
        assertEquals(2, withB.lines().count { it.startsWith("#EXTINF") })
    }

    @Test fun fileNameIsSanitized() {
        assertEquals("Road_Trip 2024_.m3u", M3u.fileName("Road/Trip 2024?"))
        assertEquals("untitled.m3u", M3u.fileName("   "))
    }

    @Test fun csvEscapesQuotesCommasAndNewlines() {
        assertEquals("plain", Csv.escape("plain"))
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.escape("two\nlines"))
    }

    @Test fun csvHeaderIsUnionOfKeysInFirstSeenOrderOrExplicitFields() {
        val items = listOf(item("title" to "A", "artist" to "X"), item("artist" to "Y", "album" to "Z"))
        assertEquals(listOf("title", "artist", "album"), Csv.header(items, emptyList()))
        assertEquals(listOf("album", "title"), Csv.header(items, listOf("album", "title")))
        val csv = Csv.render(items, listOf("title", "album"), includeHeader = true)
        assertEquals("title,album\nA,\n,Z\n", csv)
        assertEquals("A,\n,Z\n", Csv.render(items, listOf("title", "album"), includeHeader = false))
    }

    @Test fun csvCellsRenderNestedValuesAsJson() {
        val items = listOf(item("n" to 3, "ok" to true, "tags" to listOf("a", "b")))
        assertEquals("n,ok,tags\n3,true,\"[\"\"a\"\",\"\"b\"\"]\"\n", Csv.render(items, emptyList(), includeHeader = true))
    }

    @Test fun catalogHasAll35ActionNodesWithDesignIdsAndStrictToolDefs() {
        val expected = listOf(
            "add_to_playlist", "media_control", "notify", "cancel_notification", "reply_notification", "launch_app", "open_url", "send_intent", "share",
            "dial", "compose_sms", "compose_email", "navigate", "add_calendar_event", "add_contact", "set_alarm", "tts", "play_sound", "vibrate",
            "clipboard_set", "toast", "ringer_dnd", "display_settings", "set_ringtone", "settings_panel", "flashlight", "wallpaper", "write_file",
            "download", "save_note", "schedule_run", "toggle_workflow", "log", "knowledge_add", "knowledge_remove",
        ).map { "action.$it" }
        val catalog = Catalog(listOf(ActionNodes.all))
        assertEquals(35, ActionNodes.all.size)
        assertEquals(expected.toSet(), ActionNodes.all.map { it.spec.id }.toSet())
        assertTrue(ActionNodes.all.all { it.spec.kind == NodeKind.ACTION })
        val notTools = setOf("action.send_intent", "action.toggle_workflow", "action.reply_notification", "action.set_ringtone", "action.knowledge_remove")
        for (n in ActionNodes.all) {
            assertEquals(n.spec.id, n.spec.id !in notTools, n.spec.agentTool)
            if (n.spec.agentTool) assertTrue(n.spec.toolDef()["strict"].toString() == "true")
            assertTrue(n.spec.id, n.spec.validate(com.mob8n.core.EMPTY).all { it.contains("required") })
        }
        assertEquals(30, catalog.agentTools().size)
        assertFalse(catalog.spec("action.reply_notification")!!.agentTool)
        assertTrue(catalog.spec("action.reply_notification")!!.optional)
    }

    @Test fun calendarStartParsing() {
        val zone = java.time.ZoneId.of("UTC")
        assertEquals(1_700_000_000_000L, AddCalendarEventNode.parseStart("1700000000000", zone, 0))
        assertEquals(1_700_000_000_000L, AddCalendarEventNode.parseStart("1700000000", zone, 0))
        assertEquals(0L, AddCalendarEventNode.parseStart("1970-01-01T00:00:00Z", zone, 5))
        assertEquals(0L, AddCalendarEventNode.parseStart("1970-01-01T00:00", zone, 5))
        assertEquals(9 * 3_600_000L, AddCalendarEventNode.parseStart("1970-01-01", zone, 5))
        assertEquals(42L, AddCalendarEventNode.parseStart("", zone, 42))
        org.junit.Assert.assertThrows(com.mob8n.core.NodeException::class.java) { AddCalendarEventNode.parseStart("2025", zone, 0) }   // F48
    }
}
