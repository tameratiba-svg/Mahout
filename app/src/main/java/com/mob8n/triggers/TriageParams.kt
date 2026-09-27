package com.mob8n.triggers

import com.mob8n.ai.DecideNode
import com.mob8n.core.ParamSpec
import com.mob8n.core.choice
import com.mob8n.core.number
import com.mob8n.core.rows
import com.mob8n.core.text
import com.mob8n.core.whenIs

/**
 * v5 (DESIGN5 §3.3 / §6.1): System 1 triage params appended LAST to trigger.notification_posted and trigger.share.
 * accepts()/toItems() ignore them; the engine's TriggerHub.triaged() reads `triageEngine` and ai.Triage does the rest.
 * The sanctioned cross-lane import is exactly one value: com.mob8n.ai.DecideNode.QUESTION_COLUMNS (README §2 addendum).
 */
object TriageParams {
    const val ENGINE = "triageEngine"; const val QUESTIONS = "triageQuestions"; const val ON_ERROR = "triageOnError"; const val STATE = "triageState"
    val ON = arrayOf("default", "jev", "laya")
    /** v5 device phase (F3): what the engine reads — the message text, not the whole item JSON (package, key, nulls, postTime diluted p 0.81 -> 0.64). */
    const val NOTIFICATION_STATE = "{{appName}}: {{title}} — {{text}} {{bigText}}"   // bigText: the BigTextStyle mail/chat body (text is then only the subject); a space, not \n: TEXT fields are single-line
    const val SHARE_STATE = "{{subject}} {{text}} {{url}}"
    fun params(stateDefault: String): List<ParamSpec> = listOf(
        choice(ENGINE, "Triage with the decision engine", listOf("off") + ON, "off",
            help = "System 1 pre-filter evaluated BEFORE a run starts; the event is dropped unless every row passes (~50–300 ms; no run row for dropped events). Sends the event text to that engine (Laya stays on your LAN)."),
        rows(QUESTIONS, "Triage questions", DecideNode.QUESTION_COLUMNS.map { if (it.key == "type") choice("type", "Type", listOf("noul", "score"), "noul") else it }
            + number("threshold", "Threshold", 0.7, 0.0, 9.0, help = "noul: minimum P(true) 0–1; score: minimum level index (0 = lowest)"),
            visibleWhen = whenIs(ENGINE, *ON), help = "AND over rows; kept events carry the answers under triage.<name>. Ask about the message, e.g. noul " +
                "'Is this message urgent enough to interrupt the user?' (a real \"call me NOW, emergency\" scored p=0.81 on Laya)"),
        choice(ON_ERROR, "If the engine fails", listOf("run", "drop"), "run", visibleWhen = whenIs(ENGINE, *ON), help = "run = fail open (default); drop = fail closed"),
        text(STATE, "Triage text", stateDefault, visibleWhen = whenIs(ENGINE, *ON),
            help = "What the engine reads, templated against the event (blank = the whole event JSON). Keep it to the message text: extra fields lower the scores."),
    )
}
