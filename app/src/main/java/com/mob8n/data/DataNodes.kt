package com.mob8n.data

import com.mob8n.core.Node

/** DESIGN.md §4.2 order. Ids are stored in graphs and used by Seed.kt; never rename. */
object DataNodes {
    val all: List<Node> = listOf(
        DeviceStateNode,
        NowPlayingNode,
        ActiveNotificationsNode,
        LocationNode,
        CalendarEventsNode,
        ContactLookupNode,
        MediaListNode,
        ReadFileNode,
        ClipboardNode,
        InstalledAppsNode,
        AppInfoNode,
        VariableNode,
        HttpNode,
        DateTimeNode,
        RandomNode,
        StorageNode,
        SensorNode,
        KnowledgeSearchNode,
    )
}
