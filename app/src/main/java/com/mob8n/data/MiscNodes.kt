package com.mob8n.data

import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.labels
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.text
import com.mob8n.core.whenIs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

object DateTimeNode : Node() {
    override val spec = NodeSpec(
        id = "data.datetime", name = "Date & Time", kind = NodeKind.DATA,
        description = "Formats the current (or a given) time, optionally shifted by an amount of minutes, hours, days or weeks.",
        params = listOf(
            text("base", "Base time", "now", help = "now, ISO-8601 (2025-01-31T08:00, 2025-01-31) or epoch milliseconds"),
            number("amount", "Amount", 0.0, help = "Shift; negative for the past"),
            choice("unit", "Unit", listOf("minutes", "hours", "days", "weeks")),
            text("pattern", "Format pattern", help = "java.time pattern, e.g. EEEE d MMM HH:mm; empty = ISO offset date-time"),
            text("outputField", "Output field", "datetime"),
        ),
        agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val base = parseBase(ctx.strOrNull("base") ?: "now", ctx.nowMs(), ctx.zone)
        val n = (ctx.double("amount") ?: 0.0).toLong()
        val t = when (ctx.str("unit")) {
            "hours" -> base.plusHours(n)
            "days" -> base.plusDays(n)
            "weeks" -> base.plusWeeks(n)
            else -> base.plusMinutes(n)
        }
        val fmt = ctx.strOrNull("pattern")?.let { p ->
            runCatching { DateTimeFormatter.ofPattern(p, Locale.getDefault()) }.getOrElse { throw NodeException("Invalid date pattern '$p': ${it.message}") }
        } ?: DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val field = ctx.strOrNull("outputField") ?: "datetime"
        return out(ctx.item.add(
            field to fmt.format(t), "epochMs" to t.toInstant().toEpochMilli(),
            "weekday" to t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            "hour" to t.hour, "minute" to t.minute,
            "date" to t.toLocalDate().toString(), "time" to "%02d:%02d".format(t.hour, t.minute),
        ))
    }

    /** "now" | epoch (seconds if < 1e10, else ms; digit strings shorter than 9 rejected) | ISO zoned/offset/local date-time | ISO date | "yyyy-MM-dd HH:mm". */
    fun parseBase(s: String, nowMs: Long, zone: ZoneId): ZonedDateTime {
        val t = s.trim()
        if (t.isEmpty() || t.equals("now", ignoreCase = true)) return Instant.ofEpochMilli(nowMs).atZone(zone)
        if (t.matches(Regex("-?\\d{1,19}"))) {   // F48: same rule as logic.date
            val n = t.toLong()
            if (t.trimStart('-').length < 9) throw NodeException("DateTime base '$s' is not an epoch (seconds or ms) or ISO date")
            return Instant.ofEpochMilli(if (abs(n) < 10_000_000_000L) n * 1000 else n).atZone(zone)
        }
        val attempts = listOf<() -> ZonedDateTime>(
            { ZonedDateTime.parse(t).withZoneSameInstant(zone) },
            { OffsetDateTime.parse(t).atZoneSameInstant(zone) },
            { LocalDateTime.parse(t).atZone(zone) },
            { LocalDate.parse(t).atStartOfDay(zone) },
            { LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")).atZone(zone) },
        )
        for (p in attempts) runCatching { return p() }
        throw NodeException("Cannot parse date/time '$s' (use now, ISO-8601 or epoch seconds/ms)")
    }
}

object RandomNode : Node() {
    override val spec = NodeSpec(
        id = "data.random", name = "Random", kind = NodeKind.DATA,
        description = "Produces a random number, a random choice from a list, or a UUID.",
        params = listOf(
            choice("mode", "Mode", listOf("number", "choice", "uuid")),
            number("min", "Min", 0.0, visibleWhen = whenIs("mode", "number")),
            number("max", "Max", 100.0, visibleWhen = whenIs("mode", "number")),
            bool("integer", "Whole numbers only", true, visibleWhen = whenIs("mode", "number")),
            labels("choices", "Choices", visibleWhen = whenIs("mode", "choice")),
            text("outputField", "Output field", "random"),
        ),
        agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val field = ctx.strOrNull("outputField") ?: "random"
        val value: Any = when (ctx.str("mode")) {
            "uuid" -> UUID.randomUUID().toString()
            "choice" -> ctx.labels("choices").ifEmpty { throw NodeException("Random: add at least one choice") }.random()
            else -> {
                val min = ctx.double("min") ?: 0.0
                val max = ctx.double("max") ?: 100.0
                if (min > max) throw NodeException("Random: min ($min) must be <= max ($max)")
                if (ctx.bool("integer")) {
                    val lo = Math.ceil(min).toLong(); val hi = Math.floor(max).toLong()
                    if (lo > hi) throw NodeException("Random: no whole number between $min and $max")
                    Random.nextLong(lo, hi + 1)
                } else if (min == max) min else Random.nextDouble(min, max)
            }
        }
        return out(ctx.item.add(field to value))
    }
}
