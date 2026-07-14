package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure unit tests for the speed-backstop hysteresis. The point of the detector
 * is to *not* misfire in slow traffic and *not* drop the tier the moment you hit
 * a red light, so the thresholds and dwell windows are the actual product.
 */
@RunWith(RobolectricTestRunner::class)
class DriveDetectorTest {

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
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun forcedLight_overridesCarBt() {
        val d = DriveDetector(settings(mode = Settings.MODE_LIGHT))
        d.carConnected = true
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun off_isOff() {
        val d = DriveDetector(settings(mode = Settings.MODE_OFF))
        d.carConnected = true
        d.obdConnected = true
        assertEquals(DriveDetector.Tier.OFF, d.tier())
    }

    @Test fun carBt_promotesToDriving() {
        val d = DriveDetector(settings())
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.carConnected = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("car BT", d.reason())
    }

    @Test fun obd_promotesToDriving_evenWithoutSpeed() {
        val d = DriveDetector(settings())
        d.obdConnected = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("OBD", d.reason())
    }

    @Test fun highSingleSpeed_promotesImmediately() {
        val d = DriveDetector(settings())
        d.onSpeed(10f, 1_000L)   // ~22 mph, above ENTER_FAST
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("speed", d.reason())
    }

    @Test fun moderateSpeed_requiresSustainedWindow() {
        val d = DriveDetector(settings())
        d.onSpeed(7f, 0L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.onSpeed(7f, 25_000L)   // past the 20s sustained window
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun speedExit_requiresSustainedStop() {
        val d = DriveDetector(settings())
        d.onSpeed(10f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0.5f, 60_000L)                             // the stop begins HERE
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0.5f, 380_000L)                            // 5+ min AFTER the stop began
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun driveBySpeedFalse_disablesSpeedBackstop() {
        val d = DriveDetector(settings(bySpeed = false))
        d.onSpeed(20f, 0L)
        d.onSpeed(20f, 60_000L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    // ---- motion-onset path (device-agnostic fast start) ----

    @Test fun motionDriving_promotesToDriving() {
        val d = DriveDetector(settings())
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.motionDriving = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("motion", d.reason())
    }

    @Test fun motionDriving_outranksSpeedInReason() {
        val d = DriveDetector(settings())
        d.motionDriving = true
        d.onSpeed(10f, 0L)                 // speed backstop also fires
        assertEquals("motion", d.reason())  // cascade order: motion before speed
    }

    @Test fun forcedLight_overridesMotion() {
        val d = DriveDetector(settings(mode = Settings.MODE_LIGHT))
        d.motionDriving = true
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun confirmOnset_highDopplerIsVehicular() {
        val d = DriveDetector(settings())
        assertTrue(d.confirmOnset(5f, 0f, 0L))   // >= onsetSpeedMps (4)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("motion", d.reason())
    }

    @Test fun confirmOnset_belowWalkingFloorRejects() {
        val d = DriveDetector(settings())
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
        assertFalse(d.confirmOnset(20f, 0f, 0L))
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun motion_clearsAfterSustainedStop() {
        val d = DriveDetector(settings())
        assertTrue(d.confirmOnset(5f, 0f, 0L))
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0.5f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // hysteresis holds through a brief stop
        d.onSpeed(0.5f, 320_000L)                            // past STOP_MS (5 min)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    // ---- resume latch (process restart in the middle of a drive: app update / OEM kill) ----

    @Test fun resumeDriving_promotesToDriving() {
        val d = DriveDetector(settings())
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        d.resumeDriving = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        assertEquals("resumed", d.reason())
    }

    @Test fun resume_holdsThroughColdStartThenEndsOnSustainedStop() {
        val d = DriveDetector(settings())
        d.resumeDriving = true
        // Cold restart: no speed sample yet. The drive must NOT be dropped here — that is the
        // whole bug (the first fixless tier resolution used to end it and zero its stats).
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0.5f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // brief stop held by hysteresis
        d.onSpeed(0.5f, 320_000L)                            // past STOP_MS (5 min): drive really over
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun resume_survivesWithSpeedBackstopOff() {
        val d = DriveDetector(settings(bySpeed = false))
        d.resumeDriving = true
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // independent of driveBySpeed
    }

    @Test fun resume_realDrivingCarriesPastTheGrace() {
        val d = DriveDetector(settings())
        d.resumeDriving = true
        d.onSpeed(10f, 0L)                                   // still actually moving (~22 mph)
        d.onSpeed(10f, 400_000L)                             // long after STOP_MS
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
        d.obdConnected = true
        d.onSpeed(10f, 0L)                                   // arrive somewhere
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0f, 60_000L)                               // engine off, sitting — the stop begins
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // held: could be a pump or a light
        d.onSpeed(0f, 370_000L)                              // 5+ min into the stop, dongle STILL connected
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())     // parked wins over the connection
        assertTrue(d.isParked)
        assertFalse(d.isMoving)
    }

    @Test fun carBt_stillConnected_butParked_dropsToLight() {
        val d = DriveDetector(settings())
        d.carConnected = true
        d.onSpeed(10f, 0L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0f, 10_000L)                               // head unit on accessory power
        d.onSpeed(0f, 320_000L)                              // 5+ min into the stop, BT still connected
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun parked_thenMoving_repromotesImmediately() {
        val d = DriveDetector(settings())
        d.obdConnected = true
        d.onSpeed(0f, 0L)
        d.onSpeed(0f, 320_000L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())     // parked
        d.onSpeed(9f, 330_000L)                              // pulling away
        assertFalse(d.isParked)                              // one moving fix clears it
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // no timer to wait out
    }

    @Test fun engineRunning_holdsThroughALongIdle() {
        // Idling in the car (engine on) is not parked: a drive-through, a warm-up or a bad jam is
        // still one drive. The engine extends the stop we tolerate — but only up to the ceiling
        // the next test pins.
        val d = DriveDetector(settings())
        d.obdConnected = true
        d.engineRunning = true
        d.onSpeed(0f, 0L)
        d.onSpeed(0f, 600_000L)                              // ten minutes stationary, engine on
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        // Kill the engine and the same stop now parks it.
        d.engineRunning = false
        d.onSpeed(0f, 610_000L)
        d.onSpeed(0f, 930_000L)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun engineRunning_cannotHoldDrivingForever() {
        // The bound that keeps OBD additive. A dongle in a parked car keeps its socket and can
        // keep serving a stale nonzero rpm; without a ceiling that pins DRIVING (and the OBD
        // loop, which exits on !isParked) for as long as the socket lives. Half an hour without
        // moving an inch is a parked car whatever the dongle insists.
        val d = DriveDetector(settings())
        d.obdConnected = true
        d.engineRunning = true                               // the dongle never stops claiming it
        d.onSpeed(0f, 0L)
        d.onSpeed(0f, 1_500_000L)                            // 25 min: still inside the hold
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0f, 1_800_000L)                            // 30 min stationary — GPS wins
        assertTrue(d.isParked)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
        // And it re-promotes the instant the car actually moves: the signal never dropped.
        d.onSpeed(9f, 1_810_000L)
        assertFalse(d.isParked)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
    }

    @Test fun engineRunning_flappingCannotResetTheStationaryClock() {
        // The nastier half: engineRunning only has to read true ONCE per five-minute window to
        // reset parkedSince forever. The stationary clock behind the ceiling is therefore driven
        // by motion alone — a dongle that flickers cannot wind it back.
        val d = DriveDetector(settings())
        d.obdConnected = true
        d.onSpeed(0f, 0L)
        var t = 0L
        while (t < 1_800_000L) {
            t += 240_000L                                    // a bogus rpm frame every four minutes
            d.engineRunning = !d.engineRunning
            d.onSpeed(0f, t)
        }
        assertTrue(d.isParked)                               // still parks, on time
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }

    @Test fun parked_isNotReportedAsTheDrivingReason() {
        val d = DriveDetector(settings())
        d.obdConnected = true
        d.onSpeed(0f, 0L)
        d.onSpeed(0f, 320_000L)
        assertEquals("auto", d.reason())                     // not "OBD" — it isn't holding anything
    }

    // ---- the signal light: the honest live state the UI shows (independent of the tier) ----

    @Test fun light_goesRedAtAStop_whileTheDriveContinues() {
        val d = DriveDetector(settings())
        d.onSpeed(10f, 0L)
        assertTrue(d.isMoving)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0f, 10_000L)                               // the wheels stop
        assertTrue(d.isMoving)                               // debounce: not yet
        d.onSpeed(0f, 16_000L)                               // past MOVING_OFF_MS (5 s)
        assertFalse(d.isMoving)                              // red light…
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())   // …but the drive is still on
        assertFalse(d.isParked)                              // and it is nowhere near parked
    }

    @Test fun light_goesGreenOnTheFirstMovingFix() {
        val d = DriveDetector(settings())
        d.onSpeed(0f, 0L)
        d.onSpeed(0f, 40_000L)
        assertFalse(d.isMoving)
        d.onSpeed(5f, 41_000L)                               // green light
        assertTrue(d.isMoving)                               // no debounce on the way up
    }

    @Test fun stoppedSince_datesTheStopFromTheFirstStoppedFix_notTheDebounce() {
        val d = DriveDetector(settings())
        d.onSpeed(10f, 100_000L)
        assertEquals(0L, d.stoppedSince)                     // moving: no stop to count
        d.onSpeed(0f, 130_000L)                              // the wheels stop HERE
        d.onSpeed(0f, 160_000L)                              // …the light goes red later
        assertFalse(d.isMoving)
        assertEquals(130_000L, d.stoppedSince)               // the clock counts the real stop
        d.onSpeed(9f, 170_000L)
        assertEquals(0L, d.stoppedSince)                     // rolling again: cleared
    }
}
