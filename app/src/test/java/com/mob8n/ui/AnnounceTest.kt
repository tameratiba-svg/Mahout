package com.mob8n.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DESIGN6 §5.4: throttled polite announcements while streaming. */
class AnnounceTest {
    @Test fun firstAnnouncementIsResponding() {
        assertEquals(ANNOUNCE_FIRST to 0, nextAnnouncement("", 0, 1_000, 0L, done = false))
    }

    @Test fun nothingWithinTheGap() {
        assertNull(nextAnnouncement("One. Two. Three", 0, 3_000, 1_000, done = false))
        assertNull(nextAnnouncement("One. Two. Three", 0, 3_499, 1_000, done = false))
    }

    @Test fun completedSentencesAfterTheGap() {
        val text = "First sentence. Second one! Third is still grow"
        val a = nextAnnouncement(text, 0, 3_500, 1_000, done = false)!!
        assertEquals("First sentence. Second one!", a.first)
        assertEquals(text.indexOf("Third") - 1, a.second)
        // no sentence boundary after the spoken part -> nothing yet
        assertNull(nextAnnouncement(text, a.second, 7_000, 3_500, done = false))
    }

    @Test fun doneFlushesTheRemainderOnce() {
        val text = "First sentence. Second one! Third ends"
        val upTo = text.indexOf("Third")
        assertEquals("Third ends" to text.length, nextAnnouncement(text, upTo, 3_600, 3_500, done = true))
        assertNull(nextAnnouncement(text, text.length, 9_000, 3_600, done = true))       // nothing re-announced
        assertNull(nextAnnouncement(text, text.length, 9_000, 3_600, done = false))
    }

    @Test fun doneWithNothingSpokenIsPrefixed() {
        assertEquals("Mahout: Short answer." to 13, nextAnnouncement("Short answer.", 0, 100, 50, done = true))
    }

    @Test fun newlineEndsASentence() {
        val t = "- item one\n- item two\n- item"
        assertEquals("- item one\n- item two", nextAnnouncement(t, 0, 10_000, 1, done = false)!!.first)
    }
}
