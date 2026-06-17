package com.jon2g.aa_keyboard_unlock.hooks

/** Format stack traces for MAPS-DRIVE-* logs — skip hook framework frames. */
object MapsDrivingTrace {
    private val skipPrefixes = listOf(
        "com.jon2g.aa_keyboard_unlock",
        "de.robv.android.xposed",
        "org.lsposed",
        "java.lang.reflect",
        "dalvik.system",
        "LSPHooker",
        "HookChains",
        "HookParam",
        "MethodHook",
        "MapsHooks\$",
        "MapsDrivingTrace",
    )

    fun formatCallerStack(maxFrames: Int = 8): String {
        val throwableFrames = Throwable().stackTrace.asSequence().drop(1)
        val mapsFrames = throwableFrames
            .filter { frame -> !isSkippedFrame(frame.className) }
            .filter { frame -> isMapsRelevantFrame(frame.className) }
            .take(maxFrames)
            .toList()
        if (mapsFrames.isNotEmpty()) {
            return mapsFrames.joinToString(" <- ") { formatFrame(it) }
        }
        return throwableFrames
            .filter { frame -> !isSkippedFrame(frame.className) }
            .take(6)
            .joinToString(" <- ") { formatFrame(it) }
            .ifEmpty { "no-caller-frames" }
    }

    private fun formatFrame(frame: StackTraceElement): String {
        val cls = frame.className.substringAfterLast('.')
        return "$cls.${frame.methodName}:${frame.lineNumber}"
    }

    private fun isSkippedFrame(className: String): Boolean {
        return skipPrefixes.any { className.contains(it) }
    }

    private fun isMapsRelevantFrame(className: String): Boolean {
        if (isSkippedFrame(className)) return false
        if (className.startsWith("android.") && !className.contains("apps.maps")) return false
        if (className.startsWith("java.")) return false
        if (className.startsWith("kotlin.")) return false
        if (className.startsWith("sun.")) return false
        return className.contains("defpackage") ||
            className.contains("apps.maps") ||
            className.contains("apps.auto")
    }
}
