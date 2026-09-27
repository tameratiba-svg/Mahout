package com.mob8n.triggers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.mob8n.Mob8NApp
import com.mob8n.core.HostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * K1 hostless fallback for trigger.charger `connected` (see ChargerTrigger). The request is gated on setRequiresCharging(true), so it runs
 * when the phone is plugged in: it fires once, then stays suspended until unplugged. Losing the charging constraint makes JobScheduler stop
 * the job and WorkManager re-enqueue the same request, so the next plug-in runs (and fires) it again. Workers are capped at 10 min, so a
 * still-charging timeout re-arms itself behind a short-lived NO_FIRE marker that the follow-up run consumes without firing.
 * ponytail: one suspended job while charging (Doze never starts while plugged in); an unplug+replug inside the seconds between the timeout
 * re-arm and the follow-up start misses one event. Upgrade path = keep HostService alive while a charger workflow is enabled.
 */
class ChargerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wf = inputData.getString("workflowId") ?: return Result.failure()
        val node = inputData.getString("nodeId") ?: return Result.failure()
        val engine = Mob8NApp.of(applicationContext).engine
        try {
            engine.awaitReady()   // cold process: the cache is empty until rearmAll has run
            val host = engine.host
            val inst = host.instancesOf(ChargerTrigger.spec.id).firstOrNull { it.workflowId == wf && it.nodeId == node }
            if (inst == null || ChargerTrigger.spec.pStr(inst.params, "event") == "disconnected") {
                logT("charger worker for $wf/$node ignored"); return Result.success()   // chain ends; schedule() re-arms on the next enable/attach
            }
            val snap = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (snap != null && BatteryLevelTrigger.pluggedOf(snap) == "none") { logT("charger worker for $wf/$node: not plugged, retry"); return Result.retry() }
            // ponytail: WorkManager's charging tracker keys off battery *status* while we key off EXTRA_PLUGGED; when they disagree (signal lag,
            // `dumpsys battery unplug`) re-enqueueing looped at ~25 workers/s on device. retry() keeps one WorkSpec behind a 30 s+ backoff;
            // a replug inside that backoff fires late by at most the backoff. Upgrade path = keep HostService alive while a charger workflow is enabled.
            val scope = "$wf:$node"
            val p = engine.persistence
            val skip = p.getState(scope, NO_FIRE) != null
            if (skip) p.putState(scope, NO_FIRE, null)
            if (!skip && !HostState.alive) {   // with a host alive the runtime receiver already fired
                val event = ChargerTrigger.event(applicationContext, connected = true)
                if (ChargerTrigger.accepts(inst.params, event)) engine.hub.fireWorkflow(wf, node, ChargerTrigger.toItems(inst.params, event), hostedByCaller = true).await()   // F16: this worker is the host
            }
            val unplugged = awaitUnplug(applicationContext, WAIT_MS)
            if (!unplugged) p.putState(scope, NO_FIRE, JsonPrimitive(true), ttlMs = NO_FIRE_TTL_MS)
            ChargerTrigger.schedule(host, inst, ExistingWorkPolicy.APPEND_OR_REPLACE)   // after ourselves; KEEP would drop it while we still run
        } catch (e: CancellationException) { throw e }   // stopped by the unplug: WorkManager re-enqueues this request untouched
        catch (e: Exception) { logW("ChargerWorker $wf/$node", e) }
        return Result.success()
    }

    /** True when ACTION_POWER_DISCONNECTED arrived (or the phone was already unplugged); false on timeout. */
    private suspend fun awaitUnplug(ctx: Context, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var closer: AutoCloseable? = null
            closer = registerSystem(ctx, filterOf(Intent.ACTION_POWER_DISCONNECTED)) { closer?.close(); if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { closer?.close() }
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null && BatteryLevelTrigger.pluggedOf(b) == "none") { closer?.close(); if (cont.isActive) cont.resume(Unit) }
        }
    } != null

    companion object {
        private const val WAIT_MS = 9 * 60_000L          // under WorkManager's 10 min worker ceiling
        private const val NO_FIRE = "chgNoFire"
        private const val NO_FIRE_TTL_MS = 60_000L        // the follow-up run normally starts within seconds
    }
}
