package com.mob8n.logic

import android.content.Context
import com.mob8n.apps.JsBridge
import com.mob8n.apps.JsRuntime
import com.mob8n.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * logic.js — run a JavaScript function body over the items inside the app's sandboxed WebView engine (DESIGN4 §7.3).
 * LOGIC / LIST / agentTool = false (the chat operator has the gated `run_js`; ai.agent must never run JS ungated).
 * Approval inside a script = the user-authored `allowNodes` list; ctx.runNode still enforces gates and per-node timeouts.
 */
object JsNode : Node() {
    override val spec = NodeSpec(
        id = "logic.js", name = "JavaScript", kind = NodeKind.LOGIC,
        description = "Run JavaScript over the items in a sandboxed engine (no network except mob8n.http via data.http); returns the new items.",
        params = listOf(
            multiline("code", "JavaScript", required = true, templated = false,
                default = "// item, items, \$vars, mob8n, console are in scope; return the new item (per_item) or an array (all_items)\nreturn { ...item, ok: true };",
                help = "Body of an async function. per_item: called once per item, return an object (undefined passes the item through, null drops it). all_items: called once with items, return an array of objects. Bridge: mob8n.runNode/http/readFile/writeFile/listFiles/deleteFile/mkdir/knowledgeSearch/getVar/setVar/log/now. No network except mob8n.http (needs data.http in allowNodes)."),
            choice("mode", "Mode", listOf("per_item", "all_items"), "per_item"),
            labels("allowNodes", "Nodes the script may run", help = "Catalog ids for mob8n.runNode (e.g. action.notify; add data.http for mob8n.http). Empty = none. Gates still apply"),
            durationMs("timeoutMs", "Timeout", 30_000, 1_000, JsRuntime.MAX_TIMEOUT_MS),
        ),
        mode = ExecMode.LIST,
        timeoutMs = 130_000,
    )

    /** Injectable engine call so JsNodeTest runs on the JVM (no WebView); production = JsRuntime.run. */
    var runner: suspend (Context, String, JsonObject, Long, JsBridge) -> JsRuntime.Run =
        { app, code, input, timeoutMs, bridge -> JsRuntime.run(app, code, input, timeoutMs, bridge) }
    /** Injectable bridge factory (JsBridge.forNode touches Workspace/engine, which need a real Android runtime). */
    var bridge: (ExecutionContext, Set<String>) -> JsBridge = { ctx, allow -> JsBridge.forNode(ctx, allow) }

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val code = ctx.req("code")
        val mode = ctx.str("mode")
        val allow = ctx.labels("allowNodes").toSet()
        val timeout = (ctx.long("timeoutMs") ?: 30_000L).coerceIn(1_000L, JsRuntime.MAX_TIMEOUT_MS)
        val run = runner(ctx.requireAndroid(), code, input(input.items, ctx.vars, mode), timeout, bridge(ctx, allow))
        run.logs.forEach { ctx.log("js: $it") }
        return out(normalize(run.value))
    }

    /** Pure: the wrapper's IN object {item, items, vars, mode} (item = first item; the wrapper iterates items itself in per_item). */
    fun input(items: Items, vars: Map<String, JsonElement>, mode: String): JsonObject = buildJsonObject {
        put("item", items.firstOrNull() ?: EMPTY); put("items", JsonArray(items)); put("vars", JsonObject(vars)); put("mode", mode)
    }

    /** Pure: array -> items (non-object elements wrapped {value}; null elements dropped); object -> [object]; null -> []; primitive -> [{value}]. */
    fun normalize(result: JsonElement): Items = when (result) {
        is JsonArray -> result.mapNotNull { e -> when (e) { is JsonNull -> null; is JsonObject -> e; else -> item("value" to e) } }
        is JsonObject -> listOf(result)
        is JsonNull -> emptyList()
        else -> listOf(item("value" to result))
    }
}
