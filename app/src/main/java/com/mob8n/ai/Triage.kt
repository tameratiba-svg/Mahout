package com.mob8n.ai

import android.content.Context
import android.util.Log
import com.mob8n.core.Item
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInstance
import com.mob8n.core.Scope
import com.mob8n.core.Template
import com.mob8n.core.Workflow
import com.mob8n.core.add
import com.mob8n.core.asDouble
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/**
 * System 1 triage (DESIGN5 §3.3, §6.1): the body of Engine.preFilter for trigger.notification_posted / trigger.share. Evaluated BEFORE a run exists;
 * an event is kept only when every row passes. Keys mirror triggers/TriageParams (read here as plain strings: ai never imports triggers).
 */
// ponytail: triage fails open by default (triageOnError); dropped events leave a logcat line + ai_usage rows, no run row
object Triage {
    const val FIELD = "triage"
    const val ENGINE = "triageEngine"; const val QUESTIONS = "triageQuestions"; const val ON_ERROR = "triageOnError"; const val STATE = "triageState"
    const val DEFAULT_THRESHOLD = 0.7
    const val ITEM_TIMEOUT_MS = 5_000L
    private const val WHO = "Triage"

    data class Spec(val engine: String, val questions: List<S1Question>, val thresholds: Map<String, Double>, val onError: String)

    /** Pure: null when triageEngine is off/absent or there are no rows; NodeException on malformed rows (choice type included). */
    fun spec(params: JsonObject): Spec? {
        val engine = params[ENGINE].asTextOrNull()?.trim()?.ifBlank { null } ?: return null
        if (engine == "off") return null
        val rows = (params[QUESTIONS] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        if (rows.isEmpty()) return null
        rows.forEach { r ->
            val t = r["type"].asTextOrNull()?.trim()?.ifBlank { null } ?: "noul"
            if (t != "noul" && t != "score") throw NodeException("$WHO: question ${r["name"].asTextOrNull()}: type must be noul or score")
        }
        val qs = SystemOne.questionsFromRows(rows.map { r -> if (r["type"].asTextOrNull().isNullOrBlank()) JsonObject(r + ("type" to JsonPrimitive("noul"))) else r }, WHO)
        SystemOne.validate(qs)?.let { throw NodeException("$WHO: $it") }
        val thresholds = rows.associate { r -> r["name"].asTextOrNull().orEmpty().trim() to (r["threshold"].asDouble() ?: DEFAULT_THRESHOLD) }
        return Spec(engine, qs, thresholds, if (params[ON_ERROR].asTextOrNull() == "drop") "drop" else "run")
    }

    /** Pure: noul -> p >= t; score -> level >= t; every row (AND). A missing answer fails the row. */
    fun passes(spec: Spec, answers: Map<String, S1Answer>): Boolean = spec.questions.all { q ->
        val t = spec.thresholds[q.name] ?: DEFAULT_THRESHOLD
        when (val a = answers[q.name]) {
            is S1Answer.Noul -> a.p >= t
            is S1Answer.Score -> a.level >= t
            else -> false
        }
    }

    /** Pure: item + "triage": {<name>: value, <name>_confidence, engine, latencyMs} (noul value = P(true), score value = level index). */
    fun annotate(item: Item, r: S1Result): Item {
        val m = LinkedHashMap<String, JsonElement>()
        for ((n, a) in r.answers) {
            m[n] = when (a) { is S1Answer.Noul -> JsonPrimitive(a.p); is S1Answer.Score -> JsonPrimitive(a.level); is S1Answer.Choice -> JsonPrimitive(a.choice) }
            a.confidence?.let { m["${n}_confidence"] = JsonPrimitive(it) }
        }
        m["engine"] = JsonPrimitive(r.engine); m["latencyMs"] = JsonPrimitive(r.latencyMs)
        return item.add(FIELD to JsonObject(m))
    }

    /**
     * Pure (F3): the text Laya/Jev judges. `triageState` rendered against the item (instance value, else the trigger's spec default);
     * blank template or a result with no letter/digit -> the whole item JSON (the v5.0 behaviour). The whole item (package, key, nulls, postTime) diluted
     * "call me NOW, emergency" to p=0.64 and costs ~4x the tokens.
     */
    fun stateFor(template: String?, item: Item, index: Int, count: Int, workflowName: String): JsonElement {
        val rendered = template?.takeIf { it.isNotBlank() }?.let {
            Template.render(it, Scope(item, index, count, emptyMap(), emptyMap(), System.currentTimeMillis(), ZoneId.systemDefault(), "", workflowName)).trim()
        }
        // all-null fields render the template's punctuation alone (": —"): judge the item instead
        return if (rendered.isNullOrBlank() || rendered.none { it.isLetterOrDigit() }) SystemOne.stateOf(JSON.encodeToString(JsonObject.serializer(), item)) else SystemOne.stateOf(rendered)
    }

    /** The hook body (Engine.preFilter). Never throws (cancellation aside). `defaultState` = the trigger spec's triageState default. */
    suspend fun filter(app: Context, wf: Workflow, node: NodeInstance, items: Items, defaultState: String? = null): Items =
        filter(node.params, items, wf.id, "${wf.name}/${node.name}", { SystemOne.target(app, it) },
            { t, state, qs, source, ref -> SystemOne.decide(t, state, qs, ITEM_TIMEOUT_MS, source, ref) }, defaultState = defaultState, workflowName = wf.name)

    /** Testable core: `target` resolves the engine (ERR_* throws), `decide` is the engine call (source is always "triage", ref = workflow id). */
    internal suspend fun filter(
        params: JsonObject, items: Items, ref: String, where: String, target: (String) -> S1Target,
        decide: suspend (S1Target, JsonElement, List<S1Question>, String, String?) -> S1Result,
        defaultState: String? = null, workflowName: String = "",
        log: (String) -> Unit = { runCatching { Log.i(LOG_TAG, it) } },
    ): Items {
        val onError = if (params[ON_ERROR].asTextOrNull() == "drop") "drop" else "run"
        fun failed(msg: String?): Items { log("triage $where: ${msg ?: "error"} -> ${if (onError == "run") "run (fail open)" else "drop"}"); return if (onError == "run") items else emptyList() }
        return try {
            val s = spec(params) ?: return items
            val t = target(s.engine)
            val template = if (params.containsKey(STATE) && params[STATE] !is JsonNull) params[STATE].asTextOrNull() else defaultState
            items.mapIndexedNotNull { i, item ->
                try {
                    val r = decide(t, stateFor(template, item, i, items.size, workflowName), s.questions, "triage", ref)
                    if (passes(s, r.answers)) annotate(item, r) else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("triage $where: ${e.message} -> ${if (s.onError == "run") "run (fail open)" else "drop"}")
                    if (s.onError == "run") item else null
                }
            }   // the "dropped" line is TriageHook's (engine), logged once (F4)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message)
        }
    }
}
