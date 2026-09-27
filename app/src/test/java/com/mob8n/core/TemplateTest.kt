package com.mob8n.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TemplateTest {
    private val it0 = item("title" to "Song", "meta" to mapOf("artist" to "Band", "tags" to listOf("a", "b")), "n" to 3, "ok" to true,
        "items" to listOf(mapOf("name" to "first"), mapOf("name" to "second")), "nul" to null)
    private val upstream = mapOf("Now Playing" to listOf(item("artist" to "Up1"), item("artist" to "Up2")), "HTTP" to listOf(item("body" to mapOf("x" to 42))))
    private val vars = mapOf("count" to JsonPrimitive(7), "cfg" to item("k" to "v"))
    // 2024-01-02T03:04:05Z
    private val scope = Scope(it0, 2, 5, upstream, vars, 1704164645000L, ZoneId.of("UTC"), "run-1", "My WF")

    @Test fun fieldsDottedAndIndexedPaths() {
        assertEquals("Song by Band", Template.render("{{title}} by {{meta.artist}}", scope))
        assertEquals("b", Template.render("{{meta.tags[1]}}", scope))
        assertEquals("b", Template.render("{{meta.tags.1}}", scope))
        assertEquals("first/second", Template.render("{{items[0].name}}/{{items[1].name}}", scope))
        assertEquals("", Template.render("{{missing.deep}}", scope))
        assertEquals("plain", Template.render("plain", scope))
    }

    @Test fun jsonAndNodeAndVars() {
        assertEquals(JSON.encodeToString(JsonObject.serializer(), it0), Template.render("{{\$json}}", scope))
        assertEquals("Song", Template.render("{{\$json.title}}", scope))
        assertEquals("Up1", Template.render("{{\$node.Now Playing.artist}}", scope))
        assertEquals("42", Template.render("{{\$node.HTTP.body.x}}", scope))
        assertEquals(JsonArray(upstream["Now Playing"]!!), Template.renderJson("{{\$node.Now Playing.all}}", scope))
        assertEquals("Up2", Template.render("{{\$node.Now Playing.all[1].artist}}", scope))
        assertEquals("", Template.render("{{\$node.Nope.x}}", scope))
        assertEquals("7", Template.render("{{\$vars.count}}", scope))
        assertEquals("v", Template.render("{{\$vars.cfg.k}}", scope))
    }

    /** F57: one shared segment splitter; [n] indexes work after every scoped prefix. */
    @Test fun indexedPathsAfterScopedPrefixes() {
        assertEquals(listOf("a", "b", "0", "c"), segments("a.b[0].c"))
        assertEquals(emptyList<String>(), segments(""))
        assertEquals("second", Template.render("{{\$json.items[1].name}}", scope))
        val s = scope.copy(vars = vars + ("list" to JsonArray(listOf(JsonPrimitive("z0"), JsonPrimitive("z1")))),
            upstream = upstream + ("HTTP" to listOf(item("body" to mapOf("items" to listOf(mapOf("x" to 9)))))))
        assertEquals("z0", Template.render("{{\$vars.list[0]}}", s))
        assertEquals("9", Template.render("{{\$node.HTTP.body.items[0].x}}", s))
        assertEquals("Up2", Template.render("{{\$node.Now Playing.all[1].artist}}", s))
    }

    @Test fun globals() {
        assertEquals("2024-01-02", Template.render("{{\$date}}", scope))
        assertEquals("03:04", Template.render("{{\$time}}", scope))
        assertEquals("1704164645000", Template.render("{{\$epoch}}", scope))
        assertTrue(Template.render("{{\$now}}", scope).startsWith("2024-01-02T03:04:05Z"))
        assertEquals("2 5 run-1 My WF", Template.render("{{\$index}} {{\$count}} {{\$runId}} {{\$workflow}}", scope))
    }

    @Test fun defaults() {
        assertEquals("x", Template.render("{{missing ?? \"x\"}}", scope))
        assertEquals("0", Template.render("{{missing ?? 0}}", scope))
        assertEquals("bare text", Template.render("{{missing ?? bare text}}", scope))
        assertEquals("Song", Template.render("{{title ?? \"x\"}}", scope))
        assertEquals("d", Template.render("{{nul ?? d}}", scope))                 // JsonNull counts as missing
        assertEquals(JsonPrimitive(0), Template.renderJson("{{missing ?? 0}}", scope))
        assertEquals(JsonNull, Template.eval("missing", scope))
    }

    @Test fun renderJsonPreservesTypesOnlyForWholeExpressions() {
        assertEquals(JsonPrimitive(3), Template.renderJson("{{n}}", scope))
        assertEquals(JsonPrimitive(true), Template.renderJson(" {{ok}} ", scope))
        assertEquals(it0["meta"], Template.renderJson("{{meta}}", scope))
        assertEquals(JsonPrimitive("n=3"), Template.renderJson("n={{n}}", scope))
        assertEquals(JsonPrimitive("3 and 3!"), Template.renderJson("{{n}} and {{n}}!", scope))
        // K3: two adjacent expressions used to be matched WHOLE by the lazy regex (group "n}} {{title") and yield JsonNull
        assertEquals(JsonPrimitive("3 Song"), Template.renderJson("{{n}} {{title}}", scope))
        assertEquals(JsonPrimitive("3Song"), Template.renderJson("{{n}}{{title}}", scope))
        assertEquals(JsonPrimitive("x 3"), Template.renderJson("x {{n}}", scope))
        assertEquals(JsonPrimitive("literal"), Template.renderJson("literal", scope))
        assertTrue(Template.hasTemplate("a {{b}}")); assertTrue(!Template.hasTemplate("a {b}"))
    }

    @Test fun keysOfFlattensOneLevel() {
        assertEquals(listOf("title", "meta", "meta.artist", "meta.tags", "n", "ok", "items", "nul"), Template.keysOf(it0))
        assertEquals(emptyList<String>(), Template.keysOf(null))
    }
}
