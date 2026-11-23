package com.example.targettapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad: hace dos cosas
 *  - Sabe qué app está en primer plano (para vigilar solo la app elegida).
 *  - Envía los toques (dispatchGesture) y dibuja el marcador del toque.
 */
class TapAccessibilityService : AccessibilityService() {

    private var overlay: TapOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = TapOverlay(this)
        foregroundPackage = rootInActiveWindow?.packageName?.toString()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
        foregroundPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        overlay?.remove()
        overlay = null
        instance = null
        return super.onUnbind(intent)
    }

    /** Paquete de la app en primer plano (o null si no se conoce). */
    fun currentPackage(): String? =
        foregroundPackage ?: rootInActiveWindow?.packageName?.toString()

    /** Pulsa en cada punto (en un único gesto multitáctil) y muestra el marcador. */
    fun tap(points: List<Target>) {
        if (points.isEmpty()) return
        val n = minOf(points.size, GestureDescription.getMaxStrokeCount())
        val builder = GestureDescription.Builder()
        for (i in 0 until n) {
            val path = Path().apply { moveTo(points[i].x.toFloat(), points[i].y.toFloat()) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
        }
        dispatchGesture(builder.build(), null, null)
        overlay?.flash(points.take(n))
    }

    fun hideOverlay() {
        overlay?.remove()
    }

    companion object {
        private const val TAP_DURATION_MS = 40L

        @Volatile
        var instance: TapAccessibilityService? = null

        @Volatile
        var foregroundPackage: String? = null

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(ctx, TapAccessibilityService::class.java)
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }
    }
}
