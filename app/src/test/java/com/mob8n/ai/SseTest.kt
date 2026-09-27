package com.mob8n.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** DESIGN6 §9: SSE framing + the streamVisible display filter. */
class SseTest {
    private fun ev(s: String) = Sse.events(s.lineSequence()).toList()

    @Test fun blankLineEndsAnEvent() = assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), ev("data: {\"a\":1}\n\ndata: {\"b\":2}\n\n"))
    @Test fun crlfTolerated() = assertEquals(listOf("x", "y"), ev("data: x\r\n\r\ndata: y\r\n\r\n"))
    @Test fun multiLineDataJoinedWithNewline() = assertEquals(listOf("line1\nline2"), ev("data: line1\ndata: line2\n\n"))
    @Test fun commentsAndOtherFieldsIgnored() =
        assertEquals(listOf("p"), ev(": OPENROUTER PROCESSING\n\nevent: message\nid: 7\nretry: 100\ndata: p\n\n: keep-alive\n\n"))
    @Test fun trailingEventWithoutBlankLine() = assertEquals(listOf("a", "last"), ev("data: a\n\ndata: last"))
    @Test fun doneReturnedAsIs() = assertEquals(listOf("x", "[DONE]"), ev("data: x\n\ndata: [DONE]\n\n"))
    @Test fun noSpaceAfterColonAndEmptyData() = assertEquals(listOf("x", ""), ev("data:x\n\ndata\n\n"))

    @Test fun streamVisibleStripsThinking() {
        assertEquals("x", streamVisible("<think>abc</think>x"))
        assertEquals("x", streamVisible("</think>x"))
        assertEquals("x", streamVisible("x<think>partial"))
        assertEquals("x", streamVisible("<mm:think>…</mm:think>x"))
        assertEquals("", streamVisible("</th"))                  // a closer still arriving stays hidden
        assertEquals("x", streamVisible("x</mm:think"))          // MiniMax-M3 lone closer cut by an SSE chunk (9 chars after '<')
        assertEquals("a < b", streamVisible("a < b"))
    }
}
