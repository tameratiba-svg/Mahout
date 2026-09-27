package com.mob8n.apps

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.MAIN
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
import com.mob8n.core.label
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.route
import com.mob8n.core.secret
import com.mob8n.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.io.File
import kotlin.math.roundToInt

// ---------------------------------------------------------------- shared helpers (lane-internal)

private val ACCESSIBILITY = listOf(Gate.Accessibility)
private const val PORT_TIMEOUT = "timeout"

/** Matcher params shared by tap/long_press (§7.5). */
private fun matcherParams() = listOf(
    text("text", "Text", help = "Visible text (case-insensitive; contains unless Exact)"),
    text("contentDescription", "Content description"),
    text("viewId", "View id", help = "Resource id suffix, e.g. search_action_bar"),
    text("className", "Class name", help = "Simple class, e.g. Button"),
    number("index", "Node index", help = "From app.ui_read"), number("nth", "Nth match", 1.0, min = 1.0),
)

/** Param value only when the spec has that key (matcher sets differ per node). */
private fun ExecutionContext.opt(key: String): String? = if (spec.param(key) == null) null else strOrNull(key)
private fun ExecutionContext.optInt(key: String): Int? = if (spec.param(key) == null) null else int(key)
private fun ExecutionContext.hasMatcher(textKey: String = "text") =
    listOf(textKey, "contentDescription", "viewId", "className").any { opt(it) != null } || optInt("index") != null

private fun ExecutionContext.find(nodes: List<UiNode>, textKey: String = "text"): UiNode? =
    UiMatch.find(nodes, opt(textKey), opt("contentDescription"), opt("viewId"), opt("className"), optInt("index"), optInt("nth") ?: 1, if (spec.param("exact") == null) false else bool("exact"))

private fun ExecutionContext.describeMatchers(textKey: String = "text") =
    listOf(textKey, "contentDescription", "viewId", "className", "index").mapNotNull { k -> opt(k)?.let { "$k='$it'" } }.joinToString(", ").ifBlank { "(no matcher)" }

private class Found(val snap: UiAutomationService.Snapshot, val node: UiNode)

/** Re-snapshot every 300 ms until the matcher hits or `waitMs` passes. The returned snapshot must be closed by the caller. */
private suspend fun ExecutionContext.awaitMatch(svc: UiAutomationService, waitMs: Long, textKey: String = "text"): Found? {
    val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0)
    while (true) {
        val snap = svc.snapshot()
        find(snap.nodes, textKey)?.let { return Found(snap, it) }
        snap.close()
        if (System.currentTimeMillis() >= deadline) return null
        delay(300)
    }
}

private fun perform(n: AccessibilityNodeInfo?, action: Int, args: Bundle? = null): Boolean = n != null && runCatching { n.performAction(action, args) }.getOrDefault(false)

// ---------------------------------------------------------------- nodes

object LaunchWaitNode : Node() {
    override val spec = NodeSpec(
        id = "app.launch_wait", name = "Launch app & wait", kind = NodeKind.ACTION,
        description = "Launches an app and waits until it is in the foreground (needs the UI automation service).",
        params = listOf(appPicker("packageName", "App", required = true), durationMs("timeoutMs", "Wait up to", 8_000, 1_000, 30_000)),
        timeoutMs = 35_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        val a = ctx.requireAndroid()
        val pkg = ctx.req("packageName").trim()
        val intent = a.packageManager.getLaunchIntentForPackage(pkg) ?: throw NodeException("App $pkg is not installed or has no launcher")
        val r = AppLaunch.start(a, intent, true, "Open ${a.packageManager.label(pkg)}")
        val timeout = (ctx.long("timeoutMs") ?: 8_000L).coerceIn(1_000L, 30_000L)
        val t0 = System.currentTimeMillis()
        var fg = false
        while (System.currentTimeMillis() - t0 < timeout) {
            if (svc.frontPackage() == pkg) { fg = true; break }
            delay(250)
        }
        return out(ctx.item.add("launched" to r.started, "viaNotification" to r.viaNotification, "foreground" to fg, "waitedMs" to (System.currentTimeMillis() - t0)))
    }
}

object UiReadNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_read", name = "Read screen", kind = NodeKind.DATA,
        description = "Reads the visible UI tree of the front app: texts, descriptions, view ids, bounds and clickable/editable flags.",
        params = listOf(
            number("maxNodes", "Max nodes", 80.0, 10.0, 400.0), text("filter", "Filter", help = "Keep nodes whose text/description/id contains this"),
            bool("onlyClickable", "Only clickable", false), appPicker("packageFilter", "Expect app", help = "Error when another app is in front"),
        ),
        timeoutMs = 15_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        var snap = svc.snapshot()
        // Device-verified: right after app.launch_wait the new window can be attached but not yet populated (0 nodes); settle briefly.
        // ponytail: fixed 4 × 250 ms retry on an empty tree; upgrade = wait for TYPE_WINDOW_CONTENT_CHANGED from the service
        var settle = 0
        while (snap.nodes.isEmpty() && settle++ < 4) { snap.close(); delay(250); snap = svc.snapshot() }
        snap.use { snap ->
            ctx.strOrNull("packageFilter")?.let { if (snap.packageName != it) throw NodeException("Front app is ${snap.packageName ?: "unknown"}, expected $it") }
            val (nodes, truncated) = UiMatch.limit(snap.nodes, (ctx.int("maxNodes") ?: 80).coerceIn(10, 400), ctx.strOrNull("filter"), ctx.bool("onlyClickable"))
            return out(ctx.item.add(
                "package" to snap.packageName, "activity" to UiAutomationService.lastWindow.get()?.takeIf { it.packageName == snap.packageName }?.className,
                "count" to nodes.size, "truncated" to truncated, "nodes" to JsonArray(nodes.map { it.toJson() }),
            ))
        }
    }
}

object UiTapNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_tap", name = "Tap", kind = NodeKind.ACTION,
        description = "Taps a UI element of the front app matched by text, content description, view id, class or index (or raw x,y when no matcher).",
        params = matcherParams() + listOf(
            number("x", "X (px)", help = "Used only without a matcher"), number("y", "Y (px)"),
            bool("exact", "Exact text match", false), durationMs("waitMs", "Wait for element", 3_000, 0, 30_000),
        ),
        timeoutMs = 15_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        if (!ctx.hasMatcher()) {
            val x = ctx.double("x") ?: throw NodeException("Tap needs a matcher (text/contentDescription/viewId/className/index) or x and y")
            val y = ctx.double("y") ?: throw NodeException("Tap needs both x and y")
            val ok = svc.tap(x.toFloat(), y.toFloat())
            return out(ctx.item.add("tapped" to ok, "method" to "gesture", "target" to mapOf("x" to x, "y" to y)))
        }
        val f = ctx.awaitMatch(svc, ctx.long("waitMs") ?: 3_000L) ?: throw NodeException("No UI element matches ${ctx.describeMatchers()}")
        f.snap.use { snap ->
            val t = UiMatch.clickTarget(snap.nodes, f.node)
            var method = t?.method ?: "gesture"
            var ok = t != null && perform(snap.live(t.node.index), AccessibilityNodeInfo.ACTION_CLICK)
            if (!ok) {
                val (cx, cy) = f.node.center() ?: throw NodeException("Element has no bounds to tap")
                ok = svc.tap(cx, cy); method = "gesture"
            }
            return out(ctx.item.add("tapped" to ok, "method" to method, "target" to UiMatch.brief(f.node)))
        }
    }
}

object UiLongPressNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_long_press", name = "Long press", kind = NodeKind.ACTION,
        description = "Long-presses a UI element of the front app matched like Tap.",
        params = matcherParams() + listOf(bool("exact", "Exact text match", false), durationMs("durationMs", "Press duration", 600, 100, 10_000), durationMs("waitMs", "Wait for element", 3_000, 0, 30_000)),
        timeoutMs = 15_000, gates = ACCESSIBILITY, optional = true, agentTool = false,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        if (!ctx.hasMatcher()) throw NodeException("Long press needs a matcher (text/contentDescription/viewId/className/index)")
        val f = ctx.awaitMatch(svc, ctx.long("waitMs") ?: 3_000L) ?: throw NodeException("No UI element matches ${ctx.describeMatchers()}")
        f.snap.use { snap ->
            // nearest long-clickable: the node, then its ancestors
            val byIndex = snap.nodes.associateBy { it.index }
            var cur: UiNode? = f.node; var hops = 0
            while (cur != null && !cur.longClickable && cur.parentIndex >= 0 && hops++ < 64) cur = byIndex[cur.parentIndex]
            var method = if (cur?.longClickable == true) (if (cur === f.node) "click" else "ancestor") else "gesture"
            var ok = cur?.longClickable == true && perform(snap.live(cur.index), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (!ok) {
                val (cx, cy) = f.node.center() ?: throw NodeException("Element has no bounds to press")
                ok = svc.tap(cx, cy, (ctx.long("durationMs") ?: 600L).coerceIn(100, 10_000)); method = "gesture"
            }
            return out(ctx.item.add("pressed" to ok, "method" to method, "target" to UiMatch.brief(f.node)))
        }
    }
}

object UiTypeNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_type", name = "Type text", kind = NodeKind.ACTION,
        description = "Types text into a field of the front app (matched by view id, description or field text; default = the focused or first editable field). Refuses password fields.",
        params = listOf(
            text("text", "Text", required = true, help = "Ignored when Secret is set"),
            secret("secret", "Secret", help = "Type this secret's value instead of Text (never logged)"),
            text("viewId", "Field view id"), text("contentDescription", "Field content description"), text("fieldText", "Field current text/hint"),
            bool("append", "Append to existing text", false), bool("submit", "Press Enter after typing", false, help = "Android 11+"),
        ),
        timeoutMs = 15_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        val secretName = ctx.strOrNull("secret")
        val value = if (secretName != null) ctx.secret(secretName) else ctx.req("text")
        svc.snapshot().use { snap ->
            var live: AccessibilityNodeInfo? = null; var node: UiNode? = null
            if (ctx.hasMatcher("fieldText")) {
                node = ctx.find(snap.nodes, "fieldText") ?: throw NodeException("No field matches ${ctx.describeMatchers("fieldText")}")
                live = snap.live(node.index)
            } else {
                val root = svc.root()
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                UiAutomationService.recycle(root)
                if (focused != null && focused.isEditable) { live = focused; node = UiAutomationService.toUiNode(focused, -1, 0, -1) }
                else {
                    UiAutomationService.recycle(focused)
                    node = snap.nodes.firstOrNull { it.editable } ?: throw NodeException("No editable field on screen")
                    live = snap.live(node.index)
                }
            }
            try {
                val target = live ?: throw NodeException("Field is no longer available")
                if (node!!.password || target.isPassword) throw NodeException("Refusing to type into a password field")
                if (!target.isEditable) throw NodeException("Matched element is not editable")
                val current = target.text?.toString().orEmpty()
                val newText = if (ctx.bool("append")) current + value else value
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
                perform(target, AccessibilityNodeInfo.ACTION_FOCUS)
                if (!perform(target, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw NodeException("Field rejected ACTION_SET_TEXT")
                var submitted = false
                if (ctx.bool("submit")) {
                    if (Build.VERSION.SDK_INT >= 30) submitted = perform(target, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    else ctx.log("submit needs Android 11+; skipped")
                }
                return out(ctx.item.add("typed" to true, "length" to newText.length, "submitted" to submitted, "field" to mapOf("id" to node.id, "cls" to node.cls)))
            } finally { if (node?.index == -1) UiAutomationService.recycle(live) }   // focus-path node is not owned by the snapshot
        }
    }
}

object UiScrollNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_scroll", name = "Scroll", kind = NodeKind.ACTION,
        description = "Scrolls a scrollable element of the front app (default: the first scrollable) in a direction, N times.",
        params = listOf(
            choice("direction", "Direction", listOf("down", "up", "left", "right")), number("count", "Times", 1.0, 1.0, 20.0),
            text("text", "Text"), text("contentDescription", "Content description"), text("viewId", "View id"),
        ),
        timeoutMs = 30_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        val dir = ctx.str("direction"); val count = (ctx.int("count") ?: 1).coerceIn(1, 20)
        svc.snapshot().use { snap ->
            val node = if (ctx.hasMatcher()) ctx.find(snap.nodes) ?: throw NodeException("No element matches ${ctx.describeMatchers()}") else snap.nodes.firstOrNull { it.scrollable }
            val live = node?.let { snap.live(it.index) }
            val dirAction = when (dir) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP; "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT; else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            }
            val legacy = if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val rect = node?.rect() ?: svc.screenSize().let { (w, h) -> intArrayOf(0, 0, w, h) }
            var scrolled = 0
            for (i in 0 until count) {
                var ok = perform(live, dirAction.id) || perform(live, legacy)
                if (!ok) {
                    val w = rect[2] - rect[0]; val h = rect[3] - rect[1]; val cx = rect[0] + w / 2f; val cy = rect[1] + h / 2f
                    ok = when (dir) {
                        "down" -> svc.swipe(cx, rect[1] + h * 0.7f, cx, rect[1] + h * 0.3f)
                        "up" -> svc.swipe(cx, rect[1] + h * 0.3f, cx, rect[1] + h * 0.7f)
                        "left" -> svc.swipe(rect[0] + w * 0.7f, cy, rect[0] + w * 0.3f, cy)
                        else -> svc.swipe(rect[0] + w * 0.3f, cy, rect[0] + w * 0.7f, cy)
                    }
                }
                if (ok) scrolled++
                if (i < count - 1) delay(300)
            }
            return out(ctx.item.add("scrolled" to scrolled, "target" to node?.let { UiMatch.brief(it) }))
        }
    }
}

object UiWaitForNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_wait_for", name = "Wait for element", kind = NodeKind.DATA,
        description = "Waits until an element (text/description/view id) appears or disappears, or until an app is in front; routes to 'timeout' otherwise.",
        params = listOf(
            text("text", "Text"), text("contentDescription", "Content description"), text("viewId", "View id"),
            appPicker("packageName", "App in front"), durationMs("timeoutMs", "Timeout", 10_000, 500, 60_000), durationMs("pollMs", "Poll every", 300, 100, 5_000),
            bool("gone", "Wait until gone", false),
            bool("exact", "Exact match", false, help = "Whole text/description equals instead of contains"),
        ),
        outputs = listOf(MAIN, PORT_TIMEOUT), timeoutMs = 70_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        val pkg = ctx.strOrNull("packageName"); val gone = ctx.bool("gone"); val matcher = ctx.hasMatcher()
        if (pkg == null && !matcher) throw NodeException("Wait for needs a matcher (text/contentDescription/viewId) or an app")
        val timeout = (ctx.long("timeoutMs") ?: 10_000L).coerceIn(500, 60_000); val poll = (ctx.long("pollMs") ?: 300L).coerceIn(100, 5_000)
        val t0 = System.currentTimeMillis()
        while (true) {
            val front = svc.frontPackage()
            val pkgOk = pkg == null || front == pkg
            var target: UiNode? = null
            var present = false
            if (matcher && (pkgOk || gone)) {
                runCatching { svc.snapshot() }.getOrNull()?.use { snap -> target = ctx.find(snap.nodes); present = target != null }
            }
            val hit = if (gone) (if (matcher) !present else front != pkg) else pkgOk && (!matcher || present)
            val waited = System.currentTimeMillis() - t0
            if (hit) return out(ctx.item.add("found" to true, "waitedMs" to waited, "target" to target?.let { UiMatch.brief(it) }, "package" to front))
            if (waited >= timeout) return route(PORT_TIMEOUT, ctx.item.add("found" to false, "waitedMs" to waited, "package" to front))
            delay(poll)
        }
    }
}

object UiGlobalNode : Node() {
    private val ACTIONS = mapOf(
        "back" to AccessibilityService.GLOBAL_ACTION_BACK, "home" to AccessibilityService.GLOBAL_ACTION_HOME, "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
        "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
    )
    override val spec = NodeSpec(
        id = "app.ui_global", name = "System action", kind = NodeKind.ACTION,
        description = "Performs a global navigation action: back, home, recents, notifications or quick settings.",
        params = listOf(choice("action", "Action", ACTIONS.keys.toList())),
        timeoutMs = 10_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        val id = ACTIONS[ctx.str("action")] ?: throw NodeException("Unknown action '${ctx.str("action")}'")
        return out(ctx.item.add("performed" to svc.global(id)))
    }
}

object UiScreenshotNode : Node() {
    override val spec = NodeSpec(
        id = "app.ui_screenshot", name = "Screenshot", kind = NodeKind.DATA,
        description = "Takes a screenshot of the current screen (Android 11+) and returns a JPEG content URI usable as Image URI in AI nodes.",
        params = listOf(number("quality", "JPEG quality", 80.0, 30.0, 95.0), number("maxSide", "Max side (px)", 1568.0, 256.0, 4096.0)),
        timeoutMs = 15_000, gates = ACCESSIBILITY, optional = true, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val svc = UiAutomationService.require()
        val a = ctx.requireAndroid()
        if (Build.VERSION.SDK_INT < 30) throw NodeException("Screenshots need Android 11+")
        val quality = (ctx.int("quality") ?: 80).coerceIn(30, 95); val maxSide = (ctx.int("maxSide") ?: 1568).coerceIn(256, 4096)
        var bmp = svc.screenshot()
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
            bmp.recycle(); bmp = scaled
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(a.cacheDir, "screens").apply { mkdirs() }
            // ponytail: prune screenshots older than 1 h on every capture; upgrade = HousekeepingWorker sweep
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
            File(dir, "ui_${System.currentTimeMillis()}.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) } }
        }
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        val uri = FileProvider.getUriForFile(a, Recipes.FILE_AUTHORITY, file).toString()
        // The image is NOT put in the item (bounded Suspend payloads); vision consumers read it via Images.base64(uri).
        return out(ctx.item.add("uri" to uri, "width" to w, "height" to h, "bytes" to file.length()))
    }
}
