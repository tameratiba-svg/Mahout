package com.mob8n.triggers

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mob8n.Mob8NApp
import com.mob8n.core.HostState
import com.mob8n.core.item
import com.mob8n.core.labelOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Host #1 (system-kept-alive once notification access is granted). Sole legal source of media sessions + notifications.
 * Also attaches the runtime triggers (Engine.attachRuntimeTriggers) so no foreground service is needed in the common case.
 * data/actions lanes reach the helpers below through [instance].
 */
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifListener? = null
        private const val DEBOUNCE_MS = 1_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var attached: AutoCloseable? = null
    private var msm: MediaSessionManager? = null
    private val tracked = HashMap<MediaSession.Token, Tracked>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { syncSessions(it ?: emptyList()) }

    private inner class Tracked(val controller: MediaController) {
        var lastKey: String? = null
        var lastFiredPlaying = false
        val fire = Runnable { fireNowPlaying(this) }
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = debounce()
            override fun onPlaybackStateChanged(state: PlaybackState?) = debounce()
            override fun onSessionDestroyed() { tracked.remove(controller.sessionToken)?.let { untrack(it) } }
        }
        fun debounce() { main.removeCallbacks(fire); main.postDelayed(fire, DEBOUNCE_MS) }
    }

    private val host get() = Mob8NApp.of(this).engine.host

    override fun onListenerConnected() {
        instance = this
        HostState.listenerConnected = true
        logT("listener connected")
        try { attached?.close() } catch (_: Exception) {}
        attached = try { Mob8NApp.of(this).engine.attachRuntimeTriggers() } catch (e: Exception) { logW("attachRuntimeTriggers", e); null }
        try {
            val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            val cn = ComponentName(this, NotifListener::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            syncSessions(m.getActiveSessions(cn))
        } catch (e: Exception) { logW("media sessions unavailable", e) }
    }

    override fun onListenerDisconnected() {
        instance = null
        HostState.listenerConnected = false
        logT("listener disconnected")
        try { attached?.close() } catch (_: Exception) {}
        attached = null
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        tracked.values.forEach(::untrack); tracked.clear()
    }

    // ---- now playing ----
    private fun syncSessions(controllers: List<MediaController>) {
        val live = controllers.associateBy { it.sessionToken }
        tracked.keys.filter { it !in live }.forEach { tracked.remove(it)?.let(::untrack) }
        for ((token, c) in live) if (token !in tracked) {
            val t = Tracked(c)
            tracked[token] = t
            try { c.registerCallback(t.cb, main) } catch (e: Exception) { logW("registerCallback ${c.packageName}", e) }
            t.debounce()   // announce whatever is playing now (debounced; filtered by accepts/dedupe downstream)
        }
    }

    private fun untrack(t: Tracked) {
        main.removeCallbacks(t.fire)
        try { t.controller.unregisterCallback(t.cb) } catch (_: Exception) {}
    }

    private fun fireNowPlaying(t: Tracked) {
        try {
            val md = t.controller.metadata
            val ps = t.controller.playbackState
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            if (title.isNullOrBlank() && artist.isNullOrBlank()) return
            val state = stateName(ps?.state)
            val playing = state == "playing"
            val key = "$title|$artist"
            if (key == t.lastKey && (!playing || t.lastFiredPlaying)) return   // fire on (title,artist) change, or first time this track plays
            t.lastKey = key; t.lastFiredPlaying = playing
            host.fire(NowPlayingTrigger.spec.id, item(
                "title" to title, "artist" to artist, "album" to md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                "durationMs" to md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
                "sourceApp" to t.controller.packageName, "state" to state, "positionMs" to ps?.position, "at" to now(),
            ))
        } catch (e: Exception) { logW("now_playing", e) }
    }

    private fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"; PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"; null, PlaybackState.STATE_NONE -> "none"; else -> "other"
    }

    // ---- notifications ----
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return   // never react to our own notifications (loops)
        try { host.fire(NotificationPostedTrigger.spec.id, toEvent(s)) } catch (e: Exception) { logW("notification_posted", e) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        try {
            val ex = s.notification?.extras
            host.fire(NotificationRemovedTrigger.spec.id, item(
                "packageName" to s.packageName, "title" to ex.cs(Notification.EXTRA_TITLE), "text" to ex.cs(Notification.EXTRA_TEXT),
                "key" to s.key, "reason" to reason,
            ))
        } catch (e: Exception) { logW("notification_removed", e) }
    }

    private fun Bundle?.cs(key: String): String? = try { this?.getCharSequence(key)?.toString() } catch (e: Exception) { null }

    fun toEvent(s: StatusBarNotification): JsonObject {
        val n = s.notification
        val ex = n?.extras
        return item(
            "packageName" to s.packageName, "appName" to packageManager.labelOrNull(s.packageName),
            "title" to ex.cs(Notification.EXTRA_TITLE), "text" to ex.cs(Notification.EXTRA_TEXT),
            "bigText" to ex.cs(Notification.EXTRA_BIG_TEXT), "subText" to ex.cs(Notification.EXTRA_SUB_TEXT),
            "key" to s.key, "category" to n?.category, "postTime" to s.postTime, "ongoing" to s.isOngoing,
            "isGroupSummary" to ((n?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY != 0),
            "actions" to (n?.actions?.mapNotNull { it?.title?.toString() } ?: emptyList<String>()),
        )
    }


    // ---- helpers for data.active_notifications / action.cancel_notification / action.reply_notification (via instance) ----
    fun activeNotificationsJson(packageName: String? = null, limit: Int = 50): List<JsonObject> = try {
        (activeNotifications ?: emptyArray()).asSequence()
            .filter { packageName.isNullOrBlank() || it.packageName == packageName }
            .sortedByDescending { it.postTime }.take(limit.coerceIn(1, 500)).map(::toEvent).toList()
    } catch (e: Exception) { logW("activeNotifications", e); emptyList() }

    fun cancel(key: String) { try { cancelNotification(key) } catch (e: Exception) { logW("cancel $key", e) } }

    /** Best effort inline reply through the first action that carries a RemoteInput. */
    fun reply(key: String, text: String): Boolean = try {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return false
        val action = sbn.notification?.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
        val intent = Intent()
        val results = Bundle().apply { action.remoteInputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        action.actionIntent.send(this, 0, intent)
        true
    } catch (e: Exception) { logW("reply $key", e); false }
}
