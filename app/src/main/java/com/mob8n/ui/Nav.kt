package com.mob8n.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.mob8n.core.JSON
import kotlinx.serialization.Serializable

/** Plain state navigation (DESIGN §9.1). Serialized to one JSON string for rememberSaveable (ids may contain any character). */
@Serializable
sealed class Screen {
    @Serializable data object List : Screen()
    @Serializable data class Editor(val workflowId: String, val focusNodeId: String? = null) : Screen()
    @Serializable data class Runs(val workflowId: String? = null) : Screen()
    @Serializable data class RunDetail(val runId: String) : Screen()
    @Serializable data object Permissions : Screen()
    @Serializable data object AiSettings : Screen()
    @Serializable data object Notes : Screen()
    @Serializable data object Playlist : Screen()
    /** v3 (DESIGN3 §6.1): the knowledge base screen. */
    @Serializable data object Knowledge : Screen()
    /** v3 (DESIGN3 §6.2): Settings > AI > MCP servers; Back returns to AiSettings. */
    @Serializable data object McpSettings : Screen()
    /** Build with AI; workflowId = the editor it was opened from (Back returns there). */
    @Serializable data class Build(val workflowId: String? = null) : Screen()
    /** v4 (DESIGN4 §3.7): top-level Dashboard (Workflows stays the landing screen). */
    @Serializable data object Dashboard : Screen()
    /** v4: chat history (conversationId == null) or one thread. */
    @Serializable data class Chat(val conversationId: String? = null) : Screen()
    /** v4: skills list / editor. */
    @Serializable data object Skills : Screen()
    /** v5 (DESIGN5 §8.3): Dashboard > Panels; slug = the panel to show first (deep link mob8n://panel/<slug>). */
    @Serializable data class Panels(val slug: String? = null) : Screen()

    /** Where Back goes. */
    val parent: Screen get() = when (this) {
        is Runs -> if (workflowId != null) Editor(workflowId) else List
        is Build -> if (workflowId != null) Editor(workflowId) else List
        McpSettings -> AiSettings
        is Chat -> if (conversationId != null) Chat() else List
        is Panels -> Dashboard
        else -> List
    }

    /** Which top-level tab is highlighted (DESIGN4 §3.7). */
    val section: Screen get() = when (this) {
        Dashboard, is Panels -> Dashboard
        is Chat -> Chat()
        Knowledge -> Knowledge
        Skills -> Skills
        else -> List
    }

    /** Screens that show the phone bottom bar; a chat thread hides it so the keyboard and composer have room. */
    val topLevel: Boolean get() = this == Dashboard || this == List || this == Chat() || this == Knowledge || this == Skills

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        /** A malformed saved string (e.g. from an older build) must never throw on restore: fall back to List. */
        fun decode(s: String): Screen = runCatching { JSON.decodeFromString(serializer(), s) }.getOrDefault(List)
        val SAVER: Saver<Screen, String> = Saver({ it.encode() }, { decode(it) })
    }
}

@Composable
fun rememberScreen(): MutableState<Screen> = rememberSaveable(stateSaver = Screen.SAVER) { mutableStateOf(Screen.List) }

// ---- v6 navigation motion (DESIGN6 §6.1; pure, NavTransitionTest) ----

/** Navigation depth: a deeper target pushes, a shallower one pops, equal depth fades through. */
val Screen.depth: Int get() = when (this) {
    Screen.List, Screen.Dashboard, Screen.Knowledge, Screen.Skills -> 0
    is Screen.Chat -> if (conversationId == null) 0 else 1
    is Screen.Editor, Screen.Permissions, Screen.AiSettings, Screen.Notes, Screen.Playlist, is Screen.Panels -> 1
    is Screen.Build -> if (workflowId == null) 1 else 2
    is Screen.Runs -> if (workflowId == null) 1 else 2
    Screen.McpSettings -> 2
    is Screen.RunDetail -> 3
}

/** SaveableStateProvider key + AnimatedContent contentKey. */
val Screen.contentKey: String get() = encode()

enum class NavKind { NONE, FADE_THROUGH, PUSH, POP }

fun navKind(from: Screen, to: Screen): NavKind = when {
    from.contentKey == to.contentKey -> NavKind.NONE
    to.depth > from.depth -> NavKind.PUSH
    to.depth < from.depth -> NavKind.POP
    else -> NavKind.FADE_THROUGH
}

/** Phone bottom-bar labels: hidden above 130 % font scale so 200 % never clips the 80 dp bar. */
fun showNavLabels(fontScale: Float): Boolean = fontScale <= 1.3f
