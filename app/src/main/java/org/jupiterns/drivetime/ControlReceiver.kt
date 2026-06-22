package org.jupiterns.drivetime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Broadcast entry point for automation apps (Tasker/MacroDroid "Send Intent" as
 * broadcast). STOP/TOGGLE-off are fully reliable from the background; START may be
 * subject to background foreground-service-start limits on Android 12+, so prefer
 * ControlActivity for routines that need to *start* logging from the background.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Control.apply(context, intent.action)
    }
}
