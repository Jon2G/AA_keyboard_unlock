package com.jon2g.aa_keyboard_unlock.hooks

import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MapsHooks {
    private const val TAG = "AAKeyboardUnlock"

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
        log("Installing Maps hooks (enabled=${ModulePrefs.isEnabled()}, debug=${ModulePrefs.isDebug()})")
        hookSearchHintResolver(lpparam)
        hookHeaderUiState(lpparam)
        hookSearchBarTap(lpparam)
        hookProjectedKeyboard(lpparam)
        hookTrtDrivingFlags(lpparam)
        log("Maps hooks installed for ${lpparam.packageName}")
    }

    /**
     * kur.aJ picks CAR_VOICE_ONLY_WHEN_DRIVING when the driving flag (arg z) is true.
     * Force parked/keyboard-friendly hint selection at the source.
     */
    private fun hookSearchHintResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val kur = findMapsClass(lpparam.classLoader, "kur")
            val contextClass = XposedHelpers.findClass("android.content.Context", lpparam.classLoader)
            val tqv = findMapsClass(lpparam.classLoader, "tqv")
            XposedHelpers.findAndHookMethod(
                kur,
                "aJ",
                contextClass,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                tqv,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        debugEntry("kur.aJ() driving=${param.args[1]} keyboardBlocked=${param.args[4]}")
                        if (param.args[1] == true) {
                            log("kur.aJ() forced driving=false")
                            param.args[1] = false
                        }
                        if (param.args[4] == true) {
                            log("kur.aJ() forced keyboardBlocked=false")
                            param.args[4] = false
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ModulePrefs.isEnabled()) return
                        val hint = param.result as? String ?: return
                        if (!VoicePlateHints.isVoiceOnlyText(hint)) return
                        val context = param.args[0] as android.content.Context
                        val resId = context.resources.getIdentifier(
                            "CAR_SEARCH_HINT",
                            "string",
                            context.packageName
                        )
                        if (resId == 0) return
                        val searchHint = context.getString(resId)
                        debug("kur.aJ() rewritten voice-only hint -> search hint")
                        param.result = searchHint
                    }
                }
            )
            log("Hooked kur.aJ (${kur.name})")
        }.onFailure { log("Failed to hook kur.aJ: ${it.message}") }
    }

    /**
     * qha carries isMicRestricted / isKeyboardRestricted used by qhf.l() tap routing.
     * Hint-only kur.aJ hooks leave these true while driving, so search taps no-op.
     */
    private fun hookHeaderUiState(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val qha = findMapsClass(lpparam.classLoader, "qha")
            XposedBridge.hookAllConstructors(qha, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    if (param.args.size < 5) return
                    if (param.args[3] == true) {
                        log("qha forced isMicRestricted=false")
                        param.args[3] = false
                    }
                    if (param.args[4] == true) {
                        log("qha forced isKeyboardRestricted=false")
                        param.args[4] = false
                    }
                }
            })
            log("Hooked qha constructors (${qha.name})")
        }.onFailure { log("Failed to hook qha: ${it.message}") }
    }

    /** Always open projected keyboard on search-bar tap (rek.d → snp.k → gearhead IME). */
    private fun hookSearchBarTap(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val qhf = findMapsClass(lpparam.classLoader, "qhf")
            val blhx = findMapsClass(lpparam.classLoader, "blhx")
            val blhxDone = XposedHelpers.getStaticObjectField(blhx, "a")
            XposedHelpers.findAndHookMethod(qhf, "l", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    log(">> qhf.l() search tap — opening projected keyboard")
                    val rek = XposedHelpers.getObjectField(param.thisObject, "b")
                    XposedHelpers.callMethod(rek, "d")
                    param.result = blhxDone
                }
            })
            log("Hooked qhf.l (${qhf.name})")
        }.onFailure { log("Failed to hook qhf.l: ${it.message}") }
    }

    private fun hookProjectedKeyboard(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val rel = findMapsClass(lpparam.classLoader, "rel")
            XposedHelpers.findAndHookMethod(rel, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    log(">> rel.d() show keyboard overlay")
                }
            })
            log("Hooked rel.d (${rel.name})")
        }.onFailure { log("Failed to hook rel.d: ${it.message}") }

        runCatching {
            val snp = findMapsClass(lpparam.classLoader, "snp")
            XposedHelpers.findAndHookMethod(snp, "k", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    log(">> snp.k() show car IME")
                }
            })
            XposedHelpers.findAndHookMethod(snp, "j", findMapsClass(lpparam.classLoader, "bisy"), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    log(">> snp.j() bind car input connection")
                }
            })
            log("Hooked snp.j/k (${snp.name})")
        }.onFailure { log("Failed to hook snp: ${it.message}") }
    }

    /** trt.i() feeds keyboard/driving restriction into header view model. */
    private fun hookTrtDrivingFlags(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!ModulePrefs.isEnabled()) return
                if (param.result == true) {
                    debug("trt.i() forced false in ${param.thisObject.javaClass.simpleName}")
                    param.result = false
                }
            }
        }
        for (name in listOf("trp", "tro", "trn", "trq", "trk")) {
            runCatching {
                val clazz = findMapsClass(lpparam.classLoader, name)
                XposedHelpers.findAndHookMethod(clazz, "i", hook)
                log("Hooked $name.i (${clazz.name})")
            }.onFailure { debug("Skip $name.i: ${it.message}") }
        }
    }

    private fun findMapsClass(classLoader: ClassLoader, shortName: String): Class<*> {
        for (name in listOf(shortName, "defpackage.$shortName")) {
            runCatching {
                return XposedHelpers.findClass(name, classLoader)
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
        XposedBridge.log("[$TAG] $message")
    }
}
