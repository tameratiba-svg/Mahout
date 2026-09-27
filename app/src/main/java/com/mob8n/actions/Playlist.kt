package com.mob8n.actions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mob8n.Mob8NApp
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.LOG_TAG
import com.mob8n.core.Node
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.PlaylistEntry
import com.mob8n.core.add
import com.mob8n.core.bool
import com.mob8n.core.out
import com.mob8n.core.playlistName
import com.mob8n.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Pure .m3u text (unit-tested). Extended M3U; the location line is the MediaStore path when known, else "Artist - Title". */
object M3u {
    const val MIME = "audio/x-mpegurl"
    fun fileName(playlist: String): String = PublicFiles.safeName(playlist) + ".m3u"
    fun label(e: PlaylistEntry): String = listOfNotNull(e.artist?.takeIf { it.isNotBlank() }, e.title).joinToString(" - ")
    fun entryLines(e: PlaylistEntry, path: String?): String = "#EXTINF:-1,${label(e).replace('\n', ' ')}\n${path ?: label(e)}\n"
    fun render(playlist: String, entries: List<PlaylistEntry>, paths: Map<Long, String> = emptyMap()): String =
        "#EXTM3U\n#PLAYLIST:$playlist\n" + entries.joinToString("") { entryLines(it, it.mediaStoreId?.let { id -> paths[id] }) }
    /** Append one entry to an existing file's text (or start a fresh file); no-op when the same #EXTINF line is already there. */
    fun append(existing: String?, playlist: String, e: PlaylistEntry, path: String?): String {
        val base = existing?.takeIf { it.isNotBlank() } ?: render(playlist, emptyList())
        val lines = entryLines(e, path)
        if (base.lineSequence().any { it == lines.lineSequence().first() }) return base
        return (if (base.endsWith("\n")) base else base + "\n") + lines
    }
}

object AddToPlaylistNode : Node() {
    override val spec = NodeSpec(
        id = "action.add_to_playlist", name = "Add to playlist", kind = NodeKind.ACTION,
        description = "Save a track (title/artist) to a named Mahout playlist, mirror it to the device playlist when possible and export Music/Mob8N/<playlist>.m3u.",
        params = listOf(
            playlistName("playlist", "Playlist"),
            text("title", "Title", "{{title}}", required = true),
            text("artist", "Artist", "{{artist}}"),
            text("album", "Album", "{{album}}"),
            text("sourceApp", "Source app", "{{sourceApp}}"),
            bool("exportM3u", "Export .m3u", true),
        ),
        gates = listOf(Gate.Advisory(Gate.Permission(Manifest.permission.READ_MEDIA_AUDIO)), Gate.Advisory(Gate.Permission(Manifest.permission.READ_EXTERNAL_STORAGE)), Gate.Advisory(Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE))), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val playlist = ctx.req("playlist").trim()
        val entry0 = PlaylistEntry(playlist = playlist, title = ctx.req("title").trim(), artist = ctx.strOrNull("artist")?.trim(),
            album = ctx.strOrNull("album")?.trim(), sourceApp = ctx.strOrNull("sourceApp"), addedAt = ctx.nowMs())
        val canReadAudio = Gate.Permission(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE).granted(a)
        // 2) best-effort MediaStore match + device playlist mirror (deprecated API, skipped on any failure)
        val match: Pair<Long, String?>? = if (canReadAudio) withContext(Dispatchers.IO) { runCatching { findTrack(a, entry0) }.onFailure { ctx.log("MediaStore lookup skipped: ${it.message}") }.getOrNull() } else null
        val entry = entry0.copy(mediaStoreId = match?.first)
        // 1) Room is the source of truth (unique playlist+title+artist)
        val added = ctx.persistence.addPlaylistEntry(entry)
        if (added && match != null) withContext(Dispatchers.IO) { runCatching { mirrorToDevicePlaylist(a, playlist, match.first) }.onFailure { ctx.log("device playlist mirror skipped: ${it.message}") } }
        // 3) m3u export
        var m3uUri: String? = null
        if (ctx.bool("exportM3u")) {
            if (Build.VERSION.SDK_INT <= 28 && !Gate.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE).granted(a)) ctx.log("m3u export skipped: storage permission missing")
            else m3uUri = withContext(Dispatchers.IO) {
                try {
                    val all = withTimeoutOrNull(3_000) { Mob8NApp.of(a).engine.playlist(playlist).first() } ?: emptyList()
                    val paths = if (canReadAudio && all.any { it.mediaStoreId != null }) runCatching { pathsFor(a, all.mapNotNull { it.mediaStoreId }) }.getOrDefault(emptyMap()) else emptyMap()
                    val name = M3u.fileName(playlist)
                    val text = if (all.isNotEmpty()) M3u.render(playlist, if (all.any { it.title == entry.title && it.artist == entry.artist }) all else all + entry, paths + (match?.let { m -> m.second?.let { mapOf(m.first to it) } } ?: emptyMap()))
                    else M3u.append(PublicFiles.read(a, Environment.DIRECTORY_MUSIC, name), playlist, entry, match?.second) // ponytail: engine read unavailable -> append to the existing file text
                    val t = PublicFiles.open(a, Environment.DIRECTORY_MUSIC, name, M3u.MIME, append = false)
                    try { t.stream.write(text.toByteArray(Charsets.UTF_8)); t.stream.flush() } finally { t.close() }
                    t.uri
                } catch (e: Exception) {
                    android.util.Log.w(LOG_TAG, "m3u export failed", e); ctx.log("m3u export failed: ${e.message}"); null
                }
            }
        }
        return out(ctx.item.add("added" to added, "duplicate" to !added, "playlist" to playlist, "mediaStoreMatched" to (match != null), "m3uUri" to m3uUri, "title" to entry.title, "artist" to entry.artist))
    }

    private fun findTrack(a: Context, e: PlaylistEntry): Pair<Long, String?>? {
        val sel = StringBuilder("${MediaStore.Audio.Media.TITLE}=?"); val args = arrayListOf(e.title)
        if (!e.artist.isNullOrBlank()) { sel.append(" AND ${MediaStore.Audio.Media.ARTIST} LIKE ?"); args += "%${e.artist}%" }
        @Suppress("DEPRECATION") val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
        a.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel.toString(), args.toTypedArray(), null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0) to c.getString(1)
        }
        return null
    }

    private fun pathsFor(a: Context, ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        @Suppress("DEPRECATION") val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
        val out = HashMap<Long, String>()
        a.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")})", null, null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { out[c.getLong(0)] = it }
        }
        return out
    }

    /** MediaStore.Audio.Playlists is deprecated (31+) and often read-only on 30+; Room + m3u are authoritative (DESIGN §1.2). */
    @Suppress("DEPRECATION")
    private fun mirrorToDevicePlaylist(a: Context, name: String, audioId: Long) {
        val cr = a.contentResolver
        val plUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        var plId: Long? = null
        cr.query(plUri, arrayOf(MediaStore.Audio.Playlists._ID), "${MediaStore.Audio.Playlists.NAME}=?", arrayOf(name), null)?.use { if (it.moveToFirst()) plId = it.getLong(0) }
        if (plId == null) plId = cr.insert(plUri, ContentValues().apply { put(MediaStore.Audio.Playlists.NAME, name) })?.lastPathSegment?.toLongOrNull() ?: return
        val members = MediaStore.Audio.Playlists.Members.getContentUri("external", plId!!)
        var order = 0
        cr.query(members, arrayOf(MediaStore.Audio.Playlists.Members.PLAY_ORDER), null, null, "${MediaStore.Audio.Playlists.Members.PLAY_ORDER} DESC")?.use { if (it.moveToFirst()) order = it.getInt(0) + 1 }
        cr.insert(members, ContentValues().apply { put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId); put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, order) })
    }
}
