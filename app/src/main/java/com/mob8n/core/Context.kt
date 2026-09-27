package com.mob8n.core

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** Engine-provided side effects the executor and nodes may need. Concrete; the engine lane fills the lambdas, tests leave defaults. */
class Hooks(
    /** Called AFTER the SuspendedRun is persisted: post approval notification (APPROVAL) or enqueue DelayedRunWorker (TIMER). */
    val onSuspend: suspend (SuspendedRun) -> Unit = {},
    /** WorkManager one-time run of a workflow with payload after delayMs (action.schedule_run). */
    val scheduleRun: suspend (workflowId: String, delayMs: Long, items: Items) -> Unit = { _, _, _ -> },
    /** Enable/disable a workflow and (de)register its triggers (action.toggle_workflow). */
    val setWorkflowEnabled: suspend (workflowId: String, enabled: Boolean) -> Unit = { _, _ -> },
    /** Fire another workflow asynchronously with items (logic.run_workflow with waitForResult=false, action.notify buttons). depth = caller depth + 1 (F7). */
    val fireWorkflow: suspend (workflowId: String, items: Items, depth: Int) -> Unit = { _, _, _ -> },
)

class ExecutionContext(
    val runId: String,
    val workflow: Workflow,
    val instance: NodeInstance,
    val spec: NodeSpec,
    val input: NodeInput,
    val itemIndex: Int,
    /** outputs of already-executed nodes in this run, keyed by node NAME -> MAIN items */
    val upstream: Map<String, Items>,
    val vars: Map<String, JsonElement>,
    val persistence: Persistence,
    val catalog: Catalog,
    val hooks: Hooks,
    /** null in JVM tests; Android nodes call requireAndroid(). */
    val android: Context?,
    val zone: ZoneId,
    val nowMs: () -> Long,
    val logger: (String) -> Unit,
    /** Synchronous sub-workflow (depth-limited). Returns the callee's leaf MAIN items. */
    val runWorkflow: suspend (workflowId: String, items: Items) -> Items,
    /** Run any non-trigger node in isolation with explicit params (Agent tools). Gates + timeout enforced; returns MAIN items or throws. */
    val runNode: suspend (specId: String, params: JsonObject, item: Item) -> Items,
    /** {{$count}}: total items this node processes in the run (PER_ITEM: the whole batch, not 1). Trailing so named-arg callers keep compiling. */
    val itemCount: Int = input.items.size,
    /** Sub-workflow nesting depth of this run (0 = top level); async run_workflow passes depth + 1 so A->B->A chains stop at maxDepth (F7). */
    val depth: Int = 0,
) {
    /** PER_ITEM: the current item. LIST: the first item (or EMPTY). */
    val item: Item get() = input.items.firstOrNull() ?: EMPTY
    val scope: Scope get() = Scope(item, itemIndex, itemCount, upstream, vars, nowMs(), zone, runId, workflow.name)
    val stateScope: String get() = "${workflow.id}:${instance.id}"
    val timeoutMs: Long get() = instance.timeoutMs ?: spec.timeoutMs

    fun requireAndroid(): Context = android ?: throw NodeException("${spec.name} needs the Android runtime")

    /** Raw param (instance value, else schema default), untemplated. */
    fun raw(key: String): JsonElement? = instance.params[key]?.takeUnless { it is JsonNull } ?: spec.param(key)?.default

    /** Param with {{templates}} rendered against the current item; typed by ParamKind. */
    fun param(key: String): JsonElement {
        val p = spec.param(key) ?: throw NodeException("${spec.id} has no param '$key'")
        val v = raw(key) ?: return JsonNull
        return if (p.templated && p.kind != ParamKind.SECRET) render(v, p) else v
    }

    private fun render(v: JsonElement, p: ParamSpec): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.mapValues { (k, cell) ->
            val col = p.rows.firstOrNull { it.key == k }
            if (col != null && !col.templated) cell else render(cell, col ?: p)
        })
        is JsonArray -> JsonArray(v.map { render(it, p) })
        is JsonPrimitive -> if (v.isString && Template.hasTemplate(v.content)) {
            if (p.kind == ParamKind.TEXT || p.kind == ParamKind.MULTILINE || p.kind == ParamKind.TIME) JsonPrimitive(Template.render(v.content, scope))
            else Template.renderJson(v.content, scope)
        } else v
    }

    fun str(key: String): String = param(key).asText()
    fun strOrNull(key: String): String? = str(key).ifBlank { null }
    fun req(key: String): String = strOrNull(key) ?: throw NodeException("${spec.name}: '${spec.param(key)?.label ?: key}' is required")
    fun double(key: String): Double? = param(key).asDouble()
    fun long(key: String): Long? = double(key)?.toLong()
    fun int(key: String): Int? = double(key)?.toInt()
    fun bool(key: String): Boolean = param(key).asBool() ?: false
    fun labels(key: String): List<String> = (param(key) as? JsonArray)?.mapNotNull { it.asTextOrNull() }?.filter { it.isNotBlank() } ?: emptyList()
    fun rows(key: String): List<JsonObject> = (param(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    fun render(template: String): String = Template.render(template, scope)
    fun renderJson(template: String): JsonElement = Template.renderJson(template, scope)

    fun secret(name: String): String = persistence.getSecret(name)?.takeIf { it.isNotBlank() } ?: throw NodeException("Secret '$name' is not set (Settings > AI)")
    suspend fun getVar(key: String): JsonElement? = persistence.getVariable(key)
    suspend fun setVar(key: String, value: JsonElement?) = persistence.setVariable(key, value)
    suspend fun getState(key: String): JsonElement? = persistence.getState(stateScope, key)
    suspend fun putState(key: String, value: JsonElement?, ttlMs: Long? = null) = persistence.putState(stateScope, key, value, ttlMs)
    fun log(msg: String) = logger("[${workflow.name}/${instance.name}] $msg")

    /** Error item for this node: input = current item (PER_ITEM) or the whole list (LIST). */
    fun errorItem(e: Throwable): Item = makeErrorItem(
        e.message ?: e.javaClass.simpleName, instance.name, spec.id,
        if (spec.mode == ExecMode.LIST) JsonArray(input.items) else item,
    )
}
