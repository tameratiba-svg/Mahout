package com.mob8n.engine

import com.mob8n.core.RunRecord
import com.mob8n.core.RunStatus
import com.mob8n.core.Workflow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// ---------------------------------------------------------------- dashboard records (DESIGN4 §3.2)

data class RunCounts(val total: Int, val success: Int, val failed: Int, val suspended: Int, val running: Int, val cancelled: Int)
data class WorkflowStat(val id: String, val name: String, val runs: Int, val failures: Int, val avgMs: Long?, val lastRunAt: Long?, val lastStatus: RunStatus?)
data class UsageRow(val provider: String, val model: String, val calls: Int, val inTok: Long, val outTok: Long, val cachedTok: Long, val costUsd: Double?, val unknownCost: Int, val estimated: Boolean)
data class DashboardStats(
    val today: RunCounts, val week: RunCounts, val perDay: List<Int>, val perDayFailed: List<Int>, val workflows: List<WorkflowStat>,
    val usageToday: List<UsageRow>, val usage7d: List<UsageRow>, val usage30d: List<UsageRow>, val bySource: Map<String, Int>,
    /** Runs (30 days) whose workflow no longer exists: one muted line, never a table row. */
    val deletedRuns: Int = 0,
)

/**
 * Pure aggregations over Engine.runsSince / aiUsageSince for the Dashboard (DESIGN4 §6.2). Day buckets use LocalDate arithmetic in the given
 * ZoneId, never 86_400_000 multiples, so a DST change keeps every run in its local day.
 * // ponytail: Stats aggregates <= 2000 runs in Kotlin; upgrade = SQL GROUP BY
 */
object Stats {
    const val DAY_MS = 86_400_000L
    const val WEEK_MS = 7 * DAY_MS
    const val MONTH_MS = 30 * DAY_MS

    /** Local midnight of the day containing `now`. */
    fun dayStart(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun counts(runs: List<RunRecord>, since: Long): RunCounts {
        val r = runs.filter { it.startedAt >= since }
        fun n(s: RunStatus) = r.count { it.status == s }
        return RunCounts(r.size, n(RunStatus.SUCCESS), n(RunStatus.FAILED), n(RunStatus.SUSPENDED), n(RunStatus.RUNNING), n(RunStatus.CANCELLED))
    }

    /** `days` buckets, oldest first, local-day boundaries via ZoneId (DST-safe). Returns (total, failed). */
    fun perDay(runs: List<RunRecord>, now: Long, zone: ZoneId, days: Int = 7): Pair<List<Int>, List<Int>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays((days - 1).toLong())
        val total = IntArray(days); val failed = IntArray(days)
        for (r in runs) {
            val d: LocalDate = Instant.ofEpochMilli(r.startedAt).atZone(zone).toLocalDate()
            if (d.isBefore(first) || d.isAfter(today)) continue
            val i = (d.toEpochDay() - first.toEpochDay()).toInt()
            total[i]++
            if (r.status == RunStatus.FAILED) failed[i]++
        }
        return total.toList() to failed.toList()
    }

    /**
     * Per EXISTING workflow since `since`; avgMs over SUCCESS/FAILED rows with endedAt; sorted by runs desc then name. Workflows without runs are
     * included with zeros. Runs of deleted workflows are not rows here (see [deletedRuns]); the run rows themselves are never touched.
     */
    fun perWorkflow(runs: List<RunRecord>, workflows: List<Workflow>, since: Long): List<WorkflowStat> {
        val byWf = runs.filter { it.startedAt >= since }.groupBy { it.workflowId }
        val names = workflows.associate { it.id to it.name }
        return names.keys.map { id ->
            val rs = byWf[id].orEmpty()
            val durations = rs.filter { (it.status == RunStatus.SUCCESS || it.status == RunStatus.FAILED) && it.endedAt != null }.map { it.endedAt!! - it.startedAt }
            val last = rs.maxByOrNull { it.startedAt }
            WorkflowStat(id, names.getValue(id), rs.size, rs.count { it.status == RunStatus.FAILED },
                if (durations.isEmpty()) null else durations.sum() / durations.size, last?.startedAt, last?.status)
        }.sortedWith(compareByDescending<WorkflowStat> { it.runs }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** Runs since `since` whose workflow id is not in `workflows` (deleted, e.g. temp workflows). */
    fun deletedRuns(runs: List<RunRecord>, workflows: List<Workflow>, since: Long): Int {
        val ids = workflows.mapTo(HashSet()) { it.id }
        return runs.count { it.startedAt >= since && it.workflowId !in ids }
    }

    /** Group provider+model; costUsd = sum of non-null (null when every row is unpriced); unknownCost = count of null; estimated = any. */
    fun usage(rows: List<AiUsageRow>, since: Long): List<UsageRow> =
        rows.filter { it.ts >= since }.groupBy { it.provider to it.model }.map { (k, rs) ->
            val priced = rs.mapNotNull { it.costUsd }
            UsageRow(k.first, k.second, rs.size, rs.sumOf { it.inTok }, rs.sumOf { it.outTok }, rs.sumOf { it.cachedTok },
                if (priced.isEmpty()) null else priced.sum(), rs.size - priced.size, rs.any { it.estimated })
        }.sortedWith(compareByDescending<UsageRow> { it.calls }.thenBy { it.provider }.thenBy { it.model })

    fun bySource(rows: List<AiUsageRow>, since: Long): Map<String, Int> =
        rows.filter { it.ts >= since }.groupingBy { it.source }.eachCount().toSortedMap()

    fun build(runs: List<RunRecord>, workflows: List<Workflow>, usage: List<AiUsageRow>, now: Long, zone: ZoneId): DashboardStats {
        val day = dayStart(now, zone)
        val (total, failed) = perDay(runs, now, zone)
        return DashboardStats(
            today = counts(runs, day), week = counts(runs, now - WEEK_MS), perDay = total, perDayFailed = failed,
            workflows = perWorkflow(runs, workflows, now - MONTH_MS),
            usageToday = usage(usage, day), usage7d = usage(usage, now - WEEK_MS), usage30d = usage(usage, now - MONTH_MS),
            bySource = bySource(usage, now - MONTH_MS),
            deletedRuns = deletedRuns(runs, workflows, now - MONTH_MS),
        )
    }

    /** 999 -> "999", 12_300 -> "12.3k", 4_000_000 -> "4.0M". */
    fun fmtTokens(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
        else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    }

    /** null -> "—" (price unknown); tiny amounts keep 4 decimals so a $0.0012 call never shows as $0.00. */
    fun fmtUsd(d: Double?): String = when {
        d == null -> "—"
        d == 0.0 -> "$0.00"
        d < 0.01 -> String.format(Locale.US, "$%.4f", d)
        else -> String.format(Locale.US, "$%.2f", d)
    }
}
