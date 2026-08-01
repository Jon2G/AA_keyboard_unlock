package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.ModuleLog
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Clear isMicRestricted / isKeyboardRestricted on Maps car search UiState.
 *
 * Identity is the custom `UiState(… isMicRestricted= … isKeyboardRestricted= …)` toString
 * (stable across R8). Field letters are never used as the source of truth.
 */
object MapsCarUiStatePatches {
    private val QUERY_RE = Regex("""searchQuery=(.*?), searchBoxCursorPosition=""")
    private val CURSOR_RE = Regex("""searchBoxCursorPosition=(-?\d+), hintString=""")
    private val HINT_RE = Regex("""hintString=(.*?), isMicRestricted=""")

    fun looksLikeCarSearchUiState(value: Any): Boolean {
        val text = value.toString()
        return text.contains("isMicRestricted=") && text.contains("isKeyboardRestricted=")
    }

    /** Patch restriction flags on any UiState-like args; returns number of args patched. */
    fun patchArgs(args: Array<Any?>): Int {
        var patched = 0
        for (index in args.indices) {
            val arg = args[index] ?: continue
            val updated = clearRestrictions(arg) ?: continue
            if (updated !== arg) {
                args[index] = updated
            }
            patched++
        }
        return patched
    }

    /**
     * Force mic/keyboard restriction flags false. Rebuilds via constructor when fields are final.
     */
    fun clearRestrictions(state: Any): Any? {
        if (!looksLikeCarSearchUiState(state)) return null
        val text = state.toString()
        val micRestricted = text.contains("isMicRestricted=true")
        val keyboardRestricted = text.contains("isKeyboardRestricted=true")
        if (!micRestricted && !keyboardRestricted) return null
        return rebuildWithRestrictionsCleared(state, text)
    }

    private fun rebuildWithRestrictionsCleared(state: Any, text: String): Any? {
        val clazz = state.javaClass
        val ctor = clazz.declaredConstructors.firstOrNull { ctor ->
            isCarSearchUiStateConstructor(ctor.parameterTypes)
        } ?: return null

        val query = QUERY_RE.find(text)?.groupValues?.getOrNull(1) ?: ""
        val cursor = CURSOR_RE.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val hint = HINT_RE.find(text)?.groupValues?.getOrNull(1) ?: ""
        val icon = readNonPrimitiveNonStringField(state)
        val gemini = text.contains("isGeminiAnimationMicEnabled=true")

        ctor.isAccessible = true
        val params = ctor.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        args[0] = query
        args[1] = cursor
        args[2] = hint
        args[3] = false // isMicRestricted
        args[4] = false // isKeyboardRestricted
        if (params.size > 5) args[5] = icon
        if (params.size > 6) {
            args[6] = when {
                params[6] == Boolean::class.javaPrimitiveType || params[6] == Boolean::class.java -> gemini
                else -> null
            }
        }
        val rebuilt = runCatching { ctor.newInstance(*args) }.getOrNull() ?: return null
        ModuleLog.maps(
            "MAPS-DRIVE-012",
            "UiState rebuild: cleared mic/keyboard restrictions on ${clazz.simpleName}",
            always = true
        )
        return rebuilt
    }

    private fun readNonPrimitiveNonStringField(state: Any): Any? {
        return state.javaClass.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) &&
                field.type != String::class.java &&
                !field.type.isPrimitive &&
                field.type != Boolean::class.java &&
                field.type != java.lang.Boolean::class.java
        }?.let { field ->
            field.isAccessible = true
            runCatching { field.get(state) }.getOrNull()
        }
    }

    /** Safe for types whose ctor params reference optional platform stubs (e.g. XR Node). */
    fun matchesCarSearchUiStateConstructor(ctor: Constructor<*>): Boolean =
        runCatching { isCarSearchUiStateConstructor(ctor.parameterTypes) }.getOrDefault(false)

    /** UiState ctor: String, int, String, bool mic, bool keyboard, … */
    fun isCarSearchUiStateConstructor(parameterTypes: Array<Class<*>>): Boolean {
        if (parameterTypes.size < 5) return false
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        if (parameterTypes[3] != booleanPrimitive && parameterTypes[3] != Boolean::class.java) return false
        if (parameterTypes[4] != booleanPrimitive && parameterTypes[4] != Boolean::class.java) return false
        return parameterTypes[0] == String::class.java &&
            (parameterTypes[1] == Int::class.javaPrimitiveType || parameterTypes[1] == Int::class.java) &&
            parameterTypes[2] == String::class.java
    }

    fun forceCarSearchUiStateConstructorBools(args: Array<Any?>): Int {
        var forced = 0
        if (args.size >= 5 && args[3] == true) {
            args[3] = false
            forced++
        }
        if (args.size >= 5 && args[4] == true) {
            args[4] = false
            forced++
        }
        return forced
    }
}
