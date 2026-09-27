package com.mob8n.engine

import android.content.Context
import com.mob8n.core.SECRETS_PREFS

/**
 * Read side of SharedPreferences("secrets") (ai.AiPrefs is the write side). Values never enter items or logs;
 * Redaction uses allValues() to mask them.
 * ponytail: plaintext app-private prefs, ceiling = rooted device; upgrade = wrap with AndroidKeyStore AES-GCM.
 */
object Secrets {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, name: String): String? =
        runCatching { prefs(ctx).getString(name, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun allValues(ctx: Context): Collection<String> =
        runCatching { prefs(ctx).all.values.filterIsInstance<String>().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
