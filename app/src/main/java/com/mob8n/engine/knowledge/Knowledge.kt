package com.mob8n.engine.knowledge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.mob8n.core.LOG_TAG
import com.mob8n.core.NodeException
import com.mob8n.engine.db.ChunkMatch
import com.mob8n.engine.db.KnowledgeChunkEntity
import com.mob8n.engine.db.KnowledgeSourceEntity
import com.mob8n.engine.db.Mob8nDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.roundToInt

enum class SourceKind { DOCUMENT, FOLDER, URL, TEXT, NOTES, PLAYLIST }

data class KnowledgeSource(
    val id: String, val name: String, val kind: SourceKind, val uri: String?, val mime: String?,
    val bytes: Long, val chunks: Int, val chars: Int, val indexedAt: Long?, val error: String?,
    val pinned: Boolean, val group: String, val parentId: String?, val createdAt: Long,
) { val indexing: Boolean get() = indexedAt == null && error == null }

data class Hit(val text: String, val source: String, val sourceId: String, val seq: Int, val score: Double)
data class Usage(val sources: Int, val chunks: Int, val chars: Long)

/**
 * The ONE on-device knowledge index (DESIGN3 §3.2 / §5.6): SAF documents + folder walks with persistable grants, engine-private URL GET,
 * pasted text, Notes/Playlist snapshots; Room FTS4 storage; BM25 ranking. Other lanes reach it only through `Engine.knowledge`.
 * // ponytail: one global indexing Mutex; upgrade = per-source
 * // ponytail: Knowledge.progress is one Float per source; upgrade = phases
 */
class Knowledge internal constructor(
    private val app: Context, private val dao: Mob8nDao, @Suppress("unused") private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_DOCUMENTS = 200; const val MAX_FOLDER_FILES = 500; const val MAX_DEPTH = 8
        const val MAX_INDEX_CHARS = 40_000_000L; const val PINNED_CAP = 8 * 1024; const val STALE_MS = 20 * 3_600_000L
        const val URL_TIMEOUT_MS = 30_000; const val URL_REDIRECTS = 3; const val PINNED_PER_SOURCE = 4096; const val HIT_CHARS = 1200
        const val URI_NOTES = "mob8n://notes"; const val URI_PLAYLIST = "mob8n://playlist"; const val URI_TEXT = "mob8n://text/"
        const val ID_NOTES = "builtin-notes"; const val ID_PLAYLIST = "builtin-playlist"
        private const val NAME_BOOST = 0.5
        private const val IN_LIMIT = 900            // SQLite variable cap is 999 on older Android; larger id lists are filtered in Kotlin
        val SUPPORTED_MIMES: List<String> get() = Extract.SUPPORTED_MIMES

        // ---- pure, JVM-tested ----
        /** FOLDER/URL/NOTES/PLAYLIST age out after STALE_MS; TEXT never; DOCUMENT never by age (re-indexed on LAST_MODIFIED/SIZE change only). */
        fun isStale(kind: SourceKind, indexedAt: Long?, now: Long): Boolean = when (kind) {
            SourceKind.FOLDER, SourceKind.URL, SourceKind.NOTES, SourceKind.PLAYLIST -> indexedAt == null || now - indexedAt > STALE_MS
            SourceKind.TEXT, SourceKind.DOCUMENT -> false
        }

        /** "all" -> every indexed non-folder row; else name (ci) | group (ci) | id; FOLDER resolves to its children; unknown labels ignored. */
        fun resolve(rows: List<KnowledgeSource>, labels: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            fun expand(r: KnowledgeSource) {
                if (r.kind == SourceKind.FOLDER) rows.filter { it.parentId == r.id }.forEach { out += it.id } else out += r.id
            }
            for (raw in labels) {
                val l = raw.trim()
                if (l.isEmpty()) continue
                if (l.equals("all", ignoreCase = true)) { rows.filter { it.indexedAt != null && it.kind != SourceKind.FOLDER }.forEach { out += it.id }; continue }
                val byName = rows.filter { it.name.equals(l, ignoreCase = true) }
                if (byName.isNotEmpty()) { byName.forEach(::expand); continue }
                val byGroup = rows.filter { it.kind != SourceKind.FOLDER && it.group.isNotEmpty() && it.group.equals(l, ignoreCase = true) }
                if (byGroup.isNotEmpty()) { byGroup.forEach { out += it.id }; continue }
                rows.firstOrNull { it.id == l }?.let(::expand)
            }
            return out.toList()
        }

        /** loopback, *.local, RFC-1918 (copy of Providers.isLanHost: ai is not importable from engine). */
        internal fun isLanHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".localhost")) return true
            val parts = h.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
            val a = parts[0].toInt(); val b = parts[1].toInt()
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }

        /** `<knowledge source="…">` fences with attribute and `</knowledge` escaping; total <= maxChars with a truncation marker. */
        internal fun fence(blocks: List<Pair<String, String>>, maxChars: Int): String {
            val sb = StringBuilder()
            for ((name, text) in blocks) {
                val n = name.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                val body = text.replace("</knowledge", "<\\/knowledge")
                val open = "<knowledge source=\"$n\">\n"; val close = "\n</knowledge>\n"
                val room = maxChars - sb.length - open.length - close.length
                if (room <= 0) { sb.append("…[pinned text truncated]"); break }
                if (body.length > room) { sb.append(open).append(body.take(room)).append("…[pinned text truncated]").append(close); break }
                sb.append(open).append(body).append(close)
            }
            return sb.toString().trimEnd()
        }

        private fun KnowledgeSourceEntity.toModel() = KnowledgeSource(
            id, name, runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.TEXT), uri, mime, bytes, chunks, chars, indexedAt, error, pinned, grp, parentId, createdAt,
        )
    }

    private val mutex = Mutex()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val sources: Flow<List<KnowledgeSource>> = dao.knowledgeSourcesFlow().map { l -> l.map { it.toModel() } }
    val progress: StateFlow<Map<String, Float>> get() = _progress

    suspend fun names(): List<String> {
        val rows = dao.knowledgeSources()
        return (rows.map { it.name } + rows.map { it.grp }.filter { it.isNotBlank() } + "all").distinct()
    }

    suspend fun usage(): Usage {
        val rows = dao.knowledgeSources()
        val st = dao.chunkStats()
        return Usage(rows.count { it.kind != SourceKind.FOLDER.name }, st.chunks, st.chars)
    }

    // ---- add

    suspend fun addText(name: String, text: String, group: String = "", pinned: Boolean = false, replace: Boolean = true): KnowledgeSource {
        val n = name.trim().ifEmpty { text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(60) ?: "Text" }
        val row = newOrExisting(n, SourceKind.TEXT, null, group, pinned, replace)
        val r = row.copy(uri = URI_TEXT + row.id, mime = "text/plain")
        return index(r) { Loaded(text.toByteArray(), "text/plain", r.name, null) }
    }

    suspend fun addDocument(uri: Uri, name: String? = null, group: String = "", pinned: Boolean = false, replace: Boolean = true): KnowledgeSource {
        checkFileUri(uri)
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }   // share-cache file:// and non-persistable grants skip it
        val meta = docMeta(uri)
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: meta.name
        val existing = dao.knowledgeSourceByName(n)
        if (existing == null || !replace) {
            val docs = dao.knowledgeSources().count { it.kind == SourceKind.DOCUMENT.name && it.parentId == null }
            if (docs >= MAX_DOCUMENTS) throw NodeException("Too many documents ($MAX_DOCUMENTS) — add a folder instead")
        }
        val row = newOrExisting(n, SourceKind.DOCUMENT, uri.toString(), group, pinned, replace).copy(mime = meta.mime, uri = uri.toString())
        return index(row) { loadDocument(uri, row.name) }
    }

    suspend fun addFolder(treeUri: Uri, name: String? = null): KnowledgeSource {
        runCatching { app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: runCatching { docMeta(docUri).name }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "Folder"
        val row = newOrExisting(n, SourceKind.FOLDER, treeUri.toString(), n, false, true).copy(uri = treeUri.toString(), grp = n)
        dao.upsertKnowledgeSource(row)
        return walkFolder(row)
    }

    suspend fun addUrl(url: String, name: String? = null, group: String = "", pinned: Boolean = false, replace: Boolean = true): KnowledgeSource {
        val u = url.trim()
        val parsed = runCatching { URI(u) }.getOrNull()
        val host = parsed?.host ?: throw NodeException("Not a valid URL: $u")
        when (parsed.scheme?.lowercase()) {
            "https" -> {}
            "http" -> if (!isLanHost(host)) throw NodeException("Plain http:// is only allowed for local-network hosts; use https://")
            else -> throw NodeException("URL must start with http:// or https://")
        }
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: (host + (parsed.path ?: "").trimEnd('/')).take(80)
        val row = newOrExisting(n, SourceKind.URL, u, group, pinned, replace).copy(uri = u)
        return index(row) { fetchUrl(u, row.name) }
    }

    /** NOTES | PLAYLIST snapshot rows (uri mob8n://notes | mob8n://playlist); disabling removes the row. */
    suspend fun setBuiltin(kind: SourceKind, enabled: Boolean) {
        val (id, uri, name) = when (kind) {
            SourceKind.NOTES -> Triple(ID_NOTES, URI_NOTES, "Notes")
            SourceKind.PLAYLIST -> Triple(ID_PLAYLIST, URI_PLAYLIST, "Playlist")
            else -> throw NodeException("Not a builtin source: $kind")
        }
        if (!enabled) { remove(id); return }
        val existing = dao.knowledgeSource(id)
        val row = existing ?: KnowledgeSourceEntity(id, uniqueName(name), kind.name, uri, "text/plain", 0, 0, 0, null, null, null, false, "", null, nowMs())
        index(row) { Loaded(snapshot(kind).toByteArray(), "text/plain", row.name, null) }
    }

    // ---- maintain

    /** DOCUMENT: re-read; FOLDER: re-walk + diff; URL: refetch; NOTES/PLAYLIST: re-snapshot; TEXT: no-op. */
    suspend fun reindex(id: String): KnowledgeSource {
        val row = dao.knowledgeSource(id) ?: throw NodeException("Knowledge source not found: $id")
        return when (row.kind) {
            SourceKind.DOCUMENT.name -> { val u = Uri.parse(row.uri ?: throw NodeException("${row.name}: no document uri")); index(row) { loadDocument(u, row.name) } }
            SourceKind.FOLDER.name -> walkFolder(row)
            SourceKind.URL.name -> index(row) { fetchUrl(row.uri ?: throw NodeException("${row.name}: no url"), row.name) }
            SourceKind.NOTES.name -> index(row) { Loaded(snapshot(SourceKind.NOTES).toByteArray(), "text/plain", row.name, null) }
            SourceKind.PLAYLIST.name -> index(row) { Loaded(snapshot(SourceKind.PLAYLIST).toByteArray(), "text/plain", row.name, null) }
            else -> row.toModel()
        }
    }

    /** HousekeepingWorker: stale FOLDER/URL/NOTES/PLAYLIST rows and DOCUMENTs whose LAST_MODIFIED/SIZE changed; oldest first; stops at the budget. Never throws. */
    suspend fun reindexStale(budgetMs: Long = 5 * 60_000L): Int {
        val start = nowMs()
        var n = 0
        try {
            val rows = dao.knowledgeSources().filter { it.parentId == null }.sortedBy { it.indexedAt ?: 0L }
            val online = isOnline()
            for (r in rows) {
                if (nowMs() - start > budgetMs) break
                val kind = runCatching { SourceKind.valueOf(r.kind) }.getOrNull() ?: continue
                val due = when (kind) {
                    SourceKind.DOCUMENT -> r.uri != null && documentChanged(r)
                    SourceKind.URL -> online && isStale(kind, r.indexedAt, nowMs())
                    else -> isStale(kind, r.indexedAt, nowMs())
                }
                if (!due) continue
                try { reindex(r.id); n++ } catch (e: Exception) { Log.w(LOG_TAG, "knowledge: re-index ${r.name}: ${e.message}") }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "knowledge: reindexStale: ${e.message}")
        }
        return n
    }

    /** FOLDER -> children too; releases the persisted grant when no other row uses the uri. */
    suspend fun remove(id: String) {
        val row = dao.knowledgeSource(id) ?: return
        val uris = (listOf(row.uri) + dao.knowledgeChildren(id).map { it.uri }).filterNotNull().filter { it.startsWith("content://") }
        dao.deleteKnowledgeSource(id)
        _progress.update { it - id }
        val stillUsed = dao.knowledgeSources().mapNotNull { it.uri }.toSet()
        for (u in uris) if (u !in stillUsed && (row.kind != SourceKind.FOLDER.name || u == row.uri)) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    suspend fun removeByName(nameOrId: String): Int {
        val t = nameOrId.trim()
        val row = dao.knowledgeSourceByName(t) ?: dao.knowledgeSource(t) ?: return 0
        remove(row.id)
        return 1
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        val row = dao.knowledgeSource(id) ?: return
        dao.upsertKnowledgeSource(row.copy(pinned = pinned))
    }

    // ---- query

    suspend fun resolve(labels: List<String>): List<String> = resolve(dao.knowledgeSources().map { it.toModel() }, labels)

    /** FTS MATCH (AND, then OR when empty) -> <= 2000 matchinfo rows -> BM25 (+0.5 source-name boost) -> top-k texts; scores normalised to (0, 1]. */
    suspend fun search(query: String, k: Int = 5, sourceIds: List<String> = emptyList()): List<Hit> {
        val kk = k.coerceIn(1, 20)
        val tokens = Bm25.tokens(query)
        val expr = Bm25.matchExpr(query) ?: return emptyList()
        val ids = sourceIds.filter { it.isNotBlank() }.distinct()
        var rows = match(expr, ids)
        if (rows.isEmpty() && tokens.size > 1) rows = match(Bm25.matchExpr(query, orMode = true) ?: return emptyList(), ids)
        if (rows.isEmpty()) return emptyList()
        val names = dao.knowledgeSources().associate { it.id to it.name }
        val scored = rows.map { m ->
            val name = names[m.sourceId] ?: ""
            val lower = name.lowercase()
            val boost = if (tokens.any { lower.contains(it) }) NAME_BOOST else 0.0
            Triple(m, Bm25.score(Bm25.ints(m.mi)) + boost, name)
        }.sortedWith(compareByDescending<Triple<ChunkMatch, Double, String>> { it.second }.thenBy { it.first.seq }).take(kk)
        val max = scored.maxOf { it.second }.takeIf { it > 0.0 } ?: 1.0
        val texts = dao.chunksByRowid(scored.map { it.first.rowid }).associate { it.rowid to it.text }
        return scored.map { (m, s, name) -> Hit(texts[m.rowid].orEmpty().take(HIT_CHARS), name, m.sourceId, m.seq, ((s / max) * 1000).roundToInt() / 1000.0) }
    }

    private suspend fun match(expr: String, ids: List<String>) = runCatching {
        when {
            ids.isEmpty() -> dao.matchAll(expr, Bm25.CANDIDATES)
            ids.size <= IN_LIMIT -> dao.matchIn(expr, ids, Bm25.CANDIDATES)
            else -> { val set = ids.toSet(); dao.matchAll(expr, Bm25.CANDIDATES).filter { it.sourceId in set } }
        }
    }.getOrElse { e -> Log.w(LOG_TAG, "knowledge: MATCH failed: ${e.message}"); emptyList() }

    /** Fenced blocks of the pinned rows among [sourceIds], sorted by name; "" when none. */
    suspend fun pinnedText(sourceIds: List<String>, maxChars: Int = PINNED_CAP): String {
        val rows = sourceIds.distinct().mapNotNull { dao.knowledgeSource(it) }.filter { it.pinned }.sortedBy { it.name.lowercase() }
        if (rows.isEmpty()) return ""
        val blocks = rows.map { r -> r.name to dao.chunkTexts(r.id, 40).joinToString("\n").take(PINNED_PER_SOURCE) }.filter { it.second.isNotBlank() }
        return if (blocks.isEmpty()) "" else fence(blocks, maxChars)
    }

    // ---- indexing core

    private class Loaded(val bytes: ByteArray, val mime: String?, val name: String, val lastModified: Long?)

    /**
     * ONE global indexing Mutex; Dispatchers.IO. Upserts the row first (visible as indexing), reads (<= MAX_RAW), extracts, chunks, replaces the
     * chunks atomically, then stamps the row. Failure -> row.error = message (a re-index failure keeps the old chunks; a first-time failure removes
     * them) and NodeException rethrown with the user text.
     */
    private suspend fun index(row: KnowledgeSourceEntity, load: suspend () -> Loaded): KnowledgeSource = mutex.withLock {
        withContext(Dispatchers.IO) {
            dao.upsertKnowledgeSource(row)
            _progress.update { it + (row.id to 0f) }
            try {
                val loaded = load()
                _progress.update { it + (row.id to 0.3f) }
                val extracted = Extract.extract(loaded.bytes, loaded.mime ?: row.mime, loaded.name)
                val chunks = Chunk.split(extracted.text)
                val chars = chunks.sumOf { it.length }
                val stats = dao.chunkStats()
                if (stats.chars - row.chars + chars > MAX_INDEX_CHARS) throw NodeException("Knowledge index is full (40 MB of text) — remove sources")
                _progress.update { it + (row.id to 0.6f) }
                dao.replaceChunks(row.id, chunks.mapIndexed { i, t -> KnowledgeChunkEntity(0, row.id, i, t) })
                _progress.update { it + (row.id to 0.9f) }
                val marker = when {
                    chunks.size >= Chunk.MAX_CHUNKS -> "truncated: ${Chunk.MAX_CHUNKS} chunks"
                    extracted.truncated -> "truncated at 2 MB"
                    else -> null
                }
                val done = row.copy(
                    mime = loaded.mime ?: row.mime, bytes = loaded.bytes.size.toLong(), chunks = chunks.size, chars = chars,
                    indexedAt = nowMs(), lastModified = loaded.lastModified ?: row.lastModified, error = marker,
                )
                dao.upsertKnowledgeSource(done)
                done.toModel()
            } catch (e: Exception) {
                val msg = userMessage(e, row)
                val failed = row.copy(error = msg, indexedAt = row.indexedAt)
                if (row.indexedAt == null) { runCatching { dao.deleteChunks(row.id) }; dao.upsertKnowledgeSource(failed.copy(chunks = 0, chars = 0)) }
                else dao.upsertKnowledgeSource(failed)
                throw if (e is NodeException) e else NodeException(msg, e)
            } finally {
                _progress.update { it - row.id }
            }
        }
    }

    private fun userMessage(e: Exception, row: KnowledgeSourceEntity): String = when (e) {
        is NodeException -> e.message ?: "failed"
        is SecurityException -> "Cannot open document — permission lost, add it again"
        is FileNotFoundException -> if (row.indexedAt != null) "original file gone; indexed text kept" else "Cannot open document — file not found"
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException -> "Not reachable now"
        is java.io.IOException -> if (row.kind == SourceKind.URL.name) "Not reachable now" else "Cannot read document: ${e.message?.take(120)}"
        else -> (e.message ?: e.javaClass.simpleName).take(200)
    }

    /** The existing same-name row (replace = true) or a fresh row with a unique name (" 2", " 3" suffixes). */
    private suspend fun newOrExisting(name: String, kind: SourceKind, uri: String?, group: String, pinned: Boolean, replace: Boolean): KnowledgeSourceEntity {
        val existing = dao.knowledgeSourceByName(name)
        if (existing != null && replace) return existing.copy(kind = kind.name, uri = uri ?: existing.uri, grp = group.ifBlank { existing.grp }, pinned = pinned || existing.pinned)
        val n = if (existing == null) name else uniqueName(name)
        return KnowledgeSourceEntity(UUID.randomUUID().toString(), n, kind.name, uri, null, 0, 0, 0, null, null, null, pinned, group, null, nowMs())
    }

    private suspend fun uniqueName(base: String): String {
        if (dao.knowledgeSourceByName(base) == null) return base
        var i = 2
        while (dao.knowledgeSourceByName("$base $i") != null) i++
        return "$base $i"
    }

    private suspend fun snapshot(kind: SourceKind): String = when (kind) {
        SourceKind.NOTES -> dao.notesFlow().first().joinToString("\n\n") { n -> n.title.trim() + "\n" + n.body.trim() }
        SourceKind.PLAYLIST -> dao.playlistFlow().first().joinToString("\n") { e ->
            e.title + (e.artist?.let { " — $it" } ?: "") + (e.album?.let { " ($it)" } ?: "") + " [${e.playlist}]"
        }
        else -> ""
    }

    // ---- documents (SAF + engine-private files)

    private class Meta(val name: String, val mime: String?, val size: Long?, val lastModified: Long?, val virtual: Boolean)

    private fun checkFileUri(uri: Uri) {
        if (uri.scheme != "file") return
        val f = File(uri.path ?: throw NodeException("Invalid file uri"))
        val canon = f.canonicalPath
        val allowed = listOf(app.filesDir, app.cacheDir).map { it.canonicalPath }
        if (allowed.none { canon.startsWith(it + File.separator) }) throw NodeException("file:// documents must live inside Mahout's own storage")
    }

    private fun docMeta(uri: Uri): Meta {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return Meta(f.name.ifEmpty { "file" }, null, f.length().takeIf { f.exists() }, f.lastModified().takeIf { f.exists() }, false)
        }
        var name: String? = null; var size: Long? = null; var lm: Long? = null; var mime: String? = null; var flags = 0
        runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
                    fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getLong(it) }
                    name = str(OpenableColumns.DISPLAY_NAME); size = long(OpenableColumns.SIZE)
                    lm = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED); mime = str(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    flags = long(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() ?: 0
                }
            }
        }
        val type = mime ?: runCatching { app.contentResolver.getType(uri) }.getOrNull()
        val virtual = (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0 || type?.startsWith("application/vnd.google-apps.") == true
        return Meta(name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "document", type, size, lm, virtual)
    }

    private fun loadDocument(uri: Uri, rowName: String): Loaded {
        if (uri.scheme == "file") {
            checkFileUri(uri)
            val f = File(uri.path!!)
            if (!f.isFile) throw FileNotFoundException(f.name)
            if (f.length() > Extract.MAX_RAW) throw NodeException("${f.name} is larger than 8 MB")
            return Loaded(FileInputStream(f).use { readCapped(it) }, null, f.name, f.lastModified())
        }
        val meta = docMeta(uri)
        val name = meta.name.ifBlank { rowName }
        if (meta.size != null && meta.size > Extract.MAX_RAW) throw NodeException("$name is larger than 8 MB")
        if (meta.virtual) {
            val types = runCatching { app.contentResolver.getStreamTypes(uri, "text/*") }.getOrNull()
            val type = types?.firstOrNull { it.equals("text/plain", true) } ?: types?.firstOrNull()
                ?: throw NodeException("This Google Docs file cannot be exported as text by its provider")
            val afd = app.contentResolver.openTypedAssetFileDescriptor(uri, type, null) ?: throw FileNotFoundException(name)
            val bytes = afd.createInputStream().use { readCapped(it) }
            return Loaded(bytes, type, if (name.contains('.')) name else "$name.txt", meta.lastModified)
        }
        val input = app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(name)
        return Loaded(input.use { readCapped(it) }, meta.mime, name, meta.lastModified)
    }

    /** Reads at most MAX_RAW bytes; anything larger is a NodeException (never buffers more than the cap). */
    private fun readCapped(input: InputStream, cap: Int = Extract.MAX_RAW): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) throw NodeException("Content larger than ${cap / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun documentChanged(r: KnowledgeSourceEntity): Boolean {
        val uri = Uri.parse(r.uri ?: return false)
        val m = runCatching { docMeta(uri) }.getOrNull() ?: return false
        val lmChanged = m.lastModified != null && r.lastModified != null && m.lastModified != r.lastModified
        val sizeChanged = m.size != null && r.bytes > 0 && m.size != r.bytes
        return lmChanged || sizeChanged
    }

    // ---- folders

    private class Entry(val uri: String, val name: String, val mime: String?, val size: Long?, val lastModified: Long?)

    /** Re-walk + diff: new files indexed, changed lastModified/size re-extracted, missing files removed. The folder row's progress = files done / total. */
    private suspend fun walkFolder(folder: KnowledgeSourceEntity): KnowledgeSource {
        val tree = Uri.parse(folder.uri ?: throw NodeException("${folder.name}: no folder uri"))
        _progress.update { it + (folder.id to 0f) }
        try {
            val entries = ArrayList<Entry>()
            var stopped = false
            withContext(Dispatchers.IO) {
                try {
                    stopped = walk(tree, DocumentsContract.getTreeDocumentId(tree), 0, entries)
                } catch (e: SecurityException) {
                    throw NodeException("Cannot open folder — permission lost, add it again")
                }
            }
            val existing = dao.knowledgeChildren(folder.id).associateBy { it.uri.orEmpty() }
            val seen = HashSet<String>()
            var done = 0
            for (e in entries) {
                seen += e.uri
                val old = existing[e.uri]
                val changed = old == null || old.indexedAt == null || (e.lastModified != null && e.lastModified != old.lastModified) || (e.size != null && e.size != old.bytes)
                if (changed) {
                    val childName = if (old != null) old.name else uniqueName(e.name)
                    val row = old?.copy(mime = e.mime ?: old.mime)
                        ?: KnowledgeSourceEntity(UUID.randomUUID().toString(), childName, SourceKind.DOCUMENT.name, e.uri, e.mime, 0, 0, 0, null, null, null, false, folder.grp, folder.id, nowMs())
                    try { index(row) { loadDocument(Uri.parse(e.uri), row.name) } } catch (ex: Exception) { Log.w(LOG_TAG, "knowledge: ${row.name}: ${ex.message}") }
                }
                done++
                _progress.update { it + (folder.id to done.toFloat() / entries.size.coerceAtLeast(1)) }
            }
            for ((uri, old) in existing) if (uri !in seen) dao.deleteKnowledgeSource(old.id)
            val children = dao.knowledgeChildren(folder.id)
            val stamped = folder.copy(
                bytes = children.sumOf { it.bytes }, chunks = children.sumOf { it.chunks }, chars = children.sumOf { it.chars }, indexedAt = nowMs(),
                error = if (stopped) "Folder walk stopped at $MAX_FOLDER_FILES files" else null,
            )
            dao.upsertKnowledgeSource(stamped)
            if (stopped) throw NodeException("Folder walk stopped at $MAX_FOLDER_FILES files")
            return stamped.toModel()
        } catch (e: Exception) {
            val msg = userMessage(e, folder)
            val cur = dao.knowledgeSource(folder.id) ?: folder
            if (cur.error != msg) dao.upsertKnowledgeSource(cur.copy(error = msg))
            throw if (e is NodeException) e else NodeException(msg, e)
        } finally {
            _progress.update { it - folder.id }
        }
    }

    /** ONE query per directory (never DocumentFile.listFiles); depth <= MAX_DEPTH; dot-names skipped; returns true when the file cap stopped the walk. */
    private fun walk(tree: Uri, docId: String, depth: Int, out: MutableList<Entry>): Boolean {
        if (depth > MAX_DEPTH) return false
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = ArrayList<String>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        app.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: id.substringAfterLast('/')
                if (name.startsWith(".")) continue
                val mime = if (c.isNull(2)) null else c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { dirs += id; continue }
                if (!Extract.supports(mime, name)) continue          // MIME filter + extension fallback (Drive reports octet-stream for .md)
                if (out.size >= MAX_FOLDER_FILES) return true
                out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, mime, if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4))
            }
        }
        for (d in dirs) if (walk(tree, d, depth + 1, out)) return true
        return false
    }

    // ---- URL

    /**
     * // ponytail: engine-private 25-line GET (data.Http not importable); upgrade = move Http to core when it unfreezes.
     * https or LAN http, <= 3 redirects (re-validated), 30 s timeouts, <= 8 MB, charset from Content-Type, non-text types rejected.
     */
    private fun fetchUrl(url: String, rowName: String): Loaded {
        var current = url
        repeat(URL_REDIRECTS + 1) { hop ->
            val u = URI(current)
            val host = u.host ?: throw NodeException("Not a valid URL: $current")
            if (!(u.scheme.equals("https", true) || (u.scheme.equals("http", true) && isLanHost(host)))) throw NodeException("Only https:// (or LAN http://) URLs can be indexed")
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = URL_TIMEOUT_MS; conn.readTimeout = URL_TIMEOUT_MS; conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "text/html, text/plain, text/markdown, application/json;q=0.9, */*;q=0.5")
                conn.setRequestProperty("User-Agent", "Mahout/knowledge")
                val status = conn.responseCode
                if (status in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw NodeException("HTTP $status without a Location header")
                    if (hop >= URL_REDIRECTS) throw NodeException("Too many redirects")
                    current = URI(current).resolve(loc).toString()
                    return@repeat
                }
                if (status !in 200..299) throw NodeException("HTTP $status from $host")
                val contentType = conn.contentType ?: "text/plain"
                val mime = contentType.substringBefore(';').trim().lowercase()
                if (Extract.kindOf(mime, "") == null || mime.startsWith("application/vnd.openxml")) throw NodeException("$host returned $mime, not text")
                var bytes = (conn.inputStream ?: throw NodeException("Empty response")).use { readCapped(it) }
                val cs = charsetOf(contentType)
                if (cs != Charsets.UTF_8) bytes = String(bytes, cs).toByteArray(Charsets.UTF_8)
                val name = u.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: rowName
                return Loaded(bytes, mime, if (Extract.kindOf(mime, name) != null) name else "$name.txt", null)
            } finally {
                conn.disconnect()
            }
        }
        throw NodeException("Too many redirects")
    }

    private fun charsetOf(contentType: String?): Charset = contentType?.split(';')?.drop(1)?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }?.substringAfter('=')?.trim('"', '\'', ' ')
        ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    private fun isOnline(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetwork != null
    }.getOrDefault(true)
}
