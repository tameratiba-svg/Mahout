package com.mob8n.actions

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import com.mob8n.core.label
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
import com.mob8n.core.asText
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.clockTime
import com.mob8n.core.multiline
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.rows
import com.mob8n.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Start an activity from wherever the engine runs; Android 10+ blocks background starts, so fall back to a "Tap to open" notification. */
object Launch {
    data class Result(val started: Boolean, val viaNotification: Boolean)
    private val QUERIED = setOf("https", "geo", "mailto", "smsto")

    /** True when a plain startActivity will actually show something (pre-10, "Display over other apps" granted, or our own activity is visible). */
    fun canStartDirectly(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        if (Settings.canDrawOverlays(ctx)) return true
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    fun start(ctx: Context, intent: Intent, fallbackNotification: Boolean, title: String): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // resolveActivity only sees packages visible via <queries>; pre-check just the schemes the manifest declares so the trampoline path also fails fast.
        val s = intent.data?.scheme
        if (s in QUERIED && intent.`package` == null && intent.component == null && intent.resolveActivity(ctx.packageManager) == null) throw NodeException("No app can handle ${intent.data}")
        if (canStartDirectly(ctx)) {
            try { ctx.startActivity(intent); return Result(true, false) } catch (e: ActivityNotFoundException) { throw NodeException("No app can handle this: ${intent.action} ${intent.data ?: ""}".trim(), e) }
        }
        if (!fallbackNotification) throw NodeException("Cannot open from the background (Android 10+); enable the notification fallback")
        Notifs.trampoline(ctx, title, intent)
        return Result(false, true)
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Gates of every node that may start an activity from a background host: overlay = direct start, notifications = the "Tap to open" trampoline. Both optional. */
internal val BG_LAUNCH: List<Gate> = listOf(Gate.Overlay, Gate.Advisory(Gate.PostNotifications))

object LaunchAppNode : Node() {
    override val spec = NodeSpec(
        id = "action.launch_app", name = "Launch app", kind = NodeKind.ACTION,
        description = "Open an installed app (falls back to a tap-to-open notification when started from the background).",
        params = listOf(appPicker("packageName", "App", required = true), bool("fallbackNotification", "Notify when blocked", true)),
        gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val pkg = ctx.req("packageName")
        val intent = a.packageManager.getLaunchIntentForPackage(pkg) ?: throw NodeException("App $pkg is not installed or has no launcher")
        val label = a.packageManager.label(pkg)
        val r = Launch.start(a, intent, ctx.bool("fallbackNotification"), "Open $label")
        return out(ctx.item.add("launched" to r.started, "viaNotification" to r.viaNotification))
    }
}

object OpenUrlNode : Node() {
    private val SCHEMES = setOf("http", "https", "geo", "tel", "mailto", "market", "content", "sms", "smsto")
    override val spec = NodeSpec(
        id = "action.open_url", name = "Open URL", kind = NodeKind.ACTION,
        description = "View a URL (http/https/geo/tel/mailto/market/content) in the matching app.",
        params = listOf(text("url", "URL", required = true), bool("fallbackNotification", "Notify when blocked", true)),
        gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val url = ctx.req("url").trim()
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in SCHEMES) throw NodeException("Unsupported URL scheme in $url (allowed: ${SCHEMES.joinToString()})")
        val r = Launch.start(a, Intent(Intent.ACTION_VIEW, uri), ctx.bool("fallbackNotification"), "Open ${uri.host ?: url.take(40)}")
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object SendIntentNode : Node() {
    override val spec = NodeSpec(
        id = "action.send_intent", name = "Send intent", kind = NodeKind.ACTION,
        description = "Build and send an arbitrary Android intent as an activity or broadcast (never a service).",
        params = listOf(
            text("action", "Action", required = true, help = "e.g. android.intent.action.VIEW"),
            text("data", "Data URI"),
            appPicker("packageName", "Package"),
            text("className", "Class name", help = "Fully-qualified component class (with Package)"),
            rows("extras", "Extras", listOf(
                text("key", "Key", required = true), text("value", "Value"),
                choice("type", "Type", listOf("string", "int", "long", "bool", "float")),
            )),
            choice("mode", "Mode", listOf("activity", "broadcast")),
        ),
        gates = BG_LAUNCH, agentTool = false,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val i = Intent(ctx.req("action"))
        ctx.strOrNull("data")?.let { i.data = Uri.parse(it) }
        val pkg = ctx.strOrNull("packageName")
        val cls = ctx.strOrNull("className")
        if (pkg != null) i.setPackage(pkg)
        if (cls != null) i.setClassName(pkg ?: throw NodeException("Class name needs a package"), cls)
        for (row in ctx.rows("extras")) {
            val k = row["key"].asText().ifBlank { throw NodeException("Extra key is required") }
            val v = row["value"].asText()
            when (row["type"].asText().ifBlank { "string" }) {
                "int" -> i.putExtra(k, v.trim().toIntOrNull() ?: throw NodeException("Extra $k: '$v' is not an int"))
                "long" -> i.putExtra(k, v.trim().toLongOrNull() ?: throw NodeException("Extra $k: '$v' is not a long"))
                "bool" -> i.putExtra(k, v.trim().toBooleanStrictOrNull() ?: throw NodeException("Extra $k: '$v' is not true/false"))
                "float" -> i.putExtra(k, v.trim().toFloatOrNull() ?: throw NodeException("Extra $k: '$v' is not a float"))
                else -> i.putExtra(k, v)
            }
        }
        if (ctx.str("mode") == "broadcast") {
            try { a.sendBroadcast(i) } catch (e: SecurityException) { throw NodeException("Broadcast not allowed: ${e.message}", e) }
            return out(ctx.item.add("sent" to true))
        }
        val r = Launch.start(a, i, true, "Open ${i.action}")
        return out(ctx.item.add("sent" to r.started, "viaNotification" to r.viaNotification))
    }
}

object ShareNode : Node() {
    override val spec = NodeSpec(
        id = "action.share", name = "Share", kind = NodeKind.ACTION,
        description = "Open the system share sheet with text and/or a file (content:// URI or a path inside Mahout's files/cache dir).",
        params = listOf(multiline("text", "Text"), text("fileUri", "File URI or path"), text("mimeType", "MIME type", "text/plain"), text("subject", "Subject")),
        gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val textV = ctx.strOrNull("text")
        val file = ctx.strOrNull("fileUri")
        if (textV == null && file == null) throw NodeException("Share needs text or a file")
        val send = Intent(Intent.ACTION_SEND).setType(ctx.strOrNull("mimeType") ?: "text/plain")
        textV?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        ctx.strOrNull("subject")?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        if (file != null) {
            val u = Uri.parse(file)
            val shared: Uri = when {
                u.scheme == "content" -> u
                u.scheme == null || u.scheme == "file" -> {
                    val f = File(u.path ?: file).canonicalFile
                    val ok = listOf(a.filesDir, a.cacheDir).any { f.path.startsWith(it.canonicalPath + File.separator) }
                    if (!ok || !f.exists()) throw NodeException("Only files inside Mahout's files/cache dir can be shared: $file")
                    FileProvider.getUriForFile(a, "com.mob8n.files", f)
                }
                else -> throw NodeException("Unsupported file URI: $file")
            }
            send.putExtra(Intent.EXTRA_STREAM, shared).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri("share", shared)
        }
        val chooser = Intent.createChooser(send, ctx.strOrNull("subject") ?: "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val r = Launch.start(a, chooser, true, "Share")
        return out(ctx.item.add("shared" to r.started, "viaNotification" to r.viaNotification))
    }
}

object DialNode : Node() {
    override val spec = NodeSpec(
        id = "action.dial", name = "Dial", kind = NodeKind.ACTION,
        description = "Open the dialer with a number filled in (does not place the call).",
        params = listOf(text("number", "Phone number", required = true)), gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val n = ctx.req("number").filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        if (n.isBlank()) throw NodeException("Invalid phone number")
        val r = Launch.start(a, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(n)}")), true, "Call $n")
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object ComposeSmsNode : Node() {
    override val spec = NodeSpec(
        id = "action.compose_sms", name = "Compose SMS", kind = NodeKind.ACTION,
        description = "Open the messaging app with a prefilled text (does not send).",
        params = listOf(text("number", "Phone number"), multiline("text", "Message", required = true)), gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val n = ctx.strOrNull("number")?.filter { it.isDigit() || it == '+' } ?: ""
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(n)}")).putExtra("sms_body", ctx.req("text"))
        val r = Launch.start(a, i, true, "Send SMS")
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object ComposeEmailNode : Node() {
    override val spec = NodeSpec(
        id = "action.compose_email", name = "Compose email", kind = NodeKind.ACTION,
        description = "Open the email app with recipient, subject and body prefilled (does not send).",
        params = listOf(text("to", "To"), text("subject", "Subject"), multiline("body", "Body")), gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val to = ctx.strOrNull("to")
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to ?: "")}"))
        to?.let { i.putExtra(Intent.EXTRA_EMAIL, it.split(',', ';').map(String::trim).filter { s -> s.isNotEmpty() }.toTypedArray()) }
        ctx.strOrNull("subject")?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
        ctx.strOrNull("body")?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        val r = Launch.start(a, i, true, "Send email")
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object NavigateNode : Node() {
    override val spec = NodeSpec(
        id = "action.navigate", name = "Navigate", kind = NodeKind.ACTION,
        description = "Start turn-by-turn navigation (Google Maps) or show a place on a map.",
        params = listOf(text("destination", "Destination", required = true), choice("mode", "Mode", listOf("driving", "walking", "bicycling", "map"))),
        gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val dest = ctx.req("destination")
        val mode = ctx.str("mode")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Launch.enc(dest)}"))
        val nav = if (mode == "map") geo else {
            val m = when (mode) { "walking" -> "w"; "bicycling" -> "b"; else -> "d" }
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Launch.enc(dest)}&mode=$m"))
        }
        val r = try { Launch.start(a, nav, true, "Navigate to $dest") } catch (e: NodeException) { if (nav === geo) throw e; Launch.start(a, geo, true, "Navigate to $dest") }
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object AddCalendarEventNode : Node() {
    override val spec = NodeSpec(
        id = "action.add_calendar_event", name = "Add calendar event", kind = NodeKind.ACTION,
        description = "Create a calendar event via the calendar app, or silently in the primary calendar when allowed.",
        params = listOf(
            text("title", "Title", required = true),
            text("start", "Start", "{{\$now}}", help = "ISO date-time or epoch millis"),
            number("durationMinutes", "Duration (min)", 60.0, min = 1.0),
            text("location", "Location"), multiline("description", "Description"),
            bool("silent", "Insert silently", false, help = "Needs calendar write permission"),
        ),
        gates = BG_LAUNCH + Gate.Advisory(Gate.Permission(Manifest.permission.WRITE_CALENDAR)), agentTool = true,
    )

    /** ISO-8601 (offset/local date-time/date) or epoch millis -> epoch millis. */
    fun parseStart(s: String, zone: ZoneId, nowMs: Long): Long {
        val t = s.trim()
        if (t.isEmpty()) return nowMs
        t.toLongOrNull()?.let {   // F48: same epoch rule as logic.date / data.datetime (seconds if < 1e10, else ms; fewer than 9 digits is not an epoch)
            if (t.trimStart('-').length < 9) throw NodeException("Start '$s' is not an epoch (seconds or ms) or ISO date")
            return if (kotlin.math.abs(it) < 10_000_000_000L) it * 1000 else it
        }
        return try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
            try { ZonedDateTime.parse(t).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
                try { LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
                    try { LocalDate.parse(t).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
                        throw NodeException("Start '$s' is not an ISO date-time or epoch millis")
                    }
                }
            }
        }
    }

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val title = ctx.req("title")
        val start = parseStart(ctx.str("start"), ctx.zone, ctx.nowMs())
        val end = start + ((ctx.double("durationMinutes") ?: 60.0).coerceAtLeast(1.0) * 60_000).toLong()
        val location = ctx.strOrNull("location"); val desc = ctx.strOrNull("description")
        if (ctx.bool("silent")) {
            if (!Gate.Permission(Manifest.permission.WRITE_CALENDAR).granted(a)) throw NodeException("Needs write calendar permission")
            val id = withContext(Dispatchers.IO) {
                val calId = primaryCalendarId(a) ?: throw NodeException("No writable calendar found")
                val cv = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
                    put(CalendarContract.Events.EVENT_TIMEZONE, ctx.zone.id)
                    location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }; desc?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                }
                try { a.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)?.lastPathSegment?.toLongOrNull() } catch (e: SecurityException) { throw NodeException("Calendar insert denied: ${e.message}", e) }
            } ?: throw NodeException("Calendar insert failed")
            return out(ctx.item.add("eventId" to id))
        }
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }; desc?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        val r = Launch.start(a, i, true, "Add event: $title")
        return out(ctx.item.add("eventId" to null, "opened" to r.started, "viaNotification" to r.viaNotification))
    }

    private fun primaryCalendarId(a: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        a.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return id
                if (first == null && c.getInt(2) == 1) first = id
            }
            return first
        }
        return null
    }
}

object AddContactNode : Node() {
    override val spec = NodeSpec(
        id = "action.add_contact", name = "Add contact", kind = NodeKind.ACTION,
        description = "Open the contacts app with a new contact prefilled.",
        params = listOf(text("name", "Name", required = true), text("phone", "Phone"), text("email", "Email")), gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, ctx.req("name"))
        ctx.strOrNull("phone")?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        ctx.strOrNull("email")?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        val r = Launch.start(a, i, true, "Add contact")
        return out(ctx.item.add("opened" to r.started, "viaNotification" to r.viaNotification))
    }
}

object SetAlarmNode : Node() {
    override val spec = NodeSpec(
        id = "action.set_alarm", name = "Set alarm / timer", kind = NodeKind.ACTION,
        description = "Set an alarm at a time or start a countdown timer in the clock app.",
        params = listOf(
            choice("mode", "Mode", listOf("alarm", "timer")),
            clockTime("time", "Alarm time", "07:00"),
            number("seconds", "Timer seconds", 300.0, min = 1.0, max = 86_400.0),
            text("message", "Label"), bool("skipUi", "Skip clock UI", true),
        ),
        gates = BG_LAUNCH, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val skip = ctx.bool("skipUi")
        val i = if (ctx.str("mode") == "timer") {
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, (ctx.double("seconds") ?: 300.0).toInt().coerceIn(1, 86_400))
        } else {
            val t = ctx.str("time").trim()
            val m = Regex("([01]?\\d|2[0-3]):([0-5]\\d)").matchEntire(t) ?: throw NodeException("Alarm time must be HH:mm, got '$t'")
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, m.groupValues[1].toInt()).putExtra(AlarmClock.EXTRA_MINUTES, m.groupValues[2].toInt())
        }
        ctx.strOrNull("message")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, skip)
        // The clock app handles SET_ALARM in an activity even with SKIP_UI, so background-start rules apply: same trampoline fallback.
        val r = Launch.start(a, i, true, if (skip) "Confirm alarm" else "Set alarm")
        return out(ctx.item.add("set" to r.started, "viaNotification" to r.viaNotification))
    }
}
