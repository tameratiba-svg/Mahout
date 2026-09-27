package com.mob8n.ai

import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.PORT_OTHER
import com.mob8n.core.add
import com.mob8n.core.addAll
import com.mob8n.core.asTextOrNull
import com.mob8n.core.choice
import com.mob8n.core.labels
import com.mob8n.core.multiline
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.route
import com.mob8n.core.rows
import com.mob8n.core.str
import com.mob8n.core.text
import com.mob8n.core.whenIs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Param helpers shared by the four AI nodes (DESIGN2 §5.1).
private val NOT_NANO = whenIs("provider", *(PROVIDERS - PROVIDER_NANO).toTypedArray())
private val EFFORT_ON = whenIs("provider", PROVIDER_DEFAULT, PROVIDER_AUTO, PROVIDER_CLAUDE)
private val TEMP_ON = whenIs("provider", *(listOf(PROVIDER_DEFAULT, PROVIDER_AUTO) + Providers.HTTP.map { it.id }).toTypedArray())
internal fun providerParam() = choice("provider", "Provider", PROVIDERS, PROVIDER_DEFAULT, help = "default = the Default AI chosen in Settings > AI (auto is the same)")
internal fun modelParam() = text("model", "Model", help = "Blank = the provider's default (or the Default AI model)", templated = false, visibleWhen = NOT_NANO)
internal fun effortParam(d: String) = choice("effort", "Effort", AiPrefs.EFFORTS, d, visibleWhen = EFFORT_ON)
internal fun temperatureParam() = number("temperature", "Temperature", min = 0.0, max = 2.0, help = "Blank = provider default", visibleWhen = TEMP_ON)
private fun imageParam() = text("imageUri", "Image URI", help = "content:// or file:// image to include")

object AskAiNode : Node() {
    override val spec = NodeSpec(
        id = "ai.ask", name = "Ask AI", kind = NodeKind.AI, description = "Sends a prompt to the configured AI provider and adds the answer to the item",
        params = listOf(
            providerParam(),
            multiline("system", "System prompt"),
            multiline("prompt", "Prompt", "{{text}}", required = true),
            imageParam(),
            modelParam(), effortParam("high"), temperatureParam(),
            number("maxTokens", "Max tokens", 4096.0, 256.0, 16000.0),
            choice("outputMode", "Output", listOf("text", "json"), "text", help = "json = merge the returned JSON object's fields into the item"),
            text("outputField", "Output field", "answer", help = "Field that receives the text answer"),
        ),
        timeoutMs = 120_000, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val t = Llm.target(ctx)
        val json = ctx.str("outputMode") == "json"
        var prompt = ctx.req("prompt")
        if (json && t.provider == null) prompt += "\n\nRespond with ONLY a JSON object (no prose, no code fences)."   // claude/nano; OpenAiCompat adds its own tier suffix
        val r = Llm.complete(ctx, t, LlmRequest(ctx.strOrNull("system"), prompt, ctx.strOrNull("imageUri"), null, t.model, t.effort, ctx.int("maxTokens") ?: 4096, ctx.timeoutMs, t.temperature, jsonMode = json))
        val meta = ctx.item.add("provider" to r.provider, "model" to r.model, "stopReason" to r.stopReason)
        if (!json) return out(meta.add((ctx.strOrNull("outputField") ?: "answer") to r.text))
        val obj = (r.json as? JsonObject) ?: Llm.parseJsonObject(r.text) ?: throw NodeException("AI did not return a JSON object")
        return out(meta.addAll(obj))
    }
}

object ClassifyNode : Node() {
    override val spec = NodeSpec(
        id = "ai.classify", name = "AI Classify", kind = NodeKind.AI, description = "Routes each item to the output port whose label the AI picks (or 'other')",
        params = listOf(
            providerParam(),
            multiline("text", "Text", "{{text}}", required = true),
            labels("labels", "Labels", required = true, definesPorts = true, help = "One output port per label"),
            multiline("instructions", "Instructions", help = "What the labels mean / how to decide"),
            imageParam(),
            modelParam(), effortParam("low"), temperatureParam(),
            // ponytail: LLM params stay visible when classify engine=system1 (one visibleWhen per param)
            choice("engine", "Engine", listOf("generative", "system1"), "generative",
                help = "system1 = one choice question to the decision engine (Settings > AI): ~50 ms, no image; provider/model/effort/temperature ignored. Avoid labels named true/false."),
        ),
        outputs = listOf(PORT_OTHER), timeoutMs = 120_000,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val labels = ctx.labels("labels")
        if (labels.isEmpty()) throw NodeException("AI Classify: at least one label is required")
        val text = ctx.req("text")
        val instr = ctx.strOrNull("instructions") ?: "Classify the text into exactly one label."
        if (ctx.str("engine") == "system1") return system1(ctx, labels, text, instr)
        val t = Llm.target(ctx)
        val label: String
        val r: LlmResult
        if (t.providerId == PROVIDER_NANO) {
            val prompt = "$instr\n\nText:\n$text\n\nAnswer with exactly one of: ${labels.joinToString(", ")}. Reply with the label only."
            r = Llm.complete(ctx, t, LlmRequest(null, prompt, ctx.strOrNull("imageUri"), null, maxTokens = 64, timeoutMs = ctx.timeoutMs))
            val ans = r.text.trim().trim('"', '\'', '.', '`', '*').lowercase()
            label = labels.firstOrNull { it.lowercase() == ans } ?: PORT_OTHER
        } else {
            val schema = Llm.objectSchema(mapOf("label" to buildJsonObject {
                put("type", "string"); put("enum", JsonArray(labels.map { JsonPrimitive(it) })); put("description", "The single best matching label")
            }))
            // ponytail: 1024 fixed, thinking counts against max_tokens on opus/sonnet-5; upgrade = expose maxTokens param like ai.ask
            r = Llm.complete(ctx, t, LlmRequest(null, "$instr\n\nText:\n$text", ctx.strOrNull("imageUri"), schema, t.model, t.effort, 1024, ctx.timeoutMs, t.temperature))
            val ans = (r.json as? JsonObject)?.get("label").asTextOrNull()
            label = labels.firstOrNull { it == ans } ?: labels.firstOrNull { it.equals(ans, ignoreCase = true) } ?: PORT_OTHER
        }
        return route(label, ctx.item.add("label" to label, "provider" to r.provider))
    }

    /** DESIGN5 §5.3: one choice question to the decision engine (engine "default" = Settings); an image is refused honestly. */
    private suspend fun system1(ctx: ExecutionContext, labels: List<String>, text: String, instr: String): NodeResult {
        if (ctx.strOrNull("imageUri") != null) throw NodeException("System 1 engines do not accept images — use engine = generative")
        val q = S1Question.Choice("label", instr, LinkedHashMap(labels.associateWith { it }))
        SystemOne.validate(listOf(q))?.let { throw NodeException("AI Classify: $it") }
        val r = DecideNode.decideFn(ctx, SystemOne.ENGINE_DEFAULT, SystemOne.stateOf(text), listOf(q), ctx.timeoutMs.coerceAtMost(7_000))
        val a = r.answers["label"] as? S1Answer.Choice ?: throw NodeException("AI Classify: the decision engine returned no label")
        val label = labels.firstOrNull { it == a.choice } ?: labels.firstOrNull { it.equals(a.choice, ignoreCase = true) } ?: PORT_OTHER
        return route(label, ctx.item.add("label" to label, "provider" to "system1:${r.engine}", "confidence" to a.confidence,
            "probabilities" to JsonObject(a.probabilities.mapValues { JsonPrimitive(it.value) })))
    }
}

object ExtractNode : Node() {
    private val TYPES = listOf("string", "number", "boolean", "string_list")

    override val spec = NodeSpec(
        id = "ai.extract", name = "AI Extract", kind = NodeKind.AI, description = "Extracts named fields from text as JSON and adds them to the item",
        params = listOf(
            providerParam(),
            multiline("text", "Text", "{{text}}", required = true),
            rows("fields", "Fields", listOf(
                text("name", "Name", required = true, templated = false),
                text("description", "Description", templated = false),
                choice("type", "Type", TYPES),
            ), required = true),
            imageParam(),
            modelParam(), effortParam("low"), temperatureParam(),
        ),
        timeoutMs = 120_000, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val fields = ctx.rows("fields")
        val names = fields.map { it.str("name")?.trim().orEmpty() }
        if (names.isEmpty() || names.any { it.isBlank() }) throw NodeException("AI Extract: every field needs a name")
        if (names.toSet().size != names.size) throw NodeException("AI Extract: duplicate field names")
        val schema = Llm.objectSchema(fields.zip(names).associate { (row, name) ->
            name to buildJsonObject {
                when (row.str("type") ?: "string") {
                    "number" -> put("type", "number")
                    "boolean" -> put("type", "boolean")
                    "string_list" -> { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) }
                    else -> put("type", "string")
                }
                row.str("description")?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            }
        })
        val t = Llm.target(ctx)
        val r = Llm.complete(ctx, t, LlmRequest(null, "Extract the requested fields from the text. Use null-like empty values only when a field is truly absent.\n\nText:\n${ctx.req("text")}",
            ctx.strOrNull("imageUri"), schema, t.model, t.effort, 4096, ctx.timeoutMs, t.temperature))
        val obj = r.json as? JsonObject ?: throw NodeException("AI did not return a JSON object")
        // ponytail: value types are trusted (Claude/json_schema providers enforce them; Nano's and prompt-only tiers are best-effort); upgrade = coerce per declared type.
        return out(ctx.item.addAll(JsonObject(obj.filterKeys { it in names })).add("extractedFrom" to t.providerId))
    }
}

object AiNodes { val all: List<Node> = listOf(AskAiNode, ClassifyNode, ExtractNode, AgentNode, McpToolNode, McpResourceNode, DecideNode) }
