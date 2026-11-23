package com.example.targettapper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Capa transparente, no tocable, que dibuja durante un instante un marcador en el punto
 * exacto donde se envía el toque. Usa colores cian/amarillo para no confundirse con las
 * dianas (rojo/blanco) en la captura.
 */
class TapOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val view = MarkerView(service)
    private var attached = false

    private val clearRunnable = Runnable {
        view.markers = emptyList()
        view.invalidate()
    }

    fun flash(points: List<Target>) {
        handler.post {
            ensureAttached()
            view.markers = points
            view.invalidate()
            handler.removeCallbacks(clearRunnable)
            handler.postDelayed(clearRunnable, MARKER_MS)
        }
    }

    fun remove() {
        handler.post {
            handler.removeCallbacks(clearRunnable)
            if (attached) {
                try {
                    wm.removeView(view)
                } catch (_: Exception) {
                }
                attached = false
            }
        }
    }

    private fun ensureAttached() {
        if (attached) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(view, lp)
        attached = true
    }

    private class MarkerView(ctx: Context) : View(ctx) {
        var markers: List<Target> = emptyList()

        private val d = ctx.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(0, 255, 255)
            strokeWidth = 4 * d
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(255, 235, 0)
            strokeWidth = 3 * d
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 235, 0)
            textSize = 14 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val textOutline = Paint(text).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
            color = Color.BLACK
        }
        private val loc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            for (m in markers) {
                val x = m.x - loc[0].toFloat()
                val y = m.y - loc[1].toFloat()
                val r = maxOf(m.radius * 1.25f, 24 * d)
                canvas.drawCircle(x, y, r, ring)
                canvas.drawCircle(x, y, 6 * d, dot)
                val label = "TAP ${m.x},${m.y}"
                canvas.drawText(label, x, y - r - 8 * d, textOutline)
                canvas.drawText(label, x, y - r - 8 * d, text)
            }
        }
    }

    companion object {
        private const val MARKER_MS = 600L
    }
}
