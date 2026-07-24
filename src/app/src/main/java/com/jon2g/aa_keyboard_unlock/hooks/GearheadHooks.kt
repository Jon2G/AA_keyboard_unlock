package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import com.jon2g.aa_keyboard_unlock.BuildConfig
import com.jon2g.aa_keyboard_unlock.MapsNativeIme
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import com.jon2g.aa_keyboard_unlock.xposed.HookChains
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.HookParam
import com.jon2g.aa_keyboard_unlock.xposed.MethodHook
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Gearhead hooks: spoof parked/driving sensors, unlock stock projection IME, and
 * block Maps search voice so the native projected QWERTY can open (no custom overlay).
 */
object GearheadHooks {
    private const val SENSOR_TYPE_CAR_SPEED = 2
    private const val SENSOR_TYPE_DRIVING_STATUS = 11
    private const val LHA_FIELD_CAR_PARKED = "b"
    private const val VOICE_SEARCH_TRIGGER_MAPS = 10
    private const val VOICE_SESSION_TYPE_VOICE = 1
    private const val VOICE_SESSION_TYPE_DIRECT_REPLY = 2
    private const val VOICE_SESSION_TYPE_START_TRANSCRIPTION = 3
    private const val VOICE_SESSION_TYPE_TRANSCRIPTION = 6

    @Volatile
    private lateinit var xposed: XposedInterface

    @Volatile
    private var installedForProcess = false

    @Volatile
    private var targets: GearheadSignatureDiscovery.DiscoveredTargets =
        GearheadSignatureDiscovery.DiscoveredTargets()

    @Volatile
    private var activeInputFragment: WeakReference<Any>? = null

    @Volatile
    private var activeImeService: WeakReference<Any>? = null

    @Volatile
    private var mapsSearchBlockUntilMs = 0L

    @Volatile
    private var mapsMicUntilMs = 0L

    @Volatile
    private var micDictationActive = false

    fun install(ctx: HookContext) {
        if (installedForProcess) return
        installedForProcess = true
        xposed = ctx.xposed
        installHooks(ctx)
    }

    private fun installHooks(ctx: HookContext) {
        ModuleLog.install(
            ModuleLog.Process.GH,
            "enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()} " +
                "pref=${ModulePrefs.lastPrefSource} build=${BuildConfig.BUILD_TYPE} pkg=${ctx.packageName}"
        )
        targets = GearheadSignatureDiscovery.discover(ctx)
        hookSensorCallbacks(ctx)
        hookLocationManager(ctx)
        hookInputMethodFragment(ctx)
        hookParkingAndAssistantSettings(ctx)
        hookCarUiConstraints(ctx)
        hookCarAppKeyboardGate(ctx)
        hookMapsNativeSearchKeyboard(ctx)
        ModuleLog.gearhead(
            "GH-INSTALL",
            "hooks installed for ${ctx.packageName} discoveryCache=${targets.fromCache}",
            always = true
        )
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

    private fun hookSensorCallbacks(ctx: HookContext) {
        val methods = targets.sensorCallbacks
        if (methods.isNotEmpty()) {
            for (method in methods) {
                runCatching {
                    HookChains.hookMethod(xposed, method, sensorSpoofHook)
                    log("Hooked sensor ${method.declaringClass.name}.${method.name}")
                }.onFailure {
                    log("Failed sensor ${method.declaringClass.name}.${method.name}: ${it.message}")
                }
            }
            return
        }
        // Legacy short-name fallback (pre-discovery / cache miss)
        for (shortName in listOf("lhl", "lhv", "lhk", "lhu")) {
            runCatching {
                val clazz = findGearheadClass(ctx.classLoader, shortName)
                if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return@runCatching
                HookChains.hookAllMethods(xposed, clazz, "d", sensorSpoofHook)
                log("Hooked fallback $shortName.d (${clazz.name})")
            }.onFailure { log("Failed fallback $shortName.d: ${it.message}") }
        }
    }

    private fun hookLocationManager(ctx: HookContext) {
        val q = targets.locationKeyboardEnabled
        val s = targets.locationWheelSpeedNonZero
        val f = targets.locationSpeed
        if (q != null) {
            runCatching {
                HookChains.hookMethod(xposed, q, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != true) {
                            debug("loc.q() forced true (was ${param.result})")
                            param.result = true
                        }
                    }
                })
                log("Hooked location q (${q.declaringClass.name})")
            }.onFailure { log("Failed loc.q: ${it.message}") }
        } else {
            runCatching {
                val lhu = findGearheadClass(ctx.classLoader, "lhu")
                HookChains.findAndHookMethod(xposed, lhu, "q", object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != true) {
                            debug("lhu.q() forced true (was ${param.result})")
                            param.result = true
                        }
                    }
                })
                log("Hooked fallback lhu.q")
            }.onFailure { log("Failed fallback lhu.q: ${it.message}") }
        }

        if (s != null) {
            runCatching {
                HookChains.hookMethod(xposed, s, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != false) {
                            debug("loc.s() forced false (was ${param.result})")
                            param.result = false
                        }
                    }
                })
                log("Hooked location s (${s.declaringClass.name})")
            }.onFailure { log("Failed loc.s: ${it.message}") }
        } else {
            runCatching {
                val lhu = findGearheadClass(ctx.classLoader, "lhu")
                HookChains.findAndHookMethod(xposed, lhu, "s", object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != false) {
                            debug("lhu.s() forced false (was ${param.result})")
                            param.result = false
                        }
                    }
                })
                log("Hooked fallback lhu.s")
            }.onFailure { log("Failed fallback lhu.s: ${it.message}") }
        }

        if (f != null) {
            runCatching {
                HookChains.hookMethod(xposed, f, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val speed = param.result as? Float ?: return
                        if (speed != 0f) {
                            debug("loc.f() speed $speed -> 0")
                            param.result = 0f
                        }
                    }
                })
                log("Hooked location f (${f.declaringClass.name})")
            }.onFailure { log("Failed loc.f: ${it.message}") }
        }
    }

    private fun hookParkingAndAssistantSettings(ctx: HookContext) {
        val carParked = targets.carParkedValue()
        val parking = targets.locationParkingState
        if (parking != null && carParked != null) {
            runCatching {
                HookChains.hookMethod(xposed, parking, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != carParked) {
                            debug("loc.c() forced CAR_PARKED (was ${param.result})")
                            param.result = carParked
                        }
                    }
                })
                log("Hooked location parking c (${parking.declaringClass.name})")
            }.onFailure { log("Failed loc.c: ${it.message}") }
        } else {
            runCatching {
                val lhu = findGearheadClass(ctx.classLoader, "lhu")
                val lhb = findGearheadClass(ctx.classLoader, "lhb")
                val parked = Reflect.getStaticObjectField(lhb, LHA_FIELD_CAR_PARKED)
                HookChains.findAndHookMethod(xposed, lhu, "c", object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != parked) {
                            debug("lhu.c() forced CAR_PARKED (was ${param.result})")
                            param.result = parked
                        }
                    }
                })
                log("Hooked fallback lhu.c")
            }.onFailure { log("Failed fallback lhu.c: ${it.message}") }
        }

        targets.assistantKeyboardEnabled?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != true) {
                            debug("assist.a() forced true (was ${param.result})")
                            param.result = true
                        }
                    }
                })
                log("Hooked assistant keyboard a (${method.declaringClass.name})")
            }.onFailure { log("Failed assist.a: ${it.message}") }
        }

        // Force keyboard-enable callbacks (lha.a(boolean)) when discovered.
        targets.keyboardCallbackInterface?.let { iface ->
            runCatching {
                HookChains.hookAllMethods(xposed, iface, "a", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args.getOrNull(0) == false) {
                            debug("kbd callback a() forced true")
                            param.args[0] = true
                        }
                    }
                })
                log("Hooked keyboard callback interface ${iface.name}")
            }.onFailure { log("Failed kbd callback: ${it.message}") }
        }
    }

    private fun hookCarUiConstraints(ctx: HookContext) {
        val a = targets.carUiTouchscreen
        val b = targets.carUiTouchpad
        if (a != null || b != null) {
            for (method in listOfNotNull(a, b)) {
                runCatching {
                    HookChains.hookMethod(xposed, method, object : MethodHook() {
                        override fun beforeHookedMethod(param: HookParam) {
                            debugEntry("carUi.${method.name}()")
                        }

                        override fun afterHookedMethod(param: HookParam) {
                            if (!ModulePrefs.isEnabled()) return
                            if (param.result == true) {
                                debug("carUi.${method.name}() forced false (was true)")
                                param.result = false
                            }
                        }
                    })
                    log("Hooked carUi ${method.declaringClass.name}.${method.name}")
                }.onFailure { log("Failed carUi.${method.name}: ${it.message}") }
            }
            return
        }
        runCatching {
            val jqt = findGearheadClass(ctx.classLoader, "jqt")
            for (method in listOf("a", "b")) {
                HookChains.findAndHookMethod(xposed, jqt, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result == true) {
                            debug("jqt.$method() forced false (was true)")
                            param.result = false
                        }
                    }
                })
            }
            log("Hooked fallback jqt.a/b")
        }.onFailure { log("Failed fallback jqt: ${it.message}") }
    }

    private fun hookCarAppKeyboardGate(ctx: HookContext) {
        targets.carAppKeyboardBlocked?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        debugEntry("carApp.blocked()")
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result != false) {
                            debug("carApp.blocked() forced false (was ${param.result})")
                            param.result = false
                        }
                    }
                })
                log("Hooked carApp blocked (${method.declaringClass.name})")
            }.onFailure { log("Failed carApp blocked: ${it.message}") }
        } ?: runCatching {
            val juv = findGearheadClass(ctx.classLoader, "juv")
            HookChains.findAndHookMethod(xposed, juv, "b", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != false) {
                        debug("juv.b() forced false (was ${param.result})")
                        param.result = false
                    }
                }
            })
            log("Hooked fallback juv.b")
        }.onFailure { log("Failed fallback juv.b: ${it.message}") }

        targets.carAppConstraintsClass?.let { clazz ->
            runCatching {
                HookChains.hookAllConstructors(xposed, clazz, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        runCatching {
                            Reflect.setBooleanField(param.thisObject, "b", true)
                            val state = Reflect.getObjectField(param.thisObject, "g")
                            if (state != null) {
                                runCatching { Reflect.callMethod(state, "d", true) }
                            }
                            val gij = Reflect.getObjectField(param.thisObject, "h")
                            if (gij != null) {
                                runCatching { Reflect.callMethod(gij, "a", 6) }
                            }
                            debug("carApp constraints init: keyboard state forced true")
                        }
                    }
                })
                log("Hooked carApp constraints ctors (${clazz.name})")
            }.onFailure { log("Failed carApp constraints ctors: ${it.message}") }
        }

        targets.searchHintMethod?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        debugEntry("searchHint.d()")
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args.getOrNull(1) == false) {
                            debug("searchHint.d() isKeyboardAllowed forced true")
                            param.args[1] = true
                        }
                    }
                })
                log("Hooked searchHint (${method.declaringClass.name}.${method.name})")
            }.onFailure { log("Failed searchHint: ${it.message}") }
        }

        targets.keyboardRestrictionCtor?.let { ctor ->
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args.size >= 2 && param.args[1] != true) {
                            debug("kbdRestriction isKeyboardAllowed forced true")
                            param.args[1] = true
                        }
                    }
                })
                log("Hooked kbdRestriction ctor (${ctor.declaringClass.name})")
            }.onFailure { log("Failed kbdRestriction ctor: ${it.message}") }
        }

        targets.textFieldCtor?.let { ctor ->
            runCatching {
                HookChains.hookExecutable(xposed, ctor, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args.size >= 9) {
                            // showKeyboardByDefault=true (idx 2), voiceOnlyEnabled=false (idx 8)
                            if (param.args[8] != false || param.args[2] != true) {
                                debug("textField forced showKeyboard=true voiceOnly=false")
                                param.args[8] = false
                                param.args[2] = true
                            }
                        }
                    }
                })
                log("Hooked textField ctor (${ctor.declaringClass.name})")
            }.onFailure { log("Failed textField ctor: ${it.message}") }
        }
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

        targets.imeOnStart?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        activeInputFragment = WeakReference(param.thisObject)
                        debugEntry("ime.onStart()")
                        val locked = Reflect.getBooleanField(param.thisObject, "c")
                        Reflect.setBooleanField(param.thisObject, "c", false)
                        if (locked) {
                            debug("ime.onStart() forced c=false before d()")
                        }
                        runCatching { Reflect.callMethod(param.thisObject, "d") }
                    }
                })
                log("Hooked ime onStart (${method.declaringClass.name})")
            }.onFailure { log("Failed ime onStart: ${it.message}") }
        } ?: runCatching {
            val xaw = findGearheadClass(ctx.classLoader, "xaw")
            HookChains.findAndHookMethod(xposed, xaw, "onStart", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    activeInputFragment = WeakReference(param.thisObject)
                    Reflect.setBooleanField(param.thisObject, "c", false)
                    runCatching { Reflect.callMethod(param.thisObject, "d") }
                }
            })
            log("Hooked fallback xaw.onStart")
        }.onFailure { log("Failed fallback xaw.onStart: ${it.message}") }

        if (targets.imeUnlockMethods.isNotEmpty()) {
            for (method in targets.imeUnlockMethods) {
                runCatching {
                    HookChains.hookMethod(xposed, method, unlockHook)
                    log("Hooked ime unlock ${method.declaringClass.name}.${method.name}")
                }.onFailure { log("Failed ime unlock: ${it.message}") }
            }
        } else {
            for (shortName in listOf("xbg", "xbp", "xdl", "xdu")) {
                runCatching {
                    val clazz = findGearheadClass(ctx.classLoader, shortName)
                    if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return@runCatching
                    HookChains.findAndHookMethod(xposed, clazz, "d", unlockHook)
                    log("Hooked fallback $shortName.d")
                }.onFailure { log("Failed fallback $shortName.d: ${it.message}") }
            }
        }

        targets.imeRotaryLockout?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result == true) {
                            debug("ime.k() forced false (rotary lockout bypass)")
                            param.result = false
                        }
                    }
                })
                log("Hooked ime rotary k (${method.declaringClass.name})")
            }.onFailure { log("Failed ime rotary: ${it.message}") }
        }

        targets.imeStartExternal?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("ime.h() maybeStartExternalKeyboard")
                    }
                })
                log("Hooked ime startExternal (${method.declaringClass.name}.${method.name})")
            }.onFailure { log("Failed ime startExternal: ${it.message}") }
        }

        targets.imeFactory?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.result == null) {
                            log("ime.e() was null — trying concrete fragment")
                            val fallbackClass = targets.imeUnlockMethods.firstOrNull()?.declaringClass
                                ?: runCatching { findGearheadClass(ctx.classLoader, "xbg") }.getOrNull()
                            if (fallbackClass != null) {
                                param.result = Reflect.newInstance(fallbackClass)
                            }
                        }
                    }
                })
                log("Hooked ime factory (${method.declaringClass.name}.${method.name})")
            }.onFailure { log("Failed ime factory: ${it.message}") }
        }
    }

    /**
     * Maps AA search tap → DemandClientService → demand.k(trigger=10) → voice.
     * Block voice sessions and open stock projected IME — no overlay or broadcasts.
     */
    private fun hookMapsNativeSearchKeyboard(ctx: HookContext) {
        hookProjectedImeCache(ctx)
        hookMapsVoiceSessionBlock(ctx)
        hookMapsSearchDemandOpen(ctx)
        hookDemandMicBypass(ctx)
        hookDemandTranscriptionPassthrough(ctx)
    }

    private fun hookProjectedImeCache(ctx: HookContext) {
        targets.imeCacheMethod?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        activeImeService = WeakReference(param.thisObject)
                        ModuleLog.gearhead(
                            "GH-MAPS-000",
                            "cached active IME service (${param.thisObject?.javaClass?.simpleName})",
                            always = true
                        )
                    }
                })
                log("Hooked ime cache (${method.declaringClass.name}.${method.name})")
            }.onFailure { log("Failed ime cache: ${it.message}") }
            return
        }
        runCatching {
            val carRegionId = Reflect.findClass(
                "com.google.android.gms.car.display.CarRegionId",
                ctx.classLoader
            )
            val xaq = findGearheadClass(ctx.classLoader, "xaq")
            HookChains.findAndHookMethod(xposed, xaq, "c", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    activeImeService = WeakReference(param.thisObject)
                    ModuleLog.gearhead(
                        "GH-MAPS-000",
                        "cached active IME service (${param.thisObject?.javaClass?.simpleName})",
                        always = true
                    )
                }
            }, EditorInfo::class.java, carRegionId)
            log("Hooked fallback xaq.c IME cache")
        }.onFailure { log("Failed fallback xaq.c: ${it.message}") }
    }

    private fun hookMapsVoiceSessionBlock(ctx: HookContext) {
        val mapsVoiceTypes = setOf(
            VOICE_SESSION_TYPE_VOICE,
            VOICE_SESSION_TYPE_DIRECT_REPLY,
            VOICE_SESSION_TYPE_TRANSCRIPTION,
            VOICE_SESSION_TYPE_START_TRANSCRIPTION
        )

        targets.voiceTriggerF?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[0] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        if (micDictationActive || inMapsMicFromHeader()) {
                            ModuleLog.gearhead("GH-MIC-001", "voice.F($trigger) mic passthrough", always = true)
                            return
                        }
                        if (!inMapsSearchVoiceBlock()) return
                        ModuleLog.gearhead(
                            "GH-MAPS-001",
                            "voice.F($trigger) blocked — native keyboard path",
                            always = true
                        )
                        param.result = null
                    }
                })
                log("Hooked voice F (${method.declaringClass.name})")
            }.onFailure { log("Failed voice F: ${it.message}") }
        }

        targets.voiceTriggerG?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[0] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        if (micDictationActive || inMapsMicFromHeader()) return
                        if (!inMapsSearchVoiceBlock()) return
                        ModuleLog.gearhead("GH-MAPS-001", "voice.G($trigger) blocked Maps voice session", always = true)
                        param.result = null
                    }
                })
                log("Hooked voice G (${method.declaringClass.name})")
            }.onFailure { log("Failed voice G: ${it.message}") }
        }

        targets.voiceSessionStart?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val config = param.args[0] ?: return
                        val sessionType = Reflect.getIntField(config, "a")
                        val trigger = Reflect.getIntField(config, "f")
                        if (micDictationActive || inMapsMicFromHeader()) return
                        val mapsTapBlock = inMapsSearchVoiceBlock()
                        if ((mapsTapBlock || trigger == VOICE_SEARCH_TRIGGER_MAPS) &&
                            sessionType in mapsVoiceTypes
                        ) {
                            ModuleLog.gearhead(
                                "GH-MAPS-001",
                                "voice.session blocked type=$sessionType trigger=$trigger",
                                always = true
                            )
                            param.result = null
                        }
                    }
                })
                log("Hooked voice session (${method.declaringClass.name}.${method.name})")
            }.onFailure { log("Failed voice session: ${it.message}") }
        }

        if (targets.voiceTriggerF == null) {
            runCatching {
                val kxi = findGearheadClass(ctx.classLoader, "kxi")
                HookChains.findAndHookMethod(xposed, kxi, "F", object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[0] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        if (micDictationActive || inMapsMicFromHeader()) return
                        if (!inMapsSearchVoiceBlock()) return
                        param.result = null
                    }
                }, Int::class.javaPrimitiveType!!)
                log("Hooked fallback kxi.F")
            }.onFailure { log("Failed fallback kxi.F: ${it.message}") }
        }
    }

    private fun hookMapsSearchDemandOpen(ctx: HookContext) {
        targets.demandOpen?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[0] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        if (inMapsMicFromHeader()) {
                            debugEntry("demand.k() trigger=$trigger — mic passthrough")
                            return
                        }
                        debugEntry("demand.k() trigger=$trigger — surgical voice block, open native IME")
                        markMapsSearchVoiceBlock()
                        closeMapsVoiceDemand(ctx.classLoader)
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val trigger = param.args[0] as Int
                        if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                        if (inMapsMicFromHeader()) return
                        if (param.hasThrowable()) return
                        val classLoader = ctx.classLoader
                        runOnMainThread {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (inMapsMicFromHeader() || micDictationActive) {
                                    ModuleLog.gearhead(
                                        "GH-MIC-001",
                                        "skip keyboard — mic session active",
                                        always = true
                                    )
                                    return@postDelayed
                                }
                                ModuleLog.gearhead(
                                    "GH-MAPS-001",
                                    "demand.k($trigger) opening native projected keyboard",
                                    always = true
                                )
                                openNativeProjectedKeyboard(classLoader)
                            }, 300L)
                        }
                    }
                })
                log("Hooked demand open k (${method.declaringClass.name})")
            }.onFailure { log("Failed demand.k: ${it.message}") }
            return
        }
        runCatching {
            val qfy = findGearheadClass(ctx.classLoader, "qfy")
            HookChains.findAndHookMethod(xposed, qfy, "k", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                    if (inMapsMicFromHeader()) return
                    markMapsSearchVoiceBlock()
                    closeMapsVoiceDemand(ctx.classLoader)
                }

                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                    if (inMapsMicFromHeader() || param.hasThrowable()) return
                    runOnMainThread {
                        Handler(Looper.getMainLooper()).postDelayed({
                            openNativeProjectedKeyboard(ctx.classLoader)
                        }, 300L)
                    }
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked fallback qfy.k")
        }.onFailure { log("Failed fallback qfy.k: ${it.message}") }
    }

    private fun hookDemandTranscriptionPassthrough(ctx: HookContext) {
        targets.demandTranscription?.let { method ->
            runCatching {
                HookChains.hookMethod(xposed, method, object : MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        if (param.args[0] as Int != VOICE_SEARCH_TRIGGER_MAPS) return
                        micDictationActive = true
                        mapsMicUntilMs = System.currentTimeMillis() + 4000L
                        ModuleLog.gearhead("GH-MIC-001", "demand.l(10) mic transcription allowed", always = true)
                    }

                    override fun afterHookedMethod(param: HookParam) {
                        micDictationActive = false
                    }
                })
                log("Hooked demand transcription l (${method.declaringClass.name})")
            }.onFailure { log("Failed demand.l: ${it.message}") }
        }
    }

    private fun hookDemandMicBypass(ctx: HookContext) {
        val method = targets.demandOpenCause ?: runCatching {
            val demandService = Reflect.findClass(
                "com.google.android.gearhead.demand.DemandClientService",
                ctx.classLoader
            )
            demandService.getDeclaredMethod("b", Bundle::class.java).also { it.isAccessible = true }
        }.getOrNull()

        if (method == null) {
            log("Failed to hook DemandClientService.b: not found")
            return
        }
        runCatching {
            HookChains.hookMethod(xposed, method, object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val bundle = param.args[0] as? Bundle ?: return
                    signalMapsMicFromDemandBundle(bundle)
                }
            })
            log("Hooked DemandClientService.b mic detection")
        }.onFailure { log("Failed to hook DemandClientService.b: ${it.message}") }
    }

    private fun signalMapsMicFromDemandBundle(bundle: Bundle) {
        val transcription = bundle.getBoolean("gmm_transcription_request")
        val hardwareMic = bundle.getInt("open_cause") == 3 && bundle.getInt("open_cause_key_code") == 84
        if (!transcription && !hardwareMic) return
        micDictationActive = true
        mapsMicUntilMs = System.currentTimeMillis() + 4000L
        ModuleLog.gearhead(
            "GH-MIC-001",
            "demand mic bundle (transcription=$transcription hardware=$hardwareMic)",
            always = true
        )
    }

    private fun markMapsSearchVoiceBlock() {
        mapsSearchBlockUntilMs = System.currentTimeMillis() + 5000L
    }

    private fun inMapsSearchVoiceBlock(): Boolean {
        return System.currentTimeMillis() < mapsSearchBlockUntilMs
    }

    private fun inMapsMicFromHeader(): Boolean {
        return System.currentTimeMillis() < mapsMicUntilMs
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        Handler(Looper.getMainLooper()).post(block)
    }

    private fun closeMapsVoiceDemand(classLoader: ClassLoader) {
        runCatching {
            val controller = targets.demandOpen?.declaringClass?.let { clazz ->
                // Prefer live demand controller instance from key.i() / mil when available.
                runCatching {
                    val key = findGearheadClass(classLoader, "key")
                    Reflect.callStaticMethod(key, "i")
                }.getOrNull()?.takeIf { clazz.isInstance(it) }
            }
            val yui = findGearheadClass(classLoader, "yui")
            val interrupted = Reflect.getStaticObjectField(yui, "INTERRUPTED")
            if (controller != null) {
                Reflect.callMethod(controller, "j", interrupted)
            } else {
                val qfy = findGearheadClass(classLoader, "qfy")
                val instance = runCatching {
                    val key = findGearheadClass(classLoader, "key")
                    Reflect.callStaticMethod(key, "i")
                }.getOrNull()
                if (instance != null && qfy.isInstance(instance)) {
                    Reflect.callMethod(instance, "j", interrupted)
                }
            }
            ModuleLog.gearhead("GH-MAPS-003", "closed demand-space voice for keyboard", always = true)
        }.onFailure {
            ModuleLog.gearhead("GH-MAPS-004", "close demand voice failed: ${it.message}", always = true)
        }
    }

    private fun openNativeProjectedKeyboard(classLoader: ClassLoader) {
        val ctx = resolveGearheadContext(classLoader)
        if (ctx != null) {
            MapsNativeIme.sendPrepare(ctx.applicationContext)
            ModuleLog.gearhead("GH-MAPS-002", "broadcast PREPARE_MAPS_NATIVE_IME (before shell)", always = true)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (openProjectedImeViaService(classLoader)) {
                ModuleLog.gearhead("GH-KBD-002", "stock projected keyboard opened via ime service", always = true)
            } else if (openProjectedImeViaFragment()) {
                ModuleLog.gearhead("GH-KBD-002", "stock projected keyboard opened via fragment", always = true)
            } else if (openProjectedImeViaFactory(classLoader)) {
                ModuleLog.gearhead("GH-KBD-002", "projection keyboard opened via factory", always = true)
            }
            if (ctx != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    MapsNativeIme.sendOpen(ctx.applicationContext)
                    ModuleLog.gearhead("GH-MAPS-002", "broadcast OPEN_MAPS_NATIVE_IME to Maps", always = true)
                }, 120L)
            }
        }, 200L)
    }

    private fun requestMapsNativeKeyboard(classLoader: ClassLoader) {
        openNativeProjectedKeyboard(classLoader)
    }

    private fun resolveGearheadContext(classLoader: ClassLoader): Context? {
        return runCatching {
            val key = findGearheadClass(classLoader, "key")
            val controller = Reflect.callStaticMethod(key, "i")
            Reflect.getObjectField(controller, "g") as Context
        }.getOrNull() ?: runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            Reflect.callStaticMethod(atClass, "currentApplication") as Context
        }.getOrNull()
    }

    private fun openProjectedImeViaService(classLoader: ClassLoader): Boolean {
        val imeClass = targets.imeServiceClass
        val ime = activeImeService?.get()
            ?: findRunningImeService(classLoader, imeClass)
        if (ime == null) {
            ModuleLog.gearhead("GH-MAPS-004", "no IME service in this process", always = true)
            return false
        }
        return runCatching {
            ModuleLog.gearhead("GH-MAPS-002", "attempt projected IME startExternal()", always = true)
            prepareImeForExternalKeyboard(ime)
            val start = targets.imeStartExternal
            if (start != null) {
                start.invoke(ime)
            } else {
                Reflect.callMethod(ime, "h")
            }
            isProjectedKeyboardStarted(ime)
        }.getOrElse {
            ModuleLog.gearhead(
                "GH-MAPS-004",
                "ime start failed: ${it.javaClass.simpleName}: ${it.message}",
                always = true
            )
            false
        }
    }

    private fun openProjectedImeViaFragment(): Boolean {
        val fragment = activeInputFragment?.get() ?: return false
        return runCatching {
            Reflect.setBooleanField(fragment, "c", false)
            Reflect.callMethod(fragment, "d")
            ModuleLog.gearhead("GH-MAPS-003", "fragment.d() projection keyboard", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "fragment.d failed: ${it.message}", always = true)
            false
        }
    }

    private fun openProjectedImeViaFactory(classLoader: ClassLoader): Boolean {
        return runCatching {
            val config = targets.imeUnlockMethods.firstOrNull()?.declaringClass?.let {
                Reflect.newInstance(it)
            } ?: runCatching {
                Reflect.newInstance(findGearheadClass(classLoader, "xbg"))
            }.getOrNull()
            if (config == null) return false
            Reflect.setBooleanField(config, "c", false)
            Reflect.callMethod(config, "d")
            ModuleLog.gearhead("GH-MAPS-003", "factory fragment.d() projection keyboard", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead(
                "GH-MAPS-004",
                "factory.d failed: ${it.javaClass.simpleName}: ${it.message}",
                always = true
            )
            false
        }
    }

    private fun findRunningImeService(classLoader: ClassLoader, imeClass: Class<*>?): Any? {
        val targetClass = imeClass ?: runCatching {
            findGearheadClass(classLoader, "xaq")
        }.getOrNull() ?: return null
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val services = Reflect.getObjectField(at, "mServices") as? Map<*, *> ?: return null
            for (record in services.values) {
                val service = Reflect.getObjectField(record, "service") ?: continue
                if (targetClass.isInstance(service)) {
                    ModuleLog.gearhead(
                        "GH-MAPS-000",
                        "discovered running IME service via ActivityThread scan",
                        always = true
                    )
                    return service
                }
            }
            null
        }.getOrNull()
    }

    private fun isProjectedKeyboardStarted(ime: Any): Boolean {
        return runCatching { Reflect.getObjectField(ime, "q") != null }.getOrDefault(false)
    }

    private fun prepareImeForExternalKeyboard(ime: Any) {
        runCatching {
            val alreadyRunning = Reflect.getObjectField(ime, "q")
            if (Reflect.getBooleanField(ime, "k") && alreadyRunning == null) {
                Reflect.setBooleanField(ime, "k", false)
            }
            Reflect.setBooleanField(ime, "l", true)
            val editorInfo = Reflect.getObjectField(ime, "h") as? EditorInfo
                ?: EditorInfo().apply {
                    packageName = "com.google.android.apps.maps"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    hintText = "Search"
                }
            if (Reflect.getObjectField(ime, "h") == null) {
                Reflect.setObjectField(ime, "h", editorInfo)
            }
            runCatching {
                val fragment = Reflect.callMethod(ime, "f")
                if (fragment != null) {
                    Reflect.setBooleanField(fragment, "c", false)
                }
            }
        }
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
