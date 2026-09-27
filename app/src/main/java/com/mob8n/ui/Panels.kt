@file:OptIn(ExperimentalMaterial3Api::class)

package com.mob8n.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.graphics.BitmapFactory
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mob8n.ai.Panel
import com.mob8n.ai.PanelKind
import com.mob8n.ai.PanelPrefs
import com.mob8n.core.SETTINGS_PREFS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

// ---- pure helpers (PanelsUrlTest) ----

const val PANELS_GRID_KEY = "panels_grid"    // settings: comma-joined panel ids shown in the 2x2 grid
const val PANELS_GRID_MAX = 4                  // <= 4 live WebViews (they share Chromium's renderer with JsRuntime)
const val PANEL_FRAME_MAX = 5 shl 20           // IMAGE tile: 5 MB per frame
const val PANEL_READ_MS = 5_000
const val PANEL_CONNECT_MS = 3_000
const val PANEL_PHONE_WARNING = "3D globe panels use ~300 MB; keep one open on phones"
const val PANEL_AUTH_CEILING = "The Authorization header reaches only the top page; requests the page makes itself do not carry it (God's Eye View takes its token inside the page)."

/** The add sheet's presets (DESIGN5 §8.3); `<host>` is replaced by the Mac's LAN IP. */
data class PanelPreset(val label: String, val title: String, val urlTemplate: String, val kind: PanelKind, val authHeader: Boolean, val refreshMs: Long = 1_000)
const val PANEL_HOST = "<host>"
val PANEL_PRESETS: List<PanelPreset> = listOf(
    PanelPreset("God's Eye View", "God's Eye View", "http://$PANEL_HOST:4173", PanelKind.WEB, authHeader = false),
    PanelPreset("UAV camera", "Drone1 cam", "http://$PANEL_HOST:8790/camera/Drone1", PanelKind.IMAGE, authHeader = true, refreshMs = 1_000),
    PanelPreset("Any URL", "", "", PanelKind.WEB, authHeader = false),
)
fun presetUrl(p: PanelPreset, host: String): String = p.urlTemplate.replace(PANEL_HOST, host.trim())

/**
 * WEB tile navigation rule: the same site over http(s) stays — same host, or (DNS names) the same last two labels, so
 * en.m.wikipedia.org -> en.wikipedia.org and www.openstreetmap.org -> openstreetmap.org load; an IP literal / single-label host must match
 * exactly; an https panel never downgrades to http; anything else is refused (the tile says so, [blockedNavMessage]).
 */
fun panelNavAllowed(panelUrl: String, target: String): Boolean {
    val p = runCatching { URI(panelUrl) }.getOrNull() ?: return false
    val t = runCatching { URI(target) }.getOrNull() ?: return false
    val ts = t.scheme?.lowercase() ?: return false
    if (ts != "http" && ts != "https") return false
    if (t.rawUserInfo != null) return false
    val ph = p.host ?: return false
    if (siteOf(ph) != siteOf(t.host ?: return false)) return false
    return ts == "https" || p.scheme.equals("http", ignoreCase = true)
}

// ponytail: naive registrable domain = last two labels (co.uk-style suffixes over-match their siblings); upgrade = the public suffix list
private fun siteOf(host: String): String {
    val h = host.lowercase().trimEnd('.')
    if (h.startsWith("[") || h.all { it.isDigit() || it == '.' } || '.' !in h) return h
    return h.split('.').takeLast(2).joinToString(".")
}

/** The WEB tile's text for a refused main-frame navigation / redirect. */
fun blockedNavMessage(target: String): String = "Blocked navigation to ${runCatching { URI(target).host }.getOrNull() ?: target.substringBefore(':')}"

/** "Open in browser" is offered only for http(s) targets (never intent:, javascript:, file: …). */
fun canOpenInBrowser(target: String): Boolean = runCatching { URI(target).scheme?.lowercase() in setOf("http", "https") && URI(target).host != null }.getOrDefault(false)

/** Ids shown in the grid: the saved ones that still exist (max 4), else the first four panels. */
fun gridIds(saved: String?, panels: List<Panel>): List<String> {
    val ids = panels.map { it.id }.toSet()
    val kept = saved?.split(',')?.filter { it in ids }?.distinct()?.take(PANELS_GRID_MAX).orEmpty()
    return kept.ifEmpty { panels.take(PANELS_GRID_MAX).map { it.id } }
}

/** Chip tap in the grid: remove (never the last one) or add, dropping the oldest when four are shown. */
fun toggleGrid(current: List<String>, id: String): List<String> = when {
    id in current -> if (current.size == 1) current else current - id
    current.size >= PANELS_GRID_MAX -> current.drop(1) + id
    else -> current + id
}

/** "Panel GEV, 2 of 4" (grid) / "Panel GEV" (single tile). */
fun panelCellDescription(title: String, index: Int?, count: Int): String = if (index == null) "Panel $title" else "Panel $title, ${index + 1} of $count"

/** Short, secret-free reason for the IMAGE tile's offline overlay. */
fun frameError(e: Throwable): String = when (e) {
    is SocketTimeoutException -> "timed out"
    is ConnectException, is UnknownHostException -> "cannot connect"
    else -> e.message?.take(120) ?: e.javaClass.simpleName
}

// ---- screen ----

/**
 * Dashboard > Panels (DESIGN5 §8.3): >= 840 dp -> a 2x2 grid of up to four tiles (chips choose which; `panels_grid` remembers), else one tile + chips.
 * Panels are the operator's own screens: never agent tools, never driven by the model.
 */
@Composable
fun PanelsScreen(slug: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { PanelPrefs.load(ctx) }
    val panels by PanelPrefs.panels.collectAsStateWithLifecycle()
    val prefs = remember { ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var gridSaved by remember { mutableStateOf(prefs.getString(PANELS_GRID_KEY, null)) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Panel?>(null) }
    var isNew by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val wide = LocalConfiguration.current.screenWidthDp >= 840   // same predicate as the navigation rail (App.kt)
    val grid = gridIds(gridSaved, panels)
    fun saveGrid(ids: List<String>) { val s = ids.joinToString(","); gridSaved = s; prefs.edit().putString(PANELS_GRID_KEY, s).apply() }

    // Deep link mob8n://panel/<slug> (show_panel): select that panel, and bring it into the grid.
    LaunchedEffect(slug) {
        if (slug.isNullOrBlank()) return@LaunchedEffect
        PanelPrefs.load(ctx)
        val p = PanelPrefs.bySlug(ctx, slug)
        if (p == null) { snack.showSnackbar("No panel \"$slug\""); return@LaunchedEffect }
        selected = p.id; fullscreen = null
        val g = gridIds(prefs.getString(PANELS_GRID_KEY, null), PanelPrefs.read(ctx))
        if (p.id !in g) saveGrid(toggleGrid(g, p.id))
    }
    BackHandler(enabled = fullscreen != null) { fullscreen = null }
    val current = panels.firstOrNull { it.id == selected } ?: panels.firstOrNull { it.id == grid.firstOrNull() } ?: panels.firstOrNull()
    val full = panels.firstOrNull { it.id == fullscreen }

    Scaffold(
        topBar = { MahoutTopBar("Panels", onBack = onBack) },
        snackbarHost = { MahoutSnackbarHost(snack) },
        floatingActionButton = {
            if (full == null && panels.size < PanelPrefs.MAX_PANELS) FloatingActionButton(onClick = { editing = null; isNew = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add panel") }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (panels.isEmpty() || current == null) {
                EmptyState(Icons.Rounded.Dashboard, "No panels yet", "Tap + to add a web page (e.g. God's Eye View) or a camera feed from your LAN.\n\n" +
                    "https:// works for any host; http:// only for localhost, *.local and private LAN addresses. Tokens go in the panel's secret, never in the URL.",
                    Modifier.fillMaxWidth().padding(Space.xl))
                return@Column
            }
            if (full != null) {
                key(full.id, full.url, full.kind, full.authHeader, full.refreshMs) {
                    PanelTile(full, panelCellDescription(full.title, null, 1), fullscreen = true, onFullscreen = { fullscreen = null }, onEdit = { editing = full; isNew = false },
                        modifier = Modifier.fillMaxSize().padding(8.dp))
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (p in panels) {
                    val on = if (wide) p.id in grid else p.id == current.id
                    FilterChip(selected = on, onClick = { if (wide) saveGrid(toggleGrid(grid, p.id)) else selected = p.id }, label = { Text(p.title, maxLines = 1) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${if (wide) "Show in grid" else "Show"} ${p.title}${if (on) ", shown" else ""}" })
                }
            }
            if (wide) {
                val shown = grid.mapNotNull { id -> panels.firstOrNull { it.id == id } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val rows = (shown.size + 1) / 2
                    val cellH = (maxHeight - 16.dp - 8.dp * (rows - 1)) / rows.coerceAtLeast(1)
                    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), userScrollEnabled = false,
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(shown, key = { _, p -> "${p.id}|${p.url}|${p.kind}|${p.authHeader}|${p.refreshMs}" }) { i, p ->
                            PanelTile(p, panelCellDescription(p.title, i, shown.size), fullscreen = false, onFullscreen = { fullscreen = p.id }, onEdit = { editing = p; isNew = false },
                                modifier = Modifier.fillMaxWidth().height(cellH))
                        }
                    }
                }
            } else key(current.id, current.url, current.kind, current.authHeader, current.refreshMs) {
                PanelTile(current, panelCellDescription(current.title, null, 1), fullscreen = false, onFullscreen = { fullscreen = current.id }, onEdit = { editing = current; isNew = false },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp))
            }
        }
    }
    if (isNew || editing != null) PanelEditSheet(if (isNew) null else editing, onClose = { editing = null; isNew = false }, onSaved = { selected = it })
}

@Composable
private fun PanelTile(p: Panel, description: String, fullscreen: Boolean, onFullscreen: () -> Unit, onEdit: () -> Unit, modifier: Modifier) {
    var reload by remember { mutableIntStateOf(0) }
    // v6 (DESIGN6 §6.9 / §2.8): L1 card, Radius.m, light-mode hairline; restyle only
    Card(modifier, shape = RoundedCornerShape(Radius.m), colors = CardDefaults.cardColors(containerColor = MaterialTheme.mahout.card, contentColor = MaterialTheme.colorScheme.onSurface),
        border = if (isSystemInDarkTheme()) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f).semantics { contentDescription = description; heading() })
                IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "Reload ${p.title}") }
                IconButton(onClick = onFullscreen) { Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen ${p.title}") }
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit ${p.title}") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (p.kind) {
                    PanelKind.WEB -> WebTile(p, reload, Modifier.fillMaxSize())
                    PanelKind.IMAGE -> ImageTile(p, reload, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Hardened WebView (DESIGN5 §8.3). JS + DOM storage on (Cesium/GEV need both); no file/content access, no geolocation, every permission request denied. */
@SuppressLint("SetJavaScriptEnabled")
private fun hardenedWebView(ctx: Context, panelUrl: String, onBlocked: (String) -> Unit): WebView = WebView(ctx).apply {
    // F5: an explicit MATCH_PARENT box; without it AndroidView measures WRAP_CONTENT and 100vh/100% pages (the OSM map) get a 0-height viewport
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setGeolocationEnabled(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (panelNavAllowed(panelUrl, target)) return false
            if (request.isForMainFrame) onBlocked(target)
            return true
        }
    }
}

@Composable
private fun WebTile(p: Panel, reload: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(web, lifecycle) {
        val w = web
        val obs = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_RESUME -> w?.onResume(); Lifecycle.Event.ON_PAUSE -> w?.onPause(); else -> {} } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(web, reload) {
        val w = web ?: return@LaunchedEffect
        val secret = if (p.authHeader) withContext(Dispatchers.IO) { PanelPrefs.secret(ctx, p) } else null
        // ponytail: WebView Authorization header reaches the top document only; image tiles fetch natively
        if (secret != null) w.loadUrl(p.url, mapOf("Authorization" to "Bearer $secret")) else w.loadUrl(p.url)
    }
    var blocked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) { blocked = null }
    Box(modifier) {
        AndroidView(factory = { c -> hardenedWebView(c, p.url) { blocked = it }.also { web = it } }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Web page ${p.title}" },
            onRelease = { it.stopLoading(); it.destroy() })
        blocked?.let { target ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(blockedNavMessage(target), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (canOpenInBrowser(target)) PillButton("Open in browser", onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                    TextButton(onClick = { blocked = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
                }
            }
        }
    }
}

/** One JPEG GET: Bearer header (never ?token=), 3 s connect / 5 s read, no redirects (the header must not follow), 5 MB cap. */
private fun fetchFrame(url: String, bearer: String?): ImageBitmap {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = PANEL_CONNECT_MS; c.readTimeout = PANEL_READ_MS; c.instanceFollowRedirects = false; c.useCaches = false
        c.setRequestProperty("Accept", "image/jpeg,image/*")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        val code = c.responseCode
        if (code != 200) throw IOException(if (code == 401 || code == 403) "HTTP $code — check the panel token" else "HTTP $code")
        if (c.contentLengthLong > PANEL_FRAME_MAX) throw IOException("frame larger than 5 MB")
        val out = ByteArrayOutputStream()
        c.inputStream.use { ins ->
            val buf = ByteArray(16 shl 10)
            while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > PANEL_FRAME_MAX) throw IOException("frame larger than 5 MB") }
        }
        val bytes = out.toByteArray()
        return (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("not an image")).asImageBitmap()
    } finally { c.disconnect() }
}

@Composable
private fun ImageTile(p: Panel, reload: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var offlineSince by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        val secret = if (p.authHeader) withContext(Dispatchers.IO) { PanelPrefs.secret(ctx, p) } else null
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {   // paused when not resumed
            while (true) {
                try {
                    frame = withContext(Dispatchers.IO) { fetchFrame(p.url, secret) }
                    offlineSince = null; reason = null
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    if (offlineSince == null) offlineSince = System.currentTimeMillis()
                    reason = frameError(e)
                }
                delay(p.refreshMs.coerceIn(500L, 10_000L))
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val f = frame
        if (f != null) Image(f, contentDescription = "Camera frame ${p.title}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else if (offlineSince == null) SkeletonBlock(Modifier.fillMaxSize().padding(Space.s).semantics { contentDescription = "Loading ${p.title}" }, height = 160.dp)   // v6: skeleton while the first frame loads
        offlineSince?.let { t ->
            Text("Offline since ${fmtTime(t)}${reason?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.BottomStart).padding(Space.s).background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(Radius.s)).padding(horizontal = Space.s, vertical = Space.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

// ---- add / edit ----

@Composable
private fun PanelEditSheet(initial: Panel?, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    var preset by remember { mutableStateOf<PanelPreset?>(null) }
    var host by remember { mutableStateOf("") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: PanelKind.WEB) }
    var authHeader by remember { mutableStateOf(initial?.authHeader ?: false) }
    var refreshMs by remember { mutableStateOf(initial?.refreshMs ?: 1_000L) }
    var secretDraft by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasSecret = initial?.hasSecret == true
    fun pick(p: PanelPreset) { preset = p; title = p.title; kind = p.kind; authHeader = p.authHeader; refreshMs = p.refreshMs; url = if (host.isBlank()) p.urlTemplate else presetUrl(p, host); error = null }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.l, end = Space.l, bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(if (initial == null) "Add panel" else "Edit ${initial.title}", style = MaterialTheme.typography.titleLarge)
            if (initial == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in PANEL_PRESETS) FilterChip(selected = preset == p, onClick = { pick(p) }, label = { Text(p.label) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Preset ${p.label}${if (preset == p) ", selected" else ""}" })
                }
                val pr = preset
                if (pr != null && PANEL_HOST in pr.urlTemplate) OutlinedTextField(host, { host = it.trim(); url = presetUrl(pr, host); error = null }, label = { Text("Mac IP on your LAN") }, singleLine = true,
                    placeholder = { Text("192.168.1.10") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mac IP on your LAN" })
            }
            OutlinedTextField(title, { title = it; error = null }, label = { Text("Title") }, singleLine = true, supportingText = { Text("Unique, ≤ 40 characters; chat can open it with show_panel") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Panel title" })
            OutlinedTextField(url, { url = it; error = null }, label = { Text("URL") }, singleLine = true, isError = error != null, placeholder = { Text("http://192.168.1.10:4173") },
                supportingText = { Text(error ?: "https:// any host; http:// only for localhost, *.local and private LAN addresses; never put tokens in the URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Panel URL" })
            EnumDropdown("Kind", listOf("Web page", "Image (JPEG refresh)"), if (kind == PanelKind.WEB) "Web page" else "Image (JPEG refresh)",
                { kind = if (it == "Web page") PanelKind.WEB else PanelKind.IMAGE }, modifier = Modifier.semantics { contentDescription = "Panel kind" })
            if (kind == PanelKind.IMAGE) {
                Text("Refresh every ${"%.1f".format(refreshMs / 1000.0)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(value = refreshMs.toFloat(), onValueChange = { refreshMs = (Math.round(it / 500f) * 500L).coerceIn(500L, 10_000L) }, valueRange = 500f..10_000f, steps = 18,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Refresh interval ${"%.1f".format(refreshMs / 1000.0)} seconds" })
            } else Text(PANEL_PHONE_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Send Authorization: Bearer", if (kind == PanelKind.WEB) PANEL_AUTH_CEILING else "Sent with every frame request.", authHeader) { authHeader = it }
            if (authHeader) OutlinedTextField(
                value = secretDraft, onValueChange = { secretDraft = it }, singleLine = true, label = { Text(if (hasSecret) "Replace token" else "Bearer token") },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),   // secret hygiene: no IME dictionary learning
                trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide token" else "Reveal token") } },
                supportingText = { Text(if (hasSecret) "A token is saved (secret ${PanelPrefs.secretName(id)}) and never shown again; leave blank to keep it" else "Stored app-private as secret ${PanelPrefs.secretName(id)}, excluded from backup") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Panel token" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Save", onClick = {
                    val p = Panel(id, title.trim(), url.trim(), kind, authHeader, hasSecret, refreshMs)
                    try {
                        PanelPrefs.save(ctx, p, when { !authHeader -> ""; secretDraft.isNotEmpty() -> secretDraft; else -> null })
                        secretDraft = ""; onSaved(id); onClose()
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Invalid" }
                }, enabled = title.isNotBlank() && url.isNotBlank() && PANEL_HOST !in url, contentDescription = "Save panel")
                if (initial != null) TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Delete panel" }) { Text("Delete") }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
    if (confirmDelete && initial != null) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${initial.title}?") }, text = { Text("Its saved token is removed too.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; PanelPrefs.delete(ctx, initial.id); onClose() }, modifier = Modifier.semantics { contentDescription = "Confirm delete panel" }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
