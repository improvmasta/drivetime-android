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
        d.onSpeed(0.5f, 60_000L)
        assertEquals(DriveDetector.Tier.DRIVING, d.tier())
        d.onSpeed(0.5f, 240_000L)
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
        d.onSpeed(0.5f, 200_000L)                            // past EXIT_MS (3 min)
        assertEquals(DriveDetector.Tier.LIGHT, d.tier())
    }
}
