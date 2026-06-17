package com.jon2g.aa_keyboard_unlock.overlay

import android.app.Activity
import android.app.Application
import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.view.Display
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.jon2g.aa_keyboard_unlock.ModuleLog
import com.jon2g.aa_keyboard_unlock.debug.DisplayDiagnostics
import com.jon2g.aa_keyboard_unlock.xposed.Reflect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full QWERTY overlay injected into the gearhead process car UI when stock projected IME fails.
 */
object ProjectedKeyboardOverlay {
    /**
     * Full-screen root for Presentation.
     * - Touches on the keyboard panel: consumed (never pass through to Maps).
     * - Touches above the keyboard: dismiss overlay (tap-outside-to-close).
     * Uses alpha=1 background so hit-testing works on car displays (fully transparent views often don't).
     */
    private class KeyboardDismissRoot(
        context: Context,
        private val keyboardHeightPx: Int,
        private val onDismiss: () -> Unit
    ) : FrameLayout(context) {
        init {
            // Nearly invisible but participates in hit-testing on virtual/car displays.
            setBackgroundColor(Color.argb(1, 0, 0, 0))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        private fun keyboardChild(): View? {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.VISIBLE) return child
            }
            return null
        }

        private fun keyboardTopY(): Float {
            val panel = keyboardChild()
            if (panel != null && panel.height > 0) {
                return panel.top.toFloat()
            }
            val h = if (height > 0) height else resources.displayMetrics.heightPixels
            return (h - keyboardHeightPx).coerceAtLeast(0).toFloat()
        }

        private fun isInKeyboardBounds(ev: MotionEvent): Boolean = ev.y >= keyboardTopY()

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!isInKeyboardBounds(ev)) {
                if (ev.action == MotionEvent.ACTION_UP) {
                    onDismiss()
                }
                return true
            }
            super.dispatchTouchEvent(ev)
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isInKeyboardBounds(event)) {
                if (event.action == MotionEvent.ACTION_UP) {
                    onDismiss()
                }
                return true
            }
            return true
        }
    }

    /** Keyboard chrome — always consume touches so they never reach the Maps GLES surface below. */
    private class KeyboardPanelLayout(context: Context) : LinearLayout(context) {
        init {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            super.dispatchTouchEvent(ev)
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var attachedRoot: ViewGroup? = null

    @Volatile
    private var keyboardPanel: View? = null

    @Volatile
    private var windowManager: WindowManager? = null

    @Volatile
    private var usingWindowManager = false

    @Volatile
    private var usingViewOverlay = false

    @Volatile
    private var overlayHostDecor: ViewGroup? = null

    @Volatile
    private var overlayDismissRoot: ViewGroup? = null

    @Volatile
    private var carPresentation: Presentation? = null

    @Volatile
    private var usingPresentation = false

    @Volatile
    private var lastShowMs = 0L

    @Volatile
    private var suppressNextOpen = false

    private const val SHOW_DEBOUNCE_MS = 1500L

    private const val SUPPRESS_OPEN_AFTER_TOGGLE_MS = 800L

    private val queryBuilder = StringBuilder()

    private var onSubmit: ((String) -> Unit)? = null

    private val qwertyRows = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M")
    )

    fun show(
        context: Context,
        preferredDisplay: Display? = null,
        hostActivity: Activity? = null,
        onSubmit: (String) -> Unit
    ) {
        runOnMain {
            if (toggleIfVisible(context)) return@runOnMain
            if (shouldSuppressNextOpen()) {
                logOverlay(context, "GH-KBD-002", "show suppressed — keyboard was just toggle-closed")
                return@runOnMain
            }
            try {
                showInternal(context, preferredDisplay, hostActivity, onSubmit)
            } catch (t: Throwable) {
                logOverlay(context, "GH-KBD-004", "show failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** True when the custom overlay is attached on screen. */
    fun isVisible(): Boolean = isVisibleInternal()

    /**
     * Close the overlay when it is already open (search/mic re-tap toggle).
     * @return true if the overlay was visible and is now hidden
     */
    fun toggleIfVisible(context: Context? = null): Boolean {
        if (!isVisibleInternal()) return false
        hideInternal()
        suppressNextOpen = true
        mainHandler.postDelayed({ suppressNextOpen = false }, SUPPRESS_OPEN_AFTER_TOGGLE_MS)
        if (context != null) {
            logOverlay(context, "GH-KBD-002", "toggle hide — keyboard was already open")
        } else {
            ModuleLog.gearhead("GH-KBD-002", "toggle hide — keyboard was already open", always = true)
        }
        return true
    }

    /** Blocks a trailing OPEN broadcast right after toggle-close (gearhead PREPARE + OPEN). */
    fun shouldSuppressNextOpen(): Boolean = suppressNextOpen

    /**
     * Maps car input injects touches via biue.c into the map Presentation, bypassing our overlay window.
     * Intercept there and dispatch into the keyboard shell instead.
     */
    fun dispatchCarTouch(event: MotionEvent): Boolean {
        if (!isVisibleInternal()) return false
        val shell = overlayDismissRoot ?: keyboardPanel ?: return false
        val copy = MotionEvent.obtain(event)
        return runOnMainSync {
            try {
                shell.dispatchTouchEvent(copy)
            } finally {
                copy.recycle()
            }
        }
    }

    private fun runOnMainSync(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            result.set(block())
            latch.countDown()
        }
        latch.await(250, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun isVisibleInternal(): Boolean {
        if (usingPresentation && carPresentation?.isShowing == true) return true
        return keyboardPanel != null &&
            (attachedRoot != null || usingWindowManager || usingViewOverlay)
    }

    private fun showInternal(
        context: Context,
        preferredDisplay: Display?,
        hostActivity: Activity?,
        onSubmit: (String) -> Unit
    ) {
            val now = System.currentTimeMillis()
            if (now - lastShowMs < SHOW_DEBOUNCE_MS) {
                logOverlay(context, "GH-KBD-002", "show debounced (${now - lastShowMs}ms since last)")
                return
            }
            DisplayDiagnostics.dumpDisplays(context)
            DisplayDiagnostics.dumpActivityHosts()
            hideInternal(log = false)
            this.onSubmit = onSubmit
            queryBuilder.clear()

            val panelContext = hostActivity ?: context
            val panel = buildKeyboardPanel(panelContext, hostActivity)
            keyboardPanel = panel

            val onCarDisplay = hostActivity != null &&
                (preferredDisplay?.displayId ?: hostActivity.display?.displayId ?: Display.DEFAULT_DISPLAY) !=
                Display.DEFAULT_DISPLAY
            if (onCarDisplay) {
                if (attachOnMapsCarDisplay(context, hostActivity!!, panel, preferredDisplay, now)) {
                    return
                }
                // Never attach via car/createDisplayContext WindowManager — it ANR'd Maps and
                // tore down GhostActivityDisplay, killing gearhead:projection (see 21:09:55 log).
                keyboardPanel = null
                logOverlay(context, "GH-KBD-004", "Maps car decor attach failed — not using WM fallback")
                return
            }

            val bestHost = DisplayDiagnostics.findBestDecorHost()
            val decorHost = bestHost?.first ?: findDecorHost()
            if (decorHost != null) {
                attachedRoot = decorHost
                usingWindowManager = false
                decorHost.addView(
                    panel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                )
                lastShowMs = now
                val hostInfo = bestHost?.second
                val displayNote = hostInfo?.let { " display=${it.displayId} activity=${it.activityClass}" } ?: ""
                logOverlay(
                    context,
                    "GH-KBD-001",
                    "show overlay on decor ${decorHost.javaClass.simpleName}$displayNote"
                )
                logOverlay(
                    context,
                    "GH-KBD-003",
                    "attach target=decor:${decorHost.javaClass.name}$displayNote"
                )
                return
            }

            if (attachViaWindowManager(context, panel)) {
                usingWindowManager = true
                lastShowMs = now
                logOverlay(context, "GH-KBD-001", "show overlay via WindowManager panel")
                logOverlay(context, "GH-KBD-003", "attach target=WindowManager.TYPE_APPLICATION_PANEL")
                return
            }

            keyboardPanel = null
            logOverlay(context, "GH-KBD-004", "no attach host for overlay")
    }

    fun hide() {
        runOnMain { hideInternal() }
    }

    /** Dismiss without toggle-suppress (navigation / layout change). */
    fun hideForNavigation() {
        runOnMain {
            if (!isVisibleInternal()) return@runOnMain
            if (shouldIgnoreNavigationDismiss()) return@runOnMain
            suppressNextOpen = false
            hideInternal()
        }
    }

    private fun shouldIgnoreNavigationDismiss(): Boolean {
        return System.currentTimeMillis() - lastShowMs < 400L
    }

    private fun hideInternal(log: Boolean = true) {
        when {
            usingPresentation -> {
                runCatching { carPresentation?.dismiss() }
                carPresentation = null
                usingPresentation = false
                overlayDismissRoot = null
            }
            keyboardPanel != null && usingViewOverlay -> {
                val toRemove = overlayDismissRoot ?: keyboardPanel!!
                runCatching { overlayHostDecor?.overlay?.remove(toRemove) }
                usingViewOverlay = false
                overlayHostDecor = null
            }
            keyboardPanel != null && usingWindowManager -> {
                val toRemove = overlayDismissRoot ?: keyboardPanel!!
                runCatching { windowManager?.removeView(toRemove) }
                windowManager = null
                usingWindowManager = false
            }
            keyboardPanel != null -> {
                val toRemove = overlayDismissRoot ?: keyboardPanel!!
                runCatching { (toRemove.parent as? ViewGroup)?.removeView(toRemove) }
                attachedRoot?.let { host ->
                    runCatching { if (toRemove.parent === host) host.removeView(toRemove) }
                }
            }
        }
        keyboardPanel = null
        attachedRoot = null
        overlayHostDecor = null
        overlayDismissRoot = null
        usingViewOverlay = false
        onSubmit = null
        queryBuilder.clear()
        lastShowMs = 0L
        if (log) {
            ModuleLog.gearhead("GH-KBD-002", "hide overlay", always = true)
        }
    }

    private fun dismissFromUserTap() {
        suppressNextOpen = false
        hideInternal()
        ModuleLog.maps("MAPS-KBD-002", "hide overlay — tap outside keyboard", always = true)
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun carKeyboardHeight(context: Context, hostActivity: Activity? = null): Int {
        hostActivity?.let { activity ->
            val frame = android.graphics.Rect()
            activity.window?.decorView?.getWindowVisibleDisplayFrame(frame)
            if (frame.height() in 200..4000) {
                return (frame.height() * 0.55f).toInt()
                    .coerceIn(dp(context, 220), dp(context, 380))
            }
        }
        val metrics = context.resources.displayMetrics
        val height = metrics.heightPixels
        if (height in 200..4000) {
            return (height * 0.55f).toInt().coerceIn(dp(context, 220), dp(context, 380))
        }
        // GhostActivityDisplay reports bogus metrics (e.g. 384640px @ 1dpi) — never use raw values.
        return dp(context, 280)
    }

    private fun bottomLayoutParams(context: Context, hostActivity: Activity? = null): FrameLayout.LayoutParams {
        val host = hostActivity
        if (host != null) {
            val metrics = host.resources.displayMetrics
            val width = metrics.widthPixels.coerceIn(200, 4096)
            val height = carKeyboardHeight(context, host)
            return FrameLayout.LayoutParams(width, height, Gravity.BOTTOM)
        }
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            carKeyboardHeight(context, hostActivity),
            Gravity.BOTTOM
        )
    }

    private fun forcePanelLayout(panel: View, context: Context, hostActivity: Activity, hostView: View) {
        val metrics = hostActivity.resources.displayMetrics
        val panelW = metrics.widthPixels.coerceIn(200, 4096)
        val panelH = carKeyboardHeight(context, hostActivity)
        val hostW = hostView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val hostH = hostView.height.takeIf { it > 0 } ?: metrics.heightPixels
        val top = (hostH - panelH).coerceAtLeast(0)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panelH, View.MeasureSpec.EXACTLY)
        )
        panel.layout(0, top, panelW.coerceAtMost(hostW), top + panelH)
        panel.requestLayout()
    }

    private fun scheduleLayoutProbe(
        context: Context,
        panel: View,
        hostLabel: String,
        hostActivity: Activity? = null
    ) {
        panel.post {
            if ((panel.width == 0 || panel.height == 0) && hostActivity != null) {
                hostActivity.window?.decorView?.let { forcePanelLayout(panel, context, hostActivity, it) }
            }
            val metrics = context.resources.displayMetrics
            logOverlay(
                context,
                "GH-KBD-005",
                "panel measured ${panel.width}x${panel.height} " +
                    "display=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density} host=$hostLabel"
            )
        }
    }

    private fun attachOnMapsCarDisplay(
        context: Context,
        hostActivity: Activity,
        panel: View,
        preferredDisplay: Display?,
        now: Long
    ): Boolean {
        val display = preferredDisplay ?: hostActivity.display ?: return false
        if (display.displayId == Display.DEFAULT_DISPLAY) return false

        detachFromParent(panel)
        // Car head unit composites the maps/tws render display — not GhostActivity window children.
        if (attachViaPresentation(context, hostActivity, panel, now)) {
            scheduleLayoutProbe(context, panel, "tws-Presentation", hostActivity)
            return true
        }
        if (attachViaActivityWindowManager(context, hostActivity, panel, now)) {
            scheduleLayoutProbe(context, panel, "activity-WindowManager", hostActivity)
            return true
        }
        if (attachViaViewOverlay(context, hostActivity, panel, now)) {
            scheduleLayoutProbe(context, panel, "activity-overlay", hostActivity)
            return true
        }
        if (attachToActivityDecor(context, hostActivity, panel, now, hostActivity)) {
            scheduleLayoutProbe(context, panel, "activity-decor", hostActivity)
            return true
        }
        return false
    }

    /** Maps GLES surface uses a dedicated virtual display (…/tws) — what AA mirrors to the car. */
    private fun findMapsRenderDisplay(context: Context, hostActivity: Activity): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
                display.name?.contains("/tws") == true
        }?.let { return it }
        return hostActivity.display
    }

    private fun attachViaPresentation(
        context: Context,
        hostActivity: Activity,
        panel: View,
        now: Long
    ): Boolean {
        if (hostActivity.isFinishing) return false
        val display = findMapsRenderDisplay(context, hostActivity) ?: return false
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        val metrics = hostActivity.resources.displayMetrics
        val panelWidth = metrics.widthPixels.coerceIn(200, 4096)
        val panelHeight = carKeyboardHeight(context, hostActivity)
        return runCatching {
            detachFromParent(panel)
            val shell = attachDismissShell(context, hostActivity, panel, panelHeight)
            val presentation = Presentation(hostActivity, display)
            presentation.setOnDismissListener {
                mainHandler.post {
                    if (usingPresentation) {
                        carPresentation = null
                        usingPresentation = false
                        overlayDismissRoot = null
                        keyboardPanel = null
                        onSubmit = null
                        queryBuilder.clear()
                        lastShowMs = 0L
                    }
                }
            }
            presentation.setContentView(shell)
            configureOverlayWindow(presentation.window)
            presentation.show()
            shell.post {
                shell.requestFocus()
                presentation.window?.decorView?.requestFocus()
                panel.requestFocus()
            }
            carPresentation = presentation
            usingPresentation = true
            usingWindowManager = false
            usingViewOverlay = false
            attachedRoot = null
            overlayHostDecor = null
            windowManager = null
            lastShowMs = now
            logOverlay(
                context,
                "GH-KBD-001",
                "show overlay via Presentation display=${display.displayId} name=${display.name} " +
                    "size=${panelWidth}x$panelHeight"
            )
            logOverlay(
                context,
                "GH-KBD-003",
                "attach target=tws-Presentation:${display.name}:${display.displayId}"
            )
            true
        }.getOrElse {
            logOverlay(context, "GH-KBD-004", "Presentation attach failed: ${it.message}")
            false
        }
    }

    /**
     * Second window on the GhostActivity token — same display as the car UI, above the GLES surface.
     * Uses hostActivity.windowManager (not createDisplayContext) with clamped height to avoid the 21:09 ANR.
     */
    private fun attachViaActivityWindowManager(
        context: Context,
        hostActivity: Activity,
        panel: View,
        now: Long
    ): Boolean {
        if (hostActivity.isFinishing) return false
        val decor = hostActivity.window?.decorView ?: return false
        val token = decor.windowToken ?: return false
        val metrics = hostActivity.resources.displayMetrics
        val panelWidth = metrics.widthPixels.coerceIn(200, 4096)
        val panelHeight = carKeyboardHeight(context, hostActivity)
        return runCatching {
            detachFromParent(panel)
            val shell = attachDismissShell(context, hostActivity, panel, panelHeight)
            val wm = hostActivity.windowManager
            val params = WindowManager.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.BOTTOM
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE
                type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                this.token = token
            }
            wm.addView(shell, params)
            shell.post {
                shell.requestFocus()
                panel.requestFocus()
            }
            windowManager = wm
            usingWindowManager = true
            usingViewOverlay = false
            usingPresentation = false
            attachedRoot = null
            overlayHostDecor = null
            lastShowMs = now
            val displayId = hostActivity.display?.displayId ?: -1
            logOverlay(
                context,
                "GH-KBD-001",
                "show overlay via activity WindowManager display=$displayId size=${panelWidth}x$panelHeight"
            )
            logOverlay(context, "GH-KBD-003", "attach target=activity-WindowManager:$displayId")
            true
        }.getOrElse {
            logOverlay(context, "GH-KBD-004", "activity WM attach failed: ${it.message}")
            false
        }
    }

    /** Draw on GhostActivity decor overlay layer — visible on car display above GLES surface. */
    private fun attachViaViewOverlay(
        context: Context,
        hostActivity: Activity,
        panel: View,
        now: Long
    ): Boolean {
        if (hostActivity.isFinishing) return false
        val decor = hostActivity.window?.decorView as? ViewGroup ?: return false
        if (decor.windowToken == null) return false
        return runCatching {
            detachFromParent(panel)
            val panelHeight = carKeyboardHeight(context, hostActivity)
            val shell = attachDismissShell(context, hostActivity, panel, panelHeight)
            shell.elevation = 24f
            shell.visibility = View.VISIBLE
            shell.isFocusable = true
            shell.isFocusableInTouchMode = true
            shell.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            decor.overlay.add(shell)
            forcePanelLayout(panel, context, hostActivity, decor)
            usingViewOverlay = true
            usingWindowManager = false
            usingPresentation = false
            attachedRoot = null
            overlayHostDecor = decor
            lastShowMs = now
            val displayId = hostActivity.display?.displayId ?: -1
            logOverlay(
                context,
                "GH-KBD-001",
                "show overlay on ${hostActivity.javaClass.simpleName} ViewOverlay display=$displayId"
            )
            logOverlay(
                context,
                "GH-KBD-003",
                "attach target=activity-overlay:${hostActivity.javaClass.name}:$displayId"
            )
            true
        }.getOrElse {
            logOverlay(context, "GH-KBD-004", "activity overlay attach failed: ${it.message}")
            false
        }
    }

    /** GhostActivity decor lives on the car virtual display — attach here when Maps owns the display. */
    private fun attachToActivityDecor(
        context: Context,
        hostActivity: Activity?,
        panel: View,
        now: Long,
        layoutHost: Activity? = hostActivity
    ): Boolean {
        val activity = hostActivity ?: return false
        if (activity.isFinishing) return false
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        if (decor.windowToken == null) return false
        return runCatching {
            detachFromParent(panel)
            val panelHeight = carKeyboardHeight(context, layoutHost ?: activity)
            val shell = attachDismissShell(context, activity, panel, panelHeight)
            shell.elevation = 24f
            shell.visibility = View.VISIBLE
            attachedRoot = decor
            usingWindowManager = false
            decor.addView(
                shell,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            decor.bringChildToFront(shell)
            shell.requestLayout()
            lastShowMs = now
            val displayId = activity.display?.displayId ?: -1
            logOverlay(
                context,
                "GH-KBD-001",
                "show overlay on ${activity.javaClass.simpleName} decor display=$displayId"
            )
            logOverlay(
                context,
                "GH-KBD-003",
                "attach target=activity-decor:${activity.javaClass.name}:$displayId"
            )
            true
        }.getOrElse {
            logOverlay(context, "GH-KBD-004", "activity decor attach failed: ${it.message}")
            false
        }
    }

    private fun buildDismissShell(
        context: Context,
        panel: View,
        panelHeight: Int
    ): KeyboardDismissRoot {
        preparePanelForTouch(panel)
        return KeyboardDismissRoot(context, panelHeight) { dismissFromUserTap() }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelHeight,
                    Gravity.BOTTOM
                )
            )
        }
    }

    private fun attachDismissShell(
        context: Context,
        hostActivity: Activity,
        panel: View,
        panelHeight: Int
    ): KeyboardDismissRoot {
        val shell = buildDismissShell(hostActivity, panel, panelHeight)
        overlayDismissRoot = shell
        return shell
    }

    private fun preparePanelForTouch(panel: View) {
        panel.isClickable = true
        panel.isFocusable = true
        panel.isFocusableInTouchMode = true
    }

    private fun configureOverlayWindow(window: android.view.Window?) {
        window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.decorView.apply {
            setBackgroundColor(Color.argb(1, 0, 0, 0))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun logOverlay(context: Context, eventId: String, message: String) {
        val mapsProcess = context.packageName.contains("maps")
        val id = if (mapsProcess) eventId.replace("GH-KBD", "MAPS-KBD") else eventId
        if (mapsProcess) {
            ModuleLog.maps(id, message, always = true)
        } else {
            ModuleLog.gearhead(id, message, always = true)
        }
    }

    private fun attachViaWindowManager(context: Context, panel: View): Boolean {
        val token = findWindowToken() ?: return false
        return runCatching {
            detachFromParent(panel)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = carKeyboardHeight(context)
                gravity = Gravity.BOTTOM
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                this.token = token
            }
            wm.addView(panel, params)
            windowManager = wm
            true
        }.getOrElse {
            ModuleLog.gearhead("GH-KBD-004", "WindowManager attach failed: ${it.message}", always = true)
            false
        }
    }

    private fun findDecorHost(): ViewGroup? {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(at, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") as? Activity ?: continue
                if (activity.isFinishing) continue
                val decor = activity.window?.decorView as? ViewGroup ?: continue
                if (decor.windowToken != null) return decor
            }
        }
        return null
    }

    private fun findWindowToken(): IBinder? {
        findDecorHost()?.windowToken?.let { return it }
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread")
            val activities = Reflect.getObjectField(at, "mActivities") as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = Reflect.getObjectField(record, "activity") as? Activity ?: continue
                activity.window?.decorView?.windowToken?.let { return it }
            }
        }
        return null
    }

    private fun buildKeyboardPanel(context: Context, hostActivity: Activity? = null): View {
        val keyboardHeight = carKeyboardHeight(context, hostActivity)
        val bottomBarHeight = dp(context, 44)
        val root = KeyboardPanelLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            background = roundedBg(Color.parseColor("#E6121212"), dp(context, 8))
            minimumHeight = keyboardHeight
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeight
            )
        }

        for (row in qwertyRows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            for (key in row) {
                rowLayout.addView(letterKey(context, key))
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                bottomBarHeight
            )
        }
        bottomRow.addView(
            actionKey(context, "Space", 0, weight = 3f) { appendText(" ") }
        )
        bottomRow.addView(
            actionKey(context, "⌫", 0, weight = 1f) { backspace() }
        )
        bottomRow.addView(
            actionKey(context, "Search", 0, weight = 2f) { submitQuery() }
        )
        bottomRow.addView(
            actionKey(context, "✕", 0, weight = 1f) {
                suppressNextOpen = false
                hide()
            }
        )
        root.addView(bottomRow)

        return root
    }

    private fun letterKey(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 20f
            isClickable = true
            isFocusable = true
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBg(Color.parseColor("#FF2D2D2D"), dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            }
            setOnClickListener { appendText(label.lowercase()) }
        }
    }

    private fun actionKey(
        context: Context,
        label: String,
        widthDp: Int,
        weight: Float = 0f,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            textSize = 15f
            isClickable = true
            isFocusable = true
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBg(Color.parseColor("#FF3A3A3A"), dp(context, 4))
            layoutParams = if (weight > 0f) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                    setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
                }
            } else {
                LinearLayout.LayoutParams(widthDp, dp(context, 48)).apply {
                    setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun appendText(text: String) {
        queryBuilder.append(text)
    }

    private fun backspace() {
        if (queryBuilder.isNotEmpty()) {
            queryBuilder.deleteCharAt(queryBuilder.length - 1)
        }
    }

    private fun submitQuery() {
        val query = queryBuilder.toString().trim()
        if (query.isEmpty()) return
        onSubmit?.invoke(query)
        hide()
    }

    private fun roundedBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
