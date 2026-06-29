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
    @Volatile var pids: Map<String, Double> = emptyMap()   // every OBD PID we're reading
    @Volatile var updatedAt = 0L

    fun clear() {
        tier = null; driveReason = null; onsetState = null
        speedMph = null; obdConnected = false; rpm = null; coolantC = null; voltage = null
        pids = emptyMap()
    }
}
