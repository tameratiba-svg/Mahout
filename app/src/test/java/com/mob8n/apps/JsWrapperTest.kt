package com.mob8n.apps

import com.mob8n.core.EMPTY
import com.mob8n.core.JSON
import com.mob8n.core.item
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of JsRuntime (§7.2). // ponytail: no JS engine on the JVM; execution is device plan step 8 */
class JsWrapperTest {
    private fun enc(s: String) = JSON.encodeToString(JsonElement.serializer(), JsonPrimitive(s))

    @Test fun wrapperEmbedsJsonEncodedIdInputAndCode() {
        val code = "const s = \"a\\nb\";\nreturn `x\${item.v}` + '</script>' + s; // 'quotes' \"more\""
        val input = """{"item":{"v":1},"items":[{"v":1}],"vars":{},"mode":"single"}"""
        val w = JsRuntime.wrapper("call-1", code, input)
        assertTrue(w.contains("ID=" + enc("call-1")))
        assertTrue(w.contains("var IN=$input;"))
        assertTrue(w.contains("\"\$index\",\"\$count\"," + enc(code) + ")"))          // code only ever appears as a JSON string
        assertFalse(w.contains("\n" + code))                                            // never raw
        assertTrue(w.contains("new AF("))
        assertTrue(w.contains("try{var AF=Object.getPrototypeOf(async function(){}).constructor;fn=new AF(") && w.contains("}catch(e){fail(e);return;}"))
        for (g in listOf("fetch", "XMLHttpRequest", "WebSocket", "EventSource", "Worker", "SharedWorker", "importScripts", "RTCPeerConnection", "Image")) assertTrue(g, w.contains("\"$g\""))
        assertTrue(w.contains("navigator.sendBeacon=undefined"))
        assertTrue(w.contains("if(IN.mode===\"per_item\")"))
        assertTrue(w.contains("LOGS.length<${JsRuntime.MAX_LOG_LINES}"))
        assertTrue(w.trimStart().startsWith("(function(){\"use strict\";")); assertTrue(w.trimEnd().endsWith("})();"))
    }

    @Test fun wrapperIsBalancedOutsideStrings() {
        val w = JsRuntime.wrapper("id", "return 1;", "{}")
        // strip the JSON string literals (code/id) before counting: user code may contain unbalanced braces
        val stripped = w.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
        for ((o, c) in listOf('(' to ')', '{' to '}', '[' to ']')) assertEquals("$o$c", stripped.count { it == o }, stripped.count { it == c })
        val nasty = JsRuntime.wrapper("id", "return {{{ ((( [[[ '\"' ;", "{}").replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
        for ((o, c) in listOf('(' to ')', '{' to '}', '[' to ']')) assertEquals("$o$c nasty", nasty.count { it == o }, nasty.count { it == c })
    }

    @Test fun parseDoneOkErrorLogsOversizeMalformed() {
        val ok = JsRuntime.parseDone("""{"ok":true,"value":{"a":1},"logs":["l1","l2"]}""").getOrThrow()
        assertEquals(1, ok.first.jsonObject["a"]!!.jsonPrimitive.content.toInt()); assertEquals(listOf("l1", "l2"), ok.second)
        val nul = JsRuntime.parseDone("""{"ok":true,"value":null,"logs":[]}""").getOrThrow()
        assertEquals(JsonNull, nul.first)
        val err = JsRuntime.parseDone("""{"ok":false,"error":"ReferenceError: x is not defined","logs":["before"]}""")
        assertTrue(err.isFailure); assertTrue(err.exceptionOrNull()!!.message!!.contains("ReferenceError"))
        val big = JsRuntime.parseDone("""{"ok":true,"value":"${"x".repeat(JsRuntime.MAX_RESULT)}","logs":[]}""")
        assertTrue(big.isFailure); assertTrue(big.exceptionOrNull()!!.message!!.contains("too large"))
        assertTrue(JsRuntime.parseDone("not json").isFailure)
        assertTrue(JsRuntime.parseDone("[1,2]").isFailure)
        val many = JsRuntime.parseDone("""{"ok":true,"value":1,"logs":[${(1..300).joinToString(",") { "\"$it\"" }}]}""").getOrThrow()
        assertEquals(JsRuntime.MAX_LOG_LINES, many.second.size)
    }

    @Test fun bridgeResultBothShapes() {
        assertEquals("""{"ok":[{"a":1}]}""", JsRuntime.bridgeResult(Result.success(JSON.parseToJsonElement("""[{"a":1}]"""))))
        assertEquals("""{"ok":null}""", JsRuntime.bridgeResult(Result.success(JsonNull)))
        assertEquals("""{"error":"node x is not allowed"}""", JsRuntime.bridgeResult(Result.failure(IllegalStateException("node x is not allowed"))))
    }

    @Test fun inputJsonShapePerMode() {
        val items = listOf(item("a" to 1), item("a" to 2))
        for (mode in listOf("single", "per_item", "all_items")) {
            val o = JSON.parseToJsonElement(JsRuntime.inputJson(items[0], items, mapOf("k" to JsonPrimitive("v")), mode)).jsonObject
            assertEquals(setOf("item", "items", "vars", "mode"), o.keys)
            assertEquals(mode, o["mode"]!!.jsonPrimitive.content)
            assertEquals(2, o["items"]!!.jsonArray.size); assertEquals("1", o["item"]!!.jsonObject["a"]!!.jsonPrimitive.content)
            assertEquals("v", o["vars"]!!.jsonObject["k"]!!.jsonPrimitive.content)
        }
        val empty = JSON.parseToJsonElement(JsRuntime.inputJson(EMPTY, emptyList(), emptyMap(), "single")).jsonObject
        assertEquals(JsonObject(emptyMap()), empty["item"]); assertEquals(0, empty["items"]!!.jsonArray.size)
    }

    @Test fun limitsAndStatusDefaults() {
        assertEquals(256 * 1024, JsRuntime.MAX_CODE); assertEquals(512 * 1024, JsRuntime.MAX_INPUT); assertEquals(1024 * 1024, JsRuntime.MAX_RESULT)
        assertEquals(120_000L, JsRuntime.MAX_TIMEOUT_MS); assertEquals(120_000L, JsRuntime.IDLE_DESTROY_MS); assertEquals(60_000L, JsRuntime.BRIDGE_CALL_MS)
        assertEquals("idle", JsRuntime.status.value)
    }
}
