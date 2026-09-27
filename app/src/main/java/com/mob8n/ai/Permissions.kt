package com.mob8n.ai

import android.content.Context
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PLAN-v5 §4 / DESIGN4P §2.1. Order matters (ordinal = permissiveness); `key` is the settings / ai.agent wire value. */
@Serializable
enum class PermissionMode {
    @SerialName("plan") PLAN, @SerialName("ask") ASK, @SerialName("auto") AUTO, @SerialName("bypass") BYPASS;
    val key: String get() = name.lowercase()
    companion object {
        const val INHERIT = "inherit"
        /** "inherit" / null / junk -> null. */
        fun parse(s: String?): PermissionMode? = entries.firstOrNull { it.key == s?.trim()?.lowercase() }
    }
}

enum class Verdict { RUN, ASK, BLOCK }

/** One policy answer. `reason` is the human line shown on the card / in the blocked tool result. */
data class Decision(val verdict: Verdict, val mode: PermissionMode, val risk: Risk, val reason: String)

/** The one place that reads the PLAN-v5 §4 table (DESIGN4P P1). Pure. */
object Permissions {
    const val BYPASS_TTL_MS = 60 * 60_000L
    const val STOPPED_BY_USER = "stopped by user"
    const val BLOCKED_PREFIX = "plan mode: "
    const val EXPIRED = "Bypass expired — this call now needs approval; ask the user to approve it or switch mode"

    /** PLAN: READ runs, else BLOCK. ASK: READ runs, else ASK. AUTO: READ/WRITE/CODING run, ALWAYS asks. BYPASS: everything runs. */
    fun decide(mode: PermissionMode, risk: Risk, toolName: String = "this tool"): Decision {
        val v = when {
            risk == Risk.READ || mode == PermissionMode.BYPASS -> Verdict.RUN
            mode == PermissionMode.PLAN -> Verdict.BLOCK
            mode == PermissionMode.ASK -> Verdict.ASK
            else -> if (risk == Risk.ALWAYS) Verdict.ASK else Verdict.RUN   // AUTO
        }
        return Decision(v, mode, risk, reason(v, mode, risk, toolName))
    }

    private fun reason(v: Verdict, mode: PermissionMode, risk: Risk, toolName: String): String = when (v) {
        Verdict.RUN -> if (risk == Risk.READ) "read-only" else "auto-approved by ${mode.key}"
        Verdict.ASK -> "asks in ${mode.key}"
        Verdict.BLOCK -> blockedMessage(toolName)
    }

    fun blockedMessage(toolName: String): String =
        "$BLOCKED_PREFIX$toolName is blocked (read-only tools only). Draft and preview; the user switches to Ask or Auto to run it." +
            (if (toolName == "save_workflow") " Open in editor to save it yourself." else "")

    /** agent param > conversation > global; a BYPASS at EITHER scope collapses to ASK when ITS OWN until <= now (P3). */
    fun resolve(agentParam: PermissionMode? = null, conversation: PermissionMode? = null, conversationUntil: Long = 0L, global: PermissionMode, globalUntil: Long, now: Long): PermissionMode {
        if (agentParam != null) return agentParam
        if (conversation != null) return if (conversation == PermissionMode.BYPASS && conversationUntil <= now) PermissionMode.ASK else conversation
        return if (global == PermissionMode.BYPASS && globalUntil <= now) PermissionMode.ASK else global
    }

    /** The decision for one built tool: the table, plus PLAN blocking every `kind == "mcp"` tool whatever its risk (P18a). */
    // ponytail: MCP blocked in Plan by kind; upgrade = honour readOnlyHint
    fun decideFor(t: AgentTool, mode: PermissionMode, risk: Risk): Decision =
        if (mode == PermissionMode.PLAN && t.kind == "mcp") Decision(Verdict.BLOCK, mode, risk, blockedMessage(t.name)) else decide(mode, risk, t.name)

    /** Pure: withApproval per verdict; BLOCK -> AgentTool.blocked(message). Same instance when nothing changes. */
    fun gate(tools: Map<String, AgentTool>, mode: PermissionMode, risk: (AgentTool) -> Risk): Map<String, AgentTool> = tools.mapValues { (_, t) ->
        val d = decideFor(t, mode, risk(t))
        if (d.verdict == Verdict.BLOCK) t.blocked(d.reason) else t.withApproval(d.verdict == Verdict.ASK)
    }

    /** DESTRUCTIVE_IDS or a UI-automation node — the node ids the §4 table never lets Auto run. */
    fun isAlwaysNode(id: String): Boolean = id in OperatorTools.DESTRUCTIVE_IDS || AgentNode.isUiTool(id)

    /** P15: AUTO -> `allowNodes` minus every isAlwaysNode id (second = the dropped ids); any other mode -> (input, empty). */
    // ponytail: run_js allow-list sanitised by mode (ASK = the whole call was approved), not per id; upgrade = per-id approval inside the script
    fun sanitizeJsAllow(input: JsonObject, mode: PermissionMode): Pair<JsonObject, List<String>> {
        if (mode != PermissionMode.AUTO) return input to emptyList()
        val allow = (input["allowNodes"] as? JsonArray)?.mapNotNull { it.asTextOrNull() } ?: return input to emptyList()
        val dropped = allow.filter { isAlwaysNode(it.trim()) }.distinct()
        if (dropped.isEmpty()) return input to emptyList()
        return JsonObject(input + ("allowNodes" to JsonArray(allow.filter { it.trim() !in dropped }.map(::JsonPrimitive)))) to dropped
    }

    fun riskLabel(r: Risk): String = when (r) { Risk.READ -> "read-only"; Risk.WRITE -> "safe action"; Risk.CODING -> "coding"; Risk.ALWAYS -> "destructive" }
    fun verdictLabel(v: Verdict): String = when (v) { Verdict.RUN -> "ran"; Verdict.ASK -> "asks"; Verdict.BLOCK -> "blocked" }
    /** "safe action · ran (auto)" — the tool-card chip text (P8). */
    fun chip(d: Decision): String = "${riskLabel(d.risk)} · ${verdictLabel(d.verdict)} (${d.mode.key})"

    /** P8 frozen audit: { "<tool_use id>": "<risk>/<verdict>" } for one assistant row; second-opinion `escalated` ids read "<risk>/ASK" (DESIGN5 §3.4). */
    fun gateMeta(uses: List<ToolUse>, decisionFor: (toolName: String) -> Decision?): JsonObject = gateMeta(uses, decisionFor, emptySet())
    fun gateMeta(uses: List<ToolUse>, decisionFor: (toolName: String) -> Decision?, escalated: Set<String>): JsonObject = buildJsonObject {
        uses.forEach { u -> decisionFor(u.name)?.let { put(u.id, "${it.risk.name.lowercase()}/${if (u.id in escalated) Verdict.ASK.name else it.verdict.name}") } }
    }

    fun parseGate(s: String?, mode: PermissionMode): Decision? {
        val (r, v) = s?.split('/')?.takeIf { it.size == 2 } ?: return null
        val risk = Risk.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } ?: return null
        val verdict = Verdict.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: return null
        return Decision(verdict, mode, risk, reason(verdict, mode, risk, "this tool"))
    }
}

/** settings["permission_mode"] (default ASK) + settings["bypass_until"] (epoch ms, 0 = none). DESIGN4P P13. The GLOBAL scope only; a conversation Bypass lives in ChatSettings. */
object HarnessPrefs {
    const val KEY_MODE = "permission_mode"
    const val KEY_BYPASS_UNTIL = "bypass_until"
    private val _mode = MutableStateFlow(PermissionMode.ASK)
    private val _bypassUntil = MutableStateFlow(0L)
    val mode: StateFlow<PermissionMode> = _mode.asStateFlow()
    val bypassUntil: StateFlow<Long> = _bypassUntil.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    /** Idempotent re-read into the flows (screens call it in a LaunchedEffect, like AiPrefs.load). */
    fun load(ctx: Context) { _mode.value = readMode(ctx); _bypassUntil.value = readBypassUntil(ctx) }
    fun readMode(ctx: Context): PermissionMode = runCatching { PermissionMode.parse(prefs(ctx).getString(KEY_MODE, null)) }.getOrNull() ?: PermissionMode.ASK
    fun readBypassUntil(ctx: Context): Long = runCatching { prefs(ctx).getLong(KEY_BYPASS_UNTIL, 0L) }.getOrDefault(0L)
    fun isBypassActive(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = readBypassUntil(ctx) > now

    /** BYPASS requires confirmed = true and arms bypass_until = now + TTL; every other mode writes bypass_until = 0. The only writer of a non-zero bypass_until. */
    fun setMode(ctx: Context, mode: PermissionMode, confirmed: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (mode == PermissionMode.BYPASS && !confirmed) throw IllegalArgumentException("Bypass needs confirmation")
        val until = if (mode == PermissionMode.BYPASS) now + Permissions.BYPASS_TTL_MS else 0L
        prefs(ctx).edit().putString(KEY_MODE, mode.key).putLong(KEY_BYPASS_UNTIL, until).commit()
        _mode.value = mode; _bypassUntil.value = until
    }

    /** Kill switch / banner Stop / TIME_SET / BOOT: bypass_until = 0; a stored global BYPASS is written back to ASK. Synchronous (commit), never throws. */
    fun clearBypass(ctx: Context) {
        runCatching {
            val e = prefs(ctx).edit().putLong(KEY_BYPASS_UNTIL, 0L)
            if (readMode(ctx) == PermissionMode.BYPASS) e.putString(KEY_MODE, PermissionMode.ASK.key)
            e.commit()
        }
        _bypassUntil.value = 0L
        if (_mode.value == PermissionMode.BYPASS) _mode.value = PermissionMode.ASK
    }

    /** The mode a chat turn / an inheriting agent runs under right now (conversation scope > global, each with its own expiry). */
    fun effective(ctx: Context, s: ChatSettings? = null, now: Long = System.currentTimeMillis()): PermissionMode =
        Permissions.resolve(null, s?.modeOrLegacy(), s?.bypassUntil ?: 0L, readMode(ctx), readBypassUntil(ctx), now)
}
