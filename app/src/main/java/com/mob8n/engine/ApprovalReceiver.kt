package com.mob8n.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mob8n.Mob8NApp
import com.mob8n.core.DECISION_APPROVE
import com.mob8n.core.LOG_TAG
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Approval-notification buttons -> engine.resume(runId, decision). goAsync with a 9 s cap; the hub starts HostService when the graph needsHost.
 * v4 (DESIGN4 V3): a chat approval carries EXTRA_CONVERSATION_ID instead and goes to Engine.chatDecision (ChatRunner::decide), which only completes a
 * Deferred or launches a Job in engine.scope — no manifest change, no second receiver.
 */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val decision = intent.getStringExtra(Notifs.EXTRA_DECISION)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return
        val engine = Mob8NApp.of(context).engine
        intent.getStringExtra(Notifs.EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() && it.length <= 64 }?.let { conversationId ->
            try { engine.chatDecision?.invoke(conversationId, decision == DECISION_APPROVE) ?: Log.w(LOG_TAG, "chat decision for $conversationId: no handler") }
            catch (e: Exception) { Log.w(LOG_TAG, "chat decision $conversationId/$decision: ${e.message}") }
            return
        }
        val runId = intent.getStringExtra(Notifs.EXTRA_RUN_ID)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return
        val pending = goAsync()
        engine.scope.launch {
            try { withTimeoutOrNull(RECEIVER_CAP_MS) { engine.resume(runId, decision) } }   // the run itself continues in engine.scope
            catch (e: Exception) { Log.w(LOG_TAG, "approval $runId/$decision: ${e.message}") }
            finally { pending.finish() }
        }
    }

    companion object { const val RECEIVER_CAP_MS = 9_000L }
}
