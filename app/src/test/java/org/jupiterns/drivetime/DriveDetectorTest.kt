package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    private fun settings(mode: String = Settings.MODE_AUTO, bySpeed: Boolean = true): Settings {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Settings(ctx).apply {
            trackingMode = mode
            driveBySpeed = bySpeed
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
}
