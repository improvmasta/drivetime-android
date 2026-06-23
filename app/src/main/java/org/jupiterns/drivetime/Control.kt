package org.jupiterns.drivetime

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * External control surface so the user, a shortcut, or a phone routine (Samsung
 * Modes & Routines, Tasker, MacroDroid, Home Assistant) can drive logging.
 *
 * Triggerable two ways, both carrying one of the ACTION_* strings (and, for SET,
 * `key`/`value` extras):
 *   - launch the (invisible) ControlActivity — preferred for *starting* (an Activity
 *     context dodges the background foreground-service-start limit), or
 *   - send a broadcast to ControlReceiver.
 *
 * The verbs map onto the [Settings.trackingMode] the DriveDetector reads:
 *   START / MODE_AUTO → AUTO      (detector decides Light vs Driving)
 *   MODE_DRIVING      → DRIVING   (force the dense tier)
 *   MODE_ECO          → LIGHT     (force the sparse tier)
 *   STOP              → OFF       (stop logging)
 *   TOGGLE            → OFF ↔ AUTO
 *   SET key=mode value=<auto|driving|light|eco|off>  (generic, routine-friendly)
 */
object Control {
    const val ACTION_START = "org.jupiterns.drivetime.action.START"
    const val ACTION_STOP = "org.jupiterns.drivetime.action.STOP"
    const val ACTION_TOGGLE = "org.jupiterns.drivetime.action.TOGGLE"
    const val ACTION_MODE_AUTO = "org.jupiterns.drivetime.action.MODE_AUTO"
    const val ACTION_MODE_DRIVING = "org.jupiterns.drivetime.action.MODE_DRIVING"
    const val ACTION_MODE_ECO = "org.jupiterns.drivetime.action.MODE_ECO"
    const val ACTION_SET = "org.jupiterns.drivetime.action.SET"

    /** Convenience for callers that only have an action string (Android Auto pane,
     *  activity-recognition receiver). */
    fun apply(context: Context, action: String?): String =
        apply(context, Intent(action))

    /** Apply a control intent. Returns the resulting desired [Settings.trackingMode]. */
    fun apply(context: Context, intent: Intent?): String {
        val settings = Settings(context)
        val mode = when (intent?.action) {
            ACTION_START, ACTION_MODE_AUTO -> Settings.MODE_AUTO
            ACTION_STOP -> Settings.MODE_OFF
            ACTION_MODE_DRIVING -> Settings.MODE_DRIVING
            ACTION_MODE_ECO -> Settings.MODE_LIGHT
            ACTION_TOGGLE ->
                if (settings.trackingMode == Settings.MODE_OFF) Settings.MODE_AUTO else Settings.MODE_OFF
            ACTION_SET -> resolveSet(intent) ?: return settings.trackingMode
            else -> return settings.trackingMode
        }
        applyMode(context, settings, mode)
        return mode
    }

    /** Parse a generic SET intent. Today only `key=mode`; other keys are reserved. */
    private fun resolveSet(intent: Intent): String? {
        val key = intent.getStringExtra("key") ?: "mode"
        if (key != "mode") return null
        return when (intent.getStringExtra("value")?.lowercase()) {
            Settings.MODE_AUTO -> Settings.MODE_AUTO
            Settings.MODE_DRIVING -> Settings.MODE_DRIVING
            Settings.MODE_LIGHT, "eco" -> Settings.MODE_LIGHT
            Settings.MODE_OFF -> Settings.MODE_OFF
            else -> null
        }
    }

    private fun applyMode(context: Context, settings: Settings, mode: String) {
        val svc = Intent(context, LocationService::class.java)
        if (mode == Settings.MODE_OFF) {
            settings.trackingMode = Settings.MODE_OFF
            settings.loggingEnabled = false
            Watchdog.cancel(context)
            context.stopService(svc)
            return
        }
        if (!settings.isConfigured) return
        // Record desired state + arm the watchdog before starting, so logging resumes
        // even if this background FGS-start is throttled. The (re)start re-applies the
        // tier the detector now resolves, so a forced mode takes effect immediately.
        settings.trackingMode = mode
        settings.loggingEnabled = true
        Watchdog.schedule(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc)
        else context.startService(svc)
    }
}
