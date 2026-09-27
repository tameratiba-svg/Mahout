package com.mob8n.actions

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import com.mob8n.core.ExecutionContext
import com.mob8n.core.LOG_TAG
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.Note
import com.mob8n.core.add
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.text
import com.mob8n.core.workflowPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object ClipboardSetNode : Node() {
    override val spec = NodeSpec(
        id = "action.clipboard_set", name = "Copy to clipboard", kind = NodeKind.ACTION,
        description = "Put text on the clipboard.",
        params = listOf(multiline("text", "Text", "{{text}}", required = true), bool("sensitive", "Sensitive (hide preview)", false)),
        agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val t = ctx.req("text")
        withContext(Dispatchers.Main.immediate) {
            val clip = ClipData.newPlainText("Mahout", t)
            if (Build.VERSION.SDK_INT >= 33 && ctx.bool("sensitive")) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }
        return out(ctx.item)
    }
}

object ToastNode : Node() {
    override val spec = NodeSpec(
        id = "action.toast", name = "Toast", kind = NodeKind.ACTION,
        description = "Show a short on-screen toast message.",
        params = listOf(text("text", "Text", required = true), bool("long", "Long duration", false)),
        agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val t = ctx.req("text")
        val long = ctx.bool("long")
        withContext(Dispatchers.Main.immediate) { Toast.makeText(a, t, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        return out(ctx.item)
    }
}

object WallpaperNode : Node() {
    private const val MAX_BYTES = 25L * 1024 * 1024
    override val spec = NodeSpec(
        id = "action.wallpaper", name = "Set wallpaper", kind = NodeKind.ACTION,
        description = "Set the home and/or lock screen wallpaper from an image URI or URL.",
        params = listOf(text("imageUri", "Image URI", "{{uri}}", required = true), choice("target", "Target", listOf("home", "lock", "both"), "home")),
        timeoutMs = 60_000, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val src = ctx.req("imageUri").trim()
        val flags = when (ctx.str("target")) { "lock" -> WallpaperManager.FLAG_LOCK; "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK; else -> WallpaperManager.FLAG_SYSTEM }
        withContext(Dispatchers.IO) {
            var stream: InputStream? = null
            try {   // F46: a raw IOException from the download must surface as a NodeException, not escape the node
                stream = openImage(a, src)
                WallpaperManager.getInstance(a).setStream(stream, null, true, flags)
            } catch (e: NodeException) { throw e
            } catch (e: Exception) { throw NodeException("Could not set wallpaper: ${e.message}", e) } finally { runCatching { stream?.close() } }
        }
        return out(ctx.item)
    }

    private fun openImage(a: Context, src: String): InputStream {
        val uri = Uri.parse(src)
        return when (uri.scheme) {
            "http" -> throw NodeException("Image URL must be https:// (plain http is blocked by network policy)")   // F46: usesCleartextTraffic=false
            "https" -> {
                val c = (URL(src).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true }
                if (c.responseCode !in 200..299) { c.disconnect(); throw NodeException("Image download failed: HTTP ${c.responseCode}") }
                if (c.contentLengthLong > MAX_BYTES) { c.disconnect(); throw NodeException("Image larger than 25 MB") }
                CappedInputStream(c.inputStream, MAX_BYTES, "Image") // chunked bodies report -1 above; cap the actual bytes too
            }
            "content", "file", "android.resource" -> a.contentResolver.openInputStream(uri) ?: throw NodeException("Cannot open image $src")
            else -> throw NodeException("Image URI must be content://, file:// or https://")
        }
    }
}

/** Throws NodeException once more than [cap] bytes have been read. */
// ponytail: duplicates data.Http.readCapped; the lane import rule (DESIGN §2) forbids actions -> data, upgrade path = move a capped-stream helper into core
internal class CappedInputStream(src: InputStream, private val cap: Long, private val what: String) : FilterInputStream(src) {
    private var n = 0L
    override fun read(): Int { val b = super.read(); if (b >= 0) bump(1); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) bump(r); return r }
    private fun bump(k: Int) { n += k; if (n > cap) throw NodeException("$what larger than ${cap / (1024 * 1024)} MB") }
}

object SaveNoteNode : Node() {
    override val spec = NodeSpec(
        id = "action.save_note", name = "Save note", kind = NodeKind.ACTION,
        description = "Save a note (title + body) in Mahout's Notes screen.",
        params = listOf(text("title", "Title", "{{title}}"), multiline("body", "Body", "{{\$json}}", required = true)),
        agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val body = ctx.req("body")
        val title = ctx.strOrNull("title") ?: body.lineSequence().first().take(60)
        val id = ctx.persistence.addNote(Note(title = title, body = body, createdAt = ctx.nowMs(), runId = ctx.runId))
        return out(ctx.item.add("noteId" to id))
    }
}

object ScheduleRunNode : Node() {
    override val spec = NodeSpec(
        id = "action.schedule_run", name = "Schedule run", kind = NodeKind.ACTION,
        description = "Run another workflow after a delay, optionally passing the current item.",
        params = listOf(
            workflowPicker("workflow", "Workflow"),
            durationMs("delayMs", "Delay", 60_000, minMs = 1_000, maxMs = 30L * 24 * 3_600_000),
            choice("payload", "Payload", listOf("current_item", "none")),
        ),
        agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val wf = ctx.req("workflow")
        val delay = ctx.long("delayMs") ?: 60_000
        if (ctx.persistence.loadWorkflow(wf) == null) throw NodeException("Workflow $wf does not exist")
        val items = if (ctx.str("payload") == "none") emptyList() else listOf(ctx.item)
        ctx.hooks.scheduleRun(wf, delay, items)
        return out(ctx.item.add("scheduled" to true, "delayMs" to delay))
    }
}

object ToggleWorkflowNode : Node() {
    override val spec = NodeSpec(
        id = "action.toggle_workflow", name = "Enable / disable workflow", kind = NodeKind.ACTION,
        description = "Enable, disable or toggle another workflow.",
        params = listOf(workflowPicker("workflow", "Workflow"), choice("state", "State", listOf("enable", "disable", "toggle"), "toggle")),
        agentTool = false,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val id = ctx.req("workflow")
        val wf = ctx.persistence.loadWorkflow(id) ?: throw NodeException("Workflow $id does not exist")
        val enabled = when (ctx.str("state")) { "enable" -> true; "disable" -> false; else -> !wf.enabled }
        ctx.hooks.setWorkflowEnabled(id, enabled)
        return out(ctx.item.add("enabled" to enabled, "workflow" to id))
    }
}

object LogNode : Node() {
    override val spec = NodeSpec(
        id = "action.log", name = "Log", kind = NodeKind.ACTION,
        description = "Write a message to the run log (and Logcat) and pass the item through.",
        params = listOf(multiline("message", "Message", "{{\$json}}", required = true), choice("level", "Level", listOf("info", "warn", "error"))),
        agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val msg = ctx.req("message")
        val level = ctx.str("level")
        ctx.log("[$level] $msg")
        when (level) { "error" -> Log.e(LOG_TAG, msg); "warn" -> Log.w(LOG_TAG, msg); else -> Log.i(LOG_TAG, msg) }
        return out(ctx.item.add("log" to msg))
    }
}
