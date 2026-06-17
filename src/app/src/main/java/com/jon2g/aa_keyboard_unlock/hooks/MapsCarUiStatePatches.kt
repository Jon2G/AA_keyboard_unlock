package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.Reflect

/** Clear isMicRestricted / isKeyboardRestricted on Maps car search UiState (e.g. qjb). */
object MapsCarUiStatePatches {
    private const val MIC_FIELD = "isMicRestricted"
    private const val KEYBOARD_FIELD = "isKeyboardRestricted"

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
     * Force mic/keyboard restriction flags false. Returns the same instance if fields were
     * mutated in place, or a copy() result when the data class is immutable.
     */
    fun clearRestrictions(state: Any): Any? {
        if (!looksLikeCarSearchUiState(state)) return null
        if (patchFieldsInPlace(state)) {
            return state
        }
        return clearRestrictionsViaCopy(state)
    }

    private fun patchFieldsInPlace(state: Any): Boolean {
        var changed = false
        runCatching {
            if (Reflect.getBooleanField(state, MIC_FIELD)) {
                Reflect.setBooleanField(state, MIC_FIELD, false)
                changed = true
            }
        }
        runCatching {
            if (Reflect.getBooleanField(state, KEYBOARD_FIELD)) {
                Reflect.setBooleanField(state, KEYBOARD_FIELD, false)
                changed = true
            }
        }
        if (changed) {
            ModuleLog.maps(
                "MAPS-DRIVE-012",
                "UiState in-place: cleared mic/keyboard restrictions on ${state.javaClass.simpleName}",
                always = true
            )
        }
        return changed
    }

    private fun clearRestrictionsViaCopy(state: Any): Any? {
        val clazz = state.javaClass
        val components = readComponents(state) ?: return null
        val micIndex = componentIndexForField(state, components, MIC_FIELD) ?: 3
        val keyboardIndex = componentIndexForField(state, components, KEYBOARD_FIELD) ?: 4
        if (micIndex !in components.indices || keyboardIndex !in components.indices) return null

        val patched = components.toMutableList()
        var changed = false
        if (patched[micIndex] == true) {
            patched[micIndex] = false
            changed = true
        }
        if (patched[keyboardIndex] == true) {
            patched[keyboardIndex] = false
            changed = true
        }
        if (!changed) return null

        val copy = clazz.declaredMethods.firstOrNull { method ->
            method.name == "copy" && method.parameterCount == patched.size
        } ?: return null
        copy.isAccessible = true
        val result = copy.invoke(state, *patched.toTypedArray()) ?: return null
        ModuleLog.maps(
            "MAPS-DRIVE-012",
            "UiState copy(): cleared mic/keyboard restrictions on ${clazz.simpleName}",
            always = true
        )
        return result
    }

    private fun readComponents(state: Any): List<Any?>? {
        val clazz = state.javaClass
        val components = mutableListOf<Any?>()
        var index = 1
        while (true) {
            val component = runCatching {
                val method = clazz.getDeclaredMethod("component$index")
                method.isAccessible = true
                method.invoke(state)
            }.getOrNull() ?: break
            components += component
            index++
        }
        return components.takeIf { it.isNotEmpty() }
    }

    private fun componentIndexForField(
        state: Any,
        components: List<Any?>,
        fieldName: String,
    ): Int? {
        val fieldValue = runCatching { Reflect.getBooleanField(state, fieldName) }.getOrNull() ?: return null
        return components.indices.firstOrNull { components[it] == fieldValue }
    }

    /** qjb-style ctor: String, int, String, bool mic, bool keyboard, … */
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
