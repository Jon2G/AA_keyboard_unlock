package com.jon2g.aa_keyboard_unlock

import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import de.robv.android.xposed.XposedBridge

object ModuleLog {
    enum class Process { GH, MAPS }

    private const val TAG = "AAKeyboardUnlock"

    fun gearhead(eventId: String, message: String, always: Boolean = false) = log(Process.GH, eventId, message, always)

    fun maps(eventId: String, message: String, always: Boolean = false) = log(Process.MAPS, eventId, message, always)

    fun install(process: Process, message: String) = log(process, "INSTALL", message, always = true)

    fun debug(process: Process, message: String) {
        if (ModulePrefs.isDebug()) {
            log(process, "DEBUG", message, always = true)
        }
    }

    private fun log(process: Process, eventId: String, message: String, always: Boolean) {
        if (!always && !ModulePrefs.isDebug()) return
        val prefix = when (process) {
            Process.GH -> "GH"
            Process.MAPS -> "MAPS"
        }
        XposedBridge.log("[$TAG v${BuildConfig.VERSION_CODE}] [$prefix] [$eventId] $message")
    }
}
