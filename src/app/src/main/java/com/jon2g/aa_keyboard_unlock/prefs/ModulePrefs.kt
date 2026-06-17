package com.jon2g.aa_keyboard_unlock.prefs

import android.content.Context
import android.content.SharedPreferences
import com.jon2g.aa_keyboard_unlock.BuildConfig
import java.io.File

object ModulePrefs {
    const val PREFS_NAME = "com.jon2g.aa_keyboard_unlock_prefs"
    const val MODULE_PACKAGE = "com.jon2g.aa_keyboard_unlock"

    const val KEY_ENABLED = "enabled"
    const val KEY_MAPS_MIC_UNTIL = "maps_mic_until"

    const val DEFAULT_ENABLED = true

    /** LSPosed allows world-readable prefs when [xposedsharedprefs] manifest meta-data is set. */
    private const val MODE_WORLD_READABLE = 0x00000001

    @Volatile
    var lastPrefSource: String = "unset"
        private set

    /** Baked in at compile time — install the logging APK to capture MAPS-DRIVE traces. */
    fun isDebug(): Boolean = BuildConfig.MODULE_DEBUG

    fun isEnabled(): Boolean {
        readViaXSharedPreferences(KEY_ENABLED, DEFAULT_ENABLED)?.let {
            lastPrefSource = "xsp"
            return it
        }
        readViaPackageContext(KEY_ENABLED, DEFAULT_ENABLED)?.let {
            lastPrefSource = "pkgctx"
            return it
        }
        readViaProvider(KEY_ENABLED, DEFAULT_ENABLED)?.let {
            lastPrefSource = "provider"
            return it
        }
        lastPrefSource = "default"
        return DEFAULT_ENABLED
    }

    private fun readViaXSharedPreferences(key: String, default: Boolean): Boolean? {
        return runCatching {
            val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
            val pref = clazz.getConstructor(String::class.java, String::class.java)
                .newInstance(MODULE_PACKAGE, PREFS_NAME)
            clazz.getMethod("reload").invoke(pref)
            val file = clazz.getMethod("getFile").invoke(pref) as File
            if (!file.canRead()) return null
            clazz.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(pref, key, default) as Boolean
        }.getOrNull()
    }

    private fun readViaPackageContext(key: String, default: Boolean): Boolean? {
        val caller = hookContext() ?: return null
        return runCatching {
            val moduleCtx = caller.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            appPrefs(moduleCtx).getBoolean(key, default)
        }.getOrNull()
    }

    private fun readViaProvider(key: String, default: Boolean): Boolean? {
        if (key != KEY_ENABLED) return null
        val caller = hookContext() ?: return null
        return runCatching {
            ModulePrefsProvider.readBoolean(caller, ModulePrefsProvider.URI_ENABLED, default)
        }.getOrNull()
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

    private fun sharedWritablePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, MODE_WORLD_READABLE)

    fun setEnabled(context: Context, enabled: Boolean) {
        appPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        sharedWritablePrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        notifyPrefChange(context, ModulePrefsProvider.URI_ENABLED)
    }

    private fun notifyPrefChange(context: Context, uri: android.net.Uri) {
        context.contentResolver.notifyChange(uri, null)
    }
}
