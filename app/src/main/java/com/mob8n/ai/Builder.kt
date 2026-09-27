package com.mob8n.ai

import com.mob8n.core.Catalog
import com.mob8n.core.EMPTY
import com.mob8n.core.Edge
import com.mob8n.core.ERROR
import com.mob8n.core.Graph
import com.mob8n.core.JSON
import com.mob8n.core.MAIN
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.ParamKind
import com.mob8n.core.ParamSpec
import com.mob8n.core.asBool
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Build with AI (DESIGN2 §6): compact catalog -> system prompt -> ONE model call -> parse/normalise -> Graph.validate -> at most ONE repair round.
 * Pure Kotlin apart from the `android: Context` handle [build] needs to resolve the Default AI; everything else is JVM-tested.
 * ponytail: whole catalog in every Build-with-AI call; upgrade = two-pass (pick ids, then send those specs)
 */
object Builder {
    data class Result(val name: String, val graph: Graph, val errors: List<String>, val rounds: Int, val providerLabel: String, val model: String)
    sealed class Progress { data object Asking : Progress(); data object Validating : Progress(); data object Repairing : Progress() }

    const val ERR_NANO = "Build with AI needs a cloud provider — Gemini Nano's 256-token output cannot hold a workflow. Pick a Default AI in Settings > AI"
    const val ERR_NO_JSON = "The AI did not return a workflow JSON"
    const val ERR_TOKENS = "The model ran out of output tokens; shorten the description or pick a larger model"
    private const val MAX_OUT = 8000
    private const val TIMEOUT_MS = 180_000L

    /** Documented output fields (a hand-kept copy of ui.FALLBACK_OUTPUT_FIELDS' trigger/data/ai rows; BuilderPromptTest checks the ids exist). */
    val OUTPUT_HINTS: Map<String, List<String>> = mapOf(
        "trigger.now_playing" to listOf("title", "artist", "album", "durationMs", "sourceApp", "state", "positionMs", "at"),
        "trigger.notification_posted" to listOf("packageName", "appName", "title", "text", "bigText", "subText", "key", "category", "postTime", "ongoing"),
        "trigger.notification_removed" to listOf("packageName", "title", "text", "key", "reason"),
        "trigger.share" to listOf("text", "url", "subject", "uri", "mimeType", "fileName", "sizeBytes"),
        "trigger.tile" to listOf("tileState", "at"),
        "trigger.shortcut" to listOf("at"),
        "trigger.manual" to listOf("at"),
        "data.device_state" to listOf("battery", "charging", "plugged", "wifiSsid", "networkType", "metered", "screenOn", "powerSave", "ringerMode", "dnd", "btAudioConnected", "wiredHeadset", "outputDevice", "brightness", "orientation"),
        "data.now_playing" to listOf("title", "artist", "album", "durationMs", "sourceApp", "state", "positionMs"),
        "data.active_notifications" to listOf("packageName", "appName", "title", "text", "key", "postTime", "ongoing"),
        "data.location" to listOf("lat", "lng", "accuracyM", "altitude", "speed", "provider", "time"),
        "data.calendar_events" to listOf("title", "begin", "end", "beginIso", "endIso", "location", "description", "calendar", "allDay", "eventId"),
        "data.contact_lookup" to listOf("found", "contactName", "phones", "emails", "lookupKey"),
        "data.http" to listOf("status", "headers", "body"),
        "ai.ask" to listOf("answer", "provider", "model", "stopReason"),
        "ai.classify" to listOf("label", "provider", "confidence", "probabilities"),
        "ai.decide" to listOf("answers", "decisions", "engine", "s1Model", "latencyMs"),
        "ai.agent" to listOf("result", "steps", "stopReason", "truncated"),
        "data.knowledge_search" to listOf("context", "count", "hits", "text", "source", "sourceId", "seq", "score"),
        "ai.mcp_tool" to listOf("text", "content", "structured", "isError", "server", "tool"),
        "ai.mcp_resource" to listOf("text", "mimeType", "uri", "server"),
        "app.shell_run" to listOf("exitCode", "stdout", "stderr", "truncated", "timedOut", "ms", "outputFile"),
        "logic.js" to listOf("value"),
    )

    /** Mirrors seed-2 (share -> fetch -> clip -> summarise -> notify, + notify on fetch error); BuilderPromptTest validates it against the real catalog. */
    const val FEW_SHOT_JSON = """{"name":"Summarize shared link","nodes":[""" +
        """{"id":"n1","type":"trigger.share","name":"Share","params":{"accept":"url"}},""" +
        """{"id":"n2","type":"data.http","name":"Fetch","params":{"method":"GET","url":"{{url}}"}},""" +
        """{"id":"n3","type":"logic.text","name":"Clip","params":{"op":"truncate","input":"{{body}}","maxLength":12000,"outputField":"text"}},""" +
        """{"id":"n4","type":"ai.ask","name":"Summarize","params":{"prompt":"Summarize this page in 5 bullet points:\n\n{{text}}","outputField":"summary"}},""" +
        """{"id":"n5","type":"action.notify","name":"Notify","params":{"title":"Summary","text":"{{url}}","bigText":"{{summary}}"}},""" +
        """{"id":"n6","type":"action.notify","name":"Notify fail","params":{"title":"Could not fetch","text":"{{error}}"}}],""" +
        """"edges":[{"from":"n1","fromPort":"main","to":"n2","toPort":"main"},{"from":"n2","fromPort":"main","to":"n3","toPort":"main"},""" +
        """{"from":"n3","fromPort":"main","to":"n4","toPort":"main"},{"from":"n4","fromPort":"main","to":"n5","toPort":"main"},""" +
        """{"from":"n2","fromPort":"error","to":"n6","toPort":"main"}]}"""

    // ---------------------------------------------------------------- compact catalog (§6.1)

    private fun kindLetter(k: NodeKind) = when (k) { NodeKind.TRIGGER -> "T"; NodeKind.DATA -> "D"; NodeKind.LOGIC -> "L"; NodeKind.ACTION -> "A"; NodeKind.AI -> "I" }

    private fun paramKind(p: ParamSpec): String = when (p.kind) {
        ParamKind.TEXT, ParamKind.MULTILINE -> "s"
        ParamKind.NUMBER -> "n"
        ParamKind.BOOL -> "b"
        ParamKind.ENUM -> "e"
        ParamKind.DURATION -> "ms"
        ParamKind.TIME -> "hm"
        ParamKind.APP -> "app"
        ParamKind.PLAYLIST -> "pl"
        ParamKind.LABELS -> "L"
        ParamKind.ROWS -> "R[" + p.rows.filter { it.kind != ParamKind.SECRET }.joinToString(",") { paramLine(it) } + "]"
        ParamKind.WORKFLOW -> "wf"
        ParamKind.SECRET -> "sec"
    }

    private fun flat(s: String): String = s.replace('\n', ' ').replace('|', '/').trim()

    private fun defaultText(d: JsonElement): String = when (d) {
        is JsonPrimitive -> if (!d.isString) d.content.toDoubleOrNull()?.let { if (it == Math.floor(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() } ?: d.content else flat(d.content)
        else -> flat(JSON.encodeToString(JsonElement.serializer(), d))
    }

    /** key:kind[*][^][=default][{opt1,opt2}] — `*` required (ENUM/TIME/PLAYLIST always carry a default, so no star), `^` LABELS param that defines output ports. */
    fun paramLine(p: ParamSpec): String = buildString {
        append(p.key).append(':').append(paramKind(p))
        if (p.required && !(p.default != null && p.kind in setOf(ParamKind.ENUM, ParamKind.TIME, ParamKind.PLAYLIST))) append('*')
        if (p.definesPorts) append('^')
        p.default?.takeUnless { it is JsonNull }?.let { append('=').append(defaultText(it)) }
        if (p.kind == ParamKind.ENUM) append('{').append(p.options.joinToString(",")).append('}')
    }

    fun catalogLine(s: NodeSpec): String = buildString {
        append(s.id).append('|').append(kindLetter(s.kind)).append('|').append(flat(s.name)).append('|').append(flat(s.description)).append('|')
        append(s.params.filter { it.kind != ParamKind.SECRET }.joinToString(",") { paramLine(it) })
        append("|in:").append(if (s.inputs.isEmpty()) "-" else s.inputs.joinToString(","))
        val dyn = s.params.firstOrNull { it.definesPorts }
        append("|out:").append((listOfNotNull(dyn?.let { "<${it.key}>" }) + s.outputs).joinToString(","))
        if (s.mode == com.mob8n.core.ExecMode.LIST) append("|LIST")
        if (s.optional) append("|opt")
        OUTPUT_HINTS[s.id]?.let { append("|fields:").append(it.joinToString(",")) }
    }

    fun compactCatalog(catalog: Catalog): String = catalog.nodes.joinToString("\n") { catalogLine(it.spec) }

    /** Ids only, grouped by kind (~3 KB): the chat operator's describe_node points at it instead of the 32 KB catalog (DESIGN4 V17). */
    fun catalogIndex(catalog: Catalog): String = catalog.nodes.groupBy { it.spec.kind }.entries.joinToString("\n") { (k, ns) -> "${k.name.lowercase()}: " + ns.joinToString(", ") { it.spec.id } }

    // ---------------------------------------------------------------- prompts (§6.2)

    private fun recipeLines(catalog: Catalog): String {
        val spec = catalog.spec("app.action") ?: return "(app.action is not available in this build)"
        val recipe = spec.param("recipe") ?: return "(none)"
        return recipe.options.joinToString("\n") { r ->
            val keys = spec.params.filter { it.visibleWhen?.key == "recipe" && r in it.visibleWhen!!.equalsAny }.map { it.key }
            "$r: ${keys.joinToString(", ").ifBlank { "-" }}"
        }
    }

    /** DESIGN5 §5.4 rule 12: appended only when a decision engine is configured (S1Prefs.isConfigured). */
    const val RULE_DECIDE = "12. A decision engine is configured: for routing, scoring, yes/no gates and urgency/priority questions use ai.decide (fast System 1, adds " +
        "answers.<name>: choice → the label, score → the level index with answers.<name>_label, noul → a probability 0–1) followed by logic.switch " +
        "(field answers.<name>) or logic.if (answers.<name> gte N), or ai.classify with engine=\"system1\" when exactly one label must route ports. " +
        "Criteria text: choice = \"label: description\" lines; score = levels low→high comma-separated; noul = optional \"true: …\"/\"false: …\". Use " +
        "ai.ask/ai.agent only when text must be written or tools used."

    fun systemPrompt(catalog: Catalog, decisionEngine: Boolean = false): String = """
You design workflows for Mahout, an n8n-style automation app running on the user's Android device. Reply with ONLY one JSON object (no prose, no code fences) in this shape:
{"name":"Short workflow name","nodes":[{"id":"n1","type":"<catalog id>","name":"Unique Name","params":{"key":"value"}}],"edges":[{"from":"n1","fromPort":"main","to":"n2","toPort":"main"}]}

Rules:
1. Exactly one trigger node (kind T) unless the user asks for several; triggers have no inputs and start every chain.
2. Every other node must be reachable from a trigger through edges. No cycles.
3. "type" must be a catalog id below; "params" keys must be that node's param keys. Never invent types, keys or option values.
4. Param values are typed by kind: n → number, b → true/false, e → exactly one listed option, L → ["a","b"], R → [{"col":value}], ms → milliseconds as a number, hm → "HH:mm", s/app/pl/wf → string. Params marked * are required.
5. Node names are unique, short, and contain no '.'.
6. Ports: "fromPort" is "main" unless the node lists other outputs — "true"/"false" for logic.if, "done" for logic.split_batches, your labels for nodes whose L^ param defines ports (ai.classify, logic.switch), "approve"/"deny" for logic.wait_approval, "denied" for ai.agent, "timeout" for app.ui_wait_for. Every node also has an "error" output port: wire it to action.notify when the user wants failure alerts. "toPort" is "main" except logic.merge ("a"/"b"). To make a workflow callable by the AI as a tool set exposeAsTool=true and declare inputs rows on trigger.called.
7. Templates inside string params: {{field}} = a field of the incoming item, {{${'$'}node.Name.field}} = a field of an earlier node's output, {{${'$'}now}}, {{${'$'}date}}, {{${'$'}time}}, {{${'$'}json}}, {{field ?? "default"}}. Use the output fields listed after "fields:" when present.
8. AI nodes: leave "provider" at "default" and "model" empty. Secrets are never literal: SECRET params take a secret NAME.
9. To drive another app prefer app.action with a recipe, then action.open_url / action.launch_app; use app.ui_* nodes only when the user explicitly asks to tap/type inside another app's screen (they need Accessibility access).
10. Prefer fewer nodes. Omit x and y. Return the complete workflow every time.
11. ai.agent: never set askApproval=false or permissionMode other than "inherit" — the user chooses that in the editor.${if (decisionEngine) "\n" + RULE_DECIDE else ""}

Example — request: "When I share a link, fetch it, summarise it with AI and notify me; notify me too if the fetch fails"
$FEW_SHOT_JSON

Recipes for app.action (recipe id: params):
${recipeLines(catalog)}

Catalog (id|kind|name|description|params|in|out[|LIST][|opt]):
${compactCatalog(catalog)}
""".trim()

    fun userPrompt(description: String, current: Graph? = null, currentName: String? = null, instruction: String? = null): String =
        if (current != null) "Current workflow (JSON):\n${graphJson(currentName ?: "Workflow", current)}\n\nChange it as follows: ${instruction ?: description}\nReturn the complete updated workflow."
        else "Create a workflow: $description"

    fun repairPrompt(errors: List<String>): String = "Your workflow failed validation:\n" + errors.joinToString("\n") { "- $it" } + "\nReturn the complete corrected workflow JSON."

    /** Compact (no x/y/disabled/timeoutMs; ids shortened to n1..nk), used for refine + few-shot. */
    fun graphJson(name: String, graph: Graph): String {
        val ids = graph.nodes.mapIndexed { i, n -> n.id to "n${i + 1}" }.toMap()
        val o = buildJsonObject {
            put("name", name)
            put("nodes", buildJsonArray {
                graph.nodes.forEach { n -> add(buildJsonObject { put("id", ids[n.id]!!); put("type", n.type); put("name", n.name); put("params", n.params) }) }
            })
            put("edges", buildJsonArray {
                graph.edges.forEach { e -> add(buildJsonObject { put("from", ids[e.from] ?: e.from); put("fromPort", e.fromPort); put("to", ids[e.to] ?: e.to); put("toPort", e.toPort) }) }
            })
        }
        return JSON.encodeToString(JsonObject.serializer(), o)
    }

    fun estimateTokens(s: String): Int = s.length / 3               // conservative

    // ---------------------------------------------------------------- parse + normalise (§6.3)

    internal class Parsed(val name: String, val graph: Graph, val warnings: List<String>)

    fun parse(text: String, catalog: Catalog): kotlin.Result<Pair<String, Graph>> = parseDetailed(text, catalog).map { it.name to it.graph }

    internal fun parseDetailed(text: String, catalog: Catalog): kotlin.Result<Parsed> {
        val root = Llm.parseJsonObject(text) ?: return kotlin.Result.failure(NodeException(ERR_NO_JSON))
        val g = (root["graph"] as? JsonObject) ?: (root["workflow"] as? JsonObject) ?: root
        val nodesArr = g["nodes"] as? JsonArray ?: return kotlin.Result.failure(NodeException("$ERR_NO_JSON (no nodes)"))
        val name = (root["name"] ?: g["name"]).asTextOrNull()?.trim()?.ifBlank { null } ?: "Generated workflow"
        val warnings = ArrayList<String>()
        val idMap = LinkedHashMap<String, String>()
        val names = HashSet<String>()
        val nodes = ArrayList<NodeInstance>()
        nodesArr.filterIsInstance<JsonObject>().forEachIndexed { i, n ->
            val rawId = n["id"].asTextOrNull()?.trim()?.ifBlank { null } ?: "n${i + 1}"
            if (rawId in idMap) return kotlin.Result.failure(NodeException("duplicate node id $rawId"))
            val type = n["type"].asTextOrNull()?.trim() ?: ""
            val spec = catalog.spec(type)
            var nm = n["name"].asTextOrNull()?.replace('.', ' ')?.trim()?.ifBlank { null } ?: spec?.name ?: type.substringAfter('.').ifBlank { "Node" }
            if (nm in names) { var k = 2; while ("$nm $k" in names) k++; nm = "$nm $k" }
            names += nm
            val params = (n["params"] as? JsonObject) ?: EMPTY
            val id = UUID.randomUUID().toString()
            idMap[rawId] = id
            nodes += NodeInstance(id, type, nm, if (spec != null) normaliseParams(spec, params, nm, warnings) else params)
        }
        val edges = (g["edges"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull { e ->
            val from = e["from"].asTextOrNull()?.trim() ?: return@mapNotNull null
            val to = e["to"].asTextOrNull()?.trim() ?: return@mapNotNull null
            Edge(idMap[from] ?: from, e["fromPort"].asTextOrNull()?.trim()?.ifBlank { null } ?: MAIN, idMap[to] ?: to, e["toPort"].asTextOrNull()?.trim()?.ifBlank { null } ?: MAIN)
        } ?: emptyList()
        return kotlin.Result.success(Parsed(name, Graph(nodes, edges), warnings))
    }

    private fun normaliseParams(spec: NodeSpec, params: JsonObject, nodeName: String, warnings: MutableList<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in params) {
            if (v is JsonNull) continue
            val p = spec.param(k)
            if (p == null) { warnings += "$nodeName: ignored unknown param $k"; continue }
            out[k] = coerce(p, v)
        }
        return JsonObject(out)
    }

    private fun coerce(p: ParamSpec, v: JsonElement): JsonElement = when (p.kind) {
        ParamKind.NUMBER, ParamKind.DURATION -> if (v is JsonPrimitive && v.isString && !v.content.contains("{{")) {
            v.content.trim().let { s -> s.toLongOrNull()?.let { JsonPrimitive(it) } ?: s.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: v }
        } else v
        ParamKind.BOOL -> if (v is JsonPrimitive && v.isString) (v.content.trim().lowercase().toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: v) else v
        ParamKind.LABELS -> when (v) {
            is JsonPrimitive -> JsonArray(listOf(JsonPrimitive(v.content)))
            is JsonArray -> JsonArray(v.map { if (it is JsonPrimitive) JsonPrimitive(it.content) else JsonPrimitive(it.toString()) })
            else -> v
        }
        ParamKind.ROWS -> when (v) {
            is JsonObject -> JsonArray(listOf(v))
            is JsonArray -> JsonArray(v.map { row -> if (row is JsonObject) JsonObject(row.mapValues { (ck, cv) -> p.rows.firstOrNull { it.key == ck }?.let { coerce(it, cv) } ?: cv }) else row })
            else -> v
        }
        ParamKind.ENUM -> if (v is JsonPrimitive) JsonPrimitive(v.content) else v
        else -> when (v) {
            is JsonPrimitive -> if (v.isString) v else JsonPrimitive(v.content)
            else -> JsonPrimitive(JSON.encodeToString(JsonElement.serializer(), v))
        }
    }

    // ---------------------------------------------------------------- validate (§6.4)

    private fun closest(type: String, catalog: Catalog): String {
        val q = type.substringAfter('.', type).replace('_', ' ')
        val hits = (catalog.search(type) + catalog.search(q) + q.split(' ').filter { it.length > 2 }.flatMap { catalog.search(it) }).map { it.spec.id }.distinct().take(3)
        return hits.joinToString(", ")
    }

    const val UNATTENDED_PREFIX = "Runs unattended: "

    /** DESIGN4P P16: every ai.agent node the Builder (or anyone) set to run without asking — printed in the error colour by every draft/save preview. */
    fun unattendedAgents(graph: Graph): List<String> = graph.nodes.filter { it.type == AgentNode.ID }.mapNotNull { n ->
        val flags = listOfNotNull(
            if (n.params["askApproval"].asBool() == false) "askApproval=false" else null,
            n.params["permissionMode"].asTextOrNull()?.takeIf { it == PermissionMode.BYPASS.key || it == PermissionMode.AUTO.key }?.let { "permissionMode=$it" },
        )
        if (flags.isEmpty()) null else "$UNATTENDED_PREFIX${n.name} (${flags.joinToString(", ")})"
    }

    /** Graph.validate + builder checks: trigger wording, closest ids for unknown types, unreachable nodes, unattended agents (P16). */
    fun validate(graph: Graph, catalog: Catalog): List<String> {
        val errs = ArrayList<String>()
        for (e in graph.validate(catalog)) errs += when {
            e == "Workflow needs at least one trigger" -> "Add exactly one trigger node (kind T)"
            e.contains(": unknown node type ") -> e + closest(e.substringAfter(": unknown node type ").trim(), catalog).let { if (it.isBlank()) "" else "; closest: $it" }
            else -> e
        }
        val triggers = graph.nodes.filter { catalog.spec(it.type)?.kind == NodeKind.TRIGGER }.map { it.id }.toSet()
        if (triggers.isNotEmpty()) {
            val seen = HashSet(triggers)
            val q = ArrayDeque(triggers)
            while (q.isNotEmpty()) { val n = q.removeFirst(); for (e in graph.edges) if (e.from == n && e.to !in seen) { seen += e.to; q.addLast(e.to) } }
            graph.nodes.filter { it.id !in seen }.forEach { errs += "${it.name} is not reachable from a trigger" }
        }
        errs += unattendedAgents(graph)
        return errs
    }

    // ---------------------------------------------------------------- build (§6.4)

    /** ONE model call, validate, at most ONE repair round. Throws NodeException for provider/Nano/key problems. Cancellation-safe (the HTTP socket is disconnected). */
    suspend fun build(
        android: android.content.Context, catalog: Catalog, description: String, current: Graph? = null, currentName: String? = null,
        instruction: String? = null, onProgress: (Progress) -> Unit = {}, decisionEngine: Boolean = S1Prefs.isConfigured(android),
    ): Result {
        val t = Llm.defaultTarget(android)
        if (t.providerId == PROVIDER_NANO) throw NodeException(ERR_NANO)
        return run(
            catalog, description, current, currentName, instruction, t.label, t.model,
            first = { system, user -> Llm.completeDirect(android, t, LlmRequest(system, user, jsonMode = true, maxTokens = MAX_OUT, temperature = 0.2, timeoutMs = TIMEOUT_MS)) },
            again = { system, msgs -> Llm.step(t, MAX_OUT, system, msgs, emptyList(), true, TIMEOUT_MS) },
            decisionEngine = decisionEngine, onProgress = onProgress,
        )
    }

    /** The provider-free core of [build] (JVM-tested with fakes). */
    internal suspend fun run(
        catalog: Catalog, description: String, current: Graph?, currentName: String?, instruction: String?, providerLabel: String, model: String,
        first: suspend (system: String, user: String) -> LlmResult, again: suspend (system: String, messages: List<JsonObject>) -> Turn,
        decisionEngine: Boolean = false, onProgress: (Progress) -> Unit = {},
    ): Result {
        val system = systemPrompt(catalog, decisionEngine)
        val user = userPrompt(description, current, currentName, instruction)
        onProgress(Progress.Asking)
        val r1 = first(system, user)
        if (r1.stopReason == "max_tokens") throw NodeException(ERR_TOKENS)
        onProgress(Progress.Validating)
        val p1 = parseDetailed(r1.text, catalog)
        val parsed1 = p1.getOrNull()
        val e1 = parsed1?.let { validate(it.graph, catalog) } ?: listOf(p1.exceptionOrNull()?.message ?: ERR_NO_JSON)
        if (parsed1 != null && e1.isEmpty()) return Result(parsed1.name, parsed1.graph, emptyList(), 1, providerLabel, model)
        onProgress(Progress.Repairing)
        val msgs = listOf(
            ClaudeClient.userMessage(user),
            buildJsonObject { put("role", "assistant"); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", r1.text) }) }) },
            ClaudeClient.userMessage(repairPrompt(e1)),
        )
        val turn = again(system, msgs)
        if (turn.stopReason == "max_tokens") throw NodeException(ERR_TOKENS)
        val parsed2 = parseDetailed(turn.text, catalog).getOrNull()
        if (parsed2 == null) {
            if (parsed1 == null) throw NodeException(ERR_NO_JSON)
            return Result(parsed1.name, parsed1.graph, e1 + parsed1.warnings, 2, providerLabel, model)
        }
        val e2 = validate(parsed2.graph, catalog)
        return Result(parsed2.name, parsed2.graph, if (e2.isEmpty()) emptyList() else e2 + parsed2.warnings, 2, providerLabel, model)
    }
}
