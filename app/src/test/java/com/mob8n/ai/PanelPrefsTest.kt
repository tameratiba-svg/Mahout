package com.mob8n.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN5 §3.5 / §8.3: the pure URL rule (LAN-only http, no userinfo, no secrets in query) and slugs. */
class PanelPrefsTest {
    private fun rejected(url: String): String = try { PanelPrefs.validateUrl(url); fail("accepted $url"); "" } catch (e: IllegalArgumentException) { e.message!! }

    @Test fun validateUrlRules() {
        assertEquals("https://www.openstreetmap.org", PanelPrefs.validateUrl(" https://www.openstreetmap.org "))
        assertEquals("http://192.168.1.5:4173", PanelPrefs.validateUrl("http://192.168.1.5:4173"))
        assertEquals("http://mac.local:8790/camera/Drone1", PanelPrefs.validateUrl("http://mac.local:8790/camera/Drone1"))
        assertEquals("http://10.0.0.61:8790/snapshot?vehicle=Drone1", PanelPrefs.validateUrl("http://10.0.0.61:8790/snapshot?vehicle=Drone1"))
        assertTrue(rejected("http://example.com").contains("local-network"))
        assertTrue(rejected("http://8.8.8.8:4173").contains("local-network"))
        assertTrue(rejected("ftp://192.168.1.5/x").contains("Only http"))
        assertTrue(rejected("javascript:alert(1)").contains("Only http"))
        assertTrue(rejected("file:///sdcard/x.html").contains("Only http"))
        assertTrue(rejected("content://com.x/1").contains("Only http"))
        assertTrue(rejected("data:text/html,hi").contains("Only http"))
        assertTrue(rejected("https://user:pw@example.com").contains("Credentials"))
        assertTrue(rejected("http://192.168.1.5:8790/camera/Drone1?token=abc").contains("Secrets never ride in URLs"))
        assertTrue(rejected("https://example.com/x?a=1&api_key=abc").contains("Secrets never ride in URLs"))
        assertTrue(rejected("192.168.1.5:4173").isNotBlank())
        assertTrue(rejected("").contains("required"))
    }

    @Test fun slugs() {
        assertEquals("god-s-eye-view", PanelPrefs.slug("God's Eye View"))
        assertEquals("drone1-cam", PanelPrefs.slug("  Drone1 cam!! "))
        assertEquals(40, PanelPrefs.slug("x".repeat(80)).length)
        assertEquals("panel_abc_auth", PanelPrefs.secretName("abc"))
        assertEquals(8, PanelPrefs.MAX_PANELS)
    }
}
