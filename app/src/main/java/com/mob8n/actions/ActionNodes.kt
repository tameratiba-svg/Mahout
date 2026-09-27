package com.mob8n.actions

import com.mob8n.core.Node

/** DESIGN §4.4 — all 35 action nodes, one object each (Playlist / Notify / Intents / Media / SystemSettings / Files / Misc / Knowledge). */
object ActionNodes {
    val all: List<Node> = listOf(
        AddToPlaylistNode, MediaControlNode,
        NotifyNode, CancelNotificationNode, ReplyNotificationNode,
        LaunchAppNode, OpenUrlNode, SendIntentNode, ShareNode, DialNode, ComposeSmsNode, ComposeEmailNode, NavigateNode,
        AddCalendarEventNode, AddContactNode, SetAlarmNode,
        TtsNode, PlaySoundNode, VibrateNode,
        ClipboardSetNode, ToastNode,
        RingerDndNode, DisplaySettingsNode, SetRingtoneNode, SettingsPanelNode, FlashlightNode,
        WallpaperNode, WriteFileNode, DownloadNode, SaveNoteNode, ScheduleRunNode, ToggleWorkflowNode, LogNode,
        KnowledgeAddNode, KnowledgeRemoveNode,
    )
}
