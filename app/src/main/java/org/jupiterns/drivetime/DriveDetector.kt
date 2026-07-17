package org.jupiterns.drivetime

import android.location.Location

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
 * **Presence is evidence; absence is not.** That asymmetry is the rule the whole file turns on.
 * A connection signal proves you are in the car, so it may *hold* a drive open — but only until
 * [parked], because an OBD-II port is permanently powered and a head unit can sit on accessory
 * power, so both stay *connected* with the ignition off. An unbounded hold pinned DRIVING for as
 * long as the phone sat in a parking lot: dense GPS forever, phantom drives out of parked drift,
 * and an app insisting you were driving while you sat still. Connection signals are for *starting
 * fast*, not for deciding you never stopped. Equally, a signal going *away* proves nothing —
 * Bluetooth drops mid-drive all the time — so nothing here ever ends a drive because a signal
 * vanished. **Only affirmative evidence ends a drive**, and there are exactly two kinds:
 *
 *   - **[parked] by dwell** — the vehicle demonstrably has not gone anywhere. Positional, and
 *     that is not a detail: see the note on [PARK_ANCHOR_M].
 *   - **[parked] by egress** — you demonstrably got out and walked away ([confirmEgress]).
 *
 * Either ends the drive; neither is required; the absence of both is not evidence of driving.
 *
 * Three things are kept deliberately separate here, because collapsing them is what caused
 * the parked-car-pins-DRIVING bug:
 *   - [isMoving] — are the wheels turning right now? The honest live state; what the UI shows.
 *   - [tier]     — how fast to sample. Stays DRIVING through a stop, until [parked].
 *   - the drive session (LocationService.markDriveStart) — ends when the tier leaves DRIVING.
 *
 * The detector owns no Android objects and no threads — callers feed it fixes via [onFix] and
 * read [tier]; LocationService maps the tier to a sampling rate. (It does call the framework's
 * static geodesy, [Location.distanceBetween], which is why its tests run under Robolectric —
 * the same reason [DriveEndProcessor]'s do.)
 */
class DriveDetector(private val settings: Settings) {

    enum class Tier { OFF, LIGHT, DRIVING }

    @Volatile var carConnected = false
    @Volatile var obdConnected = false

    /** True while the engine is actually turning (a plausible OBD rpm — see
     *  [org.jupiterns.drivetime.obd.ObdSession.engineRunning]), fed by LocationService's OBD loop.
     *  The dongle keeps its Bluetooth socket long after the ignition is off, so rpm is the only
     *  signal that knows the difference. Always false for the (vast majority of) users with no
     *  dongle, which is why it can only ever *extend* a hold, never be required to end one — and
     *  why the hold it extends is itself bounded by [ENGINE_HOLD_MAX_MS]. OBD is additive: it
     *  refines what GPS already decided, and never overrules it. */
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

    /** The vehicle has stopped — by dwell or by egress. This is the bound on the connection
     *  signals, and the thing that clears every driving latch below. */
    @Volatile private var parked = false
    val isParked: Boolean get() = parked

    /** Egress confirmed: you are on foot, so whatever the speed latches still believe, the drive
     *  is over. Cleared by [VEHICLE_MPS] — a fix that fast is a vehicle, not a walk, so you are
     *  demonstrably back in the car. */
    @Volatile private var onFoot = false

    /** Set by the motion-onset confirmer in LocationService (significant-motion wake →
     *  instant GPS Doppler + a short accelerometer check). The fast, device-agnostic
     *  start signal that works in ANY car — see [confirmOnset]. Cleared by [parked],
     *  independent of the speed backstop's own toggle. */
    @Volatile var motionDriving = false

    /** Set by LocationService when the process restarts in the *middle* of a drive (app
     *  update, OEM kill): the persisted drive start survived but the in-memory driving
     *  signals ([speedDriving] etc.) did not, so the cold detector would resolve LIGHT and
     *  end the drive — resetting its live clock and miles — before the first speed fix lands.
     *  This latch holds DRIVING across that cold start and clears on [parked], so a drive that
     *  truly ended still ends. Independent of the speed-backstop toggle, mirroring
     *  [motionDriving]. */
    @Volatile var resumeDriving = false

    // Speed-backstop state (hysteresis). NO_TS = "window not started" — a distinct
    // sentinel rather than 0L, so a sample stamped at t=0 still accumulates the window.
    @Volatile private var speedDriving = false
    private var fastSince = NO_TS
    private var stillSince = NO_TS        // debounce timer for the signal light

    // The previous fix, for [groundMps] — the speed the chip didn't give us.
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTs = NO_TS

    // Positional dwell state: the tight jitter-proof anchor and the wider drift anchor.
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorSince = NO_TS
    private var driftLat = 0.0
    private var driftLon = 0.0
    private var driftSince = NO_TS

    // Egress candidacy: the cheap conjuncts, and where/when they started holding.
    private var egressLat = 0.0
    private var egressLon = 0.0
    private var egressSince = NO_TS

    /** The cheap egress conjuncts have held for [EGRESS_MS] — worth spending an accelerometer
     *  sample to settle it. LocationService polls this and calls [confirmEgress]; it is a
     *  *candidacy*, never a decision. */
    @Volatile private var egressReady = false
    val egressPending: Boolean get() = egressReady

    /**
     * Feed a GPS fix. **Position matters as much as speed here**, which is the whole point of
     * this signature: the old `onSpeed(mps, now)` could only ever ask "is the Doppler low?", and
     * that question cannot tell a 3 mph walk from a 3 mph crawl.
     *
     * [mps] is **nullable, and the null is the point**. A fix with no Doppler is a fix that does
     * not know its speed; it is not a fix at 0 mph. Passing 0f for it — which is what the caller
     * used to do — is a lie that this detector had no way to see through, and it cost whole
     * drives: the LIGHT tier asks for `PRIORITY_BALANCED_POWER_ACCURACY`, ~99.5% of the fixes it
     * gets back are wifi/cell-derived and carry no Doppler at all, so every one of them arrived
     * here claiming to be stationary. The speed backstop below could therefore never fire from
     * inside LIGHT — and LIGHT is the only tier that needs it, since a car with no BT pairing and
     * no dongle has nothing else to promote it. A closed loop: coarse fixes say "stopped", so we
     * stay in the tier that asks for coarse fixes. Drives ran 10+ minutes before an onset probe
     * happened to catch a fix above [ENTER_FAST_MPS], and the minutes before that were logged at
     * 60 s intervals with speeds invented from the coarse positions.
     *
     * So when the chip has no Doppler we measure the ground track instead ([groundMps]) — the one
     * signal LIGHT does deliver. That is the same move [updateDwell] already makes at the other
     * end of the drive, and for the same reason.
     */
    fun onFix(lat: Double, lon: Double, mps: Float?, accuracyM: Float?, now: Long) {
        // Doppler first — it is a measurement. Ground speed is the fallback, never an override.
        val v = mps ?: groundMps(lat, lon, accuracyM, now)
        trackLast(lat, lon, now)
        // Unknown is not moving *and* not stopped, but the dwell has to start somewhere: an
        // unmeasurable fix must not hold a parked car in DRIVING, so it reads as stopped. This is
        // the only place a missing speed is allowed to look like zero, and it is safe here because
        // the dwell that consumes it is positional — it will not park a car that is going
        // anywhere, whatever this says.
        val stopped = (v ?: 0f) < EXIT_MPS

        // The drive's signal light. It goes green on the first moving fix — no debounce, pulling
        // away is unambiguous — and red a few seconds into a stop, the delay being only enough
        // that GPS noise and a crawling queue can't strobe it. In DRIVING we sample every second
        // or two, so this tracks the real thing about as closely as the fixes allow.
        if (!stopped) { moving = true; stillSince = NO_TS; stoppedAt = 0L }
        else {
            if (stillSince == NO_TS) { stillSince = now; stoppedAt = now }
            if (now - stillSince >= MOVING_OFF_MS) moving = false
        }

        val dwell = updateDwell(lat, lon, v, stopped, now)
        updateEgressCandidacy(lat, lon, v, now)
        // A fix at unambiguously vehicular speed says you are back in the car, whatever the
        // accelerometer concluded a minute ago. An unmeasurable fix says nothing, so it may not
        // clear [onFoot]: this is affirmative evidence or it is not evidence.
        if (v != null && v >= VEHICLE_MPS) onFoot = false

        // The one stop rule. The engine may *extend* the dwell we tolerate — idling is genuinely
        // not parked, which is what makes a drive-through or a long light read as one drive — but
        // it may not abolish it, so the bar it raises is a ceiling and not a veto. However long
        // the dongle insists the engine is turning, a car that has not moved for half an hour is
        // parked. Egress bypasses both bars: if you are on foot, the car is not going anywhere
        // regardless of what any of this says.
        val bar = if (engineRunning) ENGINE_HOLD_MAX_MS else STOP_MS
        parked = onFoot || dwell >= bar

        // Parked clears every driving latch. This is the ONE sustained-stop rule in the file —
        // the speed, motion and resume latches used to carry three near-identical copies of it,
        // each keyed on `mps < EXIT_MPS`, and every one of them was defeated by the same thing: a
        // walk registers as speed, so it reset all three timers at once and the drive never ended.
        if (parked) {
            speedDriving = false
            motionDriving = false
            resumeDriving = false
            fastSince = NO_TS
        }

        if (!settings.driveBySpeed) { speedDriving = false; fastSince = NO_TS; return }
        // Deliberately after the clear above, so a genuinely fast fix re-latches in the same call
        // and pulling out of a parking space needs no timer to wait out.
        //
        // `v == null` is "no speed to judge", so it may neither enter nor reset the window — it
        // simply is not a sample. Resetting `fastSince` on it would be the old bug wearing a new
        // hat: at 60 s LIGHT sampling an unmeasurable fix between two fast ones would wipe the
        // window every time and the backstop could never accumulate.
        when {
            v == null -> {}                       // not a sample; leave the window alone
            v >= ENTER_FAST_MPS -> { speedDriving = true; fastSince = NO_TS }  // unambiguous
            v >= ENTER_MPS -> {                   // moderate speed: confirm it's sustained
                if (fastSince == NO_TS) fastSince = now
                if (now - fastSince >= ENTER_MS) speedDriving = true
            }
            else -> fastSince = NO_TS             // below the entry bar: only [parked] exits
        }
    }

    /**
     * Ground speed (m/s) since the previous fix, or **null when the track cannot answer**.
     *
     * This is the entry rule's half of the same insight the dwell rule is built on: *a speed
     * reading cannot block a park it knows nothing about* — and equally, a speed reading cannot
     * **start** a drive it knows nothing about. LIGHT has no speed readings, so it must read the
     * ground.
     *
     * The accuracy gate is what makes that safe, and it is doing two jobs at once. A coarse fix
     * is wrong in two ways simultaneously — no Doppler *and* a position that can be a kilometre
     * out — so a naive displacement/time would promote on cell-tower noise while the phone sat in
     * a driveway. Requiring the displacement to dwarf the fix's own stated error ([GROUND_SNR])
     * separates them cleanly, because the two cases are not close:
     *
     *  - a real drive at 25 mph covers ~670 m between two 60 s LIGHT fixes, against wifi error of
     *    tens of metres — an order of magnitude clear, and it promotes.
     *  - a tower-to-tower jump covers ~2 km and *says so*: it arrives stamped with ~2 km of
     *    accuracy, so it cannot clear its own error bar and is rejected as the noise it is.
     *  - a walk covers ~80 m/min and never reaches [ENTER_MPS] however clean the fix.
     *
     * That is why `accuracy` had to be plumbed in for this: the field the pipeline used to throw
     * away is exactly the one that tells a drive from a teleport. With no accuracy reported at
     * all we assume [NOMINAL_ACC_M] — the honest reading of a fix that declines to say.
     */
    private fun groundMps(lat: Double, lon: Double, accuracyM: Float?, now: Long): Float? {
        if (lastTs == NO_TS) return null
        val dtS = (now - lastTs) / 1000f
        if (dtS <= 0f) return null
        val d = metersBetween(lastLat, lastLon, lat, lon)
        // Below its own error bar the fix has not said anything: not "stopped", *unknown*.
        if (d < (accuracyM ?: NOMINAL_ACC_M) * GROUND_SNR) return null
        return d / dtS
    }

    private fun trackLast(lat: Double, lon: Double, now: Long) {
        lastLat = lat; lastLon = lon; lastTs = now
    }

    /**
     * How long (ms) the **vehicle** has demonstrably not gone anywhere — a port of `segment.js`'s
     * dual positional park detection, and the reason this detector needed position at all.
     *
     * `STOP_MS`'s comment has always claimed the live app and the drive log agree on what a stop
     * is. They shared the five *minutes* and nothing else: `segment.js` asks "have you moved 40 m?"
     * while this asked "is the Doppler under 2.9 mph?" — and 2.9 mph is *walking pace*. Park at a
     * shop and walk around inside it and every stop timer here reset on every step, so the tier
     * never left DRIVING while the segmenter, reading the same fixes positionally, had long since
     * ended the drive. Two answers to one question, and the phone believed the wrong one.
     *
     * Two anchors, because either alone is wrong:
     *  - **[PARK_ANCHOR_M]** (tight, 40 m, *no* speed condition) — the jitter-proof one, and the
     *    one that catches a walk: a speed reading cannot block a park it knows nothing about.
     *  - **[PARK_DRIFT_M]** (wide, 100 m, *requires* stopped speed) — the drift-tolerant one: GPS
     *    multipath in a parking garage can wander further than 40 m and would otherwise reset the
     *    tight anchor forever.
     *
     * Either dwelling [STOP_MS] is a park, so the dwell is the **max** of the two.
     *
     * Unlike `segment.js`, which smooths its positions first, this reads raw fixes — it has no
     * lookahead to smooth with. The tight anchor absorbs ordinary jitter (~5–10 m) and the drift
     * anchor absorbs the rest.
     */
    private fun updateDwell(lat: Double, lon: Double, mps: Float?, stopped: Boolean, now: Long): Long {
        // Proof the vehicle is moving resets both anchors outright, rather than waiting to
        // accumulate 40 m of displacement — so pulling away re-promotes on the very next fix.
        // Two things count as proof, and the second one is not optional:
        //
        //  - **Unambiguously vehicular speed** ([VEHICLE_MPS]). Below it, only *displacement*
        //    counts, which is exactly what a walk cannot produce and a crawl in traffic can:
        //    3 mph for five minutes is 400 m of road, and 40 m of shop floor.
        //  - **Any motion at all while something that only exists inside a car says you are in
        //    one.** Without this the dwell is a trap: park overnight and it is EIGHT HOURS old,
        //    so the next morning's gentle pull-out is still "parked" until it clears 40 m or
        //    9 mph — and `parked` clears the driving latches, so it would undo the very start
        //    that had just been detected and drop the first quarter-mile back to LIGHT. A car
        //    that is *stopped* still parks on schedule (a lying dongle and a head unit on
        //    accessory power are both stationary, which is what bounds them); this only says a
        //    car that is *rolling* is not parked, whatever a stale clock believes.
        val inCarAndRolling = !stopped && (carConnected || engineRunning)
        if ((mps != null && mps >= VEHICLE_MPS) || inCarAndRolling) {
            anchorLat = lat; anchorLon = lon; anchorSince = now
            driftLat = lat; driftLon = lon; driftSince = now
            return 0L
        }
        if (anchorSince == NO_TS || metersBetween(anchorLat, anchorLon, lat, lon) > PARK_ANCHOR_M) {
            anchorLat = lat; anchorLon = lon; anchorSince = now
        }
        if (driftSince == NO_TS || !stopped ||
            metersBetween(driftLat, driftLon, lat, lon) > PARK_DRIFT_M
        ) {
            driftLat = lat; driftLon = lon; driftSince = now
        }
        return maxOf(now - anchorSince, now - driftSince)
    }

    /**
     * The three *cheap* egress conjuncts, tracked per fix. All must hold continuously for
     * [EGRESS_MS] before [confirmEgress] is worth an accelerometer sample:
     *
     *  1. **on-foot speed band** — fast enough that the dwell rule above is being defeated
     *     ([EXIT_MPS]), slow enough not to be a vehicle ([VEHICLE_MPS]).
     *  2. **no car connection** — this is where Bluetooth earns its keep, and note that it is
     *     doing the *opposite* of "Bluetooth wins": BT never ends a drive, it only *vetoes* the
     *     end. Its ~10 m range is the feature — walk into a shop and it drops, but stand at the
     *     pump and it holds, so a fuel stop can't be mistaken for egress. `engineRunning` vetoes
     *     for the same reason and is honest where `obdConnected` is not (the port stays powered
     *     with the ignition off, so a *connected* dongle proves nothing and is not consulted).
     *  3. **no vehicle-scale displacement** — you are milling about, not being carried. This is
     *     the conjunct that kills the false positive that matters: a phone loose in a cupholder in
     *     stop-and-go traffic on a rough road can satisfy 1, 2 *and* the accelerometer, but it is
     *     still advancing down the road. In [EGRESS_MS] a jam covers hundreds of metres; a shop
     *     floor covers none.
     *
     * Known gap, accepted: walking away from the car *in a straight line* keeps relocating past
     * [EGRESS_MOVE_M], so it never confirms, and the tight dwell anchor keeps resetting too. That
     * walk rides on the end of the drive until you arrive somewhere and stand still, and then the
     * dwell rule ends it [STOP_MS] later. It costs a drive that is long by a few hundred metres
     * — visible, fixable with a split, and much rarer than the shop case, which is why it buys
     * nothing to chase it with a looser rule that would start ending real drives in traffic.
     */
    private fun updateEgressCandidacy(lat: Double, lon: Double, mps: Float?, now: Long) {
        // No speed, no band — egress is affirmative evidence that you got out and walked away, and
        // a fix that cannot measure its own speed is not evidence of anything.
        val onFootBand = mps != null && mps >= EXIT_MPS && mps < VEHICLE_MPS
        if (!onFootBand || carConnected || engineRunning) {
            egressSince = NO_TS
            egressReady = false
            return
        }
        if (egressSince == NO_TS || metersBetween(egressLat, egressLon, lat, lon) > EGRESS_MOVE_M) {
            egressLat = lat; egressLon = lon; egressSince = now
        }
        egressReady = now - egressSince >= EGRESS_MS
    }

    /**
     * The mirror of [confirmOnset], and the question this detector never used to ask.
     *
     * `confirmOnset` already knew how to tell a walk from a car at an ambiguous ground speed — a
     * car crawling out of a driveway is *smooth* (low accel RMS) while a body on foot is *bouncy*
     * (high) — but it only ever asked on the way *in*. Nothing asked on the way out, so a drive
     * that ended in a shop car park stayed "driving" for as long as its owner kept walking around.
     * Same physics, same tuned threshold ([Settings.onsetAccelRms]), opposite end of the drive.
     *
     * Called by LocationService only while [egressPending], so by the time we get here the three
     * cheap conjuncts have already held for [EGRESS_MS]; the accelerometer is the fourth and last.
     * Returns whether egress was confirmed.
     */
    fun confirmEgress(accelRmsMs2: Float?, now: Long): Boolean {
        // A user knob may not silently disable a *correctness* rule, so this only ever gates the
        // fast path: 0 means "no on-foot discriminator" (see ControlParse.BOUNDS), which here has
        // to mean "can't confirm", never "confirm anything". The dwell rule still ends the drive.
        if (settings.onsetAccelRms <= 0) return false
        if (!egressReady) return false                    // conditions lapsed while we sampled
        val rms = accelRmsMs2 ?: return false             // no accelerometer ⇒ dwell is the only path
        if (rms < settings.onsetAccelRms / 100f) return false  // smooth ⇒ still in a vehicle
        onFoot = true
        parked = true
        speedDriving = false
        motionDriving = false
        resumeDriving = false
        fastSince = NO_TS
        egressSince = NO_TS
        egressReady = false
        return true
    }

    /** Resolve the desired tier from the override + signals. */
    fun tier(): Tier = when (settings.trackingMode) {
        Settings.MODE_OFF -> Tier.OFF
        Settings.MODE_DRIVING -> Tier.DRIVING
        Settings.MODE_LIGHT -> Tier.LIGHT
        // The connection signals hold the tier through a stop, but only until [parked] — see the
        // class doc. The speed/motion/resume latches are cleared by [parked] directly, so they
        // need no gate here.
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
        // Confirming is an affirmative statement that the vehicle is moving, so it must reset the
        // dwell as surely as a vehicular fix does — otherwise the stale clock from the park this
        // wake just ended would read as [parked] on the very next fix and clear [motionDriving]
        // again. Dropping the anchors (rather than stamping them here) lets the next fix re-anchor
        // at a real position: this confirmer has a Doppler speed but no coordinates.
        if (confirmed) {
            motionDriving = true
            onFoot = false
            anchorSince = NO_TS
            driftSince = NO_TS
        }
        return confirmed
    }

    private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    companion object {
        // Speed backstop thresholds (m/s).  1 m/s ≈ 2.24 mph.
        private const val ENTER_FAST_MPS = 8.0f   // ~18 mph: a single fix this fast = driving now
        private const val ENTER_MPS = 6.0f        // ~13 mph: driving if sustained for ENTER_MS
        private const val EXIT_MPS = 1.3f         // ~3 mph: near-stopped
        private const val ENTER_MS = 20_000L      // sustained-moderate-speed window to enter

        /**
         * Unambiguously vehicular ground speed (~9 mph): above a run, below any crawl worth
         * calling traffic. A fix this fast resets the dwell anchors and clears [onFoot].
         *
         * A **constant**, though [Settings.onsetSpeedMps] means the same thing and carries the
         * same default — deliberately, and this is the one place the two must not be shared.
         * That setting clamps to `1..100` (see `ControlParse.BOUNDS`), and at 1 m/s a *walk*
         * would read as vehicular, reset the anchors on every step, and reopen the exact bug the
         * positional dwell exists to close. A tuning knob may not silently disable a correctness
         * rule; it may only ever make a *fast path* fire less often. Same reasoning as the
         * `onsetAccelRms <= 0` guard in [confirmEgress].
         */
        private const val VEHICLE_MPS = 4.0f

        /** ONE definition of "a sustained stop", used by [parked] and therefore by every latch:
         *  five minutes. It is the same five minutes `segment.js`/`detail.py` call a park, and —
         *  now that [updateDwell] measures it the same *way* they do — the live app, the drive log
         *  and the phone's own segmentation finally agree on what a stop is, rather than sharing a
         *  number while asking different questions. */
        private const val STOP_MS = 300_000L

        /** Park anchors, ported from `segment.js` (`PARK_ANCHOR_M` / `PARK_DRIFT_M`) — keep them
         *  in lockstep, or the drive you watched will not be the drive you get. See [updateDwell]. */
        private const val PARK_ANCHOR_M = 40.0f
        private const val PARK_DRIFT_M = 100.0f

        /** The ceiling on how long [engineRunning] may hold off [parked]. Half an hour of not
         *  moving an inch is a parked car whatever the dongle says — long enough that a real
         *  drive-through, a warm-up or a bad jam still reads as one drive, short enough that a
         *  lying dongle costs half an hour of dense GPS rather than a whole afternoon. */
        private const val ENGINE_HOLD_MAX_MS = 1_800_000L

        /** How long the cheap egress conjuncts must hold before an accelerometer sample is worth
         *  taking. 90 s rather than 60: the cost of being wrong is a drive split in two (a merge
         *  — the gas-stop prompt already offers it), and the cost of being slow is 30 extra
         *  seconds of dense GPS, so the trade is cheap in one direction and annoying in the
         *  other. Still ~3× faster than waiting out [STOP_MS]. */
        private const val EGRESS_MS = 90_000L

        /** How far you may relocate inside the egress window and still be "on foot near the car".
         *  Sized off the tight park anchor: at walking pace this is ~30 s of travel, so milling
         *  about a shop stays inside it while anything being *carried by a vehicle* leaves it. */
        private const val EGRESS_MOVE_M = 40.0f

        /** How long stationary before the light goes red. This only *labels* — it ends nothing —
         *  so it is deliberately short: long enough that GPS noise and a crawling queue can't
         *  strobe the signal, short enough that the drive reads true within a few seconds. */
        private const val MOVING_OFF_MS = 5_000L

        private const val NO_TS = Long.MIN_VALUE  // "timer not started" sentinel (0L is a valid now)

        /** How far a fix must have moved, as a multiple of its own stated accuracy, before the
         *  displacement counts as travel rather than error. 3× is ~the point where a coarse fix's
         *  error can no longer manufacture the distance: it lets a 60 s LIGHT step at 25 mph
         *  (~670 m against tens of metres of wifi error) through, and rejects a tower jump, whose
         *  displacement and error are the same size by construction. See [groundMps]. */
        private const val GROUND_SNR = 3.0f

        /** Assumed horizontal error for a fix that reports none. Roughly a clean GNSS fix — if a
         *  provider won't say, believing it is precise is the risky read, not the safe one, so
         *  this is deliberately not 0. */
        private const val NOMINAL_ACC_M = 30.0f

        // Motion-onset: below this ground speed nothing vehicular is happening yet (just
        // above a brisk walk, ~3.4 mph), so we don't promote — the re-armed trigger and
        // the dense probe catch the real roll a moment later.
        private const val ONSET_MIN_MPS = 1.5f
    }
}
