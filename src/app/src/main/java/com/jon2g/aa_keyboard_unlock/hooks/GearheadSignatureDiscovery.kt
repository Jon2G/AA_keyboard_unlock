package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import dalvik.system.DexFile
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Discover Gearhead hook targets by API shape / stable anchors, not R8 short names.
 * Results are persisted via [DiscoveryCache] keyed by gearhead [longVersionCode].
 */
object GearheadSignatureDiscovery {
    private const val TAG_SENSOR = "sensor_d"
    private const val TAG_LOC_Q = "loc_q"
    private const val TAG_LOC_S = "loc_s"
    private const val TAG_LOC_C = "loc_c"
    private const val TAG_LOC_F = "loc_f"
    private const val TAG_PARK_ENUM = "park_enum"
    private const val TAG_ASSIST_KBD = "assist_kbd_a"
    private const val TAG_CAR_UI_A = "car_ui_a"
    private const val TAG_CAR_UI_B = "car_ui_b"
    private const val TAG_CAR_APP_BLOCKED = "car_app_blocked"
    private const val TAG_CAR_APP_CONSTRAINTS = "car_app_constraints"
    private const val TAG_IME_SERVICE = "ime_service"
    private const val TAG_IME_CACHE = "ime_cache_c"
    private const val TAG_IME_START = "ime_start_h"
    private const val TAG_IME_FRAG_BASE = "ime_frag_base"
    private const val TAG_IME_ON_START = "ime_on_start"
    private const val TAG_IME_UNLOCK_D = "ime_unlock_d"
    private const val TAG_IME_ROTARY_K = "ime_rotary_k"
    private const val TAG_IME_FACTORY_E = "ime_factory_e"
    private const val TAG_VOICE_CTRL = "voice_ctrl"
    private const val TAG_VOICE_F = "voice_F"
    private const val TAG_VOICE_G = "voice_G"
    private const val TAG_VOICE_SESSION = "voice_session"
    private const val TAG_DEMAND_K = "demand_k"
    private const val TAG_DEMAND_L = "demand_l"
    private const val TAG_DEMAND_B = "demand_b"
    private const val TAG_TEXT_FIELD_CTOR = "text_field_ctor"
    private const val TAG_KBD_RESTRICT_CTOR = "kbd_restrict_ctor"
    private const val TAG_SEARCH_HINT_D = "search_hint_d"
    private const val TAG_KBD_CALLBACK = "kbd_callback_iface"

    data class DiscoveredTargets(
        val sensorCallbacks: List<Method> = emptyList(),
        val locationKeyboardEnabled: Method? = null,
        val locationWheelSpeedNonZero: Method? = null,
        val locationParkingState: Method? = null,
        val locationSpeed: Method? = null,
        val parkingEnumClass: Class<*>? = null,
        val assistantKeyboardEnabled: Method? = null,
        val carUiTouchscreen: Method? = null,
        val carUiTouchpad: Method? = null,
        val carAppKeyboardBlocked: Method? = null,
        val carAppConstraintsClass: Class<*>? = null,
        val imeServiceClass: Class<*>? = null,
        val imeCacheMethod: Method? = null,
        val imeStartExternal: Method? = null,
        val imeFragmentBase: Class<*>? = null,
        val imeOnStart: Method? = null,
        val imeUnlockMethods: List<Method> = emptyList(),
        val imeRotaryLockout: Method? = null,
        val imeFactory: Method? = null,
        val voiceController: Class<*>? = null,
        val voiceTriggerF: Method? = null,
        val voiceTriggerG: Method? = null,
        val voiceSessionStart: Method? = null,
        val demandOpen: Method? = null,
        val demandTranscription: Method? = null,
        val demandOpenCause: Method? = null,
        val textFieldCtor: Constructor<*>? = null,
        val keyboardRestrictionCtor: Constructor<*>? = null,
        val searchHintMethod: Method? = null,
        val keyboardCallbackInterface: Class<*>? = null,
        val fromCache: Boolean = false,
    ) {
        fun carParkedValue(): Any? {
            val enumClass = parkingEnumClass ?: return null
            return runCatching {
                Reflect.getStaticObjectField(enumClass, "b")
            }.getOrNull()
        }

        fun toCachePayload(): DiscoveryCache.CachePayload {
            val members = mutableListOf<DiscoveryCache.MemberRef>()
            sensorCallbacks.forEach { members += DiscoveryCache.methodRef(it, TAG_SENSOR) }
            locationKeyboardEnabled?.let { members += DiscoveryCache.methodRef(it, TAG_LOC_Q) }
            locationWheelSpeedNonZero?.let { members += DiscoveryCache.methodRef(it, TAG_LOC_S) }
            locationParkingState?.let { members += DiscoveryCache.methodRef(it, TAG_LOC_C) }
            locationSpeed?.let { members += DiscoveryCache.methodRef(it, TAG_LOC_F) }
            parkingEnumClass?.let { members += DiscoveryCache.classRef(it, TAG_PARK_ENUM) }
            assistantKeyboardEnabled?.let { members += DiscoveryCache.methodRef(it, TAG_ASSIST_KBD) }
            carUiTouchscreen?.let { members += DiscoveryCache.methodRef(it, TAG_CAR_UI_A) }
            carUiTouchpad?.let { members += DiscoveryCache.methodRef(it, TAG_CAR_UI_B) }
            carAppKeyboardBlocked?.let { members += DiscoveryCache.methodRef(it, TAG_CAR_APP_BLOCKED) }
            carAppConstraintsClass?.let { members += DiscoveryCache.classRef(it, TAG_CAR_APP_CONSTRAINTS) }
            imeServiceClass?.let { members += DiscoveryCache.classRef(it, TAG_IME_SERVICE) }
            imeCacheMethod?.let { members += DiscoveryCache.methodRef(it, TAG_IME_CACHE) }
            imeStartExternal?.let { members += DiscoveryCache.methodRef(it, TAG_IME_START) }
            imeFragmentBase?.let { members += DiscoveryCache.classRef(it, TAG_IME_FRAG_BASE) }
            imeOnStart?.let { members += DiscoveryCache.methodRef(it, TAG_IME_ON_START) }
            imeUnlockMethods.forEach { members += DiscoveryCache.methodRef(it, TAG_IME_UNLOCK_D) }
            imeRotaryLockout?.let { members += DiscoveryCache.methodRef(it, TAG_IME_ROTARY_K) }
            imeFactory?.let { members += DiscoveryCache.methodRef(it, TAG_IME_FACTORY_E) }
            voiceController?.let { members += DiscoveryCache.classRef(it, TAG_VOICE_CTRL) }
            voiceTriggerF?.let { members += DiscoveryCache.methodRef(it, TAG_VOICE_F) }
            voiceTriggerG?.let { members += DiscoveryCache.methodRef(it, TAG_VOICE_G) }
            voiceSessionStart?.let { members += DiscoveryCache.methodRef(it, TAG_VOICE_SESSION) }
            demandOpen?.let { members += DiscoveryCache.methodRef(it, TAG_DEMAND_K) }
            demandTranscription?.let { members += DiscoveryCache.methodRef(it, TAG_DEMAND_L) }
            demandOpenCause?.let { members += DiscoveryCache.methodRef(it, TAG_DEMAND_B) }
            textFieldCtor?.let { members += DiscoveryCache.ctorRef(it, TAG_TEXT_FIELD_CTOR) }
            keyboardRestrictionCtor?.let { members += DiscoveryCache.ctorRef(it, TAG_KBD_RESTRICT_CTOR) }
            searchHintMethod?.let { members += DiscoveryCache.methodRef(it, TAG_SEARCH_HINT_D) }
            keyboardCallbackInterface?.let { members += DiscoveryCache.classRef(it, TAG_KBD_CALLBACK) }
            return DiscoveryCache.CachePayload(members)
        }

        fun isEffectivelyEmpty(): Boolean =
            sensorCallbacks.isEmpty() &&
                imeCacheMethod == null &&
                voiceTriggerF == null &&
                locationKeyboardEnabled == null

        /** Critical 17.x driving/IME surface — enough to skip a full dex walk. */
        fun isAnchorComplete(): Boolean =
            sensorCallbacks.isNotEmpty() &&
                locationKeyboardEnabled != null &&
                locationParkingState != null &&
                parkingEnumClass != null &&
                imeCacheMethod != null &&
                imeUnlockMethods.isNotEmpty() &&
                voiceTriggerF != null &&
                demandOpen != null &&
                carAppKeyboardBlocked != null
    }

    @Volatile
    private var cached: DiscoveredTargets? = null

    fun discover(ctx: HookContext, force: Boolean = false): DiscoveredTargets {
        if (!force) {
            cached?.let { return it }
        } else {
            cached = null
        }

        val hostCtx = resolveHostContext()
        val fingerprint = hostCtx?.let {
            DiscoveryCache.packageFingerprint(it, ctx.packageName)
        }

        if (!force && hostCtx != null && fingerprint != null) {
            val payload = DiscoveryCache.load(hostCtx, DiscoveryCache.Namespace.GEARHEAD, fingerprint)
            if (payload != null) {
                val resolved = resolveFromCache(ctx.classLoader, payload)
                if (resolved != null && !resolved.isEffectivelyEmpty()) {
                    DiscoveryCache.logHit(
                        ModuleLog.Process.GH,
                        DiscoveryCache.Namespace.GEARHEAD,
                        fingerprint,
                        payload.members.size,
                    )
                    cached = resolved
                    return resolved
                }
                DiscoveryCache.logMiss(
                    ModuleLog.Process.GH,
                    DiscoveryCache.Namespace.GEARHEAD,
                    fingerprint,
                    "resolve_failed",
                )
            } else {
                DiscoveryCache.logMiss(
                    ModuleLog.Process.GH,
                    DiscoveryCache.Namespace.GEARHEAD,
                    fingerprint,
                    "no_entry",
                )
            }
        } else if (fingerprint != null) {
            DiscoveryCache.logMiss(
                ModuleLog.Process.GH,
                DiscoveryCache.Namespace.GEARHEAD,
                fingerprint,
                if (force) "forced" else "no_context",
            )
        }

        val scanned = scan(ctx)
        cached = scanned
        if (hostCtx != null && fingerprint != null && !scanned.isEffectivelyEmpty()) {
            val payload = scanned.toCachePayload()
            DiscoveryCache.save(hostCtx, DiscoveryCache.Namespace.GEARHEAD, fingerprint, payload)
            DiscoveryCache.logWrite(
                ModuleLog.Process.GH,
                DiscoveryCache.Namespace.GEARHEAD,
                fingerprint,
                payload.members.size,
            )
        }
        logDiscovery(scanned)
        return scanned
    }

    fun invalidate(context: Context? = resolveHostContext()) {
        cached = null
        context?.let { DiscoveryCache.clear(it, DiscoveryCache.Namespace.GEARHEAD) }
    }

    private fun resolveFromCache(
        classLoader: ClassLoader,
        payload: DiscoveryCache.CachePayload,
    ): DiscoveredTargets? {
        fun methods(tag: String) = payload.members
            .filter { it.tag == tag }
            .mapNotNull { DiscoveryCache.resolveMethod(classLoader, it) }

        fun method(tag: String) = methods(tag).firstOrNull()
        fun clazz(tag: String) = payload.members
            .firstOrNull { it.tag == tag }
            ?.let { DiscoveryCache.resolveClass(classLoader, it) }

        fun ctor(tag: String) = payload.members
            .firstOrNull { it.tag == tag }
            ?.let { DiscoveryCache.resolveConstructor(classLoader, it) }

        return DiscoveredTargets(
            sensorCallbacks = methods(TAG_SENSOR),
            locationKeyboardEnabled = method(TAG_LOC_Q),
            locationWheelSpeedNonZero = method(TAG_LOC_S),
            locationParkingState = method(TAG_LOC_C),
            locationSpeed = method(TAG_LOC_F),
            parkingEnumClass = clazz(TAG_PARK_ENUM),
            assistantKeyboardEnabled = method(TAG_ASSIST_KBD),
            carUiTouchscreen = method(TAG_CAR_UI_A),
            carUiTouchpad = method(TAG_CAR_UI_B),
            carAppKeyboardBlocked = method(TAG_CAR_APP_BLOCKED),
            carAppConstraintsClass = clazz(TAG_CAR_APP_CONSTRAINTS),
            imeServiceClass = clazz(TAG_IME_SERVICE),
            imeCacheMethod = method(TAG_IME_CACHE),
            imeStartExternal = method(TAG_IME_START),
            imeFragmentBase = clazz(TAG_IME_FRAG_BASE),
            imeOnStart = method(TAG_IME_ON_START),
            imeUnlockMethods = methods(TAG_IME_UNLOCK_D),
            imeRotaryLockout = method(TAG_IME_ROTARY_K),
            imeFactory = method(TAG_IME_FACTORY_E),
            voiceController = clazz(TAG_VOICE_CTRL),
            voiceTriggerF = method(TAG_VOICE_F),
            voiceTriggerG = method(TAG_VOICE_G),
            voiceSessionStart = method(TAG_VOICE_SESSION),
            demandOpen = method(TAG_DEMAND_K),
            demandTranscription = method(TAG_DEMAND_L),
            demandOpenCause = method(TAG_DEMAND_B),
            textFieldCtor = ctor(TAG_TEXT_FIELD_CTOR),
            keyboardRestrictionCtor = ctor(TAG_KBD_RESTRICT_CTOR),
            searchHintMethod = method(TAG_SEARCH_HINT_D),
            keyboardCallbackInterface = clazz(TAG_KBD_CALLBACK),
            fromCache = true,
        )
    }

    private fun scan(ctx: HookContext): DiscoveredTargets {
        ModuleLog.gearhead(
            "GH-DRIVE-010",
            "dex scan starting paths=${ctx.sourcePaths.size}",
            always = true,
        )

        val carRegionId = runCatching {
            Reflect.findClass("com.google.android.gms.car.display.CarRegionId", ctx.classLoader)
        }.getOrNull()
        val voiceSessionConfig = runCatching {
            Reflect.findClass(
                "com.google.android.gearhead.sdk.assistant.VoiceSessionConfig",
                ctx.classLoader,
            )
        }.getOrNull()
        val fragmentClass = runCatching {
            Reflect.findClass("androidx.fragment.app.Fragment", ctx.classLoader)
        }.getOrNull() ?: runCatching {
            Reflect.findClass("android.app.Fragment", ctx.classLoader)
        }.getOrNull()
        val sensorIface = loadObfuscatedClass(ctx.classLoader, "qqf")
        val demandIface = loadObfuscatedClass(ctx.classLoader, "kvp")
        val voiceIface = loadObfuscatedClass(ctx.classLoader, "kvj")

        // Anchor-first seeds (stable FQCNs / known 17.3 shapes) before loose dex heuristics.
        val seeded = resolveAnchors(ctx.classLoader, carRegionId, voiceSessionConfig, sensorIface)
        if (seeded.isAnchorComplete()) {
            val demandOpenCause = runCatching {
                val demand = Reflect.findClass(
                    "com.google.android.gearhead.demand.DemandClientService",
                    ctx.classLoader,
                )
                demand.getDeclaredMethod("b", Bundle::class.java).also { it.isAccessible = true }
            }.getOrNull()
            val anchored = seeded.copy(demandOpenCause = demandOpenCause)
            ModuleLog.gearhead(
                "GH-DRIVE-010",
                "dex scan skipped — anchors complete " +
                    "sensors=${anchored.sensorCallbacks.size} " +
                    "ime=${anchored.imeServiceClass?.simpleName} " +
                    "voice=${anchored.voiceController?.simpleName} " +
                    "demand=${anchored.demandOpen?.declaringClass?.simpleName} " +
                    "imeFrags=${anchored.imeUnlockMethods.size}",
                always = true,
            )
            return anchored
        }

        val sensorMethods = linkedSetOf<Method>().also { it.addAll(seeded.sensorCallbacks) }
        var parkingEnum: Class<*>? = seeded.parkingEnumClass
        var locationQ: Method? = seeded.locationKeyboardEnabled
        var locationS: Method? = seeded.locationWheelSpeedNonZero
        var locationC: Method? = seeded.locationParkingState
        var locationF: Method? = seeded.locationSpeed
        var assistKbd: Method? = seeded.assistantKeyboardEnabled
        var carUiA: Method? = seeded.carUiTouchscreen
        var carUiB: Method? = seeded.carUiTouchpad
        var carAppBlocked: Method? = seeded.carAppKeyboardBlocked
        var carAppConstraints: Class<*>? = seeded.carAppConstraintsClass
        var imeService: Class<*>? = seeded.imeServiceClass
        var imeCache: Method? = seeded.imeCacheMethod
        var imeStart: Method? = seeded.imeStartExternal
        var imeFragBase: Class<*>? = seeded.imeFragmentBase
        var imeOnStart: Method? = seeded.imeOnStart
        val imeUnlock = linkedSetOf<Method>().also { it.addAll(seeded.imeUnlockMethods) }
        var imeRotary: Method? = seeded.imeRotaryLockout
        var imeFactory: Method? = seeded.imeFactory
        var voiceCtrl: Class<*>? = seeded.voiceController
        var voiceF: Method? = seeded.voiceTriggerF
        var voiceG: Method? = seeded.voiceTriggerG
        var voiceSession: Method? = seeded.voiceSessionStart
        var demandK: Method? = seeded.demandOpen
        var demandL: Method? = seeded.demandTranscription
        var textFieldCtor: Constructor<*>? = seeded.textFieldCtor
        var kbdRestrictCtor: Constructor<*>? = seeded.keyboardRestrictionCtor
        var searchHint: Method? = seeded.searchHintMethod
        var kbdCallback: Class<*>? = seeded.keyboardCallbackInterface

        val seen = mutableSetOf<String>()
        var loaded = 0

        for (path in ctx.sourcePaths.ifEmpty { listOf(ctx.sourcePath) }) {
            if (!apkContainsDex(path)) continue
            runCatching {
                val dex = DexFile(path)
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = normalizeDexClassName(entries.nextElement())
                    if (!isObfuscatedGearheadClass(name)) continue
                    if (!seen.add(name)) continue
                    val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                    loaded++
                    if (isCoroutineLike(clazz)) continue

                    if (parkingEnum == null && isParkingEnum(clazz)) {
                        parkingEnum = clazz
                    }

                    if (clazz.isInterface) {
                        if (kbdCallback == null && isKeyboardCallbackInterface(clazz)) {
                            kbdCallback = clazz
                        }
                        continue
                    }

                    // Prefer sensor callbacks that implement the sensor interface.
                    if (sensorIface != null && sensorIface.isAssignableFrom(clazz)) {
                        clazz.declaredMethods.filter {
                            isSensorCallback(it) && !Modifier.isAbstract(it.modifiers)
                        }.forEach { sensorMethods += it }
                    }

                    for (method in clazz.declaredMethods) {
                        if (sensorIface == null &&
                            isSensorCallback(method) &&
                            !Modifier.isAbstract(method.modifiers)
                        ) {
                            sensorMethods += method
                        }
                        if (carRegionId != null &&
                            imeCache == null &&
                            isImeCacheMethod(method, carRegionId)
                        ) {
                            imeService = clazz
                            imeCache = method
                        }
                        if (imeStart == null &&
                            imeService != null &&
                            clazz == imeService &&
                            isMaybeStartExternal(method)
                        ) {
                            imeStart = method
                        }
                        if (voiceSessionConfig != null && voiceF == null) {
                            if (isVoiceControllerClass(clazz, voiceSessionConfig, voiceIface) &&
                                isVoiceTriggerF(method)
                            ) {
                                voiceCtrl = clazz
                                voiceF = method
                            }
                            if (isVoiceControllerClass(clazz, voiceSessionConfig, voiceIface) &&
                                isVoiceTriggerG(method)
                            ) {
                                voiceCtrl = clazz
                                voiceG = method
                            }
                            if (isVoiceSessionStart(method, voiceSessionConfig) &&
                                isVoiceControllerClass(clazz, voiceSessionConfig, voiceIface)
                            ) {
                                voiceCtrl = clazz
                                if (voiceSession == null || method.name == "ab") {
                                    voiceSession = method
                                }
                            }
                        }
                        if (demandK == null &&
                            isDemandOpenK(method) &&
                            looksLikeDemandController(clazz, demandIface)
                        ) {
                            demandK = method
                        }
                        if (demandL == null &&
                            isDemandTranscriptionL(method) &&
                            looksLikeDemandController(clazz, demandIface)
                        ) {
                            demandL = method
                        }
                        if (searchHint == null && isSearchHintBuilder(method, clazz)) {
                            searchHint = method
                        }
                        if (assistKbd == null && isAssistantKeyboardEnabled(method, clazz)) {
                            assistKbd = method
                        }
                        if (isCarUiFlagPair(clazz)) {
                            if (carUiA == null && method.name == "a" && isCarUiFlagMethod(method)) {
                                carUiA = method
                            }
                            if (carUiB == null && method.name == "b" && isCarUiFlagMethod(method)) {
                                carUiB = method
                            }
                        }
                        if (carAppBlocked == null && isCarAppKeyboardBlocked(method)) {
                            carAppBlocked = method
                            carAppConstraints = clazz
                        }
                    }

                    for (ctor in clazz.declaredConstructors) {
                        if (textFieldCtor == null && isTextFieldCtor(ctor)) {
                            textFieldCtor = ctor
                        }
                        if (kbdRestrictCtor == null && isKeyboardRestrictionCtor(ctor, clazz)) {
                            kbdRestrictCtor = ctor
                        }
                    }

                    if (fragmentClass != null && fragmentClass.isAssignableFrom(clazz)) {
                        if (isImeFragmentBase(clazz)) {
                            imeFragBase = clazz
                            clazz.declaredMethods.firstOrNull {
                                it.name == "onStart" && it.parameterCount == 0
                            }?.let { imeOnStart = it }
                        }
                    }
                }
            }
        }

        // After IME service found, pick h()/start method on same class if missing.
        imeService?.let { svc ->
            if (imeStart == null) {
                imeStart = svc.declaredMethods.firstOrNull {
                    it.parameterCount == 0 &&
                        it.returnType == Void.TYPE &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.name == "h"
                }
            }
        }

        // Location manager: class with q()+s()+c() returning parking enum / booleans.
        if (parkingEnum != null) {
            for (name in seen) {
                val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) continue
                val q = clazz.declaredMethods.firstOrNull {
                    it.parameterCount == 0 &&
                        it.returnType == Boolean::class.javaPrimitiveType &&
                        it.name == "q"
                }
                val s = clazz.declaredMethods.firstOrNull {
                    it.parameterCount == 0 &&
                        it.returnType == Boolean::class.javaPrimitiveType &&
                        it.name == "s"
                }
                val c = clazz.declaredMethods.firstOrNull {
                    it.parameterCount == 0 && it.returnType == parkingEnum && it.name == "c"
                }
                val f = clazz.declaredMethods.firstOrNull {
                    it.parameterCount == 0 &&
                        it.returnType == java.lang.Float::class.java &&
                        it.name == "f"
                }
                if (q != null && s != null && c != null) {
                    locationQ = q
                    locationS = s
                    locationC = c
                    locationF = f
                    break
                }
            }
        }

        imeFragBase?.let { base ->
            for (name in seen) {
                val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                if (!base.isAssignableFrom(clazz) || Modifier.isAbstract(clazz.modifiers)) continue
                clazz.declaredMethods.firstOrNull {
                    it.name == "d" && it.parameterCount == 0 && !Modifier.isAbstract(it.modifiers)
                }?.let { imeUnlock += it }
                clazz.declaredMethods.firstOrNull {
                    it.name == "k" &&
                        it.parameterCount == 0 &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }?.let { imeRotary = it }
            }
            for (name in seen) {
                val clazz = loadObfuscatedClass(ctx.classLoader, name) ?: continue
                clazz.declaredMethods.firstOrNull {
                    it.parameterCount == 0 &&
                        it.returnType == base &&
                        !Modifier.isStatic(it.modifiers) &&
                        (it.name == "e" || Modifier.isAbstract(it.modifiers).not())
                }?.let {
                    if (imeFactory == null || it.name == "e") imeFactory = it
                }
            }
        }

        val demandOpenCause = runCatching {
            val demand = Reflect.findClass(
                "com.google.android.gearhead.demand.DemandClientService",
                ctx.classLoader,
            )
            demand.getDeclaredMethod("b", Bundle::class.java).also { it.isAccessible = true }
        }.getOrNull()

        ModuleLog.gearhead(
            "GH-DRIVE-010",
            "dex scan done loaded=$loaded sensors=${sensorMethods.size} " +
                "ime=${imeService?.simpleName} voice=${voiceCtrl?.simpleName} " +
                "locQ=${locationQ != null} demandK=${demandK != null}",
            always = true,
        )

        return DiscoveredTargets(
            sensorCallbacks = sensorMethods.toList(),
            locationKeyboardEnabled = locationQ,
            locationWheelSpeedNonZero = locationS,
            locationParkingState = locationC,
            locationSpeed = locationF,
            parkingEnumClass = parkingEnum,
            assistantKeyboardEnabled = assistKbd,
            carUiTouchscreen = carUiA,
            carUiTouchpad = carUiB,
            carAppKeyboardBlocked = carAppBlocked,
            carAppConstraintsClass = carAppConstraints,
            imeServiceClass = imeService,
            imeCacheMethod = imeCache,
            imeStartExternal = imeStart,
            imeFragmentBase = imeFragBase,
            imeOnStart = imeOnStart,
            imeUnlockMethods = imeUnlock.toList(),
            imeRotaryLockout = imeRotary,
            imeFactory = imeFactory,
            voiceController = voiceCtrl,
            voiceTriggerF = voiceF,
            voiceTriggerG = voiceG,
            voiceSessionStart = voiceSession,
            demandOpen = demandK,
            demandTranscription = demandL,
            demandOpenCause = demandOpenCause,
            textFieldCtor = textFieldCtor,
            keyboardRestrictionCtor = kbdRestrictCtor,
            searchHintMethod = searchHint,
            keyboardCallbackInterface = kbdCallback,
            fromCache = false,
        )
    }

    private fun logDiscovery(t: DiscoveredTargets) {
        ModuleLog.gearhead(
            "GH-DRIVE-010",
            "discovered sensors=${t.sensorCallbacks.size} " +
                "imeService=${t.imeServiceClass?.name} " +
                "imeFrags=${t.imeUnlockMethods.size} " +
                "voice=${t.voiceController?.name} " +
                "demandOpen=${t.demandOpen?.declaringClass?.simpleName} " +
                "carAppBlocked=${t.carAppKeyboardBlocked != null} " +
                "cache=${t.fromCache}",
            always = true,
        )
    }

    /**
     * Resolve critical targets via stable FQCNs / known short-name **seeds** before loose heuristics.
     * Seeds are validated by API shape ([isSensorCallback], [isVoiceControllerClass], etc.) — they are
     * not permanent remaps. When [isAnchorComplete] the full dex walk is skipped.
     */
    private fun resolveAnchors(
        classLoader: ClassLoader,
        carRegionId: Class<*>?,
        voiceSessionConfig: Class<*>?,
        sensorIface: Class<*>?,
    ): DiscoveredTargets {
        val sensors = linkedSetOf<Method>()
        for (name in listOf("lhl", "lhv")) {
            val clazz = loadObfuscatedClass(classLoader, name) ?: continue
            clazz.declaredMethods.filter {
                isSensorCallback(it) && !Modifier.isAbstract(it.modifiers)
            }.forEach { sensors += it }
        }

        val parkingEnum = loadObfuscatedClass(classLoader, "lhb")?.takeIf { isParkingEnum(it) }
        val lhu = loadObfuscatedClass(classLoader, "lhu")
        val locationQ = lhu?.declaredMethods?.firstOrNull {
            it.name == "q" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        val locationS = lhu?.declaredMethods?.firstOrNull {
            it.name == "s" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        val locationC = lhu?.declaredMethods?.firstOrNull {
            it.name == "c" && it.parameterCount == 0 && parkingEnum != null &&
                it.returnType == parkingEnum
        }
        val locationF = lhu?.declaredMethods?.firstOrNull {
            it.name == "f" && it.parameterCount == 0 &&
                it.returnType == java.lang.Float::class.java
        }

        val jqt = loadObfuscatedClass(classLoader, "jqt")
        val carUiA = jqt?.declaredMethods?.firstOrNull {
            it.name == "a" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        val carUiB = jqt?.declaredMethods?.firstOrNull {
            it.name == "b" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }

        val juv = loadObfuscatedClass(classLoader, "juv")
        val carAppBlocked = juv?.declaredMethods?.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == "b" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }

        // IME: TouchInputMethodService extends xbh extends xaq
        val touchIme = runCatching {
            Reflect.findClass(
                "com.google.android.projection.gearhead.input.TouchInputMethodService",
                classLoader,
            )
        }.getOrNull()
        var imeService: Class<*>? = touchIme
        var imeCache: Method? = null
        var imeStart: Method? = null
        if (carRegionId != null) {
            var cursor: Class<*>? = touchIme
            while (cursor != null && cursor != Any::class.java) {
                val cache = cursor.declaredMethods.firstOrNull { isImeCacheMethod(it, carRegionId) }
                if (cache != null) {
                    imeService = cursor
                    imeCache = cache
                    break
                }
                cursor = cursor.superclass
            }
            if (imeCache == null) {
                val xaq = loadObfuscatedClass(classLoader, "xaq")
                imeCache = xaq?.declaredMethods?.firstOrNull { isImeCacheMethod(it, carRegionId) }
                if (imeCache != null) imeService = xaq
            }
        }
        imeService?.declaredMethods?.firstOrNull {
            it.name == "h" && it.parameterCount == 0 && it.returnType == Void.TYPE
        }?.let { imeStart = it }

        val imeFragBase = loadObfuscatedClass(classLoader, "xaw")?.takeIf { isImeFragmentBase(it) }
        val imeOnStart = imeFragBase?.declaredMethods?.firstOrNull {
            it.name == "onStart" && it.parameterCount == 0
        }
        val imeUnlock = linkedSetOf<Method>()
        var imeRotary: Method? = null
        for (name in listOf("xbg", "xbp")) {
            val clazz = loadObfuscatedClass(classLoader, name) ?: continue
            clazz.declaredMethods.firstOrNull {
                it.name == "d" && it.parameterCount == 0 && !Modifier.isAbstract(it.modifiers)
            }?.let { imeUnlock += it }
            clazz.declaredMethods.firstOrNull {
                it.name == "k" && it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }?.let { imeRotary = it }
        }
        val imeFactory = loadObfuscatedClass(classLoader, "xbh")?.declaredMethods?.firstOrNull {
            it.name == "e" && it.parameterCount == 0
        }

        // Voice: prefer kxi / any kvj implementor with VoiceSessionConfig methods
        var voiceCtrl: Class<*>? = null
        var voiceF: Method? = null
        var voiceG: Method? = null
        var voiceSession: Method? = null
        if (voiceSessionConfig != null) {
            for (name in listOf("kxi")) {
                val clazz = loadObfuscatedClass(classLoader, name) ?: continue
                if (!isVoiceControllerClass(clazz, voiceSessionConfig, null)) continue
                voiceCtrl = clazz
                voiceF = clazz.declaredMethods.firstOrNull { isVoiceTriggerF(it) }
                voiceG = clazz.declaredMethods.firstOrNull { isVoiceTriggerG(it) }
                voiceSession = clazz.declaredMethods.firstOrNull {
                    isVoiceSessionStart(it, voiceSessionConfig) && it.name == "ab"
                } ?: clazz.declaredMethods.firstOrNull { isVoiceSessionStart(it, voiceSessionConfig) }
            }
        }

        // Demand: qfy implements kvp; also accept key.i() return type
        var demandK: Method? = null
        var demandL: Method? = null
        val qfy = loadObfuscatedClass(classLoader, "qfy")
        if (qfy != null) {
            demandK = qfy.declaredMethods.firstOrNull { isDemandOpenK(it) }
            demandL = qfy.declaredMethods.firstOrNull { isDemandTranscriptionL(it) }
        }
        if (demandK == null) {
            runCatching {
                val key = loadObfuscatedClass(classLoader, "key") ?: return@runCatching
                val iMethod = key.declaredMethods.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.name == "i" && it.parameterCount == 0
                } ?: return@runCatching
                val demandClass = iMethod.returnType
                demandK = demandClass.declaredMethods.firstOrNull { isDemandOpenK(it) }
                demandL = demandClass.declaredMethods.firstOrNull { isDemandTranscriptionL(it) }
            }
        }

        val gbo = loadObfuscatedClass(classLoader, "gbo")
        val textFieldCtor = gbo?.declaredConstructors?.firstOrNull { isTextFieldCtor(it) }
        val gyr = loadObfuscatedClass(classLoader, "gyr")
        val kbdRestrictCtor = gyr?.declaredConstructors?.firstOrNull {
            isKeyboardRestrictionCtor(it, gyr)
        }
        val gyq = loadObfuscatedClass(classLoader, "gyq")
        val searchHint = gyq?.declaredMethods?.firstOrNull {
            isSearchHintBuilder(it, gyq)
        }
        val kxo = loadObfuscatedClass(classLoader, "kxo")
        val assistKbd = kxo?.declaredMethods?.firstOrNull {
            isAssistantKeyboardEnabled(it, kxo)
        }

        ModuleLog.gearhead(
            "GH-DRIVE-010",
            "anchors sensors=${sensors.size} ime=${imeService?.simpleName} " +
                "voice=${voiceCtrl?.simpleName} demand=${demandK?.declaringClass?.simpleName} " +
                "locQ=${locationQ != null} sensorIface=${sensorIface?.simpleName}",
            always = true,
        )

        return DiscoveredTargets(
            sensorCallbacks = sensors.toList(),
            locationKeyboardEnabled = locationQ,
            locationWheelSpeedNonZero = locationS,
            locationParkingState = locationC,
            locationSpeed = locationF,
            parkingEnumClass = parkingEnum,
            assistantKeyboardEnabled = assistKbd,
            carUiTouchscreen = carUiA,
            carUiTouchpad = carUiB,
            carAppKeyboardBlocked = carAppBlocked,
            carAppConstraintsClass = juv,
            imeServiceClass = imeService,
            imeCacheMethod = imeCache,
            imeStartExternal = imeStart,
            imeFragmentBase = imeFragBase,
            imeOnStart = imeOnStart,
            imeUnlockMethods = imeUnlock.toList(),
            imeRotaryLockout = imeRotary,
            imeFactory = imeFactory,
            voiceController = voiceCtrl,
            voiceTriggerF = voiceF,
            voiceTriggerG = voiceG,
            voiceSessionStart = voiceSession,
            demandOpen = demandK,
            demandTranscription = demandL,
            textFieldCtor = textFieldCtor,
            keyboardRestrictionCtor = kbdRestrictCtor,
            searchHintMethod = searchHint,
        )
    }

    private fun looksLikeDemandController(clazz: Class<*>, demandIface: Class<*>?): Boolean {
        if (demandIface != null && demandIface.isAssignableFrom(clazz)) return true
        // qfy-shaped: Context field + k/l/j/m methods
        val names = clazz.declaredMethods.map { it.name }.toSet()
        if (!("k" in names && "l" in names && "j" in names && "m" in names)) return false
        return clazz.declaredFields.any {
            Context::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers)
        }
    }

    private fun isVoiceControllerClass(
        clazz: Class<*>,
        voiceSessionConfig: Class<*>,
        voiceIface: Class<*>?,
    ): Boolean {
        if (voiceIface != null && voiceIface.isAssignableFrom(clazz)) return true
        val hasSession = clazz.declaredMethods.any { isVoiceSessionStart(it, voiceSessionConfig) }
        val hasF = clazz.declaredMethods.any { isVoiceTriggerF(it) }
        return hasSession && hasF
    }

    private fun isSensorCallback(method: Method): Boolean {
        if (method.parameterCount != 4) return false
        val p = method.parameterTypes
        return p[0] == Int::class.javaPrimitiveType &&
            p[1] == Long::class.javaPrimitiveType &&
            p[2] == FloatArray::class.java &&
            p[3] == ByteArray::class.java &&
            method.returnType == Void.TYPE
    }

    private fun isParkingEnum(clazz: Class<*>): Boolean {
        if (!clazz.isEnum) return false
        return clazz.enumConstants?.any { (it as Enum<*>).name == "CAR_PARKED" } == true
    }

    private fun isKeyboardCallbackInterface(clazz: Class<*>): Boolean {
        if (!clazz.isInterface) return false
        val methods = clazz.declaredMethods.filter { !it.isDefault }
        return methods.size == 1 &&
            methods[0].parameterCount == 1 &&
            methods[0].parameterTypes[0] == Boolean::class.javaPrimitiveType &&
            methods[0].returnType == Void.TYPE
    }

    private fun isImeCacheMethod(method: Method, carRegionId: Class<*>): Boolean {
        if (method.parameterCount != 2) return false
        return method.parameterTypes[0] == EditorInfo::class.java &&
            method.parameterTypes[1] == carRegionId &&
            method.returnType == Void.TYPE &&
            !Modifier.isStatic(method.modifiers)
    }

    private fun isMaybeStartExternal(method: Method): Boolean {
        return method.parameterCount == 0 &&
            method.returnType == Void.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            method.name.length <= 2
    }

    private fun isVoiceTriggerF(method: Method): Boolean {
        return method.parameterCount == 1 &&
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
            method.returnType == Void.TYPE &&
            method.name == "F"
    }

    private fun isVoiceTriggerG(method: Method): Boolean {
        return method.parameterCount == 2 &&
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
            method.parameterTypes[1] == Bundle::class.java &&
            method.returnType == Void.TYPE &&
            method.name == "G"
    }

    private fun isVoiceSessionStart(method: Method, voiceSessionConfig: Class<*>): Boolean {
        return method.parameterCount == 1 &&
            method.parameterTypes[0] == voiceSessionConfig &&
            method.returnType == Void.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            method.name.length in 1..3
    }

    private fun isDemandOpenK(method: Method): Boolean {
        return method.parameterCount == 1 &&
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
            method.returnType == Void.TYPE &&
            method.name == "k" &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun isDemandTranscriptionL(method: Method): Boolean {
        return method.parameterCount == 1 &&
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
            method.returnType == Void.TYPE &&
            method.name == "l" &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun isSearchHintBuilder(method: Method, clazz: Class<*>): Boolean {
        if (method.parameterCount != 3) return false
        if (method.parameterTypes[1] != Boolean::class.javaPrimitiveType) return false
        if (Modifier.isStatic(method.modifiers)) return false
        if (method.name != "d") return false
        // Prefer classes that hold a boolean "keyboard allowed" style field.
        return clazz.declaredFields.any {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }
    }

    private fun isAssistantKeyboardEnabled(method: Method, clazz: Class<*>): Boolean {
        if (method.parameterCount != 0) return false
        if (method.returnType != Boolean::class.javaPrimitiveType) return false
        if (method.name != "a" || Modifier.isStatic(method.modifiers)) return false
        // Assistant settings managers typically expose boolean fields b/c.
        val boolFields = clazz.declaredFields.count {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }
        return boolFields >= 2
    }

    private fun isCarUiFlagMethod(method: Method): Boolean {
        return method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            !Modifier.isStatic(method.modifiers) &&
            method.name.length == 1
    }

    private fun isCarUiFlagPair(clazz: Class<*>): Boolean {
        val boolNoArg = clazz.declaredMethods.filter {
            it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                !Modifier.isStatic(it.modifiers)
        }
        return boolNoArg.any { it.name == "a" } && boolNoArg.any { it.name == "b" } &&
            clazz.declaredFields.count {
                it.type == java.lang.Boolean::class.java && !Modifier.isStatic(it.modifiers)
            } >= 1
    }

    private fun isCarAppKeyboardBlocked(method: Method): Boolean {
        if (!Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 0) return false
        if (method.returnType != Boolean::class.javaPrimitiveType) return false
        if (method.name != "b") return false
        // juv-style constraints host: instance fields include ComponentName + boolean keyboard flag.
        val clazz = method.declaringClass
        val hasComponent = clazz.declaredFields.any {
            it.type.name == "android.content.ComponentName" && !Modifier.isStatic(it.modifiers)
        }
        val hasBool = clazz.declaredFields.any {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }
        return hasComponent && hasBool
    }

    private fun isTextFieldCtor(ctor: Constructor<*>): Boolean {
        if (ctor.parameterCount < 9) return false
        val p = ctor.parameterTypes
        return p[0] == String::class.java &&
            p[2] == Boolean::class.javaPrimitiveType &&
            p[7] == Boolean::class.javaPrimitiveType &&
            p[8] == Boolean::class.javaPrimitiveType
    }

    private fun isKeyboardRestrictionCtor(ctor: Constructor<*>, clazz: Class<*>): Boolean {
        if (ctor.parameterCount != 2) return false
        if (ctor.parameterTypes[1] != Boolean::class.javaPrimitiveType) return false
        // Class should expose boolean field for isKeyboardAllowed.
        return clazz.declaredFields.any {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }
    }

    private fun isImeFragmentBase(clazz: Class<*>): Boolean {
        if (Modifier.isFinal(clazz.modifiers)) return false
        val lockField = clazz.declaredFields.firstOrNull {
            it.name == "c" && it.type == Boolean::class.javaPrimitiveType
        } ?: return false
        if (Modifier.isStatic(lockField.modifiers)) return false
        return clazz.declaredMethods.any {
            it.name == "d" && it.parameterCount == 0 && Modifier.isAbstract(it.modifiers)
        }
    }

    private fun isCoroutineLike(clazz: Class<*>): Boolean {
        val name = clazz.name
        return name.contains("Continuation") ||
            name.endsWith("Kt") ||
            clazz.interfaces.any { it.name.contains("Continuation") }
    }

    private fun normalizeDexClassName(raw: String): String {
        var name = raw.trim()
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length - 1)
        }
        return name.replace('/', '.')
    }

    private fun isObfuscatedGearheadClass(binaryName: String): Boolean {
        val simple = binaryName.substringAfterLast('.').substringBefore('$')
        if (simple.length !in 2..5) return false
        if (!simple.all { it in 'a'..'z' }) return false
        if (binaryName.startsWith("defpackage.")) return true
        if (!binaryName.contains('.')) return true
        return false
    }

    private fun loadObfuscatedClass(classLoader: ClassLoader, binaryName: String): Class<*>? {
        val normalized = normalizeDexClassName(binaryName)
        val candidates = linkedSetOf(normalized)
        if (!normalized.contains('.')) candidates += "defpackage.$normalized"
        if (normalized.startsWith("defpackage.")) {
            candidates += normalized.removePrefix("defpackage.")
        }
        for (candidate in candidates) {
            runCatching { return classLoader.loadClass(candidate) }
        }
        return null
    }

    private fun apkContainsDex(path: String): Boolean {
        if (!path.endsWith(".apk")) return true
        return runCatching {
            java.util.zip.ZipFile(path).use { zip -> zip.getEntry("classes.dex") != null }
        }.getOrDefault(true)
    }

    fun resolveHostContext(): Context? {
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            Reflect.callStaticMethod(at, "currentApplication") as? Context
        }.getOrNull()
    }
}
