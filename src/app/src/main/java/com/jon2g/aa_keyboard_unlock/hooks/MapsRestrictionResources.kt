package com.jon2g.aa_keyboard_unlock.hooks

import android.content.res.Resources
import com.jon2g.aa_keyboard_unlock.debug.MapsDrivingTrace

/** Maps Car search hint resource ids (DHU capture 2026-06-17). */
object MapsRestrictionResources {
    const val FALLBACK_VOICE_ONLY_RES_ID = 2132018912
    const val FALLBACK_SEARCH_HINT_RES_ID = 2132018832

    fun voiceOnlyResId(): Int =
        MapsDrivingTrace.resourceIds?.voiceOnlyResId?.takeIf { it != 0 } ?: FALLBACK_VOICE_ONLY_RES_ID

    fun searchHintResId(): Int =
        MapsDrivingTrace.resourceIds?.searchHintResId?.takeIf { it != 0 } ?: FALLBACK_SEARCH_HINT_RES_ID

    fun isVoiceOnlyResId(resId: Int): Boolean = resId != 0 && resId == voiceOnlyResId()

    fun loadSearchHint(resources: Resources): CharSequence {
        return runCatching { resources.getText(searchHintResId()) }
            .getOrDefault("Search all destinations")
    }
}
