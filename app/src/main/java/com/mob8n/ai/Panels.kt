package com.mob8n.ai

import android.content.Context
import android.content.SharedPreferences
import com.mob8n.core.JSON
import com.mob8n.core.SECRETS_PREFS
import com.mob8n.core.SETTINGS_PREFS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.util.UUID

enum class PanelKind { WEB, IMAGE }

/** One Dashboard > Panels tile (DESIGN5 §3.5, §8.3). Never carries a secret VALUE: value = secrets["panel_<id>_auth"] (Authorization: Bearer). */
@Serializable
data class Panel(
    val id: String, val title: String, val url: String, val kind: PanelKind = PanelKind.WEB,
    val authHeader: Boolean = false, val hasSecret: Boolean = false, val refreshMs: Long = 1_000 /* IMAGE only, 500..10_000 */,
)

/** settings["panels"] JSON array (no secret values) + secrets["panel_<id>_auth"]; MAX 8; title ≤ 40, unique (case-insensitive). McpPrefs idiom. */
object PanelPrefs {
    const val KEY = "panels"; const val MAX_PANELS = 8
    private const val TITLE_MAX = 40
    private val SECRET_QUERY = listOf("token", "key")

    private val _panels = MutableStateFlow<List<Panel>>(emptyList())
    val panels: StateFlow<List<Panel>> = _panels.asStateFlow()

    private fun settings(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun secrets(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)
    fun secretName(id: String): String = "panel_${id}_auth"

    fun load(ctx: Context) { _panels.value = read(ctx) }

    /** Synchronous; hasSecret recomputed, never trusted from JSON. */
    fun read(ctx: Context): List<Panel> {
        val raw = settings(ctx).getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val rows = runCatching { JSON.decodeFromString(ListSerializer(Panel.serializer()), raw) }.getOrDefault(emptyList())
        val sec = secrets(ctx)
        return rows.map { it.copy(hasSecret = !sec.getString(secretName(it.id), null).isNullOrBlank()) }
    }

    fun byName(ctx: Context, name: String): Panel? = read(ctx).let { all -> all.firstOrNull { it.title.equals(name.trim(), ignoreCase = true) } ?: all.firstOrNull { slug(it.title) == slug(name) } }
    fun bySlug(ctx: Context, slug: String): Panel? = read(ctx).firstOrNull { slug(it.title) == slug }

    /** Upsert by id; validateUrl + title rules -> IllegalArgumentException text shown by ui; secret null = keep, "" = remove. */
    fun save(ctx: Context, p: Panel, secret: String?) {
        val title = p.title.trim()
        require(title.isNotBlank()) { "Title is required" }
        require(title.length <= TITLE_MAX) { "Title must be at most $TITLE_MAX characters" }
        val url = validateUrl(p.url)
        val id = p.id.ifBlank { UUID.randomUUID().toString() }
        val rows = read(ctx)
        require(rows.none { it.id != id && (it.title.equals(title, ignoreCase = true) || slug(it.title) == slug(title)) }) { "A panel named '$title' already exists" }
        require(rows.any { it.id == id } || rows.size < MAX_PANELS) { "At most $MAX_PANELS panels" }
        val sec = secrets(ctx)
        when {
            !p.authHeader || secret == "" -> sec.edit().remove(secretName(id)).apply()
            secret != null -> sec.edit().putString(secretName(id), secret.trim()).apply()
        }
        val row = p.copy(id = id, title = title, url = url, refreshMs = p.refreshMs.coerceIn(500, 10_000), hasSecret = !sec.getString(secretName(id), null).isNullOrBlank())
        write(ctx, rows.filter { it.id != id } + row)
    }

    fun delete(ctx: Context, id: String) {
        secrets(ctx).edit().remove(secretName(id)).apply()
        write(ctx, read(ctx).filter { it.id != id })
    }

    /** Secrets read; null when unset. Never logged. */
    fun secret(ctx: Context, p: Panel): String? = secrets(ctx).getString(secretName(p.id), null)?.takeIf { it.isNotBlank() }

    private fun write(ctx: Context, rows: List<Panel>) {
        settings(ctx).edit().putString(KEY, JSON.encodeToString(ListSerializer(Panel.serializer()), rows.map { it.copy(hasSecret = false) })).apply()
        _panels.value = rows
    }

    /**
     * Pure: http(s) only; https any host; http only for Providers.isLanHost; no userinfo; no `token=`/`key=`-style query names (secrets never ride
     * in URLs); javascript:/file:/content:/data: rejected. Returns the trimmed URL; IllegalArgumentException with the reason otherwise.
     */
    fun validateUrl(url: String): String {
        val s = url.trim()
        require(s.isNotEmpty()) { "URL is required" }
        val uri = try { URI(s) } catch (e: Exception) { throw IllegalArgumentException("Not a valid URL") }
        when (uri.scheme?.lowercase()) {
            "https" -> {}
            "http" -> {}
            null -> throw IllegalArgumentException("URL must start with http:// or https://")
            else -> throw IllegalArgumentException("Only http:// and https:// panels are allowed (not ${uri.scheme}:)")
        }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("URL needs a host, e.g. http://192.168.1.5:4173")
        require(uri.rawUserInfo == null) { "Credentials in the URL are not allowed — use the Bearer secret" }
        if (uri.scheme.equals("http", ignoreCase = true)) require(Providers.isLanHost(host)) { "Plain http:// is only allowed for local-network hosts (localhost, *.local, 10.x, 172.16-31.x, 192.168.x); use https://" }
        val names = uri.rawQuery?.split('&')?.map { it.substringBefore('=').lowercase() }.orEmpty()
        require(names.none { n -> SECRET_QUERY.any { n.endsWith(it) } }) { "Secrets never ride in URLs (token=/key= query) — use the Bearer secret instead" }
        return s
    }

    /** lowercase, [^a-z0-9]+ -> '-', trimmed, ≤ 40. */
    fun slug(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(TITLE_MAX).trim('-')
}
