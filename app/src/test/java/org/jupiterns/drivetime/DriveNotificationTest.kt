package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The ongoing drive card's two decisions: what it says, and when it is worth redrawing.
 *
 * The card is on the lock screen for the whole of every drive, which is exactly when the SPA is not
 * on screen — so it is the only thing the driver can see, and both of these fail quietly. A wrong
 * [DriveNotification.tierText] is the app lying about its own state; a wrong
 * [DriveNotification.signature] either freezes a number the driver is watching or throws away the
 * cheapest battery win in the app.
 */
class DriveNotificationTest {

    private val driving = DriveDetector.Tier.DRIVING

    // ---- what it says ----

    @Test fun aDriveSittingStillSaysStopped_notDriving() {
        // The honest thing, and the one the three-way tier/moving/session split exists to allow: a
        // car at a red light or a pump is still DRIVING (dense sampling, session running, mileage
        // clock going) but is NOT moving. The card has to be able to say both at once.
        assertEquals("Driving · car BT", DriveNotification.tierText(driving, moving = true, reason = "car BT"))
        assertEquals("Stopped · car BT", DriveNotification.tierText(driving, moving = false, reason = "car BT"))
    }

    @Test fun theOtherTiersSayWhatTheyAre() {
        assertEquals("Idle", DriveNotification.tierText(DriveDetector.Tier.LIGHT, moving = false, reason = "auto"))
        assertEquals("Off", DriveNotification.tierText(DriveDetector.Tier.OFF, moving = false, reason = "auto"))
        // `moving` is meaningless outside a drive and must not leak into the text — the phone in
        // your pocket on a bus is "Idle", not "Driving".
        assertEquals("Idle", DriveNotification.tierText(DriveDetector.Tier.LIGHT, moving = true, reason = "speed"))
    }

    // ---- when to redraw ----

    private fun sig(
        tier: DriveDetector.Tier = DriveDetector.Tier.DRIVING,
        moving: Boolean = true,
        reason: String = "car BT",
        channel: String = "drive_logging",
        speedMph: Int? = 45,
        driveMeters: Double = 8_046.72,   // 5.0 mi
        markerCount: Int = 0,
    ) = DriveNotification.signature(tier, moving, reason, channel, speedMph, driveMeters, markerCount)

    @Test fun anIdenticalCardHasAnIdenticalSignature() {
        // At ~1 Hz for a whole drive, this is the check that stops a redraw-per-fix. The elapsed
        // time is a self-ticking Chronometer and updates without a redraw, so it is deliberately
        // NOT in the signature — if it were, nothing would ever coalesce.
        assertEquals(sig(), sig())
    }

    @Test fun everythingTheDriverCanReadChangesTheSignature() {
        assertNotEquals(sig(), sig(speedMph = 46))
        assertNotEquals(sig(), sig(driveMeters = 8_207.0))      // 5.1 mi
        assertNotEquals(sig(), sig(markerCount = 1))
        assertNotEquals("the card prints the reason", sig(), sig(reason = "OBD"))
        assertNotEquals("…and 'Stopped' vs 'Driving'", sig(), sig(moving = false))
    }

    @Test fun milesAreComparedAtThePrecisionTheCardPrints() {
        // The heart of it. The card shows one decimal place, so two fixes 30 metres apart draw the
        // SAME card and the second must not redraw. Compare raw metres and every single fix differs,
        // the coalescing buys exactly nothing, and the drive card wakes the notification manager
        // once a second for four hours.
        assertEquals(sig(driveMeters = 8_046.72), sig(driveMeters = 8_066.0))   // both 5.0 mi

        // But it must still be fine-grained enough to move: cross into the next tenth and it draws.
        assertNotEquals(sig(driveMeters = 8_046.72), sig(driveMeters = 8_150.0)) // 5.0 → 5.1 mi
    }

    @Test fun anIdleCardIsComparedOnItsChannel() {
        // Idle has no speed, miles or markers to show — but `notif_driving_only` re-posts the same
        // notification id on a different (IMPORTANCE_MIN) channel, and that IS a visible change:
        // the status-bar icon disappears. Without the channel in the signature, flipping that
        // setting would coalesce away and appear not to have worked.
        val light = DriveDetector.Tier.LIGHT
        assertNotEquals(
            sig(tier = light, channel = "drive_logging"),
            sig(tier = light, channel = "drive_idle"),
        )
    }

    @Test fun aMissingSpeedIsStableRatherThanRandom() {
        // No speed on this fix (a cold GPS start). It must produce one consistent signature, not
        // one that differs from the last no-speed fix — otherwise a stationary drive with a flaky
        // speed reading redraws forever.
        assertEquals(sig(speedMph = null), sig(speedMph = null))
        assertNotEquals(sig(speedMph = null), sig(speedMph = 0))
    }
}
