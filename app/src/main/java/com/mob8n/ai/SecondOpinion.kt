package com.mob8n.ai

import android.content.Context
import com.mob8n.core.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Risk second opinion (DESIGN5 §3.4, §6.2; flag s1_second_opinion, default OFF): a System 1 yes/no on "is this call destructive beyond its class?".
 * RUN -> ASK only, never lowers a class, fail-open (engine down = the baseline table decides).
 */
// ponytail: second opinion only for CODING tools in Auto; upgrade = WRITE tools behind a second flag
object SecondOpinion {
    const val THRESHOLD = 0.7; const val TIMEOUT_MS = 3_000L; const val INPUT_CAP = 4_000
    val QUESTION = S1Question.Noul("destructive",
        "An AI agent is about to run the tool below on the user's phone under the permission class given, which may run WITHOUT asking. Is this specific input destructive, irreversible, data-exfiltrating, or clearly beyond what that class covers? 'coding' permits creating/editing files inside the app workspace and read-only shell text tools.",
        trueDesc = "yes — deletes or overwrites data outside the workspace, sends data to third parties, changes system settings, or does more than the class implies",
        falseDesc = "no — a routine, reversible, in-scope use")

    /** Pure: RUN verdict, Risk.CODING, mode AUTO. READ never; WRITE not in v5; ASK/BLOCK already decided; PLAN blocks; BYPASS is the user's explicit choice and is NOT overridden. */
    fun candidate(d: Decision): Boolean = d.verdict == Verdict.RUN && d.risk == Risk.CODING && d.mode == PermissionMode.AUTO

    /** {"tool", "class", "input": redacted JSON text ≤ 4 000 chars}. */
    fun state(tool: String, input: JsonObject, risk: Risk, secrets: Collection<String>): JsonObject = buildJsonObject {
        put("tool", tool); put("class", Permissions.riskLabel(risk)); put("input", Redaction.redact(input, secrets).toString().take(INPUT_CAP))
    }

    /** Reason line or null. Flag off / not configured / not a candidate / engine error or timeout -> null (logged). */
    suspend fun escalate(app: Context, secrets: Collection<String>, tool: String, d: Decision, input: JsonObject, source: String, ref: String?, log: (String) -> Unit): String? =
        escalate(S1Prefs.readSecondOpinion(app), { SystemOne.target(app, SystemOne.ENGINE_DEFAULT) }, { t, state -> SystemOne.decide(t, state, listOf(QUESTION), TIMEOUT_MS, source, ref) },
            secrets, tool, d, input, log)

    /** Testable core. */
    internal suspend fun escalate(
        flagOn: Boolean, target: () -> S1Target, decide: suspend (S1Target, JsonElement) -> S1Result,
        secrets: Collection<String>, tool: String, d: Decision, input: JsonObject, log: (String) -> Unit,
    ): String? {
        if (!flagOn || !candidate(d)) return null
        return try {
            val t = target()
            val r = withTimeoutOrNull(TIMEOUT_MS) { decide(t, state(tool, input, d.risk, secrets)) }
                ?: run { log("second opinion unavailable: timed out after ${TIMEOUT_MS / 1000} s"); return null }
            val p = (r.answers[QUESTION.name] as? S1Answer.Noul)?.p ?: run { log("second opinion unavailable: no answer"); return null }
            if (p > THRESHOLD) "second opinion: looks destructive beyond '${Permissions.riskLabel(d.risk)}' (p=${String.format(Locale.US, "%.2f", p)})" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("second opinion unavailable: ${e.message}")
            null
        }
    }
}
