package com.jon2g.aa_keyboard_unlock

import com.jon2g.aa_keyboard_unlock.hooks.GearheadHooks
import com.jon2g.aa_keyboard_unlock.hooks.MapsHooks
import com.jon2g.aa_keyboard_unlock.xposed.HookContext
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class AaKeyboardUnlockModule : XposedModule() {

    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        ModuleLog.bind(this)
        ModuleLog.gearhead(
            "MODULE",
            "onModuleLoaded process=$processName framework=$frameworkName api=$apiVersion",
            always = true
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val ctx = HookContext.from(param, this, processName)
        when (param.packageName) {
            GEARHEAD_PKG -> GearheadHooks.install(ctx)
            MAPS_PKG -> MapsHooks.install(ctx)
        }
    }

    companion object {
        private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
        private const val MAPS_PKG = "com.google.android.apps.maps"
    }
}
