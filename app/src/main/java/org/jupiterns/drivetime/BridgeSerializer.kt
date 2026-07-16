package org.jupiterns.drivetime

import org.json.JSONObject

/**
 * The `DrivetimeNative` bridge's payloads, as data rather than as a method on an Activity.
 *
 * This is **contract #4** (HARDENING.md): the bundled SPA reads these keys *by name*, and a
 * WebView running an older cached snapshot reads them too. So the failure mode of this file is
 * uniquely nasty — drop a key or rename one and nothing throws, nothing logs, no test that checks
 * "does the bridge return JSON" notices; a settings row simply goes blank, or worse, silently reads
 * as its default. [BridgeSerializerTest] exists to make that a red test instead of a bug report.
 *
 * It lives outside [WebViewActivity] because it needs nothing from an Activity — only [Settings] —
 * and inside an Activity it was unreachable from a unit test.
 */
object BridgeSerializer {

    /**
     * Every editable tracker setting plus the derived flags the Settings tabs render.
     *
     * The device token itself is **never** surfaced (AUTH.md) — only whether one is set — so
     * pairing stays a native-only flow and the WebView never holds the credential.
     */
    fun settings(s: Settings): JSONObject = JSONObject()
        .put("serverUrl", s.serverUrl)
        .put("hasServer", s.hasServer)
        .put("server_enabled", s.serverEnabled)
        .put("isConfigured", s.isConfigured)
        .put("standalone", !s.isConfigured)
        .put("deviceTokenSet", s.deviceToken.isNotBlank())
        // `trackingMode` is the *desired tier* (auto/driving/light/off) and `loggingEnabled` is
        // the **master switch** — whether the user has actually started the logger. They are not
        // interchangeable, and shipping only the first one cost us a bug report: `trackingMode`
        // defaults to `auto` on a fresh install, so the SPA (which had nothing else to read)
        // derived "tracking is on" from `trackingMode !== 'off'` and rendered a green
        // "enabled (idle)" pill, a pre-flipped switch, and a wizard step reading "✓ Tracking is
        // on" — on a phone whose service had never once run. Nobody turns on what the app says is
        // already on. Send the master switch, and let the UI read the switch.
        .put("trackingMode", s.trackingMode)
        .put("loggingEnabled", s.loggingEnabled)
        .put("interval_sec", s.intervalSec)
        .put("idle_interval_sec", s.idleIntervalSec)
        .put("light_interval_sec", s.lightIntervalSec)
        .put("upload_interval_sec", s.uploadIntervalSec)
        .put("driving_upload_interval_sec", s.drivingUploadIntervalSec)
        .put("stationary_stop_min", s.stationaryStopMin)
        .put("drive_by_speed", s.driveBySpeed)
        .put("motion_onset", s.motionOnset)
        .put("auto_trip", s.autoTrip)
        .put("alerts_enabled", s.alertsEnabled)
        .put("notif_driving_only", s.notifDrivingOnly)
        .put("notify_drive_complete", s.notifyDriveComplete)
        .put("notify_gas_stop", s.notifyGasStop)
        .put("notify_digest", s.notifyDigest)
        .put("notify_tracking_health", s.notifyTrackingHealth)
        .put("notify_backup_health", s.notifyBackupHealth)
        .put("notify_apply_usual", s.notifyApplyUsual)
        .put("notify_coverage_gap", s.notifyCoverageGap)
        .put("notify_auth_failed", s.notifyAuthFailed)
        // The in-app half of every kind's pair. Nothing native reads these — the SPA's notify.js
        // does, to gate its own bell (Settings' in-app block explains why they are stored here
        // anyway). All default ON, which is what the bell has always done.
        .put("notify_drive_complete_inapp", s.inAppDriveComplete)
        .put("notify_gas_stop_inapp", s.inAppGasStop)
        .put("notify_apply_usual_inapp", s.inAppApplyUsual)
        .put("notify_digest_inapp", s.inAppDigest)
        .put("notify_check_engine_inapp", s.inAppCheckEngine)
        .put("notify_tracking_health_inapp", s.inAppTrackingHealth)
        .put("notify_coverage_gap_inapp", s.inAppCoverageGap)
        .put("notify_backup_health_inapp", s.inAppBackupHealth)
        .put("notify_auth_failed_inapp", s.inAppAuthFailed)
        .put("digest_day", s.digestDay)
        .put("digest_time", s.digestTime)
        .put("control_token", s.controlToken)
        // `updates_enabled` and `updates_supported` are deliberately GONE, and the order in which
        // that happened is the whole point. `updates_supported=false` was load-bearing for as long
        // as the SPA still rendered a check-for-updates card behind `updates_supported !== false`:
        // dropping the key made an ABSENT key read as *supported* and put a dead button back in
        // front of every user. The card is now deleted from the SPA itself, so nothing tests the
        // key and nothing renders the button — which is what makes removing it safe. Re-adding an
        // update affordance means re-adding an updater, which Play forbids. Don't.
        // The legacy standalone car/OBD devices. Nothing in the SPA *configures* these any more —
        // the vehicle that owns the device does — but they're reported so the registry can adopt a
        // pre-registry install's devices into a real vehicle exactly once (vehicles.js
        // adoptLegacyDevices), instead of stranding them in a settings screen that no longer shows
        // them.
        .put("carBtName", s.carBtName)
        .put("carBtMac", s.carBtMac)
        .put("obdName", s.obdName)
        .put("obdMac", s.obdMac)
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
}
