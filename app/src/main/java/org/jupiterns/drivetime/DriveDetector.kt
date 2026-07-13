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
 * Connection signals (2,3) hold DRIVING through a dead stop (a red light, a pump), so a stop
 * never drops the tier — but that hold is BOUNDED by [parked]. It has to be: an OBD-II port is
 * permanently powered and a car head unit can sit on accessory power, so both stay *connected*
 * with the ignition off. An unbounded hold therefore pinned DRIVING for as long as the phone
 * sat in a parking lot, which burned battery, invented phantom drives out of parked GPS drift,
 * and had the app insisting you were driving when you were sitting still. Connection signals
 * are for *starting fast*, not for deciding you never stopped.
 *
 * Three things are kept deliberately separate here, because collapsing them is what caused
 * that bug:
 *   - [isMoving] — are the wheels turning right now? The honest live state; what the UI shows.
 *   - [tier]     — how fast to sample. Stays DRIVING through a stop, until [parked].
 *   - the drive session (LocationService.markDriveStart) — ends when the tier leaves DRIVING.
 *
 * The detector owns no Android objects and no threads — callers feed it signal
 * updates and read [tier]; LocationService maps the tier to a sampling rate.
 */
class DriveDetector(private val settings: Settings) {

    enum class Tier { OFF, LIGHT, DRIVING }

    @Volatile var carConnected = false
    @Volatile var obdConnected = false

    /** True while the engine is actually turning (OBD rpm > 0), fed by LocationService's OBD
     *  loop. The dongle keeps its Bluetooth socket long after the ignition is off — rpm is the
     *  only signal that knows the difference. Always false for the (vast majority of) users with
     *  no dongle, which is why it can only ever *extend* a hold, never be required to end one. */
    @Volatile var engineRunning = false

    /** Are the wheels turning right now? Deliberately NOT the tier: sitting at a red light or a
     *  gas pump is not moving, but it is still a drive. The UI shows this as the drive's signal
     *  light — green moving, red stopped — so a drive reads honestly second by second while its
     *  dense sampling and its session carry on underneath. */
    @Volatile private var moving = false
    val isMoving: Boolean get() = moving

    /** Wall-clock (ms) of the first stopped fix of the current stop; 0 while moving. The UI
     *  counts up from this, so "Stopped 0:42" is the real length of the stop — measured from
     *  when the wheels stopped, not from when the debounce below got around to admitting it. */
    @Volatile private var stoppedAt = 0L
    val stoppedSince: Long get() = stoppedAt

    /** Stationary for a full [STOP_MS] with the engine not turning. This is the bound on the
     *  connection signals — the thing that lets a parked car actually end the drive even though
     *  the dongle/head unit is still connected. Cleared by the first moving fix, so re-promotion
     *  is instant: the connection signal never dropped, so there is nothing to re-acquire. */
    @Volatile private var parked = false
    val isParked: Boolean get() = parked

    /** Set by the motion-onset confirmer in LocationService (significant-motion wake →
     *  instant GPS Doppler + a short accelerometer check). The fast, device-agnostic
     *  start signal that works in ANY car — see [confirmOnset]. Cleared on a sustained
     *  stop by [onSpeed], independent of the speed backstop's own toggle. */
    @Volatile var motionDriving = false

    /** Set by LocationService when the process restarts in the *middle* of a drive (app
     *  update, OEM kill): the persisted drive start survived but the in-memory driving
     *  signals ([speedDriving] etc.) did not, so the cold detector would resolve LIGHT and
     *  end the drive — resetting its live clock and miles — before the first speed fix lands.
     *  This latch holds DRIVING across that cold start and clears on the same sustained stop
     *  as the speed/motion latches, so a drive that truly ended still ends. Independent of the
     *  speed-backstop toggle, mirroring [motionDriving]. */
    @Volatile var resumeDriving = false

    // Speed-backstop state (hysteresis). NO_TS = "window not started" — a distinct
    // sentinel rather than 0L, so a sample stamped at t=0 still accumulates the window.
    @Volatile private var speedDriving = false
    private var fastSince = NO_TS
    private var slowSince = NO_TS
    private var motionSlowSince = NO_TS   // sustained-stop timer for the motion latch
    private var resumeSlowSince = NO_TS   // sustained-stop timer for the resume latch
    private var stillSince = NO_TS        // debounce timer for the signal light
    private var parkedSince = NO_TS       // sustained-stop timer for [parked]

    /** Feed a GPS speed sample (m/s) to drive the speed backstop. */
    fun onSpeed(mps: Float, now: Long) {
        val stopped = mps < EXIT_MPS

        // The drive's signal light. It goes green on the first moving fix — no debounce, pulling
        // away is unambiguous — and red a few seconds into a stop, the delay being only enough
        // that GPS noise and a crawling queue can't strobe it. In DRIVING we sample every second
        // or two, so this tracks the real thing about as closely as the fixes allow.
        if (!stopped) { moving = true; stillSince = NO_TS; stoppedAt = 0L }
        else {
            if (stillSince == NO_TS) { stillSince = now; stoppedAt = now }
            if (now - stillSince >= MOVING_OFF_MS) moving = false
        }

        // Parked: stationary for a full sustained stop with the engine not turning. Any moving
        // fix clears it outright — you are demonstrably not parked — so pulling away out of a
        // gas station re-promotes on the very next fix without waiting on any timer.
        if (stopped && !engineRunning) {
            if (parkedSince == NO_TS) parkedSince = now
            if (now - parkedSince >= STOP_MS) parked = true
        } else { parkedSince = NO_TS; parked = false }

        // The motion-onset latch clears after the same sustained stop (STOP_MS) as the
        // speed backstop, but independent of driveBySpeed — the onset path has its own
        // master switch, so a user who turned off the speed backstop still gets a clean
        // end to a motion-detected drive.
        if (motionDriving) {
            if (mps < EXIT_MPS) {
                if (motionSlowSince == NO_TS) motionSlowSince = now
                if (now - motionSlowSince >= STOP_MS) { motionDriving = false; motionSlowSince = NO_TS }
            } else motionSlowSince = NO_TS
        }
        // The resume latch ends the same way — a sustained stop — so a drive resumed across a
        // mid-drive restart that has actually finished (you parked during the update) still
        // ends cleanly, independent of driveBySpeed.
        if (resumeDriving) {
            if (mps < EXIT_MPS) {
                if (resumeSlowSince == NO_TS) resumeSlowSince = now
                if (now - resumeSlowSince >= STOP_MS) { resumeDriving = false; resumeSlowSince = NO_TS }
            } else resumeSlowSince = NO_TS
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
                if (now - slowSince >= STOP_MS) speedDriving = false
            }
            else -> { fastSince = NO_TS; slowSince = NO_TS }  // between thresholds: hold current state
        }
    }

    /** Resolve the desired tier from the override + signals. */
    fun tier(): Tier = when (settings.trackingMode) {
        Settings.MODE_OFF -> Tier.OFF
        Settings.MODE_DRIVING -> Tier.DRIVING
        Settings.MODE_LIGHT -> Tier.LIGHT
        // The connection signals hold the tier through a stop, but only until [parked] — see the
        // class doc. The speed/motion/resume latches carry their own sustained-stop timers, so
        // they need no gate here.
        else -> if (((carConnected || obdConnected) && !parked) || motionDriving || speedDriving || resumeDriving)
            Tier.DRIVING else Tier.LIGHT
    }

    /** Short human reason for the current Driving decision (for the notification/status). */
    fun reason(): String = when {
        settings.trackingMode == Settings.MODE_DRIVING -> "forced"
        settings.trackingMode == Settings.MODE_LIGHT -> "eco"
        carConnected && !parked -> "car BT"
        obdConnected && !parked -> "OBD"
        motionDriving -> "motion"
        speedDriving -> "speed"
        resumeDriving -> "resumed"
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

        /** ONE definition of "a sustained stop", used by every latch and by [parked]: five
         *  minutes stationary. It is the same five minutes `segment.js`/`detail.py` call a park
         *  (a 5-minute dwell ends a drive), so the live app, the drive log and the phone's own
         *  segmentation all agree on what a stop is instead of each having its own opinion. */
        private const val STOP_MS = 300_000L

        /** How long stationary before the light goes red. This only *labels* — it ends nothing —
         *  so it is deliberately short: long enough that GPS noise and a crawling queue can't
         *  strobe the signal, short enough that the drive reads true within a few seconds. */
        private const val MOVING_OFF_MS = 5_000L

        private const val NO_TS = Long.MIN_VALUE  // "timer not started" sentinel (0L is a valid now)

        // Motion-onset: below this ground speed nothing vehicular is happening yet (just
        // above a brisk walk, ~3.4 mph), so we don't promote — the re-armed trigger and
        // the dense probe catch the real roll a moment later.
        private const val ONSET_MIN_MPS = 1.5f
    }
}
