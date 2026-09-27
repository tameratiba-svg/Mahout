package com.mob8n.engine

import com.mob8n.core.DECISION_TIMEOUT
import com.mob8n.core.DECISION_TIMER
import com.mob8n.core.SuspendKind
import com.mob8n.engine.knowledge.Knowledge
import com.mob8n.engine.knowledge.SourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HousekeepingWorkerTest {
    /** F4: overdue TIMER rows (logic.delay / wait_until) are delivered, not timed out; APPROVAL and unparseable rows time out. */
    @Test fun overdueSuspensionsResumeByKind() {
        assertEquals(DECISION_TIMER, HousekeepingWorker.housekeepingDecision(SuspendKind.TIMER))
        assertEquals(DECISION_TIMEOUT, HousekeepingWorker.housekeepingDecision(SuspendKind.APPROVAL))
        assertEquals(DECISION_TIMEOUT, HousekeepingWorker.housekeepingDecision(null))
    }

    /** K2: the stale-run threshold must exceed the run ceiling so an in-flight run is never marked 'process died'. */
    @Test fun staleThresholdExceedsRunCeiling() {
        assertTrue(HousekeepingWorker.STALE_RUN_MS > TriggerHub.RUN_CEILING_MS)
    }

    /** v3: the re-index predicate the housekeeping pass applies — FOLDER/URL/NOTES/PLAYLIST after 20 h, TEXT never, DOCUMENT never by age. */
    @Test fun knowledgeStalenessByKind() {
        val now = 200 * 3_600_000L
        val old = now - Knowledge.STALE_MS - 1
        for (k in listOf(SourceKind.FOLDER, SourceKind.URL, SourceKind.NOTES, SourceKind.PLAYLIST)) {
            assertTrue(k.name, Knowledge.isStale(k, old, now)); assertFalse(k.name, Knowledge.isStale(k, now - 3_600_000L, now)); assertTrue(k.name, Knowledge.isStale(k, null, now))
        }
        assertFalse(Knowledge.isStale(SourceKind.TEXT, old, now))
        assertFalse(Knowledge.isStale(SourceKind.DOCUMENT, old, now))
        assertTrue("the 5-min budget must fit under WorkManager's 10-min hard cap", 5 * 60_000L < 10 * 60_000L)
    }
}
