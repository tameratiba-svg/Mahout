package com.mob8n.apps

import com.mob8n.core.JSON
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMatchTest {
    // 0 root frame (clickable, no text) > 1 Button "Battery" > 2 TextView "Battery saver" ; 3 clickable row > 4 TextView "Network & internet" (desc "Network")
    // 5 EditText search (viewId search_action_bar) ; 6 TextView "Battery" (second) ; 7 image desc "Battery"
    private val nodes = listOf(
        UiNode(0, null, null, "content", "FrameLayout", "0,0,1080,1920", clickable = true, depth = 0, parentIndex = -1),
        UiNode(1, "Battery", null, null, "Button", "0,100,1080,200", clickable = true, depth = 1, parentIndex = 0),
        UiNode(2, "Battery saver", null, "summary", "TextView", "0,200,1080,260", depth = 2, parentIndex = 1),
        UiNode(3, null, null, "row", "LinearLayout", "0,300,1080,400", clickable = true, depth = 1, parentIndex = 0),
        UiNode(4, "Network & internet", "Network", "title", "TextView", "0,300,1080,400", depth = 2, parentIndex = 3),
        UiNode(5, null, "Search settings", "search_action_bar", "EditText", "0,0,1080,100", editable = true, focused = true, depth = 1, parentIndex = 0),
        UiNode(6, "Battery", null, null, "TextView", "0,500,1080,560", depth = 1, parentIndex = 0),
        UiNode(7, null, "Battery", null, "ImageView", "0,600,100,700", clickable = true, depth = 1, parentIndex = 0),
    )

    @Test fun priorityIndexOverViewIdOverTextOverDescOverContains() {
        assertEquals(6, UiMatch.find(nodes, text = "Battery", viewIdSuffix = "row", index = 6)!!.index)     // index wins
        assertEquals(3, UiMatch.find(nodes, text = "Battery", viewIdSuffix = "row")!!.index)                  // viewId beats text
        assertEquals(5, UiMatch.find(nodes, viewIdSuffix = "com.android.settings:id/search_action_bar")!!.index)
        assertEquals(1, UiMatch.find(nodes, text = "battery")!!.index)                                        // exact text (ci) before contains
        assertEquals(7, UiMatch.find(nodes, desc = "battery")!!.index)                                        // exact desc
        assertEquals(1, UiMatch.find(nodes, text = "Battery", desc = "Network")!!.index)                      // exact text beats exact desc
        assertEquals(4, UiMatch.find(nodes, text = "network")!!.index)                                        // contains text
        assertEquals(4, UiMatch.find(nodes, desc = "netw")!!.index)                                           // contains desc
        assertNull(UiMatch.find(nodes, text = "netw", exact = true))
        assertNull(UiMatch.find(nodes))
        assertEquals(6, UiMatch.find(nodes, text = "Battery", className = "TextView")!!.index)               // className narrows
        assertEquals(5, UiMatch.find(nodes, className = "EditText")!!.index)
    }

    @Test fun nth() {
        assertEquals(1, UiMatch.find(nodes, text = "Battery", nth = 1)!!.index)
        assertEquals(6, UiMatch.find(nodes, text = "Battery", nth = 2)!!.index)
        assertNull(UiMatch.find(nodes, text = "Battery", nth = 3))
        assertNull(UiMatch.find(nodes, text = "Battery", className = "TextView", nth = 2))   // nth counts within the winning stage only
    }

    @Test fun clickableAncestorWalk() {
        val t = UiMatch.clickTarget(nodes, nodes[4])!!
        assertEquals(3, t.node.index); assertEquals("ancestor", t.method)
        val self = UiMatch.clickTarget(nodes, nodes[1])!!
        assertEquals(1, self.node.index); assertEquals("click", self.method)
        val deep = UiMatch.clickTarget(nodes, nodes[2])!!
        assertEquals(1, deep.node.index)
        assertNull(UiMatch.clickTarget(listOf(UiNode(0, "x", null, null, null, "0,0,1,1")), UiNode(0, "x", null, null, null, "0,0,1,1")))
        assertEquals(540f to 350f, nodes[4].center())
    }

    @Test fun toJsonCompact() {
        val j = nodes[4].toJson()
        val s = JSON.encodeToString(JsonElement.serializer(), j)
        assertTrue(s, s.length <= 110)                                                                                   // text + desc + id + cls: the fattest shape
        assertTrue(JSON.encodeToString(JsonElement.serializer(), nodes[4].copy(desc = null).toJson()).length <= 90)      // typical node (text, id, cls, bounds)
        assertFalse(s.contains("click")); assertFalse(s.contains("null"))
        assertTrue(s.contains("\"text\":\"Network & internet\"")); assertTrue(s.contains("\"desc\":\"Network\"")); assertTrue(s.contains("\"id\":\"title\""))
        val e = nodes[5].toJson()
        assertEquals(true, e["edit"]?.toString()?.toBoolean()); assertEquals(true, e["focus"]?.toString()?.toBoolean()); assertNull(e["text"])
        assertTrue(JSON.encodeToString(JsonElement.serializer(), nodes[1].toJson()).contains("\"click\":true"))
    }

    @Test fun limitTruncatesAndFilters() {
        val (all, tr) = UiMatch.limit(nodes, 3)
        assertEquals(3, all.size); assertTrue(tr)
        val (none, tr2) = UiMatch.limit(nodes, 80)
        assertEquals(nodes.size, none.size); assertFalse(tr2)
        val (f, _) = UiMatch.limit(nodes, 80, filter = "battery")
        assertEquals(listOf(1, 2, 6, 7), f.map { it.index })
        val (c, _) = UiMatch.limit(nodes, 80, onlyClickable = true)
        assertEquals(listOf(0, 1, 3, 7), c.map { it.index })
        val (idf, _) = UiMatch.limit(nodes, 80, filter = "search_action")
        assertEquals(listOf(5), idf.map { it.index })
    }
}
