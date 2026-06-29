package org.jupiterns.drivetime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

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

        b.trackingSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) startTracking()
            else { Control.apply(this, Control.ACTION_STOP, "user"); toast("Tracking off") }
            refresh()
        }

        b.modeAuto.setOnClickListener { forceMode(Control.ACTION_MODE_AUTO, "Auto") }
        b.modeDriving.setOnClickListener { forceMode(Control.ACTION_MODE_DRIVING, "Driving (forced)") }
        b.modeEco.setOnClickListener { forceMode(Control.ACTION_MODE_ECO, "Eco (light only)") }

        b.testConn.setOnClickListener { testConnection() }
        b.viewLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        b.openSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.warningAction.setOnClickListener { warningAction?.invoke() }
        b.permsRecheck.setOnClickListener { renderPermissions(); toast("Re-checked") }
        renderPermissions()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        renderPermissions()   // returning from a system permission screen → reflect the new state
        // Foreground = "the user is looking, ship what we have" — drains any backlog
        // immediately instead of waiting for the next tier-cadence tick.
        Thread { runCatching { uploader.flush() } }.start()
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---- actions ----

    private fun startTracking() {
        if (!settings.isConfigured) {
            toast("Enter server URL, username + password in Settings")
            scrollToSetup()
            return
        }
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
        if (!settings.isConfigured) { toast("Finish setup first"); scrollToSetup(); return }
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

    private fun testConnection() {
        if (!settings.isConfigured) { toast("Open Settings → enter server + login"); scrollToSetup(); return }
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
                b.connError.text = if (auth) "Uploads are being rejected. Fix your username/password in Settings."
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
     *  setup blocks everything; a fresh OEM-kill incident is louder than a routine
     *  permission nag because it represents data loss the user already experienced. */
    private fun nextWarning(): Pair<String, () -> Unit>? {
        if (!settings.isConfigured) {
            return "Finish setup: enter your server URL and login in Settings." to { scrollToSetup() }
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

    // ---- helpers ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun toast(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
    private fun snackBar(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

    /** "Setup is missing/incomplete" → launch the full Settings screen. Replaces the
     *  inline-setup scroll target the dashboard used to have. */
    private fun scrollToSetup() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** "12s ago" under a minute, else a coarse relative span. */
    private fun rel(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return if (d < 60_000) "${d / 1000}s ago"
        else DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    companion object {
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
