package org.jupiterns.drivetime

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * External control surface so phone routines/automation can enable or disable
 * drive logging (to save battery when it isn't needed).
 *
 * Triggerable two ways, both carrying one of the ACTION_* strings:
 *   - launch the (invisible) ControlActivity, or
 *   - send a broadcast to ControlReceiver.
 *
 * Works with Tasker, MacroDroid, Samsung Modes & Routines (via a Tasker/MacroDroid
 * task), Google Assistant routines, and Home Assistant (companion-app intents).
 */
object Control {
    const val ACTION_START = "org.jupiterns.drivetime.action.START"
    const val ACTION_STOP = "org.jupiterns.drivetime.action.STOP"
    const val ACTION_TOGGLE = "org.jupiterns.drivetime.action.TOGGLE"

    /** Apply a control action. Returns the resulting desired state (true=logging). */
    fun apply(context: Context, action: String?): Boolean {
        val settings = Settings(context)
        val start = when (action) {
            ACTION_START -> true
            ACTION_STOP -> false
            ACTION_TOGGLE -> !settings.loggingEnabled
            else -> return settings.loggingEnabled
        }
        val svc = Intent(context, LocationService::class.java)
        if (start) {
            if (!settings.isConfigured) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(svc)
            else
                context.startService(svc)
        } else {
            context.stopService(svc)
        }
        return start
    }
}
