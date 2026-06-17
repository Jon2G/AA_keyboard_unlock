package com.jon2g.aa_keyboard_unlock.hooks

import android.app.Application
import android.content.Context
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.DexHooks
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import java.lang.reflect.Modifier

/** Install-time Maps API / dex probes — diagnostics only, no behavior change. */
object MapsInstallProbe {
    @Volatile
    var voiceOnlyResId: Int = 0
        private set

    @Volatile
    var searchHintResId: Int = 0
        private set

    data class InstallAudit(
        var kurHintHooks: Int = 0,
        var qhfTapHooks: Int = 0,
        var trtHooks: Int = 0,
        var carParamsHooks: Int = 0,
        var snpHooks: Int = 0,
        var voiceBypassHooks: Int = 0,
        var voiceOnlyPathHooks: Int = 0,
        var kurHasAj: Boolean = false,
        var qhfIsInterface: Boolean = false,
        var qhfHasL: Boolean = false,
    )

    fun resolveAtInstall(ctx: HookContext) {
        resolveHintResourceIds(ctx)
    }

    fun resolveFromApplication(app: Application, packageName: String) {
        runCatching {
            val res = app.resources
            voiceOnlyResId = res.getIdentifier("CAR_VOICE_ONLY_WHEN_DRIVING", "string", packageName)
            searchHintResId = res.getIdentifier("CAR_SEARCH_HINT", "string", packageName)
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "hint resIds (app ready) voiceOnly=$voiceOnlyResId searchHint=$searchHintResId",
                always = true
            )
        }.onFailure {
            ModuleLog.maps("MAPS-DRIVE-010", "hint resId resolve failed: ${it.message}", always = true)
        }
    }

    fun run(
        ctx: HookContext,
        audit: InstallAudit,
        targets: MapsSignatureDiscovery.DiscoveredTargets,
    ) {
        probeDrivingStrings(ctx)
        emitInstallAudit(ctx, audit, targets)
    }

    private fun resolveHintResourceIds(ctx: HookContext) {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val app = Reflect.callStaticMethod(at, "currentApplication") as? Context
            val res = app?.resources ?: return
            val pkg = ctx.packageName
            voiceOnlyResId = res.getIdentifier("CAR_VOICE_ONLY_WHEN_DRIVING", "string", pkg)
            searchHintResId = res.getIdentifier("CAR_SEARCH_HINT", "string", pkg)
            ModuleLog.maps(
                "MAPS-DRIVE-010",
                "hint resIds voiceOnly=$voiceOnlyResId searchHint=$searchHintResId pkg=$pkg",
                always = true
            )
        }.onFailure {
            ModuleLog.maps("MAPS-DRIVE-010", "hint resId resolve failed: ${it.message}", always = true)
        }
    }

    private fun probeDrivingStrings(ctx: HookContext) {
        val needles = listOf(
            "CAR_VOICE_ONLY_WHEN_DRIVING",
            "Voice only while driving",
            "CAR_SEARCH_HINT",
        )
        for (path in ctx.sourcePaths) {
            val hits = DexHooks.findClassesReferencingStrings(path, needles, limit = 12)
            if (hits.isNotEmpty()) {
                ModuleLog.maps(
                    "MAPS-DRIVE-010",
                    "dex[$path] driving-string refs: ${hits.joinToString { it.first + "->" + it.second.take(48) }}",
                    always = true
                )
            }
        }
    }

    private fun dumpClassApi(ctx: HookContext, shortName: String, audit: InstallAudit? = null) {
        runCatching {
            val clazz = findMapsClass(ctx.classLoader, shortName)
            val flags = buildList {
                if (clazz.isInterface) add("interface")
                if (Modifier.isAbstract(clazz.modifiers)) add("abstract")
            }
            val header = buildString {
                append(shortName)
                append(" ")
                append(clazz.name)
                if (flags.isNotEmpty()) append(" [${flags.joinToString()}]")
            }
            val ctors = clazz.declaredConstructors.take(6).joinToString(" | ") { ctor ->
                ctor.parameterTypes.joinToString(",") { it.simpleName }.let { params ->
                    "${clazz.simpleName}($params)"
                }
            }
            val methods = clazz.declaredMethods
                .filter { !Modifier.isAbstract(it.modifiers) || clazz.isInterface }
                .take(20)
                .joinToString(" | ") { m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    "${m.name}($params):${m.returnType.simpleName}"
                }
            ModuleLog.maps(
                "MAPS-DRIVE-011",
                "$header ctors=[$ctors] methods=[$methods]",
                always = true
            )
            if (shortName == "kur") {
                audit?.kurHasAj = clazz.declaredMethods.any { it.name == "aJ" }
                val hintMethods = clazz.declaredMethods.filter { isKurHintCandidate(it) }
                ModuleLog.maps(
                    "MAPS-DRIVE-011",
                    "kur hint candidates x${hintMethods.size}: " +
                        hintMethods.joinToString { m ->
                            val sig = m.parameterTypes.joinToString(",") { it.simpleName }
                            "${m.name}($sig)"
                        },
                    always = true
                )
            }
            if (shortName == "qhf" && audit != null) {
                audit.qhfIsInterface = clazz.isInterface
                audit.qhfHasL = clazz.declaredMethods.any { it.name == "l" && it.parameterCount == 0 }
                val lMethods = clazz.declaredMethods.filter { it.name == "l" }
                ModuleLog.maps(
                    "MAPS-DRIVE-011",
                    "qhf l() variants x${lMethods.size}: " +
                        lMethods.joinToString { m ->
                            val abs = if (Modifier.isAbstract(m.modifiers)) "abstract" else "concrete"
                            "$abs(${m.parameterCount} params)->${m.returnType.simpleName}"
                        },
                    always = true
                )
            }
        }.onFailure {
            ModuleLog.maps("MAPS-DRIVE-011", "$shortName class missing: ${it.message}", always = true)
        }
    }

    private fun emitInstallAudit(
        ctx: HookContext,
        audit: InstallAudit,
        targets: MapsSignatureDiscovery.DiscoveredTargets,
    ) {
        ModuleLog.maps(
            "MAPS-DRIVE-003",
            "install audit process=${ctx.processName} " +
                "sigHint=${targets.hintMethods.size} kurHooks=${audit.kurHintHooks} " +
                "sigHeaderTap=${targets.searchHeaderTaps.size} qhfHooks=${audit.qhfTapHooks} " +
                "sigRek=${targets.rekOverlayTypes.size} sigIme=${targets.carImeTypes.size} " +
                "trtHooks=${audit.trtHooks} carParamsHooks=${audit.carParamsHooks} " +
                "snpHooks=${audit.snpHooks} voiceBypassHooks=${audit.voiceBypassHooks} " +
                "voiceOnlyPathHooks=${audit.voiceOnlyPathHooks} " +
                "voiceOnlyResId=$voiceOnlyResId",
            always = true
        )
    }

    fun isHintCandidateStrict(method: java.lang.reflect.Method): Boolean {
        if (!Modifier.isStatic(method.modifiers)) return false
        if (method.returnType != String::class.java) return false
        val params = method.parameterTypes
        if (params.isEmpty() || params[0] != Context::class.java) return false
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        return params.count { it == booleanPrimitive || it == Boolean::class.java } >= 2
    }

    /** Static String(…, 2+ booleans) without requiring Context as first arg. */
    fun isHintCandidateLoose(method: java.lang.reflect.Method): Boolean {
        if (isHintCandidateStrict(method)) return false
        if (!Modifier.isStatic(method.modifiers)) return false
        if (method.returnType != String::class.java) return false
        val params = method.parameterTypes
        if (params.isEmpty()) return false
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        val boolCount = params.count { it == booleanPrimitive || it == Boolean::class.java }
        if (boolCount < 2) return false
        val first = params[0]
        if (first.isPrimitive || first == String::class.java) return false
        return true
    }

    fun isKurHintCandidate(method: java.lang.reflect.Method): Boolean = isHintCandidateStrict(method)

    fun isRekLikeType(type: Class<*>): Boolean {
        return type.methods.any { it.name == "d" && it.parameterTypes.isEmpty() } &&
            type.methods.any { it.name == "e" && it.parameterTypes.isNotEmpty() }
    }

    private fun findMapsClass(classLoader: ClassLoader, shortName: String): Class<*> {
        for (name in listOf(shortName, "defpackage.$shortName")) {
            runCatching {
                return Reflect.findClass(name, classLoader)
            }
        }
        throw ClassNotFoundException(shortName)
    }
}
