package com.mob8n.engine.knowledge

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.TreeMap
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * On-device text extraction, zero dependencies (DESIGN3 §5.2). Pure: JVM-tested with fixtures and in-test built docx/xlsx.
 * docx/xlsx stream through `java.util.zip` + `javax.xml.parsers.SAXParser` (present identically on Android and the JVM), so the
 * XML entry is never materialised as a String.
 * // ponytail: SAX text runs only (no headers/footers/comments/text boxes); upgrade = handle w:hdr/w:ftr parts. PDF needs pdfbox-android.
 */
object Extract {
    const val MAX_RAW = 8 * 1024 * 1024; const val MAX_TEXT = 2 * 1024 * 1024; const val TRUNCATED = "\n\n…[truncated at 2 MB]"
    const val MAX_ZIP_ENTRIES = 2000; const val MAX_ENTRY = 32 * 1024 * 1024        // zip-bomb guards (inflated bytes counted while streaming)
    const val PDF_TEXT = "PDF text extraction is not bundled (upgrade: pdfbox-android); share the text or export as .docx/.txt"
    private const val JSON_PRETTY_MAX = 256 * 1024
    private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    val SUPPORTED_MIMES = listOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown", "text/html", "application/xhtml+xml", MIME_DOCX, MIME_XLSX)
    val EXTENSIONS = listOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "html", "htm", "xml", "log", "yaml", "yml", "docx", "xlsx")

    class Result(val text: String, val kind: String /* text|markdown|csv|json|html|docx|xlsx */, val truncated: Boolean)

    private val BY_MIME = mapOf(
        "text/markdown" to "markdown", "text/x-markdown" to "markdown", "text/csv" to "csv", "text/tab-separated-values" to "csv",
        "text/html" to "html", "application/xhtml+xml" to "html", "application/json" to "json", "application/ld+json" to "json",
        MIME_DOCX to "docx", MIME_XLSX to "xlsx", "application/pdf" to "pdf", "application/xml" to "text", "text/xml" to "text",
    )
    private val BY_EXT = mapOf(
        "txt" to "text", "log" to "text", "xml" to "text", "yaml" to "text", "yml" to "text", "md" to "markdown", "markdown" to "markdown",
        "csv" to "csv", "tsv" to "csv", "json" to "json", "jsonl" to "json", "html" to "html", "htm" to "html", "docx" to "docx", "xlsx" to "xlsx", "pdf" to "pdf",
    )

    /** By mime, then by extension; "pdf" for application/pdf|.pdf; null = unsupported (images, audio, binaries). */
    fun kindOf(mime: String?, name: String): String? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        BY_MIME[m]?.let { return it }
        val ext = name.substringAfterLast('.', "").lowercase()
        BY_EXT[ext]?.let { if (ext.isNotEmpty() && name.contains('.')) return it }
        if (m != null && m.startsWith("text/")) return "text"
        return null
    }

    fun supports(mime: String?, name: String): Boolean = kindOf(mime, name).let { it != null && it != "pdf" }

    /** Dispatch + normalisation: CRLF -> LF, >= 3 blank lines -> 2, cap MAX_TEXT + TRUNCATED. */
    fun extract(bytes: ByteArray, mime: String?, name: String): Result {
        val kind = kindOf(mime, name) ?: throw NodeException("$name is not a text document")
        val raw = when (kind) {
            "pdf" -> throw NodeException(PDF_TEXT)
            "docx" -> docx(ByteArrayInputStream(bytes))
            "xlsx" -> xlsx(ByteArrayInputStream(bytes))
            "html" -> html(decode(bytes))
            "json" -> json(decode(bytes))
            "csv" -> csv(decode(bytes))
            else -> decode(bytes)
        }
        var text = normalise(raw)
        val truncated = text.length > MAX_TEXT
        if (truncated) text = text.substring(0, MAX_TEXT) + TRUNCATED
        return Result(text, kind, truncated)
    }

    internal fun normalise(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n').replace(BLANK_RUN, "\n\n").trim()

    /** BOM-aware UTF-8 / UTF-16; when more than 5 % of the decoded chars are U+FFFD the bytes are re-read as ISO-8859-1. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF; val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 3 && b0 == 0xEF && b1 == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.isNotEmpty()) {
            val bad = utf8.count { it == '�' }
            if (bad * 20 > utf8.length) return String(bytes, Charsets.ISO_8859_1)
        }
        return utf8.removePrefix("﻿")
    }

    /** Tag strip: script/style/noscript/template/head dropped, block ends -> newline, other tags -> space, entities decoded, whitespace collapsed. */
    fun html(s: String): String {
        val title = TITLE.find(s)?.groupValues?.get(1)?.let { entities(TAG.replace(it, " ")).trim() }?.takeIf { it.isNotEmpty() }
        var t = COMMENT.replace(s, " ")
        t = DROP_BLOCKS.replace(t, " ")
        t = BLOCK_END.replace(t, "\n")
        t = TAG.replace(t, " ")
        t = entities(t)
        val lines = t.split('\n').map { SPACES.replace(it, " ").trim() }
        val body = normalise(lines.joinToString("\n"))
        return if (title != null && !body.startsWith(title)) "$title\n\n$body" else body
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "bull" to "•", "middot" to "·", "deg" to "°", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "times" to "×", "divide" to "÷",
        "para" to "¶", "sect" to "§", "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "", "zwj" to "",
    )

    internal fun entities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    /** Pretty-printed when <= 256 KB (keys become tokens on their own lines) else raw; invalid JSON -> raw. */
    fun json(s: String): String {
        if (s.length > JSON_PRETTY_MAX) return s
        val el = runCatching { JSON.parseToJsonElement(s) }.getOrNull() ?: return s
        return PRETTY.encodeToString(JsonElement.serializer(), el)
    }

    fun csv(s: String): String = s

    /** word/document.xml streamed through SAX: <w:t> runs, </w:p> -> "\n", <w:tab/> -> "\t", <w:br/> -> "\n", </w:tr> -> "\n". */
    fun docx(zip: InputStream): String {
        val out = StringBuilder()
        val handler = object : SafeHandler() {
            var inText = 0
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (qName) { "w:t" -> inText++; "w:tab" -> out.append('\t'); "w:br", "w:cr" -> out.append('\n') }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) { "w:t" -> inText--; "w:p", "w:tr" -> out.append('\n') }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText > 0) { out.append(ch, start, length); if (out.length > MAX_TEXT + 1024) throw Stop() }
            }
        }
        walkZip(zip) { name, input -> if (name == "word/document.xml") { parse(input, handler); false } else true }
        return out.toString().trimEnd()
    }

    /**
     * Shared strings + every xl/worksheets/sheet*.xml, emitted in numeric order under a "## sheetN.xml" header; cells joined "\t", rows "\n".
     * One pass over the zip: shared-string cells are written as placeholders and resolved at the end (sharedStrings.xml usually FOLLOWS the sheets).
     */
    fun xlsx(zip: InputStream): String {
        val shared = ArrayList<String>()
        val sheets = TreeMap<Int, StringBuilder>()
        var total = 0
        val sstHandler = object : SafeHandler() {
            var inSi = false; var inT = 0; val cur = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (qName) { "si" -> { inSi = true; cur.setLength(0) }; "t" -> inT++ }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) { "si" -> { inSi = false; shared += cur.toString() }; "t" -> inT-- }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inSi && inT > 0) cur.append(ch, start, length) }
        }
        fun sheetHandler(sb: StringBuilder) = object : SafeHandler() {
            var cellType: String? = null; var inV = 0; var inIsT = 0; var inIs = false; val cell = StringBuilder(); val row = ArrayList<String>()
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (qName) {
                    "row" -> row.clear()
                    "c" -> { cellType = attributes?.getValue("t"); cell.setLength(0) }
                    "v" -> inV++
                    "is" -> inIs = true
                    "t" -> if (inIs) inIsT++
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "v" -> inV--
                    "is" -> inIs = false
                    "t" -> if (inIs) inIsT--
                    "c" -> row += if (cellType == "s") "$cell" else cell.toString()
                    "row" -> {
                        val line = row.joinToString("\t").trimEnd()
                        sb.append(line).append('\n'); total += line.length + 1
                        if (total > MAX_TEXT + 1024) throw Stop()
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inV > 0 || inIsT > 0) cell.append(ch, start, length) }
        }
        walkZip(zip) { name, input ->
            when {
                name == "xl/sharedStrings.xml" -> parse(input, sstHandler)
                name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> {
                    val n = name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: (1000 + sheets.size)
                    if (total <= MAX_TEXT + 1024) parse(input, sheetHandler(sheets.getOrPut(n) { StringBuilder() }))
                }
            }
            true
        }
        val out = StringBuilder()
        for ((n, sb) in sheets) {
            out.append("## sheet").append(n).append(".xml\n")
            out.append(PLACEHOLDER.replace(sb) { m -> m.groupValues[1].toIntOrNull()?.let { shared.getOrNull(it) } ?: "" })
            out.append('\n')
        }
        return out.toString().trimEnd()
    }

    /** Non-validating, namespace-unaware (qNames like "w:t"); external entities/DTDs never fetched (features + an empty EntityResolver). */
    internal fun sax(): SAXParser {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        // Android's Expat-based factory rejects some of these names, hence one runCatching each; the SafeHandler resolver is the belt to these braces.
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return f.newSAXParser()
    }

    // ---- internals ----

    /** Every external entity / DTD resolves to an empty document: nothing is fetched from disk or network. */
    private open class SafeHandler : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
    }

    /** Thrown by a handler once enough text is collected; parsing stops, the collected text is kept. */
    private class Stop : SAXException("enough text")

    private fun parse(input: InputStream, handler: DefaultHandler) {
        try {
            sax().parse(NoClose(input), handler)
        } catch (_: Stop) {
        } catch (e: NodeException) {
            throw e
        } catch (e: Exception) {
            throw NodeException("Cannot read the document's XML: ${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
    }

    /** Iterates entries (<= MAX_ZIP_ENTRIES); `visit` returns false to stop. The inflated bytes of visited entries are capped at MAX_ENTRY. */
    private fun walkZip(zip: InputStream, visit: (name: String, input: InputStream) -> Boolean) {
        val z = ZipInputStream(zip)
        var count = 0
        try {
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > MAX_ZIP_ENTRIES) throw NodeException("Archive has more than $MAX_ZIP_ENTRIES entries")
                if (e.isDirectory) continue
                if (!visit(e.name, Capped(z, MAX_ENTRY))) break
            }
        } catch (e: NodeException) {
            throw e
        } catch (e: Exception) {
            throw NodeException("Cannot read the document archive: ${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
    }

    private class NoClose(input: InputStream) : FilterInputStream(input) { override fun close() {} }

    private class Capped(input: InputStream, private val cap: Int) : FilterInputStream(input) {
        private var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }
        private fun count(k: Int) { n += k; if (n > cap) throw NodeException("Archive entry larger than ${cap / (1024 * 1024)} MB") }
        override fun close() {}
    }

    private val PRETTY = Json(JSON) { prettyPrint = true }
    private val BLANK_RUN = Regex("\n[ \t]*\n(?:[ \t]*\n)+")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val TITLE = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
    private val DROP_BLOCKS = Regex("(?is)<(script|style|noscript|template|head)\\b[^>]*>.*?</\\1\\s*>")
    private val BLOCK_END = Regex("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6]|tr|blockquote|pre|section|article|ul|ol|table)\\s*>|<(?:p|li|tr|h[1-6])\\b[^>]*>")
    private val TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,10});")
    private val SPACES = Regex("[ \t  -​]+")
    private val PLACEHOLDER = Regex("([0-9]+)")
}
