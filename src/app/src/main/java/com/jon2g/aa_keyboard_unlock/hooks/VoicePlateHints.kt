package com.jon2g.aa_keyboard_unlock.hooks

import de.robv.android.xposed.XposedHelpers

object VoicePlateHints {
    const val KEYBOARD_SEARCH_HINT = "Search"

    private val VOICE_ONLY_MARKERS = listOf(
        "Voice only while driving",
        "Select and speak",
        "voice only",
        "select and speak",
        "Can't use keyboard while driving"
    )

    fun isVoiceOnlyText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        val s = text.toString()
        return VOICE_ONLY_MARKERS.any { marker -> s.contains(marker, ignoreCase = true) }
    }

    fun isVoiceOnlyHint(value: Any?): Boolean {
        if (value == null) return false
        val text = runCatching {
            XposedHelpers.callMethod(value, "toCharSequence") as? CharSequence
        }.getOrNull()?.toString() ?: value.toString()
        return isVoiceOnlyText(text)
    }

    fun createCarText(classLoader: ClassLoader, hint: String = KEYBOARD_SEARCH_HINT): Any {
        val carText = XposedHelpers.findClass("androidx.car.app.model.CarText", classLoader)
        return XposedHelpers.callStaticMethod(carText, "create", hint)
    }
}
