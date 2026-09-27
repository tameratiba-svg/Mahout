package com.mob8n.triggers

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.mob8n.Mob8NApp
import com.mob8n.core.Items
import com.mob8n.core.TriggerHost
import com.mob8n.core.TriggerInstance
import com.mob8n.core.item
import com.mob8n.core.l
import com.mob8n.core.s
import com.mob8n.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Translucent, no-history entry point for share sheet, launcher shortcuts and NFC. Always finishes. */
class EntryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val engine = Mob8NApp.of(this).engine
        try {
            when (i?.action) {
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> { handleShare(i, engine); return }   // finishes itself (async copy / chooser)
                "com.mob8n.SHORTCUT", NfcAdapter.ACTION_NDEF_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TAG_DISCOVERED -> {
                    scope.launch {   // F14: cold start must see the enabled-workflow cache before instancesOf()
                        try {
                            engine.awaitReady()
                            if (i.action == "com.mob8n.SHORTCUT") handleShortcut(i, engine.host) else handleNfc(i, engine.host)
                        } catch (e: Exception) { logW("EntryActivity ${i.action}", e); toast("Mahout: ${e.message}") }
                        finish()
                    }
                    return
                }
                else -> logT("EntryActivity: unhandled ${i?.action}")
            }
        } catch (e: Exception) { logW("EntryActivity ${i?.action}", e); toast("Mahout: ${e.message}") }
        finish()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun toast(msg: String) { try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {} }

    // ---- share ----
    private fun handleShare(i: Intent, engine: Engine) {
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { shareItems(i) }
                engine.awaitReady()
                val host = engine.host
                val matches = host.instancesOf(ShareTrigger.spec.id)
                    .map { inst -> inst to items.filter { ShareTrigger.accepts(inst.params, it) } }
                    .filter { it.second.isNotEmpty() }
                when (matches.size) {
                    0 -> { toast("No enabled workflow accepts this share"); finish() }
                    1 -> { fireShare(host, matches[0]); finish() }
                    else -> {
                        val names = matches.map { (inst, _) -> engine.workflow(inst.workflowId)?.name ?: inst.workflowId }.toTypedArray()
                        AlertDialog.Builder(this@EntryActivity)
                            .setTitle("Run which workflow?")
                            .setItems(names) { _, idx -> fireShare(host, matches[idx]); finish() }
                            .setNegativeButton("Cancel") { _, _ -> finish() }
                            .setOnCancelListener { finish() }
                            .show()
                    }
                }
            } catch (e: Exception) { logW("share", e); toast("Mahout: ${e.message}"); finish() }
        }
    }

    private fun fireShare(host: TriggerHost, m: Pair<TriggerInstance, Items>) {
        host.fireWorkflow(m.first.workflowId, m.first.nodeId, m.second.flatMap { ShareTrigger.toItems(m.first.params, it) })
    }

    /** One item per shared thing. Content URIs are copied to cacheDir/share/<uuid> (<= 50 MB each) so they outlive the grant. */
    private fun shareItems(i: Intent): Items {
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        val url = ShareTrigger.urlIn(text)
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND_MULTIPLE)
            IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        if (uris.isEmpty()) {
            if (text == null && subject == null) return emptyList()
            return listOf(item("text" to text, "url" to url, "subject" to subject, "uri" to null, "mimeType" to i.type, "fileName" to null, "sizeBytes" to null))
        }
        return uris.take(50).map { uri ->
            var name: String? = null; var size: Long? = null
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.s(OpenableColumns.DISPLAY_NAME)
                        size = c.l(OpenableColumns.SIZE)
                    }
                }
            } catch (e: Exception) { logW("share: query $uri", e) }
            val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null } ?: i.type
            val copied: Uri? = if ((size ?: 0L) > MAX_SHARE_BYTES) { logW("share: $name too large, keeping original uri"); null } else copyToCache(uri, name)
            item(
                "text" to text, "url" to url, "subject" to subject, "uri" to (copied ?: uri).toString(),
                "mimeType" to mime, "fileName" to (name ?: uri.lastPathSegment), "sizeBytes" to size,
            )
        }
    }

    private fun copyToCache(uri: Uri, name: String?): Uri? {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val ext = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 8 }?.let { ".$it" } ?: ""
            val f = File(dir, UUID.randomUUID().toString() + ext)
            val ins = contentResolver.openInputStream(uri) ?: return null
            ins.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_SHARE_BYTES) { f.delete(); return null }
                        out.write(buf, 0, n)
                    }
                }
            }
            return Uri.fromFile(f)
        } catch (e: Exception) { logW("share: copy $uri", e); return null }
    }

    // ---- shortcut ----
    private fun handleShortcut(i: Intent, host: TriggerHost) {
        val slot = i.getStringExtra("slot")
        val wfId = i.getStringExtra("workflowId")
        val hits = host.instancesOf(ShortcutTrigger.spec.id).filter { ShortcutTrigger.matches(it, slot, wfId) }
        if (hits.isEmpty()) { toast("No enabled workflow uses this shortcut"); return }
        for (inst in hits) host.fireWorkflow(inst.workflowId, inst.nodeId, listOf(item("at" to now(), "slot" to slot)))
    }

    // ---- nfc ----
    private fun handleNfc(i: Intent, host: TriggerHost) {
        val tag = IntentCompat.getParcelableExtra(i, NfcAdapter.EXTRA_TAG, Tag::class.java)
        val tagId = tag?.id?.joinToString("") { "%02x".format(it) }
        val msgs = IntentCompat.getParcelableArrayExtra(i, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.filterIsInstance<NdefMessage>().orEmpty()
        var text: String? = null; var uri: String? = null
        for (r in msgs.flatMap { it.records.toList() }) {
            if (uri == null) uri = try { r.toUri()?.toString() } catch (e: Exception) { null }
            if (text == null && r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT)) text = decodeText(r.payload)
        }
        host.fire(NfcTrigger.spec.id, item("tagId" to tagId, "text" to text, "uri" to uri, "techs" to (tag?.techList?.toList() ?: emptyList<String>())))
    }

    /** NFC Forum RTD_TEXT: status byte (bit7 = UTF-16, low 6 bits = language code length), then language, then text. */
    private fun decodeText(p: ByteArray): String? {
        if (p.isEmpty()) return null
        val utf16 = (p[0].toInt() and 0x80) != 0
        val langLen = p[0].toInt() and 0x3F
        if (1 + langLen > p.size) return null
        return String(p, 1 + langLen, p.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }

    companion object { private const val MAX_SHARE_BYTES = 50L * 1024 * 1024 }
}
