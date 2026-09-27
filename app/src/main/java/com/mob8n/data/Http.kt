package com.mob8n.data

import com.mob8n.core.ExecutionContext
import com.mob8n.core.JSON
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.addAll
import com.mob8n.core.asText
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.item
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.rows
import com.mob8n.core.secret
import com.mob8n.core.str
import com.mob8n.core.text
import com.mob8n.core.whenIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/** Pure, Android-free helpers for data.http (unit-tested in data/HttpParseTest). */
object Http {
    const val MAX_BODY = 5 * 1024 * 1024
    const val MAX_TIMEOUT_MS = 120_000L
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    /** Header ROWS -> ordered map. Blank names are skipped; invalid names / CR-LF in values are rejected. */
    fun headerMap(rows: List<JsonObject>): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (r in rows) {
            val name = r.str("name")?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (!TOKEN.matches(name)) throw NodeException("Invalid header name '$name'")
            val value = r.str("value").orEmpty()
            if (value.any { it == '\r' || it == '\n' }) throw NodeException("Header '$name' must not contain line breaks")
            m[name] = value
        }
        return m
    }

    /** Reads at most [cap] bytes; anything larger is a NodeException (never buffers more than the cap). */
    fun readCapped(input: InputStream, cap: Int = MAX_BODY): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) throw NodeException("Content larger than ${cap / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun charsetOf(contentType: String?): Charset = contentType?.split(';')?.drop(1)?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')?.trim('"', '\'', ' ')
        ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    /** Parsed JSON when the content type says json, or the text looks like JSON, AND it parses; else null. */
    fun parseJson(text: String, contentType: String?): JsonElement? {
        val t = text.trim()
        val declared = contentType?.contains("json", ignoreCase = true) == true
        val looks = t.startsWith("{") || t.startsWith("[")
        if (!declared && !looks) return null
        if (t.isEmpty()) return null
        return runCatching { JSON.parseToJsonElement(t) }.getOrNull()
    }

    /** A JSON object body becomes k=v&k2=v2 (url-encoded); anything else is passed through as already-encoded. */
    fun formEncode(body: String): String {
        val obj = runCatching { JSON.parseToJsonElement(body.trim()) }.getOrNull() as? JsonObject ?: return body
        return obj.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v.asText(), "UTF-8") }
    }

    /**
     * Output fields for one response. `body` is the parsed JSON when the response is JSON, else the text (DESIGN §4.2);
     * `json` additionally carries the parsed element or null so templates can rely on either.
     * Non-2xx -> NodeException when [failOnHttpError].
     */
    fun result(status: Int, headers: Map<String, List<String>>, bodyText: String, url: String, failOnHttpError: Boolean): JsonObject {
        // HttpURLConnection puts the status line under a null key; drop it.
        val hdrs = LinkedHashMap<String, String>()
        for ((k, v) in headers) if (k != null) hdrs[k.lowercase()] = v.joinToString(", ")
        val contentType = hdrs["content-type"]
        val json = parseJson(bodyText, contentType)
        val ok = status in 200..299
        if (!ok && failOnHttpError) throw NodeException("HTTP $status from $url: ${bodyText.take(200)}")
        return item(
            "status" to status, "ok" to ok, "headers" to hdrs,
            "body" to (json ?: JsonPrimitive(bodyText)), "json" to (json ?: JsonNull),
            "url" to url, "contentType" to contentType,
        )
    }
}

object HttpNode : Node() {
    override val spec = NodeSpec(
        id = "data.http", name = "HTTP Request", kind = NodeKind.DATA,
        description = "Calls a URL with GET/POST/PUT/PATCH/DELETE and returns status, headers and the (JSON-parsed) body.",
        params = listOf(
            choice("method", "Method", listOf("GET", "POST", "PUT", "PATCH", "DELETE")),
            text("url", "URL", required = true, help = "https://... ({{templates}} allowed)"),
            rows("headers", "Headers", listOf(text("name", "Name"), text("value", "Value"))),
            choice("bodyType", "Body type", listOf("none", "json", "form", "text")),
            multiline("body", "Body", help = "JSON object; form fields as a JSON object or a=b&c=d; or plain text", visibleWhen = whenIs("bodyType", "json", "form", "text")),
            durationMs("timeoutMs", "Timeout", 30_000, 1_000, Http.MAX_TIMEOUT_MS),
            secret("authSecret", "Auth secret", "Sent as 'Authorization: Bearer <secret>'"),
            bool("failOnHttpError", "Fail on non-2xx status", true),
            bool("allowHttp", "Allow plain http://", false, help = "Currently blocked by Mahout's network policy (cleartext disabled); https only"),
        ),
        timeoutMs = Http.MAX_TIMEOUT_MS + 5_000, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val method = ctx.str("method")
        val urlStr = ctx.req("url").trim()
        val url = runCatching { URL(urlStr) }.getOrElse { throw NodeException("Invalid URL: $urlStr") }
        when (url.protocol) {
            "https" -> Unit
            "http" -> {
                if (!ctx.bool("allowHttp")) throw NodeException("Plain http:// is blocked; enable 'Allow plain http://' to use $urlStr")
                // ponytail: manifest usesCleartextTraffic=false and no network_security_config, so allowHttp cannot actually
                // enable cleartext; fail here with an honest message instead of an opaque "Request failed: CLEARTEXT ..." from
                // HttpURLConnection. Upgrade path = per-domain <domain-config cleartextTrafficPermitted="true"> for LAN hosts.
                // runCatching: getInstance() is a stub (null) in JVM unit tests -> treated as "not permitted".
                val permitted = runCatching { android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(url.host) }.getOrDefault(false)
                if (!permitted) throw NodeException("Plain http:// to ${url.host} is blocked by Mahout's network policy (cleartext disabled app-wide); use https://")
            }
            else -> throw NodeException("Only http(s) URLs are supported: $urlStr")
        }
        val headers = Http.headerMap(ctx.rows("headers"))
        ctx.strOrNull("authSecret")?.let { headers["Authorization"] = "Bearer ${ctx.secret(it)}" }
        val bodyType = ctx.str("bodyType")
        val body = ctx.str("body")
        val (contentType, payload) = when (bodyType) {
            "json" -> {
                runCatching { JSON.parseToJsonElement(body) }.getOrElse { throw NodeException("Body is not valid JSON: ${it.message}") }
                "application/json; charset=utf-8" to body
            }
            "form" -> "application/x-www-form-urlencoded; charset=utf-8" to Http.formEncode(body)
            "text" -> "text/plain; charset=utf-8" to body
            else -> null to null
        }
        val timeout = (ctx.long("timeoutMs") ?: 30_000L).coerceIn(1_000L, Http.MAX_TIMEOUT_MS).toInt()
        val failOnError = ctx.bool("failOnHttpError")
        ctx.log("$method $urlStr")   // never log header values (Authorization)
        return withContext(Dispatchers.IO) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json, text/*;q=0.9, */*;q=0.8")
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (payload != null && method != "GET") {
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                val stream = (if (status >= 400) conn.errorStream else conn.inputStream) ?: ByteArrayInputStream(ByteArray(0))
                val bytes = stream.use { Http.readCapped(it) }
                val textBody = String(bytes, Http.charsetOf(conn.contentType))
                ctx.log("$method $urlStr -> $status (${bytes.size} bytes)")
                out(ctx.item.addAll(Http.result(status, conn.headerFields, textBody, conn.url.toString(), failOnError)))
            } catch (e: NodeException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw NodeException("Timed out after $timeout ms: $urlStr", e)
            } catch (e: IOException) {
                throw NodeException("Request failed: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                conn.disconnect()
            }
        }
    }
}
