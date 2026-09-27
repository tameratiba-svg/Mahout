package com.mob8n.triggers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TriggerHost
import com.mob8n.core.TriggerInstance
import com.mob8n.core.TriggerNode
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.item
import com.mob8n.core.labelOrNull
import com.mob8n.core.str
import com.mob8n.core.text
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.TimeZone

// Raw events for these come from SystemReceiver (manifest-exempt broadcasts). accepts() does the per-instance filtering.

object BootTrigger : TriggerNode() {
    override val hosting = Hosting.MANIFEST
    override val spec = NodeSpec(
        id = "trigger.boot", name = "Device Booted", kind = NodeKind.TRIGGER,
        description = "Fires after the device finishes booting.", inputs = emptyList(),
    )
}

/**
 * K1: ACTION_POWER_CONNECTED/DISCONNECTED are not implicit-broadcast exempt, so a manifest receiver never sees them (confirmed on Android 17).
 * Live host -> runtime receiver (attach) covers both events. No host -> ChargerWorker (WorkManager setRequiresCharging) covers `connected`;
 * hostless `disconnected` has no producer (Gate.LiveHost). The work item is armed by the hub (schedule/rearm) and re-arms itself after each fire.
 */
object ChargerTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.charger", name = "Charger", kind = NodeKind.TRIGGER,
        description = "Fires when the charger is connected or disconnected.",
        params = listOf(choice("event", "Event", listOf("connected", "disconnected", "either"), "either",
            help = "'connected' also fires without a background host (WorkManager); 'disconnected' needs one")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean =
        wants(spec.pStr(params, "event"), if (event.bool("connected") == true) "connected" else "disconnected")

    /** Raw event shared by SystemReceiver, attach() and ChargerWorker (level/plugged from the sticky battery snapshot). */
    fun event(ctx: Context, connected: Boolean): JsonObject {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return item(
            "connected" to connected, "level" to b?.let(BatteryLevelTrigger::levelOf),
            "plugged" to (b?.let(BatteryLevelTrigger::pluggedOf) ?: if (connected) "other" else "none"), "at" to now(),
        )
    }

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        for (inst in instances) schedule(host, inst)   // arm the hostless fallback too; it outlives this host on purpose
        return registerSystem(ctx, filterOf(Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED)) { i ->
            host.fire(spec.id, event(ctx, i.action == Intent.ACTION_POWER_CONNECTED))
        }
    }

    /** Hostless `connected` fallback (the hub treats trigger.charger as durable: enable/boot/housekeeping arm it, disable cancels it), unique "trig:<wf>:<node>:chg". KEEP: re-arming must never cancel a pending/waiting worker. */
    override fun schedule(host: TriggerHost, instance: TriggerInstance) = schedule(host, instance, ExistingWorkPolicy.KEEP)
    fun schedule(host: TriggerHost, instance: TriggerInstance, policy: ExistingWorkPolicy) {
        val ctx = host.android ?: return
        try {
            if (spec.pStr(instance.params, "event") == "disconnected") { unschedule(host, instance); return }
            val req = OneTimeWorkRequestBuilder<ChargerWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .setInputData(workDataOf("workflowId" to instance.workflowId, "nodeId" to instance.nodeId)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(workName(instance), policy, req)
        } catch (e: Exception) { logW("charger schedule", e) }
    }
    override fun unschedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try { WorkManager.getInstance(ctx).cancelUniqueWork(workName(instance)) } catch (e: Exception) { logW("charger unschedule", e) }
    }
    fun workName(inst: TriggerInstance): String = uniqueName(inst) + ":chg"
}

/** timezone/time_set arrive via the manifest receiver; date needs a runtime receiver (ACTION_DATE_CHANGED is not manifest-exempt). */
object TimeChangedTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.time_changed", name = "Time Changed", kind = NodeKind.TRIGGER,
        description = "Fires when the time zone, clock or date changes.",
        params = listOf(choice("event", "Event", listOf("timezone", "time_set", "date", "any"), "any")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    fun event(kind: String): JsonObject = item("event" to kind, "timezone" to TimeZone.getDefault().id, "date" to LocalDate.now().toString())

    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "event"), event.str("event") ?: "", "any")

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        if (instances.none { (spec.pStr(it.params, "event") ?: "any") in setOf("date", "any") }) return null
        return registerSystem(ctx, filterOf(Intent.ACTION_DATE_CHANGED)) { host.fire(spec.id, event("date")) }
    }
}

object LocaleTrigger : TriggerNode() {
    override val hosting = Hosting.MANIFEST
    override val spec = NodeSpec(
        id = "trigger.locale", name = "Language Changed", kind = NodeKind.TRIGGER,
        description = "Fires when the device language or locale changes.", inputs = emptyList(),
    )
}

/**
 * F17: PACKAGE_ADDED/REMOVED/REPLACED are not implicit-broadcast exempt (API 26+), so the manifest receiver never fired; a live host registers them.
 * ponytail: package visibility = <queries> LAUNCHER apps only on 30+ (no QUERY_ALL_PACKAGES); upgrade path = QUERY_ALL_PACKAGES + Play declaration.
 */
object PackageTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.package", name = "App Installed/Removed", kind = NodeKind.TRIGGER,
        description = "Fires when an app is installed, updated or removed.",
        params = listOf(
            choice("event", "Event", listOf("installed", "removed", "updated", "any"), "any"),
            text("packageRegex", "Package matches (regex)", templated = false),
        ),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean =
        wants(spec.pStr(params, "event"), event.str("event") ?: "", "any") && regexOk(spec.pStr(params, "packageRegex"), event.str("packageName"))

    /** Broadcast action (+ EXTRA_REPLACING) -> event name; null = the ADDED/REMOVED halves of an update (REPLACED covers it). */
    fun eventKind(action: String, replacing: Boolean): String? = when (action) {
        Intent.ACTION_PACKAGE_REPLACED -> "updated"
        Intent.ACTION_PACKAGE_ADDED -> if (replacing) null else "installed"
        Intent.ACTION_PACKAGE_REMOVED -> if (replacing) null else "removed"
        else -> null
    }

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val filter = IntentFilter().apply {   // filterOf() cannot be used: PACKAGE_* only match with the data scheme
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        return registerSystem(ctx, filter) { i ->
            val pkg = i.data?.schemeSpecificPart ?: return@registerSystem
            val ev = eventKind(i.action ?: "", i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) ?: return@registerSystem
            host.fire(spec.id, item("packageName" to pkg, "event" to ev, "appName" to ctx.packageManager.labelOrNull(pkg)))
        }
    }
}

object DownloadTrigger : TriggerNode() {
    override val hosting = Hosting.MANIFEST
    override val spec = NodeSpec(
        id = "trigger.download", name = "Download Complete", kind = NodeKind.TRIGGER,
        description = "Fires when a download started by Mahout (action.download) completes.",
        inputs = emptyList(), optional = true,
    )
}

object PhoneCallTrigger : TriggerNode() {
    override val hosting = Hosting.MANIFEST
    override val spec = NodeSpec(
        id = "trigger.phone_call", name = "Phone Call", kind = NodeKind.TRIGGER,
        description = "Fires when the phone starts ringing, a call connects or ends (no caller number).",
        params = listOf(choice("event", "Event", listOf("ringing", "offhook", "idle", "any"), "ringing")),
        inputs = emptyList(), gates = listOf(Gate.Permission(Manifest.permission.READ_PHONE_STATE)), optional = true,
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "event"), event.str("state") ?: "", "any")
}

object SmsTrigger : TriggerNode() {
    override val hosting = Hosting.MANIFEST
    override val spec = NodeSpec(
        id = "trigger.sms", name = "SMS Received", kind = NodeKind.TRIGGER,
        description = "Fires when an SMS arrives (sensitive permission; off by default).",
        params = listOf(
            text("fromRegex", "Sender matches (regex)", templated = false),
            text("bodyRegex", "Body matches (regex)", templated = false),
        ),
        inputs = emptyList(), gates = listOf(Gate.Permission(Manifest.permission.RECEIVE_SMS)), optional = true,
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean =
        regexOk(spec.pStr(params, "fromRegex"), event.str("from")) && regexOk(spec.pStr(params, "bodyRegex"), event.str("body"))
}
