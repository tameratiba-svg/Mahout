package com.mob8n.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.genai.common.DownloadStatus
import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.SECRETS_PREFS
import com.mob8n.core.SECRET_CLAUDE_KEY
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** One row of Settings > AI > Providers. Never carries a key value. */
data class ProviderState(
    val id: String, val label: String,
    val hasKey: Boolean, val maskedKey: String,            // "" or "••••••••abcd"
    val needsKey: Boolean,                                 // false: ollama, on_device_gemini_nano
    val baseUrl: String, val editableBaseUrl: Boolean,     // effective URL (override or table default); editable: ollama, custom
    val models: List<String>,                              // last Fetch models (cached) or the static list; may be empty
    val verifiedAt: Long?,                                 // last successful Test
    val tools: Boolean, val jsonSchema: Boolean, val jsonObject: Boolean, val vision: Boolean,
)

/** The ONE default LLM. provider is a concrete id (never "default"/"auto"). effort applies to claude only; temperature null = provider default. */
data class DefaultAi(val provider: String, val model: String, val effort: String = "high", val temperature: Double? = null)

/**
 * AI settings + the write side of every provider key (DESIGN §8.6, DESIGN2 §3.1). The ui lane codes against exactly this surface.
 * Keys live in app-private SharedPreferences("secrets"), backup-excluded by scaffold's xml rules and redacted from logs via Secrets.allValues().
 * ponytail: plaintext app-private prefs, ceiling = rooted device; upgrade = AndroidKeyStore AES-GCM wrap.
 */
object AiPrefs {
    val MODELS: List<String> = listOf(ClaudeClient.MODEL_OPUS, ClaudeClient.MODEL_SONNET, ClaudeClient.MODEL_HAIKU)
    val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")
    /** Settings order: claude, the 11 http rows, nano. */
    val PROVIDER_IDS: List<String> = listOf(PROVIDER_CLAUDE) + Providers.HTTP.map { it.id } + listOf(PROVIDER_NANO)

    private const val KEY_MODEL = "ai_default_model"          // v1: the Claude model
    private const val KEY_EFFORT = "ai_default_effort"        // v1: the Claude effort
    private const val KEY_PREFER = "ai_prefer_on_device"
    private const val KEY_VERIFIED_AT = "ai_key_verified_at"  // v1: Claude
    private const val KEY_DEF_PROVIDER = "ai_default_provider"
    private const val KEY_DEF_MODEL = "ai_default_model_v2"
    private const val KEY_DEF_EFFORT = "ai_default_effort_v2"
    private const val KEY_DEF_TEMP = "ai_default_temperature"
    private const val KEY_MODELS = "ai_models_"
    private const val KEY_VERIFIED = "ai_verified_"

    private val _hasKey = MutableStateFlow(false)
    private val _maskedKey = MutableStateFlow("")
    private val _model = MutableStateFlow(MODELS.first())
    private val _effort = MutableStateFlow("high")
    private val _preferOnDevice = MutableStateFlow(true)
    private val _nanoStatus = MutableStateFlow(NanoStatus.UNKNOWN)
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    private val _providers = MutableStateFlow<Map<String, ProviderState>>(emptyMap())
    private val _defaultAi = MutableStateFlow<DefaultAi?>(null)
    private val _defaultLabel = MutableStateFlow("Not set")

    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()
    val maskedKey: StateFlow<String> = _maskedKey.asStateFlow()
    val model: StateFlow<String> = _model.asStateFlow()
    val effort: StateFlow<String> = _effort.asStateFlow()
    val preferOnDevice: StateFlow<Boolean> = _preferOnDevice.asStateFlow()
    val nanoStatus: StateFlow<NanoStatus> = _nanoStatus.asStateFlow()
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    val providers: StateFlow<Map<String, ProviderState>> = _providers.asStateFlow()
    val defaultAi: StateFlow<DefaultAi?> = _defaultAi.asStateFlow()
    val defaultLabel: StateFlow<String> = _defaultLabel.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun secrets(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)
    private fun settings(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun key(c: Context): String? = secret(c, SECRET_CLAUDE_KEY)

    /** Raw secret read (ai lane is the write side; Llm.defaultTarget has no Persistence). Never logged. */
    fun secret(c: Context, name: String): String? = secrets(c).getString(name, null)?.takeIf { it.isNotBlank() }

    /** Synchronous pref read for the execution path (Llm.target): no flow writes, no Nano status IPC. [load] is for the settings screen only. */
    fun preferOnDevice(c: Context): Boolean = settings(c).getBoolean(KEY_PREFER, true)

    /** Synchronous read of the Default AI (execution path). null = not chosen yet. */
    fun readDefaultAi(c: Context): DefaultAi? {
        val s = settings(c)
        val p = s.getString(KEY_DEF_PROVIDER, null)?.takeIf { it in PROVIDER_IDS } ?: return null
        return DefaultAi(
            p, s.getString(KEY_DEF_MODEL, null) ?: "",
            s.getString(KEY_DEF_EFFORT, null)?.takeIf { it in EFFORTS } ?: "high",
            s.getString(KEY_DEF_TEMP, null)?.toDoubleOrNull(),
        )
    }

    fun providerLabel(id: String): String = Providers.label(id)

    /** MiniMax reasoning mode (Settings > AI > MiniMax): true = reasoning_split (separate reasoning), false = inline <think> in content. */
    fun minimaxReasoningSplit(c: Context): Boolean = Providers.parseFlag(settings(c).all[Providers.KEY_MINIMAX_SPLIT], Providers.MINIMAX_SPLIT_DEFAULT)
    fun setMinimaxReasoningSplit(c: Context, split: Boolean) { settings(c).edit().putBoolean(Providers.KEY_MINIMAX_SPLIT, split).apply() }

    /** Idempotent: re-reads prefs into the flows (incl. providers + defaultAi) and refreshes nanoStatus asynchronously (unless a download is running). */
    fun load(context: Context) {
        val k = key(context)
        _hasKey.value = k != null
        _maskedKey.value = mask(k)
        val s = settings(context)
        _model.value = s.getString(KEY_MODEL, null)?.takeIf { it in MODELS } ?: MODELS.first()
        _effort.value = s.getString(KEY_EFFORT, null)?.takeIf { it in EFFORTS } ?: "high"
        _preferOnDevice.value = s.getBoolean(KEY_PREFER, true)
        refresh(context)
        if (_nanoStatus.value != NanoStatus.DOWNLOADING) scope.launch { _nanoStatus.value = NanoClient.status() }
    }

    /** Rebuilds providers / defaultAi / defaultLabel from prefs. */
    private fun refresh(c: Context) {
        val s = settings(c)
        _providers.value = PROVIDER_IDS.associateWith { id -> providerState(c, s, id) }
        val d = readDefaultAi(c)
        _defaultAi.value = d
        _defaultLabel.value = d?.let { "${providerLabel(it.provider)} · ${it.model.ifBlank { defaultModelOf(it.provider) }.ifBlank { "(pick a model)" }}" } ?: "Not set"
    }

    private fun defaultModelOf(id: String): String = when (id) {
        PROVIDER_CLAUDE -> ClaudeClient.MODEL_OPUS
        PROVIDER_NANO -> NanoClient.MODEL_NAME
        else -> Providers.byId(id)?.defaultModel ?: ""
    }

    private fun providerState(c: Context, s: SharedPreferences, id: String): ProviderState {
        val verified = s.getLong(KEY_VERIFIED + id, 0L).takeIf { it > 0 } ?: (if (id == PROVIDER_CLAUDE) s.getLong(KEY_VERIFIED_AT, 0L).takeIf { it > 0 } else null)
        return when (id) {
            PROVIDER_CLAUDE -> {
                val k = key(c)
                ProviderState(id, providerLabel(id), k != null, mask(k), true, "https://api.anthropic.com", false, MODELS, verified, tools = true, jsonSchema = true, jsonObject = true, vision = true)
            }
            PROVIDER_NANO -> ProviderState(id, providerLabel(id), false, "", false, "", false, listOf(NanoClient.MODEL_NAME), verified, tools = false, jsonSchema = false, jsonObject = false, vision = true)
            else -> {
                val p0 = Providers.byId(id)!!
                val p = runCatching { Providers.effective(c, p0) }.getOrDefault(p0)
                val k = secret(c, p.keySecret)
                val cached = s.getString(KEY_MODELS + id, null)?.let { runCatching { (JSON.parseToJsonElement(it) as JsonArray).mapNotNull { e -> e.asTextOrNull() } }.getOrNull() }
                ProviderState(id, p.label, k != null, mask(k), p.needsKey, p.baseUrl, p.editableBaseUrl, cached ?: p.staticModels, verified, p.tools, p.jsonSchema, p.jsonObject, p.vision)
            }
        }
    }

    // ---------------------------------------------------------------- v1 surface (Claude-specific, semantics unchanged)

    /** null/blank clears. == setProviderKey(context, claude, key). */
    fun setKey(context: Context, key: String?) = setProviderKey(context, PROVIDER_CLAUDE, key)

    fun setModel(context: Context, model: String) {
        if (model !in MODELS) return
        settings(context).edit().putString(KEY_MODEL, model).apply(); _model.value = model
    }

    fun setEffort(context: Context, effort: String) {
        if (effort !in EFFORTS) return
        settings(context).edit().putString(KEY_EFFORT, effort).apply(); _effort.value = effort
    }

    fun setPreferOnDevice(context: Context, value: Boolean) {
        settings(context).edit().putBoolean(KEY_PREFER, value).apply(); _preferOnDevice.value = value
    }

    /** == testProvider(context, claude, null). */
    suspend fun testKey(context: Context): Result<String> = testProvider(context, PROVIDER_CLAUDE, null)

    /** Drives downloadProgress (0..1, null when idle) and nanoStatus; warms the model up on completion. */
    suspend fun downloadNano(context: Context): Result<Unit> {
        var total = 0L
        var failure: Throwable? = null
        return try {
            _nanoStatus.value = NanoStatus.DOWNLOADING
            _downloadProgress.value = 0f
            NanoClient.download().collect { st ->
                when (st) {
                    is DownloadStatus.DownloadStarted -> total = st.bytesToDownload
                    is DownloadStatus.DownloadProgress -> _downloadProgress.value = if (total > 0) (st.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    is DownloadStatus.DownloadCompleted -> _downloadProgress.value = 1f
                    is DownloadStatus.DownloadFailed -> failure = st.e
                }
            }
            failure?.let { throw it }
            NanoClient.warmup()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(NodeException("Gemini Nano download failed: ${e.message ?: e.javaClass.simpleName}", e))
        } finally {
            _downloadProgress.value = null
            _nanoStatus.value = NanoClient.status()
        }
    }

    // ---------------------------------------------------------------- v2

    /** secrets["<id>_api_key"] (claude keeps SECRET_CLAUDE_KEY); null/blank removes; the first connected provider becomes the Default AI when none is set. */
    fun setProviderKey(context: Context, providerId: String, key: String?) {
        if (providerId == PROVIDER_NANO || providerId !in PROVIDER_IDS) return
        val k = key?.trim()?.takeIf { it.isNotBlank() }
        val name = Providers.keySecret(providerId)
        secrets(context).edit().apply { if (k == null) remove(name) else putString(name, k) }.apply()
        if (providerId == PROVIDER_CLAUDE) { _hasKey.value = k != null; _maskedKey.value = mask(k) }
        if (k != null && readDefaultAi(context) == null) setDefaultAi(context, DefaultAi(providerId, "", "high", null))
        refresh(context)
    }

    /** ollama/custom only; trims, strips trailing "/"; http:// only for loopback, *.local, RFC-1918 hosts else IllegalArgumentException; null = table default. */
    fun setProviderBaseUrl(context: Context, providerId: String, baseUrl: String?) {
        val p = Providers.byId(providerId)?.takeIf { it.editableBaseUrl } ?: throw IllegalArgumentException("${providerLabel(providerId)} has a fixed base URL")
        val v = baseUrl?.trim()?.takeIf { it.isNotBlank() }?.let { Providers.normalizeBaseUrl(it) }
        settings(context).edit().apply { if (v == null) remove(Providers.baseUrlKey(p.id)) else putString(Providers.baseUrlKey(p.id), v) }.apply()
        refresh(context)
    }

    /** settings ai_default_provider / ai_default_model_v2 / ai_default_effort_v2 / ai_default_temperature; null clears. */
    fun setDefaultAi(context: Context, value: DefaultAi?) {
        val e = settings(context).edit()
        val id = value?.let { Providers.LEGACY_ALIASES[it.provider] ?: it.provider }
        if (value == null || id !in PROVIDER_IDS) {
            e.remove(KEY_DEF_PROVIDER).remove(KEY_DEF_MODEL).remove(KEY_DEF_EFFORT).remove(KEY_DEF_TEMP)
        } else {
            e.putString(KEY_DEF_PROVIDER, id).putString(KEY_DEF_MODEL, value.model.trim())
                .putString(KEY_DEF_EFFORT, value.effort.takeIf { it in EFFORTS } ?: "high")
                .putString(KEY_DEF_TEMP, value.temperature?.toString() ?: "")
        }
        e.apply()
        refresh(context)
    }

    /** GET models; caches settings["ai_models_<id>"]; claude -> MODELS; nano -> ["gemini-nano"]; minimax 404 -> static list (inside OpenAiCompat.listModels). */
    suspend fun fetchModels(context: Context, providerId: String): Result<List<String>> {
        val list = when (providerId) {
            PROVIDER_CLAUDE -> MODELS
            PROVIDER_NANO -> listOf(NanoClient.MODEL_NAME)
            else -> {
                val p0 = Providers.byId(providerId) ?: return Result.failure(NodeException("Unknown provider $providerId"))
                val key = secret(context, p0.keySecret)
                try {
                    OpenAiCompat.listModels(Providers.effective(context, p0), key)
                } catch (e: NodeException) {
                    return Result.failure(e)
                } catch (e: IllegalArgumentException) {
                    return Result.failure(NodeException("${p0.label} base URL: ${e.message}"))
                } catch (e: Exception) {
                    return Result.failure(NodeException("Fetch models failed: ${OpenAiCompat.clean(e.message ?: e.javaClass.simpleName, key)}"))
                }
            }
        }
        settings(context).edit().putString(KEY_MODELS + providerId, JsonArray(list.map { JsonPrimitive(it) }).toString()).apply()
        refresh(context)
        return Result.success(list)
    }

    /** "Say OK", maxTokens 32, 30 s; stamps settings["ai_verified_<id>"]; the message never contains the key. */
    suspend fun testProvider(context: Context, providerId: String, model: String?): Result<String> {
        val m = model?.trim()?.takeIf { it.isNotBlank() }
        var key: String? = null
        return try {
            val reply = when (providerId) {
                PROVIDER_CLAUDE -> {
                    key = key(context) ?: throw NodeException(ERR_NO_KEY)
                    val r = ClaudeClient.complete(key, LlmRequest(system = null, prompt = "Say OK", model = m ?: ClaudeClient.MODEL_OPUS, effort = "low", maxTokens = 32, timeoutMs = 30_000))
                    Usage.record(PROVIDER_CLAUDE, r.model, r.usage, "test", null)   // calls the client directly, so the Llm funnel is bypassed (V7)
                    settings(context).edit().putLong(KEY_VERIFIED_AT, System.currentTimeMillis()).apply()
                    r.text.trim().ifBlank { "(empty reply, stop_reason=${r.stopReason})" }
                }
                PROVIDER_NANO -> {
                    val st = NanoClient.status()
                    NanoClient.statusError(st)?.let { throw it }
                    "Gemini Nano is available on this device"
                }
                else -> {
                    val p0 = Providers.byId(providerId) ?: throw NodeException("Unknown provider $providerId")
                    val p = Providers.effective(context, p0)
                    key = secret(context, p.keySecret)
                    val mm = m ?: readDefaultAi(context)?.takeIf { it.provider == providerId }?.model?.ifBlank { null } ?: p.defaultModel
                    if (mm.isBlank()) throw NodeException("Pick a model for ${p.label} (Settings > AI > Fetch models)")
                    val r = withTimeout(30_000) { OpenAiCompat.complete(p, key, LlmRequest(null, "Say OK", model = mm, maxTokens = 32, timeoutMs = 30_000), null) {} }
                    Usage.record(providerId, r.model, r.usage, "test", null)
                    r.text.trim().ifBlank { "(connected; empty reply, finish=${r.stopReason})" }
                }
            }
            settings(context).edit().putLong(KEY_VERIFIED + providerId, System.currentTimeMillis()).apply()
            refresh(context)
            Result.success(reply)
        } catch (e: NodeException) {
            Result.failure(NodeException(OpenAiCompat.clean(e.message, key)))
        } catch (e: IllegalArgumentException) {
            Result.failure(NodeException("Base URL: ${e.message}"))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(NodeException("${providerLabel(providerId)} timed out after 30 s"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(NodeException("Test failed: ${OpenAiCompat.clean(e.message ?: e.javaClass.simpleName, key)}"))
        }
    }

    private fun mask(k: String?): String = when {
        k == null -> ""
        k.length >= 12 -> "••••••••" + k.takeLast(4)
        else -> "••••••••"
    }
}
