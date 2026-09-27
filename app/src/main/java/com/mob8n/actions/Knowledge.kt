package com.mob8n.actions

import android.net.Uri
import com.mob8n.Mob8NApp
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.text
import com.mob8n.core.whenIs

/**
 * DESIGN3 §5.9 — knowledge base action nodes. Both reach the index only through the Engine facade
 * (`Mob8NApp.of(ctx.requireAndroid()).engine.knowledge`); extraction errors are `NodeException`s and land on the error port.
 */

/**
 * Pure `auto` precedence: url → uri → text (first non-blank). An explicit [source] uses that value only.
 * Returns (kind, value) with kind ∈ {"url","uri","text"}; a blank pick throws.
 */
fun pickSource(source: String, text: String, uri: String, url: String): Pair<String, String> {
    val picks = listOf("url" to url, "uri" to uri, "text" to text)
    val pick = if (source == "auto") picks.firstOrNull { it.second.isNotBlank() } else picks.firstOrNull { it.first == source }
    if (pick == null || pick.second.isBlank()) throw NodeException("Give a document URI, a URL or text")
    return pick
}

object KnowledgeAddNode : Node() {
    override val spec = NodeSpec(
        id = "action.knowledge_add", name = "Add to knowledge", kind = NodeKind.ACTION,
        description = "Index text, a document (content:// or file://) or a web page into the on-device knowledge base the Agent can search.",
        params = listOf(
            choice("source", "Source", listOf("auto", "text", "uri", "url"), "auto"),
            multiline("text", "Text", "{{text}}", visibleWhen = whenIs("source", "auto", "text")),
            text("uri", "Document URI", "{{uri}}", help = "content:// from the picker or share sheet, or file:// inside Mahout storage", visibleWhen = whenIs("source", "auto", "uri")),
            text("url", "URL", "{{url}}", visibleWhen = whenIs("source", "auto", "url")),
            text("name", "Name", "{{fileName ?? title ?? subject ?? url}}", help = "Blank = file name / first line / host"),
            text("group", "Group"),
            bool("pinned", "Pin (always given to the agent)", false),
            bool("replace", "Replace same-named source", true),
        ),
        timeoutMs = 180_000, agentTool = true,   // Drive downloads
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val knowledge = Mob8NApp.of(ctx.requireAndroid()).engine.knowledge
        val (kind, value) = pickSource(ctx.str("source"), ctx.str("text"), ctx.str("uri"), ctx.str("url"))
        val name = ctx.strOrNull("name")
        val group = ctx.str("group"); val pinned = ctx.bool("pinned"); val replace = ctx.bool("replace")
        val src = when (kind) {
            "url" -> knowledge.addUrl(value, name, group, pinned, replace)
            "uri" -> knowledge.addDocument(Uri.parse(value), name, group, pinned, replace)
            else -> knowledge.addText(name ?: value.lineSequence().first { it.isNotBlank() }.trim().take(60), value, group, pinned, replace)
        }
        return out(ctx.item.add(
            "sourceId" to src.id, "name" to src.name, "kind" to src.kind.name,
            "chunks" to src.chunks, "chars" to src.chars, "bytes" to src.bytes,
            "truncated" to (src.error?.contains("truncated") == true),   // §5.6: a successful add carries only the truncation marker in `error`
        ))
    }
}

object KnowledgeRemoveNode : Node() {
    override val spec = NodeSpec(
        id = "action.knowledge_remove", name = "Remove from knowledge", kind = NodeKind.ACTION,
        description = "Remove a knowledge source (and its indexed text) by name or id.",
        params = listOf(text("name", "Source name or id", required = true)),
        timeoutMs = 10_000,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val removed = Mob8NApp.of(ctx.requireAndroid()).engine.knowledge.removeByName(ctx.req("name"))
        return out(ctx.item.add("removed" to removed))
    }
}
