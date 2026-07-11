package org.jupiterns.drivetime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        // Resume whenever the user meant logging to be on — server-optional (STANDALONE.md),
        // so a standalone (no-server) logger must survive a reboot/update just the same.
        if (s.loggingEnabled) {
            Watchdog.schedule(context)   // backstop if the direct start below is refused
            s.lastCommandSource = "boot"
            // Refused (too early / throttled) → the watchdog picks it up next cycle.
            Control.startTrackingService(context)
        }
        // Reboot clears these registrations; re-arm whatever the user had enabled.
        if (s.autoTrip) runCatching { TripDetector.enable(context) }
        // Check-engine alerts are on-device now; retire any legacy server-poll worker.
        AlertWorker.cancel(context)
    }
}
