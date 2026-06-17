package com.jon2g.aa_keyboard_unlock

import com.jon2g.aa_keyboard_unlock.hooks.GearheadHooks
import com.jon2g.aa_keyboard_unlock.hooks.MapsHooks
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
        private const val MAPS_PKG = "com.google.android.apps.maps"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            GEARHEAD_PKG -> {
                ModuleLog.gearhead(
                    "LOAD",
                    "loading hooks in $GEARHEAD_PKG (enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()})",
                    always = true
                )
                GearheadHooks.install(lpparam)
            }
            MAPS_PKG -> {
                ModuleLog.maps(
                    "LOAD",
                    "loading hooks in $MAPS_PKG (enabled=${ModulePrefs.isEnabled()} debug=${ModulePrefs.isDebug()})",
                    always = true
                )
                MapsHooks.install(lpparam)
            }
        }
    }
}
