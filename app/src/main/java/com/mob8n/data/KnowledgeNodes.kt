package com.mob8n.data

import com.mob8n.Mob8NApp
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.addAll
import com.mob8n.core.choice
import com.mob8n.core.item
import com.mob8n.core.labels
import com.mob8n.core.multiline
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.engine.knowledge.Hit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * DESIGN3 §5.9 `data.knowledge_search`. agentTool = false on purpose: the Agent binds its own `knowledge_search` tool
 * (DESIGN3 W13); a node tool would duplicate it. Results are the user's own documents — downstream prompts should frame
 * `{{context}}` as data (the Agent's system prompt already does).
 */
object KnowledgeSearchNode : Node() {
    override val spec = NodeSpec(
        id = "data.knowledge_search", name = "Knowledge search", kind = NodeKind.DATA,
        description = "Searches the on-device knowledge base (documents, folders, web pages, notes) and returns the most relevant passages with their source names.",
        params = listOf(
            multiline("query", "Query", "{{text}}", required = true),
            number("k", "Results", 5.0, 1.0, 20.0),
            labels("sources", "Sources", help = "Source, folder or group names, or 'all'; empty = all"),
            choice("output", "Output", listOf("merged", "items"), "merged"),
        ),
        agentTool = false,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val query = ctx.req("query")
        val k = (ctx.int("k") ?: 5).coerceIn(1, 20)
        val knowledge = Mob8NApp.of(ctx.requireAndroid()).engine.knowledge
        val labels = ctx.labels("sources")
        val ids = if (labels.isEmpty()) emptyList() else knowledge.resolve(labels)
        val hits = if (labels.isNotEmpty() && ids.isEmpty()) {
            ctx.log("knowledge: no source matches $labels")           // restricted to nothing = nothing, never "all"
            emptyList()
        } else knowledge.search(query, k, ids)
        ctx.log("${hits.size} hits for '${query.take(60)}'")
        return if (ctx.str("output") == "items") out(hits.map { ctx.item.addAll(hitItem(it)) })
        else out(ctx.item.addAll(merge(hits)))
    }

    /** Pure: `{count, context, hits}` — `context` = "### source\ntext" blocks joined by blank lines; empty hits -> count 0, context "". */
    fun merge(hits: List<Hit>): JsonObject = item(
        "count" to hits.size,
        "context" to hits.joinToString("\n\n") { "### ${it.source}\n${it.text}" },
        "hits" to JsonArray(hits.map(::hitItem)),
    )

    fun hitItem(h: Hit): JsonObject = item("text" to h.text, "source" to h.source, "sourceId" to h.sourceId, "seq" to h.seq, "score" to h.score)
}
