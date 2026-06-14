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

    // Voice session types / triggers (VoiceSessionConfig: a=type, f=trigger)
    private const val VOICE_SESSION_TYPE_VOICE = 1
    private const val VOICE_SESSION_TYPE_DIRECT_REPLY = 3
    private const val VOICE_SESSION_TYPE_TRANSCRIPTION = 5
    private const val VOICE_SESSION_TYPE_START_TRANSCRIPTION = 6
    private const val VOICE_SEARCH_TRIGGER_MAPS = 10

    // Block raw voice / transcription session types; allow type 6 (demand-space keyboard UI).
    private val VOICE_BLOCK_SESSION_TYPES = setOf(
        VOICE_SESSION_TYPE_VOICE,
        VOICE_SESSION_TYPE_DIRECT_REPLY,
        VOICE_SESSION_TYPE_TRANSCRIPTION
    )

    // lha enum: a=CAR_MOVING, b=CAR_PARKED, c=UNKNOWN
    private const val LHA_FIELD_CAR_PARKED = "b"

    // Voice Plate transcriptionState: 1=INACTIVE (keyboard), 2=ACTIVE (voice)
    private const val TRANSCRIPTION_INACTIVE = 1
    private const val TRANSCRIPTION_ACTIVE = 2

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
        hookParkingAndAssistantSettings(lpparam)
        hookCarUiConstraints(lpparam)
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
                        val component = XposedHelpers.getObjectField(param.thisObject, "a")
                        val state = XposedHelpers.getObjectField(param.thisObject, "g")
                        XposedHelpers.callMethod(state, "d", true)
                        val gij = XposedHelpers.getObjectField(param.thisObject, "h")
                        XposedHelpers.callMethod(gij, "a", 6)
                        debug("jtg init: keyboard state forced true for $component, refresh dispatched")
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

    private fun hookParkingAndAssistantSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val lht = findGearheadClass(lpparam.classLoader, "lht")
            val lha = findGearheadClass(lpparam.classLoader, "lha")
            val carParked = XposedHelpers.getStaticObjectField(lha, LHA_FIELD_CAR_PARKED)
            XposedHelpers.findAndHookMethod(lht, "c", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
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
            val lhi = findGearheadClass(lpparam.classLoader, "lhi")
            val lha = findGearheadClass(lpparam.classLoader, "lha")
            val carParked = XposedHelpers.getStaticObjectField(lha, LHA_FIELD_CAR_PARKED)
            XposedHelpers.findAndHookMethod(lhi, "d", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
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
            val kxk = findGearheadClass(lpparam.classLoader, "kxk")
            XposedHelpers.findAndHookMethod(kxk, "a", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
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
    private fun hookCarUiConstraints(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val jpm = findGearheadClass(lpparam.classLoader, "jpm")
            for (method in listOf("a", "b")) {
                XposedHelpers.findAndHookMethod(jpm, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        debugEntry("jpm.$method()")
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
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

    private fun hookVoiceMicCapture(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val kwt = findGearheadClass(lpparam.classLoader, "kwt")
            XposedHelpers.findAndHookMethod(kwt, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debug("kwt.b() blocked voice mic capture")
                    param.result = null
                }
            })
            log("Hooked kwt.b (${kwt.name})")
        }.onFailure { log("Failed to hook kwt.b: ${it.message}") }

        runCatching {
            val micProvider = XposedHelpers.findClass(
                "com.google.android.apps.auto.components.demand.audio.GhMicrophoneContentProvider",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(micProvider, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debug("GhMicrophoneContentProvider.b() blocked")
                    param.result = null
                }
            })
            log("Hooked GhMicrophoneContentProvider.b")
        }.onFailure { log("Failed to hook GhMicrophoneContentProvider.b: ${it.message}") }
    }

    private fun hookVoicePlateAndAssistant(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookVoicePlateWidget(lpparam)
        hookVoiceMicCapture(lpparam)
        hookAssistantController(lpparam)
    }

    private fun hookVoicePlateWidget(lpparam: XC_LoadPackage.LoadPackageParam) {
        val carText = XposedHelpers.findClass("androidx.car.app.model.CarText", lpparam.classLoader)

        runCatching {
            val voicePlateWidget = XposedHelpers.findClass(
                "com.android.car.libraries.apphost.external.model.widgets.VoicePlateWidget",
                lpparam.classLoader
            )
            val action = XposedHelpers.findClass("androidx.car.app.model.Action", lpparam.classLoader)
            val carIcon = XposedHelpers.findClass("androidx.car.app.model.CarIcon", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(
                voicePlateWidget,
                action,
                carIcon,
                carText,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("VoicePlateWidget.<init>()")
                        rewritePlaceholderArg(param, 2, lpparam.classLoader, carText)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(voicePlateWidget, "getPlaceholderText", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val placeholder = param.result ?: return
                    if (!VoicePlateHints.isVoiceOnlyHint(placeholder)) return
                    debug("VoicePlateWidget.getPlaceholderText() rewritten")
                    param.result = VoicePlateHints.createCarText(lpparam.classLoader)
                }
            })
            log("Hooked VoicePlateWidget constructor + getPlaceholderText")
        }.onFailure { log("Failed to hook VoicePlateWidget: ${it.message}") }

        runCatching {
            val fuw = findGearheadClass(lpparam.classLoader, "fuw")
            val fyt = findGearheadClass(lpparam.classLoader, "fyt")
            val hjq = findGearheadClass(lpparam.classLoader, "hjq")
            XposedHelpers.findAndHookConstructor(hjq, fuw, fyt, carText, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("hjq.<init>()")
                    rewritePlaceholderArg(param, 2, lpparam.classLoader, carText)
                }
            })
            log("Hooked hjq constructor (${hjq.name})")
        }.onFailure { log("Failed to hook hjq: ${it.message}") }

        runCatching {
            val fvj = findGearheadClass(lpparam.classLoader, "fvj")
            val afes = findGearheadClass(lpparam.classLoader, "afes")
            val fyh = findGearheadClass(lpparam.classLoader, "fyh")
            val gbg = findGearheadClass(lpparam.classLoader, "gbg")
            val gbh = findGearheadClass(lpparam.classLoader, "gbh")
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
                        val state = param.args[4] as Int
                        debugEntry("hjv.<init>() transcriptionState=$state")
                        if (state == TRANSCRIPTION_ACTIVE) {
                            param.args[4] = TRANSCRIPTION_INACTIVE
                            debug("hjv transcriptionState forced INACTIVE (keyboard)")
                        }
                        val text = param.args[3]
                        if (text != null && VoicePlateHints.isVoiceOnlyHint(text)) {
                            param.args[3] = XposedHelpers.callStaticMethod(
                                gbh,
                                "a",
                                VoicePlateHints.KEYBOARD_SEARCH_HINT
                            )
                            debug("hjv text rewritten to keyboard hint")
                        }
                    }
                }
            )
            log("Hooked hjv constructor (${hjv.name})")
        }.onFailure { log("Failed to hook hjv: ${it.message}") }
    }

    private fun rewritePlaceholderArg(
        param: XC_MethodHook.MethodHookParam,
        index: Int,
        classLoader: ClassLoader,
        carText: Class<*>
    ) {
        val placeholder = param.args[index] ?: return
        val text = runCatching {
            XposedHelpers.callMethod(placeholder, "toCharSequence") as? CharSequence
        }.getOrNull()?.toString()
        if (text != null) {
            debug("VoicePlate placeholder: \"$text\"")
        }
        if (!VoicePlateHints.isVoiceOnlyHint(placeholder)) return
        param.args[index] = VoicePlateHints.createCarText(classLoader)
        debug("placeholder rewritten to keyboard hint")
    }

    private fun hookAssistantController(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val kxe = findGearheadClass(lpparam.classLoader, "kxe")
            val qjr = findGearheadClass(lpparam.classLoader, "qjr")
            XposedHelpers.findAndHookMethod(kxe, "O", qjr, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("kxe.O() startTranscription")
                }
            })
            log("Hooked kxe.O (${kxe.name})")
        }.onFailure { log("Failed to hook kxe.O: ${it.message}") }

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
                    val sessionType = XposedHelpers.getIntField(config, "a")
                    val trigger = XposedHelpers.getIntField(config, "f")
                    debugEntry("kxe.ac() type=$sessionType trigger=$trigger")
                    if (sessionType in VOICE_BLOCK_SESSION_TYPES) {
                        debug("kxe.ac() blocked voice/dictation type=$sessionType trigger=$trigger")
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
                            debug("kxe.aa() direct reply proceeds; mic blocked via kwt/GhMicrophone")
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
                        val trigger = param.args[1] as Int
                        debugEntry("kcw.k() trigger=$trigger")
                        if (trigger == VOICE_SEARCH_TRIGGER_MAPS) {
                            debug("kcw.k($trigger) voice search (Maps keyboard uses qhf.l/snp.k path)")
                        }
                    }
                }
            )
            log("Hooked kcw.k (${kcw.name})")
        }.onFailure { log("Failed to hook kcw.k: ${it.message}") }

        runCatching {
            val qib = findGearheadClass(lpparam.classLoader, "qib")
            XposedHelpers.findAndHookMethod(
                qib,
                "l",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("kvl.l() trigger=${param.args[0]}")
                    }
                }
            )
            log("Hooked kvl.l (${qib.name})")
        }.onFailure { log("Failed to hook kvl.l: ${it.message}") }
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

        runCatching {
            val xcu = findGearheadClass(lpparam.classLoader, "xcu")
            XposedHelpers.findAndHookMethod(xcu, "h", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("xcu.h() maybeStartExternalKeyboard")
                }
            })
            log("Hooked xcu.h (${xcu.name})")
        }.onFailure { log("Failed to hook xcu.h: ${it.message}") }

        // xdm.e() returns null when npz reports invalid input config — NPE in xcu.c without this.
        runCatching {
            val xdm = findGearheadClass(lpparam.classLoader, "xdm")
            XposedHelpers.findAndHookMethod(xdm, "e", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.result == null) {
                        log("xdm.e() was null — falling back to xdl")
                        val xdl = findGearheadClass(lpparam.classLoader, "xdl")
                        param.result = XposedHelpers.newInstance(xdl)
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
        XposedBridge.log("[$TAG] $message")
    }
}
