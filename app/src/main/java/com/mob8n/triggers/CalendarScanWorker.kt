package com.mob8n.triggers

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mob8n.Mob8NApp
import com.mob8n.core.item
import kotlinx.serialization.json.JsonPrimitive

/** trigger.calendar_upcoming: every 15 min scan [now, now + minutesBefore + 15 min]; fire once per calendar instance id (state ttl 1 day). */
class CalendarScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wf = inputData.getString("workflowId") ?: return Result.failure()
        val node = inputData.getString("nodeId") ?: return Result.failure()
        val engine = Mob8NApp.of(applicationContext).engine
        engine.awaitReady()
        val inst = engine.host.instancesOf(CalendarUpcomingTrigger.spec.id).firstOrNull { it.workflowId == wf && it.nodeId == node }
            ?: run { logT("calendar worker for disabled $wf/$node ignored"); return Result.success() }
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            logW("calendar_upcoming: READ_CALENDAR missing"); return Result.success()
        }
        try {
            val spec = CalendarUpcomingTrigger.spec
            val minutesBefore = (spec.pNum(inst.params, "minutesBefore") ?: 10.0).toLong().coerceIn(0, 1440)
            val nowMs = now()
            val end = nowMs + (minutesBefore + 15) * 60_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { ContentUris.appendId(it, nowMs); ContentUris.appendId(it, end); it.build() }
            val proj = arrayOf(
                CalendarContract.Instances._ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID,
            )
            val scope = "${inst.workflowId}:${inst.nodeId}"
            applicationContext.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val instanceId = c.getLong(0)
                    val begin = c.getLong(2)
                    if (begin < nowMs) continue                              // already started
                    val key = "cal:$instanceId"
                    if (engine.persistence.getState(scope, key) != null) continue
                    val event = item(
                        "title" to c.getString(1), "begin" to begin, "end" to c.getLong(3), "location" to c.getString(4), "description" to c.getString(5),
                        "calendar" to c.getString(6), "allDay" to (c.getInt(7) == 1), "eventId" to c.getLong(8),
                    )
                    if (!CalendarUpcomingTrigger.accepts(inst.params, event)) continue
                    engine.persistence.putState(scope, key, JsonPrimitive(begin), ttlMs = 24 * 3600_000L)
                    engine.hub.fireWorkflow(inst.workflowId, inst.nodeId, CalendarUpcomingTrigger.toItems(inst.params, event), hostedByCaller = true).await()   // F16: the worker is the host
                }
            }
        } catch (e: Exception) { logW("CalendarScanWorker $wf/$node", e) }
        return Result.success()
    }
}
