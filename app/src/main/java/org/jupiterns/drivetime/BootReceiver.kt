package org.jupiterns.drivetime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Resume logging after the two events that otherwise silently end it:
 *  - **device reboot** (`BOOT_COMPLETED`) — the process and service are gone, and
 *    the OS has also cleared the activity-recognition and alert registrations.
 *  - **app update** (`MY_PACKAGE_REPLACED`) — the running service is killed when the
 *    APK is replaced.
 *
 * If the user meant logging to be on (`loggingEnabled`), restart the service and
 * arm the watchdog as a backstop in case the direct foreground-service start is
 * refused this early. Activity-recognition (auto start/stop) and the alert poll are
 * re-registered because reboot drops them.
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are exempt from the implicit-broadcast
 * limits and grant a brief allowance to start a foreground service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
            }
            else -> return
        }

        val s = Settings(context)
        if (s.loggingEnabled && s.isConfigured) {
            Watchdog.schedule(context)   // backstop if the direct start below is refused
            s.lastCommandSource = "boot"
            try {
                val svc = Intent(context, LocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(svc)
                else
                    context.startService(svc)
            } catch (e: Exception) {
                // Too early / throttled — the watchdog will pick it up.
            }
        }
        // Reboot clears these registrations; re-arm whatever the user had enabled.
        if (s.autoTrip) runCatching { TripDetector.enable(context) }
        if (s.alertsEnabled) AlertWorker.schedule(context)
    }
}
