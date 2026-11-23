package com.example.targettapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Servicio en primer plano: captura la pantalla (MediaProjection), busca dianas en cada
 * frame y ordena al servicio de accesibilidad que pulse en ellas.
 */
class CaptureService : Service() {

    private class RecentTap(val x: Int, val y: Int, val radius: Int, val time: Long)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var capW = 0
    private var capH = 0

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private val detector = TargetDetector()
    private val recentTaps = ArrayList<RecentTap>()

    private val sizeChecker = object : Runnable {
        override fun run() {
            checkDisplaySize()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (running) return

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return
        }

        thread = HandlerThread("TargetTapperCapture").also { it.start() }
        handler = Handler(thread.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return
        }
        projection = mp
        // Obligatorio (Android 14+) registrar el callback antes de crear el VirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val dm = realMetrics()
        capW = dm.widthPixels
        capH = dm.heightPixels
        val reader = newReader(capW, capH)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "TargetTapper",
            capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        running = true
        handler.postDelayed(sizeChecker, 1000)
        Log.d(TAG, "Captura iniciada ${capW}x$capH")
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onFrame(it) }, handler)
        return reader
    }

    /** Si la pantalla rota o cambia de tamaño, reconstruye la captura. */
    private fun checkDisplaySize() {
        val dm = realMetrics()
        if (dm.widthPixels == capW && dm.heightPixels == capH) return
        val vd = virtualDisplay ?: return
        capW = dm.widthPixels
        capH = dm.heightPixels
        val old = imageReader
        val reader = newReader(capW, capH)
        imageReader = reader
        vd.resize(capW, capH, dm.densityDpi)
        vd.surface = reader.surface
        old?.close()
        Log.d(TAG, "Captura reconfigurada ${capW}x$capH")
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            // Solo actuamos si la app elegida está en primer plano.
            val svc = TapAccessibilityService.instance ?: return
            val wanted = Prefs.getPackage(this) ?: return
            if (svc.currentPackage() != wanted) return

            val plane = image.planes[0]
            val targets = detector.detect(
                plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride
            )
            if (targets.isEmpty()) return

            val now = SystemClock.uptimeMillis()
            recentTaps.removeAll { now - it.time > COOLDOWN_MS }

            // Evita volver a pulsar la misma diana mientras sigue en pantalla.
            val fresh = targets.filter { t ->
                recentTaps.none {
                    val dx = (it.x - t.x).toFloat()
                    val dy = (it.y - t.y).toFloat()
                    val lim = maxOf(t.radius * 1.5f, 60f)
                    dx * dx + dy * dy < lim * lim
                }
            }
            if (fresh.isEmpty()) return

            Log.d(TAG, "Dianas: " + fresh.joinToString { "(${it.x},${it.y} r=${it.radius})" })
            svc.tap(fresh)
            fresh.forEach { recentTaps.add(RecentTap(it.x, it.y, it.radius, now)) }
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        if (::thread.isInitialized) thread.quitSafely()
        TapAccessibilityService.instance?.hideOverlay()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detección de dianas", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_foreground)
            .setContentTitle("TargetTapper detectando dianas")
            .setContentText("Toca para abrir · usa «Detener» para parar")
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TargetTapper"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "com.example.targettapper.START"
        private const val ACTION_STOP = "com.example.targettapper.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        /** Tiempo durante el que no se vuelve a pulsar en el mismo punto. */
        private const val COOLDOWN_MS = 450L

        @Volatile
        var running = false

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }
}
