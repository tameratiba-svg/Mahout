package com.mob8n.apps

import android.content.Context
import android.util.Log
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mob8n.Mob8NApp
import com.mob8n.core.EMPTY
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Item
import com.mob8n.core.Items
import com.mob8n.core.JSON
import com.mob8n.core.LOG_TAG
import com.mob8n.core.NodeException
import com.mob8n.core.asText
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.Engine
import com.mob8n.engine.knowledge.Hit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/**
 * Host services a script may reach (DESIGN4 §7.2). Built per run by the caller; every lambda runs on the WebView's JavaBridge thread
 * inside runBlocking(withTimeout). `allowNodes` is the whole contract: mob8n.runNode refuses every id not listed (READ-class included).
 */
class JsBridge(
    val allowNodes: Set<String>,
    val runNode: suspend (id: String, params: JsonObject) -> Items,
    val knowledgeSearch: suspend (query: String, k: Int) -> List<Hit>,
    val getVar: suspend (String) -> JsonElement?,
    val setVar: suspend (String, JsonElement?) -> Unit,
    val workspace: File,
    val log: (String) -> Unit,
) {
    companion object {
        /** logic.js: ctx.runNode enforces gates + per-node timeouts; knowledge search over every source. */
        fun forNode(ctx: ExecutionContext, allow: Set<String>): JsBridge {
            val android = ctx.requireAndroid()
            return JsBridge(
                allowNodes = allow,
                runNode = { id, p -> ctx.runNode(id, p, ctx.item) },
                knowledgeSearch = { q, k -> Mob8NApp.of(android).engine.knowledge.search(q, k) },
                getVar = { ctx.getVar(it) }, setVar = { k, v -> ctx.setVar(k, v) },
                workspace = Workspace.root(android), log = { ctx.log(it) },
            )
        }

        /** Chat run_js: engine.runNode (no run row), knowledge limited to the conversation's sources. */
        fun forChat(engine: Engine, allow: Set<String>, workspace: File, knowledgeIds: List<String>, log: (String) -> Unit): JsBridge = JsBridge(
            allowNodes = allow,
            runNode = { id, p -> engine.runNode(id, p, EMPTY, "js") },
            knowledgeSearch = { q, k -> engine.knowledge.search(q, k, knowledgeIds) },
            getVar = { engine.persistence.getVariable(it) }, setVar = { k, v -> engine.persistence.setVariable(k, v) },
            workspace = workspace, log = log,
        )
    }
}

/**
 * One hidden platform WebView used as a JavaScript engine (V10). Serial under a Mutex; created and driven on the main thread with the
 * Application context (headless); network blocked three ways (blockNetworkLoads, shouldInterceptRequest 403, globals deleted in the
 * wrapper); user code is constructed with `new AsyncFunction(...)` so a SyntaxError is a caught `fail`; destroyed on timeout and after
 * 2 min idle. No WebView provider -> status "unavailable" and every run throws a clear NodeException.
 * // ponytail: single serial JS runtime; upgrade = pool
 * // ponytail: renderer terminated + WebView destroyed on timeout (a hot loop cannot be interrupted; Chromium's single renderer would stay busy after destroy() alone — device phase); upgrade = separate process
 */
object JsRuntime {
    const val MAX_CODE = 256 * 1024
    const val MAX_INPUT = 512 * 1024
    const val MAX_RESULT = 1024 * 1024
    const val MAX_LOG_LINES = 200
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val BRIDGE_CALL_MS = 60_000L
    const val IDLE_DESTROY_MS = 120_000L
    private const val NODE_RESULT_CAP = 64 * 1024

    class Run(val value: JsonElement, val logs: List<String>, val ms: Long)

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> get() = _status

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var web: WebView? = null
    private var ready: CompletableDeferred<Unit> = CompletableDeferred()
    /** (callId, bridge, done) of the run in flight; bridge calls with another id are stale. */
    @Volatile private var current: Triple<String, JsBridge, CompletableDeferred<String>>? = null
    private var idleJob: Job? = null
    private var version: String = ""

    /** Serial; throws NodeException on script error, timeout or a missing WebView. */
    suspend fun run(app: Context, code: String, input: JsonObject, timeoutMs: Long, bridge: JsBridge): Run {
        if (code.length > MAX_CODE) throw NodeException("Script too long (${code.length} chars > $MAX_CODE)")
        val inputJson = JSON.encodeToString(JsonElement.serializer(), input)
        if (inputJson.length > MAX_INPUT) throw NodeException("Script input too large (${inputJson.length} chars > $MAX_INPUT)")
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        mutex.withLock {
            idleJob?.cancel()
            val t0 = System.currentTimeMillis()
            try {
                val callId = UUID.randomUUID().toString()
                val done = CompletableDeferred<String>()
                // Creation + page load sit inside the timeout too: a renderer left busy by an earlier script never fires onPageFinished (device phase).
                val payload = withTimeoutOrNull(timeout) {
                    ensureWebView(app)
                    ready.await()
                    current = Triple(callId, bridge, done)
                    _status.value = "running"
                    val js = wrapper(callId, code, inputJson)
                    withContext(Dispatchers.Main) { web?.evaluateJavascript(js, null) ?: throw NodeException(NO_WEBVIEW) }
                    done.await()
                } ?: run {
                    destroyNow(killRenderer = true)
                    throw NodeException("JavaScript timed out after ${timeout / 1000} s")
                }
                val (value, logs) = parseDone(payload).getOrThrow()
                return Run(value, logs, System.currentTimeMillis() - t0)
            } finally {
                current = null
                if (web != null) {
                    _status.value = "WebView $version idle"
                    idleJob = scope.launch { delay(IDLE_DESTROY_MS); release() }
                }
            }
        }
    }

    /** Destroy the WebView (onTrimMemory, idle timer). A run in flight keeps its WebView; its own completion re-arms the idle timer. */
    fun release() {
        if (mutex.isLocked) return
        scope.launch { destroyNow(); _status.value = "idle (released)" }
    }

    fun statusLine(app: Context): String = try {
        WebView.getCurrentWebViewPackage()?.versionName?.let { "WebView $it" } ?: NO_PROVIDER
    } catch (_: Throwable) { NO_PROVIDER }

    private const val NO_PROVIDER = "No WebView provider — JavaScript unavailable"
    private const val NO_WEBVIEW = "No WebView on this device — JavaScript is unavailable"

    /** [killRenderer]: a script stuck in a hot loop blocks the (app-wide, shared) Chromium renderer; destroy() alone leaves it spinning and the next WebView never loads. API 29+ can terminate it. */
    private suspend fun destroyNow(killRenderer: Boolean = false) {
        val w = web ?: return
        web = null
        ready = CompletableDeferred()
        withContext(Dispatchers.Main) {
            if (killRenderer && Build.VERSION.SDK_INT >= 29) runCatching { w.webViewRenderProcess?.terminate() }
            runCatching { w.stopLoading(); w.destroy() }
        }
        _status.value = "idle (released)"
    }

    private suspend fun ensureWebView(app: Context) {
        if (web != null) return
        val readyNow = CompletableDeferred<Unit>()
        ready = readyNow
        try {
            web = withContext(Dispatchers.Main) { create(app.applicationContext, readyNow) }
            version = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "?"
        } catch (e: Throwable) {
            _status.value = "unavailable: ${e.message ?: e.javaClass.simpleName}"
            Log.w(LOG_TAG, "js: WebView unavailable: ${e.message}")
            throw NodeException(NO_WEBVIEW)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun create(app: Context, readyNow: CompletableDeferred<Unit>): WebView = WebView(app).apply {
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true                                            // layer 1
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.domStorageEnabled = false; settings.databaseEnabled = false
        settings.javaScriptCanOpenWindowsAutomatically = false; settings.setGeolocationEnabled(false)
        settings.cacheMode = WebSettings.LOAD_NO_CACHE; settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) =                                       // layer 2
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Mahout", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
            override fun onPageFinished(v: WebView, url: String?) { readyNow.complete(Unit) }
            /** Renderer killed (our timeout) or crashed: returning false would kill the app process. Drop this WebView once no run holds it; the next run creates a fresh one. */
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                scope.launch { mutex.withLock { if (web === v) destroyNow() } }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(m: ConsoleMessage) = true }   // captured in JS instead
        addJavascriptInterface(Host(), "__mob8n")
        loadDataWithBaseURL(null, "<!doctype html><html><head><meta charset=utf-8></head><body></body></html>", "text/html", "utf-8", null)
    }

    /** Exactly two bridge methods; both run on the WebView's JavaBridge thread (never main), so runBlocking cannot deadlock the UI. */
    private class Host {
        @JavascriptInterface fun call(callId: String, name: String, argsJson: String): String {
            val cur = current
            if (cur == null || cur.first != callId) return bridgeResult(Result.failure(NodeException("stale run")))
            val r = runCatching {
                val args = (JSON.parseToJsonElement(argsJson) as? JsonArray) ?: JsonArray(emptyList())
                runBlocking { withTimeout(BRIDGE_CALL_MS) { dispatch(cur.second, name, args) } }
            }
            return bridgeResult(r)
        }

        @JavascriptInterface fun done(callId: String, resultJson: String) {
            val cur = current ?: return
            if (cur.first == callId) cur.third.complete(resultJson)
        }
    }

    private fun JsonArray.str(i: Int): String = getOrNull(i).asTextOrNull() ?: ""
    private fun JsonArray.obj(i: Int): JsonObject = getOrNull(i) as? JsonObject ?: EMPTY

    private suspend fun dispatch(b: JsBridge, name: String, a: JsonArray): JsonElement = when (name) {
        "runNode" -> {
            val id = a.str(0)
            if (id !in b.allowNodes) throw NodeException("node $id is not in this script's allowNodes — ask the user to allow it")
            val items = b.runNode(id, a.obj(1))
            JsonArray(items).also { if (JSON.encodeToString(JsonElement.serializer(), it).length > NODE_RESULT_CAP) throw NodeException("$id returned more than 64 KB; narrow the request") }
        }
        "http" -> {
            if ("data.http" !in b.allowNodes) throw NodeException("network is not allowed for this script (allowNetwork / data.http)")
            val body = a.getOrNull(3)?.takeUnless { it is JsonNull }?.asText()
            val headers = JsonArray((a.getOrNull(2) as? JsonObject ?: EMPTY).map { (k, v) -> buildJsonObject { put("name", k); put("value", v.asText()) } })
            val params = buildJsonObject {
                put("method", a.str(0).ifBlank { "GET" }.uppercase()); put("url", a.str(1)); put("headers", headers)
                put("bodyType", if (body == null) "none" else if (body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) "json" else "text")
                if (body != null) put("body", body)
                put("failOnHttpError", false)
            }
            b.runNode("data.http", params).firstOrNull() ?: EMPTY
        }
        "readFile" -> Workspace.read(b.workspace, a.str(0)).let { r -> if (r.binary) throw NodeException("${a.str(0)} is a binary file") else JsonPrimitive(r.text) }
        "writeFile" -> JsonPrimitive(Workspace.write(b.workspace, a.str(0), a.str(1), a.getOrNull(2)?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false))
        "listFiles" -> JsonArray(Workspace.list(b.workspace, a.str(0)).map { e -> buildJsonObject { put("path", e.path); put("dir", e.dir); put("bytes", e.bytes); put("modified", e.modified) } })
        "deleteFile" -> JsonPrimitive(Workspace.delete(b.workspace, a.str(0)))
        "mkdir" -> { Workspace.mkdir(b.workspace, a.str(0)); JsonPrimitive(true) }
        "knowledgeSearch" -> JsonArray(b.knowledgeSearch(a.str(0), (a.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 5).map { h ->
            buildJsonObject { put("source", h.source); put("score", h.score); put("text", h.text) }
        })
        "getVar" -> b.getVar(a.str(0)) ?: JsonNull
        "setVar" -> { b.setVar(a.str(0), a.getOrNull(1)?.takeUnless { it is JsonNull }); JsonPrimitive(true) }
        else -> throw NodeException("unknown bridge function '$name'")
    }

    // ---- pure, JVM-tested ----

    private fun q(s: String): String = JSON.encodeToString(JsonElement.serializer(), JsonPrimitive(s))

    /** §7.2 verbatim; <ID>, <INPUT>, <CODE_JSON> are JSON-encoded so user code is never concatenated raw. */
    fun wrapper(callId: String, code: String, inputJson: String): String = """
(function(){"use strict";
var B=window.__mob8n, ID=${q(callId)}, LOGS=[];
["fetch","XMLHttpRequest","WebSocket","EventSource","Worker","SharedWorker","importScripts","RTCPeerConnection","Image"].forEach(function(k){try{window[k]=undefined;delete window[k];}catch(e){}});
try{navigator.sendBeacon=undefined;}catch(e){}
function call(name){var args=Array.prototype.slice.call(arguments,1);var r=JSON.parse(B.call(ID,name,JSON.stringify(args)));if(r.error!==undefined)throw new Error(r.error);return r.ok;}
function log(){if(LOGS.length<$MAX_LOG_LINES)LOGS.push(Array.prototype.map.call(arguments,function(x){return typeof x==="string"?x:JSON.stringify(x);}).join(" ").slice(0,2000));}
var console_={log:log,info:log,warn:log,error:log,debug:log};
var mob8n={
  runNode:function(id,p){return call("runNode",String(id),p||{});},
  http:function(m,u,h,b){return call("http",m||"GET",String(u),h||{},b==null?null:(typeof b==="string"?b:JSON.stringify(b)));},
  readFile:function(p){return call("readFile",String(p));}, writeFile:function(p,t,a){return call("writeFile",String(p),String(t),!!a);},
  listFiles:function(d){return call("listFiles",d==null?"":String(d));}, deleteFile:function(p){return call("deleteFile",String(p));}, mkdir:function(d){return call("mkdir",String(d));},
  knowledgeSearch:function(q,k){return call("knowledgeSearch",String(q),(k|0)||5);},
  getVar:function(k){return call("getVar",String(k));}, setVar:function(k,v){return call("setVar",String(k),v===undefined?null:v);},
  log:log, now:function(){return Date.now();}
};
var IN=$inputJson;
function done(v){B.done(ID,JSON.stringify({ok:true,value:v===undefined?null:v,logs:LOGS}));}
function fail(e){B.done(ID,JSON.stringify({ok:false,error:String(e&&e.stack||e).slice(0,4000),logs:LOGS}));}
var fn;
try{var AF=Object.getPrototypeOf(async function(){}).constructor;fn=new AF("item","items","${'$'}vars","mob8n","console","${'$'}index","${'$'}count",${q(code)});}catch(e){fail(e);return;}
Promise.resolve().then(async function(){
  if(IN.mode==="per_item"){var out=[];for(var i=0;i<IN.items.length;i++){var r=await fn(IN.items[i],IN.items,IN.vars,mob8n,console_,i,IN.items.length);out.push(r===undefined?IN.items[i]:r);}return out;}
  return fn(IN.item,IN.items,IN.vars,mob8n,console_,0,IN.items.length);
}).then(done,fail);
})();
""".trimStart()

    /** {ok:true,value,logs} -> (value, logs); {ok:false,error,logs} -> failure; > MAX_RESULT or malformed -> failure. */
    fun parseDone(payload: String): Result<Pair<JsonElement, List<String>>> {
        if (payload.length > MAX_RESULT) return Result.failure(NodeException("JavaScript result too large (${payload.length} chars > $MAX_RESULT)"))
        val o = runCatching { JSON.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return Result.failure(NodeException("JavaScript returned malformed data"))
        val logs = (o["logs"] as? JsonArray)?.map { it.asText() }?.take(MAX_LOG_LINES) ?: emptyList()
        val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
        // ponytail: logs are dropped when the script fails; upgrade = carry them on the exception
        if (!ok) return Result.failure(NodeException("JavaScript error: " + (o["error"].asTextOrNull() ?: "unknown")))
        return Result.success((o["value"] ?: JsonNull) to logs)
    }

    /** {"ok":<value>} | {"error":"..."} */
    fun bridgeResult(r: Result<JsonElement>): String = JSON.encodeToString(JsonElement.serializer(), buildJsonObject {
        r.fold({ put("ok", it) }, { put("error", it.message ?: it.javaClass.simpleName) })
    })

    /** {item, items, vars, mode} — what the wrapper's IN sees. */
    fun inputJson(item: Item, items: Items, vars: Map<String, JsonElement>, mode: String): String =
        JSON.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("item", item); put("items", JsonArray(items)); put("vars", JsonObject(vars)); put("mode", mode)
        })
}
