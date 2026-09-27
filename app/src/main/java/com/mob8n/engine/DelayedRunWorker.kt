package com.mob8n.engine

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mob8n.Mob8NApp
import com.mob8n.core.DECISION_TIMEOUT
import com.mob8n.core.EMPTY
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.Persistence
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Durable timers: TIMER resumes, DECISION_TIMEOUT delivery at expiresAt, and action.schedule_run payload runs.
 * Awaits run completion so WorkManager keeps the process alive for the run (10 min worker ceiling == run ceiling).
 */
class DelayedRunWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val engine = Mob8NApp.of(applicationContext).engine
        try {
            when (inputData.getString(K_KIND)) {
                KIND_RESUME -> {
                    val runId = inputData.getString(K_RUN_ID) ?: return Result.failure()
                    val decision = inputData.getString(K_DECISION) ?: return Result.failure()
                    engine.resume(runId, decision)   // no-op when the run was already resumed
                }
                KIND_RUN -> {
                    val wfId = inputData.getString(K_WORKFLOW) ?: return Result.failure()
                    val items = loadItems(engine.persistence)
                    // Explicit invocation (schedule_run): `enabled` is not checked, like run_workflow/runSub; disabling cancels pending work instead (cancelScheduledRuns).
                    val wf = engine.persistence.loadWorkflow(wfId) ?: return Result.success()
                    // F19: a hostless receiver fire names its trigger node so multi-trigger graphs run the right branch; schedule_run uses the entry trigger
                    val trig = inputData.getString(K_NODE)?.let { id -> wf.graph.node(id)?.takeIf { !it.disabled } } ?: engine.hub.entryTrigger(wf) ?: return Result.success()
                    engine.hub.launchRun(wf, trig, items, hostedByCaller = true).await()   // this worker is the process hold
                }
                else -> return Result.failure()
            }
        } catch (e: CancellationException) { throw e   // F2: a stopped worker must not report success
        } catch (e: Exception) { Log.w(LOG_TAG, "DelayedRunWorker: ${e.message}") }
        return Result.success()   // failures are recorded as run rows; never retry a run
    }

    private suspend fun loadItems(p: Persistence): Items {
        val inline = inputData.getString(K_ITEMS)
        val key = inputData.getString(K_STATE_KEY)
        val raw = when {
            inline != null -> runCatching { JSON.parseToJsonElement(inline) }.getOrNull()
            key != null -> p.getState(STATE_SCOPE, key).also { p.putState(STATE_SCOPE, key, null) }
            else -> null
        }
        if (key != null && raw == null) Log.w(LOG_TAG, "schedule_run: parked payload $key missing (expired?); running with an empty item")
        return (raw as? JsonArray)?.filterIsInstance<JsonObject>()?.ifEmpty { listOf(EMPTY) } ?: listOf(EMPTY)
    }

    companion object {
        private const val K_KIND = "kind"; private const val K_RUN_ID = "runId"; private const val K_DECISION = "decision"
        private const val K_WORKFLOW = "workflowId"; private const val K_ITEMS = "items"; private const val K_STATE_KEY = "stateKey"; private const val K_NODE = "nodeId"
        private const val KIND_RESUME = "resume"; private const val KIND_RUN = "run"
        private const val STATE_SCOPE = "engine:scheduled"
        private const val MAX_INLINE_BYTES = 9 * 1024
        /** F8: the parked payload outlives the timer by this much (ScheduleRunNode allows delays up to 30 days; a fixed 7-day TTL lost payloads). */
        const val PAYLOAD_SLACK_MS = 24 * 3600_000L

        private fun name(runId: String, decision: String) = if (decision == DECISION_TIMEOUT) "expire:$runId" else "resume:$runId"
        private fun schedTag(workflowId: String) = "sched:$workflowId"

        // ponytail: names-only helper so the self-cancel rule is unit-testable without WorkManager; upgrade path = WorkManagerTestInitHelper Robolectric test
        /** Unique names to cancel when `delivering` resumes the run: every pending delivery except the one carrying this decision. */
        internal fun namesToCancel(runId: String, delivering: String): List<String> {
            val keep = name(runId, delivering)
            return listOf("expire:$runId", "resume:$runId").filter { it != keep }
        }

        // ponytail: ttl derived from delay + 1 day slack; upgrade path = Doze/alarm drift-aware expiry if WorkManager delays > 1 day are observed.
        internal fun payloadTtlMs(delayMs: Long): Long = delayMs.coerceAtLeast(0) + PAYLOAD_SLACK_MS

        /** Unique per (run, timer|expire); REPLACE keeps exactly one pending delivery each. */
        fun enqueueResume(ctx: Context, runId: String, decision: String, atMs: Long) {
            val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<DelayedRunWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(K_KIND to KIND_RESUME, K_RUN_ID to runId, K_DECISION to decision))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(name(runId, decision), ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * Called when a run resumes: cancel the OTHER pending delivery (timeout vs timer); never the unique work that is delivering this decision,
         * or WorkManager would stop the worker awaiting the run and drop the process hold (F2).
         */
        fun cancelResume(ctx: Context, runId: String, delivering: String) {
            val wm = WorkManager.getInstance(ctx)
            for (n in namesToCancel(runId, delivering)) wm.cancelUniqueWork(n)
        }

        /** Engine.delete (F9): the run is closed, so both pending deliveries go. */
        fun cancelAllResumes(ctx: Context, runId: String) { val wm = WorkManager.getInstance(ctx); wm.cancelUniqueWork("expire:$runId"); wm.cancelUniqueWork("resume:$runId") }

        /** F12: Engine.setEnabled(false) drops this workflow's pending schedule_run deliveries (tagged per target workflow). */
        fun cancelScheduledRuns(ctx: Context, workflowId: String) { WorkManager.getInstance(ctx).cancelAllWorkByTag(schedTag(workflowId)) }

        /** action.schedule_run: payload <= 9 KB inline in Data, else parked in node_state (TTL = delay + 1 day) and referenced by key. */
        suspend fun enqueueRun(ctx: Context, p: Persistence, workflowId: String, delayMs: Long, items: Items, nodeId: String? = null) {
            val json = JSON.encodeToString(JsonArray.serializer(), JsonArray(items))
            val data = if (json.toByteArray().size <= MAX_INLINE_BYTES) workDataOf(K_KIND to KIND_RUN, K_WORKFLOW to workflowId, K_ITEMS to json, K_NODE to nodeId)
            else {
                val key = UUID.randomUUID().toString()
                p.putState(STATE_SCOPE, key, JsonArray(items), payloadTtlMs(delayMs))
                workDataOf(K_KIND to KIND_RUN, K_WORKFLOW to workflowId, K_STATE_KEY to key, K_NODE to nodeId)
            }
            val req = OneTimeWorkRequestBuilder<DelayedRunWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(schedTag(workflowId))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
