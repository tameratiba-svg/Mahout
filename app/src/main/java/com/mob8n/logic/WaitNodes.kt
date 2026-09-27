package com.mob8n.logic

import com.mob8n.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

object DedupeWindowNode : Node() {
    const val DUPLICATE = "duplicate"
    override val spec = NodeSpec(
        id = "logic.dedupe_window", name = "Dedupe Window", kind = NodeKind.LOGIC,
        description = "Pass an item only if the same key was not seen within the window; otherwise route it to duplicate.",
        params = listOf(
            text("keyTemplate", "Key", "{{title}}|{{artist}}", required = true),
            durationMs("windowMs", "Window", 600_000, minMs = 1),
        ),
        outputs = listOf(MAIN, DUPLICATE),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val key = sha1(ctx.strOrNull("keyTemplate") ?: ctx.render("{{title}}|{{artist}}"))   // blank = default key
        val window = (ctx.long("windowMs") ?: 600_000L).coerceAtLeast(1)
        val now = ctx.nowMs()
        val seen = ctx.getState(key).asDouble()?.toLong()
        if (seen != null && now - seen < window) return route(DUPLICATE, input.item)
        ctx.putState(key, JsonPrimitive(now), ttlMs = window)
        return out(input.item)
    }
}

object RateLimitNode : Node() {
    const val LIMITED = "limited"
    override val spec = NodeSpec(
        id = "logic.rate_limit", name = "Rate Limit", kind = NodeKind.LOGIC,
        description = "Allow at most N items per sliding time window; extra items go to limited.",
        params = listOf(number("maxRuns", "Max runs", 1.0, min = 1.0), durationMs("perMs", "Per", 60_000, minMs = 1)),
        outputs = listOf(MAIN, LIMITED),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val max = (ctx.int("maxRuns") ?: 1).coerceAtLeast(1)
        val per = (ctx.long("perMs") ?: 60_000L).coerceAtLeast(1)
        val now = ctx.nowMs()
        val ts = (ctx.getState("ts") as? JsonArray).orEmpty().mapNotNull { it.asDouble()?.toLong() }.filter { now - it < per }
        if (ts.size >= max) return route(LIMITED, input.item)
        ctx.putState("ts", JsonArray((ts + now).map { JsonPrimitive(it) }), ttlMs = per)
        return out(input.item)
    }
}

object DelayNode : Node() {
    const val INLINE_MAX_MS = 5_000L
    override val spec = NodeSpec(
        id = "logic.delay", name = "Delay", kind = NodeKind.LOGIC,
        description = "Wait for a duration before continuing (longer than 5 s survives process death via a timer).",
        params = listOf(durationMs("delayMs", "Delay", 2_000, minMs = 0, maxMs = 86_400_000)),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val ms = (ctx.long("delayMs") ?: 2_000L).coerceIn(0, 86_400_000)
        if (ms <= INLINE_MAX_MS) { if (ms > 0) delay(ms); return out(input.items) }
        return NodeResult.Suspend(SuspendKind.TIMER, "Delay ${ms / 1000} s", resumeAtMs = ctx.nowMs() + ms)
    }
}

object WaitUntilNode : Node() {
    override val spec = NodeSpec(
        id = "logic.wait_until", name = "Wait Until", kind = NodeKind.LOGIC,
        description = "Pause the run until the next occurrence of a time of day.",
        params = listOf(ParamSpec("time", "Time", ParamKind.TIME, required = true, templated = false), bool("skipIfPast", "Continue immediately if today's time has passed", false)),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val t = DateNode.hhmm(ctx.req("time"))
        val now = Instant.ofEpochMilli(ctx.nowMs()).atZone(ctx.zone)
        var target = now.with(t)
        if (!target.isAfter(now)) {
            if (ctx.bool("skipIfPast")) return out(input.items)
            target = target.plusDays(1)
        }
        return NodeResult.Suspend(SuspendKind.TIMER, "Wait until ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}", resumeAtMs = target.toInstant().toEpochMilli())
    }
}

object WaitApprovalNode : Node() {
    const val APPROVED = "approved"; const val DENIED = "denied"; const val TIMEOUT = "timeout"
    override val spec = NodeSpec(
        id = "logic.wait_approval", name = "Wait for Approval", kind = NodeKind.LOGIC,
        description = "Post a notification with Approve/Deny and continue on the chosen output (timeout after a duration).",
        params = listOf(
            text("title", "Title", "Approve?", required = true),
            multiline("text", "Text", "{{\$json}}"),
            durationMs("timeoutMs", "Timeout", 86_400_000, minMs = 1_000),
        ),
        outputs = listOf(APPROVED, DENIED, TIMEOUT),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val title = ctx.strOrNull("title") ?: "Approve?"
        return NodeResult.Suspend(
            SuspendKind.APPROVAL, reason = "Waiting for approval", title = title, text = ctx.str("text").take(4_000),
            choices = listOf(DECISION_APPROVE, DECISION_DENY),
            resumeAtMs = ctx.nowMs() + (ctx.long("timeoutMs") ?: 86_400_000L).coerceAtLeast(1_000),
        )
    }

    override suspend fun resume(ctx: ExecutionContext, input: NodeInput, decision: String, payload: JsonObject): NodeResult = when (decision) {
        DECISION_APPROVE, APPROVED -> route(APPROVED, input.items)
        DECISION_DENY, DENIED -> route(DENIED, input.items)
        DECISION_TIMEOUT, TIMEOUT -> route(TIMEOUT, input.items)
        else -> throw NodeException("Unknown approval decision '$decision'")
    }
}

object CounterNode : Node() {
    override val spec = NodeSpec(
        id = "logic.counter", name = "Counter", kind = NodeKind.LOGIC,
        description = "Increment, decrement, reset or read a persistent counter (per node, or shared by name).",
        params = listOf(
            choice("op", "Operation", listOf("increment", "decrement", "reset", "get"), "increment"),
            text("name", "Shared name", help = "empty = private to this node; set to share via variables"),
            number("step", "Step", 1.0, visibleWhen = whenIs("op", "increment", "decrement")),
            text("outputField", "Output field", "count"),
        ),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val name = ctx.strOrNull("name")
        val cur = (if (name != null) ctx.getVar(name) else ctx.getState("count")).asDouble() ?: 0.0
        val step = ctx.double("step") ?: 1.0
        val next = when (ctx.str("op")) {
            "increment" -> cur + step
            "decrement" -> cur - step
            "reset" -> 0.0
            else -> cur
        }
        if (next != cur) { if (name != null) ctx.setVar(name, num(next)) else ctx.putState("count", num(next)) }
        return out(input.item.add((ctx.strOrNull("outputField") ?: "count") to num(next)))
    }
}

object ExecuteOnceNode : Node() {
    const val SKIPPED = "skipped"
    override val spec = NodeSpec(
        id = "logic.execute_once", name = "Execute Once", kind = NodeKind.LOGIC,
        description = "Let items through only the first time within a window (e.g. once per day); later runs go to skipped.",
        params = listOf(durationMs("windowMs", "Window", 86_400_000, minMs = 0, help = "0 = only ever once")),
        outputs = listOf(MAIN, SKIPPED),
        mode = ExecMode.LIST,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val window = (ctx.long("windowMs") ?: 86_400_000L).coerceAtLeast(0)
        val now = ctx.nowMs()
        val ran = ctx.getState("ran").asDouble()?.toLong()
        if (ran != null && (window == 0L || now - ran < window)) return route(SKIPPED, input.items)
        ctx.putState("ran", JsonPrimitive(now), ttlMs = if (window == 0L) null else window)
        return out(input.items)
    }
}
