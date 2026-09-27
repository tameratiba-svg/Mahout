package com.mob8n.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DESIGN6 §5.3.13: @-mention parsing and the plain-text Context line. */
class MentionTest {
    @Test fun noChipsLeavesTextUnchanged() { assertEquals("hi", composeOutgoing("hi", emptyList())) }

    @Test fun oneOfEachKind() {
        val out = composeOutgoing("Why did it fail?", listOf(
            Mention(MentionKind.WORKFLOW, "Morning digest", "1a2b3c4d5e6f"), Mention(MentionKind.SKILL, "coding-on-device"), Mention(MentionKind.KNOWLEDGE, "Manuals")))
        assertEquals("Context: workflow \"Morning digest\" [1a2b3c4d]; skill coding-on-device; knowledge \"Manuals\".\n\nWhy did it fail?", out)
    }

    @Test fun quotesAreEscaped() {
        assertEquals("Context: workflow \"The \\\"big\\\" one\" [w1].\n\nx", composeOutgoing("x", listOf(Mention(MentionKind.WORKFLOW, "The \"big\" one", "w1"))))
        assertEquals("Context: knowledge \"a\\\\b\".\n\nx", composeOutgoing("x", listOf(Mention(MentionKind.KNOWLEDGE, "a\\b"))))
    }

    @Test fun duplicatesCollapse() {
        val m = Mention(MentionKind.SKILL, "s1")
        assertEquals("Context: skill s1.\n\nx", composeOutgoing("x", listOf(m, m, Mention(MentionKind.SKILL, "s1"))))
    }

    @Test fun parseMentionAtWordStartOnly() {
        assertEquals(MentionQuery(0, ""), parseMention("@", 1))
        assertEquals(MentionQuery(6, "Morn"), parseMention("check @Morn", 11))
        assertNull(parseMention("mail@example", 12))          // not after whitespace
        assertNull(parseMention("@Morning digest", 15))       // whitespace after the query: the mention is over
        assertNull(parseMention("plain text", 10))
    }

    @Test fun suggestionsAndRemoval() {
        val s = mentionSuggestions("man", listOf("w1" to "Manual backup"), listOf("manage"), listOf("all", "Manuals"))
        assertEquals(listOf(Mention(MentionKind.WORKFLOW, "Manual backup", "w1"), Mention(MentionKind.SKILL, "manage"), Mention(MentionKind.KNOWLEDGE, "Manuals")), s)
        assertEquals(emptyList<Mention>(), mentionSuggestions("zzz", listOf("w1" to "a"), listOf("b"), listOf("all")))
        val q = parseMention("see @Man now", 8)!!
        assertEquals("see  now" to 4, removeMentionQuery("see @Man now", q, 8))
    }
}
