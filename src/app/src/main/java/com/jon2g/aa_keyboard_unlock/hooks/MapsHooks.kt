package com.jon2g.aa_keyboard_unlock.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.app.Application
import com.jon2g.aa_keyboard_unlock.BuildConfig
import com.jon2g.aa_keyboard_unlock.MapsNativeIme
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import com.jon2g.aa_keyboard_unlock.xposed.DexHooks
import com.jon2g.aa_keyboard_unlock.xposed.HookChains
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.HookParam
import com.jon2g.aa_keyboard_unlock.xposed.MethodHook
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Maps-process hooks: spoof in-process driving/distraction detection so the stock
 * projected keyboard (rek.d / reh.b) opens instead of voice search.
 */
object MapsHooks {
    @Volatile
    private lateinit var xposed: XposedInterface

    @Volatile
    private var installedForProcess = false

    @Volatile
    private var kurHintHookLogged = false

    @Volatile
    private var imeReceiverRegistered = false

    @Volatile
    private var blhxSingleton: Any? = null

    /** Latest rek overlay (rel) for native keyboard bind/show. */
    @Volatile
    private var lastRek: WeakReference<Any>? = null

    @Volatile
    private var lastSnp: WeakReference<Any>? = null

    @Volatile
    private var mapsClassLoader: ClassLoader? = null

    private val installAudit = MapsInstallProbe.InstallAudit()

    private lateinit var targets: MapsSignatureDiscovery.DiscoveredTargets

    @Volatile
    private var storedCtx: HookContext? = null

    @Volatile
    private var discoveryRetried = false

    private val mapsMicVoiceActive = ThreadLocal<Boolean>()

    fun install(ctx: HookContext) {
        if (installedForProcess) return
        installedForProcess = true
        xposed = ctx.xposed
        installHooks(ctx)
    }

    private fun installHooks(ctx: HookContext) {
        storedCtx = ctx
        mapsClassLoader = ctx.classLoader
        ModuleLog.install(
            ModuleLog.Process.MAPS,
            "enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()} " +
                "pref=${ModulePrefs.lastPrefSource} build=${BuildConfig.BUILD_TYPE} " +
                "pkg=${ctx.packageName} process=${ctx.processName}"
        )
        MapsInstallProbe.resolveAtInstall(ctx)
        targets = MapsSignatureDiscovery.discover(ctx)
        hookDrivingHintResolver(ctx)
        hookHeaderUiState(ctx)
        hookDistractionState(ctx)
        hookCarParametersKeyboardFlag(ctx)
        hookRekCache(ctx)
        hookSearchHeaderRekCache(ctx)
        hookSnpNativePath(ctx)
        hookSearchBarTap(ctx)
        hookTrtDrivingFlags(ctx)
        hookMapsVoiceBypass(ctx)
        hookTurNavSearch(ctx)
        hookDrivingResourceTrace(ctx)
        hookVoiceOnlyPath(ctx)
        hookLazyImeReceiverRegistration(ctx)
        MapsInstallProbe.run(ctx, installAudit, targets)
        ModuleLog.maps("MAPS-INSTALL", "hooks installed for ${ctx.processName}", always = true)
    }

    private fun retryDiscoveryIfEmpty(app: Application) {
        if (discoveryRetried) return
        val ctx = storedCtx ?: return
        if (!targets.isEffectivelyEmpty()) return
        discoveryRetried = true
        ModuleLog.maps(
            "MAPS-DRIVE-010",
            "retrying signature scan after Application.onCreate classLoader=${app.classLoader.javaClass.simpleName}",
            always = true
        )
        MapsSignatureDiscovery.invalidate()
        targets = MapsSignatureDiscovery.discover(ctx, force = true)
        if (targets.isEffectivelyEmpty()) {
            ModuleLog.maps("MAPS-DRIVE-010", "retry scan still found no primary targets", always = true)
            return
        }
        installDynamicHooks(ctx)
        MapsInstallProbe.run(ctx, installAudit, targets)
    }

    private fun installDynamicHooks(ctx: HookContext) {
        if (installAudit.kurHintHooks == 0 && targets.hintMethods.isNotEmpty()) hookDrivingHintResolver(ctx)
        if (installAudit.qhfTapHooks == 0 && targets.searchHeaderTaps.isNotEmpty()) {
            hookSearchHeaderRekCache(ctx)
            hookSearchBarTap(ctx)
        }
        if (targets.rekOverlayTypes.isNotEmpty()) hookRekCache(ctx)
        if (installAudit.snpHooks == 0 && targets.carImeTypes.isNotEmpty()) hookSnpNativePath(ctx)
        if (installAudit.trtHooks == 0 && targets.keyboardRestrictedMethods.isNotEmpty()) hookTrtDrivingFlags(ctx)
        if (installAudit.carParamsHooks == 0 && targets.carParameterMethods.isNotEmpty()) {
            hookCarParametersKeyboardFlag(ctx)
        }
        if (installAudit.voiceBypassHooks == 0 && targets.voiceBypassMethods.isNotEmpty()) {
            hookMapsVoiceBypass(ctx)
        }
        if (targets.headerRestrictionConstructors.isNotEmpty()) {
            hookHeaderUiState(ctx)
            hookDistractionState(ctx)
        }
        if (installAudit.voiceOnlyPathHooks == 0) hookVoiceOnlyPath(ctx)
    }

    private fun hookVoiceOnlyPath(ctx: HookContext) {
        val hooked = MapsVoiceOnlyPathHooks.install(xposed, ctx.classLoader)
        installAudit.voiceOnlyPathHooks = hooked
    }

    /** Static String(Context, driving, …) hint resolvers — discovered by signature, not class name. */
    private fun hookDrivingHintResolver(ctx: HookContext) {
        var hooked = 0
        for (method in targets.hintMethods) {
            val sig = method.parameterTypes.joinToString(",") { it.simpleName }
            val installed = runCatching {
                HookChains.hookMethod(xposed, method, kurHintHook(method.name, method.parameterTypes))
                if (!kurHintHookLogged) {
                    kurHintHookLogged = true
                    ModuleLog.maps(
                        "MAPS-000",
                        "hook hint sig ${method.declaringClass.simpleName}.${method.name}($sig)",
                        always = true
                    )
                }
                log("Hooked hint ${method.declaringClass.simpleName}.${method.name}($sig)")
                true
            }.getOrElse { error ->
                log("hint ${method.declaringClass.simpleName}.${method.name} failed: ${error.message}")
                false
            }
            if (installed) hooked++
        }
        installAudit.kurHintHooks = hooked
        installAudit.kurHasAj = targets.hintMethods.any { it.name == "aJ" }
        if (hooked == 0) {
            log("WARN no hint resolver hooked — signature scan found ${targets.hintMethods.size} candidates")
            ModuleLog.maps(
                "MAPS-DRIVE-003",
                "WARN kur hint resolver not hooked — voice label may leak",
                always = true
            )
        } else {
            log("Hooked hint resolver x$hooked (signature)")
        }
    }

    private fun kurHintHook(methodName: String, params: Array<Class<*>>) = object : MethodHook() {
        override fun beforeHookedMethod(param: HookParam) {
            if (!ModulePrefs.isEnabled()) return
            if (param.args.size < 5) return
            ModuleLog.maps(
                "MAPS-DRIVE-006",
                "kur.$methodName() driving=${param.args[1]} routeSearch=${param.args.getOrNull(2)} " +
                    "micRestricted=${param.args.getOrNull(4)}",
                always = ModulePrefs.isDebug()
            )
            debugEntry(
                "kur.$methodName() driving=${param.args[1]} micRestricted=${param.args.getOrNull(4)}"
            )
            if (param.args[1] == true) {
                debug("kur.$methodName() forced driving=false")
                param.args[1] = false
            }
            if (param.args.getOrNull(2) == true) {
                debug("kur.$methodName() forced routeSearch=false")
                param.args[2] = false
            }
            if (param.args.getOrNull(4) == true) {
                debug("kur.$methodName() forced micRestricted=false")
                param.args[4] = false
            }
        }

        override fun afterHookedMethod(param: HookParam) {
            if (!ModulePrefs.isEnabled()) return
            val hint = param.result as? String ?: return
            if (hint.contains("voice only", ignoreCase = true)) {
                ModuleLog.maps(
                    "MAPS-DRIVE-001",
                    "kur.$methodName() returned voice-only hint \"${hint.take(60)}\" — args may still be driving",
                    always = true
                )
            } else if (ModulePrefs.isDebug()) {
                ModuleLog.maps("MAPS-DRIVE-009", "kur.$methodName() hint \"${hint.take(60)}\"")
            }
        }
    }

    /** Header UiState ctors with isMicRestricted / isKeyboardRestricted bools (signature). */
    private fun hookHeaderUiState(ctx: HookContext) {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (ctor in targets.headerRestrictionConstructors) {
            val params = ctor.parameterTypes
            val isQhaLike = params.size >= 5 &&
                params[3] == booleanPrimitive &&
                params[4] == booleanPrimitive
            if (!isQhaLike) continue
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args.size >= 5) {
                            if (param.args[3] == true) {
                                debug("header ctor forced isMicRestricted=false")
                                param.args[3] = false
                            }
                            if (param.args[4] == true) {
                                debug("header ctor forced isKeyboardRestricted=false")
                                param.args[4] = false
                            }
                        }
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val summary = param.args.mapIndexed { index, value ->
                            "$index:${value?.javaClass?.simpleName}=$value"
                        }.joinToString(" ")
                        ModuleLog.maps(
                            "MAPS-DRIVE-005",
                            "${ctor.declaringClass.simpleName} constructed [$summary]",
                            always = true
                        )
                    }
                })
                hooked++
            }.onFailure { debug("header ctor hook failed: ${it.message}") }
        }
        if (hooked > 0) {
            log("Hooked header restriction ctors x$hooked (signature)")
        } else {
            log("WARN no header restriction ctors hooked")
        }
    }

    private fun hookDistractionState(ctx: HookContext) {
        var hooked = 0
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        for (ctor in targets.headerRestrictionConstructors) {
            val params = ctor.parameterTypes
            val isQwtLike = params.size >= 2 &&
                params[0] == booleanPrimitive &&
                params[1] == booleanPrimitive &&
                params.size < 5
            if (!isQwtLike) continue
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args[0] == true) {
                            debug("distraction ctor forced isKeyboardRestricted=false")
                            param.args[0] = false
                        }
                        if (param.args[1] == true) {
                            debug("distraction ctor forced isConfigRestricted=false")
                            param.args[1] = false
                        }
                    }
                })
                hooked++
            }
        }
        if (hooked > 0) {
            log("Hooked distraction ctors x$hooked (signature)")
        }
    }

    private fun hookRekCache(ctx: HookContext) {
        var hooked = 0
        for (rekType in targets.rekOverlayTypes) {
            runCatching {
                HookChains.hookAllConstructors(xposed, rekType, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        lastRek = WeakReference(param.thisObject)
                        debugEntry("${rekType.simpleName} constructed — cached rek")
                    }
                })
                val bindHandles = HookChains.hookAllMethods(xposed, rekType, "d", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        lastRek = WeakReference(param.thisObject)
                        debugEntry("${rekType.simpleName}.d() cached rek overlay")
                    }
                })
                if (bindHandles.isNotEmpty()) hooked++
            }.onFailure { debug("rek cache ${rekType.name} failed: ${it.message}") }
        }
        if (hooked > 0) {
            log("Hooked rek overlay types x$hooked (signature)")
        } else {
            log("WARN no rek overlay types hooked")
        }
    }

    private fun hookSearchHeaderRekCache(ctx: HookContext) {
        var hooked = 0
        val headerClasses = targets.searchHeaderTaps.map { it.headerClass }.distinct()
        for (headerClass in headerClasses) {
            runCatching {
                HookChains.hookAllConstructors(xposed, headerClass, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val header = param.thisObject ?: return
                        cacheRekFromHeader(header)?.let {
                            ModuleLog.maps(
                                "MAPS-000",
                                "cached rek from ${headerClass.simpleName} constructor",
                                always = true
                            )
                        }
                    }
                })
                hooked++
            }
        }
        if (hooked > 0) {
            log("Hooked search header ctors x$hooked (signature)")
        }
    }

    private fun hookSnpNativePath(ctx: HookContext) {
        var hooked = 0
        for (snpType in targets.carImeTypes) {
            for (methodName in listOf("j", "k")) {
                val handles = HookChains.hookAllMethods(xposed, snpType, methodName, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        lastSnp = WeakReference(param.thisObject)
                        ModuleLog.maps(
                            "MAPS-003",
                            "${snpType.simpleName}.$methodName() native car IME path",
                            always = true
                        )
                    }
                })
                hooked += handles.size
            }
        }
        installAudit.snpHooks = hooked
        if (hooked > 0) {
            log("Hooked car IME j/k x$hooked (signature)")
        } else {
            log("WARN no car IME j/k hooked")
        }
    }

    /** Search bar tap: discovered header.l() → rek.d(). */
    private fun hookSearchBarTap(ctx: HookContext) {
        val classLoader = ctx.classLoader
        val micPassthrough = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                mapsMicVoiceActive.set(true)
                ModuleLog.maps("MAPS-MIC-001", "${param.method.declaringClass.simpleName}.${param.method.name}() mic", always = true)
            }

            override fun afterHookedMethod(param: HookParam) {
                mapsMicVoiceActive.remove()
            }
        }
        var hookedL = 0
        for (tap in targets.searchHeaderTaps) {
            val keyboardTap = object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val headerClass = param.thisObject?.javaClass?.name ?: "?"
                    ModuleLog.maps("MAPS-DRIVE-006", "$headerClass.${tap.tapMethod.name}() search tap", always = true)
                    val rek = runCatching {
                        Reflect.getObjectField(param.thisObject, tap.rekFieldName)
                    }.getOrNull() ?: MapsSignatureDiscovery.findRekFieldOnHeader(param.thisObject!!)?.second
                    if (rek == null) {
                        ModuleLog.maps("MAPS-004", "search tap rek field null on ${tap.headerClass.simpleName}", always = true)
                        return
                    }
                    ModuleLog.maps(
                        "MAPS-001",
                        "${tap.headerClass.simpleName}.${tap.tapMethod.name}() — native rek.d keyboard",
                        always = true
                    )
                    openNativeKeyboard(rek)
                    param.result = runCatching { blhxDone(classLoader, tap.tapMethod.returnType) }.getOrNull()
                }
            }
            runCatching {
                HookChains.hookMethod(xposed, tap.tapMethod, keyboardTap)
                log("Hooked ${tap.headerClass.simpleName}.${tap.tapMethod.name}() rekField=${tap.rekFieldName}")
                hookedL++
            }.onFailure { log("tap hook ${tap.headerClass.simpleName}.l failed: ${it.message}") }
            for (methodName in listOf("k", "i")) {
                runCatching {
                    HookChains.hookAllMethods(xposed, tap.headerClass, methodName, micPassthrough)
                }
            }
        }
        installAudit.qhfTapHooks = hookedL
        installAudit.qhfHasL = hookedL > 0
        installAudit.qhfIsInterface = false
        if (hookedL == 0) {
            log("Failed to hook search header tap — ${targets.searchHeaderTaps.size} signature candidates")
        } else {
            log("Hooked search header tap x$hookedL (signature)")
        }
    }

    private fun hookTrtDrivingFlags(ctx: HookContext) {
        val hook = object : MethodHook() {
            override fun afterHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (param.result == true) {
                    ModuleLog.maps(
                        "MAPS-DRIVE-004",
                        "${param.thisObject?.javaClass?.simpleName}.${param.method.name}() forced false",
                        always = true
                    )
                    param.result = false
                }
            }
        }
        var totalHooked = 0
        for (method in targets.keyboardRestrictedMethods) {
            runCatching {
                HookChains.hookMethod(xposed, method, hook)
                totalHooked++
            }
        }
        installAudit.trtHooks = totalHooked
        if (totalHooked > 0) {
            log("Hooked keyboard-restricted methods x$totalHooked (signature)")
        } else {
            log("WARN no keyboard-restricted methods hooked")
        }
    }

    private fun hookCarParametersKeyboardFlag(ctx: HookContext) {
        val hook = object : MethodHook() {
            override fun afterHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                val carParams = param.result ?: return
                if (!MapsSignatureDiscovery.isCarParamsType(carParams.javaClass)) return
                patchCarParamsFlags(carParams)
            }
        }
        var hooked = 0
        for (method in targets.carParameterMethods) {
            runCatching {
                HookChains.hookMethod(xposed, method, hook)
                log("Hooked car params ${method.declaringClass.simpleName}.${method.name}()")
                hooked++
            }
        }
        installAudit.carParamsHooks = hooked
        if (hooked > 0) {
            log("Hooked getCarParameters-like x$hooked (signature)")
        } else {
            log("WARN no car parameter getters hooked")
        }
    }

    private fun patchCarParamsFlags(carParams: Any) {
        runCatching {
            for (field in carParams.javaClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.type != Boolean::class.javaPrimitiveType && field.type != Boolean::class.java) continue
                field.isAccessible = true
                when (field.name) {
                    "A" -> if (!field.getBoolean(carParams)) {
                        field.setBoolean(carParams, true)
                        debug("carParams.A forced true")
                    }
                    "c" -> if (field.getBoolean(carParams)) {
                        field.setBoolean(carParams, false)
                        debug("carParams.c forced false")
                    }
                }
            }
        }.onFailure { debug("carParams flag patch failed: ${it.message}") }
    }

    /**
     * AA search can bypass qhf via NavAssistantCallbacks gRPC (aoeb.l).
     * Redirect to native keyboard instead of voice dictation.
     */
    private fun hookMapsVoiceBypass(ctx: HookContext) {
        val redirect = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (mapsMicVoiceActive.get() == true) return
                val trigger = param.args.getOrNull(0) as? Int
                ModuleLog.maps(
                    "MAPS-001",
                    "${param.method.declaringClass.simpleName}.${param.method.name}($trigger) voice bypass blocked",
                    always = true
                )
                openNativeKeyboardIfReady()
                param.result = null
            }
        }
        var voiceHooked = 0
        for (method in targets.voiceBypassMethods) {
            runCatching {
                HookChains.hookMethod(xposed, method, redirect)
                log("Hooked voice bypass ${method.declaringClass.simpleName}.${method.name}(int)")
                voiceHooked++
            }
        }
        installAudit.voiceBypassHooks = voiceHooked
        if (voiceHooked == 0) {
            log("WARN no voice bypass methods hooked")
        }
    }

    /**
     * Head-unit search may call tur.s() → gmm_mic IPC. Trace only — mic uses the same entry.
     */
    private fun hookTurNavSearch(ctx: HookContext) {
        val trace = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                ModuleLog.maps(
                    "MAPS-DRIVE-006",
                    "${param.thisObject?.javaClass?.simpleName}.s() micActive=${mapsMicVoiceActive.get() == true}",
                    always = ModulePrefs.isDebug()
                )
            }
        }
        var hooked = 0
        runCatching {
            val pub = findMapsClass(ctx.classLoader, "pub")
            for (shortName in listOf("tur", "tvj", "rzb", "rzc")) {
                runCatching {
                    val clazz = findMapsClass(ctx.classLoader, shortName)
                    if (!pub.isAssignableFrom(clazz)) return@runCatching
                    hooked += HookChains.hookAllMethods(xposed, clazz, "s", trace).size
                }
            }
        }.onFailure { debug("Skip pub.s trace: ${it.message}") }
        if (hooked == 0) {
            runCatching {
                val tur = findMapsClass(ctx.classLoader, "tur")
                hooked = HookChains.hookAllMethods(xposed, tur, "s", trace).size
            }.onFailure { debug("Skip tur.s trace: ${it.message}") }
        }
        if (hooked > 0) {
            log("Hooked tur.s trace x$hooked")
        }
    }

    /** Trace driving-related resource loads — no string rewrite. */
    private fun hookDrivingResourceTrace(ctx: HookContext) {
        val traceHook = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                val resId = param.args[0] as? Int ?: return
                val kind = tracedDrivingResKind(resId) ?: return
                ModuleLog.maps(
                    "MAPS-DRIVE-008",
                    "$kind res load caller=${MapsDrivingTrace.formatCallerStack()}",
                    always = true
                )
            }

            override fun afterHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                val resId = param.args[0] as? Int ?: return
                val kind = tracedDrivingResKind(resId) ?: return
                val text = when (val result = param.result) {
                    is String -> result
                    is CharSequence -> result.toString()
                    else -> return
                }
                val stack = MapsDrivingTrace.formatCallerStack()
                ModuleLog.maps(
                    "MAPS-DRIVE-008",
                    "$kind resId=$resId text=\"${text.take(50)}\" stack=$stack",
                    always = true
                )
            }
        }
        runCatching {
            HookChains.findAndHookMethod(
                xposed,
                android.content.res.Resources::class.java,
                "getString",
                traceHook,
                Int::class.javaPrimitiveType!!,
            )
            HookChains.findAndHookMethod(
                xposed,
                android.content.res.Resources::class.java,
                "getText",
                traceHook,
                Int::class.javaPrimitiveType!!,
            )
            log("Hooked Resources.getString/getText driving trace (no rewrite)")
        }.onFailure { log("Failed to hook Resources driving trace: ${it.message}") }
    }

    private fun tracedDrivingResKind(resId: Int): String? {
        if (resId == 0) return null
        return when (resId) {
            MapsInstallProbe.voiceOnlyResId -> "voiceOnly".takeIf { MapsInstallProbe.voiceOnlyResId != 0 }
            MapsInstallProbe.searchHintResId -> "searchHint".takeIf { MapsInstallProbe.searchHintResId != 0 }
            MapsInstallProbe.keyboardDeniedResId -> "keyboardDenied".takeIf {
                MapsInstallProbe.keyboardDeniedResId != 0
            }
            else -> null
        }
    }

    private fun hookLazyImeReceiverRegistration(ctx: HookContext) {
        if (ctx.processName != "com.google.android.apps.maps") return
        runCatching {
            HookChains.findAndHookMethod(xposed, Application::class.java, "onCreate", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    val app = param.thisObject as Application
                    MapsInstallProbe.resolveFromApplication(app, app.packageName)
                    retryDiscoveryIfEmpty(app)
                    ensureNativeImeReceiver(app)
                }
            })
            log("Hooked Application.onCreate for IME receiver")
        }.onFailure { log("Failed to hook Application.onCreate: ${it.message}") }

        runCatching {
            HookChains.findAndHookMethod(xposed, Activity::class.java, "onResume", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("GhostActivity")) return
                    ensureNativeImeReceiver(activity.application)
                    resolveRek()
                }
            })
            log("Hooked Activity.onResume for GhostActivity IME receiver")
        }.onFailure { log("Failed to hook Activity.onResume: ${it.message}") }

        ensureNativeImeReceiver()
    }

    private fun ensureNativeImeReceiver(app: Application? = null) {
        if (imeReceiverRegistered) return
        val application = app ?: runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            Reflect.callStaticMethod(atClass, "currentApplication") as? Application
        }.getOrNull() ?: return

        runCatching {
            val filter = IntentFilter().apply {
                addAction(MapsNativeIme.ACTION_PREPARE)
                addAction(MapsNativeIme.ACTION_OPEN)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!ModulePrefs.isEnabled()) return
                    when (intent.action) {
                        MapsNativeIme.ACTION_PREPARE -> {
                            ModuleLog.maps("MAPS-002", "broadcast PREPARE_MAPS_NATIVE_IME", always = true)
                            Handler(Looper.getMainLooper()).post {
                                prepareNativeKeyboardBind()
                            }
                        }
                        MapsNativeIme.ACTION_OPEN -> {
                            ModuleLog.maps("MAPS-001", "broadcast OPEN_MAPS_NATIVE_IME", always = true)
                            scheduleOpenNativeKeyboard()
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                application.registerReceiver(receiver, filter)
            }
            imeReceiverRegistered = true
            log("Registered native IME broadcast receiver")
        }.onFailure { log("Failed to register IME receiver: ${it.message}") }
    }

    private fun scheduleOpenNativeKeyboard() {
        val attempts = longArrayOf(0L, 150L, 400L, 800L, 1500L, 2500L, 4000L)
        val handler = Handler(Looper.getMainLooper())
        for (delayMs in attempts) {
            handler.postDelayed({
                if (openNativeKeyboardIfReady()) return@postDelayed
                if (delayMs == attempts.last()) {
                    val editText = findGhostSearchEditText()
                    val ghostActivities = countGhostActivities()
                    val msg = buildString {
                        if (editText != null) {
                            append("search EditText visible but no rek — bind failed")
                        } else {
                            append("no cached rek for native keyboard")
                        }
                        append("; ghostActivities=$ghostActivities qhfTapHooks=${installAudit.qhfTapHooks}")
                        append(" kurHooks=${installAudit.kurHintHooks}")
                    }
                    ModuleLog.maps("MAPS-004", msg, always = true)
                }
            }, delayMs)
        }
    }

    private fun countGhostActivities(): Int {
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(thread, "mActivities") as? Map<*, *> ?: return 0
            activities.values.count { record ->
                val activity = Reflect.getObjectField(record, "activity") as? Activity
                activity?.javaClass?.name?.contains("GhostActivity") == true
            }
        }.getOrDefault(0)
    }

    private fun prepareNativeKeyboardBind() {
        resolveRek()?.let { rek ->
            runCatching {
                Reflect.callMethod(rek, "d")
                ModuleLog.maps("MAPS-002", "PREPARE rek.d() native keyboard bind", always = true)
            }.onFailure {
                ModuleLog.maps("MAPS-004", "PREPARE rek.d() failed: ${it.message}", always = true)
            }
            invokeSnpBindIfPresent()
            return
        }
        ModuleLog.maps("MAPS-004", "PREPARE no rek — trying stock tap path", always = true)
        tryStockSearchTapPath(bindOnly = true)
    }

    private fun invokeSnpBindIfPresent() {
        lastSnp?.get()?.let { snp ->
            runCatching {
                Reflect.callMethod(snp, "j")
                ModuleLog.maps("MAPS-003", "snp.j() bind car input", always = true)
            }.onFailure {
                ModuleLog.maps("MAPS-004", "snp.j() failed: ${it.message}", always = true)
            }
        }
    }

    private fun invokeSnpShowIfPresent(): Boolean {
        lastSnp?.get()?.let { snp ->
            return runCatching {
                Reflect.callMethod(snp, "k")
                ModuleLog.maps("MAPS-003", "snp.k() show car IME", always = true)
                true
            }.getOrElse {
                ModuleLog.maps("MAPS-004", "snp.k() failed: ${it.message}", always = true)
                false
            }
        }
        return false
    }

    private fun openNativeKeyboardIfReady(): Boolean {
        resolveRek()?.let { rek ->
            openNativeKeyboard(rek)
            return true
        }
        return tryStockSearchTapPath(bindOnly = false)
    }

    private fun resolveRek(): Any? {
        lastRek?.get()?.let { return it }
        return discoverRekInProcess()?.also { lastRek = WeakReference(it) }
    }

    private fun discoverRekInProcess(): Any? {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(thread, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") as? Activity ?: continue
                if (!activity.javaClass.name.contains("GhostActivity")) continue

                findSearchEditText(activity)?.let { editText ->
                    scanForRekMatchingEditText(activity, editText, depth = 0, maxDepth = 7)?.let { rek ->
                        ModuleLog.maps(
                            "MAPS-000",
                            "discovered rek via search EditText (${rek.javaClass.simpleName})",
                            always = true
                        )
                        return rek
                    }
                    ModuleLog.maps("MAPS-000", "search EditText visible — scanning for rek", always = true)
                }

                discoverQhfHeader(activity)?.let { qhf ->
                    cacheRekFromQhf(qhf)?.let { rek ->
                        ModuleLog.maps("MAPS-000", "discovered rek via qhf.b", always = true)
                        return rek
                    }
                }

                scanForRek(activity, depth = 0, maxDepth = 7)?.let { rek ->
                    ModuleLog.maps(
                        "MAPS-000",
                        "discovered rek via activity scan (${rek.javaClass.simpleName})",
                        always = true
                    )
                    return rek
                }
            }
        }.onFailure {
            debug("discoverRek failed: ${it.message}")
        }
        return null
    }

    private fun findGhostSearchEditText(): View? {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(thread, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") as? Activity ?: continue
                if (!activity.javaClass.name.contains("GhostActivity")) continue
                findSearchEditText(activity)?.let { return it }
            }
        }
        return null
    }

    private fun findSearchEditText(activity: Activity): View? {
        val resId = runCatching {
            activity.resources.getIdentifier(
                "destination_input_keyboard_search_edit_text",
                "id",
                activity.packageName
            )
        }.getOrNull()?.takeIf { it != 0 } ?: 0x7f0b02d0
        val root = activity.window?.decorView ?: return null
        return findViewByIdRecursive(root, resId)
    }

    private fun findViewByIdRecursive(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByIdRecursive(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    private fun scanForRekMatchingEditText(obj: Any, editText: View, depth: Int, maxDepth: Int): Any? {
        if (depth > maxDepth) return null
        if (isRekLike(obj) && rekOwnsEditText(obj, editText)) return obj
        cacheRekFromQhf(obj)?.let { rek ->
            if (rekOwnsEditText(rek, editText)) return rek
        }
        runCatching {
            val fieldB = Reflect.getObjectField(obj, "b")
            if (fieldB != null && isRekLike(fieldB) && rekOwnsEditText(fieldB, editText)) return fieldB
        }
        if (obj is View) return null
        for (field in obj.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
            if (View::class.java.isAssignableFrom(field.type)) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) ?: return@runCatching
                scanForRekMatchingEditText(value, editText, depth + 1, maxDepth)?.let { return it }
            }
        }
        return null
    }

    private fun rekOwnsEditText(rek: Any, editText: View): Boolean {
        return runCatching { Reflect.callMethod(rek, "f") == editText }.getOrDefault(false)
    }

    private fun discoverQhfHeader(root: Any): Any? = scanForQhf(root, depth = 0, maxDepth = 7)

    private fun scanForQhf(obj: Any, depth: Int, maxDepth: Int): Any? {
        if (depth > maxDepth) return null
        if (isQhfHeader(obj)) return obj
        if (obj is View) return null
        for (field in obj.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
            if (View::class.java.isAssignableFrom(field.type)) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) ?: return@runCatching
                scanForQhf(value, depth + 1, maxDepth)?.let { return it }
            }
        }
        return null
    }

    private fun isQhfHeader(obj: Any): Boolean {
        if (MapsSignatureDiscovery.isDiscoveredHeaderClass(obj.javaClass, targets)) return true
        return MapsSignatureDiscovery.findRekFieldOnHeader(obj) != null
    }

    /**
     * When qhf.l hook fails to install, invoke stock search tap reflectively.
     * With driving flags spoofed, qhf.l() should call rek.d() internally.
     */
    private fun tryStockSearchTapPath(bindOnly: Boolean): Boolean {
        val header = discoverSearchHeaderFromActivities() ?: return false
        val tap = targets.searchHeaderTaps.firstOrNull { it.headerClass == header.javaClass }
            ?: targets.searchHeaderTaps.firstOrNull()
        val tapName = tap?.tapMethod?.name ?: "l"
        return runCatching {
            ModuleLog.maps("MAPS-001", "reflective ${header.javaClass.simpleName}.$tapName() search tap", always = true)
            Reflect.callMethod(header, tapName)
            val rek = cacheRekFromHeader(header) ?: resolveRek()
            if (rek == null) return false
            if (!bindOnly) {
                scheduleShowCarIme(rek)
            }
            true
        }.getOrElse {
            ModuleLog.maps("MAPS-004", "reflective search tap failed: ${it.message}", always = true)
            false
        }
    }

    private fun discoverSearchHeaderFromActivities(): Any? {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(thread, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") ?: continue
                discoverQhfHeader(activity)?.let { return it }
            }
        }
        return null
    }

    private fun scanForRek(obj: Any, depth: Int, maxDepth: Int): Any? {
        if (depth > maxDepth) return null
        if (isRekLike(obj)) return obj
        cacheRekFromHeader(obj)?.let { return it }
        if (obj is View) return null
        for (field in obj.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
            if (View::class.java.isAssignableFrom(field.type)) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) ?: return@runCatching
                if (isRekLike(value)) return value
                scanForRek(value, depth + 1, maxDepth)?.let { return it }
            }
        }
        return null
    }

    private fun cacheRekFromHeader(header: Any): Any? {
        val pair = MapsSignatureDiscovery.findRekFieldOnHeader(header) ?: return null
        val rek = pair.second
        if (!isRekLike(rek)) return null
        lastRek = WeakReference(rek)
        return rek
    }

    private fun cacheRekFromQhf(header: Any): Any? = cacheRekFromHeader(header)

    private fun isRekLikeType(type: Class<*>): Boolean =
        MapsSignatureDiscovery.isRekOverlayType(type) || MapsInstallProbe.isRekLikeType(type)

    private fun isRekLike(obj: Any): Boolean = isRekLikeType(obj.javaClass)

    /** Stock Maps car IME: rel.d() bind, then reh.b() show. */
    private fun openNativeKeyboard(rek: Any) {
        lastRek = WeakReference(rek)
        runCatching {
            Reflect.callMethod(rek, "d")
            ModuleLog.maps("MAPS-002", "rek.d() native keyboard bind", always = true)
        }.onFailure {
            ModuleLog.maps("MAPS-004", "rek.d() failed: ${it.message}", always = true)
            return
        }
        invokeSnpBindIfPresent()
        scheduleShowCarIme(rek)
    }

    private fun scheduleShowCarIme(rek: Any) {
        val showIme = Runnable {
            if (invokeSnpShowIfPresent()) return@Runnable
            runCatching {
                val reh = findRehController(rek)
                    ?: throw IllegalStateException("no reh on ${rek.javaClass.name}")
                Reflect.callMethod(reh, "b")
                ModuleLog.maps("MAPS-003", "reh.b() native car IME show", always = true)
            }.onFailure {
                ModuleLog.maps("MAPS-004", "reh.b() failed: ${it.message}", always = true)
            }
        }
        val anchor = runCatching { Reflect.callMethod(rek, "f") as? View }.getOrNull()
            ?: runCatching {
                val field = rek.javaClass.declaredFields.firstOrNull { it.type == View::class.java }
                field?.isAccessible = true
                field?.get(rek) as? View
            }.getOrNull()
        if (anchor != null) {
            anchor.post(showIme)
        } else {
            showIme.run()
        }
    }

    private fun findRehController(overlay: Any): Any? {
        runCatching {
            val reh = Reflect.getObjectField(overlay, "c")
            if (reh != null) return reh
        }
        for (field in overlay.javaClass.declaredFields) {
            if (field.type.isPrimitive) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(overlay) ?: return@runCatching
                if (value.javaClass.methods.any { it.name == "b" && it.parameterTypes.isEmpty() }) {
                    return value
                }
            }
        }
        return null
    }

    private fun blhxDone(classLoader: ClassLoader, returnType: Class<*>? = null): Any? {
        blhxSingleton?.let { return it }
        runCatching {
            val blhx = findMapsClass(classLoader, "blhx")
            val value = Reflect.getStaticObjectField(blhx, "a")
            if (value != null) {
                blhxSingleton = value
                return value
            }
        }
        if (returnType != null && returnType != Void.TYPE && returnType != Void::class.java) {
            return null
        }
        return null
    }

    private fun findMapsClass(classLoader: ClassLoader, shortName: String): Class<*> {
        for (name in listOf(shortName, "defpackage.$shortName")) {
            runCatching {
                return Reflect.findClass(name, classLoader)
            }
        }
        throw ClassNotFoundException(shortName)
    }

    private fun debugEntry(message: String) {
        if (ModulePrefs.isDebug()) {
            ModuleLog.maps("DEBUG", message)
        }
    }

    private fun debug(message: String) {
        if (ModulePrefs.isDebug()) {
            ModuleLog.maps("HOOK", message)
        }
    }

    private fun log(message: String) {
        ModuleLog.maps("HOOK", message, always = true)
    }
}
