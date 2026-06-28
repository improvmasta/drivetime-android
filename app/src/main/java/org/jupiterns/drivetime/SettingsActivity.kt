package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The full settings surface — sectioned, with per-row help so nothing's a mystery
 * field. Every routine-controllable knob lives here too, so a user editing in the
 * UI and a routine sending SET are always looking at the same set of options.
 *
 * Backup is JSON via the system file pickers: keys match the routine API SET
 * names, so an exported file is *also* a "settings preset" a Routine could ship.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: Settings
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var serverUrl: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var intervalSec: EditText
    private lateinit var idleIntervalSec: EditText
    private lateinit var lightIntervalSec: EditText
    private lateinit var uploadIntervalSec: EditText
    private lateinit var drivingUploadIntervalSec: EditText
    private lateinit var stationaryStopMin: EditText
    private lateinit var driveBySpeed: CheckBox
    private lateinit var carBt: Button
    private lateinit var obdDevice: Button
    private lateinit var autoTrip: CheckBox
    private lateinit var controlToken: EditText
    private lateinit var alerts: CheckBox
    private lateinit var batteryState: TextView
    private lateinit var batteryAdvice: TextView
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        setContentView(R.layout.activity_settings)
        s = Settings(this)

        serverUrl = findViewById(R.id.serverUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        intervalSec = findViewById(R.id.intervalSec)
        idleIntervalSec = findViewById(R.id.idleIntervalSec)
        lightIntervalSec = findViewById(R.id.lightIntervalSec)
        uploadIntervalSec = findViewById(R.id.uploadIntervalSec)
        drivingUploadIntervalSec = findViewById(R.id.drivingUploadIntervalSec)
        stationaryStopMin = findViewById(R.id.stationaryStopMin)
        driveBySpeed = findViewById(R.id.driveBySpeed)
        carBt = findViewById(R.id.carBt)
        obdDevice = findViewById(R.id.obdDevice)
        autoTrip = findViewById(R.id.autoTrip)
        controlToken = findViewById(R.id.controlToken)
        alerts = findViewById(R.id.alerts)
        batteryState = findViewById(R.id.batteryState)
        batteryAdvice = findViewById(R.id.batteryAdvice)
        testResult = findViewById(R.id.testResult)

        loadFromSettings()
        findViewById<TextView>(R.id.cheatSheet).text = AutomationHelp.cheatSheet()

        findViewById<Button>(R.id.save).setOnClickListener { saveAll(); finish() }
        findViewById<Button>(R.id.testConn).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.batteryExempt).setOnClickListener { Battery.requestExemption(this) }
        findViewById<Button>(R.id.openOemPage).setOnClickListener { OemBatteryLinks.openProtectedAppsPage(this) }

        carBt.setOnClickListener { pickBt("Select car Bluetooth", onPick = { mac, name ->
            s.carBtMac = mac; s.carBtName = name; refreshDeviceLabels()
        }, onClear = { s.carBtMac = ""; s.carBtName = ""; refreshDeviceLabels() }) }
        obdDevice.setOnClickListener { pickBt("Select OBD dongle", onPick = { mac, name ->
            s.obdMac = mac; s.obdName = name; refreshDeviceLabels()
        }, onClear = { s.obdMac = ""; s.obdName = ""; refreshDeviceLabels() }) }

        alerts.setOnCheckedChangeListener { _, on ->
            s.alertsEnabled = on
            if (on) AlertWorker.schedule(this) else AlertWorker.cancel(this)
        }
        autoTrip.setOnCheckedChangeListener { _, on ->
            s.autoTrip = on
            if (on) runCatching { TripDetector.enable(this) }
            else runCatching { TripDetector.disable(this) }
        }

        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { exportTo(it) } }
        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { importFrom(it) } }

        findViewById<Button>(R.id.exportSettings).setOnClickListener {
            saveAll()
            exportLauncher.launch("drivetime-settings.json")
        }
        findViewById<Button>(R.id.importSettings).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    override fun onResume() { super.onResume(); refreshBattery() }

    private fun loadFromSettings() {
        serverUrl.setText(s.serverUrl)
        username.setText(s.username)
        password.setText(s.password)
        intervalSec.setText(s.intervalSec.toString())
        idleIntervalSec.setText(s.idleIntervalSec.toString())
        lightIntervalSec.setText(s.lightIntervalSec.toString())
        uploadIntervalSec.setText(s.uploadIntervalSec.toString())
        drivingUploadIntervalSec.setText(s.drivingUploadIntervalSec.toString())
        stationaryStopMin.setText(s.stationaryStopMin.toString())
        driveBySpeed.isChecked = s.driveBySpeed
        autoTrip.isChecked = s.autoTrip
        controlToken.setText(s.controlToken)
        alerts.isChecked = s.alertsEnabled
        refreshDeviceLabels()
    }

    private fun refreshDeviceLabels() {
        carBt.text = "Car Bluetooth: " + s.carBtName.ifBlank { "none" }
        obdDevice.text = "OBD dongle: " + s.obdName.ifBlank { "none" }
    }

    private fun refreshBattery() {
        val exempt = Battery.isExempt(this)
        val help = OemBatteryLinks.help()
        batteryState.text = if (exempt) "● Battery exemption granted" else "○ Battery exemption not granted"
        batteryState.setTextColor(ContextCompat.getColor(this,
            if (exempt) R.color.status_green else R.color.status_amber))
        batteryAdvice.text = help.advice
        findViewById<Button>(R.id.openOemPage).text = help.label
    }

    private fun saveAll() {
        s.serverUrl = serverUrl.text.toString()
        s.username = username.text.toString()
        s.password = password.text.toString()
        s.intervalSec = intervalSec.text.toString().toIntOrNull() ?: s.intervalSec
        s.idleIntervalSec = idleIntervalSec.text.toString().toIntOrNull() ?: s.idleIntervalSec
        s.lightIntervalSec = lightIntervalSec.text.toString().toIntOrNull() ?: s.lightIntervalSec
        s.uploadIntervalSec = uploadIntervalSec.text.toString().toIntOrNull() ?: s.uploadIntervalSec
        s.drivingUploadIntervalSec = drivingUploadIntervalSec.text.toString().toIntOrNull() ?: s.drivingUploadIntervalSec
        s.stationaryStopMin = stationaryStopMin.text.toString().toIntOrNull() ?: s.stationaryStopMin
        s.driveBySpeed = driveBySpeed.isChecked
        s.autoTrip = autoTrip.isChecked
        s.controlToken = controlToken.text.toString()
        s.alertsEnabled = alerts.isChecked
        snack("Settings saved")
    }

    private fun testConnection() {
        saveAll()
        if (!s.isConfigured) { snack("Set server, username and password first"); return }
        testResult.visibility = View.VISIBLE
        testResult.text = "… testing"
        testResult.setTextColor(ContextCompat.getColor(this, R.color.status_grey))
        Thread {
            val (msg, color) = runCatching {
                val req = Request.Builder()
                    .url(s.ingestUrl)
                    .header("Authorization", s.authHeader)
                    .post("[]".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use {
                    when {
                        it.isSuccessful -> "✓ Connection OK" to R.color.status_green
                        it.code == 401 -> "✕ Auth failed — check username/password" to R.color.status_red
                        else -> "⚠ Server error: HTTP ${it.code}" to R.color.status_amber
                    }
                }
            }.getOrElse { ("✕ Can't reach server: ${it.message ?: "network error"}") to R.color.status_red }
            runOnUiThread {
                testResult.text = msg
                testResult.setTextColor(ContextCompat.getColor(this, color))
            }
        }.start()
    }

    private fun exportTo(uri: Uri) {
        val json = SettingsExport.toJson(s).toString(2)
        runCatching {
            contentResolver.openOutputStream(uri, "w").use { it?.write(json.toByteArray()) }
            snack("Exported")
        }.getOrElse { snack("Export failed: ${it.message}") }
    }

    private fun importFrom(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri).use { it?.bufferedReader()?.readText() } }
            .getOrNull()
        if (text.isNullOrBlank()) { snack("Import failed: empty file"); return }
        val applied = SettingsExport.fromJson(this, s, text)
        if (applied == 0) { snack("Import failed: no recognised keys"); return }
        loadFromSettings()
        snack("Imported $applied settings")
    }

    @SuppressLint("MissingPermission")
    private fun pickBt(title: String, onPick: (mac: String, name: String) -> Unit, onClear: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            snack("Grant Bluetooth permission on the dashboard first")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        // Cheap ELM327 clones connect over *insecure* RFCOMM and never bond, so they're
        // absent from bondedDevices (and from system BT settings entirely). Always offer
        // manual MAC entry so such a dongle can still be selected — never dead-end on an
        // empty paired list.
        val labels = bonded.map { "${it.name}\n${it.address}" } + "Enter MAC address manually"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < bonded.size) { val d = bonded[i]; onPick(d.address, d.name ?: d.address) }
                else promptForMac(title, onPick)
            }
            .setNeutralButton("Clear") { _, _ -> onClear() }
            .show()
    }

    /** Manual MAC entry — the path for an unbonded dongle (the insecure-RFCOMM case)
     *  that never appears in the paired list. LocationService connects by MAC via
     *  getRemoteDevice() + the insecure-socket fallback, so no bonding is needed. */
    private fun promptForMac(title: String, onPick: (mac: String, name: String) -> Unit) {
        val input = EditText(this).apply { hint = "AA:BB:CC:DD:EE:FF"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Enter the adapter's Bluetooth MAC (from Torque, the dongle's label, or a BT scanner app).")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val mac = input.text.toString().trim().uppercase()
                if (BluetoothAdapter.checkBluetoothAddress(mac)) onPick(mac, mac)
                else snack("Invalid MAC — expected AA:BB:CC:DD:EE:FF")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun snack(msg: String) = Snackbar.make(
        findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT
    ).show()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
