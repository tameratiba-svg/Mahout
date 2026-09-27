package com.mob8n.triggers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mob8n.Mob8NApp

/** trigger.schedule (daily / weekly / interval / once, non-exact). Fires ONE workflow, then re-arms via ScheduleTrigger.afterFire. */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wf = inputData.getString("workflowId") ?: return Result.failure()
        val node = inputData.getString("nodeId") ?: return Result.failure()
        val scheduledFor = inputData.getLong("scheduledFor", 0L).takeIf { it > 0 } ?: now()
        try {
            val engine = Mob8NApp.of(applicationContext).engine
            engine.awaitReady()   // cold process: instancesOf() is empty until rearmAll has run, and this fire must not be dropped as "ignored"
            val inst = engine.host.instancesOf(ScheduleTrigger.spec.id).firstOrNull { it.workflowId == wf && it.nodeId == node }
            if (inst == null) { logT("schedule worker for disabled $wf/$node ignored"); return Result.success() }
            // F16/F19: this worker is the process hold (hostedByCaller); await before afterFire, whose REPLACE would cancel us mid-run
            engine.hub.fireWorkflow(wf, node, listOf(ScheduleTrigger.fireItem(scheduledFor)), hostedByCaller = true).await()
            ScheduleTrigger.afterFire(applicationContext, inst)
        } catch (e: Exception) { logW("ScheduleWorker $wf/$node", e) }
        return Result.success()   // never retry a schedule: the next occurrence is already armed
    }
}
