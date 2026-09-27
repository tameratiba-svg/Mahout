package com.mob8n.engine.db

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mob8n.core.Graph
import com.mob8n.core.JSON
import com.mob8n.core.MAIN
import com.mob8n.core.NodeLog
import com.mob8n.core.NodeStatus
import com.mob8n.core.Note
import com.mob8n.core.Persistence
import com.mob8n.core.PlaylistEntry
import com.mob8n.core.RunRecord
import com.mob8n.core.RunStatus
import com.mob8n.core.SuspendedRun
import com.mob8n.core.Template
import com.mob8n.core.Workflow
import com.mob8n.core.item
import com.mob8n.engine.Secrets
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------- entities (DESIGN §7.6)

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String, val name: String, val enabled: Boolean, val graphJson: String,
    val updatedAt: Long, val lastRunStatus: String?, val lastRunAt: Long?,
)

@Entity(tableName = "runs", indices = [Index("workflowId"), Index("startedAt")])
data class RunEntity(
    @PrimaryKey val runId: String, val workflowId: String, val workflowName: String, val triggerType: String,
    val status: String, val startedAt: Long, val endedAt: Long?, val failedNodeId: String?, val error: String?, val parentRunId: String?,
)

@Entity(
    tableName = "node_logs", indices = [Index("runId")],
    foreignKeys = [ForeignKey(entity = RunEntity::class, parentColumns = ["runId"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class NodeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: String, val seq: Int, val nodeId: String, val nodeName: String,
    val nodeType: String, val status: String, val inputJson: String, val outputJson: String, val error: String?, val at: Long, val durationMs: Long,
)

@Entity(tableName = "suspended_runs", indices = [Index("expiresAt")])
data class SuspendedEntity(@PrimaryKey val runId: String, val json: String, val expiresAt: Long)

@Entity(tableName = "variables")
data class VariableEntity(@PrimaryKey val key: String, val valueJson: String, val updatedAt: Long)

@Entity(tableName = "node_state", primaryKeys = ["scope", "key"])
data class NodeStateEntity(val scope: String, val key: String, val valueJson: String, val expiresAt: Long?)

@Entity(tableName = "playlist_entries", indices = [Index("playlist"), Index(value = ["playlist", "title", "artist"], unique = true)])
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val playlist: String, val title: String, val artist: String?,
    val album: String?, val sourceApp: String?, val addedAt: Long, val mediaStoreId: Long?,
)

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val body: String, val createdAt: Long, val runId: String?)

// ---- v3 knowledge (DESIGN3 §5.4): one regular table for sources, one FTS4 table holding the chunk text
@Entity(tableName = "knowledge_sources", indices = [Index(value = ["name"], unique = true), Index("parentId"), Index("grp")])
data class KnowledgeSourceEntity(
    @PrimaryKey val id: String, val name: String, val kind: String /* SourceKind.name */, val uri: String?, val mime: String?,
    val bytes: Long, val chunks: Int, val chars: Int, val indexedAt: Long?, val lastModified: Long?, val error: String?,
    val pinned: Boolean, val grp: String /* "group" is an SQL keyword */, val parentId: String?, val createdAt: Long,
)

/** ONE FTS4 table holds the chunks: no external-content mirror, no sync triggers, no FK (FTS tables cannot carry them) — cascade is a DAO @Transaction. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["sourceId", "seq"])
@Entity(tableName = "knowledge_chunks")
data class KnowledgeChunkEntity(@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowid: Int = 0, val sourceId: String, val seq: Int, val text: String)

data class ChunkMatch(val rowid: Int, val sourceId: String, val seq: Int, val mi: ByteArray)
data class ChunkStats(val chunks: Int, val chars: Long)

// ---- v4 chat operator, usage, skills (DESIGN4 §4.1): four new tables; nothing existing changes
@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long,
    val settingsJson: String, val pendingJson: String?, val status: String, val lastError: String?,
)

@Entity(
    tableName = "messages", indices = [Index("conversationId"), Index(value = ["conversationId", "seq"], unique = true)],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val conversationId: String, val seq: Int, val role: String,
    val json: String /* redacted, image-free, <= 256 KB */, val text: String /* <= 4 KB projection */, val metaJson: String, val createdAt: Long,
)

@Entity(tableName = "ai_usage", indices = [Index("ts")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val provider: String, val model: String, val source: String,
    val inTok: Long, val outTok: Long, val cachedTok: Long, val cacheWriteTok: Long, val costUsd: Double?, val estimated: Boolean, val runId: String?, val conversationId: String?,
)

@Entity(tableName = "skills", indices = [Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey val id: String, val name: String, val description: String, val instructions: String,
    val allowedToolsJson: String, val tagsJson: String, val createdBy: String, val enabled: Boolean, val createdAt: Long, val updatedAt: Long, val usageCount: Int,
)

// ---------------------------------------------------------------- DAO (one, abstract so @Transaction works without jvm-default flags)

@Dao
abstract class Mob8nDao {
    // workflows
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC") abstract fun workflowsFlow(): Flow<List<WorkflowEntity>>
    @Query("SELECT * FROM workflows WHERE id = :id") abstract suspend fun workflow(id: String): WorkflowEntity?
    @Query("SELECT * FROM workflows WHERE enabled = 1") abstract suspend fun enabledWorkflows(): List<WorkflowEntity>
    @Upsert abstract suspend fun upsertWorkflow(w: WorkflowEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertWorkflowsIgnore(ws: List<WorkflowEntity>)
    @Query("UPDATE workflows SET enabled = :enabled, updatedAt = :at WHERE id = :id") abstract suspend fun setEnabled(id: String, enabled: Boolean, at: Long)
    @Query("DELETE FROM workflows WHERE id = :id") abstract suspend fun deleteWorkflow(id: String)
    @Query("UPDATE workflows SET lastRunStatus = :status, lastRunAt = :at WHERE id = :id") abstract suspend fun touchLastRun(id: String, status: String, at: Long)

    // runs
    @Upsert abstract suspend fun upsertRun(r: RunEntity)
    @Query("SELECT * FROM runs WHERE runId = :runId") abstract suspend fun run(runId: String): RunEntity?
    @Query("SELECT * FROM runs WHERE runId = :runId") abstract fun runFlow(runId: String): Flow<RunEntity?>
    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit") abstract fun runsFlow(limit: Int): Flow<List<RunEntity>>
    @Query("SELECT * FROM runs WHERE workflowId = :wf ORDER BY startedAt DESC LIMIT :limit") abstract fun runsFlow(wf: String, limit: Int): Flow<List<RunEntity>>
    /** The workflow's own RUNNING rows plus RUNNING sub-workflow rows they parent (F5: a cancelled sync callee must not stay RUNNING). */
    @Query("UPDATE runs SET status = 'FAILED', endedAt = :now, error = :error WHERE status = 'RUNNING' AND (workflowId = :wf OR parentRunId IN (SELECT runId FROM runs WHERE workflowId = :wf))")
    abstract suspend fun failRunningRows(wf: String, error: String, now: Long): Int
    /** K2: RUNNING rows started before :before whose workflow is not executing in this process (a resumed run keeps its old startedAt, hence :busy). */
    @Query("UPDATE runs SET status = 'FAILED', endedAt = :now, error = :error WHERE status = 'RUNNING' AND startedAt < :before AND workflowId NOT IN (:busy)")
    abstract suspend fun failStaleRunningRows(before: Long, error: String, now: Long, busy: List<String>): Int
    /** Re-derive the denormalized chip: a workflow whose lastRunStatus says RUNNING but has no RUNNING row left is FAILED. */
    @Query("UPDATE workflows SET lastRunStatus = 'FAILED', lastRunAt = :now WHERE lastRunStatus = 'RUNNING' AND id NOT IN (SELECT workflowId FROM runs WHERE status = 'RUNNING')")
    abstract suspend fun fixStaleLastRunStatus(now: Long): Int
    // ponytail: in-flight rows are never pruned; K2 fails stale RUNNING rows so this cannot grow unbounded
    @Query("DELETE FROM runs WHERE status NOT IN ('RUNNING','SUSPENDED') AND runId NOT IN (SELECT runId FROM runs ORDER BY startedAt DESC LIMIT :keep)") abstract suspend fun pruneRuns(keep: Int): Int

    @Transaction
    open suspend fun saveRun(r: RunEntity) {
        upsertRun(r)
        touchLastRun(r.workflowId, r.status, r.endedAt ?: r.startedAt)
    }

    @Transaction
    open suspend fun failRunning(wf: String, error: String, now: Long): Int {
        val n = failRunningRows(wf, error, now)
        if (n > 0) fixStaleLastRunStatus(now)
        return n
    }

    @Transaction
    open suspend fun failStaleRunning(before: Long, error: String, now: Long, busy: List<String>): Int {
        val n = failStaleRunningRows(before, error, now, busy)
        if (n > 0) fixStaleLastRunStatus(now)
        return n
    }

    // node logs
    @Insert abstract suspend fun insertNodeLog(l: NodeLogEntity)
    @Query("SELECT * FROM node_logs WHERE runId = :runId ORDER BY seq, id") abstract fun nodeLogsFlow(runId: String): Flow<List<NodeLogEntity>>
    @Query("SELECT outputJson FROM node_logs WHERE nodeId = :nodeId AND runId IN (SELECT runId FROM runs WHERE workflowId = :wf) ORDER BY at DESC LIMIT 1")
    abstract suspend fun lastOutputJson(wf: String, nodeId: String): String?

    // suspended runs
    @Upsert abstract suspend fun upsertSuspended(s: SuspendedEntity)
    @Query("SELECT * FROM suspended_runs WHERE runId = :runId") abstract suspend fun suspended(runId: String): SuspendedEntity?
    @Query("DELETE FROM suspended_runs WHERE runId = :runId") abstract suspend fun deleteSuspended(runId: String)
    @Query("SELECT * FROM suspended_runs ORDER BY expiresAt") abstract fun suspendedFlow(): Flow<List<SuspendedEntity>>
    @Query("SELECT * FROM suspended_runs WHERE expiresAt < :now") abstract suspend fun expiredSuspended(now: Long): List<SuspendedEntity>
    @Query("SELECT * FROM suspended_runs") abstract suspend fun allSuspended(): List<SuspendedEntity>

    // variables
    @Query("SELECT * FROM variables WHERE `key` = :key") abstract suspend fun variable(key: String): VariableEntity?
    @Upsert abstract suspend fun upsertVariable(v: VariableEntity)
    @Query("DELETE FROM variables WHERE `key` = :key") abstract suspend fun deleteVariable(key: String)
    @Query("SELECT * FROM variables ORDER BY `key`") abstract suspend fun allVariables(): List<VariableEntity>
    @Query("SELECT * FROM variables ORDER BY `key`") abstract fun variablesFlow(): Flow<List<VariableEntity>>

    // node state
    @Query("SELECT * FROM node_state WHERE scope = :scope AND `key` = :key") abstract suspend fun state(scope: String, key: String): NodeStateEntity?
    @Upsert abstract suspend fun upsertState(s: NodeStateEntity)
    @Query("DELETE FROM node_state WHERE scope = :scope AND `key` = :key") abstract suspend fun deleteState(scope: String, key: String)
    @Query("DELETE FROM node_state WHERE expiresAt IS NOT NULL AND expiresAt < :now") abstract suspend fun expireState(now: Long): Int

    // playlist
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertPlaylistIgnore(e: PlaylistEntryEntity): Long
    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlist = :playlist AND title = :title AND artist IS :artist")
    abstract suspend fun playlistCount(playlist: String, title: String, artist: String?): Int
    @Query("SELECT * FROM playlist_entries ORDER BY addedAt DESC") abstract fun playlistFlow(): Flow<List<PlaylistEntryEntity>>
    @Query("SELECT * FROM playlist_entries WHERE playlist = :name ORDER BY addedAt DESC") abstract fun playlistFlow(name: String): Flow<List<PlaylistEntryEntity>>
    @Query("SELECT DISTINCT playlist FROM playlist_entries ORDER BY playlist") abstract fun playlistNamesFlow(): Flow<List<String>>
    @Query("DELETE FROM playlist_entries WHERE id = :id") abstract suspend fun deletePlaylistEntry(id: Long)

    /** UNIQUE(playlist,title,artist) does not catch NULL artists (SQL NULLs are distinct), hence the IS-check first. */
    @Transaction
    open suspend fun addPlaylistEntry(e: PlaylistEntryEntity): Boolean {
        if (playlistCount(e.playlist, e.title, e.artist) > 0) return false
        return insertPlaylistIgnore(e) != -1L
    }

    // notes
    @Insert abstract suspend fun insertNote(n: NoteEntity): Long
    @Query("SELECT * FROM notes ORDER BY createdAt DESC") abstract fun notesFlow(): Flow<List<NoteEntity>>
    @Query("DELETE FROM notes WHERE id = :id") abstract suspend fun deleteNote(id: Long)

    // knowledge (v3)
    @Query("SELECT * FROM knowledge_sources ORDER BY grp, name") abstract fun knowledgeSourcesFlow(): Flow<List<KnowledgeSourceEntity>>
    @Query("SELECT * FROM knowledge_sources") abstract suspend fun knowledgeSources(): List<KnowledgeSourceEntity>
    @Query("SELECT * FROM knowledge_sources WHERE id = :id") abstract suspend fun knowledgeSource(id: String): KnowledgeSourceEntity?
    @Query("SELECT * FROM knowledge_sources WHERE name = :name COLLATE NOCASE LIMIT 1") abstract suspend fun knowledgeSourceByName(name: String): KnowledgeSourceEntity?
    @Query("SELECT * FROM knowledge_sources WHERE parentId = :parentId") abstract suspend fun knowledgeChildren(parentId: String): List<KnowledgeSourceEntity>
    @Upsert abstract suspend fun upsertKnowledgeSource(s: KnowledgeSourceEntity)
    @Query("DELETE FROM knowledge_sources WHERE id = :id") abstract suspend fun deleteKnowledgeSourceRow(id: String)
    @Insert abstract suspend fun insertChunks(c: List<KnowledgeChunkEntity>)
    @Query("DELETE FROM knowledge_chunks WHERE sourceId = :id") abstract suspend fun deleteChunks(id: String)
    @Query("SELECT rowid, sourceId, seq, matchinfo(knowledge_chunks, 'pcxnal') AS mi FROM knowledge_chunks WHERE knowledge_chunks MATCH :expr LIMIT :limit")
    abstract suspend fun matchAll(expr: String, limit: Int): List<ChunkMatch>
    @Query("SELECT rowid, sourceId, seq, matchinfo(knowledge_chunks, 'pcxnal') AS mi FROM knowledge_chunks WHERE knowledge_chunks MATCH :expr AND sourceId IN (:ids) LIMIT :limit")
    abstract suspend fun matchIn(expr: String, ids: List<String>, limit: Int): List<ChunkMatch>
    @Query("SELECT rowid, sourceId, seq, text FROM knowledge_chunks WHERE rowid IN (:rowids)") abstract suspend fun chunksByRowid(rowids: List<Int>): List<KnowledgeChunkEntity>
    @Query("SELECT text FROM knowledge_chunks WHERE sourceId = :id ORDER BY seq LIMIT :limit") abstract suspend fun chunkTexts(id: String, limit: Int): List<String>
    @Query("SELECT COUNT(*) AS chunks, COALESCE(SUM(LENGTH(text)), 0) AS chars FROM knowledge_chunks") abstract suspend fun chunkStats(): ChunkStats

    /** Cascade by hand: FTS tables cannot carry foreign keys. FOLDER rows take their children along. */
    @Transaction
    open suspend fun deleteKnowledgeSource(id: String) {
        for (c in knowledgeChildren(id)) { deleteChunks(c.id); deleteKnowledgeSourceRow(c.id) }
        deleteChunks(id); deleteKnowledgeSourceRow(id)
    }

    @Transaction
    open suspend fun replaceChunks(id: String, chunks: List<KnowledgeChunkEntity>) {
        deleteChunks(id)
        chunks.chunked(200).forEach { insertChunks(it) }
    }

    // ---- v4 (DESIGN4 §4.3)
    // conversations
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") abstract fun conversationsFlow(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") abstract suspend fun conversation(id: String): ConversationEntity?
    @Upsert abstract suspend fun upsertConversation(c: ConversationEntity)
    @Query("DELETE FROM conversations WHERE id = :id") abstract suspend fun deleteConversation(id: String)
    @Query("UPDATE conversations SET status = :status, pendingJson = :pending, lastError = :err, updatedAt = :at WHERE id = :id")
    abstract suspend fun setConversationState(id: String, status: String, pending: String?, err: String?, at: Long)
    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id") abstract suspend fun touchConversation(id: String, at: Long)
    @Query("SELECT id FROM conversations WHERE status = 'awaiting' AND updatedAt < :before") abstract suspend fun awaitingBefore(before: Long): List<String>
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :c") abstract suspend fun messageCount(c: String): Int
    // messages
    @Query("SELECT * FROM messages WHERE conversationId = :c ORDER BY seq DESC LIMIT :limit") abstract fun messagesFlow(c: String, limit: Int): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :c ORDER BY seq DESC LIMIT :limit") abstract suspend fun newestMessages(c: String, limit: Int): List<MessageEntity>
    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM messages WHERE conversationId = :c") abstract suspend fun nextSeq(c: String): Int
    @Insert abstract suspend fun insertMessage(m: MessageEntity): Long
    @Query("UPDATE messages SET metaJson = :meta WHERE id = :id") abstract suspend fun setMessageMeta(id: Long, meta: String)
    @Query("SELECT * FROM messages WHERE text LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT :limit") abstract suspend fun searchMessages(q: String, limit: Int): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages") abstract suspend fun messageCount(): Int

    /** seq = next free seq of the conversation; the parent row's updatedAt moves so the history list re-sorts. Returns the stored row (id + seq filled). */
    @Transaction
    open suspend fun appendMessage(m: MessageEntity, at: Long): MessageEntity {
        val row = m.copy(seq = nextSeq(m.conversationId))
        val id = insertMessage(row)
        touchConversation(m.conversationId, at)
        return row.copy(id = id)
    }

    // ai_usage
    @Insert abstract suspend fun insertUsage(u: AiUsageEntity)
    @Query("SELECT * FROM ai_usage WHERE ts >= :since ORDER BY ts DESC") abstract fun usageSince(since: Long): Flow<List<AiUsageEntity>>
    @Query("DELETE FROM ai_usage WHERE ts < :before") abstract suspend fun pruneUsage(before: Long): Int
    // runs (dashboard)
    @Query("SELECT * FROM runs WHERE startedAt >= :since ORDER BY startedAt DESC LIMIT :limit") abstract fun runsSince(since: Long, limit: Int): Flow<List<RunEntity>>
    // skills
    @Query("SELECT * FROM skills ORDER BY usageCount DESC, name") abstract fun skillsFlow(): Flow<List<SkillEntity>>
    @Query("SELECT * FROM skills ORDER BY usageCount DESC, name") abstract suspend fun skills(): List<SkillEntity>
    @Query("SELECT * FROM skills WHERE name = :name COLLATE NOCASE LIMIT 1") abstract suspend fun skillByName(name: String): SkillEntity?
    @Upsert abstract suspend fun upsertSkill(s: SkillEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertSkillsIgnore(s: List<SkillEntity>)
    @Query("DELETE FROM skills WHERE id = :id") abstract suspend fun deleteSkill(id: String)
    @Query("UPDATE skills SET usageCount = usageCount + 1 WHERE id = :id") abstract suspend fun bumpSkill(id: String)
}

// ---------------------------------------------------------------- database

@Database(
    entities = [WorkflowEntity::class, RunEntity::class, NodeLogEntity::class, SuspendedEntity::class, VariableEntity::class,
        NodeStateEntity::class, PlaylistEntryEntity::class, NoteEntity::class, KnowledgeSourceEntity::class, KnowledgeChunkEntity::class,
        ConversationEntity::class, MessageEntity::class, AiUsageEntity::class, SkillEntity::class],
    version = 3, exportSchema = true,
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): Mob8nDao

    companion object {
        // Copied VERBATIM from app/schemas/com.mob8n.engine.db.Db/2.json (TABLE_NAME placeholder substituted). MigrationSqlTest asserts equality:
        // Room compares the FTS option string at open, and a mismatch throws "Migration didn't properly handle" on a release user's first launch.
        const val CREATE_SOURCES = "CREATE TABLE IF NOT EXISTS `knowledge_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `uri` TEXT, `mime` TEXT, `bytes` INTEGER NOT NULL, `chunks` INTEGER NOT NULL, `chars` INTEGER NOT NULL, `indexedAt` INTEGER, `lastModified` INTEGER, `error` TEXT, `pinned` INTEGER NOT NULL, `grp` TEXT NOT NULL, `parentId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val CREATE_IDX_NAME = "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_sources_name` ON `knowledge_sources` (`name`)"
        const val CREATE_IDX_PARENT = "CREATE INDEX IF NOT EXISTS `index_knowledge_sources_parentId` ON `knowledge_sources` (`parentId`)"
        const val CREATE_IDX_GRP = "CREATE INDEX IF NOT EXISTS `index_knowledge_sources_grp` ON `knowledge_sources` (`grp`)"
        const val CREATE_CHUNKS = "CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_chunks` USING FTS4(`sourceId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `text` TEXT NOT NULL, tokenize=unicode61, notindexed=`sourceId`, notindexed=`seq`)"

        /** 1 -> 2: v3 knowledge tables only; every v1 table and row is untouched. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_SOURCES); db.execSQL(CREATE_IDX_NAME); db.execSQL(CREATE_IDX_PARENT); db.execSQL(CREATE_IDX_GRP); db.execSQL(CREATE_CHUNKS)
            }
        }

        // Copied VERBATIM from app/schemas/com.mob8n.engine.db.Db/3.json (TABLE_NAME placeholder substituted); MigrationSqlTest.migration23SqlEqualsExportedSchema pins tables AND indices.
        const val CREATE_CONVERSATIONS = "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `settingsJson` TEXT NOT NULL, `pendingJson` TEXT, `status` TEXT NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))"
        const val CREATE_IDX_CONV_UPDATED = "CREATE INDEX IF NOT EXISTS `index_conversations_updatedAt` ON `conversations` (`updatedAt`)"
        const val CREATE_MESSAGES = "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `role` TEXT NOT NULL, `json` TEXT NOT NULL, `text` TEXT NOT NULL, `metaJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        const val CREATE_IDX_MSG_CONV = "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)"
        const val CREATE_IDX_MSG_CONV_SEQ = "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_conversationId_seq` ON `messages` (`conversationId`, `seq`)"
        const val CREATE_AI_USAGE = "CREATE TABLE IF NOT EXISTS `ai_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, `source` TEXT NOT NULL, `inTok` INTEGER NOT NULL, `outTok` INTEGER NOT NULL, `cachedTok` INTEGER NOT NULL, `cacheWriteTok` INTEGER NOT NULL, `costUsd` REAL, `estimated` INTEGER NOT NULL, `runId` TEXT, `conversationId` TEXT)"
        const val CREATE_IDX_USAGE_TS = "CREATE INDEX IF NOT EXISTS `index_ai_usage_ts` ON `ai_usage` (`ts`)"
        const val CREATE_SKILLS = "CREATE TABLE IF NOT EXISTS `skills` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `instructions` TEXT NOT NULL, `allowedToolsJson` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `usageCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val CREATE_IDX_SKILLS_NAME = "CREATE UNIQUE INDEX IF NOT EXISTS `index_skills_name` ON `skills` (`name`)"

        /** 2 -> 3: four v4 tables (conversations, messages, ai_usage, skills) + five indices; every v1/v2 table and row untouched (the FTS table is not touched). */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(CREATE_CONVERSATIONS, CREATE_IDX_CONV_UPDATED, CREATE_MESSAGES, CREATE_IDX_MSG_CONV, CREATE_IDX_MSG_CONV_SEQ,
                    CREATE_AI_USAGE, CREATE_IDX_USAGE_TS, CREATE_SKILLS, CREATE_IDX_SKILLS_NAME).forEach(db::execSQL)
            }
        }

        fun open(ctx: Context): Db {
            // ponytail: destructive migration in debug only (BuildConfig is not generated: buildFeatures.buildConfig is off, so use the debuggable flag); release relies on the migration chain.
            val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return Room.databaseBuilder(ctx.applicationContext, Db::class.java, "mob8n.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)                   // release path: install-over-existing-data migrates 1->2->3 in one open
                .apply { if (debuggable) fallbackToDestructiveMigration() }    // debug only, unchanged rule
                .build()
        }
    }
}

// ---------------------------------------------------------------- mapping

private const val MAX_JSON_CHARS = 64 * 1024
private const val MAX_SUSPENDED_CHARS = 1_500_000   // under the 2 MB CursorWindow

private fun marker(s: String) = item("truncated" to true, "length" to s.length, "preview" to s.take(2048))
/** <= 64 KB or a parseable marker of the same JSON shape (array / object) so readers never see broken JSON. */
private fun capArray(a: JsonArray): String {
    val s = JSON.encodeToString(JsonArray.serializer(), a)
    return if (s.length <= MAX_JSON_CHARS) s else JSON.encodeToString(JsonArray.serializer(), JsonArray(listOf(marker(s))))
}
private fun capObject(o: JsonObject): String {
    val s = JSON.encodeToString(JsonObject.serializer(), o)
    return if (s.length <= MAX_JSON_CHARS) s else JSON.encodeToString(JsonObject.serializer(), item("truncated" to JsonArray(listOf(marker(s)))))
}
private fun parse(s: String): JsonElement? = runCatching { JSON.parseToJsonElement(s) }.getOrNull()

fun WorkflowEntity.toWorkflow() = Workflow(
    id, name, enabled,
    runCatching { JSON.decodeFromString(Graph.serializer(), graphJson) }.getOrDefault(Graph()),
    updatedAt, lastRunStatus?.let { s -> RunStatus.entries.firstOrNull { it.name == s } }, lastRunAt,
)
fun Workflow.toEntity() = WorkflowEntity(id, name, enabled, JSON.encodeToString(Graph.serializer(), graph), updatedAt, lastRunStatus?.name, lastRunAt)

fun RunEntity.toRecord() = RunRecord(
    runId, workflowId, workflowName, triggerType,
    RunStatus.entries.firstOrNull { it.name == status } ?: RunStatus.FAILED, startedAt, endedAt, failedNodeId, error, parentRunId,
)
fun RunRecord.toEntity() = RunEntity(runId, workflowId, workflowName, triggerType, status.name, startedAt, endedAt, failedNodeId, error, parentRunId)

fun NodeLogEntity.toRecord() = NodeLog(
    runId, seq, nodeId, nodeName, nodeType, NodeStatus.entries.firstOrNull { it.name == status } ?: NodeStatus.FAILED,
    parse(inputJson) as? JsonArray ?: JsonArray(emptyList()), parse(outputJson) as? JsonObject ?: JsonObject(emptyMap()), error, at, durationMs,
)
fun NodeLog.toEntity() = NodeLogEntity(0, runId, seq, nodeId, nodeName, nodeType, status.name, capArray(input), capObject(output), error, at, durationMs)

fun SuspendedEntity.toRecord(): SuspendedRun? = runCatching { JSON.decodeFromString(SuspendedRun.serializer(), json) }.getOrNull()
fun SuspendedRun.toEntity() = SuspendedEntity(runId, JSON.encodeToString(SuspendedRun.serializer(), this), expiresAt)

fun PlaylistEntryEntity.toRecord() = PlaylistEntry(id, playlist, title, artist, album, sourceApp, addedAt, mediaStoreId)
fun NoteEntity.toRecord() = Note(id, title, body, createdAt, runId)
/** Rows with unparseable JSON are dropped (same rule for Persistence.allVariables and the Variables screen). */
fun List<VariableEntity>.toVarMap(): Map<String, JsonElement> = mapNotNull { v -> parse(v.valueJson)?.let { v.key to it } }.toMap()

// ---------------------------------------------------------------- Persistence over Room

class RoomPersistence(private val ctx: Context, val dao: Mob8nDao, private val nowMs: () -> Long = System::currentTimeMillis) : Persistence {
    override suspend fun loadWorkflow(id: String): Workflow? = dao.workflow(id)?.toWorkflow()
    override suspend fun enabledWorkflows(): List<Workflow> = dao.enabledWorkflows().map { it.toWorkflow() }
    override suspend fun saveRun(run: RunRecord) = dao.saveRun(run.toEntity())
    override suspend fun loadRun(runId: String): RunRecord? = dao.run(runId)?.toRecord()
    override suspend fun saveNodeLog(log: NodeLog) = dao.insertNodeLog(log.toEntity())
    override suspend fun saveSuspended(s: SuspendedRun) {   // throws on failure: executor fails the run ("could not persist suspended run")
        val e = s.toEntity()
        require(e.json.length <= MAX_SUSPENDED_CHARS) { "suspended run payload ${e.json.length} chars exceeds the ${MAX_SUSPENDED_CHARS / 1024} KB row limit" }   // F25: a row no CursorWindow can read is worse than a failed run
        dao.upsertSuspended(e)
    }
    override suspend fun loadSuspended(runId: String): SuspendedRun? = dao.suspended(runId)?.toRecord()
    override suspend fun deleteSuspended(runId: String) = dao.deleteSuspended(runId)

    override suspend fun getVariable(key: String): JsonElement? = dao.variable(key)?.let { parse(it.valueJson) }
    override suspend fun setVariable(key: String, value: JsonElement?) {
        if (value == null) dao.deleteVariable(key)
        else dao.upsertVariable(VariableEntity(key, JSON.encodeToString(JsonElement.serializer(), value), nowMs()))
    }
    override suspend fun allVariables(): Map<String, JsonElement> = dao.allVariables().toVarMap()

    override suspend fun getState(scope: String, key: String): JsonElement? {
        val s = dao.state(scope, key) ?: return null
        if (s.expiresAt != null && s.expiresAt < nowMs()) { dao.deleteState(scope, key); return null }
        return parse(s.valueJson)
    }
    override suspend fun putState(scope: String, key: String, value: JsonElement?, ttlMs: Long?) {
        if (value == null) dao.deleteState(scope, key)
        else dao.upsertState(NodeStateEntity(scope, key, JSON.encodeToString(JsonElement.serializer(), value), ttlMs?.let { nowMs() + it }))
    }

    override fun getSecret(name: String): String? = Secrets.get(ctx, name)
    override fun allSecretValues(): Collection<String> = Secrets.allValues(ctx)

    override suspend fun addPlaylistEntry(e: PlaylistEntry): Boolean =
        dao.addPlaylistEntry(PlaylistEntryEntity(0, e.playlist, e.title, e.artist, e.album, e.sourceApp, e.addedAt, e.mediaStoreId))
    override suspend fun addNote(n: Note): Long = dao.insertNote(NoteEntity(0, n.title, n.body, n.createdAt, n.runId))

    /** Keys of the newest MAIN output item of (workflow, node) for the editor's upstream-fields helper. */
    suspend fun lastOutputKeys(workflowId: String, nodeId: String): List<String> {
        val out = dao.lastOutputJson(workflowId, nodeId)?.let { parse(it) as? JsonObject } ?: return emptyList()
        val first = (out[MAIN] as? JsonArray)?.firstOrNull() as? JsonObject
        return Template.keysOf(first)
    }
}
