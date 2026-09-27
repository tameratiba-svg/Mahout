package com.mob8n.ui

import com.mob8n.core.Workflow

/** Unsaved AI-generated workflows, keyed by workflow id; the editor loads from here when the engine has no row (DESIGN2 V9). */
// ponytail: drafts are process-memory (rotation survives, process death does not); upgrade = Room draft table
object Drafts {
    val map = java.util.concurrent.ConcurrentHashMap<String, Workflow>()
}
