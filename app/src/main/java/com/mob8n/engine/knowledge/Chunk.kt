package com.mob8n.engine.knowledge

/**
 * Paragraph-aware chunking (DESIGN3 §5.3). Pure and deterministic.
 * Paragraphs = blank-line separated blocks and Markdown headings; CSV/JSONL-like blocks (no blank lines) every 40 lines; whole paragraphs are
 * packed greedily up to [SIZE]; a paragraph over [HARD_MAX] is split at sentence ends, then hard-cut at whitespace; every chunk after the first is
 * prefixed with the previous chunk's tail (<= [OVERLAP] chars, cut at a word boundary); chunks under [MIN] chars merge into the previous one.
 * Invariants (ChunkTest): every chunk <= HARD_MAX + overlap; raw chunks concatenate back to the input modulo whitespace; >= 1 chunk for non-blank input.
 */
object Chunk {
    const val SIZE = 800; const val OVERLAP = 100; const val HARD_MAX = 1200; const val MIN = 40; const val MAX_CHUNKS = 3000
    private const val LINES_PER_BLOCK = 40

    fun split(text: String, size: Int = SIZE, overlap: Int = OVERLAP): List<String> {
        val raw = splitRaw(text, size)
        if (raw.size <= 1 || overlap <= 0) return raw
        val out = ArrayList<String>(raw.size)
        out += raw[0]
        for (i in 1 until raw.size) {
            val tail = tail(raw[i - 1], overlap)
            out += if (tail.isEmpty()) raw[i] else "$tail ${raw[i]}"
        }
        return out
    }

    /** Chunks without the overlap prefix; `split` adds the prefixes on top of exactly this list. */
    internal fun splitRaw(text: String, size: Int = SIZE): List<String> {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (t.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        for (p in paragraphs(t)) {
            if (p.length <= HARD_MAX) pieces += p else pieces += splitLong(p, size)
        }
        // greedy pack whole pieces while the chunk stays <= size
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            if (cur.isEmpty()) { cur.append(p); continue }
            if (cur.length + 2 + p.length <= size) cur.append("\n\n").append(p)
            else { chunks += cur.toString(); cur.setLength(0); cur.append(p) }
            if (chunks.size >= MAX_CHUNKS) break
        }
        if (cur.isNotEmpty() && chunks.size < MAX_CHUNKS) chunks += cur.toString()
        // tiny chunks merge into the previous one (a tiny first chunk merges forward)
        val merged = ArrayList<String>(chunks.size)
        for (c in chunks) {
            if (c.length < MIN && merged.isNotEmpty()) merged[merged.size - 1] = merged.last() + "\n\n" + c
            else if (merged.size == 1 && merged[0].length < MIN) merged[0] = merged[0] + "\n\n" + c
            else merged += c
        }
        return merged
    }

    /** The last [overlap] chars of [prev], shortened to start at a word boundary (an empty string when the tail is one long word). */
    internal fun tail(prev: String, overlap: Int): String {
        val n = (overlap - 1).coerceAtLeast(1)          // the joining space counts toward the overlap budget
        if (prev.length <= n) return prev.trim()
        var t = prev.substring(prev.length - n)
        if (!prev[prev.length - n - 1].isWhitespace() && !t[0].isWhitespace()) {
            val ws = t.indexOfFirst { it.isWhitespace() }
            t = if (ws < 0) "" else t.substring(ws)
        }
        return t.trim()
    }

    /**
     * Blank-line blocks, split further before Markdown headings; a lone heading line is attached to the paragraph that follows it so headings
     * lead a chunk instead of trailing one; blocks with many lines (CSV, JSONL, logs) are cut every 40 lines.
     */
    private fun paragraphs(t: String): List<String> {
        val out = ArrayList<String>()
        var pendingHeading: String? = null
        for (block in t.split(BLANK)) {
            val b = block.trim()
            if (b.isEmpty()) continue
            for (part in splitHeadings(b)) {
                if (HEADING.matches(part)) { pendingHeading = pendingHeading?.let { "$it\n\n$part" } ?: part; continue }
                val lines = part.split('\n')
                val pieces = if (lines.size <= LINES_PER_BLOCK) listOf(part) else lines.chunked(LINES_PER_BLOCK).map { it.joinToString("\n").trim() }
                for ((i, piece) in pieces.withIndex()) {
                    out += if (i == 0 && pendingHeading != null) "$pendingHeading\n\n$piece" else piece
                }
                pendingHeading = null
            }
        }
        pendingHeading?.let { out += it }
        return out
    }

    private fun splitHeadings(b: String): List<String> {
        val lines = b.split('\n')
        if (lines.size == 1 || lines.none { HEADING.matches(it) }) return listOf(b)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (l in lines) {
            if (HEADING.matches(l) && cur.isNotEmpty()) { out += cur.toString().trim(); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(l)
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** Sentence-packed pieces <= size; a single sentence longer than size is hard-cut at the last whitespace before size (or at size). */
    private fun splitLong(p: String, size: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in p.split(SENTENCE_END)) {
            val sentence = s.trim()
            if (sentence.isEmpty()) continue
            if (sentence.length > size) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                out += hardCut(sentence, size)
                continue
            }
            if (cur.isEmpty()) cur.append(sentence)
            else if (cur.length + 1 + sentence.length <= size) cur.append(' ').append(sentence)
            else { out += cur.toString(); cur.setLength(0); cur.append(sentence) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun hardCut(s: String, size: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > size) {
            var cut = rest.lastIndexOf(' ', size)
            if (cut < size / 2) cut = size
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private val BLANK = Regex("\n[ \t]*\n")
    private val HEADING = Regex("#{1,6} .*")
    private val SENTENCE_END = Regex("(?<=[.!?。])\\s+")
}
