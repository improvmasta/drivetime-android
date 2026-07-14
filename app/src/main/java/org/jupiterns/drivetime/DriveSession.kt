package org.jupiterns.drivetime

/**
 * The rules of the **drive session** — the thing with an identity (a start time, a running
 * distance, a marker count), as distinct from the *tier* (how fast we sample) and from
 * [DriveDetector.isMoving] (are the wheels turning). Collapsing those three is what caused the
 * parked-car-pins-DRIVING bug; see [DriveDetector]'s class doc.
 *
 * Pure and Context-free on purpose, like [ControlParse] and [Control.externalAllowed]: the one
 * judgement here decides whether a drive that was in progress when the process died is *the same
 * drive* when it comes back, and getting that wrong is visible to the driver — a fresh start mark
 * resets the live bar's clock, its miles and its marker count in the middle of a real drive.
 */
object DriveSession {

    /**
     * Longest a persisted drive-start mark stays resumable. Past this it can only be a leak — we
     * crashed out of a long-finished drive without ever clearing the mark — never a drive still
     * in progress. Twelve hours is far past any plausible single drive and far short of the days
     * a stale mark could otherwise sit there.
     */
    const val MAX_DRIVE_MS = 12 * 60 * 60 * 1000L

    /**
     * Is [startedAt] a drive we should carry on with, rather than start afresh from?
     *
     * Three ways to say no, and each is a real case:
     *  - **no mark** (0) — nothing to resume.
     *  - **too old** (> [MAX_DRIVE_MS]) — a leaked mark from a drive that ended long ago.
     *  - **in the future** (negative age) — the wall clock moved backwards under us (NTP
     *    correction, the user changing the time, an RTC that came back from a reboot behind where
     *    it left off). A mark we can't date is a mark we can't trust, and treating it as a live
     *    drive would attribute the next drive's miles to a session that started "tomorrow".
     *
     * Both callers in [LocationService] — the cold-start `resumeDriving` latch in `onCreate` and
     * `markDriveStart` — must agree, because they are two halves of one decision: the latch holds
     * the tier at DRIVING across the restart precisely so that `markDriveStart` finds the mark
     * still there and reuses it. They used to carry separate copies of this expression.
     */
    fun resumable(startedAt: Long, now: Long): Boolean =
        startedAt != 0L && now - startedAt in 0L..MAX_DRIVE_MS
}
