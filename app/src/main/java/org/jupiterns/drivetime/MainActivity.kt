package org.jupiterns.drivetime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.jupiterns.drivetime.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        b.serverUrl.setText(settings.serverUrl)
        b.token.setText(settings.token)
        b.interval.setText(settings.intervalSec.toString())

        b.save.setOnClickListener {
            settings.serverUrl = b.serverUrl.text.toString()
            settings.token = b.token.text.toString()
            settings.intervalSec = b.interval.text.toString().toIntOrNull() ?: 3
            refreshStatus()
        }
        b.start.setOnClickListener {
            if (!settings.isConfigured) { b.status.text = "Set server URL + token first"; return@setOnClickListener }
            ensurePermissions()
            if (!Battery.isExempt(this)) Battery.requestExemption(this)
            startForegroundService(Intent(this, LocationService::class.java))
            refreshStatus()
        }
        b.stop.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            refreshStatus()
        }
        b.batteryAllow.setOnClickListener { Battery.requestExemption(this) }
        b.batterySettings.setOnClickListener { Battery.openAppSettings(this) }
        refreshStatus()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val q = Uploader(this, settings).queuedCount()
        b.status.text = when {
            !settings.isConfigured -> "Not configured"
            settings.loggingEnabled -> "● Logging · ${q} fix(es) queued"
            else -> "○ Idle · ${q} fix(es) queued"
        }
        b.batteryRow.visibility = if (Battery.isExempt(this)) android.view.View.GONE
                                  else android.view.View.VISIBLE
    }

    private fun ensurePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
