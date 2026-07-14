package org.jupiterns.drivetime

/**
 * What the ongoing drive card *says*, as opposed to how it is drawn.
 *
 * The drawing is `RemoteViews` and stays in [LocationService], where the `Context` is. What lives
 * here is the two pieces of it that are actually decisions, and both of them are the sort that goes
 * wrong quietly:
 *
 *  - [tierText] is the app telling the driver, on their lock screen, what it currently believes.
 *    It is the one place the tier/moving distinction has to be said out loud, and saying it wrong
 *    means the card claims you are driving while you sit at a pump.
 *  - [signature] is what stops the card being redrawn ~once a second for a whole drive. Get it
 *    *too* coarse and a value the driver can see stops updating; get it too fine and the coalescing
 *    buys nothing. Neither failure throws.
 */
object DriveNotification {

    /**
     * Say the honest thing: a drive that is sitting still reads "Stopped", not "Driving". The drive
     * is still running underneath — dense sampling, the session, the mileage clock — it simply is
     * not moving, and the card must be able to say both at once. This is the same three-way split
     * [DriveDetector] keeps between the tier, `isMoving` and the session; collapsing it here would
     * be the same bug wearing a different hat.
     */
    fun tierText(tier: DriveDetector.Tier, moving: Boolean, reason: String): String = when (tier) {
        DriveDetector.Tier.DRIVING -> if (moving) "Driving · $reason" else "Stopped · $reason"
        DriveDetector.Tier.LIGHT -> "Idle"
        DriveDetector.Tier.OFF -> "Off"
    }

    /**
     * The visible content of the card as one comparable string — everything it shows **except** the
     * elapsed time, which is a self-ticking `Chronometer` and updates on its own without a redraw.
     * Two fixes with the same signature would draw the same card, so the second needs no `notify()`
     * at all: at ~1 Hz for the length of a drive, that is the cheapest real battery win available.
     *
     * Which is why the *precision* matters and is deliberate. Miles are compared at one decimal
     * place — the same precision the card prints — because comparing raw metres would find a
     * difference on literally every fix and coalesce nothing. Speed is compared as the rounded mph
     * integer, for the same reason. Anything the driver can read has to be in here, and anything
     * they cannot must not be.
     */
    fun signature(
        tier: DriveDetector.Tier,
        moving: Boolean,
        reason: String,
        channel: String,
        speedMph: Int?,
        driveMeters: Double,
        markerCount: Int,
    ): String {
        val text = tierText(tier, moving, reason)
        if (tier != DriveDetector.Tier.DRIVING) return "idle:$text:$channel"
        val miles10 = Math.round(driveMeters * METERS_TO_MILES * 10)
        return "drive:${speedMph ?: -1}:$miles10:$markerCount:$text"
    }

    const val METERS_TO_MILES = 0.000621371
}
