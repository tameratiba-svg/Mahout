package com.mob8n.actions

import com.mob8n.core.NodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Regression for F51/F61: the wallpaper download cap must fire on actual bytes, not only on Content-Length. */
class CappedInputStreamTest {
    @Test fun passesBodiesUpToTheCap() {
        assertEquals(1000, CappedInputStream(ByteArrayInputStream(ByteArray(1000)), 1000, "Image").readBytes().size)
    }

    @Test fun rejectsBulkReadsPastTheCap() {
        val e = assertThrows(NodeException::class.java) { CappedInputStream(ByteArrayInputStream(ByteArray(1001)), 1000, "Image").readBytes() }
        assertTrue(e.message!!, e.message!!.startsWith("Image larger than"))
    }

    @Test fun rejectsSingleByteReadsPastTheCap() {
        val s = CappedInputStream(ByteArrayInputStream(ByteArray(1001)), 1000, "Image")
        repeat(1000) { assertEquals(0, s.read()) }
        assertThrows(NodeException::class.java) { s.read() }
    }

    @Test fun reportsCapInMegabytes() {
        val cap = 25L * 1024 * 1024
        val e = assertThrows(NodeException::class.java) {
            CappedInputStream(object : java.io.InputStream() {
                var n = 0L
                override fun read(): Int = if (n++ <= cap) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (n > cap) return -1; val k = minOf(len.toLong(), cap + 1 - n).toInt(); n += k; return k }
            }, cap, "Image").readBytes()
        }
        assertEquals("Image larger than 25 MB", e.message)
    }
}
