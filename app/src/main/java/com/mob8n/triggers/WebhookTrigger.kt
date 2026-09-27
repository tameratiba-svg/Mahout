package com.mob8n.triggers

import com.mob8n.Mob8NApp
import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.JSON
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TriggerHost
import com.mob8n.core.TriggerInstance
import com.mob8n.core.TriggerNode
import com.mob8n.core.item
import com.mob8n.core.number
import com.mob8n.core.secret
import com.mob8n.core.str
import com.mob8n.core.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Minimal HTTP/1.1 listener on a daemon thread while a host lives. One ServerSocket per distinct port; requests are matched to the
 * instances on that port by path (+ X-Token == secret when tokenSecret is set) and fired with host.fireWorkflow (bypasses accepts()).
 * ponytail: sequential connection handling, 256 KB body cap, 5 s socket timeout; upgrade = thread pool + keep-alive if a real client needs it.
 */
object WebhookTrigger : TriggerNode() {
    private const val MAX_BODY = 256 * 1024

    override val hosting = Hosting.HOST_ATTACHED
    override val spec = NodeSpec(
        id = "trigger.webhook", name = "Webhook (LAN)", kind = NodeKind.TRIGGER,
        description = "Listens for HTTP GET/POST requests on the local network while Mahout's background host is running.",
        params = listOf(
            number("port", "Port", 8787.0, min = 1024.0, max = 65535.0),
            text("path", "Path", "/hook", templated = false),
            secret("tokenSecret", "Token secret name", help = "Requests must send X-Token equal to this secret"),
        ),
        inputs = emptyList(), gates = listOf(Gate.LiveHost), optional = true,
    )

    override fun accepts(params: JsonObject, event: JsonObject): Boolean = normPath(spec.pStr(params, "path") ?: "/hook") == normPath(event.str("path") ?: "")
    fun normPath(p: String): String = ("/" + p.trim().trimStart('/')).trimEnd('/').ifEmpty { "/" }
    /** Constant-time token compare (F31); false when the secret is unset/blank or the header is missing. */
    fun tokenOk(got: String?, expected: String?): Boolean = !expected.isNullOrBlank() &&
        java.security.MessageDigest.isEqual((got ?: "").toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val persistence = Mob8NApp.of(ctx).engine.persistence
        val servers = ArrayList<ServerSocket>()
        for ((port, insts) in instances.groupBy { (spec.pNum(it.params, "port") ?: 8787.0).toInt() }) {
            if (port !in 1024..65535) { logW("webhook: port $port out of range"); continue }
            val server = try { ServerSocket(port) } catch (e: Exception) { logW("webhook: cannot bind $port", e); continue }
            servers += server
            Thread({
                while (!server.isClosed) {
                    val sock = try { server.accept() } catch (e: Exception) { break }
                    try { sock.soTimeout = 5_000; handle(sock, insts, host) { name -> persistence.getSecret(name) } }
                    catch (e: Exception) { logW("webhook request", e) }
                    finally { try { sock.close() } catch (_: Exception) {} }
                }
            }, "mob8n-webhook-$port").apply { isDaemon = true }.start()
            logT("webhook listening on $port for ${insts.size} instance(s)")
        }
        if (servers.isEmpty()) return null
        return AutoCloseable { servers.forEach { try { it.close() } catch (_: Exception) {} } }
    }

    private fun handle(sock: Socket, insts: List<TriggerInstance>, host: TriggerHost, secretOf: (String) -> String?) {
        val ins = sock.getInputStream(); val out = sock.getOutputStream()
        val req = parse(ins) ?: return respond(out, 400, """{"ok":false,"error":"bad request"}""")
        if (req.tooLarge) return respond(out, 413, """{"ok":false,"error":"body too large"}""")
        val path = normPath(req.path)
        var fired = 0; var unauthorized = false
        for (inst in insts) {
            if (normPath(spec.pStr(inst.params, "path") ?: "/hook") != path) continue
            val secretName = spec.pStr(inst.params, "tokenSecret")
            if (secretName != null) {
                if (!tokenOk(req.headers["x-token"], secretOf(secretName))) { unauthorized = true; continue }
            }
            host.fireWorkflow(inst.workflowId, inst.nodeId, listOf(item(
                "method" to req.method, "path" to path, "query" to req.query, "headers" to req.headers.filterKeys { it != "x-token" },
                "body" to req.body, "remote" to sock.inetAddress?.hostAddress,
            )))
            fired++
        }
        when {
            fired > 0 -> respond(out, 200, """{"ok":true}""")
            unauthorized -> respond(out, 401, """{"ok":false,"error":"unauthorized"}""")
            else -> respond(out, 404, """{"ok":false,"error":"no workflow on $path"}""")
        }
    }

    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: JsonElement?, val tooLarge: Boolean)

    /** Request line + headers + Content-Length body. Public for tests. */
    fun parse(ins: InputStream): Request? {
        val line = readLine(ins) ?: return null
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(ins) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':'); if (i <= 0) continue
            headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
            decode(k) to decode(v)
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) return Request(method, path, query, headers, null, tooLarge = true)
        val bytes = ByteArray(len); var read = 0
        while (read < len) { val n = ins.read(bytes, read, len - read); if (n < 0) break; read += n }
        val text = String(bytes, 0, read, Charsets.UTF_8)
        val ct = headers["content-type"] ?: ""
        val body: JsonElement? = when {
            text.isEmpty() -> null
            ct.contains("json", true) || text.trimStart().let { it.startsWith("{") || it.startsWith("[") } ->
                try { JSON.parseToJsonElement(text) } catch (e: Exception) { JsonPrimitive(text) }
            else -> JsonPrimitive(text)
        }
        return Request(method, path, query, headers, body, tooLarge = false)
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, json: String) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val body = json.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
    }
}
