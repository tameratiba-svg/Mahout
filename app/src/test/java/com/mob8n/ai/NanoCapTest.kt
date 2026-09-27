package com.mob8n.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F29: the Nano prompt body is capped with room for the schema text AND the JSON repair retry suffix (NanoClient itself needs ML Kit). */
class NanoCapTest {
    private val limit = 4000
    private val schemaText = "\n\nRespond with ONLY a JSON object (no prose, no code fences) with exactly these keys:\n" +
        Llm.describeSchema(Llm.objectSchema(mapOf("title" to buildJsonObject { put("type", "string"); put("description", "Headline") }, "count" to buildJsonObject { put("type", "number") })))
    private val retrySuffix = "\n\nYour previous answer was rejected because it was not a valid JSON object. Previous answer:\n" + "y".repeat(1000) + "\n\nRespond with ONLY the JSON object."

    @Test fun cappedBodyPlusSchemaPlusRetrySuffixFitsTheLimit() {
        val body = "z".repeat(limit * 3 + 5_000)                                 // longer than the whole budget
        val capped = nanoCapChars(body, limit, schemaText.length + 1200)
        assertTrue(capped.length + schemaText.length <= limit * 3)              // first prompt fits
        assertTrue(capped.length + schemaText.length + retrySuffix.length <= limit * 3)   // repair retry fits too
    }

    @Test fun shortBodyIsUntouchedAndReserveNeverGoesNegative() {
        assertEquals("hello", nanoCapChars("hello", limit, schemaText.length + 1200))
        assertEquals("", nanoCapChars("hello", 10, 1_000_000))
        assertEquals("abc", nanoCapChars("abcdef", 1, 0))                        // no reserve: plain limit*3
    }
}
