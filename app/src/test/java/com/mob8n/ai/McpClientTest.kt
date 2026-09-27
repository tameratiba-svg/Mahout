package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpClientTest {
    private fun res(name: String): String = javaClass.getResourceAsStream("/mcp/$name")!!.bufferedReader().readText()
    private fun obj(s: String) = JSON.parseToJsonElement(s) as JsonObject
    private fun server(name: String = "GitHub Tools", auth: McpAuth = McpAuth.NONE, protocol: String? = null) =
        McpServer("srv" + java.util.UUID.randomUUID(), name, "https://mcp.example.com/mcp", auth = auth, protocol = protocol)

    // ---------------------------------------------------------------- framing

    @Test fun messagesParseJsonAndSseInBothLineEndings() {
        val json = McpClient.messages("application/json", res("tools_list.json"))
        assertEquals(1, json.size); assertEquals("1", json[0]["id"].asTextOrNull())
        val lf = res("tools_call.sse")
        for (body in listOf(lf, lf.replace("\n", "\r\n"))) {
            val msgs = McpClient.messages("text/event-stream; charset=utf-8", body)
            assertEquals(2, msgs.size)                                                            // progress notification + response; keepalive/id/event lines ignored
            assertEquals("notifications/progress", msgs[0]["method"].asTextOrNull())
            val resp = McpClient.responseFor(1, msgs)!!
            assertEquals("hello", (((resp["result"] as JsonObject)["content"] as JsonArray)[0] as JsonObject)["text"].asTextOrNull())   // multi-line data: joined
        }
        assertTrue(McpClient.messages("application/json", "").isEmpty())
        assertTrue(McpClient.messages("text/event-stream", "   \n\n").isEmpty())
        assertTrue(McpClient.messages("text/event-stream", "data: {not json\n\n").isEmpty())                                             // malformed frame skipped
        assertNull(McpClient.responseFor(2, McpClient.messages("text/event-stream", lf)))
    }

    @Test fun requestShapesPerEra() {
        val modern = McpClient.request("tools/list", buildJsonObject { put("cursor", "c") }, 5, "modern", McpClient.MODERN)
        assertEquals("2.0", modern["jsonrpc"].asTextOrNull()); assertEquals(JsonPrimitive(5), modern["id"])
        val meta = (modern["params"] as JsonObject)["_meta"] as JsonObject
        assertEquals(McpClient.MODERN, meta["io.modelcontextprotocol/protocolVersion"].asTextOrNull())
        assertEquals(JsonObject(emptyMap()), meta["io.modelcontextprotocol/clientCapabilities"])
        assertEquals("Mahout", (meta["io.modelcontextprotocol/clientInfo"] as JsonObject)["name"].asTextOrNull())
        assertEquals("c", (modern["params"] as JsonObject)["cursor"].asTextOrNull())
        val legacy = McpClient.request("tools/list", buildJsonObject { put("cursor", "c") }, 6, "legacy", "2025-11-25")
        assertNull((legacy["params"] as JsonObject)["_meta"])
        val notif = McpClient.request("notifications/initialized", JsonObject(emptyMap()), null, "legacy", "2025-11-25")
        assertFalse(notif.containsKey("id")); assertFalse(notif.containsKey("params"))
    }

    @Test fun headersPerEraNameParamsAndAuth() {
        val s = server()
        val tool = McpClient.Tool(s.id, s.name, "repos.list", null, "", JsonObject(emptyMap()), null, mapOf(listOf("region") to "Region", listOf("opts", "tenant") to "Tenant"))
        val args = buildJsonObject { put("name", "repos.list"); put("arguments", buildJsonObject { put("region", "us-west1"); put("opts", buildJsonObject { put("tenant", "acme") }) }) }
        val h = McpClient.headers("tools/call", args, "modern", McpClient.MODERN, null, tool, s, null)
        assertEquals("application/json, text/event-stream", h["Accept"])
        assertEquals(McpClient.MODERN, h["MCP-Protocol-Version"]); assertEquals("tools/call", h["Mcp-Method"]); assertEquals("repos.list", h["Mcp-Name"])
        assertEquals("us-west1", h["Mcp-Param-Region"]); assertEquals("acme", h["Mcp-Param-Tenant"])                   // nested properties chain mirrored
        assertFalse(h.containsKey("Authorization")); assertFalse(h.containsKey("Mcp-Session-Id"))
        val r = McpClient.headers("resources/read", buildJsonObject { put("uri", "file:///a b") }, "modern", McpClient.MODERN, null, null, s, null)
        assertEquals("file:///a b", r["Mcp-Name"])
        val l = McpClient.headers("tools/list", JsonObject(emptyMap()), "legacy", "2025-06-18", "abc", null, s, null)
        assertEquals("abc", l["Mcp-Session-Id"]); assertEquals("2025-06-18", l["MCP-Protocol-Version"]); assertFalse(l.containsKey("Mcp-Method")); assertFalse(l.containsKey("Mcp-Name"))
        val init = McpClient.headers("initialize", JsonObject(emptyMap()), "legacy", null, null, null, s, null)
        assertFalse(init.containsKey("MCP-Protocol-Version"))
        assertEquals("Bearer tok-123", McpClient.headers("tools/list", JsonObject(emptyMap()), "modern", McpClient.MODERN, null, null, server(auth = McpAuth.BEARER), "tok-123")["Authorization"])
        val custom = server(auth = McpAuth.HEADER).copy(headerName = "X-Api-Key")
        assertEquals("k9", McpClient.headers("tools/list", JsonObject(emptyMap()), "modern", McpClient.MODERN, null, null, custom, "k9")["X-Api-Key"])
        try { McpClient.headers("tools/list", JsonObject(emptyMap()), "modern", McpClient.MODERN, null, null, custom.copy(headerName = "Bad Name"), "k9"); fail() } catch (e: NodeException) {}
        try { McpClient.headers("tools/list", JsonObject(emptyMap()), "modern", McpClient.MODERN, null, null, server(auth = McpAuth.BEARER), "a\r\nb"); fail() } catch (e: NodeException) {}
    }

    @Test fun headerValueEncodesPerSpecExamples() {
        assertEquals("us-west1", McpClient.headerValue("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpClient.headerValue("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpClient.headerValue(" padded "))
        val re = McpClient.headerValue("=?base64?literal?=")
        assertTrue(re.startsWith("=?base64?") && re != "=?base64?literal?=")
        assertTrue(McpClient.headerValue("tab\there").contains("tab\there"))
        assertTrue(McpClient.headerValue("nl\nhere").startsWith("=?base64?"))
    }

    @Test fun headerParamsWalksPropertiesChainsOnly() {
        val ok = McpClient.headerParams(obj("""{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},"opts":{"type":"object","properties":{"tenant":{"type":"string","x-mcp-header":"Tenant"}}},"plain":{"type":"string"}}}""")).getOrThrow()
        assertEquals(mapOf(listOf("region") to "Region", listOf("opts", "tenant") to "Tenant"), ok)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"list":{"type":"array","items":{"type":"string","x-mcp-header":"H"}}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"a":{"anyOf":[{"type":"string","x-mcp-header":"H"},{"type":"null"}]}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/X"}},"${'$'}defs":{"X":{"type":"string","x-mcp-header":"H"}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"n":{"type":"number","x-mcp-header":"N"}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"Dup"},"b":{"type":"string","x-mcp-header":"dup"}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"Bad Name"}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":""}}}""")).isFailure)
        assertTrue(McpClient.headerParams(obj("""{"type":"object","x-mcp-header":"Root","properties":{}}""")).isFailure)
    }

    @Test fun modernErrorDetectionAndSupportedVersions() {
        fun err(code: Int, extra: String = "") = """{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"x"$extra}}"""
        assertTrue(McpClient.isModernError(400, err(-32022, ""","data":{"supported":["2026-07-28"],"requested":"2027-01-01"}""")))
        assertTrue(McpClient.isModernError(400, err(-32020))); assertTrue(McpClient.isModernError(400, err(-32021))); assertTrue(McpClient.isModernError(404, err(-32601)))
        assertFalse(McpClient.isModernError(400, err(-32602))); assertFalse(McpClient.isModernError(400, err(-32000))); assertFalse(McpClient.isModernError(400, err(-32600)))
        assertFalse(McpClient.isModernError(400, "<html>bad request</html>")); assertFalse(McpClient.isModernError(405, "")); assertFalse(McpClient.isModernError(404, ""))
        assertFalse(McpClient.isModernError(200, err(-32601)))
        assertEquals(listOf("2026-07-28", "2026-01-01"), McpClient.supportedVersions(err(-32022, ""","data":{"supported":["2026-07-28","2026-01-01"]}""")))
        assertTrue(McpClient.supportedVersions("nope").isEmpty())
    }

    // ---------------------------------------------------------------- era machine via the transport fake

    private class Exchange(val headers: Map<String, String>, val body: JsonObject) {
        val method get() = body["method"].asTextOrNull()
        val id get() = body["id"].asTextOrNull()
    }

    /** Scripted server. `mode` picks the behaviour; every exchange is recorded (never the real network). */
    private class FakeServer(val mode: String) {
        val log = ArrayList<Exchange>()
        var session = "abc"
        var notFoundOnce = true
        fun result(id: String?, result: JsonObject, headers: Map<String, String> = emptyMap(), status: Int = 200, sse: Boolean = false): McpClient.HttpResp {
            val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", id?.toInt() ?: 0); put("result", result) }.toString()
            return if (sse) McpClient.HttpResp(status, headers + ("content-type" to "text/event-stream"), ": keepalive\nevent: message\ndata: $msg\n\n")
            else McpClient.HttpResp(status, headers + ("content-type" to "application/json"), msg)
        }
        fun error(id: String?, code: Int, status: Int, data: JsonObject? = null) = McpClient.HttpResp(status, mapOf("content-type" to "application/json"),
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id?.toInt() ?: 0); put("error", buildJsonObject { put("code", code); put("message", "err $code"); if (data != null) put("data", data) }) }.toString())
        fun toolsResult(cursor: String?): JsonObject = buildJsonObject {
            put("tools", buildJsonArray { add(buildJsonObject { put("name", "t_${cursor ?: "first"}"); put("inputSchema", buildJsonObject { put("type", "object") }) }) })
            when (mode) {
                "pages" -> when (cursor) { null -> put("nextCursor", "c1"); "c1" -> put("nextCursor", ""); else -> {} }
                "endless" -> put("nextCursor", "more")
            }
        }
        val transport: suspend (String, Map<String, String>, String, Long) -> McpClient.HttpResp = { _, headers, body, _ ->
            val req = JSON.parseToJsonElement(body) as JsonObject
            val ex = Exchange(headers, req); log += ex
            val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
            val hasMeta = params.containsKey("_meta")
            val hasSession = headers.containsKey("Mcp-Session-Id")
            fun legacyFlow(): McpClient.HttpResp = when (ex.method) {
                "initialize" -> result(ex.id, buildJsonObject { put("protocolVersion", if (mode == "old") "2024-11-05" else "2025-06-18"); put("capabilities", JsonObject(emptyMap())); put("serverInfo", buildJsonObject { put("name", "legacy"); put("version", "1") }) }, mapOf("mcp-session-id" to session))
                "notifications/initialized" -> McpClient.HttpResp(202, emptyMap(), "")
                else -> when {
                    !hasSession -> error(ex.id, -32000, 400)
                    mode == "legacy404" && notFoundOnce -> { notFoundOnce = false; session = "def"; McpClient.HttpResp(404, emptyMap(), "") }
                    ex.method == "tools/list" -> result(ex.id, toolsResult(params["cursor"].asTextOrNull()))
                    else -> result(ex.id, buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "legacy ok") }) }) })
                }
            }
            when (mode) {
                "modern", "pages", "endless", "sse" -> when {
                    !hasMeta -> McpClient.HttpResp(400, emptyMap(), "")
                    ex.method == "tools/list" -> result(ex.id, toolsResult(params["cursor"].asTextOrNull()), sse = mode == "sse")
                    ex.method == "resources/list" -> error(ex.id, -32601, 404)
                    else -> result(ex.id, buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "modern ok") }) }) }, sse = mode == "sse")
                }
                "legacy", "old", "legacy404" -> if (hasMeta && ex.method != "initialize") error(ex.id, -32000, 400) else legacyFlow()
                "legacy200" -> if (hasMeta && ex.method != "initialize") error(ex.id, -32601, 200) else legacyFlow()
                "v32022" -> if (log.size == 1) error(ex.id, -32022, 400, buildJsonObject { put("supported", buildJsonArray { add(JsonPrimitive(McpClient.MODERN)) }); put("requested", "x") })
                    else result(ex.id, toolsResult(null))
                "v32022unsupported" -> error(ex.id, -32022, 400, buildJsonObject { put("supported", buildJsonArray { add(JsonPrimitive("2027-01-01")) }) })
                "401" -> McpClient.HttpResp(401, emptyMap(), """{"error":"unauthorized Bearer sekrit"}""")
                "redirect" -> McpClient.HttpResp(301, mapOf("location" to "https://mcp.example.com/v2/mcp"), "")
                "405" -> McpClient.HttpResp(405, emptyMap(), "")
                "404" -> McpClient.HttpResp(404, emptyMap(), "not found")
                "bad_header_tool" -> result(ex.id, buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject { put("name", "good"); put("inputSchema", buildJsonObject { put("type", "object") }) })
                        add(buildJsonObject { put("name", "bad"); put("inputSchema", JSON.parseToJsonElement("""{"type":"object","properties":{"l":{"type":"array","items":{"type":"string","x-mcp-header":"H"}}}}""") as JsonObject) })
                        add(buildJsonObject { put("name", ""); put("inputSchema", buildJsonObject { put("type", "object") }) })
                    })
                })
                "call_error" -> if (ex.method == "tools/call") error(ex.id, -32602, 200) else result(ex.id, toolsResult(null))
                else -> error("unknown mode $mode")
            }
        }
    }

    private fun use(mode: String): FakeServer = FakeServer(mode).also { McpClient.transport = it.transport }

    @Test fun modernServerNeedsNoInitialize() = runBlocking {
        val f = use("modern"); val s = server()
        val tools = McpClient.listTools(s, null)
        assertEquals(listOf("t_first"), tools.map { it.name })
        assertEquals("modern", McpClient.conn(s.id)!!.era); assertEquals(McpClient.MODERN, McpClient.conn(s.id)!!.protocol)
        assertEquals(listOf("tools/list"), f.log.map { it.method })
        assertEquals(McpClient.MODERN, f.log[0].headers["MCP-Protocol-Version"]); assertEquals("tools/list", f.log[0].headers["Mcp-Method"])
        // cached for 10 min: a second call does not touch the network; force does
        McpClient.listTools(s, null); assertEquals(1, f.log.size)
        McpClient.listTools(s, null, force = true); assertEquals(2, f.log.size)
        assertTrue(McpClient.listResources(s, null).isEmpty())                                   // 404 + -32601 on a modern server = no resources
    }

    @Test fun sseFramedModernResponsesParse() = runBlocking {
        use("sse"); val s = server()
        assertEquals("t_first", McpClient.listTools(s, null).single().name)
        val r = McpClient.callTool(s, null, "t_first", JsonObject(emptyMap()))
        assertEquals("modern ok", McpClient.render(r, false).text)
    }

    @Test fun legacyServerFallsBackToInitializeAndEchoesTheSession() = runBlocking {
        val f = use("legacy"); val s = server()
        val tools = McpClient.listTools(s, null)
        assertEquals(1, tools.size)
        assertEquals(listOf("tools/list", "initialize", "notifications/initialized", "tools/list"), f.log.map { it.method })   // exactly 4 exchanges
        val c = McpClient.conn(s.id)!!
        assertEquals("legacy", c.era); assertEquals("2025-06-18", c.protocol); assertEquals("abc", c.sessionId)
        assertFalse(f.log[1].headers.containsKey("MCP-Protocol-Version"))                          // no protocol header on initialize
        assertEquals("2025-11-25", ((f.log[1].body["params"] as JsonObject)["protocolVersion"]).asTextOrNull())
        assertFalse(f.log[1].body.containsKey("_meta")); assertFalse((f.log[1].body["params"] as JsonObject).containsKey("_meta"))
        assertFalse(f.log[2].body.containsKey("id"))                                                // notification
        assertEquals("abc", f.log[3].headers["Mcp-Session-Id"]); assertEquals("2025-06-18", f.log[3].headers["MCP-Protocol-Version"])
        assertFalse(f.log[3].headers.containsKey("Mcp-Method"))
        // subsequent call reuses the session, no re-probe
        McpClient.callTool(s, null, "t_first", JsonObject(emptyMap()))
        assertEquals(5, f.log.size); assertEquals("abc", f.log[4].headers["Mcp-Session-Id"])
    }

    @Test fun legacyServerAnsweringTheProbeWith200PlusErrorIsLegacy() = runBlocking {
        val f = use("legacy200"); val s = server()
        McpClient.listTools(s, null)
        assertEquals("legacy", McpClient.conn(s.id)!!.era)
        assertEquals(listOf("tools/list", "initialize", "notifications/initialized", "tools/list"), f.log.map { it.method })
    }

    @Test fun protocolHintSkipsTheProbe() = runBlocking {
        val f = use("legacy"); val s = server(protocol = "2025-06-18")
        McpClient.listTools(s, null)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), f.log.map { it.method })
    }

    @Test fun legacy404OnASessionRequestReinitialisesOnce() = runBlocking {
        val f = use("legacy404"); val s = server()
        McpClient.listTools(s, null)
        assertEquals(listOf("tools/list", "initialize", "notifications/initialized", "tools/list", "initialize", "notifications/initialized", "tools/list"), f.log.map { it.method })
        assertEquals("def", McpClient.conn(s.id)!!.sessionId); assertEquals("def", f.log[6].headers["Mcp-Session-Id"])
    }

    @Test fun unsupportedLegacyVersionIsAClearError() = runBlocking {
        use("old"); val s = server("Old One")
        try { McpClient.listTools(s, null); fail() } catch (e: NodeException) {
            assertTrue(e.message!!, e.message!!.contains("2024-11-05") && e.message!!.contains("2025-03-26") && e.message!!.contains(McpClient.MODERN))
        }
        assertNull(McpClient.conn(s.id))
    }

    @Test fun versionErrorRetriesOnceThenIsModern() = runBlocking {
        val f = use("v32022"); val s = server()
        assertEquals(1, McpClient.listTools(s, null).size)
        assertEquals(listOf("tools/list", "tools/list"), f.log.map { it.method }); assertEquals("modern", McpClient.conn(s.id)!!.era)
        use("v32022unsupported")
        try { McpClient.listTools(server(), null); fail() } catch (e: NodeException) { assertTrue(e.message, e.message!!.contains("supports only 2027-01-01")) }
    }

    @Test fun httpErrorsHaveUserTextsAndNeverLeakTheSecret() = runBlocking {
        use("401")
        try { McpClient.listTools(server(auth = McpAuth.BEARER), "sekrit"); fail() } catch (e: NodeException) {
            assertTrue(e.message, e.message!!.contains("authentication failed")); assertFalse(e.message!!.contains("sekrit"))
        }
        use("redirect")
        try { McpClient.listTools(server(), null); fail() } catch (e: NodeException) { assertTrue(e.message, e.message!!.contains("redirected to https://mcp.example.com/v2/mcp")) }
        use("405")
        try { McpClient.listTools(server(), null); fail() } catch (e: NodeException) { assertTrue(e.message, e.message!!.contains("HTTP+SSE")) }
        use("404")
        try { McpClient.listTools(server(), null); fail() } catch (e: NodeException) { assertTrue(e.message, e.message!!.contains("no MCP endpoint at https://mcp.example.com/mcp")) }
        try { McpClient.listTools(server(auth = McpAuth.BEARER), null); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("credential not set")) }
        try { McpClient.listTools(server().copy(url = "http://evil.example/mcp"), null); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("http://")) }
        use("call_error"); val s = server("Srv")
        McpClient.listTools(s, null)                                                                 // era known; a JSON-RPC error on tools/call is the server's answer, not an era signal
        try { McpClient.callTool(s, null, "nope", JsonObject(emptyMap())); fail() } catch (e: NodeException) { assertEquals("Srv: err -32602 (code -32602)", e.message) }
    }

    @Test fun paginationFollowsCursorsIncludingEmptyAndStopsAtTwentyPages() = runBlocking {
        val f = use("pages"); val s = server()
        assertEquals(listOf("t_first", "t_c1", "t_"), McpClient.listTools(s, null).map { it.name })
        assertEquals(listOf(null, "c1", ""), f.log.map { (it.body["params"] as JsonObject)["cursor"].asTextOrNull() })
        val e = use("endless")
        assertEquals(McpClient.MAX_PAGES, McpClient.listTools(server(), null).size); assertEquals(McpClient.MAX_PAGES, e.log.size)
    }

    @Test fun toolsWithInvalidHeaderAnnotationsOrBlankNamesAreExcludedAndLogged() = runBlocking {
        use("bad_header_tool"); val logs = ArrayList<String>()
        val tools = McpClient.listTools(server(), null, log = logs::add)
        assertEquals(listOf("good"), tools.map { it.name })
        assertTrue(logs.any { it.contains("bad") && it.contains("excluded") }); assertTrue(logs.any { it.contains("without a name") })
        assertTrue(logs.none { it.contains("Authorization") || it.contains("inputSchema") })         // log lines carry no headers/bodies
    }

    // ---------------------------------------------------------------- render / names / defs / clean

    private fun call(name: String): McpClient.CallResult {
        val r = (obj(res(name))["result"] as JsonObject)
        val ir = r["resultType"].asTextOrNull() == "input_required"
        return McpClient.CallResult(r["content"] as JsonArray, r["structuredContent"], (r["isError"] as? JsonPrimitive)?.content == "true" || ir, ir)
    }

    @Test fun renderJoinsTextBlocksAndHandlesMixedContent() {
        val t = McpClient.render(call("call_text.json"), false)
        assertEquals("first line\nsecond line", t.text); assertFalse(t.isError); assertNull(t.imageBase64)
        val v = McpClient.render(call("call_mixed.json"), true)
        assertNotNull(v.imageBase64); assertEquals("image/png", v.imageMime)
        assertTrue(v.text.contains("summary")); assertTrue(v.text.contains("[resource file:///notes/a.txt]\nresource body"))
        assertTrue(v.text.contains("[link] https://example.com/doc Doc — A linked document")); assertTrue(v.text.contains("[audio audio/wav omitted]"))
        assertFalse(v.text.contains("\"count\""))                                                      // structuredContent only when there is no text block
        val nv = McpClient.render(call("call_mixed.json"), false)
        assertNull(nv.imageBase64); assertTrue(nv.text, nv.text.contains("[image image/png, 0 KB omitted]"))
        val e = McpClient.render(call("call_is_error.json"), true)
        assertTrue(e.isError); assertEquals("boom: repository not found", e.text)
        val ir = McpClient.render(call("call_input_required.json"), true)
        assertTrue(ir.isError); assertTrue(ir.text.contains("interactive input"))
        val so = McpClient.render(call("call_structured_only.json"), false)
        assertEquals("""{"temperature":21.5,"unit":"C"}""", so.text)
        val big = McpClient.CallResult(buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "x".repeat(20_000)) }) }, null, false, false)
        val capped = McpClient.render(big, false)
        assertTrue(capped.text.endsWith("…(truncated)")); assertTrue(capped.text.length <= McpClient.RESULT_CAP + 20)
    }

    @Test fun sanitizeProducesStableProviderSafeNames() {
        assertEquals("mcp__github_tools__repos_list", McpClient.sanitize("GitHub Tools", "repos.list"))
        assertEquals("server", McpClient.slug("!!!")); assertEquals("a_b", McpClient.slug("A  B"))
        val long = McpClient.sanitize("Some Very Long Server Name Here", "x".repeat(200))
        assertEquals(64, long.length); assertTrue(long.matches(Regex("[a-zA-Z0-9_-]+")))
        assertEquals(long, McpClient.sanitize("Some Very Long Server Name Here", "x".repeat(200)))          // stable
        val names = HashSet<String>()
        val rnd = java.util.Random(7)
        repeat(1000) { i -> names += McpClient.sanitize("Srv $i", "tool_" + (0 until 120).map { ('a' + rnd.nextInt(26)) }.joinToString("") + i) }
        assertEquals(1000, names.size)
        assertTrue(names.all { it.length in 1..64 && it.matches(Regex("[a-zA-Z0-9_-]+")) })
    }

    @Test fun toolDefIsLooseAndScrubbed() {
        val t = (obj(res("tools_list.json"))["result"] as JsonObject)["tools"] as JsonArray
        val raw = t[0] as JsonObject
        val tool = McpClient.Tool("s", "GitHub Tools", "repos.list", "List repos", "Lists repositories of an org", raw["inputSchema"] as JsonObject, true, emptyMap())
        val def = McpClient.toolDef(tool, "mcp__github_tools__repos_list")
        assertEquals(JsonPrimitive(false), def["strict"]); assertEquals("mcp__github_tools__repos_list", def["name"].asTextOrNull())
        assertEquals("[MCP GitHub Tools] List repos — Lists repositories of an org", def["description"].asTextOrNull())
        val schema = def["input_schema"] as JsonObject
        assertFalse(schema.containsKey("\$schema")); assertEquals("object", schema["type"].asTextOrNull())
        val props = schema["properties"] as JsonObject
        assertFalse((props["region"] as JsonObject).containsKey("x-mcp-header"))
        assertFalse((((props["opts"] as JsonObject)["properties"] as JsonObject)["tenant"] as JsonObject).containsKey("x-mcp-header"))
        assertEquals(raw["inputSchema"]!!.let { (it as JsonObject)["required"] }, schema["required"])                      // everything else identical
        assertEquals("Organisation", (props["org"] as JsonObject)["description"].asTextOrNull())
        val empty = McpClient.scrub(JsonObject(emptyMap()))
        assertEquals("object", empty["type"].asTextOrNull()); assertEquals(JsonObject(emptyMap()), empty["properties"])
    }

    @Test fun cleanMasksSecretsAndBearerTokens() {
        assertEquals("auth failed for *** and ***", McpClient.clean("auth failed for Bearer abc.def and sekrit", "sekrit"))
        assertEquals(300, McpClient.clean("y".repeat(1000), null).length)
        assertEquals("", McpClient.clean(null, "x"))
    }
}
