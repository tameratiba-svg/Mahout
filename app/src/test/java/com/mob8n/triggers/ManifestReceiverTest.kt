package com.mob8n.triggers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard (K1/F17/F18): a manifest-registered receiver only ever fires for implicit-broadcast-exempt actions on API 26+.
 * Every action listed under SystemReceiver must be on the allow-list; anything else (POWER_*, BATTERY_LOW/OKAY, PACKAGE_*) belongs in a runtime receiver.
 */
class ManifestReceiverTest {
    private val exempt = setOf(
        "android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED",
        "android.intent.action.TIMEZONE_CHANGED", "android.intent.action.TIME_SET", "android.intent.action.LOCALE_CHANGED",
        "android.intent.action.PHONE_STATE", "android.provider.Telephony.SMS_RECEIVED",
        "android.intent.action.DOWNLOAD_COMPLETE",   // explicit setPackage delivery by DownloadManager
    )

    private fun manifest(): String {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"), File("../app/src/main/AndroidManifest.xml"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    @Test fun systemReceiverListsOnlyExemptActions() {
        val m = manifest()
        val block = Regex("""<receiver android:name="com\.mob8n\.triggers\.SystemReceiver"[\s\S]*?</receiver>""").find(m)?.value ?: error("SystemReceiver block missing")
        val actions = Regex("""<action android:name="([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue("SystemReceiver has no actions", actions.isNotEmpty())
        val bad = actions.filter { it !in exempt }
        assertTrue("non-exempt manifest actions (never delivered on API 26+): $bad", bad.isEmpty())
        assertTrue("PACKAGE_* need a runtime receiver", "package" !in block)
    }

    @Test fun proximityReceiverIsDeclaredAndPrivate() {
        val m = manifest()
        assertTrue(Regex("""<receiver android:name="com\.mob8n\.triggers\.ProximityReceiver" android:exported="false"""").containsMatchIn(m))
        assertTrue("com.mob8n.PROXIMITY must not be an intent-filter action", """<action android:name="com.mob8n.PROXIMITY"""" !in m)
    }
}
