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
}
