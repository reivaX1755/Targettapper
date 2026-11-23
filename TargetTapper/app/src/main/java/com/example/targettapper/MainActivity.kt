package com.example.targettapper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvNotif: TextView
    private lateinit var tvApp: TextView
    private lateinit var btnNotif: Button
    private lateinit var btnStart: Button

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                CaptureService.start(this, res.resultCode, data)
                // Abrimos la app elegida para empezar a jugar.
                Prefs.getPackage(this)
                    ?.let { packageManager.getLaunchIntentForPackage(it) }
                    ?.let { startActivity(it) }
            } else {
                Toast.makeText(this, "Permiso de captura denegado", Toast.LENGTH_SHORT).show()
            }
            refresh()
            tvStatus.postDelayed({ refresh() }, 600)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvAccess = findViewById(R.id.tvAccess)
        tvNotif = findViewById(R.id.tvNotif)
        tvApp = findViewById(R.id.tvApp)
        btnNotif = findViewById(R.id.btnNotif)
        btnStart = findViewById(R.id.btnStart)

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        findViewById<Button>(R.id.btnApp).setOnClickListener { pickApp() }
        btnStart.setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun refresh() {
        val access = TapAccessibilityService.isEnabled(this)
        val notif = hasNotifPermission()
        val pkg = Prefs.getPackage(this)
        val running = CaptureService.running
        val allOk = access && notif && pkg != null

        tvAccess.text = (if (access) "✅" else "❌") + " Servicio de accesibilidad (para pulsar)"
        tvNotif.text = (if (notif) "✅" else "❌") + " Notificaciones"
        btnNotif.isEnabled = !notif
        tvApp.text = if (pkg != null) "✅ App vigilada: ${appLabel(pkg)}" else "❌ Ninguna app seleccionada"

        when {
            running -> {
                tvStatus.text = "🎯 Detectando dianas en ${appLabel(pkg ?: "")}…"
                btnStart.text = "Detener"
                btnStart.isEnabled = true
            }
            allOk -> {
                tvStatus.text = "✅ Todo OK. Pulsa «Iniciar» para empezar."
                btnStart.text = "Iniciar"
                btnStart.isEnabled = true
            }
            else -> {
                tvStatus.text = "Faltan permisos o ajustes (marcados con ❌)"
                btnStart.text = "Iniciar"
                btnStart.isEnabled = false
            }
        }
    }

    private fun onStartClicked() {
        if (CaptureService.running) {
            CaptureService.stop(this)
            btnStart.postDelayed({ refresh() }, 300)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun pickApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        AlertDialog.Builder(this)
            .setTitle("Selecciona la app a vigilar")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                Prefs.setPackage(this, apps[i].first)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
