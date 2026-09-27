package com.mob8n.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN5 §8.1 / D9: godseye-uav only, untrusted, Bearer, LAN rule enforced on save. */
class McpPresetsTest {
    @Test fun godseyeOnlyUntrustedBearer() {
        assertEquals(listOf("godseye-uav"), McpPresets.ALL.map { it.id })
        assertFalse(McpPresets.ALL.any { it.id.contains("laya", ignoreCase = true) || it.name.contains("laya", ignoreCase = true) })
        val p = McpPresets.ALL.single()
        assertEquals(McpAuth.BEARER, p.auth); assertFalse(p.trusted); assertTrue(p.urlTemplate.contains(McpPresets.HOST))
        assertTrue(p.note.contains("UNTRUSTED")); assertFalse(p.note.contains("GODSEYE_TOKEN=")); assertTrue(p.note.contains("never share"))
    }

    @Test fun instantiateSubstitutesTheHostAndStaysUntrusted() {
        val a = McpPresets.instantiate(McpPresets.ALL.single(), " 192.168.1.10 ")
        val b = McpPresets.instantiate(McpPresets.ALL.single(), "192.168.1.10")
        assertEquals("http://192.168.1.10:8791/mcp", a.url); assertEquals("godseye-uav", a.name); assertEquals(McpAuth.BEARER, a.auth)
        assertFalse(a.trusted); assertFalse(a.hasSecret); assertNotEquals(a.id, b.id)
        assertEquals("http://192.168.1.10:8791/mcp", Providers.normalizeBaseUrl(a.url))                  // what McpPrefs.save runs
        try { Providers.normalizeBaseUrl(McpPresets.instantiate(McpPresets.ALL.single(), "8.8.8.8").url); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("local-network")) }
    }
}
