package com.mob8n.ui

import android.Manifest
import android.content.pm.PackageManager
import com.mob8n.core.Catalog
import com.mob8n.core.Gate
import com.mob8n.core.GateGroup
import com.mob8n.core.GrantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN3P §7.3: the pure half of the permissions center — statusOf truth table, the derived inventory over the real catalog,
 * and the Grant-all plan. Never calls granted()/available() (Android); sdk is always explicit (Build.VERSION.SDK_INT is 0 on the JVM).
 */
class PermissionStatusTest {
    private fun p(perm: String) = Gate.Permission(perm)
    private val catalog = Catalog(listOf(
        com.mob8n.triggers.TriggerNodes.all, com.mob8n.data.DataNodes.all, com.mob8n.logic.LogicNodes.all,
        com.mob8n.actions.ActionNodes.all, com.mob8n.ai.AiNodes.all, com.mob8n.apps.AppNodes.all,
    ))
    private fun st(gate: Gate, granted: Boolean = false, available: Boolean = true, applies: Boolean = true, asked: Boolean = false, rationale: Boolean = true,
                   attempted: Boolean = false, sdk: Int = 34, partial: Boolean = false) =
        statusOf(gate, granted, available, applies, asked, rationale, attempted, sdk, partial)

    @Test fun statusTruthTable() {
        val cal = p(Manifest.permission.READ_CALENDAR)
        assertEquals(GateStatus.NOT_APPLICABLE, st(p(Manifest.permission.READ_EXTERNAL_STORAGE), applies = false))
        assertEquals(GateStatus.UNAVAILABLE, st(Gate.Feature(PackageManager.FEATURE_NFC, "NFC"), available = false))
        assertEquals(GateStatus.UNAVAILABLE, st(p(Manifest.permission.READ_PHONE_STATE), available = false))
        assertEquals(GateStatus.INFO, st(Gate.Feature(PackageManager.FEATURE_NFC, "NFC"), granted = true))
        assertEquals(GateStatus.GRANTED, st(cal, granted = true))
        assertEquals(GateStatus.GRANTED, st(Gate.Accessibility, granted = true, attempted = true))
        assertEquals(GateStatus.PARTIAL, st(p(Manifest.permission.READ_MEDIA_IMAGES), partial = true))
        assertEquals(GateStatus.PARTIAL, st(Gate.Advisory(p(Manifest.permission.READ_MEDIA_VIDEO)), partial = true))
        assertEquals(GateStatus.NOT_GRANTED, st(p(Manifest.permission.READ_MEDIA_AUDIO), partial = true))
        assertEquals(GateStatus.PERMANENTLY_DENIED, st(cal, asked = true, rationale = false))
        assertEquals(GateStatus.NOT_GRANTED, st(cal, asked = false, rationale = false))          // never-asked is not permanently denied
        assertEquals(GateStatus.NOT_GRANTED, st(cal, asked = true, rationale = true))
        assertEquals(GateStatus.PERMANENTLY_DENIED, st(Gate.PostNotifications, asked = true, rationale = false))
        val bg = p(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(GrantKind.BACKGROUND_LOCATION, bg.kind)
        assertEquals(GateStatus.PERMANENTLY_DENIED, st(bg, asked = true, rationale = false))
        assertEquals(GateStatus.NOT_GRANTED, st(bg, asked = false, rationale = false))
        assertEquals(GateStatus.BLOCKED_RESTRICTED, st(Gate.Accessibility, attempted = true, sdk = 33))
        assertEquals(GateStatus.NOT_GRANTED, st(Gate.Accessibility, attempted = true, sdk = 32))
        assertEquals(GateStatus.NOT_GRANTED, st(Gate.Accessibility, attempted = false, sdk = 33))
        assertEquals(GateStatus.BLOCKED_RESTRICTED, st(Gate.NotificationListener, attempted = true, sdk = 33))
        assertEquals(GateStatus.NOT_GRANTED, st(Gate.NotificationListener, attempted = false, sdk = 34))
        assertEquals(GateStatus.NOT_GRANTED, st(Gate.Overlay, attempted = true, sdk = 34))         // never BLOCKED_RESTRICTED
        assertEquals(GateStatus.NOT_GRANTED, st(Gate.DndPolicy, asked = true, rationale = false))  // Settings gates never read as permanently denied
        assertEquals("Not enabled (restricted setting?)", statusText(GateStatus.BLOCKED_RESTRICTED, Gate.Accessibility))
        assertEquals("Available", statusText(GateStatus.INFO, Gate.Feature(PackageManager.FEATURE_NFC, "NFC")))
    }

    @Test fun inventoryOverTheRealCatalog() {
        val inv = inventory(catalog)
        for (g in GateGroup.entries) assertTrue("$g non-empty", inv[g]?.isNotEmpty() == true)
        val rows = inv.values.flatten()
        assertEquals("no duplicate keys", rows.size, rows.map { it.gate.key }.toSet().size)
        assertTrue(rows.all { it.gate.key == it.gate })                                             // rows carry keys, never Advisory wrappers
        assertTrue(rows.none { it.gate == Gate.LiveHost || it.gate == Gate.ForegroundOnly })
        val listener = rows.first { it.gate == Gate.NotificationListener }
        assertTrue(listener.usedBy.toString(), SELF in listener.usedBy && "Now Playing" in listener.usedBy && "Media control (optional)" in listener.usedBy)
        assertFalse(listener.advisoryOnly)
        // DESIGN3P §7.3 (after the gates fixer): Overlay arrives only through the 13 background-launch nodes and is never enforced.
        val overlay = rows.first { it.gate == Gate.Overlay }
        assertTrue(overlay.usedBy.toString(), listOf("Launch app", "Open URL", "Share", "Settings panel", "App action").all { "$it (optional)" in overlay.usedBy })
        assertTrue(overlay.advisoryOnly)
        assertEquals(GateGroup.AUTOMATION, overlay.gate.group)
        assertTrue(rows.any { it.gate == Gate.LocationOn && it.advisoryOnly && it.gate.group == GateGroup.CONNECTIVITY })
        val download = rows.first { it.gate == p(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        assertTrue(download.usedBy.toString(), "Download" in download.usedBy && "Write file (optional)" in download.usedBy)
        assertFalse(download.advisoryOnly)
        assertEquals(GateGroup.ESSENTIAL, listener.gate.group)
        assertEquals(listOf(SELF), rows.first { it.gate == Gate.IgnoreBatteryOpt }.usedBy)
        assertTrue(rows.first { it.gate == Gate.PostNotifications }.usedBy.containsAll(listOf(SELF, "Notify")))
        val fine = rows.first { it.gate == p(Manifest.permission.ACCESS_FINE_LOCATION) }
        assertTrue(fine.usedBy.toString(), "Location" in fine.usedBy && "Location Enter/Exit" in fine.usedBy &&
            "Network Changed (optional)" in fine.usedBy && "Device State (optional)" in fine.usedBy)
        assertEquals(GateGroup.CONNECTIVITY, fine.gate.group)
        assertTrue(rows.any { it.gate == Gate.Accessibility && it.gate.group == GateGroup.AUTOMATION })
        assertTrue(rows.any { it.gate is Gate.Feature && it.gate.kind == GrantKind.INFO })
        // Advisory-only users are marked "(optional)" and rows that are only ever advisory sort after enforced ones within a section.
        for ((_, list) in inv) {
            val firstAdvisory = list.indexOfFirst { it.advisoryOnly }
            if (firstAdvisory >= 0) assertTrue(list.drop(firstAdvisory).all { it.advisoryOnly })
        }
        rows.filter { it.advisoryOnly }.forEach { r -> assertTrue(r.usedBy.toString(), r.usedBy.all { it.endsWith("(optional)") }) }
        rows.filter { !it.advisoryOnly }.forEach { r -> assertTrue(r.usedBy.toString(), r.usedBy.any { !it.endsWith("(optional)") }) }
    }

    @Test fun inventoryMergesAdvisoryAndEnforcedUses() {
        // A synthetic catalog: Advisory(X) and X collapse into one row; the advisory-only user gets " (optional)".
        val fine = p(Manifest.permission.ACCESS_FINE_LOCATION)
        val a = object : com.mob8n.core.Node() {
            override val spec = com.mob8n.core.NodeSpec("data.fa", "Hard", com.mob8n.core.NodeKind.DATA, "x", gates = listOf(fine, Gate.Overlay))
            override suspend fun execute(ctx: com.mob8n.core.ExecutionContext, input: com.mob8n.core.NodeInput) = com.mob8n.core.out(input.items)
        }
        val b = object : com.mob8n.core.Node() {
            override val spec = com.mob8n.core.NodeSpec("data.fb", "Soft", com.mob8n.core.NodeKind.DATA, "x", gates = listOf(Gate.Advisory(fine), Gate.Advisory(Gate.Overlay)))
            override suspend fun execute(ctx: com.mob8n.core.ExecutionContext, input: com.mob8n.core.NodeInput) = com.mob8n.core.out(input.items)
        }
        val rows = inventory(Catalog(listOf(listOf(a, b)))).values.flatten()
        val fineRow = rows.single { it.gate == fine }
        assertEquals(listOf("Hard", "Soft (optional)"), fineRow.usedBy)
        assertFalse(fineRow.advisoryOnly)
        val overlay = rows.single { it.gate == Gate.Overlay }
        assertEquals(listOf("Hard (optional)", "Soft (optional)"), overlay.usedBy)   // Overlay is intrinsically non-enforced (D5): even a bare use is optional
        assertTrue(overlay.advisoryOnly)
    }

    // ---- wizardSteps ----
    private val fine = p(Manifest.permission.ACCESS_FINE_LOCATION)
    private val bg = p(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private val images = p(Manifest.permission.READ_MEDIA_IMAGES)
    private val contacts = p(Manifest.permission.READ_CONTACTS)
    private val phone = p(Manifest.permission.READ_PHONE_STATE)
    private val ext = p(Manifest.permission.READ_EXTERNAL_STORAGE)
    private val rows = listOf(Gate.NotificationListener, Gate.PostNotifications, Gate.IgnoreBatteryOpt, Gate.Accessibility, Gate.Overlay, Gate.DndPolicy, Gate.WriteSettings,
        fine, bg, Gate.LocationOn, Gate.Feature(PackageManager.FEATURE_NFC, "NFC"), images, contacts, phone, ext).map { PermRow(it, listOf("n"), false) }

    @Test fun wizardNeverBatchesBackgroundLocation() {
        val status: (Gate) -> GateStatus = { g -> when (g) {
            fine, bg, contacts, Gate.PostNotifications -> GateStatus.NOT_GRANTED
            images -> GateStatus.PARTIAL
            phone -> GateStatus.UNAVAILABLE
            ext -> GateStatus.NOT_APPLICABLE
            Gate.NotificationListener, Gate.Accessibility -> GateStatus.NOT_GRANTED
            Gate.LocationOn -> GateStatus.NOT_GRANTED
            is Gate.Feature -> GateStatus.INFO
            else -> GateStatus.GRANTED
        } }
        val steps = wizardSteps(rows, status, fineGranted = false, sdk = 34)
        val runtime = steps.filterIsInstance<Step.Runtime>().single()
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in runtime.permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in runtime.permissions)             // FINE brings COARSE
        assertTrue(Manifest.permission.READ_MEDIA_IMAGES in runtime.permissions && Manifest.permission.READ_MEDIA_VIDEO in runtime.permissions)   // PARTIAL re-asks both
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in runtime.permissions)
        assertFalse(Manifest.permission.READ_PHONE_STATE in runtime.permissions)                  // UNAVAILABLE
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in runtime.permissions)             // NOT_APPLICABLE
        assertEquals(runtime.permissions.size, runtime.permissions.toSet().size)
        assertEquals(Step.Runtime::class, steps[0]::class)
        assertTrue(steps[1] === Step.BackgroundLocation)
        assertEquals(listOf(Gate.NotificationListener, Gate.Accessibility, Gate.LocationOn), steps.filterIsInstance<Step.Settings>().map { it.gate })   // SETTINGS_ORDER
        assertTrue(steps.none { it is Step.AppInfo })
        assertEquals(listOf("runtime", "bgloc", "settings:${Gate.NotificationListener.label}", "settings:${Gate.Accessibility.label}", "settings:${Gate.LocationOn.label}"), steps.map { it.key })
        // sdk 28: no background step at all
        assertTrue(wizardSteps(rows, status, false, 28).none { it === Step.BackgroundLocation })
    }

    @Test fun wizardCollectsPermanentlyDeniedIntoOneTrailingAppInfoStep() {
        val status: (Gate) -> GateStatus = { g -> when (g) { contacts, Gate.PostNotifications -> GateStatus.PERMANENTLY_DENIED; fine -> GateStatus.NOT_GRANTED; is Gate.Feature -> GateStatus.INFO; else -> GateStatus.GRANTED } }
        val steps = wizardSteps(rows, status, fineGranted = false, sdk = 34)
        assertEquals(listOf("runtime", "appinfo"), steps.map { it.key })
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), (steps[0] as Step.Runtime).permissions)
        assertEquals(setOf(Gate.PostNotifications, contacts), (steps[1] as Step.AppInfo).gates.toSet())
        assertTrue(wizardSteps(rows, { if (it is Gate.Feature) GateStatus.INFO else GateStatus.GRANTED }, true, 34).isEmpty())
        assertNull(wizardSteps(rows, { if (it == Gate.Accessibility) GateStatus.BLOCKED_RESTRICTED else GateStatus.GRANTED }, true, 34).singleOrNull { it !is Step.Settings })
    }

    @Test fun runtimePermissionCompanions() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), runtimePermissions(fine))
        assertEquals(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), runtimePermissions(Gate.Advisory(bg)))    // alone, always
        assertEquals(MEDIA_VISUAL, runtimePermissions(images).toSet())
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), runtimePermissions(Gate.PostNotifications))
        assertTrue(runtimePermissions(Gate.Accessibility).isEmpty())
        assertTrue(Gate.Feature(PackageManager.FEATURE_NFC, "NFC").grantable().not() && Gate.LiveHost.grantable().not() && Gate.Overlay.grantable())
    }
}
