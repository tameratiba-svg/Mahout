package com.mob8n.triggers

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.Item
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TriggerHost
import com.mob8n.core.TriggerInstance
import com.mob8n.core.TriggerNode
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.clockTime
import com.mob8n.core.item
import com.mob8n.core.labels
import com.mob8n.core.number
import com.mob8n.core.str
import com.mob8n.core.text
import com.mob8n.core.whenIs
import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * daily/weekly -> ScheduleWorker one-shot re-enqueued after each fire (or AlarmManager.setAndAllowWhileIdle -> AlarmReceiver when exact);
 * interval -> PeriodicWorkRequest (15 min floor); once -> one-shot, the worker disables the workflow afterwards.
 */
object ScheduleTrigger : TriggerNode() {
    const val ACTION_ALARM = "com.mob8n.ALARM"
    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    override val hosting = Hosting.WORK_MANAGER
    override val spec = NodeSpec(
        id = "trigger.schedule", name = "Schedule", kind = NodeKind.TRIGGER,
        description = "Runs on a schedule: daily, weekly, every N minutes, or once at a date and time.",
        params = listOf(
            choice("mode", "Mode", listOf("daily", "weekly", "interval", "once"), "daily"),
            clockTime("time", "Time", "08:00", visibleWhen = whenIs("mode", "daily", "weekly")),
            labels("days", "Days", DAYS, visibleWhen = whenIs("mode", "weekly")),
            number("everyMinutes", "Every (minutes)", 30.0, min = 15.0, help = "15 minute minimum (WorkManager)", visibleWhen = whenIs("mode", "interval")),
            text("at", "At", templated = false, help = "yyyy-MM-ddTHH:mm local time", visibleWhen = whenIs("mode", "once")),
            bool("exact", "Exact time", false, help = "Use an alarm instead of WorkManager (still subject to Doze batching)"),
        ),
        inputs = emptyList(),
    )

    // ---- pure schedule math (unit-tested) ----
    fun dayOf(label: String): DayOfWeek? = DAYS.indexOfFirst { label.startsWith(it, ignoreCase = true) }.takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }
    fun intervalMinutes(params: JsonObject): Long = maxOf(15L, (spec.pNum(params, "everyMinutes") ?: 30.0).toLong())   // ponytail: WorkManager 15-min floor

    /** Next fire time (epoch ms) strictly after nowMs, or null when nothing is left to schedule (once in the past, no weekdays). */
    fun nextRunMs(params: JsonObject, nowMs: Long, zone: ZoneId): Long? {
        val mode = spec.pStr(params, "mode") ?: "daily"
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (mode) {
            "daily", "weekly" -> {
                val t = LocalTime.parse(spec.pStr(params, "time") ?: "08:00")
                val days: Set<DayOfWeek> = if (mode == "weekly") spec.pLabels(params, "days").mapNotNull(::dayOf).toSet() else DayOfWeek.values().toSet()
                if (days.isEmpty()) return null
                (0L..7L).asSequence().map { today.plusDays(it) }.filter { it.dayOfWeek in days }
                    .map { it.atTime(t).atZone(zone).toInstant().toEpochMilli() }   // DST gap -> shifted forward, overlap -> earlier offset
                    .firstOrNull { it > nowMs }
            }
            "interval" -> nowMs + intervalMinutes(params) * 60_000L
            "once" -> LocalDateTime.parse(spec.pStr(params, "at") ?: return null).atZone(zone).toInstant().toEpochMilli().takeIf { it > nowMs }
            else -> null
        }
    }

    fun weekday(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    fun fireItem(scheduledForMs: Long, firedAtMs: Long = now(), zone: ZoneId = ZoneId.systemDefault()): Item = item(
        "scheduledFor" to Instant.ofEpochMilli(scheduledForMs).atZone(zone).toOffsetDateTime().toString(),
        "firedAt" to firedAtMs, "weekday" to weekday(firedAtMs, zone),
    )

    // ---- durable arming ----
    override fun schedule(host: TriggerHost, instance: TriggerInstance) = schedule(host, instance, ExistingWorkPolicy.REPLACE)
    override fun rearm(host: TriggerHost, instance: TriggerInstance) = schedule(host, instance, ExistingWorkPolicy.KEEP)   // F15: rearmAll never cancels the request that woke us

    /**
     * REPLACE for user edits and afterFire (the new time must win). Pass KEEP from rearmAll: on a cold start the ScheduleWorker request that woke
     * the process is still ENQUEUED/RUNNING under this unique name and REPLACE would cancel today's fire (F15). With KEEP an exhausted `once`
     * is left pending instead of cancelled. Interval (UPDATE) and exact alarms (same PendingIntent) are idempotent either way.
     * ponytail: KEEP on rearm means a TZ/DST change re-arms only after the next fire; upgrade = compare WorkInfo.nextScheduleTimeMillis.
     */
    fun schedule(host: TriggerHost, instance: TriggerInstance, policy: ExistingWorkPolicy) {
        val ctx = host.android ?: return
        val name = uniqueName(instance)
        try {
            val mode = spec.pStr(instance.params, "mode") ?: "daily"
            val wm = WorkManager.getInstance(ctx)
            if (mode == "interval") {
                cancelAlarm(ctx, instance)
                val req = PeriodicWorkRequestBuilder<ScheduleWorker>(intervalMinutes(instance.params), TimeUnit.MINUTES)
                    .setInputData(workDataOf("workflowId" to instance.workflowId, "nodeId" to instance.nodeId)).build()
                wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
                return
            }
            val nowMs = now()
            val next = nextRunMs(instance.params, nowMs, ZoneId.systemDefault())
            if (next == null) {
                if (policy == ExistingWorkPolicy.REPLACE) { logT("schedule $name: nothing to arm"); unschedule(host, instance) }
                return
            }
            if (spec.pBool(instance.params, "exact")) {
                wm.cancelUniqueWork(name)
                val am = ctx.getSystemService(AlarmManager::class.java) ?: return
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(ctx, instance, next))
            } else {
                cancelAlarm(ctx, instance)
                val req = OneTimeWorkRequestBuilder<ScheduleWorker>().setInitialDelay(next - nowMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("workflowId" to instance.workflowId, "nodeId" to instance.nodeId, "scheduledFor" to next)).build()
                wm.enqueueUniqueWork(name, policy, req)
            }
            logT("schedule $name armed for $next (exact=${spec.pBool(instance.params, "exact")})")
        } catch (e: Exception) { logW("schedule $name", e) }
    }

    override fun unschedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try { WorkManager.getInstance(ctx).cancelUniqueWork(uniqueName(instance)); cancelAlarm(ctx, instance) } catch (e: Exception) { logW("unschedule", e) }
    }

    private fun alarmIntent(ctx: Context, inst: TriggerInstance, scheduledFor: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, uniqueName(inst).hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM).setData(Uri.parse("mob8n://alarm/${inst.workflowId}/${inst.nodeId}"))
            .putExtra("workflowId", inst.workflowId).putExtra("nodeId", inst.nodeId).putExtra("scheduledFor", scheduledFor),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    private fun cancelAlarm(ctx: Context, inst: TriggerInstance) {
        val pi = alarmIntent(ctx, inst, 0L)
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    /** Shared by ScheduleWorker and AlarmReceiver after a fire: re-arm repeating modes, disable after `once`. */
    suspend fun afterFire(ctx: Context, inst: TriggerInstance) {
        val engine = com.mob8n.Mob8NApp.of(ctx).engine
        when (spec.pStr(inst.params, "mode") ?: "daily") {
            "daily", "weekly" -> schedule(engine.host, inst)
            "once" -> try { engine.setEnabled(inst.workflowId, false) } catch (e: Exception) { logW("once: disable", e) }
        }
    }
}

/** CalendarScanWorker every 15 min per instance; fires once per calendar instance id (putState, ttl 1 day). */
object CalendarUpcomingTrigger : TriggerNode() {
    override val hosting = Hosting.WORK_MANAGER
    override val spec = NodeSpec(
        id = "trigger.calendar_upcoming", name = "Calendar Event Soon", kind = NodeKind.TRIGGER,
        description = "Fires shortly before a calendar event starts (checked every 15 minutes).",
        params = listOf(
            number("minutesBefore", "Minutes before", 10.0, min = 0.0, max = 1440.0),
            text("calendarNameRegex", "Calendar name matches (regex)", templated = false),
        ),
        inputs = emptyList(), gates = listOf(Gate.Permission(Manifest.permission.READ_CALENDAR)),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = regexOk(spec.pStr(params, "calendarNameRegex"), event.str("calendar"))

    override fun schedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try {
            val req = PeriodicWorkRequestBuilder<CalendarScanWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("workflowId" to instance.workflowId, "nodeId" to instance.nodeId)).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(uniqueName(instance), ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (e: Exception) { logW("calendar schedule", e) }
    }
    override fun unschedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try { WorkManager.getInstance(ctx).cancelUniqueWork(uniqueName(instance)) } catch (e: Exception) { logW("calendar unschedule", e) }
    }
}
