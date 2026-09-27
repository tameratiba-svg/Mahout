package com.mob8n.engine.knowledge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

class Bm25Test {
    @Test fun matchExprAndMode() {
        assertEquals("how* do reset* the* router*", Bm25.matchExpr("How do I reset the router?"))   // "i" < 2 chars dropped; 2-char tokens keep no prefix
        assertEquals("battery* OR 80 OR delete*", Bm25.matchExpr("Battery: 80% OR delete", orMode = true))   // "or" is a STOP token
        assertEquals("battery* 80 delete*", Bm25.matchExpr("Battery: 80% OR delete"))
        assertNull(Bm25.matchExpr("!!"))
        assertNull(Bm25.matchExpr("a or and not near"))
    }

    @Test fun tokensDistinctCappedAndUnicode() {
        val q = (1..30).joinToString(" ") { "word$it" }
        assertEquals(12, Bm25.tokens(q).size)
        assertEquals(listOf("router"), Bm25.tokens("router ROUTER Router"))
        assertEquals("résumé*", Bm25.matchExpr("résumé"))
        assertEquals(listOf("日本語"), Bm25.tokens("日本語"))
        assertTrue(Bm25.tokens("x".repeat(41)).isEmpty())
    }

    /** 'pcxnal' with p phrases, c = 3 columns (only column 2 indexed): x = 3*p*c ints, then n, a[c], l[c]. */
    private fun mi(hits: IntArray, docs: IntArray, n: Int, avg: Int, len: Int): IntArray {
        val p = hits.size; val c = 3
        val out = ArrayList<Int>()
        out += p; out += c
        for (i in 0 until p) for (j in 0 until c) { if (j == Bm25.TEXT_COL) { out += hits[i]; out += hits[i] * 3; out += docs[i] } else { out += 0; out += 0; out += 0 } }
        out += n
        out += 0; out += 0; out += avg
        out += 0; out += 0; out += len
        return out.toIntArray()
    }

    private fun closed(hits: Int, df: Int, n: Int, avg: Int, len: Int): Double {
        val idf = ln((n - df + 0.5) / (df + 0.5) + 1)
        return idf * hits * (Bm25.K1 + 1) / (hits + Bm25.K1 * (1 - Bm25.B + Bm25.B * len.toDouble() / avg))
    }

    @Test fun scoreMatchesClosedFormAndOrders() {
        val base = mi(intArrayOf(2, 1), intArrayOf(50, 5), 1000, 120, 100)
        val expected = closed(2, 50, 1000, 120, 100) + closed(1, 5, 1000, 120, 100)
        assertTrue(abs(Bm25.score(base) - expected) < 1e-9)
        // higher tf -> higher
        assertTrue(Bm25.score(mi(intArrayOf(4, 1), intArrayOf(50, 5), 1000, 120, 100)) > Bm25.score(base))
        // rarer term -> higher
        assertTrue(Bm25.score(mi(intArrayOf(2, 1), intArrayOf(5, 5), 1000, 120, 100)) > Bm25.score(base))
        // longer doc -> lower at equal tf
        assertTrue(Bm25.score(mi(intArrayOf(2, 1), intArrayOf(50, 5), 1000, 120, 400)) < Bm25.score(base))
        // a phrase with no hit in the row contributes 0
        assertTrue(abs(Bm25.score(mi(intArrayOf(2, 0), intArrayOf(50, 5), 1000, 120, 100)) - closed(2, 50, 1000, 120, 100)) < 1e-9)
        // non-indexed columns contribute 0
        assertEquals(0.0, Bm25.score(base, col = 0), 0.0); assertEquals(0.0, Bm25.score(base, col = 1), 0.0)
        // malformed blobs never throw
        assertEquals(0.0, Bm25.score(IntArray(0)), 0.0); assertEquals(0.0, Bm25.score(intArrayOf(2, 3, 1)), 0.0)
    }

    @Test fun intsRoundTripInNativeOrder() {
        val src = mi(intArrayOf(3), intArrayOf(7), 42, 90, 88)
        val buf = ByteBuffer.allocate(src.size * 4).order(ByteOrder.nativeOrder())
        src.forEach { buf.putInt(it) }
        assertArrayEquals(src, Bm25.ints(buf.array()))
        assertEquals(0, Bm25.ints(ByteArray(0)).size)
    }
}
