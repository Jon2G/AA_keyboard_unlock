package com.jon2g.aa_keyboard_unlock.hooks

import android.app.Activity
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import com.jon2g.aa_keyboard_unlock.xposed.Reflect

/**
 * Gates Maps behavioral hooks to projected car UI only.
 *
 * Phone [MapsActivity] (lists, place details, add-to-list) must stay unhooked even when
 * Android Auto / gearhead is connected in the background.
 */
object MapsCarContext {
    @Volatile
    var processName: String = ""

    @Volatile
    var ghostActivityCount: Int = 0
        private set

    @Volatile
    var phoneMapsActivityCount: Int = 0
        private set

    /** Set when gearhead broadcasts PREPARE/OPEN — AA 17.4 may not resume GhostActivity. */
    @Volatile
    var projectedKeyboardRequested: Boolean = false
        private set

    fun markProjectedKeyboardRequested() {
        projectedKeyboardRequested = true
    }

    fun isCarProcess(): Boolean = processName.endsWith(":car")

    fun isAuxiliaryProcess(): Boolean =
        processName.contains(':') && !isCarProcess()

    fun isProjectedMapsUiActive(): Boolean = ghostActivityCount > 0

    /** ActivityThread scan — lifecycle counters can miss GhostActivity on some 26.x builds. */
    fun hasGhostActivityInProcess(): Boolean {
        if (ghostActivityCount > 0) return true
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(thread, "mActivities") as? Map<*, *> ?: return false
            activities.values.any { record ->
                val activity = Reflect.getObjectField(record, "activity") as? Activity ?: return@any false
                activity.javaClass.name.contains("GhostActivity")
            }
        }.getOrDefault(false)
    }

    fun isPhoneMapsActivityForeground(): Boolean =
        phoneMapsActivityCount > 0 && ghostActivityCount == 0

    fun onGhostActivityResumed() {
        ghostActivityCount++
    }

    fun onGhostActivityPaused() {
        ghostActivityCount = (ghostActivityCount - 1).coerceAtLeast(0)
    }

    fun onPhoneMapsActivityResumed() {
        phoneMapsActivityCount++
    }

    fun onPhoneMapsActivityPaused() {
        phoneMapsActivityCount = (phoneMapsActivityCount - 1).coerceAtLeast(0)
    }

    fun isGhostActivityClass(className: String): Boolean =
        className.contains("GhostActivity")

    fun isPhoneMapsActivityClass(className: String): Boolean =
        className == "com.google.android.maps.MapsActivity" ||
            className.endsWith(".MapsActivity")

    /** Behavioral hooks: :car process or GhostActivity on screen — not phone MapsActivity. */
    fun shouldApplyBehavioralHooks(): Boolean {
        if (!ModulePrefs.isEnabled()) return false
        if (isCarProcess()) return true
        if (isProjectedMapsUiActive()) return true
        if (hasGhostActivityInProcess()) return true
        if (projectedKeyboardRequested) return true
        return false
    }
}
