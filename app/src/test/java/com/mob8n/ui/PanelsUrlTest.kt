package com.mob8n.ui

import com.mob8n.ai.Panel
import com.mob8n.ai.PanelKind
import com.mob8n.ai.PanelPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/** DESIGN5 §8.3 / §9 ui: the Panels URL rules (delegating to PanelPrefs.validateUrl), the WebView navigation rule, presets and grid selection. */
class PanelsUrlTest {
    private fun ok(url: String) = runCatching { PanelPrefs.validateUrl(url) }.isSuccess

    @Test fun validateUrlLanRule() {
        for (u in listOf("https://www.openstreetmap.org", "http://192.168.1.5:4173", "http://mac.local:8790", "http://10.0.0.2:8790/camera/Drone1", "http://localhost:4173"))
            assertTrue(u, ok(u))
        for (u in listOf("http://example.com", "http://8.8.8.8:8000", "ftp://192.168.1.5/x", "javascript:alert(1)", "file:///sdcard/x.html", "content://x/y", "data:text/html,hi",
            "https://user:pw@example.com/", "http://192.168.1.5:8790/camera/Drone1?token=abc", "https://example.com/?key=abc"))
            assertFalse(u, ok(u))
    }

    @Test fun presetsProduceValidUrls() {
        assertEquals(listOf("God's Eye View", "UAV camera", "Any URL"), PANEL_PRESETS.map { it.label })
        val gev = PANEL_PRESETS[0]; val cam = PANEL_PRESETS[1]
        assertEquals("http://192.168.1.10:4173", presetUrl(gev, " 192.168.1.10 "))
        assertEquals("http://192.168.1.10:8790/camera/Drone1", presetUrl(cam, "192.168.1.10"))
        assertEquals(PanelKind.WEB, gev.kind); assertFalse(gev.authHeader)
        assertEquals(PanelKind.IMAGE, cam.kind); assertTrue(cam.authHeader); assertEquals(1_000L, cam.refreshMs)
        assertTrue(ok(presetUrl(gev, "192.168.1.10"))); assertTrue(ok(presetUrl(cam, "mac.local")))
        assertFalse(ok(presetUrl(cam, "203.0.113.7")))                 // a public IP never gets plain http
        assertFalse(PANEL_PRESETS.any { "token" in it.urlTemplate })   // secrets never ride in URLs
    }

    @Test fun webNavigationStaysOnTheSameHost() {
        val gev = "http://192.168.1.10:4173"
        assertTrue(panelNavAllowed(gev, "http://192.168.1.10:4173/#/map"))
        assertTrue(panelNavAllowed(gev, "https://192.168.1.10/x"))
        assertFalse(panelNavAllowed(gev, "http://192.168.1.11:4173/"))
        assertFalse(panelNavAllowed(gev, "https://evil.example.com/"))
        assertFalse(panelNavAllowed(gev, "intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(panelNavAllowed(gev, "file:///sdcard/x"))
        assertFalse(panelNavAllowed(gev, "javascript:alert(1)"))
        assertFalse(panelNavAllowed(gev, "http://u:p@192.168.1.10:4173/"))
        val osm = "https://www.openstreetmap.org"
        assertTrue(panelNavAllowed(osm, "https://WWW.openstreetmap.org/#map=5/1/2"))
        assertFalse(panelNavAllowed(osm, "http://www.openstreetmap.org/"))    // https panel never downgrades
        assertTrue(panelNavAllowed(osm, "https://openstreetmap.org/"))          // same registrable domain (v5 device phase, F5)
        assertFalse(panelNavAllowed("not a url", "https://x.org"))
    }

    @Test fun crossHostRedirectsWithinTheSiteAndTheBlockedMessage() {
        val wiki = "https://en.m.wikipedia.org/wiki/Drone"
        assertTrue(panelNavAllowed(wiki, "https://en.wikipedia.org/wiki/Drone"))     // the device-phase redirect that left a blank tile
        assertTrue(panelNavAllowed(wiki, "https://upload.WIKIPEDIA.org./x"))
        assertFalse(panelNavAllowed(wiki, "https://wikipedia.org.evil.com/"))
        assertFalse(panelNavAllowed(wiki, "https://wikimedia.org/"))
        assertFalse(panelNavAllowed(wiki, "http://en.wikipedia.org/"))             // still never downgrades
        // IP literals and single-label hosts never widen to "the last two labels"
        assertFalse(panelNavAllowed("http://10.0.1.10:4173", "http://192.168.1.10:4173/"))
        assertFalse(panelNavAllowed("http://localhost:4173", "http://otherhost:4173/"))
        assertTrue(panelNavAllowed("http://mac.local:4173", "http://mac.local:8790/x"))
        assertFalse(panelNavAllowed("http://mac.local:4173", "http://pc.local:4173/"))
        assertEquals("Blocked navigation to accounts.example.com", blockedNavMessage("https://accounts.example.com/login?x=1"))
        assertEquals("Blocked navigation to intent", blockedNavMessage("intent:#Intent;end"))
        assertTrue(canOpenInBrowser("https://accounts.example.com/login"))
        assertFalse(canOpenInBrowser("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(canOpenInBrowser("javascript:alert(1)"))
        assertFalse(canOpenInBrowser("file:///sdcard/x"))
    }

    @Test fun gridSelection() {
        val ps = (1..6).map { Panel("p$it", "P$it", "https://p$it.org") }
        assertEquals(listOf("p1", "p2", "p3", "p4"), gridIds(null, ps))
        assertEquals(listOf("p5", "p2"), gridIds("p5,gone,p2,p5", ps))
        assertEquals(listOf("p1", "p2", "p3", "p4"), gridIds("gone", ps))
        assertEquals(listOf("p1", "p2", "p3", "p4"), gridIds("p1,p2,p3,p4,p5", ps))
        assertEquals(emptyList<String>(), gridIds(null, emptyList()))
        assertEquals(listOf("p2", "p3", "p4", "p5"), toggleGrid(listOf("p1", "p2", "p3", "p4"), "p5"))   // full -> oldest drops
        assertEquals(listOf("p1", "p3"), toggleGrid(listOf("p1", "p2", "p3"), "p2"))
        assertEquals(listOf("p1"), toggleGrid(listOf("p1"), "p1"))                                     // never empty
        assertEquals(listOf("p1", "p2"), toggleGrid(listOf("p1"), "p2"))
        assertEquals(4, PANELS_GRID_MAX)
    }

    @Test fun a11yAndErrorsAreText() {
        assertEquals("Panel GEV, 2 of 4", panelCellDescription("GEV", 1, 4))
        assertEquals("Panel GEV", panelCellDescription("GEV", null, 1))
        assertEquals("timed out", frameError(SocketTimeoutException("Read timed out")))
        assertEquals("cannot connect", frameError(ConnectException("failed to connect")))
        assertEquals("HTTP 401 — check the panel token", frameError(java.io.IOException("HTTP 401 — check the panel token")))
        assertEquals(5 shl 20, PANEL_FRAME_MAX)
        assertEquals(5_000, PANEL_READ_MS); assertEquals(3_000, PANEL_CONNECT_MS)
    }
}
