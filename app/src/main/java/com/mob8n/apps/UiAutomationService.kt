package com.mob8n.apps

/*
 * res/xml/accessibility_service_config.xml (integrator creates the file; DESIGN2 §8.2). Content, verbatim:
 *
 * <?xml version="1.0" encoding="utf-8"?>
 * <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:description="@string/ui_automation_description"
 *     android:accessibilityEventTypes="typeWindowStateChanged"
 *     android:accessibilityFeedbackType="feedbackGeneric"
 *     android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
 *     android:notificationTimeout="200"
 *     android:canRetrieveWindowContent="true"
 *     android:canPerformGestures="true"
 *     android:canTakeScreenshot="true"
 *     android:isAccessibilityTool="false" />
 *
 * Manifest (§8.1): <service android:name="com.mob8n.apps.UiAutomationService" android:exported="true" android:label="@string/ui_automation_label"
 *   android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"> <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
 *   <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config"/> </service>
 * strings.xml (§8.3): ui_automation_label = "Mob8N UI automation"; ui_automation_description = "Lets Mob8N workflows read the screen and tap, type
 *   and scroll in other apps only while a UI-automation step runs. Idle otherwise; nothing is recorded except screenshots you explicitly take."
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.mob8n.core.NodeException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Idle AccessibilityService (DESIGN2 §7.4 / V13): receives only TYPE_WINDOW_STATE_CHANGED (stores the front window), everything
 * else is on-demand from an executing app.ui_* node. No timers, no observers, no tree walks outside a node.
 */
// ponytail: WINDOW_STATE_CHANGED-only service, no runtime triggers from it; upgrade = attachRuntimeTriggers() like NotifListener
class UiAutomationService : AccessibilityService() {
    data class WindowInfo(val packageName: String?, val className: String?, val atMs: Long)

    companion object {
        @Volatile var instance: UiAutomationService? = null
        val lastWindow = AtomicReference<WindowInfo?>()
        /** Device-verified: the system re-binds the service (package update, settings toggle, process restart) with a short gap; wait ≤ 1.5 s before failing. */
        // ponytail: fixed 6 × 250 ms poll on a null instance; upgrade = observe AccessibilityManager.isEnabled + a bind latch
        suspend fun require(): UiAutomationService {
            repeat(6) { i -> instance?.let { return it }; if (i < 5) delay(250) }
            throw NodeException("UI automation is off — enable Mahout in Settings > Accessibility (Permissions screen)")
        }

        private const val MAX_VISITS = 1500
        private const val MAX_DEPTH = 40
        const val ERROR_SECURE_WINDOW = 4   // AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34), also returned by older 30+ builds per AOSP

        @Suppress("DEPRECATION")
        fun recycle(n: AccessibilityNodeInfo?) { if (Build.VERSION.SDK_INT < 33) runCatching { n?.recycle() } }

        fun toUiNode(n: AccessibilityNodeInfo, index: Int, depth: Int, parentIndex: Int): UiNode {
            val r = Rect(); n.getBoundsInScreen(r)
            return UiNode(
                index = index, text = n.text?.toString()?.takeIf { it.isNotBlank() }, desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                id = n.viewIdResourceName?.substringAfter('/')?.takeIf { it.isNotBlank() }, cls = n.className?.toString()?.substringAfterLast('.'),
                bounds = "${r.left},${r.top},${r.right},${r.bottom}", clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable,
                scrollable = n.isScrollable, checked = if (n.isCheckable) n.isChecked else null, focused = n.isFocused, password = n.isPassword, depth = depth, parentIndex = parentIndex,
            )
        }
    }

    /** Flattened tree + the live nodes behind it (same index). close() recycles them on API < 33. */
    class Snapshot(val nodes: List<UiNode>, private val live: List<AccessibilityNodeInfo>, val packageName: String?) : java.io.Closeable {
        fun live(index: Int): AccessibilityNodeInfo? = live.getOrNull(index)
        override fun close() { live.forEach { recycle(it) } }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastWindow.set(WindowInfo(e.packageName?.toString(), e.className?.toString(), System.currentTimeMillis()))
    }
    override fun onInterrupt() {}
    // Only clear the shared instance when it is still THIS object: on a re-bind the new instance connects before the old one is destroyed.
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** rootInActiveWindow, 3 tries × 100 ms when null (window transitions). Caller recycles on API < 33. */
    suspend fun root(): AccessibilityNodeInfo? {
        repeat(3) { i -> rootInActiveWindow?.let { return it }; if (i < 2) delay(100) }
        return null
    }

    /** Package of the front window: root first, lastWindow as fallback. */
    suspend fun frontPackage(): String? {
        val r = root()
        val p = r?.packageName?.toString()
        recycle(r)
        return p ?: lastWindow.get()?.packageName
    }

    /** DFS ≤ 40 deep, ≤ 1500 visits; keeps visible nodes with text|desc|viewId|clickable|longClickable|editable|scrollable|checkable. */
    suspend fun snapshot(): Snapshot {
        val root = root() ?: throw NodeException("No accessible window in front (screen off, keyguard or a secure app)")
        val nodes = ArrayList<UiNode>(); val live = ArrayList<AccessibilityNodeInfo>()
        var visits = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int, parentKept: Int) {
            if (++visits > MAX_VISITS || depth > MAX_DEPTH) { recycle(n); return }
            val keep = n.isVisibleToUser && (!n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() || n.viewIdResourceName != null ||
                n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable)
            var mine = parentKept
            if (keep) { mine = nodes.size; nodes += toUiNode(n, mine, depth, parentKept); live += n }
            for (i in 0 until n.childCount) { val c = runCatching { n.getChild(i) }.getOrNull() ?: continue; walk(c, depth + 1, mine) }
            if (!keep) recycle(n)
        }
        val pkg = root.packageName?.toString()
        walk(root, 0, -1)
        // Device-verified (Pixel Tablet): two-pane apps built on activity embedding (Settings) draw the detail pane in a SECOND window of the
        // same package; rootInActiveWindow only sees the focused pane, so wait_for/tap targets on the detail pane were invisible.
        // ponytail: append the other application windows of the same package; upgrade = every visible window ordered by layer
        val activeId = root.windowId
        for (w in runCatching { windows }.getOrDefault(emptyList())) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || w.id == activeId) continue
            val r = runCatching { w.root }.getOrNull() ?: continue
            if (r.packageName?.toString() != pkg || visits >= MAX_VISITS) { recycle(r); continue }
            walk(r, 0, -1)
        }
        return Snapshot(nodes, live, pkg)
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean = gesture(Path().apply { moveTo(x, y) }, durationMs)
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean = gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    /** dispatchGesture awaited on `main`; capped at duration + 2 s so a lost callback never hangs the node. */
    private suspend fun gesture(path: Path, durationMs: Long): Boolean {
        val d = durationMs.coerceIn(1, 59_000)
        return withTimeoutOrNull(d + 2_000) {
            suspendCancellableCoroutine { cont ->
                val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, d)).build()
                val cb = object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }
                if (!dispatchGesture(g, cb, main) && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Screen size in px (for gesture fallbacks). */
    fun screenSize(): Pair<Int, Int> = resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** API 30+: takeScreenshot -> software ARGB_8888 bitmap. INTERVAL_TIME_SHORT retried once after 1100 ms; SECURE_WINDOW mapped to a clear error. */
    @RequiresApi(30)
    suspend fun screenshot(): Bitmap {
        var attempt = 0
        while (true) {
            val r: Any = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Any> { cont ->
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(s: ScreenshotResult) { if (cont.isActive) cont.resume(s) }
                        override fun onFailure(code: Int) { if (cont.isActive) cont.resume(code) }
                    })
                }
            } ?: throw NodeException("Screenshot timed out after 10 s")
            when (r) {
                is ScreenshotResult -> {
                    val hb = r.hardwareBuffer
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace) ?: throw NodeException("Screenshot buffer could not be wrapped")
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false) ?: throw NodeException("Screenshot could not be copied")
                        hw.recycle()
                        return sw
                    } finally { hb.close() }
                }
                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> { if (attempt++ == 0) { delay(1100); continue }; throw NodeException("Screenshots are rate-limited; wait a second and retry") }
                ERROR_SECURE_WINDOW -> throw NodeException("This app blocks screenshots (secure window)")
                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> throw NodeException("Screenshot failed: NO_ACCESSIBILITY_ACCESS (re-enable the service)")
                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> throw NodeException("Screenshot failed: INVALID_DISPLAY")
                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> throw NodeException("Screenshot failed: INTERNAL_ERROR")
                else -> throw NodeException("Screenshot failed (code $r)")
            }
        }
    }
}
