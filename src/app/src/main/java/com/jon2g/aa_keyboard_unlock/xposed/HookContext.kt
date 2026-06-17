package com.jon2g.aa_keyboard_unlock.xposed

import dalvik.system.BaseDexClassLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Per-process hook runtime passed into gearhead/maps installers. */
class HookContext(
    val xposed: XposedInterface,
    val classLoader: ClassLoader,
    val packageName: String,
    val processName: String,
    val sourcePath: String,
    val sourcePaths: List<String> = listOf(sourcePath)
) {
    val dexClassLoader: BaseDexClassLoader
        get() = classLoader as BaseDexClassLoader

    companion object {
        fun from(param: PackageReadyParam, xposed: XposedInterface, processName: String): HookContext {
            val info = param.applicationInfo
            val paths = buildList {
                add(info.sourceDir)
                info.splitSourceDirs?.forEach { split -> add(split) }
            }
            return HookContext(
                xposed = xposed,
                classLoader = param.classLoader,
                packageName = param.packageName,
                processName = processName,
                sourcePath = info.sourceDir,
                sourcePaths = paths
            )
        }
    }
}
