package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.xposed.Reflect

/** Detect voice-only / keyboard-blocked hint text for Maps driving trace (no rewriting). */
object DrivingRestrictionMarkers {
    private val VOICE_ONLY_MARKERS = listOf(
        "Voice only while driving",
        "Select and speak",
        "voice only",
        "select and speak",
        "Can't use keyboard while driving",
        "Can't type while driving",
        "Say a command",
    )

    private val SEARCH_HINT_MARKERS = listOf(
        "Search all destinations",
        "Search here",
        "CAR_SEARCH_HINT",
    )

    fun isVoiceOnlyText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        val s = text.toString()
        return VOICE_ONLY_MARKERS.any { marker -> s.contains(marker, ignoreCase = true) }
    }

    fun isSearchHintText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        val s = text.toString()
        return SEARCH_HINT_MARKERS.any { marker -> s.contains(marker, ignoreCase = true) }
    }

    fun extractHintText(value: Any?): String? {
        if (value == null) return null
        if (value is CharSequence) return value.toString()
        if (value is String) return value

        runCatching {
            val fromCarText = Reflect.callMethod(value, "toCharSequence") as? CharSequence
            if (!fromCarText.isNullOrEmpty()) return fromCarText.toString()
        }

        runCatching {
            val gdo = Reflect.getObjectField(value, "a") ?: return@runCatching
            val der = Reflect.getObjectField(gdo, "a")
            if (der != null) {
                val text = der.toString()
                if (text.isNotEmpty() && text != "null") return text
            }
            val fromGdo = Reflect.callMethod(gdo, "toString")?.toString()
            if (!fromGdo.isNullOrEmpty() && fromGdo != "null") return fromGdo
        }

        val fallback = value.toString()
        return if (fallback.isNotEmpty() && fallback != "null") fallback else null
    }
}
