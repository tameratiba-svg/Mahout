package com.mob8n.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParamSpecTest {
    private fun s(v: String) = JsonPrimitive(v)
    private fun arr(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })

    @Test fun requiredAndAbsent() {
        val req = text("url", "URL", required = true)
        assertEquals("URL is required", req.validate(null))
        assertEquals("URL is required", req.validate(JsonNull))
        assertEquals("URL is required", req.validate(s("  ")))
        assertEquals("URL is required", req.validate(JsonArray(emptyList())))
        assertNull(text("x", "X").validate(null))
        assertNull(choice("m", "Mode", listOf("a", "b")).validate(null))          // required but has default
        assertNull(req.validate(s("{{url}}")))
    }

    @Test fun numbersDurationsAndTemplates() {
        val n = number("n", "N", min = 1.0, max = 10.0)
        assertNull(n.validate(JsonPrimitive(5)))
        assertNull(n.validate(s("5")))
        assertEquals("N must be a number", n.validate(s("five")))
        assertEquals("N must be >= 1", n.validate(JsonPrimitive(0)))
        assertEquals("N must be <= 10", n.validate(JsonPrimitive(11)))
        assertNull(n.validate(s("{{count}}")))                                      // templated NUMBER accepts a template
        val d = durationMs("d", "D", 1000, minMs = 100, maxMs = 5000)
        assertEquals("D must be >= 100", d.validate(JsonPrimitive(50)))
        assertEquals("D must be a number", d.validate(s("{{x}}")))                    // durations are not templated
    }

    @Test fun boolEnumTimeLabels() {
        val b = bool("b", "B")
        assertNull(b.validate(JsonPrimitive(true))); assertNull(b.validate(s("false")))
        assertEquals("B must be true/false", b.validate(s("maybe")))
        val e = choice("mode", "Mode", listOf("a", "b"))
        assertNull(e.validate(s("a"))); assertEquals("Mode must be one of [a, b]", e.validate(s("c")))
        val t = clockTime("t", "Time")
        assertNull(t.validate(s("08:00"))); assertNull(t.validate(s("23:59")))
        assertEquals("Time must be HH:mm", t.validate(s("24:00"))); assertEquals("Time must be HH:mm", t.validate(s("8:00")))
        val l = labels("l", "Labels", definesPorts = true)
        assertNull(l.validate(arr("x", "y")))
        assertEquals("Labels must be a list of names", l.validate(JsonArray(listOf(JsonPrimitive(1)))))
        assertEquals("Labels must be a list of names", l.validate(arr("x", " ")))
        assertEquals("Labels must be a list of names", l.validate(s("x")))
    }

    @Test fun rowsRecurse() {
        val r = rows("headers", "Headers", listOf(text("name", "Name", required = true), number("n", "N", min = 0.0)))
        assertNull(r.validate(JsonArray(listOf(item("name" to "a", "n" to 1)))))
        assertEquals("Headers must be a list", r.validate(s("x")))
        assertEquals("Headers row 1 is malformed", r.validate(JsonArray(listOf(s("x")))))
        assertEquals("Headers row 2: Name is required", r.validate(JsonArray(listOf(item("name" to "a"), item("n" to 1)))))
        assertEquals("Headers row 1: N must be >= 0", r.validate(JsonArray(listOf(item("name" to "a", "n" to -1)))))
    }

    @Test fun constructorInvariants() {
        try { ParamSpec("Bad Key", "x", ParamKind.TEXT); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("identifier")) }
        try { ParamSpec("k", "x", ParamKind.ENUM); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("options")) }
        try { ParamSpec("k", "x", ParamKind.ROWS); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("row schema")) }
        try { ParamSpec("k", "x", ParamKind.TEXT, definesPorts = true); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("LABELS")) }
        try { NodeSpec("bad", "x", NodeKind.LOGIC, "d"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("lane.snake_case")) }
        try { NodeSpec("a.b", "x", NodeKind.LOGIC, "d", outputs = listOf(ERROR)); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("implicit")) }
    }

    @Test fun jsonSchemaShapes() {
        assertEquals("number", number("n", "N").jsonSchema()["type"]!!.asText())
        assertEquals("integer", durationMs("d", "D", 1).jsonSchema()["type"]!!.asText())
        assertEquals("boolean", bool("b", "B").jsonSchema()["type"]!!.asText())
        val e = choice("m", "Mode", listOf("a", "b"), help = "pick").jsonSchema()
        assertEquals("string", e["type"]!!.asText()); assertEquals(arr("a", "b"), e["enum"]); assertEquals("Mode. pick", e["description"]!!.asText())
        val l = labels("l", "L").jsonSchema()
        assertEquals("array", l["type"]!!.asText()); assertEquals("string", (l["items"] as JsonObject)["type"]!!.asText())
        val r = rows("h", "H", listOf(text("name", "Name", required = true), number("n", "N"))).jsonSchema()
        val items = r["items"] as JsonObject
        assertEquals("object", items["type"]!!.asText())
        assertEquals(JsonPrimitive(false), items["additionalProperties"])
        assertEquals(arr("name", "n"), items["required"])                                 // every row column required (strict)
        val props = items["properties"] as JsonObject
        assertEquals("string", (props["name"] as JsonObject)["type"]!!.asText())        // required column: plain schema
        assertNotNull((props["n"] as JsonObject)["anyOf"])                               // optional column: anyOf [.., null]
        assertEquals("Time. HH:mm 24h", clockTime("t", "Time").jsonSchema()["description"]!!.asText())
        assertEquals("App. Android package name", appPicker("app", "App").jsonSchema()["description"]!!.asText())
    }

    @Test fun strictSchemaMakesOptionalsNullable() {
        val required = text("url", "URL", required = true).strictSchema()
        assertEquals("string", required["type"]!!.asText()); assertNull(required["anyOf"])
        val optional = text("body", "Body", default = "x").strictSchema()
        val anyOf = optional["anyOf"] as JsonArray
        assertEquals(2, anyOf.size)
        assertEquals("string", (anyOf[0] as JsonObject)["type"]!!.asText()); assertNull((anyOf[0] as JsonObject)["description"])
        assertEquals("null", (anyOf[1] as JsonObject)["type"]!!.asText())
        assertEquals("Body (null = default: x)", optional["description"]!!.asText())
        assertEquals("N (null = default)", number("n", "N").strictSchema()["description"]!!.asText())
    }

    @Test fun toolDefExcludesSecretsAndRequiresEveryKey() {
        val spec = NodeSpec("data.http", "HTTP", NodeKind.DATA, "call", params = listOf(text("url", "URL", required = true), secret("auth", "Auth"), bool("x", "X")), agentTool = true)
        val def = spec.toolDef()
        assertEquals("data_http", def["name"]!!.asText())
        assertEquals("HTTP: call", def["description"]!!.asText())
        assertEquals(JsonPrimitive(true), def["strict"])
        val schema = def["input_schema"] as JsonObject
        assertEquals(arr("url", "x"), schema["required"])
        assertEquals(setOf("url", "x"), (schema["properties"] as JsonObject).keys)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        val cleaned = spec.paramsFromToolInput(item("url" to "https://a", "x" to null, "auth" to "leak", "junk" to 1))
        assertEquals(setOf("url"), cleaned.keys)
        try { spec.paramsFromToolInput(item("url" to null)); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("URL is required")) }
    }

    @Test fun outputPortsWithDefinesPorts() {
        val spec = NodeSpec("ai.classify", "Classify", NodeKind.AI, "d", params = listOf(labels("labels", "Labels", default = listOf("a", "b"), definesPorts = true)), outputs = listOf(PORT_OTHER))
        assertEquals(listOf("a", "b", PORT_OTHER), spec.outputPorts(EMPTY))                                  // default labels
        assertEquals(listOf("x", "y", PORT_OTHER), spec.outputPorts(item("labels" to listOf("x", "y"))))
        assertEquals(listOf("other", "x"), spec.outputPorts(item("labels" to listOf("other", "x"))))          // static port not duplicated
        assertEquals(listOf(MAIN), NodeSpec("a.b", "x", NodeKind.LOGIC, "d").outputPorts(item("labels" to listOf("x"))))
        try { NodeSpec("a.b", "x", NodeKind.LOGIC, "d", params = listOf(labels("l1", "L1", definesPorts = true), labels("l2", "L2", definesPorts = true))); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("at most one")) }
    }
}
