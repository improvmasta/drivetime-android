package org.jupiterns.drivetime

import android.content.Context
import android.util.Base64

/** Server connection settings, backed by SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("drivetime", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "https://drivetime.jupiterns.org") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()

    /** Dashboard login — the app authenticates ingest/alerts with these via HTTP
     *  Basic (see [authHeader]), so a new build only needs creds you remember, not
     *  the random ingest token. */
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

    /** Seconds between *batched* upload flushes. Fixes are buffered to the on-disk
     *  queue and sent in bursts on this cadence (radio-friendly) instead of one POST
     *  per fix; a regained connection or a full batch flushes early. */
    var uploadIntervalSec: Int
        get() = prefs.getInt("upload_interval_sec", 45)
        set(v) = prefs.edit().putInt("upload_interval_sec", v).apply()

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

    val ingestUrl: String
        get() = "$serverUrl/api/ingest"

    /** HTTP Basic header from the dashboard login — stateless per-request auth for
     *  ingest + alerts (no token to copy, nothing to expire mid-drive). Blank when
     *  unconfigured. */
    val authHeader: String
        get() = if (username.isBlank()) "" else
            "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_DRIVING = "driving"
        const val MODE_LIGHT = "light"
        const val MODE_OFF = "off"
    }
}
