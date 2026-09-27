package com.mob8n.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mob8n.core.ExecMode
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.appPicker
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.item
import com.mob8n.core.l
import com.mob8n.core.label
import com.mob8n.core.s
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.str
import com.mob8n.core.text
import com.mob8n.triggers.NotifListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---- shared helpers (lane-internal) ----
internal fun iso(ms: Long?, zone: ZoneId): String? = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toOffsetDateTime().toString() }
internal fun granted(ctx: Context, permission: String) = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

object DeviceStateNode : Node() {
    override val spec = NodeSpec(
        id = "data.device_state", name = "Device State", kind = NodeKind.DATA,
        description = "Reads battery, network, screen, audio output, volumes, ringer/DND, brightness and orientation.",
        gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION)), Gate.Advisory(Gate.Permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)), Gate.LocationOn),
        agentTool = true,
    )

    private val BT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        26 /* TYPE_BLE_HEADSET (31+) */, 27 /* TYPE_BLE_SPEAKER (31+) */, 30 /* TYPE_BLE_BROADCAST (33+) */,
    )
    private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
    private val STREAMS = mapOf(
        "music" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val bm = a.getSystemService(BatteryManager::class.java)
        val sticky = runCatching { a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val plugged = when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            0 -> "none"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "other"
        }
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val networkType = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val wifiSsid = if (networkType == "wifi" && granted(a, Manifest.permission.ACCESS_FINE_LOCATION)) ssid(a) else null
        val pm = a.getSystemService(PowerManager::class.java)
        val am = a.getSystemService(AudioManager::class.java)
        val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
        val bt = devices.filter { it.type in BT_TYPES }
        val wired = devices.filter { it.type in WIRED_TYPES }
        val output = bt.firstOrNull() ?: wired.firstOrNull()
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: devices.firstOrNull()
        val nm = a.getSystemService(NotificationManager::class.java)
        val dnd = when (nm?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
            else -> "unknown"
        }
        val brightness = runCatching { Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        val autoBrightness = runCatching {
            Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrNull()
        val orientation = if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        return out(ctx.item.add(
            "battery" to bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "charging" to (bm?.isCharging ?: false), "plugged" to plugged,
            "wifiSsid" to wifiSsid, "networkType" to networkType, "metered" to (cm?.isActiveNetworkMetered ?: false),
            "screenOn" to (pm?.isInteractive ?: true), "powerSave" to (pm?.isPowerSaveMode ?: false),
            "volumes" to STREAMS.mapValues { am?.getStreamVolume(it.value) },
            "volumesMax" to STREAMS.mapValues { am?.getStreamMaxVolume(it.value) },
            "ringerMode" to when (am?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal"
            },
            "dnd" to dnd,
            "btAudioConnected" to bt.isNotEmpty(), "wiredHeadset" to wired.isNotEmpty(), "outputDevice" to output?.let(::deviceLabel),
            "brightness" to brightness, "autoBrightness" to autoBrightness, "orientation" to orientation,
        ))
    }

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
            26, 27, 30 -> "bluetooth_le"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_HDMI -> "hdmi"
            else -> "other"
        }
        val name = d.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty() || type == "speaker" || type == "earpiece") type else "$type:$name"
    }

    // ponytail: WifiManager.connectionInfo is deprecated on 31+ and yields "<unknown ssid>" unless foreground + location;
    // upgrade = ConnectivityManager.NetworkCallback with FLAG_INCLUDE_LOCATION_INFO (31+) and WifiInfo from transportInfo.
    @Suppress("DEPRECATION")
    private fun ssid(a: Context): String? = runCatching {
        a.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
            ?.takeUnless { it.isBlank() || it == "<unknown ssid>" || it == "0x" }
    }.getOrNull()
}

object ActiveNotificationsNode : Node() {
    override val spec = NodeSpec(
        id = "data.active_notifications", name = "Active Notifications", kind = NodeKind.DATA,
        description = "Lists the notifications currently shown in the status bar, optionally for one app.",
        params = listOf(
            appPicker("packageName", "App", help = "Leave empty for all apps"),
            number("limit", "Limit", 50.0, 1.0, 500.0),
        ),
        mode = ExecMode.LIST, gates = listOf(Gate.NotificationListener), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val listener = NotifListener.instance
            ?: throw NodeException("Notification listener is not connected (grant notification access, then re-toggle it)")
        val pkg = ctx.strOrNull("packageName")
        val limit = (ctx.int("limit") ?: 50).coerceIn(1, 500)
        val sbns = try { listener.activeNotifications ?: emptyArray() } catch (e: Exception) {
            throw NodeException("Could not read notifications: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val pm = a.packageManager
        val items = sbns.asSequence()
            .filter { pkg == null || it.packageName == pkg }
            .sortedByDescending { it.postTime }
            .take(limit)
            .map { sbn ->
                val ex = sbn.notification?.extras
                item(
                    "packageName" to sbn.packageName, "appName" to pm.label(sbn.packageName),
                    "title" to ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    "text" to (ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()),
                    "key" to sbn.key, "postTime" to sbn.postTime, "ongoing" to sbn.isOngoing,
                )
            }.toList()
        return out(items)
    }
}

object LocationNode : Node() {
    override val spec = NodeSpec(
        id = "data.location", name = "Location", kind = NodeKind.DATA,
        description = "Gets the current device location, falling back to the last known fix.",
        params = listOf(
            choice("provider", "Provider", listOf("fused", "gps", "network")),
            durationMs("timeoutMs", "Timeout", 20_000, 1_000, 120_000),
        ),
        gates = listOf(Gate.Permission(Manifest.permission.ACCESS_FINE_LOCATION), Gate.LocationOn),
        timeoutMs = 125_000, agentTool = true,
    )

    @SuppressLint("MissingPermission")   // the ACCESS_FINE_LOCATION gate runs before execute()
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val lm = a.getSystemService(LocationManager::class.java) ?: throw NodeException("No location service on this device")
        val provider = when (ctx.str("provider")) {
            "gps" -> LocationManager.GPS_PROVIDER
            "network" -> LocationManager.NETWORK_PROVIDER
            else -> when {
                Build.VERSION.SDK_INT >= 31 -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
        }
        val timeout = (ctx.long("timeoutMs") ?: 20_000L).coerceIn(1_000L, 120_000L)
        val fresh: Location? = withTimeoutOrNull(timeout) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Location?> { cont ->
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val signal = CancellationSignal()
                            cont.invokeOnCancellation { signal.cancel() }
                            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(a)) { loc -> if (cont.isActive) cont.resume(loc) }
                        } else {
                            val listener = LocationListener { loc -> if (cont.isActive) cont.resume(loc) }
                            cont.invokeOnCancellation { lm.removeUpdates(listener) }
                            @Suppress("DEPRECATION")
                            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        }
                    } catch (e: Exception) {
                        ctx.log("location request failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        val loc = fresh ?: lastKnown(lm) ?: throw NodeException("No location available within $timeout ms (is location enabled?)")
        return out(ctx.item.add(
            "lat" to loc.latitude, "lng" to loc.longitude,
            "accuracyM" to (if (loc.hasAccuracy()) loc.accuracy else null),
            "altitude" to (if (loc.hasAltitude()) loc.altitude else null),
            "speed" to (if (loc.hasSpeed()) loc.speed else null),
            "provider" to loc.provider, "time" to loc.time, "stale" to (fresh == null),
        ))
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
}

object CalendarEventsNode : Node() {
    override val spec = NodeSpec(
        id = "data.calendar_events", name = "Calendar Events", kind = NodeKind.DATA,
        description = "Lists calendar event instances starting within the next N hours.",
        params = listOf(
            number("hours", "Hours ahead", 12.0, 1.0, 24.0 * 31),
            number("limit", "Limit", 50.0, 1.0, 500.0),
            text("calendarNameRegex", "Calendar name regex", help = "Only calendars whose display name matches"),
        ),
        mode = ExecMode.LIST, gates = listOf(Gate.Permission(Manifest.permission.READ_CALENDAR)), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val hours = (ctx.double("hours") ?: 12.0).coerceIn(1.0, 24.0 * 31)
        val limit = (ctx.int("limit") ?: 50).coerceIn(1, 500)
        val regex = ctx.strOrNull("calendarNameRegex")?.let { r ->
            runCatching { Regex(r, RegexOption.IGNORE_CASE) }.getOrElse { throw NodeException("Invalid calendar regex '$r': ${it.message}") }
        }
        val now = ctx.nowMs()
        val end = now + (hours * 3_600_000).toLong()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, end) }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.EVENT_ID,
        )
        val items = ArrayList<JsonObject>()
        withContext(Dispatchers.IO) {
            try {
                a.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                    while (c.moveToNext() && items.size < limit) {
                        val cal = c.s(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                        if (regex != null && !regex.containsMatchIn(cal.orEmpty())) continue
                        val b = c.l(CalendarContract.Instances.BEGIN)
                        val e = c.l(CalendarContract.Instances.END)
                        items += item(
                            "title" to c.s(CalendarContract.Instances.TITLE), "begin" to b, "end" to e,
                            "beginIso" to iso(b, ctx.zone), "endIso" to iso(e, ctx.zone),
                            "location" to c.s(CalendarContract.Instances.EVENT_LOCATION), "description" to c.s(CalendarContract.Instances.DESCRIPTION),
                            "calendar" to cal, "allDay" to (c.l(CalendarContract.Instances.ALL_DAY) == 1L), "eventId" to c.l(CalendarContract.Instances.EVENT_ID),
                        )
                    }
                }
            } catch (e: SecurityException) { throw NodeException("Needs calendar permission", e) }
        }
        ctx.log("${items.size} events in the next ${hours}h")
        return out(items)
    }
}

object ContactLookupNode : Node() {
    override val spec = NodeSpec(
        id = "data.contact_lookup", name = "Contact Lookup", kind = NodeKind.DATA,
        description = "Finds a contact by name or phone number and returns its phones and emails.",
        params = listOf(
            text("query", "Query", required = true, help = "Name fragment or phone number"),
            choice("by", "Match by", listOf("name", "number")),
        ),
        gates = listOf(Gate.Permission(Manifest.permission.READ_CONTACTS)), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val q = ctx.req("query").trim()
        if (q.length > 200) throw NodeException("Query too long")
        val byNumber = ctx.str("by") == "number"
        return withContext(Dispatchers.IO) {
            try {
                val cr = a.contentResolver
                val (uri, idCol) = if (byNumber)
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(q).build() to ContactsContract.PhoneLookup.CONTACT_ID
                else
                    ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(q).build() to ContactsContract.Contacts._ID
                var contactId: Long? = null; var name: String? = null; var lookupKey: String? = null
                cr.query(uri, arrayOf(idCol, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { contactId = c.l(idCol); name = c.s(ContactsContract.Contacts.DISPLAY_NAME); lookupKey = c.s(ContactsContract.Contacts.LOOKUP_KEY) }
                }
                val id = contactId ?: return@withContext out(ctx.item.add("found" to false, "contactName" to null, "phones" to emptyList<String>(), "emails" to emptyList<String>(), "lookupKey" to null))
                val phones = LinkedHashSet<String>(); val emails = LinkedHashSet<String>()
                cr.query(
                    ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
                    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (?,?)",
                    arrayOf(id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val v = c.s(ContactsContract.Data.DATA1)?.trim().orEmpty()
                        if (v.isEmpty()) continue
                        if (c.s(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) phones += v else emails += v
                    }
                }
                out(ctx.item.add("found" to true, "contactName" to name, "phones" to phones.toList(), "emails" to emails.toList(), "lookupKey" to lookupKey))
            } catch (e: SecurityException) { throw NodeException("Needs contacts permission", e) }
        }
    }
}

object ClipboardNode : Node() {
    override val spec = NodeSpec(
        id = "data.clipboard", name = "Clipboard", kind = NodeKind.DATA,
        description = "Reads the clipboard text (Android 10+ only while Mahout is in the foreground).",
        gates = listOf(Gate.ForegroundOnly), optional = true, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val text = withContext(Dispatchers.Main.immediate) {
            runCatching {
                a.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString()
            }.getOrNull()
        }
        if (text == null) ctx.log("clipboard empty or not readable in background")
        return out(ctx.item.add("clipboard" to text))
    }
}

object InstalledAppsNode : Node() {
    override val spec = NodeSpec(
        id = "data.installed_apps", name = "Installed Apps", kind = NodeKind.DATA,
        description = "Lists launchable apps with package name, label and version.",
        params = listOf(bool("includeSystem", "Include system apps", false)),
        mode = ExecMode.LIST, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val includeSystem = ctx.bool("includeSystem")
        val pm = a.packageManager
        return withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val infos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val items = infos.mapNotNull { it.activityInfo?.applicationInfo }.distinctBy { it.packageName }
                .map { ai -> ai to isSystem(ai) }
                .filter { (_, sys) -> includeSystem || !sys }
                .map { (ai, sys) ->
                    item(
                        "packageName" to ai.packageName, "label" to pm.getApplicationLabel(ai).toString(),
                        "versionName" to runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull(),
                        "isSystem" to sys,
                    )
                }.sortedBy { it.str("label")?.lowercase() }
            out(items)
        }
    }

    /** Preloaded and never updated from the store (Chrome/Gmail updated via Play do not count as "system"). */
    private fun isSystem(ai: ApplicationInfo) =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}

object AppInfoNode : Node() {
    override val spec = NodeSpec(
        id = "data.app_info", name = "App Info", kind = NodeKind.DATA,
        description = "Returns label, version and install time of an installed app.",
        params = listOf(appPicker("packageName", "App", required = true)),
        agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val pkg = ctx.req("packageName").trim()
        val pm = a.packageManager
        val pi = try { pm.getPackageInfo(pkg, 0) } catch (e: PackageManager.NameNotFoundException) {
            throw NodeException("App '$pkg' is not installed or not visible to Mahout")
        }
        val ai = pi.applicationInfo
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return out(ctx.item.add(
            "label" to (ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg),
            "versionName" to pi.versionName, "versionCode" to versionCode,
            "installedAt" to pi.firstInstallTime, "updatedAt" to pi.lastUpdateTime,
            "enabled" to (ai?.enabled ?: true),
        ))
    }
}

object StorageNode : Node() {
    override val spec = NodeSpec(
        id = "data.storage", name = "Storage", kind = NodeKind.DATA,
        description = "Reports free and total internal storage plus the external storage state.",
        agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        ctx.requireAndroid()
        val st = withContext(Dispatchers.IO) { StatFs(Environment.getDataDirectory().path) }
        val free = st.availableBytes; val total = st.totalBytes
        return out(ctx.item.add(
            "freeBytes" to free, "totalBytes" to total,
            "freePercent" to if (total > 0) Math.round(free * 1000.0 / total) / 10.0 else null,
            "externalState" to Environment.getExternalStorageState(),
        ))
    }
}

object SensorNode : Node() {
    override val spec = NodeSpec(
        id = "data.sensor", name = "Sensor", kind = NodeKind.DATA,
        description = "Takes one reading from the light, pressure, step counter, temperature or proximity sensor.",
        params = listOf(choice("sensor", "Sensor", listOf("light", "pressure", "steps", "temperature", "proximity"))),
        gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.ACTIVITY_RECOGNITION))), optional = true, agentTool = true,
    )

    private val TYPES = mapOf(
        "light" to (Sensor.TYPE_LIGHT to "lx"), "pressure" to (Sensor.TYPE_PRESSURE to "hPa"),
        "steps" to (Sensor.TYPE_STEP_COUNTER to "steps"), "temperature" to (Sensor.TYPE_AMBIENT_TEMPERATURE to "°C"),
        "proximity" to (Sensor.TYPE_PROXIMITY to "cm"),
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val name = ctx.str("sensor")
        val (type, unit) = TYPES[name] ?: throw NodeException("Unknown sensor '$name'")
        if (name == "steps" && Build.VERSION.SDK_INT >= 29 && !granted(a, Manifest.permission.ACTIVITY_RECOGNITION))
            throw NodeException("Needs activity recognition permission for the step counter")
        val sm = a.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(type) ?: throw NodeException("This device has no $name sensor")
        val value = withContext(Dispatchers.Main.immediate) {
            var listener: SensorEventListener? = null
            try {
                withTimeoutOrNull(3_000) {
                    suspendCancellableCoroutine<Float> { cont ->
                        val l = object : SensorEventListener {
                            override fun onSensorChanged(e: SensorEvent) { if (cont.isActive) cont.resume(e.values[0]) }
                            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
                        }
                        listener = l
                        if (!sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL))
                            cont.resumeWithException(NodeException("Could not start the $name sensor"))
                    }
                }
            } finally { listener?.let { sm.unregisterListener(it) } }
        } ?: throw NodeException("No $name reading within 3 s")
        return out(ctx.item.add(name to value, "unit" to unit))
    }
}
