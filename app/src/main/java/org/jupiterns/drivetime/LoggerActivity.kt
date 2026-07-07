package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jupiterns.drivetime.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

/**
 * The single native Tracker screen — the old Logger dashboard and Settings screen
 * merged into one place, reached in one tap from the shell's status pill. It leads
 * with live status (the Tracking switch + what the logger is doing right now) and
 * flows down through the setup you touch less often: access/battery, devices, an
 * *optional* sync server, in-app updates, an Advanced fold (timing / automation /
 * notifications), and backup. The top cards refresh once a second from [LiveState] +
 * [Uploader.health]; edits auto-save on leave (onPause), matching a routine's SET.
 */
class LoggerActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private val ui = Handler(Looper.getMainLooper())
    private val uploader by lazy { Uploader(this, settings) }

    private var updatingSwitch = false
    private var warningAction: (() -> Unit)? = null
    private var pendingStartAfterGrant = false   // user just hit Start; resume it on grant

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    /** Foreground location → triggers the two-step flow. On grant, we either resume
     *  a pending Start or ask for background location next (Q+). */
    private lateinit var fgLocationLauncher: ActivityResultLauncher<Array<String>>
    /** Background location (Q+) — must be requested *separately* after fine; this is
     *  the second half of the two-step. */
    private lateinit var bgLocationLauncher: ActivityResultLauncher<Array<String>>
    /** Notifications / Bluetooth / activity-recognition — small one-shot prompts. */
    private lateinit var miscPermLauncher: ActivityResultLauncher<Array<String>>
    /** Backup export/import via the system file pickers. */
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    /** Bluetooth CONNECT/SCAN, self-granted the first time a device picker opens. */
    private lateinit var btPermLauncher: ActivityResultLauncher<Array<String>>
    private var pendingBtPick: (() -> Unit)? = null
    /** QR pairing scanner (AUTH.md): scans the server dashboard's device-token QR. */
    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        // Load persisted values into the fields BEFORE attaching checkbox listeners, so
        // seeding a checkbox doesn't fire its onCheckedChange (scheduling workers etc.).
        loadSettingsFields()
        b.cheatSheet.text = AutomationHelp.cheatSheet()
        b.versionLabel.text =
            "Installed version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        fgLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (fineGranted) maybeRequestBackgroundLocation()
            else snackBar("Location permission denied — drivetime can't log without it.")
            if (pendingStartAfterGrant && fineGranted) tryResumeStart()
            refresh(); renderPermissions()
        }
        bgLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            if (pendingStartAfterGrant) tryResumeStart()
            refresh(); renderPermissions()
        }
        miscPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> refresh(); renderPermissions() }
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { exportTo(it) } }
        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { importFrom(it) } }
        btPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val resume = pendingBtPick; pendingBtPick = null
            if (grants.values.all { it }) resume?.invoke()
            else toast("Bluetooth permission is needed to find your dongle")
        }
        scanLauncher = registerForActivityResult(ScanContract()) { result ->
            result?.contents?.let { applyPairing(it) }
        }

        // --- live status controls ---
        b.trackingSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) startTracking()
            else { Control.apply(this, Control.ACTION_STOP, "user"); toast("Tracking off") }
            refresh()
        }
        b.modeAuto.setOnClickListener { forceMode(Control.ACTION_MODE_AUTO, "Auto") }
        b.modeDriving.setOnClickListener { forceMode(Control.ACTION_MODE_DRIVING, "Driving (forced)") }
        b.modeEco.setOnClickListener { forceMode(Control.ACTION_MODE_ECO, "Eco (light only)") }
        b.warningAction.setOnClickListener { warningAction?.invoke() }
        b.permsRecheck.setOnClickListener { renderPermissions(); toast("Re-checked") }
        b.viewLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }

        // --- access & battery ---
        b.batteryExempt.setOnClickListener { Battery.requestExemption(this) }
        b.openOemPage.setOnClickListener { OemBatteryLinks.openProtectedAppsPage(this) }

        // --- devices ---
        b.carBt.setOnClickListener { pickBt("Select car Bluetooth", onPick = { mac, name ->
            settings.carBtMac = mac; settings.carBtName = name; refreshDeviceLabels()
        }, onClear = { settings.carBtMac = ""; settings.carBtName = ""; refreshDeviceLabels() }) }
        b.obdDevice.setOnClickListener { pickBt("Select OBD dongle", onPick = { mac, name ->
            settings.obdMac = mac; settings.obdName = name; refreshDeviceLabels()
        }, onClear = { settings.obdMac = ""; settings.obdName = ""; refreshDeviceLabels() }) }

        // --- sync ---
        b.scanQr.setOnClickListener { startPairScan() }
        b.testConn.setOnClickListener { testConnection() }

        // --- updates ---
        b.updatesEnabled.isChecked = settings.updatesEnabled
        b.updatesEnabled.setOnCheckedChangeListener { _, on -> settings.updatesEnabled = on }
        b.checkUpdates.setOnClickListener {
            saveSettingsFields()   // honour a just-typed server URL before checking
            toast("Checking for updates…")
            Updater.checkFromUi(this, interactive = true)
        }

        // --- advanced (collapsed by default) ---
        b.advancedToggle.setOnClickListener {
            val show = b.advancedBody.visibility != View.VISIBLE
            b.advancedBody.visibility = if (show) View.VISIBLE else View.GONE
            b.advancedToggle.text = if (show) "▾  Advanced" else "▸  Advanced"
        }
        b.alerts.setOnCheckedChangeListener { _, on ->
            settings.alertsEnabled = on
            if (on) AlertWorker.schedule(this) else AlertWorker.cancel(this)
        }
        b.autoTrip.setOnCheckedChangeListener { _, on ->
            settings.autoTrip = on
            if (on) runCatching { TripDetector.enable(this) }
            else runCatching { TripDetector.disable(this) }
        }

        // --- backup ---
        b.exportSettings.setOnClickListener {
            saveSettingsFields()
            exportLauncher.launch("drivetime-settings.json")
        }
        b.importSettings.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        renderPermissions()

        // Deep-link from the SPA Settings tabs (NativeBridge.openTrackerSection): land the
        // user straight in the requested section instead of at the top of the screen.
        intent?.getStringExtra(EXTRA_SECTION)?.let { section ->
            b.scroll.post { scrollToSection(section) }
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        renderPermissions()   // returning from a system permission screen → reflect the new state
        refreshBattery()
        // Foreground = "the user is looking, ship what we have" — drains any backlog
        // immediately instead of waiting for the next tier-cadence tick.
        Thread { runCatching { uploader.flush() } }.start()
    }

    // Auto-save on leave so edits are never silently lost by pressing Back. Device pickers
    // and the alerts/auto-trip/updates toggles already persist instantly on tap.
    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        saveSettingsFields()
    }

    // ---- actions ----

    private fun startTracking() {
        // No server gate — tracking is server-optional (STANDALONE.md). A fresh install with
        // no server logs to the phone and segments its own drives; sync is a later opt-in.
        // Two-step flow: if fine-location is missing, kick that off and remember we
        // wanted to start so we can resume after the rationale flow completes.
        val snap = Permissions.snapshot(this, settings)
        if (!snap.hasFineLocation) {
            pendingStartAfterGrant = true
            fgLocationLauncher.launch(Permissions.requestArgsFor(Permissions.Action.REQUEST_FOREGROUND_LOCATION))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !snap.hasBackgroundLocation) {
            pendingStartAfterGrant = true
            maybeRequestBackgroundLocation()
            return
        }
        if (!snap.hasBatteryExempt) Battery.requestExemption(this)
        pendingStartAfterGrant = false
        Control.apply(this, Control.ACTION_MODE_AUTO, "user")
        toast("Tracking on — Auto mode")
    }

    private fun forceMode(action: String, label: String) {
        if (!Permissions.snapshot(this, settings).hasFineLocation) {
            fgLocationLauncher.launch(Permissions.requestArgsFor(Permissions.Action.REQUEST_FOREGROUND_LOCATION))
            return
        }
        Control.apply(this, action, "user")
        toast("Mode: $label")
        refresh()
    }

    /** If logging was queued behind a permission prompt and the prompt has cleared,
     *  complete the start. The fine→background steps each call this on grant. */
    private fun tryResumeStart() {
        val snap = Permissions.snapshot(this, settings)
        if (!snap.hasFineLocation) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !snap.hasBackgroundLocation) return
        pendingStartAfterGrant = false
        if (!snap.hasBatteryExempt) Battery.requestExemption(this)
        Control.apply(this, Control.ACTION_MODE_AUTO, "user")
        toast("Tracking on — Auto mode")
    }

    /** Step 2 of the location flow: explain that background access is what lets
     *  logging continue when the phone is locked, then route to the system prompt. */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("Keep logging when the phone is locked")
            .setMessage(
                "drivetime records GPS the whole time you're driving — even with the screen off. " +
                "On the next screen, pick \"Allow all the time\".\n\n" +
                "Without this, fixes stop the moment the phone sleeps."
            )
            .setNegativeButton("Not now") { _, _ -> refresh() }
            .setPositiveButton("Continue") { _, _ ->
                bgLocationLauncher.launch(
                    Permissions.requestArgsFor(Permissions.Action.REQUEST_BACKGROUND_LOCATION)
                )
            }
            .show()
    }

    /** Launch the QR scanner to pair with a server (AUTH.md). */
    private fun startPairScan() {
        saveSettingsFields()   // keep any just-typed server URL
        val opts = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the pairing QR from the server's Settings → Pair a device")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(opts)
    }

    /** Apply a scanned or pasted pairing payload: set the server URL (if the payload
     *  carries one) + the device token, persist, reflect in the fields, then test. */
    private fun applyPairing(raw: String) {
        val p = Pairing.parse(raw)
        if (!p.hasToken) { toast("That QR didn't contain a device token"); return }
        p.url?.let { settings.serverUrl = it }
        settings.deviceToken = p.token!!
        // A device token supersedes any legacy username/password login on this device.
        settings.username = ""; settings.password = ""
        loadSettingsFields()
        toast("Paired — testing connection…")
        testConnection()
    }

    private fun testConnection() {
        saveSettingsFields()   // honour just-typed server URL + token
        if (!settings.isConfigured) { toast("Enter a server URL, then scan or paste the device token"); scrollToSync(); return }
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
                        it.code == 401 -> "✕ Auth failed — re-scan the pairing QR (token may have rotated)" to R.color.status_red
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

    // ---- settings load/save ----

    private fun loadSettingsFields() {
        b.serverUrl.setText(settings.serverUrl)
        b.deviceToken.setText(settings.deviceToken)
        b.intervalSec.setText(settings.intervalSec.toString())
        b.idleIntervalSec.setText(settings.idleIntervalSec.toString())
        b.lightIntervalSec.setText(settings.lightIntervalSec.toString())
        b.uploadIntervalSec.setText(settings.uploadIntervalSec.toString())
        b.drivingUploadIntervalSec.setText(settings.drivingUploadIntervalSec.toString())
        b.stationaryStopMin.setText(settings.stationaryStopMin.toString())
        b.driveBySpeed.isChecked = settings.driveBySpeed
        b.motionOnset.isChecked = settings.motionOnset
        b.autoTrip.isChecked = settings.autoTrip
        b.controlToken.setText(settings.controlToken)
        b.alerts.isChecked = settings.alertsEnabled
        refreshDeviceLabels()
    }

    private fun saveSettingsFields() {
        settings.serverUrl = b.serverUrl.text.toString()
        settings.deviceToken = b.deviceToken.text.toString()
        settings.intervalSec = b.intervalSec.text.toString().toIntOrNull() ?: settings.intervalSec
        settings.idleIntervalSec = b.idleIntervalSec.text.toString().toIntOrNull() ?: settings.idleIntervalSec
        settings.lightIntervalSec = b.lightIntervalSec.text.toString().toIntOrNull() ?: settings.lightIntervalSec
        settings.uploadIntervalSec = b.uploadIntervalSec.text.toString().toIntOrNull() ?: settings.uploadIntervalSec
        settings.drivingUploadIntervalSec = b.drivingUploadIntervalSec.text.toString().toIntOrNull() ?: settings.drivingUploadIntervalSec
        settings.stationaryStopMin = b.stationaryStopMin.text.toString().toIntOrNull() ?: settings.stationaryStopMin
        settings.driveBySpeed = b.driveBySpeed.isChecked
        settings.motionOnset = b.motionOnset.isChecked
        settings.autoTrip = b.autoTrip.isChecked
        settings.controlToken = b.controlToken.text.toString()
        settings.alertsEnabled = b.alerts.isChecked
    }

    private fun refreshDeviceLabels() {
        b.carBt.text = "Car Bluetooth: " + settings.carBtName.ifBlank { "none" }
        b.obdDevice.text = "OBD dongle: " + settings.obdName.ifBlank { "none" }
    }

    private fun refreshBattery() {
        val exempt = Battery.isExempt(this)
        val help = OemBatteryLinks.help()
        b.batteryState.text = if (exempt) "● Battery exemption granted" else "○ Battery exemption not granted"
        b.batteryState.setTextColor(col(if (exempt) R.color.status_green else R.color.status_amber))
        b.batteryAdvice.text = help.advice
        b.openOemPage.text = help.label
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
            // Server-optional (STANDALONE.md): the phone tracks and saves drives on its own,
            // so "off" is always just "ready to turn on", never "finish setup first".
            b.statusDetail.text = "Ready — turn on to start logging"
            return
        }
        // A significant-motion wake is mid-probe (deciding "did the car just start?") —
        // surface it so the device-agnostic fast start is visible, not a black box.
        val probing = LiveState.onsetState == "probing"
        val (label, color) = when {
            LiveState.tier == "DRIVING" -> "● Driving" to R.color.status_green
            LiveState.tier == "LIGHT" && probing -> "◌ Checking motion…" to R.color.status_amber
            LiveState.tier == "LIGHT" -> "● Light tracking" to R.color.status_blue
            else -> "● Starting…" to R.color.status_amber
        }
        b.statusState.text = label
        b.statusState.setTextColor(col(color))
        val bits = mutableListOf<String>()
        if (probing && LiveState.tier == "LIGHT") bits.add("motion onset")
        LiveState.driveReason?.let { bits.add(it) }   // "motion" once a start is confirmed
        LiveState.speedMph?.let { bits.add("$it mph") }
        if (LiveState.updatedAt > 0) bits.add("fix ${rel(LiveState.updatedAt)}")
        b.statusDetail.text = bits.joinToString(" · ").ifEmpty { "waiting for first fix…" }
    }

    private fun refreshConnection() {
        // No server configured → standalone/local mode: there's nothing to connect to, and
        // that's fine — drives are saved on the phone. Say so instead of "not connected yet".
        if (!settings.hasServer) {
            b.connState.text = "◌ Standalone"
            b.connState.setTextColor(col(R.color.status_grey))
            b.connDetail.text = "No server — drives are saved on your phone."
            b.connError.visibility = View.GONE
            return
        }
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
                b.connError.text = if (auth) "Uploads are being rejected — re-scan the pairing QR (the token may have rotated)."
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
            // Show every PID we're pulling, not just a curated few (prune later).
            val all = org.jupiterns.drivetime.obd.Elm327Client.describe(LiveState.pids)
            b.obdDetail.text = all.joinToString("  ·  ").ifEmpty { "reading…" }
        } else {
            b.obdState.text = "○ OBD not connected"
            b.obdState.setTextColor(col(R.color.status_grey))
            b.obdDetail.text = settings.obdName.ifBlank { "Dongle configured" } + " · connects when you start driving"
        }
    }

    private fun refreshWarnings() {
        val (message, onFix) = nextWarning() ?: (null to null)
        if (message == null) {
            b.warningCard.visibility = View.GONE
            warningAction = null
        } else {
            b.warningCard.visibility = View.VISIBLE
            b.warningText.text = message
            warningAction = onFix
        }
    }

    /** Picks the single most-important thing the user should act on. Order matters:
     *  a fresh OEM-kill incident is louder than a routine permission nag because it
     *  represents data loss the user already experienced. */
    private fun nextWarning(): Pair<String, () -> Unit>? {
        // Standalone (no server) is a first-class state — no setup to finish. Only nag when a
        // server URL is set but its credentials are incomplete, so sync silently can't auth.
        if (settings.hasServer && !settings.isConfigured) {
            return "Sync setup incomplete — scan the pairing QR or paste the device token below." to { scrollToSync() }
        }
        val killAt = settings.lastKillDetectedAt
        if (killAt > settings.killAcknowledgedAt &&
            System.currentTimeMillis() - killAt < KILL_WARNING_TTL_MS) {
            val help = OemBatteryLinks.help()
            return "Your phone killed logging for a while. ${help.advice}" to {
                settings.killAcknowledgedAt = System.currentTimeMillis()
                OemBatteryLinks.openProtectedAppsPage(this)
            }
        }
        val issue = Permissions.snapshot(this, settings).firstIssue
        return issue?.let { it.message to { runWarningAction(it.action) } }
    }

    private fun runWarningAction(action: Permissions.Action) {
        when (action) {
            Permissions.Action.REQUEST_FOREGROUND_LOCATION ->
                fgLocationLauncher.launch(Permissions.requestArgsFor(action))
            Permissions.Action.REQUEST_BACKGROUND_LOCATION -> maybeRequestBackgroundLocation()
            Permissions.Action.REQUEST_NOTIFICATIONS,
            Permissions.Action.REQUEST_BLUETOOTH,
            Permissions.Action.REQUEST_ACTIVITY_RECOGNITION ->
                miscPermLauncher.launch(Permissions.requestArgsFor(action))
            Permissions.Action.REQUEST_BATTERY_EXEMPT -> Battery.requestExemption(this)
            Permissions.Action.OPEN_LOCATION_SETTINGS ->
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Permissions.Action.OPEN_APP_SETTINGS -> Battery.openAppSettings(this)
        }
    }

    /** The full access checklist — every applicable item with a ✓/✗ and, for the missing
     *  ones, a tappable "Grant ›" that runs the same request flow the warning banner uses.
     *  Rebuilt on resume, on a permission result, and on the Re-check button (not per tick,
     *  to avoid flicker). */
    private fun renderPermissions() {
        val list = b.permsList
        list.removeAllViews()
        val missing = Permissions.checklist(this, settings).filter { !it.granted }
        if (missing.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "✓  All access granted"
                setTextColor(col(R.color.status_green))
                textSize = 14f
                minimumHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
            })
            return
        }
        // Show every outstanding item at once (not one-at-a-time); each is tappable to fix.
        for (c in missing) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                isClickable = true
                setOnClickListener { runWarningAction(c.action) }
            }
            row.addView(TextView(this).apply {
                text = "✗  ${c.label}"
                setTextColor(col(R.color.status_amber))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Grant ›"
                setTextColor(col(R.color.status_blue))
                textSize = 14f
            })
            list.addView(row)
        }
    }

    // ---- device pickers ----

    private fun pickBt(title: String, onPick: (mac: String, name: String) -> Unit, onClear: () -> Unit) {
        if (!hasBtPerms()) {
            // Request inline — the dashboard only prompts once a device is set, which can't
            // happen until one is picked, so the picker must self-grant CONNECT + SCAN.
            pendingBtPick = { pickBt(title, onPick, onClear) }
            btPermLauncher.launch(btPerms())
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("No Bluetooth on this device"); return }
        showScanningPicker(title, adapter, onPick, onClear)
    }

    /**
     * Live device picker: lists bonded devices *and* actively discovers nearby ones —
     * how Torque finds a dongle that never appears in system Bluetooth settings — so an
     * unpaired ELM327 can simply be tapped (we capture its MAC) instead of hunting for
     * the address. Discovery + the receiver are torn down when the dialog closes.
     */
    @SuppressLint("MissingPermission")
    private fun showScanningPicker(
        title: String,
        adapter: BluetoothAdapter,
        onPick: (mac: String, name: String) -> Unit,
        onClear: () -> Unit,
    ) {
        val seen = HashSet<String>()
        val picks = ArrayList<Pair<String, String>>()       // (mac, name), parallel to rows
        val rows = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        var nearbyCount = 0
        var dialog: AlertDialog? = null

        // Title doubles as the status line: scanning → result/empty guidance, so the user
        // is never left staring at a static list wondering if it's still working.
        fun setStatus(scanning: Boolean) {
            dialog?.setTitle(when {
                scanning -> "$title — scanning…"
                picks.isEmpty() -> "$title — none found. Rescan, or Enter MAC."
                nearbyCount == 0 -> "$title — tap a paired device, or Rescan / Enter MAC"
                else -> "$title — tap your device"
            })
        }

        fun add(mac: String?, name: String?, nearby: Boolean) {
            if (mac == null || !seen.add(mac)) return
            val nm = name?.takeIf { it.isNotBlank() } ?: mac
            picks.add(mac to nm)
            rows.add("$nm\n$mac · ${if (nearby) "nearby" else "paired"}")   // auto-refreshes
            if (nearby) nearbyCount++
        }

        adapter.bondedDevices?.forEach { add(it.address, it.name, nearby = false) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?.let { add(it.address, it.name, nearby = true) }
                        setStatus(scanning = true)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> setStatus(scanning = false)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            .apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        fun startScan() {
            runCatching { adapter.cancelDiscovery() }
            runCatching { adapter.startDiscovery() }
            setStatus(scanning = true)
        }

        val d = AlertDialog.Builder(this)
            .setTitle("$title — scanning…")
            .setAdapter(rows) { _, i -> onPick(picks[i].first, picks[i].second) }
            .setPositiveButton("Rescan", null)              // overridden below so it doesn't dismiss
            .setNeutralButton("Clear") { _, _ -> onClear() }
            .setNegativeButton("Enter MAC") { _, _ -> promptForMac(title, onPick) }
            .setOnDismissListener {
                runCatching { adapter.cancelDiscovery() }
                runCatching { unregisterReceiver(receiver) }
            }
            .create()
        dialog = d
        d.show()
        // Rescan re-runs discovery in place instead of closing the picker.
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { startScan() }
        startScan()
    }

    private fun hasBtPerms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        else emptyArray()

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
                else toast("Invalid MAC — expected AA:BB:CC:DD:EE:FF")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- backup ----

    private fun exportTo(uri: Uri) {
        val json = SettingsExport.toJson(settings).toString(2)
        runCatching {
            contentResolver.openOutputStream(uri, "w").use { it?.write(json.toByteArray()) }
            toast("Exported")
        }.getOrElse { toast("Export failed: ${it.message}") }
    }

    private fun importFrom(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri).use { it?.bufferedReader()?.readText() } }
            .getOrNull()
        if (text.isNullOrBlank()) { toast("Import failed: empty file"); return }
        val applied = SettingsExport.fromJson(this, settings, text)
        if (applied == 0) { toast("Import failed: no recognised keys"); return }
        loadSettingsFields()
        toast("Imported $applied settings")
    }

    // ---- helpers ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun toast(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
    private fun snackBar(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

    /** Scroll the one-screen surface to a section — used by the SPA Settings tabs' deep-links
     *  (NativeBridge.openTrackerSection) and by in-screen jumps like the "sync incomplete"
     *  warning or a Test connection with no server. */
    private fun scrollToSection(section: String?) {
        val target = when (section) {
            "tracking" -> b.statusState
            "devices" -> b.obdCard
            "sync" -> b.syncCard
            "backup" -> b.exportSettings
            "updates" -> b.checkUpdates
            "advanced" -> {
                if (b.advancedBody.visibility != View.VISIBLE) {
                    b.advancedBody.visibility = View.VISIBLE
                    b.advancedToggle.text = "▾  Advanced"
                }
                b.advancedToggle
            }
            else -> return
        }
        b.scroll.post { b.scroll.smoothScrollTo(0, scrollTopOf(target)) }
    }

    /** Y of [v] within the scroll content — summed offsets, since a target may be nested in a
     *  card rather than a direct child of the scroll's LinearLayout. */
    private fun scrollTopOf(v: View): Int {
        var y = 0
        var view: View? = v
        while (view != null && view !== b.scroll) {
            y += view.top
            view = view.parent as? View
        }
        return y
    }

    private fun scrollToSync() = scrollToSection("sync")

    /** "12s ago" under a minute, else a coarse relative span. */
    private fun rel(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return if (d < 60_000) "${d / 1000}s ago"
        else DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    companion object {
        /** Intent extra: which section to scroll to on open (set by NativeBridge.openTrackerSection). */
        const val EXTRA_SECTION = "section"

        /** How long after a suspected OEM kill we keep nagging the user about it — a
         *  week is enough to notice and fix, after that there's nothing actionable
         *  left and the warning becomes noise. */
        private const val KILL_WARNING_TTL_MS = 7L * 24 * 60 * 60_000L

        private val testClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
