package com.mob8n.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mob8n.R
import com.mob8n.core.DECISION_APPROVE
import com.mob8n.core.DECISION_DENY
import com.mob8n.core.LOG_TAG
import com.mob8n.core.SuspendedRun

/** Engine-owned notifications: channels `approvals` + `host`, approval prompts (one button per choice), the FGS notification. */
object Notifs {
    const val CH_APPROVALS = "approvals"
    const val CH_HOST = "host"
    const val HOST_NOTIFICATION_ID = 1
    const val EXTRA_RUN_ID = "runId"
    const val EXTRA_DECISION = "decision"
    const val EXTRA_CONVERSATION_ID = "conversationId"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Workflows waiting for your decision"
        })
        nm.createNotificationChannel(NotificationChannel(CH_HOST, "Background host", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Mahout keeps automations running in the background"; setShowBadge(false)
        })
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Deep link mob8n://run/{id} into MainActivity (scaffold owns the intent filter). */
    fun openRunIntent(ctx: Context, runId: String): PendingIntent = PendingIntent.getActivity(
        ctx, runId.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse("mob8n://run/$runId")).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        flags(),
    )

    fun decisionIntent(ctx: Context, runId: String, decision: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, (runId + decision).hashCode(),
        Intent(ctx, ApprovalReceiver::class.java).putExtra(EXTRA_RUN_ID, runId).putExtra(EXTRA_DECISION, decision),
        flags(),
    )

    fun notificationId(runId: String): Int = 1000 + (runId.hashCode() and 0x7fffffff) % 1_000_000

    /** Posts the approval prompt; silently logs when notifications are disabled (POST_NOTIFICATIONS is a per-node gate elsewhere). */
    fun postApproval(ctx: Context, s: SuspendedRun) {
        val b = NotificationCompat.Builder(ctx, CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(s.title.ifBlank { s.reason }.take(200))
            .setContentText(s.text.take(400))
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.text.take(4000)))
            .setContentIntent(openRunIntent(ctx, s.runId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        for (choice in s.choices.take(3)) b.addAction(0, choice.replaceFirstChar { it.uppercase() }, decisionIntent(ctx, s.runId, choice))
        try { NotificationManagerCompat.from(ctx).notify(notificationId(s.runId), b.build()) }
        catch (e: SecurityException) { Log.w(LOG_TAG, "approval notification blocked: ${e.message}") }
    }

    fun cancelApproval(ctx: Context, runId: String) {
        try { NotificationManagerCompat.from(ctx).cancel(notificationId(runId)) } catch (_: Exception) {}
    }

    // ---- v4 chat approvals (DESIGN4 §5.7): same channel, Approve/Deny -> ApprovalReceiver -> Engine.chatDecision; body opens mob8n://chat/<id>
    fun chatNotificationId(conversationId: String): Int = 2000 + (conversationId.hashCode() and 0x7fffffff) % 1_000_000

    fun openChatIntent(ctx: Context, conversationId: String): PendingIntent = PendingIntent.getActivity(
        ctx, ("chat" + conversationId).hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse("mob8n://chat/$conversationId")).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        flags(),
    )

    fun chatDecisionIntent(ctx: Context, conversationId: String, decision: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, ("chat" + conversationId + decision).hashCode(),
        Intent(ctx, ApprovalReceiver::class.java).putExtra(EXTRA_CONVERSATION_ID, conversationId).putExtra(EXTRA_DECISION, decision),
        flags(),
    )

    fun postChatApproval(ctx: Context, conversationId: String, title: String, text: String) {
        val b = NotificationCompat.Builder(ctx, CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title.ifBlank { "Chat needs your approval" }.take(200))
            .setContentText(text.take(400))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(4000)))
            .setContentIntent(openChatIntent(ctx, conversationId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Approve all", chatDecisionIntent(ctx, conversationId, DECISION_APPROVE))
            .addAction(0, "Deny all", chatDecisionIntent(ctx, conversationId, DECISION_DENY))
        try { NotificationManagerCompat.from(ctx).notify(chatNotificationId(conversationId), b.build()) }
        catch (e: SecurityException) { Log.w(LOG_TAG, "chat approval notification blocked: ${e.message}") }
    }

    fun cancelChatApproval(ctx: Context, conversationId: String) {
        try { NotificationManagerCompat.from(ctx).cancel(chatNotificationId(conversationId)) } catch (_: Exception) {}
    }

    fun hostNotification(ctx: Context): Notification {
        val stop = PendingIntent.getService(ctx, 0, Intent(ctx, HostService::class.java).setAction(HostService.ACTION_STOP), flags())
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse("mob8n://workflows")).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags(),
        )
        return NotificationCompat.Builder(ctx, CH_HOST)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Mahout automations active")
            .setContentText("Keeping your enabled triggers listening")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
