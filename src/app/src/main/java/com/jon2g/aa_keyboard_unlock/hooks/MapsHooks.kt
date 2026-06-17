package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.xposed.DexHooks
import com.jon2g.aa_keyboard_unlock.xposed.HookChains
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.HookParam
import com.jon2g.aa_keyboard_unlock.xposed.MethodHook
import com.jon2g.aa_keyboard_unlock.xposed.MethodReplacement
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface


import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.ComponentCallbacks2
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Pair
import android.view.View
import com.jon2g.aa_keyboard_unlock.KeyboardBridge
import com.jon2g.aa_keyboard_unlock.MapsSearchSubmit
import com.jon2g.aa_keyboard_unlock.MicSignal
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.overlay.ProjectedKeyboardOverlay
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import java.lang.ref.WeakReference

object MapsHooks {
    @Volatile
    private lateinit var xposed: XposedInterface

    @Volatile
    private var installedForProcess = false

    @Volatile
    private var kurAjHookLogged = false

    /** Latest rek overlay (rel or dex-discovered implementor). */
    @Volatile
    private var lastRel: WeakReference<Any>? = null

    /** Maps car host (tur/tvj/llj) for dispatchKeyEvent keyboard fallback. */
    @Volatile
    private var lastMapsCarHost: WeakReference<Any>? = null

    @Volatile
    private var lastSnp: WeakReference<Any>? = null

    private val pendingGmmMic = ThreadLocal<Boolean>()

    /** Set while qhf.k()/i() mic shortcuts run — allow pub.s / lka voice IPC. */
    private val mapsMicVoiceActive = ThreadLocal<Boolean>()

    @Volatile
    private var mapsClassLoader: ClassLoader? = null

    fun install(ctx: HookContext) {
        if (installedForProcess) return
        installedForProcess = true
        xposed = ctx.xposed
        installHooks(ctx)
        runCatching {
            val app = Reflect.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Application
            if (app != null) {
                registerMapsKeyboardReceiver(app)
                registerOverlayDismissOnNavigation(app)
            }
        }
    }

    private fun installHooks(ctx: HookContext) {
        mapsClassLoader = ctx.classLoader
        ModuleLog.install(
            ModuleLog.Process.MAPS,
            "enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()} pkg=${ctx.packageName} process=${ctx.processName}"
        )
        hookSearchHintResolver(ctx)
        hookHeaderUiState(ctx)
        hookDistractionState(ctx)
        hookMapsCarHostCache(ctx)
        hookGmmMicBinder(ctx)
        hookPubVoiceSearch(ctx)
        hookSearchBarTap(ctx)
        hookProjectedKeyboard(ctx)
        hookMapsCarTouchIntercept(ctx)
        hookMapsVoiceSession(ctx)
        hookTrtDrivingFlags(ctx)
        ModuleLog.maps("MAPS-INSTALL", "hooks installed for ${ctx.processName}", always = true)
    }

    private fun registerMapsKeyboardReceiver(app: Application) {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!ModulePrefs.isEnabled()) return
                    when (intent.action) {
                        KeyboardBridge.ACTION_PREPARE_MAPS_IME -> {
                            ModuleLog.maps(
                                "MAPS-001",
                                "broadcast PREPARE_MAPS_IME — binding projected keyboard",
                                always = true
                            )
                            prepareMapsIme()
                        }
                        KeyboardBridge.ACTION_OPEN_MAPS_KEYBOARD -> {
                            ModuleLog.maps(
                                "MAPS-001",
                                "broadcast OPEN_MAPS_KEYBOARD — opening projected keyboard",
                                always = true
                            )
                            openMapsKeyboard(lastMapsCarHost?.get())
                        }
                        KeyboardBridge.ACTION_CLOSE_MAPS_KEYBOARD -> {
                            ModuleLog.maps(
                                "MAPS-KBD-002",
                                "broadcast CLOSE_MAPS_KEYBOARD — hiding overlay",
                                always = true
                            )
                            ProjectedKeyboardOverlay.hideForNavigation()
                        }
                        KeyboardBridge.ACTION_SUBMIT_MAPS_SEARCH -> {
                            val query = intent.getStringExtra(KeyboardBridge.EXTRA_QUERY)?.trim().orEmpty()
                            if (query.isEmpty()) {
                                ModuleLog.maps("MAPS-KBD-004", "submit ignored — empty query", always = true)
                                return
                            }
                            ModuleLog.maps(
                                "MAPS-001",
                                "broadcast SUBMIT_MAPS_SEARCH — \"$query\"",
                                always = true
                            )
                            submitSearchQuery(query)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(KeyboardBridge.ACTION_PREPARE_MAPS_IME)
                addAction(KeyboardBridge.ACTION_OPEN_MAPS_KEYBOARD)
                addAction(KeyboardBridge.ACTION_CLOSE_MAPS_KEYBOARD)
                addAction(KeyboardBridge.ACTION_SUBMIT_MAPS_SEARCH)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            log("Registered PREPARE + OPEN + CLOSE + SUBMIT Maps keyboard receiver")
        }.onFailure { log("Failed to register Maps keyboard receiver: ${it.message}") }
    }

    private fun registerOverlayDismissOnNavigation(app: Application) {
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    if (!ModulePrefs.isEnabled()) return
                    if (!isMapsCarUiHost(activity)) return
                    if (!ProjectedKeyboardOverlay.isVisible()) return
                    ModuleLog.maps(
                        "MAPS-KBD-002",
                        "hide overlay — ${activity.javaClass.simpleName} stopped",
                        always = true
                    )
                    ProjectedKeyboardOverlay.hideForNavigation()
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (!ModulePrefs.isEnabled()) return
                    if (!isMapsCarUiHost(activity)) return
                    if (!ProjectedKeyboardOverlay.isVisible()) return
                    ProjectedKeyboardOverlay.hideForNavigation()
                }
            })
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (!ModulePrefs.isEnabled()) return
                    if (!ProjectedKeyboardOverlay.isVisible()) return
                    ModuleLog.maps(
                        "MAPS-KBD-002",
                        "hide overlay — application configuration changed",
                        always = true
                    )
                    ProjectedKeyboardOverlay.hideForNavigation()
                }

                override fun onLowMemory() = Unit

                override fun onTrimMemory(level: Int) = Unit
            })
            log("Registered overlay dismiss on navigation / layout change")
        }.onFailure { log("Failed to register overlay dismiss lifecycle: ${it.message}") }
    }

    private fun isMapsCarUiHost(activity: Activity): Boolean {
        val className = activity.javaClass.name
        if (className.contains("GhostActivity") ||
            className.contains("biph") ||
            className.contains("CarApp") ||
            className.contains("Projection")
        ) {
            return true
        }
        val displayId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        return displayId != Display.DEFAULT_DISPLAY
    }

    /**
     * kur hint resolver — method name obfuscates between Maps builds (aJ may be absent).
     * Match static String methods taking Context + multiple boolean flags.
     */
    private fun hookSearchHintResolver(ctx: HookContext) {
        runCatching {
            val kur = findMapsClass(ctx.classLoader, "kur")
            val contextClass = android.content.Context::class.java
            val booleanPrimitive = Boolean::class.javaPrimitiveType!!
            var hooked = 0
            for (method in kur.declaredMethods) {
                if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != String::class.java) continue
                val params = method.parameterTypes
                if (params.isEmpty() || params[0] != contextClass) continue
                if (params.count { it == booleanPrimitive } < 2) continue
                HookChains.hookMethod(xposed, method, kurAjHook())
                if (!kurAjHookLogged) {
                    kurAjHookLogged = true
                    val sig = params.joinToString(",") { it.simpleName }
                    ModuleLog.maps("MAPS-000", "hook kur hint sig=${method.name}($sig)", always = true)
                }
                hooked++
            }
            if (hooked == 0) {
                log("kur hint resolver not found on ${kur.name}")
            } else {
                log("Hooked kur hint resolver x$hooked (${kur.name})")
            }
        }.onFailure { log("Failed to hook kur hint resolver: ${it.message}") }

        runCatching {
            HookChains.findAndHookMethod(xposed, android.content.res.Resources::class.java, "getString", object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val original = param.result as? String ?: return
                        val rewritten = VoicePlateHints.rewriteIfVoiceOnly(original) ?: return
                        param.result = rewritten
                        ModuleLog.maps(
                            "MAPS-HINT-001",
                            "Resources.getString rewritten \"${original.take(40)}\" -> \"$rewritten\"",
                            always = true
                        )
                    }
                }, Int::class.javaPrimitiveType!!)
            log("Hooked Maps Resources.getString hint rewrite")
        }.onFailure { log("Failed to hook Maps Resources.getString: ${it.message}") }
    }

    private fun kurAjHook() = object : MethodHook() {
        override fun beforeHookedMethod(param: HookParam) {
            if (!ModulePrefs.isEnabled()) return
            if (param.args.size < 5) return
            debugEntry("kur.aJ() driving=${param.args[1]} keyboardBlocked=${param.args.getOrNull(4)}")
            if (param.args[1] == true) {
                debug("kur.aJ() forced driving=false")
                param.args[1] = false
            }
            if (param.args.getOrNull(4) == true) {
                debug("kur.aJ() forced keyboardBlocked=false")
                param.args[4] = false
            }
        }

        override fun afterHookedMethod(param: HookParam) {
            if (!ModulePrefs.isEnabled()) return
            val hint = param.result as? String ?: return
            val rewritten = VoicePlateHints.rewriteIfVoiceOnly(hint) ?: return
            ModuleLog.maps(
                "MAPS-HINT-001",
                "kur.aJ rewritten \"${hint.take(40)}\" -> \"$rewritten\"",
                always = true
            )
            param.result = rewritten
        }
    }

    /** qha carries isMicRestricted / isKeyboardRestricted used by qhf tap routing. */
    private fun hookHeaderUiState(ctx: HookContext) {
        runCatching {
            val qha = findMapsClass(ctx.classLoader, "qha")
            HookChains.hookAllConstructors(xposed, qha, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args.size < 5) return
                    if (param.args[3] == true) {
                        debug("qha forced isMicRestricted=false")
                        param.args[3] = false
                    }
                    if (param.args[4] == true) {
                        debug("qha forced isKeyboardRestricted=false")
                        param.args[4] = false
                    }
                }
            })
            log("Hooked qha constructors (${qha.name})")
        }.onFailure { log("Failed to hook qha: ${it.message}") }
    }

    /** qwt DistractionState feeds qwv header — force unrestricted for keyboard path. */
    private fun hookDistractionState(ctx: HookContext) {
        runCatching {
            val qwt = findMapsClass(ctx.classLoader, "qwt")
            HookChains.hookAllConstructors(xposed, qwt, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args.size < 2) return
                    if (param.args[0] == true) {
                        debug("qwt forced isKeyboardRestricted=false")
                        param.args[0] = false
                    }
                    if (param.args[1] == true) {
                        debug("qwt forced isConfigRestricted=false")
                        param.args[1] = false
                    }
                }
            })
            log("Hooked qwt constructors (${qwt.name})")
        }.onFailure { log("Failed to hook qwt: ${it.message}") }
    }

    private fun hookMapsCarHostCache(ctx: HookContext) {
        val cacheHook = object : MethodHook() {
            override fun afterHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                lastMapsCarHost = WeakReference(param.thisObject)
                ModuleLog.maps(
                    "MAPS-000",
                    "cached car host ${param.thisObject?.javaClass?.simpleName}",
                    always = true
                )
            }
        }
        for (shortName in listOf("tur", "tvj", "llj", "biph")) {
            runCatching {
                val clazz = findMapsClass(ctx.classLoader, shortName)
                HookChains.hookAllMethods(xposed, clazz, "onResume", cacheHook)
                HookChains.hookAllMethods(xposed, clazz, "onStart", cacheHook)
                log("Hooked ${clazz.simpleName} onResume/onStart host cache")
            }.onFailure { debug("Skip ${shortName} lifecycle: ${it.message}") }
        }
        runCatching {
            val llj = findMapsClass(ctx.classLoader, "llj")
            HookChains.hookAllMethods(xposed, llj, "c", cacheHook)
            log("Hooked llj.c onCreate host cache (${llj.name})")
        }.onFailure { debug("Skip llj.c host cache: ${it.message}") }
    }

    /**
     * Voice search IPC: Bundle gmm_mic=true → lka.z(1) → gearhead kcw.k(10).
     * pub.s() is not always on the call stack; intercept the binder transaction.
     */
    private fun hookGmmMicBinder(ctx: HookContext) {
        val gmmMicPassthrough = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (param.args[0] as Int != 1) return
                if (mapsMicVoiceActive.get() != true) {
                    pendingGmmMic.remove()
                    return
                }
                pendingGmmMic.remove()
                mapsMicVoiceActive.remove()
                ModuleLog.maps("MAPS-MIC-001", "lka.${param.method.name}(1) gmm_mic — voice passthrough", always = true)
                notifyGearheadMapsMicVoice()
            }
        }

        runCatching {
            HookChains.hookAllMethods(xposed, android.os.Bundle::class.java, "putBoolean", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] == "gmm_mic" && param.args[1] == true) {
                        pendingGmmMic.set(true)
                    }
                }
            })
            log("Hooked Bundle.putBoolean gmm_mic tracker")
        }.onFailure { log("Failed to hook Bundle.putBoolean: ${it.message}") }

        runCatching {
            val lkc = findMapsClass(ctx.classLoader, "lkc")
            HookChains.hookAllMethods(xposed, lkc, "f", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args.size < 2) return
                    val bundle = param.args[1] as? android.os.Bundle ?: return
                    if (bundle.getBoolean("gmm_mic")) {
                        pendingGmmMic.set(true)
                        ModuleLog.maps("MAPS-000", "lkc.f gmm_mic bundle detected", always = true)
                    }
                }
            })
            log("Hooked lkc.f gmm_mic tracker (${lkc.name})")
        }.onFailure { log("Failed to hook lkc.f: ${it.message}") }

        runCatching {
            val lka = findMapsClass(ctx.classLoader, "lka")
            HookChains.hookAllMethods(xposed, lka, "z", gmmMicPassthrough)
            HookChains.hookAllMethods(xposed, lka, "A", gmmMicPassthrough)
            log("Hooked lka.z/A gmm_mic mic passthrough (${lka.name})")
        }.onFailure { log("Failed to hook lka.z/A: ${it.message}") }
    }

    /**
     * pub.s() on tur/tvj is the Maps header mic — always passthrough; notify gearhead before gmm_mic IPC.
     */
    private fun hookPubVoiceSearch(ctx: HookContext) {
        val micPassthroughHook = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                mapsMicVoiceActive.set(true)
                pendingGmmMic.remove()
                notifyGearheadMapsMicVoice()
                ModuleLog.maps(
                    "MAPS-MIC-001",
                    "${param.thisObject?.javaClass?.simpleName}.s() mic voice allowed",
                    always = true
                )
            }

            override fun afterHookedMethod(param: HookParam) {
                mapsMicVoiceActive.remove()
            }
        }

        var hooked = 0
        runCatching {
            val pub = findMapsClass(ctx.classLoader, "pub")
            for (shortName in listOf("tur", "tvj", "rzb", "rzc")) {
                runCatching {
                    val clazz = findMapsClass(ctx.classLoader, shortName)
                    if (!pub.isAssignableFrom(clazz)) return@runCatching
                    HookChains.hookAllMethods(xposed, clazz, "s", micPassthroughHook)
                    log("Hooked ${clazz.simpleName}.s (mic passthrough)")
                    hooked++
                }
            }
        }.onFailure { log("Failed to hook pub.s candidates: ${it.message}") }

        if (hooked == 0) {
            runCatching {
                val tur = findMapsClass(ctx.classLoader, "tur")
                HookChains.hookAllMethods(xposed, tur, "s", micPassthroughHook)
                log("Hooked tur.s fallback mic passthrough (${tur.name})")
            }.onFailure { log("Failed to hook tur.s fallback: ${it.message}") }
        }
    }

    private fun openMapsKeyboard(host: Any? = null) {
        if (ProjectedKeyboardOverlay.toggleIfVisible()) {
            ModuleLog.maps("MAPS-KBD-002", "keyboard toggle — closed overlay", always = true)
            return
        }
        if (ProjectedKeyboardOverlay.shouldSuppressNextOpen()) {
            ModuleLog.maps(
                "MAPS-KBD-002",
                "open suppressed after toggle-close (trailing OPEN broadcast)",
                always = true
            )
            return
        }

        val carHost = resolveMapsCarHost(host)

        lastSnp?.get()?.let { snp ->
            runCatching {
                Reflect.callMethod(snp, "k")
                ModuleLog.maps("MAPS-003", "snp.k() invoked directly", always = true)
                return
            }.onFailure {
                ModuleLog.maps("MAPS-004", "snp.k() failed: ${it.message}", always = true)
            }
        }

        val rek = discoverRekOverlay() ?: lastRel?.get()
        if (rek != null) {
            openProjectedKeyboard(rek)
            return
        }

        if (carHost != null) {
            ModuleLog.maps(
                "MAPS-002",
                "attempt dispatchKeyEvent keyboard on ${carHost.javaClass.simpleName}",
                always = true
            )
            runCatching {
                val activity = carHost as? android.app.Activity
                val content = activity?.findViewById<View>(android.R.id.content)
                    ?: (carHost as? View)
                val keyEvent = KeyEvent(KeyEvent.ACTION_UP, 22)
                if (content?.dispatchKeyEvent(keyEvent) == true) {
                    ModuleLog.maps("MAPS-003", "dispatchKeyEvent(KEYCODE=22) consumed", always = true)
                    return
                }
                ModuleLog.maps("MAPS-004", "dispatchKeyEvent(KEYCODE=22) not consumed", always = true)
            }.onFailure {
                ModuleLog.maps("MAPS-004", "dispatchKeyEvent failed: ${it.message}", always = true)
            }

            val activity = carHost as? android.app.Activity
            val ctx = activity ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as Context
            }.getOrNull()
            if (ctx != null) {
                ModuleLog.maps(
                    "MAPS-KBD-001",
                    "stock keyboard failed — showing custom QWERTY in Maps process",
                    always = true
                )
                ProjectedKeyboardOverlay.show(
                    context = ctx,
                    onSubmit = { query -> MapsSearchSubmit.submit(ctx, query) },
                    preferredDisplay = activity?.display,
                    hostActivity = activity
                )
            }
            return
        }

        ModuleLog.maps("MAPS-004", "no rek overlay or car host for keyboard open", always = true)
    }

    /** Proactive bind: rel.d → snp.j before gearhead opens xcu/xdb. */
    private fun prepareMapsIme() {
        resolveMapsCarHost(lastMapsCarHost?.get())
        discoverRekOverlay()?.let { rek ->
            ModuleLog.maps("MAPS-002", "PREPARE — rel.d bind chain", always = true)
            openProjectedKeyboard(rek)
            return
        }
        lastRel?.get()?.let { rek ->
            ModuleLog.maps("MAPS-002", "PREPARE — cached rel.d bind chain", always = true)
            openProjectedKeyboard(rek)
            return
        }
        lastSnp?.get()?.let { snp ->
            runCatching {
                Reflect.callMethod(snp, "j")
                ModuleLog.maps("MAPS-003", "PREPARE — snp.j() on cached snp", always = true)
            }.onFailure {
                ModuleLog.maps("MAPS-004", "PREPARE snp.j failed: ${it.message}", always = true)
            }
            runCatching {
                Reflect.callMethod(snp, "k")
                ModuleLog.maps("MAPS-003", "PREPARE — snp.k() on cached snp", always = true)
            }.onFailure {
                ModuleLog.maps("MAPS-004", "PREPARE snp.k failed: ${it.message}", always = true)
            }
            return
        }
        ModuleLog.maps("MAPS-004", "PREPARE — no rek overlay (discover via qhf/activity scan failed)", always = true)
    }

    /** Find rek via qhf.b field on live activities when lastRel is empty. */
    private fun notifyGearheadMapsMicVoice() {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as android.app.Application
            MicSignal.signalMapsMicVoice(app)
            val intent = Intent(KeyboardBridge.ACTION_MAPS_MIC_VOICE)
                .setPackage("com.google.android.projection.gearhead")
            app.sendBroadcast(intent)
        }.onFailure {
            ModuleLog.maps("MAPS-004", "MAPS_MIC_VOICE broadcast failed: ${it.message}", always = true)
        }
    }

    private fun discoverRekOverlay(): Any? {
        val classLoader = mapsClassLoader ?: return null
        val rekClass = runCatching { findMapsClass(classLoader, "rek") }.getOrNull() ?: return null
        val qhfClass = runCatching { findMapsClass(classLoader, "qhf") }.getOrNull()
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val app = Reflect.callStaticMethod(atClass, "currentApplication") ?: return@runCatching
            scanForRek(app, rekClass, qhfClass)?.let { return it }
            val activities = Reflect.getObjectField(at, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") ?: continue
                scanForRek(activity, rekClass, qhfClass)?.let { rek ->
                    lastRel = WeakReference(rek)
                    ModuleLog.maps(
                        "MAPS-002",
                        "discovered rek overlay via ${activity.javaClass.simpleName}",
                        always = true
                    )
                    return rek
                }
            }
        }.onFailure {
            ModuleLog.maps("MAPS-004", "rek discovery scan failed: ${it.message}", always = true)
        }
        return null
    }

    private fun scanForRek(root: Any, rekClass: Class<*>, qhfClass: Class<*>?): Any? {
        if (rekClass.isInstance(root)) return root
        if (qhfClass != null && qhfClass.isInstance(root)) {
            runCatching {
                val rek = Reflect.getObjectField(root, "b")
                if (rek != null && rekClass.isInstance(rek)) return rek
            }
        }
        for (field in root.javaClass.declaredFields) {
            if (field.type.isPrimitive) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(root) ?: return@runCatching
                if (rekClass.isInstance(value)) return value
                if (qhfClass != null && qhfClass.isInstance(value)) {
                    val rek = Reflect.getObjectField(value, "b")
                    if (rek != null && rekClass.isInstance(rek)) return rek
                }
            }
        }
        return null
    }

    private fun resolveMapsCarHost(host: Any?): Any? {
        host?.let {
            lastMapsCarHost = WeakReference(it)
            return it
        }
        lastMapsCarHost?.get()?.let { return it }
        return findMapsCarActivity()
    }

    private fun findMapsCarActivity(): Any? {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(at, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") ?: continue
                val name = activity.javaClass.name
                if (name.contains("tur") || name.contains("tvj") || name.contains("llj") ||
                    name.contains("biph") || name.contains("GhostActivity") || name.contains("qhf")
                ) {
                    lastMapsCarHost = WeakReference(activity)
                    ModuleLog.maps("MAPS-002", "discovered car activity $name", always = true)
                    return activity
                }
            }
        }.onFailure {
            ModuleLog.maps("MAPS-004", "activity scan failed: ${it.message}", always = true)
        }
        return null
    }

    /**
     * Search bar: qhf.l() → rek.d keyboard. Mic: qhf.k()/i() → pub.s voice (left alone).
     */
    private fun hookSearchBarTap(ctx: HookContext) {
        runCatching {
            val qhf = findMapsClass(ctx.classLoader, "qhf")
            val blhxDone = runCatching { blhxDone(ctx) }.getOrNull()
            val keyboardTap = object : MethodReplacement() {
                override fun replaceHookedMethod(param: HookParam): Any? {
                    if (!ModulePrefs.isEnabled()) {
                        return HookChains.invokeOriginal(xposed, param)
                    }
                    ModuleLog.maps("MAPS-001", "qhf.l() search tap — opening projected keyboard", always = true)
                    val rek = Reflect.getObjectField(param.thisObject, "b") ?: return blhxDone
                    openProjectedKeyboard(rek)
                    return blhxDone
                }
            }
            val micPassthrough = object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    mapsMicVoiceActive.set(true)
                    ModuleLog.maps("MAPS-MIC-001", "qhf.${param.method.name}() mic voice", always = true)
                }

                override fun afterHookedMethod(param: HookParam) {
                    mapsMicVoiceActive.remove()
                }
            }
            HookChains.hookAllMethods(xposed, qhf, "l", keyboardTap)
            for (method in listOf("k", "i")) {
                HookChains.hookAllMethods(xposed, qhf, method, micPassthrough)
            }
            log("Hooked qhf.l keyboard + qhf.k/i mic (${qhf.name})")
        }.onFailure { log("Failed to hook qhf tap methods: ${it.message}") }
    }

    /**
     * Maps-only voice dictation block (gearhead voice assistant hooks stay disabled).
     * AA search often uses NavAssistantCallbacks gRPC → aoeb.l → voice session, bypassing qhf.
     */
    private fun hookMapsVoiceSession(ctx: HookContext) {
        runCatching {
            val aoeb = findMapsClass(ctx.classLoader, "aoeb")
            HookChains.findAndHookMethod(xposed, aoeb, "l", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    ModuleLog.maps("MAPS-001", "aoeb.l($trigger) blocked — opening projected keyboard", always = true)
                    openFromLastRel()
                    param.result = null
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked aoeb.l (${aoeb.name})")
        }.onFailure { log("Failed to hook aoeb.l: ${it.message}") }

        runCatching {
            val aodo = findMapsClass(ctx.classLoader, "aodo")
            HookChains.findAndHookMethod(xposed, aodo, "m", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    ModuleLog.maps("MAPS-001", "aodo.m(${param.args[0]}) blocked voice session", always = true)
                    openFromLastRel()
                    param.result = null
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked aodo.m (${aodo.name})")
        }.onFailure { log("Failed to hook aodo.m: ${it.message}") }
    }

    /**
     * Car head-unit touches are injected via [biug.c] into Maps' map [Presentation] (biqv),
     * not into our keyboard [Presentation]. Steer them to the overlay while it is visible.
     */
    private fun hookMapsCarTouchIntercept(ctx: HookContext) {
        val intercept = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (!ProjectedKeyboardOverlay.isVisible()) return
                val motionEvent = param.args[0] as? MotionEvent ?: return
                if (ProjectedKeyboardOverlay.dispatchCarTouch(motionEvent)) {
                    param.result = Pair.create(1, null)
                    ModuleLog.maps(
                        "MAPS-KBD-006",
                        "car touch steered to keyboard (${param.thisObject?.javaClass?.simpleName})",
                        always = true
                    )
                }
            }
        }
        var hooked = 0
        for (shortName in listOf("biue", "biui")) {
            runCatching {
                val clazz = findMapsClass(ctx.classLoader, shortName)
                HookChains.findAndHookMethod(xposed, clazz, "c", intercept, MotionEvent::class.java)
                log("Hooked $shortName.c car touch intercept (${clazz.name})")
                hooked++
            }.onFailure { log("Failed to hook $shortName.c touch intercept: ${it.message}") }
        }
        if (hooked == 0) {
            log("No biug car touch hook installed — overlay taps may pass through")
        }
        hookGhostActivityTouchFallback(ctx)
    }

    private fun hookGhostActivityTouchFallback(ctx: HookContext) {
        runCatching {
            val ghost = Reflect.findClass(
                "com.google.android.apps.auto.client.activity.ghost.GhostActivity",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, ghost, "dispatchTouchEvent", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (!ProjectedKeyboardOverlay.isVisible()) return
                    val motionEvent = param.args[0] as? MotionEvent ?: return
                    if (ProjectedKeyboardOverlay.dispatchCarTouch(motionEvent)) {
                        param.result = true
                    }
                }
            }, MotionEvent::class.java)
            log("Hooked GhostActivity.dispatchTouchEvent fallback")
        }.onFailure { log("Failed to hook GhostActivity touch fallback: ${it.message}") }
    }

    private fun hookProjectedKeyboard(ctx: HookContext) {
        val rekShowHook = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                lastRel = WeakReference(param.thisObject)
                debugEntry("${param.thisObject?.javaClass?.simpleName}.d() show keyboard overlay")
            }

            override fun afterHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                val rel = param.thisObject ?: return
                scheduleShowCarIme(rel)
            }
        }

        runCatching {
            val rek = findMapsClass(ctx.classLoader, "rek")
            var hooked = 0
            runCatching {
                val rel = findMapsClass(ctx.classLoader, "rel")
                HookChains.hookAllMethods(xposed, rel, "d", rekShowHook)
                log("Hooked rel.d (${rel.name})")
                hooked++
            }.onFailure { debug("rel.d hook failed: ${it.message}") }
            if (hooked == 0) {
                hooked = hookRekImplementorsByDex(ctx, rek, rekShowHook)
            }
            if (hooked == 0) {
                hooked = hookKeyboardOverlayBySignature(ctx, rekShowHook)
            }
            if (hooked == 0) {
                log("rek.d overlay hook not found")
            }
        }.onFailure { log("Failed to hook rek overlay: ${it.message}") }

        runCatching {
            val snp = findMapsClass(ctx.classLoader, "snp")
            HookChains.hookAllMethods(xposed, snp, "k", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    lastSnp = WeakReference(param.thisObject)
                    ModuleLog.maps("MAPS-003", "snp.k() show car IME", always = true)
                }
            })
            HookChains.hookAllMethods(xposed, snp, "j", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    lastSnp = WeakReference(param.thisObject)
                    ModuleLog.maps("MAPS-003", "snp.j() bind car input", always = true)
                }
            })
            log("Hooked snp.j/k (${snp.name})")
        }.onFailure { log("Failed to hook snp: ${it.message}") }
    }

    private fun hookRekImplementorsByDex(
        ctx: HookContext,
        rek: Class<*>,
        hook: MethodHook
    ): Int {
        var hooked = 0
        runCatching {
            val dex = DexFile(ctx.sourcePath)
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!name.startsWith("defpackage.")) continue
                runCatching {
                    val clazz = ctx.classLoader.loadClass(name)
                    if (clazz.isInterface || !rek.isAssignableFrom(clazz)) return@runCatching
                    HookChains.hookAllMethods(xposed, clazz, "d", hook)
                    log("Hooked rek.d on ${clazz.name} (dex scan)")
                    hooked++
                }
            }
        }.onFailure { debug("rek dex scan failed: ${it.message}") }
        return hooked
    }

    /** rel-like overlay: void d() plus void e(String, cbwl, ccxd) even when rek interface moved. */
    private fun hookKeyboardOverlayBySignature(
        ctx: HookContext,
        hook: MethodHook
    ): Int {
        var hooked = 0
        runCatching {
            val dex = DexFile(ctx.sourcePath)
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!name.startsWith("defpackage.")) continue
                runCatching {
                    val clazz = ctx.classLoader.loadClass(name)
                    if (clazz.isInterface) return@runCatching
                    val methods = clazz.declaredMethods
                    val hasShow = methods.any {
                        it.name == "d" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
                    }
                    val hasSubmit = methods.any {
                        it.name == "e" && it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == String::class.java
                    }
                    if (!hasShow || !hasSubmit) return@runCatching
                    HookChains.hookAllMethods(xposed, clazz, "d", hook)
                    log("Hooked keyboard overlay ${clazz.name} (signature scan)")
                    hooked++
                }
            }
        }.onFailure { debug("keyboard overlay signature scan failed: ${it.message}") }
        return hooked
    }

    /** trt.i() feeds keyboard/driving restriction into header view model. */
    private fun hookTrtDrivingFlags(ctx: HookContext) {
        val hook = object : MethodHook() {
            override fun afterHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (param.result == true) {
                    debug("trt.i() forced false in ${param.thisObject?.javaClass?.simpleName}")
                    param.result = false
                }
            }
        }
        for (name in listOf("trp", "tro", "trn", "trq", "trk")) {
            runCatching {
                val clazz = findMapsClass(ctx.classLoader, name)
                HookChains.findAndHookMethod(xposed, clazz, "i", hook)
                log("Hooked $name.i (${clazz.name})")
            }.onFailure { debug("Skip $name.i: ${it.message}") }
        }
    }

    private fun openProjectedKeyboard(rek: Any) {
        if (ProjectedKeyboardOverlay.toggleIfVisible()) {
            ModuleLog.maps("MAPS-KBD-002", "keyboard toggle — closed overlay", always = true)
            return
        }
        if (ProjectedKeyboardOverlay.shouldSuppressNextOpen()) {
            ModuleLog.maps(
                "MAPS-KBD-002",
                "rek.d suppressed after toggle-close",
                always = true
            )
            return
        }
        lastRel = WeakReference(rek)
        runCatching {
            Reflect.callMethod(rek, "d")
        }.onFailure {
            log("rek.d() failed: ${it.message}")
            return
        }
        scheduleShowCarIme(rek)
    }

    private fun openFromLastRel(): Boolean {
        val rel = lastRel?.get() ?: return false
        openProjectedKeyboard(rel)
        return true
    }

    /** Submit query from custom overlay — rek.e / snp.b / geo intent fallback. */
    private fun submitSearchQuery(query: String) {
        if (query.isBlank()) return
        val rek = discoverRekOverlay() ?: lastRel?.get()
        if (rek != null) {
            runCatching {
                Reflect.callMethod(rek, "e", query, null, null)
                ModuleLog.maps("MAPS-KBD-001", "submitted via rek.e", always = true)
                return
            }.onFailure {
                ModuleLog.maps("MAPS-KBD-004", "rek.e failed: ${it.message}", always = true)
            }
        }
        lastSnp?.get()?.let { snp ->
            runCatching {
                val classLoader = mapsClassLoader ?: throw IllegalStateException("no classloader")
                val szf = runCatching { findMapsClass(classLoader, "szf") }.getOrNull()
                val szfValue = szf?.enumConstants?.firstOrNull()
                Reflect.callMethod(
                    snp,
                    "b",
                    query,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    szfValue,
                    null,
                    false
                )
                ModuleLog.maps("MAPS-KBD-001", "submitted via snp.b", always = true)
                return
            }.onFailure {
                ModuleLog.maps("MAPS-KBD-004", "snp.b failed: ${it.message}", always = true)
            }
        }
        prepareMapsIme()
        val rekRetry = discoverRekOverlay() ?: lastRel?.get()
        if (rekRetry != null) {
            runCatching {
                Reflect.callMethod(rekRetry, "e", query, null, null)
                ModuleLog.maps("MAPS-KBD-001", "submitted via rek.e after PREPARE", always = true)
                return
            }.onFailure {
                ModuleLog.maps("MAPS-KBD-004", "rek.e retry failed: ${it.message}", always = true)
            }
        }
        submitViaGeoIntent(query)
    }

    private fun submitViaGeoIntent(query: String) {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as android.app.Application
            val uri = android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            ModuleLog.maps("MAPS-KBD-001", "submitted via geo intent fallback", always = true)
        }.onFailure {
            ModuleLog.maps("MAPS-KBD-004", "geo intent submit failed: ${it.message}", always = true)
        }
    }

    /** rel.d() binds car input (snp.j); reh.b() shows projection IME (snp.k). */
    private fun scheduleShowCarIme(rel: Any) {
        val showIme = Runnable {
            runCatching {
                val reh = findRehController(rel)
                    ?: throw IllegalStateException("no reh on ${rel.javaClass.name}")
                Reflect.callMethod(reh, "b")
                ModuleLog.maps("MAPS-003", "reh.b() requested car IME", always = true)
            }.onFailure { log("reh.b() failed: ${it.message}") }
        }
        val anchor = runCatching { Reflect.callMethod(rel, "f") as? View }.getOrNull()
            ?: runCatching {
                val field = rel.javaClass.declaredFields.firstOrNull { it.type == View::class.java }
                field?.isAccessible = true
                field?.get(rel) as? View
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

    private fun blhxDone(ctx: HookContext): Any {
        val blhx = findMapsClass(ctx.classLoader, "blhx")
        return Reflect.getStaticObjectField(blhx, "a")
            ?: throw IllegalStateException("blhx.a is null")
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
        if (ModulePrefs.isDebug()) log(">> $message")
    }

    private fun debug(message: String) {
        if (ModulePrefs.isDebug()) log(message)
    }

    private fun log(message: String) {
        ModuleLog.maps("HOOK", message, always = true)
    }
}
