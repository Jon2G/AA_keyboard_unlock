package com.jon2g.aa_keyboard_unlock.xposed

import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

object DexHooks {
    fun hookAllMethodsImplementing(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        sourcePath: String,
        baseType: Class<*>,
        methodName: String,
        hook: MethodHook
    ): Int {
        var hooked = 0
        runCatching {
            val dex = DexFile(sourcePath)
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!name.startsWith("defpackage.")) continue
                runCatching {
                    val clazz = classLoader.loadClass(name)
                    if (clazz.isInterface || !baseType.isAssignableFrom(clazz)) return@runCatching
                    hooked += HookChains.hookAllMethods(xposed, clazz, methodName, hook).size
                }
            }
        }
        return hooked
    }

    fun hookMethodsBySignature(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        sourcePath: String,
        methodName: String,
        parameterCount: Int,
        hook: MethodHook
    ): Int {
        var hooked = 0
        runCatching {
            val dex = DexFile(sourcePath)
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!name.startsWith("defpackage.")) continue
                runCatching {
                    val clazz = classLoader.loadClass(name)
                    if (clazz.isInterface) return@runCatching
                    for (method in clazz.declaredMethods) {
                        if (method.name != methodName) continue
                        if (method.parameterCount != parameterCount) continue
                        if (Modifier.isAbstract(method.modifiers)) continue
                        HookChains.hookMethod(xposed, method, hook)
                        hooked++
                    }
                }
            }
        }
        return hooked
    }
}
