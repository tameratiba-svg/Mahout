package com.mob8n.engine

import com.mob8n.core.EMPTY
import com.mob8n.core.JSON
import com.mob8n.core.Redaction
import com.mob8n.core.asText
import com.mob8n.engine.db.MessageEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4 V5 / §4.3: what appendMessage does to a row before Room sees it, and messagesTail's window. */
class ChatStoreTest {
    private val secret = "sk-live-0123456789abcdef"
    private fun msg(json: JsonObject, text: String = "t", meta: JsonObject = EMPTY) = ChatMessage(conversationId = "c1", role = json["role"].asText(), json = json, text = text, meta = meta, createdAt = 1)
    private fun enc(o: JsonObject) = JSON.encodeToString(JsonObject.serializer(), o)
    private fun content(o: JsonObject) = o["content"] as JsonArray
    private fun text(b: Any?) = (b as JsonObject)["text"].asText()
    private fun toolResult(id: String, body: String) = buildJsonObject { put("type", "tool_result"); put("tool_use_id", id); put("content", body) }
    private fun image() = buildJsonObject { put("type", "image"); putJsonObject("source") { put("type", "base64"); put("media_type", "image/png"); put("data", "AAAA") } }

    @Test fun redactsSecretValuesAndSecretNamedKeys() {
        val json = buildJsonObject {
            put("role", "assistant")
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", "Using $secret now") })
                add(buildJsonObject { put("type", "tool_use"); put("id", "tu1"); put("name", "data_http"); putJsonObject("input") { put("url", "https://x"); putJsonObject("headers") { put("Authorization", "Bearer abc") } } })
            }
        }
        val meta = buildJsonObject { put("error", "bad key $secret"); put("api_key", "whatever") }
        val out = ChatStore.prepare(msg(json, text = "leak $secret", meta = meta), listOf(secret, "short"))
        val s = enc(out.json)
        assertFalse(s.contains(secret)); assertTrue(s.contains(Redaction.MASK))
        val input = (content(out.json)[1] as JsonObject)["input"] as JsonObject
        assertEquals(Redaction.MASK, (input["headers"] as JsonObject)["Authorization"].asText())
        assertEquals("https://x", input["url"].asText())
        assertEquals("leak ***", out.text)
        assertEquals("bad key ***", out.meta["error"].asText()); assertEquals(Redaction.MASK, out.meta["api_key"].asText())
    }

    @Test fun stripsImagesTopLevelAndInsideToolResults() {
        val json = buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(image())
                add(buildJsonObject { put("type", "text"); put("text", "keep") })
                add(buildJsonObject { put("type", "tool_result"); put("tool_use_id", "tu1"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "shot:") }); add(image()) } })
                add(toolResult("tu2", "plain"))
            }
        }
        val out = ChatStore.prepare(msg(json), emptyList())
        assertFalse(enc(out.json).contains("\"image\""))
        val c = content(out.json)
        assertEquals(4, c.size)
        assertEquals(ChatStore.REMOVED_IMAGE, text(c[0])); assertEquals("keep", text(c[1]))
        val inner = (c[2] as JsonObject)["content"] as JsonArray
        assertEquals("shot:", text(inner[0])); assertEquals(ChatStore.REMOVED_IMAGE, text(inner[1]))
        assertEquals("plain", (c[3] as JsonObject)["content"].asText())
        val plain = buildJsonObject { put("role", "user"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "x") }) } }
        assertTrue("image-free rows keep their identity", ChatStore.stripImages(plain) === plain)
    }

    @Test fun dropsRawEchoThenTruncatesToolResultsWhenOversize() {
        val big = "x".repeat(200 * 1024)
        // 1) small content + huge _oai: only the echo goes
        val withEcho = buildJsonObject { put("role", "assistant"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "hi") }) }; putJsonObject("_oai") { put("reasoning", big + big) } }
        val logs = ArrayList<String>()
        val o1 = ChatStore.prepare(msg(withEcho), emptyList()) { logs += it }
        assertNull(o1.json[ChatStore.RAW_KEY]); assertEquals("hi", text(content(o1.json)[0]))
        assertEquals(listOf("chat: raw echo dropped, row too large"), logs)
        assertTrue(enc(o1.json).length <= ChatStore.MAX_ROW_CHARS)
        // 2) a small _oai survives when the row fits
        val small = buildJsonObject { put("role", "assistant"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "hi") }) }; putJsonObject("_oai") { put("k", "v") } }
        assertEquals("v", (ChatStore.prepare(msg(small), emptyList()).json[ChatStore.RAW_KEY] as JsonObject)["k"].asText())
        // 3) two 200 KB tool results (string + text-block forms): each capped to 8 KB + marker, ids kept
        val results = buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(toolResult("tu1", big))
                add(buildJsonObject { put("type", "tool_result"); put("tool_use_id", "tu2"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", big) }) } })
            }
        }
        val o3 = ChatStore.prepare(msg(results), emptyList())
        assertTrue(enc(o3.json).length <= ChatStore.MAX_ROW_CHARS)
        val c = content(o3.json)
        assertEquals("tu1", (c[0] as JsonObject)["tool_use_id"].asText())
        assertTrue((c[0] as JsonObject)["content"].asText().length < ChatStore.TOOL_RESULT_CAP + 20)
        assertTrue((c[0] as JsonObject)["content"].asText().endsWith("[truncated]"))
        assertEquals("tu2", (c[1] as JsonObject)["tool_use_id"].asText())
        assertTrue(text(((c[1] as JsonObject)["content"] as JsonArray)[0]).length < ChatStore.TOOL_RESULT_CAP + 20)
        // 4) oversize with nothing to truncate: one placeholder block
        val plain = buildJsonObject { put("role", "assistant"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", big + big) }) } }
        val o4 = ChatStore.prepare(msg(plain), emptyList())
        assertEquals(1, content(o4.json).size); assertTrue(text(content(o4.json)[0]).startsWith("(message too large: "))
        assertTrue(enc(o4.json).length <= ChatStore.MAX_ROW_CHARS)
    }

    @Test fun textProjectionCappedAt4K() {
        val out = ChatStore.prepare(msg(buildJsonObject { put("role", "user"); putJsonArray("content") {} }, text = "y".repeat(10_000)), emptyList())
        assertEquals(ChatStore.TEXT_CAP, out.text.length)
    }

    @Test fun messagesTailRespectsMaxCharsOldestFirst() {
        fun row(seq: Int, size: Int) = MessageEntity(seq.toLong(), "c1", seq, "user", "\"" + "a".repeat(size - 2) + "\"", "t", "{}", seq.toLong())
        val newestFirst = listOf(row(5, 100), row(4, 100), row(3, 100), row(2, 100), row(1, 100))
        assertEquals(listOf(3, 4, 5), ChatStore.tail(newestFirst, 300).map { it.seq })
        assertEquals(listOf(3, 4, 5), ChatStore.tail(newestFirst, 350).map { it.seq })
        assertEquals(listOf(1, 2, 3, 4, 5), ChatStore.tail(newestFirst, 100_000).map { it.seq })
        assertEquals("the newest row is always kept even when it alone exceeds the budget", listOf(5), ChatStore.tail(newestFirst, 10).map { it.seq })
        assertEquals(emptyList<ChatMessage>(), ChatStore.tail(emptyList(), 300))
        assertEquals(5, ChatStore.tail(newestFirst, 100).single().id)
    }

    /** The mapper round trip the DAO relies on: json/meta strings <-> JsonObject, unparseable stays EMPTY rather than throwing. */
    @Test fun entityMappersRoundTrip() {
        val m = ChatMessage(7, "c1", 3, "assistant", buildJsonObject { put("role", "assistant"); putJsonArray("content") {} }, "hello", buildJsonObject { put("pending", true) }, 42)
        assertEquals(m, m.toEntity().toRecord())
        val broken = MessageEntity(1, "c1", 0, "note", "not json", "t", "{", 1)
        assertEquals(EMPTY, broken.toRecord().json); assertEquals(EMPTY, broken.toRecord().meta)
        val s = Skill("id", "coding-on-device", "d", "# i", listOf("run_shell", "run_js"), listOf("code"), "preset", true, 1, 2, 3)
        assertEquals(s, s.toEntity().toRecord())
        val u = AiUsageRow(0, 1, "claude", "claude-sonnet-5", "chat", 10, 5, 2, 1, 0.001, false, null, "c1")
        assertEquals(u, u.toEntity().toRecord())
        val c = Conversation("c1", "New chat", 1, 2, "{}", null, "idle", null)
        assertEquals(c, c.toEntity().toRecord())
    }
}
