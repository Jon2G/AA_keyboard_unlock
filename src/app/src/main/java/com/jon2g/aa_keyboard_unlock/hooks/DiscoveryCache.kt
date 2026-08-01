package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jon2g.aa_keyboard_unlock.ModuleLog
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Persists discovered hook targets keyed by target-app [longVersionCode].
 * Full dex scans run only on cache miss (first install, package update, schema bump, or resolve failure).
 *
 * Stored in the **hooked app's** private prefs (gearhead/maps process) — no cross-app IPC.
 */
object DiscoveryCache {
    /** Bump when discovery rules change so stale false-positive caches are discarded. */
    const val SCHEMA_VERSION = 7

    private const val PREFS_NAME = "aa_keyboard_unlock_discovery_v$SCHEMA_VERSION"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_SCHEMA = "schema"

    enum class Namespace(val key: String) {
        GEARHEAD("gearhead"),
        MAPS("maps"),
    }

    data class MemberRef(
        val kind: Kind,
        val className: String,
        val memberName: String = "",
        val paramTypes: List<String> = emptyList(),
        val tag: String = "",
    ) {
        enum class Kind { METHOD, CONSTRUCTOR, CLASS }
    }

    data class CachePayload(
        val members: List<MemberRef>,
    )

    fun packageFingerprint(context: Context, packageName: String): String? {
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val version = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$packageName@$version"
        }.getOrNull()
    }

    fun load(
        context: Context,
        namespace: Namespace,
        fingerprint: String,
    ): CachePayload? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = namespace.key
        val storedFp = prefs.getString("${prefix}_$KEY_FINGERPRINT", null) ?: return null
        val schema = prefs.getInt("${prefix}_$KEY_SCHEMA", 0)
        if (schema != SCHEMA_VERSION || storedFp != fingerprint) return null
        val raw = prefs.getString("${prefix}_$KEY_PAYLOAD", null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun save(
        context: Context,
        namespace: Namespace,
        fingerprint: String,
        payload: CachePayload,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = namespace.key
        prefs.edit()
            .putInt("${prefix}_$KEY_SCHEMA", SCHEMA_VERSION)
            .putString("${prefix}_$KEY_FINGERPRINT", fingerprint)
            .putString("${prefix}_$KEY_PAYLOAD", encode(payload))
            .apply()
    }

    fun clear(context: Context, namespace: Namespace) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = namespace.key
        prefs.edit()
            .remove("${prefix}_$KEY_SCHEMA")
            .remove("${prefix}_$KEY_FINGERPRINT")
            .remove("${prefix}_$KEY_PAYLOAD")
            .apply()
    }

    fun methodRef(method: Method, tag: String = ""): MemberRef =
        MemberRef(
            kind = MemberRef.Kind.METHOD,
            className = method.declaringClass.name,
            memberName = method.name,
            paramTypes = method.parameterTypes.map { it.name },
            tag = tag,
        )

    fun ctorRef(ctor: Constructor<*>, tag: String = ""): MemberRef =
        MemberRef(
            kind = MemberRef.Kind.CONSTRUCTOR,
            className = ctor.declaringClass.name,
            memberName = "<init>",
            paramTypes = ctor.parameterTypes.map { it.name },
            tag = tag,
        )

    fun classRef(clazz: Class<*>, tag: String = ""): MemberRef =
        MemberRef(
            kind = MemberRef.Kind.CLASS,
            className = clazz.name,
            tag = tag,
        )

    fun resolveMethod(classLoader: ClassLoader, ref: MemberRef): Method? {
        if (ref.kind != MemberRef.Kind.METHOD) return null
        return runCatching {
            val clazz = classLoader.loadClass(ref.className)
            val params = ref.paramTypes.map { loadType(classLoader, it) }.toTypedArray()
            clazz.getDeclaredMethod(ref.memberName, *params).also { it.isAccessible = true }
        }.getOrNull()
    }

    fun resolveConstructor(classLoader: ClassLoader, ref: MemberRef): Constructor<*>? {
        if (ref.kind != MemberRef.Kind.CONSTRUCTOR) return null
        return runCatching {
            val clazz = classLoader.loadClass(ref.className)
            val params = ref.paramTypes.map { loadType(classLoader, it) }.toTypedArray()
            clazz.getDeclaredConstructor(*params).also { it.isAccessible = true }
        }.getOrNull()
    }

    fun resolveClass(classLoader: ClassLoader, ref: MemberRef): Class<*>? {
        if (ref.kind != MemberRef.Kind.CLASS) return null
        return runCatching { classLoader.loadClass(ref.className) }.getOrNull()
    }

    fun logHit(process: ModuleLog.Process, namespace: Namespace, fingerprint: String, count: Int) {
        val msg = "discovery cache HIT ns=${namespace.key} fp=$fingerprint members=$count"
        when (process) {
            ModuleLog.Process.MAPS -> ModuleLog.maps("MAPS-DRIVE-010", msg, always = true)
            ModuleLog.Process.GH -> ModuleLog.gearhead("GH-DRIVE-010", msg, always = true)
        }
    }

    fun logMiss(process: ModuleLog.Process, namespace: Namespace, fingerprint: String, reason: String) {
        val msg = "discovery cache MISS ns=${namespace.key} fp=$fingerprint reason=$reason — scanning"
        when (process) {
            ModuleLog.Process.MAPS -> ModuleLog.maps("MAPS-DRIVE-010", msg, always = true)
            ModuleLog.Process.GH -> ModuleLog.gearhead("GH-DRIVE-010", msg, always = true)
        }
    }

    fun logWrite(process: ModuleLog.Process, namespace: Namespace, fingerprint: String, count: Int) {
        val msg = "discovery cache WRITE ns=${namespace.key} fp=$fingerprint members=$count"
        when (process) {
            ModuleLog.Process.MAPS -> ModuleLog.maps("MAPS-DRIVE-010", msg, always = true)
            ModuleLog.Process.GH -> ModuleLog.gearhead("GH-DRIVE-010", msg, always = true)
        }
    }

    private fun encode(payload: CachePayload): String {
        val arr = JSONArray()
        for (m in payload.members) {
            arr.put(
                JSONObject()
                    .put("kind", m.kind.name)
                    .put("class", m.className)
                    .put("name", m.memberName)
                    .put("params", JSONArray(m.paramTypes))
                    .put("tag", m.tag),
            )
        }
        return JSONObject().put("members", arr).toString()
    }

    private fun decode(raw: String): CachePayload {
        val root = JSONObject(raw)
        val arr = root.getJSONArray("members")
        val members = mutableListOf<MemberRef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val paramsJson = o.optJSONArray("params") ?: JSONArray()
            val params = buildList {
                for (j in 0 until paramsJson.length()) add(paramsJson.getString(j))
            }
            members += MemberRef(
                kind = MemberRef.Kind.valueOf(o.getString("kind")),
                className = o.getString("class"),
                memberName = o.optString("name", ""),
                paramTypes = params,
                tag = o.optString("tag", ""),
            )
        }
        return CachePayload(members)
    }

    private fun loadType(classLoader: ClassLoader, name: String): Class<*> {
        return when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            "void" -> Void.TYPE
            else -> classLoader.loadClass(name)
        }
    }
}
