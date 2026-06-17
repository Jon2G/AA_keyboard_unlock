package com.jon2g.aa_keyboard_unlock.xposed

import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

object DexHooks {
    /**
     * Scan APK dex blobs for string constants (class name -> matched needle).
     * Best-effort: reads raw dex bytes inside the APK zip.
     */
    fun findClassesReferencingStrings(
        apkPath: String,
        needles: List<String>,
        limit: Int = 20
    ): List<Pair<String, String>> {
        val hits = mutableListOf<Pair<String, String>>()
        if (needles.isEmpty()) return hits
        runCatching {
            ZipFile(File(apkPath)).use { zip ->
                val dexNames = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it == "classes.dex" || it.matches(Regex("classes\\d+\\.dex")) }
                    .toList()
                for (dexName in dexNames) {
                    val bytes = zip.getInputStream(zip.getEntry(dexName)).readBytes()
                    val text = bytes.decodeToString()
                    for (needle in needles) {
                        if (!text.contains(needle)) continue
                        val classHits = Regex("""Ldefpackage/([a-z]{2,4});""")
                            .findAll(text)
                            .map { it.groupValues[1] }
                            .distinct()
                            .take(limit)
                        for (shortName in classHits) {
                            hits += "defpackage.$shortName" to needle
                            if (hits.size >= limit) return hits
                        }
                    }
                }
            }
        }
        return hits
    }

    /** Hook zero-arg [methodName] on classes with a rek-like instance field (any name). */
    fun hookClassesWithRekFieldMethod(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        sourcePath: String,
        methodName: String,
        hook: MethodHook,
        rekTypeCheck: (Class<*>) -> Boolean
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
                    val tapMethod = clazz.declaredMethods.firstOrNull { method ->
                        method.name == methodName &&
                            method.parameterCount == 0 &&
                            !Modifier.isAbstract(method.modifiers)
                    } ?: return@runCatching
                    val rekField = clazz.declaredFields.firstOrNull { field ->
                        !Modifier.isStatic(field.modifiers) && rekTypeCheck(field.type)
                    } ?: return@runCatching
                    HookChains.hookMethod(xposed, tapMethod, hook)
                    hooked++
                }
            }
        }
        return hooked
    }

    fun hookStaticStringHintResolvers(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        sourcePath: String,
        declaringShortName: String,
        hook: MethodHook,
        filter: (Method) -> Boolean
    ): Int {
        var hooked = 0
        runCatching {
            val dex = DexFile(sourcePath)
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!name.endsWith(".$declaringShortName")) continue
                runCatching {
                    val clazz = classLoader.loadClass(name)
                    for (method in clazz.declaredMethods) {
                        if (!filter(method)) continue
                        HookChains.hookMethod(xposed, method, hook)
                        hooked++
                    }
                }
            }
        }
        return hooked
    }

    fun hookMethodsOnImplementors(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        sourcePath: String,
        baseType: Class<*>,
        methodName: String,
        parameterCount: Int,
        hook: MethodHook,
        extraFilter: ((Method) -> Boolean)? = null
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
                    if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return@runCatching
                    if (!baseType.isAssignableFrom(clazz)) return@runCatching
                    for (method in clazz.declaredMethods) {
                        if (method.name != methodName) continue
                        if (method.parameterCount != parameterCount) continue
                        if (Modifier.isAbstract(method.modifiers)) continue
                        if (extraFilter != null && !extraFilter(method)) continue
                        HookChains.hookMethod(xposed, method, hook)
                        hooked++
                    }
                }
            }
        }
        return hooked
    }
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
        hook: MethodHook,
        filter: ((java.lang.reflect.Method) -> Boolean)? = null
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
                        if (filter != null && !filter(method)) continue
                        HookChains.hookMethod(xposed, method, hook)
                        hooked++
                    }
                }
            }
        }
        return hooked
    }

    fun hookBooleanMethodsByName(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        sourcePath: String,
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
                    if (clazz.isInterface) return@runCatching
                    for (method in clazz.declaredMethods) {
                        if (method.name != methodName) continue
                        if (Modifier.isAbstract(method.modifiers)) continue
                        val returnsBoolean = method.returnType == Boolean::class.javaPrimitiveType ||
                            method.returnType == Boolean::class.java
                        if (!returnsBoolean) continue
                        HookChains.hookMethod(xposed, method, hook)
                        hooked++
                    }
                }
            }
        }
        return hooked
    }
}
