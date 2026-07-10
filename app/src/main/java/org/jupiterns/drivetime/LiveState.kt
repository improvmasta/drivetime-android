package org.jupiterns.drivetime

/**
 * The single source of truth for the drive in progress, written by LocationService and read
 * by the Android Auto screen, the notification, and the SPA's live bar (all one process).
 * Glanceable, not persisted.
 */
object LiveState {
    @Volatile var logging = false
    @Volatile var tier: String? = null        // "DRIVING" | "LIGHT" — current sampling tier
    @Volatile var driveReason: String? = null  // why: "car BT" | "OBD" | "motion" | "speed" | "forced" | "auto"
    @Volatile var onsetState: String? = null   // motion-onset probe: "probing" | "confirmed" | "idle" | null
    @Volatile var speedMph: Int? = null
    @Volatile var obdConnected = false
    @Volatile var rpm: Int? = null
    @Volatile var throttle: Double? = null
    @Volatile var coolantC: Int? = null
    @Volatile var voltage: Double? = null
    @Volatile var updatedAt = 0L

    /** Wall-clock (ms) the current drive began; 0 when not driving. Mirrors the durable
     *  [Settings.driveStartedAt], so the SPA's live bar can anchor elapsed + distance to the
     *  real start rather than to whenever the WebView first noticed the drive. */
    @Volatile var driveStartedAt = 0L

    /** Where the phone was at the most recent recorded fix — for anything that stamps a
     *  place at a moment (the live bar's trip markers). Null until the first fix. */
    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null

    /**
     * Metres driven since [driveStartedAt] — summed by LocationService as each fix arrives,
     * reset when a new drive is stamped.
     *
     * This used to be the SPA's job: live.js re-scanned the whole fixes store every tick and
     * re-summed haversine from scratch. The service already sees every fix, so it keeps a
     * running total instead — cheaper, and *correct while the WebView is dead*, which is the
     * entire point of moving the live drive into the notification (MARKERS.md §6).
     */
    @Volatile var driveMeters = 0.0

    /** Markers stamped during the current drive, and the newest one's epoch-second ts. The
     *  notification's "since #N" line and its "Marked #3" confirmation both read these. */
    @Volatile var markerCount = 0
    @Volatile var lastMarkerTs: Long? = null

    fun clear() {
        tier = null; driveReason = null; onsetState = null
        speedMph = null; obdConnected = false; rpm = null; throttle = null; coolantC = null; voltage = null
        driveStartedAt = 0L; lat = null; lon = null
        driveMeters = 0.0; markerCount = 0; lastMarkerTs = null
    }
}
