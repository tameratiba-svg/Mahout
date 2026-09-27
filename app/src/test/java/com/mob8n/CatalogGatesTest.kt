package com.mob8n

import com.mob8n.core.Catalog
import com.mob8n.core.Gate
import com.mob8n.ui.APP_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN3P §7.2: the static gate table of the whole catalog (135 nodes since v4, 136 since v5 — ai.decide is DATA with no gate). A new permission-guarded API without a gate, or a gate
 * without a row here, fails; the manifest must declare every gated permission and never the ones we deliberately left out.
 * Pure JVM: compares gate identity only, never granted()/available().
 */
class CatalogGatesTest {
    private val lanes = listOf(
        com.mob8n.triggers.TriggerNodes.all,
        com.mob8n.data.DataNodes.all,
        com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all,
        com.mob8n.ai.AiNodes.all,
        com.mob8n.apps.AppNodes.all,
    )
    private val catalog = Catalog(lanes)

    /** "~" = advisory / never enforced; Permission -> its short name; Feature -> "Feature:<feature>"; objects -> class name. */
    private fun Gate.sig(): String = (if (enforced) "" else "~") + when (val k = key) {
        is Gate.Permission -> k.permission.substringAfterLast('.')
        is Gate.Feature -> "Feature:" + k.feature
        else -> k::class.simpleName
    }

    private fun rows(vararg ids: String, gates: List<String>) = ids.map { it to gates.toSet() }

    private val expected: Map<String, Set<String>> = (
        rows("trigger.now_playing", "trigger.notification_posted", "trigger.notification_removed", gates = listOf("NotificationListener")) +
        rows("trigger.charger", "trigger.time_changed", "trigger.package", "trigger.battery_level", "trigger.headset", "trigger.screen", "trigger.unlocked",
            "trigger.airplane", "trigger.ringer", "trigger.volume", "trigger.dnd", "trigger.power_save", "trigger.webhook", gates = listOf("~LiveHost")) +
        rows("trigger.network", gates = listOf("~LiveHost", "~ACCESS_FINE_LOCATION", "~ACCESS_BACKGROUND_LOCATION", "~LocationOn")) +
        rows("trigger.bluetooth", gates = listOf("~LiveHost", "~BLUETOOTH_CONNECT")) +
        rows("trigger.new_photo", gates = listOf("READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE")) +
        rows("trigger.clipboard", gates = listOf("~LiveHost", "~ForegroundOnly")) +
        rows("trigger.calendar_upcoming", gates = listOf("READ_CALENDAR")) +
        rows("trigger.phone_call", gates = listOf("READ_PHONE_STATE")) +
        rows("trigger.sms", gates = listOf("RECEIVE_SMS")) +
        rows("trigger.geofence", gates = listOf("ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "~LocationOn")) +
        rows("trigger.shake", gates = listOf("~LiveHost", "Feature:android.hardware.sensor.accelerometer")) +
        rows("trigger.nfc", gates = listOf("Feature:android.hardware.nfc")) +
        rows("data.device_state", gates = listOf("~ACCESS_FINE_LOCATION", "~ACCESS_BACKGROUND_LOCATION", "~LocationOn")) +
        rows("data.now_playing", "data.active_notifications", gates = listOf("NotificationListener")) +
        rows("data.location", gates = listOf("ACCESS_FINE_LOCATION", "~LocationOn")) +
        rows("data.calendar_events", gates = listOf("READ_CALENDAR")) +
        rows("data.contact_lookup", gates = listOf("READ_CONTACTS")) +
        rows("data.media_list", gates = listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE")) +
        rows("data.clipboard", gates = listOf("~ForegroundOnly")) +
        rows("data.sensor", gates = listOf("~ACTIVITY_RECOGNITION")) +
        rows("action.add_to_playlist", gates = listOf("~READ_MEDIA_AUDIO", "~READ_EXTERNAL_STORAGE", "~WRITE_EXTERNAL_STORAGE")) +
        rows("action.media_control", gates = listOf("~NotificationListener", "~DndPolicy")) +
        rows("action.notify", gates = listOf("PostNotifications")) +
        rows("action.cancel_notification", gates = listOf("~NotificationListener")) +
        rows("action.reply_notification", gates = listOf("NotificationListener")) +
        rows("action.launch_app", "action.open_url", "action.send_intent", "action.share", "action.dial", "action.compose_sms", "action.compose_email",
            "action.navigate", "action.add_contact", "action.set_alarm", gates = listOf("~Overlay", "~PostNotifications")) +
        rows("action.add_calendar_event", gates = listOf("~Overlay", "~PostNotifications", "~WRITE_CALENDAR")) +
        rows("action.ringer_dnd", gates = listOf("DndPolicy")) +
        rows("action.display_settings", "action.set_ringtone", gates = listOf("WriteSettings")) +
        rows("action.settings_panel", gates = listOf("~Overlay", "~PostNotifications", "~BLUETOOTH_CONNECT")) +
        rows("action.flashlight", gates = listOf("Feature:android.hardware.camera.flash")) +
        rows("action.write_file", gates = listOf("~WRITE_EXTERNAL_STORAGE")) +
        rows("action.download", gates = listOf("WRITE_EXTERNAL_STORAGE")) +
        rows("ai.agent", gates = listOf("PostNotifications")) +
        rows("app.action", gates = listOf("~Overlay", "~PostNotifications")) +
        rows("app.launch_wait", "app.ui_read", "app.ui_tap", "app.ui_long_press", "app.ui_type", "app.ui_scroll", "app.ui_wait_for", "app.ui_global", "app.ui_screenshot",
            gates = listOf("Accessibility"))
        ).toMap()

    @Test fun expectedIdsExistAndCatalogIs136() {
        assertEquals(136, catalog.nodes.size)
        val ids = catalog.nodes.map { it.spec.id }.toSet()
        val unknown = expected.keys - ids
        assertTrue("EXPECTED names unknown ids: $unknown", unknown.isEmpty())
    }

    @Test fun everyGatedNodeCarriesExactlyItsDesignGates() {
        val bad = catalog.nodes.mapNotNull { n ->
            val e = expected[n.spec.id] ?: return@mapNotNull null
            val actual = n.spec.gates.map { it.sig() }.toSet()
            if (actual == e) null else "${n.spec.id}: expected $e, got $actual"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** DESIGN4 §4 / V20: the two coding nodes need no permission — ProcessBuilder on /system/bin/sh and a headless WebView are ordinary app code. */
    @Test fun codingNodesAreGateless() {
        for (id in listOf("app.shell_run", "logic.js")) {
            assertTrue("$id must not be in the gate table", id !in expected)
            assertEquals(id, emptySet<String>(), catalog.spec(id)!!.gates.map { it.sig() }.toSet())
        }
    }

    /** The static list assertion: a node not in EXPECTED must declare no gate at all. */
    @Test fun everyOtherNodeHasNoGates() {
        val bad = catalog.nodes.filter { it.spec.id !in expected && it.spec.gates.isNotEmpty() }.map { "${it.spec.id}: ${it.spec.gates.map { g -> g.sig() }}" }
        assertTrue("gated nodes missing from EXPECTED: $bad", bad.isEmpty())
    }

    /** The four formerly Build.VERSION-branched specs are static now (SDK_RANGE decides applicability). */
    @Test fun formerlyBranchedSpecsAreStatic() {
        for (id in listOf("trigger.bluetooth", "trigger.new_photo", "data.media_list", "data.sensor")) {
            val g = catalog.spec(id)!!.gates
            assertTrue(id, g.isNotEmpty())
            assertEquals(id, expected[id], g.map { it.sig() }.toSet())
        }
    }

    @Test fun everyCatalogPermissionIsDeclared() {
        val m = manifest()
        val perms = catalog.nodes.flatMap { it.spec.gates }.map { it.key }.filterIsInstance<Gate.Permission>().map { it.permission }.toSet()
        val missing = perms.filter { """<uses-permission android:name="$it"""" !in m }
        assertTrue("catalog permissions not declared: $missing", missing.isEmpty())
        for (p in listOf("SYSTEM_ALERT_WINDOW", "ACCESS_NOTIFICATION_POLICY", "WRITE_SETTINGS", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "POST_NOTIFICATIONS"))
            assertTrue(p, """<uses-permission android:name="android.permission.$p"""" in m)
        assertTrue(Regex("""<service android:name="com\.mob8n\.triggers\.NotifListener"[\s\S]*?android:permission="android\.permission\.BIND_NOTIFICATION_LISTENER_SERVICE"""").containsMatchIn(m))
        assertTrue(Regex("""<service android:name="com\.mob8n\.apps\.UiAutomationService"[\s\S]*?android:permission="android\.permission\.BIND_ACCESSIBILITY_SERVICE"""").containsMatchIn(m))
    }

    /** Every declared dangerous/special permission is referenced by a node gate or APP_LEVEL (companions excepted). */
    @Test fun noDeadDeclarations() {
        val m = manifest()
        val declared = Regex("""<uses-permission android:name="android\.permission\.([A-Z_]+)"""").findAll(m).map { it.groupValues[1] }.toSet()
        val normal = setOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "RECEIVE_BOOT_COMPLETED",
            "WAKE_LOCK", "VIBRATE", "SET_WALLPAPER", "NFC", "BLUETOOTH")
        val companions = setOf("ACCESS_COARSE_LOCATION", "READ_MEDIA_VISUAL_USER_SELECTED")
        val special = mapOf(Gate.Overlay to "SYSTEM_ALERT_WINDOW", Gate.DndPolicy to "ACCESS_NOTIFICATION_POLICY", Gate.WriteSettings to "WRITE_SETTINGS",
            Gate.IgnoreBatteryOpt to "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", Gate.PostNotifications to "POST_NOTIFICATIONS")
        val used = (catalog.nodes.flatMap { it.spec.gates } + APP_LEVEL).map { it.key }.flatMap { k ->
            when (k) { is Gate.Permission -> listOf(k.permission.substringAfterLast('.')); else -> listOfNotNull(special[k]) }
        }.toSet()
        val dead = declared - normal - companions - used
        assertTrue("declared but unused by any gate: $dead", dead.isEmpty())
    }

    @Test fun neverDeclared() {
        val m = manifest()
        val banned = listOf("SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "CAMERA", "RECORD_AUDIO", "BLUETOOTH_SCAN", "PACKAGE_USAGE_STATS", "NEARBY_WIFI_DEVICES",
            "CALL_PHONE", "SEND_SMS", "READ_CALL_LOG", "QUERY_ALL_PACKAGES")
        val present = banned.filter { """android:name="android.permission.$it"""" in m }
        assertTrue("permissions that must never be declared: $present", present.isEmpty())
    }

    private fun manifest(): String {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"), File("../app/src/main/AndroidManifest.xml"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }
}
