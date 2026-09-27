package com.mob8n.engine

import com.mob8n.core.RunRecord
import com.mob8n.core.RunStatus
import com.mob8n.core.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** DESIGN4 §11 engine: pure Stats over RunRecord / AiUsageRow lists. */
class StatsTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(zone: ZoneId, date: LocalDate, time: LocalTime = LocalTime.NOON) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    private fun run(id: String, wf: String, status: RunStatus, started: Long, ended: Long? = started + 1_000, name: String = "WF $wf") =
        RunRecord(id, wf, name, "trigger.manual", status, started, ended)
    private fun usage(ts: Long, provider: String = "minimax", model: String = "MiniMax-M2.7", source: String = "node", cost: Double? = null, estimated: Boolean = false, inTok: Long = 100, outTok: Long = 20, cached: Long = 0) =
        AiUsageRow(0, ts, provider, model, source, inTok, outTok, cached, 0, cost, estimated, null, null)

    @Test fun countsByStatusAndSinceBoundary() {
        val runs = listOf(
            run("a", "w1", RunStatus.SUCCESS, 1_000), run("b", "w1", RunStatus.FAILED, 2_000), run("c", "w2", RunStatus.SUSPENDED, 3_000, null),
            run("d", "w2", RunStatus.RUNNING, 4_000, null), run("e", "w2", RunStatus.CANCELLED, 5_000), run("old", "w1", RunStatus.SUCCESS, 999),
        )
        val c = Stats.counts(runs, 1_000)   // since is inclusive
        assertEquals(RunCounts(5, 1, 1, 1, 1, 1), c)
        assertEquals(6, Stats.counts(runs, 0).total)
        assertEquals(0, Stats.counts(emptyList(), 0).total)
    }

    /** 2026-03-29 is the spring DST change in Berlin (the day has 23 hours): a run at 01:30 and one at 23:30 both land in that bucket, none leaks into a neighbour. */
    @Test fun perDaySevenBucketsAcrossDst() {
        val dst = LocalDate.of(2026, 3, 29)
        val now = at(berlin, LocalDate.of(2026, 4, 1), LocalTime.of(10, 0))
        val runs = listOf(
            run("a", "w", RunStatus.SUCCESS, at(berlin, dst, LocalTime.of(1, 30))),
            run("b", "w", RunStatus.FAILED, at(berlin, dst, LocalTime.of(23, 30))),
            run("c", "w", RunStatus.SUCCESS, at(berlin, LocalDate.of(2026, 3, 30), LocalTime.of(0, 10))),
            run("d", "w", RunStatus.SUCCESS, at(berlin, LocalDate.of(2026, 3, 26), LocalTime.of(0, 0))),   // first bucket, first millisecond
            run("e", "w", RunStatus.SUCCESS, at(berlin, LocalDate.of(2026, 3, 25), LocalTime.of(23, 59))), // before the window
            run("f", "w", RunStatus.SUCCESS, now + 60_000),                                                 // today, later than now: still today
        )
        val (total, failed) = Stats.perDay(runs, now, berlin)
        assertEquals(7, total.size); assertEquals(7, failed.size)
        // buckets: 26, 27, 28, 29, 30, 31, Apr 1
        assertEquals(listOf(1, 0, 0, 2, 1, 0, 1), total)
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0), failed)
    }

    @Test fun perDayNegativeOffsetZoneUsesLocalDays() {
        val la = ZoneId.of("America/Los_Angeles")
        val now = at(la, LocalDate.of(2026, 9, 26), LocalTime.of(9, 0))
        // 23:30 local on the 25th is 06:30 UTC on the 26th: must count for the 25th (bucket 5), not today
        val runs = listOf(run("a", "w", RunStatus.SUCCESS, at(la, LocalDate.of(2026, 9, 25), LocalTime.of(23, 30))))
        val (total, _) = Stats.perDay(runs, now, la)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 0), total)
        assertEquals(3, Stats.perDay(runs, now, la, days = 3).first.size)
        assertEquals(at(la, LocalDate.of(2026, 9, 26), LocalTime.MIDNIGHT), Stats.dayStart(now, la))
    }

    @Test fun perWorkflowAverageIgnoresOpenRows() {
        val wfs = listOf(Workflow("w1", "Alpha"), Workflow("w2", "Beta"), Workflow("w3", "Quiet"))
        val runs = listOf(
            run("a", "w1", RunStatus.SUCCESS, 10_000, 12_000), run("b", "w1", RunStatus.FAILED, 20_000, 24_000),
            run("c", "w1", RunStatus.RUNNING, 30_000, null), run("d", "w1", RunStatus.SUSPENDED, 40_000, 41_000),
            run("e", "w1", RunStatus.SUCCESS, 50_000, null),   // no endedAt: excluded from the average
            run("f", "w2", RunStatus.CANCELLED, 5_000, 6_000), run("g", "w2", RunStatus.SUCCESS, 100, 200),   // before since
            run("h", "gone", RunStatus.SUCCESS, 7_000, 7_500, name = "Deleted WF"),
        )
        val stats = Stats.perWorkflow(runs, wfs, 1_000)
        val w1 = stats.first { it.id == "w1" }
        assertEquals(5, w1.runs); assertEquals(1, w1.failures); assertEquals(3_000L, w1.avgMs)   // (2000 + 4000) / 2
        assertEquals(50_000L, w1.lastRunAt); assertEquals(RunStatus.SUCCESS, w1.lastStatus)
        val w2 = stats.first { it.id == "w2" }
        assertEquals(1, w2.runs); assertNull(w2.avgMs)                                             // CANCELLED excluded, `g` before since
        val w3 = stats.first { it.id == "w3" }
        assertEquals(0, w3.runs); assertNull(w3.lastRunAt); assertNull(w3.lastStatus)
        assertEquals(listOf("w1", "w2", "w3"), stats.map { it.id })                               // runs desc, then name; the deleted workflow is no row
        assertEquals(1, Stats.deletedRuns(runs, wfs, 1_000))                                        // `h` counted once, in the muted line
    }

    @Test fun deletedWorkflowsAreOneLineNeverRows() {
        val zone = ZoneId.of("UTC")
        val now = at(zone, LocalDate.of(2026, 9, 26), LocalTime.of(15, 0))
        val runs = listOf(
            run("a", "keep", RunStatus.SUCCESS, now - 1), run("b", "tmp-1", RunStatus.SUCCESS, now - 2, name = "temp A"),
            run("c", "tmp-2", RunStatus.FAILED, now - 3, name = "temp B"), run("d", "tmp-2", RunStatus.SUCCESS, now - 4, name = "temp B"),
            run("e", "tmp-3", RunStatus.SUCCESS, now - 40 * Stats.DAY_MS, name = "old temp"),   // outside the 30-day window
        )
        val d = Stats.build(runs, listOf(Workflow("keep", "Kept")), emptyList(), now, zone)
        assertEquals(listOf("keep"), d.workflows.map { it.id })
        assertEquals(3, d.deletedRuns)
        assertEquals(4, d.week.total)                                                               // totals still count every run (no data is hidden)
        assertEquals("Deleted workflows · 3 runs", com.mob8n.ui.deletedWorkflowsLine(d.deletedRuns))
        assertEquals("Deleted workflows · 1 run", com.mob8n.ui.deletedWorkflowsLine(1))
        assertNull(com.mob8n.ui.deletedWorkflowsLine(0))
        assertEquals(0, Stats.build(runs.take(1), listOf(Workflow("keep", "Kept")), emptyList(), now, zone).deletedRuns)
    }

    @Test fun usageGroupsWithUnknownCostAndEstimated() {
        val rows = listOf(
            usage(10, cost = 0.01), usage(11, cost = null), usage(12, cost = 0.02, estimated = true),
            usage(13, provider = "claude", model = "claude-sonnet-5", cost = 0.5, inTok = 1_000, outTok = 200, cached = 300),
            usage(14, provider = "ollama", model = "llama3", cost = null), usage(15, provider = "ollama", model = "llama3", cost = null),
            usage(1, cost = 99.0),   // before since
        )
        val u = Stats.usage(rows, 10)
        val mm = u.first { it.provider == "minimax" }
        assertEquals(3, mm.calls); assertEquals(300L, mm.inTok); assertEquals(60L, mm.outTok)
        assertEquals(0.03, mm.costUsd!!, 1e-9); assertEquals(1, mm.unknownCost); assertTrue(mm.estimated)
        val cl = u.first { it.provider == "claude" }
        assertEquals(1, cl.calls); assertEquals(300L, cl.cachedTok); assertEquals(0.5, cl.costUsd!!, 1e-9); assertEquals(0, cl.unknownCost); assertTrue(!cl.estimated)
        val ol = u.first { it.provider == "ollama" }
        assertNull("all rows unpriced -> price unknown, never $0", ol.costUsd); assertEquals(2, ol.unknownCost)
        assertEquals(listOf("minimax", "ollama", "claude"), u.map { it.provider })   // calls desc, then provider
    }

    @Test fun bySourceAndBuild() {
        val zone = ZoneId.of("UTC")
        val now = at(zone, LocalDate.of(2026, 9, 26), LocalTime.of(15, 0))
        val day = Stats.dayStart(now, zone)
        val rows = listOf(usage(now, source = "chat"), usage(now - 1, source = "node"), usage(day - 1, source = "node"), usage(now - 10 * Stats.DAY_MS, source = "builder"), usage(now - 40 * Stats.DAY_MS, source = "test"))
        assertEquals(mapOf("builder" to 1, "chat" to 1, "node" to 2), Stats.bySource(rows, now - Stats.MONTH_MS))
        val runs = listOf(run("a", "w", RunStatus.SUCCESS, now - 1), run("b", "w", RunStatus.FAILED, day - 1), run("c", "w", RunStatus.SUCCESS, now - 8 * Stats.DAY_MS))
        val d = Stats.build(runs, listOf(Workflow("w", "W")), rows, now, zone)
        assertEquals(1, d.today.total); assertEquals(2, d.week.total); assertEquals(1, d.week.failed)
        assertEquals(7, d.perDay.size); assertEquals(1, d.perDay.last()); assertEquals(1, d.perDay[5]); assertEquals(1, d.perDayFailed[5])
        assertEquals(2, d.usageToday.sumOf { it.calls }); assertEquals(3, d.usage7d.sumOf { it.calls }); assertEquals(4, d.usage30d.sumOf { it.calls })
        assertEquals(3, d.workflows.first().runs)
    }

    @Test fun formatting() {
        assertEquals("999", Stats.fmtTokens(999)); assertEquals("12.3k", Stats.fmtTokens(12_300)); assertEquals("4.0M", Stats.fmtTokens(4_000_000))
        assertEquals("—", Stats.fmtUsd(null)); assertEquals("$0.12", Stats.fmtUsd(0.12)); assertEquals("$0.0012", Stats.fmtUsd(0.0012)); assertEquals("$0.00", Stats.fmtUsd(0.0))
    }
}
