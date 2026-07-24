package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import com.jon2g.aa_keyboard_unlock.xposed.HookChains
import com.jon2g.aa_keyboard_unlock.xposed.HookParam
import com.jon2g.aa_keyboard_unlock.xposed.MethodHook
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Hooks on the voice-only hint path discovered via MAPS-DRIVE-008.
 * Current Maps (26.30): onl.bj <- qnu.t <- qns.b
 * Older builds also used oiz / qjg / qjh.
 */
object MapsVoiceOnlyPathHooks {
    private val hookedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Classes from device stacks — not assumed to exist on every Maps build. */
    private val TRACED_SHORT_NAMES = listOf("onl", "qnu", "qns", "oiz", "qjg", "qjh")

    fun install(xposed: XposedInterface, classLoader: ClassLoader): Int {
        var hooked = 0
        hooked += hookVoiceOnlyStringMethods(xposed, classLoader, "onl")
        hooked += hookVoiceOnlyStringMethods(xposed, classLoader, "oiz")
        hooked += hookOnlBjHintGate(xposed, classLoader)
        hooked += hookQnuSearchTapKeyboard(xposed, classLoader)
        hooked += hookQnuUiStateUpdate(xposed, classLoader)
        hooked += hookQjgDrivingState(xposed, classLoader)
        hooked += hookQjhFactories(xposed, classLoader)
        hooked += hookCarSearchUiStateConstructors(xposed, classLoader)
        for (shortName in TRACED_SHORT_NAMES) {
            hooked += hookTracedClassConstructors(xposed, classLoader, shortName)
        }
        if (hooked > 0) {
            ModuleLog.maps("MAPS-DRIVE-012", "voice-only path hooks installed x$hooked", always = true)
        } else {
            ModuleLog.maps("MAPS-DRIVE-012", "WARN voice-only path hooks found no targets", always = true)
        }
        return hooked
    }

    /**
     * onl.bj(Context, z, z2, txs, z3, z4, z5): when z=true picks keyboard-denied / voice-only.
     * Force z=false so hint resolves to a normal search string.
     */
    private fun hookOnlBjHintGate(xposed: XposedInterface, classLoader: ClassLoader): Int {
        val onl = loadClass(classLoader, "onl") ?: return 0
        var hooked = 0
        for (method in onl.declaredMethods) {
            if (method.name != "bj") continue
            if (method.returnType != String::class.java) continue
            if (method.parameterCount < 2) continue
            if (method.parameterTypes[0] != android.content.Context::class.java) continue
            if (!hookOnce("${onl.name}#bj#hint")) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
                        // z at index 1 drives keyboard-denied / voice-only branch
                        if (param.args.size > 1 &&
                            (method.parameterTypes[1] == booleanPrimitive ||
                                method.parameterTypes[1] == Boolean::class.java) &&
                            param.args[1] == true
                        ) {
                            param.args[1] = false
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "onl.bj() forced keyboard/voice driving flag false",
                                always = true
                            )
                        }
                    }
                })
                hooked++
                ModuleLog.maps("MAPS-DRIVE-012", "hooked onl.bj driving hint gate", always = true)
            }
        }
        return hooked
    }

    /**
     * qnu.l() search-bar action:
     *   if carParams.z || !isKeyboardRestricted → rkw.d() (open keyboard)
     *   else if !isMicRestricted → voice
     *   else → no-op  ← current broken drive-mode path
     *
     * Always open native keyboard and skip the no-op branch.
     */
    private fun hookQnuSearchTapKeyboard(xposed: XposedInterface, classLoader: ClassLoader): Int {
        val qnu = loadClass(classLoader, "qnu") ?: return 0
        val method = qnu.declaredMethods.firstOrNull {
            it.name == "l" && it.parameterCount == 0 && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        } ?: return 0
        if (!hookOnce("${qnu.name}#l#tap")) return 0
        val doneResult = runCatching {
            val blzo = Reflect.findClass("blzo", classLoader)
            Reflect.getStaticObjectField(blzo, "a")
        }.getOrNull()
        return runCatching {
            HookChains.hookMethod(xposed, method, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                    val self = param.thisObject ?: return
                    val rkw = Reflect.getObjectField(self, "b") ?: return
                    Reflect.callMethod(rkw, "d")
                    ModuleLog.maps(
                        "MAPS-DRIVE-012",
                        "qnu.l() forced rkw.d() native keyboard open",
                        always = true
                    )
                    if (doneResult != null && method.returnType.isInstance(doneResult)) {
                        param.result = doneResult
                    }
                }
            })
            ModuleLog.maps("MAPS-DRIVE-012", "hooked qnu.l() search tap → keyboard", always = true)
            1
        }.getOrDefault(0)
    }

    /**
     * qnu.t(...) rebuilds UiState; booleans control mic/keyboard restriction in onl.bj + new qnp.
     * Force those false and clear any UiState arg/result.
     */
    private fun hookQnuUiStateUpdate(xposed: XposedInterface, classLoader: ClassLoader): Int {
        val qnu = loadClass(classLoader, "qnu") ?: return 0
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (method in qnu.declaredMethods) {
            if (method.name != "t") continue
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
            if (method.parameterCount < 2) continue
            if (!hookOnce("${qnu.name}#t#${method.parameterCount}")) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        MapsCarUiStatePatches.patchArgs(param.args)
                        for (index in method.parameterTypes.indices) {
                            val type = method.parameterTypes[index]
                            if (type != booleanPrimitive && type != Boolean::class.java) continue
                            if (param.args.getOrNull(index) == true) {
                                param.args[index] = false
                            }
                        }
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val result = param.result ?: return
                        val cleared = MapsCarUiStatePatches.clearRestrictions(result) ?: return
                        if (cleared !== result) {
                            param.result = cleared
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "qnu.t() result UiState restrictions cleared",
                                always = true
                            )
                        }
                    }
                })
                hooked++
                ModuleLog.maps("MAPS-DRIVE-012", "hooked qnu.t() UiState update", always = true)
            }
        }
        return hooked
    }

    /** Rewrite methods that load voice-only / keyboard-denied strings by resId. */
    private fun hookVoiceOnlyStringMethods(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shortName: String,
    ): Int {
        val clazz = loadClass(classLoader, shortName) ?: return 0
        var hooked = 0
        for (method in clazz.declaredMethods) {
            val returnsText =
                method.returnType == String::class.java ||
                    CharSequence::class.java.isAssignableFrom(method.returnType)
            if (!returnsText) continue
            if (!method.parameterTypes.any {
                    it == Int::class.javaPrimitiveType || it == Int::class.java
                }
            ) {
                continue
            }
            if (!hookOnce("${clazz.name}#${method.name}#str")) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val voiceId = MapsInstallProbe.voiceOnlyResId
                        val keyboardDeniedId = MapsInstallProbe.keyboardDeniedResId
                        val searchId = MapsInstallProbe.searchHintResId
                        if (voiceId == 0 && keyboardDeniedId == 0) return
                        val resId = param.args.firstOrNull { it is Int } as? Int ?: return
                        if (resId != voiceId && resId != keyboardDeniedId) return
                        val replacement = when {
                            searchId != 0 -> runCatching {
                                val ctx = param.thisObject?.javaClass
                                    ?.getDeclaredField("a")
                                    ?.also { it.isAccessible = true }
                                    ?.get(param.thisObject) as? android.content.Context
                                ctx?.getString(searchId)
                            }.getOrNull() ?: "Search"
                            else -> "Search"
                        }
                        param.result = replacement
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "$shortName.${method.name} rewrote resId=$resId -> \"$replacement\"",
                            always = true
                        )
                    }
                })
                hooked++
                ModuleLog.maps(
                    "MAPS-DRIVE-012",
                    "hooked $shortName.${method.name} string rewrite",
                    always = true
                )
            }
        }
        return hooked
    }

    private fun hookQjgDrivingState(xposed: XposedInterface, classLoader: ClassLoader): Int {
        val qjg = loadClass(classLoader, "qjg") ?: return 0
        var hooked = 0
        for (method in qjg.declaredMethods) {
            if (method.name != "t") continue
            if (method.returnType != String::class.java) continue
            if (!hookOnce("${qjg.name}#${method.name}")) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "qjg.t() args=${formatArgs(param.args)}",
                            always = ModulePrefs.isDebug()
                        )
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val hint = param.result as? String ?: return
                        if (hint.contains("voice only", ignoreCase = true)) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "qjg.t() returned voice-only \"${hint.take(50)}\" — driving branch active",
                                always = true
                            )
                        }
                    }
                })
                hooked++
            }
        }
        return hooked
    }

    private fun hookQjhFactories(xposed: XposedInterface, classLoader: ClassLoader): Int {
        val qjh = loadClass(classLoader, "qjh") ?: return 0
        var hooked = 0
        for (method in qjh.declaredMethods) {
            if (method.name != "a") continue
            if (!hookOnce("${qjh.name}#${method.name}")) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "qjh.a() args=${formatArgs(param.args)}",
                            always = ModulePrefs.isDebug()
                        )
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val result = param.result ?: return
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "qjh.a() -> ${result.javaClass.simpleName}",
                            always = ModulePrefs.isDebug()
                        )
                    }
                })
                hooked++
            }
        }
        return hooked
    }

    /** qjb UiState ctors — force isMicRestricted / isKeyboardRestricted false at construction. */
    private fun hookCarSearchUiStateConstructors(
        xposed: XposedInterface,
        classLoader: ClassLoader,
    ): Int {
        val qjb = loadClass(classLoader, "qjb") ?: return 0
        var hooked = 0
        for (ctor in qjb.declaredConstructors) {
            val params = ctor.parameterTypes
            val isUiStateShape = MapsCarUiStatePatches.isCarSearchUiStateConstructor(params) ||
                params.count {
                    it == Boolean::class.javaPrimitiveType || it == Boolean::class.java
                } >= 2
            if (!isUiStateShape) continue
            if (!hookOnce("${qjb.name}#<init>#ui#${params.size}")) continue
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val forced = if (MapsCarUiStatePatches.isCarSearchUiStateConstructor(params)) {
                            MapsCarUiStatePatches.forceCarSearchUiStateConstructorBools(param.args)
                        } else {
                            forceAllRestrictionBools(param.args, params)
                        }
                        if (forced > 0) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "qjb.<init> forced $forced UiState restriction bool(s) false",
                                always = true
                            )
                        }
                    }
                })
                hooked++
                ModuleLog.maps(
                    "MAPS-DRIVE-012",
                    "hooked qjb.<init>(${params.joinToString { it.simpleName }})",
                    always = true
                )
            }
        }
        return hooked
    }

    private fun forceAllRestrictionBools(args: Array<Any?>, params: Array<Class<*>>): Int {
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        var forced = 0
        for (index in params.indices) {
            val type = params[index]
            if (type != booleanPrimitive && type != Boolean::class.java) continue
            if (args.getOrNull(index) == true) {
                args[index] = false
                forced++
            }
        }
        return forced
    }

    /** qjg/qjh ctors with restriction bools — force driving/mic flags false at source. */
    private fun hookTracedClassConstructors(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shortName: String,
    ): Int {
        val clazz = loadClass(classLoader, shortName) ?: return 0
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (ctor in clazz.declaredConstructors) {
            val params = ctor.parameterTypes
            val boolIndices = params.mapIndexedNotNull { index, type ->
                if (type == booleanPrimitive || type == Boolean::class.java) index else null
            }
            if (boolIndices.isEmpty()) continue
            if (!hookOnce("${clazz.name}#<init>#${params.size}")) continue
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val uiStatePatched = MapsCarUiStatePatches.patchArgs(param.args)
                        var forced = 0
                        for (index in boolIndices) {
                            if (param.args.getOrNull(index) == true) {
                                param.args[index] = false
                                forced++
                            }
                        }
                        if (forced > 0 || uiStatePatched > 0) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "$shortName.<init> forced $forced restriction bool(s) false " +
                                    "uiStatePatched=$uiStatePatched args=${formatArgs(param.args)}",
                                always = true
                            )
                        } else if (ModulePrefs.isDebug()) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "$shortName.<init> args=${formatArgs(param.args)}",
                                always = true
                            )
                        }
                    }
                })
                hooked++
            }
        }
        return hooked
    }

    private fun loadClass(classLoader: ClassLoader, shortName: String): Class<*>? {
        return MapsSignatureDiscovery.loadObfuscatedClass(classLoader, shortName)
            ?: runCatching {
                Reflect.findClass(shortName, classLoader)
            }.getOrNull()
            ?: runCatching {
                Reflect.findClass("defpackage.$shortName", classLoader)
            }.getOrNull()
    }

    private fun hookOnce(key: String): Boolean = hookedKeys.add(key)

    private fun formatArgs(args: Array<Any?>): String {
        return args.mapIndexed { index, value ->
            "$index:${value?.javaClass?.simpleName}=$value"
        }.joinToString(" ")
    }
}
