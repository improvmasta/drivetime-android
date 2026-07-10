package org.jupiterns.drivetime

import android.content.Context
import android.util.Base64

/** Server connection settings, backed by SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("drivetime", Context.MODE_PRIVATE)

    /** Server URL. **Empty by default → standalone/local mode** (STANDALONE.md A3): a fresh
     *  install runs entirely on-device against the bundled SPA + replica, no server required.
     *  Setting a URL opts into server sync + the hosted dashboard. */
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()

    /** True when a server is configured (server mode); false = standalone/local mode. */
    val hasServer: Boolean
        get() = serverUrl.isNotBlank()

    /** The **device token** — the single server credential (AUTH.md). Paired once (scan the
     *  QR on the server's dashboard, or paste the code); sent as `Bearer <token>` on every
     *  API call. Replaces the old username/password login *and* the separate ingest token. */
    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(v) = prefs.edit().putString("device_token", v.trim()).apply()

    /** Legacy dashboard login (username/password → HTTP Basic). Kept only so an app that
     *  paired before the device-token model keeps syncing until it re-pairs (AUTH.md →
     *  Migration). New pairings set [deviceToken] and leave these blank. */
    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v.trim()).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit().putString("password", v).apply()

    /** Seconds between GPS fixes while *driving and moving* (the dense tier). */
    var intervalSec: Int
        get() = prefs.getInt("interval_sec", 3)
        set(v) = prefs.edit().putInt("interval_sec", v).apply()

    /** Seconds between fixes while *driving but stopped* (red light / traffic):
     *  adaptive back-off so a stop doesn't flood at the moving rate. */
    var idleIntervalSec: Int
        get() = prefs.getInt("idle_interval_sec", 20)
        set(v) = prefs.edit().putInt("idle_interval_sec", v).apply()

    /** Seconds between fixes when *not driving* (the light background tier):
     *  a sparse, low-power everyday-location pulse that ramps to dense on a drive. */
    var lightIntervalSec: Int
        get() = prefs.getInt("light_interval_sec", 60)
        set(v) = prefs.edit().putInt("light_interval_sec", v).apply()

    /** Seconds between *batched* upload flushes while in **LIGHT** tier. Fixes are
     *  buffered to the on-disk queue and sent in bursts on this cadence (radio-friendly)
     *  instead of one POST per fix; a regained connection or a full batch flushes early. */
    var uploadIntervalSec: Int
        get() = prefs.getInt("upload_interval_sec", 45)
        set(v) = prefs.edit().putInt("upload_interval_sec", v).apply()

    /** Seconds between flushes while **DRIVING** — short, so the dashboard / live ETA
     *  / Android Auto pane see near-real-time position instead of the battery-friendly
     *  LIGHT cadence. Foreground UI and charge-connected events also trigger an
     *  immediate flush regardless of this. */
    var drivingUploadIntervalSec: Int
        get() = prefs.getInt("driving_upload_interval_sec", 10)
        set(v) = prefs.edit().putInt("driving_upload_interval_sec", v).apply()

    /**
     * Tracking mode = the *desired* behaviour, set by the user or a routine:
     *   AUTO    — the DriveDetector decides Light vs Driving from car-BT/OBD/speed.
     *   DRIVING — force the dense tier (routine "I'm driving").
     *   LIGHT   — force the sparse tier (routine "eco / battery saver").
     *   OFF     — no logging (service stopped).
     */
    var trackingMode: String
        get() = prefs.getString("tracking_mode", MODE_AUTO) ?: MODE_AUTO
        set(v) = prefs.edit().putString("tracking_mode", v).apply()

    /** In AUTO, let sustained/high GPS speed promote to Driving when neither the car
     *  Bluetooth nor the OBD dongle is connected (the backstop trigger). */
    var driveBySpeed: Boolean
        get() = prefs.getBoolean("drive_by_speed", true)
        set(v) = prefs.edit().putBoolean("drive_by_speed", v).apply()

    /** Master switch for the **motion-onset** fast path: a hardware significant-motion
     *  trigger (near-zero battery, no pairing) wakes an instant GPS Doppler check so a
     *  drive starts dense logging within seconds in ANY car — not just one paired over
     *  Bluetooth. The 60 s LIGHT heartbeat stays as the backstop. */
    var motionOnset: Boolean
        get() = prefs.getBoolean("motion_onset", true)
        set(v) = prefs.edit().putBoolean("motion_onset", v).apply()

    /** Probationary GPS cadence (seconds) after a significant-motion trigger, so the
     *  speed backstop has dense fixes to confirm a real start. */
    var onsetProbeIntervalSec: Int
        get() = prefs.getInt("onset_probe_interval_sec", 3)
        set(v) = prefs.edit().putInt("onset_probe_interval_sec", v).apply()

    /** How long (seconds) the probationary dense GPS runs before falling back to LIGHT
     *  if no drive was confirmed. */
    var onsetProbeWindowSec: Int
        get() = prefs.getInt("onset_probe_window_sec", 25)
        set(v) = prefs.edit().putInt("onset_probe_window_sec", v).apply()

    /** Doppler speed (m/s) at/above which a motion-onset wake is unambiguously vehicular. */
    var onsetSpeedMps: Int
        get() = prefs.getInt("onset_speed_mps", 4)
        set(v) = prefs.edit().putInt("onset_speed_mps", v).apply()

    /** Accelerometer-energy RMS threshold (×100 m/s²) separating a smooth vehicle from an
     *  on-foot bounce in the ambiguous low-speed band; below it ⇒ vehicle. */
    var onsetAccelRms: Int
        get() = prefs.getInt("onset_accel_rms", 250)
        set(v) = prefs.edit().putInt("onset_accel_rms", v).apply()

    /** Car Bluetooth device (stereo / head unit). Its connection is the #1 "I'm
     *  driving" signal — deterministic, no activity-recognition guessing. */
    var carBtMac: String
        get() = prefs.getString("car_bt_mac", "") ?: ""
        set(v) = prefs.edit().putString("car_bt_mac", v).apply()
    var carBtName: String
        get() = prefs.getString("car_bt_name", "") ?: ""
        set(v) = prefs.edit().putString("car_bt_name", v).apply()

    /** End a trip after this many minutes stationary, as a backstop for a missed
     *  activity-recognition "exited vehicle". 0 disables; only used with autoTrip. */
    var stationaryStopMin: Int
        get() = prefs.getInt("stationary_stop_min", 5)
        set(v) = prefs.edit().putInt("stationary_stop_min", v).apply()

    /** Whether the logging service is currently meant to be running. */
    var loggingEnabled: Boolean
        get() = prefs.getBoolean("logging_enabled", false)
        set(v) = prefs.edit().putBoolean("logging_enabled", v).apply()

    /** Paired OBD-II (ELM327) dongle, if configured. */
    var obdMac: String
        get() = prefs.getString("obd_mac", "") ?: ""
        set(v) = prefs.edit().putString("obd_mac", v).apply()
    var obdName: String
        get() = prefs.getString("obd_name", "") ?: ""
        set(v) = prefs.edit().putString("obd_name", v).apply()

    /** Auto start/stop logging on driving (activity-recognition IN_VEHICLE). */
    var autoTrip: Boolean
        get() = prefs.getBoolean("auto_trip", false)
        set(v) = prefs.edit().putBoolean("auto_trip", v).apply()

    /** Poll the server for alerts and post notifications. */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean("alerts_enabled", false)
        set(v) = prefs.edit().putBoolean("alerts_enabled", v).apply()

    /**
     * Show the full drive card only while DRIVING; collapse to a bare, icon-less notification
     * when idle (MARKERS.md §6). It **demotes**, it never stops the service.
     *
     * A location foreground service is not allowed to hide its notification, and actually
     * removing it would mean giving up the LIGHT tier that watches for the next drive onset:
     * no idle GPS, and a restart-from-broadcast that Android 12+ blocks for the receivers we
     * have. So the idle card moves to an IMPORTANCE_MIN channel with no status-bar icon, and
     * every drive is still detected, every idle fix still logged.
     */
    var notifDrivingOnly: Boolean
        get() = prefs.getBoolean("notif_driving_only", false)
        set(v) = prefs.edit().putBoolean("notif_driving_only", v).apply()

    /** Optional shared secret gating *parameter-setting* control intents (SET, QUERY).
     *  Blank → open (the default; this is a private app on the user's own phone). When
     *  set, a routine must include `token=<this>` in its intent extras or the action is
     *  silently ignored. START/STOP/TOGGLE remain open per the roadmap — they're
     *  low-risk and routines should be able to stop the app even if the token rotates. */
    var controlToken: String
        get() = prefs.getString("control_token", "") ?: ""
        set(v) = prefs.edit().putString("control_token", v.trim()).apply()

    /** Last thing that changed tracking state — "user", "shortcut", "routine",
     *  "watchdog", "boot", "auto", … — surfaced in the STATE_CHANGED broadcast so a
     *  routine can react to *who* just did the thing (e.g. ignore its own echo). */
    var lastCommandSource: String
        get() = prefs.getString("last_command_source", "") ?: ""
        set(v) = prefs.edit().putString("last_command_source", v).apply()

    /** Wall-clock (ms) of the most recent enqueued fix — written periodically by the
     *  logger and read by [Watchdog] / the OEM kill detector to spot "we were meant
     *  to be logging but no fixes for a long time", which is the silent-death case
     *  the warning banner exists to name. */
    var lastFixAt: Long
        get() = prefs.getLong("last_fix_at", 0L)
        set(v) = prefs.edit().putLong("last_fix_at", v).apply()

    /** Wall-clock (ms) the current drive began, 0 when not driving. Written when the tier
     *  enters DRIVING and zeroed when it leaves. Durable (not just [LiveState]) so a service
     *  restart in the middle of a drive — watchdog resurrection, OEM kill — re-enters DRIVING
     *  and keeps the original start instead of pretending the drive began just now. */
    var driveStartedAt: Long
        get() = prefs.getLong("drive_started_at", 0L)
        set(v) = prefs.edit().putLong("drive_started_at", v).apply()

    /** Metres driven in the current drive, mirrored from [LiveState.driveMeters] on the same
     *  throttled cadence as [lastFixAt]. Durable for the same reason [driveStartedAt] is: a
     *  service restart mid-drive (app update, OEM kill, watchdog) re-enters DRIVING, and without
     *  this the running distance would reset to zero and the drive card would undercount. 0 when
     *  not driving; restored by `resumeDriveTotals`, zeroed on a genuinely new drive. */
    var driveMeters: Float
        get() = prefs.getFloat("drive_meters", 0f)
        set(v) = prefs.edit().putFloat("drive_meters", v).apply()

    /** Wall-clock (ms) of the most recent suspected OEM-kill — set by the watchdog
     *  when it restarts the service after a suspicious gap, so the dashboard can name
     *  the manufacturer-specific setting. Zero = no incident recorded. */
    var lastKillDetectedAt: Long
        get() = prefs.getLong("last_kill_detected_at", 0L)
        set(v) = prefs.edit().putLong("last_kill_detected_at", v).apply()

    /** When the user last dismissed (or "fixed") the OEM-kill warning. We only show
     *  the warning while [lastKillDetectedAt] > [killAcknowledgedAt]. */
    var killAcknowledgedAt: Long
        get() = prefs.getLong("kill_acknowledged_at", 0L)
        set(v) = prefs.edit().putLong("kill_acknowledged_at", v).apply()

    /** Auto-check the server for a newer APK when the app comes to the foreground
     *  (throttled). Off = only the manual "Check for updates" button checks. */
    var updatesEnabled: Boolean
        get() = prefs.getBoolean("updates_enabled", true)
        set(v) = prefs.edit().putBoolean("updates_enabled", v).apply()

    /** Wall-clock (ms) of the last in-app update check, so the foreground auto-check
     *  throttles instead of hitting the server every resume. */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong("last_update_check_at", 0L)
        set(v) = prefs.edit().putLong("last_update_check_at", v).apply()

    val ingestUrl: String
        get() = "$serverUrl/api/ingest"

    /** The `Authorization` header for every server call (AUTH.md). Prefers the device
     *  token (`Bearer <token>`); falls back to the legacy Basic login so an app that
     *  paired before the token model keeps working until it re-pairs. Blank when neither
     *  is set (standalone). */
    val authHeader: String
        get() = when {
            deviceToken.isNotBlank() -> "Bearer $deviceToken"
            username.isNotBlank() -> "Basic " +
                Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            else -> ""
        }

    /** A *usable* server is configured: a URL plus a credential (device token, or the
     *  legacy username+password). Empty ⇒ standalone/local mode. */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() &&
            (deviceToken.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_DRIVING = "driving"
        const val MODE_LIGHT = "light"
        const val MODE_OFF = "off"
    }
}
