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
 * Hooks on the voice-only hint path discovered via MAPS-DRIVE-008:
 * oiz.aC(resId) <- qjg.<init> / qjg.t <- qjh.a / qje.invokeSuspend
 */
object MapsVoiceOnlyPathHooks {
    private val hookedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Classes/methods from 16:29 trace — not fixed hook targets for all builds. */
    private val TRACED_SHORT_NAMES = listOf("oiz", "qjg", "qjh")

    fun install(xposed: XposedInterface, classLoader: ClassLoader): Int {
        var hooked = 0
        hooked += hookOizResourceGetters(xposed, classLoader)
        hooked += hookQjgDrivingState(xposed, classLoader)
        hooked += hookQjhFactories(xposed, classLoader)
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

    private fun hookOizResourceGetters(xposed: XposedInterface, classLoader: ClassLoader): Int {
        val oiz = loadClass(classLoader, "oiz") ?: return 0
        var hooked = 0
        for (method in oiz.declaredMethods) {
            if (method.returnType != String::class.java) continue
            if (!method.parameterTypes.any { it == Int::class.javaPrimitiveType || it == Int::class.java }) {
                continue
            }
            if (!hookOnce("${oiz.name}#${method.name}")) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val voiceId = MapsInstallProbe.voiceOnlyResId
                        if (voiceId == 0) return
                        val resId = param.args.firstOrNull { it is Int } as? Int ?: return
                        if (resId != voiceId) return
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "oiz.${method.name} voiceOnly resId=$resId " +
                                "args=${formatArgs(param.args)} caller=${MapsDrivingTrace.formatCallerStack()}",
                            always = true
                        )
                    }
                })
                hooked++
                ModuleLog.maps(
                    "MAPS-DRIVE-012",
                    "hooked oiz.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})",
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
                        if (!ModulePrefs.isEnabled()) return
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "qjg.t() args=${formatArgs(param.args)}",
                            always = ModulePrefs.isDebug()
                        )
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
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
                        if (!ModulePrefs.isEnabled()) return
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "qjh.a() args=${formatArgs(param.args)}",
                            always = ModulePrefs.isDebug()
                        )
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
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
                        if (!ModulePrefs.isEnabled()) return
                        var forced = 0
                        for (index in boolIndices) {
                            if (param.args.getOrNull(index) == true) {
                                param.args[index] = false
                                forced++
                            }
                        }
                        if (forced > 0) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "$shortName.<init> forced $forced restriction bool(s) false " +
                                    "args=${formatArgs(param.args)}",
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
