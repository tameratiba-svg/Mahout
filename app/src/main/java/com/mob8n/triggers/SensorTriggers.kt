package com.mob8n.triggers

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
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
import com.mob8n.core.num
import com.mob8n.core.number
import kotlinx.serialization.json.JsonObject
import kotlin.math.sqrt

/** LocationManager.addProximityAlert -> ProximityReceiver (action com.mob8n.PROXIMITY, exported=false). Re-added on boot via rearmAll. */
object GeofenceTrigger : TriggerNode() {
    const val ACTION_PROXIMITY = "com.mob8n.PROXIMITY"

    override val hosting = Hosting.WORK_MANAGER
    override val spec = NodeSpec(
        id = "trigger.geofence", name = "Location Enter/Exit", kind = NodeKind.TRIGGER,
        description = "Fires when the device enters or leaves a circle around a location (proximity alert).",
        params = listOf(
            number("lat", "Latitude", required = true, min = -90.0, max = 90.0),
            number("lng", "Longitude", required = true, min = -180.0, max = 180.0),
            number("radiusM", "Radius (m)", 150.0, min = 20.0, max = 100_000.0),
            choice("event", "Event", listOf("enter", "exit", "either"), "enter"),
        ),
        inputs = emptyList(),
        gates = listOf(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION), Gate.Permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION), Gate.LocationOn),
        optional = true,
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean =
        wants(spec.pStr(params, "event"), if (event.bool("entering") == true) "enter" else "exit")

    override fun schedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { logW("geofence: no location permission"); return }
            val lat = spec.pNum(instance.params, "lat") ?: return
            val lng = spec.pNum(instance.params, "lng") ?: return
            val radius = (spec.pNum(instance.params, "radiusM") ?: 150.0).toFloat()
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return
            val pi = proximityIntent(ctx, instance, lat, lng, radius.toDouble())
            lm.removeProximityAlert(pi)
            lm.addProximityAlert(lat, lng, radius, -1L, pi)
        } catch (e: Exception) { logW("geofence schedule", e) }
    }
    override fun unschedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try {
            val pi = proximityIntent(ctx, instance, 0.0, 0.0, 0.0)
            ctx.getSystemService(LocationManager::class.java)?.removeProximityAlert(pi)
            pi.cancel()
        } catch (e: Exception) { logW("geofence unschedule", e) }
    }
    private fun proximityIntent(ctx: Context, inst: TriggerInstance, lat: Double, lng: Double, radiusM: Double): PendingIntent = PendingIntent.getBroadcast(
        ctx, uniqueName(inst).hashCode(),
        Intent(ctx, ProximityReceiver::class.java).setAction(ACTION_PROXIMITY).setData(Uri.parse("mob8n://geofence/${inst.workflowId}/${inst.nodeId}"))
            .putExtra("workflowId", inst.workflowId).putExtra("nodeId", inst.nodeId).putExtra("lat", lat).putExtra("lng", lng).putExtra("radiusM", radiusM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Accelerometer; fires when total g-force exceeds the sensitivity threshold, 1.5 s cooldown. */
object ShakeTrigger : TriggerNode() {
    override val hosting = Hosting.HOST_ATTACHED
    override val spec = NodeSpec(
        id = "trigger.shake", name = "Shake", kind = NodeKind.TRIGGER,
        description = "Fires when the device is shaken.",
        params = listOf(choice("sensitivity", "Sensitivity", listOf("low", "medium", "high"), "medium")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost, Gate.Feature(PackageManager.FEATURE_SENSOR_ACCELEROMETER, "Accelerometer")), optional = true,
    )
    /** Total acceleration in g needed to count as a shake. */
    fun thresholdG(sensitivity: String?): Double = when (sensitivity) { "low" -> 2.8; "high" -> 1.7; else -> 2.2 }
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = (event.num("gForce") ?: 0.0) >= thresholdG(spec.pStr(params, "sensitivity"))

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run { logW("shake: no accelerometer"); return null }
        val minT = instances.minOf { thresholdG(spec.pStr(it.params, "sensitivity")) }
        var lastFire = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = e.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val t = now()
                if (g >= minT && t - lastFire >= 1_500) { lastFire = t; host.fire(spec.id, item("gForce" to Math.round(g * 100.0) / 100.0, "at" to t)) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return AutoCloseable { try { sm.unregisterListener(listener) } catch (_: Exception) {} }
    }
}
