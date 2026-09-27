package com.mob8n.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN4 §11 ui: the minimal renderer produces the expected spans and plain text and never throws. */
class MarkdownTest {
    private val s = MdStyle.DEFAULT
    private fun spans(md: String) = markdownToAnnotated(md, s).let { a -> a.spanStyles.map { Triple(a.text.substring(it.start, it.end), it.item, it.start) } }

    @Test fun headingsBecomeHeadingSpansWithoutTheHashes() {
        val a = markdownToAnnotated("# Title\n## Sub\n### Small\nbody", s)
        assertEquals("Title\nSub\nSmall\nbody", a.text)
        val st = spans("# Title\n## Sub\n### Small\nbody")
        assertTrue(st.any { it.first == "Title" && it.second == s.h1 })
        assertTrue(st.any { it.first == "Sub" && it.second == s.h2 })
        assertTrue(st.any { it.first == "Small" && it.second == s.h3 })
    }

    @Test fun boldItalicAndInlineCode() {
        val a = markdownToAnnotated("a **bold** and *it* and `x = 1` end", s)
        assertEquals("a bold and it and x = 1 end", a.text)
        val st = spans("a **bold** and *it* and `x = 1` end")
        assertTrue(st.any { it.first == "bold" && it.second.fontWeight == FontWeight.Bold })
        assertTrue(st.any { it.first == "it" && it.second.fontStyle == FontStyle.Italic })
        assertTrue(st.any { it.first == "x = 1" && it.second.fontFamily == FontFamily.Monospace })
    }

    @Test fun fencedBlockIsMonospaceAndKeepsItsLines() {
        val md = "before\n```\nline 1\n  line 2 **not bold**\n```\nafter"
        val a = markdownToAnnotated(md, s)
        assertEquals("before\nline 1\n  line 2 **not bold**\nafter", a.text)
        val code = spans(md).filter { it.second.fontFamily == FontFamily.Monospace }.map { it.first }
        assertEquals(listOf("line 1", "  line 2 **not bold**"), code)
        assertTrue(spans(md).none { it.second.fontWeight == FontWeight.Bold })
    }

    @Test fun listsBecomeIndentedBullets() {
        val a = markdownToAnnotated("- one\n* two\n1. three\n2) four", s)
        assertEquals("   • one\n   • two\n   1. three\n   2) four", a.text)
    }

    @Test fun linksRenderAsUnderlinedText() {
        val md = "see [docs](https://example.com/x) now"
        assertEquals("see docs now", markdownToAnnotated(md, s).text)
        assertTrue(spans(md).any { it.first == "docs" && it.second.textDecoration == TextDecoration.Underline })
    }

    @Test fun malformedMarkdownNeverThrowsAndKeepsText() {
        for (md in listOf("", "**unclosed", "`tick", "```", "```\nopen fence", "[bad](", "***", "_ _", "#", "# ", "1.", "- ", "a * b * c", "snake_case_word", "**")) {
            val a = markdownToAnnotated(md, s)
            assertTrue(md, a.text.length <= md.length + 8)   // bullets add "   • " at most
        }
        assertEquals("**unclosed", markdownToAnnotated("**unclosed", s).text)
        assertEquals("snake_case_word", markdownToAnnotated("snake_case_word", s).text)
        assertEquals("a * b * c", markdownToAnnotated("a * b * c", s).text)
    }

    @Test fun customStyleIsApplied() {
        val custom = s.copy(bold = SpanStyle(fontWeight = FontWeight.Black))
        assertTrue(markdownToAnnotated("**x**", custom).spanStyles.any { it.item.fontWeight == FontWeight.Black })
    }
}
