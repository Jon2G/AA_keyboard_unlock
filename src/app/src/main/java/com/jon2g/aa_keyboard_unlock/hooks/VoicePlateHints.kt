package com.jon2g.aa_keyboard_unlock.hooks

import de.robv.android.xposed.XposedHelpers

object VoicePlateHints {
    const val MAPS_HOOK_BANNER = "Type anytime"
    const val KEYBOARD_SEARCH_HINT = MAPS_HOOK_BANNER

    private val VOICE_ONLY_MARKERS = listOf(
        "Voice only while driving",
        "Select and speak",
        "voice only",
        "select and speak",
        "Can't use keyboard while driving",
        "Can't type while driving",
        "Say a command",
    )

    fun isVoiceOnlyText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        val s = text.toString()
        return VOICE_ONLY_MARKERS.any { marker -> s.contains(marker, ignoreCase = true) }
    }

    /** Extract display text from CarText, gbg/gdo, String, or CharSequence wrappers. */
    fun extractHintText(value: Any?): String? {
        if (value == null) return null
        if (value is CharSequence) return value.toString()
        if (value is String) return value

        runCatching {
            val fromCarText = XposedHelpers.callMethod(value, "toCharSequence") as? CharSequence
            if (!fromCarText.isNullOrEmpty()) return fromCarText.toString()
        }

        // gbg → gdo (often gej) → der AnnotatedString
        runCatching {
            val gdo = XposedHelpers.getObjectField(value, "a") ?: return@runCatching
            val der = XposedHelpers.getObjectField(gdo, "a")
            if (der != null) {
                val text = der.toString()
                if (text.isNotEmpty() && text != "null") return text
            }
            val fromGdo = XposedHelpers.callMethod(gdo, "toString")?.toString()
            if (!fromGdo.isNullOrEmpty() && fromGdo != "null") return fromGdo
        }

        val fallback = value.toString()
        return if (fallback.isNotEmpty() && fallback != "null") fallback else null
    }

    fun isVoiceOnlyHint(value: Any?): Boolean {
        return isVoiceOnlyText(extractHintText(value))
    }

    fun rewriteIfVoiceOnly(original: String?): String? {
        if (original == null || !isVoiceOnlyText(original)) return null
        return MAPS_HOOK_BANNER
    }

    fun rewriteHintArg(value: Any?): Any? {
        if (value == null) return null
        val original = extractHintText(value) ?: return null
        return if (isVoiceOnlyText(original)) MAPS_HOOK_BANNER else null
    }

    fun createCarText(classLoader: ClassLoader, hint: String = MAPS_HOOK_BANNER): Any {
        val carText = XposedHelpers.findClass("androidx.car.app.model.CarText", classLoader)
        return XposedHelpers.callStaticMethod(carText, "create", hint)
    }
}
