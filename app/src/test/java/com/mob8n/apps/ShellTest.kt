package com.mob8n.apps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ProcessBuilder runs on the JVM: every test passes sh = "/bin/sh" (DESIGN4 §11 apps). Real time, so runBlocking rather than runTest. */
class ShellTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sh = "/bin/sh"
    /** Shell.env() pins PATH to Android's /system/bin (never inherited), so JVM tests locate POSIX tools themselves. */
    private val P = "PATH=/bin:/usr/bin; "

    private fun run(cmd: String, stdin: String? = null, timeoutMs: Long = 10_000, spill: File? = null): Shell.Result = runBlocking {
        Shell.run(cmd, tmp.root, stdin, timeoutMs, sh, spillDir = spill ?: tmp.root)
    }

    @Test fun echoExitZero() {
        val r = run("echo hi")
        assertEquals(0, r.exitCode); assertEquals("hi\n", r.stdout); assertEquals("", r.stderr)
        assertFalse(r.timedOut); assertFalse(r.stdoutTruncated); assertNull(r.outputFile)
        assertTrue(r.ms >= 0)
    }

    @Test fun exitCodeAndStderrSeparate() {
        val r = run("echo out; echo err 1>&2; exit 3")
        assertEquals(3, r.exitCode); assertEquals("out\n", r.stdout); assertEquals("err\n", r.stderr)
    }

    @Test fun stdinRoundTrip() {
        val r = run(P + "cat", stdin = "line1\nline2")
        assertEquals("line1\nline2", r.stdout)
    }

    @Test fun capTruncatesAndSpills() {
        val r = run(P + "yes | head -c 200000")
        assertEquals(0, r.exitCode)
        assertTrue(r.stdoutTruncated); assertEquals(Shell.OUT_CAP, r.stdout.length)
        assertNotNull(r.outputFile)
        val spill = File(tmp.root, r.outputFile!!)
        assertTrue(spill.path, spill.isFile)
        assertTrue(spill.length() <= Shell.SPILL_CAP)
        assertEquals(200000L - Shell.OUT_CAP, spill.length())   // overflow only; head is in stdout
        assertTrue(r.outputFile!!.startsWith(".mob8n/out-"))
    }

    @Test fun timeoutKillsProcess() {
        val t0 = System.currentTimeMillis()
        val r = run(P + "sleep 5", timeoutMs = 1_000)   // coerced floor is 1 s
        assertTrue(r.timedOut); assertEquals(-1, r.exitCode)
        assertTrue("took ${r.ms}", r.ms < 3_000); assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun envIsFixedAndNotInherited() {
        val e = Shell.env(tmp.root, tmp.root)
        assertEquals(setOf("PATH", "HOME", "TMPDIR", "LANG", "TERM"), e.keys)
        assertEquals(tmp.root.path, e["HOME"])
        val out = run("/usr/bin/env").stdout   // absolute: our PATH has no /usr/bin
        val keys = out.lines().filter { it.contains('=') }.map { it.substringBefore('=') }.filter { it != "PWD" && it != "SHLVL" && it != "_" && it != "OLDPWD" }.toSet()
        assertEquals(setOf("PATH", "HOME", "TMPDIR", "LANG", "TERM"), keys)
        assertFalse(out.contains("USER=")); assertFalse(out.contains("JAVA_HOME="))
    }

    @Test fun redactedCommandMasksSecretsAndCaps() {
        val secret = "sk-ant-verysecret123"
        assertEquals("curl -H 'Authorization: ***'", Shell.redactedCommand("curl -H 'Authorization: $secret'", listOf(secret)))
        assertEquals("echo short1", Shell.redactedCommand("echo short1", listOf("short1")))   // < 8 chars are not masked (Redaction rule)
        assertEquals(500, Shell.redactedCommand("x".repeat(900), emptyList()).length)
    }

    @Test fun argvAndStatusAndJson() {
        assertEquals(listOf("/system/bin/sh", "-c", "ls"), Shell.argv("ls"))
        assertTrue(Shell.status() == "sh not found" || Shell.status().startsWith("available"))
        val j = run("echo x").toJson()
        assertEquals(setOf("exitCode", "stdout", "stderr", "stdoutTruncated", "stderrTruncated", "timedOut", "ms", "outputFile"), j.keys)
    }
}
