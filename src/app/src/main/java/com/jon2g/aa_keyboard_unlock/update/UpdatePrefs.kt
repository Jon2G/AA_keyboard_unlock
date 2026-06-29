package com.jon2g.aa_keyboard_unlock.update

import android.content.Context

object UpdatePrefs {
    private const val PREFS_NAME = "aa_keyboard_unlock_update"
    private const val KEY_LAST_REMOTE_DISMISSED = "last_remote_version_dismissed"
    private const val KEY_UPDATE_JUST_INSTALLED = "update_just_installed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastRemoteDismissed(context: Context): Int =
        prefs(context).getInt(KEY_LAST_REMOTE_DISMISSED, 0)

    fun setLastRemoteDismissed(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_LAST_REMOTE_DISMISSED, versionCode).apply()
    }

    fun isUpdateJustInstalled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPDATE_JUST_INSTALLED, false)

    fun setUpdateJustInstalled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_UPDATE_JUST_INSTALLED, value).apply()
    }
}
