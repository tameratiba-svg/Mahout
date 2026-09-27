package com.mob8n.triggers

import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.Items
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TriggerNode
import com.mob8n.core.appPicker
import com.mob8n.core.bool
import com.mob8n.core.str
import com.mob8n.core.text
import com.mob8n.core.without
import kotlinx.serialization.json.JsonObject

/** Raw events come from NotifListener.onNotificationPosted (null-safe extras). */
object NotificationPostedTrigger : TriggerNode() {
    override val hosting = Hosting.LISTENER
    override val spec = NodeSpec(
        id = "trigger.notification_posted", name = "Notification Posted", kind = NodeKind.TRIGGER,
        description = "Fires when an app posts a notification (needs notification access).",
        params = listOf(
            appPicker("packageName", "App", help = "Only notifications from this app (blank = any)"),
            text("titleRegex", "Title matches (regex)", templated = false),
            text("textRegex", "Text matches (regex)", templated = false, help = "Matched against text and bigText"),
            bool("ignoreOngoing", "Ignore ongoing notifications", true),
            bool("ignoreGroupSummary", "Ignore group summaries", true),
        ) + TriageParams.params(TriageParams.NOTIFICATION_STATE),   // v5: System 1 triage, LAST; accepts()/toItems() ignore them (DESIGN5 §6.1)
        inputs = emptyList(), gates = listOf(Gate.NotificationListener),
    )

    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val pkg = spec.pStr(params, "packageName")
        if (pkg != null && event.str("packageName") != pkg) return false
        if (spec.pBool(params, "ignoreOngoing") && event.bool("ongoing") == true) return false
        if (spec.pBool(params, "ignoreGroupSummary") && event.bool("isGroupSummary") == true) return false
        if (!regexOk(spec.pStr(params, "titleRegex"), event.str("title"))) return false
        val body = listOfNotNull(event.str("text"), event.str("bigText")).joinToString("\n")
        return regexOk(spec.pStr(params, "textRegex"), body)
    }

    override fun toItems(params: JsonObject, event: JsonObject): Items = listOf(event.without(listOf("isGroupSummary")))
}

/** Raw events come from NotifListener.onNotificationRemoved. */
object NotificationRemovedTrigger : TriggerNode() {
    override val hosting = Hosting.LISTENER
    override val spec = NodeSpec(
        id = "trigger.notification_removed", name = "Notification Removed", kind = NodeKind.TRIGGER,
        description = "Fires when a notification is dismissed or cancelled (needs notification access).",
        params = listOf(
            appPicker("packageName", "App", help = "Only notifications from this app (blank = any)"),
            text("titleRegex", "Title matches (regex)", templated = false),
        ),
        inputs = emptyList(), gates = listOf(Gate.NotificationListener),
    )

    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val pkg = spec.pStr(params, "packageName")
        if (pkg != null && event.str("packageName") != pkg) return false
        return regexOk(spec.pStr(params, "titleRegex"), event.str("title"))
    }
}
