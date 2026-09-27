package com.mob8n.engine.knowledge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/**
 * Query tokenising, FTS4 MATCH expressions and BM25 over `matchinfo(knowledge_chunks, 'pcxnal')` blobs (DESIGN3 §5.5). Pure.
 * Standard FTS query syntax only (implicit AND, `OR`, prefix `*`): no parentheses, no AND/NOT keywords (OEM SQLite builds may lack
 * SQLITE_ENABLE_FTS3_PARENTHESIS).
 * // ponytail: keyword FTS + BM25 over <= 2000 MATCH rows; upgrade = embeddings (hybrid rank, second table)
 */
object Bm25 {
    const val K1 = 1.2; const val B = 0.75; const val CANDIDATES = 2000; const val TEXT_COL = 2   // columns: 0 sourceId, 1 seq, 2 text
    private const val MAX_TOKENS = 12
    private val STOP = setOf("or", "and", "not", "near")
    private val SPLIT = Regex("[^\\p{L}\\p{N}_]+")

    /** lowercase, split on non-letter/digit/underscore, drop < 2 or > 40 chars and FTS keywords, distinct, first 12. */
    fun tokens(q: String): List<String> =
        q.lowercase().split(SPLIT).filter { it.length in 2..40 && it !in STOP }.distinct().take(MAX_TOKENS)

    /** Tokens of >= 3 chars get a prefix `*`; joined by " " (implicit AND) or " OR "; null when the query has no usable token. */
    fun matchExpr(q: String, orMode: Boolean = false): String? {
        val t = tokens(q)
        if (t.isEmpty()) return null
        return t.map { if (it.length >= 3) "$it*" else it }.joinToString(if (orMode) " OR " else " ")
    }

    /** matchinfo is "machine byte-order" (little-endian on every Android ABI and the test JVM). */
    fun ints(mi: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(mi).order(ByteOrder.nativeOrder()).asIntBuffer()
        val out = IntArray(buf.remaining())
        buf.get(out)
        return out
    }

    /**
     * Layout 'pcxnal': [0]=p phrases, [1]=c columns, then 3*p*c ints (phrase i, column j at 2+3*(i*c+j): hitsRow, hitsAll, docsWith),
     * then n = [2+3pc], a[c] average tokens per column, l[c] tokens of this row per column.
     * score = Σ_i (hits>0) idf * hits*(K1+1) / (hits + K1*(1 - B + B*l/max(a,1))), idf = ln((n - df + 0.5)/(df + 0.5) + 1).
     */
    fun score(mi: IntArray, col: Int = TEXT_COL): Double {
        if (mi.size < 2) return 0.0
        val p = mi[0]; val c = mi[1]
        if (p <= 0 || c <= 0 || col >= c) return 0.0
        val nIdx = 2 + 3 * p * c
        if (mi.size < nIdx + 1 + 2 * c) return 0.0
        val n = mi[nIdx].toDouble()
        val avg = max(mi[nIdx + 1 + col].toDouble(), 1.0)
        val len = mi[nIdx + 1 + c + col].toDouble()
        var s = 0.0
        for (i in 0 until p) {
            val base = 2 + 3 * (i * c + col)
            val hits = mi[base].toDouble()
            if (hits <= 0.0) continue
            val df = mi[base + 2].toDouble()
            val idf = ln((n - df + 0.5) / (df + 0.5) + 1.0)
            s += idf * hits * (K1 + 1) / (hits + K1 * (1 - B + B * len / avg))
        }
        return s
    }
}
