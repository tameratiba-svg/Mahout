package com.mob8n.apps

import com.mob8n.core.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * `/system/bin/sh -c <command>` as Mob8N's own sandboxed user (DESIGN4 §7.1). Tasker-class non-root shell: toybox only, no exec
 * from app storage (Android 10 W^X), no network as a feature. The environment is a FIXED map — the parent process environment
 * is never inherited, so no key can leak through env. Callers log only `redactedCommand(...)`.
 */
object Shell {
    const val DEFAULT_SH = "/system/bin/sh"
    const val OUT_CAP = 64 * 1024
    const val SPILL_CAP = 4 * 1024 * 1024
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MAX_STDIN = 256 * 1024
    private const val SPILL_DIR = ".mob8n"

    data class Result(
        val exitCode: Int, val stdout: String, val stderr: String, val stdoutTruncated: Boolean, val stderrTruncated: Boolean,
        val timedOut: Boolean, val ms: Long, val outputFile: String? /* workspace-relative spill file */,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("exitCode", exitCode); put("stdout", stdout); put("stderr", stderr)
            put("stdoutTruncated", stdoutTruncated); put("stderrTruncated", stderrTruncated)
            put("timedOut", timedOut); put("ms", ms); put("outputFile", outputFile)
        }
    }

    fun argv(command: String, sh: String = DEFAULT_SH): List<String> = listOf(sh, "-c", command)

    /** Pure, fixed: never System.getenv(). */
    fun env(cwd: File, tmp: File): Map<String, String> =
        mapOf("PATH" to "/system/bin:/system/xbin", "HOME" to cwd.path, "TMPDIR" to tmp.path, "LANG" to "C.UTF-8", "TERM" to "dumb")

    fun redactedCommand(command: String, secrets: Collection<String>): String = Redaction.redactText(command, secrets).take(500)

    fun status(): String = if (File(DEFAULT_SH).exists()) "available ($DEFAULT_SH, toybox)" else "sh not found"

    /**
     * stdout and stderr are drained concurrently (one reader deadlocks on a full pipe). Each keeps the first OUT_CAP chars; stdout
     * beyond that streams into `spillDir/.mob8n/out-<ts>.txt` up to SPILL_CAP, then is discarded. Timeout -> destroyForcibly,
     * exit -1. Cancellation of the calling coroutine destroys the process too.
     */
    suspend fun run(
        command: String, cwd: File, stdin: String? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS, sh: String = DEFAULT_SH,
        spillDir: File? = cwd, tmp: File = cwd, now: () -> Long = System::currentTimeMillis,
    ): Result {
        val t0 = now()
        cwd.mkdirs()
        val process = ProcessBuilder(argv(command, sh)).directory(cwd).apply { environment().clear(); environment().putAll(env(cwd, tmp)) }.start()
        val spill = spillDir?.let { File(it, "$SPILL_DIR/out-${t0}.txt") }
        try {
            return coroutineScope {
                val outD = async(Dispatchers.IO) { drain(process.inputStream, spill) }
                val errD = async(Dispatchers.IO) { drain(process.errorStream, null) }
                val inD = async(Dispatchers.IO) {
                    runCatching { process.outputStream.use { o -> stdin?.let { o.write(it.take(MAX_STDIN).toByteArray()) } } }
                }
                val exit = withTimeoutOrNull(timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
                val timedOut = exit == null
                if (timedOut) process.destroyForcibly()
                inD.await()
                val out = outD.await(); val err = errD.await()
                Result(
                    exitCode = exit ?: -1, stdout = out.text, stderr = err.text, stdoutTruncated = out.truncated, stderrTruncated = err.truncated,
                    timedOut = timedOut, ms = now() - t0,
                    outputFile = if (out.spilled && spillDir != null) spill?.relativeTo(spillDir)?.path else null,
                )
            }
        } catch (e: CancellationException) {
            process.destroyForcibly(); throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private class Drained(val text: String, val truncated: Boolean, val spilled: Boolean)

    /** Blocking; runs on IO. Keeps OUT_CAP chars in memory; the rest goes to `spill` (stdout only) up to SPILL_CAP. */
    private fun drain(input: InputStream, spill: File?): Drained {
        val sb = StringBuilder()
        var truncated = false
        var spilled = false
        var spillBytes = 0L
        var spillOut: FileOutputStream? = null
        try {
            input.reader(Charsets.UTF_8).use { r ->
                val cbuf = CharArray(8192)
                while (true) {
                    val n = r.read(cbuf)
                    if (n < 0) break
                    val room = OUT_CAP - sb.length
                    if (room > 0) sb.appendRange(cbuf, 0, minOf(n, room))
                    if (n > room) {
                        truncated = true
                        if (spill != null) {
                            if (spillOut == null) {
                                spill.parentFile?.mkdirs()
                                spillOut = FileOutputStream(spill)   // overflow only: the first OUT_CAP chars are in `stdout`
                                spilled = true
                            }
                            val rest = String(cbuf, maxOf(room, 0), n - maxOf(room, 0)).toByteArray()
                            if (spillBytes < SPILL_CAP) {
                                val take = minOf(rest.size.toLong(), SPILL_CAP - spillBytes).toInt()
                                spillOut!!.write(rest, 0, take); spillBytes += take
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // stream closed by destroyForcibly: keep what we have
        } finally {
            runCatching { spillOut?.close() }
        }
        return Drained(sb.toString(), truncated, spilled)
    }
}
