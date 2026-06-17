package com.jon2g.aa_keyboard_unlock

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefsProvider

/** Cross-process mic tap signal (Maps tur.s → gearhead kcw.k) via module ContentProvider. */
object MicSignal {
    private const val WINDOW_MS = 4000L

    fun signalMapsMicVoice(context: Context) {
        val until = System.currentTimeMillis() + WINDOW_MS
        runCatching {
            val values = ContentValues().apply { put(ModulePrefsProvider.COLUMN_VALUE, until) }
            context.contentResolver.update(ModulePrefsProvider.URI_MAPS_MIC, values, null, null)
        }.onFailure {
            ModuleLog.maps("MAPS-004", "MicSignal write failed: ${it.message}", always = true)
        }
    }

    fun isMapsMicActive(context: Context): Boolean {
        val until = runCatching {
            ModulePrefsProvider.readLong(context, ModulePrefsProvider.URI_MAPS_MIC, 0L)
        }.getOrDefault(0L)
        return System.currentTimeMillis() < until
    }

    fun clearMapsMic(context: Context) {
        runCatching {
            val values = ContentValues().apply { put(ModulePrefsProvider.COLUMN_VALUE, 0L) }
            context.contentResolver.update(ModulePrefsProvider.URI_MAPS_MIC, values, null, null)
        }
    }
}
