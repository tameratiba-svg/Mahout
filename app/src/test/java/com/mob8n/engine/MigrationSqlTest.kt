package com.mob8n.engine

import com.mob8n.engine.db.Db
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM gate for MIGRATION_1_2 (DESIGN3 §5.4/§7) and MIGRATION_2_3 (DESIGN4 §4.2): every CREATE statement a migration runs must equal the
 * createSql Room exported for that schema, otherwise Room throws "Migration didn't properly handle" on a release user's first launch after the update.
 */
class MigrationSqlTest {
    private fun schema(version: Int): File =
        listOf(File("schemas"), File("app/schemas"), File("../app/schemas")).map { File(it, "com.mob8n.engine.db.Db/$version.json") }.firstOrNull { it.isFile }
            ?: error("schema $version.json not found from ${File(".").absolutePath}")

    private fun norm(sql: String, table: String) = sql.replace("\${TABLE_NAME}", table).replace(Regex("\\s+"), " ").trim()

    @Test fun migrationSqlEqualsExportedSchema() {
        val db = Json.parseToJsonElement(schema(2).readText()).jsonObject["database"]!!.jsonObject
        assertEquals(2, db["version"]!!.jsonPrimitive.content.toInt())
        val entities = db["entities"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }
        val sources = entities["knowledge_sources"]; val chunks = entities["knowledge_chunks"]
        assertNotNull(sources); assertNotNull(chunks)
        assertEquals(norm(sources!!["createSql"]!!.jsonPrimitive.content, "knowledge_sources"), norm(Db.CREATE_SOURCES, "knowledge_sources"))
        assertEquals(norm(chunks!!["createSql"]!!.jsonPrimitive.content, "knowledge_chunks"), norm(Db.CREATE_CHUNKS, "knowledge_chunks"))
        val indices = sources["indices"]!!.jsonArray.map { norm(it.jsonObject["createSql"]!!.jsonPrimitive.content, "knowledge_sources") }
        assertEquals(setOf(Db.CREATE_IDX_NAME, Db.CREATE_IDX_PARENT, Db.CREATE_IDX_GRP).map { norm(it, "knowledge_sources") }.toSet(), indices.toSet())
        assertEquals(3, indices.size)
        // v1 tables are still in v2 (nothing dropped)
        for (t in listOf("workflows", "runs", "node_logs", "suspended_runs", "variables", "node_state", "playlist_entries", "notes")) assertNotNull(t, entities[t])
    }

    /** DESIGN4 §4.2: every CREATE statement MIGRATION_2_3 runs equals the createSql Room exported for schema 3 — 4 tables + 5 indices; all 10 v2 tables still present. */
    @Test fun migration23SqlEqualsExportedSchema() {
        val db = Json.parseToJsonElement(schema(3).readText()).jsonObject["database"]!!.jsonObject
        assertEquals(3, db["version"]!!.jsonPrimitive.content.toInt())
        val entities = db["entities"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }
        assertEquals(14, entities.size)
        val tables = mapOf("conversations" to Db.CREATE_CONVERSATIONS, "messages" to Db.CREATE_MESSAGES, "ai_usage" to Db.CREATE_AI_USAGE, "skills" to Db.CREATE_SKILLS)
        for ((t, sql) in tables) assertEquals(t, norm(entities[t]!!["createSql"]!!.jsonPrimitive.content, t), norm(sql, t))
        val indices = mapOf(
            "conversations" to setOf(Db.CREATE_IDX_CONV_UPDATED), "messages" to setOf(Db.CREATE_IDX_MSG_CONV, Db.CREATE_IDX_MSG_CONV_SEQ),
            "ai_usage" to setOf(Db.CREATE_IDX_USAGE_TS), "skills" to setOf(Db.CREATE_IDX_SKILLS_NAME),
        )
        for ((t, want) in indices) {
            val have = entities[t]!!["indices"]!!.jsonArray.map { norm(it.jsonObject["createSql"]!!.jsonPrimitive.content, t) }
            assertEquals(t, want.map { norm(it, t) }.toSet(), have.toSet()); assertEquals(t, want.size, have.size)
        }
        assertEquals(5, indices.values.sumOf { it.size })
        for (t in listOf("workflows", "runs", "node_logs", "suspended_runs", "variables", "node_state", "playlist_entries", "notes", "knowledge_sources", "knowledge_chunks")) assertNotNull(t, entities[t])
        // the FTS table is not touched by 2 -> 3: its exported option string still equals the v2 constant
        assertEquals(norm(entities["knowledge_chunks"]!!["createSql"]!!.jsonPrimitive.content, "knowledge_chunks"), norm(Db.CREATE_CHUNKS, "knowledge_chunks"))
    }

    @Test fun schemaTwoIsUntouched() {
        val db = Json.parseToJsonElement(schema(2).readText()).jsonObject["database"]!!.jsonObject
        assertEquals(2, db["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("ce34dd0d600af5f3f1c327b0d77b86f0", db["identityHash"]!!.jsonPrimitive.content)
        assertEquals(10, db["entities"]!!.jsonArray.size)
    }

    @Test fun schemaOneIsUntouched() {
        val db = Json.parseToJsonElement(schema(1).readText()).jsonObject["database"]!!.jsonObject
        assertEquals(1, db["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("1cbdab06c9635cada96d8a5fd86a3a32", db["identityHash"]!!.jsonPrimitive.content)
        assertEquals(8, db["entities"]!!.jsonArray.size)
    }
}
