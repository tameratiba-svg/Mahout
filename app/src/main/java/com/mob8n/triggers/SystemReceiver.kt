package com.mob8n.triggers

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.mob8n.Mob8NApp
import com.mob8n.ai.ChatRunner
import com.mob8n.ai.HarnessPrefs
import com.mob8n.core.item
import com.mob8n.core.l
import com.mob8n.core.s
import com.mob8n.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * Manifest-exempt system broadcasts only (boot, MY_PACKAGE_REPLACED, TIMEZONE/TIME_SET, LOCALE, PHONE_STATE, SMS, DOWNLOAD_COMPLETE). goAsync(), <= 9 s,
 * every branch guarded. Non-exempt actions (POWER_*, BATTERY_LOW/OKAY, PACKAGE_*) live in runtime receivers (K1/F17/F18); proximity alerts in ProximityReceiver.
 */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val engine = Mob8NApp.of(app).engine
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { withTimeout(9_000) { handle(app, engine, intent, action) } }
            catch (e: Exception) { logW("SystemReceiver $action", e) }
            finally { pending.finish() }
        }
    }

    private suspend fun handle(ctx: Context, engine: Engine, i: Intent, action: String) {
        engine.awaitReady()   // F14: cold process; instancesOf()/fire() need the first cache fill
        val host = engine.host
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                clearBypass(ctx, engine)
                host.fire(BootTrigger.spec.id, item("at" to now()))
                rearm(engine, "boot")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> rearm(engine, "package replaced")
            Intent.ACTION_TIMEZONE_CHANGED -> host.fire(TimeChangedTrigger.spec.id, TimeChangedTrigger.event("timezone"))
            Intent.ACTION_TIME_CHANGED -> { clearBypass(ctx, engine); host.fire(TimeChangedTrigger.spec.id, TimeChangedTrigger.event("time_set")) }
            Intent.ACTION_LOCALE_CHANGED -> host.fire(LocaleTrigger.spec.id, item("locale" to Locale.getDefault().toLanguageTag()))
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L); if (id < 0) return
                val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (!c.moveToFirst()) return
                    val status = when (c.l(DownloadManager.COLUMN_STATUS)?.toInt()) {
                        DownloadManager.STATUS_SUCCESSFUL -> "successful"; DownloadManager.STATUS_FAILED -> "failed"; DownloadManager.STATUS_PAUSED -> "paused"
                        DownloadManager.STATUS_RUNNING -> "running"; DownloadManager.STATUS_PENDING -> "pending"; else -> "unknown"
                    }
                    host.fire(DownloadTrigger.spec.id, item(
                        "downloadId" to id, "title" to c.s(DownloadManager.COLUMN_TITLE), "uri" to c.s(DownloadManager.COLUMN_LOCAL_URI),
                        "mimeType" to c.s(DownloadManager.COLUMN_MEDIA_TYPE), "status" to status, "bytes" to c.l(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    ))
                }
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                    TelephonyManager.EXTRA_STATE_RINGING -> "ringing"; TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"; TelephonyManager.EXTRA_STATE_IDLE -> "idle"; else -> return
                }
                host.fire(PhoneCallTrigger.spec.id, item("state" to state, "at" to now()))
            }
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)?.filterNotNull().orEmpty()
                if (msgs.isEmpty()) return
                host.fire(SmsTrigger.spec.id, item(
                    "from" to msgs.first().originatingAddress, "body" to msgs.joinToString("") { it.messageBody ?: "" }, "at" to now(),
                ))
            }
            else -> logT("SystemReceiver: unhandled $action")
        }
    }

    /** DESIGN4P P7 wall-clock hardening: a Bypass never survives a clock change or a boot (the only triggers -> ai import). */
    private suspend fun clearBypass(ctx: Context, engine: Engine) {
        try { HarnessPrefs.clearBypass(ctx) } catch (e: Exception) { logW("clearBypass", e) }
        try { ChatRunner.clearConversationBypass(engine) } catch (e: Exception) { logW("clearConversationBypass", e) }
    }

    /**
     * BOOT / MY_PACKAGE_REPLACED: start() is a no-op on a warm process, so re-arm explicitly (same as HousekeepingWorker): every WORK_MANAGER
     * schedule incl. exact alarms and geofences, attachments, host start when runtime triggers need one, shortcuts. Inside the 9 s goAsync budget.
     */
    private suspend fun rearm(engine: Engine, reason: String) {
        try { engine.start() } catch (e: Exception) { logW("engine.start on $reason", e) }
        try { engine.hub.rearmAll() } catch (e: Exception) { logW("rearm on $reason", e) }
        engine.refreshStatus()
    }
}
