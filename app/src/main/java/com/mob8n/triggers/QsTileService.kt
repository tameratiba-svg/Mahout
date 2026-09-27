package com.mob8n.triggers

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mob8n.Mob8NApp
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.item

/** Quick Settings tile: each tap toggles ACTIVE/INACTIVE and fires trigger.tile for every enabled workflow using it. */
class QsTileService : TileService() {
    private val prefs get() = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private var active: Boolean
        get() = prefs.getBoolean("tile_active", false)
        set(v) { prefs.edit().putBoolean("tile_active", v).apply() }

    override fun onStartListening() { render() }

    override fun onClick() {
        val on = !active
        active = on
        render()
        try {
            Mob8NApp.of(this).engine.host.fire(TileTrigger.spec.id, item("tileState" to if (on) "active" else "inactive", "at" to now()))
        } catch (e: Exception) { logW("tile", e) }
    }

    private fun render() {
        val t = qsTile ?: return
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.contentDescription = if (active) "Mahout tile, active" else "Mahout tile, inactive"
        t.updateTile()
    }
}
