package com.mob8n.apps

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.mob8n.core.NodeException
import java.io.File
import java.io.RandomAccessFile

/**
 * The coding sandbox's file root (DESIGN4 §7.4): `getExternalFilesDir(null)/workspace` — no permission, removed on uninstall,
 * NOT browsable in the Files app / system picker on Android 11+ (V12). Every path is resolved through [resolve] (canonical-path check).
 * // ponytail: workspace files reach flows only through app.shell_run / logic.js; upgrade = app.workspace_read/write nodes
 */
object Workspace {
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_READ_CHARS = 256 * 1024
    const val MAX_LIST = 500
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    const val MAX_NAME = 255
    const val MAX_PATH = 4 * 1024
    const val VISIBILITY = "Not browsable in the Files app on Android 11+; use Share/Export or adb pull"
    private const val SNIFF_BYTES = 8 * 1024

    fun root(ctx: Context): File = (ctx.getExternalFilesDir(null)?.let { File(it, "workspace") } ?: File(ctx.filesDir, "workspace")).also { it.mkdirs() }

    fun describe(ctx: Context): String = "${root(ctx).path} — $VISIBILITY"

    /** Pure: rejects absolute, "..", NUL, blank segments, long names/paths; the canonical child must be root or under root + separator (symlinks resolved). */
    fun resolve(root: File, rel: String): File {
        if (rel.length > MAX_PATH) throw NodeException("Path too long")
        if (rel.indexOf('\u0000') >= 0) throw NodeException("Path contains NUL")
        val norm = rel.replace('\\', '/').trim()
        if (norm.startsWith("/")) throw NodeException("Absolute paths are not allowed; paths are relative to the workspace")
        val segs = norm.split('/').filter { it != "." }
        if (norm.isNotEmpty() && norm != "." && segs.any { it.isBlank() }) throw NodeException("Blank path segment in '$rel'")
        for (s in segs) {
            if (s == "..") throw NodeException("'..' is not allowed in workspace paths")
            if (s.length > MAX_NAME) throw NodeException("File name longer than $MAX_NAME chars")
        }
        val child = if (segs.isEmpty()) root else File(root, segs.joinToString(File.separator))
        val rootC = root.canonicalPath
        val childC = child.canonicalPath
        if (childC != rootC && !childC.startsWith(rootC + File.separator)) throw NodeException("Path '$rel' escapes the workspace")
        return child
    }

    fun rel(root: File, f: File): String = f.canonicalFile.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    data class Entry(val path: String, val dir: Boolean, val bytes: Long, val modified: Long)

    /** Direct children of `dir`, dirs first then by name; dot-files included; at most `limit`. */
    fun list(root: File, dir: String = "", limit: Int = MAX_LIST): List<Entry> {
        val d = resolve(root, dir)
        if (!d.isDirectory) return emptyList()
        val kids = d.listFiles() ?: return emptyList()
        return kids.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name })).take(limit.coerceIn(1, MAX_LIST))
            .map { Entry(rel(root, it), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
    }

    data class Read(val text: String, val truncated: Boolean, val totalChars: Int, val binary: Boolean)

    /** UTF-8; binary sniff (NUL in the first 8 KB) -> binary = true, text = "". */
    fun read(root: File, path: String, offsetChars: Int = 0, limitChars: Int = MAX_READ_CHARS): Read {
        val f = resolve(root, path)
        if (!f.isFile) throw NodeException("No such file: $path")
        if (isBinary(f)) return Read("", false, 0, true)
        val all = f.readText(Charsets.UTF_8)
        val off = offsetChars.coerceIn(0, all.length)
        val lim = limitChars.coerceIn(0, MAX_READ_CHARS)
        val end = minOf(all.length, off + lim)
        return Read(all.substring(off, end), end < all.length, all.length, false)
    }

    private fun isBinary(f: File): Boolean {
        val n = minOf(f.length(), SNIFF_BYTES.toLong()).toInt()
        if (n <= 0) return false
        val buf = ByteArray(n)
        RandomAccessFile(f, "r").use { it.readFully(buf) }
        return buf.any { it == 0.toByte() }
    }

    /** Parents created inside root; overwrite = temp + rename; size bounds enforced. Returns the file's new length. */
    fun write(root: File, path: String, text: String, append: Boolean = false): Long {
        val f = resolve(root, path)
        if (f == root || f.isDirectory) throw NodeException("'$path' is a directory")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val existing = if (f.isFile) f.length() else 0L
        val newLen = if (append) existing + bytes.size else bytes.size.toLong()
        if (newLen > MAX_FILE_BYTES) throw NodeException("File would exceed ${MAX_FILE_BYTES / 1024 / 1024} MB")
        val total = size(root).second - existing + newLen
        if (total > MAX_TOTAL_BYTES) throw NodeException("Workspace would exceed ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
        f.parentFile?.mkdirs()
        if (append) {
            f.appendBytes(bytes)
        } else {
            val tmp = File(f.parentFile, ".${f.name}.${System.nanoTime()}.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw NodeException("Could not write $path") }
            } finally { tmp.delete() }
        }
        return f.length()
    }

    fun mkdir(root: File, dir: String) {
        val d = resolve(root, dir)
        if (d.isFile) throw NodeException("'$dir' is a file")
        if (!d.isDirectory && !d.mkdirs()) throw NodeException("Could not create $dir")
    }

    /** Files or EMPTY directories; refuses root. */
    fun delete(root: File, path: String): Boolean {
        val f = resolve(root, path)
        if (f.canonicalPath == root.canonicalPath) throw NodeException("Refusing to delete the workspace root")
        if (!f.exists()) return false
        if (f.isDirectory && !(f.listFiles()?.isEmpty() ?: true)) throw NodeException("Directory '$path' is not empty")
        return f.delete()
    }

    /** (files, bytes) under root. */
    fun size(root: File): Pair<Int, Long> {
        var files = 0; var bytes = 0L
        root.walkTopDown().forEach { if (it.isFile) { files++; bytes += it.length() } }
        return files to bytes
    }

    fun shareUri(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, Recipes.FILE_AUTHORITY, file)
}
