package com.mob8n.core

import kotlinx.serialization.json.JsonElement

/** The engine's storage seam. Impl 1: engine/db/RoomPersistence. Impl 2: test InMemoryPersistence (engine lane's test dir). */
interface Persistence {
    suspend fun loadWorkflow(id: String): Workflow?
    suspend fun enabledWorkflows(): List<Workflow>
    suspend fun saveRun(run: RunRecord)                    // upsert by runId; also denormalizes Workflow.lastRunStatus/lastRunAt
    suspend fun loadRun(runId: String): RunRecord?
    suspend fun saveNodeLog(log: NodeLog)
    suspend fun saveSuspended(s: SuspendedRun)             // MUST throw on failure (executor fails the run: data-loss protection)
    suspend fun loadSuspended(runId: String): SuspendedRun?
    suspend fun deleteSuspended(runId: String)
    suspend fun getVariable(key: String): JsonElement?
    suspend fun setVariable(key: String, value: JsonElement?)          // null deletes
    suspend fun allVariables(): Map<String, JsonElement>
    /** Namespaced node state (dedupe windows, rate buckets, counters, last-seen ids). ttlMs null = keep. */
    suspend fun getState(scope: String, key: String): JsonElement?
    suspend fun putState(scope: String, key: String, value: JsonElement?, ttlMs: Long? = null)
    /** Secrets never enter items or logs. Reads SharedPreferences SECRETS_PREFS on Android. */
    fun getSecret(name: String): String?
    fun allSecretValues(): Collection<String>
    /** Returns false when the (playlist,title,artist) row already existed. */
    suspend fun addPlaylistEntry(e: PlaylistEntry): Boolean
    suspend fun addNote(n: Note): Long
}
