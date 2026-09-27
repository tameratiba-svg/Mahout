package com.mob8n.triggers

import android.content.pm.PackageManager
import com.mob8n.core.ExecMode
import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TRIGGER_CALLED
import com.mob8n.core.TRIGGER_MANUAL
import com.mob8n.core.TRIGGER_NOTIFICATION_ACTION
import com.mob8n.core.TriggerInstance
import com.mob8n.core.TriggerNode
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.item
import com.mob8n.core.multiline
import com.mob8n.core.rows
import com.mob8n.core.str
import com.mob8n.core.text
import com.mob8n.core.whenIs
import kotlinx.serialization.json.JsonObject

/** Fired by EntryActivity (ACTION_SEND / SEND_MULTIPLE); one event per shared thing. */
object ShareTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = "trigger.share", name = "Shared to Mahout", kind = NodeKind.TRIGGER,
        description = "Fires when something is shared to Mahout from another app's share sheet.",
        params = listOf(choice("accept", "Accept", listOf("any", "text", "url", "image", "file"))) + TriageParams.params(TriageParams.SHARE_STATE),   // v5: triage LAST; accepts() ignores them
        inputs = emptyList(),
    )
    private val URL_RE = Regex("""https?://\S+""")
    fun urlIn(text: String?): String? = text?.let { URL_RE.find(it)?.value?.trimEnd('.', ',', ')', '>') }

    override fun accepts(params: JsonObject, event: JsonObject): Boolean = when (spec.pStr(params, "accept") ?: "any") {
        "text" -> event.str("text") != null
        "url" -> event.str("url") != null
        "image" -> event.str("mimeType")?.startsWith("image/") == true
        "file" -> event.str("uri") != null
        else -> true
    }
}

/** Fired by QsTileService.onClick for every enabled workflow using it. */
object TileTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = "trigger.tile", name = "Quick Settings Tile", kind = NodeKind.TRIGGER,
        description = "Fires when the Mahout Quick Settings tile is tapped; the tile toggles active/inactive.",
        inputs = emptyList(),
    )
}

/** Fired by EntryActivity (action com.mob8n.SHORTCUT, extra slot=1..4 or workflowId for dynamic shortcuts). */
object ShortcutTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = "trigger.shortcut", name = "Launcher Shortcut", kind = NodeKind.TRIGGER,
        description = "Fires from a launcher shortcut: one of the 4 static slots or this workflow's dynamic shortcut.",
        params = listOf(choice("slot", "Shortcut", listOf("1", "2", "3", "4", "dynamic"), "dynamic")),
        inputs = emptyList(),
    )
    /** Static slot match, or the workflow's own dynamic shortcut. */
    fun matches(inst: TriggerInstance, slot: String?, workflowId: String?): Boolean {
        val want = spec.pStr(inst.params, "slot") ?: "dynamic"
        return if (want == "dynamic") workflowId != null && workflowId == inst.workflowId else slot == want
    }
    override fun accepts(params: JsonObject, event: JsonObject): Boolean =
        (spec.pStr(params, "slot") ?: "dynamic").let { it == "dynamic" || it == event.str("slot") }
}

/** Fired by Engine.runManual (UI Run button). */
object ManualTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = TRIGGER_MANUAL, name = "Manual", kind = NodeKind.TRIGGER,
        description = "Runs when you press Run in the editor.",
        params = listOf(multiline("payloadJson", "Payload (JSON object)", templated = false, help = "Optional; becomes the trigger item")),
        inputs = emptyList(),
    )
    override fun toItems(params: JsonObject, event: JsonObject): Items {
        val raw = spec.pStr(params, "payloadJson") ?: return listOf(if (event.isEmpty()) item("at" to now()) else event)
        val parsed = try { JSON.parseToJsonElement(raw) as? JsonObject } catch (e: Exception) { null }
        return listOf(parsed ?: event.ifEmpty { item("at" to now()) })
    }
    private fun JsonObject.ifEmpty(f: () -> JsonObject) = if (isEmpty()) f() else this
}

/**
 * Entry point for logic.run_workflow / Executor.runSub and, since v4 (DESIGN4 §8.1), for the AI: the three params make the workflow a
 * `workflow__<name>` tool (ai.WorkflowTools reads them; accepts/toItems ignore them at run time). Params verbatim from DESIGN4 §8.1.
 */
object CalledByWorkflowTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = TRIGGER_CALLED, name = "Called by Workflow", kind = NodeKind.TRIGGER,
        description = "Starts this workflow when another workflow — or the AI (chat operator / ai.agent) — calls it; the caller's items flow in.",
        params = listOf(
            bool("exposeAsTool", "Expose as an AI tool", false, help = "The chat operator and ai.agent (includeWorkflows) can call this workflow as workflow__<name>"),
            multiline("toolDescription", "Tool description", templated = false, help = "What the workflow does and returns (shown to the model, ≤ 300 chars)", visibleWhen = whenIs("exposeAsTool", "true")),
            rows("inputs", "Tool inputs", listOf(
                text("name", "Field", required = true, templated = false),
                choice("type", "Type", listOf("string", "number", "boolean", "json"), "string"),
                text("description", "Description", templated = false),
                bool("required", "Required", true),
            ), help = "Each row becomes a tool parameter and a field of the single input item. Empty = one free-form 'item' parameter", visibleWhen = whenIs("exposeAsTool", "true")),
        ),
        inputs = emptyList(), mode = ExecMode.LIST,
    )
}

/** Fired by actions.NotificationActionReceiver with the notification's item + {action}. */
object NotificationActionTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = TRIGGER_NOTIFICATION_ACTION, name = "Notification Button", kind = NodeKind.TRIGGER,
        description = "Fires when a button on a Mahout notification (action.notify) is tapped.",
        params = listOf(text("actionLabelRegex", "Button label matches (regex)", templated = false)),
        inputs = emptyList(),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = regexOk(spec.pStr(params, "actionLabelRegex"), event.str("action"))
}

/** Fired by EntryActivity for NDEF/TECH/TAG_DISCOVERED. */
object NfcTrigger : TriggerNode() {
    override val hosting = Hosting.COMPONENT
    override val spec = NodeSpec(
        id = "trigger.nfc", name = "NFC Tag", kind = NodeKind.TRIGGER,
        description = "Fires when an NFC tag is scanned; reads its id and NDEF text/URI records.",
        params = listOf(text("tagIdHex", "Tag id (hex)", templated = false, help = "Only this tag (blank = any)")),
        inputs = emptyList(), gates = listOf(Gate.Feature(PackageManager.FEATURE_NFC, "NFC")), optional = true,
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val want = spec.pStr(params, "tagIdHex") ?: return true
        return want.replace(":", "").equals(event.str("tagId")?.replace(":", ""), ignoreCase = true)
    }
}

