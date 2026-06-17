package com.jon2g.aa_keyboard_unlock.hooks

import android.content.Context
import android.graphics.drawable.Drawable
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.xposed.Reflect

/**
 * Voice Plate search bar shows a mic [CarIcon] that we route to the projected keyboard.
 * Replace it with a keyboard glyph from gearhead, or hide the slot when no drawable is found.
 */
object VoicePlateMicIcon {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    /** gearhead R.drawable — stable across recent builds */
    const val RES_MIC_THEME = 0x7f0803fe
    const val RES_KEYBOARD_BLACK = 0x7f08055b
    const val RES_KEYBOARD_CAPSLOCK = 0x7f0803ed

    private val KEYBOARD_DRAWABLE_NAMES = listOf(
        "ic_keyboard_black_24dp",
        "gs_keyboard_capslock_vd_theme_24",
        "gs_keyboard_capslock_vd_24",
    )

    /** null = hide icon slot */
    fun replaceMicCarIcon(classLoader: ClassLoader, context: Context?, original: Any?): Any? {
        if (original == null) return null
        val ctx = context ?: return null
        return createKeyboardCarIcon(classLoader, ctx) ?: run {
            logHidden("CarIcon")
            null
        }
    }

    /** null = hide icon slot */
    fun replaceMicFyt(classLoader: ClassLoader, context: Context?, original: Any?): Any? {
        if (original == null) return null
        val ctx = context ?: return null
        return createKeyboardFyt(classLoader, ctx) ?: run {
            logHidden("fyt")
            null
        }
    }

    /** null = hide icon slot */
    fun replaceMicFyh(classLoader: ClassLoader, context: Context?, original: Any?): Any? {
        if (original == null) return null
        val ctx = context ?: return null
        return createKeyboardFyh(classLoader, ctx) ?: run {
            logHidden("fyh")
            null
        }
    }

    private fun createKeyboardCarIcon(classLoader: ClassLoader, context: Context): Any? {
        val gearCtx = resolveGearheadContext(context) ?: return null
        val resId = resolveKeyboardDrawableId(gearCtx)
        val carIcon = Reflect.findClass("androidx.car.app.model.CarIcon", classLoader)
        return runCatching {
            Reflect.callStaticMethod(carIcon, "createWithResource", gearCtx, resId)
        }.getOrNull()?.also {
            ModuleLog.gearhead("GH-ICON-001", "Voice Plate mic -> keyboard CarIcon res=0x${resId.toString(16)}", always = true)
        }
    }

    private fun createKeyboardFyt(classLoader: ClassLoader, context: Context): Any? {
        val drawable = loadKeyboardDrawable(context) ?: return null
        val fyt = findGearheadModelClass(classLoader, "fyt")
        return runCatching {
            Reflect.newInstance(fyt, drawable, null, null)
        }.getOrNull()?.also {
            ModuleLog.gearhead("GH-ICON-001", "Voice Plate mic -> keyboard fyt", always = true)
        }
    }

    private fun createKeyboardFyh(classLoader: ClassLoader, context: Context): Any? {
        val drawable = loadKeyboardDrawable(context) ?: return null
        val fyh = findGearheadModelClass(classLoader, "fyh")
        return runCatching {
            Reflect.newInstance(fyh, drawable, null, null)
        }.getOrNull()?.also {
            ModuleLog.gearhead("GH-ICON-001", "Voice Plate mic -> keyboard fyh", always = true)
        }
    }

    private fun loadKeyboardDrawable(context: Context): Drawable? {
        val gearCtx = resolveGearheadContext(context) ?: return null
        val resId = resolveKeyboardDrawableId(gearCtx)
        return runCatching {
            gearCtx.getDrawable(resId)
        }.getOrNull()
    }

    fun resolveKeyboardDrawableId(context: Context?): Int {
        val gearCtx = resolveGearheadContext(context) ?: return RES_KEYBOARD_BLACK
        val res = gearCtx.resources
        for (name in KEYBOARD_DRAWABLE_NAMES) {
            val id = res.getIdentifier(name, "drawable", GEARHEAD_PKG)
            if (id != 0) return id
        }
        return RES_KEYBOARD_BLACK
    }

    private fun resolveGearheadContext(context: Context?): Context? {
        if (context == null) return null
        if (context.packageName == GEARHEAD_PKG) return context
        return runCatching {
            context.createPackageContext(GEARHEAD_PKG, 0)
        }.getOrNull() ?: context
    }

    private fun findGearheadModelClass(classLoader: ClassLoader, shortName: String): Class<*> {
        for (name in listOf(shortName, "defpackage.$shortName")) {
            runCatching {
                return Reflect.findClass(name, classLoader)
            }
        }
        throw ClassNotFoundException(shortName)
    }

    private fun logHidden(layer: String) {
        ModuleLog.gearhead("GH-ICON-002", "Voice Plate mic hidden ($layer — no keyboard drawable)", always = true)
    }
}
