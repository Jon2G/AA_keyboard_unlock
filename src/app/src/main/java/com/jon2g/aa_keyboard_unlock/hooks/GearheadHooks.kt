package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.xposed.DexHooks
import com.jon2g.aa_keyboard_unlock.xposed.HookChains
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.HookParam
import com.jon2g.aa_keyboard_unlock.xposed.MethodHook
import com.jon2g.aa_keyboard_unlock.xposed.MethodReplacement
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import io.github.libxposed.api.XposedInterface


import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.jon2g.aa_keyboard_unlock.KeyboardBridge
import com.jon2g.aa_keyboard_unlock.MicSignal
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

object GearheadHooks {
    // Car sensor types (gearhead xnv / DHU protocol)
    private const val SENSOR_TYPE_CAR_SPEED = 2
    private const val SENSOR_TYPE_DRIVING_STATUS = 11

    // Voice session types / triggers (VoiceSessionConfig: a=type, f=trigger)
    private const val VOICE_SESSION_TYPE_VOICE = 1
    private const val VOICE_SESSION_TYPE_DIRECT_REPLY = 3
    private const val VOICE_SESSION_TYPE_TRANSCRIPTION = 5
    private const val VOICE_SESSION_TYPE_START_TRANSCRIPTION = 6
    private const val VOICE_SEARCH_TRIGGER_MAPS = 10

    // Block raw voice / transcription session types for Maps search tap window.
    private val VOICE_BLOCK_SESSION_TYPES = setOf(
        VOICE_SESSION_TYPE_VOICE,
        VOICE_SESSION_TYPE_DIRECT_REPLY,
        VOICE_SESSION_TYPE_TRANSCRIPTION,
        VOICE_SESSION_TYPE_START_TRANSCRIPTION
    )

    /** After blocking kcw.k(10), reject assistant voice/transcription sessions briefly. */
    @Volatile
    private var mapsSearchBlockUntilMs = 0L

    /** Mic icon uses hgy.c/jxa — allow through even during search-voice blocks. */
    @Volatile
    private var micDictationActive = false

    /** Maps header mic (gmm_mic IPC) — allow kxe.F(10) briefly. */
    @Volatile
    private var mapsMicFromMapsHeaderUntilMs = 0L

    // lha enum: a=CAR_MOVING, b=CAR_PARKED, c=UNKNOWN
    private const val LHA_FIELD_CAR_PARKED = "b"

    // Voice Plate transcriptionState: 1=INACTIVE (keyboard), 2=ACTIVE (voice)
    private const val TRANSCRIPTION_INACTIVE = 1
    private const val TRANSCRIPTION_ACTIVE = 2

    @Volatile
    private lateinit var xposed: XposedInterface

    @Volatile
    private var installedForProcess = false

    /** Latest SearchTemplate model — used as hgy.c(kkl) fallback for Maps keyboard. */
    @Volatile
    private var lastSearchGxy: WeakReference<Any>? = null

    /** Active projected IME service (xcu/xdm) — used to show QWERTY after Maps search tap. */
    @Volatile
    private var activeImeService: WeakReference<Any>? = null

    /** Active input method fragment (xdb/xdl) — fallback keyboard unlock. */
    @Volatile
    private var activeInputFragment: WeakReference<Any>? = null

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
            if (app != null) registerGearheadBridgeReceiver(app)
        }
    }

    private fun installHooks(ctx: HookContext) {
        ModuleLog.install(
            ModuleLog.Process.GH,
            "enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()} pkg=${ctx.packageName}"
        )
        hookSensorCallbacks(ctx, "lhk")
        hookSensorCallbacks(ctx, "lhu")
        hookLocationManager(ctx)
        hookInputMethodFragment(ctx)
        hookParkingAndAssistantSettings(ctx)
        hookCarUiConstraints(ctx)
        hookCarAppKeyboardGate(ctx)
        hookMapsSearchKeyboard(ctx)
        // hookVoicePlateAndAssistant(ctx) // Disabled to allow voice assistant to function
        ModuleLog.gearhead("GH-INSTALL", "hooks installed for ${ctx.packageName}", always = true)
    }

    private fun findGearheadClass(classLoader: ClassLoader, shortName: String): Class<*> {
        for (name in listOf(shortName, "defpackage.$shortName")) {
            runCatching {
                return Reflect.findClass(name, classLoader)
            }
        }
        throw ClassNotFoundException(shortName)
    }

    private val sensorSpoofHook = object : MethodHook() {
        override fun beforeHookedMethod(param: HookParam) {
            if (!ModulePrefs.isEnabled()) return
            val args = param.args ?: return
            if (args.size < 4) return

            val sensorType = args[0] as? Int ?: return
            val floats = args[2] as? FloatArray ?: return
            val bytes = args[3] as? ByteArray ?: return

            when (sensorType) {
                SENSOR_TYPE_CAR_SPEED -> {
                    if (floats.isNotEmpty() && floats[0] != 0f) {
                        debug("Spoofing car speed ${floats[0]} -> 0")
                        floats[0] = 0f
                    }
                }
                SENSOR_TYPE_DRIVING_STATUS -> {
                    if (bytes.isNotEmpty()) {
                        val original = bytes[0]
                        // Clear all restriction bits so apps like Maps see fully parked state
                        val cleared = 0.toByte() 
                        if (cleared != original) {
                            debug(
                                "Spoofing driving status 0x${original.toUByte().toString(16)} -> " +
                                    "0x${cleared.toUByte().toString(16)}"
                            )
                            bytes[0] = cleared
                        }
                    }
                }
            }
        }
    }

    private fun hookSensorCallbacks(ctx: HookContext, shortName: String) {
        runCatching {
            val clazz = findGearheadClass(ctx.classLoader, shortName)
            if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
                log("Skipping $shortName.d (abstract/interface; use concrete implementor)")
                return
            }
            HookChains.hookAllMethods(xposed, clazz, "d", sensorSpoofHook)
            log("Hooked $shortName.d (${clazz.name})")
        }.onFailure { log("Failed to hook $shortName.d: ${it.message}") }
    }

    private fun hookLocationManager(ctx: HookContext) {
        runCatching {
            val lht = findGearheadClass(ctx.classLoader, "lht")

            // kzr.d().q() — isKeyboardEnabled (parking / keyboard allowed)
            HookChains.findAndHookMethod(xposed, lht, "q", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != true) {
                        debug("lht.q() forced true (was ${param.result})")
                        param.result = true
                    }
                }
            })
            // kzr.d().s() — wheel speed was non-zero
            HookChains.findAndHookMethod(xposed, lht, "s", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != false) {
                        debug("lht.s() forced false (was ${param.result})")
                        param.result = false
                    }
                }
            })
            log("Hooked lht.q/s (${lht.name})")
        }.onFailure { log("Failed to hook lht: ${it.message}") }

        runCatching {
            val lhi = findGearheadClass(ctx.classLoader, "lhi")
            HookChains.findAndHookMethod(xposed, lhi, "f", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val speed = param.result as? Float ?: return
                    if (speed != 0f) {
                        debug("lhi.f() speed $speed -> 0")
                        param.result = 0f
                    }
                }
            })
            log("Hooked lhi.f (${lhi.name})")
        }.onFailure { log("Failed to hook lhi.f: ${it.message}") }
    }

    private fun hookCarAppKeyboardGate(ctx: HookContext) {
        runCatching {
            val jtg = findGearheadClass(ctx.classLoader, "jtg")
            HookChains.findAndHookMethod(xposed, jtg, "b", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    debugEntry("jtg.b()")
                }
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != false) {
                        debug("jtg.b() forced false (keyboard allowed, was ${param.result})")
                        param.result = false
                    }
                }
            })
            HookChains.hookAllConstructors(xposed, jtg, object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    runCatching {
                        val component = Reflect.getObjectField(param.thisObject, "a")
                        val state = Reflect.getObjectField(param.thisObject, "g")
                        Reflect.callMethod(state, "d", true)
                        val gij = Reflect.getObjectField(param.thisObject, "h")
                        Reflect.callMethod(gij, "a", 6)
                        debug("jtg init: keyboard state forced true for $component, refresh dispatched")
                    }.onFailure { debug("jtg init refresh failed: ${it.message}") }
                }
            })
            log("Hooked jtg.b() + constructors (${jtg.name})")
        }.onFailure { log("Failed to hook jtg: ${it.message}") }

        hookTemplateKeyboardMethod(ctx, "jyn", "b", Boolean::class.javaPrimitiveType!!)
        hookTemplateKeyboardMethod(ctx, "jys", "b", Boolean::class.javaPrimitiveType!!)
        hookTemplateKeyboardMethod(ctx, "jyu", "d", Boolean::class.javaPrimitiveType!!)
        hookTemplateHintMethod(ctx, "jyn", "p", Boolean::class.javaPrimitiveType!!)
        hookTemplateHintMethod(ctx, "jys", "p", Boolean::class.javaPrimitiveType!!)

        runCatching {
            val lgz = findGearheadClass(ctx.classLoader, "lgz")
            hookLgzImplementors(ctx, lgz, "lht")
            hookLgzImplementors(ctx, lgz, "jtg")
            hookLgzImplementors(ctx, lgz, "lhl")
            log("Hooked lgz.a implementors")
        }.onFailure { log("Failed to hook lgz.a: ${it.message}") }

        runCatching {
            val gxy = findGearheadClass(ctx.classLoader, "gxy")
            HookChains.hookAllConstructors(xposed, gxy, object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    lastSearchGxy = WeakReference(param.thisObject)
                    ModuleLog.gearhead("GH-MAPS-000", "cached gxy SearchTemplate", always = true)
                }
            })
            val gyb = findGearheadClass(ctx.classLoader, "gyb")
            val hgx = findGearheadClass(ctx.classLoader, "hgx")
            val gbh = findGearheadClass(ctx.classLoader, "gbh")
            HookChains.findAndHookMethod(xposed, gxy, "d", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    debugEntry("gxy.d()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[1] == false) {
                        debug("gxy.d() isKeyboardAllowed forced true")
                        param.args[1] = true
                    }
                }

                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    patchSearchTemplateHint(param.result, gbh, ctx.classLoader)
                }
            }, gyb, Boolean::class.javaPrimitiveType!!, hgx)
            log("Hooked gxy.d (${gxy.name})")
        }.onFailure { log("Failed to hook gxy.d: ${it.message}") }

        runCatching {
            val gxz = findGearheadClass(ctx.classLoader, "gxz")
            HookChains.hookAllConstructors(xposed, gxz, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args.size >= 2 && param.args[1] != true) {
                        debug("gxz isKeyboardAllowed forced true (was ${param.args[1]})")
                        param.args[1] = true
                    }
                }
            })
            log("Hooked gxz constructors (${gxz.name})")
        }.onFailure { log("Failed to hook gxz: ${it.message}") }

        runCatching {
            val gan = findGearheadClass(ctx.classLoader, "gan")
            HookChains.hookAllConstructors(xposed, gan, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args.size >= 9) {
                        if (param.args[8] != false || param.args[2] != true) {
                            debug("gan forced showKeyboard=true voiceOnly=false")
                            param.args[8] = false
                            param.args[2] = true
                        }
                    }
                }
            })
            log("Hooked gan constructors (${gan.name})")
        }.onFailure { log("Failed to hook gan: ${it.message}") }
    }

    private fun hookParkingAndAssistantSettings(ctx: HookContext) {
        runCatching {
            val lht = findGearheadClass(ctx.classLoader, "lht")
            val lha = findGearheadClass(ctx.classLoader, "lha")
            val carParked = Reflect.getStaticObjectField(lha, LHA_FIELD_CAR_PARKED)
            HookChains.findAndHookMethod(xposed, lht, "c", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != carParked) {
                        debug("lht.c() forced CAR_PARKED (was ${param.result})")
                        param.result = carParked
                    }
                }
            })
            log("Hooked lht.c (${lht.name})")
        }.onFailure { log("Failed to hook lht.c: ${it.message}") }

        runCatching {
            val lhi = findGearheadClass(ctx.classLoader, "lhi")
            val lha = findGearheadClass(ctx.classLoader, "lha")
            val carParked = Reflect.getStaticObjectField(lha, LHA_FIELD_CAR_PARKED)
            HookChains.findAndHookMethod(xposed, lhi, "d", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != carParked) {
                        debug("lhi.d() forced CAR_PARKED (was ${param.result})")
                        param.result = carParked
                    }
                }
            })
            log("Hooked lhi.d (${lhi.name})")
        }.onFailure { log("Failed to hook lhi.d: ${it.message}") }

        runCatching {
            val kxk = findGearheadClass(ctx.classLoader, "kxk")
            HookChains.findAndHookMethod(xposed, kxk, "a", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != true) {
                        debug("kxk.a() forced true (keyboard enabled, was ${param.result})")
                        param.result = true
                    }
                }
            })
            log("Hooked kxk.a (${kxk.name})")
        }.onFailure { log("Failed to hook kxk.a: ${it.message}") }
    }

    /** jpm CarUiInfo hint path — do NOT force npz.d/e/f false (breaks xdm.e() IME selection). */
    private fun hookCarUiConstraints(ctx: HookContext) {
        runCatching {
            val jpm = findGearheadClass(ctx.classLoader, "jpm")
            for (method in listOf("a", "b")) {
                HookChains.findAndHookMethod(xposed, jpm, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        debugEntry("jpm.$method()")
                    }
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result == true) {
                            debug("jpm.$method() forced false (was true)")
                            param.result = false
                        }
                    }
                })
            }
            log("Hooked jpm.a/b (${jpm.name})")
        }.onFailure { log("Failed to hook jpm: ${it.message}") }
    }

    private fun hookVoiceMicCapture(ctx: HookContext) {
        runCatching {
            val kwt = findGearheadClass(ctx.classLoader, "kwt")
            HookChains.findAndHookMethod(xposed, kwt, "b", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debug("kwt.b() blocked voice mic capture")
                    param.result = null
                }
            })
            log("Hooked kwt.b (${kwt.name})")
        }.onFailure { log("Failed to hook kwt.b: ${it.message}") }

        runCatching {
            val micProvider = Reflect.findClass(
                "com.google.android.apps.auto.components.demand.audio.GhMicrophoneContentProvider",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, micProvider, "b", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debug("GhMicrophoneContentProvider.b() blocked")
                    param.result = null
                }
            })
            log("Hooked GhMicrophoneContentProvider.b")
        }.onFailure { log("Failed to hook GhMicrophoneContentProvider.b: ${it.message}") }
    }

    /**
     * Maps AA search tap → DemandClientService → kcw.k(trigger=10) → voice / transcription.
     * Block gearhead demand-space voice; Maps process opens projected IME via rek.d (MapsHooks).
     */
    private fun hookMapsSearchKeyboard(ctx: HookContext) {
        hookVoicePlateWidget(ctx)
        hookHintStringRewrites(ctx)
        hookComposeMicDrawableSwap(ctx)
        hookProjectedImeCache(ctx)
        hookMapsVoiceSessionBlock(ctx)
        hookMapsDemandSpaceVoiceBlock(ctx)
        hookMapsDemandSpaceScrimBlock(ctx)
        hookCarAppMicDictation(ctx)
        hookDemandClientMicBypass(ctx)
        hookDismissKeyboardOnAaLayoutChange(ctx)

        runCatching {
            val kvl = findGearheadClass(ctx.classLoader, "kvl")
            val kcw = findGearheadClass(ctx.classLoader, "kcw")
            HookChains.findAndHookMethod(xposed, kcw, "k", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[1] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        val gearCtx = resolveGearheadContext(ctx.classLoader)
                        if (gearCtx != null && MicSignal.isMapsMicActive(gearCtx)) {
                            mapsMicFromMapsHeaderUntilMs = System.currentTimeMillis() + 4000L
                            debugEntry("kcw.k() trigger=$trigger — mic passthrough (MicSignal)")
                            return
                        }
                        if (inMapsMicFromHeader()) {
                            debugEntry("kcw.k() trigger=$trigger — mic passthrough (skip keyboard)")
                            return
                        }
                        debugEntry("kcw.k() trigger=$trigger — keyboard path (skip demand open)")
                        markMapsSearchVoiceBlock()
                        closeMapsVoiceDemand(ctx.classLoader)
                        requestPrepareMapsKeyboardBroadcast(ctx.classLoader)
                        param.result = null
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[1] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        if (inMapsMicFromHeader()) return
                        if (param.hasThrowable()) return
                        val classLoader = ctx.classLoader
                        runOnMainThread {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (inMapsMicFromHeader() || micDictationActive) {
                                    ModuleLog.gearhead("GH-MIC-001", "skip keyboard — mic session active", always = true)
                                    return@postDelayed
                                }
                                ModuleLog.gearhead(
                                    "GH-MAPS-001",
                                    "kcw.k($trigger) after demand blocked — opening keyboard",
                                    always = true
                                )
                                openMapsSearchKeyboardInGearhead(classLoader)
                            }, 300L)
                        }
                    }
                }, kvl, Int::class.javaPrimitiveType!!)
            log("Hooked kcw.k Maps surgical intercept (${kcw.name})")
        }.onFailure { log("Failed to hook kcw.k Maps intercept: ${it.message}") }
    }

    /** Close Maps overlay when AA layout changes or Maps leaves the Voice Plate template. */
    private fun hookDismissKeyboardOnAaLayoutChange(ctx: HookContext) {
        runCatching {
            HookChains.findAndHookMethod(xposed, Activity::class.java, "onConfigurationChanged", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        requestCloseMapsKeyboardBroadcast(ctx.classLoader)
                    }
                }, Configuration::class.java)
            log("Hooked Activity.onConfigurationChanged keyboard dismiss")
        }.onFailure { log("Failed to hook onConfigurationChanged dismiss: ${it.message}") }

        runCatching {
            val voicePlateWidget = Reflect.findClass(
                "com.android.car.libraries.apphost.external.model.widgets.VoicePlateWidget",
                ctx.classLoader
            )
            val jpf = findGearheadClass(ctx.classLoader, "jpf")
            val componentName = android.content.ComponentName::class.java
            val sessionInfo = Reflect.findClass("androidx.car.app.SessionInfo", ctx.classLoader)
            val templateWrapper = Reflect.findClass("androidx.car.app.model.TemplateWrapper", ctx.classLoader)
            HookChains.findAndHookMethod(xposed, jpf, "a", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val component = param.args[0] as? android.content.ComponentName ?: return
                        if (component.packageName != "com.google.android.apps.maps") return
                        val wrapper = param.args[2] ?: return
                        val template = runCatching {
                            Reflect.callMethod(wrapper, "getTemplate")
                        }.getOrNull() ?: return
                        if (voicePlateWidget.isInstance(template)) return
                        requestCloseMapsKeyboardBroadcast(ctx.classLoader)
                    }
                }, componentName, sessionInfo, templateWrapper)
            log("Hooked jpf.a Maps template navigation dismiss (${jpf.name})")
        }.onFailure { log("Failed to hook jpf.a navigation dismiss: ${it.message}") }
    }

    private fun requestCloseMapsKeyboardBroadcast(classLoader: ClassLoader): Boolean {
        return sendMapsKeyboardBroadcast(
            classLoader,
            KeyboardBridge.ACTION_CLOSE_MAPS_KEYBOARD,
            "CLOSE_MAPS_KEYBOARD"
        )
    }

    /** Block gray demand-space scrim (qib.p) on Maps search tap; mic paths still open demand space. */
    private fun hookMapsDemandSpaceScrimBlock(ctx: HookContext) {
        runCatching {
            val qib = findGearheadClass(ctx.classLoader, "qib")
            HookChains.findAndHookMethod(xposed, qib, "p", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (micDictationActive || inMapsMicFromHeader()) return
                    if (!inMapsSearchVoiceBlock()) return
                    ModuleLog.gearhead(
                        "GH-MAPS-003",
                        "qib.p() blocked — Maps search keyboard (no dictation scrim)",
                        always = true
                    )
                    param.result = null
                }
            })
            log("Hooked qib.p demand scrim block (${qib.name})")
        }.onFailure { log("Failed to hook qib.p scrim block: ${it.message}") }
    }

    /** Voice Plate / SearchTemplate compose hardcodes gs_mic_vd_theme_24 via daf.A — swap at load time. */
    private fun hookComposeMicDrawableSwap(ctx: HookContext) {
        runCatching {
            val daf = findGearheadClass(ctx.classLoader, "daf")
            val buq = findGearheadClass(ctx.classLoader, "buq")
            val keyboardId = VoicePlateMicIcon.RES_KEYBOARD_BLACK
            HookChains.findAndHookMethod(xposed, daf, "A", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val resId = param.args[0] as Int
                        if (resId != VoicePlateMicIcon.RES_MIC_THEME) return
                        param.args[0] = keyboardId
                        ModuleLog.gearhead(
                            "GH-ICON-001",
                            "compose mic drawable -> keyboard (daf.A 0x${keyboardId.toString(16)})",
                            always = true
                        )
                    }
                }, Int::class.javaPrimitiveType!!, buq, Int::class.javaPrimitiveType!!)
            log("Hooked daf.A compose mic icon swap (${daf.name})")
        }.onFailure { log("Failed to hook daf.A mic swap: ${it.message}") }
    }

    private fun hookMapsDemandSpaceVoiceBlock(ctx: HookContext) {
        runCatching {
            val qib = findGearheadClass(ctx.classLoader, "qib")
            HookChains.findAndHookMethod(xposed, qib, "l", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] as Int != VOICE_SEARCH_TRIGGER_MAPS) return
                    micDictationActive = true
                    runCatching {
                        val atClass = Class.forName("android.app.ActivityThread")
                        val app = Reflect.callStaticMethod(atClass, "currentApplication") as? Context
                        if (app != null) MicSignal.signalMapsMicVoice(app)
                    }
                    ModuleLog.gearhead("GH-MIC-001", "qib.l(10) mic transcription allowed", always = true)
                }

                override fun afterHookedMethod(param: HookParam) {
                    micDictationActive = false
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked qib.l mic passthrough (${qib.name})")
        }.onFailure { log("Failed to hook qib.l mic passthrough: ${it.message}") }
    }

    /** gmm_transcription_request bundle → qib.l (mic), not kcw.k (search). */
    private fun hookDemandClientMicBypass(ctx: HookContext) {
        runCatching {
            val demandService = Reflect.findClass(
                "com.google.android.gearhead.demand.DemandClientService",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, demandService, "b", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val bundle = param.args[0] as? android.os.Bundle ?: return
                        signalMapsMicFromDemandBundle(ctx.classLoader, bundle, param.thisObject as? Context)
                    }
                }, android.os.Bundle::class.java)
            log("Hooked DemandClientService.b mic detection")
        }.onFailure { log("Failed to hook DemandClientService.b: ${it.message}") }
    }

    private fun signalMapsMicFromDemandBundle(classLoader: ClassLoader, bundle: android.os.Bundle, context: Context?) {
        val transcription = bundle.getBoolean("gmm_transcription_request")
        val hardwareMic = bundle.getInt("open_cause") == 3 && bundle.getInt("open_cause_key_code") == 84
        if (!transcription && !hardwareMic) return
        micDictationActive = true
        mapsMicFromMapsHeaderUntilMs = System.currentTimeMillis() + 4000L
        val ctx = context ?: resolveGearheadContext(classLoader)
        if (ctx != null) MicSignal.signalMapsMicVoice(ctx.applicationContext)
        ModuleLog.gearhead(
            "GH-MIC-001",
            "demand mic bundle (transcription=$transcription hardware=$hardwareMic)",
            always = true
        )
    }

    private fun registerGearheadBridgeReceiver(app: Application) {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!ModulePrefs.isEnabled()) return
                    if (intent.action != KeyboardBridge.ACTION_MAPS_MIC_VOICE) return
                    mapsMicFromMapsHeaderUntilMs = System.currentTimeMillis() + 4000L
                    ModuleLog.gearhead("GH-MIC-002", "MAPS_MIC_VOICE received — allow kxe.F(10)", always = true)
                }
            }
            val filter = IntentFilter(KeyboardBridge.ACTION_MAPS_MIC_VOICE)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            log("Registered MAPS_MIC_VOICE broadcast receiver")
        }.onFailure { log("Failed to register gearhead bridge receiver: ${it.message}") }
    }

    private fun inMapsMicFromHeader(): Boolean {
        if (System.currentTimeMillis() < mapsMicFromMapsHeaderUntilMs) return true
        val ctx = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            Reflect.callStaticMethod(atClass, "currentApplication") as? Context
        }.getOrNull()
        return ctx != null && MicSignal.isMapsMicActive(ctx)
    }

    /**
     * Mic icon on SearchTemplate: gan.onTriggerTranscription → hgy.c(kkl) → jxa dictation.
     * Search bar uses kcw.k(10) instead — leave mic path untouched.
     */
    private fun hookCarAppMicDictation(ctx: HookContext) {
        runCatching {
            val jwz = findGearheadClass(ctx.classLoader, "jwz")
            val kkl = findGearheadClass(ctx.classLoader, "kkl")
            HookChains.findAndHookMethod(xposed, jwz, "c", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    micDictationActive = true
                    runCatching {
                        val atClass = Class.forName("android.app.ActivityThread")
                        val app = Reflect.callStaticMethod(atClass, "currentApplication") as? Context
                        if (app != null) MicSignal.signalMapsMicVoice(app)
                    }
                    ModuleLog.gearhead("GH-MIC-001", "hgy.c(kkl) mic dictation allowed", always = true)
                }

                override fun afterHookedMethod(param: HookParam) {
                    micDictationActive = false
                }
            }, kkl)
            log("Hooked jwz.c mic dictation passthrough (${jwz.name})")
        }.onFailure { log("Failed to hook jwz.c mic passthrough: ${it.message}") }
    }

    private fun hookProjectedImeCache(ctx: HookContext) {
        runCatching {
            val carRegionId = Reflect.findClass(
                "com.google.android.gms.car.display.CarRegionId",
                ctx.classLoader
            )
            val editorInfo = android.view.inputmethod.EditorInfo::class.java
            val xcu = findGearheadClass(ctx.classLoader, "xcu")
            HookChains.findAndHookMethod(xposed, xcu, "c", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    activeImeService = WeakReference(param.thisObject)
                    ModuleLog.gearhead(
                        "GH-MAPS-000",
                        "cached active IME service (${param.thisObject?.javaClass?.simpleName})",
                        always = true
                    )
                }
            }, editorInfo, carRegionId)
            log("Hooked xcu.c IME cache (${xcu.name})")
        }.onFailure { log("Failed to hook xcu.c IME cache: ${it.message}") }
    }

    private fun hookMapsVoiceSessionBlock(ctx: HookContext) {
        val mapsVoiceTypes = setOf(
            VOICE_SESSION_TYPE_VOICE,
            VOICE_SESSION_TYPE_DIRECT_REPLY,
            VOICE_SESSION_TYPE_TRANSCRIPTION,
            VOICE_SESSION_TYPE_START_TRANSCRIPTION
        )

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            HookChains.findAndHookMethod(xposed, kxe, "F", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                    if (micDictationActive || inMapsMicFromHeader()) {
                        ModuleLog.gearhead("GH-MIC-001", "kxe.F($trigger) mic passthrough", always = true)
                        return
                    }
                    if (!inMapsSearchVoiceBlock()) return
                    ModuleLog.gearhead(
                        "GH-MAPS-001",
                        "kxe.F($trigger) blocked — Maps search keyboard",
                        always = true
                    )
                    param.result = null
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked kxe.F Maps voice block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.F Maps voice block: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val bundle = android.os.Bundle::class.java
            HookChains.findAndHookMethod(xposed, kxe, "G", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                    if (micDictationActive || inMapsMicFromHeader()) return
                    ModuleLog.gearhead("GH-MAPS-001", "kxe.G($trigger) blocked Maps voice session", always = true)
                    param.result = null
                }
            }, Int::class.javaPrimitiveType!!, bundle)
            log("Hooked kxe.G Maps voice block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.G Maps voice block: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val qjr = findGearheadClass(ctx.classLoader, "qjr")
            HookChains.findAndHookMethod(xposed, kxe, "O", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (!inMapsSearchVoiceBlock()) return
                    if (micDictationActive) return
                    val callback = param.args[0] ?: return
                    if (!isJxaTranscriptionCallback(callback)) return
                    ModuleLog.gearhead(
                        "GH-MAPS-001",
                        "kxe.O(jxa) blocked during Maps search (no dictation)",
                        always = true
                    )
                    param.result = null
                }
            }, qjr)
            log("Hooked kxe.O Maps transcription block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.O Maps block: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val voiceSessionConfig = Reflect.findClass(
                "com.google.android.gearhead.sdk.assistant.VoiceSessionConfig",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, kxe, "ac", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val config = param.args[0] ?: return
                    val sessionType = Reflect.getIntField(config, "a")
                    val trigger = Reflect.getIntField(config, "f")
                    debugEntry("kxe.ac() type=$sessionType trigger=$trigger")
                    val mapsTapBlock = inMapsSearchVoiceBlock()
                    if (micDictationActive || inMapsMicFromHeader()) return
                    if (mapsTapBlock && sessionType in mapsVoiceTypes) {
                        ModuleLog.gearhead(
                            "GH-MAPS-001",
                            "kxe.ac blocked Maps voice/transcription type=$sessionType trigger=$trigger",
                            always = true
                        )
                        param.result = null
                    } else if (trigger == VOICE_SEARCH_TRIGGER_MAPS && sessionType in mapsVoiceTypes) {
                        ModuleLog.gearhead(
                            "GH-MAPS-001",
                            "kxe.ac blocked Maps voice/transcription type=$sessionType trigger=$trigger",
                            always = true
                        )
                        param.result = null
                    }
                }

                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.throwable != null) return
                    val config = param.args[0] ?: return
                    val sessionType = Reflect.getIntField(config, "a")
                    val trigger = Reflect.getIntField(config, "f")
                    if (trigger == VOICE_SEARCH_TRIGGER_MAPS && sessionType in mapsVoiceTypes) return
                    if (sessionType == VOICE_SESSION_TYPE_START_TRANSCRIPTION) {
                        ModuleLog.gearhead(
                            "GH-MAPS-003",
                            "kxe.ac type=$sessionType transcription session started",
                            always = true
                        )
                    }
                }
            }, voiceSessionConfig)
            log("Hooked kxe.ac Maps voice block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.ac Maps voice block: ${it.message}") }
    }

    private fun markMapsSearchVoiceBlock() {
        mapsSearchBlockUntilMs = System.currentTimeMillis() + 5000L
    }

    private fun inMapsSearchVoiceBlock(): Boolean {
        return System.currentTimeMillis() < mapsSearchBlockUntilMs
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        Handler(Looper.getMainLooper()).post(block)
    }

    /** Maps search tap: stock IME attempts, then custom overlay when keyboard is not verified on car display. */
    private fun openMapsSearchKeyboardInGearhead(classLoader: ClassLoader) {
        val stockVerified = openProjectedImeKeyboard(classLoader)
        if (!stockVerified) {
            openKxeQwertyDirect(classLoader)
            lastSearchGxy?.get()?.let { gxy ->
                val hgy = Reflect.getObjectField(gxy, "d")
                if (hgy != null) openCarAppQwertyKeyboard(hgy, classLoader)
            } ?: ModuleLog.gearhead("GH-MAPS-004", "no cached gxy in this gearhead process", always = true)
            sendMapsKeyboardBroadcast(classLoader, KeyboardBridge.ACTION_OPEN_MAPS_KEYBOARD, "OPEN_MAPS_KEYBOARD")
        }
        if (stockVerified) {
            ModuleLog.gearhead("GH-KBD-002", "stock projected keyboard verified — skip overlay", always = true)
            return
        }
        closeMapsVoiceDemand(classLoader)
        ModuleLog.gearhead(
            "GH-KBD-001",
            "keyboard overlay delegated to Maps (gearhead cannot draw on GhostActivityDisplay)",
            always = true
        )
    }

    /** Dismiss assistant demand-space voice UI before showing the typing overlay. */
    private fun closeMapsVoiceDemand(classLoader: ClassLoader) {
        runCatching {
            val kcw = findGearheadClass(classLoader, "kcw")
            val controller = Reflect.callStaticMethod(kcw, "l")
            val ywj = findGearheadClass(classLoader, "ywj")
            val interrupted = Reflect.getStaticObjectField(ywj, "INTERRUPTED")
            Reflect.callMethod(controller, "n", interrupted)
            ModuleLog.gearhead("GH-MAPS-003", "closed demand-space voice for keyboard overlay", always = true)
        }.onFailure {
            ModuleLog.gearhead("GH-MAPS-004", "close demand voice failed: ${it.message}", always = true)
        }
    }

    private fun resolveGearheadContext(classLoader: ClassLoader): Context? {
        return runCatching {
            val kcw = findGearheadClass(classLoader, "kcw")
            val controller = Reflect.callStaticMethod(kcw, "l")
            Reflect.getObjectField(controller, "d") as Context
        }.getOrNull() ?: runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            Reflect.callStaticMethod(atClass, "currentApplication") as Context
        }.getOrNull()
    }

    /** kcw.l().O(qjo) — QWERTY in the gearhead process handling the tap (no gxy cache needed). */
    private fun openKxeQwertyDirect(classLoader: ClassLoader): Boolean {
        return runCatching {
            val kcw = findGearheadClass(classLoader, "kcw")
            val kxe = Reflect.callStaticMethod(kcw, "l")
                ?: throw IllegalStateException("kcw.l() returned null")
            val view = findGearheadInputView(kxe) ?: throw IllegalStateException("no gearhead input view")
            val editorInfo = EditorInfo().apply {
                packageName = "com.google.android.apps.maps"
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val connection = view.onCreateInputConnection(editorInfo)
                ?: BaseInputConnection(view, true)
            val qjoClass = findGearheadClass(classLoader, "qjo")
            val callback = Reflect.newInstance(qjoClass, connection, Runnable {})
            markMapsSearchVoiceBlock()
            ModuleLog.gearhead("GH-MAPS-002", "attempt kxe.O(qjo) direct QWERTY", always = true)
            Reflect.callMethod(kxe, "O", callback)
            ModuleLog.gearhead("GH-MAPS-003", "kxe.O(qjo) invoked", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead(
                "GH-MAPS-004",
                "kxe.O(qjo) failed: ${it.javaClass.simpleName}: ${it.message}",
                always = true
            )
            false
        }
    }

    private fun findGearheadInputView(kxe: Any): View? {
        runCatching {
            val context = Reflect.getObjectField(kxe, "d") as? Context
            (context as? Activity)?.window?.decorView?.let { return it }
        }
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(at, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") as? Activity ?: continue
                activity.window?.decorView?.let { return it }
            }
        }
        return null
    }

    private fun isJxaTranscriptionCallback(callback: Any): Boolean {
        val name = callback.javaClass.name
        return name.endsWith(".jxa") || name.contains("jxa")
    }

    /** hgy.a(InputConnection) → kxe.O(qjo) — real QWERTY, not jxa dictation. */
    private fun openCarAppQwertyKeyboard(hgy: Any, classLoader: ClassLoader): Boolean {
        if (Reflect.callMethod(hgy, "b") != true) {
            ModuleLog.gearhead("GH-MAPS-004", "hgy.b() false — Car App keyboard gated", always = true)
            return false
        }
        val hostView = findCarAppHostView(lastSearchGxy?.get()) ?: run {
            ModuleLog.gearhead("GH-MAPS-004", "no Car App host view for InputConnection", always = true)
            return false
        }
        return runCatching {
            val editorInfo = EditorInfo().apply {
                packageName = "com.google.android.apps.maps"
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val connection = hostView.onCreateInputConnection(editorInfo)
                ?: BaseInputConnection(hostView, true)
            val refresh = Runnable {
                runCatching {
                    lastSearchGxy?.get()?.let { gxy ->
                        Reflect.callMethod(gxy, "f")
                    }
                }
            }
            markMapsSearchVoiceBlock()
            ModuleLog.gearhead("GH-MAPS-002", "attempt hgy.a(qjo) Car App QWERTY", always = true)
            Reflect.callMethod(hgy, "a", connection, refresh)
            ModuleLog.gearhead("GH-MAPS-003", "hgy.a(qjo) invoked", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead(
                "GH-MAPS-004",
                "hgy.a(qjo) failed: ${it.javaClass.simpleName}: ${it.message}",
                always = true
            )
            false
        }
    }

    private fun findCarAppHostView(gxy: Any?): View? {
        if (gxy == null) return null
        val gdi = Reflect.getObjectField(gxy, "b") ?: return null
        runCatching {
            val ctx = Reflect.callMethod(gdi, "a") as? Context
            val activity = ctx as? Activity
            val decor = activity?.window?.decorView
            if (decor != null) return decor
        }
        runCatching {
            val base = gdi as? Context
            val activity = base as? Activity
            val decor = activity?.window?.decorView
            if (decor != null) return decor
        }
        return null
    }

    private fun requestPrepareMapsKeyboardBroadcast(classLoader: ClassLoader): Boolean {
        return sendMapsKeyboardBroadcast(classLoader, KeyboardBridge.ACTION_PREPARE_MAPS_IME, "PREPARE_MAPS_IME")
    }

    private fun requestMapsKeyboardBroadcast(classLoader: ClassLoader): Boolean {
        return sendMapsKeyboardBroadcast(classLoader, KeyboardBridge.ACTION_OPEN_MAPS_KEYBOARD, "OPEN_MAPS_KEYBOARD")
    }

    private fun sendMapsKeyboardBroadcast(classLoader: ClassLoader, action: String, label: String): Boolean {
        return runCatching {
            val kcw = findGearheadClass(classLoader, "kcw")
            val controller = Reflect.callStaticMethod(kcw, "l")
            val context = Reflect.getObjectField(controller, "d") as Context
            val intent = Intent(action).setPackage("com.google.android.apps.maps")
            context.sendBroadcast(intent)
            ModuleLog.gearhead("GH-MAPS-002", "broadcast $label to Maps", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "Maps broadcast $label failed: ${it.message}", always = true)
            false
        }
    }

    private fun openProjectedImeKeyboard(classLoader: ClassLoader): Boolean {
        val ime = activeImeService?.get() ?: findRunningXcuService(classLoader)
        if (ime != null) {
            activeImeService = WeakReference(ime)
        }
        val xdbShown = when {
            activeInputFragment?.get() != null -> openProjectedImeViaXdb()
            else -> tryOpenProjectionFragmentFromIme(classLoader, ime)
        }
        val phoneKeyboard = if (ime != null) openProjectedImeViaXcu(classLoader, ime) else false
        if (xdbShown || phoneKeyboard) return true
        if (ime != null || activeInputFragment?.get() != null) {
            ModuleLog.gearhead("GH-MAPS-004", "projected IME present but did not open", always = true)
        } else {
            ModuleLog.gearhead("GH-MAPS-004", "projected IME unavailable — no active xcu/xdb (bind snp.j first)", always = true)
        }
        return false
    }

    /** Multi-PID trap: xcu may be running without a cache hit in this process. */
    private fun findRunningXcuService(classLoader: ClassLoader): Any? {
        return runCatching {
            val xcuClass = findGearheadClass(classLoader, "xcu")
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val services = Reflect.getObjectField(at, "mServices") as? Map<*, *> ?: return null
            for (record in services.values) {
                val service = Reflect.getObjectField(record, "service") ?: continue
                if (xcuClass.isInstance(service)) {
                    ModuleLog.gearhead(
                        "GH-MAPS-000",
                        "discovered running xcu service via ActivityThread scan",
                        always = true
                    )
                    return service
                }
            }
            null
        }.getOrNull()
    }

    private fun openProjectedImeViaXcu(classLoader: ClassLoader, ime: Any? = null): Boolean {
        val resolved = ime ?: activeImeService?.get() ?: return false
        return runCatching {
            ModuleLog.gearhead("GH-MAPS-002", "attempt projected IME xcu.h()", always = true)
            prepareXcuForExternalKeyboard(resolved)
            Reflect.callMethod(resolved, "h")
            if (isProjectedKeyboardStarted(resolved)) {
                ModuleLog.gearhead("GH-MAPS-003", "xcu.h() started PhoneKeyboardActivity", always = true)
                true
            } else {
                // Do not force-start PhoneKeyboardActivity — it flashes on the phone display and
                // never verifies on the car virtual display; Maps GhostActivity overlay handles typing.
                ModuleLog.gearhead(
                    "GH-MAPS-004",
                    "xcu.h() did not verify keyboard — skip PhoneKeyboardActivity (Maps overlay next)",
                    always = true
                )
                false
            }
        }.getOrElse {
            ModuleLog.gearhead(
                "GH-MAPS-004",
                "xcu.h failed: ${it.javaClass.simpleName}: ${it.message} — skip PhoneKeyboardActivity",
                always = true
            )
            false
        }
    }

    private fun isProjectedKeyboardStarted(ime: Any): Boolean {
        return runCatching { Reflect.getObjectField(ime, "q") != null }.getOrDefault(false)
    }

    private fun tryOpenProjectionFragmentFromIme(classLoader: ClassLoader, ime: Any?): Boolean {
        val resolved = ime ?: return false
        val xdm = findGearheadClass(classLoader, "xdm")
        if (!xdm.isInstance(resolved)) return false
        return runCatching {
            val fragment = Reflect.callMethod(resolved, "e") ?: return false
            activeInputFragment = WeakReference(fragment)
            openProjectedImeViaXdb()
        }.getOrDefault(false)
    }

    private fun prepareXcuForExternalKeyboard(ime: Any) {
        runCatching {
            val alreadyRunning = Reflect.getObjectField(ime, "q")
            if (Reflect.getBooleanField(ime, "k") && alreadyRunning == null) {
                Reflect.setBooleanField(ime, "k", false)
            }
            Reflect.setBooleanField(ime, "l", true)
            val editorInfo = Reflect.getObjectField(ime, "h") as? EditorInfo
                ?: EditorInfo().apply {
                    packageName = "com.google.android.apps.maps"
                    inputType = InputType.TYPE_CLASS_TEXT
                    hintText = "Search"
                }
            if (Reflect.getObjectField(ime, "h") == null) {
                Reflect.setObjectField(ime, "h", editorInfo)
            }
            runCatching {
                val xdb = Reflect.callMethod(ime, "f")
                if (xdb != null) {
                    Reflect.setBooleanField(xdb, "c", false)
                }
            }
        }
    }

    /** Bypass xcu.h() gates when bind exists but stock h() returns early (Fermata / Voice Plate). */
    private fun forceStartPhoneKeyboardActivity(ime: Any, classLoader: ClassLoader): Boolean {
        return runCatching {
            if (ime !is Service) return false
            prepareXcuForExternalKeyboard(ime)
            val editorInfo = Reflect.getObjectField(ime, "h") as EditorInfo
            val phoneKb = Reflect.findClass(
                "com.google.android.apps.auto.components.externalkeyboard.phone.PhoneKeyboardActivity",
                classLoader
            )
            val clientId = Reflect.getIntField(ime, "m") + 1
            Reflect.setIntField(ime, "m", clientId)
            val intent = Intent(ime, phoneKb).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("IMEClass", ime.javaClass.name)
                putExtra("BinderClientId", clientId)
                putExtra("ImeOptions", editorInfo.imeOptions)
                putExtra("ImeHint", editorInfo.hintText?.toString() ?: "")
            }
            val lfe = findGearheadClass(classLoader, "lfe")
            val launchOpts = Reflect.callStaticMethod(lfe, "b") as android.os.Bundle
            ime.startActivity(intent, launchOpts)
            ModuleLog.gearhead("GH-MAPS-003", "force-started PhoneKeyboardActivity (car display)", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "force PhoneKeyboardActivity failed: ${it.message}", always = true)
            false
        }
    }

    private fun openProjectedImeViaXdl(classLoader: ClassLoader): Boolean {
        return runCatching {
            val xdm = findGearheadClass(classLoader, "xdm")
            val config = Reflect.callMethod(xdm, "e")
                ?: Reflect.newInstance(findGearheadClass(classLoader, "xdl"))
            Reflect.setBooleanField(config, "c", false)
            Reflect.callMethod(config, "d")
            ModuleLog.gearhead("GH-MAPS-003", "xdl/xdu.d() projection keyboard", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "xdl.d failed: ${it.javaClass.simpleName}: ${it.message}", always = true)
            false
        }
    }

    private fun openProjectedImeViaXdb(): Boolean {
        return runCatching {
            ModuleLog.gearhead("GH-MAPS-002", "attempt projected IME xdb.d() unlock", always = true)
            val fragment = activeInputFragment?.get()
                ?: throw IllegalStateException("no cached xdb fragment")
            val wasLocked = Reflect.getBooleanField(fragment, "c")
            if (wasLocked) {
                Reflect.setBooleanField(fragment, "c", false)
            }
            Reflect.callMethod(fragment, "d")
            val unlocked = !Reflect.getBooleanField(fragment, "c")
            if (unlocked) {
                ModuleLog.gearhead("GH-MAPS-003", "xdb.d() projection keyboard shown", always = true)
                true
            } else {
                ModuleLog.gearhead("GH-MAPS-004", "xdb.d() did not unlock projection keyboard", always = true)
                false
            }
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "xdb.d failed: ${it.javaClass.simpleName}: ${it.message}", always = true)
            false
        }
    }

    private fun hookHintStringRewrites(ctx: HookContext) {
        runCatching {
            val gbh = findGearheadClass(ctx.classLoader, "gbh")
            HookChains.findAndHookMethod(xposed, gbh, "a", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val original = param.args[0] as? String ?: return
                    val rewritten = VoicePlateHints.rewriteIfVoiceOnly(original) ?: return
                    ModuleLog.gearhead(
                        "GH-HINT-001",
                        "gbh.a rewritten \"${original.take(40)}\" -> \"$rewritten\"",
                        always = true
                    )
                    param.args[0] = rewritten
                }
            }, String::class.java)
            log("Hooked gbh.a (${gbh.name})")
        }.onFailure { log("Failed to hook gbh.a: ${it.message}") }

        runCatching {
            val gdm = findGearheadClass(ctx.classLoader, "gdm")
            HookChains.findAndHookMethod(xposed, gdm, "b", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val original = param.args[0] as? String ?: return
                    val rewritten = VoicePlateHints.rewriteIfVoiceOnly(original) ?: return
                    ModuleLog.gearhead(
                        "GH-HINT-001",
                        "gdm.b rewritten \"${original.take(40)}\" -> \"$rewritten\"",
                        always = true
                    )
                    param.args[0] = rewritten
                }
            }, String::class.java)
            log("Hooked gdm.b (${gdm.name})")
        }.onFailure { log("Failed to hook gdm.b: ${it.message}") }

        runCatching {
            val carText = Reflect.findClass("androidx.car.app.model.CarText", ctx.classLoader)
            for (method in carText.declaredMethods) {
                if (method.name != "create") continue
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        for (i in param.args.indices) {
                            val arg = param.args[i] ?: continue
                            val text = when (arg) {
                                is CharSequence -> arg.toString()
                                is String -> arg
                                else -> VoicePlateHints.extractHintText(arg)
                            } ?: continue
                            val rewritten = VoicePlateHints.rewriteIfVoiceOnly(text) ?: continue
                            param.args[i] = rewritten
                            ModuleLog.gearhead(
                                "GH-HINT-001",
                                "CarText.create rewritten \"${text.take(40)}\" -> \"$rewritten\"",
                                always = true
                            )
                        }
                    }
                })
            }
            log("Hooked CarText.create")
        }.onFailure { log("Failed to hook CarText.create: ${it.message}") }

        runCatching {
            HookChains.findAndHookMethod(xposed, android.content.res.Resources::class.java, "getString", object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val original = param.result as? String ?: return
                        val rewritten = VoicePlateHints.rewriteIfVoiceOnly(original) ?: return
                        param.result = rewritten
                        ModuleLog.gearhead(
                            "GH-HINT-001",
                            "Resources.getString rewritten \"${original.take(40)}\" -> \"$rewritten\"",
                            always = true
                        )
                    }
                }, Int::class.javaPrimitiveType!!)
            log("Hooked Resources.getString hint rewrite")
        }.onFailure { log("Failed to hook Resources.getString: ${it.message}") }
    }

    private fun patchSearchTemplateHint(result: Any?, gbh: Class<*>, @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        if (result == null) return
        runCatching {
            val gan = Reflect.getObjectField(result, "c") ?: return
            val hintObj = Reflect.getObjectField(gan, "b") ?: return
            val original = runCatching {
                Reflect.callMethod(hintObj, "toCharSequence") as? CharSequence
            }.getOrNull()?.toString() ?: hintObj.toString()
            val rewritten = VoicePlateHints.rewriteIfVoiceOnly(original) ?: return
            Reflect.setObjectField(
                gan,
                "b",
                Reflect.callStaticMethod(gbh, "a", rewritten)
            )
            ModuleLog.gearhead(
                "GH-HINT-001",
                "gxy.d hint rewritten \"${original.take(40)}\" -> \"$rewritten\"",
                always = true
            )
        }.onFailure {
            debug("gxy.d hint patch failed: ${it.message}")
        }
    }

    private fun hookVoicePlateAndAssistant(ctx: HookContext) {
        hookVoicePlateWidget(ctx)
        hookVoiceMicCapture(ctx)
        hookAssistantController(ctx)
    }

    private fun hookVoicePlateWidget(ctx: HookContext) {
        val carText = Reflect.findClass("androidx.car.app.model.CarText", ctx.classLoader)

        runCatching {
            val voicePlateWidget = Reflect.findClass(
                "com.android.car.libraries.apphost.external.model.widgets.VoicePlateWidget",
                ctx.classLoader
            )
            val action = Reflect.findClass("androidx.car.app.model.Action", ctx.classLoader)
            val carIcon = Reflect.findClass("androidx.car.app.model.CarIcon", ctx.classLoader)
            HookChains.findAndHookConstructor(xposed,
                voicePlateWidget,
                object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("VoicePlateWidget.<init>()")
                        rewritePlaceholderArg(param, 2, ctx.classLoader, carText)
                        rewriteVoicePlateMicIconArg(param, 1, ctx.classLoader)
                    }
                },
                action,
                carIcon,
                carText
            )
            HookChains.findAndHookMethod(xposed, voicePlateWidget, "getPlaceholderText", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val placeholder = param.result ?: return
                    if (!VoicePlateHints.isVoiceOnlyHint(placeholder)) return
                    debug("VoicePlateWidget.getPlaceholderText() rewritten")
                    param.result = VoicePlateHints.createCarText(ctx.classLoader)
                }
            })
            HookChains.findAndHookMethod(xposed, voicePlateWidget, "getIcon", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val icon = param.result ?: return
                    val replaced = VoicePlateMicIcon.replaceMicCarIcon(
                        ctx.classLoader,
                        resolveGearheadContext(ctx.classLoader),
                        icon
                    )
                    if (replaced !== icon) {
                        param.result = replaced
                    }
                }
            })
            log("Hooked VoicePlateWidget constructor + getPlaceholderText + getIcon")
        }.onFailure { log("Failed to hook VoicePlateWidget: ${it.message}") }

        runCatching {
            val fuw = findGearheadClass(ctx.classLoader, "fuw")
            val fyt = findGearheadClass(ctx.classLoader, "fyt")
            val hjq = findGearheadClass(ctx.classLoader, "hjq")
            HookChains.findAndHookConstructor(xposed, hjq,
                object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("hjq.<init>()")
                    rewritePlaceholderArg(param, 2, ctx.classLoader, carText)
                    rewriteVoicePlateMicFytArg(param, 1, ctx.classLoader)
                }
            }, fuw, fyt, carText)
            log("Hooked hjq constructor (${hjq.name})")
        }.onFailure { log("Failed to hook hjq: ${it.message}") }

        runCatching {
            val fvj = findGearheadClass(ctx.classLoader, "fvj")
            val afes = findGearheadClass(ctx.classLoader, "afes")
            val fyh = findGearheadClass(ctx.classLoader, "fyh")
            val gbg = findGearheadClass(ctx.classLoader, "gbg")
            val gbh = findGearheadClass(ctx.classLoader, "gbh")
            val hdx = findGearheadClass(ctx.classLoader, "hdx")
            val hjv = findGearheadClass(ctx.classLoader, "hjv")
            HookChains.findAndHookConstructor(xposed,
                hjv,
                object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val state = param.args[4] as Int
                        debugEntry("hjv.<init>() transcriptionState=$state")
                        if (state == TRANSCRIPTION_ACTIVE) {
                            param.args[4] = TRANSCRIPTION_INACTIVE
                            debug("hjv transcriptionState forced INACTIVE (keyboard)")
                        }
                        val text = param.args[3]
                        if (text != null && VoicePlateHints.isVoiceOnlyHint(text)) {
                            param.args[3] = Reflect.callStaticMethod(
                                gbh,
                                "a",
                                VoicePlateHints.MAPS_HOOK_BANNER
                            )
                            ModuleLog.gearhead(
                                "GH-HINT-001",
                                "hjv text rewritten \"${VoicePlateHints.extractHintText(text)?.take(40)}\" -> banner",
                                always = true
                            )
                        }
                        rewriteVoicePlateMicFyhArg(param, 2, ctx.classLoader)
                    }
                },
                fvj,
                afes,
                fyh,
                gbg,
                Int::class.javaPrimitiveType!!,
                hdx,
                Boolean::class.javaPrimitiveType!!
            )
            log("Hooked hjv constructor (${hjv.name})")
        }.onFailure { log("Failed to hook hjv: ${it.message}") }
    }

    private fun rewriteVoicePlateMicIconArg(
        param: HookParam,
        index: Int,
        classLoader: ClassLoader
    ) {
        if (param.args[index] == null) return
        param.args[index] = VoicePlateMicIcon.replaceMicCarIcon(
            classLoader,
            resolveGearheadContext(classLoader),
            param.args[index]
        )
    }

    private fun rewriteVoicePlateMicFytArg(
        param: HookParam,
        index: Int,
        classLoader: ClassLoader
    ) {
        if (param.args[index] == null) return
        param.args[index] = VoicePlateMicIcon.replaceMicFyt(
            classLoader,
            resolveGearheadContext(classLoader),
            param.args[index]
        )
    }

    private fun rewriteVoicePlateMicFyhArg(
        param: HookParam,
        index: Int,
        classLoader: ClassLoader
    ) {
        if (param.args[index] == null) return
        param.args[index] = VoicePlateMicIcon.replaceMicFyh(
            classLoader,
            resolveGearheadContext(classLoader),
            param.args[index]
        )
    }

    private fun rewritePlaceholderArg(
        param: HookParam,
        index: Int,
        classLoader: ClassLoader,
        carText: Class<*>
    ) {
        val placeholder = param.args[index] ?: return
        val text = runCatching {
            Reflect.callMethod(placeholder, "toCharSequence") as? CharSequence
        }.getOrNull()?.toString()
        if (text != null) {
            debug("VoicePlate placeholder: \"$text\"")
        }
        if (!VoicePlateHints.isVoiceOnlyHint(placeholder)) return
        param.args[index] = VoicePlateHints.createCarText(classLoader)
        ModuleLog.gearhead("GH-HINT-001", "VoicePlate placeholder rewritten to banner", always = true)
    }

    private fun hookAssistantController(ctx: HookContext) {
        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val qjr = findGearheadClass(ctx.classLoader, "qjr")
            HookChains.findAndHookMethod(xposed, kxe, "O", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("kxe.O() startTranscription")
                }
            }, qjr)
            log("Hooked kxe.O (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.O: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val voiceSessionConfig = Reflect.findClass(
                "com.google.android.gearhead.sdk.assistant.VoiceSessionConfig",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, kxe, "ac", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val config = param.args[0] ?: return
                    val sessionType = Reflect.getIntField(config, "a")
                    val trigger = Reflect.getIntField(config, "f")
                    debugEntry("kxe.ac() type=$sessionType trigger=$trigger")
                    if (sessionType in VOICE_BLOCK_SESSION_TYPES) {
                        debug("kxe.ac() blocked voice/dictation type=$sessionType trigger=$trigger")
                        param.result = null
                    }
                }
            }, voiceSessionConfig)
            log("Hooked kxe.ac (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.ac: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val messagingInfo = Reflect.findClass(
                "com.google.android.gearhead.sdk.assistant.MessagingInfo",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, kxe, "aa", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val type = param.args[1] as Int
                        debugEntry("kxe.aa() type=$type")
                        if (type == VOICE_SESSION_TYPE_DIRECT_REPLY) {
                            debug("kxe.aa() direct reply proceeds; mic blocked via kwt/GhMicrophone")
                        }
                    }
                }, messagingInfo, Int::class.javaPrimitiveType!!)
            HookChains.findAndHookMethod(xposed, kxe, "t", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("kxe.t() notification direct reply")
                }
            }, messagingInfo)
            log("Hooked kxe.aa/t (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.aa/t: ${it.message}") }

        runCatching {
            val kvl = findGearheadClass(ctx.classLoader, "kvl")
            val kcw = findGearheadClass(ctx.classLoader, "kcw")
            HookChains.findAndHookMethod(xposed, kcw, "k", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[1] as Int
                        debugEntry("kcw.k() trigger=$trigger")
                        if (trigger == VOICE_SEARCH_TRIGGER_MAPS) {
                            debug("kcw.k($trigger) voice search (Maps keyboard uses qhf.l/snp.k path)")
                        }
                    }
                }, kvl, Int::class.javaPrimitiveType!!)
            log("Hooked kcw.k (${kcw.name})")
        }.onFailure { log("Failed to hook kcw.k: ${it.message}") }

        runCatching {
            val qib = findGearheadClass(ctx.classLoader, "qib")
            HookChains.findAndHookMethod(xposed, qib, "l", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("kvl.l() trigger=${param.args[0]}")
                    }
                }, Int::class.javaPrimitiveType!!)
            log("Hooked kvl.l (${qib.name})")
        }.onFailure { log("Failed to hook kvl.l: ${it.message}") }
    }

    private fun hookLgzImplementors(
        ctx: HookContext,
        lgz: Class<*>,
        outerShortName: String
    ) {
        val outer = findGearheadClass(ctx.classLoader, outerShortName)
        val hook = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (param.args[0] == false) {
                    debug("lgz.a() forced true in ${param.thisObject?.javaClass?.name}")
                    param.args[0] = true
                }
            }
        }
        for (inner in outer.declaredClasses) {
            if (lgz.isAssignableFrom(inner)) {
                runCatching {
                    HookChains.findAndHookMethod(xposed, inner, "a", hook, Boolean::class.javaPrimitiveType!!)
                }
            }
        }
    }

    private fun hookTemplateHintMethod(
        ctx: HookContext,
        className: String,
        methodName: String,
        paramType: Class<*>
    ) {
        runCatching {
            val clazz = findGearheadClass(ctx.classLoader, className)
            HookChains.findAndHookMethod(xposed, clazz, methodName, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    debugEntry("$className.$methodName()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] == false) {
                        debug("$className.$methodName() forced true (keyboard hint)")
                        param.args[0] = true
                    }
                }
            }, paramType)
            log("Hooked $className.$methodName (${clazz.name})")
        }.onFailure { log("Failed to hook $className.$methodName: ${it.message}") }
    }

    private fun hookTemplateKeyboardMethod(
        ctx: HookContext,
        className: String,
        methodName: String,
        paramType: Class<*>
    ) {
        runCatching {
            val clazz = findGearheadClass(ctx.classLoader, className)
            HookChains.findAndHookMethod(xposed, clazz, methodName, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    debugEntry("$className.$methodName()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] == false) {
                        debug("$className.$methodName() forced true (keyboard shown)")
                        param.args[0] = true
                    }
                }
            }, paramType)
            log("Hooked $className.$methodName (${clazz.name})")
        }.onFailure { log("Failed to hook $className.$methodName: ${it.message}") }
    }

    private fun hookInputMethodFragment(ctx: HookContext) {
        val unlockHook = object : MethodHook() {
            override fun beforeHookedMethod(param: HookParam) {
                if (!ModulePrefs.isEnabled()) return
                val name = param.thisObject?.javaClass?.simpleName ?: return
                debugEntry("$name.d()")
                val locked = Reflect.getBooleanField(param.thisObject, "c")
                Reflect.setBooleanField(param.thisObject, "c", false)
                if (locked) {
                    debug("$name.d() forced c=false (keyboard unlock)")
                }
            }
        }

        runCatching {
            val xdb = findGearheadClass(ctx.classLoader, "xdb")
            HookChains.findAndHookMethod(xposed, xdb, "onStart", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    activeInputFragment = WeakReference(param.thisObject)
                    debugEntry("xdb.onStart()")
                    val locked = Reflect.getBooleanField(param.thisObject, "c")
                    Reflect.setBooleanField(param.thisObject, "c", false)
                    if (locked) {
                        debug("xdb.onStart() forced c=false before d()")
                    }
                    Reflect.callMethod(param.thisObject, "d")
                }
            })
            log("Hooked xdb.onStart (${xdb.name})")
        }.onFailure { log("Failed to hook xdb.onStart: ${it.message}") }

        for (shortName in listOf("xdl", "xdu")) {
            runCatching {
                val clazz = findGearheadClass(ctx.classLoader, shortName)
                if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
                    log("Skipping $shortName.d (abstract/interface)")
                    return@runCatching
                }
                HookChains.findAndHookMethod(xposed, clazz, "d", unlockHook)
                log("Hooked $shortName.d (${clazz.name})")
            }.onFailure { log("Failed to hook $shortName.d: ${it.message}") }
        }

        runCatching {
            val xdu = findGearheadClass(ctx.classLoader, "xdu")
            HookChains.findAndHookMethod(xposed, xdu, "k", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result == true) {
                        debug("xdu.k() forced false (rotary lockout bypass)")
                        param.result = false
                    }
                }
            })
            log("Hooked xdu.k (${xdu.name})")
        }.onFailure { log("Failed to hook xdu.k: ${it.message}") }

        runCatching {
            val xcu = findGearheadClass(ctx.classLoader, "xcu")
            HookChains.findAndHookMethod(xposed, xcu, "h", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("xcu.h() maybeStartExternalKeyboard")
                }
            })
            log("Hooked xcu.h (${xcu.name})")
        }.onFailure { log("Failed to hook xcu.h: ${it.message}") }

        // xdm.e() returns null when npz reports invalid input config — NPE in xcu.c without this.
        runCatching {
            val xdm = findGearheadClass(ctx.classLoader, "xdm")
            HookChains.findAndHookMethod(xposed, xdm, "e", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result == null) {
                        log("xdm.e() was null — falling back to xdl")
                        val xdl = findGearheadClass(ctx.classLoader, "xdl")
                        param.result = Reflect.newInstance(xdl)
                    }
                }
            })
            log("Hooked xdm.e (${xdm.name})")
        }.onFailure { log("Failed to hook xdm.e: ${it.message}") }
    }

    private fun debugEntry(message: String) {
        if (ModulePrefs.isDebug()) {
            log(">> $message")
        }
    }

    private fun debug(message: String) {
        if (ModulePrefs.isDebug()) {
            log(message)
        }
    }

    private fun log(message: String) {
        ModuleLog.gearhead("HOOK", message, always = true)
    }
}
