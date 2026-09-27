package com.mob8n.apps

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.mob8n.actions.Launch
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
import com.mob8n.core.labelOrNull
import com.mob8n.core.out
import com.mob8n.core.rows
import com.mob8n.core.text
import com.mob8n.core.whenIs
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * One deep-link / intent template (DESIGN2 §7.2). `params` entries ending in `*` are required. `{param}` placeholders in
 * `data`/`web` are percent-encoded on fill; in `extras` they are substituted raw. `pkg == null` = the `packageName` param
 * (generic rows) or no package at all (camera/clock/calendar). `web` = ACTION_VIEW fallback when `pkg` is not installed.
 */
data class Recipe(
    val id: String, val label: String, val pkg: String?, val action: String,
    val data: String? = null, val mime: String? = null, val extras: Map<String, String> = emptyMap(),
    val params: List<String>, val web: String? = null, val verified: Boolean,
) {
    val keys: List<String> get() = params.map { it.removeSuffix("*") }
    val required: List<String> get() = params.filter { it.endsWith("*") }.map { it.removeSuffix("*") }
    /** Generic rows: the target package comes from the `packageName` param. */
    val usesPackageParam: Boolean get() = pkg == null && "packageName" in keys
    val template: String get() = listOfNotNull(action.substringAfterLast('.'), data, mime, extras.entries.joinToString(" ") { "${it.key.substringAfterLast('.')}=${it.value}" }.ifBlank { null }).joinToString(" ")
}

/** A recipe with its arguments substituted: pure, JVM-tested; `Recipes.intent()` turns it into an Intent on Android. */
data class Filled(
    val recipe: Recipe, val pkg: String?, val action: String, val data: String?, val mime: String?,
    val extras: Map<String, String>, val web: String?,
    /** `data` is the fileUri argument (Instagram story): resolved through FileProvider + read grant on Android. */
    val fileData: Boolean = false,
)

object Recipes {
    const val VIEW = "android.intent.action.VIEW"
    const val SEND = "android.intent.action.SEND"
    const val SENDTO = "android.intent.action.SENDTO"
    const val INSERT = "android.intent.action.INSERT"
    const val PLAY_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    const val STILL_CAMERA = "android.media.action.STILL_IMAGE_CAMERA"
    const val SHOW_ALARMS = "android.intent.action.SHOW_ALARMS"
    const val ADD_TO_STORY = "com.instagram.share.ADD_TO_STORY"
    /** Pseudo action: PackageManager.getLaunchIntentForPackage. */
    const val LAUNCH = "mob8n.launch"
    const val EXTRA_STREAM = "android.intent.extra.STREAM"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
    const val EXTRA_QUERY = "query"                          // SearchManager.QUERY
    const val EXTRA_MEDIA_FOCUS = "android.intent.extra.focus"
    const val FILE_AUTHORITY = "com.mob8n.files"

    private const val IG = "com.instagram.android"
    private const val YT = "com.google.android.youtube"
    private const val YTM = "com.google.android.apps.youtube.music"
    private const val SPOTIFY = "com.spotify.music"
    private const val WA = "com.whatsapp"
    private const val TG = "org.telegram.messenger"
    private const val X = "com.twitter.android"
    private const val MAPS = "com.google.android.apps.maps"
    private const val PLAY = "com.android.vending"
    private const val CHROME = "com.android.chrome"
    private const val GMAIL = "com.google.android.gm"

    /** Alternate package tried when the primary is missing (WhatsApp Business). */
    val ALT_PKG: Map<String, List<String>> = mapOf(WA to listOf("com.whatsapp.w4b"))

    // ponytail: ~37-option recipe ENUM instead of a per-app dynamic picker (ParamKind frozen)
    val ALL: List<Recipe> = listOf(
        Recipe("instagram_share_photo", "Instagram: share photo", IG, SEND, mime = "image/*", extras = mapOf(EXTRA_STREAM to "{fileUri}"), params = listOf("fileUri*"), verified = false),
        Recipe("instagram_share_video", "Instagram: share video", IG, SEND, mime = "video/*", extras = mapOf(EXTRA_STREAM to "{fileUri}"), params = listOf("fileUri*"), verified = false),
        Recipe("instagram_add_to_story", "Instagram: add to story", IG, ADD_TO_STORY, data = "{fileUri}", mime = "image/*",
            extras = mapOf("source_application" to "{facebookAppId}", "top_background_color" to "{topColor}", "bottom_background_color" to "{bottomColor}"),
            params = listOf("fileUri*", "facebookAppId*", "topColor", "bottomColor"), verified = true),
        Recipe("instagram_open_profile", "Instagram: open profile", IG, VIEW, data = "https://www.instagram.com/{username}/", params = listOf("username*"), verified = true),
        Recipe("instagram_open_hashtag", "Instagram: open hashtag", IG, VIEW, data = "https://www.instagram.com/explore/tags/{tag}/", params = listOf("tag*"), verified = true),
        Recipe("youtube_search", "YouTube: search", YT, VIEW, data = "https://www.youtube.com/results?search_query={query}", params = listOf("query*"), verified = true),
        Recipe("youtube_open_video", "YouTube: open video", YT, VIEW, data = "vnd.youtube:{id}", params = listOf("id*"), web = "https://youtu.be/{id}", verified = false),
        Recipe("youtube_open_channel", "YouTube: open channel", YT, VIEW, data = "https://www.youtube.com/@{handle}", params = listOf("handle*"), verified = true),
        Recipe("ytmusic_search", "YouTube Music: search", YTM, VIEW, data = "https://music.youtube.com/search?q={query}", params = listOf("query*"), verified = true),
        Recipe("ytmusic_play_search", "YouTube Music: play from search", YTM, PLAY_SEARCH, extras = mapOf(EXTRA_QUERY to "{query}", EXTRA_MEDIA_FOCUS to "vnd.android.cursor.item/*"), params = listOf("query*"), verified = true),
        Recipe("spotify_play_uri", "Spotify: play URI/link", SPOTIFY, VIEW, data = "{uri}", params = listOf("uri*"), verified = true),
        Recipe("spotify_search", "Spotify: search", SPOTIFY, VIEW, data = "spotify:search:{query}", params = listOf("query*"), verified = false),
        Recipe("spotify_play_search", "Spotify: play from search", SPOTIFY, PLAY_SEARCH, extras = mapOf(EXTRA_QUERY to "{query}"), params = listOf("query*"), verified = true),
        Recipe("whatsapp_message", "WhatsApp: message", WA, VIEW, data = "https://wa.me/{phone}?text={text}", params = listOf("phone", "text"), verified = false),
        Recipe("whatsapp_share_text", "WhatsApp: share text", WA, SEND, mime = "text/plain", extras = mapOf(EXTRA_TEXT to "{text}"), params = listOf("text*"), verified = true),
        Recipe("telegram_share", "Telegram: share link", TG, VIEW, data = "tg://msg_url?url={url}&text={text}", params = listOf("url*", "text"), web = "https://t.me/share/url?url={url}&text={text}", verified = true),
        Recipe("telegram_open_chat", "Telegram: open chat", TG, VIEW, data = "tg://resolve?domain={username}&text={text}", params = listOf("username*", "text"), web = "https://t.me/{username}", verified = true),
        Recipe("x_post", "X: compose post", X, VIEW, data = "https://x.com/intent/post?text={text}&url={url}", params = listOf("text", "url"), verified = true),
        Recipe("x_open_profile", "X: open profile", X, VIEW, data = "https://x.com/{username}", params = listOf("username*"), verified = true),
        Recipe("maps_navigate", "Maps: navigate", MAPS, VIEW, data = "google.navigation:q={destination}&mode={mode}", params = listOf("destination*", "mode"), verified = true),
        Recipe("maps_search", "Maps: search", MAPS, VIEW, data = "geo:0,0?q={query}", params = listOf("query*"), verified = true),
        Recipe("playstore_open", "Play Store: open app page", PLAY, VIEW, data = "market://details?id={packageName}", params = listOf("packageName*"), web = "https://play.google.com/store/apps/details?id={packageName}", verified = true),
        Recipe("playstore_search", "Play Store: search", PLAY, VIEW, data = "market://search?q={query}", params = listOf("query*"), verified = true),
        Recipe("chrome_open", "Chrome: open URL", CHROME, VIEW, data = "{url}", params = listOf("url*"), verified = true),
        Recipe("gmail_compose", "Gmail: compose", GMAIL, SENDTO, data = "mailto:{to}", extras = mapOf(EXTRA_SUBJECT to "{subject}", EXTRA_TEXT to "{text}"), params = listOf("to", "subject", "text"), verified = true),
        Recipe("camera_open", "Camera: open", null, STILL_CAMERA, params = emptyList(), verified = true),
        Recipe("clock_show_alarms", "Clock: show alarms", null, SHOW_ALARMS, params = emptyList(), verified = true),
        Recipe("calendar_new_event", "Calendar: new event", null, INSERT, data = "content://com.android.calendar/events",
            extras = mapOf("title" to "{title}", "beginTime" to "{beginTime}", "endTime" to "{endTime}"), params = listOf("title*", "start", "durationMinutes"), verified = true),
        Recipe("uber_ride", "Uber: request ride", "com.ubercab", VIEW, data = "uber://?action=setPickup&pickup=my_location&dropoff%5Bformatted_address%5D={destination}", params = listOf("destination*"),
            web = "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff%5Bformatted_address%5D={destination}", verified = false),
        Recipe("slack_open_channel", "Slack: open channel", "com.Slack", VIEW, data = "slack://channel?team={teamId}&id={channelId}", params = listOf("teamId*", "channelId*"), verified = false),
        Recipe("zoom_join", "Zoom: join meeting", "us.zoom.videomeetings", VIEW, data = "zoomus://zoom.us/join?confno={id}&pwd={passcode}", params = listOf("id*", "passcode"), verified = false),
        Recipe("tiktok_open_profile", "TikTok: open profile", "com.zhiliaoapp.musically", VIEW, data = "https://www.tiktok.com/@{username}", params = listOf("username*"), verified = true),
        Recipe("reddit_open", "Reddit: open subreddit", "com.reddit.frontpage", VIEW, data = "https://www.reddit.com/r/{subreddit}", params = listOf("subreddit*"), verified = true),
        Recipe("generic_share_text", "Any app: share text", null, SEND, mime = "text/plain", extras = mapOf(EXTRA_TEXT to "{text}", EXTRA_SUBJECT to "{subject}"), params = listOf("packageName*", "text*", "subject"), verified = true),
        Recipe("generic_share_file", "Any app: share file", null, SEND, mime = "{mimeType}", extras = mapOf(EXTRA_STREAM to "{fileUri}", EXTRA_TEXT to "{text}"), params = listOf("packageName*", "fileUri*", "mimeType", "text"), verified = true),
        Recipe("generic_open_url", "Any app: open URL", null, VIEW, data = "{url}", params = listOf("packageName*", "url*"), verified = true),
        Recipe("generic_play_search", "Any app: play from search", null, PLAY_SEARCH, extras = mapOf(EXTRA_QUERY to "{query}"), params = listOf("packageName*", "query*"), verified = true),
        Recipe("generic_launch", "Any app: launch", null, LAUNCH, params = listOf("packageName*"), verified = true),
    )
    private val BY_ID = ALL.associateBy { it.id }
    fun byId(id: String): Recipe? = BY_ID[id]

    /** Ids of every recipe that has `key` as a param (drives `visibleWhen` in app.action). */
    fun paramsUsing(key: String): Array<String> = ALL.filter { key in it.keys }.map { it.id }.toTypedArray()

    /** Digits only; a parenthesised trunk prefix such as "(0)" is dropped: "+49 (0)170-1" -> "491701". */
    fun normalizePhone(s: String): String = s.replace(Regex("\\([^)]*\\)"), "").filter { it.isDigit() }

    private const val UNRESERVED = "_-!.~'()*"
    /** android.net.Uri.encode semantics in pure Kotlin (JVM-testable): alphanumerics and `_-!.~'()*` kept, everything else %XX (UTF-8). */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED)) sb.append(ch) else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    private val VIDEO_EXT = setOf("mp4", "mov", "3gp", "mkv", "webm", "m4v")
    private val PLACEHOLDER = Regex("\\{([a-zA-Z]+)\\}")   // '}' escaped: Android ICU rejects a bare '}' (JVM tolerates it) — device crash on first launch

    /** Substitute + validate. Pure. Throws NodeException naming the missing/invalid argument. */
    fun fill(r: Recipe, args: Map<String, String?>, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Filled {
        val a = HashMap<String, String>()
        for (k in r.keys) args[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { a[k] = it }
        for (k in r.required) if (a[k] == null) throw NodeException("${r.label}: '$k' is required")
        when (r.id) {
            "whatsapp_message" -> if (a["phone"] == null && a["text"] == null) throw NodeException("${r.label}: needs a phone or a text")
            "x_post" -> if (a["text"] == null && a["url"] == null) throw NodeException("${r.label}: needs a text or a url")
            "maps_navigate" -> a["mode"] = when (a["mode"]?.lowercase()) { null, "d", "driving" -> "d"; "w", "walking" -> "w"; "b", "bicycling" -> "b"; else -> throw NodeException("${r.label}: mode must be d, w or b") }
            "generic_share_file" -> a["mimeType"] = a["mimeType"] ?: "image/*"
            "calendar_new_event" -> {
                val start = parseStart(a["start"], zone, nowMs)
                val minutes = a["durationMinutes"]?.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 60.0
                a["beginTime"] = start.toString(); a["endTime"] = (start + (minutes * 60_000).toLong()).toString()
            }
        }
        a["phone"]?.let { a["phone"] = normalizePhone(it).ifEmpty { throw NodeException("${r.label}: phone has no digits") } }
        a["fileUri"]?.let { u -> if (r.id.startsWith("instagram_") && !(u.startsWith("content://") || u.startsWith("file://") || u.startsWith("/"))) throw NodeException("${r.label}: fileUri must be a content:// URI or a file inside Mahout's files/cache dir") }
        fun sub(t: String, enc: Boolean) = PLACEHOLDER.replace(t) { m -> a[m.groupValues[1]]?.let { if (enc) encode(it) else it } ?: "" }
        // a template that IS one placeholder ("{url}", "{uri}", "{fileUri}") carries a whole URI: substituted raw, must have a scheme
        fun whole(t: String?): String? = t?.let { PLACEHOLDER.matchEntire(it)?.groupValues?.get(1) }
        var data = whole(r.data)?.let { k -> a[k]?.also { v -> if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(v)) throw NodeException("${r.label}: '$k' must be a full URI with a scheme, got '$v'") } } ?: r.data?.let { sub(it, true) }
        var mime = r.mime?.let { sub(it, false) }
        var web = whole(r.web)?.let { a[it] } ?: r.web?.let { sub(it, true) }
        val fileData = r.data == "{fileUri}"
        if (r.id == "whatsapp_message" && a["phone"] == null) data = "https://wa.me/?text=" + encode(a["text"] ?: "")
        if (r.id == "spotify_play_uri") web = a["uri"]?.takeIf { it.startsWith("https://") }
        if (r.id == "instagram_add_to_story" && (a["fileUri"] ?: "").substringAfterLast('.').substringBefore('?').lowercase() in VIDEO_EXT) mime = "video/*"
        // extras: raw substitution; an extra whose only placeholder is blank is dropped (optional subject/text/colour)
        val extras = LinkedHashMap<String, String>()
        for ((k, t) in r.extras) {
            val refs = PLACEHOLDER.findAll(t).map { it.groupValues[1] }.toList()
            if (refs.isNotEmpty() && refs.all { a[it] == null }) continue
            extras[k] = sub(t, false)
        }
        val pkg = r.pkg ?: if (r.usesPackageParam) a["packageName"] else null
        return Filled(r, pkg, r.action, data, mime, extras, web, fileData)
    }

    /** ISO-8601 (offset/local date-time/date) or epoch (s if < 1e10, else ms) -> epoch millis; blank = now. Mirrors action.add_calendar_event. */
    private fun parseStart(s: String?, zone: ZoneId, nowMs: Long): Long {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return nowMs
        t.toLongOrNull()?.let {
            if (t.trimStart('-').length < 9) throw NodeException("start '$s' is not an epoch (seconds or ms) or ISO date")
            return if (kotlin.math.abs(it) < 10_000_000_000L) it * 1000 else it
        }
        return try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
            try { ZonedDateTime.parse(t).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
                try { LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
                    try { LocalDate.parse(t).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {
                        throw NodeException("start '$s' is not an ISO date-time or epoch millis")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Android side

    /** content:// as is; a path inside filesDir/cacheDir -> FileProvider (the action.share rule). */
    fun shareUri(a: Context, v: String): Uri {
        val u = Uri.parse(v)
        return when {
            u.scheme == "content" -> u
            u.scheme == null || u.scheme == "file" -> {
                val f = File(u.path ?: v).canonicalFile
                val ok = listOf(a.filesDir, a.cacheDir).any { f.path.startsWith(it.canonicalPath + File.separator) }
                if (!ok || !f.exists()) throw NodeException("Only files inside Mahout's files/cache dir can be shared: $v")
                FileProvider.getUriForFile(a, FILE_AUTHORITY, f)
            }
            else -> throw NodeException("Unsupported file URI: $v")
        }
    }

    /** The Intent for `pkg` (null = unbound). Extras typed by key: STREAM -> Uri + read grant, calendar times -> long. */
    fun bind(f: Filled, a: Context, pkg: String?): Intent {
        val i = Intent(f.action)
        val data: Uri? = f.data?.let { if (f.fileData) shareUri(a, it) else Uri.parse(it) }
        when {
            data != null && f.mime != null -> i.setDataAndType(data, f.mime)
            data != null -> i.data = data
            f.mime != null -> i.type = f.mime
        }
        if (f.fileData) i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for ((k, v) in f.extras) when (k) {
            EXTRA_STREAM -> { val u = shareUri(a, v); i.putExtra(k, u); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); i.clipData = ClipData.newRawUri("share", u) }
            "beginTime", "endTime" -> i.putExtra(k, v.toLong())
            else -> i.putExtra(k, v)
        }
        if (pkg != null) i.setPackage(pkg)
        return i
    }

    /** True when `pkg` (or an ALT_PKG) resolves the filled intent — the `available` flag of app.capabilities / app.recipes. */
    fun resolves(f: Filled, a: Context, pkg: String): Boolean {
        val pm = a.packageManager
        if (f.action == LAUNCH) return pm.getLaunchIntentForPackage(pkg) != null
        return runCatching { pm.resolveActivity(bind(f, a, pkg), PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)
    }

    /**
     * Intent ready for AppLaunch.start: bound to the recipe package when installed (ALT_PKG tried), else the `web` fallback,
     * else NodeException("<label> is not installed" / "no app can handle").
     */
    fun intent(f: Filled, a: Context): Intent {
        val pm = a.packageManager
        if (f.action == LAUNCH) {
            val p = f.pkg ?: throw NodeException("${f.recipe.label}: 'packageName' is required")
            return pm.getLaunchIntentForPackage(p) ?: throw NodeException("${pm.labelOrNull(p) ?: p} is not installed or has no launcher")
        }
        val pkg = f.pkg ?: return bind(f, a, null)
        for (p in listOf(pkg) + ALT_PKG[pkg].orEmpty()) if (resolves(f, a, p)) return bind(f, a, p)
        if (f.web != null) return Intent(Intent.ACTION_VIEW, Uri.parse(f.web))
        val installed = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        throw NodeException(if (installed) "${pm.labelOrNull(pkg) ?: pkg}: no app can handle ${f.action.substringAfterLast('.')} ${f.data ?: f.mime ?: ""}".trim()
        else "${f.recipe.label.substringBefore(':')} is not installed ($pkg)")
    }
}

/** Direct startActivity while the accessibility binding lifts the background-start block (V14), else the v1 trampoline path. */
object AppLaunch {
    fun start(a: Context, intent: Intent, fallbackNotification: Boolean, title: String): Launch.Result {
        // ponytail: `instance != null` alone; upgrade = `&& Launch.canStartDirectly()` if an OEM logs "Background activity launch blocked" (device plan step 8)
        if (UiAutomationService.instance != null) {
            try { a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return Launch.Result(true, false) }
            catch (e: ActivityNotFoundException) { throw NodeException("No app can handle this: ${intent.action} ${intent.data ?: ""}".trim(), e) }
        }
        return Launch.start(a, intent, fallbackNotification, title)
    }
}

object AppActionNode : Node() {
    private val LABELS = mapOf(
        "text" to "Text", "url" to "URL", "query" to "Query", "phone" to "Phone", "username" to "Username", "handle" to "Channel handle", "tag" to "Hashtag",
        "id" to "Id", "uri" to "URI or link", "fileUri" to "File URI or path", "mimeType" to "MIME type", "to" to "To", "subject" to "Subject",
        "destination" to "Destination", "mode" to "Mode (d/w/b)", "facebookAppId" to "Facebook App ID", "topColor" to "Top colour (#RRGGBB)", "bottomColor" to "Bottom colour (#RRGGBB)",
        "teamId" to "Team id", "channelId" to "Channel id", "passcode" to "Passcode", "title" to "Title", "start" to "Start (ISO or epoch)", "durationMinutes" to "Duration (min)", "subreddit" to "Subreddit",
    )
    /** Every distinct recipe param except the app picker, in first-seen order. */
    val TEXT_KEYS: List<String> = Recipes.ALL.flatMap { it.keys }.distinct().filter { it != "packageName" }

    override val spec = NodeSpec(
        id = "app.action", name = "App action", kind = NodeKind.ACTION,
        description = "Opens another app at a specific place via a recipe (Instagram share/story, YouTube/Spotify search or play, WhatsApp/Telegram message, Maps navigate, Play Store, generic share/open/launch).",
        params = listOf(choice("recipe", "Recipe", Recipes.ALL.map { it.id }, "generic_share_text", help = "Community rows (Uber, Slack, Zoom, TikTok, Reddit) are unverified")) +
            appPicker("packageName", "App", visibleWhen = whenIs("recipe", *Recipes.paramsUsing("packageName"))) +
            TEXT_KEYS.map { k -> text(k, LABELS[k] ?: k.replaceFirstChar(Char::titlecase), visibleWhen = whenIs("recipe", *Recipes.paramsUsing(k))) } +
            rows("extras", "Extra intent extras", listOf(text("key", "Key", required = true), text("value", "Value"), choice("type", "Type", listOf("string", "int", "boolean")))) +
            bool("fallbackNotification", "Notify when blocked", true),
        gates = listOf(Gate.Overlay, Gate.Advisory(Gate.PostNotifications)), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val r = Recipes.byId(ctx.str("recipe")) ?: throw NodeException("Unknown recipe '${ctx.str("recipe")}'")
        val args = r.keys.associateWith { ctx.strOrNull(it) }
        val filled = Recipes.fill(r, args, ctx.nowMs(), ctx.zone)
        val intent = Recipes.intent(filled, a)
        for (row in ctx.rows("extras")) {
            val k = row["key"].asText().ifBlank { throw NodeException("Extra key is required") }
            val v = row["value"].asText()
            when (row["type"].asText().ifBlank { "string" }) {
                "int" -> intent.putExtra(k, v.trim().toIntOrNull() ?: throw NodeException("Extra $k: '$v' is not an int"))
                "boolean" -> intent.putExtra(k, v.trim().toBooleanStrictOrNull() ?: throw NodeException("Extra $k: '$v' is not true/false"))
                else -> intent.putExtra(k, v)
            }
        }
        val res = AppLaunch.start(a, intent, ctx.bool("fallbackNotification"), "Open ${r.label.substringBefore(':')}")
        return out(ctx.item.add("launched" to res.started, "viaNotification" to res.viaNotification, "recipe" to r.id, "package" to intent.`package`, "data" to intent.dataString))
    }
}
