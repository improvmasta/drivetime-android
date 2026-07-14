package org.jupiterns.drivetime

import android.location.Location

/**
 * What happens the moment a drive ends: is it a *real* drive, and is it actually the second half of
 * the one before it?
 *
 * Both questions are pure — two timestamps, four coordinates and a distance — and both were
 * previously answered inside [LocationService], mixed in with the Settings writes and the
 * notification posts they cause. That is why the gas-stop heuristic, which decides whether to
 * interrupt the user with "these two legs look like one drive", has never had a test despite being
 * a rule with five separate thresholds and two distance checks pointing in opposite directions.
 *
 * The numbers are the SPA's numbers (`localmerge.js` / `detail.py`), and that is the whole point of
 * running them natively: the split gets flagged at drive end even with the app closed, and it gets
 * flagged the *same way* the SPA would flag it, so the phone and the website never disagree about
 * what a gas stop is.
 */
object DriveEndProcessor {

    /**
     * A finished drive, reduced to what the pair rule needs. This is also exactly what
     * [Settings.prevDriveSummary] holds — one drive's worth of memory, so the *next* drive to end
     * can ask whether it is the other half of this one.
     */
    data class Leg(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startLat: Double,
        val startLon: Double,
        val endLat: Double,
        val endLon: Double,
    )

    /**
     * Is this worth telling the user about at all?
     *
     * A jitter loop — GPS drift in a parking lot, a phantom hundred metres while the car sits —
     * must never prompt, and must never become the left leg of a gas pair either. Everything
     * downstream of this gate is a notification, so the gate is what stands between the app and
     * nagging you about a drive you did not take.
     */
    fun isRealDrive(meters: Double, durationMs: Long): Boolean =
        meters >= REAL_DRIVE_METERS && durationMs >= REAL_DRIVE_MS

    /**
     * Do [prev] and [cur] look like one drive that was interrupted to buy fuel? Returns the gap in
     * minutes when they do, null when they don't — the caller needs the gap for the message.
     *
     * Three tests, and the last two are a matched pair that must not be confused with each other:
     *
     *  1. **The gap is fuel-stop shaped** — 4 to 15 minutes. Under four minutes you did not stop
     *     for anything; over fifteen you did an errand, and the two legs are genuinely two drives.
     *  2. **You resumed where you left off** — `prev`'s END is within 500 m of `cur`'s START. This
     *     is what makes it one *journey* rather than two: the car did not go anywhere in between.
     *  3. **You carried on** — `cur`'s END is at least 1.5 km from `prev`'s START. This is the
     *     out-and-back veto, and it is the check people leave out. Without it, driving to the
     *     station, filling up and driving straight home reads as a gas-stop split of a single drive
     *     — and merging those two legs would produce one "drive" that begins and ends in your own
     *     driveway and claims to have gone nowhere.
     *
     * Note which endpoints each distance uses: test 2 compares prev-END to cur-START (did the car
     * move while stopped?), test 3 compares prev-START to cur-END (did the journey get anywhere?).
     * They read alike and mean opposite things.
     */
    fun gasStopGapMinutes(prev: Leg, cur: Leg): Double? {
        val gapMin = (cur.startedAtMs - prev.endedAtMs) / 60_000.0
        if (gapMin < GAS_MIN_GAP_MIN || gapMin > GAS_MAX_GAP_MIN) return null
        if (metersBetween(prev.endLat, prev.endLon, cur.startLat, cur.startLon) > GAS_SAME_SPOT_M) return null
        if (metersBetween(prev.startLat, prev.startLon, cur.endLat, cur.endLon) < GAS_PROGRESS_M) return null
        return gapMin
    }

    /**
     * The on-disk form of [Settings.prevDriveSummary]: six comma-separated numbers. Not a format
     * worth defending, but it is a *persisted* one — an installed phone has one of these sitting in
     * prefs right now — so [decode] has to keep reading exactly what [encode] has always written,
     * and a garbled or half-written value must read as "no previous drive", never as a leg at
     * coordinates 0,0 (which is in the Atlantic, and 1.5 km from nothing).
     */
    fun encode(leg: Leg): String = with(leg) {
        "$startedAtMs,$endedAtMs,$startLat,$startLon,$endLat,$endLon"
    }

    fun decode(raw: String): Leg? {
        val p = raw.split(",").mapNotNull { it.toDoubleOrNull() }
        if (p.size != 6) return null
        return Leg(
            startedAtMs = p[0].toLong(),
            endedAtMs = p[1].toLong(),
            startLat = p[2], startLon = p[3],
            endLat = p[4], endLon = p[5],
        )
    }

    /** "lat,lon" as [Settings.driveStartPos] stores it, or null if it was never stamped (a drive
     *  whose first fix hadn't landed yet). */
    fun decodePos(raw: String): Pair<Double, Double>? {
        val p = raw.split(",").mapNotNull { it.toDoubleOrNull() }
        return if (p.size == 2) p[0] to p[1] else null
    }

    private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    // What counts as a REAL drive worth prompting about — mirrors the SPA min-trip gate's spirit.
    private const val REAL_DRIVE_METERS = 800.0
    private const val REAL_DRIVE_MS = 5 * 60 * 1000L

    // Native gas-stop pair rules — the same numbers as localmerge.js / detail.py.
    private const val GAS_MIN_GAP_MIN = 4.0
    private const val GAS_MAX_GAP_MIN = 15.0
    private const val GAS_SAME_SPOT_M = 500f
    private const val GAS_PROGRESS_M = 1500f
}
