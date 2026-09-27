package com.mob8n.ui

import com.mob8n.ai.PermissionMode

// Pure composer logic (DESIGN6 §5.3.12, §5.3.13, §5.4): slash commands, @-mentions, the outgoing Context line, streaming announcements. JVM-tested.

/** The six composer commands, in popup order. `takesArg` = the popup continues with argument suggestions after "/cmd ". */
enum class SlashCmd(val key: String, val help: String, val takesArg: Boolean) {
    NEW("new", "Start a fresh chat", false),
    CLEAR("clear", "Fresh chat with these settings", false),
    MODE("mode", "Set this chat's mode", true),
    RUN("run", "Run a workflow now", true),
    SKILL("skill", "Add a skill as context", true),
    PANEL("panel", "Open a panel", true);
    val label: String get() = "/$key"
    companion object { fun of(key: String): SlashCmd? = entries.firstOrNull { it.key == key.lowercase() } }
}

/** Where the cursor is inside a leading slash command: still typing the command, or typing its argument. */
sealed interface SlashState {
    data class Command(val prefix: String) : SlashState
    data class Arg(val cmd: SlashCmd, val arg: String) : SlashState
}

/** One popup row. `text` = the whole composer text after picking it; `needsConfirm` = picking it opens a confirmation (Bypass). */
data class SlashSuggestion(val label: String, val help: String, val text: String, val needsConfirm: Boolean = false)

/** Names the argument popups offer: workflows (id, name), enabled skills, panels (title). */
data class SlashSources(val workflows: List<Pair<String, String>> = emptyList(), val skills: List<String> = emptyList(), val panels: List<String> = emptyList())

val MODE_ARGS = listOf("inherit", "plan", "ask", "auto", "bypass")

/**
 * Active only when the text starts with `/` and the cursor is in the first token (a known command's prefix) or in the argument of a
 * command that takes one. Anything else (unknown command, text before the slash, cursor elsewhere) -> null: the text is a normal message.
 */
fun parseSlash(text: String, cursor: Int): SlashState? {
    if (!text.startsWith("/") || cursor < 1 || cursor > text.length) return null
    val tokenEnd = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
    val token = text.substring(1, tokenEnd)
    if (cursor <= tokenEnd) {
        if (text.substring(tokenEnd).isNotBlank()) return null   // editing the command of a line that already has an argument
        val prefix = text.substring(1, cursor).lowercase()
        return if (SlashCmd.entries.any { it.key.startsWith(prefix) }) SlashState.Command(prefix) else null
    }
    val cmd = SlashCmd.of(token)?.takeIf { it.takesArg } ?: return null
    if (text[tokenEnd] != ' ') return null
    if ('\n' in text.substring(tokenEnd)) return null   // a multi-line message is never a command
    val arg = text.substring(tokenEnd + 1, cursor)
    return SlashState.Arg(cmd, arg.trimStart())
}

/** Popup rows for a state; argument lists filtered case-insensitively (prefix matches first, then substring matches). */
fun slashSuggestions(state: SlashState, src: SlashSources = SlashSources()): List<SlashSuggestion> = when (state) {
    is SlashState.Command -> SlashCmd.entries.filter { it.key.startsWith(state.prefix) }
        .map { SlashSuggestion(it.label, it.help, if (it.takesArg) "${it.label} " else it.label) }
    is SlashState.Arg -> {
        val names = when (state.cmd) {
            SlashCmd.MODE -> MODE_ARGS
            SlashCmd.RUN -> src.workflows.map { it.second }
            SlashCmd.SKILL -> src.skills
            SlashCmd.PANEL -> src.panels
            else -> emptyList()
        }
        filterNames(names, state.arg).map { n ->
            SlashSuggestion(n, if (state.cmd == SlashCmd.MODE) modeArgHelp(n) else state.cmd.help, "${state.cmd.label} $n", needsConfirm = state.cmd == SlashCmd.MODE && n == "bypass")
        }
    }
}

private fun modeArgHelp(n: String): String = when (n) { "inherit" -> "Use the global mode"; "bypass" -> "Asks for confirmation"; else -> PermissionMode.parse(n)?.let(::modeLabel).orEmpty() }

internal fun filterNames(names: List<String>, q: String, max: Int = 8): List<String> {
    val needle = q.trim().lowercase()
    val distinct = names.distinct()
    if (needle.isEmpty()) return distinct.take(max)
    val starts = distinct.filter { it.lowercase().startsWith(needle) }
    return (starts + distinct.filter { needle in it.lowercase() && it !in starts }).take(max)
}

/** What pressing Send does with a slash line. null = not a command: send the text as a normal message (never swallowed). */
sealed interface SlashAction {
    data object New : SlashAction
    data object Clear : SlashAction
    /** mode == null = inherit. */
    data class Mode(val mode: PermissionMode?) : SlashAction
    data class Run(val name: String) : SlashAction
    data class Skill(val name: String) : SlashAction
    data class Panel(val name: String) : SlashAction
    /** Known command, missing/unknown argument: keep the draft and show the hint. */
    data class Incomplete(val hint: String) : SlashAction
}

fun resolveSlash(text: String): SlashAction? {
    val t = text.trim()
    if (!t.startsWith("/") || '\n' in t) return null
    val cmd = SlashCmd.of(t.drop(1).substringBefore(' ')) ?: return null
    val arg = t.substringAfter(' ', "").trim()
    return when (cmd) {
        SlashCmd.NEW -> if (arg.isEmpty()) SlashAction.New else null
        SlashCmd.CLEAR -> if (arg.isEmpty()) SlashAction.Clear else null
        SlashCmd.MODE -> when {
            arg.lowercase() == PermissionMode.INHERIT -> SlashAction.Mode(null)
            else -> PermissionMode.parse(arg)?.let { SlashAction.Mode(it) } ?: SlashAction.Incomplete("Pick a mode: ${MODE_ARGS.joinToString(", ")}")
        }
        SlashCmd.RUN -> if (arg.isEmpty()) SlashAction.Incomplete("Name a workflow: /run <workflow>") else SlashAction.Run(arg)
        SlashCmd.SKILL -> if (arg.isEmpty()) SlashAction.Incomplete("Name a skill: /skill <skill>") else SlashAction.Skill(arg)
        SlashCmd.PANEL -> if (arg.isEmpty()) SlashAction.Incomplete("Name a panel: /panel <panel>") else SlashAction.Panel(arg)
    }
}

// ---- @-mentions ----

enum class MentionKind(val word: String) { WORKFLOW("workflow"), SKILL("skill"), KNOWLEDGE("knowledge") }

/** A context chip. `id` only for workflows (first 8 chars go into the Context line). */
data class Mention(val kind: MentionKind, val name: String, val id: String? = null)

/** `@word` being typed: `start` = index of '@' (at text start or after whitespace), `query` = text between '@' and the cursor. */
data class MentionQuery(val start: Int, val query: String)

fun parseMention(text: String, cursor: Int): MentionQuery? {
    if (cursor < 1 || cursor > text.length) return null
    val at = text.lastIndexOf('@', cursor - 1)
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
    val q = text.substring(at + 1, cursor)
    if (q.any { it.isWhitespace() }) return null
    return MentionQuery(at, q)
}

/** Mention candidates filtered by the query; workflows, then skills, then knowledge sources. */
fun mentionSuggestions(q: String, workflows: List<Pair<String, String>>, skills: List<String>, knowledge: List<String>, max: Int = 8): List<Mention> {
    val w = filterNames(workflows.map { it.second }, q, max).map { n -> Mention(MentionKind.WORKFLOW, n, workflows.first { it.second == n }.first) }
    val s = filterNames(skills, q, max).map { Mention(MentionKind.SKILL, it) }
    val k = filterNames(knowledge.filter { it != "all" }, q, max).map { Mention(MentionKind.KNOWLEDGE, it) }
    return (w + s + k).take(max)
}

/** Removes the `@query` being typed (cursor lands where the '@' was). */
fun removeMentionQuery(text: String, m: MentionQuery, cursor: Int): Pair<String, Int> = (text.removeRange(m.start, cursor.coerceIn(m.start, text.length))) to m.start

private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * The user row's text (plain text: transcript contract intact). No chips -> `text` unchanged; otherwise
 * `Context: workflow "Morning digest" [1a2b3c4d]; skill coding-on-device; knowledge "Manuals".` + blank line + text.
 */
fun composeOutgoing(text: String, mentions: List<Mention>): String {
    val parts = mentions.distinctBy { Triple(it.kind, it.name, it.id) }.map { m ->
        when (m.kind) {
            MentionKind.WORKFLOW -> "workflow ${quoted(m.name)}" + (m.id?.let { " [${it.take(8)}]" } ?: "")
            MentionKind.SKILL -> "skill ${m.name}"
            MentionKind.KNOWLEDGE -> "knowledge ${quoted(m.name)}"
        }
    }.distinct()
    return if (parts.isEmpty()) text else "Context: ${parts.joinToString("; ")}.\n\n$text"
}

// ---- streaming announcements (§5.4) ----

const val ANNOUNCE_GAP_MS = 2_500L
const val ANNOUNCE_FIRST = "Mahout is responding"

/**
 * Next polite live-region announcement for streaming text, or null. `spokenUpTo` = chars of `text` already spoken; `lastMs` = time of
 * the last announcement (0 = none yet this turn). First call of a turn -> "Mahout is responding". Then, at most every 2.5 s, the newly
 * completed sentences. `done` -> the unspoken remainder ("Mahout: …" when nothing was spoken). Returns (announcement, new spokenUpTo).
 */
fun nextAnnouncement(text: String, spokenUpTo: Int, nowMs: Long, lastMs: Long, done: Boolean): Pair<String, Int>? {
    val from = spokenUpTo.coerceIn(0, text.length)
    if (done) {
        val rest = text.substring(from).trim()
        return if (rest.isEmpty()) null else (if (from == 0) "Mahout: $rest" else rest) to text.length
    }
    if (lastMs == 0L) return ANNOUNCE_FIRST to from
    if (nowMs - lastMs < ANNOUNCE_GAP_MS) return null
    val end = lastSentenceEnd(text, from)
    if (end <= from) return null
    val said = text.substring(from, end).trim()
    return if (said.isEmpty()) null else said to end
}

/** Index just past the last sentence terminator (. ! ? followed by whitespace, or a newline) at or after `from`; `from` when none. */
private fun lastSentenceEnd(text: String, from: Int): Int {
    var end = from
    for (i in from until text.length) {
        val c = text[i]
        if (c == '\n' || (c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < text.length && text[i + 1].isWhitespace()) end = i + 1
    }
    return end
}
