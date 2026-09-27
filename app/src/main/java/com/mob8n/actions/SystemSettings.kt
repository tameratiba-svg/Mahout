package com.mob8n.actions

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.addAll
import com.mob8n.core.appPicker
import com.mob8n.core.asObject
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.item
import com.mob8n.core.num
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.str
import com.mob8n.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume
import kotlin.math.roundToInt

object RingerDndNode : Node() {
    private val RINGERS = mapOf("normal" to AudioManager.RINGER_MODE_NORMAL, "vibrate" to AudioManager.RINGER_MODE_VIBRATE, "silent" to AudioManager.RINGER_MODE_SILENT)
    private val FILTERS = mapOf("off" to NotificationManager.INTERRUPTION_FILTER_ALL, "priority" to NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        "alarms_only" to NotificationManager.INTERRUPTION_FILTER_ALARMS, "total" to NotificationManager.INTERRUPTION_FILTER_NONE)

    override val spec = NodeSpec(
        id = "action.ringer_dnd", name = "Ringer / Do Not Disturb", kind = NodeKind.ACTION,
        description = "Set ringer mode and Do Not Disturb, optionally saving the previous state to a variable for a later restore.",
        params = listOf(
            choice("ringer", "Ringer", listOf("unchanged", "normal", "vibrate", "silent")),
            choice("dnd", "Do Not Disturb", listOf("unchanged", "off", "priority", "alarms_only", "total")),
            choice("op", "Operation", listOf("set", "restore")),
            text("restoreVariable", "Restore variable", help = "set: saves previous state here (once); restore: restores from it", templated = false),
        ),
        gates = listOf(Gate.DndPolicy), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prevRinger = RINGERS.entries.firstOrNull { it.value == am.ringerMode }?.key ?: "normal"
        val prevDnd = FILTERS.entries.firstOrNull { it.value == nm.currentInterruptionFilter }?.key ?: "off"
        val variable = ctx.strOrNull("restoreVariable")
        var ringer: String? = ctx.str("ringer").takeIf { it != "unchanged" }
        var dnd: String? = ctx.str("dnd").takeIf { it != "unchanged" }
        if (ctx.str("op") == "restore") {
            val v = variable ?: throw NodeException("Restore needs a restore variable")
            val saved = ctx.getVar(v).asObject() ?: return out(ctx.item.add("previousRinger" to prevRinger, "previousDnd" to prevDnd, "restored" to false))
            ringer = saved.str("ringer"); dnd = saved.str("filter")
            ctx.setVar(v, null)
        } else if (variable != null && ctx.getVar(variable) == null) {
            ctx.setVar(variable, item("ringer" to prevRinger, "filter" to prevDnd))
        }
        try {
            // DND first: leaving DND resets the ringer on some OEMs, so the explicit ringer wins.
            dnd?.let { nm.setInterruptionFilter(FILTERS[it] ?: throw NodeException("Unknown DND mode $it")) }
            ringer?.let { am.ringerMode = RINGERS[it] ?: throw NodeException("Unknown ringer mode $it") }
        } catch (e: SecurityException) { throw NodeException("Needs ${Gate.DndPolicy.label}", e) }
        return out(ctx.item.add("previousRinger" to prevRinger, "previousDnd" to prevDnd))
    }
}

object DisplaySettingsNode : Node() {
    override val spec = NodeSpec(
        id = "action.display_settings", name = "Display settings", kind = NodeKind.ACTION,
        description = "Change brightness, auto-brightness, auto-rotate and screen timeout, optionally saving the previous values for a later restore.",
        params = listOf(
            number("brightnessPercent", "Brightness %", min = 0.0, max = 100.0, help = "Blank = unchanged"),
            choice("autoBrightness", "Auto brightness", listOf("unchanged", "on", "off")),
            choice("autoRotate", "Auto rotate", listOf("unchanged", "on", "off")),
            durationMs("screenTimeoutMs", "Screen timeout", 0, help = "0 = unchanged"),
            choice("op", "Operation", listOf("set", "restore")),
            text("restoreVariable", "Restore variable", templated = false),
        ),
        gates = listOf(Gate.WriteSettings), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val cr = a.contentResolver
        fun get(k: String, def: Int) = Settings.System.getInt(cr, k, def)
        val prevB = get(Settings.System.SCREEN_BRIGHTNESS, 128)
        val prevAuto = get(Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val prevRot = get(Settings.System.ACCELEROMETER_ROTATION, 1)
        val prevTimeout = get(Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
        val previous = item("previousBrightness" to (prevB * 100 / 255), "previousAutoBrightness" to (prevAuto == 1), "previousAutoRotate" to (prevRot == 1), "previousTimeout" to prevTimeout)
        val variable = ctx.strOrNull("restoreVariable")
        var brightness: Int? = ctx.double("brightnessPercent")?.let { (it.coerceIn(0.0, 100.0) * 255 / 100).roundToInt() }
        var auto: Int? = when (ctx.str("autoBrightness")) { "on" -> 1; "off" -> 0; else -> null }
        var rotate: Int? = when (ctx.str("autoRotate")) { "on" -> 1; "off" -> 0; else -> null }
        var timeout: Int? = ctx.long("screenTimeoutMs")?.takeIf { it > 0 }?.toInt()
        if (ctx.str("op") == "restore") {
            val v = variable ?: throw NodeException("Restore needs a restore variable")
            val saved = ctx.getVar(v).asObject() ?: return out(ctx.item.addAll(previous).add("restored" to false))
            brightness = saved.num("brightness")?.toInt(); auto = saved.num("auto")?.toInt(); rotate = saved.num("rotate")?.toInt(); timeout = saved.num("timeout")?.toInt()
            ctx.setVar(v, null)
        } else if (variable != null && ctx.getVar(variable) == null) {
            ctx.setVar(variable, item("brightness" to prevB, "auto" to prevAuto, "rotate" to prevRot, "timeout" to prevTimeout))
        }
        try {
            auto?.let { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            brightness?.let { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, it.coerceIn(0, 255)) }
            rotate?.let { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, it) }
            timeout?.let { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, it) }
        } catch (e: SecurityException) { throw NodeException("Needs ${Gate.WriteSettings.label}", e) }
        return out(ctx.item.addAll(previous))
    }
}

object SetRingtoneNode : Node() {
    override val spec = NodeSpec(
        id = "action.set_ringtone", name = "Set ringtone", kind = NodeKind.ACTION,
        description = "Set the default ringtone, notification or alarm sound to a content URI.",
        params = listOf(choice("type", "Type", listOf("ringtone", "notification", "alarm")), text("uri", "Sound URI", "{{uri}}", required = true)),
        gates = listOf(Gate.WriteSettings), optional = true, agentTool = false,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val uri = Uri.parse(ctx.req("uri"))
        if (uri.scheme != "content" && uri.scheme != "file") throw NodeException("Ringtone URI must be a content:// URI")
        val type = when (ctx.str("type")) { "notification" -> RingtoneManager.TYPE_NOTIFICATION; "alarm" -> RingtoneManager.TYPE_ALARM; else -> RingtoneManager.TYPE_RINGTONE }
        try { RingtoneManager.setActualDefaultRingtoneUri(a, type, uri) } catch (e: Exception) { throw NodeException("Could not set ringtone: ${e.message}", e) }
        return out(ctx.item)
    }
}

object SettingsPanelNode : Node() {
    override val spec = NodeSpec(
        id = "action.settings_panel", name = "Settings panel", kind = NodeKind.ACTION,
        description = "Open a system settings panel or screen (Wi-Fi, volume, Bluetooth, battery saver, DND, location, app details).",
        params = listOf(
            choice("panel", "Panel", listOf("bluetooth_enable", "wifi", "internet", "volume", "nfc", "bluetooth_settings", "battery_saver", "dnd_settings", "location_settings", "app_details"), "wifi"),
            appPicker("packageName", "App (for app_details)"),
        ),
        gates = BG_LAUNCH + Gate.Advisory(Gate.Permission(Manifest.permission.BLUETOOTH_CONNECT)), agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val panel = ctx.str("panel")
        val p29 = Build.VERSION.SDK_INT >= 29
        val i = when (panel) {
            "bluetooth_enable" -> {
                if (Build.VERSION.SDK_INT >= 31 && !Gate.Permission(Manifest.permission.BLUETOOTH_CONNECT).granted(a)) throw NodeException("Needs bluetooth connect permission")
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            }
            "wifi" -> Intent(if (p29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            "internet" -> Intent(if (p29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
            "volume" -> Intent(if (p29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
            "nfc" -> Intent(if (p29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS)
            "bluetooth_settings" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "battery_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "dnd_settings" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "location_settings" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.req("packageName")}"))
            else -> throw NodeException("Unknown panel $panel")
        }
        val r = Launch.start(a, i, true, "Open ${panel.replace('_', ' ')}")
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object FlashlightNode : Node() {
    private const val VAR = "torch"
    override val spec = NodeSpec(
        id = "action.flashlight", name = "Flashlight", kind = NodeKind.ACTION,
        description = "Turn the camera torch on, off, or toggle it.",
        params = listOf(choice("mode", "Mode", listOf("on", "off", "toggle"), "toggle")),
        gates = listOf(Gate.Feature("android.hardware.camera.flash", "Camera flash")), agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val cm = a.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList.toList() } catch (e: Exception) { throw NodeException("Cannot access cameras: ${e.message}", e) }
        val withFlash = ids.filter { runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false) }
        val camId = withFlash.firstOrNull { runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }.getOrDefault(false) }
            ?: withFlash.firstOrNull() ?: throw NodeException("No camera with a flash")
        val current = currentTorch(cm, camId) ?: (ctx.getVar(VAR) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val on = when (ctx.str("mode")) { "on" -> true; "off" -> false; else -> !current }
        try { withContext(Dispatchers.Main.immediate) { cm.setTorchMode(camId, on) } } catch (e: Exception) { throw NodeException("Torch unavailable (camera in use?): ${e.message}", e) }
        ctx.setVar(VAR, JsonPrimitive(on))
        return out(ctx.item.add("torchOn" to on))
    }

    /** The torch callback reports the current state immediately on registration; null when it does not arrive in time. */
    private suspend fun currentTorch(cm: CameraManager, camId: String): Boolean? {
        var cb: CameraManager.TorchCallback? = null
        try {
            return withTimeoutOrNull(1_000) {
                suspendCancellableCoroutine { cont ->
                    val c = object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == camId && cont.isActive) cont.resume(enabled) }
                        override fun onTorchModeUnavailable(id: String) { if (id == camId && cont.isActive) cont.resume(false) }
                    }
                    cb = c
                    try { cm.registerTorchCallback(c, Handler(Looper.getMainLooper())) } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
                }
            }
        } finally { cb?.let { runCatching { cm.unregisterTorchCallback(it) } } }
    }
}
