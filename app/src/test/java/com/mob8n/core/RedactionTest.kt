package com.mob8n.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RedactionTest {
    private val secret = "sk-ant-verysecret123"

    @Test fun secretLookingKeysAreMaskedAtAnyDepth() {
        val e = item("api_key" to "x", "Authorization" to "Bearer y", "X-Api-Key" to "z", "password" to "p", "token" to "t", "mySecretThing" to 1,
            "nested" to mapOf("passwd" to "q", "safe" to "ok"), "list" to listOf(mapOf("apikey" to "a", "name" to "n")))
        val r = Redaction.redact(e, emptyList()) as JsonObject
        for (k in listOf("api_key", "Authorization", "X-Api-Key", "password", "token", "mySecretThing")) assertEquals(k, JsonPrimitive(Redaction.MASK), r[k])
        assertEquals(JsonPrimitive(Redaction.MASK), (r["nested"] as JsonObject)["passwd"])
        assertEquals(JsonPrimitive("ok"), (r["nested"] as JsonObject)["safe"])
        assertEquals(JsonPrimitive(Redaction.MASK), ((r["list"] as JsonArray)[0] as JsonObject)["apikey"])
        assertEquals(JsonPrimitive("n"), ((r["list"] as JsonArray)[0] as JsonObject)["name"])
    }

    @Test fun secretValuesAreMaskedInsideStringsWhenLongEnough() {
        val e = item("text" to "header: $secret; twice $secret", "url" to "https://x/?k=$secret", "short" to "abc", "n" to 5, "b" to true,
            "arr" to listOf("no", secret))
        val r = Redaction.redact(e, listOf(secret, "abc")) as JsonObject     // "abc" < 8 chars: never masked
        assertEquals("header: ***; twice ***", r["text"]!!.asText())
        assertEquals("https://x/?k=***", r["url"]!!.asText())
        assertEquals("abc", r["short"]!!.asText())
        assertEquals(JsonPrimitive(5), r["n"]); assertEquals(JsonPrimitive(true), r["b"])
        assertEquals(listOf("no", "***"), (r["arr"] as JsonArray).map { it.asText() })
    }

    @Test fun redactTextMasksStoredSecrets() {
        assertEquals("HTTP 403 from https://x/?k=***: body ***", Redaction.redactText("HTTP 403 from https://x/?k=$secret: body $secret", listOf(secret, "abc")))
        assertEquals("abc stays", Redaction.redactText("abc stays", listOf("abc")))       // < 8 chars: never masked
        assertEquals("plain", Redaction.redactText("plain", listOf(secret)))
        assertEquals("", Redaction.redactText("", listOf(secret)))
    }

    @Test fun untouchedValuesKeepIdentityAndShape() {
        val e = item("a" to mapOf("b" to listOf(1, 2, mapOf("c" to "d"))), "s" to "plain")
        val r = Redaction.redact(e, listOf(secret))
        assertEquals(e, r)
        val prim = JsonPrimitive("plain")
        assertSame(prim, Redaction.redact(prim, listOf(secret)))
        assertEquals(JsonArray(emptyList()), Redaction.redact(JsonArray(emptyList()), listOf(secret)))
    }
}
