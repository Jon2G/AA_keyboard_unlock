package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import java.lang.reflect.Modifier

/**
 * Clear isMicRestricted / isKeyboardRestricted on Maps car search UiState.
 *
 * Live Maps 26.30 uses obfuscated final fields (`c` / `d`) with a custom
 * `UiState(...)` toString — property names are not JVM field names.
 */
object MapsCarUiStatePatches {
    private const val MIC_FIELD = "c"
    private const val KEYBOARD_FIELD = "d"

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
        if (patchObfuscatedFieldsInPlace(state)) {
            return state
        }
        return rebuildWithRestrictionsCleared(state)
    }

    private fun patchObfuscatedFieldsInPlace(state: Any): Boolean {
        var changed = false
        for (name in listOf(MIC_FIELD, KEYBOARD_FIELD)) {
            runCatching {
                val field = state.javaClass.getDeclaredField(name)
                if (field.type != Boolean::class.javaPrimitiveType && field.type != Boolean::class.java) {
                    return@runCatching
                }
                field.isAccessible = true
                if (Modifier.isFinal(field.modifiers)) {
                    FieldModifiers.clearFinal(field)
                }
                if (field.getBoolean(state)) {
                    field.setBoolean(state, false)
                    changed = true
                }
            }
        }
        if (changed && !isStillRestricted(state)) {
            ModuleLog.maps(
                "MAPS-DRIVE-012",
                "UiState in-place: cleared c/d restrictions on ${state.javaClass.simpleName}",
                always = true
            )
            return true
        }
        return false
    }

    private fun isStillRestricted(state: Any): Boolean {
        val mic = runCatching { Reflect.getBooleanField(state, MIC_FIELD) }.getOrDefault(false)
        val kbd = runCatching { Reflect.getBooleanField(state, KEYBOARD_FIELD) }.getOrDefault(false)
        return mic || kbd
    }

    private fun rebuildWithRestrictionsCleared(state: Any): Any? {
        val clazz = state.javaClass
        val ctor = clazz.declaredConstructors.firstOrNull { ctor ->
            val p = ctor.parameterTypes
            p.size >= 5 &&
                p[0] == String::class.java &&
                (p[1] == Int::class.javaPrimitiveType || p[1] == Int::class.java) &&
                p[2] == String::class.java &&
                (p[3] == Boolean::class.javaPrimitiveType || p[3] == Boolean::class.java) &&
                (p[4] == Boolean::class.javaPrimitiveType || p[4] == Boolean::class.java)
        } ?: return null

        val query = runCatching {
            clazz.getDeclaredField("g").also { it.isAccessible = true }.get(state) as? String
        }.getOrNull() ?: ""
        val cursor = runCatching { Reflect.getIntField(state, "a") }.getOrDefault(-1)
        val hint = runCatching { Reflect.getObjectField(state, "b") as? String }.getOrNull() ?: ""
        val icon = runCatching { Reflect.getObjectField(state, "e") }.getOrNull()
        val gemini = runCatching { Reflect.getBooleanField(state, "f") }.getOrDefault(false)

        val mic = runCatching { Reflect.getBooleanField(state, MIC_FIELD) }.getOrDefault(false)
        val kbd = runCatching { Reflect.getBooleanField(state, KEYBOARD_FIELD) }.getOrDefault(false)
        if (!mic && !kbd) return null

        ctor.isAccessible = true
        val params = ctor.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        args[0] = query
        args[1] = cursor
        args[2] = hint
        args[3] = false // isMicRestricted
        args[4] = false // isKeyboardRestricted
        if (params.size > 5) args[5] = icon
        if (params.size > 6) args[6] = gemini
        val rebuilt = runCatching { ctor.newInstance(*args) }.getOrNull() ?: return null
        ModuleLog.maps(
            "MAPS-DRIVE-012",
            "UiState rebuild: cleared mic/keyboard restrictions on ${clazz.simpleName}",
            always = true
        )
        return rebuilt
    }

    /** qjb/qnp-style ctor: String, int, String, bool mic, bool keyboard, … */
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

    /** Access java.lang.reflect.Field#modifiers when present (pre-Java 12 style). */
    private object FieldModifiers {
        private val modifiersField = runCatching {
            java.lang.reflect.Field::class.java.getDeclaredField("modifiers").also {
                it.isAccessible = true
            }
        }.getOrNull()

        fun clearFinal(field: java.lang.reflect.Field) {
            val mods = modifiersField ?: return
            runCatching { mods.setInt(field, field.modifiers and Modifier.FINAL.inv()) }
        }
    }
}
