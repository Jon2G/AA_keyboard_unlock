package com.jon2g.aa_keyboard_unlock.hooks

import android.view.View
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

object MapsHooks {
    @Volatile
    private var installedForProcess = false

    @Volatile
    private var kurAjHookLogged = false

    /** Latest rel overlay instance — used when AA routes search through assistant gRPC (aoeb.l). */
    @Volatile
    private var lastRel: WeakReference<Any>? = null

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
        ModuleLog.install(
            ModuleLog.Process.MAPS,
            "enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()} pkg=${lpparam.packageName}"
        )
        hookSearchHintResolver(lpparam)
        hookHeaderUiState(lpparam)
        hookDistractionState(lpparam)
        hookSearchBarTap(lpparam)
        hookProjectedKeyboard(lpparam)
        hookMapsVoiceSession(lpparam)
        hookTrtDrivingFlags(lpparam)
        ModuleLog.maps("MAPS-INSTALL", "hooks installed for ${lpparam.packageName}", always = true)
    }

    /**
     * kur hint resolver — method name obfuscates between Maps builds (aJ may be absent).
     * Match static String methods taking Context + multiple boolean flags.
     */
    private fun hookSearchHintResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val kur = findMapsClass(lpparam.classLoader, "kur")
            val contextClass = android.content.Context::class.java
            val booleanPrimitive = Boolean::class.javaPrimitiveType!!
            var hooked = 0
            for (method in kur.declaredMethods) {
                if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != String::class.java) continue
                val params = method.parameterTypes
                if (params.isEmpty() || params[0] != contextClass) continue
                if (params.count { it == booleanPrimitive } < 2) continue
                XposedBridge.hookMethod(method, kurAjHook())
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
            XposedHelpers.findAndHookMethod(
                android.content.res.Resources::class.java,
                "getString",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
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
                }
            )
            log("Hooked Maps Resources.getString hint rewrite")
        }.onFailure { log("Failed to hook Maps Resources.getString: ${it.message}") }
    }

    private fun kurAjHook() = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
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

        override fun afterHookedMethod(param: MethodHookParam) {
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
    private fun hookHeaderUiState(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val qha = findMapsClass(lpparam.classLoader, "qha")
            XposedBridge.hookAllConstructors(qha, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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
    private fun hookDistractionState(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val qwt = findMapsClass(lpparam.classLoader, "qwt")
            XposedBridge.hookAllConstructors(qwt, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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

    /**
     * Search bar taps: l() = keyboard/voice router, k() = voice shortcut, i() = mic action.
     * All redirect to projected keyboard (rek/rel → snp) instead of pub.s() voice dictation.
     */
    private fun hookSearchBarTap(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val qhf = findMapsClass(lpparam.classLoader, "qhf")
            val blhxDone = blhxDone(lpparam)
            val keyboardTap = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val method = param.method.name
                    ModuleLog.maps("MAPS-001", "qhf.$method() — opening projected keyboard", always = true)
                    val rek = XposedHelpers.getObjectField(param.thisObject, "b")
                    openProjectedKeyboard(rek)
                    param.result = blhxDone
                }
            }
            for (method in listOf("l", "k", "i")) {
                XposedHelpers.findAndHookMethod(qhf, method, keyboardTap)
            }
            log("Hooked qhf.l/k/i (${qhf.name})")
        }.onFailure { log("Failed to hook qhf tap methods: ${it.message}") }
    }

    /**
     * Maps-only voice dictation block (gearhead voice assistant hooks stay disabled).
     * AA search often uses NavAssistantCallbacks gRPC → aoeb.l → voice session, bypassing qhf.
     */
    private fun hookMapsVoiceSession(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val aoeb = findMapsClass(lpparam.classLoader, "aoeb")
            XposedHelpers.findAndHookMethod(aoeb, "l", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    val trigger = param.args[0] as Int
                    ModuleLog.maps("MAPS-001", "aoeb.l($trigger) blocked — opening projected keyboard", always = true)
                    openFromLastRel()
                    param.result = null
                }
            })
            log("Hooked aoeb.l (${aoeb.name})")
        }.onFailure { log("Failed to hook aoeb.l: ${it.message}") }

        runCatching {
            val aodo = findMapsClass(lpparam.classLoader, "aodo")
            XposedHelpers.findAndHookMethod(aodo, "m", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    ModuleLog.maps("MAPS-001", "aodo.m(${param.args[0]}) blocked voice session", always = true)
                    openFromLastRel()
                    param.result = null
                }
            })
            log("Hooked aodo.m (${aodo.name})")
        }.onFailure { log("Failed to hook aodo.m: ${it.message}") }

        runCatching {
            val rzb = findMapsClass(lpparam.classLoader, "rzb")
            XposedHelpers.findAndHookMethod(rzb, "s", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    ModuleLog.maps("MAPS-001", "pub.s() via rzb blocked — opening projected keyboard", always = true)
                    openFromLastRel()
                    param.result = null
                }
            })
            log("Hooked rzb.s (${rzb.name})")
        }.onFailure { log("Failed to hook rzb.s: ${it.message}") }
    }

    private fun hookProjectedKeyboard(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val rel = findMapsClass(lpparam.classLoader, "rel")
            XposedHelpers.findAndHookMethod(rel, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    lastRel = WeakReference(param.thisObject)
                    debugEntry("rel.d() show keyboard overlay")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    scheduleShowCarIme(param.thisObject)
                }
            })
            log("Hooked rel.d (${rel.name})")
        }.onFailure { log("Failed to hook rel.d: ${it.message}") }

        runCatching {
            val snp = findMapsClass(lpparam.classLoader, "snp")
            XposedHelpers.findAndHookMethod(snp, "k", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("snp.k() show car IME")
                }
            })
            XposedHelpers.findAndHookMethod(snp, "j", findMapsClass(lpparam.classLoader, "bisy"), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModulePrefs.isEnabled()) return
                    debugEntry("snp.j() bind car input connection")
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

    private fun openProjectedKeyboard(rek: Any) {
        lastRel = WeakReference(rek)
        runCatching {
            XposedHelpers.callMethod(rek, "d")
        }.onFailure {
            log("rek.d() failed: ${it.message}")
            return
        }
        scheduleShowCarIme(rek)
    }

    private fun openFromLastRel() {
        val rel = lastRel?.get()
        if (rel == null) {
            ModuleLog.maps("MAPS-004", "No cached rel overlay for keyboard open", always = true)
            return
        }
        openProjectedKeyboard(rel)
    }

    /** rel.d() binds car input (snp.j); reh.b() shows projection IME (snp.k). */
    private fun scheduleShowCarIme(rel: Any) {
        val showIme = Runnable {
            runCatching {
                val reh = XposedHelpers.getObjectField(rel, "c")
                XposedHelpers.callMethod(reh, "b")
                debug("reh.b() requested car IME")
            }.onFailure { log("reh.b() failed: ${it.message}") }
        }
        val anchor = runCatching { XposedHelpers.callMethod(rel, "f") as? View }.getOrNull()
        if (anchor != null) {
            anchor.post(showIme)
        } else {
            showIme.run()
        }
    }

    private fun blhxDone(lpparam: XC_LoadPackage.LoadPackageParam): Any {
        val blhx = findMapsClass(lpparam.classLoader, "blhx")
        return XposedHelpers.getStaticObjectField(blhx, "a")
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
        ModuleLog.maps("HOOK", message, always = true)
    }
}
