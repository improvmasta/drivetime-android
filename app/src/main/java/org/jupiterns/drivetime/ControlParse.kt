package org.jupiterns.drivetime

/**
 * Pure parsing helpers for [Control] — extracted into their own object so they can
 * be unit-tested without an Android Context. Every routine-driven input (Tasker,
 * MacroDroid, Home Assistant) ends up here, so any leniency change is a behaviour
 * change worth a test.
 */
object ControlParse {
    /** Map the user/routine-supplied mode string onto a [Settings] MODE_* constant,
     *  case-insensitive, with `eco` accepted as an alias for `light`. */
    fun parseMode(value: String): String? = when (value.trim().lowercase()) {
        Settings.MODE_AUTO -> Settings.MODE_AUTO
        Settings.MODE_DRIVING -> Settings.MODE_DRIVING
        Settings.MODE_LIGHT, "eco" -> Settings.MODE_LIGHT
        Settings.MODE_OFF -> Settings.MODE_OFF
        else -> null
    }

    /** Accept "1"/"0", "true"/"false", "yes"/"no", "on"/"off" — the common routine
     *  forms. Anything else is rejected (silently in [Control]; the caller decides). */
    fun parseBool(value: String): Boolean? = when (value.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    /** A positive (or zero, if [allowZero]) integer; non-numeric / negative → null. */
    fun parsePosInt(value: String, allowZero: Boolean = false): Int? {
        val n = value.trim().toIntOrNull() ?: return null
        return if (n < if (allowZero) 0 else 1) null else n
    }

    /**
     * Sane bounds for every numeric setting a routine or an imported file can write.
     *
     * A parse-level "is it a positive integer" check is not enough, because the values that
     * break tracking are absurd *and* positive. A cadence of 0 spins GPS at its maximum rate;
     * a cadence of 2000000000 asks for a fix every 63 years, which retires the LIGHT heartbeat
     * that drive detection is backstopped by — the logger goes silent while still looking
     * healthy, which is the one failure this app exists not to have.
     *
     * An hour is the ceiling for every cadence: the LIGHT pulse is what notices a drive
     * starting, so a pulse slower than that is indistinguishable from off. [Settings] applies
     * these on read and on write, so no writer — a routine SET, a settings import, or the SPA
     * bridge — can get around them.
     */
    private val BOUNDS: Map<String, IntRange> = mapOf(
        "interval_sec" to (1..3600),
        "idle_interval_sec" to (1..3600),
        "light_interval_sec" to (1..3600),
        "upload_interval_sec" to (1..3600),
        "driving_upload_interval_sec" to (1..3600),
        "onset_probe_interval_sec" to (1..3600),
        "onset_probe_window_sec" to (1..3600),
        // Minutes, and 0 legitimately means "disabled" — a day is the absurdity ceiling.
        "stationary_stop_min" to (0..1440),
        // m/s: 100 m/s is 360 km/h. That's Doppler noise, not a car.
        "onset_speed_mps" to (1..100),
        // Accelerometer RMS ×100 m/s²; 0 disables the on-foot discriminator.
        "onset_accel_rms" to (0..10_000),
    )

    /** Clamp [n] into the bounds for [key]. A key with no bounds (or a non-numeric one) passes
     *  through unchanged, so this is safe to call on any setting. */
    fun clampSetting(key: String, n: Int): Int = BOUNDS[key]?.let { n.coerceIn(it.first, it.last) } ?: n

    /** The bounded keys, for tests and the automation cheat-sheet. */
    fun boundsOf(key: String): IntRange? = BOUNDS[key]
}
