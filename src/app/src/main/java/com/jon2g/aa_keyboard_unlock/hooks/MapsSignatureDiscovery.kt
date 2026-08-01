package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import android.view.View
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.DexHooks
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import dalvik.system.DexFile
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

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
        /** Car search HeaderViewModel UiState types (isMicRestricted/isKeyboardRestricted ctor). */
        val uiStateTypes: List<Class<*>> = emptyList(),
        val stats: ScanStats = ScanStats(),
        val fromCache: Boolean = false,
    ) {
        fun isEffectivelyEmpty(): Boolean =
            hintMethods.isEmpty() &&
                rekOverlayTypes.isEmpty() &&
                searchHeaderTaps.isEmpty() &&
                carImeTypes.isEmpty()

        fun toCachePayload(): DiscoveryCache.CachePayload {
            val members = mutableListOf<DiscoveryCache.MemberRef>()
            hintMethods.forEach { members += DiscoveryCache.methodRef(it, "hint") }
            rekOverlayTypes.forEach { members += DiscoveryCache.classRef(it, "rek") }
            carImeTypes.forEach { members += DiscoveryCache.classRef(it, "car_ime") }
            searchHeaderTaps.forEach {
                members += DiscoveryCache.methodRef(it.tapMethod, "header_tap|${it.rekFieldName}")
            }
            carParameterMethods.forEach { members += DiscoveryCache.methodRef(it, "car_params") }
            keyboardRestrictedMethods.forEach { members += DiscoveryCache.methodRef(it, "kbd_restricted") }
            voiceBypassMethods.forEach { members += DiscoveryCache.methodRef(it, "voice_bypass") }
            headerRestrictionConstructors.forEach {
                members += DiscoveryCache.ctorRef(it, "header_restrict_ctor")
            }
            uiStateTypes.forEach { members += DiscoveryCache.classRef(it, "ui_state") }
            return DiscoveryCache.CachePayload(members)
        }
    }

    @Volatile
    private var cached: DiscoveredTargets? = null

    @Volatile
    private var lastStats: ScanStats = ScanStats()

    fun lastScanStats(): ScanStats = lastStats

    fun discover(ctx: HookContext, force: Boolean = false): DiscoveredTargets {
        if (!force) {
            cached?.let { return it }
        } else {
            cached = null
        }

        val hostCtx = GearheadSignatureDiscovery.resolveHostContext()
        val fingerprint = hostCtx?.let {
            DiscoveryCache.packageFingerprint(it, ctx.packageName)
        }

        if (!force && hostCtx != null && fingerprint != null) {
            val payload = DiscoveryCache.load(hostCtx, DiscoveryCache.Namespace.MAPS, fingerprint)
            if (payload != null) {
                val resolved = resolveFromCache(ctx.classLoader, payload)
                if (resolved != null && !resolved.isEffectivelyEmpty()) {
                    DiscoveryCache.logHit(
                        ModuleLog.Process.MAPS,
                        DiscoveryCache.Namespace.MAPS,
                        fingerprint,
                        payload.members.size,
                    )
                    val enriched = enrichGaps(ctx, resolved)
                    cached = enriched
                    lastStats = enriched.stats
                    return enriched
                }
                DiscoveryCache.logMiss(
                    ModuleLog.Process.MAPS,
                    DiscoveryCache.Namespace.MAPS,
                    fingerprint,
                    "resolve_failed",
                )
            } else {
                DiscoveryCache.logMiss(
                    ModuleLog.Process.MAPS,
                    DiscoveryCache.Namespace.MAPS,
                    fingerprint,
                    "no_entry",
                )
            }
        }

        val targets = enrichGaps(ctx, scan(ctx))
        cached = targets
        lastStats = targets.stats
        if (hostCtx != null && fingerprint != null && !targets.isEffectivelyEmpty()) {
            val payload = targets.toCachePayload()
            DiscoveryCache.save(hostCtx, DiscoveryCache.Namespace.MAPS, fingerprint, payload)
            DiscoveryCache.logWrite(
                ModuleLog.Process.MAPS,
                DiscoveryCache.Namespace.MAPS,
                fingerprint,
                payload.members.size,
            )
        }
        logDiscovery(targets)
        return targets
    }

    fun invalidate() {
        cached = null
        GearheadSignatureDiscovery.resolveHostContext()?.let {
            DiscoveryCache.clear(it, DiscoveryCache.Namespace.MAPS)
        }
    }

    private fun resolveFromCache(
        classLoader: ClassLoader,
        payload: DiscoveryCache.CachePayload,
    ): DiscoveredTargets? {
        fun methods(tag: String) = payload.members
            .filter { it.tag == tag }
            .mapNotNull { DiscoveryCache.resolveMethod(classLoader, it) }

        fun classes(tag: String) = payload.members
            .filter { it.tag == tag }
            .mapNotNull { DiscoveryCache.resolveClass(classLoader, it) }

        val headerTaps = payload.members
            .filter { it.tag.startsWith("header_tap") }
            .mapNotNull { ref ->
                val method = DiscoveryCache.resolveMethod(classLoader, ref) ?: return@mapNotNull null
                val field = ref.tag.substringAfter('|', missingDelimiterValue = "")
                if (field.isEmpty()) return@mapNotNull null
                SearchHeaderTap(
                    headerClass = method.declaringClass,
                    tapMethod = method,
                    rekFieldName = field,
                )
            }

        return DiscoveredTargets(
            hintMethods = methods("hint"),
            rekOverlayTypes = classes("rek"),
            carImeTypes = classes("car_ime"),
            searchHeaderTaps = headerTaps,
            carParameterMethods = methods("car_params"),
            keyboardRestrictedMethods = methods("kbd_restricted"),
            voiceBypassMethods = methods("voice_bypass"),
            headerRestrictionConstructors = payload.members
                .filter { it.tag == "header_restrict_ctor" }
                .mapNotNull { DiscoveryCache.resolveConstructor(classLoader, it) },
            uiStateTypes = classes("ui_state"),
            stats = ScanStats(),
            fromCache = true,
        )
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
        val uiStateTypes = linkedSetOf<Class<*>>()
        val seen = mutableSetOf<String>()

        val paths = ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }
        stats.pathsScanned = paths.size
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "dex scan starting paths=${paths.size} ${paths.joinToString { it.substringAfterLast('/') }}",
            always = true
        )

        val entrySamples = mutableListOf<String>()

        // Prefer ClassLoader dexElements (all multidex already mapped). DexFile(apkPath) often
        // only enumerates classes.dex and misses UiState/controllers in classes5+.
        val dexFiles = collectDexFiles(ctx.classLoader, paths, ctx.packageName)
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "dex sources=${dexFiles.size} (classLoader+apk multidex)",
            always = true
        )

        for ((label, dex) in dexFiles) {
            runCatching {
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
                            runCatching {
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
                                    uiStateTypes,
                                    stats,
                                )
                            }.onFailure { error ->
                                if (stats.firstLoadError == null) {
                                    stats.firstLoadError =
                                        "inspect ${clazz.simpleName}: ${error.javaClass.simpleName}: ${error.message}"
                                }
                            }
                        }
                    }
                }
            }.onFailure { error ->
                stats.dexOpenFailures++
                if (stats.firstDexError == null) {
                    stats.firstDexError = "$label: ${error.message}"
                }
            }
        }

        if (uiStateTypes.isEmpty()) {
            resolveUiStateViaStringAnchor(ctx, paths, uiStateTypes, headerTaps)
        }

        val allRek = linkedSetOf<Class<*>>()
        allRek += rekTypes
        allRek += rekLooseTypes

        // Prefer controllers that hold car-search UiState (qnu-shaped) over generic
        // l()+rek false positives (aaca-shaped). rkw is an interface without View anchors.
        val rankedHeaderTaps = headerTaps
            .sortedByDescending { scoreSearchHeaderTap(it, uiStateTypes) }
            .take(8)

        val carGraph = buildCarGraphClasses(rankedHeaderTaps, allRek, carImeTypes)

        logScanStats(stats, entrySamples)

        return DiscoveredTargets(
            hintMethods = hints
                .distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterCount}" }
                .sortedByDescending { scoreHintMethod(it) }
                .take(16),
            rekOverlayTypes = allRek.sortedByDescending { scoreRekType(it) }.take(20),
            carImeTypes = carImeTypes.sortedBy { it.name }.take(10),
            searchHeaderTaps = headerTaps.toList(),
            carParameterMethods = carParamMethods
                .filter { isSafeKeyboardCarParamsGetter(it) }
                .distinctBy { "${it.declaringClass.name}#${it.name}" }
                .sortedByDescending { scoreCarParamsMethod(it) }
                .take(8),
            keyboardRestrictedMethods = filterCarGraphRestrictedMethods(
                restrictedMethods.sortedByDescending { scoreRestrictedMethod(it) },
                carGraph,
            ).take(32),
            voiceBypassMethods = voiceMethods
                .filter { isVoiceBypassMethod(it, it.declaringClass) }
                .filter { method ->
                    // Stay near search/IME graph — never hook unrelated l(int) like bofy.
                    carGraph.contains(method.declaringClass) ||
                        rankedHeaderTaps.any { it.headerClass == method.declaringClass }
                }
                .sortedByDescending { scoreVoiceBypassMethod(it) }
                .take(4),
            headerRestrictionConstructors = headerCtors
                .filter { MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes) }
                .ifEmpty { headerCtors.take(24) }
                .take(24),
            uiStateTypes = uiStateTypes.sortedBy { it.name }.take(8),
            stats = stats,
            fromCache = false,
        )
    }

    /**
     * Fill gaps after dex/string scan. Optional short-name candidates are accepted only when
     * API shape matches (same contract as Gearhead seeds) — never hooked by name alone.
     */
    private fun enrichGaps(ctx: HookContext, base: DiscoveredTargets): DiscoveredTargets {
        val hints = base.hintMethods.toMutableList()
        val headerTaps = linkedSetOf<SearchHeaderTap>().also { it += base.searchHeaderTaps }
        val uiStateTypes = linkedSetOf<Class<*>>().also { it += base.uiStateTypes }
        val headerCtors = base.headerRestrictionConstructors.toMutableList()
        val paths = ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }

        if (uiStateTypes.isEmpty()) {
            resolveUiStateViaStringAnchor(ctx, paths, uiStateTypes, headerTaps)
        }

        applyShapeValidatedFallbacks(ctx, hints, headerTaps, uiStateTypes, headerCtors)

        val ranked = headerTaps
            .sortedByDescending { scoreSearchHeaderTap(it, uiStateTypes) }
            .take(8)

        return base.copy(
            hintMethods = hints
                .distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterCount}" }
                .sortedByDescending { scoreHintMethod(it) }
                .take(16),
            searchHeaderTaps = ranked,
            headerRestrictionConstructors = headerCtors
                .filter { MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes) }
                .ifEmpty { headerCtors }
                .distinct()
                .take(24),
            uiStateTypes = uiStateTypes.sortedBy { it.name }.take(8),
        )
    }

    /**
     * Fill critical gaps after cache/string scan by walking dex for matching API shapes only.
     * No obfuscated short-name probes — names in logs are discovered at runtime, not baked in.
     */
    private fun applyShapeValidatedFallbacks(
        ctx: HookContext,
        hints: MutableList<Method>,
        headerTaps: MutableSet<SearchHeaderTap>,
        uiStateTypes: MutableSet<Class<*>>,
        headerCtors: MutableList<Constructor<*>>,
    ) {
        inferUiStateFromHeaderFields(headerTaps, uiStateTypes, headerCtors)

        if (uiStateTypes.isNotEmpty()) {
            for (tap in headerTaps.toList()) {
                if (!headerHoldsUiState(tap.headerClass, uiStateTypes)) continue
                ModuleLog.maps(
                    "MAPS-DRIVE-010",
                    "shape-validated header ${tap.headerClass.simpleName}.${tap.tapMethod.name}()",
                    always = true,
                )
            }
            if (!headerTaps.any { headerHoldsUiState(it.headerClass, uiStateTypes) }) {
                discoverUiStateLinkedHeaders(ctx, uiStateTypes, headerTaps)
            }
        }

        if (!hasDrivingHint(hints)) {
            discoverDrivingHintFromDex(ctx, hints)
        }
    }

    private fun inferUiStateFromHeaderFields(
        headerTaps: Collection<SearchHeaderTap>,
        uiStateTypes: MutableSet<Class<*>>,
        headerCtors: MutableList<Constructor<*>>,
    ) {
        for (tap in headerTaps) {
            runCatching {
                for (field in tap.headerClass.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val fieldType = field.type
                    val uiCtor = fieldType.declaredConstructors.firstOrNull {
                        MapsCarUiStatePatches.matchesCarSearchUiStateConstructor(it)
                    } ?: continue
                    if (uiStateTypes.add(fieldType)) {
                        headerCtors += uiCtor
                        ModuleLog.maps(
                            "MAPS-DRIVE-010",
                            "shape-validated UiState via header field ${fieldType.simpleName}",
                            always = true,
                        )
                    }
                }
            }
        }
    }

    private fun discoverUiStateFromDex(
        ctx: HookContext,
        uiStateTypes: MutableSet<Class<*>>,
        headerCtors: MutableList<Constructor<*>>,
    ) {
        val paths = ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }
        val dexFiles = collectDexFiles(ctx.classLoader, paths, ctx.packageName)
        val seen = mutableSetOf<String>()
        for ((_, dex) in dexFiles) {
            runCatching {
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = normalizeDexClassName(entries.nextElement())
                    if (!isObfuscatedMapsClass(name)) continue
                    if (!seen.add(name)) continue
                    val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                    if (clazz.isInterface || isCoroutineLike(clazz)) continue
                    val uiCtor = clazz.declaredConstructors.firstOrNull {
                        MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes)
                    } ?: continue
                    if (uiStateTypes.add(clazz)) {
                        headerCtors += uiCtor
                        ModuleLog.maps(
                            "MAPS-DRIVE-010",
                            "shape-validated UiState ${clazz.simpleName}",
                            always = true,
                        )
                    }
                }
            }
        }
    }

    private fun discoverUiStateLinkedHeaders(
        ctx: HookContext,
        uiStateTypes: Set<Class<*>>,
        headerTaps: MutableSet<SearchHeaderTap>,
    ) {
        val paths = ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }
        val dexFiles = collectDexFiles(ctx.classLoader, paths, ctx.packageName)
        val seen = mutableSetOf<String>()
        for ((_, dex) in dexFiles) {
            runCatching {
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = normalizeDexClassName(entries.nextElement())
                    if (!isObfuscatedMapsClass(name)) continue
                    if (!seen.add(name)) continue
                    val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                    if (clazz.isInterface || isCoroutineLike(clazz)) continue
                    if (!headerHoldsUiState(clazz, uiStateTypes)) continue
                    val tap = findSearchHeaderTap(clazz) ?: continue
                    if (headerTaps.any { it.headerClass == clazz && it.tapMethod.name == tap.tapMethod.name }) {
                        continue
                    }
                    headerTaps.add(tap)
                    ModuleLog.maps(
                        "MAPS-DRIVE-010",
                        "shape-validated header ${clazz.simpleName}.${tap.tapMethod.name}()",
                        always = true,
                    )
                }
            }
        }
    }

    private fun discoverDrivingHintFromDex(
        ctx: HookContext,
        hints: MutableList<Method>,
    ) {
        val paths = ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }
        val dexFiles = collectDexFiles(ctx.classLoader, paths, ctx.packageName)
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        val seen = mutableSetOf<String>()
        for ((_, dex) in dexFiles) {
            if (hasDrivingHint(hints)) return
            runCatching {
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = normalizeDexClassName(entries.nextElement())
                    if (!isObfuscatedMapsClass(name)) continue
                    if (!seen.add(name)) continue
                    val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                    if (clazz.isInterface || isCoroutineLike(clazz)) continue
                    for (method in clazz.declaredMethods) {
                        if (!Modifier.isStatic(method.modifiers)) continue
                        if (method.returnType != String::class.java) continue
                        if (method.parameterTypes.firstOrNull()?.name != Context::class.java.name) continue
                        val bools = method.parameterTypes.count {
                            it == booleanPrimitive || it == Boolean::class.java
                        }
                        if (bools < 2) continue
                        hints += method
                        ModuleLog.maps(
                            "MAPS-DRIVE-010",
                            "shape-validated hint ${clazz.simpleName}.${method.name}()",
                            always = true,
                        )
                        return
                    }
                }
            }
        }
    }

    private fun hasDrivingHint(hints: List<Method>): Boolean =
        hints.any { method ->
            method.parameterTypes.firstOrNull()?.name == Context::class.java.name &&
                method.parameterTypes.count {
                    it == Boolean::class.javaPrimitiveType || it == Boolean::class.java
                } >= 2
        }

    private fun isValidatedCarSearchHeader(
        clazz: Class<*>,
        uiStateTypes: Set<Class<*>>,
    ): Boolean {
        findSearchHeaderTap(clazz) ?: return false
        if (uiStateTypes.isNotEmpty() && headerHoldsUiState(clazz, uiStateTypes)) return true
        // UiState may not be registered yet — accept zero-arg getter whose return type
        // itself matches the car-search UiState constructor shape.
        return clazz.declaredMethods.any { method ->
            method.parameterCount == 0 &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType.declaredConstructors.any {
                    MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes)
                }
        } || clazz.declaredConstructors.any { ctor ->
            ctor.parameterTypes.any { type ->
                type.declaredConstructors.any {
                    MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes)
                }
            }
        }
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
        uiStateTypes: MutableSet<Class<*>>,
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

        for (ctor in clazz.declaredConstructors) {
            if (MapsCarUiStatePatches.isCarSearchUiStateConstructor(ctor.parameterTypes)) {
                uiStateTypes += clazz
                headerCtors += ctor
            } else if (isHeaderRestrictionConstructor(ctor)) {
                headerCtors += ctor
            }
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
            ZipFile(path).use { zip -> zip.getEntry("classes.dex") != null }
        }.getOrDefault(true)
    }

    /**
     * All DexFile instances we can enumerate: ClassLoader pathList first (multidex), then
     * each classes*.dex extracted from APK zips as fallback.
     */
    private fun collectDexFiles(
        classLoader: ClassLoader,
        apkPaths: List<String>,
        packageName: String? = null,
    ): List<Pair<String, DexFile>> {
        val out = mutableListOf<Pair<String, DexFile>>()
        val seen = mutableSetOf<Int>()

        fun add(label: String, dex: DexFile?) {
            if (dex == null) return
            val id = System.identityHashCode(dex)
            if (!seen.add(id)) return
            out += label to dex
        }

        runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .also { it.isAccessible = true }
            val dexElementsField = Class.forName("dalvik.system.DexPathList")
                .getDeclaredField("dexElements")
                .also { it.isAccessible = true }
            val dexFileField = Class.forName("dalvik.system.DexPathList\$Element")
                .getDeclaredField("dexFile")
                .also { it.isAccessible = true }

            var loader: ClassLoader? = classLoader
            while (loader != null) {
                if (Class.forName("dalvik.system.BaseDexClassLoader").isInstance(loader)) {
                    val pathList = pathListField.get(loader)
                    val elements = dexElementsField.get(pathList) as? Array<*>
                    if (elements != null) {
                        for ((index, element) in elements.withIndex()) {
                            if (element == null) continue
                            add(
                                "cl#${loader.javaClass.simpleName}[$index]",
                                dexFileField.get(element) as? DexFile,
                            )
                        }
                    }
                }
                loader = loader.parent
            }
        }

        // ClassLoader may omit secondary dex; always merge classes*.dex extracted from APK zips.
        val beforeApk = out.size
        val dexCache = GearheadSignatureDiscovery.resolveDexCacheDir("aa_ku_maps_dex", packageName)
        if (dexCache == null) {
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "WARN apk dex merge skipped — no cache dir",
                always = true,
            )
        } else {
            for (path in apkPaths) {
                if (!path.endsWith(".apk") || !apkContainsDex(path)) continue
                runCatching {
                    ZipFile(path).use { zip ->
                        val dexNames = zip.entries().asSequence()
                            .map { it.name }
                            .filter { it == "classes.dex" || it.matches(Regex("""classes\d+\.dex""")) }
                            .toList()
                        for (dexName in dexNames) {
                            val entry = zip.getEntry(dexName) ?: continue
                            val outFile = File(dexCache, "${File(path).name}_$dexName")
                            if (!outFile.exists() || outFile.length() != entry.size) {
                                zip.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { input.copyTo(it) }
                                }
                            }
                            if (outFile.canWrite()) {
                                outFile.setReadOnly()
                            }
                            add("${File(path).name}/$dexName", DexFile(outFile.absolutePath))
                        }
                    }
                }.onFailure { error ->
                    ModuleLog.maps(
                        "MAPS-DRIVE-010",
                        "apk dex extract failed ${File(path).name}: ${error.message}",
                        always = true,
                    )
                }
            }
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "apk dex merged=${out.size - beforeApk} total=${out.size}",
                always = true,
            )
        }
        return out
    }

    /**
     * Fallback when multidex scan still missed UiState: find classes near the
     * `isMicRestricted=` toString constant, validate ctor shape, then attach header taps.
     */
    private fun resolveUiStateViaStringAnchor(
        ctx: HookContext,
        paths: List<String>,
        uiStateTypes: MutableSet<Class<*>>,
        headerTaps: MutableSet<SearchHeaderTap>,
    ) {
        val needles = listOf(
            "UiState(searchQuery=",
            "DistractionState(isKeyboardRestricted=",
            "isMicRestricted=",
            "isKeyboardRestricted=",
        )
        val candidates = linkedSetOf<String>()
        for (path in paths) {
            DexHooks.findClassesReferencingStrings(path, needles, limit = 120).forEach { (name, _) ->
                candidates += name
                candidates += name.substringAfterLast('.')
            }
        }
        for (name in candidates) {
            val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
            if (clazz.isInterface || isCoroutineLike(clazz)) continue
            runCatching {
                val isUi = clazz.declaredConstructors.any {
                    MapsCarUiStatePatches.isCarSearchUiStateConstructor(it.parameterTypes)
                }
                if (isUi) uiStateTypes += clazz
                findSearchHeaderTap(clazz)?.let { headerTaps += it }
            }
        }
        if (uiStateTypes.isEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "WARN UiState string-anchor miss candidates=${candidates.size}",
                always = true
            )
            return
        }
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "UiState via string-anchor x${uiStateTypes.size}: " +
                uiStateTypes.joinToString { it.simpleName },
            always = true
        )
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
                "voiceBypass=${targets.voiceBypassMethods.size} headerCtor=${targets.headerRestrictionConstructors.size} " +
                "uiState=${targets.uiStateTypes.size}",
            always = true
        )
        if (targets.hintMethods.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "hint methods: " + targets.hintMethods.take(4).joinToString { formatMethod(it) },
                always = true
            )
        }
        if (targets.uiStateTypes.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "uiState types: " + targets.uiStateTypes.joinToString { it.simpleName },
                always = true
            )
        }
        if (targets.searchHeaderTaps.isNotEmpty()) {
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "search header taps: " + targets.searchHeaderTaps.take(8).joinToString {
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
        } ?: clazz.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && isRekOverlayTypeLoose(field.type)
        } ?: clazz.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && MapsInstallProbe.isRekLikeType(field.type)
        } ?: clazz.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && isRlrKeyboardOpener(field.type)
        } ?: return null

        return SearchHeaderTap(clazz, tapMethod, rekField.name)
    }

    private fun hasTapMethodWithoutRek(clazz: Class<*>): Boolean {
        val hasL = walkMethods(clazz).any { it.name == "l" && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
        return hasL && clazz.declaredFields.none { !Modifier.isStatic(it.modifiers) && isRekFieldType(it.type) }
    }

    fun isRekFieldType(type: Class<*>): Boolean =
        isRekOverlayTypeLoose(type) ||
            MapsInstallProbe.isRekLikeType(type) ||
            isRlrKeyboardOpener(type)

    /** Maps 26.31+ destination-search header holds an [rlr] keyboard opener (d/e/…), not rek. */
    private fun isRlrKeyboardOpener(type: Class<*>): Boolean {
        if (!type.isInterface) return false
        val methods = type.declaredMethods
        val hasD = methods.any {
            it.name == "d" && it.parameterCount == 0 && it.returnType == Void.TYPE
        }
        val hasE = methods.any {
            it.name == "e" && it.parameterCount >= 1 && it.returnType == Void.TYPE
        }
        return hasD && hasE
    }

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

    private fun scoreSearchHeaderTap(
        tap: SearchHeaderTap,
        uiStateTypes: Set<Class<*>>,
    ): Int {
        var score = 0
        val header = tap.headerClass
        val rekType = header.declaredFields.firstOrNull { it.name == tap.rekFieldName }?.type

        // Real destination-search controller holds HeaderViewModel UiState (qnp-shaped).
        if (uiStateTypes.isNotEmpty() && headerHoldsUiState(header, uiStateTypes)) {
            score += 100
        }

        // rkw-shaped keyboard opener is often an interface (d + e(...)) without View anchors.
        if (rekType != null) {
            when {
                isRlrKeyboardOpener(rekType) -> score += 200
                isRekOverlayTypeStrict(rekType) -> score += 10
                isRekOverlayTypeLoose(rekType) -> score += 8
                MapsInstallProbe.isRekLikeType(rekType) -> score += 12
            }
            if (rekType.isInterface) score += 15
        }

        // qnu implements a rich header interface (qnq) and takes Context in a ctor.
        if (header.interfaces.any { iface ->
                walkMethods(iface).count { it.name == "l" && it.parameterCount == 0 } > 0 &&
                    iface.declaredMethods.size >= 8
            }
        ) {
            score += 25
        }
        if (header.declaredConstructors.any { ctor ->
                ctor.parameterTypes.any { it.name == Context::class.java.name }
            }
        ) {
            score += 10
        }

        // Prefer fewer fields than sprawling UI glue classes when scores otherwise tie.
        val instanceFields = header.declaredFields.count { !Modifier.isStatic(it.modifiers) }
        if (instanceFields in 8..30) score += 5

        return score
    }

    private fun headerHoldsUiState(header: Class<*>, uiStateTypes: Set<Class<*>>): Boolean {
        for (field in header.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            if (field.type in uiStateTypes) return true
        }
        for (ctor in header.declaredConstructors) {
            if (ctor.parameterTypes.any { it in uiStateTypes }) return true
        }
        for (method in header.declaredMethods) {
            if (method.returnType in uiStateTypes) return true
            if (method.parameterTypes.any { it in uiStateTypes }) return true
        }
        return false
    }

    private fun isVoiceBypassMethod(method: Method, clazz: Class<*>): Boolean {
        if (Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 1) return false
        if (method.parameterTypes[0] != Int::class.javaPrimitiveType) return false
        if (method.returnType != Void.TYPE) return false
        if (method.name != "l" && method.name != "m") return false
        // Must be a search-header-like controller with a rek keyboard field.
        // The old "methods.size <= 30" branch matched unrelated types (e.g. bofy) and
        // crashing navigation start (bofy.a / bofz.<clinit>).
        return clazz.declaredFields.any { !Modifier.isStatic(it.modifiers) && isRekFieldType(it.type) }
    }

    /**
     * Avoid mutating boolean A/c on broad Maps parameter hubs (navigation camera, ads, …).
     * Those types often share field letters with keyboard car-params but mean something else.
     */
    private fun isSafeKeyboardCarParamsGetter(method: Method): Boolean {
        val name = method.name
        if (name.startsWith("get") && name.length > 5) return false
        if (name.contains("Navigation", ignoreCase = true)) return false
        if (name.contains("Placesheet", ignoreCase = true)) return false
        if (name.contains("MapAds", ignoreCase = true)) return false
        if (name.contains("MapContent", ignoreCase = true)) return false
        if (name.contains("MapCore", ignoreCase = true)) return false
        if (name.contains("Suggest", ignoreCase = true)) return false
        return isCarParamsType(method.returnType)
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
        if (params.firstOrNull()?.name == Context::class.java.name) score += 5
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
