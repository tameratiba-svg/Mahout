package com.mob8n.actions

import android.Manifest
import android.app.DownloadManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mob8n.core.ExecMode
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.Template
import com.mob8n.core.add
import com.mob8n.core.asText
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.labels
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Pure CSV helpers (unit-tested). RFC 4180 quoting, LF line ends. */
object Csv {
    fun escape(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun cell(e: JsonElement?): String = when (e) { null, is JsonNull -> ""; is JsonPrimitive -> e.content; else -> e.asText() }
    /** Explicit fields, else the union of item keys in first-seen order. */
    fun header(items: Items, fields: List<String>): List<String> = if (fields.isNotEmpty()) fields else LinkedHashSet<String>().apply { items.forEach { addAll(it.keys) } }.toList()
    fun render(items: Items, fields: List<String>, includeHeader: Boolean): String {
        val cols = header(items, fields)
        val sb = StringBuilder()
        if (includeHeader) sb.append(cols.joinToString(",") { escape(it) }).append('\n')
        for (it in items) sb.append(cols.joinToString(",") { c -> escape(cell(it[c])) }).append('\n')
        return sb.toString()
    }
}

/** Files under a public folder (<dir>/Mob8N/): MediaStore.Files on 29+, plain File on 26-28 (needs WRITE_EXTERNAL_STORAGE). */
object PublicFiles {
    const val SUBDIR = "Mob8N"
    class Target(val uri: String, val existed: Boolean, val stream: OutputStream, private val finish: () -> Unit) {
        fun close() { runCatching { stream.close() }; finish() }
    }

    fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "untitled" }.take(120)

    fun requireLegacyWrite(ctx: Context) {
        if (Build.VERSION.SDK_INT <= 28 && !Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE).granted(ctx)) throw NodeException("Needs storage permission")
    }

    private fun legacyFile(dir: String, name: String): File = File(File(Environment.getExternalStoragePublicDirectory(dir), SUBDIR), name)

    private fun findRow(ctx: Context, relPath: String, name: String): Uri? {
        val coll = MediaStore.Files.getContentUri("external")
        ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(name, relPath), null)?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(coll, c.getLong(0))
        }
        return null
    }

    /** [dir] is an Environment.DIRECTORY_* name. Caller writes to [Target.stream] then calls close(). */
    fun open(ctx: Context, dir: String, name: String, mime: String, append: Boolean): Target {
        if (Build.VERSION.SDK_INT < 29) {
            requireLegacyWrite(ctx)
            val f = legacyFile(dir, name)
            f.parentFile?.mkdirs()
            val existed = f.exists()
            return Target(Uri.fromFile(f).toString(), existed, FileOutputStream(f, append)) {}
        }
        val relPath = "$dir/$SUBDIR/"
        val cr = ctx.contentResolver
        try {
            findRow(ctx, relPath, name)?.let { uri ->
                val s = cr.openOutputStream(uri, if (append) "wa" else "wt") ?: throw NodeException("Cannot open $name for writing")
                return Target(uri.toString(), true, s) {}
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Files.getContentUri("external"), cv) ?: throw NodeException("MediaStore refused to create $name in $relPath")
            val s = cr.openOutputStream(uri, "w") ?: throw NodeException("Cannot open $name for writing")
            return Target(uri.toString(), false, s) { runCatching { cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } }
        } catch (e: SecurityException) {
            throw NodeException("$name in $relPath belongs to another app; pick another file name", e)
        }
    }

    /** Current text content, or null when the file does not exist / cannot be read. */
    fun read(ctx: Context, dir: String, name: String): String? = runCatching {
        if (Build.VERSION.SDK_INT < 29) legacyFile(dir, name).takeIf { it.exists() }?.readText()
        else findRow(ctx, "$dir/$SUBDIR/", name)?.let { u -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } }
    }.getOrNull()
}

object WriteFileNode : Node() {
    override val spec = NodeSpec(
        id = "action.write_file", name = "Write file", kind = NodeKind.ACTION,
        description = "Append or write lines, CSV rows or JSON to a file in Mahout's storage or the public Documents/Mob8N folder.",
        params = listOf(
            choice("target", "Target", listOf("app_storage", "documents"), "documents"),
            text("fileName", "File name", "mob8n.txt", required = true),
            choice("format", "Format", listOf("line", "csv", "json")),
            multiline("content", "Line content", "{{\$json}}", help = "line format: rendered once per item"),
            labels("csvFields", "CSV columns", help = "Blank = all item keys"),
            bool("overwrite", "Overwrite", false),
        ),
        gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE))), mode = ExecMode.LIST, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val items = input.items
        val name = PublicFiles.safeName(ctx.req("fileName"))
        val format = ctx.str("format")
        val overwrite = ctx.bool("overwrite")
        val fields = ctx.labels("csvFields")
        val rawContent = ctx.raw("content").asText().ifBlank { "{{\$json}}" }
        val mime = when (format) { "csv" -> "text/csv"; "json" -> "application/json"; else -> "text/plain" }
        val (uri, bytes) = withContext(Dispatchers.IO) {
            val target: PublicFiles.Target = if (ctx.str("target") == "app_storage") {
                val f = File(a.filesDir, name)
                PublicFiles.Target(Uri.fromFile(f).toString(), f.exists(), FileOutputStream(f, !overwrite)) {}
            } else PublicFiles.open(a, Environment.DIRECTORY_DOCUMENTS, name, mime, !overwrite)
            val textOut = when (format) {
                "csv" -> Csv.render(items, fields, includeHeader = overwrite || !target.existed)
                // ponytail: json append = one object per line (JSONL), overwrite = array; upgrade = read-merge-write the array
                "json" -> if (overwrite) JSON.encodeToString(JsonArray.serializer(), JsonArray(items)) + "\n" else items.joinToString("") { JSON.encodeToString(JsonElement.serializer(), it) + "\n" }
                else -> items.mapIndexed { i, it -> Template.render(rawContent, ctx.scope.copy(item = it, index = i)) }.joinToString("") { it + "\n" }
            }
            val data = textOut.toByteArray(Charsets.UTF_8)
            try { target.stream.write(data); target.stream.flush() } finally { target.close() }
            target.uri to data.size
        }
        ctx.log("wrote $bytes bytes to $uri")
        return out(items.map { it.add("uri" to uri, "bytesWritten" to bytes) })
    }
}

object DownloadNode : Node() {
    override val spec = NodeSpec(
        id = "action.download", name = "Download", kind = NodeKind.ACTION,
        description = "Download a URL to the public Downloads folder with the system download manager.",
        params = listOf(text("url", "URL", required = true), text("fileName", "File name"), bool("wifiOnly", "Wi-Fi only", false)),
        gates = listOf(Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE)), agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val url = ctx.req("url").trim()
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) throw NodeException("Download URL must be http(s): $url")
        val name = PublicFiles.safeName(ctx.strOrNull("fileName") ?: uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "download")
        val req = DownloadManager.Request(uri)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle(name)
        if (ctx.bool("wifiOnly")) req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = try { dm.enqueue(req) } catch (e: Exception) { throw NodeException("Download manager rejected the request: ${e.message}", e) }
        return out(ctx.item.add("downloadId" to id))
    }
}
