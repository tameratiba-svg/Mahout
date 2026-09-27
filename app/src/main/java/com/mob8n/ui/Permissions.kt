@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mob8n.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.core.Catalog
import com.mob8n.core.Gate
import com.mob8n.core.GateGroup
import com.mob8n.core.GrantKind
import com.mob8n.core.LOG_TAG
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.Workflow
import com.mob8n.engine.Engine
import kotlinx.coroutines.launch

// =====================================================================================================================
// Pure model (DESIGN3P §6.1) — JVM-tested in PermissionStatusTest; nothing above the composables touches Android.
// =====================================================================================================================

/** Gates no node can express; "used by Mahout itself" (Doze, background host, approval prompts / service notice). */
val APP_LEVEL: List<Gate> = listOf(Gate.IgnoreBatteryOpt, Gate.NotificationListener, Gate.PostNotifications)
const val SELF = "Mahout itself"

/** One Permissions-screen row: a distinct gate key, the node names using it (advisory-only users get " (optional)"), and whether every use is advisory. */
data class PermRow(val gate: Gate, val usedBy: List<String>, val advisoryOnly: Boolean)

private fun p(perm: String) = Gate.Permission(perm)
private val P_FINE = Manifest.permission.ACCESS_FINE_LOCATION
private val P_COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
private val P_BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION
val MEDIA_VISUAL: Set<String> = setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

/** Section-internal display order (DESIGN3P §6.1 RANK); Feature rows go after the connectivity permissions, unknown gates last. */
private val RANK: List<Gate> = listOf(
    Gate.NotificationListener, Gate.PostNotifications, Gate.IgnoreBatteryOpt,
    Gate.Accessibility, Gate.Overlay, Gate.DndPolicy, Gate.WriteSettings, Gate.ExactAlarm,
    p(P_FINE), p(P_BACKGROUND), Gate.LocationOn, p(Manifest.permission.BLUETOOTH_CONNECT), p(Manifest.permission.ACTIVITY_RECOGNITION),
    p(Manifest.permission.READ_MEDIA_IMAGES), p(Manifest.permission.READ_MEDIA_VIDEO), p(Manifest.permission.READ_MEDIA_AUDIO),
    p(Manifest.permission.READ_EXTERNAL_STORAGE), p(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    p(Manifest.permission.READ_CALENDAR), p(Manifest.permission.WRITE_CALENDAR), p(Manifest.permission.READ_CONTACTS),
    p(Manifest.permission.READ_PHONE_STATE), p(Manifest.permission.RECEIVE_SMS),
)
private fun rank(g: Gate): Int = if (g is Gate.Feature) 50 else RANK.indexOf(g).let { if (it < 0) 99 else it }

/** Every distinct gate key across the whole catalog + APP_LEVEL, grouped by section; LiveHost/ForegroundOnly excluded (the Host status block covers them). */
fun inventory(catalog: Catalog): Map<GateGroup, List<PermRow>> {
    val uses = LinkedHashMap<Gate, MutableList<Pair<String, Boolean>>>()          // key -> (node name, enforced)
    APP_LEVEL.forEach { uses.getOrPut(it) { mutableListOf() } += SELF to true }
    for (n in catalog.nodes) for (g in n.spec.gates) uses.getOrPut(g.key) { mutableListOf() } += n.spec.name to g.enforced
    return uses.filterKeys { it != Gate.LiveHost && it != Gate.ForegroundOnly }
        .map { (g, u) ->
            val names = u.map { it.first }.distinct().map { name -> if (u.any { it.first == name && it.second }) name else "$name (optional)" }
            PermRow(g, names, advisoryOnly = u.none { it.second })
        }
        .sortedWith(compareBy({ it.advisoryOnly }, { rank(it.gate) }))
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
    partialMedia && (gate.key as? Gate.Permission)?.permission in MEDIA_VISUAL -> GateStatus.PARTIAL
    (gate.kind == GrantKind.RUNTIME || gate.kind == GrantKind.BACKGROUND_LOCATION) && askedBefore && !showRationale -> GateStatus.PERMANENTLY_DENIED
    // ponytail: heuristic (no public API): SDK>=33 && attempted && still off; installer package only enriches the hint
    (gate.key == Gate.Accessibility || gate.key == Gate.NotificationListener) && attemptedSettings && sdk >= 33 -> GateStatus.BLOCKED_RESTRICTED
    else -> GateStatus.NOT_GRANTED
}

/** Chip text per status; colour is never the only signal. Restricted-settings wording is the accepted deviation ("Not enabled (restricted setting?)"). */
fun statusText(s: GateStatus, gate: Gate): String = when (s) {
    GateStatus.GRANTED -> "Granted"
    GateStatus.NOT_GRANTED -> "Not granted"
    GateStatus.PERMANENTLY_DENIED -> "Permanently denied"
    GateStatus.BLOCKED_RESTRICTED -> "Not enabled (restricted setting?)"
    GateStatus.PARTIAL -> "Partial access (selected photos)"
    GateStatus.NOT_APPLICABLE -> "Not needed on this Android version"
    GateStatus.UNAVAILABLE -> "Not available on this device"
    GateStatus.INFO -> if (gate.key is Gate.Feature) "Available" else "Info"
}

sealed class Step(val title: String) {
    class Runtime(val permissions: List<String>) : Step("Allow permissions")           // one RequestMultiplePermissions dialog run
    object BackgroundLocation : Step("Location all the time")
    class Settings(val gate: Gate) : Step(gate.title)
    class AppInfo(val gates: List<Gate>) : Step("Open App info")
    /** Stable identity across recomputes (the plan is persisted as keys, see the stepper). */
    val key: String get() = when (this) { is Runtime -> "runtime"; BackgroundLocation -> "bgloc"; is Settings -> "settings:${gate.label}"; is AppInfo -> "appinfo" }
}

val SETTINGS_ORDER: List<Gate> = listOf(Gate.NotificationListener, Gate.IgnoreBatteryOpt, Gate.Overlay, Gate.Accessibility, Gate.DndPolicy, Gate.WriteSettings, Gate.LocationOn)

/** Runtime permission names a gate asks for (companions included: FINE brings COARSE, IMAGES/VIDEO travel together). */
fun runtimePermissions(gate: Gate): List<String> = when (val k = gate.key) {
    is Gate.Permission -> when (k.permission) {
        P_FINE -> listOf(P_FINE, P_COARSE)
        in MEDIA_VISUAL -> MEDIA_VISUAL.toList()
        else -> listOf(k.permission)
    }
    Gate.PostNotifications -> listOf(Manifest.permission.POST_NOTIFICATIONS)
    else -> emptyList()
}

/** Grant-all plan from current statuses. BACKGROUND_LOCATION is never inside the runtime batch (30+ ignores the whole request otherwise). */
fun wizardSteps(rows: List<PermRow>, status: (Gate) -> GateStatus, fineGranted: Boolean, sdk: Int): List<Step> = buildList {
    val pending = rows.filter { status(it.gate) in setOf(GateStatus.NOT_GRANTED, GateStatus.PARTIAL, GateStatus.BLOCKED_RESTRICTED) }
    val runtime = pending.filter { it.gate.kind == GrantKind.RUNTIME }.flatMap { runtimePermissions(it.gate) }.distinct()
    if (runtime.isNotEmpty()) add(Step.Runtime(runtime))                             // rows arrive in section order: Essential -> Connectivity -> Content
    if (sdk >= 29 && pending.any { it.gate.kind == GrantKind.BACKGROUND_LOCATION }) add(Step.BackgroundLocation)   // "Needs Location first" until fineGranted
    SETTINGS_ORDER.forEach { g -> if (pending.any { it.gate.key == g }) add(Step.Settings(g)) }
    rows.filter { status(it.gate) == GateStatus.PERMANENTLY_DENIED }.map { it.gate }.takeIf { it.isNotEmpty() }?.let { add(Step.AppInfo(it)) }
}

/** Display form of a gate label: Gate.Permission derives lowercase labels from the manifest name ("read media images"). */
val Gate.title: String get() = label.replaceFirstChar(Char::titlecase)

fun Gate.grantable(): Boolean = kind != GrantKind.INFO

/** Distinct enforced gate keys used by enabled workflows' nodes, in first-seen order (DESIGN3P D14: badges count enforced gates only). */
fun gatesInUse(workflows: List<Workflow>, catalog: Catalog, enabledOnly: Boolean = true): List<Gate> =
    workflows.filter { !enabledOnly || it.enabled }.flatMap { wf -> wf.graph.nodes.flatMap { catalog.spec(it.type)?.gates ?: emptyList() } }
        .filter { it.enforced }.map { it.key }.distinct()

fun missingGateCount(ctx: Context, workflows: List<Workflow>, catalog: Catalog): Int =
    gatesInUse(workflows, catalog).count { runCatching { !it.granted(ctx) }.getOrDefault(false) }

/** Missing gates of enabled workflows (count) + ids of the workflows that use one. Each distinct gate is checked once. */
data class GateCensus(val missing: Int = 0, val workflowIds: Set<String> = emptySet())

fun gateCensus(ctx: Context, workflows: List<Workflow>, catalog: Catalog): GateCensus {
    val missing = gatesInUse(workflows, catalog).filterTo(HashSet()) { runCatching { !it.granted(ctx) }.getOrDefault(false) }
    if (missing.isEmpty()) return GateCensus()
    return GateCensus(missing.size, workflows.filter { wf -> gatesInUse(listOf(wf), catalog).any { it in missing } }.mapTo(HashSet()) { it.id })
}

/** M7: the gate checks are binder calls (accessibility, notification, power, settings); run them off the main thread, never in a first-compose frame. */
@Composable
fun rememberGateCensus(workflows: List<Workflow>, catalog: Catalog, tick: Int): GateCensus {
    val ctx = LocalContext.current
    return produceState(GateCensus(), workflows, tick) { value = withContext(Dispatchers.IO) { gateCensus(ctx, workflows, catalog) } }.value
}

// ---- verbatim texts (DESIGN3P §6.4 / §6.6) ----

/** DESIGN2 §7.6 disclosure + Restricted-settings hint; shown on the Permissions card whether or not a workflow uses app.ui_* nodes. */
const val UI_AUTOMATION_DISCLOSURE = "UI automation lets workflows read what is on screen (text, buttons, field names) and tap, type and scroll in other apps on your behalf. " +
    "Mahout reads the screen only while a UI node is running; nothing is recorded except screenshots you explicitly take, which stay in Mahout's private cache and are sent to your chosen AI provider only when wired into an AI node. " +
    "Like a post, follow, comment: no Android app offers a public API for these; this is the only on-device way. " +
    "If the toggle is greyed out ('Restricted setting'), open App info > ⋮ > Allow restricted settings (Android 13+ requires this for apps installed from a file; adb installs are exempt)."
const val OVERLAY_DISCLOSURE = "Display over other apps lets a workflow open apps, links, share sheets and the dialer immediately while Mahout is in the background (Android 10 and later block this otherwise). " +
    "Mahout never draws anything over other apps. Without it, Mahout posts a 'Tap to open' notification instead — everything still works, one tap later."
const val RESTRICTED_SETTINGS_HINT = "Restricted setting: Android 13 and later block this toggle for apps installed from a file until you allow it once. " +
    "Open App info, tap ⋮ (top right) and choose 'Allow restricted settings' — the menu item appears only after you have tried the toggle once — then come back and try again. " +
    "Apps installed with adb or from a store are not affected."
const val BACKGROUND_LOCATION_WHY = "Geofences fire and Wi-Fi names are readable while Mahout runs in the background only with 'Allow all the time'. " +
    "Mahout reads location only when a Location node runs or a geofence is armed."
const val KNOWLEDGE_PRIVACY = "Knowledge stays on this device. Files are read once, split into text passages and stored in Mahout's private database. " +
    "Only the passages an agent retrieves for a question — and the sources you pin — are sent to the AI provider you chose. " +
    "Cloud files (Google Drive, Dropbox, OneDrive) are read through Android's document picker; Mahout has no cloud account access. PDFs are not supported yet."
const val MCP_PRIVACY = "MCP servers you add in Settings > AI receive only the tool arguments the agent sends, after your approval unless you mark the server as trusted."
private const val SETTINGS_UNAVAILABLE = "This Settings page is not available on this device — open App info instead"

fun gateWhy(gate: Gate): String = when (val k = gate.key) {
    Gate.NotificationListener -> "Keeps Mahout alive in the background, reads notifications and now-playing media. The recommended host."
    Gate.PostNotifications -> "Approval prompts, run failures, 'Tap to open' fallbacks and the background-service notice."
    Gate.IgnoreBatteryOpt -> "Lets schedules and delayed runs fire on time in Doze."
    Gate.Accessibility -> UI_AUTOMATION_DISCLOSURE
    Gate.Overlay -> OVERLAY_DISCLOSURE
    Gate.DndPolicy -> "Change Do Not Disturb and ring volume while DND is on."
    Gate.WriteSettings -> "Brightness, auto-rotate, screen timeout, ringtone."
    Gate.LocationOn -> "Android hides Wi-Fi names and location fixes while the device location switch is off."
    Gate.ExactAlarm -> "Exact alarm scheduling (not used by the current catalog)."
    is Gate.Permission -> when (k.permission) {
        P_FINE -> "Current location, geofences and the Wi-Fi network name."
        P_BACKGROUND -> "Geofences and Wi-Fi names while Mahout runs in the background."
        Manifest.permission.BLUETOOTH_CONNECT -> "Names and type of connecting Bluetooth devices (connect/disconnect events work without it)."
        Manifest.permission.ACTIVITY_RECOGNITION -> "Step counter reading."
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO -> "Detect new photos and screenshots, list media."
        Manifest.permission.READ_MEDIA_AUDIO -> "List songs and mirror playlists."
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Media and public Documents/Music/Downloads folders on older Android."
        Manifest.permission.READ_CALENDAR -> "Upcoming events."
        Manifest.permission.WRITE_CALENDAR -> "Insert events without opening the calendar app."
        Manifest.permission.READ_CONTACTS -> "Look up names and numbers."
        Manifest.permission.READ_PHONE_STATE -> "Ringing / call state (no numbers)."
        Manifest.permission.RECEIVE_SMS -> "Incoming SMS text (sensitive; nodes are off by default)."
        else -> k.title
    }
    is Gate.Feature -> "Hardware feature."
    else -> k.title
}

/** Where the toggle lives, for SETTINGS gates. */
fun settingsHint(gate: Gate): String? = when (gate.key) {
    Gate.NotificationListener -> "Settings > Notifications > Device & app notifications > Mahout"
    Gate.IgnoreBatteryOpt -> "System dialog: tap Allow"
    Gate.Overlay -> "Settings > Apps > Special app access > Display over other apps > Mahout"
    Gate.Accessibility -> "Settings > Accessibility > Mahout UI automation"
    Gate.DndPolicy -> "Settings > Apps > Special app access > Do Not Disturb access > Mahout"
    Gate.WriteSettings -> "Settings > Apps > Special app access > Modify system settings > Mahout"
    Gate.LocationOn -> "Settings > Location > Use location"
    else -> null
}

fun sectionTitle(g: GateGroup): String = when (g) {
    GateGroup.ESSENTIAL -> "Essential"; GateGroup.AUTOMATION -> "Automation"; GateGroup.CONNECTIVITY -> "Connectivity & sensors"; GateGroup.CONTENT -> "Content"
}

// =====================================================================================================================
// Android glue: status inputs, granting
// =====================================================================================================================

private fun prefs(ctx: Context) = ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }
private fun newTask(i: Intent) = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

fun openAppInfo(ctx: Context) {
    try { ctx.startActivity(newTask(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)))) }
    catch (e: Exception) { android.util.Log.w(LOG_TAG, "app info: $e") }
}

/** D10: the ask flag is written BEFORE the dialog opens (commit, not apply) so a process kill mid-dialog still records it. */
private fun markAsked(ctx: Context, perms: List<String>) {
    val e = prefs(ctx).edit(); perms.forEach { e.putBoolean("perm.asked.$it", true) }; e.commit()
}

/** One RequestMultiplePermissions launcher; the callback bumps the caller's result tick so statuses recompute. */
@Composable
fun rememberPermissionRequester(onResult: () -> Unit = {}): (List<String>) -> Unit {
    val ctx = LocalContext.current
    val cb by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { cb() }
    return { perms -> if (perms.isNotEmpty()) { markAsked(ctx, perms); launcher.launch(perms.toTypedArray()) } }
}

/** One lambda that knows how to ask for any Gate (DESIGN §9.2, DESIGN3P §6.7). Same lambda for ParamSheet / Palette / the Permissions rows. */
@Composable
fun rememberGranter(onResult: () -> Unit = {}, onError: (String) -> Unit = {}): (Gate) -> Unit {
    val ctx = LocalContext.current
    val request = rememberPermissionRequester(onResult)
    fun settings(gate: Gate, i: Intent) {
        prefs(ctx).edit().putLong("gate.attempt.${gate.label}", System.currentTimeMillis()).apply()   // D9: "attempted" input of BLOCKED_RESTRICTED
        ctx.startActivity(newTask(i))
    }
    return { g: Gate ->
        try {
            when (val gate = g.key) {   // Advisory(X) is asked for as X
                is Gate.Advisory -> {}  // unreachable: key unwraps every Advisory layer
                is Gate.Permission -> request(runtimePermissions(gate))   // BACKGROUND alone; FINE + COARSE; IMAGES + VIDEO
                Gate.PostNotifications -> if (Build.VERSION.SDK_INT >= 33) request(runtimePermissions(gate))
                Gate.NotificationListener, Gate.LiveHost -> settings(Gate.NotificationListener,
                    if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx.packageName, "com.mob8n.triggers.NotifListener").flattenToString())
                    else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Gate.Overlay -> settings(gate, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(ctx)))
                Gate.LocationOn -> settings(gate, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                Gate.DndPolicy -> settings(gate, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                Gate.WriteSettings -> settings(gate, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(ctx)))
                Gate.IgnoreBatteryOpt -> settings(gate, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(ctx)))
                Gate.ExactAlarm -> if (Build.VERSION.SDK_INT >= 31) settings(gate, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(ctx)))
                Gate.Accessibility -> settings(gate, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                is Gate.Feature, Gate.ForegroundOnly -> {} // informational only
            }
        } catch (e: Exception) {
            // Some OEM builds lack a settings screen: report, never crash (§6.6 snackbar with "App info").
            android.util.Log.w(LOG_TAG, "grant ${g.label}: $e")
            onError(SETTINGS_UNAVAILABLE)
        }
    }
}

/** Everything the rows need from Android, gathered once per tick (DESIGN3P §6.2). */
class GateStatuses(val status: Map<Gate, GateStatus>, val sideloadHint: Boolean, val fineGranted: Boolean, val coarseGranted: Boolean) {
    fun of(g: Gate): GateStatus = status[g.key] ?: GateStatus.INFO
}

@Composable
fun rememberGateStatuses(rows: List<PermRow>, tick: Int): GateStatuses {
    val ctx = LocalContext.current
    return remember(rows, tick) {
        val sdk = Build.VERSION.SDK_INT
        val pr = prefs(ctx)
        val activity = ctx.findActivity()
        fun has(perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        val partialMedia = sdk >= 34 && runCatching { has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) && !has(Manifest.permission.READ_MEDIA_IMAGES) }.getOrDefault(false)
        val sideload = sdk >= 33 && runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName }.getOrNull() in
            setOf("com.google.android.packageinstaller", "com.android.packageinstaller")
        val status = rows.associate { r ->
            val g = r.gate
            val granted = runCatching { g.granted(ctx) }.getOrDefault(false)
            val available = runCatching { g.available(ctx) }.getOrDefault(false)
            val applies = runCatching { g.applies(sdk) }.getOrDefault(false)
            val perms = runtimePermissions(g)
            val asked = perms.any { pr.getBoolean("perm.asked.$it", false) }
            // null activity -> treat as "rationale = true": never falsely permanently denied
            val rationale = activity == null || perms.any { runCatching { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }.getOrDefault(true) }
            val attempted = pr.getLong("gate.attempt.${g.label}", 0L) > 0L
            if (granted && attempted) pr.edit().remove("gate.attempt.${g.label}").apply()
            g to statusOf(g, granted, available, applies, asked, rationale, attempted, sdk, partialMedia)
        }
        GateStatuses(status, sideload, runCatching { has(P_FINE) }.getOrDefault(false), runCatching { has(P_COARSE) }.getOrDefault(false))
    }
}

// =====================================================================================================================
// Screen
// =====================================================================================================================

/**
 * The permissions center (DESIGN3P §6): every catalog gate + APP_LEVEL, derived not hand-listed. `onboarding = true` is the
 * first-launch variant (title "Set up Mahout", Skip, no back arrow); Back / Skip / the stepper's Done all call onBack.
 */
@Composable
fun PermissionsScreen(engine: Engine, catalog: Catalog, onBack: () -> Unit, onboarding: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val tick = rememberResumeTick()
    var resultTick by remember { mutableIntStateOf(0) }
    val onError: (String) -> Unit = { msg -> scope.launch { if (snack.showSnackbar(msg, actionLabel = "App info") == SnackbarResult.ActionPerformed) openAppInfo(ctx) } }
    val grant = rememberGranter(onResult = { resultTick++ }, onError = onError)
    val request = rememberPermissionRequester(onResult = { resultTick++ })
    val host by engine.hostStatus.collectAsStateWithLifecycle()
    val sections = remember(catalog) { inventory(catalog) }
    val rows = remember(sections) { GateGroup.entries.flatMap { sections[it] ?: emptyList() } }
    val st = rememberGateStatuses(rows, tick + resultTick)
    val sdk = Build.VERSION.SDK_INT
    val live = remember(st) { wizardSteps(rows, st::of, st.fineGranted, sdk) }
    val granted = rows.count { st.of(it.gate) == GateStatus.GRANTED }
    val total = rows.count { st.of(it.gate) !in setOf(GateStatus.NOT_APPLICABLE, GateStatus.UNAVAILABLE, GateStatus.INFO) }
    var showNa by rememberSaveable { mutableStateOf(false) }

    // Grant-all plan = step keys snapshotted at tap (+ steps that appear later, e.g. AppInfo after a denial); survives rotation and process death.
    var planStr by rememberSaveable { mutableStateOf("") }
    var skippedStr by rememberSaveable { mutableStateOf("") }
    val plan = planStr.split('\n').filter { it.isNotBlank() }
    val skipped = skippedStr.split('\n').filter { it.isNotBlank() }.toSet()
    val liveByKey = live.associateBy { it.key }
    LaunchedEffect(live, planStr) { if (planStr.isNotEmpty()) { val add = live.map { it.key }.filter { it !in plan }; if (add.isNotEmpty()) planStr = (plan + add).joinToString("\n") } }

    fun perform(step: Step) {
        when (step) {
            is Step.Runtime -> request(step.permissions)
            Step.BackgroundLocation -> when {
                !st.fineGranted -> request(listOf(P_FINE, P_COARSE))
                st.of(p(P_BACKGROUND)) == GateStatus.PERMANENTLY_DENIED -> openAppInfo(ctx)
                else -> request(listOf(P_BACKGROUND))
            }
            is Step.Settings -> grant(step.gate)
            is Step.AppInfo -> openAppInfo(ctx)
        }
    }
    val finish = { planStr = ""; skippedStr = ""; if (onboarding) onBack() }
    if (onboarding) BackHandler { onBack() }

    Scaffold(
        topBar = {
            MahoutTopBar(if (onboarding) "Set up Mahout" else "Permissions", onBack = if (onboarding) null else onBack,
                actions = { if (onboarding) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Skip setup" }) { Text("Skip") } })
        },
        snackbarHost = { MahoutSnackbarHost(snack) },
    ) { pad ->
        val motion = LocalMotion.current
        val gutter = pageGutter()
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val cols = if (maxWidth >= 720.dp) 2 else 1   // D16: two 360-dp cards fit; a 1-column grid is the phone LazyColumn
            LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(gutter), verticalArrangement = Arrangement.spacedBy(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    HeaderCard(granted, total, plan, skipped, liveByKey, st, sdk,
                        onGrantAll = { planStr = live.joinToString("\n") { it.key }; skippedStr = ""; live.firstOrNull()?.let(::perform) },
                        onPerform = ::perform, onSkip = { skippedStr = (skipped + it).joinToString("\n") }, onDone = finish, onAppInfo = { openAppInfo(ctx) })
                }
                for (group in GateGroup.entries) {
                    val list = sections[group] ?: continue
                    val (na, shown) = list.partition { st.of(it.gate) == GateStatus.NOT_APPLICABLE }
                    item(key = "section:$group", span = { GridItemSpan(maxLineSpan) }) {
                        Text(sectionTitle(group), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.m).semantics { heading() })
                    }
                    items(shown, key = { "row:" + it.gate.label }) { r -> GateRow(r, st, onGrant = {
                        if (r.gate.kind == GrantKind.BACKGROUND_LOCATION && !st.fineGranted) request(listOf(P_FINE, P_COARSE)) else grant(r.gate)
                    }, onAppInfo = { openAppInfo(ctx) }, modifier = Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect())) }
                    if (na.isNotEmpty()) {
                        item(key = "na:$group", span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { showNa = !showNa }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${if (showNa) "Hide" else "Show"} ${na.size} permissions not needed on this Android version" }) {
                                RotatingChevron(showNa); Spacer(Modifier.width(Space.xs))
                                Text("Not needed on this Android version (${na.size})")
                            }
                        }
                        if (showNa) items(na, key = { "row:" + it.gate.label }) { r -> GateRow(r, st, onGrant = {}, onAppInfo = {}, modifier = Modifier.animateItem(fadeInSpec = motion.effect(), placementSpec = motion.spatial(), fadeOutSpec = motion.effect())) }
                    }
                }
                item(key = "knowledge", span = { GridItemSpan(maxLineSpan) }) {
                    SectionCard(Modifier.semantics(mergeDescendants = true) {}) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = "Knowledge and MCP", tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(Space.s))
                                Text("Knowledge & MCP", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(KNOWLEDGE_PRIVACY, style = MaterialTheme.typography.bodySmall)
                            Text(MCP_PRIVACY, style = MaterialTheme.typography.bodySmall)
                            Text("Coding tools run as Mahout itself (no root, no extra permission).", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item(key = "host", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("Host status", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.m).semantics { heading() })
                        ListItem(headlineContent = { Text("Notification listener connected") }, leadingContent = { StatusIcon(host.listenerConnected) },   // F21: bound, not merely granted
                            supportingContent = { if (host.listenerGranted && !host.listenerConnected) Text("Granted but not bound - toggle notification access if this persists") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Background service running") }, leadingContent = { StatusIcon(host.serviceRunning, info = !host.runtimeTriggersInUse) },
                            supportingContent = { Text(if (host.runtimeTriggersInUse) "Needed: enabled workflows use live triggers" else "Not needed right now") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
        }
    }
}

private enum class StepState { DONE, PENDING, SKIPPED }

/** Header: progress + "Grant all" when idle; the stepper (D12: explicit Next, never auto-launched) while a plan is active. */
@Composable
private fun HeaderCard(
    granted: Int, total: Int, plan: List<String>, skipped: Set<String>, liveByKey: Map<String, Step>, st: GateStatuses, sdk: Int,
    onGrantAll: () -> Unit, onPerform: (Step) -> Unit, onSkip: (String) -> Unit, onDone: () -> Unit, onAppInfo: () -> Unit,
) {
    val ctx = LocalContext.current
    fun state(key: String) = when { key !in liveByKey -> StepState.DONE; key in skipped -> StepState.SKIPPED; else -> StepState.PENDING }
    val current = plan.firstOrNull { state(it) == StepState.PENDING }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(Space.s))
                Text(if (plan.isEmpty()) "$granted of $total granted" else "Grant all — ${plan.count { state(it) == StepState.DONE }} of ${plan.size} steps done", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            LinearProgressIndicator(progress = { if (total == 0) 1f else granted.toFloat() / total }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$granted of $total granted" })
            if (plan.isEmpty()) {
                val steps = liveByKey.size
                PillButton(if (steps == 0) "All set" else "Grant all ($steps steps)", onClick = onGrantAll, enabled = steps > 0, contentDescription = "Grant all missing permissions, $steps steps")
                return@Column
            }
            // step list: checkmarks survive the Settings round-trip (plan is rememberSaveable; statuses recompute ON_RESUME)
            plan.forEachIndexed { i, key ->
                val s = state(key)
                val title = liveByKey[key]?.title ?: stepTitle(key)
                val stateText = when (s) { StepState.DONE -> "done"; StepState.PENDING -> if (key == current) "current" else "pending"; StepState.SKIPPED -> "skipped" }
                Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${i + 1} of ${plan.size}, $title, $stateText" }, verticalAlignment = Alignment.CenterVertically) {
                    when (s) {
                        StepState.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Done", tint = MaterialTheme.mahout.success)
                        StepState.SKIPPED -> Icon(Icons.Rounded.Error, contentDescription = "Skipped", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        StepState.PENDING -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = "Pending", tint = if (key == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val step = current?.let { liveByKey[it] }
            if (step == null) {
                val skippedTitles = plan.filter { state(it) == StepState.SKIPPED }.map { liveByKey[it]?.title ?: stepTitle(it) }
                Text(if (skippedTitles.isEmpty()) "All set" else "Skipped: ${skippedTitles.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                PillButton("Done", onClick = onDone, contentDescription = "Done with Grant all")
                return@Column
            }
            Text("Step ${plan.indexOf(current) + 1} of ${plan.size} — ${step.title}", style = MaterialTheme.typography.titleSmall)
            var actionLabel = "Next"
            var restricted = false
            when (step) {
                is Step.Runtime -> Text("Android asks one dialog per group: " + step.permissions.map { it.substringAfterLast('.').lowercase().replace('_', ' ') }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                Step.BackgroundLocation -> {
                    Text(BACKGROUND_LOCATION_WHY, style = MaterialTheme.typography.bodySmall)
                    when {
                        !st.fineGranted -> { Text("Needs Location first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); actionLabel = "Allow location" }
                        st.of(p(P_BACKGROUND)) == GateStatus.PERMANENTLY_DENIED -> actionLabel = "Open App info"
                        else -> { Text(backgroundHint(ctx, sdk), style = MaterialTheme.typography.bodySmall); actionLabel = "Allow all the time" }
                    }
                }
                is Step.Settings -> {
                    Text(gateWhy(step.gate), style = MaterialTheme.typography.bodySmall)
                    settingsHint(step.gate)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    restricted = st.of(step.gate) == GateStatus.BLOCKED_RESTRICTED
                    if (restricted) Text(RESTRICTED_SETTINGS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    actionLabel = if (restricted) "Try again" else if (step.gate == Gate.LocationOn) "Turn on" else "Open Settings"
                }
                is Step.AppInfo -> { Text("Android will not show these dialogs again; enable them under App info > Permissions: " + step.gates.joinToString(", ") { it.title }, style = MaterialTheme.typography.bodySmall); actionLabel = "Open App info" }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PillButton(actionLabel, onClick = { onPerform(step) }, contentDescription = "$actionLabel: ${step.title}")
                if (restricted) PillButton("Open App info", onClick = onAppInfo, tone = Tone.Neutral, outlined = true, contentDescription = "Open App info for ${step.title}")
                TextButton(onClick = { onSkip(step.key) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Skip step ${step.title}" }) { Text("Skip step") }
            }
        }
    }
}

private fun stepTitle(key: String) = when { key == "runtime" -> "Allow permissions"; key == "bgloc" -> "Location all the time"; key == "appinfo" -> "Open App info"; else -> key.removePrefix("settings:").replaceFirstChar(Char::titlecase) }

private fun backgroundHint(ctx: Context, sdk: Int): String =
    if (sdk >= 30) "On the next page choose '${runCatching { ctx.packageManager.backgroundPermissionOptionLabel }.getOrNull() ?: "Allow all the time"}'" else "Choose 'Allow all the time'"

/** One permission row (DESIGN3P §6.4): icon, title, why, used by, status chip, hints and the right button for the status. */
@Composable
private fun GateRow(row: PermRow, st: GateStatuses, onGrant: () -> Unit, onAppInfo: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val gate = row.gate
    val status = st.of(gate)
    val sdk = Build.VERSION.SDK_INT
    SectionCard(modifier.semantics(mergeDescendants = true) {}) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(gate), contentDescription = gate.title, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(gate.title, style = MaterialTheme.typography.titleMedium)
                    StatusChip(status, gate)
                }
            }
            Text(gateWhy(gate), style = MaterialTheme.typography.bodySmall)
            Text("Used by: " + row.usedBy.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (gate.kind == GrantKind.SETTINGS && status != GateStatus.GRANTED) settingsHint(gate)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val perm = (gate.key as? Gate.Permission)?.permission
            if (perm == P_BACKGROUND && status == GateStatus.NOT_GRANTED && sdk >= 30) Text(backgroundHint(ctx, sdk), style = MaterialTheme.typography.bodySmall)
            if (perm == P_FINE && status == GateStatus.NOT_GRANTED && sdk >= 31 && st.coarseGranted) Text("Approximate only: tap Grant and choose Precise", style = MaterialTheme.typography.bodySmall)
            when (status) {
                GateStatus.PERMANENTLY_DENIED -> Text("Android will not show this dialog again; enable it under Permissions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                GateStatus.BLOCKED_RESTRICTED -> Text(RESTRICTED_SETTINGS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                GateStatus.PARTIAL -> Text("Only selected photos are visible; New Photo / Screenshot needs all", style = MaterialTheme.typography.bodySmall)
                GateStatus.NOT_GRANTED -> if (st.sideloadHint && gate.key == Gate.NotificationListener)   // proactive hint (D9); the a11y disclosure already carries it
                    Text(RESTRICTED_SETTINGS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {}
            }
            val label: String? = when (status) {
                GateStatus.NOT_GRANTED, GateStatus.PARTIAL -> when {
                    status == GateStatus.PARTIAL -> "Choose all photos"
                    gate.kind == GrantKind.RUNTIME -> "Grant"
                    gate.kind == GrantKind.BACKGROUND_LOCATION -> if (st.fineGranted) "Allow all the time" else "Allow location first"
                    gate.key == Gate.LocationOn -> "Turn on"
                    else -> "Open Settings"
                }
                GateStatus.PERMANENTLY_DENIED -> "Open App info"
                GateStatus.BLOCKED_RESTRICTED -> "Try again"
                else -> null
            }
            if (label != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val primary = if (status == GateStatus.PERMANENTLY_DENIED) onAppInfo else onGrant
                PillButton(label, onClick = primary, contentDescription = "$label ${gate.title}")
                if (status == GateStatus.BLOCKED_RESTRICTED) PillButton("Open App info", onClick = onAppInfo, tone = Tone.Neutral, outlined = true, contentDescription = "Open App info for ${gate.title}")
            }
        }
    }
}

@Composable
private fun StatusChip(status: GateStatus, gate: Gate) {
    val text = statusText(status, gate)
    val ok = status == GateStatus.GRANTED || (status == GateStatus.INFO && gate.key is Gate.Feature)
    val info = status in setOf(GateStatus.INFO, GateStatus.NOT_APPLICABLE, GateStatus.UNAVAILABLE)
    // v6: StatusPill (dot + words); the words carry the meaning, the tone only underlines it
    StatusPill(text, when { ok -> Tone.Positive; info -> Tone.Neutral; status == GateStatus.PARTIAL -> Tone.Caution; else -> Tone.Danger },
        Modifier.clearAndSetSemantics { contentDescription = "${gate.title}: $text" })
}

private fun iconFor(gate: Gate): ImageVector = when (val k = gate.key) {
    Gate.NotificationListener -> Icons.Rounded.Notifications
    Gate.PostNotifications -> Icons.Rounded.NotificationsActive
    Gate.IgnoreBatteryOpt -> Icons.Rounded.BatterySaver
    Gate.Accessibility -> Icons.Rounded.Accessibility
    Gate.Overlay -> Icons.Rounded.Layers
    Gate.DndPolicy -> Icons.Rounded.DoNotDisturbOn
    Gate.WriteSettings, Gate.ExactAlarm -> Icons.Rounded.SettingsIcon
    Gate.LocationOn -> Icons.Rounded.GpsFixed
    is Gate.Permission -> when (k.permission) {
        P_FINE -> Icons.Rounded.LocationOn
        P_BACKGROUND -> Icons.Rounded.MyLocation
        Manifest.permission.BLUETOOTH_CONNECT -> Icons.Rounded.Bluetooth
        Manifest.permission.ACTIVITY_RECOGNITION -> Icons.AutoMirrored.Rounded.DirectionsWalk
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO -> Icons.Rounded.Image
        Manifest.permission.READ_MEDIA_AUDIO -> Icons.Rounded.MusicNote
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE -> Icons.Rounded.Folder
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> Icons.Rounded.Event
        Manifest.permission.READ_CONTACTS -> Icons.Rounded.Contacts
        Manifest.permission.READ_PHONE_STATE -> Icons.Rounded.Phone
        Manifest.permission.RECEIVE_SMS -> Icons.Rounded.Sms
        else -> Icons.Rounded.Security
    }
    is Gate.Feature -> when {
        "flash" in k.feature -> Icons.Rounded.FlashlightOn
        "accelerometer" in k.feature -> Icons.Rounded.Vibration
        "nfc" in k.feature -> Icons.Rounded.Nfc
        else -> Icons.Rounded.Info
    }
    else -> Icons.Rounded.Security
}

@Composable
fun StatusIcon(ok: Boolean, info: Boolean = false) {
    when {
        ok -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Granted", tint = MaterialTheme.mahout.success)
        info -> Icon(Icons.Rounded.Info, contentDescription = "Informational", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Icon(Icons.Rounded.Error, contentDescription = "Missing", tint = MaterialTheme.colorScheme.error)
    }
}
