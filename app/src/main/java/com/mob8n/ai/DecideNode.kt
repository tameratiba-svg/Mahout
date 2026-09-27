package com.mob8n.ai

import com.mob8n.core.ExecutionContext
import com.mob8n.core.Item
import com.mob8n.core.Node
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.ParamSpec
import com.mob8n.core.add
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.rows
import com.mob8n.core.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ai.decide (DESIGN5 §3.2, §5.2): typed questions answered by a System 1 engine in one call. DATA kind + spec timeout 8 s so TriggerHub never wakes the
 * foreground host for a ~50–300 ms call (D2). Routing is done downstream with logic.switch / logic.if on answers.<name> (D3).
 */
object DecideNode : Node() {
    const val ID = "ai.decide"

    /** The 4 question columns; triggers/TriageParams imports this ONE value. */
    val QUESTION_COLUMNS: List<ParamSpec> = listOf(
        text("name", "Name", required = true, templated = false, help = "identifier; the answer lands in answers.<name>"),
        choice("type", "Type", listOf("choice", "score", "noul"), "choice", help = "choice = pick one label; score = ordered levels; noul = yes/no probability"),
        multiline("instructions", "Question", required = true, templated = false, help = "The question in plain words"),
        multiline("criteria", "Criteria", templated = false, help = "choice: one 'label: description' per line (2–100); score: levels low→high, comma or line separated (2–10); noul: optional 'true: …' / 'false: …' lines"),
    )

    override val spec = NodeSpec(
        id = ID, name = "AI Decide (System 1)", kind = NodeKind.DATA,
        description = "Answers typed questions about the item (choice / score / yes-no) in tens of milliseconds with a System 1 decision engine (Jev cloud or Laya on your LAN, no text generation); adds answers.<name> and a decisions object",
        params = listOf(
            multiline("state", "State", "{{\$json}}", required = true, help = "Text or JSON the questions are about (default = the whole item)"),
            rows("questions", "Questions", QUESTION_COLUMNS, required = true, help = "Every question is answered in one call"),
            choice("engine", "Engine", SystemOne.ENGINES, "default", help = "default = Settings > AI > Decision engine. jev sends the state to the Jev cloud; laya stays on your LAN"),
            durationMs("timeoutMs", "Timeout", 5_000, 1_000, 7_000),
        ),
        timeoutMs = 8_000, agentTool = true,
    )

    /** Seam (like AgentNode's injectable step): tests replace it; production resolves the target from S1Prefs and calls the engine (usage source "node", ref runId). */
    @Volatile internal var decideFn: suspend (ctx: ExecutionContext, engineParam: String?, state: JsonElement, questions: List<S1Question>, timeoutMs: Long) -> S1Result =
        { ctx, engine, state, qs, timeout -> SystemOne.decide(SystemOne.target(ctx.requireAndroid(), engine), state, qs, timeout, "node", ctx.runId, ctx::log) }

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val qs = SystemOne.questionsFromRows(ctx.rows("questions"))
        SystemOne.validate(qs)?.let { throw com.mob8n.core.NodeException("AI Decide: $it") }
        val r = decideFn(ctx, ctx.strOrNull("engine"), SystemOne.stateOf(ctx.req("state")), qs, (ctx.long("timeoutMs") ?: 5_000L).coerceIn(1_000, 7_000))
        return out(outputOf(ctx.item, r))
    }

    private fun obj(m: Map<String, Double>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })

    /** Pure §5.2 mapping (one suffix scheme). Earlier answers on the item are kept (a second ai.decide adds to them). */
    fun outputOf(item: Item, r: S1Result): Item {
        val a = LinkedHashMap<String, JsonElement>((item["answers"] as? JsonObject) ?: emptyMap())
        for ((n, ans) in r.answers) {
            when (ans) {
                is S1Answer.Choice -> a[n] = JsonPrimitive(ans.choice)
                is S1Answer.Score -> { a[n] = JsonPrimitive(ans.level); a["${n}_label"] = JsonPrimitive(ans.levelLabel); a["${n}_score"] = JsonPrimitive(ans.score) }
                is S1Answer.Noul -> { a[n] = JsonPrimitive(ans.p); a["${n}_label"] = JsonPrimitive(ans.value.toString()) }
            }
            ans.confidence?.let { a["${n}_confidence"] = JsonPrimitive(it) }
            a["${n}_probabilities"] = obj(ans.probabilities)
        }
        return item.add("answers" to JsonObject(a), "decisions" to (r.raw["answers"] ?: JsonObject(emptyMap())), "engine" to r.engine, "s1Model" to r.displayModel, "latencyMs" to r.latencyMs)
    }
}
