package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The gas-stop heuristic, tested at last.
 *
 * It decides whether to tell the user "these two drives look like one" — five thresholds and two
 * distance checks that point in opposite directions, sitting behind a notification, and until now
 * with nothing pinning any of it. The numbers are the SPA's numbers (`localmerge.js`/`detail.py`),
 * so a drift here does not just make the phone wrong, it makes the phone and the website *disagree*
 * about what a gas stop is.
 *
 * Robolectric, not plain JUnit, for one reason: `Location.distanceBetween` is real geodesy and the
 * rule is meaningless without it. Faking the distance would leave the only interesting part of this
 * function — which endpoints each check compares — untested.
 */
@RunWith(RobolectricTestRunner::class)
class DriveEndProcessorTest {

    private val minute = 60_000L

    // A stretch of road, roughly north-south. ~0.009° of latitude ≈ 1 km.
    private val home = 39.7392 to -104.9903
    private val station = 39.7482 to -104.9903      // ~1.0 km from home
    private val work = 39.7842 to -104.9903         // ~5.0 km from home
    private val nextDoor = 39.7484 to -104.9903     // ~22 m from the station: the same spot

    private fun leg(startMs: Long, endMs: Long, from: Pair<Double, Double>, to: Pair<Double, Double>) =
        DriveEndProcessor.Leg(startMs, endMs, from.first, from.second, to.first, to.second)

    // ---- the real-drive gate ----

    @Test fun aJitterLoopIsNotADrive() {
        // GPS drift in a parking lot, or a phantom hundred metres while the car sits. Everything
        // downstream of this gate is a notification, so this is what stands between the app and
        // nagging you about a drive you did not take.
        assertFalse("too short", DriveEndProcessor.isRealDrive(meters = 100.0, durationMs = 20 * minute))
        assertFalse("too quick", DriveEndProcessor.isRealDrive(meters = 5_000.0, durationMs = 60_000L))
        assertFalse(DriveEndProcessor.isRealDrive(meters = 0.0, durationMs = 0L))
    }

    @Test fun aRealDriveIsBothFarEnoughAndLongEnough() {
        assertTrue(DriveEndProcessor.isRealDrive(meters = 800.0, durationMs = 5 * minute))   // exactly at both edges
        assertTrue(DriveEndProcessor.isRealDrive(meters = 12_000.0, durationMs = 25 * minute))
    }

    // ---- the pair rule ----

    @Test fun homeToStationThenStationToWorkIsAGasStop() {
        // The case the whole feature exists for. Drive to the station, stop 7 minutes, carry on to
        // work: same spot, real progress, fuel-stop-shaped gap.
        val prev = leg(0, 10 * minute, home, station)
        val cur = leg(17 * minute, 30 * minute, nextDoor, work)

        val gap = DriveEndProcessor.gasStopGapMinutes(prev, cur)

        assertNotNull(gap)
        assertEquals(7.0, gap!!, 0.001)
    }

    @Test fun anOutAndBackIsNotAGasStop() {
        // The check people leave out, and the reason the second distance test exists. Drive to the
        // station, fill up, drive straight home. The gap is right and you resumed at the same spot —
        // but merging these two legs would produce a single "drive" that starts and ends in your own
        // driveway and claims to have gone nowhere.
        val prev = leg(0, 10 * minute, home, station)
        val cur = leg(17 * minute, 27 * minute, nextDoor, home)   // ends where prev BEGAN

        assertNull(DriveEndProcessor.gasStopGapMinutes(prev, cur))
    }

    @Test fun aStopTooShortToBuyFuelIsNotAGasStop() {
        // Under four minutes you did not stop for anything — that is a traffic light, or the drive
        // detector briefly losing and regaining the car.
        val prev = leg(0, 10 * minute, home, station)
        val cur = leg(12 * minute, 25 * minute, nextDoor, work)   // 2-minute gap

        assertNull(DriveEndProcessor.gasStopGapMinutes(prev, cur))
    }

    @Test fun anErrandIsTwoDrives() {
        // Over fifteen minutes you did something — shopped, ate, worked. These are genuinely two
        // drives and merging them would erase the stop, which is often the whole point of the trip.
        val prev = leg(0, 10 * minute, home, station)
        val cur = leg(40 * minute, 55 * minute, nextDoor, work)   // 30-minute gap

        assertNull(DriveEndProcessor.gasStopGapMinutes(prev, cur))
    }

    @Test fun resumingSomewhereElseIsNotOneJourney() {
        // The gap is right and you made progress, but the car MOVED between the two legs — you
        // ended at the station and started again five kilometres away. Something logged you out of
        // one drive and into another; they are not two halves of one thing.
        val prev = leg(0, 10 * minute, home, station)
        val cur = leg(17 * minute, 30 * minute, work, home)

        assertNull(DriveEndProcessor.gasStopGapMinutes(prev, cur))
    }

    @Test fun theTwoDistanceChecksUseOppositeEndpoints() {
        // They read alike and mean opposite things, which is exactly how one gets swapped for the
        // other in a refactor. Same-spot compares prev's END to cur's START ("did the car move
        // while stopped?"); progress compares prev's START to cur's END ("did the journey get
        // anywhere?"). This pair passes only if both are wired the right way round: prev ends at
        // the station and cur starts there (same spot ✓), and cur ends at work, far from home where
        // prev began (progress ✓). Swap either check and it fails.
        val prev = leg(0, 10 * minute, home, station)
        val cur = leg(17 * minute, 30 * minute, nextDoor, work)

        assertNotNull(DriveEndProcessor.gasStopGapMinutes(prev, cur))
    }

    // ---- the persisted format ----

    @Test fun theSummaryRoundTrips() {
        // An installed phone has one of these in its prefs right now, so decode must keep reading
        // exactly what encode has always written.
        val leg = leg(1_700_000_000_000L, 1_700_000_600_000L, home, work)

        val back = DriveEndProcessor.decode(DriveEndProcessor.encode(leg))

        assertEquals(leg, back)
    }

    @Test fun theMillisecondTimestampsSurviveTheDoubleTrip() {
        // The format stores everything as text and parses it back through Double — including the
        // epoch-millisecond timestamps. They are ~1.7e12, comfortably inside the 2^53 a Double
        // represents exactly, so this is safe; it is worth a test precisely because it looks like
        // it might not be.
        val leg = leg(1_763_251_199_999L, 1_763_251_999_999L, home, work)

        assertEquals(leg.startedAtMs, DriveEndProcessor.decode(DriveEndProcessor.encode(leg))!!.startedAtMs)
    }

    @Test fun aGarbledSummaryIsNoPreviousDriveRatherThanADriveAtZeroZero() {
        // The important failure. Coordinates 0,0 are in the Atlantic — 1.5 km from nothing and
        // 500 m from nothing — so a half-written value read as a Leg would not be rejected, it would
        // be *silently plausible*, and every drive would look like a gas-stop split of a trip to the
        // Gulf of Guinea.
        assertNull(DriveEndProcessor.decode(""))
        assertNull(DriveEndProcessor.decode("1,2,3"))
        assertNull(DriveEndProcessor.decode("not,a,drive,at,all,really"))
        assertNull(DriveEndProcessor.decode("1,2,3,4,5,6,7"))
    }

    @Test fun aStartPositionIsOnlyEverALatLonPair() {
        assertEquals(39.7392 to -104.9903, DriveEndProcessor.decodePos("39.7392,-104.9903"))
        assertNull("a drive whose first fix never landed", DriveEndProcessor.decodePos(""))
        assertNull(DriveEndProcessor.decodePos("39.7392"))
    }
}
