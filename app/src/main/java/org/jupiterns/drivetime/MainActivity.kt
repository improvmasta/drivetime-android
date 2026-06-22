package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
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
        b.obdDevice.setOnClickListener { pickObdDevice() }

        b.autoTrip.isChecked = settings.autoTrip
        b.autoTrip.setOnCheckedChangeListener { _, on ->
            settings.autoTrip = on
            if (on) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 3)
                }
                TripDetector.enable(this)
            } else TripDetector.disable(this)
        }

        b.alerts.isChecked = settings.alertsEnabled
        b.alerts.setOnCheckedChangeListener { _, on ->
            settings.alertsEnabled = on
            if (on) AlertWorker.schedule(this) else AlertWorker.cancel(this)
        }

        updateObdLabel()
        refreshStatus()
    }

    private fun updateObdLabel() {
        b.obdDevice.text = "OBD dongle: " + (settings.obdName.ifBlank { "none" })
    }

    @SuppressLint("MissingPermission")
    private fun pickObdDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            b.status.text = "No paired Bluetooth devices — pair the OBD dongle first"
            return
        }
        val names = bonded.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select OBD dongle")
            .setItems(names) { _, i ->
                settings.obdMac = bonded[i].address
                settings.obdName = bonded[i].name ?: bonded[i].address
                updateObdLabel()
            }
            .setNeutralButton("Clear") { _, _ ->
                settings.obdMac = ""; settings.obdName = ""; updateObdLabel()
            }
            .show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.obdMac.isNotBlank())
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
