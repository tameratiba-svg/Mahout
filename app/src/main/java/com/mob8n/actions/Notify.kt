package com.mob8n.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.JSON
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.asText
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.rows
import com.mob8n.core.text
import com.mob8n.core.workflowPicker
import com.mob8n.triggers.NotifListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/** Channels + shared posting helpers for action.notify and the "Tap to open" trampoline used by Intents. */
object Notifs {
    const val CH_DEFAULT = "workflows"
    const val CH_HIGH = "workflows_high"
    const val CH_LOW = "workflows_low"
    const val EXTRA_WORKFLOW_ID = "workflowId"
    const val EXTRA_LABEL = "label"
    const val EXTRA_ITEM_JSON = "itemJson"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_TAG = "tag"
    const val MAX_ITEM_JSON = 8 * 1024
    private val ids = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 1000)

    fun nextId(): Int = ids.incrementAndGet()

    fun channel(ctx: Context, importance: String): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (id, name, level) = when (importance) {
            "high" -> Triple(CH_HIGH, "Workflow alerts", NotificationManager.IMPORTANCE_HIGH)
            "low" -> Triple(CH_LOW, "Workflow updates (quiet)", NotificationManager.IMPORTANCE_LOW)
            else -> Triple(CH_DEFAULT, "Workflow notifications", NotificationManager.IMPORTANCE_DEFAULT)
        }
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, level))
        return id
    }

    fun requirePermission(ctx: Context) {
        if (!Gate.PostNotifications.granted(ctx)) throw NodeException("Needs ${Gate.PostNotifications.label}")
    }

    /** Activity PendingIntent for a tap; MainActivity deep link when [uri] is a mob8n:// url. */
    fun activityIntent(ctx: Context, intent: Intent): PendingIntent =
        // ponytail: time-seeded code so intents that differ only by extras (share text, SMS body, alarm) do not collide across process restarts; collision only if two processes start in the same ms window; upgrade = derive from intent.toUri(URI_INTENT_SCHEME)+extras hash
        PendingIntent.getActivity(ctx, nextId(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun openAppIntent(ctx: Context, runId: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("mob8n://run/$runId")).setPackage(ctx.packageName)

    /** "Tap to open" fallback when an activity cannot be started from the background (Android 10+). Returns the notification id. */
    fun trampoline(ctx: Context, title: String, intent: Intent): Int {
        requirePermission(ctx)
        val id = nextId()
        val n = NotificationCompat.Builder(ctx, channel(ctx, "high"))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText("Tap to open")
            .setAutoCancel(true)
            .setContentIntent(activityIntent(ctx, intent))
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
        return id
    }

    /** Broadcast to NotificationActionReceiver carrying the workflow to fire + the item (capped at 8 KB). */
    fun actionIntent(ctx: Context, workflowId: String, label: String, item: JsonObject, notificationId: Int, tag: String?): PendingIntent {
        var json = JSON.encodeToString(JsonObject.serializer(), item)
        if (json.length > MAX_ITEM_JSON) json = "{}" // ponytail: oversized items dropped (only {action} reaches the trigger); upgrade = stash in node_state and pass a key
        // Data URI + content-derived request code keep each (notification, button) PendingIntent distinct across process restarts (filterEquals ignores extras).
        val i = Intent(ctx, NotificationActionReceiver::class.java)
            .setAction("com.mob8n.NOTIFICATION_ACTION")
            .setData(Uri.parse("mob8n://action/$notificationId/${Uri.encode(label)}"))
            .putExtra(EXTRA_WORKFLOW_ID, workflowId)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_ITEM_JSON, json)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(EXTRA_TAG, tag)
        return PendingIntent.getBroadcast(ctx, (notificationId.toString() + label).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

object NotifyNode : Node() {
    override val spec = NodeSpec(
        id = "action.notify", name = "Notify", kind = NodeKind.ACTION,
        description = "Post a notification with optional big text, tap link and up to 3 action buttons that start other workflows.",
        params = listOf(
            text("title", "Title", required = true),
            text("text", "Text"),
            multiline("bigText", "Big text"),
            choice("importance", "Importance", listOf("default", "high", "low")),
            text("tapUrl", "Tap URL", help = "Opened on tap; blank opens this run in Mahout"),
            rows("actions", "Action buttons", listOf(text("label", "Label", required = true), workflowPicker("workflow", "Workflow")), help = "Max 3; each fires that workflow's Notification Action trigger"),
            text("tag", "Tag", help = "Same tag replaces the previous notification"),
            bool("ongoing", "Ongoing", false),
        ),
        gates = listOf(Gate.PostNotifications), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val title = ctx.req("title")
        val tag = ctx.strOrNull("tag")
        val id = tag?.hashCode() ?: Notifs.nextId()
        val b = NotificationCompat.Builder(a, Notifs.channel(a, ctx.str("importance")))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setAutoCancel(!ctx.bool("ongoing"))
            .setOngoing(ctx.bool("ongoing"))
        ctx.strOrNull("text")?.let { b.setContentText(it) }
        ctx.strOrNull("bigText")?.let { b.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        val tap = ctx.strOrNull("tapUrl")
        val tapIntent = if (tap != null) {
            val u = Uri.parse(tap)
            if (u.scheme.isNullOrBlank()) throw NodeException("Tap URL needs a scheme: $tap")
            Intent(Intent.ACTION_VIEW, u)
        } else Notifs.openAppIntent(a, ctx.runId)
        b.setContentIntent(Notifs.activityIntent(a, tapIntent))
        val actions = ctx.rows("actions")
        if (actions.size > 3) throw NodeException("At most 3 action buttons")
        for (row in actions) {
            val label = row["label"].asText().takeIf { it.isNotBlank() } ?: throw NodeException("Action button label is required")
            val wf = row["workflow"].asText().takeIf { it.isNotBlank() } ?: throw NodeException("Action '$label' needs a workflow")
            b.addAction(0, label, Notifs.actionIntent(a, wf, label, ctx.item, id, tag))
        }
        val nm = NotificationManagerCompat.from(a)
        try {
            if (tag != null) nm.notify(tag, id, b.build()) else nm.notify(id, b.build())
        } catch (e: SecurityException) { throw NodeException("Needs ${Gate.PostNotifications.label}", e) }
        return out(ctx.item.add("notificationId" to id))
    }
}

object CancelNotificationNode : Node() {
    override val spec = NodeSpec(
        id = "action.cancel_notification", name = "Cancel notification", kind = NodeKind.ACTION,
        description = "Dismiss a notification: one Mahout posted (by tag) or any app's (by listener key, needs notification access).",
        params = listOf(text("tag", "Tag (own notification)"), text("key", "Notification key", "{{key}}")),
        gates = listOf(Gate.Advisory(Gate.NotificationListener)), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val tag = ctx.strOrNull("tag")
        val key = ctx.strOrNull("key")
        var cancelled = false
        if (tag != null) { NotificationManagerCompat.from(a).cancel(tag, tag.hashCode()); cancelled = true }
        if (key != null) {
            if (!Gate.NotificationListener.granted(a)) throw NodeException("Needs ${Gate.NotificationListener.label}")
            val l = NotifListener.instance ?: throw NodeException("Notification listener is not connected yet")
            try { l.cancelNotification(key); cancelled = true } catch (e: Exception) { throw NodeException("Could not cancel $key: ${e.message}", e) }
        }
        if (tag == null && key == null) throw NodeException("Cancel notification: give a tag or a key")
        return out(ctx.item.add("cancelled" to cancelled))
    }
}

object ReplyNotificationNode : Node() {
    override val spec = NodeSpec(
        id = "action.reply_notification", name = "Reply to notification", kind = NodeKind.ACTION,
        description = "Send an inline quick reply to a chat notification (first RemoteInput action) identified by its listener key.",
        params = listOf(text("key", "Notification key", "{{key}}", required = true), multiline("text", "Reply text", required = true)),
        gates = listOf(Gate.NotificationListener), optional = true, agentTool = false,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val key = ctx.req("key")
        val reply = ctx.req("text")
        val l = NotifListener.instance ?: throw NodeException("Notification listener is not connected yet")
        val sbn = try { l.activeNotifications?.firstOrNull { it.key == key } } catch (e: Exception) { throw NodeException("Could not read notifications: ${e.message}", e) }
            ?: throw NodeException("No active notification with key $key")
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: throw NodeException("Notification has no quick-reply action")
        val ri: Array<RemoteInput> = action.remoteInputs
        val results = Bundle().apply { for (r in ri) putCharSequence(r.resultKey, reply) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(ri, fill, results)
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val pi = action.actionIntent ?: throw NodeException("Quick-reply action has no intent")
        try { withContext(Dispatchers.Main.immediate) { pi.send(a, 0, fill) } } catch (e: PendingIntent.CanceledException) { throw NodeException("Reply intent was cancelled by the app", e) }
        return out(ctx.item.add("replied" to true))
    }
}
