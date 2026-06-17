package com.jon2g.aa_keyboard_unlock

import android.content.Context
import android.content.Intent
import com.jon2g.aa_keyboard_unlock.ModuleLog

/** Sends a typed query from gearhead overlay to the Maps process. */
object MapsSearchSubmit {
    fun submit(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            ModuleLog.gearhead("GH-KBD-004", "submit ignored — empty query", always = true)
            return
        }
        runCatching {
            val intent = Intent(KeyboardBridge.ACTION_SUBMIT_MAPS_SEARCH)
                .setPackage("com.google.android.apps.maps")
                .putExtra(KeyboardBridge.EXTRA_QUERY, trimmed)
            context.sendBroadcast(intent)
            ModuleLog.gearhead("GH-KBD-004", "broadcast SUBMIT_MAPS_SEARCH to Maps", always = true)
        }.onFailure {
            ModuleLog.gearhead("GH-KBD-004", "submit broadcast failed: ${it.message}", always = true)
        }
    }
}
