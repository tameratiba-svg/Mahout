package com.mob8n.triggers

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.work.WorkManager
import com.mob8n.Mob8NApp
import com.mob8n.core.Gate
import com.mob8n.core.Hosting
import com.mob8n.core.Items
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeSpec
import com.mob8n.core.TriggerHost
import com.mob8n.core.TriggerInstance
import com.mob8n.core.TriggerNode
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.item
import com.mob8n.core.number
import com.mob8n.core.str
import com.mob8n.core.num
import com.mob8n.core.text
import com.mob8n.core.without
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Runtime-registered receivers / host-attached listeners. attach() is called ONCE per spec with every enabled instance (main thread);
// it registers ONE receiver and fires raw events; the hub then runs accepts()/toItems() per instance.

object BatteryLevelTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.battery_level", name = "Battery Level", kind = NodeKind.TRIGGER,
        description = "Fires when the battery crosses a level, or when Android reports battery low.",
        params = listOf(
            choice("mode", "Mode", listOf("below", "above", "system_low"), "below"),
            number("percent", "Percent", 20.0, min = 1.0, max = 100.0),
        ),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )

    /** Edge detection: previous vs current level around the threshold (both carried in the event). BATTERY_LOW/OKAY events only serve system_low. */
    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val mode = spec.pStr(params, "mode") ?: "below"
        val sys = event.str("systemEvent")
        if (mode == "system_low") return sys == "low"
        if (sys != null) return false
        val level = event.num("level")?.toInt() ?: return false
        val prev = event.num("previous")?.toInt() ?: return false
        val t = (spec.pNum(params, "percent") ?: 20.0).toInt()
        return if (mode == "below") prev > t && level <= t else prev < t && level >= t
    }
    override fun toItems(params: JsonObject, event: JsonObject): Items = listOf(event.without(listOf("previous", "systemEvent")))

    // ponytail: previous level kept in memory for the host's lifetime (not putState: attach() has no persistence); upgrade = engine passes persistence into TriggerHost
    // ponytail: system_low needs a live host too (F18: BATTERY_LOW/OKAY are not manifest-exempt; LiveHost gate already declared); upgrade path = WorkManager periodic BatteryManager poll when no host
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val modes = instances.map { spec.pStr(it.params, "mode") ?: "below" }
        var prev: Int? = null
        val threshold = if (modes.any { it != "system_low" }) registerSystem(ctx, filterOf(Intent.ACTION_BATTERY_CHANGED), keepSticky = true) { i ->
            val level = levelOf(i) ?: return@registerSystem
            val p = prev
            prev = level
            if (p == null || p == level) return@registerSystem
            host.fire(spec.id, item("level" to level, "previous" to p, "charging" to chargingOf(i), "at" to now()))
        } else null
        val system = if ("system_low" in modes) registerSystem(ctx, filterOf(Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY)) { i ->
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            host.fire(spec.id, item(
                "level" to b?.let(::levelOf), "charging" to (b?.let(::chargingOf) ?: false), "at" to now(),
                "systemEvent" to if (i.action == Intent.ACTION_BATTERY_LOW) "low" else "okay",
            ))
        } else null
        return AutoCloseable { threshold?.close(); system?.close() }
    }

    fun levelOf(i: Intent): Int? {
        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (raw < 0 || scale <= 0) null else raw * 100 / scale
    }
    fun chargingOf(i: Intent): Boolean = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
    fun pluggedOf(i: Intent): String = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"; BatteryManager.BATTERY_PLUGGED_USB -> "usb"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; 0 -> "none"; else -> "other"
    }
}

object NetworkTrigger : TriggerNode() {
    override val hosting = Hosting.HOST_ATTACHED
    override val spec = NodeSpec(
        id = "trigger.network", name = "Network Changed", kind = NodeKind.TRIGGER,
        description = "Fires when the device connects to or loses a network (Wi-Fi SSID needs location permission).",
        params = listOf(
            choice("event", "Event", listOf("connected", "disconnected", "either"), "connected"),
            choice("transport", "Transport", listOf("any", "wifi", "cellular"), "any"),
            text("ssidMatch", "Wi-Fi name matches (regex)", templated = false, help = "Needs location permission + location on"),
        ),
        inputs = emptyList(), gates = listOf(Gate.LiveHost, Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION)), Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)), Gate.LocationOn),
    )

    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val connected = event.bool("connected") == true
        if (!wants(spec.pStr(params, "event"), if (connected) "connected" else "disconnected")) return false
        val transport = spec.pStr(params, "transport") ?: "any"
        if (transport != "any" && event.str("transport") != transport) return false
        val ssidMatch = spec.pStr(params, "ssidMatch") ?: return true
        val ssid = event.str("ssid") ?: return false
        return Regex(ssidMatch).containsMatchIn(ssid)
    }

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val wantSsid = instances.any { spec.pStr(it.params, "ssidMatch") != null }
        val cb = NetCb.create(ctx, host, wantSsid, initialExpected = cm.activeNetwork != null)
        cm.registerDefaultNetworkCallback(cb)
        return AutoCloseable { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
    }

    private class NetCb : ConnectivityManager.NetworkCallback {
        private val ctx: Context; private val host: TriggerHost; private val wantSsid: Boolean
        private var skipInitial: Boolean
        private var announced: Network? = null
        private var lastTransport = "none"

        constructor(ctx: Context, host: TriggerHost, wantSsid: Boolean, skipInitial: Boolean) : super() {
            this.ctx = ctx; this.host = host; this.wantSsid = wantSsid; this.skipInitial = skipInitial
        }
        constructor(ctx: Context, host: TriggerHost, wantSsid: Boolean, skipInitial: Boolean, flags: Int) : super(flags) {
            this.ctx = ctx; this.host = host; this.wantSsid = wantSsid; this.skipInitial = skipInitial
        }

        companion object {
            fun create(ctx: Context, host: TriggerHost, wantSsid: Boolean, initialExpected: Boolean): NetCb =
                if (Build.VERSION.SDK_INT >= 31) NetCb(ctx, host, wantSsid, initialExpected, ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
                else NetCb(ctx, host, wantSsid, initialExpected)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == announced) return
            announced = network
            lastTransport = transportOf(caps)
            if (skipInitial) { skipInitial = false; return }   // registration snapshot is a state, not a transition
            host.fire(NetworkTrigger.spec.id, item(
                "connected" to true, "transport" to lastTransport, "ssid" to ssidOf(caps),
                "metered" to !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), "at" to now(),
            ))
        }

        override fun onLost(network: Network) {
            if (network != announced) return
            announced = null
            host.fire(NetworkTrigger.spec.id, item("connected" to false, "transport" to lastTransport, "ssid" to null, "metered" to null, "at" to now()))
        }

        private fun transportOf(c: NetworkCapabilities) = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

        @Suppress("DEPRECATION")
        private fun ssidOf(c: NetworkCapabilities): String? {
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (wantSsid) logW("network: ssidMatch set but location permission missing; ssid=null")
                return null
            }
            val raw = try {
                if (Build.VERSION.SDK_INT >= 31) (c.transportInfo as? WifiInfo)?.ssid
                else ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
            } catch (e: Exception) { logW("network: ssid read failed", e); null }
            return raw?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" || it == "0x" }
        }
    }
}

object BluetoothTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.bluetooth", name = "Bluetooth Device", kind = NodeKind.TRIGGER,
        description = "Fires when a Bluetooth device connects or disconnects.",
        params = listOf(
            choice("event", "Event", listOf("connected", "disconnected", "either"), "connected"),
            text("nameMatch", "Device name matches (regex)", templated = false),
            bool("audioOnly", "Audio devices only", false),
        ),
        inputs = emptyList(),
        gates = listOf(Gate.LiveHost, Gate.Advisory(Gate.Permission(Manifest.permission.BLUETOOTH_CONNECT))),
    )

    override fun accepts(params: JsonObject, event: JsonObject): Boolean {
        val connected = event.bool("connected") == true
        if (!wants(spec.pStr(params, "event"), if (connected) "connected" else "disconnected")) return false
        if (spec.pBool(params, "audioOnly") && event.bool("isAudio") != true) return false
        return regexOk(spec.pStr(params, "nameMatch"), event.str("name"))
    }

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        return registerSystem(ctx, filterOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED)) { i ->
            val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val name = try { dev?.name } catch (e: SecurityException) { null }   // BLUETOOTH_CONNECT missing on 31+
            val isAudio = try { dev?.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO } catch (e: SecurityException) { false }
            host.fire(spec.id, item("name" to name, "address" to dev?.address, "connected" to (i.action == BluetoothDevice.ACTION_ACL_CONNECTED), "isAudio" to isAudio, "at" to now()))
        }
    }
}

object HeadsetTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.headset", name = "Wired Headset", kind = NodeKind.TRIGGER,
        description = "Fires when a wired headset is plugged in or unplugged.",
        params = listOf(choice("event", "Event", listOf("plugged", "unplugged", "either"), "plugged")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean =
        wants(spec.pStr(params, "event"), if (event.bool("plugged") == true) "plugged" else "unplugged")

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        return registerSystem(ctx, filterOf(AudioManager.ACTION_HEADSET_PLUG)) { i ->
            host.fire(spec.id, item("plugged" to (i.getIntExtra("state", 0) == 1), "hasMic" to (i.getIntExtra("microphone", 0) == 1), "name" to i.getStringExtra("name")))
        }
    }
}

object ScreenTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.screen", name = "Screen On/Off", kind = NodeKind.TRIGGER,
        description = "Fires when the screen turns on or off.",
        params = listOf(choice("event", "Event", listOf("on", "off", "either"), "on")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "event"), if (event.bool("on") == true) "on" else "off")
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        return registerSystem(ctx, filterOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF)) { i ->
            host.fire(spec.id, item("on" to (i.action == Intent.ACTION_SCREEN_ON), "at" to now()))
        }
    }
}

object UnlockedTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.unlocked", name = "Device Unlocked", kind = NodeKind.TRIGGER,
        description = "Fires when the user unlocks the device.", inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        return registerSystem(ctx, filterOf(Intent.ACTION_USER_PRESENT)) { host.fire(spec.id, item("at" to now())) }
    }
}

object AirplaneTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.airplane", name = "Airplane Mode", kind = NodeKind.TRIGGER,
        description = "Fires when airplane mode is switched on or off.",
        params = listOf(choice("event", "Event", listOf("on", "off", "either"), "either")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "event"), if (event.bool("on") == true) "on" else "off")
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        return registerSystem(ctx, filterOf(Intent.ACTION_AIRPLANE_MODE_CHANGED)) { i -> host.fire(spec.id, item("on" to i.getBooleanExtra("state", false))) }
    }
}

object RingerTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.ringer", name = "Ringer Mode", kind = NodeKind.TRIGGER,
        description = "Fires when the ringer mode changes (normal, vibrate, silent).",
        params = listOf(choice("mode", "Mode", listOf("any", "normal", "vibrate", "silent"), "any")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "mode"), event.str("mode") ?: "", "any")
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        return registerSystem(ctx, filterOf(AudioManager.RINGER_MODE_CHANGED_ACTION)) { i ->
            val mode = when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; AudioManager.RINGER_MODE_SILENT -> "silent"; else -> return@registerSystem
            }
            host.fire(spec.id, item("mode" to mode))
        }
    }
}

object VolumeTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.volume", name = "Volume Changed", kind = NodeKind.TRIGGER,
        description = "Fires when a volume level changes (uses a hidden but stable system broadcast).",
        params = listOf(choice("stream", "Stream", listOf("any", "music", "ring", "alarm", "notification"), "any")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost), optional = true,
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "stream"), event.str("stream") ?: "", "any")
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val am = ctx.getSystemService(AudioManager::class.java)
        return registerSystem(ctx, filterOf("android.media.VOLUME_CHANGED_ACTION")) { i ->
            val type = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val level = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (type < 0 || level < 0 || level == prev) return@registerSystem
            val stream = when (type) {
                AudioManager.STREAM_MUSIC -> "music"; AudioManager.STREAM_RING -> "ring"; AudioManager.STREAM_ALARM -> "alarm"; AudioManager.STREAM_NOTIFICATION -> "notification"; else -> "other"
            }
            val max = try { am?.getStreamMaxVolume(type) } catch (e: Exception) { null }
            host.fire(spec.id, item("stream" to stream, "level" to level, "previous" to prev.takeIf { it >= 0 }, "max" to max))
        }
    }
}

object DndTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.dnd", name = "Do Not Disturb", kind = NodeKind.TRIGGER,
        description = "Fires when Do Not Disturb is turned on or off.",
        params = listOf(choice("event", "Event", listOf("on", "off", "either"), "either")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "event"), if (event.bool("dndOn") == true) "on" else "off")
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        var last: Int? = null
        return registerSystem(ctx, filterOf(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)) {
            val f = nm.currentInterruptionFilter
            if (f == last) return@registerSystem
            last = f
            val name = when (f) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "all"; NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"; NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"; else -> "unknown"
            }
            host.fire(spec.id, item("dndOn" to (name != "all" && name != "unknown"), "filter" to name))
        }
    }
}

object PowerSaveTrigger : TriggerNode() {
    override val hosting = Hosting.RUNTIME_RECEIVER
    override val spec = NodeSpec(
        id = "trigger.power_save", name = "Battery Saver", kind = NodeKind.TRIGGER,
        description = "Fires when battery saver is switched on or off.",
        params = listOf(choice("event", "Event", listOf("on", "off", "either"), "either")),
        inputs = emptyList(), gates = listOf(Gate.LiveHost),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = wants(spec.pStr(params, "event"), if (event.bool("on") == true) "on" else "off")
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return null
        return registerSystem(ctx, filterOf(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) { host.fire(spec.id, item("on" to pm.isPowerSaveMode)) }
    }
}

/**
 * Durable path: ContentTriggerWorker (JobScheduler content-URI trigger, re-enqueued after each run). Fast path: a ContentObserver
 * while a host lives (attach()). Both call scanNewPhotos(), which dedupes via putState("lastSeenId") under a lane-wide mutex.
 */
object NewPhotoTrigger : TriggerNode() {
    override val hosting = Hosting.WORK_MANAGER
    override val spec = NodeSpec(
        id = "trigger.new_photo", name = "New Photo / Screenshot", kind = NodeKind.TRIGGER,
        description = "Fires when a new image (photo or screenshot) appears in the media store.",
        params = listOf(choice("kind", "Kind", listOf("any", "screenshot", "camera"), "any")),
        inputs = emptyList(),
        gates = listOf(Gate.Permission(Manifest.permission.READ_MEDIA_IMAGES), Gate.Permission(Manifest.permission.READ_EXTERNAL_STORAGE)),
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = when (spec.pStr(params, "kind") ?: "any") {
        "screenshot" -> event.bool("isScreenshot") == true
        "camera" -> event.bool("isScreenshot") != true && (event.str("relativePath") ?: "").contains("DCIM", ignoreCase = true)
        else -> true
    }

    override fun schedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try { ContentTriggerWorker.enqueue(ctx, instance, replace = true) } catch (e: Exception) { logW("new_photo schedule", e) }
    }
    override fun unschedule(host: TriggerHost, instance: TriggerInstance) {
        val ctx = host.android ?: return
        try { WorkManager.getInstance(ctx).cancelUniqueWork(uniqueName(instance)) } catch (e: Exception) { logW("new_photo unschedule", e) }
    }

    /** Fast path while a host is alive (optional for the hub; safe to skip). */
    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val engine = Mob8NApp.of(ctx).engine
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val handler = Handler(Looper.getMainLooper())
        val scan = Runnable {
            scope.launch {
                for (inst in host.instancesOf(spec.id)) try { scanNewPhotos(ctx, engine, inst) } catch (e: Exception) { logW("new_photo observer scan", e) }
            }
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(scan); handler.postDelayed(scan, 2_000) }   // debounce bursts
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        return AutoCloseable {
            handler.removeCallbacks(scan)
            try { ctx.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
            scope.cancel()
        }
    }
}

object ClipboardTrigger : TriggerNode() {
    override val hosting = Hosting.HOST_ATTACHED
    override val spec = NodeSpec(
        id = "trigger.clipboard", name = "Clipboard Changed", kind = NodeKind.TRIGGER,
        description = "Fires when the clipboard changes (Android 10+: only while Mahout is in the foreground).",
        params = listOf(text("regex", "Text matches (regex)", templated = false)),
        inputs = emptyList(), gates = listOf(Gate.LiveHost, Gate.ForegroundOnly), optional = true,
    )
    override fun accepts(params: JsonObject, event: JsonObject): Boolean = regexOk(spec.pStr(params, "regex"), event.str("text"))

    override fun attach(host: TriggerHost, instances: List<TriggerInstance>): AutoCloseable? {
        val ctx = host.android ?: return null
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
        var last: String? = null
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: return@OnPrimaryClipChangedListener
                if (text == last) return@OnPrimaryClipChangedListener
                last = text
                host.fire(spec.id, item("text" to text, "at" to now()))
            } catch (e: Exception) { logW("clipboard", e) }
        }
        cm.addPrimaryClipChangedListener(listener)
        return AutoCloseable { try { cm.removePrimaryClipChangedListener(listener) } catch (_: Exception) {} }
    }
}

/** Shared unique-work name for WORK_MANAGER triggers (DESIGN §3.3). */
internal fun uniqueName(inst: TriggerInstance): String = "trig:${inst.workflowId}:${inst.nodeId}"
