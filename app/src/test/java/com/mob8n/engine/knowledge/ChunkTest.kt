package com.mob8n.engine.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkTest {
    private fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** raw chunks concatenate back to the input modulo whitespace; overlapped chunks = tail(prev raw) + " " + raw. */
    private fun checkInvariants(text: String, size: Int = Chunk.SIZE, overlap: Int = Chunk.OVERLAP) {
        val raw = Chunk.splitRaw(text, size)
        val out = Chunk.split(text, size, overlap)
        assertEquals(raw.size, out.size)
        assertEquals(norm(text), norm(raw.joinToString(" ")))
        assertEquals(raw.first(), out.first())
        for (i in 1 until out.size) {
            assertTrue("chunk $i too long: ${out[i].length}", out[i].length <= Chunk.HARD_MAX + overlap)
            assertTrue(out[i].endsWith(raw[i]))
            val prefix = out[i].removeSuffix(raw[i]).trimEnd()
            if (prefix.isNotEmpty()) {
                assertTrue("prefix must be a tail of the previous raw chunk", raw[i - 1].endsWith(prefix))
                val before = raw[i - 1].length - prefix.length
                assertTrue("prefix must start at a word boundary", before == 0 || raw[i - 1][before - 1].isWhitespace())
                assertTrue(prefix.length <= overlap)
            }
        }
    }

    @Test fun shortParagraphsBecomeOneChunk() {
        val text = (1..5).joinToString("\n\n") { "Paragraph $it is short and sweet." }
        val out = Chunk.split(text)
        assertEquals(1, out.size)
        assertEquals(text, out[0])
        checkInvariants(text)
    }

    @Test fun longParagraphSplitsAtSentencesWithOverlap() {
        val text = (1..90).joinToString(" ") { "The quick brown fox number $it jumps over the lazy dog while the router reboots." }
        assertTrue(text.length > 5000)
        val out = Chunk.split(text)
        assertTrue(out.size > 4)
        out.forEach { assertTrue("chunk of ${it.length}", it.length <= Chunk.SIZE + Chunk.OVERLAP) }
        for (i in 1 until out.size) assertTrue(out[i].startsWith("The") || out[i].contains("fox"))
        checkInvariants(text)
    }

    @Test fun sentencelessTextIsHardCutAtWhitespace() {
        val text = "word ".repeat(1000).trim()
        val out = Chunk.split(text)
        assertTrue(out.size >= 6)
        out.forEach { assertTrue(it.length <= Chunk.SIZE + Chunk.OVERLAP); assertTrue(!it.startsWith("ord ")) }
        checkInvariants(text)
        val oneToken = Chunk.splitRaw("x".repeat(3000))   // one 3000-char token: cut at size, no infinite loop, nothing lost
        assertEquals(4, oneToken.size)
        assertEquals("x".repeat(3000), oneToken.joinToString(""))
        Chunk.split("x".repeat(3000)).forEach { assertTrue(it.length <= Chunk.HARD_MAX + Chunk.OVERLAP) }
    }

    @Test fun headingsStartChunks() {
        val a = "# Alpha\n\n" + "alpha sentence. ".repeat(40).trim()
        val b = "## Beta\n\n" + "beta sentence. ".repeat(40).trim()
        val raw = Chunk.splitRaw("$a\n$b")   // a heading line without a blank line before it still opens a new paragraph
        assertEquals(2, raw.size)
        assertTrue(raw[0].startsWith("# Alpha")); assertTrue(raw[1].startsWith("## Beta"))
        checkInvariants("$a\n$b")
    }

    @Test fun csvLikeBlocksSplitEvery40Lines() {
        val text = (1..100).joinToString("\n") { "row$it,value$it" }
        val raw = Chunk.splitRaw(text)
        assertTrue(raw.size in 2..3)
        assertTrue(raw[0].startsWith("row1,")); assertTrue(raw.last().endsWith("row100,value100"))
        checkInvariants(text)
    }

    @Test fun tinyTrailingChunkMergesIntoPrevious() {
        val text = "a".repeat(Chunk.SIZE) + "\n\nok."
        val out = Chunk.split(text)
        assertEquals(1, out.size)
        assertTrue(out[0].endsWith("ok."))
        checkInvariants(text)
    }

    @Test fun blankAndDeterministic() {
        assertEquals(emptyList<String>(), Chunk.split(""))
        assertEquals(emptyList<String>(), Chunk.split(" \n\t\n "))
        val text = (1..200).joinToString("\n\n") { "Paragraph $it. " + "Some words here. ".repeat(it % 7 + 1) }
        assertEquals(Chunk.split(text), Chunk.split(text))
        assertTrue(Chunk.split("x").size == 1)
        checkInvariants(text)
    }

    @Test fun maxChunksCap() {
        val text = (0 until 3500).joinToString("\n\n") { "Paragraph $it " + "x".repeat(780) }
        val out = Chunk.split(text)
        assertEquals(Chunk.MAX_CHUNKS, out.size)
    }

    @Test fun tailCutsAtWordBoundary() {
        assertEquals("lazy dog", Chunk.tail("the quick brown fox jumps over the lazy dog", 10))
        assertEquals("", Chunk.tail("abcdefghijklmnopqrstuvwxyz", 10))
        assertEquals("short", Chunk.tail("short", 10))
    }
}
