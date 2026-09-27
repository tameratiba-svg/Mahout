package com.mob8n.ui

import com.mob8n.ui.MdBlock.Bullet
import com.mob8n.ui.MdBlock.Code
import com.mob8n.ui.MdBlock.Heading
import com.mob8n.ui.MdBlock.Paragraph
import com.mob8n.ui.MdBlock.Quote
import com.mob8n.ui.MdBlock.Rule
import com.mob8n.ui.MdBlock.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** DESIGN6 §5.3.4: block parser for chat markdown. */
class MarkdownBlocksTest {
    @Test fun headingsAndParagraphs() {
        assertEquals(listOf(Heading(1, "Title"), Paragraph("line one\nline two"), Heading(2, "Sub"), Heading(3, "Deep"), Paragraph("end")),
            parseMarkdown("# Title\nline one\nline two\n\n## Sub\n#### Deep\nend"))
    }

    @Test fun nestedBulletsAndOrderedLists() {
        val md = "- a\n  - b\n    - c\n- d\n1. one\n2) two\n   continued"
        assertEquals(listOf(Bullet(0, "•", "a"), Bullet(1, "•", "b"), Bullet(2, "•", "c"), Bullet(0, "•", "d"), Bullet(0, "1.", "one"), Bullet(0, "2)", "two continued")), parseMarkdown(md))
        // 4-space nesting is still one level
        assertEquals(listOf(Bullet(0, "•", "a"), Bullet(1, "•", "b")), parseMarkdown("* a\n    * b"))
    }

    @Test fun quotesAndRules() {
        assertEquals(listOf(Quote("said this\nand that"), Rule, Paragraph("after")), parseMarkdown("> said this\n>and that\n---\nafter"))
    }

    @Test fun pipeTableWithRaggedRowsPaddedAndAlignmentRowSkipped() {
        val md = "| Node | Status | ms |\n|:---|:---:|---:|\n| data.http | TIMEOUT | 30000 |\n| notify | ok |\nafter"
        assertEquals(listOf(Table(listOf("Node", "Status", "ms"), listOf(listOf("data.http", "TIMEOUT", "30000"), listOf("notify", "ok", ""))), Paragraph("after")), parseMarkdown(md))
        // without outer pipes
        assertEquals(listOf(Table(listOf("a", "b"), listOf(listOf("1", "2")))), parseMarkdown("a | b\n--- | ---\n1 | 2"))
    }

    @Test fun inlineCodeInsideTableCells() {
        val t = parseMarkdown("| cmd | note |\n|---|---|\n| `a|b` | uses \\| pipe |").single() as Table
        assertEquals(listOf(listOf("`a|b`", "uses | pipe")), t.rows)
        assertEquals("a|b", markdownToAnnotated(t.rows[0][0]).text)
    }

    @Test fun fencedCodeWithLanguage() {
        assertEquals(listOf(Paragraph("before"), Code("json", "{ \"a\": 1 }\n  indented", true), Paragraph("after")),
            parseMarkdown("before\n```json\n{ \"a\": 1 }\n  indented\n```\nafter"))
        assertEquals(listOf(Code(null, "x", true)), parseMarkdown("~~~\nx\n~~~"))
    }

    @Test fun unclosedFenceIsOpenCode() {
        assertEquals(listOf(Paragraph("Here:"), Code("kotlin", "val x = 1\nval y", false)), parseMarkdown("Here:\n```kotlin\nval x = 1\nval y"))
        assertEquals(listOf(Code(null, "", false)), parseMarkdown("```"))
    }

    @Test fun neverThrowsOnRandomInput() {
        val alphabet = "ab #*-_>|`~:.1)\n\t [](){}\\"
        val r = Random(42)
        repeat(1_000) {
            val s = String(CharArray(r.nextInt(0, 80)) { alphabet[r.nextInt(alphabet.length)] })
            val blocks = parseMarkdown(s)
            assertTrue(blocks.all { it !is Table || it.rows.all { row -> row.size == it.header.size } })
        }
    }

    @Test fun emptyInput() { assertEquals(emptyList<MdBlock>(), parseMarkdown("")) }
}
