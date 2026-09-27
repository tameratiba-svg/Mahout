package com.mob8n.ai

import android.content.Context
import android.content.SharedPreferences
import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.SECRETS_PREFS
import com.mob8n.core.SETTINGS_PREFS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

enum class McpAuth { NONE, BEARER, HEADER }

/** One Settings > AI > MCP row. Never carries a secret VALUE: value = secrets["mcp_<id>_auth"]. */
@Serializable
data class McpServer(
    val id: String, val name: String, val url: String,            // https://host/mcp ; http:// only for loopback / *.local / RFC-1918 (Providers.normalizeBaseUrl)
    val auth: McpAuth = McpAuth.NONE, val headerName: String = "Authorization",   // HEADER: "<headerName>: <secret>"; BEARER: "Authorization: Bearer <secret>"
    val hasSecret: Boolean = false, val enabled: Boolean = true,
    val trusted: Boolean = false,                                 // true = Agent calls run WITHOUT the approval gate ("trusted / read-only server")
    val protocol: String? = null,                                 // last negotiated: "2026-07-28" | "2025-11-25" | … (display + first-probe hint)
    val lastTools: List<String> = emptyList(), val testedAt: Long? = null, val lastError: String? = null,
) { val slug: String get() = McpClient.slug(name) }

/**
 * MCP server rows in settings["mcp_servers"] (JSON array, no secret values) + the write side of secrets["mcp_<id>_auth"] (DESIGN3 §3.3).
 * ponytail: one static auth secret (bearer or one header); ceiling = no OAuth 2.1 flow
 */
object McpPrefs {
    const val KEY = "mcp_servers"
    const val MAX_SERVERS = 10
    private const val NAME_MAX = 40

    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    private fun settings(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun secrets(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)
    fun secretName(id: String): String = "mcp_${id}_auth"

    /** Idempotent; parses settings["mcp_servers"], fills hasSecret, sets McpClient.clientVersion from PackageInfo.versionName. */
    fun load(context: Context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()?.let { McpClient.clientVersion = it }
        _servers.value = read(context)
    }

    /** Synchronous, side-effect free (node execute paths). hasSecret is recomputed, never trusted from JSON. */
    fun read(context: Context): List<McpServer> {
        val raw = settings(context).getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val rows = runCatching { JSON.decodeFromString(ListSerializer(McpServer.serializer()), raw) }.getOrDefault(emptyList())
        val sec = secrets(context)
        return rows.map { it.copy(hasSecret = !sec.getString(secretName(it.id), null).isNullOrBlank()) }
    }

    fun byName(context: Context, name: String): McpServer? = read(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Enabled server names (LABELS / TEXT suggestions). */
    fun names(context: Context): List<String> = read(context).filter { it.enabled }.map { it.name }

    /** Upsert by id; secret null = keep, "" = remove; validates url (IllegalArgumentException text shown by ui), name non-blank + unique (ci) ≤ 40 chars. */
    fun save(context: Context, s: McpServer, secret: String?) {
        val name = s.name.trim()
        require(name.isNotBlank()) { "Name is required" }
        require(name.length <= NAME_MAX) { "Name must be at most $NAME_MAX characters" }
        val url = Providers.normalizeBaseUrl(s.url)
        val id = s.id.ifBlank { UUID.randomUUID().toString() }
        val rows = read(context)
        require(rows.none { it.id != id && it.name.equals(name, ignoreCase = true) }) { "A server named '$name' already exists" }
        require(rows.any { it.id == id } || rows.size < MAX_SERVERS) { "At most $MAX_SERVERS MCP servers" }
        if (s.auth == McpAuth.HEADER) require(s.headerName.trim().isNotBlank() && s.headerName.none { it == ' ' || it == ':' || it == '\r' || it == '\n' }) { "Header name must be a single token" }
        val sec = secrets(context)
        when {
            s.auth == McpAuth.NONE || secret == "" -> sec.edit().remove(secretName(id)).apply()
            secret != null -> sec.edit().putString(secretName(id), secret.trim()).apply()
        }
        val row = s.copy(id = id, name = name, url = url, hasSecret = !sec.getString(secretName(id), null).isNullOrBlank())
        write(context, rows.filter { it.id != id } + row)
        McpClient.invalidate(id)
    }

    fun delete(context: Context, id: String) {
        secrets(context).edit().remove(secretName(id)).apply()
        write(context, read(context).filter { it.id != id })
        McpClient.invalidate(id)
    }

    /** Secrets read; null when unset. Never logged. */
    fun secret(context: Context, s: McpServer): String? = secrets(context).getString(secretName(s.id), null)?.takeIf { it.isNotBlank() }

    /** listTools(force) -> "12 tools · 2026-07-28" / "7 tools · legacy 2025-11-25 · session"; stamps protocol/lastTools/testedAt/lastError; never contains the secret. */
    suspend fun test(context: Context, id: String): Result<String> {
        val s = read(context).firstOrNull { it.id == id } ?: return Result.failure(NodeException("Server not found"))
        val secret = secret(context, s)
        return try {
            McpClient.invalidate(id)
            val tools = withTimeout(McpClient.LIST_MS + McpClient.CONNECT_MS) { McpClient.listTools(s, secret, force = true) }
            val c = McpClient.conn(id)
            val era = if (c?.era == "legacy") "legacy ${c.protocol}" + (if (c.sessionId != null) " · session" else "") else c?.protocol ?: McpClient.MODERN
            val text = "${tools.size} tools · $era"
            update(context, id) { it.copy(protocol = c?.protocol, lastTools = tools.map { t -> t.name }.take(64), testedAt = System.currentTimeMillis(), lastError = null) }
            Result.success(text)
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                val msg = "${s.name} timed out"
                update(context, id) { it.copy(testedAt = System.currentTimeMillis(), lastError = msg) }
                Result.failure(NodeException(msg))
            } else throw e
        } catch (e: Exception) {
            val msg = McpClient.clean(e.message ?: e.javaClass.simpleName, secret)
            update(context, id) { it.copy(testedAt = System.currentTimeMillis(), lastError = msg) }
            Result.failure(NodeException(msg))
        }
    }

    /** Agent run start: persist the era McpClient negotiated (display + first-probe hint). */
    fun noteProtocol(context: Context, id: String) {
        val p = McpClient.conn(id)?.protocol?.takeIf { it.isNotBlank() } ?: return
        val row = read(context).firstOrNull { it.id == id } ?: return
        if (row.protocol != p) update(context, id) { it.copy(protocol = p) }
    }

    private fun update(context: Context, id: String, f: (McpServer) -> McpServer) {
        write(context, read(context).map { if (it.id == id) f(it) else it })
    }

    private fun write(context: Context, rows: List<McpServer>) {
        val stored = rows.map { it.copy(hasSecret = false) }                 // never trusted from JSON
        settings(context).edit().putString(KEY, JSON.encodeToString(ListSerializer(McpServer.serializer()), stored)).apply()
        _servers.value = rows
    }
}

/** A one-tap server template (DESIGN5 §8.1): urlTemplate holds [McpPresets.HOST]; the token is pasted by the user, never shipped. */
data class McpPreset(val id: String, val name: String, val urlTemplate: String, val auth: McpAuth, val trusted: Boolean, val note: String)

/** The MCP presets (D9: godseye-uav only; Laya is an HTTP API under Settings > AI > Decision engine, its MCP server is stdio-only). */
object McpPresets {
    const val HOST = "<host>"
    val ALL: List<McpPreset> = listOf(
        McpPreset("godseye-uav", "godseye-uav", "http://$HOST:8791/mcp", McpAuth.BEARER, trusted = false,
            note = "ObraMaestra godSeye — simulated UAV ISR (45 tools: uav_*, mission_* incl. mission_dry_run, sim_*). ISR-only: no kinetic tools exist. Stays UNTRUSTED: every call, reads included, asks for approval. On the Mac godseye binds 127.0.0.1 — forward :8791 and :8790 first (README). Token = the Bearer value ./start.sh printed and wrote to godseye/.mcp.json (random per start unless GODSEYE_TOKEN is set); paste it here, never share that file."),
    )

    /** New untrusted server row; McpPrefs.save enforces the LAN rule for http:// (a public host is refused there). */
    fun instantiate(p: McpPreset, host: String): McpServer =
        McpServer(UUID.randomUUID().toString(), p.name, p.urlTemplate.replace(HOST, host.trim()), p.auth, trusted = false)
}
