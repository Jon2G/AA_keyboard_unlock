package com.jon2g.aa_keyboard_unlock.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Reflection helpers replacing legacy XposedHelpers. */
object Reflect {
    fun findClass(name: String, classLoader: ClassLoader): Class<*> {
        for (candidate in listOf(name, "defpackage.$name")) {
            runCatching {
                return Class.forName(candidate, false, classLoader)
            }
        }
        throw ClassNotFoundException(name)
    }

    fun findClassIfExists(name: String, classLoader: ClassLoader): Class<*>? {
        return runCatching { findClass(name, classLoader) }.getOrNull()
    }

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val clazz = obj?.javaClass ?: throw IllegalArgumentException("null object")
        val method = resolveMethod(clazz, methodName, args.map { it?.javaClass }.toTypedArray())
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        if (args.isEmpty()) {
            val method = clazz.getDeclaredMethod(methodName)
            method.isAccessible = true
            return method.invoke(null)
        }
        val method = resolveMethod(clazz, methodName, args.map { it?.javaClass }.toTypedArray(), static = true)
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        val target = obj ?: throw IllegalArgumentException("null object")
        val field = resolveField(target.javaClass, fieldName)
        field.isAccessible = true
        return field.get(target)
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        val target = obj ?: throw IllegalArgumentException("null object")
        val field = resolveField(target.javaClass, fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    fun getIntField(obj: Any?, fieldName: String): Int {
        val target = obj ?: throw IllegalArgumentException("null object")
        val field = resolveField(target.javaClass, fieldName)
        field.isAccessible = true
        return field.getInt(target)
    }

    fun setIntField(obj: Any?, fieldName: String, value: Int) {
        val target = obj ?: throw IllegalArgumentException("null object")
        val field = resolveField(target.javaClass, fieldName)
        field.isAccessible = true
        field.setInt(target, value)
    }

    fun getBooleanField(obj: Any?, fieldName: String): Boolean {
        val target = obj ?: throw IllegalArgumentException("null object")
        val field = resolveField(target.javaClass, fieldName)
        field.isAccessible = true
        return field.getBoolean(target)
    }

    fun setBooleanField(obj: Any?, fieldName: String, value: Boolean) {
        val target = obj ?: throw IllegalArgumentException("null object")
        val field = resolveField(target.javaClass, fieldName)
        field.isAccessible = true
        field.setBoolean(target, value)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        val field = resolveField(clazz, fieldName)
        field.isAccessible = true
        return field.get(null)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val ctor = resolveConstructor(clazz, args.map { it?.javaClass }.toTypedArray())
        ctor.isAccessible = true
        return ctor.newInstance(*args)
    }

    fun findMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        return clazz.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
    }

    fun findConstructor(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> {
        return clazz.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
    }

    private fun resolveField(clazz: Class<*>, fieldName: String): Field {
        var type: Class<*>? = clazz
        while (type != null) {
            runCatching {
                return type.getDeclaredField(fieldName)
            }
            type = type.superclass
        }
        throw NoSuchFieldException("$fieldName in ${clazz.name}")
    }

    private fun resolveMethod(
        clazz: Class<*>,
        methodName: String,
        argTypes: Array<Class<*>?>,
        static: Boolean = false
    ): Method {
        val normalized = argTypes.map { normalizeType(it) }.toTypedArray()
        var type: Class<*>? = clazz
        while (type != null) {
            runCatching {
                return type.getDeclaredMethod(methodName, *normalized)
            }
            if (static) break
            type = type.superclass
        }
        throw NoSuchMethodException("$methodName in ${clazz.name}")
    }

    private fun resolveConstructor(clazz: Class<*>, argTypes: Array<Class<*>?>): Constructor<*> {
        val normalized = argTypes.map { normalizeType(it) }.toTypedArray()
        return clazz.getDeclaredConstructor(*normalized)
    }

    private fun normalizeType(type: Class<*>?): Class<*> {
        if (type == null) return Any::class.java
        return when (type) {
            java.lang.Integer::class.java -> Int::class.javaPrimitiveType!!
            java.lang.Boolean::class.java -> Boolean::class.javaPrimitiveType!!
            java.lang.Long::class.java -> Long::class.javaPrimitiveType!!
            java.lang.Double::class.java -> Double::class.javaPrimitiveType!!
            java.lang.Float::class.java -> Float::class.javaPrimitiveType!!
            java.lang.Short::class.java -> Short::class.javaPrimitiveType!!
            java.lang.Byte::class.java -> Byte::class.javaPrimitiveType!!
            java.lang.Character::class.java -> Char::class.javaPrimitiveType!!
            else -> type
        }
    }
}
