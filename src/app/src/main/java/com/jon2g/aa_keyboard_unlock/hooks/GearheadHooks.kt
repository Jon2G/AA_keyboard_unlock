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
        hookSensorCallbacks(ctx, "lhk")
        hookSensorCallbacks(ctx, "lhu")
        hookLocationManager(ctx)
        hookInputMethodFragment(ctx)
        hookParkingAndAssistantSettings(ctx)
        hookCarUiConstraints(ctx)
        hookCarAppKeyboardGate(ctx)
        hookMapsNativeSearchKeyboard(ctx)
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
                log("Skipping $shortName.d (abstract/interface)")
                return
            }
            HookChains.hookAllMethods(xposed, clazz, "d", sensorSpoofHook)
            log("Hooked $shortName.d (${clazz.name})")
        }.onFailure { log("Failed to hook $shortName.d: ${it.message}") }
    }

    private fun hookLocationManager(ctx: HookContext) {
        runCatching {
            val lht = findGearheadClass(ctx.classLoader, "lht")
            HookChains.findAndHookMethod(xposed, lht, "q", object : MethodHook() {
                override fun afterHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != true) {
                        debug("lht.q() forced true (was ${param.result})")
                        param.result = true
                    }
                }
            })
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
                        debug("kxk.a() forced true (was ${param.result})")
                        param.result = true
                    }
                }
            })
            log("Hooked kxk.a (${kxk.name})")
        }.onFailure { log("Failed to hook kxk.a: ${it.message}") }
    }

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
                        debug("jtg init: keyboard state forced true for $component")
                    }
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
            val gyb = findGearheadClass(ctx.classLoader, "gyb")
            val hgx = findGearheadClass(ctx.classLoader, "hgx")
            HookChains.findAndHookMethod(xposed, gxy, "d", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    debugEntry("gxy.d()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[1] == false) {
                        debug("gxy.d() isKeyboardAllowed forced true")
                        param.args[1] = true
                    }
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

    private fun hookLgzImplementors(ctx: HookContext, lgz: Class<*>, outerShortName: String) {
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
                        debug("$className.$methodName() forced true")
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
                        debug("$className.$methodName() forced true")
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

    /**
     * Maps AA search tap → DemandClientService → kcw.k(trigger=10) → voice.
     * Block voice sessions and open stock projected IME (xcu.h / xdl.d) — no overlay or broadcasts.
     */
    private fun hookMapsNativeSearchKeyboard(ctx: HookContext) {
        hookProjectedImeCache(ctx)
        hookMapsVoiceSessionBlock(ctx)
        hookMapsSearchKcw(ctx)
        hookDemandMicBypass(ctx)
        hookQibMicPassthrough(ctx)
    }

    private fun hookProjectedImeCache(ctx: HookContext) {
        runCatching {
            val carRegionId = Reflect.findClass(
                "com.google.android.gms.car.display.CarRegionId",
                ctx.classLoader
            )
            val editorInfo = EditorInfo::class.java
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
                        "kxe.F($trigger) blocked — native keyboard path",
                        always = true
                    )
                    param.result = null
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked kxe.F Maps voice block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.F Maps voice block: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(ctx.classLoader, "kxe")
            val bundle = Bundle::class.java
            HookChains.findAndHookMethod(xposed, kxe, "G", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                    if (micDictationActive || inMapsMicFromHeader()) return
                    if (!inMapsSearchVoiceBlock()) return
                    ModuleLog.gearhead("GH-MAPS-001", "kxe.G($trigger) blocked Maps voice session", always = true)
                    param.result = null
                }
            }, Int::class.javaPrimitiveType!!, bundle)
            log("Hooked kxe.G Maps voice block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.G Maps voice block: ${it.message}") }

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
                    if (micDictationActive || inMapsMicFromHeader()) return
                    val mapsTapBlock = inMapsSearchVoiceBlock()
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
            }, voiceSessionConfig)
            log("Hooked kxe.ac Maps voice block (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.ac Maps voice block: ${it.message}") }
    }

    private fun hookMapsSearchKcw(ctx: HookContext) {
        runCatching {
            val kvl = findGearheadClass(ctx.classLoader, "kvl")
            val kcw = findGearheadClass(ctx.classLoader, "kcw")
            HookChains.findAndHookMethod(xposed, kcw, "k", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[1] as Int
                    if (trigger != VOICE_SEARCH_TRIGGER_MAPS) return
                    if (inMapsMicFromHeader()) {
                        debugEntry("kcw.k() trigger=$trigger — mic passthrough")
                        return
                    }
                    debugEntry("kcw.k() trigger=$trigger — surgical voice block, open native IME")
                    markMapsSearchVoiceBlock()
                    closeMapsVoiceDemand(ctx.classLoader)
                    // Let stock kcw.k proceed so Maps receives search IPC; kxe.* hooks block voice UI.
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
                                "kcw.k($trigger) opening native projected keyboard",
                                always = true
                            )
                            openNativeProjectedKeyboard(classLoader)
                        }, 300L)
                    }
                }
            }, kvl, Int::class.javaPrimitiveType!!)
            log("Hooked kcw.k Maps native keyboard (${kcw.name})")
        }.onFailure { log("Failed to hook kcw.k Maps native keyboard: ${it.message}") }
    }

    private fun hookQibMicPassthrough(ctx: HookContext) {
        runCatching {
            val qib = findGearheadClass(ctx.classLoader, "qib")
            HookChains.findAndHookMethod(xposed, qib, "l", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] as Int != VOICE_SEARCH_TRIGGER_MAPS) return
                    micDictationActive = true
                    mapsMicUntilMs = System.currentTimeMillis() + 4000L
                    ModuleLog.gearhead("GH-MIC-001", "qib.l(10) mic transcription allowed", always = true)
                }

                override fun afterHookedMethod(param: HookParam) {
                    micDictationActive = false
                }
            }, Int::class.javaPrimitiveType!!)
            log("Hooked qib.l mic passthrough (${qib.name})")
        }.onFailure { log("Failed to hook qib.l mic passthrough: ${it.message}") }
    }

    private fun hookDemandMicBypass(ctx: HookContext) {
        runCatching {
            val demandService = Reflect.findClass(
                "com.google.android.gearhead.demand.DemandClientService",
                ctx.classLoader
            )
            HookChains.findAndHookMethod(xposed, demandService, "b", object : MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val bundle = param.args[0] as? Bundle ?: return
                    signalMapsMicFromDemandBundle(bundle)
                }
            }, Bundle::class.java)
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
            val kcw = findGearheadClass(classLoader, "kcw")
            val controller = Reflect.callStaticMethod(kcw, "l")
            val ywj = findGearheadClass(classLoader, "ywj")
            val interrupted = Reflect.getStaticObjectField(ywj, "INTERRUPTED")
            Reflect.callMethod(controller, "n", interrupted)
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
            if (openProjectedImeViaXcu(classLoader)) {
                ModuleLog.gearhead("GH-KBD-002", "stock projected keyboard opened via xcu", always = true)
            } else if (openProjectedImeViaXdb()) {
                ModuleLog.gearhead("GH-KBD-002", "stock projected keyboard opened via xdb", always = true)
            } else if (openProjectedImeViaXdl(classLoader)) {
                ModuleLog.gearhead("GH-KBD-002", "projection keyboard opened via xdl", always = true)
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
            val kcw = findGearheadClass(classLoader, "kcw")
            val controller = Reflect.callStaticMethod(kcw, "l")
            Reflect.getObjectField(controller, "d") as Context
        }.getOrNull() ?: runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            Reflect.callStaticMethod(atClass, "currentApplication") as Context
        }.getOrNull()
    }

    private fun openProjectedImeViaXcu(classLoader: ClassLoader): Boolean {
        val ime = activeImeService?.get() ?: findRunningXcuService(classLoader)
        if (ime == null) {
            ModuleLog.gearhead("GH-MAPS-004", "no xcu IME service in this process", always = true)
            return false
        }
        return runCatching {
            ModuleLog.gearhead("GH-MAPS-002", "attempt projected IME xcu.h()", always = true)
            prepareXcuForExternalKeyboard(ime)
            Reflect.callMethod(ime, "h")
            isProjectedKeyboardStarted(ime)
        }.getOrElse {
            ModuleLog.gearhead(
                "GH-MAPS-004",
                "xcu.h failed: ${it.javaClass.simpleName}: ${it.message}",
                always = true
            )
            false
        }
    }

    private fun openProjectedImeViaXdb(): Boolean {
        val fragment = activeInputFragment?.get() ?: return false
        return runCatching {
            Reflect.setBooleanField(fragment, "c", false)
            Reflect.callMethod(fragment, "d")
            ModuleLog.gearhead("GH-MAPS-003", "xdb.d() projection keyboard", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "xdb.d failed: ${it.message}", always = true)
            false
        }
    }

    private fun openProjectedImeViaXdl(classLoader: ClassLoader): Boolean {
        return runCatching {
            val xdl = findGearheadClass(classLoader, "xdl")
            val config = runCatching {
                val xdm = findGearheadClass(classLoader, "xdm")
                Reflect.callStaticMethod(xdm, "e")
            }.getOrNull() ?: Reflect.newInstance(xdl)
            Reflect.setBooleanField(config, "c", false)
            Reflect.callMethod(config, "d")
            ModuleLog.gearhead("GH-MAPS-003", "xdl/xdu.d() projection keyboard", always = true)
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-MAPS-004", "xdl.d failed: ${it.javaClass.simpleName}: ${it.message}", always = true)
            false
        }
    }

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

    private fun isProjectedKeyboardStarted(ime: Any): Boolean {
        return runCatching { Reflect.getObjectField(ime, "q") != null }.getOrDefault(false)
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
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
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
