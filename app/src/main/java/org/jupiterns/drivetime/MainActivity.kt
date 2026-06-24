package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jupiterns.drivetime.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

/**
 * Live dashboard: a single Tracking switch (the one thing you flip), a glanceable
 * status line, a colour-coded connection card, optional OBD vitals, actionable
 * warnings, and a setup section. Refreshes once a second while visible from
 * [LiveState] + [Uploader.health], so what you see matches what the logger is doing.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private val ui = Handler(Looper.getMainLooper())
    private val uploader by lazy { Uploader(this, settings) }

    private var updatingSwitch = false
    private var warningAction: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
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
            toast("Settings saved")
            refresh()
        }

        b.trackingSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) startTracking()
            else { Control.apply(this, Control.ACTION_STOP); toast("Tracking off") }
            refresh()
        }

        b.modeAuto.setOnClickListener { forceMode(Control.ACTION_MODE_AUTO, "Auto") }
        b.modeDriving.setOnClickListener { forceMode(Control.ACTION_MODE_DRIVING, "Driving (forced)") }
        b.modeEco.setOnClickListener { forceMode(Control.ACTION_MODE_ECO, "Eco (light only)") }

        b.testConn.setOnClickListener { testConnection() }
        b.viewLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        b.warningAction.setOnClickListener { warningAction?.invoke() }

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
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---- actions ----

    private fun startTracking() {
        if (!settings.isConfigured) {
            toast("Enter server URL, username + password below, then Save")
            scrollToSetup()
            return
        }
        ensurePermissions()
        if (!Battery.isExempt(this)) Battery.requestExemption(this)
        Control.apply(this, Control.ACTION_MODE_AUTO)
        toast("Tracking on — Auto mode")
    }

    private fun forceMode(action: String, label: String) {
        if (!settings.isConfigured) { toast("Finish setup first"); scrollToSetup(); return }
        ensurePermissions()
        Control.apply(this, action)
        toast("Mode: $label")
        refresh()
    }

    private fun testConnection() {
        if (!settings.isConfigured) { toast("Enter server + login first"); scrollToSetup(); return }
        b.testConn.isEnabled = false
        b.connState.text = "… testing"
        b.connState.setTextColor(col(R.color.status_grey))
        Thread {
            val (msg, color) = runCatching {
                val req = Request.Builder()
                    .url(settings.ingestUrl)
                    .header("Authorization", settings.authHeader)
                    .post("[]".toRequestBody("application/json".toMediaType()))
                    .build()
                testClient.newCall(req).execute().use {
                    when {
                        it.isSuccessful -> "✓ Connection OK" to R.color.status_green
                        it.code == 401 -> "✕ Auth failed — check username/password" to R.color.status_red
                        else -> "⚠ Server error: HTTP ${it.code}" to R.color.status_amber
                    }
                }
            }.getOrElse { ("✕ Can't reach server: ${it.message ?: "network error"}") to R.color.status_red }
            ui.post {
                b.testConn.isEnabled = true
                b.connState.text = msg
                b.connState.setTextColor(col(color))
                Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
                EventLog.info("Test connection: $msg")
            }
        }.start()
    }

    // ---- live refresh ----

    private fun refresh() {
        refreshWarnings()
        refreshStatus()
        refreshConnection()
        refreshObd()
    }

    private fun refreshStatus() {
        val on = settings.trackingMode != Settings.MODE_OFF
        updatingSwitch = true
        b.trackingSwitch.isChecked = on
        updatingSwitch = false

        if (!on) {
            b.statusState.text = "○ Tracking off"
            b.statusState.setTextColor(col(R.color.status_grey))
            b.statusDetail.text = if (settings.isConfigured) "Ready — turn on to start logging"
            else "Finish setup below first"
            return
        }
        val (label, color) = when (LiveState.tier) {
            "DRIVING" -> "● Driving" to R.color.status_green
            "LIGHT" -> "● Light tracking" to R.color.status_blue
            else -> "● Starting…" to R.color.status_amber
        }
        b.statusState.text = label
        b.statusState.setTextColor(col(color))
        val bits = mutableListOf<String>()
        LiveState.driveReason?.let { bits.add(it) }
        LiveState.speedMph?.let { bits.add("$it mph") }
        if (LiveState.updatedAt > 0) bits.add("fix ${rel(LiveState.updatedAt)}")
        b.statusDetail.text = bits.joinToString(" · ").ifEmpty { "waiting for first fix…" }
    }

    private fun refreshConnection() {
        val h = uploader.health()
        val now = System.currentTimeMillis()
        when {
            h.lastError != null && h.failures > 0 -> {
                val auth = h.lastError.contains("Auth", ignoreCase = true)
                b.connState.text = if (auth) "✕ Auth failed" else "⚠ Reconnecting…"
                b.connState.setTextColor(col(if (auth) R.color.status_red else R.color.status_amber))
                val retry = if (h.backoffUntil > now) " · retry in ${(h.backoffUntil - now) / 1000}s" else ""
                b.connDetail.text = "${h.queued} fix(es) waiting$retry"
                b.connError.visibility = View.VISIBLE
                b.connError.text = if (auth) "Uploads are being rejected. Fix your username/password above and Save."
                else "Last error: ${h.lastError}"
            }
            h.lastSuccessAt > 0 -> {
                b.connState.text = "✓ Connected"
                b.connState.setTextColor(col(R.color.status_green))
                b.connDetail.text = "Last upload ${rel(h.lastSuccessAt)} · ${h.queued} queued"
                b.connError.visibility = View.GONE
            }
            else -> {
                b.connState.text = "• Not connected yet"
                b.connState.setTextColor(col(R.color.status_grey))
                b.connDetail.text = "${h.queued} queued · no upload yet — try Test connection"
                b.connError.visibility = View.GONE
            }
        }
    }

    private fun refreshObd() {
        if (settings.obdMac.isBlank()) { b.obdCard.visibility = View.GONE; return }
        b.obdCard.visibility = View.VISIBLE
        if (LiveState.obdConnected) {
            b.obdState.text = "● OBD connected"
            b.obdState.setTextColor(col(R.color.status_green))
            val bits = mutableListOf<String>()
            LiveState.rpm?.let { bits.add("$it rpm") }
            LiveState.coolantC?.let { bits.add("$it°C") }
            LiveState.voltage?.let { bits.add(String.format("%.1fV", it)) }
            b.obdDetail.text = bits.joinToString(" · ").ifEmpty { "reading…" }
        } else {
            b.obdState.text = "○ OBD not connected"
            b.obdState.setTextColor(col(R.color.status_grey))
            b.obdDetail.text = settings.obdName.ifBlank { "Dongle configured" } + " · connects when you start driving"
        }
    }

    private fun refreshWarnings() {
        val w: Pair<String, () -> Unit>? = when {
            !settings.isConfigured ->
                "Finish setup: enter your server URL and login below, then Save." to { scrollToSetup() }
            !hasLocationPermission() ->
                "Location permission is required to log drives." to { ensurePermissions() }
            !Battery.isExempt(this) ->
                "Allow background battery use so logging isn't killed mid-drive." to { Battery.requestExemption(this) }
            !hasNotifications() ->
                "Enable notifications to see the live logging status." to { Battery.openAppSettings(this) }
            else -> null
        }
        if (w == null) {
            b.warningCard.visibility = View.GONE
            warningAction = null
        } else {
            b.warningCard.visibility = View.VISIBLE
            b.warningText.text = w.first
            warningAction = w.second
        }
    }

    // ---- helpers ----

    private fun updateCarBtLabel() { b.carBt.text = "Car Bluetooth: " + settings.carBtName.ifBlank { "none" } }
    private fun updateObdLabel() { b.obdDevice.text = "OBD dongle: " + settings.obdName.ifBlank { "none" } }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun toast(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()

    private fun scrollToSetup() { b.scroll.post { b.scroll.smoothScrollTo(0, b.setupCard.top) } }

    /** "12s ago" under a minute, else a coarse relative span. */
    private fun rel(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return if (d < 60_000) "${d / 1000}s ago"
        else DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
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
            toast("No paired Bluetooth devices — pair the device first")
            return
        }
        val names = bonded.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i -> onPick(bonded[i]) }
            .setNeutralButton("Clear") { _, _ -> onClear() }
            .show()
    }

    companion object {
        private val testClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
