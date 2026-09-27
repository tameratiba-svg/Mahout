package com.mob8n.engine

import com.mob8n.core.DECISION_APPROVE
import com.mob8n.core.DECISION_TIMEOUT
import com.mob8n.core.DECISION_TIMER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedRunWorkerTest {
    /** F2: the worker delivering a decision must never cancel its own unique work (that stops the worker awaiting the run). */
    @Test fun cancelResumeSkipsTheDeliveringWork() {
        assertEquals(listOf("expire:r"), DelayedRunWorker.namesToCancel("r", DECISION_TIMER))
        assertEquals(listOf("resume:r"), DelayedRunWorker.namesToCancel("r", DECISION_TIMEOUT))
        assertEquals(listOf("expire:r"), DelayedRunWorker.namesToCancel("r", DECISION_APPROVE))   // user buttons arrive via "resume:<id>"
    }

    /** F8: the parked payload must outlive any delay ScheduleRunNode allows (30 days), not a fixed 7-day TTL. */
    @Test fun parkedPayloadTtlOutlivesTheDelay() {
        val day = 24 * 3600_000L
        assertTrue(DelayedRunWorker.payloadTtlMs(14 * day) > 14 * day)
        assertTrue(DelayedRunWorker.payloadTtlMs(30 * day) > 30 * day)
        assertEquals(DelayedRunWorker.PAYLOAD_SLACK_MS, DelayedRunWorker.payloadTtlMs(-5))   // negative delays run now; still a positive TTL
    }
}
