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

    /** Set by the motion-onset confirmer in LocationService (significant-motion wake →
     *  instant GPS Doppler + a short accelerometer check). The fast, device-agnostic
     *  start signal that works in ANY car — see [confirmOnset]. Cleared on a sustained
     *  stop by [onSpeed], independent of the speed backstop's own toggle. */
    @Volatile var motionDriving = false

    // Speed-backstop state (hysteresis). NO_TS = "window not started" — a distinct
    // sentinel rather than 0L, so a sample stamped at t=0 still accumulates the window.
    @Volatile private var speedDriving = false
    private var fastSince = NO_TS
    private var slowSince = NO_TS
    private var motionSlowSince = NO_TS   // sustained-stop timer for the motion latch

    /** Feed a GPS speed sample (m/s) to drive the speed backstop. */
    fun onSpeed(mps: Float, now: Long) {
        // The motion-onset latch clears after the same sustained stop (EXIT_MS) as the
        // speed backstop, but independent of driveBySpeed — the onset path has its own
        // master switch, so a user who turned off the speed backstop still gets a clean
        // end to a motion-detected drive.
        if (motionDriving) {
            if (mps < EXIT_MPS) {
                if (motionSlowSince == NO_TS) motionSlowSince = now
                if (now - motionSlowSince >= EXIT_MS) { motionDriving = false; motionSlowSince = NO_TS }
            } else motionSlowSince = NO_TS
        }
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
        else -> if (carConnected || obdConnected || motionDriving || speedDriving) Tier.DRIVING else Tier.LIGHT
    }

    /** Short human reason for the current Driving decision (for the notification/status). */
    fun reason(): String = when {
        settings.trackingMode == Settings.MODE_DRIVING -> "forced"
        settings.trackingMode == Settings.MODE_LIGHT -> "eco"
        carConnected -> "car BT"
        obdConnected -> "OBD"
        motionDriving -> "motion"
        speedDriving -> "speed"
        else -> "auto"
    }

    /**
     * Decide whether a significant-motion wake is a real vehicular start, from an instant
     * GPS Doppler speed (m/s) and a short accelerometer-energy RMS (m/s², gravity removed).
     * Doppler is primary: at/above [Settings.onsetSpeedMps] it's unambiguously vehicular;
     * below [ONSET_MIN_MPS] nothing is moving yet (re-arm and wait). The ambiguous band in
     * between is resolved by accel energy — a car crawling out of a driveway is *smooth*
     * (low RMS) while a jog at the same ground speed is *bouncy* (high RMS) — so we confirm
     * unless the accel signature is clearly on-foot. Deliberately NOT activity-recognition.
     *
     * Sets [motionDriving] and returns the decision. This only makes the tier flip *sooner*;
     * boundary accuracy comes from the probationary dense GPS the trigger also starts, so a
     * conservative answer here costs at most a few seconds, never the drive.
     */
    fun confirmOnset(dopplerMps: Float?, accelRmsMs2: Float?, now: Long): Boolean {
        if (!settings.motionOnset) return false
        val v = dopplerMps ?: -1f
        val confirmed = when {
            v >= settings.onsetSpeedMps -> true                                  // clearly vehicular
            v >= ONSET_MIN_MPS -> (accelRmsMs2 ?: 0f) < settings.onsetAccelRms / 100f  // smooth ⇒ vehicle
            else -> false                                                        // not moving yet
        }
        if (confirmed) { motionDriving = true; motionSlowSince = NO_TS }
        return confirmed
    }

    companion object {
        // Speed backstop thresholds (m/s).  1 m/s ≈ 2.24 mph.
        private const val ENTER_FAST_MPS = 8.0f   // ~18 mph: a single fix this fast = driving now
        private const val ENTER_MPS = 6.0f        // ~13 mph: driving if sustained for ENTER_MS
        private const val EXIT_MPS = 1.3f         // ~3 mph: near-stopped
        private const val ENTER_MS = 20_000L      // sustained-moderate-speed window to enter
        private const val EXIT_MS = 180_000L      // sustained stop (3 min) before speed-exit
        private const val NO_TS = Long.MIN_VALUE  // "timer not started" sentinel (0L is a valid now)

        // Motion-onset: below this ground speed nothing vehicular is happening yet (just
        // above a brisk walk, ~3.4 mph), so we don't promote — the re-armed trigger and
        // the dense probe catch the real roll a moment later.
        private const val ONSET_MIN_MPS = 1.5f
    }
}
