package com.mob8n.core

import android.content.pm.PackageManager
import android.database.Cursor

// Shared Android helpers (F58): one copy of the NULL-safe Cursor readers and the app-label lookup. Json.kt stays android-free.

/** Column as String, null when the column is missing or SQL NULL. */
fun Cursor.s(col: String): String? = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
/** Column as Long, null when the column is missing or SQL NULL (never 0 for NULL). */
fun Cursor.l(col: String): Long? = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) null else getLong(it) }

/** Application label, null when the package is not installed or not visible (`<queries>`). */
fun PackageManager.labelOrNull(pkg: String): String? = runCatching { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }.getOrNull()
/** Application label, falling back to the package name. */
fun PackageManager.label(pkg: String): String = labelOrNull(pkg) ?: pkg
