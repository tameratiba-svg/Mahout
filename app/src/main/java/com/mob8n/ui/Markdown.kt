package com.mob8n.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Markdown for chat and approvals (DESIGN4 §9.6, DESIGN6 §5.3.4). Two layers:
 *  - `markdownToAnnotated` / `MarkdownText`: the inline renderer (headings, **bold**, *italic*, `code`, fenced blocks, lists, links) used
 *    by Skills and approval previews; links are clickable (http/https/mailto only).
 *  - `parseMarkdown` -> `MdBlock`s -> `MarkdownBlocks`: block layout for chat (nested lists, quotes, pipe tables, fenced code in MonoBlock,
 *    an open fence while streaming). Both never throw.
 */

/** Span styles the pure renderer applies; the composable builds one from the theme, tests use DEFAULT. */
data class MdStyle(
    val h1: SpanStyle, val h2: SpanStyle, val h3: SpanStyle, val bold: SpanStyle, val italic: SpanStyle, val code: SpanStyle, val link: SpanStyle,
) {
    companion object {
        val DEFAULT = MdStyle(
            h1 = SpanStyle(fontWeight = FontWeight.Bold), h2 = SpanStyle(fontWeight = FontWeight.Bold), h3 = SpanStyle(fontWeight = FontWeight.SemiBold),
            bold = SpanStyle(fontWeight = FontWeight.Bold), italic = SpanStyle(fontStyle = FontStyle.Italic),
            code = SpanStyle(fontFamily = FontFamily.Monospace), link = SpanStyle(textDecoration = TextDecoration.Underline),
        )
        fun of(colors: ColorScheme, typography: Typography) = MdStyle(
            h1 = typography.titleLarge.toSpanStyle(), h2 = typography.titleMedium.toSpanStyle(), h3 = typography.titleSmall.toSpanStyle(),
            bold = SpanStyle(fontWeight = FontWeight.Bold), italic = SpanStyle(fontStyle = FontStyle.Italic),
            code = CodeSmallStyle.toSpanStyle().copy(background = colors.surfaceContainerHighest, color = colors.onSurface),
            link = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
        )
    }
}

fun markdownToAnnotated(md: String, colors: ColorScheme, typography: Typography): AnnotatedString = markdownToAnnotated(md, MdStyle.of(colors, typography))

fun markdownToAnnotated(md: String, s: MdStyle = MdStyle.DEFAULT): AnnotatedString = runCatching { render(md, s) }.getOrElse { AnnotatedString(md) }

private val ORDERED = Regex("^\\d+[.)]\\s+")

private fun render(md: String, s: MdStyle): AnnotatedString = buildAnnotatedString {
    var inCode = false
    val lines = md.lines()
    lines.forEachIndexed { i, raw ->
        val line = raw.trimEnd()
        val t = line.trimStart()
        when {
            t.startsWith("```") -> { inCode = !inCode; return@forEachIndexed }   // fence lines vanish; the neighbours' own newlines separate the block
            inCode -> { withStyle(s.code) { append(line) } }
            t.startsWith("### ") -> withStyle(s.h3) { inline(t.removePrefix("### "), s) }
            t.startsWith("## ") -> withStyle(s.h2) { inline(t.removePrefix("## "), s) }
            t.startsWith("# ") -> withStyle(s.h1) { inline(t.removePrefix("# "), s) }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> { append("   • "); inline(t.drop(2), s) }
            ORDERED.containsMatchIn(t) -> { val m = ORDERED.find(t)!!; append("   ${m.value.trim()} "); inline(t.substring(m.range.last + 1), s) }
            else -> inline(line, s)
        }
        if (i != lines.lastIndex) append("\n")
    }
}

private fun linkable(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")

/** Inline scan: `code` (no nesting inside), **bold**, *italic* / _italic_, [text](url). Unbalanced markers are kept as literal text. */
private fun AnnotatedString.Builder.inline(text: String, s: MdStyle) {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) { withStyle(s.code) { append(text.substring(i + 1, end)) }; i = end + 1 } else { append(c); i++ }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) { withStyle(s.bold) { inline(text.substring(i + 2, end), s) }; i = end + 2 } else { append("**"); i += 2 }
            }
            (c == '*' || c == '_') && i + 1 < n && !text[i + 1].isWhitespace() && (i == 0 || c == '*' || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1 && (end + 1 >= n || c == '*' || !text[end + 1].isLetterOrDigit())) { withStyle(s.italic) { inline(text.substring(i + 1, end), s) }; i = end + 1 } else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf("](", i + 1)
                val end = if (close > 0) text.indexOf(')', close + 2) else -1
                if (close > i + 1 && end > close && !text.substring(i + 1, close).contains('[')) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, end).trim()
                    if (linkable(url)) withLink(LinkAnnotation.Url(url)) { withStyle(s.link) { append(label) } }
                    else withStyle(s.link) { append(label) }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

/** Themed markdown text (selectable). */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val text = remember(md, colors, typography) { markdownToAnnotated(md, colors, typography) }
    SelectionContainer { Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium) }
}

// ---- block model (pure) ----

/** @Immutable so completed blocks (equal after a re-parse) skip recomposition while the last one grows. */
sealed interface MdBlock {
    @Immutable data class Heading(val level: Int, val text: String) : MdBlock
    @Immutable data class Paragraph(val text: String) : MdBlock
    /** marker = "•" for unordered items, the written number ("1." / "2)") for ordered ones. */
    @Immutable data class Bullet(val depth: Int, val marker: String, val text: String) : MdBlock
    @Immutable data class Quote(val text: String) : MdBlock
    /** closed = false: the fence is still open (streaming). */
    @Immutable data class Code(val lang: String?, val code: String, val closed: Boolean) : MdBlock
    @Immutable data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val LIST_ITEM = Regex("^([ \\t]*)([-*+]|\\d+[.)])\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

/** Never throws: any internal failure yields one Paragraph of the raw text. */
fun parseMarkdown(md: String): List<MdBlock> = runCatching { parseBlocks(md) }.getOrElse { listOf(MdBlock.Paragraph(md)) }

private fun indentOf(s: String): Int = s.takeWhile { it == ' ' || it == '\t' }.fold(0) { n, c -> n + if (c == '\t') 4 else 1 }

private fun parseBlocks(md: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = md.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()
    val listIndents = ArrayList<Int>()   // nesting levels of the current list (indent / 2)
    fun flushPara() { if (para.isNotEmpty()) { out += MdBlock.Paragraph(para.toString()); para.setLength(0) } }
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val t = line.trimStart()
        // fenced code (``` or ~~~); an unclosed fence runs to the end (closed = false)
        if (t.startsWith("```") || t.startsWith("~~~")) {
            flushPara(); listIndents.clear()
            val fence = t.take(3)
            val lang = t.drop(3).trim().trim('`').takeIf { it.isNotEmpty() }
            val code = ArrayList<String>()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val c = lines[j].trimEnd()
                if (c.trimStart().startsWith(fence) && c.trim().all { it == fence[0] }) { closed = true; break }
                code += c; j++
            }
            out += MdBlock.Code(lang, code.joinToString("\n"), closed)
            i = j + 1; continue
        }
        if (t.isEmpty()) { flushPara(); i++; continue }
        val hm = HEADING.matchEntire(t)
        if (hm != null) {
            flushPara(); listIndents.clear()
            out += MdBlock.Heading(hm.groupValues[1].length.coerceAtMost(3), hm.groupValues[2].trim().trimEnd('#').trim())
            i++; continue
        }
        if ('|' in t && i + 1 < lines.size && '|' in lines[i + 1] && '-' in lines[i + 1] && TABLE_SEP.matches(lines[i + 1].trimEnd())) {
            flushPara(); listIndents.clear()
            val header = splitRow(t)
            val rows = ArrayList<List<String>>()
            var j = i + 2
            while (j < lines.size && lines[j].isNotBlank() && '|' in lines[j]) { rows += splitRow(lines[j].trim()); j++ }
            val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
            out += MdBlock.Table(header.padded(cols), rows.map { it.padded(cols) })
            i = j; continue
        }
        if (RULE.matches(t)) { flushPara(); listIndents.clear(); out += MdBlock.Rule; i++; continue }
        if (t.startsWith(">")) {
            flushPara(); listIndents.clear()
            val q = ArrayList<String>()
            var j = i
            while (j < lines.size && lines[j].trimStart().startsWith(">")) { q += lines[j].trimStart().drop(1).removePrefix(" ").trimEnd(); j++ }
            out += MdBlock.Quote(q.joinToString("\n"))
            i = j; continue
        }
        val li = LIST_ITEM.matchEntire(line)
        if (li != null) {
            flushPara()
            val level = indentOf(li.groupValues[1]) / 2
            while (listIndents.isNotEmpty() && listIndents.last() > level) listIndents.removeAt(listIndents.lastIndex)
            if (listIndents.isEmpty() || listIndents.last() < level) listIndents += level
            val m = li.groupValues[2]
            out += MdBlock.Bullet((listIndents.size - 1).coerceIn(0, 5), if (m[0].isDigit()) m else "•", li.groupValues[3].trim())
            i++; continue
        }
        // an indented line right after a list item continues that item
        val last = out.lastOrNull()
        if (para.isEmpty() && last is MdBlock.Bullet && line.startsWith("  ") && listIndents.isNotEmpty()) {
            out[out.lastIndex] = last.copy(text = last.text + " " + t); i++; continue
        }
        listIndents.clear()
        if (para.isNotEmpty()) para.append('\n')
        para.append(t)
        i++
    }
    flushPara()
    return out
}

private fun List<String>.padded(n: Int): List<String> = if (size >= n) this else this + List(n - size) { "" }

/** Splits a pipe-table row; pipes inside `code` spans and escaped `\|` stay in the cell. */
internal fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var inCode = false
    var k = 0
    while (k < s.length) {
        val c = s[k]
        when {
            c == '\\' && k + 1 < s.length && s[k + 1] == '|' -> { cur.append('|'); k += 2; continue }
            c == '`' -> { inCode = !inCode; cur.append(c) }
            c == '|' && !inCode -> { cells += cur.toString().trim(); cur.setLength(0) }
            else -> cur.append(c)
        }
        k++
    }
    cells += cur.toString().trim()
    return cells
}

// ---- block rendering ----

private const val CARET_ID = "caret"

/**
 * Chat markdown (§5.3.4). Blocks are keyed by index so completed blocks skip recomposition while the last grows. `caretAlpha` != null
 * appends the streaming caret (0.5 em × 1.1 em, `caret` colour) to the last text block; its alpha is read in graphicsLayer only.
 * Not wrapped in a SelectionContainer: chat rows use long-press for the message menu (Copy); code blocks stay selectable (MonoBlock).
 */
@Composable
fun MarkdownBlocks(md: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, caretAlpha: State<Float>? = null) {
    val blocks = remember(md) { parseMarkdown(md) }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val ms = remember(colors, typography) { MdStyle.of(colors, typography) }
    val caretColor = MaterialTheme.mahout.caret
    val caretContent = remember(caretAlpha, caretColor) {
        caretAlpha?.let { a ->
            mapOf(CARET_ID to InlineTextContent(Placeholder(0.5.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(caretColor, RoundedCornerShape(Radius.xs)))
            })
        } ?: emptyMap()
    }
    val lastText = if (caretAlpha == null) -1 else blocks.indexOfLast { it is MdBlock.Paragraph || it is MdBlock.Heading || it is MdBlock.Bullet || it is MdBlock.Quote }
        .takeIf { it == blocks.lastIndex } ?: -1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        blocks.forEachIndexed { i, b ->
            key(i) { MdBlockView(b, ms, style, if (i == lastText) caretContent else emptyMap()) }
        }
        if (caretAlpha != null && lastText < 0) Text(buildAnnotatedString { appendInlineContent(CARET_ID, "▍") }, style = style, inlineContent = caretContent)
    }
}

private fun AnnotatedString.withCaret(caret: Map<String, InlineTextContent>): AnnotatedString =
    if (caret.isEmpty()) this else buildAnnotatedString { append(this@withCaret); appendInlineContent(CARET_ID, "▍") }

@Composable
private fun MdBlockView(b: MdBlock, ms: MdStyle, style: TextStyle, caret: Map<String, InlineTextContent>) {
    val cs = MaterialTheme.colorScheme
    val ty = MaterialTheme.typography
    when (b) {
        is MdBlock.Heading -> {
            val hs = when (b.level) { 1 -> ty.titleLarge; 2 -> ty.titleMedium; else -> ty.titleSmall }
            val a = remember(b, ms) { markdownToAnnotated(b.text, ms) }
            Text(a.withCaret(caret), style = hs, inlineContent = caret, modifier = Modifier.padding(top = Space.xs))
        }
        is MdBlock.Paragraph -> {
            val a = remember(b, ms) { markdownToAnnotated(b.text, ms) }
            Text(a.withCaret(caret), style = style, inlineContent = caret)
        }
        is MdBlock.Bullet -> Row(Modifier.padding(start = Space.l * b.depth)) {
            Text(b.marker, style = style, color = cs.primary, modifier = Modifier.widthIn(min = 20.dp).padding(end = Space.xs))
            val a = remember(b, ms) { markdownToAnnotated(b.text, ms) }
            Text(a.withCaret(caret), style = style, inlineContent = caret)
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(cs.outlineVariant, RoundedCornerShape(2.dp)))
            val a = remember(b, ms) { markdownToAnnotated(b.text, ms) }
            Text(a.withCaret(caret), style = style, color = cs.onSurfaceVariant, inlineContent = caret, modifier = Modifier.padding(start = Space.m))
        }
        is MdBlock.Code -> MonoBlock(b.code, Modifier.fillMaxWidth(), language = b.lang)
        is MdBlock.Table -> MdTable(b, ms)
        MdBlock.Rule -> HorizontalDivider(color = cs.outlineVariant)
    }
}

/** Pipe table: rows inside horizontalScroll; columns sized to their widest cell (96..280 dp), 1 dp outlineVariant grid, header on surfaceContainer. */
@Composable
private fun MdTable(t: MdBlock.Table, ms: MdStyle) {
    val cs = MaterialTheme.colorScheme
    val ty = MaterialTheme.typography
    val cols = t.header.size
    if (cols == 0) return
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier.background(cs.outlineVariant, RoundedCornerShape(Radius.xs)).padding(1.dp),
            content = {
                for (h in t.header) Box(Modifier.background(cs.surfaceContainer).padding(horizontal = Space.m, vertical = Space.s)) {
                    Text(remember(h, ms) { markdownToAnnotated(h, ms) }, style = ty.labelLarge)
                }
                for (r in t.rows) for (c in r) Box(Modifier.background(cs.surface).padding(horizontal = Space.m, vertical = Space.s)) {
                    Text(remember(c, ms) { markdownToAnnotated(c, ms) }, style = ty.bodyMedium)
                }
            },
        ) { ms2, _ ->
            val gap = 1.dp.roundToPx()
            val minW = 96.dp.roundToPx(); val maxW = 280.dp.roundToPx()
            val rowsN = ms2.size / cols
            val widths = IntArray(cols) { c -> (0 until rowsN).maxOf { r -> ms2[r * cols + c].maxIntrinsicWidth(Constraints.Infinity) }.coerceIn(minW, maxW) }
            val heights = IntArray(rowsN) { r -> (0 until cols).maxOf { c -> ms2[r * cols + c].minIntrinsicHeight(widths[c]) } }
            val placeables = ms2.mapIndexed { idx, m -> m.measure(Constraints.fixed(widths[idx % cols], heights[idx / cols])) }
            val w = widths.sum() + gap * (cols - 1)
            val h = heights.sum() + gap * (rowsN - 1)
            layout(w, h) {
                var y = 0
                for (r in 0 until rowsN) {
                    var x = 0
                    for (c in 0 until cols) { placeables[r * cols + c].place(x, y); x += widths[c] + gap }
                    y += heights[r] + gap
                }
            }
        }
    }
}
