package org.jupiterns.drivetime

/**
 * In-process snapshot of the current drive, written by LocationService and read
 * by the Android Auto screen (same app process). Glanceable, not persisted.
 */
object LiveState {
    @Volatile var logging = false
    @Volatile var tier: String? = null        // "DRIVING" | "LIGHT" — current sampling tier
    @Volatile var driveReason: String? = null  // why: "car BT" | "OBD" | "speed" | "forced" | "auto"
    @Volatile var speedMph: Int? = null
    @Volatile var rpm: Int? = null
    @Volatile var coolantC: Int? = null
    @Volatile var voltage: Double? = null
    @Volatile var updatedAt = 0L

    fun clear() {
        tier = null; driveReason = null
        speedMph = null; rpm = null; coolantC = null; voltage = null
    }
}
