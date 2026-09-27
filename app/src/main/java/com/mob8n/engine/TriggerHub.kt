package com.mob8n.engine

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mob8n.R
import com.mob8n.core.DECISION_TIMEOUT
import com.mob8n.core.DECISION_TIMER
import com.mob8n.core.Executor
import com.mob8n.core.Gate
import com.mob8n.core.Graph
import com.mob8n.core.HostState
import com.mob8n.core.Hooks
import com.mob8n.core.Hosting
import com.mob8n.core.Items
import com.mob8n.core.LOG_TAG
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.RunStatus
import com.mob8n.core.SuspendKind
import com.mob8n.core.TRIGGER_CALLED
import com.mob8n.core.TRIGGER_MANUAL
import com.mob8n.core.TriggerHost
import com.mob8n.core.TriggerInstance
import com.mob8n.core.TriggerNode
import com.mob8n.core.Workflow
import com.mob8n.core.asTextOrNull
import com.mob8n.core.matchInstances
import com.mob8n.engine.db.toRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ponytail: spec-id allow-lists instead of a core `hosting` flag (engine may not import the triggers lane, DESIGN §2); upgrade = TriggerNode.durable/attaches booleans.
/** Specs whose schedule()/unschedule()/rearm() enqueue durable work: WORK_MANAGER plus trigger.charger's ChargerWorker fallback (K1). */
internal fun TriggerNode.schedulesDurably() = hosting == Hosting.WORK_MANAGER || spec.id == "trigger.charger"
/** Specs with a live-host attach(): runtime receivers, host-attached listeners, and trigger.new_photo's ContentObserver fast path (F23). */
internal fun TriggerNode.attachesInHost() = hosting == Hosting.RUNTIME_RECEIVER || hosting == Hosting.HOST_ATTACHED || spec.id == "trigger.new_photo"

/**
 * Engine-internal: TriggerHost lambdas, run scheduling (Semaphore(4) + per-workflow Mutex + 10 min ceiling), host lifecycle
 * (needsHost / ensureHostRunning), runtime trigger attachments (diffed), WorkManager schedules, dynamic shortcuts, Hooks.
 */
internal class TriggerHub(private val engine: Engine) {
    private val app = engine.app
    private val catalog = engine.catalog
    private val persistence get() = engine.room
    private val sem = Semaphore(4)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val active = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled: List<Workflow> = emptyList()
    // ponytail: awaits the first cache fill only; upgrade = read enabled instances from Room per call if the cache ever goes stale across processes
    private val primed = CompletableDeferred<Unit>()
    /** DESIGN4P P9: every guarded() execution's Job -> wf.id, so cancelAllRuns can cancel + join them. */
    private val runJobs = ConcurrentHashMap<Job, String>()

    val activeRuns: Int get() = active.get()
    /** Workflows whose per-workflow mutex is held right now (a run or resume is executing in this process). */
    fun busyWorkflowIds(): List<String> = mutexes.filter { it.value.isLocked }.keys.toList()

    private val hooks = Hooks(
        onSuspend = { s ->
            when (s.kind) {
                SuspendKind.APPROVAL -> { Notifs.postApproval(app, s); DelayedRunWorker.enqueueResume(app, s.runId, DECISION_TIMEOUT, s.expiresAt) }
                SuspendKind.TIMER -> DelayedRunWorker.enqueueResume(app, s.runId, DECISION_TIMER, s.resumeAtMs ?: s.expiresAt)
            }
        },
        scheduleRun = { wfId, delayMs, items -> DelayedRunWorker.enqueueRun(app, persistence, wfId, delayMs, items) },
        setWorkflowEnabled = { wfId, on -> engine.setEnabled(wfId, on) },
        fireWorkflow = { wfId, items, depth -> fireWorkflowAsync(wfId, items, depth) },   // F7: async run_workflow carries depth; Executor.start refuses > maxDepth
    )

    val executor = Executor(
        catalog, engine.room, hooks, app, ZoneId.systemDefault(), System::currentTimeMillis,
        logger = { Log.d(LOG_TAG, it) },
    )

    val host = TriggerHost(
        android = app,
        fire = { specId, event -> fire(specId, event) },
        fireWorkflow = { wfId, nodeId, items -> fireWorkflow(wfId, nodeId, items) },
        instancesOf = { specId -> instancesOf(specId) },
    )

    // ------------------------------------------------------------------ cache

    /** The finally guarantees cold-start callers never hang on a Room failure (complete() is idempotent). */
    suspend fun refreshCache() { try { enabled = persistence.enabledWorkflows() } finally { primed.complete(Unit) } }

    /** F14: suspends until the first refreshCache() (Engine.start -> rearmAll) has filled `enabled`. */
    suspend fun awaitReady() { primed.await() }

    fun instancesOf(specId: String): List<TriggerInstance> =
        enabled.flatMap { wf -> wf.graph.nodes.filter { it.type == specId && !it.disabled }.map { TriggerInstance(wf.id, it.id, it.params) } }

    private fun isRuntime(n: NodeInstance) = catalog.trigger(n.type)?.hosting.let { it == Hosting.RUNTIME_RECEIVER || it == Hosting.HOST_ATTACHED }
    private fun durable(n: NodeInstance) = catalog.trigger(n.type)?.schedulesDurably() == true
    fun runtimeTriggersInUse(): Boolean = enabled.any { wf -> wf.graph.nodes.any { !it.disabled && isRuntime(it) } }

    /** DESIGN §7.1 item 18. */
    fun needsHost(graph: Graph): Boolean = graph.nodes.any { n ->
        if (n.disabled) return@any false
        val spec = catalog.spec(n.type) ?: return@any false
        spec.kind == NodeKind.AI || (n.timeoutMs ?: spec.timeoutMs) > 8_000 ||
            n.type in setOf("logic.delay", "logic.wait_until", "logic.wait_approval")
    }

    // ------------------------------------------------------------------ firing

    /** Raw event -> every enabled instance of the spec; each accepted workflow runs in its own coroutine. */
    fun fire(specId: String, event: JsonObject): CompletableDeferred<List<String>> {
        val result = CompletableDeferred<List<String>>()
        engine.scope.launch {
            try {
                primed.await()
                val trig = catalog.trigger(specId)
                if (trig == null) { result.complete(emptyList()); return@launch }
                val runs = ArrayList<Deferred<Executor.Outcome?>>()
                for ((wf, n, items) in trig.matchInstances(enabled, event) { Log.w(LOG_TAG, it) }) {   // F54: the one fan-out matcher
                    val kept = triaged(wf, n, items) ?: continue   // v5 (DESIGN5 §6.1): System 1 triage before any run row exists
                    runs += launchRun(wf, n, kept)
                }
                result.complete(runs.mapNotNull { it.await()?.run?.runId })
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                result.completeExceptionally(e)
            } finally { if (!result.isCompleted) result.complete(emptyList()) }   // P9: a cancelled job never leaves its Deferred pending
        }
        return result
    }

    /**
     * One workflow from a specific trigger node, no accepts() (tile, shortcut, notification action, share chooser).
     * F20: this is an event path through one of the workflow's own triggers, so `enabled` gates it (raw ids from PendingIntent extras may be stale).
     * hostedByCaller (F16): a WorkManager worker that awaits the Deferred is itself the process hold; see launchRun.
     */
    fun fireWorkflow(workflowId: String, nodeId: String, items: Items, hostedByCaller: Boolean = false): CompletableDeferred<Executor.Outcome?> {
        val d = CompletableDeferred<Executor.Outcome?>()
        engine.scope.launch {
            try {
                primed.await()
                val wf = persistence.loadWorkflow(workflowId)?.takeIf { it.enabled }
                if (wf == null) { Log.w(LOG_TAG, "fireWorkflow: $workflowId missing or disabled"); d.complete(null); return@launch }
                val node = wf.graph.node(nodeId)?.takeIf { !it.disabled && catalog.spec(it.type)?.kind == NodeKind.TRIGGER }
                if (node == null) { Log.w(LOG_TAG, "fireWorkflow: no trigger $nodeId in $workflowId"); d.complete(null); return@launch }
                val kept = triaged(wf, node, items) ?: run { d.complete(null); return@launch }   // v5: share chooser, tile, notification action
                d.complete(launchRun(wf, node, kept, hostedByCaller = hostedByCaller).await())
            } catch (e: Throwable) { if (e is CancellationException) throw e; d.completeExceptionally(e) }
            finally { if (!d.isCompleted) d.complete(null) }
        }
        return d
    }

    /**
     * v5 (DESIGN5 §6.1): event paths only (fire / fireWorkflow). launchRun, runManual, runCalled, fireWorkflowAsync and DelayedRunWorker (which
     * re-enters through launchRun) are never triaged, so a deferred run is not triaged twice. null = the event was dropped.
     */
    private suspend fun triaged(wf: Workflow, n: NodeInstance, items: Items): Items? =
        TriageHook.apply(engine.preFilter, wf, n, items) { Log.i(LOG_TAG, it) }

    /** Hooks.fireWorkflow / DelayedRunWorker: pick the entry trigger (called > manual > any). */
    fun entryTrigger(wf: Workflow): NodeInstance? =
        wf.graph.nodes.firstOrNull { it.type == TRIGGER_CALLED && !it.disabled }
            ?: wf.graph.nodes.firstOrNull { it.type == TRIGGER_MANUAL && !it.disabled }
            ?: wf.graph.nodes.firstOrNull { !it.disabled && catalog.spec(it.type)?.kind == NodeKind.TRIGGER }

    /**
     * logic.run_workflow waitForResult=false. Explicit invocation: like Executor.runSub it ignores `enabled` (disabled called-only helpers stay callable).
     * F7: depth = caller depth + 1 once Hooks.fireWorkflow carries it; Executor.start refuses depth > maxDepth so A->B->A async chains stop.
     */
    private fun fireWorkflowAsync(workflowId: String, items: Items, depth: Int = 0) {
        engine.scope.launch {
            primed.await()
            val wf = persistence.loadWorkflow(workflowId) ?: run { Log.w(LOG_TAG, "fireWorkflow: $workflowId missing"); return@launch }
            val trig = entryTrigger(wf) ?: return@launch
            launchRun(wf, trig, items, depth)
        }
    }

    /**
     * The one place runs start: host check -> per-workflow Mutex -> Semaphore(4) -> 10 min ceiling. Never throws to callers.
     * hostedByCaller (F16): the caller is a WorkManager worker that awaits this Deferred, so the process is held for <= 10 min (== RUN_CEILING_MS)
     * even when HostService cannot be started from a job; the host start stays best-effort like resume().
     * F19: when a receiver/tile fire needs a host, none is alive and the FGS cannot be started from the background, the run is handed to
     * DelayedRunWorker (delay 0, same trigger node) instead of failing with "background host unavailable": the worker is the host.
     * // ponytail: worker holds the process for <= 10 min (== RUN_CEILING_MS); upgrade path: setExpedited for AI graphs
     */
    fun launchRun(wf: Workflow, trigger: NodeInstance, items: Items, depth: Int = 0, hostedByCaller: Boolean = false): Deferred<Executor.Outcome?> {
        val d = CompletableDeferred<Executor.Outcome?>()
        engine.scope.launch {
            try {
                if (needsHost(wf.graph) && !HostState.alive && !tryStartHost("run ${wf.name}") && !hostedByCaller) {
                    Log.i(LOG_TAG, "run ${wf.name}: no host available, deferring to DelayedRunWorker")
                    DelayedRunWorker.enqueueRun(app, persistence, wf.id, 0, items, nodeId = trigger.id)
                    d.complete(null); return@launch
                }
                d.complete(guarded(wf) { executor.start(wf, trigger, items, null, depth) })
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(LOG_TAG, "run ${wf.name}: ${e.message}", e); d.complete(null)
            } finally { if (!d.isCompleted) d.complete(null) }   // P9: DelayedRunWorker.doWork().await() / runManual return after the kill switch
        }
        return d
    }

    /**
     * Workflows-as-tools from the chat operator (DESIGN4 §8.3): one run at the given trigger.called node, depth 0, awaited. The run row is written as
     * usual (Dashboard counts it; triggerType = trigger.called). SUSPENDED comes back as a result; null Outcome (host refused / deferred) = null runId.
     */
    suspend fun runCalled(wf: Workflow, trigger: NodeInstance, items: Items): Engine.CalledResult {
        val o = launchRun(wf, trigger, items, depth = 0).await()
            ?: return Engine.CalledResult(null, null, emptyList(), "${wf.name} could not start now (no background host); it was queued")
        return Engine.CalledResult(o.run.runId, o.run.status, o.leafItems, o.run.error)
    }

    fun resume(runId: String, decision: String): Deferred<Executor.Outcome?> {
        val d = CompletableDeferred<Executor.Outcome?>()
        engine.scope.launch {
            try {
                val s = persistence.loadSuspended(runId)
                if (s == null) { d.complete(null); return@launch }
                if (decision !in s.choices && decision != DECISION_TIMER && decision != DECISION_TIMEOUT) {
                    Log.w(LOG_TAG, "resume $runId: unknown decision '$decision'"); d.complete(null); return@launch
                }
                DelayedRunWorker.cancelResume(app, runId, decision)   // F2: only the sibling timer, never the worker delivering this decision
                val wf = persistence.loadWorkflow(s.workflowId)
                if (wf != null && needsHost(wf.graph) && !HostState.alive) tryStartHost("resume ${wf.name}")   // best effort; the run row already exists
                d.complete(if (wf == null) executor.resume(runId, decision) else guarded(wf) { executor.resume(runId, decision) })
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(LOG_TAG, "resume $runId: ${e.message}", e); d.complete(null)
            } finally { if (!d.isCompleted) d.complete(null) }
        }
        return d
    }

    private suspend fun guarded(wf: Workflow, block: suspend () -> Executor.Outcome?): Executor.Outcome? =
        mutexes.computeIfAbsent(wf.id) { Mutex() }.withLock {
            sem.withPermit {
                active.incrementAndGet()
                val job = coroutineContext.job
                runJobs[job] = wf.id
                try { withTimeout(RUN_CEILING_MS) { block() } }
                catch (e: TimeoutCancellationException) {
                    // ponytail: the executor cannot write its own terminal row after cancellation; the per-workflow mutex means exactly one RUNNING row (plus its
                    // sync sub-workflow rows, F5) belongs to us. failRunning also repairs workflows.lastRunStatus. Upgrade = NonCancellable finish() in Executor.drive.
                    try { persistence.dao.failRunning(wf.id, "run exceeded ${RUN_CEILING_MS / 60_000} min", System.currentTimeMillis()) } catch (_: Exception) {}
                    null
                } catch (e: CancellationException) {
                    // ponytail: runs cancelled by the kill switch get their FAILED row from TriggerHub.guarded (+ cancelAllRuns after join); upgrade = NonCancellable finish() in Executor.drive.
                    // Any non-timeout cancellation is the kill switch (child JobCancellationExceptions do not carry the parent's message, so never match on text).
                    try { withContext(NonCancellable) { persistence.dao.failRunning(wf.id, STOPPED_BY_USER, System.currentTimeMillis()) } } catch (_: Exception) {}
                    throw e
                } finally { runJobs.remove(job); active.decrementAndGet() }
            }
        }

    /**
     * Kill switch (DESIGN4P P9): cancel + join every executing run, write FAILED "stopped by user" for each (idempotent: failRunning matches RUNNING rows only),
     * then close every SUSPENDED run the same way (timers + approval notifications cancelled). Scheduled future runs (sched: tags) are not touched. Never throws.
     */
    suspend fun cancelAllRuns(): Int {
        var n = 0
        val now = System.currentTimeMillis()
        val snapshot = runJobs.toMap()
        snapshot.keys.forEach { it.cancel(CancellationException(STOPPED_BY_USER)) }
        snapshot.keys.forEach { runCatching { it.join() } }
        snapshot.values.toSet().forEach { wfId -> runCatching { persistence.dao.failRunning(wfId, STOPPED_BY_USER, now) } }
        n += snapshot.size
        val suspended = runCatching { persistence.dao.allSuspended().mapNotNull { it.toRecord() } }.getOrDefault(emptyList())
        for (s in suspended) {
            runCatching {
                Notifs.cancelApproval(app, s.runId); DelayedRunWorker.cancelAllResumes(app, s.runId)
                persistence.dao.deleteSuspended(s.runId)
                persistence.loadRun(s.runId)?.let { persistence.saveRun(it.copy(status = RunStatus.FAILED, endedAt = now, failedNodeId = s.nodeId, error = STOPPED_BY_USER)) }
                n++
            }.onFailure { Log.w(LOG_TAG, "cancelAllRuns: suspended ${s.runId}: ${it.message}") }
        }
        Log.i(LOG_TAG, "cancelAllRuns: $n (${snapshot.size} running, ${suspended.size} suspended)")
        return n
    }

    // ------------------------------------------------------------------ hosts

    /** startForegroundService(HostService); false when Android refused (background start limits). */
    fun tryStartHost(reason: String): Boolean {
        if (HostState.serviceRunning) return true
        return try {
            ContextCompat.startForegroundService(app, Intent(app, HostService::class.java))
            true
        } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException (31+) and friends
            Log.w(LOG_TAG, "cannot start host ($reason): ${e.message}"); engine.refreshStatus(); false
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "cannot start host ($reason): ${e.message}"); engine.refreshStatus(); false
        }
    }

    // attachments: specId -> (instances attached, closer). Alive while at least one host handle is open.
    private val attached = HashMap<String, Pair<List<TriggerInstance>, AutoCloseable?>>()
    private val hostHandles = HashSet<Any>()

    /** Called by NotifListener.onListenerConnected / HostService.onCreate on the main thread. Idempotent; diffed on toggles. */
    fun attachRuntimeTriggers(): AutoCloseable {
        val handle = Any()
        onMain { hostHandles += handle; syncAttachments(); engine.refreshStatus() }   // F21: HostStatus.listenerConnected follows the listener bind/unbind
        return AutoCloseable { onMain { hostHandles -= handle; syncAttachments(); engine.refreshStatus() } }
    }

    suspend fun refreshAttachments() = withContext(Dispatchers.Main.immediate) { syncAttachments() }

    /** Main thread only. Desired = enabled runtime instances per spec (or nothing when no host handle is open). */
    private fun syncAttachments() {
        val desired: Map<String, List<TriggerInstance>> =
            if (hostHandles.isEmpty()) emptyMap()
            else catalog.triggers.filter { it.attachesInHost() }
                .associate { it.spec.id to instancesOf(it.spec.id) }.filterValues { it.isNotEmpty() }
        for (specId in (attached.keys + desired.keys).toSet()) {
            val want = desired[specId].orEmpty()
            val have = attached[specId]
            if (have != null && have.first == want) continue
            have?.second?.let { c -> try { c.close() } catch (e: Exception) { Log.w(LOG_TAG, "detach $specId: ${e.message}") } }
            attached.remove(specId)
            if (want.isEmpty()) continue
            val trig = catalog.trigger(specId) ?: continue
            val closer = try { trig.attach(host, want) } catch (e: Exception) { Log.w(LOG_TAG, "attach $specId: ${e.message}"); null }
            // F22: a failed/empty attach is not cached, so the next sync (rearmAll, workflow save, host reconnect) retries it.
            // ponytail: null also means 'nothing to attach' (battery system_low-only, date-less DateTrigger); re-calling those each sync is a cheap no-op. Upgrade = attach() returns a sealed Attached/None/Failed.
            if (closer != null) attached[specId] = want to closer else Log.d(LOG_TAG, "attach $specId: not attached; will retry on next sync")
        }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    // ------------------------------------------------------------------ enable / save / delete (DESIGN §7.3)

    suspend fun onWorkflowChanged(old: Workflow?, new: Workflow?) {
        refreshCache()
        val oldTrigs = if (old?.enabled == true) old.graph.nodes.filter { !it.disabled && durable(it) } else emptyList()
        val newTrigs = if (new?.enabled == true) new.graph.nodes.filter { !it.disabled && durable(it) } else emptyList()
        for (n in oldTrigs) if (newTrigs.none { it.id == n.id }) unschedule(old!!, n)
        for (n in newTrigs) schedule(new!!, n)
        refreshAttachments()
        if (new?.enabled == true && !HostState.alive && new.graph.nodes.any { !it.disabled && isRuntime(it) }) tryStartHost("runtime triggers of ${new.name}")
        refreshShortcuts()
    }

    private fun schedule(wf: Workflow, n: NodeInstance) {
        try { catalog.trigger(n.type)?.schedule(host, TriggerInstance(wf.id, n.id, n.params)) }
        catch (e: Exception) { Log.w(LOG_TAG, "schedule ${wf.name}/${n.name}: ${e.message}") }
    }
    /** F15: rearm() is idempotent (ScheduleTrigger uses KEEP so the request that woke the process survives; default = schedule()). */
    private fun rearm(wf: Workflow, n: NodeInstance) {
        try { catalog.trigger(n.type)?.rearm(host, TriggerInstance(wf.id, n.id, n.params)) }
        catch (e: Exception) { Log.w(LOG_TAG, "rearm ${wf.name}/${n.name}: ${e.message}") }
    }
    private fun unschedule(wf: Workflow, n: NodeInstance) {
        try { catalog.trigger(n.type)?.unschedule(host, TriggerInstance(wf.id, n.id, n.params)) }
        catch (e: Exception) { Log.w(LOG_TAG, "unschedule ${wf.name}/${n.name}: ${e.message}") }
    }

    /** start() + HousekeepingWorker: cache, WorkManager schedules for every enabled workflow, attachments, shortcuts. */
    suspend fun rearmAll() {
        refreshCache()
        for (wf in enabled) for (n in wf.graph.nodes) if (!n.disabled && durable(n)) rearm(wf, n)
        refreshAttachments()
        if (runtimeTriggersInUse() && !HostState.listenerConnected && Gate.NotificationListener.granted(app)) {
            // ponytail: a granted-but-unbound listener is only recovered by requestRebind; if the OS still does not rebind, the next 12 h housekeeping pass retries. Upgrade path: FGS fallback after a grace period.
            runCatching { NotificationListenerService.requestRebind(ComponentName(app, "com.mob8n.triggers.NotifListener")) }
                .onFailure { Log.w(LOG_TAG, "requestRebind: ${it.message}") }
        }
        if (runtimeTriggersInUse() && !HostState.alive && !Gate.NotificationListener.granted(app)) tryStartHost("runtime triggers")
        refreshShortcuts()
    }

    /** Up to 4 dynamic shortcuts (newest enabled workflows) -> EntryActivity with a workflowId extra (triggers lane maps it). */
    private fun refreshShortcuts() {
        try {
            val max = minOf(MAX_SHORTCUTS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(app) - STATIC_SHORTCUTS).coerceAtLeast(0)
            val list = enabled.sortedByDescending { it.updatedAt }.take(max).map { wf ->
                ShortcutInfoCompat.Builder(app, "wf:${wf.id}")
                    .setShortLabel(wf.name.take(10).ifBlank { "Workflow" })
                    .setLongLabel(wf.name.take(25).ifBlank { "Workflow" })
                    .setIcon(IconCompat.createWithResource(app, R.drawable.ic_tile))
                    .setIntent(Intent("com.mob8n.SHORTCUT").setClassName(app, "com.mob8n.triggers.EntryActivity").putExtra("workflowId", wf.id))
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(app, list)
        } catch (e: Exception) { Log.w(LOG_TAG, "shortcuts: ${e.message}") }
    }

    companion object {
        const val RUN_CEILING_MS = 10 * 60_000L
        /** engine cannot import ai (Permissions.STOPPED_BY_USER has the same text). */
        const val STOPPED_BY_USER = "stopped by user"
        const val MAX_SHORTCUTS = 4
        const val STATIC_SHORTCUTS = 4   // res/xml/shortcuts.xml slot1..slot4
        /** v5: the engine knows the trigger param KEY only, never ai types (triggers/TriageParams.ENGINE has the same text). */
        const val TRIAGE_KEY = "triageEngine"
        const val TRIAGE_CEILING_MS = 10_000L
    }
}

/**
 * The pure body of TriggerHub.triaged (DESIGN5 §6.1), JVM-tested. null preFilter or triageEngine absent/"off" -> items with the hook NOT invoked
 * (zero cost); hook result empty -> null (drop, one log line); hook throws or exceeds the ceiling -> items (fail open; ai.Triage applies
 * triageOnError itself). Only the caller's own cancellation propagates.
 * // ponytail: triage fails open by default (triageOnError); dropped events leave a logcat line + ai_usage rows, no run row
 */
internal object TriageHook {
    suspend fun apply(
        preFilter: (suspend (Workflow, NodeInstance, Items) -> Items)?, wf: Workflow, n: NodeInstance, items: Items,
        ceilingMs: Long = TriggerHub.TRIAGE_CEILING_MS, log: (String) -> Unit = {},
    ): Items? {
        val f = preFilter ?: return items
        val eng = n.params[TriggerHub.TRIAGE_KEY].asTextOrNull()
        if (eng.isNullOrBlank() || eng == "off") return items
        val kept = try { withTimeout(ceilingMs) { f(wf, n, items) } }
            catch (e: TimeoutCancellationException) { log("triage ${wf.name}/${n.name}: no answer within ${ceilingMs / 1000} s, running"); items }   // before the generic catch: a timeout IS a CancellationException
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("triage ${wf.name}/${n.name}: ${e.message}, running"); items }
        if (kept.isEmpty()) { log("triage ${wf.name}/${n.name}: dropped"); return null }
        return kept
    }
}
