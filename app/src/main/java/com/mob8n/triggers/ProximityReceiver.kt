package com.mob8n.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.mob8n.Mob8NApp
import com.mob8n.core.item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** trigger.geofence: explicit PendingIntent target of LocationManager.addProximityAlert (exported=false, F17). Same goAsync pattern as AlarmReceiver. */
class ProximityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceTrigger.ACTION_PROXIMITY) return
        val wf = intent.getStringExtra("workflowId") ?: return
        val node = intent.getStringExtra("nodeId") ?: return
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeout(9_000) {
                    val engine = Mob8NApp.of(ctx).engine
                    engine.awaitReady()
                    val host = engine.host
                    val inst = host.instancesOf(GeofenceTrigger.spec.id).firstOrNull { it.workflowId == wf && it.nodeId == node }
                        ?: run { logT("proximity alert for disabled $wf/$node ignored"); return@withTimeout }
                    val event = item(
                        "entering" to intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false),
                        "lat" to intent.getDoubleExtra("lat", 0.0), "lng" to intent.getDoubleExtra("lng", 0.0), "radiusM" to intent.getDoubleExtra("radiusM", 0.0),
                    )
                    if (GeofenceTrigger.accepts(inst.params, event)) withTimeoutOrNull(8_000) { host.fireWorkflow(wf, node, GeofenceTrigger.toItems(inst.params, event)).await() }
                }
            } catch (e: Exception) { logW("ProximityReceiver $wf/$node", e) }
            finally { pending.finish() }
        }
    }
}
