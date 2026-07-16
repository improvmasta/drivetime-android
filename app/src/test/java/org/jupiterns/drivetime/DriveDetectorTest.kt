package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the tier decision. The point of the detector is to *not* misfire in slow
 * traffic, *not* drop the tier the moment you hit a red light, and *not* keep insisting you
 * are driving once you have parked and walked off — so the thresholds and dwell windows are
 * the actual product.
 *
 * Robolectric because [DriveDetector] calls `Location.distanceBetween` for real geodesy, the
 * same reason [DriveEndProcessorTest] needs it.
 */
@RunWith(RobolectricTestRunner::class)
class DriveDetectorTest {

    /**
     * Feeds fixes along a ground track, because the park rule is **positional**: a test that
     * names a speed without moving the phone is describing a car with its wheels spinning, and
     * would pass or fail for reasons that have nothing to do with the thing under test.
     *
     * [fix] advances the position the way the speed implies, so `fix(10f, 60_000L)` really does
     * put the car 600 m up the road. Below `EXIT_MPS` it does *not* advance: a parked phone
     * reporting 0.5 m/s of Doppler jitter has not travelled 160 m, and pretending it did would
     * make "sitting still for five minutes" look like a drive.
     *
     * [inPlace] is the other half — a real ground speed that gets you nowhere. That is not an
     * artificial case; it is what walking around a shop looks like to GPS, and it is the bug
     * these tests exist for.
     */
    private class Track(val d: DriveDetector, var lat: Double = 40.0, val lon: Double = -75.0) {
        private var last = Long.MIN_VALUE
        private companion object { const val M_PER_DEG_LAT = 111_320.0; const val JITTER_MPS = 1.3f }

        /** A fix at [mps] that actually covers ground at that speed since the previous fix. */
        fun fix(mps: Float, now: Long) {
            if (last != Long.MIN_VALUE && mps >= JITTER_MPS) {
                lat += mps * ((now - last) / 1000.0) / M_PER_DEG_LAT
            }
            last = now
            d.onFix(lat, lon, mps, now)
        }

        /** A fix at [mps] that covers no ground — parked, or on foot within a few paces. */
        fun inPlace(mps: Float, now: Long) {
            last = now
            d.onFix(lat, lon, mps, now)
        }

        /** A fix at [mps] displaced [meters] north of the track's start — for drift and for
         *  walking that genuinely relocates. */
        fun offset(mps: Float, meters: Double, now: Long) {
            last = now
            d.onFix(lat + meters / M_PER_DEG_LAT, lon, mps, now)
        }
    }

    private fun settings(mode: String = Settings.MODE_AUTO, bySpeed: Boolean = true, onset: Boolean = true): Settings {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Settings(ctx).apply {
            trackingMode = mode
            driveBySpeed = bySpeed
            motionOnset = onset
        }
    }

    @Test fun forcedDriving_overridesAllSignals() {
        val d = DriveDetector(settings(mode = Settings.MODE_DRIVING))
        val t = Track(d)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun forcedLight_overridesCarBt() {
        val d = DriveDetector(settings(mode = Settings.MODE_LIGHT))
        val t = Track(d)
        d.carConnected = true
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun off_isOff() {
        val d = DriveDetector(settings(mode = Settings.MODE_OFF))
        val t = Track(d)
        d.carConnected = true
        d.obdConnected = true
        assertEquals(DriveDetector.Tier.OFF, d.tier())
    }

    @Test fun carBt_promotesToDriving() {
        val d = DriveDetector(settings())
        val t = Track(d)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.carConnected = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("car BT", d.reason())
    }

    @Test fun obd_promotesToDriving_evenWithoutSpeed() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("OBD", d.reason())
    }

    @Test fun highSingleSpeed_promotesImmediately() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 1_000L)   // ~22 mph, above ENTER_FAST
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("speed", d.reason())
    }

    @Test fun moderateSpeed_requiresSustainedWindow() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(7f, 0L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        t.fix(7f, 25_000L)   // past the 20s sustained window
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun speedExit_requiresSustainedStop() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0.5f, 60_000L)                             // the stop begins HERE
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0.5f, 380_000L)                            // 5+ min AFTER the stop began
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun driveBySpeedFalse_disablesSpeedBackstop() {
        val d = DriveDetector(settings(bySpeed = false))
        val t = Track(d)
        t.fix(20f, 0L)
        t.fix(20f, 60_000L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    // ---- motion-onset path (device-agnostic fast start) ----

    @Test fun motionDriving_promotesToDriving() {
        val d = DriveDetector(settings())
        val t = Track(d)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.motionDriving = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("motion", d.reason())
    }

    @Test fun motionDriving_outranksSpeedInReason() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.motionDriving = true
        t.fix(10f, 0L)                 // speed backstop also fires
        assertEquals("motion", d.reason())  // cascade order: motion before speed
    }

    @Test fun forcedLight_overridesMotion() {
        val d = DriveDetector(settings(mode = Settings.MODE_LIGHT))
        val t = Track(d)
        d.motionDriving = true
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun confirmOnset_highDopplerIsVehicular() {
        val d = DriveDetector(settings())
        val t = Track(d)
        assertTrue(d.confirmOnset(5f, 0f, 0L))   // >= onsetSpeedMps (4)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("motion", d.reason())
    }

    @Test fun confirmOnset_belowWalkingFloorRejects() {
        val d = DriveDetector(settings())
        val t = Track(d)
        assertFalse(d.confirmOnset(1.0f, 0f, 0L))   // < ONSET_MIN_MPS (1.5): not moving yet
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun confirmOnset_ambiguousBand_smoothIsVehicle_bouncyIsNot() {
        // 2.5 m/s is in the [1.5, 4) ambiguous band: accel RMS is the tiebreaker.
        assertTrue("smooth ⇒ vehicle", DriveDetector(settings()).confirmOnset(2.5f, 1.0f, 0L))
        assertFalse("bouncy ⇒ on foot", DriveDetector(settings()).confirmOnset(2.5f, 5.0f, 0L))
    }

    @Test fun confirmOnset_nullDopplerRejects() {
        assertFalse(DriveDetector(settings()).confirmOnset(null, 0f, 0L))
    }

    @Test fun confirmOnset_disabledByMasterSwitch() {
        val d = DriveDetector(settings(onset = false))
        val t = Track(d)
        assertFalse(d.confirmOnset(20f, 0f, 0L))
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun motion_clearsAfterSustainedStop() {
        val d = DriveDetector(settings())
        val t = Track(d)
        assertTrue(d.confirmOnset(5f, 0f, 0L))
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0.5f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // hysteresis holds through a brief stop
        t.fix(0.5f, 320_000L)                            // past STOP_MS (5 min)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    // ---- resume latch (process restart in the middle of a drive: app update / OEM kill) ----

    @Test fun resumeDriving_promotesToDriving() {
        val d = DriveDetector(settings())
        val t = Track(d)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.resumeDriving = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("resumed", d.reason())
    }

    @Test fun resume_holdsThroughColdStartThenEndsOnSustainedStop() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.resumeDriving = true
        // Cold restart: no speed sample yet. The drive must NOT be dropped here — that is the
        // whole bug (the first fixless tier resolution used to end it and zero its stats).
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0.5f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // brief stop held by hysteresis
        t.fix(0.5f, 320_000L)                            // past STOP_MS (5 min): drive really over
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun resume_survivesWithSpeedBackstopOff() {
        val d = DriveDetector(settings(bySpeed = false))
        val t = Track(d)
        d.resumeDriving = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // independent of driveBySpeed
    }

    @Test fun resume_realDrivingCarriesPastTheGrace() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.resumeDriving = true
        t.fix(10f, 0L)                                   // still actually moving (~22 mph)
        t.fix(10f, 400_000L)                             // long after STOP_MS
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // the speed backstop carries it
        assertEquals("speed", d.reason())                    // cascade: speed outranks the resume latch
    }

    // ---- the parked bound on the connection signals ----
    //
    // An OBD-II port is permanently powered and a head unit can sit on accessory power, so both
    // stay CONNECTED with the ignition off. Without a bound, that pinned DRIVING for as long as
    // the phone sat in a parking lot: dense GPS forever, phantom drives out of parked drift, and
    // an app that insisted you were driving while you sat still. These are the regression tests.

    @Test fun obd_stillConnected_butParked_dropsToLight() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        t.fix(10f, 0L)                                   // arrive somewhere
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0f, 60_000L)                               // engine off, sitting — the stop begins
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // held: could be a pump or a light
        t.fix(0f, 370_000L)                              // 5+ min into the stop, dongle STILL connected
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())     // parked wins over the connection
        assertTrue(d.isParked)
        assertFalse(d.isMoving)
    }

    @Test fun carBt_stillConnected_butParked_dropsToLight() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.carConnected = true
        t.fix(10f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0f, 10_000L)                               // head unit on accessory power
        t.fix(0f, 320_000L)                              // 5+ min into the stop, BT still connected
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun parked_thenMoving_repromotesImmediately() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        t.fix(0f, 0L)
        t.fix(0f, 320_000L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())     // parked
        t.fix(9f, 330_000L)                              // pulling away
        assertFalse(d.isParked)                              // one moving fix clears it
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // no timer to wait out
    }

    @Test fun engineRunning_holdsThroughALongIdle() {
        // Idling in the car (engine on) is not parked: a drive-through, a warm-up or a bad jam is
        // still one drive. The engine extends the stop we tolerate — but only up to the ceiling
        // the next test pins.
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        d.engineRunning = true
        t.fix(0f, 0L)
        t.fix(0f, 600_000L)                              // ten minutes stationary, engine on
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        // Kill the engine and the same stop now parks it.
        d.engineRunning = false
        t.fix(0f, 610_000L)
        t.fix(0f, 930_000L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun engineRunning_cannotHoldDrivingForever() {
        // The bound that keeps OBD additive. A dongle in a parked car keeps its socket and can
        // keep serving a stale nonzero rpm; without a ceiling that pins DRIVING (and the OBD
        // loop, which exits on !isParked) for as long as the socket lives. Half an hour without
        // moving an inch is a parked car whatever the dongle insists.
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        d.engineRunning = true                               // the dongle never stops claiming it
        t.fix(0f, 0L)
        t.fix(0f, 1_500_000L)                            // 25 min: still inside the hold
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0f, 1_800_000L)                            // 30 min stationary — GPS wins
        assertTrue(d.isParked)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        // And it re-promotes the instant the car actually moves: the signal never dropped.
        t.fix(9f, 1_810_000L)
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun engineRunning_flappingCannotResetTheStationaryClock() {
        // The nastier half: engineRunning only has to read true ONCE per five-minute window to
        // reset the stop clock forever. The dwell behind the ceiling is therefore driven by
        // POSITION alone — a dongle that flickers cannot wind it back, because it does not get a
        // vote on where the car is.
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        t.fix(0f, 0L)
        var time = 0L
        while (time < 1_800_000L) {
            time += 240_000L                                 // a bogus rpm frame every four minutes
            d.engineRunning = !d.engineRunning
            t.fix(0f, time)
        }
        assertTrue(d.isParked)                               // still parks, on time
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun parked_isNotReportedAsTheDrivingReason() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        t.fix(0f, 0L)
        t.fix(0f, 320_000L)
        assertEquals("auto", d.reason())                     // not "OBD" — it isn't holding anything
    }

    // ---- the signal light: the honest live state the UI shows (independent of the tier) ----

    @Test fun light_goesRedAtAStop_whileTheDriveContinues() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        assertTrue(d.isMoving)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(0f, 10_000L)                               // the wheels stop
        assertTrue(d.isMoving)                               // debounce: not yet
        t.fix(0f, 16_000L)                               // past MOVING_OFF_MS (5 s)
        assertFalse(d.isMoving)                              // red light…
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // …but the drive is still on
        assertFalse(d.isParked)                              // and it is nowhere near parked
    }

    @Test fun light_goesGreenOnTheFirstMovingFix() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(0f, 0L)
        t.fix(0f, 40_000L)
        assertFalse(d.isMoving)
        t.fix(5f, 41_000L)                               // green light
        assertTrue(d.isMoving)                               // no debounce on the way up
    }

    // ---- the stale dwell: a park is hours old by the time the next drive starts ----
    //
    // The trap the positional dwell sets for itself, and the nastiest bug in this change. `parked`
    // CLEARS the driving latches, and after a night in the driveway the dwell is eight hours old —
    // so a gentle pull-out that hasn't yet cleared 40 m or 9 mph reads as parked and undoes the
    // very start that was just detected, dropping the first quarter-mile of the drive back to
    // LIGHT. Silently. Which is this app's entire bug class, so: two regression tests.

    private fun Track.parkOvernight() {
        var time = 0L
        while (time <= 8 * 3600_000L) { inPlace(0f, time); time += 60_000L }
    }

    @Test fun motionOnsetStart_afterAnOvernightPark_isNotUndoneByTheStaleDwell() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.parkOvernight()
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        // Significant motion → a smooth 2.5 m/s creep out of the space ⇒ vehicular (accel RMS is
        // the tiebreaker in that band). Confirming has to reset the dwell, or the next fix kills it.
        assertTrue(d.confirmOnset(2.5f, 1.0f, 28_800_000L))
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.fix(2.5f, 28_802_000L)
        assertEquals("the next fix must not undo the start", DriveDetector.Tier.DRIVING, d.tier())
        assertFalse(d.isParked)
    }

    @Test fun carBtSlowStart_afterAnOvernightPark_promotesWithoutWaitingFor40m() {
        // The other half: a rolling car with the head unit paired is not parked, whatever a stale
        // clock says. This is why `inCarAndRolling` resets the dwell.
        val d = DriveDetector(settings())
        val t = Track(d)
        t.parkOvernight()
        d.carConnected = true
        t.fix(2.0f, 28_860_000L)                             // creeping out, well under VEHICLE_MPS
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun carBt_onAccessoryPower_stillParks_becauseAParkedCarIsNotRolling() {
        // …and the bound that keeps the clause above honest: `inCarAndRolling` needs MOTION, so a
        // head unit sitting on accessory power in a stationary car cannot hold the tier open.
        // This is the original parked-car bug, re-pinned against the new reset.
        val d = DriveDetector(settings())
        val t = Track(d)
        d.carConnected = true
        t.fix(10f, 0L)
        t.inPlace(0f, 60_000L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.inPlace(0f, 400_000L)
        assertTrue(d.isParked)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    // ---- the walk that never ended: positional dwell + egress ----
    //
    // Park at a shop, switch the car off, walk around inside. Bluetooth is long gone and the
    // engine is off, yet the app insisted you were still driving for as long as you kept walking.
    // EXIT_MPS is 1.3 m/s — 2.9 mph — and a brisk walk is faster than that, so every sustained-stop
    // timer in the detector reset on every step, and a walk sits in the dead band between EXIT_MPS
    // and ENTER_MPS where the speed backstop "holds current state" forever. Meanwhile segment.js,
    // reading the same fixes positionally, had already ended the drive. These are the regressions.

    @Test fun walkingAroundAShop_parks_eventhoughWalkingIsFasterThanEXIT_MPS() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)                                       // arrive and park
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        // Walk the aisles: 1.6 m/s (~3.6 mph) — above EXIT_MPS, so the old speed rule read this as
        // "still moving" — but never more than a few paces from where you started.
        var time = 60_000L
        while (time <= 400_000L) { t.inPlace(1.6f, time); time += 20_000L }
        assertTrue("the vehicle has not moved in five minutes", d.isParked)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun walkingAroundAShop_isNotMistakenForACrawlInTraffic() {
        // The same speed, the difference being that traffic COVERS GROUND. Five minutes at 1.6 m/s
        // is ~480 m of road, which resets the tight anchor over and over: a jam must never park.
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        var time = 60_000L
        while (time <= 400_000L) { t.fix(1.6f, time); time += 20_000L }
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun gpsDriftWiderThanTheTightAnchor_stillParks() {
        // Why there are two anchors. Multipath in a garage can wander past PARK_ANCHOR_M (40 m)
        // and would reset the tight anchor forever; the drift anchor (100 m, stopped speed) is
        // what still calls this parked.
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        var time = 60_000L
        var north = 0.0
        while (time <= 400_000L) {
            north = if (north > 0) 0.0 else 55.0             // flip-flop across the 40 m anchor
            t.offset(0.4f, north, time)
            time += 20_000L
        }
        assertTrue(d.isParked)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun egress_endsTheDriveFasterThanDwell_whenYouWalkAwayOnFoot() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertFalse(d.egressPending)
        // On foot beside the car: in the on-foot band, no BT, not covering ground.
        t.inPlace(1.6f, 10_000L)
        assertFalse("not yet — the window has to hold", d.egressPending)
        t.inPlace(1.6f, 105_000L)                            // past EGRESS_MS (90 s)
        assertTrue(d.egressPending)
        assertTrue("bouncy ⇒ on foot", d.confirmEgress(5.0f, 105_000L))
        assertTrue(d.isParked)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())     // ~2 min, not the 5 dwell would take
    }

    @Test fun egress_smoothRideIsNotOnFoot() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertTrue(d.egressPending)
        assertFalse("smooth ⇒ still in the vehicle", d.confirmEgress(0.5f, 105_000L))
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun egress_carBluetoothVetoesIt_soAPumpIsNotEgress() {
        // Bluetooth never ENDS a drive here — it only vetoes the end, and its ~10 m range is
        // exactly the feature: walking around the pump keeps the head unit, so this stays a drive.
        // Same fixes as the confirming test above; the connection is the only difference.
        val d = DriveDetector(settings())
        val t = Track(d)
        d.carConnected = true
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertFalse(d.egressPending)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        // Drop the head unit and the same walk is egress — absence vetoes nothing, it just
        // stops vetoing.
        d.carConnected = false
        t.inPlace(1.6f, 115_000L)
        t.inPlace(1.6f, 210_000L)
        assertTrue(d.egressPending)
    }

    @Test fun egress_engineRunningVetoesIt() {
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        d.engineRunning = true
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertFalse(d.egressPending)
    }

    @Test fun egress_aConnectedDongleAloneDoesNotVetoIt() {
        // obdConnected is NOT consulted, deliberately: the port stays powered with the ignition
        // off, so a connected dongle proves nothing about whether you are still in the car.
        // engineRunning is the honest half, and it is false here.
        val d = DriveDetector(settings())
        val t = Track(d)
        d.obdConnected = true
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertTrue(d.egressPending)
        assertTrue(d.confirmEgress(5.0f, 105_000L))
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun egress_doesNotFireInStopAndGoTraffic() {
        // The false positive that would cost a real drive: a phone loose in a cupholder on a rough
        // road can be in the speed band, have no BT, AND read bouncy. Displacement is what saves
        // it — traffic is still going somewhere.
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        var time = 10_000L
        while (time <= 200_000L) { t.fix(2.0f, time); time += 10_000L }
        assertFalse("advancing down the road is not egress", d.egressPending)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun egress_vehicularSpeedPutsYouBackInTheCar() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertTrue(d.confirmEgress(5.0f, 105_000L))
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        t.fix(10f, 115_000L)                                 // drive off again
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun egress_withTheDiscriminatorDisabled_cannotConfirmAnything() {
        // onset_accel_rms is user-tunable and 0 means "no on-foot discriminator". That must mean
        // "can't confirm", never "confirm anything" — a knob may not silently end real drives.
        val d = DriveDetector(settings().apply { onsetAccelRms = 0 })
        val t = Track(d)
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertFalse(d.confirmEgress(9.0f, 105_000L))
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun egress_needsAnAccelerometer_andDwellStillCoversItsAbsence() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 0L)
        t.inPlace(1.6f, 10_000L)
        t.inPlace(1.6f, 105_000L)
        assertFalse("no sensor ⇒ no confirmation", d.confirmEgress(null, 105_000L))
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        t.inPlace(1.6f, 400_000L)                            // …but the dwell rule still ends it
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun stoppedSince_datesTheStopFromTheFirstStoppedFix_notTheDebounce() {
        val d = DriveDetector(settings())
        val t = Track(d)
        t.fix(10f, 100_000L)
        assertEquals(0L, d.stoppedSince)                     // moving: no stop to count
        t.fix(0f, 130_000L)                              // the wheels stop HERE
        t.fix(0f, 160_000L)                              // …the light goes red later
        assertFalse(d.isMoving)
        assertEquals(130_000L, d.stoppedSince)               // the clock counts the real stop
        t.fix(9f, 170_000L)
        assertEquals(0L, d.stoppedSince)                     // rolling again: cleared
    }
}
