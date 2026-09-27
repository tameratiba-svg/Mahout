package com.mob8n.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.mob8n.Mob8NApp
import com.mob8n.core.EMPTY
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.TRIGGER_NOTIFICATION_ACTION
import com.mob8n.core.add
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** action.notify buttons -> fire the target workflow's trigger.notification_action with [item + {action}]. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val workflowId = intent.getStringExtra(Notifs.EXTRA_WORKFLOW_ID)?.takeIf { it.isNotBlank() } ?: return
        val label = intent.getStringExtra(Notifs.EXTRA_LABEL) ?: ""
        val itemJson = intent.getStringExtra(Notifs.EXTRA_ITEM_JSON)
        val notificationId = intent.getIntExtra(Notifs.EXTRA_NOTIFICATION_ID, 0)
        val tag = intent.getStringExtra(Notifs.EXTRA_TAG)
        if (notificationId != 0) runCatching {
            val nm = NotificationManagerCompat.from(context)
            if (tag != null) nm.cancel(tag, notificationId) else nm.cancel(notificationId)
        }
        val item = runCatching { JSON.parseToJsonElement(itemJson ?: "{}") as? JsonObject }.getOrNull() ?: EMPTY
        val engine = Mob8NApp.of(context).engine
        val pending = goAsync()
        engine.scope.launch {
            try {
                withTimeout(8_000) {
                    val wf = engine.workflow(workflowId) ?: run { Log.w(LOG_TAG, "notification action: unknown workflow $workflowId"); return@withTimeout }
                    val triggers = wf.graph.nodes.filter { it.type == TRIGGER_NOTIFICATION_ACTION && !it.disabled }
                    val node = triggers.firstOrNull { n ->
                        val re = n.params["actionLabelRegex"].asTextOrNull()?.takeIf { it.isNotBlank() } ?: return@firstOrNull true
                        runCatching { Regex(re).containsMatchIn(label) }.getOrDefault(false)
                    } ?: triggers.firstOrNull()
                    if (node == null) { Log.w(LOG_TAG, "notification action: ${wf.name} has no Notification Action trigger"); return@withTimeout }
                    engine.host.fireWorkflow(workflowId, node.id, listOf(item.add("action" to label)))
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "notification action failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
