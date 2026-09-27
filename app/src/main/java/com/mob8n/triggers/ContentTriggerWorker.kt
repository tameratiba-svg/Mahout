package com.mob8n.triggers

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mob8n.Mob8NApp
import com.mob8n.core.TriggerInstance
import com.mob8n.core.asDouble
import com.mob8n.core.item
import com.mob8n.engine.Engine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * trigger.new_photo durable path: JobScheduler content-URI trigger (one-shot by nature) -> scan -> re-enqueue.
 * The ContentObserver fast path (NewPhotoTrigger.attach) calls the same scanNewPhotos(); putState("lastSeenId") + a mutex dedupe them.
 */
class ContentTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wf = inputData.getString("workflowId") ?: return Result.failure()
        val node = inputData.getString("nodeId") ?: return Result.failure()
        val engine = Mob8NApp.of(applicationContext).engine
        engine.awaitReady()
        val inst = engine.host.instancesOf(NewPhotoTrigger.spec.id).firstOrNull { it.workflowId == wf && it.nodeId == node }
            ?: run { logT("new_photo worker for disabled $wf/$node ignored"); return Result.success() }
        try { scanNewPhotos(applicationContext, engine, inst) } catch (e: Exception) { logW("new_photo scan", e) }
        try { enqueue(applicationContext, inst, replace = false) } catch (e: Exception) { logW("new_photo re-enqueue", e) }
        return Result.success()
    }

    companion object {
        /** replace=true from schedule() (enable/save); false from inside the worker (append after ourselves). */
        fun enqueue(ctx: Context, inst: TriggerInstance, replace: Boolean) {
            val req = OneTimeWorkRequestBuilder<ContentTriggerWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                        .build(),
                )
                .setInputData(workDataOf("workflowId" to inst.workflowId, "nodeId" to inst.nodeId))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(uniqueName(inst), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

private val scanMutex = Mutex()

/** Query images with _ID > lastSeenId (state scope "<wf>:<node>"), fire each accepted one, advance the marker. First run only sets the marker. */
internal suspend fun scanNewPhotos(ctx: Context, engine: Engine, inst: TriggerInstance) = scanMutex.withLock {
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) { logW("new_photo: images permission missing"); return@withLock }
    val scope = "${inst.workflowId}:${inst.nodeId}"
    val p = engine.persistence
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val cr = ctx.contentResolver
    val last = p.getState(scope, "lastSeenId").asDouble()?.toLong()
    if (last == null) {
        val max = cr.query(uri, arrayOf(MediaStore.Images.Media._ID), queryArgs(null, "${MediaStore.Images.Media._ID} DESC", 1), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        p.putState(scope, "lastSeenId", JsonPrimitive(max))
        return@withLock
    }
    val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
    val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, pathCol, MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_ADDED)
    var newest: Long = last
    cr.query(uri, proj, queryArgs("${MediaStore.Images.Media._ID} > ?", "${MediaStore.Images.Media._ID} ASC", 20, last.toString()), null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val path = c.getString(2)
            val event = item(
                "uri" to ContentUris.withAppendedId(uri, id).toString(), "displayName" to c.getString(1), "relativePath" to path,
                "mimeType" to c.getString(3), "width" to c.getInt(4), "height" to c.getInt(5), "dateAdded" to c.getLong(6) * 1000,
                "isScreenshot" to (path?.contains("Screenshots", ignoreCase = true) == true || c.getString(1)?.startsWith("Screenshot", ignoreCase = true) == true),
            )
            newest = maxOf(newest, id)
            if (NewPhotoTrigger.accepts(inst.params, event)) engine.hub.fireWorkflow(inst.workflowId, inst.nodeId, NewPhotoTrigger.toItems(inst.params, event), hostedByCaller = true).await()   // F16: worker or live host holds the process
        }
    }
    if (newest != last) p.putState(scope, "lastSeenId", JsonPrimitive(newest))
}

private fun queryArgs(selection: String?, sort: String, limit: Int, vararg args: String): Bundle = Bundle().apply {
    if (selection != null) { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection); putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(*args)) }
    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
}
