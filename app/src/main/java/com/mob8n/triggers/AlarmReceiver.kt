package com.mob8n.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mob8n.Mob8NApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Exact trigger.schedule fires (AlarmManager.setAndAllowWhileIdle): fire the one workflow, then re-arm (or disable after `once`). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleTrigger.ACTION_ALARM) return
        val wf = intent.getStringExtra("workflowId") ?: return
        val node = intent.getStringExtra("nodeId") ?: return
        val scheduledFor = intent.getLongExtra("scheduledFor", now())
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeout(9_000) {
                    val engine = Mob8NApp.of(ctx).engine
                    engine.awaitReady()   // cold process: instancesOf() is empty until rearmAll has run
                    val host = engine.host
                    val inst = host.instancesOf(ScheduleTrigger.spec.id).firstOrNull { it.workflowId == wf && it.nodeId == node }
                    if (inst == null) { logT("alarm for disabled $wf/$node ignored"); return@withTimeout }
                    // F16: await inside the goAsync cap; a hostless run that needs a host is deferred to DelayedRunWorker by the hub (F19)
                    val run = host.fireWorkflow(wf, node, listOf(ScheduleTrigger.fireItem(scheduledFor)))
                    ScheduleTrigger.afterFire(ctx, inst)
                    withTimeoutOrNull(7_000) { run.await() }
                }
            } catch (e: Exception) { logW("AlarmReceiver $wf/$node", e) }
            finally { pending.finish() }
        }
    }
}
