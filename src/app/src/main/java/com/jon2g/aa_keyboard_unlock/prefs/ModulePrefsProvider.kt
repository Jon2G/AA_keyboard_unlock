package com.jon2g.aa_keyboard_unlock.prefs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

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
            PATH_ENABLED -> if (prefs.getBoolean(ModulePrefs.KEY_ENABLED, ModulePrefs.DEFAULT_ENABLED)) 1 else 0
            PATH_DEBUG -> if (prefs.getBoolean(ModulePrefs.KEY_DEBUG, ModulePrefs.DEFAULT_DEBUG)) 1 else 0
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
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.jon2g.aa_keyboard_unlock.prefs"
        const val COLUMN_VALUE = "value"

        private const val PATH_ENABLED = "enabled"
        private const val PATH_DEBUG = "debug"

        val URI_ENABLED: Uri = Uri.parse("content://$AUTHORITY/$PATH_ENABLED")
        val URI_DEBUG: Uri = Uri.parse("content://$AUTHORITY/$PATH_DEBUG")

        fun readBoolean(ctx: Context, uri: Uri, default: Boolean): Boolean {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0) == 1
                }
            }
            return default
        }
    }
}
