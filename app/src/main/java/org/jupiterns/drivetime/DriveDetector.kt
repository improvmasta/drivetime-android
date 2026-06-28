package org.jupiterns.drivetime

/**
 * Decides whether we're **driving** from a *layered* set of signals — never tied
 * to one trigger, and deliberately *not* using Android activity-recognition
 * (its car/bike/walk guess is unreliable in slow traffic, which is exactly the
 * misclassification we're avoiding).
 *
 * Priority cascade (first hit wins):
 *   1. **Forced mode** — the user/routine set DRIVING, LIGHT or OFF explicitly.
 *   2. **Car Bluetooth connected** — deterministic: the phone is paired to the car.
 *   3. **OBD dongle connected** — deterministic: engine on, dongle in range.
 *   4. **Sustained / high GPS speed** — the backstop when there's no connection
 *      signal (a car with no BT pairing and no dongle).
 *
 * Connection signals (2,3) keep us in DRIVING even at a dead stop (sitting at a
 * light), so a stop never drops the tier. The speed backstop (4) has hysteresis so
 * traffic crawls don't flap, and it only *exits* after a sustained stop.
 *
 * The detector owns no Android objects and no threads — callers feed it signal
 * updates and read [tier]; LocationService maps the tier to a sampling rate.
 */
class DriveDetector(private val settings: Settings) {

    enum class Tier { OFF, LIGHT, DRIVING }

    @Volatile var carConnected = false
    @Volatile var obdConnected = false

    // Speed-backstop state (hysteresis). NO_TS = "window not started" — a distinct
    // sentinel rather than 0L, so a sample stamped at t=0 still accumulates the window.
    @Volatile private var speedDriving = false
    private var fastSince = NO_TS
    private var slowSince = NO_TS

    /** Feed a GPS speed sample (m/s) to drive the speed backstop. */
    fun onSpeed(mps: Float, now: Long) {
        if (!settings.driveBySpeed) { speedDriving = false; fastSince = NO_TS; slowSince = NO_TS; return }
        when {
            mps >= ENTER_FAST_MPS -> { speedDriving = true; fastSince = NO_TS; slowSince = NO_TS }  // unambiguous
            mps >= ENTER_MPS -> {                 // moderate speed: confirm it's sustained
                slowSince = NO_TS
                if (fastSince == NO_TS) fastSince = now
                if (now - fastSince >= ENTER_MS) speedDriving = true
            }
            mps < EXIT_MPS -> {                   // near-stopped: exit only after a sustained stop
                fastSince = NO_TS
                if (slowSince == NO_TS) slowSince = now
                if (now - slowSince >= EXIT_MS) speedDriving = false
            }
            else -> { fastSince = NO_TS; slowSince = NO_TS }  // between thresholds: hold current state
        }
    }

    /** Resolve the desired tier from the override + signals. */
    fun tier(): Tier = when (settings.trackingMode) {
        Settings.MODE_OFF -> Tier.OFF
        Settings.MODE_DRIVING -> Tier.DRIVING
        Settings.MODE_LIGHT -> Tier.LIGHT
        else -> if (carConnected || obdConnected || speedDriving) Tier.DRIVING else Tier.LIGHT
    }

    /** Short human reason for the current Driving decision (for the notification/status). */
    fun reason(): String = when {
        settings.trackingMode == Settings.MODE_DRIVING -> "forced"
        settings.trackingMode == Settings.MODE_LIGHT -> "eco"
        carConnected -> "car BT"
        obdConnected -> "OBD"
        speedDriving -> "speed"
        else -> "auto"
    }

    companion object {
        // Speed backstop thresholds (m/s).  1 m/s ≈ 2.24 mph.
        private const val ENTER_FAST_MPS = 8.0f   // ~18 mph: a single fix this fast = driving now
        private const val ENTER_MPS = 6.0f        // ~13 mph: driving if sustained for ENTER_MS
        private const val EXIT_MPS = 1.3f         // ~3 mph: near-stopped
        private const val ENTER_MS = 20_000L      // sustained-moderate-speed window to enter
        private const val EXIT_MS = 180_000L      // sustained stop (3 min) before speed-exit
        private const val NO_TS = Long.MIN_VALUE  // "timer not started" sentinel (0L is a valid now)
    }
}
