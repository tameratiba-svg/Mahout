# DESIGN3P — Permissions center (v3 side-track, "P")

_Product name since v4.1: **Mahout** (package `com.mob8n`). "Mob8N" below is the historical name and still the identifier._

Synthesised 2026-09-25 from two proposals + judge. Winner = proposal 0 (SDK_RANGE + `applies(sdk)`, `Gate.key` de-dup, restricted-settings state for BOTH accessibility and notification-listener access, prefs-backed permanently-denied detection). Every judge flaw is fixed and every "best idea" from proposal 1 is grafted (see §1.3). Everything here was re-verified against the code on disk (file:line anchors are exact as of today).

Requirement (user, verbatim): "must request all permissions from the permission screen - like accessibility, bluetooth, wifi, other necessary permissions for the app to function." Delivered as a COMPLETE permissions center derived from the catalog (all 128 NodeSpecs) plus three app-level gates, not a "used by enabled workflows" list.

Companion docs: DESIGN.md §3.4 (Gates), §4 (catalog), §5 (manifest), §9.2 (Permissions onboarding); DESIGN2.md §7.5-§7.7 (apps lane, `Gate.Accessibility`, disclosure), §10 (adb variables), §12.2 (open items).

---

## 1. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | The inventory is **derived**: `catalog.nodes.flatMap { spec.gates.map { it.key } }` + `APP_LEVEL = [IgnoreBatteryOpt, NotificationListener, PostNotifications]` ("used by Mob8N itself": Doze, background host, approval prompts/service notice). Accessibility and Overlay are NOT hand-listed: they arrive via app.ui_* and the 13 background-launch nodes. | Ponytail: "derive, don't hand-list". APP_LEVEL holds only what no node can express. |
| D2 | **Smallest Gate model change** (core/Gates.kt, §3): `open val enforced / kind / group / key`, `open fun applies(sdk)`, `open fun available(ctx)`, one static `SDK_RANGE` table, one `REQUIRES_FEATURE` table, one wrapper `Gate.Advisory(gate)`, two new objects `Gate.Overlay`, `Gate.LocationOn`. Nothing deleted (`ExactAlarm` stays dormant). | Removes every `Build.VERSION` branch from NodeSpecs, so the catalog test is deterministic on the JVM (SDK_INT == 0 there). |
| D3 | **Executor**: the two hard-coded `it !is Gate.LiveHost && it !is Gate.ForegroundOnly` filters (Executor.kt:106, :184) become `it.enforced`. Identical behaviour for today's catalog; `Advisory`, `Overlay`, `LocationOn` join the skipped set. | One predicate instead of a growing type list. |
| D4 | Gates that a node needs **only for some param values or as a best-effort enhancement** are declared `Gate.Advisory(...)`: never block `execute()`, always listed on the Permissions screen and the palette ("Optional: …"). Hard checks that already live inside `execute()` (WRITE_CALENDAR when `silent`, ACTIVITY_RECOGNITION when `steps`, BLUETOOTH_CONNECT for `bluetooth_enable`, listener for transport commands) are kept as-is. | Honest badges without turning optional params into hard blocks. |
| D5 | `Gate.Overlay` and `Gate.LocationOn` are **intrinsically non-enforced** (`enforced=false`) and `Overlay.applies(sdk) = sdk >= 29`. On API 26-28 the Overlay row reads "Not needed on this Android version", not "Granted" or "Not granted". | Judge flaws #2/#3: no reliance on callers wrapping it; no nagging where the trampoline is not even needed. |
| D6 | `trigger.bluetooth` declares `Advisory(BLUETOOTH_CONNECT)`: bare ACL connect/disconnect events fire without it (the receiver already catches `SecurityException`, `name=null`, `isAudio=false`); `nameMatch`/`audioOnly` need it. | Judge flaw #4. |
| D7 | `action.download` gains an **enforced** `Permission(WRITE_EXTERNAL_STORAGE)` (SDK_RANGE 1..28 → `granted()` true on 29+): `DownloadManager.Request.setDestinationInExternalPublicDir` (Files.kt:172) needs it on ≤ 28. | Judge flaw #1: the one real inventory miss. |
| D8 | **"Not available on this device"** for READ_PHONE_STATE / RECEIVE_SMS when `!hasSystemFeature(FEATURE_TELEPHONY)` (Pixel Tablet), for BLUETOOTH_CONNECT without `FEATURE_BLUETOOTH`, and for every `Gate.Feature`: `Gate.available(ctx)` + `REQUIRES_FEATURE` table in core. | Judge flaw #5; keeps the rule in core so the screen and tests share it. |
| D9 | **Restricted settings** (Android 13+, file-installed APKs) is handled for BOTH `Accessibility` and `NotificationListener`. Detection is `SDK >= 33 && attempted-and-still-off`; the installer-package heuristic only adds a *proactive* hint line before the first attempt and never gates the state. | Judge flaw #7: Files-app installs on some OEMs report the file manager or null. |
| D10 | **Permanently denied** = `perm.asked.<permission>` pref set (written *before* `launch`, so a process kill mid-dialog still records the ask) && `!shouldShowRequestPermissionRationale` && not granted → "Open App info". Never-asked never reads as permanently denied. | Proposal 1 graft (write-before-launch), proposal 0's prefs (survives process death, unlike `rememberSaveable` tick comparison). |
| D11 | **Background location two-step** enforced by construction: `wizardSteps()` never puts ACCESS_BACKGROUND_LOCATION inside the runtime batch (30+ ignores the whole request if it is); the step is active only when FINE is granted; on 29 one dialog with "Allow all the time", on 30+ the system opens the app's Location page (explained first with `pm.backgroundPermissionOptionLabel`). | Task hard constraint. `PermissionStatusTest` guards it. |
| D12 | **Grant-all stepper does not auto-launch the next Settings page on resume**: after each return the step list re-checks statuses, marks ✓, and shows the next step with an explicit **Next** (plus Skip step). | Proposal 1 graft: being bounced Settings→app→Settings is disorienting and fails silently on OEM builds. |
| D13 | `SYSTEM_ALERT_WINDOW` and `READ_MEDIA_VISUAL_USER_SELECTED` are the only manifest additions (verified: everything else is already declared). Deliberately NOT declared, and asserted by test: BLUETOOTH_SCAN, NEARBY_WIFI_DEVICES, PACKAGE_USAGE_STATS, SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM, CAMERA, RECORD_AUDIO, CALL_PHONE, SEND_SMS, READ_CALL_LOG, QUERY_ALL_PACKAGES. | §2 audit; proposal 1's `neverDeclared` test grafted. |
| D14 | Badge rule (requirement 4): Canvas red outline / list "needs permission" badge / Permissions top-bar badge count **enforced gates only**. LiveHost keeps the amber "needs host" chip (from `hostStatus`); today it also counted in `missingGateCount`, producing a double badge — that duplicate goes away. ParamSheet/Palette still show every gate (advisory as "Optional: …", info icon). | The executor enforces exactly the same set the UI paints red. |
| D15 | First-launch onboarding = the same screen, `onboarding=true` (title "Set up Mob8N", "Skip" in the top bar, no back arrow), gated by `settings.onboarded` in `SETTINGS_PREFS` next to Seed's `seeded` flag; App.kt glue ≈ 10 lines. | No second screen to maintain. |
| D16 | Tablet: `BoxWithConstraints`; `maxWidth >= 720.dp` → `LazyVerticalGrid(GridCells.Fixed(2))` with section headers and the Grant-all card spanning both columns; else `LazyColumn`. Row content identical. | In the ≥ 840 dp shell the screen lives in the detail pane (total − 360 dp); 720 dp is the pane width at which two 360-dp cards fit. |
| D17 | No new dependencies. `LocationManagerCompat` (androidx.core 1.13.1, already present), `ActivityResultContracts.RequestMultiplePermissions` (activity-compose 1.9.3, already used), `Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED` (compileSdk 34, resolves). | Hard constraint. |

### 1.1 Never simplify away
Permanently-denied handling (D10); background-location two-step (D11); restricted-settings guidance for accessibility AND notification access (D9); honest states "Not needed on this Android version" / "Not available on this device" / "Partial access" (D5, D8, §6.2); the accessibility and overlay disclosure texts (§6.6) stay honest; a11y `contentDescription` on every icon/chip/button, status never colour-only.

### 1.2 Ponytail marks (comment in code)
- `Gate.ExactAlarm`: `// ponytail: dormant — trigger.schedule uses setAndAllowWhileIdle (ScheduleTriggers.kt:126); kept so rememberGranter's when() stays exhaustive`.
- Restricted-settings detection: `// ponytail: heuristic (no public API): SDK>=33 && attempted && still off; installer package only enriches the hint`.
- `data.device_state`: `// ponytail: wifiSsid via WifiManager.connectionInfo (26-30 path); upgrade = NetworkCallback FLAG_INCLUDE_LOCATION_INFO on 31+` (existing comment at DeviceNodes.kt:169 stays).

### 1.3 Judge flaws fixed / grafts (traceability)
| Judge item | Where |
|---|---|
| download needs WRITE_EXTERNAL_STORAGE ≤ 28 | D7, §5 actions Files.kt:164, §2 row |
| Overlay `applies() < 29` | D5, §3 `Gate.Overlay` |
| Overlay intrinsically non-enforced | D5, §3 |
| trigger.bluetooth soft | D6, §5 triggers |
| telephony "Not available" | D8, §3 `REQUIRES_FEATURE`, §6.2 |
| line drift Media.kt:69, Recipes.kt:314 | §5 (all anchors re-verified) |
| sideload heuristic false negatives | D9, §6.2 `BLOCKED_RESTRICTED` rule |
| Grafts: download gate, telephony unavailable, soft BT, intrinsic Overlay/LocationOn, explicit Next, asked-flag before launch, `neverDeclared` test, adb-grant-while-Settings-open device step, APP_LEVEL kept small | D7, D8, D6, D5, D12, D10, §7.2 (e), §8 step 4, D1 |

---

## 2. Inventory (complete)

Kind: RUNTIME = `RequestMultiplePermissions` dialog; BG_LOC = two-step background location; SETTINGS = a Settings page/toggle; INFO = availability only. "Used by" = `spec.name` of every node whose `gates` contain the gate (advisory marked ~). "Gap" = what changes in this design.

| Permission / special access | Kind | Android range | Used by nodes | Manifest | Gated today | Gap / fix |
|---|---|---|---|---|---|---|
| **Notification access** (`BIND_NOTIFICATION_LISTENER_SERVICE` toggle; restricted setting on 13+ file installs) | SETTINGS | 26+ | Now Playing (trigger), Notification Posted, Notification Removed, Now Playing (data), Active Notifications, Reply to notification, ~Cancel notification (key path), ~Media control (transport cmds); Mob8N itself (background host #1) | service declared | yes, except media_control/cancel_notification (checked in execute) | Advisory on media_control + cancel_notification; deep link `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` on 30+; BLOCKED_RESTRICTED state (D9) |
| **POST_NOTIFICATIONS** | RUNTIME | 33+ (granted() true below) | Notify, AI Agent (approval); ~Launch app, ~Open URL, ~Send intent, ~Share, ~Dial, ~Compose SMS, ~Compose email, ~Navigate, ~Add calendar event, ~Add contact, ~Set alarm / timer, ~Settings panel, ~App action (trampoline "Tap to open"); Mob8N itself (prompts, HostService notice) | yes | Notify + Agent | Advisory on the 13 trampoline users; permanently-denied detection (13+ auto-denies after two refusals) |
| **Ignore battery optimizations** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, system dialog) | SETTINGS | 26+ | Mob8N itself: Schedule (Doze), Delay/Wait until (DelayedRunWorker), HostService start | yes | screen card | none (APP_LEVEL) |
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | SETTINGS | matters 29+ (`applies` = 29+) | ~Launch app, ~Open URL, ~Send intent (activity), ~Share, ~Dial, ~Compose SMS, ~Compose email, ~Navigate, ~Add calendar event (non-silent), ~Add contact, ~Set alarm / timer, ~Settings panel, ~App action (no-a11y path) | **NO → add** | no | new `Gate.Overlay` (non-enforced); `Launch.canStartDirectly(ctx)` += `Settings.canDrawOverlays`; the documented Android 10+ background-activity-start exemption; trampoline remains the fallback |
| **Accessibility service** (`BIND_ACCESSIBILITY_SERVICE` toggle; restricted setting on 13+ file installs) | SETTINGS | 26+ (Screenshot node 30+) | Launch app & wait, Read screen, Tap, Long press, Type text, Scroll, Wait for element, System action, Screenshot | service declared | yes | BLOCKED_RESTRICTED detection + "Open App info" (D9); disclosure text unchanged |
| **Do Not Disturb access** (`ACCESS_NOTIFICATION_POLICY`) | SETTINGS | 26+ | Ringer / Do Not Disturb, ~Media control (ring/notification volume while DND on → SecurityException at Media.kt:81) | yes | ringer_dnd | Advisory on media_control |
| **Modify system settings** (`WRITE_SETTINGS`) | SETTINGS | 26+ | Display settings, Set ringtone | yes | yes | none |
| **ACCESS_FINE_LOCATION** (+ COARSE requested in the same batch, never its own row) | RUNTIME | 26+ | Location, Location Enter/Exit, ~Network Changed (ssidMatch), ~Device State (wifiSsid) | yes | 3 of 4; network is hard | Advisory on network + device_state; 31+ "approximate only" reads as Not granted with hint "choose Precise" |
| **ACCESS_BACKGROUND_LOCATION** | BG_LOC | 29+ | Location Enter/Exit (proximity PendingIntent fires from a dead app), ~Network Changed, ~Device State (SSID read from a background host is redacted without it on 29+) | yes | geofence (hard) | Advisory on network + device_state; two-step flow (D11) |
| **Location services switch** (`Settings.Secure LOCATION_MODE`, not a permission) | SETTINGS | 26+ | ~Location, ~Location Enter/Exit, ~Network Changed, ~Device State — SSID reads "<unknown ssid>", `getCurrentLocation` null while off | n/a | no | new `Gate.LocationOn` (non-enforced) → `ACTION_LOCATION_SOURCE_SETTINGS` |
| **BLUETOOTH_CONNECT** (+ legacy `BLUETOOTH` maxSdk 30, normal) | RUNTIME | 31+ (granted() true below); needs `FEATURE_BLUETOOTH` | ~Bluetooth Device (name/isAudio for nameMatch/audioOnly), ~Settings panel (bluetooth_enable) | yes | trigger hard via SDK branch; panel in execute | Both Advisory; static via SDK_RANGE. **BLUETOOTH_SCAN not needed** (RuntimeTriggers.kt:229: ACL broadcasts only, no discovery/bonded enumeration) |
| **ACTIVITY_RECOGNITION** | RUNTIME | 29+ | ~Sensor (steps) | yes | execute only (DeviceNodes.kt:494) | Advisory on data.sensor |
| **READ_CALENDAR** | RUNTIME | 26+ | Calendar Event Soon, Calendar Events | yes | yes | none |
| **WRITE_CALENDAR** | RUNTIME | 26+ | ~Add calendar event (silent=true, Intents.kt:302 hard check kept) | yes | execute only | Advisory |
| **READ_CONTACTS** | RUNTIME | 26+ | Contact Lookup (Add contact uses the insert intent: no permission) | yes | yes | none |
| **READ_PHONE_STATE** | RUNTIME | 26+; `FEATURE_TELEPHONY` | Phone Call | yes | yes | "Not available on this device" on the tablet (D8) |
| **RECEIVE_SMS** (sensitive) | RUNTIME | 26+; `FEATURE_TELEPHONY` | SMS Received | yes | yes | same; disclosure "incoming SMS text; off by default" |
| **READ_MEDIA_IMAGES** | RUNTIME | 33+ | New Photo / Screenshot, Media List | yes | SDK branch | static via SDK_RANGE; PARTIAL state on 34+ |
| **READ_MEDIA_VIDEO** | RUNTIME | 33+ | Media List | yes | yes | static |
| **READ_MEDIA_AUDIO** | RUNTIME | 33+ | Media List, ~Add to playlist (MediaStore match/mirror) | yes | media_list; playlist in execute | Advisory on add_to_playlist |
| **READ_MEDIA_VISUAL_USER_SELECTED** (implicitly granted by the 34+ "Select photos" picker) | companion (never a row) | 34+ | New Photo, Media List → "Partial access" chip | **NO → add** | no | declared so the partial grant is observable; PARTIAL → "Choose all photos" re-requests IMAGES+VIDEO |
| **READ_EXTERNAL_STORAGE** | RUNTIME | ≤ 32 | New Photo / Screenshot, Media List, ~Add to playlist (29-32) | yes maxSdk 32 | SDK branch | static; on 33+ collapsed under "Not needed on this Android version" |
| **WRITE_EXTERNAL_STORAGE** | RUNTIME | ≤ 28 | Download (hard: public Downloads dir), ~Write file (documents target), ~Add to playlist (m3u) | yes maxSdk 28 | execute only (Files.kt:67) | download enforced (D7); others Advisory |
| **Device features** (INFO rows): camera flash, accelerometer, NFC | INFO | — | Flashlight (setTorchMode: **no CAMERA permission**, SystemSettings.kt:204), Shake, NFC Tag | uses-feature required=false | `Gate.Feature` | "Not available on this device" via `available()` (tablet: no NFC) |
| **Background host** (`LiveHost`) | INFO | — | 20 host-attached / runtime-receiver triggers | n/a | yes (non-enforced) | stays in the Host status section, not a grant row |
| **Only while Mob8N is open** (`ForegroundOnly`) | INFO | 29+ clipboard rule | Clipboard Changed, Clipboard (data) | n/a | yes | INFO row |
| SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | — | — | none (`setAndAllowWhileIdle`, ScheduleTriggers.kt:126) | not declared (correct) | `Gate.ExactAlarm` dormant | none; asserted never declared |
| PACKAGE_USAGE_STATS | — | — | none (no `UsageStatsManager`; foreground check = `ActivityManager.getMyMemoryState` on our own process) | no | — | none |
| NEARBY_WIFI_DEVICES | — | 33+ | none (no scan/P2P/hotspot/RTT; SSID of the connected network still needs FINE_LOCATION) | no | — | none |
| CAMERA, RECORD_AUDIO, CALL_PHONE, SEND_SMS, READ_CALL_LOG, QUERY_ALL_PACKAGES, BLUETOOTH_SCAN | — | — | none | no | — | asserted never declared |
| Normal permissions (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, FOREGROUND_SERVICE(_SPECIAL_USE), RECEIVE_BOOT_COMPLETED, WAKE_LOCK, VIBRATE, SET_WALLPAPER, SET_ALARM, NFC) | auto | — | HTTP, Webhook, Vibrate, Wallpaper, Set alarm, NFC Tag, hosts | yes | — | not shown on the screen |

Row count on the screen: 27 (24 grant rows + 3 INFO feature rows), minus those collapsed as "Not needed on this Android version" on the running SDK.

---

## 3. `core/Gates.kt` — exact code (integrator)

Unchanged bodies are marked `/* unchanged */`. Compiles against the existing imports plus `android.location.LocationManager` and `androidx.core.location.LocationManagerCompat`.

```kotlin
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
object HostState { /* unchanged */ }

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
        override fun granted(ctx: Context): Boolean { /* unchanged */ }
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
        override fun granted(ctx: Context) = /* unchanged */
    }
    object WriteSettings : Gate("Modify system settings") {
        override fun granted(ctx: Context) = /* unchanged */
    }
    object IgnoreBatteryOpt : Gate("Ignore battery optimizations") {
        override fun granted(ctx: Context) = /* unchanged */
        override val group get() = GateGroup.ESSENTIAL
    }
    // ponytail: dormant — trigger.schedule uses setAndAllowWhileIdle (ScheduleTriggers.kt:126); kept so rememberGranter's when() stays exhaustive
    object ExactAlarm : Gate("Exact alarms") {
        override fun applies(sdk: Int) = sdk >= 31
        override fun granted(ctx: Context) = /* unchanged */
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
    object LiveHost : Gate("Background host (notification access or Mob8N service)") {
        override fun granted(ctx: Context) = HostState.alive || NotificationListener.granted(ctx)
        override val enforced get() = false
        override val kind get() = GrantKind.INFO
        override val group get() = GateGroup.ESSENTIAL
    }
    /** Works only while Mob8N is in the foreground (Android 10+ clipboard). Informational. */
    object ForegroundOnly : Gate("Only while Mob8N is open") {
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
```

### 3.1 `core/Executor.kt` (integrator, two one-line edits — identical text at both sites)
```kotlin
// line 106 (was: it !is Gate.LiveHost && it !is Gate.ForegroundOnly && !it.granted(a))
val gateMissing: Gate? = android?.let { a -> spec.gates.firstOrNull { it.enforced && !it.granted(a) } }
// line 184
android?.let { a -> spec.gates.firstOrNull { it.enforced && !it.granted(a) }?.let { throw NodeException("Needs ${it.label}") } }
```
Behaviour for today's catalog is identical (LiveHost/ForegroundOnly were the only non-enforced gates); Advisory/Overlay/LocationOn join them.

Notes: `applies()` default argument reads `Build.VERSION.SDK_INT` (0 in JVM tests: tests always pass `sdk` explicitly and never call `granted()`/`available()`). `Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED` needs compileSdk ≥ 34 (it is 34). `spec.gates.distinct()` keeps `Advisory(X)` and `X` apart (different `equals`); the UI de-dups with `.map { it.key }.distinct()`.

---

## 4. `AndroidManifest.xml` additions (integrator, exact XML)

Insert after line 38 (`WRITE_SETTINGS`), inside the "special access" block, and add one runtime line after line 31 (`READ_MEDIA_VIDEO`):

```xml
    <!-- 34+: 'Select photos' partial access alongside READ_MEDIA_IMAGES/VIDEO; lets the Permissions screen show 'Partial access' instead of a bare denial (trigger.new_photo, data.media_list) -->
    <uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```
```xml
    <!-- 'Display over other apps' (Gate.Overlay, optional): the Android 10+ background-activity-start exemption for launch_app / open_url / share / navigate /
         dial / compose_* / add_contact / set_alarm / send_intent / settings_panel / add_calendar_event / app.action when no Mob8N window is visible and the
         UI-automation binding is off. Granted via Settings.ACTION_MANAGE_OVERLAY_PERMISSION; Mob8N never draws anything. Without it the 'Tap to open' trampoline is used. -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

Replace the comment at lines 39-41 with:
```xml
    <!-- NOT requested on purpose (CatalogGatesTest.neverDeclared asserts these stay out): CALL_PHONE, SEND_SMS, READ_CALL_LOG, RECORD_AUDIO,
         CAMERA (torch = CameraManager.setTorchMode, needs none), QUERY_ALL_PACKAGES, SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM (setAndAllowWhileIdle needs none),
         BLUETOOTH_SCAN (trigger.bluetooth only receives ACL broadcasts, no discovery), NEARBY_WIFI_DEVICES (no scan/P2P/hotspot API; the connected SSID stays
         FINE_LOCATION + location-on gated, plus BACKGROUND_LOCATION from a background host), PACKAGE_USAGE_STATS (unused).
         v2 (DESIGN2 §8.1): usesCleartextTraffic="true" for Ollama / custom OpenAI-compatible servers on the LAN; the ai lane only accepts http:// base URLs for loopback, *.local and RFC-1918 hosts. -->
```

No other manifest changes: ACCESS_FINE/COARSE/BACKGROUND_LOCATION, BLUETOOTH_CONNECT, BLUETOOTH maxSdk=30, READ/WRITE_CALENDAR, READ_CONTACTS, READ_PHONE_STATE, RECEIVE_SMS, READ_MEDIA_IMAGES/AUDIO/VIDEO, READ_EXTERNAL_STORAGE maxSdk=32, WRITE_EXTERNAL_STORAGE maxSdk=28, ACTIVITY_RECOGNITION, ACCESS_NOTIFICATION_POLICY, WRITE_SETTINGS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, POST_NOTIFICATIONS, the `uses-feature` rows and the three service declarations are present and correct (verified today).

`res/values/strings.xml`: no change required (`ui_automation_description` stays; screen texts live as Kotlin constants in ui/Permissions.kt like `UI_AUTOMATION_DISCLOSURE` today).

---

## 5. Per-node gate edits (the "gates" fixer), grouped by lane

Conventions: `P(X)` = `Gate.Permission(Manifest.permission.X)`; `A(g)` = `Gate.Advisory(g)`. Each edit replaces or adds the `gates =` argument on the given line (the line already holding `agentTool`/`gates`). Add `import android.Manifest` where missing; **remove** now-unused `import android.os.Build` only if nothing else in the file uses it (RuntimeTriggers/MediaNodes/DeviceNodes still do). All paths under `/Users/ankur/Mob8N/app/src/main/java/com/mob8n/`.

### 5.1 actions lane — shared helper first
`actions/Intents.kt`
- **:53-59** `Launch.canStartDirectly()` → takes `ctx`; overlay counts as a direct-start exemption:
  ```kotlin
  /** True when a plain startActivity will actually show something (pre-10, "Display over other apps" granted, or our own activity is visible). */
  fun canStartDirectly(ctx: Context): Boolean {
      if (Build.VERSION.SDK_INT < 29) return true
      if (Settings.canDrawOverlays(ctx)) return true
      val info = ActivityManager.RunningAppProcessInfo()
      ActivityManager.getMyMemoryState(info)
      return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
          info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
  }
  ```
  (add `import android.provider.Settings`); **:66** caller → `if (canStartDirectly(ctx))`. No other callers exist (grep verified; Recipes.kt mentions it only in a comment).
- **insert at :76** (right after `object Launch { … }` closes at :75, before `object LaunchAppNode`):
  ```kotlin
  /** Gates of every node that may start an activity from a background host: overlay = direct start, notifications = the "Tap to open" trampoline. Both optional. */
  internal val BG_LAUNCH: List<Gate> = listOf(Gate.Overlay, Gate.Advisory(Gate.PostNotifications))
  ```
  (line numbers below are pre-insert anchors; add 3 after inserting.)

| file:line (pre-insert) | node id | new `gates` |
|---|---|---|
| Intents.kt:82 | action.launch_app | `gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:100 | action.open_url | `gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:127 | action.send_intent | `gates = BG_LAUNCH, agentTool = false,` |
| Intents.kt:162 | action.share | `gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:197 | action.dial | `…, gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:212 | action.compose_sms | `…, gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:227 | action.compose_email | `…, gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:246 | action.navigate | `gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:273 | action.add_calendar_event | `gates = BG_LAUNCH + Gate.Advisory(Gate.Permission(Manifest.permission.WRITE_CALENDAR)), agentTool = true,` (keep the hard check at :302) |
| Intents.kt:343 | action.add_contact | `…, gates = BG_LAUNCH, agentTool = true,` |
| Intents.kt:366 | action.set_alarm | `gates = BG_LAUNCH, agentTool = true,` |
| Media.kt:69 | action.media_control | `gates = listOf(Gate.Advisory(Gate.NotificationListener), Gate.Advisory(Gate.DndPolicy)), agentTool = true,` (set_volume needs neither; execute() already throws the right messages at :78/:83/:86) |
| SystemSettings.kt:160 | action.settings_panel | `gates = BG_LAUNCH + Gate.Advisory(Gate.Permission(Manifest.permission.BLUETOOTH_CONNECT)), agentTool = true,` (keep :168 check) |
| Playlist.kt:58 | action.add_to_playlist | `gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.READ_MEDIA_AUDIO)), Gate.Advisory(Gate.Permission(Manifest.permission.READ_EXTERNAL_STORAGE)), Gate.Advisory(Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE))), agentTool = true,` |
| Files.kt:127 | action.write_file | `gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE))), mode = ExecMode.LIST, agentTool = true,` |
| Files.kt:164 | action.download | `gates = listOf(Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE)), agentTool = true,` (**enforced**; granted() is true on 29+ via SDK_RANGE) |
| Notify.kt:163 | action.cancel_notification | `gates = listOf(Gate.Advisory(Gate.NotificationListener)), agentTool = true,` (tag path needs nothing; key path throws in execute at :172). **Not :187** — that is action.reply_notification (id at :184), which keeps its hard `Gate.NotificationListener`. |

No change: Notify.kt:121 notify, :187 reply_notification; SystemSettings.kt:59 ringer_dnd, :100 display_settings, :140 set_ringtone, :193 flashlight (Feature only; CAMERA not needed); Misc/tts/play_sound/vibrate/clipboard_set/toast/wallpaper/save_note/schedule_run/toggle_workflow/log (no gates).

### 5.2 triggers lane
| file:line | node id | new `gates` |
|---|---|---|
| RuntimeTriggers.kt:124 | trigger.network | `inputs = emptyList(), gates = listOf(Gate.LiveHost, Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION)), Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)), Gate.LocationOn),` — delete the ponytail comment at :123 |
| RuntimeTriggers.kt:216 | trigger.bluetooth | `gates = listOf(Gate.LiveHost, Gate.Advisory(Gate.Permission(Manifest.permission.BLUETOOTH_CONNECT))),` (SDK_RANGE makes it granted below 31; the receiver at :229-231 already degrades) |
| RuntimeTriggers.kt:399 | trigger.new_photo | `gates = listOf(Gate.Permission(Manifest.permission.READ_MEDIA_IMAGES), Gate.Permission(Manifest.permission.READ_EXTERNAL_STORAGE)),` |
| SensorTriggers.kt:45 | trigger.geofence | `gates = listOf(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION), Gate.Permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION), Gate.LocationOn),` |

No change: MediaTriggers.kt:23, NotificationTriggers.kt:29/:55, ScheduleTriggers.kt:174, SystemTriggers.kt:174/:188 (phone/sms stay hard + `optional=true`), ComponentTriggers.kt:118, SensorTriggers.kt:87, WebhookTrigger.kt:43, every LiveHost-only row.

### 5.3 data lane
| file:line | node id | new `gates` |
|---|---|---|
| DeviceNodes.kt:81 | data.device_state | insert before `agentTool = true,`: `gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION)), Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)), Gate.LocationOn),` (wifiSsid only; battery/volume reads never block) |
| DeviceNodes.kt:224 | data.location | `gates = listOf(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION), Gate.LocationOn),` |
| DeviceNodes.kt:481 | data.sensor | `gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.ACTIVITY_RECOGNITION))), optional = true, agentTool = true,` — delete the comment at :479-480; keep the hard check at :494 |
| MediaNodes.kt:80-84 | data.media_list | replace comment + SDK branch with `gates = listOf(Gate.Permission(Manifest.permission.READ_MEDIA_IMAGES), Gate.Permission(Manifest.permission.READ_MEDIA_VIDEO), Gate.Permission(Manifest.permission.READ_MEDIA_AUDIO), Gate.Permission(Manifest.permission.READ_EXTERNAL_STORAGE)),` |

No change: DeviceNodes.kt:186/:287/:337/:379, MediaNodes.kt:37, all other data nodes (no gates).

### 5.4 apps lane
| file:line | node id | new `gates` |
|---|---|---|
| Recipes.kt:314 | app.action | `gates = listOf(Gate.Overlay, Gate.Advisory(Gate.PostNotifications)), agentTool = true,` (literal, not `BG_LAUNCH`: keeps the apps lane free of a new cross-lane import; `import com.mob8n.core.Gate` needed) |

No change: UiNodes.kt:38 `ACCESSIBILITY`, :85 app.launch_wait (it calls `UiAutomationService.require()` first, so there is no trampoline path and no PostNotifications need), all app.ui_*. `AppLaunch.start` (Recipes.kt:285-292) unchanged: the a11y binding short-circuit stays; the no-binding path goes through `Launch.start`, which now honours the overlay.

Test edit (apps lane owns it): `app/src/test/java/com/mob8n/apps/AppNodesSpecTest.kt:32-45` — the `gated: Boolean` column becomes `List<Gate>`: `"app.action" to listOf(…, listOf(Gate.Overlay, Gate.Advisory(Gate.PostNotifications)), …)`, `"app.launch_wait"`/`ui_*` → `listOf(Gate.Accessibility)`, capabilities/recipes → `emptyList<Gate>()`; line 49 asserts `assertEquals("${s.id} gates", e[2], s.gates)`.

### 5.5 ai lane
No change: Agent.kt:76 `Gate.PostNotifications` stays hard (an approval nobody sees is worse than a refused run).

### 5.6 core (integrator) — §3, §3.1.

### 5.7 ui lane one-liners (besides the Permissions.kt rewrite, §6)
- Canvas.kt:163: `s?.gates?.any { g -> g.enforced && runCatching { !g.granted(ctx) }.getOrDefault(false) } ?: false` (D14).
- Permissions.kt `gatesInUse`: `.filter { it.enforced }.map { it.key }.distinct()`; `missingGateCount` unchanged in shape (now counts enforced only).
- `Gate.grantable()` → `kind != GrantKind.INFO`.
- ParamSheet.kt:108: `StatusIcon(ok, info = g.kind == GrantKind.INFO || !g.enforced)`; label `if (ok) g.title else if (g.grantable()) "Grant: ${g.title}" else "Missing: ${g.title}"` unchanged.
- Palette.kt:84: `for (g in s.gates) GateLabel(g.title, if (g.enforced) "Needs ${g.title}" else "Optional: ${g.title}")`.

---

## 6. Permissions screen (ui lane) — `ui/Permissions.kt` rewrite + `ui/App.kt` glue

Keeps: `rememberGranter` (extended), `StatusIcon`, `UI_AUTOMATION_DISCLOSURE`, `Gate.title`, `gatesInUse`, `missingGateCount`, the Host status section. New: pure model (`inventory`, `statusOf`, `wizardSteps`) at the top of the file so `PermissionStatusTest` can run on the JVM; composables below.

### 6.1 Pure model
```kotlin
/** Gates no node can express; "used by Mob8N itself". */
val APP_LEVEL: List<Gate> = listOf(Gate.IgnoreBatteryOpt, Gate.NotificationListener, Gate.PostNotifications)
const val SELF = "Mob8N itself"

data class Row(val gate: Gate, val usedBy: List<String>, val advisoryOnly: Boolean)

/** Every distinct gate key across the whole catalog + APP_LEVEL, grouped by section, LiveHost/ForegroundOnly excluded (Host status section covers them). */
fun inventory(catalog: Catalog): Map<GateGroup, List<Row>> {
    val uses = LinkedHashMap<Gate, MutableList<Pair<String, Boolean>>>()          // key -> (node name, enforced)
    APP_LEVEL.forEach { uses.getOrPut(it) { mutableListOf() } += SELF to true }
    for (n in catalog.nodes) for (g in n.spec.gates) uses.getOrPut(g.key) { mutableListOf() } += n.spec.name to g.enforced
    return uses.filterKeys { it != Gate.LiveHost && it != Gate.ForegroundOnly }
        .map { (g, u) -> Row(g, u.map { it.first }.distinct(), advisoryOnly = u.none { it.second }) }
        .sortedWith(compareBy({ it.advisoryOnly }, { RANK.indexOf(it.gate.key::class).let { if (it < 0) 99 else it } }))
        .groupBy { it.gate.group }
}

enum class GateStatus { GRANTED, NOT_GRANTED, PERMANENTLY_DENIED, BLOCKED_RESTRICTED, PARTIAL, NOT_APPLICABLE, UNAVAILABLE, INFO }

/** Pure status rule (truth-tabled in PermissionStatusTest). Inputs are gathered by rememberGateStatuses(). */
fun statusOf(gate: Gate, granted: Boolean, available: Boolean, applies: Boolean, askedBefore: Boolean, showRationale: Boolean,
             attemptedSettings: Boolean, sdk: Int, partialMedia: Boolean): GateStatus = when {
    !applies -> GateStatus.NOT_APPLICABLE
    !available -> GateStatus.UNAVAILABLE
    gate.kind == GrantKind.INFO -> GateStatus.INFO
    granted -> GateStatus.GRANTED
    partialMedia && gate.key is Gate.Permission && (gate.key as Gate.Permission).permission in MEDIA_VISUAL -> GateStatus.PARTIAL
    (gate.kind == GrantKind.RUNTIME || gate.kind == GrantKind.BACKGROUND_LOCATION) && askedBefore && !showRationale -> GateStatus.PERMANENTLY_DENIED
    (gate.key == Gate.Accessibility || gate.key == Gate.NotificationListener) && attemptedSettings && sdk >= 33 -> GateStatus.BLOCKED_RESTRICTED
    else -> GateStatus.NOT_GRANTED
}

sealed class Step(val title: String) {
    class Runtime(val permissions: List<String>) : Step("Allow permissions")           // one RequestMultiplePermissions dialog run
    object BackgroundLocation : Step("Location all the time")
    class Settings(val gate: Gate) : Step(gate.title)
    class AppInfo(val gates: List<Gate>) : Step("Open App info")
}

/** Grant-all plan from current statuses. BACKGROUND_LOCATION is never inside the runtime batch (30+ ignores the whole request otherwise). */
fun wizardSteps(rows: List<Row>, status: (Gate) -> GateStatus, fineGranted: Boolean, sdk: Int): List<Step> = buildList {
    val pending = rows.filter { status(it.gate) in setOf(GateStatus.NOT_GRANTED, GateStatus.PARTIAL, GateStatus.BLOCKED_RESTRICTED) }
    val runtime = pending.filter { it.gate.kind == GrantKind.RUNTIME }.flatMap { r ->
        when (val k = r.gate.key) { is Gate.Permission -> listOf(k.permission); Gate.PostNotifications -> listOf(Manifest.permission.POST_NOTIFICATIONS); else -> emptyList() } }
        .let { if (Manifest.permission.ACCESS_FINE_LOCATION in it) it + Manifest.permission.ACCESS_COARSE_LOCATION else it }.distinct()
    if (runtime.isNotEmpty()) add(Step.Runtime(runtime))                             // ordered Essential -> Connectivity -> Content by the rows' section order
    if (sdk >= 29 && pending.any { it.gate.kind == GrantKind.BACKGROUND_LOCATION }) add(Step.BackgroundLocation)   // shown "Needs Location first" until fineGranted
    SETTINGS_ORDER.forEach { g -> if (pending.any { it.gate.key == g }) add(Step.Settings(g)) }
    rows.filter { status(it.gate) == GateStatus.PERMANENTLY_DENIED }.map { it.gate }.takeIf { it.isNotEmpty() }?.let { add(Step.AppInfo(it)) }
}
val SETTINGS_ORDER = listOf(Gate.NotificationListener, Gate.IgnoreBatteryOpt, Gate.Overlay, Gate.Accessibility, Gate.DndPolicy, Gate.WriteSettings, Gate.LocationOn)
```
`RANK` = section-internal display order: NotificationListener, PostNotifications, IgnoreBatteryOpt | Accessibility, Overlay, DndPolicy, WriteSettings, ExactAlarm | Permission(FINE), Permission(BACKGROUND), LocationOn, Permission(BLUETOOTH_CONNECT), Permission(ACTIVITY_RECOGNITION), Feature | Permission (content, manifest order). `MEDIA_VISUAL = setOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)`.

### 6.2 Status inputs (`@Composable rememberGateStatuses(rows, tick)`)
- `granted` / `available` / `applies()` via `runCatching { … }.getOrDefault(false)` on the gate.
- `askedBefore = prefs.getBoolean("perm.asked.<permission>", false)` (`SETTINGS_PREFS`); written **before** `permLauncher.launch` for each requested permission (D10).
- `showRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)`; `activity = ctx.findActivity()` (walk `ContextWrapper.baseContext`); null activity → treat as `true` (never falsely permanently denied).
- `attemptedSettings = prefs.getLong("gate.attempt.<label>", 0) > 0`; written on every Settings launch for that gate; cleared when the gate becomes granted.
- `partialMedia = sdk >= 34 && checkSelfPermission(READ_MEDIA_VISUAL_USER_SELECTED) == GRANTED && !READ_MEDIA_IMAGES granted`.
- `sideloadHint = sdk >= 33 && runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull() in setOf("com.google.android.packageinstaller", "com.android.packageinstaller")` — **hint only** (D9): shows the restricted-settings paragraph proactively on the Accessibility and Notification access rows; never changes the status.
- Recomputed on `rememberResumeTick()` (Theme.kt:109) and a local `resultTick` bumped in every launcher callback.

Status chip texts (colour never the only signal; every chip has `semantics { contentDescription = "<title>: <text>" }`):
GRANTED "Granted" · NOT_GRANTED "Not granted" · PERMANENTLY_DENIED "Permanently denied" · BLOCKED_RESTRICTED "Blocked by restricted settings" · PARTIAL "Partial access (selected photos)" · NOT_APPLICABLE "Not needed on this Android version" · UNAVAILABLE "Not available on this device" · INFO "Available" (Feature present) / "Info".

### 6.3 Layout
- `Scaffold`; `TopAppBar(title = if (onboarding) "Set up Mob8N" else "Permissions")`; back arrow hidden when onboarding; `actions = { if (onboarding) TextButton(onClick = onDone) { Text("Skip") } }`.
- `BoxWithConstraints`: `maxWidth >= 720.dp` → `LazyVerticalGrid(GridCells.Fixed(2), contentPadding 16.dp, spacing 12.dp)` with `item(span = { GridItemSpan(maxLineSpan) })` for the header card, every section header, every "Not needed" expander and the Host status block; else `LazyColumn`. Row content identical (one `GateRow` composable).
- **Header card** (full width): `LinearProgressIndicator(progress = granted/total)` with `semantics { contentDescription = "$granted of $total granted" }`, text "N of M granted", primary `Button("Grant all")` (`enabled = steps.isNotEmpty()`, `contentDescription = "Grant all missing permissions, ${steps.size} steps"`). When the wizard is active the card becomes the stepper (§6.5).
- **Sections** in order: "Essential" (ESSENTIAL), "Automation" (AUTOMATION), "Connectivity & sensors" (CONNECTIVITY), "Content" (CONTENT); header `Text(style = titleMedium, Modifier.semantics { heading() })`. Within a section: enforced/app-level rows first, then advisory-only rows; rows with NOT_APPLICABLE are collapsed under an expander "Not needed on this Android version (k)" at the section end (honest, not hidden).
- **Host status** (unchanged two `ListItem`s) at the bottom.

### 6.4 Row (`GateRow(row, status, hints, onGrant, onAppInfo)`), `Card` with `Modifier.semantics(mergeDescendants = true)`
- Leading `Icon(iconFor(gate), contentDescription = gate.title)`: NotificationListener→Notifications, PostNotifications→NotificationsActive, IgnoreBatteryOpt→BatterySaver, Accessibility→Accessibility, Overlay→Layers, DndPolicy→DoNotDisturbOn, WriteSettings→Settings, FINE→LocationOn, BACKGROUND→MyLocation, LocationOn→GpsFixed, BLUETOOTH_CONNECT→Bluetooth, ACTIVITY_RECOGNITION→DirectionsWalk, READ_MEDIA_IMAGES/VIDEO→Image, READ_MEDIA_AUDIO→MusicNote, *_EXTERNAL_STORAGE→Folder, *_CALENDAR→Event, READ_CONTACTS→Contacts, READ_PHONE_STATE→Phone, RECEIVE_SMS→Sms, Feature flash→FlashlightOn, accelerometer→Vibration, nfc→Nfc.
- Title = `gate.title`; one-line **why** (table below); "Used by: " + `usedBy.joinToString(", ")` (advisory-only users get " (optional)"; `SELF` shown as "Mob8N itself").
- `StatusChip(status)` (AssistChip, `enabled=false`, leading `StatusIcon`).
- Hint line for SETTINGS gates (where the toggle is): NotificationListener "Settings > Notifications > Device & app notifications > Mob8N" · IgnoreBatteryOpt "System dialog: tap Allow" · Overlay "Settings > Apps > Special app access > Display over other apps > Mob8N" · Accessibility "Settings > Accessibility > Mob8N UI automation" · DndPolicy "Settings > Apps > Special app access > Do Not Disturb access > Mob8N" · WriteSettings "Settings > Apps > Special app access > Modify system settings > Mob8N" · LocationOn "Settings > Location > Use location". BACKGROUND on 30+: "On the next page choose '${pm.backgroundPermissionOptionLabel}'". FINE on 31+ with COARSE granted: "Approximate only: tap Grant and choose Precise".
- Trailing button by status: NOT_GRANTED → "Grant" (RUNTIME: `launch(arrayOf(p) [+ COARSE if p == FINE])`; BACKGROUND_LOCATION: "Allow all the time" (fine first if missing, else `launch(arrayOf(ACCESS_BACKGROUND_LOCATION))` **alone**); SETTINGS → "Open Settings" / LocationOn "Turn on") · PERMANENTLY_DENIED → "Open App info" (`ACTION_APPLICATION_DETAILS_SETTINGS`, `package:` uri) + hint "Android will not show this dialog again; enable it under Permissions" · BLOCKED_RESTRICTED → two buttons "Open App info" and "Try again" (reopens the Settings page) + the restricted-settings paragraph (§6.6) · PARTIAL → "Choose all photos" (re-launch IMAGES+VIDEO; on 34+ the system shows the picker with "Allow all") + hint "Only selected photos are visible; New Photo / Screenshot needs all" · UNAVAILABLE / NOT_APPLICABLE / INFO → no button.
- Buttons carry `contentDescription = "<label> <gate.title>"`; touch targets ≥ 48 dp.

**Why texts** (`gateWhy(gate)`): NotificationListener "Keeps Mob8N alive in the background, reads notifications and now-playing media. The recommended host." · PostNotifications "Approval prompts, run failures, 'Tap to open' fallbacks and the background-service notice." · IgnoreBatteryOpt "Lets schedules and delayed runs fire on time in Doze." · Accessibility `UI_AUTOMATION_DISCLOSURE` (unchanged) · Overlay §6.6 · DndPolicy "Change Do Not Disturb and ring volume while DND is on." · WriteSettings "Brightness, auto-rotate, screen timeout, ringtone." · FINE "Current location, geofences and the Wi-Fi network name." · BACKGROUND "Geofences and Wi-Fi names while Mob8N runs in the background." · LocationOn "Android hides Wi-Fi names and location fixes while the device location switch is off." · BLUETOOTH_CONNECT "Names and type of connecting Bluetooth devices (connect/disconnect events work without it)." · ACTIVITY_RECOGNITION "Step counter reading." · READ_MEDIA_IMAGES/VIDEO "Detect new photos and screenshots, list media." · READ_MEDIA_AUDIO "List songs and mirror playlists." · READ/WRITE_EXTERNAL_STORAGE "Media and public Documents/Music/Downloads folders on older Android." · READ_CALENDAR "Upcoming events." · WRITE_CALENDAR "Insert events without opening the calendar app." · READ_CONTACTS "Look up names and numbers." · READ_PHONE_STATE "Ringing / call state (no numbers)." · RECEIVE_SMS "Incoming SMS text (sensitive; nodes are off by default)." · Feature "Hardware feature."

### 6.5 Grant-all stepper (inside the header card; `var stepIdx by rememberSaveable { mutableIntStateOf(-1) }`, -1 = idle, survives the Settings round-trip and process death)
1. Tap "Grant all" → `stepIdx = 0` and the first step is performed immediately.
2. `Step.Runtime`: write `perm.asked.*` for each, then one `RequestMultiplePermissions.launch(array)` — Android shows one dialog per permission group in array order (Notifications, Location [precise/approximate], Nearby devices, Physical activity, Calendar, Contacts, Phone, SMS, Photos & videos, Music & audio). Callback: bump `resultTick`; any permission now denied with `shouldShowRequestPermissionRationale == false` moves to PERMANENTLY_DENIED (the trailing `Step.AppInfo` picks it up).
3. `Step.BackgroundLocation`: card hint "Choose 'Allow all the time'" (30+: `pm.backgroundPermissionOptionLabel`). If FINE is missing the card says "Needs Location first" and **Next** requests FINE(+COARSE); otherwise `launch(arrayOf(ACCESS_BACKGROUND_LOCATION))` **alone** (29: dialog; 30+: the system opens the app's Location page). If already asked and rationale is false → the button reads "Open App info".
4. `Step.Settings(g)`: card "Step k of n — <title>", why, hint, `Button("Open Settings")` (records `gate.attempt.<label>`), `TextButton("Skip step")`. Accessibility step shows `UI_AUTOMATION_DISCLOSURE` on the card.
5. **On resume / on result**: statuses recompute; if the current step is satisfied (all its gates granted, or the runtime step has no askable permission left) it gets ✓ and `stepIdx++`; the next step is shown with an explicit **Next** button — never auto-launched (D12). Still missing → the card stays with its hint; for Accessibility / Notification access on 13+ after an attempt the card switches to the restricted-settings text with "Open App info" + "Try again".
6. `Step.AppInfo(gates)`: lists the permanently-denied names; button "Open App info".
7. End: "All set" or the list of skipped steps; `Button("Done")` → `stepIdx = -1` (+ `onDone` when onboarding). The step list (LinearProgress + rows with `CheckCircle` "Done" / `RadioButtonUnchecked` "Pending" / `Error` "Skipped", each `contentDescription = "Step k of n, <title>, <state>"`) is visible throughout. Grant-all is re-runnable: it only ever contains currently-missing gates; steps satisfied by any path (user toggled in Settings directly) are marked done on resume.

### 6.6 Disclosure / guidance texts (verbatim constants)
- `OVERLAY_DISCLOSURE` = "Display over other apps lets a workflow open apps, links, share sheets and the dialer immediately while Mob8N is in the background (Android 10 and later block this otherwise). Mob8N never draws anything over other apps. Without it, Mob8N posts a 'Tap to open' notification instead — everything still works, one tap later."
- `RESTRICTED_SETTINGS_HINT` = "Restricted setting: Android 13 and later block this toggle for apps installed from a file until you allow it once. Open App info, tap ⋮ (top right) and choose 'Allow restricted settings' — the menu item appears only after you have tried the toggle once — then come back and try again. Apps installed with adb or from a store are not affected."
- `UI_AUTOMATION_DISCLOSURE` unchanged (DESIGN2 §7.6).
- `BACKGROUND_LOCATION_WHY` = "Geofences fire and Wi-Fi names are readable while Mob8N runs in the background only with 'Allow all the time'. Mob8N reads location only when a Location node runs or a geofence is armed."
- Snackbar on `ActivityNotFoundException`: "This Settings page is not available on this device — open App info instead" with action "App info".

### 6.7 `rememberGranter` (exhaustive `when`, same lambda for ParamSheet/Palette)
`is Gate.Advisory -> grant(gate.gate)` · `is Gate.Permission -> markAsked(p [+COARSE]); launch(...)` (BACKGROUND alone when `p == ACCESS_BACKGROUND_LOCATION`) · `PostNotifications -> if (SDK >= 33) markAsked + launch` · `NotificationListener, LiveHost -> SDK >= 30 ? Intent(ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(pkg, "com.mob8n.triggers.NotifListener").flattenToString()) : ACTION_NOTIFICATION_LISTENER_SETTINGS` · `Overlay -> Intent(ACTION_MANAGE_OVERLAY_PERMISSION, package uri)` · `LocationOn -> ACTION_LOCATION_SOURCE_SETTINGS` · `DndPolicy / WriteSettings / IgnoreBatteryOpt / ExactAlarm / Accessibility` as today · `is Feature, ForegroundOnly -> {}`. Every Settings launch records `gate.attempt.<label>`; `try/catch` → snackbar (§6.6). Add `fun openAppInfo(ctx)`.

### 6.8 Onboarding glue (`ui/App.kt`, ≈10 lines)
```kotlin
val prefs = remember { ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
var onboarded by rememberSaveable { mutableStateOf(prefs.getBoolean("onboarded", false)) }
val finishOnboarding = { prefs.edit().putBoolean("onboarded", true).apply(); onboarded = true }
Mob8NTheme { Surface(...) {
    if (!onboarded) PermissionsScreen(engine, catalog, onboarding = true, onBack = finishOnboarding)   // full-screen, before the shell
    else BoxWithConstraints { /* existing body */ }
} }
```
"Skip", the stepper's final "Done" and the system Back gesture (a `BackHandler` inside the screen when `onboarding`) all call `onBack`. `Screen.Permissions` from the top bar renders the same screen with `onboarding = false`. `ScreenContent` signature: `PermissionsScreen(engine, catalog, onBack = back)` keeps working (default `onboarding = false`).

---

## 7. Tests (JUnit 4, `app/src/test`; `isReturnDefaultValues = true` → `Build.VERSION.SDK_INT == 0`, so tests pass `sdk` explicitly and never call `granted()`/`available()`)

### 7.1 `app/src/test/java/com/mob8n/core/GatesTest.kt` (integrator, new)
- `kindMapping`: `Permission(READ_CALENDAR).kind == RUNTIME`; `Permission(ACCESS_BACKGROUND_LOCATION).kind == BACKGROUND_LOCATION`; `PostNotifications.kind == RUNTIME`; NotificationListener/Accessibility/DndPolicy/WriteSettings/IgnoreBatteryOpt/ExactAlarm/Overlay/LocationOn → SETTINGS; Feature/LiveHost/ForegroundOnly → INFO.
- `groupMapping`: ESSENTIAL = {NotificationListener, PostNotifications, IgnoreBatteryOpt, LiveHost}; CONNECTIVITY = {Permission(FINE/COARSE/BACKGROUND/BLUETOOTH_CONNECT/ACTIVITY_RECOGNITION), LocationOn, Feature}; CONTENT = Permission(READ_CALENDAR/READ_MEDIA_IMAGES/RECEIVE_SMS/…); AUTOMATION = {Accessibility, Overlay, DndPolicy, WriteSettings, ExactAlarm, ForegroundOnly}.
- `enforcedFlags`: LiveHost, ForegroundOnly, Overlay, LocationOn, Advisory(anything) → false; Permission, PostNotifications, NotificationListener, Accessibility, DndPolicy, WriteSettings, IgnoreBatteryOpt, ExactAlarm, Feature → true.
- `appliesRanges`: BLUETOOTH_CONNECT (30 false / 31 true); READ_EXTERNAL_STORAGE (32 true / 33 false); WRITE_EXTERNAL_STORAGE (28 true / 29 false); READ_MEDIA_IMAGES (32 false / 33 true); READ_MEDIA_VISUAL_USER_SELECTED (33 false / 34 true); ACCESS_BACKGROUND_LOCATION and ACTIVITY_RECOGNITION (28 false / 29 true); PostNotifications (32 false / 33 true); Overlay (28 false / 29 true); ExactAlarm (30 false / 31 true); READ_CALENDAR (26 true).
- `advisoryIdentity`: `Advisory(Permission(X)).key == Permission(X)`; `Advisory(Overlay).key === Overlay`; `Advisory(Advisory(X)).key == X`; `Advisory(X) != X`; `listOf(Advisory(P(X)), P(X)).map { it.key }.distinct().size == 1`; Advisory delegates kind/group/applies.
- `requiresFeatureTable`: READ_PHONE_STATE, RECEIVE_SMS → FEATURE_TELEPHONY; BLUETOOTH_CONNECT → FEATURE_BLUETOOTH; READ_CONTACTS → absent.
- `sdkRangeKeysAreDeclared`: every `SDK_RANGE` key appears as `<uses-permission android:name="…"` in the manifest (file located like `ManifestReceiverTest.manifest()`).

### 7.2 `app/src/test/java/com/mob8n/CatalogGatesTest.kt` ("gates" fixer, new; root package, same six `lanes` as CatalogTest)
Signature helper: `fun Gate.sig() = (if (enforced) "" else "~") + when (val k = key) { is Gate.Permission -> k.permission.substringAfterLast('.'); is Gate.Feature -> "Feature:" + k.feature; else -> k::class.simpleName }`.

**EXPECTED** (static, order-insensitive; ids not listed must have `gates.isEmpty()`):
```
trigger.now_playing, trigger.notification_posted, trigger.notification_removed -> [NotificationListener]
trigger.charger, trigger.time_changed, trigger.package, trigger.battery_level, trigger.headset, trigger.screen, trigger.unlocked,
  trigger.airplane, trigger.ringer, trigger.volume, trigger.dnd, trigger.power_save, trigger.webhook -> [~LiveHost]
trigger.network -> [~LiveHost, ~ACCESS_FINE_LOCATION, ~ACCESS_BACKGROUND_LOCATION, ~LocationOn]
trigger.bluetooth -> [~LiveHost, ~BLUETOOTH_CONNECT]
trigger.new_photo -> [READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE]
trigger.clipboard -> [~LiveHost, ~ForegroundOnly]
trigger.calendar_upcoming -> [READ_CALENDAR];  trigger.phone_call -> [READ_PHONE_STATE];  trigger.sms -> [RECEIVE_SMS]
trigger.geofence -> [ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION, ~LocationOn]
trigger.shake -> [~LiveHost, Feature:android.hardware.sensor.accelerometer];  trigger.nfc -> [Feature:android.hardware.nfc]
data.device_state -> [~ACCESS_FINE_LOCATION, ~ACCESS_BACKGROUND_LOCATION, ~LocationOn]
data.now_playing, data.active_notifications -> [NotificationListener]
data.location -> [ACCESS_FINE_LOCATION, ~LocationOn];  data.calendar_events -> [READ_CALENDAR];  data.contact_lookup -> [READ_CONTACTS]
data.media_list -> [READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, READ_EXTERNAL_STORAGE]
data.clipboard -> [~ForegroundOnly];  data.sensor -> [~ACTIVITY_RECOGNITION]
action.add_to_playlist -> [~READ_MEDIA_AUDIO, ~READ_EXTERNAL_STORAGE, ~WRITE_EXTERNAL_STORAGE]
action.media_control -> [~NotificationListener, ~DndPolicy];  action.notify -> [PostNotifications]
action.cancel_notification -> [~NotificationListener];  action.reply_notification -> [NotificationListener]
action.launch_app, action.open_url, action.send_intent, action.share, action.dial, action.compose_sms, action.compose_email,
  action.navigate, action.add_contact, action.set_alarm -> [~Overlay, ~PostNotifications]
action.add_calendar_event -> [~Overlay, ~PostNotifications, ~WRITE_CALENDAR]
action.ringer_dnd -> [DndPolicy];  action.display_settings, action.set_ringtone -> [WriteSettings]
action.settings_panel -> [~Overlay, ~PostNotifications, ~BLUETOOTH_CONNECT];  action.flashlight -> [Feature:android.hardware.camera.flash]
action.write_file -> [~WRITE_EXTERNAL_STORAGE];  action.download -> [WRITE_EXTERNAL_STORAGE]
ai.agent -> [PostNotifications]
app.action -> [~Overlay, ~PostNotifications]
app.launch_wait, app.ui_read, app.ui_tap, app.ui_long_press, app.ui_type, app.ui_scroll, app.ui_wait_for, app.ui_global, app.ui_screenshot -> [Accessibility]
```
Assertions: (a) `EXPECTED.keys ⊆ catalog ids` and `catalog.nodes.size == 128`; (b) per id `spec.gates.map { it.sig() }.toSet() == expected`; (c) every id not in EXPECTED has `gates.isEmpty()` (the "static list assertion": a new permission-guarded API without a gate, or a gate without a row here, fails); (d) `everyCatalogPermissionIsDeclared`: every `Gate.Permission` in the catalog + the special gates' manifest names (Overlay→SYSTEM_ALERT_WINDOW, DndPolicy→ACCESS_NOTIFICATION_POLICY, WriteSettings→WRITE_SETTINGS, IgnoreBatteryOpt→REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, PostNotifications→POST_NOTIFICATIONS) appear in the manifest, and the two services (`NotifListener` with `BIND_NOTIFICATION_LISTENER_SERVICE`, `UiAutomationService` with `BIND_ACCESSIBILITY_SERVICE`) exist; (e) `noDeadDeclarations`: every declared dangerous/special permission except the companions (ACCESS_COARSE_LOCATION, READ_MEDIA_VISUAL_USER_SELECTED) is referenced by a node gate or `APP_LEVEL`; (f) `neverDeclared`: SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, CAMERA, RECORD_AUDIO, BLUETOOTH_SCAN, PACKAGE_USAGE_STATS, NEARBY_WIFI_DEVICES, CALL_PHONE, SEND_SMS, READ_CALL_LOG, QUERY_ALL_PACKAGES are absent; (g) `noSdkBranchesInSpecs`: for every node, `spec.gates.none { it is Gate.Permission && !it.applies(26) && !it.applies(34) }` is not the point — instead assert the four formerly-branched nodes (bluetooth, new_photo, media_list, sensor) carry the static gates above (covered by (b)).

### 7.3 `app/src/test/java/com/mob8n/ui/PermissionStatusTest.kt` (ui lane, new; pure)
- `statusOf` truth table: !applies → NOT_APPLICABLE; !available → UNAVAILABLE (Feature missing; READ_PHONE_STATE on no-telephony); INFO kind → INFO; granted → GRANTED; IMAGES + partial → PARTIAL, AUDIO + partial → NOT_GRANTED; RUNTIME asked && !rationale → PERMANENTLY_DENIED; RUNTIME !asked && !rationale → NOT_GRANTED (never-asked is not permanently denied); BACKGROUND_LOCATION same two cases; Accessibility attempted && sdk 33 → BLOCKED_RESTRICTED, attempted && sdk 32 → NOT_GRANTED, !attempted && sdk 33 → NOT_GRANTED; NotificationListener identical; Overlay never BLOCKED_RESTRICTED.
- `inventory(catalog)`: all four groups non-empty; Overlay row `usedBy` contains "Launch app", "Open URL", "Share", "Settings panel", "App action" and `advisoryOnly == true`; NotificationListener row contains "Mob8N itself", "Now Playing", "Media control (optional)" and `advisoryOnly == false`; ACCESS_FINE_LOCATION row lists "Location", "Location Enter/Exit", "Network Changed (optional)", "Device State (optional)"; IgnoreBatteryOpt present with `usedBy == [SELF]`; LiveHost/ForegroundOnly absent; no duplicate keys across rows (Advisory and enforced merge).
- `wizardSteps`: ACCESS_BACKGROUND_LOCATION never inside `Step.Runtime`; runtime batch excludes NOT_APPLICABLE/UNAVAILABLE/PERMANENTLY_DENIED/GRANTED, includes PARTIAL, adds COARSE when FINE is present; `Step.BackgroundLocation` present only when sdk ≥ 29 and the row is pending; Settings steps follow `SETTINGS_ORDER`; permanently-denied gates end up in one trailing `Step.AppInfo`; empty when everything is granted.

### 7.4 Existing tests to touch
- `apps/AppNodesSpecTest.kt` (apps lane): `gated` column → expected `List<Gate>` (§5.4).
- `CatalogTest` unchanged (128); `ManifestReceiverTest` unchanged; `ExecutorTest`: add one case — a fake spec with `gates = listOf(Gate.Advisory(Gate.Permission("x")))` runs on the JVM path (android == null skips gates anyway); the meaningful assertion is `Gate.Advisory(...).enforced == false` in GatesTest, since the executor predicate is now `it.enforced`.

---

## 8. Device plan (Pixel Tablet, Android 16 / API 36, targetSdk-34 compat; integrator, after all lanes land)

Variables and preamble from DESIGN2 §10 (`$A`, `$PKG=com.mob8n`, `$DP`). Screenshots → `scratch/perm-NN-*.png` via `$A exec-out screencap -p`. Tap helper: `$A shell uiautomator dump /sdcard/ui.xml && $A pull /sdcard/ui.xml scratch/ && grep -o 'text="<label>"[^>]*bounds="\[[0-9,]*\]\[[0-9,]*\]"' scratch/ui.xml` → centre → `$A shell input tap X Y`.

**0. Baseline (everything revoked) + first launch.** `./build.sh` green (CatalogTest 128, CatalogGatesTest, GatesTest, PermissionStatusTest, AppNodesSpecTest). Install **without** `-g`: `$A install -r app/build/outputs/apk/debug/app-debug.apk` (adb installs are exempt from Restricted settings — step 6 covers the file-install path). Reset:
```sh
for p in POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION BLUETOOTH_CONNECT READ_CALENDAR WRITE_CALENDAR READ_CONTACTS READ_PHONE_STATE RECEIVE_SMS READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO READ_MEDIA_VISUAL_USER_SELECTED ACTIVITY_RECOGNITION; do
  $A shell pm revoke $PKG android.permission.$p 2>/dev/null; $A shell pm clear-permission-flags $PKG android.permission.$p user-fixed user-set 2>/dev/null; done
$A shell appops set $PKG SYSTEM_ALERT_WINDOW default; $A shell appops set $PKG WRITE_SETTINGS default
$A shell cmd notification disallow_dnd $PKG; $A shell cmd notification disallow_listener $PKG/com.mob8n.triggers.NotifListener
$A shell dumpsys deviceidle whitelist -$PKG; $A shell settings put secure enabled_accessibility_services ""
$A shell cmd location set-location-enabled false 2>/dev/null || $A shell settings put secure location_mode 0
$A shell pm clear $PKG            # resets settings.onboarded (and seeded)
$A shell am start -n $PKG/.MainActivity
```
Expect the **onboarding** screen (title "Set up Mob8N", Skip, Grant-all card, two columns since the screen is full-width ≥ 720 dp): `perm-01-onboarding.png`. Statuses: all runtime rows "Not granted"; Post notifications shown (33+); READ/WRITE_EXTERNAL_STORAGE under "Not needed on this Android version (2)" in Content; Overlay "Not granted"; Phone Call / SMS Received rows "Not available on this device" (`$A shell pm list features | grep telephony` empty); NFC "Not available on this device"; Camera flash / Accelerometer per `pm list features`; Location services "Not granted". Header "0 of N granted". Rotate (`settings put system user_rotation 1`) → still two columns; phone width later (step 8).

**1. Grant-all via the UI (adb taps).** Tap "Grant all" → system dialogs one per group: dump + tap "Allow" / "While using the app" / "Allow all" (Photos: test "Select photos" once later, step 4). `perm-02-runtime.png`: Essential/Connectivity/Content runtime rows Granted, step ✓, next step "Location all the time" with hint "Choose 'Allow all the time'". Tap **Next** → the system opens Mob8N's Location permission page (30+ behaviour) → tap "Allow all the time" → Back → ✓ on resume (`perm-03-bgloc.png`; verify `dumpsys package $PKG | grep -E 'ACCESS_(FINE|BACKGROUND)_LOCATION.*granted=true'`).

**2. Settings-page steps, one at a time (exercise the ON_RESUME refresh explicitly).** For each step: tap **Next** → screenshot the Settings page to prove the deep link (e.g. `perm-04a-listener-detail.png` shows Mob8N's own notification-listener detail page on 30+), then **grant via adb while the page is open**, press Back, and confirm the checkmark advances without auto-launching the next page:
- Notification access: `cmd notification allow_listener $PKG/com.mob8n.triggers.NotifListener`
- Battery: `dumpsys deviceidle whitelist +$PKG` (or tap Allow in the dialog)
- Display over other apps: `appops set $PKG SYSTEM_ALERT_WINDOW allow`
- Accessibility: `settings put secure enabled_accessibility_services $PKG/com.mob8n.apps.UiAutomationService; settings put secure accessibility_enabled 1`
- DND access: `cmd notification allow_dnd $PKG`
- Modify system settings: `appops set $PKG WRITE_SETTINGS allow`
- Location services: `cmd location set-location-enabled true` (fallback `settings put secure location_mode 3`)
`perm-04b..h`. End state `perm-05-all-granted.png`: every chip Granted, "N of N granted", card "All set", Grant-all disabled. Also do one full pass tapping the real toggles instead of adb (the dialogs/toggles allow it) so every row reaches Granted by the UI path at least once.

**3. adb fast path (repeat runs):** `for p in …; do $A shell pm grant $PKG android.permission.$p; done` (READ_PHONE_STATE / RECEIVE_SMS `pm grant` may fail on the tablet — expected, rows read "Not available"); `appops set … allow`; `cmd notification allow_dnd/allow_listener`; `deviceidle whitelist +`; `settings put secure enabled_accessibility_services …`; location on. Reopen Permissions → all Granted.

**4. Honest states.**
- Permanently denied: `pm revoke $PKG android.permission.READ_CONTACTS; pm set-permission-flags $PKG android.permission.READ_CONTACTS user-fixed` (30+ shell) after having tapped Grant once (so `perm.asked` is set) → chip "Permanently denied", button "Open App info" → App info → Permissions → allow → back → Granted (`perm-06-permadenied.png`). Same for POST_NOTIFICATIONS by denying twice in the UI (13+ auto-denies).
- Partial media (34+): `pm revoke $PKG android.permission.READ_MEDIA_IMAGES; pm grant $PKG android.permission.READ_MEDIA_VISUAL_USER_SELECTED` → chip "Partial access (selected photos)", "Choose all photos" → picker → "Allow all" → Granted (`perm-07-partial.png`).
- Background location honesty: revoke FINE → the background row's step reads "Needs Location first"; grant FINE → step becomes active.
- Overlay effect: `appops set $PKG SYSTEM_ALERT_WINDOW deny`, a11y service OFF, app in background; `trigger.schedule once` +1 min → `action.open_url https://example.com` → trampoline notification (needs POST_NOTIFICATIONS); re-allow → Chrome opens directly and `logcat -s ActivityTaskManager:W` shows no "Background activity launch blocked".
- Bluetooth: pair headphones; Bluetooth Device trigger fires with `name=null` when BLUETOOTH_CONNECT is revoked (trigger not painted red: advisory) and with the name when granted.
- Wi-Fi name: Network Changed with `ssidMatch`, app backgrounded, `svc wifi disable; sleep 5; svc wifi enable` → ssid non-null only with FINE + BACKGROUND granted and location on; otherwise the run log shows the "ssid=null" warning and the row hints say why.

**5. Restricted settings (Android 13+ file-install path; manual).** `$A push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/`; uninstall; install by opening the APK from the Files app on the tablet. Launch → Permissions → the Accessibility and Notification access rows already show the proactive hint (installer = `com.google.android.packageinstaller`: `dumpsys package $PKG | grep -i installer`). Tap "Open Settings" on UI automation → toggle greyed / "Restricted setting" dialog → Back → chip "Blocked by restricted settings" + `RESTRICTED_SETTINGS_HINT` + "Open App info" / "Try again" (`perm-08-restricted.png`) → App info ⋮ > Allow restricted settings → "Try again" → toggle works → Granted. Repeat for Notification access. Reinstall via adb afterwards.

**6. Phone width:** `wm size 1080x2400 && wm density 420` → relaunch → single column (`perm-09-phone.png`) → `wm size reset; wm density reset`.

**7. Regression:** seed workflows' list badges agree with the Permissions rows (no double "needs host" + "needs permission" for host-only gaps); Palette shows "Optional: …" for advisory gates; run `data.sensor light` without ACTIVITY_RECOGNITION → succeeds; `data.sensor steps` → "Needs activity recognition…" (execute check intact); `action.add_calendar_event silent=true` without WRITE_CALENDAR → "Needs write calendar permission"; `action.download` on this device (API 36) unaffected by the ≤ 28 gate.

**8. a11y:** `uiautomator dump` of the Permissions screen: no `ImageView`/`Button` node with `content-desc=""`; TalkBack spot check (enable `com.google.android.marvin.talkback` via `settings put secure enabled_accessibility_services`, then disable): a row announces "<title>: <status>", the stepper rows "Step k of n, <title>, done/pending".

Pass criteria: unit tests green; screenshots 01-09 recorded in README; no `AndroidRuntime:E`; every row reaches Granted by the UI path at least once; the Grant-all stepper completes end-to-end with no auto-launched Settings page.

---

## 9. File ownership

| Owner | Files | Notes |
|---|---|---|
| **integrator** | `core/Gates.kt` (§3), `core/Executor.kt` :106/:184 (§3.1), `AndroidManifest.xml` (§4), `res/values/strings.xml` (no change), `app/src/test/java/com/mob8n/core/GatesTest.kt` | Lands first; everything else compiles against it. |
| **"gates" fixer** (single agent) | the per-node `gates =` edits in §5.1-5.4 (`actions/Intents.kt` incl. `BG_LAUNCH` + `canStartDirectly(ctx)`, `actions/Media.kt`, `actions/SystemSettings.kt`, `actions/Playlist.kt`, `actions/Files.kt`, `actions/Notify.kt`, `triggers/RuntimeTriggers.kt`, `triggers/SensorTriggers.kt`, `data/DeviceNodes.kt`, `data/MediaNodes.kt`, `apps/Recipes.kt`), `app/src/test/java/com/mob8n/CatalogGatesTest.kt`, `apps/AppNodesSpecTest.kt` column change | Touches only the listed lines (one-line hunks) so it merges cleanly with the v3 MCP/knowledge lanes that own those packages' other code. |
| **ui lane** | `ui/Permissions.kt` (rewrite), `ui/App.kt` (§6.8 glue only), `ui/Canvas.kt:163`, `ui/ParamSheet.kt:108`, `ui/Palette.kt:84`, `app/src/test/java/com/mob8n/ui/PermissionStatusTest.kt` | The v3 ui lane must not touch `Permissions.kt`; App.kt change is a 10-line wrapper around the existing body. |
| **integrator (device)** | §8 plan, README record | after all three land. |

Order: integrator core+manifest → gates fixer and ui lane in parallel → integrator build + device.

---

## 10. Risks

1. **SYSTEM_ALERT_WINDOW as a background-start exemption** is documented for AOSP, but OEM forks (MIUI/ColorOS) add their own "display pop-up windows while in background" switch and Android Go ignores the exemption. The trampoline stays as the fallback and `Overlay` is non-enforced, so the worst case is the old behaviour; device step 4 checks logcat. Declaring it looks heavy for a sideloaded automation app — the row text says exactly what it is used for and that Mob8N never draws.
2. **Restricted-settings detection is a heuristic** (no public API): SDK ≥ 33 && attempted && still off. A user who simply backed out of Settings sees "Blocked by restricted settings" with the explanation and both buttons; the normal "Try again" path is never hidden, and a wrong "Granted" is impossible.
3. **Permanently-denied detection** relies on our `perm.asked.*` flag + `shouldShowRequestPermissionRationale` (needs the Activity; a null activity reads as "rationale = true" → NOT_GRANTED). Permissions revoked via adb/App info before we ever asked show "Not granted" until one Grant tap, then flip correctly. Clearing app data resets the flags (acceptable). On 30+, after the background-location settings-page round-trip the rationale flag may report false → the row shows "Permanently denied / Open App info", which is the right action on 30+ anyway (only the chip text differs).
4. **Background location on 30+ has no dialog**; a user who picks "While using the app" leaves geofences dead from a cold host and SSID redacted in the background. The step stays unchecked with the "Allow all the time" hint — honest, but not finishable without Settings. Batching BACKGROUND with anything else makes the system drop the whole request: `wizardSteps` structurally prevents it and `PermissionStatusTest` guards it.
5. **Android 14+ partial photo access**: READ_MEDIA_IMAGES reads denied while VISUAL_USER_SELECTED is granted; the executor keeps refusing `trigger.new_photo` / `data.media_list` (they need all media) and the PARTIAL chip explains why. Upgrade note: `data.media_list` could accept partial access later.
6. **SSID from a background host**: even with FINE + BACKGROUND + location on, `WifiManager.getConnectionInfo()` (the 26-30 path in `data.device_state`) can still return "<unknown ssid>" from an FGS on some builds. The advisory gates and LocationOn make the requirement visible; the node keeps `wifiSsid = null` rather than failing.
7. **JVM tests see SDK_INT == 0**: `applies()` must always be called with an explicit `sdk` in tests, and `CatalogGatesTest` compares gate identity, never `granted()`/`available()`. NodeSpecs must not branch on `Build.VERSION` any more — the SDK_RANGE table replaces the four branches; a future SDK-conditional gate belongs in SDK_RANGE, not in a spec.
8. **`Gate.Advisory` is a new subclass**: every exhaustive `when (gate)` (rememberGranter, tests' `sig()`) gains a branch — a compile error if forgotten, not a runtime bug. `spec.gates.distinct()` keeps `Advisory(X)` and `X` apart; every UI aggregation must de-dup through `.key` (`inventory`, `gatesInUse`).
9. **Badge rule change (D14)**: advisory/informational gates no longer paint a node red or count in the list badge; LiveHost's missing host is signalled by the amber "needs host" chip only (it previously also counted as "needs permission"). Deliberate and documented; if the team prefers the old Canvas colouring for LiveHost, add `|| g == Gate.LiveHost` to the Canvas predicate (one clause).
10. **Onboarding** appears before any workflow exists and on every fresh install; "Skip" is one tap and the screen never blocks when an OEM lacks a Settings page (try/catch + snackbar). In the ≥ 840 dp shell onboarding is full-screen (not in the detail pane) — intended.
11. **Tablet specifics**: no telephony/NFC → "Not available on this device" rows; `pm grant READ_PHONE_STATE` may fail (expected). Android 16 running a targetSdk-34 app: the photo picker, notification auto-deny and background-location settings-page behaviours are the 33/34 ones described here; `cmd location set-location-enabled` is preferred over the deprecated `location_mode` secure setting.
12. **Lane overlap with v3 MCP/knowledge lanes** (engine/ai/data/actions/ui): the gates fixer's edits are single-line hunks on `gates =` arguments plus one small hunk in `Intents.kt` (`canStartDirectly(ctx)` + `BG_LAUNCH`); `Permissions.kt` is a whole-file rewrite owned by this ui lane only. Coordinate merge order (§9) to avoid conflicts.

---

## 11. Integration record + deviations (2026-09-25)

Landed in the order §9 prescribes: integrator core (`core/Gates.kt` §3 verbatim, `core/Executor.kt` `it.enforced` at both sites, manifest
§4: `READ_MEDIA_VISUAL_USER_SELECTED` + `SYSTEM_ALERT_WINDOW` + the "NOT requested" comment, `GatesTest` 7 cases) → ui lane
(`Permissions.kt` rewrite, `App.kt` onboarding glue, Canvas/ParamSheet/Palette one-liners, `PermissionStatusTest`) in parallel with the
v3 MCP/knowledge lanes → **gates fixer applied by the integrator** after all lanes copied back: all **26 per-node `gates =` edits** of §5.1-5.4
(11 `BG_LAUNCH` rows + media_control, settings_panel, add_to_playlist, write_file, download, cancel_notification; network, bluetooth,
new_photo, geofence; device_state, location, sensor, media_list; app.action) plus the `Intents.kt` hunk (`canStartDirectly(ctx)` with
`Settings.canDrawOverlays`, caller, `internal val BG_LAUNCH`) and the `Recipes.kt` `Gate` import; `AppNodesSpecTest` column → `List<Gate>`;
new root `CatalogGatesTest` (8 cases: 133 ids, exact per-id gate signatures, "every other node has no gates", the four formerly-branched
specs static, every catalog permission + the special-access names + both services declared, `noDeadDeclarations`, `neverDeclared`).
Gate: `./build.sh` green — 358 unit tests, 0 failures. The catalog is 133 nodes (DESIGN3), so §7.2 (a) reads 133, not 128.

### 11.1 Deviations (code wins)
- Row model is `PermRow` (Compose's `Row` is used throughout the ui package); test file is `PermissionStatusTest.kt` (§7.3 name).
- Restricted-settings chip reads **"Not enabled (restricted setting?)"** with Open App info / Try again (accepted: the detection is a heuristic,
  so the chip does not assert a cause). `RESTRICTED_SETTINGS_HINT` paragraph unchanged.
- Grant-all state is persisted as `'\n'`-joined step keys (`runtime`, `bgloc`, `settings:<label>`, `appinfo`) + skipped keys in
  `rememberSaveable`, not a `stepIdx` Int; done-ness is derived live from `wizardSteps` on every ON_RESUME / result tick. Same UX (D12).
- One `LazyVerticalGrid` with 1 column below 720 dp and 2 columns at ≥ 720 dp instead of `LazyColumn` + grid; row content identical.
- `rememberGranter(onResult = {}, onError = {})` gained two optional parameters (ParamSheet/Palette call sites unchanged).
- `PermissionStatusTest.inventoryOverTheRealCatalog` gained the §7.3 Overlay / "Media control (optional)" / "Network Changed (optional)" /
  "Device State (optional)" / `WRITE_EXTERNAL_STORAGE` (Download enforced, Write file optional) assertions once the fixer landed.
- `ExecutorTest` +1 case (§7.4) was not added: `android == null` skips gates on the JVM path, so the only meaningful assertion is
  `Gate.Advisory(...).enforced == false`, which `GatesTest.enforcedFlags` already holds.
- No new SharedPreferences file: `onboarded`, `perm.asked.<permission>` and `gate.attempt.<label>` live in `SETTINGS_PREFS`; `pm clear` resets them (§8 step 0).

### 11.2 Device phase (§8)
Pending; run after the DESIGN3 §8 steps on the same install (never uninstall — the device holds the user's MiniMax key). Notes carried over
from the v2 phase: `am force-stop com.mob8n` clears `enabled_accessibility_services` on Android 16, so re-grant accessibility before the
Overlay-effect / a11y steps; telephony and NFC rows read "Not available on this device" on the Pixel Tablet.

