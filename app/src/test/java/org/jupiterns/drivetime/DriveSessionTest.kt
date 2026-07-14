package org.jupiterns.drivetime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DriveSession.resumable] — "is the drive that was in progress when this process died the SAME
 * drive now that it's back?"
 *
 * Both answers are visibly wrong when they're wrong, in opposite directions. Say no to a live
 * drive and the driver watches the live bar's clock, miles and marker count reset to zero in the
 * middle of their trip, and the drive log splits one drive into two. Say yes to a mark that leaked
 * out of a drive that ended yesterday and tomorrow's drive is filed under yesterday's start time.
 *
 * The rule is shared by the two halves of one decision — `onCreate`'s resume latch (which holds
 * the tier at DRIVING through a cold start) and `markDriveStart` (which then reuses the mark) —
 * so a disagreement between them would be the worst case of all: the latch holds the drive open,
 * and the mark it was holding it open *for* gets thrown away.
 */
class DriveSessionTest {

    private val now = 1_700_000_000_000L

    @Test fun aDriveThatStartedMinutesAgoIsStillTheSameDrive() {
        // The whole point: an OEM kill mid-drive, or an app update, and we come back 30 seconds
        // later with the car still moving.
        assertTrue(DriveSession.resumable(now - 30_000L, now))
        assertTrue(DriveSession.resumable(now - 45 * 60_000L, now))
    }

    @Test fun noMarkIsNothingToResume() {
        assertFalse(DriveSession.resumable(0L, now))
    }

    @Test fun aMarkOlderThanAnyPlausibleDriveIsALeak() {
        // We crashed out of a drive without ever clearing its mark. Twelve hours later that is not
        // a drive still in progress, it is litter — and reusing it would date the next drive to
        // this morning and hand it every mile in between.
        assertTrue(DriveSession.resumable(now - DriveSession.MAX_DRIVE_MS, now))       // exactly at the edge
        assertFalse(DriveSession.resumable(now - DriveSession.MAX_DRIVE_MS - 1, now))  // one ms past it
        assertFalse(DriveSession.resumable(now - 24 * 60 * 60 * 1000L, now))
    }

    @Test fun aMarkFromTheFutureIsNotTrusted() {
        // The wall clock moved backwards under us — an NTP correction, a time-zone change, an RTC
        // that came back from a reboot behind where it left off. A mark we cannot date is a mark we
        // cannot trust, and `now - startedAt in 0L..MAX` is what rejects it: the range starts at 0,
        // not at Long.MIN_VALUE. A `<= MAX` test alone would happily resume it.
        assertFalse(DriveSession.resumable(now + 1, now))
        assertFalse(DriveSession.resumable(now + 60 * 60_000L, now))
    }

    @Test fun theEdgeIsInclusiveAtBothEnds() {
        assertTrue("a drive that started this instant is resumable", DriveSession.resumable(now, now))
    }
}
