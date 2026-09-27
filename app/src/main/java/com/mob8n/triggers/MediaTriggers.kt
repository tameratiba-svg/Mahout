package com.mob8n.triggers

import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TriggerNode
import com.mob8n.core.appPicker
import com.mob8n.core.bool
import com.mob8n.core.str
import kotlinx.serialization.json.JsonObject

/** Raw events come from NotifListener (MediaSessionManager + MediaController.Callback, 1 s debounce). */
object NowPlayingTrigger : TriggerNode() {
    override val hosting = Hosting.LISTENER
    override val spec = NodeSpec(
        id = "trigger.now_playing", name = "Now Playing", kind = NodeKind.TRIGGER,
        description = "Fires when the playing track changes in any media app (needs notification access).",
        params = listOf(
            appPicker("sourceApp", "Source app", help = "Only this media app (blank = any)"),
            bool("onlyWhenPlaying", "Only when playing", true),
        ),
        inputs = emptyList(), gates = listOf(Gate.NotificationListener),
    )

    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val app = spec.pStr(params, "sourceApp")
        if (app != null && event.str("sourceApp") != app) return false
        return !spec.pBool(params, "onlyWhenPlaying") || event.str("state") == "playing"
    }
}
