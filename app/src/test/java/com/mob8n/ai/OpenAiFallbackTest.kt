package com.mob8n.ai

import com.mob8n.core.NodeException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** DESIGN6 D7: the streaming fallback policy and the two new 400-downgrade quirks. */
class OpenAiFallbackTest {
    private val openai = Providers.byId("openai")!!

    @Test fun policy() {
        assertEquals(Fallback.CACHE_AND_REDO, streamFailureAction(false, IOException("reset")))
        assertEquals(Fallback.CACHE_AND_REDO, streamFailureAction(false, StreamFailure("stream ended early")))
        assertEquals(Fallback.RESET_AND_REDO, streamFailureAction(true, IOException("reset")))
        assertEquals(Fallback.RESET_AND_REDO, streamFailureAction(true, java.net.SocketTimeoutException("idle")))
        assertEquals(Fallback.THROW, streamFailureAction(false, NodeException("OpenAI error during streaming: x")))
        assertEquals(Fallback.THROW, streamFailureAction(true, CancellationException("cancelled by user")))
    }

    private fun body(stream: Boolean, quirks: Set<String> = emptySet()) =
        OpenAiCompat.buildBody(openai, "m", 100, null, JsonArray(emptyList()), emptyList(), false, null, false, quirks, stream)

    @Test fun streamingBodyAsksForUsageUnlessRejected() {
        val b = body(true)
        assertEquals(JsonPrimitive(true), b["stream"])
        assertEquals(JsonPrimitive(true), (b["stream_options"] as JsonObject)["include_usage"])
        assertEquals(setOf("stream", "stream_options"), OpenAiCompat.sentFeatures(b).intersect(setOf("stream", "stream_options")))
        assertFalse(body(true, setOf("no_stream_options")).containsKey("stream_options"))
        val plain = body(false)
        assertEquals(JsonPrimitive(false), plain["stream"]); assertFalse(plain.containsKey("stream_options"))
        assertTrue(OpenAiCompat.sentFeatures(plain).intersect(setOf("stream", "stream_options")).isEmpty())
    }

    @Test fun quirkFor400LearnsStreamQuirksOnlyWhenSent() {
        val both = setOf("stream", "stream_options", "max_tokens")
        assertEquals("no_stream_options", OpenAiCompat.quirkFor400("""{"error":{"message":"Unrecognized request argument supplied: stream_options"}}""", both))
        assertEquals("no_stream", OpenAiCompat.quirkFor400("""{"error":{"message":"'stream' is not supported with this model"}}""", both))
        assertNull(OpenAiCompat.quirkFor400("""{"error":{"message":"'stream' is not supported"}}""", setOf("max_tokens")))
        assertNull(OpenAiCompat.quirkFor400("""{"error":{"message":"stream_options not allowed"}}""", setOf("max_tokens")))
        assertNull(OpenAiCompat.quirkFor400("""{"error":{"message":"Upstream error from provider"}}""", both))   // not the word "stream"
    }
}
