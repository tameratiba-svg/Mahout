package com.mob8n.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat   // androidx.core 1.13.1, already a dependency

/** Set by NotifListener.onListenerConnected/Disconnected and HostService.onCreate/onDestroy. */
object HostState {
    @Volatile var listenerConnected: Boolean = false
    @Volatile var serviceRunning: Boolean = false
    val alive: Boolean get() = listenerConnected || serviceRunning
}

/** FQCN of the apps-lane AccessibilityService: core never imports the apps lane (DESIGN2 §7.7). */
const val UI_AUTOMATION_SERVICE = "com.mob8n.apps.UiAutomationService"

/** How the user grants a gate; drives the Permissions rows and the Grant-all stepper (DESIGN3P §6). Pure, JVM-tested. */
enum class GrantKind { RUNTIME, BACKGROUND_LOCATION, SETTINGS, INFO }

/** Section of the Permissions screen. Pure. */
enum class GateGroup { ESSENTIAL, AUTOMATION, CONNECTIVITY, CONTENT }

/**
 * Permissions / special access a node needs. Executor blocks execute() on missing *enforced* gates (Executor.kt:106/:184);
 * the UI lists every gate. Only granted()/available() touch Android; everything else is pure and unit-tested (GatesTest).
 */
sealed class Gate(val label: String) {
    abstract fun granted(ctx: Context): Boolean
    /** false = the node degrades without it (null field / log line / trampoline); the executor never blocks on it. */
    open val enforced: Boolean get() = true
    open val kind: GrantKind get() = GrantKind.SETTINGS
    open val group: GateGroup get() = GateGroup.AUTOMATION
    /** false when the permission does not exist or is not needed on this Android version; granted() is then true. Tests pass sdk explicitly. */
    open fun applies(sdk: Int = Build.VERSION.SDK_INT): Boolean = true
    /** false when the device lacks the hardware/feature behind it ("Not available on this device"). */
    open fun available(ctx: Context): Boolean = true
    /** De-duplication identity for the UI: Advisory(X) and X are one row. */
    open val key: Gate get() = this

    /** Accessibility service toggle (Settings > Accessibility); Settings.Secure fallback for OEMs whose manager list lags (DESIGN2 §7.7). */
    object Accessibility : Gate("Accessibility service (UI automation)") {
        override fun granted(ctx: Context): Boolean {
            val me = ctx.packageName
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val listed = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == me && it.resolveInfo.serviceInfo.name == UI_AUTOMATION_SERVICE }
            return listed || (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').any { it.equals("$me/$UI_AUTOMATION_SERVICE", true) || it.equals("$me/${UI_AUTOMATION_SERVICE.removePrefix(me)}", true) }
        }
    }

    /** Dangerous runtime permission requested via RequestMultiplePermissions. SDK_RANGE says where it exists/is needed. */
    class Permission(val permission: String, label: String = permission.substringAfterLast('.').lowercase().replace('_', ' ')) : Gate(label) {
        override fun applies(sdk: Int) = sdk in (SDK_RANGE[permission] ?: ALL_SDKS)
        override fun granted(ctx: Context) = !applies() || ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        override fun available(ctx: Context) = REQUIRES_FEATURE[permission]?.let { ctx.packageManager.hasSystemFeature(it) } ?: true
        override val kind get() = if (permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION) GrantKind.BACKGROUND_LOCATION else GrantKind.RUNTIME
        override val group get() = if (permission in CONNECTIVITY) GateGroup.CONNECTIVITY else GateGroup.CONTENT
        override fun equals(other: Any?) = other is Permission && other.permission == permission
        override fun hashCode() = permission.hashCode()
    }

    /** Same check as [gate] but never enforced: the node runs and degrades without it. Listed as the wrapped gate (key). */
    class Advisory(val gate: Gate) : Gate(gate.label) {
        override fun granted(ctx: Context) = gate.granted(ctx)
        override val enforced get() = false
        override val kind get() = gate.kind
        override val group get() = gate.group
        override fun applies(sdk: Int) = gate.applies(sdk)
        override fun available(ctx: Context) = gate.available(ctx)
        override val key get() = gate.key
        override fun equals(other: Any?) = other is Advisory && other.gate == gate
        override fun hashCode() = 31 * gate.hashCode()
    }

    object NotificationListener : Gate("Notification access") {
        override fun granted(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        override val group get() = GateGroup.ESSENTIAL
    }
    object PostNotifications : Gate("Post notifications") {
        override fun applies(sdk: Int) = sdk >= 33
        override fun granted(ctx: Context) = !applies() ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        override val kind get() = GrantKind.RUNTIME
        override val group get() = GateGroup.ESSENTIAL
    }
    object DndPolicy : Gate("Do Not Disturb access") {
        override fun granted(ctx: Context) = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    }
    object WriteSettings : Gate("Modify system settings") {
        override fun granted(ctx: Context) = Settings.System.canWrite(ctx)
    }
    object IgnoreBatteryOpt : Gate("Ignore battery optimizations") {
        override fun granted(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
        override val group get() = GateGroup.ESSENTIAL
    }
    // ponytail: dormant — trigger.schedule uses setAndAllowWhileIdle (ScheduleTriggers.kt:126); kept so rememberGranter's when() stays exhaustive
    object ExactAlarm : Gate("Exact alarms") {
        override fun applies(sdk: Int) = sdk >= 31
        override fun granted(ctx: Context) = Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }
    /**
     * "Display over other apps" (SYSTEM_ALERT_WINDOW): the documented Android 10+ background-activity-start exemption used by
     * launch/open/share nodes when no Mob8N window is visible and the UI-automation binding is off. Never blocks: the
     * "Tap to open" trampoline notification remains the fallback. Not needed below 29.
     */
    object Overlay : Gate("Display over other apps") {
        override fun applies(sdk: Int) = sdk >= 29
        override fun granted(ctx: Context) = !applies() || Settings.canDrawOverlays(ctx)
        override val enforced get() = false
    }
    /** System location switch (not a permission). Wi-Fi SSID reads "<unknown ssid>" and getCurrentLocation returns null while off. Never blocks. */
    object LocationOn : Gate("Location services on") {
        override fun granted(ctx: Context) =
            ctx.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
        override val enforced get() = false
        override val group get() = GateGroup.CONNECTIVITY
    }
    /** Hardware feature, e.g. PackageManager.FEATURE_CAMERA_FLASH / FEATURE_NFC / FEATURE_SENSOR_ACCELEROMETER. */
    class Feature(val feature: String, label: String) : Gate(label) {
        override fun granted(ctx: Context) = ctx.packageManager.hasSystemFeature(feature)
        override fun available(ctx: Context) = granted(ctx)
        override val kind get() = GrantKind.INFO
        override val group get() = GateGroup.CONNECTIVITY
    }
    /** Needs a live host process (granted listener or HostService). Informational for triggers. */
    object LiveHost : Gate("Background host (notification access or Mahout service)") {
        override fun granted(ctx: Context) = HostState.alive || NotificationListener.granted(ctx)
        override val enforced get() = false
        override val kind get() = GrantKind.INFO
        override val group get() = GateGroup.ESSENTIAL
    }
    /** Works only while Mob8N is in the foreground (Android 10+ clipboard). Informational. */
    object ForegroundOnly : Gate("Only while Mahout is open") {
        override fun granted(ctx: Context) = true
        override val enforced get() = false
        override val kind get() = GrantKind.INFO
    }

    companion object {
        private val ALL_SDKS = 1..Int.MAX_VALUE
        /** Android versions on which a runtime permission exists AND is needed by this app. Outside the range granted() is true and the UI lists it under "Not needed on this Android version". */
        val SDK_RANGE: Map<String, IntRange> = mapOf(
            Manifest.permission.POST_NOTIFICATIONS to 33..Int.MAX_VALUE,
            Manifest.permission.READ_MEDIA_IMAGES to 33..Int.MAX_VALUE,
            Manifest.permission.READ_MEDIA_VIDEO to 33..Int.MAX_VALUE,
            Manifest.permission.READ_MEDIA_AUDIO to 33..Int.MAX_VALUE,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to 34..Int.MAX_VALUE,   // companion of IMAGES/VIDEO (PARTIAL state), never a gate
            Manifest.permission.READ_EXTERNAL_STORAGE to 1..32,
            Manifest.permission.WRITE_EXTERNAL_STORAGE to 1..28,
            Manifest.permission.BLUETOOTH_CONNECT to 31..Int.MAX_VALUE,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION to 29..Int.MAX_VALUE,
            Manifest.permission.ACTIVITY_RECOGNITION to 29..Int.MAX_VALUE,
        )
        /** Hardware a runtime permission is useless without: the UI shows "Not available on this device" instead of a Grant button. */
        val REQUIRES_FEATURE: Map<String, String> = mapOf(
            Manifest.permission.READ_PHONE_STATE to PackageManager.FEATURE_TELEPHONY,
            Manifest.permission.RECEIVE_SMS to PackageManager.FEATURE_TELEPHONY,
            Manifest.permission.BLUETOOTH_CONNECT to PackageManager.FEATURE_BLUETOOTH,
        )
        private val CONNECTIVITY = setOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACTIVITY_RECOGNITION,
        )
    }
}
