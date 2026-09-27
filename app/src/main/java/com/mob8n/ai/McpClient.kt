package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.asBool
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ONE MCP client (DESIGN3 §4.3): Streamable HTTP over HttpURLConnection + kotlinx JSON, dual-era.
 * ponytail: dual-era probe = modern request first, legacy on non-modern 4xx or 200+error; upgrade = server/discover pre-flight
 * ponytail: 10-min tool cache ignores ttlMs/listChanged
 * Ceilings (README): no stdio, no 2024-11-05 HTTP+SSE, no GET stream / Last-Event-ID, no subscriptions/listen, no tasks, no OAuth.
 */
object McpClient {
    const val MODERN = "2026-07-28"
    val LEGACY = listOf("2025-11-25", "2025-06-18", "2025-03-26")          // offered: first; accepted from InitializeResult: any
    const val TOOL_NAME_MAX = 64; const val MAX_TOOLS = 500; const val MAX_PAGES = 20
    const val CACHE_MS = 10 * 60_000L; const val MAX_BODY = 4 * 1024 * 1024; const val MAX_IMAGE = 5 * 1024 * 1024
    const val CONNECT_MS = 15_000; const val LIST_MS = 20_000L; const val CALL_MS = 60_000L; const val CALL_MAX_MS = 120_000L; const val RESULT_CAP = 8 * 1024
    private const val RETRY_MS = 2_000L
    private const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
    private const val META_CAPS = "io.modelcontextprotocol/clientCapabilities"
    private const val META_INFO = "io.modelcontextprotocol/clientInfo"
    @Volatile var clientVersion: String = "0"                            // set by McpPrefs.load from PackageInfo.versionName (no BuildConfig in this app)

    data class Tool(val serverId: String, val serverName: String, val name: String, val title: String?, val description: String, val inputSchema: JsonObject,
                    val readOnly: Boolean?, val headerParams: Map<List<String>, String> /* property path -> Mcp-Param name */)
    data class Resource(val uri: String, val name: String, val mimeType: String?, val description: String?, val size: Long?)
    data class CallResult(val content: JsonArray, val structured: JsonElement?, val isError: Boolean, val inputRequired: Boolean)
    class Rendered(val text: String, val imageBase64: String?, val imageMime: String?, val isError: Boolean)
    /** Per-server process state (era, negotiated version, legacy session, tool cache). */
    class Conn(@Volatile var era: String /* "modern"|"legacy" */, @Volatile var protocol: String, @Volatile var sessionId: String?, @Volatile var tools: List<Tool>?, @Volatile var toolsAt: Long)
    /** One HTTP exchange; the default is HttpURLConnection, tests inject a fake. */
    class HttpResp(val status: Int, val headers: Map<String, String> /* lower-cased names */, val body: String)
    var transport: suspend (url: String, headers: Map<String, String>, body: String, timeoutMs: Long) -> HttpResp = ::httpPost

    private val conns = ConcurrentHashMap<String, Conn>()
    private val counter = AtomicInteger()

    fun invalidate(serverId: String) { conns.remove(serverId) }
    fun conn(serverId: String): Conn? = conns[serverId]

    // ---------------------------------------------------------------- network API

    suspend fun listTools(s: McpServer, secret: String?, force: Boolean = false, log: (String) -> Unit = {}): List<Tool> {
        val now = System.currentTimeMillis()
        if (!force) conns[s.id]?.let { c -> c.tools?.takeIf { now - c.toolsAt < CACHE_MS }?.let { return it } }
        val out = ArrayList<Tool>()
        var cursor: String? = null
        for (page in 0 until MAX_PAGES) {
            val params = buildJsonObject { if (cursor != null) put("cursor", cursor) }
            val result = resultOf(rpc(s, secret, "tools/list", params, LIST_MS, log), s)
            for (t in (result["tools"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()) {
                val name = t["name"].asTextOrNull()?.trim().orEmpty()
                if (name.isBlank()) { log("MCP ${s.name}: tool without a name skipped"); continue }
                val schema = t["inputSchema"] as? JsonObject ?: JsonObject(emptyMap())
                val hp = headerParams(schema).getOrElse { e -> log("MCP ${s.name}: tool $name excluded (${e.message})"); null } ?: continue
                out += Tool(s.id, s.name, name, t["title"].asTextOrNull(), t["description"].asTextOrNull() ?: "", schema,
                    (t["annotations"] as? JsonObject)?.get("readOnlyHint").asBool(), hp)
                if (out.size >= MAX_TOOLS) break
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (cursor == null || out.size >= MAX_TOOLS) break
        }
        conns[s.id]?.let { it.tools = out; it.toolsAt = System.currentTimeMillis() }
        return out
    }

    /** Protocol errors surface as NodeException(rpcError); the Agent turns that into an is_error result, nodes route it to the error port. */
    suspend fun callTool(s: McpServer, secret: String?, name: String, args: JsonObject, timeoutMs: Long = CALL_MS, log: (String) -> Unit = {}): CallResult {
        val tool = conns[s.id]?.tools?.firstOrNull { it.name == name }
        val params = buildJsonObject { put("name", name); put("arguments", args) }
        val result = resultOf(rpc(s, secret, "tools/call", params, timeoutMs.coerceIn(1_000, CALL_MAX_MS), log, tool), s)
        val inputRequired = result["resultType"].asTextOrNull() == "input_required"   // absent = complete
        // ponytail: MRTR input_required -> is_error; upgrade = elicitation dialog via Suspend(APPROVAL)
        return CallResult(result["content"] as? JsonArray ?: JsonArray(emptyList()), result["structuredContent"], (result["isError"].asBool() ?: false) || inputRequired, inputRequired)
    }

    /** Empty when the server has no resources capability (-32601). */
    suspend fun listResources(s: McpServer, secret: String?, log: (String) -> Unit = {}): List<Resource> {
        val out = ArrayList<Resource>()
        var cursor: String? = null
        for (page in 0 until MAX_PAGES) {
            val msg = rpc(s, secret, "resources/list", buildJsonObject { if (cursor != null) put("cursor", cursor) }, LIST_MS, log)
            if (errorCode(msg) == -32601) return emptyList()
            val result = resultOf(msg, s)
            for (r in (result["resources"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()) {
                val uri = r["uri"].asTextOrNull() ?: continue
                out += Resource(uri, r["name"].asTextOrNull() ?: uri, r["mimeType"].asTextOrNull(), r["description"].asTextOrNull(), r["size"].asTextOrNull()?.toLongOrNull())
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (cursor == null || out.size >= MAX_TOOLS) break
        }
        return out
    }

    /** result.contents. */
    suspend fun readResource(s: McpServer, secret: String?, uri: String, timeoutMs: Long = CALL_MS, log: (String) -> Unit = {}): JsonArray {
        val msg = rpc(s, secret, "resources/read", buildJsonObject { put("uri", uri) }, timeoutMs.coerceIn(1_000, CALL_MAX_MS), log)
        val code = errorCode(msg)
        if (code == -32602 || code == -32002) throw NodeException("Resource not found: $uri")
        return resultOf(msg, s)["contents"] as? JsonArray ?: JsonArray(emptyList())
    }

    // ---------------------------------------------------------------- the era machine (private, both eras)

    private fun errorCode(msg: JsonObject): Int? = (msg["error"] as? JsonObject)?.get("code").asTextOrNull()?.toIntOrNull()

    private fun resultOf(msg: JsonObject, s: McpServer): JsonObject {
        (msg["error"] as? JsonObject)?.let { throw NodeException(rpcError(it, s)) }
        return msg["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** One JSON-RPC request in the server's era; probes on first contact; returns the response message (result or error). */
    private suspend fun rpc(s: McpServer, secret: String?, method: String, params: JsonObject, timeoutMs: Long, log: (String) -> Unit, tool: Tool? = null): JsonObject {
        if (s.auth != McpAuth.NONE && secret.isNullOrBlank()) throw NodeException("${s.name}: credential not set (Settings > AI > MCP servers)")
        try { Providers.normalizeBaseUrl(s.url) } catch (e: IllegalArgumentException) { throw NodeException("${s.name}: ${e.message}") }
        try {
            var c = conns[s.id]
            if (c == null) {
                if (s.protocol in LEGACY) {
                    c = legacyInit(s, secret, timeoutMs, log)
                } else {
                    val probe = probe(s, secret, method, params, timeoutMs, log, tool)
                    if (probe != null) return probe
                    c = legacyInit(s, secret, timeoutMs, log)
                }
                conns[s.id] = c
            }
            var r = send(s, secret, method, params, c, timeoutMs, log, tool)
            if (c.era == "legacy" && c.sessionId != null && (r.resp.status == 404 || (r.resp.status == 400 && r.resp.body.contains("session", ignoreCase = true)))) {
                conns.remove(s.id)
                c = legacyInit(s, secret, timeoutMs, log); conns[s.id] = c
                r = send(s, secret, method, params, c, timeoutMs, log, tool)
            }
            if (r.resp.status !in 200..299) {
                if (r.resp.status == 400 || r.resp.status == 404) errorObject(r.resp.body)?.let { return it }   // JSON-RPC error with an HTTP status (e.g. 404 + -32601): callers map the code
                conns.remove(s.id); throw NodeException(errorMessage(r.resp.status, r.resp.body, s, r.resp.headers["location"]))
            }
            return responseFor(r.id, messages(r.resp.headers["content-type"], r.resp.body)) ?: throw NodeException("${s.name}: no response to $method")
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeException) {
            throw NodeException(clean(e.message, secret), e)
        }
    }

    /** Modern request first. Returns the response when the server is modern; null when the fallback to `initialize` applies. */
    private suspend fun probe(s: McpServer, secret: String?, method: String, params: JsonObject, timeoutMs: Long, log: (String) -> Unit, tool: Tool?): JsonObject? {
        val modern = Conn("modern", MODERN, null, null, 0L)
        var r = send(s, secret, method, params, modern, timeoutMs, log, tool)
        if (r.resp.status in 200..299) {
            val msg = responseFor(r.id, messages(r.resp.headers["content-type"], r.resp.body))
            if (msg != null && msg.containsKey("result")) { conns[s.id] = modern; return msg }
            return null                                                     // 2xx carrying a JSON-RPC error (or nothing): era-ambiguous -> legacy
        }
        if (!isModernError(r.resp.status, r.resp.body)) return null
        val err = errorObject(r.resp.body)!!
        when (errorCode(err)) {
            -32022 -> {
                if (MODERN !in supportedVersions(r.resp.body)) throw NodeException(rpcError(err, s))
                r = send(s, secret, method, params, modern, timeoutMs, log, tool)   // retried once with the advertised version
                if (r.resp.status !in 200..299) throw NodeException(errorObject(r.resp.body)?.let { rpcError(it, s) } ?: errorMessage(r.resp.status, r.resp.body, s, r.resp.headers["location"]))
                conns[s.id] = modern
                return responseFor(r.id, messages(r.resp.headers["content-type"], r.resp.body)) ?: throw NodeException("${s.name}: no response to $method")
            }
            -32020, -32021 -> throw NodeException(rpcError(err, s))          // our bug, surfaced
            else -> { conns[s.id] = modern; return err }                      // 404 + -32601: modern server, unknown method (callers handle it)
        }
    }

    private suspend fun legacyInit(s: McpServer, secret: String?, timeoutMs: Long, log: (String) -> Unit): Conn {
        val init = buildJsonObject {
            put("protocolVersion", LEGACY.first()); put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "Mahout"); put("version", clientVersion) })
        }
        val c0 = Conn("legacy", "", null, null, 0L)                        // protocol "" -> no MCP-Protocol-Version header on initialize
        val r = send(s, secret, "initialize", init, c0, timeoutMs, log, null)
        if (r.resp.status !in 200..299) throw NodeException(errorMessage(r.resp.status, r.resp.body, s, r.resp.headers["location"]))
        val msg = responseFor(r.id, messages(r.resp.headers["content-type"], r.resp.body)) ?: throw NodeException("${s.name}: no response to initialize")
        val result = resultOf(msg, s)
        val v = result["protocolVersion"].asTextOrNull() ?: ""
        if (v !in LEGACY) throw NodeException("${s.name} speaks MCP $v; Mahout supports ${LEGACY.last()} … $MODERN")
        val c = Conn("legacy", v, r.resp.headers["mcp-session-id"]?.trim()?.takeIf { it.isNotBlank() }, null, 0L)
        val n = send(s, secret, "notifications/initialized", JsonObject(emptyMap()), c, timeoutMs, log, null, notification = true)
        if (n.resp.status != 202 && n.resp.status != 200) log("MCP ${s.name} notifications/initialized ${n.resp.status} (ignored)")
        return c
    }

    private class Sent(val id: Int, val resp: HttpResp)

    /** One request: build, POST with exactly one retry on 429/502/503/IOException, log the line. Never logs headers, bodies or arguments. */
    private suspend fun send(s: McpServer, secret: String?, method: String, params: JsonObject, c: Conn, timeoutMs: Long, log: (String) -> Unit, tool: Tool?, notification: Boolean = false): Sent {
        val id = if (notification) null else counter.incrementAndGet()
        val protocol = c.protocol.takeIf { it.isNotBlank() }
        val body = JSON.encodeToString(JsonObject.serializer(), request(method, params, id, c.era, c.protocol))
        val headers = headers(method, params, c.era, protocol, c.sessionId, tool, s, secret)
        val t0 = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            attempt++
            val r = try {
                transport(s.url, headers, body, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                conns.remove(s.id); throw NodeException("${s.name} timed out after ${timeoutMs / 1000} s", e)
            } catch (e: IOException) {
                if (attempt == 1) { delay(RETRY_MS); continue }
                conns.remove(s.id); throw NodeException("Cannot reach ${s.name} at ${runCatching { URL(s.url).host }.getOrNull() ?: s.url} — ${e.javaClass.simpleName}", e)
            }
            if (attempt == 1 && (r.status == 429 || r.status == 502 || r.status == 503)) {
                delay(r.headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf { it in 0..10 }?.let { it * 1000 } ?: RETRY_MS); continue
            }
            log("MCP ${s.name} $method${if (method == "tools/call") " " + (params["name"].asTextOrNull() ?: "") else ""} ${r.status} ${System.currentTimeMillis() - t0} ms ${c.era}")
            return Sent(id ?: 0, r)
        }
    }

    /** Default transport: HttpURLConnection; SSE bodies stop at the frame carrying our id (closing the stream = modern cancellation, harmless after the response). */
    private suspend fun httpPost(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResp = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val conn = URL(url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = timeoutMs.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt()
                conn.instanceFollowRedirects = false
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val hs = HashMap<String, String>()
                conn.headerFields.forEach { (k, v) -> if (k != null && v != null && v.isNotEmpty()) hs[k.lowercase()] = v.joinToString(", ") }
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val sse = hs["content-type"]?.contains("text/event-stream", ignoreCase = true) == true
                // ponytail: the request id is re-parsed from the body we just sent so the transport lambda keeps the 4-arg shape tests fake
                val wantId = runCatching { (JSON.parseToJsonElement(body) as? JsonObject)?.get("id").asTextOrNull() }.getOrNull()
                val text = stream?.let { readCapped(it, sse, wantId) } ?: ""
                cont.resume(HttpResp(status, hs, text))
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun readCapped(s: java.io.InputStream, sse: Boolean, wantId: String?): String {
        val sb = StringBuilder()
        val r = BufferedReader(InputStreamReader(s, Charsets.UTF_8))
        val frame = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            if (sb.length + line.length + 1 > MAX_BODY) break
            sb.append(line).append('\n')
            if (!sse || wantId == null) continue
            if (line.isEmpty()) {                                          // frame boundary: stop once our response is in
                val msgs = messages("text/event-stream", frame.toString())
                frame.setLength(0)
                if (msgs.any { it["id"].asTextOrNull() == wantId && (it.containsKey("result") || it.containsKey("error")) }) break
            } else frame.append(line).append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- pure, JVM-tested

    /** lowercase, [^a-z0-9_-] -> "_", collapse "_", trim "_", take 24, blank -> "server". */
    fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]"), "_").replace(Regex("_+"), "_").trim('_').take(24).trim('_').ifBlank { "server" }

    /** "mcp__<serverSlug>__<toolSlug>", ≤ 64 chars, matching ^[a-zA-Z0-9_-]+$ (over 64 -> take(55) + "_" + fnv1a32 hex of "server/tool"). */
    fun sanitize(serverName: String, tool: String): String {
        val raw = "mcp__" + slug(serverName) + "__" + tool.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return if (raw.length <= TOOL_NAME_MAX) raw else raw.take(55) + "_" + fnv1a32hex("$serverName/$tool").take(8)
    }

    fun fnv1a32hex(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toInt() and 0xff); h *= 0x01000193 }
        return String.format("%08x", h)
    }

    /** {name, description: "[MCP <server>] <title ?: name> — <description>" (≤ 1024), strict: false, input_schema: scrub(inputSchema)}. */
    fun toolDef(t: Tool, name: String): JsonObject = buildJsonObject {
        put("name", name)
        val head = "[MCP ${t.serverName}] ${t.title?.takeIf { it.isNotBlank() } ?: t.name}"
        put("description", (if (t.description.isBlank()) head else "$head — ${t.description}").take(1024))
        put("strict", false)
        put("input_schema", scrub(t.inputSchema))
    }

    private val DROP_KEYS = setOf("\$schema", "\$id", "\$comment", "examples", "x-mcp-header")

    /** Ensures type:"object" + properties:{}; drops $schema/$id/$comment/examples/x-mcp-header recursively; nothing else rewritten. */
    fun scrub(schema: JsonObject): JsonObject {
        val s = dropKeys(schema) as JsonObject
        return JsonObject(LinkedHashMap<String, JsonElement>().apply {
            put("type", JsonPrimitive("object"))
            s.forEach { (k, v) -> if (k != "type") put(k, v) }
            if (!containsKey("properties")) put("properties", JsonObject(emptyMap()))
        })
    }

    private fun dropKeys(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it !in DROP_KEYS }.mapValues { dropKeys(it.value) })
        is JsonArray -> JsonArray(e.map { dropKeys(it) })
        else -> e
    }

    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val DATA_KEYS = setOf("examples", "default", "const", "enum", "description", "title")
    private val PRIMITIVE = setOf("string", "integer", "boolean")

    /** Walks `properties` chains only; failure = invalid annotation (the tool is excluded from the list). */
    fun headerParams(schema: JsonObject): Result<Map<List<String>, String>> {
        val out = LinkedHashMap<List<String>, String>()
        val seen = HashSet<String>()
        fun walk(node: JsonObject, path: List<String>, viaProps: Boolean) {
            node["x-mcp-header"]?.let { h ->
                val name = h.asTextOrNull()?.trim() ?: ""
                if (!viaProps || path.isEmpty()) throw IllegalArgumentException("x-mcp-header at ${path.joinToString("/").ifBlank { "root" }} is not reachable through properties only")
                if (name.isEmpty() || !name.matches(TOKEN)) throw IllegalArgumentException("x-mcp-header '$name' is not a header token")
                if (!seen.add(name.lowercase())) throw IllegalArgumentException("duplicate x-mcp-header '$name'")
                val type = node["type"].asTextOrNull()
                if (type !in PRIMITIVE) throw IllegalArgumentException("x-mcp-header '$name' on a ${type ?: "typeless"} property")
                out[path] = name
            }
            for ((k, v) in node) {
                if (k in DATA_KEYS) continue
                if (k == "properties" && v is JsonObject) { v.forEach { (pk, pv) -> if (pv is JsonObject) walk(pv, path + pk, viaProps) }; continue }
                when (v) {
                    is JsonObject -> walk(v, path + k, false)
                    is JsonArray -> v.filterIsInstance<JsonObject>().forEach { walk(it, path + k, false) }
                    else -> {}
                }
            }
        }
        return runCatching { walk(schema, emptyList(), true); out }
    }

    /** modern: params + "_meta"{protocolVersion, clientInfo{name:"Mob8N",version}, clientCapabilities:{}}; legacy: params as-is; id == null -> notification. */
    fun request(method: String, params: JsonObject, id: Int?, era: String, protocol: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (era == "modern") {
            put("params", JsonObject(params + ("_meta" to buildJsonObject {
                put(META_VERSION, protocol); put(META_CAPS, JsonObject(emptyMap()))
                put(META_INFO, buildJsonObject { put("name", "Mahout"); put("version", clientVersion) })
            })))
        } else if (params.isNotEmpty()) put("params", params)
    }

    fun headers(method: String, params: JsonObject, era: String, protocol: String?, sessionId: String?, tool: Tool?, s: McpServer, secret: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Accept"] = "application/json, text/event-stream"
        h["Content-Type"] = "application/json; charset=utf-8"
        if (protocol != null) h["MCP-Protocol-Version"] = protocol
        if (era == "modern") {
            h["Mcp-Method"] = method
            val name = when (method) { "tools/call", "prompts/get" -> params["name"]; "resources/read" -> params["uri"]; else -> null }.asTextOrNull()
            if (name != null) h["Mcp-Name"] = headerValue(name)
            val args = params["arguments"] as? JsonObject
            if (tool != null && args != null) for ((path, hn) in tool.headerParams) {
                var cur: JsonElement? = args
                for (seg in path) cur = (cur as? JsonObject)?.get(seg)
                val v = cur as? JsonPrimitive ?: continue
                if (v is JsonNull) continue
                h["Mcp-Param-$hn"] = headerValue(v.content)
            }
        } else if (sessionId != null) h["Mcp-Session-Id"] = sessionId
        when (s.auth) {
            McpAuth.NONE -> {}
            McpAuth.BEARER -> h["Authorization"] = "Bearer " + checkSecret(secret, s)
            McpAuth.HEADER -> {
                val n = s.headerName.trim()
                if (!n.matches(TOKEN)) throw NodeException("${s.name}: header name '$n' is not valid")
                h[n] = checkSecret(secret, s)
            }
        }
        return h
    }

    private fun checkSecret(secret: String?, s: McpServer): String {
        val v = secret?.trim().orEmpty()
        if (v.isEmpty()) throw NodeException("${s.name}: credential not set (Settings > AI > MCP servers)")
        if (v.any { it == '\r' || it == '\n' }) throw NodeException("${s.name}: credential must be a single line")
        return v
    }

    /** Plain when every char is visible ASCII / space / tab, no leading or trailing whitespace and not itself a sentinel; else =?base64?<b64(utf8)>?=. */
    fun headerValue(v: String): String {
        val plainChars = v.all { it.code in 0x21..0x7E || it == ' ' || it == '\t' }
        val trimmed = v.isEmpty() || (v.first() != ' ' && v.first() != '\t' && v.last() != ' ' && v.last() != '\t')
        val sentinel = v.startsWith("=?base64?") && v.endsWith("?=")
        return if (plainChars && trimmed && !sentinel) v else "=?base64?" + Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8)) + "?="
    }

    /** application/json -> [object]; text/event-stream -> one message per SSE event (data: lines joined "\n"); malformed frames skipped; blank -> []. */
    fun messages(contentType: String?, body: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
            val out = ArrayList<JsonObject>()
            val data = StringBuilder()
            fun flush() {
                if (data.isNotEmpty()) parseMessages(data.toString()).let { out += it }
                data.setLength(0)
            }
            for (raw in body.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                when {
                    raw.isEmpty() -> flush()
                    raw.startsWith(":") -> {}
                    raw.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(raw.substring(5).removePrefix(" ")) }
                    else -> {}                                              // event:, id:, retry:, unknown fields
                }
            }
            flush()
            return out
        }
        return parseMessages(body)
    }

    private fun parseMessages(s: String): List<JsonObject> = when (val e = runCatching { JSON.parseToJsonElement(s) }.getOrNull()) {
        is JsonObject -> listOf(e)
        is JsonArray -> e.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

    /** The message whose "id" == id (notifications and other ids ignored). */
    fun responseFor(id: Int, msgs: List<JsonObject>): JsonObject? =
        msgs.firstOrNull { it["id"].asTextOrNull() == id.toString() && (it.containsKey("result") || it.containsKey("error")) }

    private fun errorObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        val o = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return if (o["error"] is JsonObject) o else null
    }

    /** JSON-RPC error with code in {-32020,-32021,-32022} on 400, or -32601 on 404. NOT -32602/-32000/-32600 (legacy servers emit those). */
    fun isModernError(status: Int, body: String?): Boolean {
        val code = errorObject(body)?.let { errorCode(it) } ?: return false
        return (status == 400 && code in setOf(-32020, -32021, -32022)) || (status == 404 && code == -32601)
    }

    /** error.data.supported for -32022. */
    fun supportedVersions(body: String?): List<String> =
        ((errorObject(body)?.get("error") as? JsonObject)?.get("data") as? JsonObject)?.get("supported")?.let { it as? JsonArray }?.mapNotNull { it.asTextOrNull() } ?: emptyList()

    private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /** content[] -> text (+ first image on vision targets); capped; isError/inputRequired passthrough. */
    fun render(r: CallResult, vision: Boolean, cap: Int = RESULT_CAP): Rendered {
        if (r.inputRequired) return Rendered("the tool needs interactive input (elicitation) — not supported by Mahout", null, null, true)
        val parts = ArrayList<String>()
        var image: String? = null; var imageMime: String? = null
        for (b in r.content.filterIsInstance<JsonObject>()) {
            when (b["type"].asTextOrNull()) {
                "text" -> b["text"].asTextOrNull()?.let { parts += it }
                "image" -> {
                    val mime = b["mimeType"].asTextOrNull()?.lowercase() ?: "image/*"
                    val data = b["data"].asTextOrNull() ?: ""
                    val bytes = data.length.toLong() * 3 / 4
                    // ponytail: images ≤ 5 MB kept in memory for one tool result; upgrade = spill to cacheDir like screenshots
                    if (vision && image == null && mime in IMAGE_MIMES && bytes <= MAX_IMAGE && data.isNotBlank()) { image = data; imageMime = mime }
                    else parts += "[image $mime, ${bytes / 1024} KB omitted]"
                }
                "audio" -> parts += "[audio ${b["mimeType"].asTextOrNull() ?: "audio/*"} omitted]"
                "resource_link" -> parts += "[link] ${b["uri"].asTextOrNull() ?: ""} ${b["name"].asTextOrNull() ?: ""} — ${b["description"].asTextOrNull() ?: ""}".trimEnd(' ', '—')
                "resource" -> {
                    val res = b["resource"] as? JsonObject
                    val uri = res?.get("uri").asTextOrNull() ?: ""
                    val text = res?.get("text").asTextOrNull()
                    if (text != null) parts += "[resource $uri]\n$text"
                    else parts += "[resource $uri ${res?.get("mimeType").asTextOrNull() ?: "application/octet-stream"}, ${(res?.get("blob").asTextOrNull()?.length ?: 0).toLong() * 3 / 4 / 1024} KB binary omitted]"
                }
            }
        }
        if (r.content.none { (it as? JsonObject)?.get("type").asTextOrNull() == "text" } && r.structured != null && r.structured !is JsonNull)
            parts += JSON.encodeToString(JsonElement.serializer(), r.structured)
        val text = parts.joinToString("\n").let { if (it.length > cap) it.take(cap) + "…(truncated)" else it }
        return Rendered(text, image, imageMime, r.isError)
    }

    /** "<server>: <message> (code <code>)"; -32022 -> "… supports only <supported>". */
    fun rpcError(e: JsonObject, s: McpServer): String {
        val err = e["error"] as? JsonObject ?: e
        val code = err["code"].asTextOrNull()?.toIntOrNull()
        val msg = (err["message"].asTextOrNull() ?: "error").take(300)
        val base = "${s.name}: $msg (code ${code ?: "?"})"
        if (code == -32022) {
            val sup = (err["data"] as? JsonObject)?.get("supported")?.let { it as? JsonArray }?.mapNotNull { it.asTextOrNull() } ?: emptyList()
            return "$base — supports only ${sup.joinToString(", ").ifBlank { "unknown versions" }}"
        }
        return base
    }

    fun errorMessage(status: Int, body: String?, s: McpServer, location: String? = null): String {
        val msg = clean(body?.let { b -> errorObject(b)?.let { (it["error"] as JsonObject)["message"].asTextOrNull() } ?: b.trim() }, null)
        return when (status) {
            401, 403 -> "${s.name}: authentication failed — check the token/header in Settings > AI > MCP servers"
            404 -> if (errorObject(body) == null) "${s.name}: no MCP endpoint at ${s.url} (use the server's Streamable HTTP URL, usually …/mcp)" else "${s.name} error 404: $msg"
            405 -> "${s.name} only offers the retired HTTP+SSE transport (2024-11-05) — not supported"
            301, 302, 303, 307, 308 -> "${s.name} redirected to ${location ?: "another URL"}; use that URL"
            429 -> "${s.name}: rate limited, retry later"
            else -> if (status >= 500) "${s.name} error $status${if (msg.isBlank()) "" else ": $msg"}" else "${s.name} error $status${if (msg.isBlank()) "" else ": $msg"}"
        }
    }

    private val BEARER = Regex("Bearer\\s+\\S+")

    /** Masks the secret value and "Bearer …"; ≤ 300 chars. */
    fun clean(msg: String?, secret: String?): String {
        var s = msg ?: ""
        if (!secret.isNullOrBlank()) s = s.replace(secret, "***")
        s = s.replace(BEARER, "***")
        return s.take(300)
    }
}
