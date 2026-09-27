package com.mob8n.engine

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mob8n.Mob8NApp
import com.mob8n.core.DECISION_TIMEOUT
import com.mob8n.core.DECISION_TIMER
import com.mob8n.core.LOG_TAG
import com.mob8n.core.SuspendKind
import com.mob8n.engine.db.toRecord
import java.util.concurrent.TimeUnit

/**
 * DESIGN §7.1 item 19: prune runs > 500 (logs cascade; never RUNNING/SUSPENDED), expire node_state TTLs, fail RUNNING rows a dead process
 * left behind (K2), deliver overdue suspended runs (TIMER rows resume with DECISION_TIMER, APPROVAL rows time out), re-arm schedules + shortcuts.
 * v3: re-index stale knowledge sources (Knowledge.reindexStale, DESIGN3 §5.7).
 * v4: prune ai_usage older than USAGE_KEEP_MS; expire chat approvals awaiting longer than CHAT_APPROVAL_TTL_MS (the next turn closes the pair).
 */
class HousekeepingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val engine = Mob8NApp.of(applicationContext).engine
        val dao = engine.room.dao
        val now = System.currentTimeMillis()
        try { dao.pruneRuns(MAX_RUNS) } catch (e: Exception) { Log.w(LOG_TAG, "prune: ${e.message}") }
        try { dao.expireState(now) } catch (e: Exception) { Log.w(LOG_TAG, "expire state: ${e.message}") }
        // v3: stale FOLDER/URL/NOTES/PLAYLIST rows (20 h) and changed DOCUMENTs, 5-min budget under the worker's 10-min cap; never throws.
        try { Log.i(LOG_TAG, "knowledge: re-indexed ${engine.knowledge.reindexStale()}") } catch (e: Exception) { Log.w(LOG_TAG, "knowledge: ${e.message}") }
        // K2: nothing legitimately runs longer than the 10 min ceiling; rows older than that whose workflow is idle in this process are orphans.
        try { dao.failStaleRunning(now - STALE_RUN_MS, Engine.PROCESS_DIED, now, engine.hub.busyWorkflowIds()) } catch (e: Exception) { Log.w(LOG_TAG, "stale runs: ${e.message}") }
        try {
            for (s in dao.expiredSuspended(now)) {
                val decision = housekeepingDecision(s.toRecord()?.kind)
                try { engine.resume(s.runId, decision) } catch (e: Exception) { Log.w(LOG_TAG, "$decision ${s.runId}: ${e.message}") }
            }
        } catch (e: Exception) { Log.w(LOG_TAG, "expired suspended: ${e.message}") }
        try { dao.pruneUsage(now - USAGE_KEEP_MS) } catch (e: Exception) { Log.w(LOG_TAG, "prune usage: ${e.message}") }
        try { for (id in engine.expireChatApprovals(CHAT_APPROVAL_TTL_MS)) engine.cancelChatApproval(id) } catch (e: Exception) { Log.w(LOG_TAG, "expire chat approvals: ${e.message}") }
        try { engine.hub.rearmAll() } catch (e: Exception) { Log.w(LOG_TAG, "rearm: ${e.message}") }
        engine.refreshStatus()
        return Result.success()
    }

    companion object {
        const val MAX_RUNS = 500
        const val STALE_RUN_MS = 15 * 60_000L
        const val USAGE_KEEP_MS = 400 * 86_400_000L
        const val CHAT_APPROVAL_TTL_MS = 24 * 3_600_000L
        private const val PERIODIC = "housekeeping"
        private const val ONCE = "housekeeping-now"

        /**
         * F4: an overdue TIMER (logic.delay / wait_until) must be delivered, not timed out — DelayNode.resume fails on anything but DECISION_TIMER.
         * Unparseable rows (null kind) fall back to TIMEOUT so corrupt rows are still cleared. hub.resume is idempotent under the mutex, so a duplicate
         * delivery next to the DelayedRunWorker is harmless.
         */
        fun housekeepingDecision(kind: SuspendKind?): String = if (kind == SuspendKind.TIMER) DECISION_TIMER else DECISION_TIMEOUT

        /** Periodic 12 h (KEEP) + one immediate pass on every start(). */
        fun enqueue(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HousekeepingWorker>(12, TimeUnit.HOURS).build())
            wm.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<HousekeepingWorker>().build())
        }
    }
}
