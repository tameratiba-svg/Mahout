package com.mob8n.core

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN3P §7.1: the pure half of the Gate model (kind / group / enforced / applies(sdk) / key / tables). Never calls
 * granted() or available() (Android), and always passes sdk explicitly (Build.VERSION.SDK_INT is 0 on the JVM).
 */
class GatesTest {
    private fun p(perm: String) = Gate.Permission(perm)

    @Test fun kindMapping() {
        assertEquals(GrantKind.RUNTIME, p(Manifest.permission.READ_CALENDAR).kind)
        assertEquals(GrantKind.BACKGROUND_LOCATION, p(Manifest.permission.ACCESS_BACKGROUND_LOCATION).kind)
        assertEquals(GrantKind.RUNTIME, Gate.PostNotifications.kind)
        for (g in listOf(Gate.NotificationListener, Gate.Accessibility, Gate.DndPolicy, Gate.WriteSettings, Gate.IgnoreBatteryOpt, Gate.ExactAlarm, Gate.Overlay, Gate.LocationOn))
            assertEquals(g.label, GrantKind.SETTINGS, g.kind)
        for (g in listOf(Gate.Feature(PackageManager.FEATURE_NFC, "NFC"), Gate.LiveHost, Gate.ForegroundOnly))
            assertEquals(g.label, GrantKind.INFO, g.kind)
    }

    @Test fun groupMapping() {
        for (g in listOf(Gate.NotificationListener, Gate.PostNotifications, Gate.IgnoreBatteryOpt, Gate.LiveHost)) assertEquals(g.label, GateGroup.ESSENTIAL, g.group)
        for (perm in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACTIVITY_RECOGNITION)) assertEquals(perm, GateGroup.CONNECTIVITY, p(perm).group)
        assertEquals(GateGroup.CONNECTIVITY, Gate.LocationOn.group)
        assertEquals(GateGroup.CONNECTIVITY, Gate.Feature(PackageManager.FEATURE_CAMERA_FLASH, "Camera flash").group)
        for (perm in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) assertEquals(perm, GateGroup.CONTENT, p(perm).group)
        for (g in listOf(Gate.Accessibility, Gate.Overlay, Gate.DndPolicy, Gate.WriteSettings, Gate.ExactAlarm, Gate.ForegroundOnly)) assertEquals(g.label, GateGroup.AUTOMATION, g.group)
    }

    @Test fun enforcedFlags() {
        for (g in listOf(Gate.LiveHost, Gate.ForegroundOnly, Gate.Overlay, Gate.LocationOn, Gate.Advisory(Gate.NotificationListener), Gate.Advisory(p(Manifest.permission.READ_CALENDAR))))
            assertFalse(g.label, g.enforced)
        for (g in listOf(p(Manifest.permission.READ_CALENDAR), Gate.PostNotifications, Gate.NotificationListener, Gate.Accessibility, Gate.DndPolicy, Gate.WriteSettings,
            Gate.IgnoreBatteryOpt, Gate.ExactAlarm, Gate.Feature(PackageManager.FEATURE_NFC, "NFC")))
            assertTrue(g.label, g.enforced)
    }

    @Test fun appliesRanges() {
        fun range(g: Gate, offSdk: Int, onSdk: Int) { assertFalse("${g.label} @$offSdk", g.applies(offSdk)); assertTrue("${g.label} @$onSdk", g.applies(onSdk)) }
        range(p(Manifest.permission.BLUETOOTH_CONNECT), 30, 31)
        range(p(Manifest.permission.READ_EXTERNAL_STORAGE), 33, 32)
        range(p(Manifest.permission.WRITE_EXTERNAL_STORAGE), 29, 28)
        range(p(Manifest.permission.READ_MEDIA_IMAGES), 32, 33)
        range(p(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), 33, 34)
        range(p(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 28, 29)
        range(p(Manifest.permission.ACTIVITY_RECOGNITION), 28, 29)
        range(Gate.PostNotifications, 32, 33)
        range(Gate.Overlay, 28, 29)
        range(Gate.ExactAlarm, 30, 31)
        assertTrue(p(Manifest.permission.READ_CALENDAR).applies(26))
        assertTrue(Gate.NotificationListener.applies(26))
        assertTrue(Gate.LocationOn.applies(26))
    }

    @Test fun advisoryIdentity() {
        val x = p(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(x, Gate.Advisory(x).key)
        assertSame(Gate.Overlay, Gate.Advisory(Gate.Overlay).key)
        assertEquals(x, Gate.Advisory(Gate.Advisory(x)).key)
        assertFalse((x as Gate) == Gate.Advisory(x))
        assertEquals(Gate.Advisory(x), Gate.Advisory(p(Manifest.permission.ACCESS_FINE_LOCATION)))
        assertEquals(1, listOf<Gate>(Gate.Advisory(x), x).map { it.key }.distinct().size)
        assertEquals(2, listOf<Gate>(Gate.Advisory(x), x).distinct().size)   // spec.gates.distinct() keeps them apart; the UI de-dups via key
        val a = Gate.Advisory(p(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        assertEquals(GrantKind.BACKGROUND_LOCATION, a.kind)
        assertEquals(GateGroup.CONNECTIVITY, a.group)
        assertFalse(a.applies(28)); assertTrue(a.applies(29))
        assertEquals(x.label, Gate.Advisory(x).label)
    }

    @Test fun requiresFeatureTable() {
        assertEquals(PackageManager.FEATURE_TELEPHONY, Gate.REQUIRES_FEATURE[Manifest.permission.READ_PHONE_STATE])
        assertEquals(PackageManager.FEATURE_TELEPHONY, Gate.REQUIRES_FEATURE[Manifest.permission.RECEIVE_SMS])
        assertEquals(PackageManager.FEATURE_BLUETOOTH, Gate.REQUIRES_FEATURE[Manifest.permission.BLUETOOTH_CONNECT])
        assertNull(Gate.REQUIRES_FEATURE[Manifest.permission.READ_CONTACTS])
    }

    @Test fun sdkRangeKeysAreDeclared() {
        val m = manifest()
        val missing = Gate.SDK_RANGE.keys.filter { """<uses-permission android:name="$it"""" !in m }
        assertTrue("SDK_RANGE keys not declared in AndroidManifest.xml: $missing", missing.isEmpty())
    }

    private fun manifest(): String {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"), File("../app/src/main/AndroidManifest.xml"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }
}
