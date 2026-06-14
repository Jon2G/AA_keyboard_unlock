package com.jon2g.aa_keyboard_unlock.prefs

import android.content.Context
import android.content.SharedPreferences
import com.jon2g.aa_keyboard_unlock.BuildConfig
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

object ModulePrefs {
    const val PREFS_NAME = "com.jon2g.aa_keyboard_unlock_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_DEBUG = "debug"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_DEBUG = false

    private const val TAG = "AAKeyboardUnlock"

    private var xPrefs: XSharedPreferences? = null
    private var providerFallbackLogged = false

    fun initXSharedPreferences() {
        if (xPrefs == null) {
            xPrefs = XSharedPreferences(
                BuildConfig.APPLICATION_ID,
                PREFS_NAME
            ).also { it.makeWorldReadable() }
        }
    }

    fun reload() {
        xPrefs?.reload()
    }

    fun isEnabled(): Boolean = readBoolean(ModulePrefsProvider.URI_ENABLED, KEY_ENABLED, DEFAULT_ENABLED)

    fun isDebug(): Boolean = readBoolean(ModulePrefsProvider.URI_DEBUG, KEY_DEBUG, DEFAULT_DEBUG)

    private fun readBoolean(uri: android.net.Uri, key: String, default: Boolean): Boolean {
        hookContext()?.let { ctx ->
            runCatching {
                return ModulePrefsProvider.readBoolean(ctx, uri, default)
            }.onFailure {
                if (!providerFallbackLogged) {
                    providerFallbackLogged = true
                    XposedBridge.log("[$TAG] ContentProvider read failed, using XSharedPreferences fallback: ${it.message}")
                }
            }
        }
        initXSharedPreferences()
        reload()
        return xPrefs?.getBoolean(key, default) ?: default
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
