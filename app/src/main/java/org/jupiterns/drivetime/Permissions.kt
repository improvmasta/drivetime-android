package org.jupiterns.drivetime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Single source of truth for "can we actually log right now?". The dashboard
 * reads [snapshot] every tick and surfaces the first missing piece as an
 * actionable warning, so a silently-revoked permission can't sit there for days
 * before you notice queued fixes piling up.
 *
 * Two ideas matter here:
 *   1. **One gate** — every place that asks "is this OK?" (warning banner,
 *      start button, watchdog readiness check) goes through this same code, so
 *      they can never disagree.
 *   2. **Severity ordering** — missing fine-location is fatal; missing
 *      background-location only matters once the phone screen turns off; battery
 *      exemption only matters when an OEM kills the service. We expose the *worst*
 *      missing piece so the UI says one specific thing the user can fix.
 */
object Permissions {

    enum class Severity { BLOCKING, IMPORTANT, NICE }

    data class Issue(
        val severity: Severity,
        val message: String,
        val action: Action,
    )

    enum class Action {
        REQUEST_FOREGROUND_LOCATION,
        REQUEST_BACKGROUND_LOCATION,
        REQUEST_NOTIFICATIONS,
        REQUEST_BLUETOOTH,
        REQUEST_ACTIVITY_RECOGNITION,
        REQUEST_BATTERY_EXEMPT,
        OPEN_LOCATION_SETTINGS,
        OPEN_APP_SETTINGS,
    }

    data class Snapshot(
        val hasFineLocation: Boolean,
        val hasBackgroundLocation: Boolean,
        val hasNotifications: Boolean,
        val hasBluetooth: Boolean,
        val hasActivityRecognition: Boolean,
        val hasBatteryExempt: Boolean,
        val hasLocationServicesOn: Boolean,
        val firstIssue: Issue?,
    ) {
        /** True iff logging would actually work right now. The watchdog reads this
         *  before attempting an FGS-start so it doesn't pin BACKOFF on a permission
         *  problem the user has to resolve interactively. */
        val isReady: Boolean
            get() = hasFineLocation && hasLocationServicesOn
    }

    fun snapshot(context: Context, settings: Settings): Snapshot {
        val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        val btNeeded = settings.obdMac.isNotBlank() || settings.carBtMac.isNotBlank()
        val bt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !btNeeded ||
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        val arNeeded = settings.autoTrip
        val ar = !arNeeded ||
            hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
        val batt = Battery.isExempt(context)
        val locOn = isLocationServicesOn(context)

        val issue = when {
            !fine -> Issue(Severity.BLOCKING,
                "Location permission is required to log drives.",
                Action.REQUEST_FOREGROUND_LOCATION)
            !locOn -> Issue(Severity.BLOCKING,
                "Location services are turned off on this phone.",
                Action.OPEN_LOCATION_SETTINGS)
            !bg -> Issue(Severity.IMPORTANT,
                "Allow location \"All the time\" so logging continues with the screen off.",
                Action.REQUEST_BACKGROUND_LOCATION)
            !batt -> Issue(Severity.IMPORTANT,
                "Allow background battery use so logging isn't killed mid-drive.",
                Action.REQUEST_BATTERY_EXEMPT)
            !notif -> Issue(Severity.IMPORTANT,
                "Enable notifications to see the live logging status.",
                Action.REQUEST_NOTIFICATIONS)
            btNeeded && !bt -> Issue(Severity.IMPORTANT,
                "Bluetooth permission is required to connect your car/OBD dongle.",
                Action.REQUEST_BLUETOOTH)
            arNeeded && !ar -> Issue(Severity.NICE,
                "Activity-recognition permission is required for auto-trip detection.",
                Action.REQUEST_ACTIVITY_RECOGNITION)
            else -> null
        }
        return Snapshot(
            hasFineLocation = fine,
            hasBackgroundLocation = bg,
            hasNotifications = notif,
            hasBluetooth = bt,
            hasActivityRecognition = ar,
            hasBatteryExempt = batt,
            hasLocationServicesOn = locOn,
            firstIssue = issue,
        )
    }

    /** One row of the permissions checklist: what it is, whether it's granted now, and
     *  the [Action] that fixes it when it isn't. */
    data class Check(val label: String, val granted: Boolean, val action: Action)

    /** The full set of access items that apply to *this* configuration, granted or not —
     *  so the UI can show everything at once (and a Re-check) instead of revealing issues
     *  one at a time. Bluetooth/activity-recognition only appear when actually needed. */
    fun checklist(context: Context, settings: Settings): List<Check> {
        val s = snapshot(context, settings)
        val out = mutableListOf<Check>()
        out += Check("Location access", s.hasFineLocation, Action.REQUEST_FOREGROUND_LOCATION)
        out += Check("Location services on", s.hasLocationServicesOn, Action.OPEN_LOCATION_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            out += Check("Background location (\"All the time\")", s.hasBackgroundLocation,
                Action.REQUEST_BACKGROUND_LOCATION)
        out += Check("Battery unrestricted", s.hasBatteryExempt, Action.REQUEST_BATTERY_EXEMPT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            out += Check("Notifications", s.hasNotifications, Action.REQUEST_NOTIFICATIONS)
        val btNeeded = settings.obdMac.isNotBlank() || settings.carBtMac.isNotBlank()
        if (btNeeded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            out += Check("Bluetooth (car / OBD)", s.hasBluetooth, Action.REQUEST_BLUETOOTH)
        if (settings.autoTrip)
            out += Check("Activity recognition (auto-trip)", s.hasActivityRecognition,
                Action.REQUEST_ACTIVITY_RECOGNITION)
        return out
    }

    /** Build the minimal permission array to request for the given Action; the
     *  caller passes this to `requestPermissions`. Empty array means "this action
     *  doesn't go through requestPermissions" (e.g. battery exemption, settings). */
    fun requestArgsFor(action: Action): Array<String> = when (action) {
        Action.REQUEST_FOREGROUND_LOCATION -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        Action.REQUEST_BACKGROUND_LOCATION ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyArray()
        Action.REQUEST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        Action.REQUEST_BLUETOOTH ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        Action.REQUEST_ACTIVITY_RECOGNITION ->
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        else -> emptyArray()
    }

    private fun hasPermission(context: Context, name: String) =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    private fun isLocationServicesOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { true }
    }
}
