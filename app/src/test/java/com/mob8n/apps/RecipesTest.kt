package com.mob8n.apps

import com.mob8n.core.NodeException
import com.mob8n.core.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class RecipesTest {
    private val PLACEHOLDER = Regex("\\{([a-zA-Z]+)}")
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private inline fun expectError(contains: String, block: () -> Unit) {
        try { block(); fail("expected NodeException") } catch (e: NodeException) { assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains)) }
    }

    @Test fun tableShape() {
        assertTrue(Recipes.ALL.size >= 25)
        assertEquals(38, Recipes.ALL.size)   // §7.2 table as written (the doc's own count says 37)
        val ids = Recipes.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (r in Recipes.ALL) {
            assertTrue(r.id, r.id.matches(Regex("[a-z][a-z0-9_]*")))
            val refs = listOfNotNull(r.data, r.web, r.mime).flatMap { t -> PLACEHOLDER.findAll(t).map { it.groupValues[1] }.toList() } +
                r.extras.values.flatMap { t -> PLACEHOLDER.findAll(t).map { it.groupValues[1] }.toList() }
            val allowed = r.keys + listOf("beginTime", "endTime")   // derived calendar extras
            for (p in refs) assertTrue("${r.id}: {$p} not in params ${r.params}", p in allowed)
            assertTrue("${r.id}: required ⊆ keys", r.keys.containsAll(r.required))
            assertTrue("${r.id}: pkg or packageName param or package-less", r.pkg != null || r.usesPackageParam || r.id in setOf("camera_open", "clock_show_alarms", "calendar_new_event"))
        }
        assertTrue(Recipes.byId("telegram_share")!!.data!!.startsWith("tg://msg_url"))
        assertTrue(Recipes.byId("instagram_add_to_story")!!.required.containsAll(listOf("fileUri", "facebookAppId")))
        assertTrue(Recipes.byId("instagram_add_to_story")!!.verified)
        assertFalse(Recipes.byId("uber_ride")!!.verified)
    }

    @Test fun encodeAndPhone() {
        assertEquals("a%20b%26c", Recipes.encode("a b&c"))
        assertEquals("caf%C3%A9_-.~", Recipes.encode("café_-.~"))
        assertEquals("491701", Recipes.normalizePhone("+49 (0)170-1"))
        assertEquals("15551234567", Recipes.normalizePhone("+1 555-123-4567"))
    }

    @Test fun paramsUsing() {
        val q = Recipes.paramsUsing("query").toSet()
        assertEquals(Recipes.ALL.filter { "query" in it.keys }.map { it.id }.toSet(), q)
        assertTrue(q.containsAll(listOf("youtube_search", "ytmusic_search", "ytmusic_play_search", "spotify_search", "spotify_play_search", "maps_search", "playstore_search", "generic_play_search")))
        assertTrue(Recipes.paramsUsing("packageName").toSet().containsAll(listOf("generic_share_text", "generic_share_file", "generic_open_url", "generic_play_search", "generic_launch", "playstore_open")))
    }

    /** Every row fills with sample args into a valid action + scheme-bearing data (or a mime / launch), no placeholder left. */
    @Test fun everyRowFillsWithSamples() {
        val sample = mapOf("url" to "https://example.com/x?y=1", "fileUri" to "content://com.mob8n.files/cache/a.jpg", "phone" to "+1 (555) 123", "uri" to "spotify:track:abc",
            "id" to "dQw4w9WgXcQ", "mode" to "walking", "to" to "a@example.com", "facebookAppId" to "123", "start" to "2026-09-25T10:00", "durationMinutes" to "30", "mimeType" to "video/mp4",
            "packageName" to "com.example.app")
        for (r in Recipes.ALL) {
            val args = r.keys.associateWith { sample[it] ?: "hello world" }
            val f = Recipes.fill(r, args, 1_700_000_000_000L, ZoneId.of("UTC"))
            assertTrue(r.id, f.action.isNotBlank())
            assertTrue("${r.id}: action ${f.action}", f.action.startsWith("android.") || f.action == Recipes.ADD_TO_STORY || f.action == Recipes.LAUNCH)
            for (s in listOfNotNull(f.data, f.web, f.mime) + f.extras.values) assertFalse("${r.id}: unfilled placeholder in $s", PLACEHOLDER.containsMatchIn(s))
            if (f.data != null && !f.fileData) assertTrue("${r.id}: data needs a scheme: ${f.data}", SCHEME.containsMatchIn(f.data!!))
            f.web?.let { assertTrue("${r.id}: web must be https: $it", it.startsWith("https://")) }
            if (f.action == Recipes.VIEW) assertNotNull("${r.id}: VIEW needs data", f.data)
            if (f.action == Recipes.SEND) assertNotNull("${r.id}: SEND needs a mime", f.mime)
            if (r.pkg != null) assertEquals(r.pkg, f.pkg)
            if (r.usesPackageParam) assertEquals("com.example.app", f.pkg)
            if (f.data != null && !f.fileData && r.data != "{url}" && r.data != "{uri}") assertFalse("${r.id}: raw space in data ${f.data}", f.data!!.contains(' '))
        }
    }

    @Test fun fillEncodesAndValidates() {
        val yt = Recipes.fill(Recipes.byId("youtube_search")!!, mapOf("query" to "lofi beats & chill"))
        assertEquals("https://www.youtube.com/results?search_query=lofi%20beats%20%26%20chill", yt.data)
        assertEquals("com.google.android.youtube", yt.pkg)
        expectError("'query' is required") { Recipes.fill(Recipes.byId("youtube_search")!!, mapOf("query" to " ")) }

        val wa = Recipes.fill(Recipes.byId("whatsapp_message")!!, mapOf("phone" to "+49 (0)170-1", "text" to "hi there"))
        assertEquals("https://wa.me/491701?text=hi%20there", wa.data)
        assertEquals("https://wa.me/?text=hi", Recipes.fill(Recipes.byId("whatsapp_message")!!, mapOf("text" to "hi")).data)
        expectError("needs a phone or a text") { Recipes.fill(Recipes.byId("whatsapp_message")!!, emptyMap()) }
        expectError("needs a text or a url") { Recipes.fill(Recipes.byId("x_post")!!, emptyMap()) }

        val tg = Recipes.fill(Recipes.byId("telegram_share")!!, mapOf("url" to "https://a.b/c?d=1"))
        assertEquals("tg://msg_url?url=https%3A%2F%2Fa.b%2Fc%3Fd%3D1&text=", tg.data)
        assertEquals("https://t.me/share/url?url=https%3A%2F%2Fa.b%2Fc%3Fd%3D1&text=", tg.web)

        val nav = Recipes.fill(Recipes.byId("maps_navigate")!!, mapOf("destination" to "Berlin Hbf"))
        assertEquals("google.navigation:q=Berlin%20Hbf&mode=d", nav.data)
        assertEquals("google.navigation:q=X&mode=w", Recipes.fill(Recipes.byId("maps_navigate")!!, mapOf("destination" to "X", "mode" to "walking")).data)
        expectError("mode must be") { Recipes.fill(Recipes.byId("maps_navigate")!!, mapOf("destination" to "X", "mode" to "flying")) }
        assertEquals("https://example.com/a b?x=1", Recipes.fill(Recipes.byId("chrome_open")!!, mapOf("url" to "https://example.com/a b?x=1")).data)   // whole-URI placeholder: raw
        expectError("full URI with a scheme") { Recipes.fill(Recipes.byId("generic_open_url")!!, mapOf("packageName" to "com.x", "url" to "example.com")) }
    }

    @Test fun extrasRawAndOptionalDropped() {
        val g = Recipes.fill(Recipes.byId("gmail_compose")!!, mapOf("to" to "a@b.c", "text" to "hello world"))
        assertEquals("mailto:a%40b.c", g.data)
        assertEquals("hello world", g.extras[Recipes.EXTRA_TEXT])
        assertNull(g.extras[Recipes.EXTRA_SUBJECT])   // blank optional extra dropped
        val share = Recipes.fill(Recipes.byId("generic_share_text")!!, mapOf("packageName" to "com.x", "text" to "t"))
        assertEquals("com.x", share.pkg); assertEquals("text/plain", share.mime); assertEquals(mapOf(Recipes.EXTRA_TEXT to "t"), share.extras)
        val file = Recipes.fill(Recipes.byId("generic_share_file")!!, mapOf("packageName" to "com.x", "fileUri" to "content://c/1"))
        assertEquals("image/*", file.mime); assertEquals("content://c/1", file.extras[Recipes.EXTRA_STREAM])
        val ytm = Recipes.fill(Recipes.byId("ytmusic_play_search")!!, mapOf("query" to "jazz"))
        assertEquals("jazz", ytm.extras["query"]); assertEquals("vnd.android.cursor.item/*", ytm.extras[Recipes.EXTRA_MEDIA_FOCUS])
    }

    @Test fun instagramStoryAndSpotify() {
        val story = Recipes.fill(Recipes.byId("instagram_add_to_story")!!, mapOf("fileUri" to "content://c/clip.MP4", "facebookAppId" to "42", "topColor" to "#ff0000"))
        assertTrue(story.fileData); assertEquals("content://c/clip.MP4", story.data); assertEquals("video/*", story.mime)
        assertEquals("42", story.extras["source_application"]); assertEquals("#ff0000", story.extras["top_background_color"]); assertNull(story.extras["bottom_background_color"])
        assertEquals("image/*", Recipes.fill(Recipes.byId("instagram_add_to_story")!!, mapOf("fileUri" to "content://c/p.jpg", "facebookAppId" to "42")).mime)
        expectError("'facebookAppId' is required") { Recipes.fill(Recipes.byId("instagram_add_to_story")!!, mapOf("fileUri" to "content://c/p.jpg")) }
        expectError("content://") { Recipes.fill(Recipes.byId("instagram_share_photo")!!, mapOf("fileUri" to "https://x/y.jpg")) }

        val sp = Recipes.fill(Recipes.byId("spotify_play_uri")!!, mapOf("uri" to "spotify:track:1"))
        assertEquals("spotify:track:1", sp.data); assertNull(sp.web)
        val sp2 = Recipes.fill(Recipes.byId("spotify_play_uri")!!, mapOf("uri" to "https://open.spotify.com/track/1"))
        assertEquals("https://open.spotify.com/track/1", sp2.web)
    }

    @Test fun calendarTimes() {
        val f = Recipes.fill(Recipes.byId("calendar_new_event")!!, mapOf("title" to "Standup", "start" to "1700000000", "durationMinutes" to "15"), 0L, ZoneId.of("UTC"))
        assertEquals("1700000000000", f.extras["beginTime"]); assertEquals("1700000900000", f.extras["endTime"]); assertEquals("Standup", f.extras["title"])
        assertEquals("123", Recipes.fill(Recipes.byId("calendar_new_event")!!, mapOf("title" to "T"), 123L, ZoneId.of("UTC")).extras["beginTime"])
        expectError("not an ISO") { Recipes.fill(Recipes.byId("calendar_new_event")!!, mapOf("title" to "T", "start" to "yesterday")) }
    }

    @Test fun appActionSpecParamsMirrorRecipes() {
        val spec = AppActionNode.spec
        val recipe = spec.param("recipe")!!
        assertEquals(ParamKind.ENUM, recipe.kind); assertEquals(Recipes.ALL.map { it.id }, recipe.options); assertEquals("generic_share_text", recipe.default!!.toString().trim('"'))
        val pkg = spec.param("packageName")!!
        assertEquals(ParamKind.APP, pkg.kind); assertEquals(Recipes.paramsUsing("packageName").toList(), pkg.visibleWhen!!.equalsAny); assertEquals("recipe", pkg.visibleWhen!!.key)
        val distinct = Recipes.ALL.flatMap { it.keys }.distinct()
        for (k in distinct) {
            val p = spec.param(k) ?: throw AssertionError("missing param $k")
            assertEquals("$k visibleWhen", Recipes.paramsUsing(k).toList(), p.visibleWhen!!.equalsAny)
            if (k != "packageName") assertEquals(ParamKind.TEXT, p.kind)
        }
        assertNull(spec.param("extras")!!.visibleWhen); assertNull(spec.param("fallbackNotification")!!.visibleWhen); assertNull(recipe.visibleWhen)
        assertEquals(distinct.size + 3, spec.params.size)   // recipe + one per distinct key + extras + fallbackNotification
        spec.toolDef()
    }
}
