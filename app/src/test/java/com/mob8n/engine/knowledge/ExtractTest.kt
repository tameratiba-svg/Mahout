package com.mob8n.engine.knowledge

import com.mob8n.core.NodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractTest {
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("knowledge/$name")?.use { it.readBytes() } ?: error("missing fixture knowledge/$name")

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, body) in entries) { z.putNextEntry(ZipEntry(n)); z.write(body.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    @Test fun txtPassesThroughWithBomStrippedAndCrlfNormalised() {
        val r = Extract.extract(fixture("sample.txt"), "text/plain", "sample.txt")
        assertEquals("text", r.kind)
        assertTrue(r.text.startsWith("Refund policy\nItems can be returned"))
        assertFalse(r.text.contains('\r'))
        assertFalse(r.text.contains('﻿'))
        assertFalse(r.truncated)
    }

    @Test fun markdownCsvJsonKinds() {
        val md = Extract.extract(fixture("sample.md"), "application/octet-stream", "sample.md")   // Drive reports octet-stream for .md: extension wins
        assertEquals("markdown", md.kind); assertTrue(md.text.startsWith("# Warranty"))
        val csv = Extract.extract(fixture("sample.csv"), "text/csv", "sample.csv")
        assertEquals("csv", csv.kind); assertEquals(String(fixture("sample.csv")).trim(), csv.text)
        val json = Extract.extract(fixture("sample.json"), "application/json", "sample.json")
        assertEquals("json", json.kind)
        assertTrue("pretty-printed keys become their own tokens", json.text.lines().any { it.trim().startsWith("\"warranty_months\": 24") })
        assertTrue(json.text.contains("hold the reset button"))
    }

    @Test fun invalidJsonStaysRaw() { assertEquals("{not json", Extract.json("{not json")) }

    @Test fun htmlIsStrippedAndDecoded() {
        val r = Extract.extract(fixture("sample.html"), "text/html", "sample.html")
        val t = r.text
        assertEquals("html", r.kind)
        assertTrue(t.lines().first() == "Document provider guide")
        assertFalse(t.contains('<')); assertFalse(t.contains('>'))
        assertFalse(t.contains("SCRIPT_TEXT")); assertFalse(t.contains("color: red")); assertFalse(t.contains("NOSCRIPT_TEXT")); assertFalse(t.contains("a comment"))
        assertTrue(t.contains("Open files with ACTION_OPEN_DOCUMENT"))
        assertTrue(t.contains("Use SAF & persistable grants."))
        assertTrue(t.lines().any { it == "Second line after a break." })
        assertTrue(t.lines().any { it == "First item" }); assertTrue(t.lines().any { it == "Second item" })
        assertTrue(t.contains("Copyright © 2026 Android"))
    }

    @Test fun docxTextRunsTabsAndParagraphs() {
        val doc = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t xml:space="preserve">Hello</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>World</w:t></w:r></w:p>
            <w:p><w:r><w:t>Second</w:t></w:r></w:p>
            </w:body></w:document>"""
        val bytes = zip("[Content_Types].xml" to "<Types/>", "word/document.xml" to doc, "word/styles.xml" to "<w:styles/>")
        assertEquals("Hello\tWorld\nSecond", Extract.docx(ByteArrayInputStream(bytes)))
        val r = Extract.extract(bytes, DOCX, "a.docx")
        assertEquals("docx", r.kind); assertEquals("Hello\tWorld\nSecond", r.text)
        assertEquals("docx", Extract.kindOf(null, "report.DOCX"))
    }

    @Test fun xlsxSharedInlineAndNumericCells() {
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>Apple</t></is></c><c r="B2"><v>3</v></c></row>
            </sheetData></worksheet>"""
        val sst = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2"><si><t>Name</t></si><si><r><t>Q</t></r><r><t>ty</t></r></si></sst>"""
        // sharedStrings AFTER the sheet, as Excel writes it: the one-pass reader must still resolve the references
        val bytes = zip("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to sheet, "xl/sharedStrings.xml" to sst)
        assertEquals("## sheet1.xml\nName\tQty\nApple\t3", Extract.xlsx(ByteArrayInputStream(bytes)))
        assertEquals("xlsx", Extract.extract(bytes, XLSX, "q.xlsx").kind)
    }

    @Test fun pdfIsRecognisedButUnsupported() {
        assertEquals("pdf", Extract.kindOf("application/pdf", "x"))
        assertEquals("pdf", Extract.kindOf(null, "manual.pdf"))
        assertFalse(Extract.supports(null, "manual.pdf"))
        try { Extract.extract(byteArrayOf(0x25, 0x50, 0x44, 0x46), "application/pdf", "manual.pdf"); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("pdfbox")) }
    }

    @Test fun unsupportedKindsAreNull() {
        assertNull(Extract.kindOf("image/png", "photo.png")); assertNull(Extract.kindOf(null, "clip.mp3")); assertNull(Extract.kindOf("application/octet-stream", "blob"))
        assertEquals("text", Extract.kindOf("text/plain", "noext")); assertEquals("text", Extract.kindOf("text/x-log", "server.log"))
        try { Extract.extract(ByteArray(4), "image/png", "photo.png"); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("not a text document")) }
    }

    @Test fun largeTextIsCappedWithMarker() {
        val big = ("lorem ipsum dolor sit amet. ".repeat(40) + "\n").repeat(2800).toByteArray()   // ≈ 3.1 MB
        assertTrue(big.size > Extract.MAX_TEXT)
        val r = Extract.extract(big, "text/plain", "big.txt")
        assertTrue(r.truncated)
        assertTrue(r.text.endsWith(Extract.TRUNCATED))
        assertEquals(Extract.MAX_TEXT + Extract.TRUNCATED.length, r.text.length)
    }

    @Test fun latin1FallbackWhenUtf8Garbles() {
        val s = "café crème brûlée ".repeat(30)
        assertEquals(s, Extract.decode(s.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(s, Extract.decode(s.toByteArray(Charsets.UTF_8)))
        assertEquals("héllo", Extract.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "héllo".toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun zipBombEntryCountRejected() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> repeat(3000) { i -> z.putNextEntry(ZipEntry("e$i.xml")); z.write("<a/>".toByteArray()); z.closeEntry() } }
        try { Extract.extract(bos.toByteArray(), DOCX, "bomb.docx"); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("entries")) }
    }

    @Test fun doctypeWithExternalEntitiesIsIgnoredNotFetched() {
        val doc = """<?xml version="1.0"?>
            <!DOCTYPE w:document SYSTEM "file:///nonexistent/mob8n-external.dtd" [<!ENTITY ext SYSTEM "file:///nonexistent/mob8n-xxe.txt">]>
            <w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>Safe &ext; text</w:t></w:r></w:p></w:body></w:document>"""
        val text = Extract.docx(ByteArrayInputStream(zip("word/document.xml" to doc)))
        assertTrue(text, text.contains("Safe") && text.contains("text"))
        assertFalse(text.contains("nonexistent"))
    }

    @Test fun blankRunsCollapseAndSupportedListsAreSane() {
        assertEquals("a\n\nb", Extract.normalise("a\r\n\r\n\r\n\r\nb\n\n"))
        assertTrue(Extract.SUPPORTED_MIMES.contains("text/*") && Extract.SUPPORTED_MIMES.contains(DOCX))
        assertTrue(Extract.EXTENSIONS.containsAll(listOf("md", "docx", "xlsx", "csv")))
        assertEquals("<b> & \"q\" 'a' ©", Extract.entities("&lt;b&gt; &amp; &quot;q&quot; &apos;a&apos; &#xA9;"))
    }
}
