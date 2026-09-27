package com.mob8n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's ICU regex engine rejects an unescaped '}' (and a '{' that does not open a quantifier) that the JVM accepts;
 * two device crashes came from exactly that (Recipes.kt PLACEHOLDER). Unit tests run on the JVM, so they never notice.
 * This lint walks app/src/main/java, pulls the string-literal argument of Regex(...), "...".toRegex() and Pattern.compile(...)
 * (normal strings unescaped, raw strings as-is, string templates dropped) and asserts every brace outside a character class
 * is either escaped, part of a {n} / {n,} / {n,m} quantifier or inside \p{..} / \P{..} / \x{..} / \N{..} / \Q..\E.
 * Patterns built from variables are not literals and are skipped (they are user input, validated at runtime).
 */
class RegexIcuLintTest {
    private fun sourceRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("app/src/main/java not found from ${File(".").absolutePath}")

    @Test fun noUnescapedBracesInRegexLiterals() {
        val offenders = ArrayList<String>()
        var literals = 0
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { f ->
            val src = f.readText()
            for (lit in stringLiterals(src)) {
                if (!isRegexArgument(src, lit)) continue
                literals++
                val pattern = if (lit.raw) lit.body else unescape(lit.body)
                val bad = braceOffenders(pattern)
                if (bad.isNotEmpty()) {
                    val line = src.substring(0, lit.start).count { it == '\n' } + 1
                    offenders += "${f.path}:$line  ${bad.joinToString("; ")}  in  ${lit.body.take(80)}"
                }
            }
        }
        offenders.forEach { println("ICU regex lint: $it") }
        assertTrue("expected some Regex literals in app/src/main/java", literals > 0)
        assertTrue("unescaped braces (Android ICU rejects them; escape as \\{ \\}):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Self-check of the analyser: the rule itself must accept quantifiers/classes/escapes and reject bare braces. */
    @Test fun analyserRules() {
        assertTrue(braceOffenders("""a{2}b{3,}c{1,4}\{x\}[{}]\p{Cntrl}\Q{}\E""").isEmpty())
        assertTrue(braceOffenders("""\{([a-zA-Z]+)\}""").isEmpty())
        assertTrue(braceOffenders("""\{([a-zA-Z]+)}""").isNotEmpty())   // the crash pattern
        assertTrue(braceOffenders("""{name}""").isNotEmpty())
        assertTrue(braceOffenders("""[^}]+}""").isNotEmpty())           // '}' inside the class is fine, the trailing one is not
        assertEquals("""\d\{"${'$'}x""", unescape("""\\d\\{\"\${'$'}x"""))   // "\\{" in source is the runtime \{ ICU wants
    }

    // ---- Kotlin string-literal tokenizer (skips comments and char literals; templates are removed from the body) ----

    private data class Lit(val start: Int, val end: Int, val raw: Boolean, val body: String)

    private fun stringLiterals(src: String): List<Lit> {
        val out = ArrayList<Lit>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '/' && src.startsWith("//", i) -> { i = src.indexOf('\n', i).let { if (it < 0) n else it } }
                c == '/' && src.startsWith("/*", i) -> {
                    var depth = 1; i += 2
                    while (i < n && depth > 0) { if (src.startsWith("/*", i)) { depth++; i += 2 } else if (src.startsWith("*/", i)) { depth--; i += 2 } else i++ }
                }
                c == '\'' -> { val m = CHAR_LIT.find(src, i); i = if (m != null && m.range.first == i) m.range.last + 1 else i + 1 }
                c == '"' -> { val lit = readString(src, i); out += lit; i = lit.end }
                else -> i++
            }
        }
        return out
    }

    /** Reads the string starting at src[start] == '"'; returns the literal with template segments removed from body. */
    private fun readString(src: String, start: Int): Lit {
        val n = src.length
        val raw = src.startsWith("\"\"\"", start)
        val body = StringBuilder()
        var i = start + if (raw) 3 else 1
        while (i < n) {
            val c = src[i]
            if (raw && src.startsWith("\"\"\"", i)) {
                var j = i + 3
                while (j < n && src[j] == '"') j++        // a run of quotes: the last three close the literal, the rest are content
                repeat(j - i - 3) { body.append('"') }
                return Lit(start, j, true, body.toString())
            }
            if (!raw && c == '"') return Lit(start, i + 1, false, body.toString())
            if (!raw && c == '\\' && i + 1 < n) { body.append(c).append(src[i + 1]); i += 2; continue }
            if (c == '$' && i + 1 < n && src[i + 1] == '{') { i = skipTemplate(src, i + 1); continue }
            if (c == '$' && i + 1 < n && (src[i + 1].isLetter() || src[i + 1] == '_')) { i++; while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++; continue }
            body.append(c); i++
        }
        return Lit(start, n, raw, body.toString())
    }

    /** src[open] == '{' of a template; returns the index after the matching '}' (nested strings handled). */
    private fun skipTemplate(src: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++; if (depth == 0) return i }
                '"' -> i = readString(src, i).end
                '\'' -> { val m = CHAR_LIT.find(src, i); i = if (m != null && m.range.first == i) m.range.last + 1 else i + 1 }
                else -> i++
            }
        }
        return i
    }

    private fun isRegexArgument(src: String, lit: Lit): Boolean {
        val pre = src.substring(0, lit.start).trimEnd()
        val post = src.substring(lit.end).trimStart()
        if (post.startsWith(".toRegex(")) return true
        for (call in listOf("Regex(", "Pattern.compile(")) {
            if (!pre.endsWith(call)) continue
            val before = pre.getOrNull(pre.length - call.length - 1)
            if (before == null || !(before.isLetterOrDigit() || before == '_')) return true   // not compileRegex( / myPattern.compile(
        }
        return false
    }

    /** Kotlin escape sequences of a normal string literal -> the runtime String. */
    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val e = s[i + 1]) {
                't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r')
                'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                else -> sb.append(e)   // \\ \" \' \$
            }
            i += 2
        }
        return sb.toString()
    }

    // ---- the ICU brace rule ----

    private fun braceOffenders(p: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var cls = 0          // character-class nesting depth (Java allows [a[b]])
        var clsStart = -1
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    val e = p.getOrNull(i + 1)
                    if (e == 'Q') { val end = p.indexOf("\\E", i + 2); i = if (end < 0) p.length else end + 2 }
                    else if ((e == 'p' || e == 'P' || e == 'x' || e == 'N') && p.getOrNull(i + 2) == '{') { val end = p.indexOf('}', i + 3); i = if (end < 0) p.length else end + 1 }
                    else i += 2
                }
                cls > 0 -> {
                    val literalBracket = c == ']' && (i == clsStart + 1 || (i == clsStart + 2 && p[clsStart + 1] == '^'))
                    if (c == '[') cls++ else if (c == ']' && !literalBracket) cls--
                    i++
                }
                c == '[' -> { cls = 1; clsStart = i; i++ }
                c == '{' -> {
                    val q = QUANT.find(p, i)?.takeIf { it.range.first == i }
                    if (q != null) i = q.range.last + 1 else { out += "'{' at $i"; i++ }
                }
                c == '}' -> { out += "'}' at $i"; i++ }
                else -> i++
            }
        }
        return out
    }

    private companion object {
        val CHAR_LIT = Regex("""'(\\u[0-9a-fA-F]{4}|\\.|[^'\\])'""")
        val QUANT = Regex("""\{\d+(,\d*)?\}""")
    }
}
