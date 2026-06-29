package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject

/**
 * Settings ↔ JSON. Keys match the routine-API SET names from [Control.SET_KEYS] so
 * the same file can serve two purposes: a backup you restore on a new phone, and
 * a "preset" a routine could push via SET. We keep the format flat — one key per
 * setting, primitives only — so it's diff-friendly and any text editor can patch it.
 *
 * Sensitive fields (login + control token) are exported in plaintext. The export
 * picker shows the user *where* the file is going; encryption is the OS file
 * provider's concern, not ours.
 */
object SettingsExport {

    /** Build a JSON snapshot of every persisted setting that maps onto a SET key,
     *  plus the few extras (server URL, credentials, device pairings) that only
     *  live in the UI. */
    fun toJson(s: Settings): JSONObject {
        val o = JSONObject()
        o.put("schema", 1)
        o.put("server_url", s.serverUrl)
        o.put("username", s.username)
        o.put("password", s.password)
        o.put("interval_sec", s.intervalSec)
        o.put("idle_interval_sec", s.idleIntervalSec)
        o.put("light_interval_sec", s.lightIntervalSec)
        o.put("upload_interval_sec", s.uploadIntervalSec)
        o.put("driving_upload_interval_sec", s.drivingUploadIntervalSec)
        o.put("stationary_stop_min", s.stationaryStopMin)
        o.put("drive_by_speed", s.driveBySpeed)
        o.put("auto_trip", s.autoTrip)
        o.put("alerts_enabled", s.alertsEnabled)
        o.put("motion_onset", s.motionOnset)
        o.put("onset_probe_interval_sec", s.onsetProbeIntervalSec)
        o.put("onset_probe_window_sec", s.onsetProbeWindowSec)
        o.put("onset_speed_mps", s.onsetSpeedMps)
        o.put("onset_accel_rms", s.onsetAccelRms)
        o.put("car_bt_mac", s.carBtMac)
        o.put("car_bt_name", s.carBtName)
        o.put("obd_mac", s.obdMac)
        o.put("obd_name", s.obdName)
        o.put("tracking_mode", s.trackingMode)
        o.put("control_token", s.controlToken)
        return o
    }

    /**
     * Apply a JSON file to [Settings]. Returns the count of recognised keys that
     * were applied (0 means "not a valid settings file"). Unknown keys are ignored
     * so a newer-format file partially restores on an older app.
     */
    fun fromJson(context: Context, s: Settings, text: String): Int {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return 0
        var applied = 0
        if (o.has("server_url")) { s.serverUrl = o.optString("server_url"); applied++ }
        if (o.has("username")) { s.username = o.optString("username"); applied++ }
        if (o.has("password")) { s.password = o.optString("password"); applied++ }
        if (o.has("interval_sec")) { s.intervalSec = o.optInt("interval_sec", s.intervalSec); applied++ }
        if (o.has("idle_interval_sec")) { s.idleIntervalSec = o.optInt("idle_interval_sec", s.idleIntervalSec); applied++ }
        if (o.has("light_interval_sec")) { s.lightIntervalSec = o.optInt("light_interval_sec", s.lightIntervalSec); applied++ }
        if (o.has("upload_interval_sec")) { s.uploadIntervalSec = o.optInt("upload_interval_sec", s.uploadIntervalSec); applied++ }
        if (o.has("driving_upload_interval_sec")) { s.drivingUploadIntervalSec = o.optInt("driving_upload_interval_sec", s.drivingUploadIntervalSec); applied++ }
        if (o.has("stationary_stop_min")) { s.stationaryStopMin = o.optInt("stationary_stop_min", s.stationaryStopMin); applied++ }
        if (o.has("drive_by_speed")) { s.driveBySpeed = o.optBoolean("drive_by_speed", s.driveBySpeed); applied++ }
        if (o.has("motion_onset")) { s.motionOnset = o.optBoolean("motion_onset", s.motionOnset); applied++ }
        if (o.has("onset_probe_interval_sec")) { s.onsetProbeIntervalSec = o.optInt("onset_probe_interval_sec", s.onsetProbeIntervalSec); applied++ }
        if (o.has("onset_probe_window_sec")) { s.onsetProbeWindowSec = o.optInt("onset_probe_window_sec", s.onsetProbeWindowSec); applied++ }
        if (o.has("onset_speed_mps")) { s.onsetSpeedMps = o.optInt("onset_speed_mps", s.onsetSpeedMps); applied++ }
        if (o.has("onset_accel_rms")) { s.onsetAccelRms = o.optInt("onset_accel_rms", s.onsetAccelRms); applied++ }
        if (o.has("auto_trip")) {
            val v = o.optBoolean("auto_trip", s.autoTrip)
            s.autoTrip = v
            if (v) runCatching { TripDetector.enable(context) }
            else runCatching { TripDetector.disable(context) }
            applied++
        }
        if (o.has("alerts_enabled")) {
            val v = o.optBoolean("alerts_enabled", s.alertsEnabled)
            s.alertsEnabled = v
            if (v) AlertWorker.schedule(context) else AlertWorker.cancel(context)
            applied++
        }
        if (o.has("car_bt_mac")) { s.carBtMac = o.optString("car_bt_mac"); applied++ }
        if (o.has("car_bt_name")) { s.carBtName = o.optString("car_bt_name"); applied++ }
        if (o.has("obd_mac")) { s.obdMac = o.optString("obd_mac"); applied++ }
        if (o.has("obd_name")) { s.obdName = o.optString("obd_name"); applied++ }
        if (o.has("tracking_mode")) {
            ControlParse.parseMode(o.optString("tracking_mode"))?.let { s.trackingMode = it; applied++ }
        }
        if (o.has("control_token")) { s.controlToken = o.optString("control_token"); applied++ }
        return applied
    }
}
