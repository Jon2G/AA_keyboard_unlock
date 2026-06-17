package com.jon2g.aa_keyboard_unlock.prefs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.jon2g.aa_keyboard_unlock.BuildConfig

class ModulePrefsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val prefs = ModulePrefs.appPrefs(ctx)
        val value = when (uri.lastPathSegment) {
            PATH_ENABLED -> if (prefs.getBoolean(ModulePrefs.KEY_ENABLED, ModulePrefs.DEFAULT_ENABLED)) 1L else 0L
            PATH_DEBUG -> if (BuildConfig.MODULE_DEBUG) 1L else 0L
            PATH_MAPS_MIC -> prefs.getLong(ModulePrefs.KEY_MAPS_MIC_UNTIL, 0L)
            else -> return null
        }
        return MatrixCursor(arrayOf(COLUMN_VALUE)).apply {
            addRow(arrayOf(value))
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val ctx = context ?: return 0
        val prefs = ModulePrefs.appPrefs(ctx)
        return when (uri.lastPathSegment) {
            PATH_MAPS_MIC -> {
                val until = values?.getAsLong(COLUMN_VALUE) ?: return 0
                prefs.edit().putLong(ModulePrefs.KEY_MAPS_MIC_UNTIL, until).apply()
                1
            }
            else -> 0
        }
    }

    companion object {
        const val AUTHORITY = "com.jon2g.aa_keyboard_unlock.prefs"
        const val COLUMN_VALUE = "value"

        private const val PATH_ENABLED = "enabled"
        private const val PATH_DEBUG = "debug"
        private const val PATH_MAPS_MIC = "maps_mic"

        val URI_ENABLED: Uri = Uri.parse("content://$AUTHORITY/$PATH_ENABLED")
        val URI_DEBUG: Uri = Uri.parse("content://$AUTHORITY/$PATH_DEBUG")
        val URI_MAPS_MIC: Uri = Uri.parse("content://$AUTHORITY/$PATH_MAPS_MIC")

        fun readBoolean(ctx: Context, uri: Uri, default: Boolean): Boolean {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0) != 0L
                }
            }
            return default
        }

        fun readLong(ctx: Context, uri: Uri, default: Long): Long {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
            return default
        }
    }
}
