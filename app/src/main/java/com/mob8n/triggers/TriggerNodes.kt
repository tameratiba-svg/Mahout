package com.mob8n.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.mob8n.core.LOG_TAG
import com.mob8n.core.Node
import com.mob8n.core.NodeSpec
import com.mob8n.core.asBool
import com.mob8n.core.asDouble
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Every trigger node, in DESIGN §4.1 order. */
object TriggerNodes {
    val all: List<Node> = listOf(
        NowPlayingTrigger,
        NotificationPostedTrigger, NotificationRemovedTrigger,
        ShareTrigger, TileTrigger, ShortcutTrigger, ManualTrigger, CalledByWorkflowTrigger, NotificationActionTrigger,
        ScheduleTrigger,
        BootTrigger, ChargerTrigger,
        BatteryLevelTrigger, NetworkTrigger, BluetoothTrigger, HeadsetTrigger, ScreenTrigger, UnlockedTrigger, AirplaneTrigger,
        RingerTrigger, VolumeTrigger, DndTrigger, PowerSaveTrigger,
        TimeChangedTrigger, LocaleTrigger, PackageTrigger, DownloadTrigger,
        NewPhotoTrigger, CalendarUpcomingTrigger,
        PhoneCallTrigger, SmsTrigger,
        GeofenceTrigger, ShakeTrigger, NfcTrigger, ClipboardTrigger, WebhookTrigger,
    )
}

// ---- lane-internal helpers (accepts()/toItems() are pure so the JVM tests can drive them) ----

internal fun now(): Long = System.currentTimeMillis()
internal fun logT(msg: String) { Log.i(LOG_TAG, "[triggers] $msg") }
internal fun logW(msg: String, e: Throwable? = null) { Log.w(LOG_TAG, "[triggers] $msg", e) }

private fun NodeSpec.raw(params: JsonObject, key: String): JsonElement? = params[key]?.takeUnless { it is JsonNull } ?: param(key)?.default
/** Param string (instance value else schema default); blank == null. Untemplated: there is no item yet when a trigger filters. */
internal fun NodeSpec.pStr(params: JsonObject, key: String): String? = raw(params, key).asTextOrNull()?.takeIf { it.isNotBlank() }
internal fun NodeSpec.pBool(params: JsonObject, key: String): Boolean = raw(params, key).asBool() ?: false
internal fun NodeSpec.pNum(params: JsonObject, key: String): Double? = raw(params, key).asDouble()
internal fun NodeSpec.pLabels(params: JsonObject, key: String): List<String> =
    (raw(params, key) as? JsonArray)?.mapNotNull { it.asTextOrNull() }?.filter { it.isNotBlank() } ?: emptyList()

/** Blank pattern matches everything; otherwise regex find on value (null value never matches). Invalid regex throws -> executor logs and rejects. */
internal fun regexOk(pattern: String?, value: String?): Boolean =
    pattern.isNullOrBlank() || (value != null && Regex(pattern).containsMatchIn(value))

/** ENUM filter with an "either"/"any" wildcard. */
internal fun wants(selected: String?, actual: String, anyWord: String = "either"): Boolean =
    selected == null || selected == anyWord || selected == actual

internal fun filterOf(vararg actions: String): IntentFilter = IntentFilter().apply { actions.forEach { addAction(it) } }

/**
 * ONE registerReceiver for a set of system-sent (protected) broadcasts. RECEIVER_EXPORTED because the sender is the OS,
 * never another app (protected broadcasts cannot be spoofed). Sticky snapshots delivered at registration are dropped unless
 * [keepSticky] (a snapshot is a state, not an event).
 */
internal fun registerSystem(ctx: Context, filter: IntentFilter, keepSticky: Boolean = false, onReceive: (Intent) -> Unit): AutoCloseable {
    val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (isInitialStickyBroadcast && !keepSticky) return
            try { onReceive(i) } catch (e: Exception) { logW("receiver ${i.action}", e) }
        }
    }
    ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_EXPORTED)
    return AutoCloseable { try { ctx.unregisterReceiver(r) } catch (_: Exception) {} }
}
