package com.jon2g.aa_keyboard_unlock.prefs

import android.content.Context
import android.content.SharedPreferences

object ModulePrefs {
    const val PREFS_NAME = "com.jon2g.aa_keyboard_unlock_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_DEBUG = "debug"
    const val KEY_MAPS_MIC_UNTIL = "maps_mic_until"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_DEBUG = false

    fun isEnabled(): Boolean = readBoolean(ModulePrefsProvider.URI_ENABLED, KEY_ENABLED, DEFAULT_ENABLED)

    fun isDebug(): Boolean = readBoolean(ModulePrefsProvider.URI_DEBUG, KEY_DEBUG, DEFAULT_DEBUG)

    private fun readBoolean(uri: android.net.Uri, key: String, default: Boolean): Boolean {
        hookContext()?.let { ctx ->
            runCatching {
                return ModulePrefsProvider.readBoolean(ctx, uri, default)
            }
        }
        return default
    }

    private fun hookContext(): Context? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
            currentApplication.invoke(null) as? Context
        }.getOrNull()
    }

    fun appPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setEnabled(context: Context, enabled: Boolean) {
        appPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        notifyPrefChange(context, ModulePrefsProvider.URI_ENABLED)
    }

    fun setDebug(context: Context, debug: Boolean) {
        appPrefs(context).edit().putBoolean(KEY_DEBUG, debug).apply()
        notifyPrefChange(context, ModulePrefsProvider.URI_DEBUG)
    }

    private fun notifyPrefChange(context: Context, uri: android.net.Uri) {
        context.contentResolver.notifyChange(uri, null)
    }
}
