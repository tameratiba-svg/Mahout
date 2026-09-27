package com.mob8n.apps

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One visible accessibility node, flattened (DESIGN2 §7.4). `index` = DFS position in the snapshot, `parentIndex` = index of the
 * nearest KEPT ancestor (-1 for the root). `bounds` = "l,t,r,b" in screen px. Pure Kotlin so matching is JVM-tested.
 */
data class UiNode(
    val index: Int,
    val text: String?,
    val desc: String?,
    val id: String?,          // viewIdResourceName after '/', e.g. "search_action_bar"
    val cls: String?,         // simple class name, e.g. "Button"
    val bounds: String,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val focused: Boolean = false,
    val password: Boolean = false,
    val depth: Int = 0,
    val parentIndex: Int = -1,
) {
    /** Compact JSON: nulls and false flags dropped (≈ 70 bytes for a typical node). */
    fun toJson(): JsonObject = buildJsonObject {
        put("i", index)
        text?.let { put("text", it) }
        desc?.let { put("desc", it) }
        id?.let { put("id", it) }
        cls?.let { put("cls", it) }
        put("b", bounds)
        if (clickable) put("click", true)
        if (longClickable) put("long", true)
        if (editable) put("edit", true)
        if (scrollable) put("scroll", true)
        checked?.let { put("checked", it) }
        if (focused) put("focus", true)
        if (password) put("pwd", true)
    }

    /** Centre of `bounds` in px, or null when the string is malformed. */
    fun center(): Pair<Float, Float>? {
        val p = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (p.size != 4) return null
        return (p[0] + p[2]) / 2f to (p[1] + p[3]) / 2f
    }
    fun rect(): IntArray? = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }?.toIntArray()
}

/** Pure matching over a snapshot. Android-side lookups (findAccessibilityNodeInfosBy*) are deliberately not used: the snapshot is the truth. */
object UiMatch {
    data class Target(val node: UiNode, val method: String)   // method: "click" (node itself) | "ancestor" (nearest clickable parent)

    /**
     * Priority: `index` > `viewIdSuffix` > exact text (case-insensitive) > exact desc > contains text > contains desc.
     * `className` narrows every stage (simple name, case-insensitive). `nth` is 1-based. `exact` disables the contains stages.
     * Returns null when nothing matches or no matcher was given.
     */
    fun find(
        nodes: List<UiNode>, text: String? = null, desc: String? = null, viewIdSuffix: String? = null, className: String? = null,
        index: Int? = null, nth: Int = 1, exact: Boolean = false,
    ): UiNode? {
        val pool = if (className.isNullOrBlank()) nodes else nodes.filter { it.cls.equals(className.trim(), true) || it.cls?.endsWith(className.trim(), true) == true }
        val n = nth.coerceAtLeast(1)
        if (index != null) return pool.filter { it.index == index }.getOrNull(n - 1)
        if (!viewIdSuffix.isNullOrBlank()) {
            val s = viewIdSuffix.trim().substringAfter('/')
            return pool.filter { it.id != null && (it.id == s || it.id.endsWith("/$s")) }.getOrNull(n - 1)
        }
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        val d = desc?.trim()?.takeIf { it.isNotEmpty() }
        if (t == null && d == null) return if (className.isNullOrBlank()) null else pool.getOrNull(n - 1)
        val stages = ArrayList<List<UiNode>>()
        if (t != null) stages += pool.filter { it.text.equals(t, true) }
        if (d != null) stages += pool.filter { it.desc.equals(d, true) }
        if (!exact) {
            if (t != null) stages += pool.filter { it.text?.contains(t, true) == true || it.desc?.contains(t, true) == true }
            if (d != null) stages += pool.filter { it.desc?.contains(d, true) == true }
        }
        for (s in stages) if (s.isNotEmpty()) return s.getOrNull(n - 1)
        return null
    }

    /** The node itself when clickable, else the nearest clickable ancestor via parentIndex; null → caller falls back to a gesture. */
    fun clickTarget(nodes: List<UiNode>, match: UiNode): Target? {
        if (match.clickable) return Target(match, "click")
        val byIndex = nodes.associateBy { it.index }
        var cur = match
        var hops = 0
        while (cur.parentIndex >= 0 && hops++ < 64) {
            cur = byIndex[cur.parentIndex] ?: return null
            if (cur.clickable) return Target(cur, "ancestor")
        }
        return null
    }

    /** ui_read shaping: optional contains-filter on text/desc/id, clickable-only, then cap. Second = truncated. */
    fun limit(nodes: List<UiNode>, maxNodes: Int, filter: String? = null, onlyClickable: Boolean = false): Pair<List<UiNode>, Boolean> {
        val f = filter?.trim()?.takeIf { it.isNotEmpty() }
        val kept = nodes.filter { n ->
            (!onlyClickable || n.clickable) &&
                (f == null || n.text?.contains(f, true) == true || n.desc?.contains(f, true) == true || n.id?.contains(f, true) == true)
        }
        val cap = maxNodes.coerceAtLeast(1)
        return if (kept.size > cap) kept.take(cap) to true else kept to false
    }

    /** Summary of a node for run logs / tool results. */
    fun brief(n: UiNode): JsonObject = buildJsonObject {
        put("i", n.index); n.text?.let { put("text", it) }; n.desc?.let { put("desc", it) }; n.id?.let { put("id", it) }; put("bounds", n.bounds)
    }
}
