package com.mob8n.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class RunStatus { RUNNING, SUCCESS, FAILED, SUSPENDED, CANCELLED }
enum class NodeStatus { SUCCESS, FAILED, ERROR_ROUTED, SKIPPED, SUSPENDED, TIMEOUT }

@Serializable
data class RunRecord(
    val runId: String, val workflowId: String, val workflowName: String, val triggerType: String,
    val status: RunStatus, val startedAt: Long, val endedAt: Long? = null,
    val failedNodeId: String? = null, val error: String? = null, val parentRunId: String? = null,
)

@Serializable
data class NodeLog(
    val runId: String, val seq: Int, val nodeId: String, val nodeName: String, val nodeType: String,
    val status: NodeStatus,
    val input: JsonArray,          // redacted, first `snapshotItems` items
    val output: JsonObject,        // redacted, port -> JsonArray
    val error: String? = null, val at: Long, val durationMs: Long,
)

/** Everything needed to continue a run after Suspend. Fully serializable. */
@Serializable
data class EngineState(
    val edgeItems: Map<String, JsonArray> = emptyMap(),   // Edge.key -> items delivered on that edge
    val done: List<String> = emptyList(),                  // node ids finished (incl. skipped)
    val outputs: Map<String, JsonArray> = emptyMap(),      // node NAME -> MAIN items (for {{$node.X.f}} and leaves)
    val seq: Int = 0,
    val pendingIndex: Int = 0,                             // PER_ITEM: index of the item that suspended
    val pendingPorts: Map<String, JsonArray> = emptyMap(), // outputs accumulated before the suspending item
)

@Serializable
data class SuspendedRun(
    val runId: String, val workflowId: String, val nodeId: String,
    val kind: SuspendKind, val reason: String, val title: String, val text: String, val choices: List<String>,
    val resumeAtMs: Long?, val payload: JsonObject, val state: EngineState, val depth: Int,
    val createdAt: Long, val expiresAt: Long,
)

@Serializable data class Note(val id: Long = 0, val title: String, val body: String, val createdAt: Long, val runId: String? = null)
@Serializable data class PlaylistEntry(val id: Long = 0, val playlist: String, val title: String, val artist: String? = null, val album: String? = null, val sourceApp: String? = null, val addedAt: Long, val mediaStoreId: Long? = null)
