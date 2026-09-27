package com.mob8n.data

import android.net.Uri
import com.mob8n.core.EMPTY
import com.mob8n.core.ExecMode
import com.mob8n.core.ExecutionContext
import com.mob8n.core.JSON
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.asDouble
import com.mob8n.core.choice
import com.mob8n.core.item
import com.mob8n.core.out
import com.mob8n.core.text
import com.mob8n.core.whenIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.floor

object ReadFileNode : Node() {
    const val MAX_FILE = 1024 * 1024

    override val spec = NodeSpec(
        id = "data.read_file", name = "Read File", kind = NodeKind.DATA,
        description = "Reads a file (<= 1 MB) from Mahout's app storage or a picked document as lines, whole text or JSON.",
        params = listOf(
            choice("source", "Source", listOf("app_storage", "document")),
            text("name", "File name", help = "Inside Mahout's private files folder (as written by Write File)", visibleWhen = whenIs("source", "app_storage")),
            text("documentUri", "Document", help = "content:// uri from the document picker", visibleWhen = whenIs("source", "document")),
            choice("mode", "Mode", listOf("lines", "whole", "json")),
        ),
        mode = ExecMode.LIST, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val bytes = withContext(Dispatchers.IO) {
            if (ctx.str("source") == "document") {
                val u = ctx.req("documentUri").trim()
                val uri = Uri.parse(u)
                if (uri.scheme != "content") throw NodeException("Document must be a content:// uri (pick it with the document picker)")
                try {
                    a.contentResolver.openInputStream(uri)?.use { Http.readCapped(it, MAX_FILE) } ?: throw NodeException("Cannot open document $u")
                } catch (e: FileNotFoundException) { throw NodeException("Document not found: $u", e) }
                catch (e: SecurityException) { throw NodeException("No permission for document $u (pick it again)", e) }
            } else {
                val name = ctx.req("name").trim()
                val f = safeFile(a.filesDir, name)
                if (!f.isFile) throw NodeException("No such file in app storage: $name")
                if (f.length() > MAX_FILE) throw NodeException("File '$name' is larger than 1 MB")
                f.readBytes()
            }
        }
        val textBody = String(bytes, Charsets.UTF_8)
        val items: List<JsonObject> = when (ctx.str("mode")) {
            "whole" -> input.items.ifEmpty { listOf(EMPTY) }.map { it.add("text" to textBody) }
            "json" -> parseJsonItems(textBody)
            else -> lines(textBody).mapIndexed { i, l -> item("line" to l, "index" to i) }
        }
        return out(items)
    }

    /** Rejects path traversal: the resolved file must stay inside [dir]. */
    fun safeFile(dir: File, name: String): File {
        if (name.isBlank() || name.contains('\u0000')) throw NodeException("Invalid file name")
        val base = dir.canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.path != base.path && !f.path.startsWith(base.path + File.separator)) throw NodeException("Invalid file name: $name")
        return f
    }

    fun lines(text: String): List<String> = text.lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }

    fun parseJsonItems(text: String): List<JsonObject> {
        val e = runCatching { JSON.parseToJsonElement(text) }.getOrElse { throw NodeException("File is not valid JSON: ${it.message}") }
        return when (e) {
            is JsonObject -> listOf(e)
            is JsonArray -> e.map { it as? JsonObject ?: item("value" to it) }
            else -> listOf(item("value" to e))
        }
    }
}

object VariableNode : Node() {
    override val spec = NodeSpec(
        id = "data.variable", name = "Variable", kind = NodeKind.DATA,
        description = "Gets, sets, increments, appends to or deletes a global variable shared by all workflows.",
        params = listOf(
            choice("op", "Operation", listOf("get", "set", "increment", "append", "delete")),
            text("name", "Name", required = true),
            text("value", "Value", help = "Text or JSON (numbers, true/false, {..}, [..]); for increment: the step (default 1)", visibleWhen = whenIs("op", "set", "increment", "append")),
            text("outputField", "Output field", "value"),
        ),
        agentTool = true,
    )

    private val NAME = Regex("[A-Za-z0-9_.-]{1,100}")

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val name = ctx.req("name").trim()
        if (!NAME.matches(name)) throw NodeException("Variable name must be letters, digits, _ . - (max 100)")
        val field = ctx.strOrNull("outputField") ?: "value"
        val v = typed(ctx.str("value"))
        val result: JsonElement = when (ctx.str("op")) {
            "get" -> ctx.getVar(name) ?: JsonNull
            "set" -> v.also { ctx.setVar(name, it) }
            "increment" -> {
                val cur = ctx.getVar(name).asDouble() ?: 0.0
                val n = cur + (v.asDouble() ?: 1.0)
                val e: JsonElement = if (n == floor(n) && abs(n) < 1e15) JsonPrimitive(n.toLong()) else JsonPrimitive(n)
                ctx.setVar(name, e); e
            }
            "append" -> {
                val cur = ctx.getVar(name)
                val existing = (cur as? JsonArray)?.toList() ?: if (cur == null || cur is JsonNull) emptyList() else listOf(cur)
                val e = JsonArray(existing + v)
                ctx.setVar(name, e); e
            }
            else -> { ctx.setVar(name, null); JsonNull }
        }
        return out(ctx.item.add(field to result))
    }

    /** "42" -> 42, "true" -> true, "{..}"/"[..]" -> parsed, anything else -> string. Blank -> "". */
    fun typed(s: String): JsonElement {
        val t = s.trim()
        if (t.isEmpty()) return JsonPrimitive("")
        val looksJson = t.startsWith("{") || t.startsWith("[") || t.startsWith("\"") || t == "true" || t == "false" || t == "null" ||
            t.matches(Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?"))
        return if (looksJson) runCatching { JSON.parseToJsonElement(t) }.getOrElse { JsonPrimitive(s) } else JsonPrimitive(s)
    }
}
