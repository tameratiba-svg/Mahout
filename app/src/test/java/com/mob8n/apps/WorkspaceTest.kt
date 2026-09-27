package com.mob8n.apps

import com.mob8n.core.NodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root: File get() = File(tmp.root, "ws").also { it.mkdirs() }

    private fun rejects(rel: String) {
        try { Workspace.resolve(root, rel); fail("accepted '$rel'") } catch (_: NodeException) {}
    }

    @Test fun resolveRejectsEscapes() {
        rejects("../x"); rejects("/etc/passwd"); rejects("a/../../b"); rejects("a/..\\b"); rejects("a\u0000b"); rejects("a//b"); rejects(" a/ /b")
        rejects("x".repeat(300)); rejects("a/".repeat(2100) + "b")
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        rejects("link/secret.txt"); rejects("link")
    }

    @Test fun resolveAcceptsRelativePaths() {
        assertEquals(File(root, "a/b.txt").canonicalPath, Workspace.resolve(root, "a/b.txt").canonicalPath)
        assertEquals(File(root, "a").canonicalPath, Workspace.resolve(root, "./a").canonicalPath)
        assertEquals(root.canonicalPath, Workspace.resolve(root, "").canonicalPath)
        assertEquals(root.canonicalPath, Workspace.resolve(root, ".").canonicalPath)
        assertEquals("a/b.txt", Workspace.rel(root, File(root, "a/b.txt")))
    }

    @Test fun writeReadAppendAtomicOverwrite() {
        assertEquals(5L, Workspace.write(root, "notes/hello.md", "hello"))
        assertEquals("hello", Workspace.read(root, "notes/hello.md").text)
        Workspace.write(root, "notes/hello.md", " world", append = true)
        val r = Workspace.read(root, "notes/hello.md")
        assertEquals("hello world", r.text); assertFalse(r.truncated); assertEquals(11, r.totalChars); assertFalse(r.binary)
        Workspace.write(root, "notes/hello.md", "new")
        assertEquals("new", Workspace.read(root, "notes/hello.md").text)
        assertEquals(listOf("hello.md"), File(root, "notes").list()!!.toList())   // no temp file left behind
    }

    @Test fun writeRefusesOversizeAndDirs() {
        try { Workspace.write(root, "big.txt", "x".repeat(Workspace.MAX_FILE_BYTES + 1)); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("MB")) }
        Workspace.mkdir(root, "d")
        try { Workspace.write(root, "d", "x"); fail() } catch (_: NodeException) {}
        try { Workspace.write(root, "", "x"); fail() } catch (_: NodeException) {}
        assertFalse(File(root, "big.txt").exists())
    }

    @Test fun listDirsFirstAndCapped() {
        Workspace.write(root, "b.txt", "b"); Workspace.write(root, "a.txt", "a"); Workspace.write(root, ".hidden", "h")
        Workspace.mkdir(root, "zdir"); Workspace.mkdir(root, "adir")
        val l = Workspace.list(root)
        assertEquals(listOf("adir", "zdir", ".hidden", "a.txt", "b.txt"), l.map { it.path })
        assertTrue(l[0].dir); assertFalse(l[2].dir); assertEquals(1L, l[3].bytes)
        assertEquals(2, Workspace.list(root, limit = 2).size)
        assertEquals(emptyList<Workspace.Entry>(), Workspace.list(root, "missing"))
        for (i in 1..(Workspace.MAX_LIST + 10)) Workspace.write(root, "many/f$i.txt", "x")
        assertEquals(Workspace.MAX_LIST, Workspace.list(root, "many", limit = 10_000).size)
    }

    @Test fun readOffsetLimitTruncatedAndBinary() {
        Workspace.write(root, "t.txt", "0123456789")
        val r = Workspace.read(root, "t.txt", offsetChars = 2, limitChars = 3)
        assertEquals("234", r.text); assertTrue(r.truncated); assertEquals(10, r.totalChars)
        assertEquals("89", Workspace.read(root, "t.txt", offsetChars = 8, limitChars = 100).text)
        assertEquals("", Workspace.read(root, "t.txt", offsetChars = 50).text)
        File(root, "bin.dat").writeBytes(byteArrayOf(1, 2, 0, 3))
        val b = Workspace.read(root, "bin.dat")
        assertTrue(b.binary); assertEquals("", b.text)
        try { Workspace.read(root, "nope.txt"); fail() } catch (_: NodeException) {}
        Workspace.write(root, "long.txt", "y".repeat(Workspace.MAX_READ_CHARS + 10))
        val big = Workspace.read(root, "long.txt", limitChars = 1 shl 30)   // limit clamps to MAX_READ_CHARS
        assertEquals(Workspace.MAX_READ_CHARS, big.text.length); assertTrue(big.truncated); assertEquals(Workspace.MAX_READ_CHARS + 10, big.totalChars)
    }

    @Test fun deleteRulesAndSize() {
        Workspace.write(root, "d/f.txt", "abc")
        try { Workspace.delete(root, ""); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("root")) }
        try { Workspace.delete(root, "d"); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("not empty")) }
        assertEquals(1 to 3L, Workspace.size(root))
        assertTrue(Workspace.delete(root, "d/f.txt")); assertFalse(Workspace.delete(root, "d/f.txt"))
        assertTrue(Workspace.delete(root, "d"))
        assertEquals(0 to 0L, Workspace.size(root))
        assertTrue(Workspace.VISIBILITY.contains("Not browsable in the Files app"))
    }
}
