package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

object GearheadHooks {
    private const val TAG = "AAKeyboardUnlock"

    // Car sensor types (gearhead xnv / DHU protocol)
    private const val SENSOR_TYPE_CAR_SPEED = 2
    private const val SENSOR_TYPE_DRIVING_STATUS = 11

    // Driving status byte: bit 2 set = keyboard locked
    private const val DRIVING_STATUS_KEYBOARD_LOCK_BIT = 2

    // Voice session types / triggers (from log + kxe RE)
    private const val VOICE_SESSION_TYPE_DIRECT_REPLY = 3
    private const val VOICE_SEARCH_TRIGGER_MAPS = 10

    private val VOICE_ONLY_HINT_MARKERS = listOf(
        "Voice only while driving",
        "Select and speak",
        "voice only",
        "select and speak"
    )

    @Volatile
    private var installedForProcess = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (installedForProcess) return
                    installedForProcess = true
                    installHooks(lpparam)
                }
            }
        )
    }

    private fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("Installing hooks (enabled=${ModulePrefs.isEnabled()}, debug=${ModulePrefs.isDebug()})")
        hookSensorCallbacks(lpparam, "lhk")
        hookSensorCallbacks(lpparam, "lhu")
        hookLocationManager(lpparam)
        hookInputMethodFragment(lpparam)
        hookCarAppKeyboardGate(lpparam)
        hookVoicePlateAndAssistant(lpparam)
        log("Hooks installed for ${lpparam.packageName}")
    }

    private fun findGearheadClass(classLoader: ClassLoader, shortName: String): Class<*> {
        for (name in listOf(shortName, "defpackage.$shortName")) {
            runCatching {
                return XposedHelpers.findClass(name, classLoader)
            }
        }
        throw ClassNotFoundException(shortName)
    }

    private val sensorSpoofHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
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
                        val cleared = (original.toInt() and DRIVING_STATUS_KEYBOARD_LOCK_BIT.inv()).toByte()
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

    private fun hookSensorCallbacks(lpparam: XC_LoadPackage.LoadPackageParam, shortName: String) {
        runCatching {
            val clazz = findGearheadClass(lpparam.classLoader, shortName)
            if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
                log("Skipping $shortName.d (abstract/interface; use concrete implementor)")
                return
            }
            XposedBridge.hookAllMethods(clazz, "d", sensorSpoofHook)
            log("Hooked $shortName.d (${clazz.name})")
        }.onFailure { log("Failed to hook $shortName.d: ${it.message}") }
    }

    private fun hookLocationManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val lht = findGearheadClass(lpparam.classLoader, "lht")

            // kzr.d().q() — isKeyboardEnabled (parking / keyboard allowed)
            XposedHelpers.findAndHookMethod(lht, "q", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != true) {
                        debug("lht.q() forced true (was ${param.result})")
                        param.result = true
                    }
                }
            })
            // kzr.d().s() — wheel speed was non-zero
            XposedHelpers.findAndHookMethod(lht, "s", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
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
            val lhi = findGearheadClass(lpparam.classLoader, "lhi")
            XposedHelpers.findAndHookMethod(lhi, "f", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
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

    private fun hookCarAppKeyboardGate(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val jtg = findGearheadClass(lpparam.classLoader, "jtg")
            XposedHelpers.findAndHookMethod(jtg, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    debugEntry("jtg.b()")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result != false) {
                        debug("jtg.b() forced false (keyboard allowed, was ${param.result})")
                        param.result = false
                    }
                }
            })
            XposedBridge.hookAllConstructors(jtg, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    runCatching {
                        val state = XposedHelpers.getObjectField(param.thisObject, "g")
                        XposedHelpers.callMethod(state, "d", true)
                        val gij = XposedHelpers.getObjectField(param.thisObject, "h")
                        XposedHelpers.callMethod(gij, "a", 6)
                        debug("jtg init: keyboard state forced true, refresh dispatched")
                    }.onFailure { debug("jtg init refresh failed: ${it.message}") }
                }
            })
            log("Hooked jtg.b() + constructors (${jtg.name})")
        }.onFailure { log("Failed to hook jtg: ${it.message}") }

        hookTemplateKeyboardMethod(lpparam, "jyn", "b", Boolean::class.javaPrimitiveType!!)
        hookTemplateKeyboardMethod(lpparam, "jys", "b", Boolean::class.javaPrimitiveType!!)
        hookTemplateKeyboardMethod(lpparam, "jyu", "d", Boolean::class.javaPrimitiveType!!)
        hookTemplateHintMethod(lpparam, "jyn", "p", Boolean::class.javaPrimitiveType!!)
        hookTemplateHintMethod(lpparam, "jys", "p", Boolean::class.javaPrimitiveType!!)

        runCatching {
            val lgz = findGearheadClass(lpparam.classLoader, "lgz")
            hookLgzImplementors(lpparam, lgz, "lht")
            hookLgzImplementors(lpparam, lgz, "jtg")
            hookLgzImplementors(lpparam, lgz, "lhl")
            log("Hooked lgz.a implementors")
        }.onFailure { log("Failed to hook lgz.a: ${it.message}") }

        runCatching {
            val gxy = findGearheadClass(lpparam.classLoader, "gxy")
            val gyb = findGearheadClass(lpparam.classLoader, "gyb")
            val hgx = findGearheadClass(lpparam.classLoader, "hgx")
            XposedHelpers.findAndHookMethod(gxy, "d", gyb, Boolean::class.javaPrimitiveType!!, hgx, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    debugEntry("gxy.d()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[1] == false) {
                        debug("gxy.d() isKeyboardAllowed forced true")
                        param.args[1] = true
                    }
                }
            })
            log("Hooked gxy.d (${gxy.name})")
        }.onFailure { log("Failed to hook gxy.d: ${it.message}") }

        runCatching {
            val gxz = findGearheadClass(lpparam.classLoader, "gxz")
            XposedBridge.hookAllConstructors(gxz, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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
            val gan = findGearheadClass(lpparam.classLoader, "gan")
            XposedBridge.hookAllConstructors(gan, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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

    private fun hookVoicePlateAndAssistant(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookVoicePlateWidget(lpparam)
        hookAssistantController(lpparam)
    }

    private fun hookVoicePlateWidget(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val fuw = findGearheadClass(lpparam.classLoader, "fuw")
            val fyt = findGearheadClass(lpparam.classLoader, "fyt")
            val carText = XposedHelpers.findClass("androidx.car.app.model.CarText", lpparam.classLoader)
            val hjq = findGearheadClass(lpparam.classLoader, "hjq")
            XposedHelpers.findAndHookConstructor(hjq, fuw, fyt, carText, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("hjq.<init>()")
                    val placeholder = param.args[2] ?: return
                    if (!isVoiceOnlyHint(placeholder)) return
                    val replacement = XposedHelpers.callStaticMethod(carText, "create", KEYBOARD_SEARCH_HINT)
                    param.args[2] = replacement
                    debug("hjq placeholder rewritten to keyboard hint")
                }
            })
            log("Hooked hjq constructor (${hjq.name})")
        }.onFailure { log("Failed to hook hjq: ${it.message}") }

        runCatching {
            val fvj = findGearheadClass(lpparam.classLoader, "fvj")
            val afes = findGearheadClass(lpparam.classLoader, "afes")
            val fyh = findGearheadClass(lpparam.classLoader, "fyh")
            val gbg = findGearheadClass(lpparam.classLoader, "gbg")
            val hdx = findGearheadClass(lpparam.classLoader, "hdx")
            val hjv = findGearheadClass(lpparam.classLoader, "hjv")
            XposedHelpers.findAndHookConstructor(
                hjv,
                fvj,
                afes,
                fyh,
                gbg,
                Int::class.javaPrimitiveType!!,
                hdx,
                Boolean::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("hjv.<init>()")
                        // transcriptionState: 1=INACTIVE (keyboard), 2=ACTIVE (voice)
                        if (param.args[4] == 2) {
                            param.args[4] = 1
                            debug("hjv transcriptionState forced INACTIVE (keyboard)")
                        }
                    }
                }
            )
            log("Hooked hjv constructor (${hjv.name})")
        }.onFailure { log("Failed to hook hjv: ${it.message}") }
    }

    private fun hookAssistantController(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val kxe = findGearheadClass(lpparam.classLoader, "kxe")
            val voiceSessionConfig = XposedHelpers.findClass(
                "com.google.android.gearhead.sdk.assistant.VoiceSessionConfig",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(kxe, "ac", voiceSessionConfig, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val config = param.args[0] ?: return
                    val trigger = XposedHelpers.getIntField(config, "f")
                    debugEntry("kxe.ac() trigger=$trigger")
                    if (trigger == VOICE_SEARCH_TRIGGER_MAPS) {
                        debug("kxe.ac() blocked maps voice search (keyboard allowed)")
                        param.result = null
                    }
                }
            })
            log("Hooked kxe.ac (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.ac: ${it.message}") }

        runCatching {
            val kxe = findGearheadClass(lpparam.classLoader, "kxe")
            val messagingInfo = XposedHelpers.findClass(
                "com.google.android.gearhead.sdk.assistant.MessagingInfo",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                kxe,
                "aa",
                messagingInfo,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val type = param.args[1] as Int
                        debugEntry("kxe.aa() type=$type")
                        if (type == VOICE_SESSION_TYPE_DIRECT_REPLY) {
                            debug("kxe.aa() direct reply: voice session proceeds; IME unlock handles keyboard")
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(kxe, "t", messagingInfo, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("kxe.t() notification direct reply")
                }
            })
            log("Hooked kxe.aa/t (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.aa/t: ${it.message}") }

        runCatching {
            val kvl = findGearheadClass(lpparam.classLoader, "kvl")
            val kcw = findGearheadClass(lpparam.classLoader, "kcw")
            XposedHelpers.findAndHookMethod(
                kcw,
                "k",
                kvl,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("kcw.k() trigger=${param.args[1]}")
                    }
                }
            )
            log("Hooked kcw.k (${kcw.name})")
        }.onFailure { log("Failed to hook kcw.k: ${it.message}") }
    }

    private fun hookLgzImplementors(
        lpparam: XC_LoadPackage.LoadPackageParam,
        lgz: Class<*>,
        outerShortName: String
    ) {
        val outer = findGearheadClass(lpparam.classLoader, outerShortName)
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (param.args[0] == false) {
                    debug("lgz.a() forced true in ${param.thisObject.javaClass.name}")
                    param.args[0] = true
                }
            }
        }
        for (inner in outer.declaredClasses) {
            if (lgz.isAssignableFrom(inner)) {
                runCatching {
                    XposedHelpers.findAndHookMethod(inner, "a", Boolean::class.javaPrimitiveType!!, hook)
                }
            }
        }
    }

    private fun hookTemplateHintMethod(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        methodName: String,
        paramType: Class<*>
    ) {
        runCatching {
            val clazz = findGearheadClass(lpparam.classLoader, className)
            XposedHelpers.findAndHookMethod(clazz, methodName, paramType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    debugEntry("$className.$methodName()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] == false) {
                        debug("$className.$methodName() forced true (keyboard hint)")
                        param.args[0] = true
                    }
                }
            })
            log("Hooked $className.$methodName (${clazz.name})")
        }.onFailure { log("Failed to hook $className.$methodName: ${it.message}") }
    }

    private fun hookTemplateKeyboardMethod(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        methodName: String,
        paramType: Class<*>
    ) {
        runCatching {
            val clazz = findGearheadClass(lpparam.classLoader, className)
            XposedHelpers.findAndHookMethod(clazz, methodName, paramType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    debugEntry("$className.$methodName()")
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args[0] == false) {
                        debug("$className.$methodName() forced true (keyboard shown)")
                        param.args[0] = true
                    }
                }
            })
            log("Hooked $className.$methodName (${clazz.name})")
        }.onFailure { log("Failed to hook $className.$methodName: ${it.message}") }
    }

    private fun hookInputMethodFragment(lpparam: XC_LoadPackage.LoadPackageParam) {
        val unlockHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!ModulePrefs.isEnabled()) return
                val name = param.thisObject.javaClass.simpleName
                debugEntry("$name.d()")
                val locked = XposedHelpers.getBooleanField(param.thisObject, "c")
                XposedHelpers.setBooleanField(param.thisObject, "c", false)
                if (locked) {
                    debug("$name.d() forced c=false (keyboard unlock)")
                }
            }
        }

        runCatching {
            val xdb = findGearheadClass(lpparam.classLoader, "xdb")
            XposedHelpers.findAndHookMethod(xdb, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("xdb.onStart()")
                    val locked = XposedHelpers.getBooleanField(param.thisObject, "c")
                    XposedHelpers.setBooleanField(param.thisObject, "c", false)
                    if (locked) {
                        debug("xdb.onStart() forced c=false before d()")
                    }
                    XposedHelpers.callMethod(param.thisObject, "d")
                }
            })
            log("Hooked xdb.onStart (${xdb.name})")
        }.onFailure { log("Failed to hook xdb.onStart: ${it.message}") }

        for (shortName in listOf("xdl", "xdu")) {
            runCatching {
                val clazz = findGearheadClass(lpparam.classLoader, shortName)
                if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
                    log("Skipping $shortName.d (abstract/interface)")
                    return@runCatching
                }
                XposedHelpers.findAndHookMethod(clazz, "d", unlockHook)
                log("Hooked $shortName.d (${clazz.name})")
            }.onFailure { log("Failed to hook $shortName.d: ${it.message}") }
        }

        runCatching {
            val xdu = findGearheadClass(lpparam.classLoader, "xdu")
            XposedHelpers.findAndHookMethod(xdu, "k", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result == true) {
                        debug("xdu.k() forced false (rotary lockout bypass)")
                        param.result = false
                    }
                }
            })
            log("Hooked xdu.k (${xdu.name})")
        }.onFailure { log("Failed to hook xdu.k: ${it.message}") }
    }

    private fun isVoiceOnlyHint(carText: Any): Boolean {
        val text = runCatching {
            XposedHelpers.callMethod(carText, "toCharSequence") as? CharSequence
        }.getOrNull()?.toString()
            ?: carText.toString()
        return VOICE_ONLY_HINT_MARKERS.any { marker ->
            text.contains(marker, ignoreCase = true)
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
        XposedBridge.log("[$TAG] $message")
    }

    private const val KEYBOARD_SEARCH_HINT = "Search"
}
