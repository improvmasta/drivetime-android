package org.jupiterns.drivetime

/**
 * In-process snapshot of the current drive, written by LocationService and read
 * by the Android Auto screen (same app process). Glanceable, not persisted.
 */
object LiveState {
    @Volatile var logging = false
    @Volatile var tier: String? = null        // "DRIVING" | "LIGHT" — current sampling tier
    @Volatile var driveReason: String? = null  // why: "car BT" | "OBD" | "motion" | "speed" | "forced" | "auto"
    @Volatile var onsetState: String? = null   // motion-onset probe: "probing" | "confirmed" | "idle" | null
    @Volatile var speedMph: Int? = null
    @Volatile var obdConnected = false
    @Volatile var rpm: Int? = null
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

    fun clear() {
        tier = null; driveReason = null; onsetState = null
        speedMph = null; obdConnected = false; rpm = null; coolantC = null; voltage = null
        driveStartedAt = 0L; lat = null; lon = null
    }
}
