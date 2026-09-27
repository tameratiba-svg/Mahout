package com.mob8n.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mob8n.ai.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN6 §9 ui-foundation: AnimatedCounter digit diff, toneColors / riskColors total and readable in both schemes. */
class KitLogicTest {
    private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

    @Test fun digitChangesAlignFromTheRight() {
        assertEquals(listOf(false, false, true), digitChanges("123", "124"))
        assertEquals(listOf(true, true, true), digitChanges("99", "100"))          // new leading slot counts as changed
        assertEquals(listOf(false, false), digitChanges("100", "00"))
        assertEquals(emptyList<Boolean>(), digitChanges("5", ""))
        assertEquals(listOf(true), digitChanges("", "7"))
        assertEquals(listOf(false, false, false, true, false), digitChanges("12.3k", "12.4k"))
        assertEquals(listOf(false, false), digitChanges("42", "42"))
    }

    @Test fun toneColorsTotalAndReadable() {
        for ((cs, c) in listOf(LightScheme to LightMahout, DarkScheme to DarkMahout)) {
            val pairs = Tone.entries.map { toneColors(it, cs, c) }
            assertEquals(Tone.entries.size, pairs.size)
            for ((t, p) in Tone.entries.zip(pairs)) assertTrue("$t", contrastRatio(p.second.argb(), p.first.argb()) >= 4.5)
            assertEquals("distinct containers", Tone.entries.size, pairs.map { it.first }.toSet().size)
        }
    }

    @Test fun riskColorsTotalAndReadable() {
        for (c in listOf(LightMahout, DarkMahout)) for (r in Risk.entries) {
            val (bg, fg) = riskColors(r, c)
            assertTrue("$r", contrastRatio(fg.argb(), bg.argb()) >= 4.5)
        }
    }
}
