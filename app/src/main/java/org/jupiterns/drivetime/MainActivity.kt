package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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
        b.username.setText(settings.username)
        b.password.setText(settings.password)
        b.interval.setText(settings.intervalSec.toString())

        b.save.setOnClickListener {
            settings.serverUrl = b.serverUrl.text.toString()
            settings.username = b.username.text.toString()
            settings.password = b.password.text.toString()
            settings.intervalSec = b.interval.text.toString().toIntOrNull() ?: 3
            refreshStatus()
        }
        b.start.setOnClickListener {
            if (!settings.isConfigured) { b.status.text = "Set server URL, username + password first"; return@setOnClickListener }
            ensurePermissions()
            if (!Battery.isExempt(this)) Battery.requestExemption(this)
            Control.apply(this, Control.ACTION_MODE_AUTO)   // start in Auto (light → driving)
            refreshStatus()
        }
        b.stop.setOnClickListener {
            Control.apply(this, Control.ACTION_STOP)
            refreshStatus()
        }
        b.batteryAllow.setOnClickListener { Battery.requestExemption(this) }
        b.batterySettings.setOnClickListener { Battery.openAppSettings(this) }

        b.carBt.setOnClickListener {
            pickBtDevice("Select car Bluetooth",
                onPick = { settings.carBtMac = it.address; settings.carBtName = it.name ?: it.address; updateCarBtLabel() },
                onClear = { settings.carBtMac = ""; settings.carBtName = ""; updateCarBtLabel() })
        }
        b.obdDevice.setOnClickListener {
            pickBtDevice("Select OBD dongle",
                onPick = { settings.obdMac = it.address; settings.obdName = it.name ?: it.address; updateObdLabel() },
                onClear = { settings.obdMac = ""; settings.obdName = ""; updateObdLabel() })
        }

        b.alerts.isChecked = settings.alertsEnabled
        b.alerts.setOnCheckedChangeListener { _, on ->
            settings.alertsEnabled = on
            if (on) AlertWorker.schedule(this) else AlertWorker.cancel(this)
        }

        updateCarBtLabel()
        updateObdLabel()
        refreshStatus()
    }

    private fun updateCarBtLabel() {
        b.carBt.text = "Car Bluetooth: " + (settings.carBtName.ifBlank { "none" })
    }

    private fun updateObdLabel() {
        b.obdDevice.text = "OBD dongle: " + (settings.obdName.ifBlank { "none" })
    }

    @SuppressLint("MissingPermission")
    private fun pickBtDevice(title: String, onPick: (BluetoothDevice) -> Unit, onClear: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            b.status.text = "No paired Bluetooth devices — pair the device first"
            return
        }
        val names = bonded.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i -> onPick(bonded[i]) }
            .setNeutralButton("Clear") { _, _ -> onClear() }
            .show()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val q = Uploader(this, settings).queuedCount()
        b.status.text = when {
            !settings.isConfigured -> "Not configured"
            settings.trackingMode == Settings.MODE_OFF -> "○ Off · $q fix(es) queued"
            else -> {
                val mode = settings.trackingMode.replaceFirstChar { it.uppercase() }
                val tier = LiveState.tier ?: "starting…"
                val why = LiveState.driveReason?.let { " ($it)" } ?: ""
                "● $mode · $tier$why · $q fix(es) queued"
            }
        }
        b.batteryRow.visibility = if (Battery.isExempt(this)) android.view.View.GONE
                                  else android.view.View.VISIBLE
    }

    private fun ensurePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (settings.obdMac.isNotBlank() || settings.carBtMac.isNotBlank()))
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
