package com.jon2g.aa_keyboard_unlock

import android.util.Log
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import io.github.libxposed.api.XposedInterface

object ModuleLog {
    enum class Process { GH, MAPS }

    private const val TAG = "AAKeyboardUnlock"

    @Volatile
    private var xposed: XposedInterface? = null

    fun bind(interfaceRef: XposedInterface) {
        xposed = interfaceRef
    }

    fun gearhead(eventId: String, message: String, always: Boolean = false) = log(Process.GH, eventId, message, always)

    fun maps(eventId: String, message: String, always: Boolean = false) = log(Process.MAPS, eventId, message, always)

    fun install(process: Process, message: String) = log(process, "INSTALL", message, always = true)

    fun debug(process: Process, message: String) {
        if (ModulePrefs.isDebug()) {
            log(process, "DEBUG", message, always = true)
        }
    }

    private fun log(process: Process, eventId: String, message: String, always: Boolean) {
        // Release APKs set MODULE_DEBUG=false — no LSPosed/logcat output (use logging/debug APKs to trace).
        if (!ModulePrefs.isDebug()) return
        val prefix = when (process) {
            Process.GH -> "GH"
            Process.MAPS -> "MAPS"
        }
        val line = "[$TAG v${BuildConfig.VERSION_CODE}] [$prefix] [$eventId] $message"
        xposed?.log(Log.INFO, TAG, line) ?: Log.i(TAG, line)
    }
}
