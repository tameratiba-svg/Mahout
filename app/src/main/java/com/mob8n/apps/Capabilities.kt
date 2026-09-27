package com.mob8n.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.mob8n.core.ExecMode
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.appPicker
import com.mob8n.core.bool
import com.mob8n.core.item
import com.mob8n.core.out
import com.mob8n.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Capability census (DESIGN2 §7.1): 16 probes, one queryIntentActivities(MATCH_DEFAULT_ONLY) each, grouped by package, cached 60 s. */
object Capabilities {
    private const val TTL_MS = 60_000L

    /** capability id -> probe intent. Exactly 16 rows (§7.1). */
    val PROBES: List<Pair<String, () -> Intent>> = listOf(
        "share_text" to { Intent(Intent.ACTION_SEND).setType("text/plain") },
        "share_image" to { Intent(Intent.ACTION_SEND).setType("image/*") },
        "share_video" to { Intent(Intent.ACTION_SEND).setType("video/*") },
        "share_multiple_images" to { Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*") },
        "open_https" to { Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")) },
        "open_geo" to { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")) },
        "compose_email" to { Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) },
        "compose_sms" to { Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) },
        "dial" to { Intent(Intent.ACTION_DIAL, Uri.parse("tel:")) },
        "pick_image" to { Intent(Intent.ACTION_PICK).setType("image/*") },
        "get_content" to { Intent(Intent.ACTION_GET_CONTENT).setType("*/*") },
        "insert_event" to { Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event") },
        "capture_image" to { Intent(MediaStore.ACTION_IMAGE_CAPTURE) },
        "capture_video" to { Intent(MediaStore.ACTION_VIDEO_CAPTURE) },
        "play_from_search" to { Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) },
        "web_search" to { Intent(Intent.ACTION_WEB_SEARCH) },
    )

    /** Generic recipes are "available" when the matching probe listed the package (no extra binder calls). */
    // ponytail: recipe availability via probes (generic rows) + resolveActivity with sample args (app rows); upgrade = per-recipe probes
    private val GENERIC_CAP = mapOf("generic_share_text" to "share_text", "generic_share_file" to "share_image", "generic_open_url" to "open_https", "generic_play_search" to "play_from_search")
    private val SAMPLE = mapOf(
        "url" to "https://example.com/", "fileUri" to "content://com.mob8n.files/cache/sample.jpg", "phone" to "15551234567", "uri" to "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
        "id" to "dQw4w9WgXcQ", "mode" to "d", "to" to "a@example.com", "facebookAppId" to "123456789", "start" to "", "durationMinutes" to "60", "mimeType" to "image/*",
    )

    data class AppCaps(val pkg: String, val appName: String, val isSystem: Boolean, val hasLauncher: Boolean, val capabilities: List<String>, val recipes: List<JsonObject>)

    @Volatile private var cache: Pair<Long, List<AppCaps>>? = null

    suspend fun census(a: Context): List<AppCaps> {
        val now = System.currentTimeMillis()
        cache?.let { (at, v) -> if (now - at < TTL_MS) return v }
        return withContext(Dispatchers.IO) {
            val pm = a.packageManager
            @Suppress("DEPRECATION")
            val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { it.activityInfo?.applicationInfo }.distinctBy { it.packageName }.associateBy { it.packageName }
            val caps = LinkedHashMap<String, LinkedHashSet<String>>()
            for ((id, mk) in PROBES) {
                @Suppress("DEPRECATION")
                val handlers = runCatching { pm.queryIntentActivities(mk(), PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
                for (ri in handlers) ri.activityInfo?.packageName?.let { caps.getOrPut(it) { LinkedHashSet() } += id }
            }
            val pkgs = (launcher.keys + caps.keys).distinct()
            val result = pkgs.mapNotNull { pkg ->
                val ai = launcher[pkg] ?: runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                val c = caps[pkg]?.toList().orEmpty()
                AppCaps(pkg, pm.getApplicationLabel(ai).toString(), isSystem(ai), launcher.containsKey(pkg), c, recipesFor(a, pkg, c, launcher.containsKey(pkg)))
            }.sortedBy { it.appName.lowercase() }
            cache = now to result
            result
        }
    }

    /** Recipe rows applicable to `pkg`: its own rows (ALT_PKG aware) + generic rows, each {id, label, params, available, template}. */
    fun recipesFor(a: Context, pkg: String, caps: List<String>, hasLauncher: Boolean): List<JsonObject> {
        val own = Recipes.ALL.filter { r -> r.pkg == pkg || Recipes.ALT_PKG[r.pkg]?.contains(pkg) == true }
        val generic = Recipes.ALL.filter { it.usesPackageParam }
        return (own + generic).map { r ->
            val available = when {
                r.id == "generic_launch" -> hasLauncher
                r.usesPackageParam -> GENERIC_CAP[r.id]?.let { it in caps } ?: false
                else -> runCatching { Recipes.resolves(Recipes.fill(r, sampleArgs(r, pkg)), a, pkg) }.getOrDefault(false)
            }
            item("recipe" to r.id, "label" to r.label, "params" to r.params, "available" to available, "template" to r.template, "verified" to r.verified)
        }
    }

    private fun sampleArgs(r: Recipe, pkg: String): Map<String, String> = r.keys.associateWith { k -> if (k == "packageName") pkg else SAMPLE[k] ?: "example" }

    /** Preloaded and never updated from the store (same rule as data.installed_apps). */
    private fun isSystem(ai: ApplicationInfo) = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}

object CapabilitiesNode : Node() {
    override val spec = NodeSpec(
        id = "app.capabilities", name = "App capabilities", kind = NodeKind.DATA,
        description = "Lists installed apps with what they can handle (share text/image, open links, play from search, compose...) and which app.action recipes apply.",
        params = listOf(
            bool("includeSystem", "Include system apps", false),
            bool("onlyWithCapabilities", "Only apps with capabilities", true),
            text("packageRegex", "Package regex", help = "Only packages matching, e.g. com\\.google\\..*"),
        ),
        mode = ExecMode.LIST, timeoutMs = 30_000, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val regex = ctx.strOrNull("packageRegex")?.let { r -> runCatching { Regex(r) }.getOrElse { throw NodeException("Invalid package regex '$r': ${it.message}") } }
        val includeSystem = ctx.bool("includeSystem"); val onlyCaps = ctx.bool("onlyWithCapabilities")
        val items = Capabilities.census(a)
            .filter { (includeSystem || !it.isSystem) && (!onlyCaps || it.capabilities.isNotEmpty()) && (regex == null || regex.containsMatchIn(it.pkg)) }
            .map { item("package" to it.pkg, "appName" to it.appName, "isSystem" to it.isSystem, "capabilities" to it.capabilities, "recipes" to it.recipes) }
        ctx.log("${items.size} apps")
        return out(items)
    }
}

object RecipesNode : Node() {
    override val spec = NodeSpec(
        id = "app.recipes", name = "App recipes", kind = NodeKind.DATA,
        description = "Lists the app.action recipes that apply to one app, with their params and whether the installed app can handle each.",
        params = listOf(appPicker("packageName", "App", required = true)),
        mode = ExecMode.LIST, timeoutMs = 10_000, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val pkg = ctx.req("packageName").trim()
        return withContext(Dispatchers.IO) {
            val pm = a.packageManager
            val caps = Capabilities.census(a).firstOrNull { it.pkg == pkg }
            val hasLauncher = caps?.hasLauncher ?: (pm.getLaunchIntentForPackage(pkg) != null)
            out(Capabilities.recipesFor(a, pkg, caps?.capabilities.orEmpty(), hasLauncher))
        }
    }
}
