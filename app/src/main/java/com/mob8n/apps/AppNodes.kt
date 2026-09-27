package com.mob8n.apps

import com.mob8n.core.Node

/** Sixth lane list (DESIGN2 §3.6); Mob8NApp appends it to the Catalog. 13 nodes, all ids app.* (v4: + ShellRunNode). */
object AppNodes {
    val all: List<Node> = listOf(CapabilitiesNode, RecipesNode, AppActionNode, LaunchWaitNode, UiReadNode, UiTapNode, UiLongPressNode, UiTypeNode, UiScrollNode, UiWaitForNode, UiGlobalNode, UiScreenshotNode, ShellRunNode)
}
