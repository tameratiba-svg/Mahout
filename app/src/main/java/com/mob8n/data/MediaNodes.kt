package com.mob8n.data

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.mob8n.core.ExecMode
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.choice
import com.mob8n.core.item
import com.mob8n.core.l
import com.mob8n.core.s
import com.mob8n.core.number
import com.mob8n.core.out
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

object NowPlayingNode : Node() {
    override val spec = NodeSpec(
        id = "data.now_playing", name = "Now Playing", kind = NodeKind.DATA,
        description = "Reads the active media session: title, artist, album, playback state and position.",
        gates = listOf(Gate.NotificationListener), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val msm = a.getSystemService(MediaSessionManager::class.java) ?: throw NodeException("No media session service")
        val sessions: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(a, "com.mob8n.triggers.NotifListener"))
        } catch (e: SecurityException) {
            throw NodeException("Needs notification access to read media sessions", e)
        }
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        val md = c?.metadata
        val ps = c?.playbackState
        if (c == null) ctx.log("no active media session")
        return out(ctx.item.add(
            "title" to md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            "artist" to (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)),
            "album" to md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            "durationMs" to md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            "sourceApp" to c?.packageName,
            "state" to when (ps?.state) {
                null, PlaybackState.STATE_NONE -> "none"
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_STOPPED -> "stopped"
                PlaybackState.STATE_BUFFERING -> "buffering"
                else -> "other"
            },
            "positionMs" to ps?.position,
        ))
    }
}

object MediaListNode : Node() {
    override val spec = NodeSpec(
        id = "data.media_list", name = "Media List", kind = NodeKind.DATA,
        description = "Lists the newest photos, songs, videos or downloaded files from the media store.",
        params = listOf(
            choice("kind", "Kind", listOf("photos", "songs", "files", "videos")),
            number("limit", "Limit", 20.0, 1.0, 500.0),
        ),
        mode = ExecMode.LIST,
        gates = listOf(Gate.Permission(Manifest.permission.READ_MEDIA_IMAGES), Gate.Permission(Manifest.permission.READ_MEDIA_VIDEO), Gate.Permission(Manifest.permission.READ_MEDIA_AUDIO), Gate.Permission(Manifest.permission.READ_EXTERNAL_STORAGE)),
        agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val kind = ctx.str("kind")
        val limit = (ctx.int("limit") ?: 20).coerceIn(1, 500)
        val collection = when (kind) {
            "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "songs" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
        }
        val proj = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DATE_ADDED); add(MediaStore.MediaColumns.TITLE)
            if (kind == "songs") { add(MediaStore.Audio.AudioColumns.ARTIST); add(MediaStore.Audio.AudioColumns.ALBUM) }
            if (kind == "songs" || kind == "videos") add("duration")            // MediaColumns.DURATION (29+) == Audio/Video DURATION (all)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        val items = ArrayList<JsonObject>()
        withContext(Dispatchers.IO) {
            try {
                val cursor = if (Build.VERSION.SDK_INT >= 30) {
                    val args = Bundle().apply {
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                        putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                    }
                    a.contentResolver.query(collection, proj, args, null)
                } else {
                    a.contentResolver.query(collection, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit")
                }
                cursor?.use { c ->
                    while (c.moveToNext() && items.size < limit) {
                        val id = c.l(MediaStore.MediaColumns._ID) ?: continue
                        items += item(
                            "uri" to ContentUris.withAppendedId(collection, id).toString(),
                            "displayName" to c.s(MediaStore.MediaColumns.DISPLAY_NAME), "mimeType" to c.s(MediaStore.MediaColumns.MIME_TYPE),
                            "sizeBytes" to c.l(MediaStore.MediaColumns.SIZE), "dateAdded" to c.l(MediaStore.MediaColumns.DATE_ADDED)?.let { it * 1000 },
                            "title" to c.s(MediaStore.MediaColumns.TITLE),
                            "artist" to c.s(MediaStore.Audio.AudioColumns.ARTIST), "album" to c.s(MediaStore.Audio.AudioColumns.ALBUM),
                            "durationMs" to c.l("duration"), "relativePath" to c.s("relative_path"),
                        )
                    }
                }
            } catch (e: SecurityException) {
                throw NodeException("Needs media permission for $kind", e)
            } catch (e: IllegalArgumentException) {
                throw NodeException("Media query failed: ${e.message}", e)
            }
        }
        ctx.log("${items.size} $kind")
        return out(items)
    }
}
