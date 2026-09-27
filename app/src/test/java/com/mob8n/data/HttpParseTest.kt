package com.mob8n.data

import com.mob8n.core.Catalog
import com.mob8n.core.EMPTY
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Hooks
import com.mob8n.core.InMemoryPersistence
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeInstance
import com.mob8n.core.Workflow
import com.mob8n.core.asText
import com.mob8n.core.item
import com.mob8n.core.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.time.ZonedDateTime

class HttpParseTest {
    private val jsonHeaders = mapOf("Content-Type" to listOf("application/json; charset=utf-8"), "X-Req-Id" to listOf("a", "b"))

    @Test fun jsonAutoParseByContentType() {
        val r = Http.result(200, jsonHeaders, """{"a":1,"b":[1,2]}""", "https://x/y", true)
        assertEquals(200, r["status"]!!.jsonPrimitive.content.toInt())
        assertTrue(r["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(r["body"] is JsonObject)
        assertEquals("1", r["body"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        assertTrue(r["json"] is JsonObject)
        assertEquals("application/json; charset=utf-8", r.str("contentType"))
        // headers lower-cased and multi-valued joined
        assertEquals("a, b", r["headers"]!!.jsonObject["x-req-id"]!!.jsonPrimitive.content)
        assertEquals("https://x/y", r.str("url"))
    }

    @Test fun jsonAutoParseByShapeWithoutContentType() {
        val r = Http.result(200, mapOf("Content-Type" to listOf("text/plain")), """[{"k":"v"}]""", "https://x", true)
        assertTrue(r["body"] is JsonArray)
        assertEquals("v", (r["body"] as JsonArray)[0].jsonObject["k"]!!.jsonPrimitive.content)
    }

    @Test fun nonJsonStaysString() {
        val r = Http.result(200, mapOf("Content-Type" to listOf("text/html")), "<html>hi</html>", "https://x", true)
        assertEquals("<html>hi</html>", r["body"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, r["json"])
        // declared json but unparseable -> string body, null json
        val bad = Http.result(200, jsonHeaders, "{not json", "https://x", true)
        assertEquals("{not json", bad["body"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, bad["json"])
    }

    @Test fun non2xxThrowsWhenFailOnHttpError() {
        val e = assertThrows(NodeException::class.java) { Http.result(404, jsonHeaders, """{"error":"nope"}""", "https://x/missing", true) }
        assertTrue(e.message!!.contains("404"))
        assertTrue(e.message!!.contains("https://x/missing"))
    }

    @Test fun non2xxReturnedWhenAllowed() {
        val r = Http.result(503, mapOf("Content-Type" to listOf("application/problem+json")), """{"title":"down"}""", "https://x", false)
        assertEquals("503", r["status"]!!.jsonPrimitive.content)
        assertFalse(r["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("down", r["body"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun headerRows() {
        val m = Http.headerMap(listOf(
            item("name" to "Accept", "value" to "text/plain"),
            item("name" to "  ", "value" to "ignored"),
            item("name" to "X-Token", "value" to "abc"),
        ))
        assertEquals(linkedMapOf("Accept" to "text/plain", "X-Token" to "abc"), m)
        assertThrows(NodeException::class.java) { Http.headerMap(listOf(item("name" to "Bad Name", "value" to "x"))) }
        assertThrows(NodeException::class.java) { Http.headerMap(listOf(item("name" to "X", "value" to "a\r\nInjected: y"))) }
    }

    @Test fun responseCap() {
        val small = Http.readCapped(ByteArrayInputStream(ByteArray(1000) { 7 }), 1000)
        assertEquals(1000, small.size)
        assertThrows(NodeException::class.java) { Http.readCapped(ByteArrayInputStream(ByteArray(1001)), 1000) }
        assertEquals(5 * 1024 * 1024, Http.MAX_BODY)
    }

    @Test fun charsetAndForm() {
        assertEquals(Charsets.ISO_8859_1, Http.charsetOf("text/html; charset=ISO-8859-1"))
        assertEquals(Charsets.UTF_8, Http.charsetOf("text/html"))
        assertEquals(Charsets.UTF_8, Http.charsetOf("text/html; charset=bogus"))
        assertEquals("a=1&b=x+y%26z", Http.formEncode("""{"a":1,"b":"x y&z"}"""))
        assertEquals("raw=already", Http.formEncode("raw=already"))
    }

    @Test fun parseJsonEdgeCases() {
        assertNull(Http.parseJson("", "application/json"))
        assertNull(Http.parseJson("hello", null))
        assertEquals("1", Http.parseJson(" {\"a\":1} ", null)!!.jsonObject["a"]!!.asText())
    }

    // ---- F46: allowHttp cannot enable cleartext (manifest usesCleartextTraffic=false); both branches must be honest ----
    private fun httpCtx(params: JsonObject): ExecutionContext {
        val input = NodeInput(listOf(EMPTY))
        return ExecutionContext(
            runId = "run-1", workflow = Workflow("wf-1", "Test WF"),
            instance = NodeInstance("n1", HttpNode.spec.id, HttpNode.spec.name, params),
            spec = HttpNode.spec, input = input, itemIndex = 0, upstream = emptyMap(), vars = emptyMap(),
            persistence = InMemoryPersistence(), catalog = Catalog(listOf(listOf(HttpNode))), hooks = Hooks(), android = null,
            zone = ZoneId.of("UTC"), nowMs = { 0L }, logger = {},
            runWorkflow = { _, items -> items }, runNode = { _, _, _ -> throw NodeException("runNode not faked") },
        )
    }

    @Test fun plainHttpIsRejectedHonestly() {
        val url = "http://192.168.1.20/api"
        // allowHttp off -> points the user at the switch
        val off = assertThrows(NodeException::class.java) {
            runBlocking { HttpNode.execute(httpCtx(item("url" to url, "allowHttp" to false)), NodeInput(listOf(EMPTY))) }
        }
        assertTrue(off.message!!.contains("enable 'Allow plain http://'"))
        // allowHttp on -> explicit policy error (JVM: NetworkSecurityPolicy stub -> not permitted; no socket is opened)
        val on = assertThrows(NodeException::class.java) {
            runBlocking { HttpNode.execute(httpCtx(item("url" to url, "allowHttp" to true)), NodeInput(listOf(EMPTY))) }
        }
        assertTrue(on.message!!.contains("network policy"))
        assertTrue(on.message!!.contains("192.168.1.20"))
        // non-http(s) schemes are still refused up front
        val ftp = assertThrows(NodeException::class.java) {
            runBlocking { HttpNode.execute(httpCtx(item("url" to "ftp://x/y")), NodeInput(listOf(EMPTY))) }
        }
        assertTrue(ftp.message!!.contains("Only http(s)"))
    }

    // ---- small pure helpers from the other files ----
    @Test fun variableTyped() {
        assertEquals(JsonPrimitive(42), VariableNode.typed("42"))
        assertEquals(JsonPrimitive(true), VariableNode.typed("true"))
        assertTrue(VariableNode.typed("""{"a":1}""") is JsonObject)
        assertEquals(JsonPrimitive("hello world"), VariableNode.typed("hello world"))
        assertEquals(JsonPrimitive("{oops"), VariableNode.typed("{oops"))
        assertEquals(JsonPrimitive(""), VariableNode.typed("  "))
    }

    @Test fun dateTimeParseBase() {
        val z = ZoneId.of("Europe/Berlin")
        assertEquals(ZonedDateTime.parse("2025-01-31T08:00+01:00[Europe/Berlin]"), DateTimeNode.parseBase("2025-01-31T08:00", 0L, z))
        assertEquals(1_700_000_000_000L, DateTimeNode.parseBase("1700000000000", 0L, z).toInstant().toEpochMilli())
        assertEquals(1_700_000_000_000L, DateTimeNode.parseBase("1700000000", 0L, z).toInstant().toEpochMilli())   // F48: seconds if < 1e10
        assertThrows(NodeException::class.java) { DateTimeNode.parseBase("2025", 0L, z) }                          // too short to be an epoch
        assertEquals(123L, DateTimeNode.parseBase("now", 123L, z).toInstant().toEpochMilli())
        assertEquals(8, DateTimeNode.parseBase("2025-01-31 08:30", 0L, z).hour)
        assertEquals(0, DateTimeNode.parseBase("2025-01-31", 0L, z).hour)
        assertEquals(9, DateTimeNode.parseBase("2025-06-01T07:00:00Z", 0L, z).hour)
        assertThrows(NodeException::class.java) { DateTimeNode.parseBase("yesterday-ish", 0L, z) }
    }

    @Test fun readFileHelpers() {
        assertEquals(listOf("a", "b"), ReadFileNode.lines("a\nb\n"))
        assertEquals(listOf("a", "", "b"), ReadFileNode.lines("a\n\nb"))
        assertEquals(2, ReadFileNode.parseJsonItems("""[{"x":1},5]""").size)
        assertEquals("5", ReadFileNode.parseJsonItems("""[{"x":1},5]""")[1]["value"]!!.jsonPrimitive.content)
        assertThrows(NodeException::class.java) { ReadFileNode.parseJsonItems("nope{") }
        val dir = kotlin.io.path.createTempDirectory("mob8n").toFile()
        try {
            assertEquals(dir.canonicalFile.resolve("notes.txt"), ReadFileNode.safeFile(dir, "notes.txt"))
            assertThrows(NodeException::class.java) { ReadFileNode.safeFile(dir, "../secrets.xml") }
            assertThrows(NodeException::class.java) { ReadFileNode.safeFile(dir, "sub/../../x") }
            // an absolute "name" is resolved UNDER the app dir by java.io.File, so it stays contained
            assertTrue(ReadFileNode.safeFile(dir, "/etc/passwd").path.startsWith(dir.canonicalPath))
            assertThrows(NodeException::class.java) { ReadFileNode.safeFile(dir, "  ") }
        } finally { dir.deleteRecursively() }
    }
}
