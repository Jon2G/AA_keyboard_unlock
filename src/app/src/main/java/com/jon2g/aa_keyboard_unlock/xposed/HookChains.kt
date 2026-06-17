package com.jon2g.aa_keyboard_unlock.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Drop-in style replacement for XC_MethodHook.MethodHookParam. */
class HookParam internal constructor(
    private val chain: XposedInterface.Chain,
    val phase: Phase
) {
    enum class Phase { BEFORE, AFTER }

    val method: Executable
        get() = chain.executable

    private var resultExplicit = false
    private var explicitResult: Any? = null
    private var throwableValue: Throwable? = null
    private val argBuffer: Array<Any?> = chain.args.toTypedArray()

    val thisObject: Any?
        get() = chain.thisObject

    val args: Array<Any?>
        get() = argBuffer

    var result: Any?
        get() = if (resultExplicit) explicitResult else null
        set(value) {
            resultExplicit = true
            explicitResult = value
        }

    val resultWasSet: Boolean
        get() = resultExplicit

    var throwable: Throwable?
        get() = throwableValue
        set(value) {
            throwableValue = value
        }

    fun hasThrowable(): Boolean = throwableValue != null

    fun setArg(index: Int, value: Any?) {
        argBuffer[index] = value
    }
}

/** Legacy-style hook callback mapped to libxposed interceptor chains. */
abstract class MethodHook {
    open fun beforeHookedMethod(param: HookParam) {}
    open fun afterHookedMethod(param: HookParam) {}
}

abstract class MethodReplacement : MethodHook() {
    abstract fun replaceHookedMethod(param: HookParam): Any?

    final override fun beforeHookedMethod(param: HookParam) {
        param.result = replaceHookedMethod(param)
    }
}

object HookChains {
    fun findAndHookMethod(
        xposed: XposedInterface,
        clazz: Class<*>,
        methodName: String,
        hook: MethodHook
    ): HookHandle {
        val method = clazz.getDeclaredMethod(methodName)
        method.isAccessible = true
        return hookMethod(xposed, method, hook)
    }

    fun findAndHookMethod(
        xposed: XposedInterface,
        clazz: Class<*>,
        methodName: String,
        hook: MethodHook,
        vararg parameterTypes: Class<*>
    ): HookHandle {
        val method = Reflect.findMethod(clazz, methodName, *parameterTypes)
        return hookMethod(xposed, method, hook)
    }

    fun findAndHookMethod(
        xposed: XposedInterface,
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        hook: MethodHook
    ): HookHandle {
        val clazz = Reflect.findClass(className, classLoader)
        val method = clazz.getDeclaredMethod(methodName)
        method.isAccessible = true
        return hookMethod(xposed, method, hook)
    }

    fun findAndHookMethod(
        xposed: XposedInterface,
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        hook: MethodHook,
        vararg parameterTypes: Class<*>
    ): HookHandle {
        val clazz = Reflect.findClass(className, classLoader)
        return findAndHookMethod(xposed, clazz, methodName, hook, *parameterTypes)
    }

    fun findAndHookConstructor(
        xposed: XposedInterface,
        clazz: Class<*>,
        hook: MethodHook,
        vararg parameterTypes: Class<*>
    ): HookHandle {
        val ctor = Reflect.findConstructor(clazz, *parameterTypes)
        return hookExecutable(xposed, ctor, hook)
    }

    fun hookAllMethods(
        xposed: XposedInterface,
        clazz: Class<*>,
        methodName: String,
        hook: MethodHook
    ): List<HookHandle> {
        val handles = mutableListOf<HookHandle>()
        var type: Class<*>? = clazz
        while (type != null) {
            for (method in type.declaredMethods) {
                if (method.name == methodName && !Modifier.isAbstract(method.modifiers)) {
                    runCatching {
                        handles += hookMethod(xposed, method, hook)
                    }
                }
            }
            type = type.superclass
        }
        return handles
    }

    fun hookAllConstructors(
        xposed: XposedInterface,
        clazz: Class<*>,
        hook: MethodHook
    ): List<HookHandle> {
        return clazz.declaredConstructors.mapNotNull { ctor ->
            runCatching { hookExecutable(xposed, ctor, hook) }.getOrNull()
        }
    }

    fun hookMethod(xposed: XposedInterface, method: Method, hook: MethodHook): HookHandle {
        return hookExecutable(xposed, method, hook)
    }

    fun invokeOriginal(xposed: XposedInterface, param: HookParam): Any? {
        val method = param.method as Method
        return xposed.getInvoker(method)
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(param.thisObject, *param.args)
    }

    fun hookExecutable(xposed: XposedInterface, executable: Executable, hook: MethodHook): HookHandle {
        return xposed.hook(executable).intercept { chain ->
            val beforeParam = HookParam(chain, HookParam.Phase.BEFORE)
            runCatching {
                hook.beforeHookedMethod(beforeParam)
            }.onFailure { throwable ->
                throw throwable
            }
            if (beforeParam.resultWasSet) {
                val afterParam = HookParam(chain, HookParam.Phase.AFTER)
                afterParam.result = beforeParam.result
                runCatching {
                    hook.afterHookedMethod(afterParam)
                }.onFailure { throwable ->
                    throw throwable
                }
                return@intercept if (afterParam.resultWasSet) afterParam.result else beforeParam.result
            }

            val result = runCatching {
                chain.proceed(beforeParam.args)
            }.getOrElse { throwable ->
                val afterParam = HookParam(chain, HookParam.Phase.AFTER)
                afterParam.throwable = throwable
                runCatching {
                    hook.afterHookedMethod(afterParam)
                }
                throw throwable
            }

            val afterParam = HookParam(chain, HookParam.Phase.AFTER)
            afterParam.result = result
            runCatching {
                hook.afterHookedMethod(afterParam)
            }.onFailure { throwable ->
                throw throwable
            }
            if (afterParam.resultWasSet) afterParam.result else result
        }
    }
}
