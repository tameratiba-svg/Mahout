package com.mob8n.engine

import android.app.Application
import android.content.Context
import android.util.Log
import com.mob8n.core.Catalog
import com.mob8n.core.EMPTY
import com.mob8n.core.Gate
import com.mob8n.core.HostState
import com.mob8n.core.Item
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.Note
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeLog
import com.mob8n.core.Persistence
import com.mob8n.core.PlaylistEntry
import com.mob8n.core.RunRecord
import com.mob8n.core.Redaction
import com.mob8n.core.RunStatus
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.SuspendedRun
import com.mob8n.core.TRIGGER_CALLED
import com.mob8n.core.TRIGGER_MANUAL
import com.mob8n.core.TriggerHost
import com.mob8n.core.Workflow
import com.mob8n.engine.db.Db
import com.mob8n.engine.db.RoomPersistence
import com.mob8n.engine.db.toEntity
import com.mob8n.engine.db.toRecord
import com.mob8n.engine.db.toVarMap
import com.mob8n.engine.db.toWorkflow
import com.mob8n.engine.knowledge.Knowledge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** listenerConnected: access granted AND the OS currently has NotifListener bound (F21: granted-but-unbound is the silent failure mode). */
data class HostStatus(val listenerGranted: Boolean, val serviceRunning: Boolean, val runtimeTriggersInUse: Boolean, val listenerConnected: Boolean = false)

/** The facade every other lane talks to (DESIGN §2.3). Signatures are frozen; everything else lives in TriggerHub / db. */
class Engine(val app: Application, val catalog: Catalog) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db: Db = Db.open(app)                      // cheap: Room opens the file lazily on first query
    internal val room: RoomPersistence = RoomPersistence(app, db.dao())
    val persistence: Persistence get() = room
    /** v3: the on-device knowledge index (Room FTS4). Other lanes reach it only through this property. */
    val knowledge: Knowledge = Knowledge(app, room.dao, scope)
    internal val hub: TriggerHub = TriggerHub(this)
    val host: TriggerHost get() = hub.host
    private val _hostStatus = MutableStateFlow(HostStatus(false, false, false))
    val hostStatus: StateFlow<HostStatus> get() = _hostStatus
    private val started = AtomicBoolean(false)
    private val createdAt = System.currentTimeMillis()
    private val holdCount = AtomicInteger(0)
    /** Chat approval buttons (ApprovalReceiver) land here; Mob8NApp points it at ChatRunner::decide (DESIGN4 V3). */
    @Volatile var chatDecision: ((conversationId: String, approve: Boolean) -> Unit)? = null
    /** v5: async pre-filter for event-fired runs (System 1 triage). Mob8NApp points it at ai.Triage::filter; null = no filtering. Returns the items to run with; empty = drop. */
    @Volatile var preFilter: (suspend (Workflow, NodeInstance, Items) -> Items)? = null

    /** Non-blocking (Application.onCreate): channels now; stale-run sweep, seed, re-arm, housekeeping inside scope. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try { Notifs.ensureChannels(app) } catch (e: Exception) { Log.w(LOG_TAG, "channels: ${e.message}") }
        scope.launch {
            // K2: every RUNNING row older than this process belongs to a process that died mid-run.
            try { room.dao.failStaleRunning(createdAt, PROCESS_DIED, System.currentTimeMillis(), hub.busyWorkflowIds()) } catch (e: Exception) { Log.w(LOG_TAG, "stale runs: ${e.message}") }
            try { Seed.seedIfNeeded(app, room.dao, System.currentTimeMillis()) } catch (e: Exception) { Log.w(LOG_TAG, "seed: ${e.message}") }
            try { hub.rearmAll() } catch (e: Exception) { Log.w(LOG_TAG, "rearm: ${e.message}") }
            try { HousekeepingWorker.enqueue(app) } catch (e: Exception) { Log.w(LOG_TAG, "housekeeping: ${e.message}") }
            refreshStatus()
        }
    }

    /** Cold-start entry points (receivers, workers, EntryActivity) await the first enabled-workflow cache fill before instancesOf()/fire (F14). */
    suspend fun awaitReady() = hub.awaitReady()

    // ---- hosts
    fun attachRuntimeTriggers(): AutoCloseable = hub.attachRuntimeTriggers()
    fun ensureHostRunning(reason: String) { hub.tryStartHost(reason) }

    // ---- process holds (chat turns, DESIGN4 V6): HostService.idleWatch treats holds > 0 as busy
    /** Increments; close() decrements (idempotent); also tries to start the host best-effort (a foreground Send may start the FGS). */
    fun holdHost(reason: String): AutoCloseable {
        holdCount.incrementAndGet()
        try { hub.tryStartHost(reason) } catch (e: Exception) { Log.w(LOG_TAG, "hold $reason: ${e.message}") }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) holdCount.decrementAndGet() }
    }
    val holds: Int get() = holdCount.get()

    internal fun refreshStatus() {
        val listener = try { Gate.NotificationListener.granted(app) } catch (_: Exception) { false }
        _hostStatus.value = HostStatus(listener, HostState.serviceRunning, hub.runtimeTriggersInUse(), HostState.listenerConnected)
    }

    // ---- workflows
    fun workflows(): Flow<List<Workflow>> = room.dao.workflowsFlow().map { l -> l.map { it.toWorkflow() } }
    suspend fun workflow(id: String): Workflow? = room.loadWorkflow(id)

    suspend fun save(wf: Workflow) {
        val old = room.loadWorkflow(wf.id)
        // the editor's copy may carry stale lastRun* and `enabled` (F36: toggled elsewhere while editing); the stored values win
        val w = wf.copy(updatedAt = System.currentTimeMillis(), enabled = old?.enabled ?: wf.enabled, lastRunStatus = old?.lastRunStatus ?: wf.lastRunStatus, lastRunAt = old?.lastRunAt ?: wf.lastRunAt)
        room.dao.upsertWorkflow(w.toEntity())
        hub.onWorkflowChanged(old, w)
        refreshStatus()
    }

    suspend fun delete(id: String) {
        val old = room.loadWorkflow(id)
        // F9: close the workflow's waiting runs now (Executor.resume would also fail them lazily on the next decision)
        // ponytail: linear scan of suspended_runs, add workflowId column if it ever grows
        try {
            val now = System.currentTimeMillis()
            for (s in room.dao.allSuspended().mapNotNull { it.toRecord() }.filter { it.workflowId == id }) {
                Notifs.cancelApproval(app, s.runId); DelayedRunWorker.cancelAllResumes(app, s.runId)
                room.dao.deleteSuspended(s.runId)
                room.loadRun(s.runId)?.let { room.saveRun(it.copy(status = RunStatus.FAILED, endedAt = now, failedNodeId = s.nodeId, error = "workflow deleted")) }
            }
        } catch (e: Exception) { Log.w(LOG_TAG, "delete $id: suspended cleanup: ${e.message}") }
        room.dao.deleteWorkflow(id)
        hub.onWorkflowChanged(old, null)
        refreshStatus()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val old = room.loadWorkflow(id) ?: return
        if (old.enabled == enabled) return
        val now = System.currentTimeMillis()
        room.dao.setEnabled(id, enabled, now)
        // F12: disabling cancels this workflow's pending schedule_run deliveries; already-disabled helpers stay callable (enabled gates event triggers only).
        if (!enabled) try { DelayedRunWorker.cancelScheduledRuns(app, id) } catch (e: Exception) { Log.w(LOG_TAG, "cancel scheduled runs: ${e.message}") }
        hub.onWorkflowChanged(old, old.copy(enabled = enabled, updatedAt = now))
        refreshStatus()
    }

    /** Fires the workflow's trigger.manual node; awaits completion (or suspension) and returns the runId, null when it cannot start. */
    suspend fun runManual(workflowId: String, items: Items = listOf(EMPTY)): String? {
        val wf = room.loadWorkflow(workflowId) ?: return null
        val trig = wf.graph.nodes.firstOrNull { it.type == TRIGGER_MANUAL && !it.disabled }
            ?: wf.graph.nodes.firstOrNull { catalog.spec(it.type)?.kind == NodeKind.TRIGGER && !it.disabled }
            ?: return null
        return hub.launchRun(wf, trig, items).await()?.run?.runId
    }

    suspend fun resume(runId: String, decision: String) {
        Notifs.cancelApproval(app, runId)
        hub.resume(runId, decision).await()
    }

    /**
     * Kill switch (DESIGN4P P9): cancels every executing run (TriggerHub Jobs) and joins them; their rows become FAILED "stopped by user"; every SUSPENDED
     * run is closed FAILED "stopped by user" (timers + notifications cancelled). Returns runs cancelled + suspended closed. Never throws.
     */
    suspend fun cancelAllRuns(): Int = try { hub.cancelAllRuns() } catch (e: Exception) { Log.w(LOG_TAG, "cancelAllRuns: ${e.message}"); 0 }

    // ---- history
    fun runs(workflowId: String? = null, limit: Int = 200): Flow<List<RunRecord>> =
        (if (workflowId == null) room.dao.runsFlow(limit) else room.dao.runsFlow(workflowId, limit)).map { l -> l.map { it.toRecord() } }
    fun run(runId: String): Flow<RunRecord?> = room.dao.runFlow(runId).map { it?.toRecord() }   // F41: one row, not the newest 200
    fun nodeLogs(runId: String): Flow<List<NodeLog>> = room.dao.nodeLogsFlow(runId).map { l -> l.map { it.toRecord() } }
    suspend fun lastOutputKeys(workflowId: String, nodeId: String): List<String> = room.lastOutputKeys(workflowId, nodeId)
    fun suspendedRuns(): Flow<List<SuspendedRun>> = room.dao.suspendedFlow().map { l -> l.mapNotNull { it.toRecord() } }

    // ---- side tables
    fun notes(): Flow<List<Note>> = room.dao.notesFlow().map { l -> l.map { it.toRecord() } }
    suspend fun deleteNote(id: Long) = room.dao.deleteNote(id)
    fun playlist(name: String? = null): Flow<List<PlaylistEntry>> =
        (if (name == null) room.dao.playlistFlow() else room.dao.playlistFlow(name)).map { l -> l.map { it.toRecord() } }
    fun playlistNames(): Flow<List<String>> = room.dao.playlistNamesFlow()
    suspend fun deletePlaylistEntry(id: Long) = room.dao.deletePlaylistEntry(id)
    fun variables(): Flow<Map<String, JsonElement>> = room.dao.variablesFlow().map { it.toVarMap() }

    // ---- v4 chat store (DESIGN4 §3.1; records in ChatRecords.kt)
    fun conversations(): Flow<List<Conversation>> = room.dao.conversationsFlow().map { l -> l.map { it.toRecord() } }
    suspend fun conversation(id: String): Conversation? = room.dao.conversation(id)?.toRecord()
    suspend fun saveConversation(c: Conversation) = room.dao.upsertConversation(c.toEntity())
    /** Messages cascade (FK); the conversation's approval notification is cancelled. */
    suspend fun deleteConversation(id: String) { cancelChatApproval(id); room.dao.deleteConversation(id) }
    suspend fun setConversationState(id: String, status: String, pendingJson: String?, lastError: String? = null) =
        room.dao.setConversationState(id, status, pendingJson, lastError?.let { Redaction.redactText(it, secretsOrEmpty()) }, System.currentTimeMillis())
    /** Newest MESSAGE_WINDOW rows, ascending seq. */
    fun messages(conversationId: String): Flow<List<ChatMessage>> = room.dao.messagesFlow(conversationId, MESSAGE_WINDOW).map { l -> l.asReversed().map { it.toRecord() } }
    /** Newest rows whose json sizes sum <= maxChars, oldest first. */
    suspend fun messagesTail(conversationId: String, maxChars: Int = 300_000): List<ChatMessage> = ChatStore.tail(room.dao.newestMessages(conversationId, MESSAGE_WINDOW), maxChars)
    /** Assigns seq, redacts json/meta (Redaction.redact with allSecretValues()), strips images, caps at 256 KB (V5), bumps conversations.updatedAt. Returns the stored row. */
    suspend fun appendMessage(m: ChatMessage): ChatMessage {
        val prepared = ChatStore.prepare(m, secretsOrEmpty()) { Log.w(LOG_TAG, it) }
        return room.dao.appendMessage(prepared.toEntity(), System.currentTimeMillis()).toRecord()
    }
    suspend fun updateMessageMeta(id: Long, meta: JsonObject) =
        room.dao.setMessageMeta(id, JSON.encodeToString(JsonObject.serializer(), Redaction.redact(meta, secretsOrEmpty()) as JsonObject))
    // ponytail: LIKE search over messages.text; upgrade = FTS4 like knowledge_chunks
    suspend fun searchMessages(query: String, limit: Int = 50): List<ChatMessage> =
        if (query.isBlank()) emptyList() else room.dao.searchMessages(query.trim(), limit).map { it.toRecord() }
    /** Conversations awaiting an approval for longer than the TTL: pendingJson = null, status idle. Returns their ids (HousekeepingWorker cancels the notifications). */
    suspend fun expireChatApprovals(olderThanMs: Long): List<String> {
        val now = System.currentTimeMillis()
        val ids = room.dao.awaitingBefore(now - olderThanMs)
        for (id in ids) room.dao.setConversationState(id, "idle", null, null, now)
        return ids
    }

    // ---- usage + dashboard
    suspend fun recordAiUsage(u: AiUsageRow) = room.dao.insertUsage(u.toEntity())
    fun aiUsageSince(sinceMs: Long): Flow<List<AiUsageRow>> = room.dao.usageSince(sinceMs).map { l -> l.map { it.toRecord() } }
    fun runsSince(sinceMs: Long, limit: Int = 2000): Flow<List<RunRecord>> = room.dao.runsSince(sinceMs, limit).map { l -> l.map { it.toRecord() } }
    /** mob8n.db + -wal + -shm lengths. */
    fun dbBytes(): Long {
        val f = app.getDatabasePath("mob8n.db")
        return listOf(f, File(f.path + "-wal"), File(f.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L }
    }

    // ---- skills
    fun skills(): Flow<List<Skill>> = room.dao.skillsFlow().map { l -> l.map { it.toRecord() } }
    suspend fun skillsNow(): List<Skill> = room.dao.skills().map { it.toRecord() }
    suspend fun skill(name: String): Skill? = room.dao.skillByName(name)?.toRecord()
    /** Upsert; a different id with the same name -> IllegalArgumentException("A skill named X exists"). */
    suspend fun saveSkill(s: Skill) {
        val other = room.dao.skillByName(s.name)
        if (other != null && other.id != s.id) throw IllegalArgumentException("A skill named ${s.name} exists")
        room.dao.upsertSkill(s.toEntity())
    }
    suspend fun deleteSkill(id: String) = room.dao.deleteSkill(id)
    suspend fun bumpSkillUsage(id: String) = room.dao.bumpSkill(id)
    /** Insert IGNORE by name, once: guarded by settings["skills_seeded_v1"] so a user's deletion of a preset sticks (Skills > Restore presets re-seeds explicitly). */
    suspend fun seedSkills(presets: List<Skill>) {
        val prefs = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SKILLS_SEEDED, false)) return
        restoreSkills(presets)
        prefs.edit().putBoolean(KEY_SKILLS_SEEDED, true).apply()
    }
    /** Re-seed by name (missing presets only; existing rows untouched). */
    suspend fun restoreSkills(presets: List<Skill>) {
        val existing = room.dao.skills().map { it.name.lowercase() }.toSet()
        room.dao.insertSkillsIgnore(presets.filter { it.name.lowercase() !in existing }.map { it.toEntity() })
    }

    // ---- execution seams for the operator
    data class CalledResult(val runId: String?, val status: RunStatus?, val leafItems: Items, val error: String?)

    /** Runs a workflow at its enabled trigger.called node (workflows-as-tools from chat). `enabled` ignored like logic.run_workflow. SUSPENDED is a RESULT, not an error. null runId = could not start (no called trigger / host refused). */
    suspend fun runCalled(workflowId: String, items: Items): CalledResult {
        val wf = room.loadWorkflow(workflowId) ?: return CalledResult(null, null, emptyList(), "workflow $workflowId not found")
        val trig = wf.graph.nodes.firstOrNull { it.type == TRIGGER_CALLED && !it.disabled }
            ?: return CalledResult(null, null, emptyList(), "${wf.name} has no Called-by-Workflow trigger")
        return hub.runCalled(wf, trig, items)
    }

    /**
     * Runs one non-trigger node in isolation for the chat operator / JS bridge: gates, node timeout and the no-Suspend rule are enforced by
     * Executor.runNode; NO run row, NO node_logs (the chat message is the log), so chat node calls never inflate the Dashboard's run counts.
     */
    suspend fun runNode(specId: String, params: JsonObject, item: Item = EMPTY, label: String = "chat"): Items =
        hub.executor.runNode("chat:$label", CHAT_WORKFLOW, specId, params, item, emptyMap(), room.allVariables(), 0)

    // ---- chat approval notifications (channel `approvals`; buttons -> ApprovalReceiver -> chatDecision)
    fun postChatApproval(conversationId: String, title: String, text: String) = Notifs.postChatApproval(app, conversationId, title, text)
    fun cancelChatApproval(conversationId: String) = Notifs.cancelChatApproval(app, conversationId)

    private fun secretsOrEmpty(): Collection<String> = try { room.allSecretValues() } catch (_: Exception) { emptyList() }

    companion object {
        const val PROCESS_DIED = "process died"
        const val MESSAGE_WINDOW = 500
        const val KEY_SKILLS_SEEDED = "skills_seeded_v1"
        /** The synthetic workflow chat node calls run under (runId chat:<label>). */
        val CHAT_WORKFLOW = Workflow(id = "chat", name = "Chat")
    }
}
