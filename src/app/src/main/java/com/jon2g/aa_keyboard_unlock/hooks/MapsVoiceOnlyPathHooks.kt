package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.HookChains
import com.jon2g.aa_keyboard_unlock.xposed.HookParam
import com.jon2g.aa_keyboard_unlock.xposed.MethodHook
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice-only / keyboard-denied path hooks.
 *
 * Discovery supplies candidates when shapes match. For Maps car-search we also try
 * **shape-validated seeds** (same approach as gearhead anchors): short names are only
 * used after confirming API shape (UiState toString / rek.d / Context+bool hint gate).
 */
object MapsVoiceOnlyPathHooks {
    private val hookedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Seeds validated by shape before hooking — not pinned as the sole resolution path. */
    private val HINT_SEEDS = listOf("onl")
    private val HEADER_SEEDS = listOf("qnu")
    private val UISTATE_SEEDS = listOf("qnp")

    fun install(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        targets: MapsSignatureDiscovery.DiscoveredTargets,
    ): Int {
        var hooked = 0
        // Proven car-search path first (seeds validated by shape).
        hooked += runCatching { hookSeedHintGates(xposed, classLoader) }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "seed hint failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching { hookSeedSearchHeaderKeyboard(xposed, classLoader) }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "seed header failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching { hookSeedUiStateUpdate(xposed, classLoader) }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "seed UiState update failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching { hookSeedUiStateTypes(xposed, classLoader) }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "seed UiState types failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching {
            hookDrivingHintGates(xposed, targets.hintMethods)
        }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "hint gates failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching {
            hookUiStateConstructors(xposed, targets.headerRestrictionConstructors)
        }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "UiState ctors failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching {
            discoverAndHookUiStateTypes(xposed, classLoader, targets)
        }.getOrElse {
            ModuleLog.maps(
                "MAPS-DRIVE-012",
                "UiState discover failed: ${it.javaClass.simpleName}: ${it.message}",
                always = true
            )
            0
        }
        hooked += runCatching {
            hookSearchControllerTaps(xposed, targets.searchHeaderTaps)
        }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "search taps failed: ${it.message}", always = true)
            0
        }
        hooked += runCatching {
            hookResIdStringRewrites(xposed, classLoader, targets)
        }.getOrElse {
            ModuleLog.maps("MAPS-DRIVE-012", "res rewrite failed: ${it.message}", always = true)
            0
        }
        if (hooked > 0) {
            ModuleLog.maps("MAPS-DRIVE-012", "voice-only path hooks installed x$hooked", always = true)
        } else {
            ModuleLog.maps("MAPS-DRIVE-012", "WARN voice-only path hooks found no targets", always = true)
        }
        return hooked
    }

    private fun loadClass(classLoader: ClassLoader, shortName: String): Class<*>? =
        Reflect.findClassIfExists(shortName, classLoader)

    /** onl.bj-shaped: static String(Context, bool, …) — force driving bool false. */
    private fun hookSeedHintGates(xposed: XposedInterface, classLoader: ClassLoader): Int {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (seed in HINT_SEEDS) {
            val clazz = loadClass(classLoader, seed) ?: continue
            for (method in clazz.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != String::class.java) continue
                if (method.parameterCount < 2) continue
                if (method.parameterTypes[0].name != Context::class.java.name) continue
                val boolCount = method.parameterTypes.count {
                    it == booleanPrimitive || it == Boolean::class.java
                }
                if (boolCount < 2) continue
                if (!hookOnce("${clazz.name}#${method.name}#seedHint")) continue
                runCatching {
                    HookChains.hookMethod(xposed, method, object : MethodHook() {
                        override fun beforeHookedMethod(param: HookParam) {
                            if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                            // First bool after Context drives voice-only / keyboard-denied branch.
                            if (param.args.size > 1 &&
                                (method.parameterTypes[1] == booleanPrimitive ||
                                    method.parameterTypes[1] == Boolean::class.java) &&
                                param.args[1] == true
                            ) {
                                param.args[1] = false
                                ModuleLog.maps(
                                    "MAPS-DRIVE-012",
                                    "${clazz.simpleName}.${method.name}() forced driving hint flag false",
                                    always = true
                                )
                            }
                        }
                    })
                    hooked++
                    ModuleLog.maps(
                        "MAPS-DRIVE-012",
                        "hooked ${clazz.simpleName}.${method.name} driving hint gate (seed+shape)",
                        always = true
                    )
                }
            }
        }
        return hooked
    }

    /**
     * qnu.l-shaped: zero-arg tap on controller with rek-like field → always open keyboard
     * and skip the mic / no-op branches.
     */
    private fun hookSeedSearchHeaderKeyboard(xposed: XposedInterface, classLoader: ClassLoader): Int {
        var hooked = 0
        val doneResult = runCatching {
            Reflect.getStaticObjectField(Reflect.findClass("blzo", classLoader), "a")
        }.getOrNull()
        for (seed in HEADER_SEEDS) {
            val clazz = loadClass(classLoader, seed) ?: continue
            if (!isValidatedSearchHeader(clazz)) {
                ModuleLog.maps(
                    "MAPS-DRIVE-012",
                    "seed $seed failed shape check (not search header)",
                    always = true
                )
                continue
            }
            val tap = MapsSignatureDiscovery.findSearchHeaderTap(clazz) ?: continue
            if (!hookOnce("${clazz.name}#${tap.tapMethod.name}#seedTap")) continue
            runCatching {
                HookChains.hookMethod(xposed, tap.tapMethod, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val self = param.thisObject ?: return
                        val rek = runCatching {
                            Reflect.getObjectField(self, tap.rekFieldName)
                        }.getOrNull() ?: MapsSignatureDiscovery.findRekFieldOnHeader(self)?.second
                        if (rek == null) return
                        Reflect.callMethod(rek, "d")
                        ModuleLog.maps(
                            "MAPS-DRIVE-012",
                            "${clazz.simpleName}.${tap.tapMethod.name}() forced rek.d() native keyboard open",
                            always = true
                        )
                        if (doneResult != null && tap.tapMethod.returnType.isInstance(doneResult)) {
                            param.result = doneResult
                        }
                    }
                })
                hooked++
                ModuleLog.maps(
                    "MAPS-DRIVE-012",
                    "hooked ${clazz.simpleName}.${tap.tapMethod.name}() search tap → keyboard (seed+shape)",
                    always = true
                )
            }
            // Also force restriction bools false on header constructors that take UiState.
            for (ctor in clazz.declaredConstructors) {
                if (!hookOnce("${clazz.name}#<init>#${ctor.parameterCount}#seedHdr")) continue
                runCatching {
                    HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                        override fun beforeHookedMethod(param: HookParam) {
                            if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                            val patched = MapsCarUiStatePatches.patchArgs(param.args)
                            val forced = forceTrueBoolsFalse(param.args, ctor.parameterTypes)
                            if (patched > 0 || forced > 0) {
                                ModuleLog.maps(
                                    "MAPS-DRIVE-012",
                                    "${clazz.simpleName}.<init> forced $forced restriction bool(s) " +
                                        "false uiStatePatched=$patched",
                                    always = true
                                )
                            }
                        }
                    })
                    hooked++
                }
            }
        }
        return hooked
    }

    private fun isValidatedSearchHeader(clazz: Class<*>): Boolean {
        val tap = MapsSignatureDiscovery.findSearchHeaderTap(clazz) ?: return false
        // Must expose UiState-like accessor or ctor/param/field (qnu.u() → qnp).
        val hasUiStateApi =
            clazz.declaredMethods.any { method ->
                method.parameterCount == 0 &&
                    !Modifier.isStatic(method.modifiers) &&
                    looksLikeUiStateType(method.returnType)
            } || clazz.declaredConstructors.any { ctor ->
                ctor.parameterTypes.any { looksLikeUiStateType(it) }
            } || clazz.declaredFields.any { field ->
                !Modifier.isStatic(field.modifiers) && looksLikeUiStateType(field.type)
            }
        if (!hasUiStateApi) return false
        val rekType = clazz.declaredFields.firstOrNull { it.name == tap.rekFieldName }?.type
        return rekType != null && MapsSignatureDiscovery.isRekFieldType(rekType)
    }

    private fun looksLikeUiStateType(type: Class<*>): Boolean {
        if (type.isPrimitive || type == String::class.java) return false
        return type.declaredConstructors.any {
            MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes)
        }
    }

    /** qnu.t-shaped static UiState rebuilder — clear restriction bools + result. */
    private fun hookSeedUiStateUpdate(xposed: XposedInterface, classLoader: ClassLoader): Int {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (seed in HEADER_SEEDS) {
            val clazz = loadClass(classLoader, seed) ?: continue
            for (method in clazz.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.parameterCount < 2) continue
                if (!looksLikeUiStateType(method.returnType)) continue
                if (!hookOnce("${clazz.name}#${method.name}#seedRebuild")) continue
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
                                    "${clazz.simpleName}.${method.name}() UiState restrictions cleared",
                                    always = true
                                )
                            }
                        }
                    })
                    hooked++
                    ModuleLog.maps(
                        "MAPS-DRIVE-012",
                        "hooked ${clazz.simpleName}.${method.name}() UiState update (seed+shape)",
                        always = true
                    )
                }
            }
        }
        return hooked
    }

    private fun hookSeedUiStateTypes(xposed: XposedInterface, classLoader: ClassLoader): Int {
        var hooked = 0
        for (seed in UISTATE_SEEDS) {
            val clazz = loadClass(classLoader, seed) ?: continue
            if (!looksLikeUiStateType(clazz)) {
                ModuleLog.maps(
                    "MAPS-DRIVE-012",
                    "seed $seed failed UiState shape check",
                    always = true
                )
                continue
            }
            for (ctor in clazz.declaredConstructors) {
                if (!MapsCarUiStatePatches.isCarSearchUiStateConstructor(ctor.parameterTypes)) continue
                if (!hookOnce("${clazz.name}#<init>#uiSeed#${ctor.parameterCount}")) continue
                runCatching {
                    HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                        override fun beforeHookedMethod(param: HookParam) {
                            if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                            val forced =
                                MapsCarUiStatePatches.forceCarSearchUiStateConstructorBools(param.args)
                            if (forced > 0) {
                                ModuleLog.maps(
                                    "MAPS-DRIVE-012",
                                    "${clazz.simpleName}.<init> forced $forced restriction bool(s) false",
                                    always = true
                                )
                            }
                        }
                    })
                    hooked++
                    ModuleLog.maps(
                        "MAPS-DRIVE-012",
                        "hooked ${clazz.simpleName} UiState ctor (seed+shape)",
                        always = true
                    )
                }
            }
        }
        return hooked
    }

    /** Force first driving bool false on discovered hint resolvers (onl.bj-shaped). */
    private fun hookDrivingHintGates(xposed: XposedInterface, hintMethods: List<Method>): Int {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (method in hintMethods) {
            if (!MapsInstallProbe.isHintCandidateStrict(method) &&
                !MapsInstallProbe.isHintCandidateLoose(method)
            ) {
                continue
            }
            if (!hookOnce("${method.declaringClass.name}#${method.name}#hintGate")) continue
            val firstBoolIndex = method.parameterTypes.indexOfFirst {
                it == booleanPrimitive || it == Boolean::class.java
            }
            if (firstBoolIndex < 0) continue
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        if (param.args.getOrNull(firstBoolIndex) == true) {
                            param.args[firstBoolIndex] = false
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "${method.declaringClass.simpleName}.${method.name}() " +
                                    "forced driving hint flag false",
                                always = true
                            )
                        }
                    }
                })
                hooked++
            }
        }
        if (hooked > 0) {
            ModuleLog.maps("MAPS-DRIVE-012", "hooked driving hint gates x$hooked", always = true)
        }
        return hooked
    }

    private fun hookUiStateConstructors(
        xposed: XposedInterface,
        constructors: List<Constructor<*>>,
    ): Int {
        var hooked = 0
        for (ctor in constructors) {
            val params = ctor.parameterTypes
            if (!MapsCarUiStatePatches.isCarSearchUiStateConstructor(params) &&
                params.count {
                    it == Boolean::class.javaPrimitiveType || it == Boolean::class.java
                } < 2
            ) {
                continue
            }
            if (!hookOnce("${ctor.declaringClass.name}#<init>#${params.size}#ui")) continue
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val forced = if (MapsCarUiStatePatches.isCarSearchUiStateConstructor(params)) {
                            MapsCarUiStatePatches.forceCarSearchUiStateConstructorBools(param.args)
                        } else {
                            forceTrueBoolsFalse(param.args, params)
                        }
                        if (forced > 0) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "${ctor.declaringClass.simpleName}.<init> forced $forced " +
                                    "UiState restriction bool(s) false",
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

    /**
     * Walk discovered rek / header / hint declaring classes for UiState types, then hook
     * their constructors and static rebuilders by shape.
     */
    private fun discoverAndHookUiStateTypes(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        targets: MapsSignatureDiscovery.DiscoveredTargets,
    ): Int {
        val uiStateTypes = linkedSetOf<Class<*>>()
        uiStateTypes += targets.uiStateTypes
        val seedClasses = linkedSetOf<Class<*>>()
        targets.hintMethods.forEach { seedClasses += it.declaringClass }
        targets.searchHeaderTaps.forEach { seedClasses += it.headerClass }
        targets.rekOverlayTypes.forEach { seedClasses += it }
        targets.headerRestrictionConstructors.forEach { seedClasses += it.declaringClass }

        for (seed in seedClasses) {
            runCatching { collectUiStateTypesNear(seed, uiStateTypes) }
        }
        for (tap in targets.searchHeaderTaps) {
            for (field in tap.headerClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                runCatching {
                    collectUiStateTypesNear(field.type, uiStateTypes)
                }
            }
            for (method in tap.headerClass.declaredMethods) {
                if (method.parameterCount == 0 && !Modifier.isStatic(method.modifiers)) {
                    runCatching {
                        collectUiStateTypesNear(method.returnType, uiStateTypes)
                    }
                }
            }
        }

        var hooked = 0
        for (uiType in uiStateTypes) {
            hooked += hookAllUiStateConstructors(xposed, uiType)
            hooked += hookStaticUiStateRebuilders(xposed, uiType)
            hooked += hookControllersHoldingUiState(xposed, uiType, targets)
        }
        if (uiStateTypes.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-012",
                "UiState types discovered x${uiStateTypes.size}: " +
                    uiStateTypes.take(6).joinToString { it.simpleName },
                always = true
            )
        }
        return hooked
    }

    private fun collectUiStateTypesNear(clazz: Class<*>, out: MutableSet<Class<*>>) {
        if (clazz.isPrimitive || clazz == Any::class.java) return
        if (clazz.name.startsWith("android.") || clazz.name.startsWith("java.")) return
        for (ctor in clazz.declaredConstructors) {
            if (MapsCarUiStatePatches.isCarSearchUiStateConstructor(ctor.parameterTypes)) {
                out += clazz
                return
            }
        }
        // Nested / companion holders sometimes reference UiState as field type
        for (field in clazz.declaredFields) {
            val type = field.type
            if (type.isPrimitive || type == String::class.java) continue
            if (type.declaredConstructors.any {
                    MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes)
                }
            ) {
                out += type
            }
        }
    }

    private fun hookAllUiStateConstructors(xposed: XposedInterface, uiType: Class<*>): Int {
        var hooked = 0
        for (ctor in uiType.declaredConstructors) {
            if (!MapsCarUiStatePatches.isCarSearchUiStateConstructor(ctor.parameterTypes)) continue
            if (!hookOnce("${uiType.name}#<init>#uiState#${ctor.parameterCount}")) continue
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val forced =
                            MapsCarUiStatePatches.forceCarSearchUiStateConstructorBools(param.args)
                        if (forced > 0) {
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "${uiType.simpleName}.<init> forced $forced restriction bool(s) false",
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

    /** Static methods that take/return UiState and rebuild with restriction bools (qnu.t-shaped). */
    private fun hookStaticUiStateRebuilders(xposed: XposedInterface, uiType: Class<*>): Int {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        // Rebuilders live on controllers, not always on UiState itself — scan classes that
        // reference uiType in method signatures via header/rek seeds is done in controllers hook.
        // Here: static methods declared ON types that have a method returning uiType.
        for (method in uiType.declaredMethods) {
            // no-op on UiState type itself typically
            if (!Modifier.isStatic(method.modifiers)) continue
            if (method.returnType != uiType) continue
            if (!hookOnce("${uiType.name}#${method.name}#rebuild")) continue
            hooked += hookUiStateRebuilderMethod(xposed, method, booleanPrimitive)
        }
        return hooked
    }

    private fun hookControllersHoldingUiState(
        xposed: XposedInterface,
        uiType: Class<*>,
        targets: MapsSignatureDiscovery.DiscoveredTargets,
    ): Int {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        val controllers = linkedSetOf<Class<*>>()
        targets.searchHeaderTaps.forEach { controllers += it.headerClass }
        for (tap in targets.searchHeaderTaps) {
            for (method in tap.headerClass.declaredMethods) {
                if (method.parameterCount != 0 || Modifier.isStatic(method.modifiers)) continue
                if (method.returnType == uiType) controllers += tap.headerClass
            }
        }
        for (hint in targets.hintMethods) {
            for (method in hint.declaringClass.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != uiType) continue
                if (method.parameterTypes.none { it == uiType }) continue
                if (!hookOnce("${method.declaringClass.name}#${method.name}#uirebuild")) continue
                hooked += hookUiStateRebuilderMethod(xposed, method, booleanPrimitive)
            }
        }
        for (controller in controllers) {
            for (method in controller.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != uiType) continue
                if (!hookOnce("${controller.name}#${method.name}#uirebuild")) continue
                hooked += hookUiStateRebuilderMethod(xposed, method, booleanPrimitive)
            }
        }
        if (targets.searchHeaderTaps.isEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-012",
                "no searchHeaderTaps — UiState=${uiType.simpleName}; " +
                    "keyboard open depends on header-tap / rek discovery",
                always = true
            )
        }
        return hooked
    }

    private fun hookUiStateRebuilderMethod(
        xposed: XposedInterface,
        method: Method,
        booleanPrimitive: Class<*>,
    ): Int {
        return runCatching {
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
                            "${method.declaringClass.simpleName}.${method.name}() " +
                                "UiState restrictions cleared",
                            always = true
                        )
                    }
                }
            })
            ModuleLog.maps(
                "MAPS-DRIVE-012",
                "hooked UiState rebuilder ${method.declaringClass.simpleName}.${method.name}",
                always = true
            )
            1
        }.getOrDefault(0)
    }

    /** Ensure search header taps force keyboard even if MapsHooks path missed show. */
    private fun hookSearchControllerTaps(
        xposed: XposedInterface,
        taps: List<MapsSignatureDiscovery.SearchHeaderTap>,
    ): Int {
        var hooked = 0
        for (tap in taps) {
            if (!hookOnce("${tap.headerClass.name}#${tap.tapMethod.name}#voiceOnlyTap")) continue
            val doneResult = runCatching {
                val loader = tap.headerClass.classLoader ?: return@runCatching null
                val unit = Reflect.findClass("blzo", loader)
                Reflect.getStaticObjectField(unit, "a")
            }.getOrNull()
            runCatching {
                HookChains.hookMethod(xposed, tap.tapMethod, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!MapsCarContext.shouldApplyBehavioralHooks()) return
                        val header = param.thisObject ?: return
                        val rek = runCatching {
                            Reflect.getObjectField(header, tap.rekFieldName)
                        }.getOrNull() ?: MapsSignatureDiscovery.findRekFieldOnHeader(header)?.second
                        if (rek == null) return
                        runCatching {
                            Reflect.callMethod(rek, "d")
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "${tap.headerClass.simpleName}.${tap.tapMethod.name}() " +
                                    "forced rek.d() keyboard open",
                                always = true
                            )
                            if (doneResult != null &&
                                tap.tapMethod.returnType.isInstance(doneResult)
                            ) {
                                param.result = doneResult
                            }
                        }
                    }
                })
                hooked++
            }
        }
        if (hooked > 0) {
            ModuleLog.maps("MAPS-DRIVE-012", "hooked search controller taps x$hooked", always = true)
        }
        return hooked
    }

    /**
     * Rewrite methods that return String/CharSequence from a resId matching voice-only /
     * keyboard-denied resources — found via declaring classes of hint methods + UiState helpers.
     */
    private fun hookResIdStringRewrites(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        targets: MapsSignatureDiscovery.DiscoveredTargets,
    ): Int {
        val classes = linkedSetOf<Class<*>>()
        targets.hintMethods.forEach { classes += it.declaringClass }
        var hooked = 0
        for (clazz in classes) {
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
                if (!hookOnce("${clazz.name}#${method.name}#resStr")) continue
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
                                    val ctx = param.args.firstOrNull { it is Context } as? Context
                                        ?: (param.thisObject as? Context)
                                    ctx?.getString(searchId)
                                }.getOrNull() ?: "Search"
                                else -> "Search"
                            }
                            param.result = replacement
                            ModuleLog.maps(
                                "MAPS-DRIVE-012",
                                "${clazz.simpleName}.${method.name} rewrote resId=$resId -> \"$replacement\"",
                                always = true
                            )
                        }
                    })
                    hooked++
                }
            }
        }
        return hooked
    }

    private fun forceTrueBoolsFalse(args: Array<Any?>, params: Array<Class<*>>): Int {
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

    private fun hookOnce(key: String): Boolean = hookedKeys.add(key)
}
