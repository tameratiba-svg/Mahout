package com.mob8n.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.NodeException
import com.mob8n.core.SECRETS_PREFS
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.asDouble
import com.mob8n.core.asTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** One typed question in Jev wire terms (DESIGN5 §3.1). name: ^[A-Za-z_][A-Za-z0-9_]{0,63}$, unique per request. */
sealed class S1Question(val name: String, val instructions: String) {
    abstract val type: String

    /** criteria = ordered label -> description (blank description -> the label itself, ponytail). */
    class Choice(name: String, instructions: String, val options: LinkedHashMap<String, String>) : S1Question(name, instructions) { override val type = "choice" }
    /** criteria = ordered level labels low -> high (2..10). */
    class Score(name: String, instructions: String, val levels: List<String>) : S1Question(name, instructions) { override val type = "score" }
    /** criteria optional {true: …, false: …} (omitted when both null). */
    class Noul(name: String, instructions: String, val trueDesc: String? = null, val falseDesc: String? = null) : S1Question(name, instructions) { override val type = "noul" }

    fun toJson(): JsonObject = buildJsonObject {
        put("type", type); put("instructions", instructions)
        when (val q = this@S1Question) {
            // ponytail: blank choice description = the label repeated (Jev needs a string map); upgrade = null once Laya accepts it
            is Choice -> put("criteria", JsonObject(q.options.entries.associate { (k, v) -> k to JsonPrimitive(v.ifBlank { k }) }))
            is Score -> put("criteria", JsonArray(q.levels.map(::JsonPrimitive)))
            is Noul -> if (q.trueDesc != null || q.falseDesc != null) put("criteria", buildJsonObject { q.trueDesc?.let { put("true", it) }; q.falseDesc?.let { put("false", it) } })
        }
    }
}

sealed class S1Answer(val name: String, val type: String, val confidence: Double?, val probabilities: Map<String, Double>) {
    class Choice(name: String, val choice: String, probabilities: Map<String, Double>, confidence: Double?) : S1Answer(name, "choice", confidence, probabilities)
    /** score = wire value (probability-weighted level, fractional); level = the ROUNDED expected level (real Laya capture, see parse); levelLabel from `legend`. */
    class Score(name: String, val score: Double, val level: Int, val levelLabel: String, probabilities: Map<String, Double>, confidence: Double?) : S1Answer(name, "score", confidence, probabilities)
    /** p = P(true) 0..1; value = p >= 0.5. */
    class Noul(name: String, val p: Double, val value: Boolean, confidence: Double?) : S1Answer(name, "noul", confidence, mapOf("true" to p, "false" to 1 - p))
}

data class S1Result(val engine: String, val model: String, val answers: Map<String, S1Answer>, val usage: TokenUsage?, val latencyMs: Long, val routing: JsonObject?, val raw: JsonObject) {
    /** Laya's auto-routed checkpoint ("english" | "multilingual" | …) else the top-level model. */
    val displayModel: String get() = routing?.get("model").asTextOrNull() ?: model
}

data class S1Target(val engine: String /* jev|laya */, val url: String /* full POST URL */, val key: String?, val model: String? /* "jev-latest" | null */) {
    val label: String get() = if (engine == SystemOne.ENGINE_JEV) "Jev" else "Laya"
}

/**
 * The ONE System 1 client (DESIGN5 D1, §4): Jev (cloud) and Laya (laya-serve on the LAN) speak the same /v1/systemone wire protocol.
 * java.net.HttpURLConnection + kotlinx JSON; one retry; honest errors through OpenAiCompat.clean (the key never leaves this object).
 */
// ponytail: no S1 fallback chain; upgrade = device → laya → jev when §7 lands
object SystemOne {
    const val ENGINE_DEFAULT = "default"; const val ENGINE_JEV = "jev"; const val ENGINE_LAYA = "laya"; const val ENGINE_NONE = "none"
    val ENGINES = listOf(ENGINE_DEFAULT, ENGINE_JEV, ENGINE_LAYA)
    const val JEV_URL = "https://api.typesafe.ai/v1/systemone"; const val JEV_MODEL = "jev-latest"; const val LAYA_PATH = "/v1/systemone"
    const val SECRET_JEV_KEY = "jev_api_key"; const val SECRET_LAYA_KEY = "laya_api_key"
    const val DEFAULT_TIMEOUT_MS = 5_000L; const val CONNECT_MS = 3_000; const val MAX_TIMEOUT_MS = 30_000L; const val MAX_BODY = 1 shl 20
    const val MAX_STATE_CHARS = 50_000; const val MAX_QUESTIONS = 64; const val MAX_OPTIONS = 100; val LEVELS = 2..10
    const val ERR_NOT_CONFIGURED = "No decision engine configured — Settings > AI > Decision engine"
    const val ERR_NO_JEV_KEY = "Jev API key not set — Settings > AI > Decision engine"
    const val ERR_NO_LAYA_URL = "Laya URL not set — Settings > AI > Decision engine (e.g. http://192.168.1.5:8000)"
    private const val RETRY_CAP_MS = 2_000L
    private const val RETRY_MS = 1_000L
    private val NAME_RE = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    // ---------------------------------------------------------------- pure (JVM-tested)

    /** null = ok; else the first problem (strictest of Jev and laya-serve limits). */
    fun validate(questions: List<S1Question>): String? {
        if (questions.isEmpty()) return "add at least one question"
        if (questions.size > MAX_QUESTIONS) return "at most $MAX_QUESTIONS questions per call"
        val seen = HashSet<String>()
        for (q in questions) {
            if (!NAME_RE.matches(q.name)) return "question name '${q.name}' must be a letter or _ followed by letters, digits or _ (≤ 64)"
            if (!seen.add(q.name)) return "duplicate question name '${q.name}'"
            if (q.instructions.isBlank()) return "question ${q.name}: the question text is required"
            when (q) {
                is S1Question.Choice -> {
                    if (q.options.size !in 2..MAX_OPTIONS) return "question ${q.name}: choice needs 2–$MAX_OPTIONS options (has ${q.options.size})"
                    if (q.options.keys.any { it.isBlank() }) return "question ${q.name}: blank option label"
                }
                is S1Question.Score -> {
                    if (q.levels.size !in LEVELS) return "question ${q.name}: score needs ${LEVELS.first}–${LEVELS.last} levels (has ${q.levels.size})"
                    if (q.levels.any { it.isBlank() }) return "question ${q.name}: blank level"
                }
                is S1Question.Noul -> {}
            }
        }
        return null
    }

    /** {"state", "model"?, "questions": {name: {type, instructions, criteria}}}; model omitted when null (laya auto-routes). */
    fun request(state: JsonElement, questions: List<S1Question>, model: String?): JsonObject = buildJsonObject {
        put("state", state)
        model?.let { put("model", it) }
        put("questions", JsonObject(LinkedHashMap<String, JsonElement>().apply { questions.forEach { put(it.name, it.toJson()) } }))
    }

    /** JSON object/array when the text parses as one, else the text; > MAX_STATE_CHARS -> truncated text + logged. */
    fun stateOf(rendered: String): JsonElement {
        val s = rendered.trim()
        if (s.length > MAX_STATE_CHARS) {
            runCatching { Log.w(LOG_TAG, "S1 state ${s.length} chars truncated to $MAX_STATE_CHARS") }
            return JsonPrimitive(s.take(MAX_STATE_CHARS))
        }
        if (s.startsWith("{") || s.startsWith("[")) runCatching { JSON.parseToJsonElement(s) }.getOrNull()?.let { if (it is JsonObject || it is JsonArray) return it }
        return JsonPrimitive(s)
    }

    private fun label(engine: String) = if (engine == ENGINE_JEV) "Jev" else "Laya"

    /** probabilities (or the `distribution` alias) as an object keyed by label/index, or an array in option order. */
    private fun probs(a: JsonObject, keys: List<String>): Map<String, Double> = when (val p = a["probabilities"] ?: a["distribution"]) {
        is JsonObject -> LinkedHashMap<String, Double>().apply { p.forEach { (k, v) -> v.asDouble()?.let { put(k, it) } } }
        is JsonArray -> LinkedHashMap<String, Double>().apply { p.forEachIndexed { i, v -> val d = v.asDouble(); if (d != null && i < keys.size) put(keys[i], d) } }
        else -> emptyMap()
    }

    /**
     * Tolerant parser (§4.3, binding shape = the real laya-serve 0.3.20 capture in resources/s1): answers by requested name; choice = `choice`
     * (else argmax); score = wire `score` (expected level, e.g. 1.4035) -> level = round(score) clamped, label = legend[level] ?: levels[level];
     * noul = P(true); `answer_confidence` / `action` ignored; model = body.model ?: routing.model ?: the engine default.
     */
    fun parse(body: JsonObject, questions: List<S1Question>, engine: String, latencyMs: Long): S1Result {
        val l = label(engine)
        val answers = body["answers"] as? JsonObject ?: throw NodeException("$l returned no answers")
        val out = LinkedHashMap<String, S1Answer>()
        for (q in questions) {
            val a = answers[q.name] as? JsonObject
                ?: throw NodeException("$l answered ${questions.count { answers[it.name] is JsonObject }} of ${questions.size} questions — no answer for '${q.name}'")
            val conf = a["confidence"].asDouble()
            out[q.name] = when (q) {
                is S1Question.Choice -> {
                    val p = probs(a, q.options.keys.toList())
                    val choice = a["choice"].asTextOrNull() ?: p.maxByOrNull { it.value }?.key ?: throw NodeException("$l returned no choice for '${q.name}'")
                    S1Answer.Choice(q.name, choice, p, conf)
                }
                is S1Question.Score -> {
                    val p = probs(a, q.levels.indices.map { it.toString() })
                    val score = a["score"].asDouble() ?: p.entries.sumOf { (k, v) -> (k.toIntOrNull() ?: 0) * v }.takeIf { p.isNotEmpty() }
                        ?: throw NodeException("$l returned no score for '${q.name}'")
                    val level = score.roundToInt().coerceIn(0, q.levels.size - 1)
                    val legend = (a["legend"] as? JsonObject)?.get(level.toString()).asTextOrNull()
                    S1Answer.Score(q.name, score, level, legend ?: q.levels[level], p, conf)
                }
                is S1Question.Noul -> {
                    val p = a["noul"].asDouble() ?: probs(a, listOf("false", "true"))["true"] ?: throw NodeException("$l returned no noul for '${q.name}'")
                    S1Answer.Noul(q.name, p, p >= 0.5, conf)
                }
            }
        }
        val routing = body["routing"] as? JsonObject
        val model = body["model"].asTextOrNull() ?: routing?.get("model").asTextOrNull() ?: if (engine == ENGINE_JEV) JEV_MODEL else ENGINE_LAYA
        val usage = (body["usage"] as? JsonObject)?.let { u -> u["input_tokens"].asDouble()?.let { TokenUsage(it.toLong(), u["output_tokens"].asDouble()?.toLong() ?: 0) } }
        return S1Result(engine, model, out, usage, latencyMs, routing, body)
    }

    private fun hostPort(url: String): String = runCatching { URL(url).let { if (it.port > 0) "${it.host}:${it.port}" else it.host } }.getOrDefault(url)

    /** §4.4 table; ≤ 300 chars; always through clean(). */
    fun errorMessage(t: S1Target, status: Int, body: String?): String {
        val msg = clean(OpenAiCompat.bodyMessage(body), t.key)
        val jev = t.engine == ENGINE_JEV
        val s = when (status) {
            400 -> if (jev) "Jev rejected the request: $msg" else "Laya rejected the request: $msg"
            401, 403 -> if (jev) "Invalid Jev API key (Settings > AI > Decision engine)" else "Laya refused the token — the server has LAYA_API_KEY set; paste it under Decision engine"
            404 -> if (jev) "Jev endpoint not found (client out of date?)" else "No /v1/systemone at ${hostPort(t.url)} — is this laya-serve?"
            413 -> if (jev) "Jev: request too large: $msg" else "Laya: too large — ≤ 64 questions, 50 000 state chars, 100 options, 32 levels, 2 MB body"
            422 -> if (jev) "Jev rejected the questions: $msg" else "Laya rejected the questions: $msg"
            429 -> if (jev) "Jev: rate limited, retry later" else "Laya: rate limited"
            500 -> if (jev) "Jev error 500" else "Laya inference failed (500) — check laya-serve logs"
            503, 529 -> if (jev) "Jev is overloaded ($status), retry later" else "Laya is busy ($status, LAYA_MAX_CONCURRENT reached)"
            else -> "${t.label} error $status: $msg"
        }
        return clean(s, t.key)
    }

    fun networkMessage(t: S1Target): String =
        if (t.engine == ENGINE_JEV) "Cannot reach Jev — check network"
        else "Cannot reach Laya at ${hostPort(t.url)} — is laya-serve running on this Wi-Fi? (LAYA_HOST=0.0.0.0)"

    fun clean(msg: String?, key: String?): String = OpenAiCompat.clean(msg, key)

    /** §5.1 grammar. choice: `label: description` / `label` lines; score: comma or line separated low→high; noul: optional `true: …` / `false: …` lines. */
    fun parseCriteria(type: String, text: String, name: String, instructions: String, who: String = "AI Decide"): S1Question {
        fun bad(why: String): Nothing = throw NodeException("$who: question $name: $why")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when (type) {
            "choice" -> {
                val opts = LinkedHashMap<String, String>()
                for (ln in lines) {
                    val label = ln.substringBefore(':').trim()
                    if (label.isEmpty()) bad("blank option label in '$ln'")
                    if (opts.put(label, if (ln.contains(':')) ln.substringAfter(':').trim() else "") != null) bad("duplicate option '$label'")
                }
                if (opts.size !in 2..MAX_OPTIONS) bad("choice needs 2–$MAX_OPTIONS options, one 'label: description' per line (has ${opts.size})")
                S1Question.Choice(name, instructions, opts)
            }
            "score" -> {
                val levels = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (levels.size !in LEVELS) bad("score needs ${LEVELS.first}–${LEVELS.last} levels low→high, comma separated (has ${levels.size})")
                S1Question.Score(name, instructions, levels)
            }
            "noul" -> {
                var t: String? = null; var f: String? = null
                for (ln in lines) {
                    val k = ln.substringBefore(':').trim().lowercase()
                    val v = ln.substringAfter(':', "").trim()
                    when {
                        !ln.contains(':') -> bad("noul criteria lines are 'true: …' or 'false: …'")
                        k == "true" -> t = v
                        k == "false" -> f = v
                        else -> bad("noul criteria lines are 'true: …' or 'false: …' (got '$k')")
                    }
                }
                S1Question.Noul(name, instructions, t, f)
            }
            else -> bad("type must be choice, score or noul")
        }
    }

    /** ROWS {name, type, instructions, criteria} -> questions; row-numbered NodeException. */
    fun questionsFromRows(rows: List<JsonObject>, who: String = "AI Decide"): List<S1Question> = rows.mapIndexed { i, r ->
        val name = r["name"].asTextOrNull()?.trim().orEmpty()
        if (name.isEmpty()) throw NodeException("$who: question row ${i + 1}: name is required")
        val instructions = r["instructions"].asTextOrNull()?.trim().orEmpty()
        if (instructions.isEmpty()) throw NodeException("$who: question $name: the question text is required")
        val criteria = when (val c = r["criteria"]) { is JsonArray -> c.mapNotNull { it.asTextOrNull() }.joinToString("\n"); else -> c.asTextOrNull().orEmpty() }
        parseCriteria(r["type"].asTextOrNull()?.trim()?.ifBlank { null } ?: "choice", criteria, name, instructions, who)
    }

    /** §4.5: default -> the Settings engine; none -> ERR_NOT_CONFIGURED; the chosen engine needs its key / URL. Never falls back (D7). */
    fun resolveTarget(engineParam: String?, defaultEngine: String, jevKey: String?, layaUrl: String?, layaKey: String?): S1Target {
        val e = engineParam?.trim()?.ifBlank { null }?.takeUnless { it == ENGINE_DEFAULT } ?: defaultEngine
        return when (e) {
            ENGINE_JEV -> S1Target(ENGINE_JEV, JEV_URL, jevKey?.takeIf { it.isNotBlank() } ?: throw NodeException(ERR_NO_JEV_KEY), JEV_MODEL)
            ENGINE_LAYA -> S1Target(ENGINE_LAYA, (layaUrl?.takeIf { it.isNotBlank() } ?: throw NodeException(ERR_NO_LAYA_URL)).trimEnd('/') + LAYA_PATH, layaKey?.takeIf { it.isNotBlank() }, null)
            else -> throw NodeException(ERR_NOT_CONFIGURED)
        }
    }

    // ---------------------------------------------------------------- Android

    fun target(android: Context, engineParam: String?): S1Target =
        resolveTarget(engineParam, S1Prefs.readDefault(android), S1Prefs.secret(android, SECRET_JEV_KEY), S1Prefs.readLayaUrl(android), S1Prefs.secret(android, SECRET_LAYA_KEY))

    /** One validated call; usage recorded under provider = engine (source node|chat|triage|test). Throws NodeException with the §4.4 text. */
    suspend fun decide(
        t: S1Target, state: JsonElement, questions: List<S1Question>, timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        source: String, ref: String?, log: (String) -> Unit = {},
    ): S1Result {
        validate(questions)?.let { throw NodeException("${t.label}: $it") }
        val body = JSON.encodeToString(JsonObject.serializer(), request(state, questions, t.model))
        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS)
        val t0 = System.nanoTime()
        val (status, text) = exchange(t, body, timeout)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val obj = if (status in 200..299) runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() else null
        val routing = (obj?.get("routing") as? JsonObject)?.get("model").asTextOrNull()
        val line = "S1 ${t.engine} POST ${hostPort(t.url)} q=${questions.size} state=${state.toString().length}c -> $status $ms ms" + (routing?.let { " [routing=$it]" } ?: "")
        runCatching { Log.i(LOG_TAG, line) }; log(line)   // never the state, never the key
        if (status !in 200..299) throw NodeException(errorMessage(t, status, text))
        val r = parse(obj ?: throw NodeException("${t.label} returned a non-JSON reply"), questions, t.engine, ms)
        Usage.record(t.engine, r.model, r.usage, source, ref)
        return r
    }

    /** Connect phase failed (TCP never established; F2): an IOException, so it gets the one retry; reported by [connectMessage]. */
    internal class ConnectFailed(cause: IOException, val connectMs: Int = CONNECT_MS) : IOException(cause)

    /**
     * Pure: attempt 1 connects within a third of the call budget (0.75–3 s) so the immediate retry still fits — SecondOpinion's 3 s
     * withTimeoutOrNull would otherwise be spent on one stalled connect; attempt 2 gets the full [CONNECT_MS].
     */
    fun connectMs(attempt: Int, timeoutMs: Long): Int = if (attempt == 1) minOf(CONNECT_MS.toLong(), timeoutMs / 3).toInt().coerceAtLeast(750) else CONNECT_MS

    /**
     * "Laya not reachable at 10.0.0.61:8000 (connect timed out after 3 s)"; a refused / unresolvable host -> [networkMessage].
     * https: connect() also runs the TLS handshake, which readTimeout bounds, so the wait is not named.
     */
    fun connectMessage(t: S1Target, cause: Throwable?, connectMs: Int = CONNECT_MS): String = when {
        cause !is SocketTimeoutException -> networkMessage(t)
        t.url.startsWith("https", ignoreCase = true) -> "${t.label} not reachable at ${hostPort(t.url)} (connect or TLS handshake timed out)"
        else -> "${t.label} not reachable at ${hostPort(t.url)} (connect timed out after ${(connectMs + 999) / 1000} s)"
    }

    /**
     * Exactly one retry on IOException (connect phase: immediately, with a budget-sized first connect, see [connectMs]) / 429 / 503 / 529
     * after Retry-After capped at 2 s (else 1 s); other 4xx never retried; a READ timeout is not retried ("timed out after N s"); a TLS
     * failure is not retried and is named. `send(target, body, readMs, connectMs)` is the transport seam (JVM tests pass a fake).
     */
    internal suspend fun exchange(
        t: S1Target, body: String, timeoutMs: Long,
        send: suspend (S1Target, String, Long, Int) -> Response = { a, b, c, d -> raw(a, b, c, d) },
    ): Pair<Int, String> {
        var attempt = 0
        while (true) {
            attempt++
            val r = try {
                send(t, body, timeoutMs, connectMs(attempt, timeoutMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectFailed) {
                if (attempt == 1) continue
                throw NodeException(connectMessage(t, e.cause, e.connectMs), e)
            } catch (e: javax.net.ssl.SSLException) {
                throw NodeException("${t.label}: TLS handshake failed (${e.javaClass.simpleName})", e)
            } catch (e: SocketTimeoutException) {
                throw NodeException("${t.label} timed out after ${(timeoutMs + 999) / 1000} s", e)
            } catch (e: IOException) {
                if (attempt == 1) { delay(RETRY_MS); continue }
                throw NodeException(networkMessage(t), e)
            }
            if (attempt == 1 && (r.status == 429 || r.status == 503 || r.status == 529)) { delay(r.retryAfterMs ?: RETRY_MS); continue }
            return r.status to r.body
        }
    }

    internal class Response(val status: Int, val body: String, val retryAfterMs: Long?)

    /**
     * Copy of OpenAiCompat.raw (its retry loop is chat-shaped): connectMs connect (explicit, so its failure is [ConnectFailed]), read = timeoutMs,
     * fixed-length body, 1 MB cap. Keep-alive (F1): a fully read response returns the socket to the platform pool, so disconnect() only on
     * failure / cancellation — the next call to the same host skips the TCP (+ Wi-Fi wake) handshake while laya-serve keeps it open.
     */
    private suspend fun raw(t: S1Target, body: String, timeoutMs: Long, connectMs: Int): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val conn = URL(t.url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            try {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.requestMethod = "POST"
                conn.connectTimeout = connectMs
                conn.readTimeout = timeoutMs.toInt()
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                if (!t.key.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${t.key}")   // never logged
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                try { conn.connect() } catch (e: IOException) {   // TCP-phase failures only; an SSLException propagates as itself
                    if (e is SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException ||
                        e is java.net.NoRouteToHostException) throw ConnectFailed(e, connectMs) else throw e
                }
                conn.outputStream.use { it.write(bytes) }
                val status = conn.responseCode
                val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { readCapped(it) } ?: ""
                cont.resume(Response(status, text, retryAfterMs(conn.getHeaderField("Retry-After"))))
            } catch (e: Throwable) {
                runCatching { conn.disconnect() }
                cont.resumeWithException(e)
            }
        }
    }

    /** Pure: Retry-After seconds -> ms capped at 2 s; absent/junk -> null (the 1 s default applies). */
    fun retryAfterMs(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).coerceAtMost(RETRY_CAP_MS) }

    private fun readCapped(s: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf); if (n < 0) break
            if (out.size() + n > MAX_BODY) { out.write(buf, 0, MAX_BODY - out.size()); break }
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** "dept=billing (0.93)" style summary of the first answer (Test line). */
    internal fun summary(a: S1Answer): String {
        val v = when (a) { is S1Answer.Choice -> a.choice; is S1Answer.Score -> a.levelLabel; is S1Answer.Noul -> a.value.toString() }
        return "${a.name}=$v" + (a.confidence?.let { " (" + String.format(Locale.US, "%.2f", it) + ")" } ?: "")
    }
}

/** Settings + secrets for the decision engine (§3.1); AiPrefs/McpPrefs idiom (StateFlows + sync reads). */
object S1Prefs {
    const val KEY_DEFAULT = "s1_default_engine"
    const val KEY_LAYA_URL = "s1_laya_url"
    const val KEY_VERIFIED = "s1_verified_"
    const val KEY_LAST_MODEL = "s1_last_model_"
    const val KEY_SECOND_OPINION = "s1_second_opinion"
    /** Test probe: first calls on a cold laya-serve load the checkpoint (~19 s measured on the Mac), so Test gets the max timeout, not 5 s. */
    const val TEST_TIMEOUT_MS = SystemOne.MAX_TIMEOUT_MS
    private val ENGINES = listOf(SystemOne.ENGINE_JEV, SystemOne.ENGINE_LAYA)

    private val _defaultEngine = MutableStateFlow(SystemOne.ENGINE_NONE)
    private val _layaUrl = MutableStateFlow("")
    private val _jevHasKey = MutableStateFlow(false)
    private val _jevMaskedKey = MutableStateFlow("")
    private val _layaHasKey = MutableStateFlow(false)
    private val _verifiedAt = MutableStateFlow<Map<String, Long?>>(emptyMap())
    private val _lastModel = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _secondOpinion = MutableStateFlow(false)
    val defaultEngine: StateFlow<String> = _defaultEngine.asStateFlow()
    val layaUrl: StateFlow<String> = _layaUrl.asStateFlow()
    val jevHasKey: StateFlow<Boolean> = _jevHasKey.asStateFlow()
    val jevMaskedKey: StateFlow<String> = _jevMaskedKey.asStateFlow()
    val layaHasKey: StateFlow<Boolean> = _layaHasKey.asStateFlow()
    val verifiedAt: StateFlow<Map<String, Long?>> = _verifiedAt.asStateFlow()
    val lastModel: StateFlow<Map<String, String>> = _lastModel.asStateFlow()
    val secondOpinion: StateFlow<Boolean> = _secondOpinion.asStateFlow()

    private fun settings(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun secrets(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)
    /** Secret value or null; never logged. */
    fun secret(c: Context, name: String): String? = secrets(c).getString(name, null)?.takeIf { it.isNotBlank() }

    fun load(ctx: Context) {
        val s = settings(ctx)
        _defaultEngine.value = readDefault(ctx)
        _layaUrl.value = readLayaUrl(ctx).orEmpty()
        val jk = secret(ctx, SystemOne.SECRET_JEV_KEY)
        _jevHasKey.value = jk != null
        _jevMaskedKey.value = when { jk == null -> ""; jk.length >= 12 -> "••••••••" + jk.takeLast(4); else -> "••••••••" }
        _layaHasKey.value = secret(ctx, SystemOne.SECRET_LAYA_KEY) != null
        _verifiedAt.value = ENGINES.associateWith { e -> s.getLong(KEY_VERIFIED + e, 0L).takeIf { it > 0 } }
        _lastModel.value = ENGINES.mapNotNull { e -> s.getString(KEY_LAST_MODEL + e, null)?.let { e to it } }.toMap()
        _secondOpinion.value = readSecondOpinion(ctx)
    }

    fun readDefault(ctx: Context): String = runCatching { settings(ctx).getString(KEY_DEFAULT, null) }.getOrNull()?.takeIf { it in ENGINES } ?: SystemOne.ENGINE_NONE
    fun readLayaUrl(ctx: Context): String? = runCatching { settings(ctx).getString(KEY_LAYA_URL, null) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun readSecondOpinion(ctx: Context): Boolean = runCatching { settings(ctx).getBoolean(KEY_SECOND_OPINION, false) }.getOrDefault(false)

    /** default != none && that engine has its key / URL. */
    fun isConfigured(ctx: Context): Boolean = when (readDefault(ctx)) {
        SystemOne.ENGINE_JEV -> secret(ctx, SystemOne.SECRET_JEV_KEY) != null
        SystemOne.ENGINE_LAYA -> readLayaUrl(ctx) != null
        else -> false
    }

    /** Pure: normalised base URL (LAN rule for http) with a pasted trailing /v1/systemone stripped. IllegalArgumentException text shown by ui. */
    fun normalizeLayaUrl(raw: String): String = Providers.normalizeBaseUrl(raw).removeSuffix(SystemOne.LAYA_PATH).trimEnd('/').let { Providers.normalizeBaseUrl(it) }

    /** null/blank removes; the first configured engine becomes the default. */
    fun setJevKey(ctx: Context, key: String?) {
        val k = key?.trim()?.ifBlank { null }
        secrets(ctx).edit().apply { if (k == null) remove(SystemOne.SECRET_JEV_KEY) else putString(SystemOne.SECRET_JEV_KEY, k) }.apply()
        if (k != null && readDefault(ctx) == SystemOne.ENGINE_NONE) settings(ctx).edit().putString(KEY_DEFAULT, SystemOne.ENGINE_JEV).apply()
        load(ctx)
    }

    fun setLayaUrl(ctx: Context, url: String?) {
        val u = url?.trim()?.ifBlank { null }?.let(::normalizeLayaUrl)
        settings(ctx).edit().apply { if (u == null) remove(KEY_LAYA_URL) else putString(KEY_LAYA_URL, u) }.apply()
        if (u != null && readDefault(ctx) == SystemOne.ENGINE_NONE) settings(ctx).edit().putString(KEY_DEFAULT, SystemOne.ENGINE_LAYA).apply()
        load(ctx)
    }

    fun setLayaKey(ctx: Context, key: String?) {
        val k = key?.trim()?.ifBlank { null }
        secrets(ctx).edit().apply { if (k == null) remove(SystemOne.SECRET_LAYA_KEY) else putString(SystemOne.SECRET_LAYA_KEY, k) }.apply()
        load(ctx)
    }

    fun setDefault(ctx: Context, engine: String) {
        require(engine == SystemOne.ENGINE_NONE || engine in ENGINES) { "Unknown decision engine '$engine'" }
        settings(ctx).edit().putString(KEY_DEFAULT, engine).apply()
        load(ctx)
    }

    fun setSecondOpinion(ctx: Context, on: Boolean) { settings(ctx).edit().putBoolean(KEY_SECOND_OPINION, on).apply(); _secondOpinion.value = on }

    /** Fixed probe (source "test"): "OK: dept=billing (0.93) · 187 ms · jev-latest" | "OK: dept=billing (0.99) · 112 ms · routing english". Stamps verified/lastModel. */
    suspend fun test(ctx: Context, engine: String): Result<String> {
        val t = try { SystemOne.target(ctx, engine) } catch (e: NodeException) { return Result.failure(e) }
        val q = S1Question.Choice("dept", "Which team should handle this message?", linkedMapOf("billing" to "refunds, invoices, payments", "tech" to "bugs, outages, errors"))
        return try {
            val r = SystemOne.decide(t, JsonPrimitive("Billed twice, please refund or we cancel"), listOf(q), TEST_TIMEOUT_MS, "test", null)
            val model = if (r.routing != null) "routing ${r.displayModel}" else r.model
            settings(ctx).edit().putLong(KEY_VERIFIED + t.engine, System.currentTimeMillis()).putString(KEY_LAST_MODEL + t.engine, r.displayModel).apply()
            load(ctx)
            Result.success("OK: ${SystemOne.summary(r.answers.getValue("dept"))} · ${r.latencyMs} ms · $model")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(NodeException(SystemOne.clean(e.message ?: e.javaClass.simpleName, t.key)))
        }
    }
}
