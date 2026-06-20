package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import android.view.View
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import dalvik.system.DexFile
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Find Maps Car keyboard / driving-detection types by API shape, not obfuscated short names.
 */
object MapsSignatureDiscovery {
    data class SearchHeaderTap(
        val headerClass: Class<*>,
        val tapMethod: Method,
        val rekFieldName: String,
    )

    data class ScanStats(
        var pathsScanned: Int = 0,
        var dexOpenFailures: Int = 0,
        var dexEntries: Int = 0,
        var obfuscatedEntries: Int = 0,
        var classesLoaded: Int = 0,
        var loadFailures: Int = 0,
        var coroutineSkipped: Int = 0,
        var nearHint: Int = 0,
        var nearRekLoose: Int = 0,
        var nearRekStrict: Int = 0,
        var nearIme: Int = 0,
        var nearHeaderTap: Int = 0,
        var nearCarParams: Int = 0,
        var firstLoadError: String? = null,
        var firstDexError: String? = null,
    )

    data class DiscoveredTargets(
        val hintMethods: List<Method> = emptyList(),
        val rekOverlayTypes: List<Class<*>> = emptyList(),
        val carImeTypes: List<Class<*>> = emptyList(),
        val searchHeaderTaps: List<SearchHeaderTap> = emptyList(),
        val carParameterMethods: List<Method> = emptyList(),
        val keyboardRestrictedMethods: List<Method> = emptyList(),
        val voiceBypassMethods: List<Method> = emptyList(),
        val headerRestrictionConstructors: List<Constructor<*>> = emptyList(),
        val stats: ScanStats = ScanStats(),
    ) {
        fun isEffectivelyEmpty(): Boolean =
            hintMethods.isEmpty() &&
                rekOverlayTypes.isEmpty() &&
                searchHeaderTaps.isEmpty() &&
                carImeTypes.isEmpty()
    }

    @Volatile
    private var cached: DiscoveredTargets? = null

    @Volatile
    private var lastStats: ScanStats = ScanStats()

    fun lastScanStats(): ScanStats = lastStats

    fun discover(ctx: HookContext, force: Boolean = false): DiscoveredTargets {
        if (!force) {
            cached?.let { return it }
        }
        val targets = scan(ctx)
        cached = targets
        lastStats = targets.stats
        logDiscovery(targets)
        return targets
    }

    fun invalidate() {
        cached = null
    }

    private fun scan(ctx: HookContext): DiscoveredTargets {
        val stats = ScanStats()
        val hints = mutableListOf<Method>()
        val rekTypes = linkedSetOf<Class<*>>()
        val rekLooseTypes = linkedSetOf<Class<*>>()
        val carImeTypes = linkedSetOf<Class<*>>()
        val headerTaps = linkedSetOf<SearchHeaderTap>()
        val carParamMethods = linkedSetOf<Method>()
        val restrictedMethods = linkedSetOf<Method>()
        val voiceMethods = linkedSetOf<Method>()
        val headerCtors = linkedSetOf<Constructor<*>>()
        val seen = mutableSetOf<String>()

        val paths = ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }
        stats.pathsScanned = paths.size
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "dex scan starting paths=${paths.size} ${paths.joinToString { it.substringAfterLast('/') }}",
            always = true
        )

        val entrySamples = mutableListOf<String>()

        for (path in paths) {
            if (!apkContainsDex(path)) {
                stats.dexOpenFailures++
                if (stats.firstDexError == null) {
                    stats.firstDexError = "${path.substringAfterLast('/')}: no classes.dex"
                }
                continue
            }
            runCatching {
                val dex = DexFile(path)
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val rawName = entries.nextElement()
                    stats.dexEntries++
                    val name = normalizeDexClassName(rawName)
                    if (entrySamples.size < 8 && isObfuscatedMapsClass(name)) {
                        entrySamples += name
                    } else if (entrySamples.size < 5) {
                        entrySamples += rawName
                    }
                    if (!isObfuscatedMapsClass(name)) continue
                    stats.obfuscatedEntries++
                    if (!seen.add(name)) continue

                    val clazz = loadObfuscatedClass(ctx.classLoader, name)
                    if (clazz == null) {
                        stats.loadFailures++
                        if (stats.firstLoadError == null) {
                            stats.firstLoadError = "$name: ClassNotFoundException"
                        }
                    } else {
                        stats.classesLoaded++
                        if (clazz.isInterface || isCoroutineLike(clazz)) {
                            stats.coroutineSkipped++
                        } else {
                            inspectClass(
                                clazz,
                                hints,
                                rekTypes,
                                rekLooseTypes,
                                carImeTypes,
                                headerTaps,
                                carParamMethods,
                                restrictedMethods,
                                voiceMethods,
                                headerCtors,
                                stats,
                            )
                        }
                    }
                }
            }.onFailure { error ->
                stats.dexOpenFailures++
                if (stats.firstDexError == null) {
                    stats.firstDexError = "${path.substringAfterLast('/')}: ${error.message}"
                }
            }
        }

        val allRek = linkedSetOf<Class<*>>()
        allRek += rekTypes
        allRek += rekLooseTypes

        val carGraph = buildCarGraphClasses(headerTaps, allRek, carImeTypes)

        logScanStats(stats, entrySamples)

        return DiscoveredTargets(
            hintMethods = hints
                .distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterCount}" }
                .sortedByDescending { scoreHintMethod(it) }
                .take(16),
            rekOverlayTypes = allRek.sortedByDescending { scoreRekType(it) }.take(20),
            carImeTypes = carImeTypes.sortedBy { it.name }.take(10),
            searchHeaderTaps = headerTaps
                .sortedByDescending { scoreSearchHeaderTap(it) }
                .take(4),
            carParameterMethods = carParamMethods
                .distinctBy { "${it.declaringClass.name}#${it.name}" }
                .sortedByDescending { scoreCarParamsMethod(it) }
                .take(16),
            keyboardRestrictedMethods = filterCarGraphRestrictedMethods(
                restrictedMethods.sortedByDescending { scoreRestrictedMethod(it) },
                carGraph,
            ).take(32),
            voiceBypassMethods = voiceMethods
                .sortedByDescending { scoreVoiceBypassMethod(it) }
                .take(12),
            headerRestrictionConstructors = headerCtors.take(24),
            stats = stats,
        )
    }

    private fun inspectClass(
        clazz: Class<*>,
        hints: MutableList<Method>,
        rekTypes: MutableSet<Class<*>>,
        rekLooseTypes: MutableSet<Class<*>>,
        carImeTypes: MutableSet<Class<*>>,
        headerTaps: MutableSet<SearchHeaderTap>,
        carParamMethods: MutableSet<Method>,
        restrictedMethods: MutableSet<Method>,
        voiceMethods: MutableSet<Method>,
        headerCtors: MutableSet<Constructor<*>>,
        stats: ScanStats,
    ) {
        for (method in clazz.declaredMethods) {
            if (MapsInstallProbe.isHintCandidateStrict(method)) {
                hints += method
                stats.nearHint++
            } else if (MapsInstallProbe.isHintCandidateLoose(method)) {
                stats.nearHint++
                hints += method
            }
        }

        when {
            isRekOverlayTypeStrict(clazz) -> {
                rekTypes += clazz
                stats.nearRekStrict++
            }
            isRekOverlayTypeLoose(clazz) -> {
                rekLooseTypes += clazz
                stats.nearRekLoose++
            }
        }

        when {
            isCarImeControllerTypeStrict(clazz) -> {
                carImeTypes += clazz
                stats.nearIme++
            }
            isCarImeControllerTypeLoose(clazz) -> {
                carImeTypes += clazz
                stats.nearIme++
            }
        }

        findSearchHeaderTap(clazz)?.let {
            headerTaps += it
            stats.nearHeaderTap++
        } ?: run {
            if (hasTapMethodWithoutRek(clazz)) stats.nearHeaderTap++
        }

        for (method in clazz.declaredMethods) {
            if (Modifier.isStatic(method.modifiers)) continue
            if (method.parameterCount == 0 && isCarParamsType(method.returnType)) {
                carParamMethods += method
                stats.nearCarParams++
            }
            if (isKeyboardRestrictedMethod(method, clazz)) {
                restrictedMethods += method
            }
            if (isVoiceBypassMethod(method, clazz)) {
                voiceMethods += method
            }
        }

        for (ctor in clazz.declaredConstructors) {
            if (isHeaderRestrictionConstructor(ctor)) {
                headerCtors += ctor
            }
        }
    }

    /** DexFile.entries() may return `defpackage/kur` or `Ldefpackage/kur;` — normalize to JVM binary name. */
    fun normalizeDexClassName(raw: String): String {
        var name = raw.trim()
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length - 1)
        }
        return name.replace('/', '.')
    }

    private fun apkContainsDex(path: String): Boolean {
        if (!path.endsWith(".apk")) return true
        return runCatching {
            java.util.zip.ZipFile(path).use { zip -> zip.getEntry("classes.dex") != null }
        }.getOrDefault(true)
    }

    private fun logScanStats(stats: ScanStats, entrySamples: List<String>) {
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "dex stats paths=${stats.pathsScanned} entries=${stats.dexEntries} " +
                "obfuscated=${stats.obfuscatedEntries} loaded=${stats.classesLoaded} " +
                "loadFail=${stats.loadFailures} coroutine=${stats.coroutineSkipped} " +
                "dexOpenFail=${stats.dexOpenFailures}",
            always = true
        )
        if (stats.obfuscatedEntries == 0 && entrySamples.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "dex entry samples raw=[${entrySamples.joinToString { it.take(64) }}] " +
                    "normalized=[${entrySamples.joinToString { normalizeDexClassName(it).take(64) }}]",
                always = true
            )
        }
        if (stats.firstDexError != null || stats.firstLoadError != null) {
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "dex errors dexOpen=${stats.firstDexError} load=${stats.firstLoadError}",
                always = true
            )
        }
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "near-miss hint=${stats.nearHint} rekStrict=${stats.nearRekStrict} rekLoose=${stats.nearRekLoose} " +
                "ime=${stats.nearIme} headerTap=${stats.nearHeaderTap} carParams=${stats.nearCarParams}",
            always = true
        )
    }

    /** Load dex entry name — tries bare short name and defpackage fallback. */
    fun loadObfuscatedClass(classLoader: ClassLoader, binaryName: String): Class<*>? {
        val normalized = normalizeDexClassName(binaryName)
        val candidates = linkedSetOf<String>()
        candidates += normalized
        if (!normalized.contains('.')) {
            candidates += "defpackage.$normalized"
        }
        if (normalized.startsWith("defpackage.")) {
            candidates += normalized.removePrefix("defpackage.")
        }
        for (candidate in candidates) {
            runCatching {
                return classLoader.loadClass(candidate)
            }
        }
        return null
    }

    private fun isObfuscatedShortName(simple: String): Boolean {
        if (simple.length !in 2..5) return false
        return simple.all { it in 'a'..'z' }
    }

    private fun isObfuscatedMapsClass(binaryName: String): Boolean {
        val simple = binaryName.substringAfterLast('.').substringBefore('$')
        if (!isObfuscatedShortName(simple)) return false
        if (binaryName.startsWith("defpackage.")) return true
        if (binaryName.contains(".apps.maps.") || binaryName.contains(".apps.auto.")) return true
        // DexFile.entries() often returns unqualified names: kur, oiz, qjg
        if (!binaryName.contains('.')) return true
        return false
    }

    private fun logDiscovery(targets: DiscoveredTargets) {
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "signature scan hint=${targets.hintMethods.size} rek=${targets.rekOverlayTypes.size} " +
                "ime=${targets.carImeTypes.size} headerTap=${targets.searchHeaderTaps.size} " +
                "carParams=${targets.carParameterMethods.size} restricted=${targets.keyboardRestrictedMethods.size} " +
                "voiceBypass=${targets.voiceBypassMethods.size} headerCtor=${targets.headerRestrictionConstructors.size}",
            always = true
        )
        if (targets.hintMethods.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "hint methods: " + targets.hintMethods.take(4).joinToString { formatMethod(it) },
                always = true
            )
        }
        if (targets.searchHeaderTaps.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "search header taps: " + targets.searchHeaderTaps.take(4).joinToString {
                    "${it.headerClass.simpleName}.${it.tapMethod.name}() rekField=${it.rekFieldName}"
                },
                always = true
            )
        }
        if (targets.rekOverlayTypes.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "rek overlay types: " + targets.rekOverlayTypes.take(8).joinToString { it.simpleName },
                always = true
            )
        }
        if (targets.carImeTypes.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "car IME types: " + targets.carImeTypes.take(4).joinToString { it.simpleName },
                always = true
            )
        }
        if (targets.carParameterMethods.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "car param getters: " + targets.carParameterMethods.take(4).joinToString {
                    "${it.declaringClass.simpleName}.${it.name}():${it.returnType.simpleName}"
                },
                always = true
            )
        }
        if (targets.isEffectivelyEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "WARN signature scan found no primary targets — will retry after Application.onCreate",
                always = true
            )
        }
    }

    fun isCoroutineLike(clazz: Class<*>): Boolean {
        if (clazz.name.contains("Continuation")) return true
        if (clazz.superclass?.name?.contains("Continuation") == true) return true
        return clazz.declaredMethods.any { it.name == "invokeSuspend" }
    }

    fun isRekOverlayTypeLoose(clazz: Class<*>): Boolean {
        if (isCoroutineLike(clazz)) return false
        return hasZeroArgMethod(clazz, "d") && hasMethodWithArity(clazz, "e", minArity = 1)
    }

    fun isRekOverlayTypeStrict(clazz: Class<*>): Boolean {
        if (!isRekOverlayTypeLoose(clazz)) return false
        return hasViewAnchor(clazz)
    }

    fun isRekOverlayType(clazz: Class<*>): Boolean = isRekOverlayTypeStrict(clazz)

    private fun hasViewAnchor(clazz: Class<*>): Boolean {
        if (clazz.declaredFields.any { !Modifier.isStatic(it.modifiers) && View::class.java.isAssignableFrom(it.type) }) {
            return true
        }
        return walkMethods(clazz).any {
            it.name == "f" && View::class.java.isAssignableFrom(it.returnType)
        }
    }

    fun isCarImeControllerTypeLoose(clazz: Class<*>): Boolean {
        if (isCoroutineLike(clazz)) return false
        return hasMethodWithArity(clazz, "j", minArity = 1, maxArity = 1) &&
            hasZeroArgMethod(clazz, "k")
    }

    fun isCarImeControllerTypeStrict(clazz: Class<*>): Boolean = isCarImeControllerTypeLoose(clazz)

    fun isCarImeControllerType(clazz: Class<*>): Boolean = isCarImeControllerTypeLoose(clazz)

    fun isCarParamsType(type: Class<*>): Boolean {
        val name = type.name
        if (name.startsWith("android.") || name.startsWith("androidx.") ||
            name.startsWith("java.") || name.startsWith("kotlin.")
        ) {
            return false
        }
        if (type.isPrimitive || type == String::class.java) return false
        val boolFields = type.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) &&
                (it.type == Boolean::class.javaPrimitiveType || it.type == Boolean::class.java)
        }
        if (boolFields.isEmpty()) return false
        val fieldNames = boolFields.map { it.name }.toSet()
        return "A" in fieldNames && "c" in fieldNames
    }

    fun findSearchHeaderTap(clazz: Class<*>): SearchHeaderTap? {
        if (isCoroutineLike(clazz)) return null
        val tapMethod = walkMethods(clazz).firstOrNull { method ->
            method.name == "l" &&
                method.parameterCount == 0 &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isStatic(method.modifiers)
        } ?: return null

        val rekField = clazz.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && isRekOverlayTypeStrict(field.type)
        } ?: return null

        return SearchHeaderTap(clazz, tapMethod, rekField.name)
    }

    private fun hasTapMethodWithoutRek(clazz: Class<*>): Boolean {
        val hasL = walkMethods(clazz).any { it.name == "l" && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
        return hasL && clazz.declaredFields.none { !Modifier.isStatic(it.modifiers) && isRekFieldType(it.type) }
    }

    fun isRekFieldType(type: Class<*>): Boolean =
        isRekOverlayTypeLoose(type) || MapsInstallProbe.isRekLikeType(type)

    fun findRekFieldOnHeader(header: Any): Pair<String, Any>? {
        for (field in header.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(header) ?: return@runCatching
                if (isRekFieldType(value.javaClass)) {
                    return field.name to value
                }
            }
        }
        return null
    }

    fun isDiscoveredHeaderClass(clazz: Class<*>, targets: DiscoveredTargets): Boolean {
        return targets.searchHeaderTaps.any { it.headerClass == clazz }
    }

    private fun isKeyboardRestrictedMethod(method: Method, clazz: Class<*>): Boolean {
        if (Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 0) return false
        if (method.returnType != Boolean::class.javaPrimitiveType &&
            method.returnType != Boolean::class.java
        ) {
            return false
        }
        if (method.name != "i" && method.name != "b") return false
        if (clazz.declaredMethods.size > 60) return false
        val simple = clazz.simpleName
        if (simple.length !in 3..4 || !simple.all { it in 'a'..'z' }) return false
        if (clazz.name.startsWith("android.") || clazz.name.startsWith("androidx.")) return false
        return true
    }

    private fun buildCarGraphClasses(
        headerTaps: Collection<SearchHeaderTap>,
        rekTypes: Collection<Class<*>>,
        carImeTypes: Collection<Class<*>>,
    ): Set<Class<*>> {
        val graph = linkedSetOf<Class<*>>()
        headerTaps.mapTo(graph) { it.headerClass }
        graph += rekTypes
        graph += carImeTypes
        var grown = true
        while (grown) {
            grown = false
            for (clazz in graph.toList()) {
                for (field in clazz.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val fieldType = field.type
                    if (!isObfuscatedShortName(fieldType.simpleName)) continue
                    if (graph.add(fieldType)) grown = true
                }
            }
        }
        return graph
    }

    private fun filterCarGraphRestrictedMethods(
        methods: Collection<Method>,
        carGraph: Set<Class<*>>,
    ): List<Method> {
        return methods.filter { method ->
            val clazz = method.declaringClass
            carGraph.contains(clazz) || clazz.simpleName.startsWith("tr")
        }
    }

    private fun scoreSearchHeaderTap(tap: SearchHeaderTap): Int {
        var score = 0
        val rekType = tap.headerClass.declaredFields.firstOrNull { it.name == tap.rekFieldName }?.type
        if (rekType != null && isRekOverlayTypeStrict(rekType)) score += 10
        if (tap.headerClass.declaredFields.any { isRekOverlayTypeStrict(it.type) }) score += 5
        return score
    }

    private fun isVoiceBypassMethod(method: Method, clazz: Class<*>): Boolean {
        if (Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 1) return false
        if (method.parameterTypes[0] != Int::class.javaPrimitiveType) return false
        if (method.name != "l" && method.name != "m") return false
        return clazz.declaredFields.any { !Modifier.isStatic(it.modifiers) && isRekFieldType(it.type) } ||
            clazz.declaredMethods.size <= 30
    }

    private fun isHeaderRestrictionConstructor(ctor: Constructor<*>): Boolean {
        if (ctor.parameterCount < 3) return false
        val boolCount = ctor.parameterTypes.count {
            it == Boolean::class.javaPrimitiveType || it == Boolean::class.java
        }
        return boolCount >= 2
    }

    private fun walkMethods(clazz: Class<*>): Sequence<Method> = sequence {
        var type: Class<*>? = clazz
        while (type != null) {
            for (method in type.declaredMethods) {
                yield(method)
            }
            type = type.superclass
        }
    }

    private fun hasZeroArgMethod(clazz: Class<*>, name: String): Boolean {
        return walkMethods(clazz).any { it.name == name && it.parameterCount == 0 }
    }

    private fun hasMethodWithArity(clazz: Class<*>, name: String, minArity: Int, maxArity: Int = minArity): Boolean {
        return walkMethods(clazz).any { it.name == name && it.parameterCount in minArity..maxArity }
    }

    private fun scoreRekType(clazz: Class<*>): Int {
        var score = 0
        if (isRekOverlayTypeStrict(clazz)) score += 10
        if (hasViewAnchor(clazz)) score += 5
        if (clazz.simpleName.length in 3..4) score += 2
        return score
    }

    private fun scoreHintMethod(method: Method): Int {
        var score = 0
        val params = method.parameterTypes
        if (MapsInstallProbe.isHintCandidateStrict(method)) score += 10
        if (params.size == 7) score += 10
        if (params.size >= 5) score += 5
        if (params.firstOrNull() == Context::class.java) score += 5
        val bools = params.count { it == Boolean::class.javaPrimitiveType || it == Boolean::class.java }
        score += bools * 2
        if (method.name == "aJ") score += 3
        return score
    }

    private fun scoreCarParamsMethod(method: Method): Int {
        var score = 0
        if (method.name == "getCarParameters") score += 20
        if (method.returnType.simpleName == "csrh") score += 10
        return score
    }

    private fun scoreRestrictedMethod(method: Method): Int {
        var score = 0
        if (method.name == "i") score += 5
        if (method.declaringClass.simpleName.length == 3) score += 3
        if (method.declaringClass.simpleName.startsWith("tr")) score += 5
        return score
    }

    private fun scoreVoiceBypassMethod(method: Method): Int {
        var score = 0
        if (method.declaringClass.declaredFields.any { isRekFieldType(it.type) }) score += 10
        if (method.name == "l") score += 3
        return score
    }

    private fun formatMethod(method: Method): String {
        val params = method.parameterTypes.joinToString(",") { it.simpleName }
        return "${method.declaringClass.simpleName}.${method.name}($params)"
    }
}
